package ru.alfagideon.bluetoothyabike

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Один ATT-кадр, вытащенный из системного HCI-лога. */
data class AttFrame(
    val timeUs: Long,
    /** "Write Command", "Write Request", "Notification", "Indication", "Read Request" … */
    val op: String,
    val handle: Int,
    val value: ByteArray,
) {
    val isWrite: Boolean get() = op == "Write Command" || op == "Write Request"
    val expectsResponse: Boolean get() = op == "Write Request"
    val isIncoming: Boolean get() = op == "Notification" || op == "Indication"
}

data class ParseReport(
    val frames: List<AttFrame>,
    val connections: List<Pair<Int, String>>,
    val totalPackets: Int,
    val truncated: Boolean,
) {
    val writes: List<AttFrame> get() = frames.filter { it.isWrite && it.value.isNotEmpty() }

    /** Уникальные записи в порядке появления — то, что имеет смысл повторять. */
    fun uniqueWrites(): List<AttFrame> {
        val seen = mutableSetOf<String>()
        return writes.filter { frame ->
            val key = frame.handle.toString() + ":" + Hex.toHex(frame.value)
            seen.add(key)
        }
    }

    fun table(limit: Int = 300): String {
        val start = frames.firstOrNull()?.timeUs ?: 0
        return buildString {
            appendLine("время, мс   операция        handle   данные")
            frames.take(limit).forEach { f ->
                val ms = (f.timeUs - start) / 1000.0
                appendLine("%9.1f  %-14s  %#06x  %s".format(ms, f.op, f.handle, Hex.pretty(f.value)))
            }
            if (frames.size > limit) appendLine("… всего кадров ${frames.size}, показаны первые $limit")
        }
    }
}

/**
 * Разбор Android HCI snoop log (btsnoop) прямо на телефоне.
 *
 * Зачем это в приложении: Android запрещает одному приложению читать Bluetooth-трафик другого,
 * поэтому кадры официального приложения можно получить только из системного лога
 * (Настройки → Для разработчиков → «Журнал Bluetooth HCI»). Здесь мы этот лог разбираем:
 * достаём btsnoop из bugreport-архива, находим LE-соединение с байком, вытаскиваем ATT-записи
 * и уведомления. Дальше их можно повторить из основного экрана.
 */
object BtsnoopParser {

    private const val MAGIC = "btsnoop\u0000"
    private const val L2CAP_CID_ATT = 4
    private const val MAX_FRAMES = 20_000

    private val ATT_HAS_HANDLE = mapOf(
        0x12 to "Write Command",
        0x52 to "Write Request",
        0x1B to "Notification",
        0x1D to "Indication",
        0x0A to "Read Request",
    )
    private val ATT_NO_HANDLE = mapOf(
        0x0B to "Read Response",
        0x1E to "Confirm",
    )

    fun looksLikeBtsnoop(bytes: ByteArray): Boolean =
        bytes.size > 16 && String(bytes, 0, 8, StandardCharsets.ISO_8859_1) == MAGIC

    fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    fun readAll(input: InputStream): ByteArray = input.use { it.readBytes() }

