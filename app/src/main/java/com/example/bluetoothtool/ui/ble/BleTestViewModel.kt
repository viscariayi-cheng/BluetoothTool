package com.example.bluetoothtool.ui.ble

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetoothtool.data.bluetooth.BleScannerDataSource
import com.example.bluetoothtool.domain.RefreshBleEnvironmentUseCase
import com.example.bluetoothtool.domain.RunBleTestUseCase
import com.example.bluetoothtool.domain.ScanBleDevicesUseCase
import com.example.bluetoothtool.domain.StopBleTestUseCase
import com.example.bluetoothtool.model.TestMode
import com.example.bluetoothtool.model.ThroughputStats
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BleTestViewModel(
    private val appContext: Context,
    private val refreshBleEnvironment: RefreshBleEnvironmentUseCase,
    private val scanBleDevices: ScanBleDevicesUseCase,
    private val runBleTest: RunBleTestUseCase,
    private val stopBleTest: StopBleTestUseCase,
) : ViewModel() {
    private var testJob: Job? = null
    private var scanJob: Job? = null

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

        val seen = mutableSetOf<String>()
        scanJob = viewModelScope.launch {
            scanBleDevices().collect { device ->
                val key = device.address
                if (seen.add(key)) {
                    _state.update { state ->
                        state.copy(
                            scannedDevices = state.scannedDevices + BleDeviceItem(
                                name = device.name,
                                address = device.address,
                                rssi = device.rssi,
                            ),
                        )
                    }
                } else {
                    // Update RSSI for existing device
                    _state.update { state ->
                        state.copy(
                            scannedDevices = state.scannedDevices.map {
                                if (it.address == key) it.copy(rssi = device.rssi) else it
                            },
                        )
                    }
                }
            }
            _state.update { it.copy(isScanning = false) }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _state.update { it.copy(isScanning = false) }
    }

    fun selectDevice(device: BleDeviceItem) {
        _state.update { it.copy(selectedDevice = device) }
    }

    fun setMode(mode: TestMode) {
        if (_state.value.isRunning) return
        _state.update {
            it.copy(
                mode = mode,
                selectedDevice = if (mode == TestMode.BleServerNotify) null else it.selectedDevice,
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
        if (current.mode == TestMode.BleClientWrite && current.selectedDevice == null) {
            appendLog("Select a BLE device before starting client test.")
            return
        }

        resetStats()
        _state.update { it.copy(isRunning = true, status = "Starting", isConnected = false, isAdvertising = false) }
        testJob = viewModelScope.launch {
            runBleTest(
                mode = current.mode,
                device = current.selectedDevice?.let {
                    com.example.bluetoothtool.model.BluetoothDeviceItem(it.name, it.address)
                },
                activeJob = { testJob },
                onLog = ::appendLog,
                onStatus = ::updateStatus,
                onConnected = ::onConnected,
                onMtuChanged = ::onMtuChanged,
                onStats = ::publishStats,
            )
            markFinished()
        }
    }

    fun stop() {
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
        _state.update { it.copy(stats = ThroughputStats(), logs = emptyList()) }
    }

    private fun onConnected(status: String) {
        _state.update {
            it.copy(
                isConnected = true,
                isAdvertising = it.mode == TestMode.BleServerNotify,
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

    private fun publishStats(bytes: Long, elapsedMillis: Long) {
        _state.update {
            it.copy(stats = ThroughputStats(bytes = bytes, elapsedMillis = elapsedMillis.coerceAtLeast(1L)))
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

    companion object {
        private const val MAX_LOG_LINES = 80
    }
}