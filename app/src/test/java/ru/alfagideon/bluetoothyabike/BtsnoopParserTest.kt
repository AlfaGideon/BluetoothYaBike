package ru.alfagideon.bluetoothyabike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BtsnoopParserTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    /** Собирает минимальный btsnoop с LE Connect, записью и уведомлением. */
    private fun sample(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("btsnoop\u0000".toByteArray(Charsets.ISO_8859_1))
        out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(1).putInt(1002).array())

        var ts = 1_000_000L
        fun packet(bytes: ByteArray) {
            ts += 2_000
            val header = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
                .putInt(bytes.size).putInt(bytes.size).putInt(0x100).putInt(0).putLong(ts)
            out.write(header.array())
            out.write(bytes)
        }

        fun acl(handle: Int, att: ByteArray) {
            val l2cap = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(att.size.toShort()).putShort(4).array() + att
            val hci = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((handle or (0x2 shl 12)).toShort()).putShort(l2cap.size.toShort())
                .put(2) // PB = 2 (start of L2CAP)
                .array() + l2cap
            packet(hci)
        }

        // LE Connection Complete: subevent 0x01, status 0, handle 0x0041, role, addr type, MAC
        val addr = byteArrayOf(0xFF.toByte(), 0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte())
        val connect = byteArrayOf(0x3E, 0x01, 0x00, 0x41, 0x00, 0x00, 0x00) + addr
        packet(byteArrayOf(0x04) + connect)

        acl(0x0041, byteArrayOf(0x12, 0x11, 0x00, 0x01, 0x02, 0x03))
        acl(0x0041, byteArrayOf(0x1B, 0x22, 0x00, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        return out.toByteArray()
    }

    @Test
    fun parsesConnectAndAttFrames() {
        val report = BtsnoopParser.parse(sample())
        assertEquals(1, report.connections.size)
        assertEquals(mac, report.connections.first().second)
        assertEquals(2, report.frames.size)
        assertEquals("Write Command", report.frames[0].op)
        assertEquals(0x11, report.frames[0].handle)
        assertEquals("01 02 03", Hex.toHex(report.frames[0].value))
        assertEquals("Notification", report.frames[1].op)
        assertEquals(0x22, report.frames[1].handle)
    }

    @Test
    fun macFilterKeepsOnlyMatchingConnection() {
        assertEquals(2, BtsnoopParser.parse(sample(), mac.lowercase()).frames.size)
        assertEquals(0, BtsnoopParser.parse(sample(), "11:22:33:44:55:66").frames.size)
    }

    @Test
    fun uniqueWritesDeduplicates() {
        val report = BtsnoopParser.parse(sample())
        assertEquals(1, report.uniqueWrites().size)
        assertTrue(report.uniqueWrites().first().isWrite)
    }

    @Test
    fun extractsBtsnoopFromBugreportZip() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { zos ->
            zos.putNextEntry(ZipEntry("FS/data/misc/bluetooth/logs/btsnoop_hci.log"))
            zos.write(sample())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("bugreport.txt"))
            zos.write("noise".toByteArray())
            zos.closeEntry()
        }
        val extracted = BtsnoopParser.extractFromZip(zip.toByteArray().inputStream())
        assertTrue(extracted != null && BtsnoopParser.looksLikeBtsnoop(extracted))
        assertEquals(2, BtsnoopParser.parse(extracted!!).frames.size)
    }

    @Test
    fun junkIsRejected() {
        val report = BtsnoopParser.parse("not a btsnoop".toByteArray())
        assertTrue(report.frames.isEmpty())
    }
}
