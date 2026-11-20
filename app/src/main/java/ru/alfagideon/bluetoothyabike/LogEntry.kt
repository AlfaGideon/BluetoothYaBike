package ru.alfagideon.bluetoothyabike

data class LogEntry(
    val timeMs: Long,
    val kind: LogKind,
    val text: String,
    val bytes: ByteArray? = null,
) {
    fun format(): String = "${TIME_FORMAT.format(java.util.Date(timeMs))} [${kind.tag}] $text"

    companion object {
        private val TIME_FORMAT =
            java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
    }
}

enum class LogKind(val tag: String, val colorRes: Int) {
    INFO("INFO", R.color.text_secondary),
    OK(" OK ", R.color.ok),
    WARN("WARN", R.color.warn),
    ERROR(" ERR", R.color.error),
    TX(" TX ", R.color.accent),
    RX(" RX ", R.color.ok),
    SERVICE("GATT", R.color.text_secondary),
    SYSTEM("SYS ", R.color.warn),
}