    /**
     * Достаёт btsnoop_hci.log из zip (bugreport). Bugreport большой — читаем потоком
     * и берём только нужную запись, не выгружая весь архив в память.
     */
    fun extractFromZip(input: InputStream): ByteArray? {
        java.util.zip.ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.lowercase()
                if (!entry.isDirectory && name.endsWith("btsnoop_hci.log")) {
                    val out = ByteArrayOutputStream(8 * 1024 * 1024)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                    return out.toByteArray()
                }
                zip.closeEntry()
            }
        }
        return null
    }

    /**
     * @param macFilter если задан — оставляем только соединения с этим MAC (регистр не важен).
     */
    fun parse(bytes: ByteArray, macFilter: String? = null): ParseReport {
        if (!looksLikeBtsnoop(bytes)) {
            return ParseReport(emptyList(), emptyList(), 0, false)
        }
        val headerEnd = 16
        var offset = headerEnd
        val handles = mutableMapOf<Int, String>()
        val connections = mutableListOf<Pair<Int, String>>()
        val frames = mutableListOf<AttFrame>()
        var truncated = false
        var total = 0

        // Буферы сборки L2CAP по ACL-handle.
        val buffers = HashMap<Int, ByteArrayOutputStream>()

        while (offset + 24 <= bytes.size) {
            val inclLen = u32be(bytes, offset + 4)
            val flags = u32be(bytes, offset + 8)
            val ts = u64be(bytes, offset + 16)
            offset += 24
            if (inclLen < 0 || offset + inclLen > bytes.size) break
            total++

            val data = bytes.copyOfRange(offset, offset + inclLen)
            offset += inclLen

            val packet = splitPacket(data, flags) ?: continue
            val (pktType, payload) = packet

            if (pktType == 4) { // HCI Event
                if (payload.size >= 13 && payload[0] == 0x3E.toByte() && payload[1] == 0x01.toByte()) {
                    val handle = u16le(payload, 3) and 0x0FFF
                    val address = addressOf(payload)
                    handles[handle] = address
                    connections += handle to address
                }
                continue
            }
            if (pktType != 2) continue // только ACL

            if (payload.size < 5) continue
            val handleFlags = u16le(payload, 0)
            val handle = handleFlags and 0x0FFF
            val pb = (handleFlags shr 12) and 0x3
            val dlen = u16le(payload, 2)
            if (payload.size < 4 + dlen) continue
            val chunk = payload.copyOfRange(4, 4 + dlen)

            if (macFilter != null) {
                val address = handles[handle]
                if (address != null && !address.equals(macFilter, ignoreCase = true)) continue
            }

            val buffer = if (pb == 0x2) {
                // Новый L2CAP PDU: сбрасываем недособранный хвост.
                ByteArrayOutputStream().also { buffers[handle] = it }
            } else {
                buffers.getOrPut(handle) { ByteArrayOutputStream() }
            }
            buffer.write(chunk)

            while (true) {
                val acc = buffer.toByteArray()
                if (acc.size < 4) break
                val l2len = u16le(acc, 0)
                val cid = u16le(acc, 2)
                if (acc.size < 4 + l2len) break
                val pdu = acc.copyOfRange(4, 4 + l2len)
                val rest = acc.copyOfRange(4 + l2len, acc.size)
                buffers[handle] = ByteArrayOutputStream().also { it.write(rest) }
                if (cid == L2CAP_CID_ATT) {
                    val frame = parseAtt(ts, pdu)
                    if (frame != null) {
                        if (frame.handle in 0x0001..0x000F) continue // служебные GAP
                        frames.add(frame)
                        if (frames.size >= MAX_FRAMES) {
                            truncated = true
                            break
                        }
                    }
                }
            }
            if (truncated) break
        }
        return ParseReport(frames, connections, total, truncated)
    }

    private fun splitPacket(data: ByteArray, flags: Int): Pair<Int, ByteArray>? {
        if (data.isEmpty()) return null
        return when {
            // Android 9+: тип пакета в flags (биты 9+), направление — бит 0.
            flags and 0x100 != 0 -> ((flags shr 9) and 0x1F) to data
            // H4/UART: тип первым байтом.
            data[0].toInt() in 1..5 -> data[0].toInt() to data.copyOfRange(1, data.size)
            // Старый btsnoop_hci: тип в битах 1..3.
            else -> ((flags shr 1) and 0x0F) to data
        }
    }

    private fun parseAtt(ts: Long, pdu: ByteArray): AttFrame? {
        if (pdu.isEmpty()) return null
        val opcode = pdu[0].toInt() and 0xFF
        ATT_HAS_HANDLE[opcode]?.let { name ->
            if (pdu.size < 3) return null
            return AttFrame(ts, name, u16le(pdu, 1), pdu.copyOfRange(3, pdu.size))
        }
        ATT_NO_HANDLE[opcode]?.let { name ->
            return AttFrame(ts, name, -1, pdu.copyOfRange(1, pdu.size))
        }
        return null
    }

    /** "AA:BB:…" из рекламного адреса в LE Connection Complete (little-endian). */
    fun addressOf(payload: ByteArray): String =
        (0 until 6).joinToString(":") { i -> "%02X".format(payload[7 + (5 - i)]) }

    private fun u16le(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32be(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun u64be(b: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (b[at + i].toLong() and 0xFF)
        return value
    }
}
