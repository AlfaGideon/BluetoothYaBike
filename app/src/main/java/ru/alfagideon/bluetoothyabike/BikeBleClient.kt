package ru.alfagideon.bluetoothyabike

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Одна характеристика GATT с удобными свойствами. */
data class CharInfo(
    val serviceUuid: UUID,
    val uuid: UUID,
    val properties: Int,
    val instance: Int,
) {
    val key: String get() = "$uuid#$instance"

    val canRead: Boolean get() = properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
    val canWriteNoResp: Boolean
        get() = properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    val canWrite: Boolean
        get() = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
    val canNotify: Boolean
        get() = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
    val canIndicate: Boolean
        get() = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
    val hasCccd: Boolean get() = canNotify || canIndicate

    val flags: String
        get() = buildList {
            if (canRead) add("R")
            if (canWriteNoResp) add("Wnr")
            if (canWrite) add("W")
            if (canNotify) add("N")
            if (canIndicate) add("I")
        }.joinToString("|").ifEmpty { "-" }

    fun describe(): String =
        "${AdvertParser.shortUuid(uuid.toString())} [${flags}] svc ${AdvertParser.shortUuid(serviceUuid.toString())}"
}

/** Полный слепок GATT-дерева, пригодный для журнала и экспорта. */
data class GattSnapshot(val services: List<BluetoothGattService>, val chars: List<CharInfo>) {

    fun text(): String {
        if (services.isEmpty()) return "GATT ещё не прочитан"
        val sb = StringBuilder()
        services.forEach { s ->
            sb.appendLine("SERVICE ${s.uuid}")
            s.characteristics.forEachIndexed { index, c ->
                val info = CharInfo(s.uuid, c.uuid, c.properties, index)
                sb.appendLine("  CHAR ${c.uuid} [${info.flags}] inst=$index")
                c.descriptors.forEach { d ->
                    sb.appendLine("    DESC ${d.uuid}")
                }
            }
        }
        return sb.toString()
    }
}

/** Состояние подключения, которое видит UI. */
sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val address: String) : ConnState
    data class Connected(val address: String, val name: String?) : ConnState
    data class Disconnected(val address: String, val reason: String) : ConnState
}

/**
 * Центральный BLE-клиент приложения.
 *
 * Задачи:
 *  - подключение к байку и восстановление GATT;
 *  - включение notifications на всех характеристиках, где это возможно
 *    (важно: обмен официального приложения идёт и на служебных каналах);
 *  - запись произвольных кадров во все writable-характеристики с журналированием;
 *  - единый hex-журнал, который можно выгрузить.
 */
