package ru.alfagideon.bluetoothyabike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BtsnoopParserTest {

    /**
     * Эталонный btsnoop в точном формате Android (datalink 1002 = H4: каждая запись
     * начинается байтом типа 0x04/0x02, в flags только направление):
     *  - LE Connection Complete (event) для AA:BB:CC:DD:EE:FF, handle 0x0041;
     *  - ATT Write Command: handle 0x0011, значение 01 02 03;
     *  - ATT Notification: handle 0x0022, значение DE AD BE EF.
     * Сгенерирован отдельным скриптом и сверен с tools/parse_btsnoop.py.
     */
    private val golden = java.util.Base64.getDecoder().decode(
        "YnRzbm9vcAAAAAABAAAD6gAAAA4AAAAOAAAAAwAAAAAAAAAAAA9KEAQ+AQBBAAAA/+7dzLuqAAAADwAAAA8AAAAA" +
            "AAAAAAAAAA9R4AJBIAoABgAEABIRAAECAwAAABAAAAAQAAAAAQAAAAAAAAAAAA9ZsAJBIAsABwAEABsiAN6t" +
            "vu8=",
    )

    private val mac = "AA:BB:CC:DD:EE:FF"

    @Test
    fun parsesConnectionAndAttFrames() {
        val report = BtsnoopParser.parse(golden)
        assertEquals(1, report.connections.size)
        assertEquals(mac, report.connections.first().second)
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
        assertEquals(2, BtsnoopParser.parse(golden, mac).frames.size)
        assertEquals(2, BtsnoopParser.parse(golden, mac.lowercase()).frames.size)
        assertEquals(0, BtsnoopParser.parse(golden, "11:22:33:44:55:66").frames.size)
    }

    @Test
    fun uniqueWritesDeduplicates() {
        val writes = BtsnoopParser.parse(golden).uniqueWrites()
        assertEquals(1, writes.size)
        assertEquals(0x11, writes.first().handle)
    }

    @Test
    fun extractsBtsnoopFromBugreportZip() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { zos ->
            zos.putNextEntry(ZipEntry("bugreport.txt"))
            zos.write("noise".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("FS/data/misc/bluetooth/logs/btsnoop_hci.log"))
            zos.write(golden)
            zos.closeEntry()
        }
        val bytes = zip.toByteArray()
        assertTrue(BtsnoopParser.looksLikeZip(bytes))
        val extracted = BtsnoopParser.extractFromZip(bytes.inputStream())
        assertNotNull(extracted)
        assertEquals(2, BtsnoopParser.parse(extracted!!).frames.size)
    }

    @Test
    fun diagnosticsForFailure() {
        System.err.println("DIAG len=" + golden.size + " hex=" + Hex.toHex(golden))
        System.err.println("DIAG magic=" + String(golden, 0, 8, Charsets.ISO_8859_1))
        System.err.println("DIAG datalink=" + ((golden[12].toInt() and 0xFF) shl 24 or ((golden[13].toInt() and 0xFF) shl 16) or ((golden[14].toInt() and 0xFF) shl 8) or (golden[15].toInt() and 0xFF)))
        val report = BtsnoopParser.parse(golden)
        System.err.println("DIAG connections=" + report.connections + " frames=" + report.frames.size + " packets=" + report.totalPackets)
    }

    @Test
    fun junkIsRejected() {
        val report = BtsnoopParser.parse("not a btsnoop".toByteArray())
        assertTrue(report.frames.isEmpty())
        assertTrue(!BtsnoopParser.looksLikeBtsnoop("not a btsnoop".toByteArray()))
    }

    @Test
    fun tableRendersFrames() {
        val text = BtsnoopParser.parse(golden).table()
        assertTrue(text.contains("Write Command"))
        assertTrue(text.contains("01 02 03"))
    }
}
