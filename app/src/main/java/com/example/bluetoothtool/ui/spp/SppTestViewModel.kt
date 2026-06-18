package com.example.bluetoothtool.ui.spp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetoothtool.domain.GetPairedDevicesUseCase
import com.example.bluetoothtool.domain.RefreshBluetoothEnvironmentUseCase
import com.example.bluetoothtool.domain.RunSppTestUseCase
import com.example.bluetoothtool.domain.StopSppTestUseCase
import com.example.bluetoothtool.model.BluetoothDeviceItem
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

class SppTestViewModel(
    private val refreshBluetoothEnvironment: RefreshBluetoothEnvironmentUseCase,
    private val getPairedDevices: GetPairedDevicesUseCase,
    private val runSppTest: RunSppTestUseCase,
    private val stopSppTest: StopSppTestUseCase,
) : ViewModel() {
    private var testJob: Job? = null

    private val _state = MutableStateFlow(SppUiState())
    val state: StateFlow<SppUiState> = _state.asStateFlow()

    init {
        refreshPermissionsAndDevices()
    }

    fun refreshPermissionsAndDevices() {
        val environment = refreshBluetoothEnvironment()
        _state.update {
            it.copy(
                bluetoothAvailable = environment.bluetoothAvailable,
                bluetoothEnabled = environment.bluetoothEnabled,
                hasBluetoothPermission = environment.hasBluetoothPermission,
            )
        }
        refreshPairedDevices()
    }

    fun selectDevice(device: BluetoothDeviceItem) {
        _state.update { it.copy(selectedDevice = device) }
    }

    fun setMode(mode: TestMode) {
        if (_state.value.isRunning) return
        _state.update { it.copy(mode = mode, selectedDevice = if (mode == TestMode.ServerReceive) null else it.selectedDevice) }
    }

    fun start() {
        if (testJob?.isActive == true) return

        val current = _state.value
        if (!current.hasBluetoothPermission) {
            appendLog("Missing Bluetooth permission.")
            return
        }
        if (!current.bluetoothAvailable || !current.bluetoothEnabled) {
            appendLog("Bluetooth is unavailable or disabled.")
            return
        }
        if (current.mode == TestMode.ClientSend && current.selectedDevice == null) {
            appendLog("Select a paired device before client test.")
            return
        }

        resetStats()
        _state.update { it.copy(isRunning = true, status = "Starting", isConnected = false) }
        testJob = viewModelScope.launch {
            runSppTest(
                mode = current.mode,
                device = current.selectedDevice,
                activeJob = { testJob },
                onLog = ::appendLog,
                onStatus = ::updateStatus,
                onConnected = ::onConnected,
                onStats = ::publishStats,
            )
            markFinished()
        }
    }

    fun stop() {
        testJob?.cancel()
        stopSppTest()
        _state.update { it.copy(isRunning = false, isConnected = false, status = "Stopped") }
        appendLog("Stopped.")
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private fun refreshPairedDevices() {
        if (!_state.value.hasBluetoothPermission || !_state.value.bluetoothAvailable) {
            _state.update { it.copy(pairedDevices = emptyList()) }
            return
        }

        val paired = getPairedDevices()
        _state.update {
            val selectedStillExists = paired.any { device -> device.address == it.selectedDevice?.address }
            it.copy(
                pairedDevices = paired,
                selectedDevice = if (selectedStillExists) it.selectedDevice else paired.firstOrNull(),
            )
        }
    }

    private fun resetStats() {
        _state.update { it.copy(stats = ThroughputStats(), logs = emptyList()) }
    }

    private fun onConnected(status: String) {
        _state.update { it.copy(isConnected = true, status = status) }
    }

    private fun updateStatus(status: String) {
        _state.update { it.copy(status = status) }
    }

    private fun publishStats(bytes: Long, elapsedMillis: Long) {
        _state.update { it.copy(stats = ThroughputStats(bytes = bytes, elapsedMillis = elapsedMillis.coerceAtLeast(1L))) }
    }

    private fun markFinished() {
        _state.update {
            it.copy(
                isRunning = false,
                isConnected = false,
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

    companion object {
        private const val MAX_LOG_LINES = 80
    }
}
