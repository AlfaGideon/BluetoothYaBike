package ru.alfagideon.bluetoothyabike

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Собирает hex-журнал в текстовый файл во внутренней папке приложения и отдаёт его
 * через FileProvider в любое приложение "Поделиться" (мессенджер, почта, облако).
 *
 * Внутренняя папка выбрана сознательно: не нужны разрешения на Android любой версии,
 * файл исчезает вместе с удалением приложения (журналы содержат MAC-адреса байков).
 */
object DebugLog {

    private const val DIR_NAME = "logs"
    private const val MIME = "text/plain"

    fun fileFor(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val name = "ble-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        return File(dir, name)
    }

    fun render(entries: List<LogEntry>, gatt: GattSnapshot, deviceLabel: String?): String = buildString {
        appendLine("BluetoothYaBike — hex-журнал BLE")
        appendLine("Устройство: ${deviceLabel ?: "не подключено"}")
        appendLine("Телефон: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        appendLine("Создан: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("=".repeat(72))
        appendLine(gatt.text())
        appendLine("=".repeat(72))
        entries.forEach { appendLine(it.format()) }
    }

    fun write(context: Context, entries: List<LogEntry>, gatt: GattSnapshot, deviceLabel: String?): File {
        val file = fileFor(context)
        file.writeText(render(entries, gatt, deviceLabel))
        return file
    }

    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
