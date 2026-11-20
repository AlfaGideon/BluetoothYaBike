package ru.alfagideon.bluetoothyabike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Эталонный btsnoop собирается кодом в точном формате Android:
 * datalink 1002 (HCI UART, H4), каждая запись начинается байтом типа
 * (0x02 ACL, 0x04 event), в flags только бит направления.
 *
 * Внутри: LE Connection Complete для AA:BB:CC:DD:EE:FF (handle 0x0041),
 * ATT Write Command (handle 0x0011, 01 02 03) и ATT Notification
 * (handle 0x0022, DE AD BE EF).
 */
class BtsnoopParserTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    private fun buildSample(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("btsnoop\u0000".toByteArray(Charsets.ISO_8859_1))
        out.write(
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putInt(1)      // version
                .putInt(1002)   // datalink: HCI UART (H4)
                .array(),
        )

        var ts = 1_000_000L

        fun record(data: ByteArray, flags: Int) {
            ts += 2_000
            out.write(
                ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
                    .putInt(data.size)  // original length
                    .putInt(data.size)  // included length
                    .putInt(flags)      // 0x01 = controller → host, 0x00 = host → controller
                    .putInt(0)          // dropped packets
                    .putLong(ts)        // timestamp, мкс
                    .array(),
            )
            out.write(data)
        }

        fun acl(handle: Int, att: ByteArray, fromDevice: Boolean) {
            val l2cap = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(att.size.toShort())
                .putShort(4) // CID: ATT
                .array() + att
            // H4: байт типа 0x02 (ACL) + handle|pb + длина + L2CAP
            val hci = byteArrayOf(0x02) +
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((handle or (0x2 shl 12)).toShort()) // PB = 2, начало L2CAP
                    .putShort(l2cap.size.toShort())
                    .array() + l2cap
            record(hci, if (fromDevice) 0x01 else 0x00)
        }

        // LE Meta Event → LE Connection Complete
        val addr = byteArrayOf(0xFF.toByte(), 0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte())
        val connect = byteArrayOf(0x04, 0x3E, 0x01, 0x00, 0x41, 0x00, 0x00, 0x00) + addr
        record(connect, 0x01)

        acl(0x0041, byteArrayOf(0x12, 0x11, 0x00, 0x01, 0x02, 0x03), fromDevice = false)
        acl(
            0x0041,
            byteArrayOf(0x1B, 0x22, 0x00, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            fromDevice = true,
        )
        return out.toByteArray()
    }

    @Test
    fun parsesConnectionAndAttFrames() {
        val report = BtsnoopParser.parse(buildSample())
        assertEquals(1, report.connections.size)
        assertEquals(mac, report.connections.first().second)
        assertEquals(3, report.totalPackets)
        assertEquals(2, report.frames.size)

        val write = report.frames[0]
        assertEquals("Write Command", write.op)
        assertEquals(0x11, write.handle)
        assertEquals("01 02 03", Hex.toHex(write.value))
        assertTrue(write.isWrite)

        val notification = report.frames[1]
        assertEquals("Notification", notification.op)
        assertEquals(0x22, notification.handle)
        assertEquals("DE AD BE EF", Hex.toHex(notification.value))
        assertTrue(notification.isIncoming)
    }

    @Test
    fun macFilterKeepsOnlyMatchingConnection() {
        assertEquals(2, BtsnoopParser.parse(buildSample(), mac).frames.size)
        assertEquals(2, BtsnoopParser.parse(buildSample(), mac.lowercase()).frames.size)
        // Фильтр по MAC не должен применяться к соединениям, которых нет в логе.
        assertEquals(2, BtsnoopParser.parse(buildSample(), null).frames.size)
        assertEquals(0, BtsnoopParser.parse(buildSample(), "11:22:33:44:55:66").frames.size)
    }

    @Test
    fun uniqueWritesDeduplicates() {
        val writes = BtsnoopParser.parse(buildSample()).uniqueWrites()
        assertEquals(1, writes.size)
        assertEquals(0x11, writes.first().handle)
        assertEquals("01 02 03", Hex.toHex(writes.first().value))
    }

    @Test
    fun extractsBtsnoopFromBugreportZip() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { zos ->
            zos.putNextEntry(ZipEntry("bugreport.txt"))
            zos.write("noise".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("FS/data/misc/bluetooth/logs/btsnoop_hci.log"))
            zos.write(buildSample())
            zos.closeEntry()
        }
        val bytes = zip.toByteArray()
        assertTrue(BtsnoopParser.looksLikeZip(bytes))
        val extracted = BtsnoopParser.extractFromZip(bytes.inputStream())
        assertNotNull(extracted)
        assertEquals(2, BtsnoopParser.parse(extracted!!).frames.size)
    }

    @Test
    fun zipWithoutSnoopLogReturnsNull() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { zos ->
            zos.putNextEntry(ZipEntry("bugreport.txt"))
            zos.write("noise".toByteArray())
            zos.closeEntry()
        }
        assertNull(BtsnoopParser.extractFromZip(zip.toByteArray().inputStream()))
    }

    @Test
    fun junkIsRejected() {
        val report = BtsnoopParser.parse("not a btsnoop".toByteArray())
        assertTrue(report.frames.isEmpty())
        assertTrue(!BtsnoopParser.looksLikeBtsnoop("not a btsnoop".toByteArray()))
    }

    @Test
    fun tableRendersFrames() {
        val text = BtsnoopParser.parse(buildSample()).table()
        assertTrue(text.contains("Write Command"))
        assertTrue(text.contains("01 02 03"))
    }

    @Test
    fun h4ServiceHandlesAreSkipped() {
        // Запись в служебный GAP-handle 0x0001 не должна попадать в список кадров.
        val out = ByteArrayOutputStream()
        out.write("btsnoop\u0000".toByteArray(Charsets.ISO_8859_1))
        out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(1).putInt(1002).array())
        val att = byteArrayOf(0x52, 0x01, 0x00, 0xAA.toByte())
        val l2cap = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(att.size.toShort()).putShort(4).array() + att
        val hci = byteArrayOf(0x02) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x2041.toShort()).putShort(l2cap.size.toShort()).array() + l2cap
        out.write(ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
            .putInt(hci.size).putInt(hci.size).putInt(0).putInt(0).putLong(1L).array())
        out.write(hci)
        assertEquals(0, BtsnoopParser.parse(out.toByteArray()).frames.size)
    }
}
