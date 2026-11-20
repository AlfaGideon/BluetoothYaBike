package ru.alfagideon.bluetoothyabike

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Держит BLE-клиент, сканнер и весь UI-стейт. Переживает поворот экрана,
 * поэтому соединение не рвётся при перевороте телефона.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val logBuffer = mutableListOf<LogEntry>()

    /**
     * Журнал отдаётся потоком новых строк, а не копией всего списка:
     *  - нет O(n²) копирований на каждой строке;
     *  - UI не «зависает», когда буфер достигает лимита и старые строки вытесняются.
     * При повороте экрана новый адаптер заполняется из [lineSnapshot].
     */
    private val _newLines = MutableSharedFlow<LogEntry>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val newLines: SharedFlow<LogEntry> = _newLines.asSharedFlow()

    private val _devices = MutableStateFlow<List<ScanDevice>>(emptyList())
    val devices: StateFlow<List<ScanDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Радар: true — карточки всех устройств, независимо от имени. */
    private val _hubMode = MutableStateFlow(false)
    val hubMode: StateFlow<Boolean> = _hubMode.asStateFlow()

    /** Автоподключение к самому сильному сигналу («поднести телефон к байку»). */
    private val _autoPick = MutableStateFlow(false)
    val autoPick: StateFlow<Boolean> = _autoPick.asStateFlow()

    private val _showAll = MutableStateFlow(false)
    /** Показывать все устройства в эфире, не только «с именем». */
    val showAll: StateFlow<Boolean> = _showAll.asStateFlow()

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

    /** Устройство, к которому уже пытались подключиться в режиме автоподключения. */
    private var autoAttempted: String? = null

    init {
        // Скан мог остановиться сам (ошибка адаптера) — сбрасываем кнопку в UI.
        scanner.onScanFailed = { _scanning.value = false }
        scanner.onStopped = {
            _scanning.value = false
            if (_hubMode.value) maybeAutoConnect()
        }
    }

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
        scanner.autoStopMs = if (_hubMode.value) 0 else 8_000
        scanner.start()
    }

    fun stopScan() {
        scanner.stop()
        _scanning.value = false
        appendLog(LogEntry(System.currentTimeMillis(), LogKind.INFO, "Сканирование остановлено"))
    }

    fun isScanning(): Boolean = _scanning.value

    fun setShowAll(value: Boolean) {
        _showAll.value = value
        publishDevices()
    }

    /** Радар: держим скан включённым и показываем всё, что слышно. */
    fun toggleHub() {
        val next = !_hubMode.value
        _hubMode.value = next
        if (next) {
            _showAll.value = true
            appendLog(LogEntry(System.currentTimeMillis(), LogKind.INFO, "Радар включён: показываю все устройства в эфире"))
            startScan()
        } else {
            _autoPick.value = false
            stopScan()
        }
    }

    fun toggleAutoPick() {
        val next = !_autoPick.value
        _autoPick.value = next
        if (next) {
            _hubMode.value = true
            _showAll.value = true
            autoAttempted = null
            appendLog(
                LogEntry(
                    System.currentTimeMillis(),
                    LogKind.INFO,
                    "Автоподключение: поднесите телефон к байку (30–50 см), подключусь к самому сильному сигналу",
                ),
            )
            startScan()
        } else {
            appendLog(LogEntry(System.currentTimeMillis(), LogKind.INFO, "Автоподключение выключено"))
        }
    }

    /** Если подключение уже есть — не трогаем; иначе пробуем самый сильный сигнал. */
    private fun maybeAutoConnect() {
        if (!_autoPick.value) return
        val current = state.value
        if (current is ConnState.Connected || current is ConnState.Connecting) {
            _autoPick.value = false
            return
        }
        val candidate = _devices.value
            .filter { it.connectable && it.address != autoAttempted }
            .maxByOrNull { it.rssi } ?: return
        if (candidate.rssi < -80) {
            appendLog(
                LogEntry(
                    System.currentTimeMillis(),
                    LogKind.WARN,
                    "Самый сильный сигнал ${candidate.rssi} dBm — слишком далеко, поднесите телефон ближе",
                ),
            )
            return
        }
        autoAttempted = candidate.address
        appendLog(
            LogEntry(
                System.currentTimeMillis(),
                LogKind.INFO,
                "Автоподключение к ${candidate.name ?: candidate.address} (${candidate.rssi} dBm)",
            ),
        )
        val context = getApplication<Application>()
        remoteDevice(context, candidate.address)?.let { connect(it) }
    }

    private fun publishDevices() {
        _devices.value = allDevices.values
            .filter { _showAll.value || it.name != null }
            .sortedByDescending { it.rssi }
    }

    fun connect(device: BluetoothDevice) {
        // В режиме радара скан можно не рвать, пока идёт подключение — так видны
        // другие устройства рядом. Но автоподключение к следующему отключаем.
        if (!_hubMode.value && _scanning.value) stopScan()
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
            val lines = synchronized(logBuffer) { logBuffer.toList() }
            val file = DebugLog.write(context, lines, snapshot.value, connectedAddress)
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch (e: Exception) {
            appendLog(LogEntry(System.currentTimeMillis(), LogKind.ERROR, "Экспорт: ${e.message}"))
            null
        }
    }

    /** Последние строки журнала — для восстановления списка после переворота экрана. */
    fun lineSnapshot(): List<LogEntry> = synchronized(logBuffer) { logBuffer.takeLast(SNAPSHOT_LINES) }

    private fun appendLog(entry: LogEntry) {
        synchronized(logBuffer) {
            logBuffer.add(entry)
            if (logBuffer.size > MAX_LOG) {
                logBuffer.subList(0, logBuffer.size - MAX_LOG).clear()
            }
        }
        _newLines.tryEmit(entry)
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
        private const val SNAPSHOT_LINES = 1500
    }
}
