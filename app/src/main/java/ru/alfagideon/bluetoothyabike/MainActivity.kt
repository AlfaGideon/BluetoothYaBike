package ru.alfagideon.bluetoothyabike

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import ru.alfagideon.bluetoothyabike.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var logAdapter: LogAdapter

    /** Действие, которое ждало выдачи разрешений (скан, подключение, включение BT). */
    private var pendingAction: (() -> Unit)? = null

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isEmpty()) {
                val action = pendingAction
                pendingAction = null
                action?.invoke()
            } else {
                pendingAction = null
                toast("Без разрешений Bluetooth не заработает: $denied")
            }
        }

    override fun onResume() {
        super.onResume()
        if (this::binding.isInitialized) {
            binding.tvAdapterState.text = BikeBleClient.adapterState(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceAdapter = DeviceAdapter { scanDevice ->
            connectTo(scanDevice)
        }
        logAdapter = LogAdapter()

        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter
        binding.rvLog.layoutManager = LinearLayoutManager(this)
        binding.rvLog.adapter = logAdapter

        binding.tvAdapterState.text = BikeBleClient.adapterState(this)

        bindButtons()
        collectFlows()
        ensurePermissions()
    }

    private fun bindButtons() {
        binding.btnScan.setOnClickListener {
            if (viewModel.isScanning()) {
                viewModel.stopScan()
            } else {
                ensurePermissions { viewModel.startScan() }
            }
        }

        binding.btnClear.setOnClickListener { deviceAdapter.clear() }

        binding.tvAdapterState.setOnClickListener {
            if (BikeBleClient.isBluetoothReady(this)) {
                toast("Bluetooth включён")
                return@setOnClickListener
            }
            ensurePermissions {
                try {
                    startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (e: Exception) {
                    toast("Не удалось запросить включение Bluetooth")
                }
            }
        }

        binding.btnDisconnect.setOnClickListener { viewModel.disconnect() }
        binding.btnGatt.setOnClickListener { showGatt() }
        binding.btnReadAll.setOnClickListener { viewModel.readAll() }
        binding.btnBond.setOnClickListener { bondLastDevice() }

        binding.btnSend.setOnClickListener { sendFromInput() }
        binding.btnPresets.setOnClickListener { showPresets() }
        binding.btnReplay.setOnClickListener { viewModel.replayLastRx() }

        binding.btnExport.setOnClickListener {
            val uri = viewModel.exportLog()
            if (uri != null) {
                startActivity(Intent.createChooser(DebugLog.shareIntent(uri), "Отправить журнал"))
            } else {
                toast("Пока нечего сохранять")
            }
        }

        binding.cbOnlyNamed.setOnCheckedChangeListener { _, _ ->
            viewModel.setOnlyNamed(binding.cbOnlyNamed.isChecked)
        }
    }

    private fun collectFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // После переворота экрана адаптер пуст — наполняем его из буфера,
                    // дальше просто принимаем новые строки потоком.
                    if (logAdapter.itemCount == 0) {
                        logAdapter.appendAll(viewModel.lineSnapshot())
                        if (logAdapter.itemCount > 0) {
                            binding.rvLog.scrollToPosition(logAdapter.itemCount - 1)
                        }
                    }
                    viewModel.newLines.collect { entry ->
                        logAdapter.append(entry)
                        binding.rvLog.scrollToPosition(logAdapter.itemCount - 1)
                    }
                }
                launch {
                    viewModel.devices.collect { devices ->
                        deviceAdapter.clear()
                        devices.forEach { deviceAdapter.submit(it) }
                    }
                }
                launch {
                    viewModel.scanning.collect { scanning ->
                        binding.btnScan.text = getString(if (scanning) R.string.scan_stop else R.string.scan_start)
                    }
                }
                launch {
                    viewModel.state.collect { state ->
                        renderState(state)
                    }
                }
                launch {
                    viewModel.snapshot.collect { snap ->
                        binding.btnReadAll.isEnabled = snap.chars.isNotEmpty()
                    }
                }
                launch {
                    viewModel.target.collect { info ->
                        binding.tvTarget.text = if (info == null) {
                            "Цель записи: все writable-характеристики"
                        } else {
                            "Цель записи: ${info.describe()}"
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: ConnState) {
        when (state) {
            is ConnState.Idle -> {
                binding.tvConnected.text = getString(R.string.no_device)
                binding.tvStatus.text = "Выберите байк в списке — подключение выполнится автоматически."
            }
            is ConnState.Connecting -> {
                binding.tvConnected.text = "Подключение: ${state.address}"
                binding.tvStatus.text = "Если ошибка 133 — подождите пару секунд и попробуйте снова."
            }
            is ConnState.Connected -> {
                binding.tvConnected.text = "${state.name ?: "Байк"} · ${state.address}"
                binding.tvStatus.text = "Подписки включены. Кнопка «GATT» покажет дерево сервисов и откроет выбор цели записи."
            }
            is ConnState.Disconnected -> {
                binding.tvConnected.text = "Отключено: ${state.address}"
                binding.tvStatus.text = "Причина: ${state.reason}. Тап по устройству повторит подключение."
            }
        }
    }

    private fun connectTo(scanDevice: ScanDevice) {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val device: BluetoothDevice? = try {
            manager?.adapter?.getRemoteDevice(scanDevice.address)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (device == null) {
            toast("Не удалось получить устройство по адресу")
            return
        }
        ensurePermissions { viewModel.connect(device) }
    }

    @SuppressLint("MissingPermission")
    private fun bondLastDevice() {
        val device = viewModel.remoteDevice(this, viewModel.connectedAddress())
        if (device == null) {
            toast("Сначала подключитесь к байку")
            return
        }
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            toast("Уже сопряжено")
            return
        }
        try {
            @Suppress("DEPRECATION")
            device.createBond()
        } catch (e: SecurityException) {
            toast("Нет разрешения на сопряжение")
        }
    }

    private fun sendFromInput() {
        val raw = binding.etHex.text?.toString().orEmpty()
        if (raw.isBlank()) {
            toast("Введите кадр в hex")
            return
        }
        val bytes = if (binding.cbAsText.isChecked) {
            raw.toByteArray()
        } else {
            Hex.parse(raw)
        }
        if (bytes == null) {
            toast(getString(R.string.toast_hex_error))
            return
        }
        viewModel.send(bytes, binding.cbNoResponse.isChecked)
    }

    private fun showPresets() {
        val names = resources.getStringArray(R.array.preset_names)
        val values = resources.getStringArray(R.array.preset_values)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_presets_title)
            .setItems(names) { _, which ->
                val bytes = Hex.parse(values[which])
                if (bytes != null) {
                    binding.etHex.setText(values[which])
                    viewModel.send(bytes, binding.cbNoResponse.isChecked)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setMessage(R.string.dialog_presets_note)
            .show()
    }

    private fun showGatt() {
        val chars = viewModel.snapshot.value.chars
        if (chars.isEmpty()) {
            toast("GATT ещё не прочитан — подключитесь к байку")
            return
        }
        val labels = chars.map { "${it.describe()}  inst=${it.instance}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Цель записи (${chars.size})")
            .setSingleChoiceItems(labels, -1) { dialog, which ->
                viewModel.setTarget(chars[which])
                dialog.dismiss()
            }
            .setNeutralButton("Все") { d, _ ->
                viewModel.setTarget(null)
                d.dismiss()
            }
            .setPositiveButton("Отмена", null)
            .show()
    }

    // ---------- Разрешения ----------

    private fun requiredPermissions(): Array<String> = viewModel.requiredPermissions()

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissions(onGranted: (() -> Unit)? = null): Boolean {
        return if (hasPermissions()) {
            onGranted?.invoke()
            true
        } else {
            pendingAction = onGranted
            requestPermissions.launch(requiredPermissions())
            false
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
