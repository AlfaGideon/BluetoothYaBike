package ru.alfagideon.bluetoothyabike

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Помощники для разбора и вывода байтов. Вынесены отдельно — их же использует экспорт лога. */
object Hex {

    /**
     * Разбирает строку вида "01 02 FF", "0102ff", "0x01 0x02", "01:02:ff" в байты.
     * Возвращает null, если строка не является корректной hex-последовательностью.
     */
    fun parse(input: String): ByteArray? {
        val cleaned = input
            .replace("0x", "", ignoreCase = true)
            .replace(Regex("[\\s,;:_-]"), "")
        if (cleaned.isEmpty()) return ByteArray(0)
        if (cleaned.length % 2 != 0) return null
        if (!cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val out = ByteArrayOutputStream(cleaned.length / 2)
        var i = 0
        while (i < cleaned.length) {
            out.write(cleaned.substring(i, i + 2).toInt(16))
            i += 2
        }
        return out.toByteArray()
    }

    /** "01 02 FF" — без префиксов, удобно сравнивать и читать. */
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    fun looksLikeAscii(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val printable = bytes.count { b ->
            val v = b.toInt() and 0xFF
            v == 9 || v == 10 || v == 13 || v in 32..126
        }
        return printable * 100 / bytes.size >= 85
    }

    fun toAscii(bytes: ByteArray): String =
        bytes.toString(StandardCharsets.UTF_8).replace(Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]"), ".")

    /** Одна строка: "HEX ... | ASCII" если читаемо. */
    fun pretty(bytes: ByteArray): String {
        val hex = toHex(bytes)
        return if (looksLikeAscii(bytes)) "$hex  |  ${toAscii(bytes)}" else hex
    }
}

/** Разбор рекламных данных BLE-пакета для читаемого вывода в журнале и списке. */
object AdvertParser {

    private val SERVICE_NAMES = mapOf(
        "0000180f-0000-1000-8000-00805f9b34fb" to "Battery",
        "0000180a-0000-1000-8000-00805f9b34fb" to "DeviceInfo",
        "00001812-0000-1000-8000-00805f9b34fb" to "HID",
        "0000fe59-0000-1000-8000-00805f9b34fb" to "NordicDFU",
        "6e400001-b5a3-f393-e0a9-e50e24dcca9e" to "NordicUART",
        "0000fee0-0000-1000-8000-00805f9b34fb" to "Xiaomi",
        "0000fe95-0000-1000-8000-00805f9b34fb" to "XiaomiMiBeacon",
        "0000fd6f-0000-1000-8000-00805f9b34fb" to "ExposureNotify",
    )

    data class Part(val label: String, val value: String)

    fun shortUuid(uuid: String): String {
        val lower = uuid.lowercase()
        return SERVICE_NAMES[lower] ?: if (lower.startsWith("0000") && lower.endsWith("-0000-1000-8000-00805f9b34fb")) {
            lower.substring(4, 8).uppercase()
        } else {
            lower
        }
    }

    /** Возвращает "Флаги, Сервисы:181A,Battery, Данные:..." — кратко, для списка. */
    fun summary(parts: List<Part>): String = parts.joinToString(", ") { "${it.label}:${it.value}" }
}
