package ru.alfagideon.bluetoothyabike

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Держит BLE-клиент, сканнер и весь UI-стейт. Переживает поворот экрана,
 * поэтому соединение не рвётся при перевороте телефона.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val logAdapterBuffer = mutableListOf<LogEntry>()
    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private val _devices = MutableStateFlow<List<ScanDevice>>(emptyList())
    val devices: StateFlow<List<ScanDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _target = MutableStateFlow<CharInfo?>(null)
    /** null = писать во все writable-характеристики. */
    val target: StateFlow<CharInfo?> = _target.asStateFlow()

    private var onlyNamed = true
    private val allDevices = LinkedHashMap<String, ScanDevice>()

    private var targetAutoChosen = false

    private val client = BikeBleClient(application) { entry ->
        if (entry.kind == LogKind.OK && entry.text.startsWith("Подключено")) {
            connectedAddress = entry.text.substringAfter("Подключено к ").substringBefore(".")
        }
        // После дампа GATT выбираем наиболее вероятный канал протокола, но только один раз
        // за подключение: ручной выбор пользователя не перетираем.
        if (entry.kind == LogKind.OK && entry.text.startsWith("GATT:") && !targetAutoChosen) {
            val suggestion = BikeBleClient.suggestTarget(snapshot.value.chars)
            if (suggestion != null) {
                targetAutoChosen = true
                _target.value = suggestion
                appendLog(
                    LogEntry(
                        System.currentTimeMillis(),
                        LogKind.OK,
                        "Предположительный канал протокола: ${suggestion.describe()} " +
                            "(сменить — кнопка «GATT»)",
                    ),
                )
            }
        }
        appendLog(entry)
    }
    val state: StateFlow<ConnState> = client.state
    val snapshot: StateFlow<GattSnapshot> = client.snapshot
    private var connectedAddress: String? = null

    init {
        scanner.onScanFailed = {
            _scanning.value = false
        }
    }

    private val scanner = BleScanner(
        context = application,
        onDevice = { device ->
            allDevices[device.address] = device
            publishDevices()
            if (device.name != null) {
                appendLog(
                    LogEntry(
                        System.currentTimeMillis(),
                        LogKind.INFO,
                        "Найдено: ${device.name} ${device.address} ${device.rssi} dBm",
                    ),
                )
            }
        },
        onError = { message -> appendLog(LogEntry(System.currentTimeMillis(), LogKind.ERROR, message)) },
    )

    fun connectedAddress(): String? = connectedAddress

    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Прочитать все читаемые характеристики заново (снимок состояния после действий). */
    fun readAll() {
        appendLog(LogEntry(System.currentTimeMillis(), LogKind.INFO, "Чтение всех характеристик…"))
        client.readAll()
    }

    fun startScan() {
        appendLog(LogEntry(System.currentTimeMillis(), LogKind.INFO, "Запуск сканирования…"))
        _scanning.value = true
        scanner.start()
    }

    fun stopScan() {
        scanner.stop()
        _scanning.value = false
        appendLog(LogEntry(System.currentTimeMillis(), LogKind.INFO, "Сканирование остановлено"))
    }

    fun isScanning(): Boolean = _scanning.value

    fun setOnlyNamed(value: Boolean) {
        onlyNamed = value
        publishDevices()
    }

    private fun publishDevices() {
        _devices.value = allDevices.values
            .filter { !onlyNamed || it.name != null }
            .sortedByDescending { it.rssi }
    }

    fun connect(device: BluetoothDevice) {
        if (_scanning.value) stopScan()
        connectedAddress = device.address
        targetAutoChosen = false
        _target.value = null
        client.connect(device)
    }

    /** Ищет устройство по адресу, чтобы UI мог запустить сопряжение. */
    fun remoteDevice(context: android.content.Context, address: String?): BluetoothDevice? {
        if (address == null) return null
        return try {
            val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
            manager?.adapter?.getRemoteDevice(address)
        } catch (e: Exception) {
            null
        }
    }

    fun disconnect() {
        connectedAddress = null
        client.disconnect()
    }

    fun setTarget(info: CharInfo?) {
        targetAutoChosen = true
        _target.value = info
        appendLog(
            LogEntry(
                System.currentTimeMillis(),
                LogKind.INFO,
                "Цель записи: ${info?.describe() ?: "все writable-характеристики"}",
            ),
        )
    }

    fun send(bytes: ByteArray, withResponse: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            client.send(bytes, _target.value, withResponse)
        }
    }

    fun replayLastRx() {
        val (info, bytes) = client.lastRx.value ?: run {
            appendLog(LogEntry(System.currentTimeMillis(), LogKind.WARN, "Пока не было входящих кадров"))
            return
        }
        appendLog(
            LogEntry(
                System.currentTimeMillis(),
                LogKind.INFO,
                "Повтор последнего RX (${AdvertParser.shortUuid(info.uuid.toString())}): ${Hex.toHex(bytes)}",
            ),
        )
        viewModelScope.launch(Dispatchers.IO) {
            client.send(bytes, info, withResponse = true)
        }
    }

    fun exportLog(): Uri? {
        val context = getApplication<Application>()
        return try {
            val file = DebugLog.write(context, logAdapterBuffer, snapshot.value, connectedAddress)
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch (e: Exception) {
            appendLog(LogEntry(System.currentTimeMillis(), LogKind.ERROR, "Экспорт: ${e.message}"))
            null
        }
    }

    private fun appendLog(entry: LogEntry) {
        synchronized(logAdapterBuffer) {
            logAdapterBuffer.add(entry)
            if (logAdapterBuffer.size > MAX_LOG) {
                logAdapterBuffer.subList(0, logAdapterBuffer.size - MAX_LOG).clear()
            }
        }
        _log.value = synchronized(logAdapterBuffer) { logAdapterBuffer.toList() }
    }


    override fun onCleared() {
        scanner.stop()
        scanner.onScanFailed = {}
        client.disconnect(silent = true)
        client.shutdown()
        super.onCleared()
    }

    companion object {
        private const val MAX_LOG = 4000
    }
}
