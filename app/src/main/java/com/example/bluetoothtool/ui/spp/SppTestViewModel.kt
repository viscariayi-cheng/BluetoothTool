package com.example.bluetoothtool.ui.spp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetoothtool.domain.GetPairedDevicesUseCase
import com.example.bluetoothtool.domain.RefreshSppEnvironmentUseCase
import com.example.bluetoothtool.domain.RunSppTestUseCase
import com.example.bluetoothtool.domain.StopSppTestUseCase
import com.example.bluetoothtool.model.BluetoothDeviceItem
import com.example.bluetoothtool.model.SppBidirectionalThroughputSample
import com.example.bluetoothtool.model.SppBidirectionalThroughputStats
import com.example.bluetoothtool.model.SppTestConfig
import com.example.bluetoothtool.model.SppThroughputSample
import com.example.bluetoothtool.model.SppThroughputStats
import com.example.bluetoothtool.model.TestRole
import com.example.bluetoothtool.model.TrafficDirection
import kotlinx.coroutines.CancellationException
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
    private val refreshSppEnvironment: RefreshSppEnvironmentUseCase,
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
        val environment = refreshSppEnvironment()
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
        if (!current.bluetoothAvailable || !current.bluetoothEnabled) {
            appendLog("Bluetooth is unavailable or disabled.")
            return
        }
        if (current.needsSelectedDevice && current.selectedDevice == null) {
            appendLog("Select a paired device before client test.")
            return
        }

        resetStats()
        _state.update { it.copy(isRunning = true, status = "Starting", isConnected = false) }
        testJob = viewModelScope.launch {
            try {
                runSppTest(
                    config = current.config,
                    device = current.selectedDevice,
                    activeJob = { testJob },
                    onLog = ::appendLog,
                    onStatus = ::updateStatus,
                    onConnected = ::onConnected,
                    onStats = ::publishStats,
                    onBidirectionalStats = ::publishBidirectionalStats,
                )
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                appendLog("SPP test error: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                markFinished()
            }
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
        _state.update {
            it.copy(
                stats = SppThroughputStats(),
                bidirectionalStats = SppBidirectionalThroughputStats(),
                logs = emptyList(),
            )
        }
    }

    private fun onConnected(status: String) {
        _state.update { it.copy(isConnected = true, status = status) }
    }

    private fun updateStatus(status: String) {
        _state.update { it.copy(status = status) }
    }

    private fun publishStats(sample: SppThroughputSample) {
        _state.update {
            it.copy(
                stats = SppThroughputStats(sample),
            )
        }
    }

    private fun publishBidirectionalStats(sample: SppBidirectionalThroughputSample) {
        _state.update {
            it.copy(
                bidirectionalStats = SppBidirectionalThroughputStats(sample),
            )
        }
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
