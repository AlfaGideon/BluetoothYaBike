package ru.alfagideon.bluetoothyabike

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Всё, что приложение МОЖЕТ сделать само с системным HCI-логом, и честная проверка того,
 * чего оно сделать не может.
 *
 * Что невозможно без прав системы (Android запрещает это любым обычным приложениям):
 *  - включить/выключить Bluetooth «молча» (с targetSdk 33+ enable()/disable() — no-op);
 *  - запустить «Взять отчёт об ошибке» (это делает только adb/system UI);
 *  - прочитать /data/misc/bluetooth/logs/btsnoop_hci.log из песочницы приложения.
 *
 * Что возможно и реализовано:
 *  - прочитать текущее состояние настройки bluetooth_hci_log (без спец-прав);
 *  - включить её, если выдано WRITE_SECURE_SETTINGS (одна adb-команда на всё про всё);
 *  - открыть нужный экран настроек ровно туда, где нужный переключатель;
 *  - если на телефоне есть root — прочитать лог напрямую, вообще без действий пользователя.
 */
object HciLogControl {

    private const val SETTING_KEY = "bluetooth_hci_log"
    private val LOG_PATHS = listOf(
        "/data/misc/bluetooth/logs/btsnoop_hci.log",
        "/data/misc/bluedroid/btsnoop_hci.log",
        "/data/local/tmp/btsnoop_hci.log",
    )

    /** null — узнать нельзя (нет такой настройки на прошивке). */
    fun isEnabled(context: Context): Boolean? = try {
        Settings.Secure.getInt(context.contentResolver, SETTING_KEY, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?.let { it != 0 }
    } catch (e: Exception) {
        null
    }

    fun canWrite(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Включает лог сам, если выдано право (adb-команда ниже). Иначе возвращает false. */
    fun setEnabled(context: Context, enabled: Boolean): Boolean = try {
        Settings.Secure.putInt(context.contentResolver, SETTING_KEY, if (enabled) 1 else 0)
        true
    } catch (e: SecurityException) {
        false
    }

    /** Команда, которую достаточно выполнить один раз через USB (разрешение не сбрасывается). */
    fun adbGrantCommand(packageName: String): String =
        "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

    fun openDeveloperSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        false
    }

    fun openBluetoothSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        false
    }

    private val YA_PRO_PACKAGES = listOf(
        "ru.yandex.pro",
        "ru.yandex.go.pro",
        "ru.yandex.go",
        "ru.yandex.maps.pro",
    )

    /** Открывает Яндекс Про, если он установлен. Возвращает имя пакета или null. */
    fun openOfficialApp(context: Context): String? {
        val pm = context.packageManager
        val found = YA_PRO_PACKAGES.firstOrNull { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        } ?: return null
        val intent = pm.getLaunchIntentForPackage(found) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return found
    }

    /** Есть ли root (su) — тогда лог можно взять вообще без отчёта об ошибке. */
    fun hasRoot(): Boolean = try {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val finished = process.waitFor(4, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            val out = process.inputStream.bufferedReader().readText()
            out.contains("uid=0")
        }
    } catch (e: Exception) {
        false
    }

    /** Читает btsnoop напрямую через su. null — нет root/файла. */
    fun readLogAsRoot(): ByteArray? {
        for (path in LOG_PATHS) {
            try {
                val process = ProcessBuilder("su", "-c", "cat $path")
                    .redirectErrorStream(false)
                    .start()
                val finished = process.waitFor(20, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    continue
                }
                val out = ByteArrayOutputStream()
                process.inputStream.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
                val bytes = out.toByteArray()
                if (bytes.size > 16 && BtsnoopParser.looksLikeBtsnoop(bytes)) return bytes
            } catch (e: Exception) {
                // пробуем следующий путь
            }
        }
        return null
    }
}
