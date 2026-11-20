package ru.alfagideon.bluetoothyabike

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid

data class ScanDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val connectable: Boolean,
    val firstSeen: Long,
    val lastSeen: Long,
    val manufacturer: List<Pair<Int, ByteArray>>,
    val services: List<String>,
    val serviceData: List<Pair<String, ByteArray>>,
    val txPower: Int?,
)

/**
 * Тонкая обёртка над BluetoothLeScanner: копит результаты и отдаёт их через колбэк.
 * Не хранит состояние Android BluetoothAdapter, чтобы не мешать жизненному циклу.
 */
class BleScanner(
    private val context: Context,
    private val onDevice: (ScanDevice) -> Unit,
    private val onError: (String) -> Unit,
) {

    private val manager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = manager?.adapter
    private val scanner: BluetoothLeScanner?
        get() = try {
            adapter?.bluetoothLeScanner
        } catch (e: SecurityException) {
            null
        }

    var isScanning: Boolean = false
        private set

    private val found = LinkedHashMap<String, ScanDevice>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handle(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            onError("Ошибка сканирования: код $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isScanning) return
        val s = scanner
        if (s == null) {
            onError("Bluetooth выключен или недоступен")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                }
            }
            .build()
        try {
            found.clear()
            s.startScan(null, settings, callback)
            isScanning = true
        } catch (e: SecurityException) {
            onError("Нет разрешения на сканирование Bluetooth")
        } catch (e: Exception) {
            onError("Не удалось запустить скан: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!isScanning) return
        try {
            scanner?.stopScan(callback)
        } catch (_: Exception) {
        }
        isScanning = false
    }

    fun snapshot(): List<ScanDevice> = found.values.toList()

    @SuppressLint("MissingPermission")
    private fun handle(result: ScanResult) {
        val record = result.scanRecord
        val now = System.currentTimeMillis()
        val address = result.device?.address ?: return
        val previous = found[address]
        val device = ScanDevice(
            address = address,
            name = record?.deviceName ?: runCatching { result.device?.name }.getOrNull() ?: previous?.name,
            rssi = result.rssi,
            connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                result.isConnectable
            } else {
                true
            },
            firstSeen = previous?.firstSeen ?: now,
            lastSeen = now,
            manufacturer = record?.manufacturerSpecificData?.let { data ->
                (0 until data.size()).map { data.keyAt(it) to data.valueAt(it) }
            } ?: emptyList(),
            services = record?.serviceUuids?.mapNotNull { (it as ParcelUuid).uuid.toString() } ?: emptyList(),
            serviceData = record?.serviceData?.map { (uuid, bytes) -> uuid.uuid.toString() to bytes } ?: emptyList(),
            txPower = record?.txPowerLevel?.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT },
        )
        found[address] = device
        onDevice(device)
    }

    /** Рекламные данные строкой для журнала. */
    fun describe(device: ScanDevice): String {
        val parts = mutableListOf<String>()
        if (device.manufacturer.isNotEmpty()) {
            parts += device.manufacturer.joinToString("; ") { (id, bytes) ->
                "MFR 0x%04X: %s".format(id, Hex.toHex(bytes))
            }
        }
        if (device.services.isNotEmpty()) {
            parts += "Services: " + device.services.joinToString(",") { AdvertParser.shortUuid(it) }
        }
        if (device.serviceData.isNotEmpty()) {
            parts += device.serviceData.joinToString("; ") { (uuid, bytes) ->
                "SvcData ${AdvertParser.shortUuid(uuid)}: ${Hex.toHex(bytes)}"
            }
        }
        device.txPower?.let { parts += "TX $it dBm" }
        return if (parts.isEmpty()) "без рекламных данных" else parts.joinToString(", ")
    }
}
