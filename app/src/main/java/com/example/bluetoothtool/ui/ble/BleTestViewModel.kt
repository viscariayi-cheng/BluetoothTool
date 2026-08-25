package com.example.bluetoothtool.ui.ble

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetoothtool.data.bluetooth.BleScannerDataSource
import com.example.bluetoothtool.data.settings.AppSettingsRepository
import com.example.bluetoothtool.domain.RefreshBleEnvironmentUseCase
import com.example.bluetoothtool.domain.RunBleTestUseCase
import com.example.bluetoothtool.domain.ScanBleDevicesUseCase
import com.example.bluetoothtool.domain.StopBleTestUseCase
import com.example.bluetoothtool.model.BleBidirectionalThroughputSample
import com.example.bluetoothtool.model.BleBidirectionalThroughputStats
import com.example.bluetoothtool.model.BleThroughputSample
import com.example.bluetoothtool.model.BleThroughputStats
import com.example.bluetoothtool.model.TestRole
import com.example.bluetoothtool.model.TrafficDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class BleTestViewModel(
    private val appContext: Context,
    private val refreshBleEnvironment: RefreshBleEnvironmentUseCase,
    private val scanBleDevices: ScanBleDevicesUseCase,
    private val runBleTest: RunBleTestUseCase,
    private val stopBleTest: StopBleTestUseCase,
    private val settingsRepository: AppSettingsRepository,
) : ViewModel() {
    private var testJob: Job? = null
    private var scanJob: Job? = null
    private val testSessionId = AtomicLong(0L)

    private val _state = MutableStateFlow(BleUiState())
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    init {
        refreshPermissionsAndDevices()
    }

    fun refreshPermissionsAndDevices() {
        val environment = refreshBleEnvironment()
        _state.update {
            it.copy(
                bluetoothAvailable = environment.bluetoothAvailable,
                bluetoothEnabled = environment.bluetoothEnabled,
                hasBluetoothPermission = environment.hasBluetoothPermission,
                hasLocationPermission = hasLocationPermission(),
                hasBleSupport = environment.bluetoothAvailable,
            )
        }
    }

    fun scanDevices() {
        if (scanJob?.isActive == true) return
        _state.update { it.copy(isScanning = true, scannedDevices = emptyList()) }

        val devicesByAddress = linkedMapOf<String, BleDeviceItem>()
        scanJob = viewModelScope.launch {
            val collectorJob = launch {
                scanBleDevices().collect { device ->
                    applyScannedDevice(devicesByAddress, device)
                }
            }

            try {
                while (isActive) {
                    delay(SCAN_UI_UPDATE_INTERVAL_MS)
                    _state.update {
                        it.copy(scannedDevices = prepareScannedDevicesForDisplay(devicesByAddress.values))
                    }
                }
            } finally {
                collectorJob.cancel()
                _state.update {
                    it.copy(
                        isScanning = false,
                        scannedDevices = prepareScannedDevicesForDisplay(devicesByAddress.values),
                    )
                }
            }
        }
    }

    private fun applyScannedDevice(
        devicesByAddress: MutableMap<String, BleDeviceItem>,
        device: BleScannerDataSource.ScannedDevice,
    ) {
        val existing = devicesByAddress[device.address]
        devicesByAddress[device.address] = when {
            existing != null && existing.name.isNotBlank() && device.name.isBlank() -> {
                existing.copy(
                    rssi = device.rssi,
                    hasTargetService = existing.hasTargetService || device.hasTargetService,
                )
            }

            else -> {
                BleDeviceItem(
                    name = device.name,
                    address = device.address,
                    rssi = device.rssi,
                    hasTargetService = device.hasTargetService,
                )
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _state.update { it.copy(isScanning = false) }
    }

    fun selectDevice(device: BleDeviceItem) {
        _state.update { it.copy(selectedDevice = device) }
    }

    fun setRole(role: TestRole) {
        if (_state.value.isRunning) return
        _state.update {
            it.copy(
                config = it.config.copy(role = role),
                selectedDevice = if (role == TestRole.Server) null else it.selectedDevice,
            )
        }
    }

    fun setTrafficDirection(trafficDirection: TrafficDirection) {
        if (_state.value.isRunning) return
        _state.update {
            it.copy(
                config = it.config.copy(trafficDirection = trafficDirection),
            )
        }
    }

    fun start() {
        if (testJob?.isActive == true) return

        val current = _state.value
        if (!current.hasBluetoothPermission) {
            appendLog("Missing Bluetooth permission.")
            return
        }
        if (!current.hasLocationPermission) {
            appendLog("Missing Location permission (required for BLE).")
            return
        }
        if (!current.bluetoothAvailable || !current.bluetoothEnabled) {
            appendLog("Bluetooth is unavailable or disabled.")
            return
        }
        if (current.needsSelectedDevice && current.selectedDevice == null) {
            appendLog("Select a BLE device before starting client test.")
            return
        }

        resetStats()
        current.selectedDevice
            ?.takeIf { current.needsSelectedDevice && !it.hasTargetService }
            ?.let {
                appendLog(
                    "Selected device was not advertised with the configured BLE service UUID. " +
                        "Connection may fail during GATT discovery.",
                )
            }
        _state.update { it.copy(isRunning = true, status = "Starting", isConnected = false, isAdvertising = false) }
        val sessionId = testSessionId.incrementAndGet()
        testJob = viewModelScope.launch {
            try {
                runBleTest(
                    config = current.config,
                    device = current.selectedDevice?.let {
                        com.example.bluetoothtool.model.BluetoothDeviceItem(it.name, it.address)
                    },
                    activeJob = { testJob },
                    onLog = { message -> if (isCurrentSession(sessionId)) appendLog(message) },
                    onStatus = { status -> if (isCurrentSession(sessionId)) updateStatus(status) },
                    onConnected = { status -> if (isCurrentSession(sessionId)) onConnected(status) },
                    onMtuChanged = { mtu -> if (isCurrentSession(sessionId)) onMtuChanged(mtu) },
                    onStats = { sample -> if (isCurrentSession(sessionId)) publishStats(sample) },
                    onBidirectionalStats = { sample ->
                        if (isCurrentSession(sessionId)) publishBidirectionalStats(sample)
                    },
                )
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                if (isCurrentSession(sessionId)) {
                    appendLog("BLE test error: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                if (isCurrentSession(sessionId)) {
                    markFinished()
                }
            }
        }
    }

    fun stop() {
        testSessionId.incrementAndGet()
        testJob?.cancel()
        stopBleTest()
        _state.update { it.copy(isRunning = false, isConnected = false, isAdvertising = false, status = "Stopped") }
        appendLog("Stopped.")
    }

    override fun onCleared() {
        stop()
        stopScan()
        super.onCleared()
    }

    private fun resetStats() {
        _state.update {
            it.copy(
                stats = BleThroughputStats(),
                bidirectionalStats = BleBidirectionalThroughputStats(),
                logs = emptyList(),
            )
        }
    }

    private fun onConnected(status: String) {
        _state.update {
            it.copy(
                isConnected = true,
                isAdvertising = it.config.role == TestRole.Server,
                status = status,
            )
        }
    }

    private fun onMtuChanged(mtu: Int) {
        _state.update { it.copy(mtu = mtu) }
    }

    private fun updateStatus(status: String) {
        _state.update { it.copy(status = status) }
    }

    private fun isCurrentSession(sessionId: Long): Boolean {
        return testSessionId.get() == sessionId
    }

    private fun publishStats(sample: BleThroughputSample) {
        _state.update {
            it.copy(stats = BleThroughputStats(sample))
        }
    }

    private fun publishBidirectionalStats(sample: BleBidirectionalThroughputSample) {
        _state.update {
            it.copy(bidirectionalStats = BleBidirectionalThroughputStats(sample))
        }
    }

    private fun markFinished() {
        _state.update {
            it.copy(
                isRunning = false,
                isConnected = false,
                isAdvertising = false,
                status = if (it.status == "Stopped") it.status else "Idle",
            )
        }
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update { state ->
            state.copy(logs = (state.logs + "$timestamp  $message").takeLast(MAX_LOG_LINES))
        }
    }

    private fun hasLocationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            (ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    private fun sortScannedDevices(devices: List<BleDeviceItem>): List<BleDeviceItem> {
        return devices.sortedWith(
            compareByDescending<BleDeviceItem> { it.hasTargetService }
                .thenByDescending { it.rssi }
                .thenBy { it.name.ifBlank { "Unknown BLE Device" }.lowercase(Locale.US) }
                .thenBy { it.address },
        )
    }

    private fun prepareScannedDevicesForDisplay(devices: Collection<BleDeviceItem>): List<BleDeviceItem> {
        val showUnnamedDevices = settingsRepository.getSettings().showUnnamedBleDevices
        val filtered = if (showUnnamedDevices) {
            devices
        } else {
            devices.filter { it.hasDisplayName() || it.hasTargetService }
        }
        return sortScannedDevices(filtered.toList())
    }

    private fun BleDeviceItem.hasDisplayName(): Boolean {
        return name.isNotBlank() &&
            name != UNKNOWN_BLE_DEVICE_NAME &&
            name != TARGET_BLE_DEVICE_FALLBACK_NAME
    }

    companion object {
        private const val MAX_LOG_LINES = 80
        private const val SCAN_UI_UPDATE_INTERVAL_MS = 500L
        private const val UNKNOWN_BLE_DEVICE_NAME = "Unknown BLE Device"
        private const val TARGET_BLE_DEVICE_FALLBACK_NAME = "BluetoothTool BLE Device"
    }
}