class BikeBleClient(
    context: Context,
    private val onLog: (LogEntry) -> Unit,
) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _snapshot = MutableStateFlow(GattSnapshot(emptyList(), emptyList()))
    val snapshot: StateFlow<GattSnapshot> = _snapshot.asStateFlow()

    private val _lastRx = MutableStateFlow<Pair<CharInfo, ByteArray>?>(null)
    /** Последний полученный от устройства кадр — для кнопки "Повторить RX". */
    val lastRx: StateFlow<Pair<CharInfo, ByteArray>?> = _lastRx.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private val charsByKey = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private var subscribed = false

    fun charForKey(key: String): CharInfo? = _snapshot.value.chars.firstOrNull { it.key == key }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect(silent = true)
        _state.value = ConnState.Connecting(device.address)
        log(LogKind.INFO, "Подключение к ${device.address} …")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, callback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect(silent: Boolean = false) {
        val current = gatt
        if (current != null) {
            try {
                current.disconnect()
            } catch (_: Exception) {
            }
            try {
                current.close()
            } catch (_: Exception) {
            }
        }
        gatt = null
        charsByKey.clear()
        subscribed = false
        _snapshot.value = GattSnapshot(emptyList(), emptyList())
        if (!silent) {
            _state.value = ConnState.Idle
            log(LogKind.INFO, "Соединение закрыто")
        }
    }

    /** Включить/повторить подписку на все характеристики с notify/indicate. */
    @SuppressLint("MissingPermission")
    fun subscribeAll() {
        val g = gatt ?: return
        val snapshot = charsByKey.values.toList()
        snapshot.filter { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
            .forEach { c ->
                try {
                    g.setCharacteristicNotification(c, true)
                    c.getDescriptor(CCCD)?.let { d ->
                        writeDescriptor(d, if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        } else {
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        })
                    }
                } catch (e: Exception) {
                    log(LogKind.WARN, "Подписка ${c.uuid}: ${e.message}")
                }
            }
        subscribed = true
    }

    /** Читает все читаемые характеристики — полезно как снимок состояния после коннекта. */
    @SuppressLint("MissingPermission")
    fun readAll() {
        val g = gatt ?: return
        charsByKey.values.toList()
            .filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
            .forEach { c ->
            try {
                g.readCharacteristic(c)
            } catch (e: Exception) {
                log(LogKind.WARN, "Чтение ${c.uuid}: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun read(info: CharInfo) {
        val g = gatt ?: return
        val c = charsByKey[info.key] ?: return
        try {
            g.readCharacteristic(c)
        } catch (e: Exception) {
            log(LogKind.WARN, "Чтение ${info.uuid}: ${e.message}")
        }
    }

    /**
     * Отправляет кадр. Если передан конкретный получатель — только в него,
     * иначе во все writable-характеристики (с паузой), чтобы не зависеть от догадок.
     */
    @SuppressLint("MissingPermission")
    fun send(payload: ByteArray, target: CharInfo?, withResponse: Boolean, delayMs: Long = 250L) {
        val g = gatt ?: run {
            log(LogKind.ERROR, "Нет подключения — кадр не отправлен")
            return
        }
        val targets = if (target != null) {
            listOfNotNull(charsByKey[target.key]).map { CharInfo(target.serviceUuid, target.uuid, target.properties, target.instance) to it }
        } else {
            val writable = charsByKey.values.toList().filter {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            val safe = writable.filterNot { isDangerous(it.service?.uuid ?: UUID(0, 0)) }
            val skipped = writable.size - safe.size
            if (skipped > 0) {
                log(LogKind.WARN, "Пропущено $skipped характеристик сервисов прошивки/служебных (DFU/OTA) — их можно выбрать вручную в «GATT»")
            }
            safe.mapIndexed { index, c -> CharInfo(c.service?.uuid ?: UUID(0, 0), c.uuid, c.properties, index) to c }
        }
        if (targets.isEmpty()) {
            log(LogKind.ERROR, "На устройстве нет writable-характеристик")
            return
        }
        if (target == null) {
            log(LogKind.INFO, "Широковещательная запись ${Hex.toHex(payload)} во все безопасные writable-характеристики (${targets.size})")
        }
        targets.forEachIndexed { index, (info, characteristic) ->
            try {
                val ok = writeCharacteristic(g, characteristic, payload, withResponse)
                log(
                    if (ok) LogKind.TX else LogKind.ERROR,
                    "TX → ${AdvertParser.shortUuid(info.uuid.toString())} ${Hex.pretty(payload)}${if (ok) "" else " (не принято)"}",
                    payload,
                )
            } catch (e: Exception) {
                log(LogKind.ERROR, "TX → ${info.uuid}: ${e.message}", payload)
            }
            if (index < targets.lastIndex) Thread.sleep(delayMs)
        }
    }

    private fun writeCharacteristic(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        payload: ByteArray,
        withResponse: Boolean,
    ): Boolean {
        val supportsResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val supportsNoResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val useResponse = when {
            withResponse && supportsResponse -> true
            supportsNoResponse -> false
            else -> supportsResponse
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = g.writeCharacteristic(
                c,
                payload,
                if (useResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            )
            result == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = if (useResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            @Suppress("DEPRECATION")
            c.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(d: BluetoothGattDescriptor, value: ByteArray) {
        val g = gatt ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value)
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private fun log(kind: LogKind, text: String, bytes: ByteArray? = null) {
        onLog(LogEntry(System.currentTimeMillis(), kind, text, bytes))
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        log(LogKind.ERROR, "GATT статус подключения: $status")
                        return
                    }
                    _state.value = ConnState.Connected(g.device.address, g.device.name)
                    log(LogKind.OK, "Подключено к ${g.device.address}. Запрашиваю MTU и GATT…")
                    runCatching { g.requestMtu(247) }
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    charsByKey.clear()
                    subscribed = false
                    _snapshot.value = GattSnapshot(emptyList(), emptyList())
                    val reason = when (status) {
                        BluetoothGatt.GATT_SUCCESS -> "устройство закрыло соединение"
                        133 -> "ошибка 133 (занято другим приложением или байк спит)"
                        8 -> "устройство не в зоне / соединение потеряно"
                        else -> "код $status"
                    }
                    _state.value = ConnState.Disconnected(g.device.address, reason)
                    log(LogKind.WARN, "Отключено: $reason")
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log(LogKind.INFO, "MTU = $mtu (status $status)")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log(LogKind.ERROR, "Обнаружение сервисов: статус $status")
                return
            }
            val services = g.services.orEmpty()
            val chars = mutableListOf<CharInfo>()
            charsByKey.clear()
            services.forEach { s ->
                s.characteristics.forEachIndexed { index, c ->
                    val info = CharInfo(s.uuid, c.uuid, c.properties, index)
                    chars += info
                    charsByKey[info.key] = c
                }
            }
            _snapshot.value = GattSnapshot(services, chars)
            log(LogKind.OK, "GATT: сервисов ${services.size}, характеристик ${chars.size}")
            services.forEach { s ->
                log(
                    LogKind.SERVICE,
                    "SERVICE ${s.uuid} — " + s.characteristics.joinToString(", ") { c ->
                        val info = CharInfo(s.uuid, c.uuid, c.properties, 0)
                        "${AdvertParser.shortUuid(c.uuid.toString())}[${info.flags}]"
                    },
                )
            }
            subscribeAll()
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            reportRead(characteristic, value, status)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                reportRead(characteristic, characteristic.value ?: ByteArray(0), status)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            log(
                if (status == BluetoothGatt.GATT_SUCCESS) LogKind.INFO else LogKind.ERROR,
                "Запись ${AdvertParser.shortUuid(characteristic.uuid.toString())}: статус $status",
            )
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            reportNotification(characteristic, value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                reportNotification(characteristic, characteristic.value ?: ByteArray(0))
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log(LogKind.WARN, "CCCD ${descriptor.characteristic.uuid}: статус $status")
            }
        }
    }

    private fun infoFor(c: BluetoothGattCharacteristic): CharInfo {
        val service = c.service?.uuid ?: UUID(0, 0)
        val index = c.service?.characteristics?.indexOf(c) ?: 0
        return CharInfo(service, c.uuid, c.properties, maxOf(index, 0))
    }

    private fun reportRead(c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        val info = infoFor(c)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            log(LogKind.WARN, "RX ← ${AdvertParser.shortUuid(c.uuid.toString())} ошибка чтения, статус $status")
            return
        }
        log(LogKind.RX, "RX ← ${AdvertParser.shortUuid(c.uuid.toString())} ${Hex.pretty(value)}", value)
        _lastRx.value = info to value
    }

    private fun reportNotification(c: BluetoothGattCharacteristic, value: ByteArray) {
        val info = infoFor(c)
        log(LogKind.RX, "RX ← ${AdvertParser.shortUuid(c.uuid.toString())} ${Hex.pretty(value)}", value)
        _lastRx.value = info to value
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Стандартные и сервисные UUID, куда не стоит писать вслепую. */
        private val PROTECTED_SERVICES: Set<UUID> = setOf(
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"), // Generic Access
            UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"), // Generic Attribute
            UUID.fromString("00001804-0000-1000-8000-00805f9b34fb"), // TX Power
            UUID.fromString("00001805-0000-1000-8000-00805f9b34fb"), // Current Time
            UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"), // Device Information
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"), // Battery
            UUID.fromString("00001816-0000-1000-8000-00805f9b34fb"), // Cycling Speed and Cadence
            UUID.fromString("00001818-0000-1000-8000-00805f9b34fb"), // Cycling Power
            UUID.fromString("00001826-0000-1000-8000-00805f9b34fb"), // Fitness Machine
            UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb"), // Nordic Secure DFU
            UUID.fromString("00001530-1212-efde-1523-785feabcd123"), // Nordic legacy DFU
            UUID.fromString("8ec90001-f315-4f60-9fb8-838830daea50"), // Nordic buttonless DFU
            UUID.fromString("8ec90002-f315-4f60-9fb8-838830daea50"),
            UUID.fromString("8ec90003-f315-4f60-9fb8-838830daea50"),
            UUID.fromString("0000fef5-0000-1000-8000-00805f9b34fb"), // Dialog OTA
            UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb"),
        )

        /**
         * true, если это сервис прошивки (DFU/OTA) или служебный GAP/GATT:
         * запись в них «наугад» может перевести модуль в режим обновления.
         */
        fun isDangerous(serviceUuid: UUID): Boolean = serviceUuid in PROTECTED_SERVICES

        /**
         * Гипотеза о канале протокола: характеристика, в которую можно писать и из которой
         * приходят уведомления, в пользовательском (128-битном) сервисе.
         * Это лишь подсказка для UI, а не утверждение о протоколе байка.
         */
        fun suggestTarget(chars: List<CharInfo>): CharInfo? {
            fun score(c: CharInfo): Int {
                var s = 0
                if (c.canWriteNoResp || c.canWrite) s += 2
                if (c.canNotify || c.canIndicate) s += 3
                if (c.serviceUuid.toString().startsWith("0000")) s -= 2
                if (c.serviceUuid == TEXT_SERVICE) s -= 1
                if (isDangerous(c.serviceUuid)) s -= 10
                return s
            }
            return chars.maxByOrNull { score(it) }?.takeIf { score(it) > 0 }
        }

        val TEXT_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val TEXT_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val TEXT_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        /** Включён ли Bluetooth и есть ли вообще BLE-адаптер. */
        fun isBluetoothReady(context: Context): Boolean {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            return manager?.adapter?.isEnabled == true
        }

        /** Есть ли модуль BLE и включён ли Bluetooth. */
        fun adapterState(context: Context): String {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter = manager?.adapter
            return when {
                adapter == null && !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> "Нет BLE"
                adapter == null -> "Нет адаптера"
                !adapter.isEnabled -> "BT выключен"
                else -> "BT готов"
            }
        }

        fun utf8(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)
    }
}
