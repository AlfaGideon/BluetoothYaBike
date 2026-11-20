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

    private val pickHciLog =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                contentResolver.openInputStream(uri)?.let { viewModel.importHciLog(it) }
                    ?: toast("Не удалось открыть файл")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceAdapter = DeviceAdapter(
            onClick = { scanDevice -> connectTo(scanDevice) },
            onLongClick = { scanDevice -> copyAddress(scanDevice) },
        )
        logAdapter = LogAdapter()

        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter
        binding.rvLog.layoutManager = LinearLayoutManager(this)
        binding.rvLog.adapter = logAdapter

        binding.tvAdapterState.text = BikeBleClient.adapterState(this)

        bindButtons()
        bindCollapsibleNotes()
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

        binding.cbOnlyNamed.setOnCheckedChangeListener { _, checked ->
            viewModel.setShowAll(checked)
        }

        binding.btnImport.setOnClickListener { showHciImport() }
        binding.btnRadar.setOnClickListener { viewModel.toggleHub() }
        binding.btnAutoPick.setOnClickListener { viewModel.toggleAutoPick() }
    }

    /** Длинные пояснения свёрнуты в две строки, по тапу разворачиваются. */
    private fun bindCollapsibleNotes() {
        listOf(binding.tvHint, binding.tvSecretNote).forEach { view ->
            view.maxLines = 2
            view.setOnClickListener {
                view.maxLines = if (view.maxLines == 2) Int.MAX_VALUE else 2
            }
        }
    }

    /** Мастер захвата: что можно включить самим, что открыть одним тапом, где разобрать. */
    private fun showHciImport() {
        val view = layoutInflater.inflate(R.layout.dialog_capture, null)
        val tvStatus = view.findViewById<android.widget.TextView>(R.id.tvCaptureStatus)
        val btnEnable = view.findViewById<android.widget.Button>(R.id.btnEnableLog)

        fun renderStatus() {
            val enabled = HciLogControl.isEnabled(this)
            tvStatus.text = when {
                HciLogControl.canWrite(this) && enabled == true -> getString(R.string.capture_state_granted)
                enabled == true -> getString(R.string.capture_state_on)
                enabled == false -> getString(R.string.capture_state_off)
                else -> getString(R.string.capture_state_unknown)
            }
            btnEnable.isEnabled = !(enabled == true)
        }
        renderStatus()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.capture_title)
            .setView(view)
            .setNeutralButton(R.string.hci_import_show) { _, _ -> showHciFrames() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        btnEnable.setOnClickListener {
            if (HciLogControl.canWrite(this)) {
                HciLogControl.setEnabled(this, true)
                toast(getString(R.string.capture_grant_now))
            } else {
                toast(getString(R.string.capture_need_grant, HciLogControl.adbGrantCommand(packageName)))
            }
            renderStatus()
        }
        view.findViewById<android.widget.Button>(R.id.btnOpenDev).setOnClickListener {
            if (!HciLogControl.openDeveloperSettings(this)) toast("Нет экрана «Для разработчиков»")
        }
        view.findViewById<android.widget.Button>(R.id.btnOpenBt).setOnClickListener {
            if (!HciLogControl.openBluetoothSettings(this)) toast("Нет настроек Bluetooth")
        }
        view.findViewById<android.widget.Button>(R.id.btnOpenPro).setOnClickListener {
            if (HciLogControl.openOfficialApp(this) == null) {
                toast(getString(R.string.capture_pro_missing))
            }
        }
        view.findViewById<android.widget.Button>(R.id.btnRootRead).setOnClickListener {
            toast(if (HciLogControl.hasRoot()) "Root есть, читаю лог…" else getString(R.string.capture_no_root))
            viewModel.readHciLogAsRoot(
                onDone = { ok ->
                    toast(if (ok) getString(R.string.capture_log_read_ok) else "Лог через root не найден")
                    if (ok) showHciFrames()
                },
            )
        }
        view.findViewById<android.widget.Button>(R.id.btnPickZip).setOnClickListener {
            pickHciLog.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        dialog.show()
    }

    private fun showHciFrames() {
        val report = viewModel.hciReport.value
        if (report == null || report.frames.isEmpty()) {
            toast(getString(R.string.hci_no_frames))
            return
        }
        val writes = report.uniqueWrites()
        val timeZero = report.frames.first().timeUs
        val labels = writes.map { frame ->
            val ms = (frame.timeUs - timeZero) / 1000.0
            "%.1f мс · handle %#06x · %s".format(ms, frame.handle, Hex.pretty(frame.value))
        }.toTypedArray()
        if (labels.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.hci_import_title)
                .setMessage(report.table())
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hci_writes_title, writes.size))
            .setItems(labels) { _, which -> confirmReplay(writes[which]) }
            .setNeutralButton(R.string.hci_show_all) { d, _ ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.hci_import_title)
                    .setMessage(report.table())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmReplay(frame: AttFrame) {
        AlertDialog.Builder(this)
            .setTitle(R.string.hci_replay_title)
            .setMessage(getString(R.string.hci_replay_note, Hex.toHex(frame.value)))
            .setPositiveButton(R.string.send) { _, _ -> viewModel.replayHciFrame(frame) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Долгое нажатие на карточку — MAC в буфер обмена (нужен для разбора HCI-лога). */
    private fun copyAddress(device: ScanDevice) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("MAC", device.address))
        toast(getString(R.string.toast_copied))
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
                    viewModel.hubMode.collect { hub ->
                        binding.btnRadar.text = getString(if (hub) R.string.radar_stop else R.string.radar_start)
                    }
                }
                launch {
                    viewModel.autoPick.collect { auto ->
                        binding.btnAutoPick.text = getString(if (auto) R.string.autopick_stop else R.string.autopick_start)
                    }
                }
                launch {
                    viewModel.showAll.collect { showAll ->
                        if (binding.cbOnlyNamed.isChecked == showAll) {
                            binding.cbOnlyNamed.isChecked = !showAll
                        }
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
