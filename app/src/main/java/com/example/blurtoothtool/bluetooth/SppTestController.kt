package com.example.blurtoothtool.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class SppTestController(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var testJob: Job? = null
    private var socket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null

    private val _state = MutableStateFlow(
        SppUiState(
            bluetoothAvailable = adapter != null,
            bluetoothEnabled = adapter?.isEnabled == true,
            hasBluetoothPermission = hasBluetoothPermission(),
        ),
    )
    val state: StateFlow<SppUiState> = _state.asStateFlow()

    init {
        refreshPairedDevices()
    }

    fun refreshPermissionsAndDevices() {
        _state.update {
            it.copy(
                bluetoothAvailable = adapter != null,
                bluetoothEnabled = adapter?.isEnabled == true,
                hasBluetoothPermission = hasBluetoothPermission(),
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
        testJob = scope.launch {
            when (_state.value.mode) {
                TestMode.ClientSend -> runClient(requireNotNull(_state.value.selectedDevice))
                TestMode.ServerReceive -> runServer()
            }
        }
    }

    fun stop() {
        testJob?.cancel()
        closeSockets()
        _state.update { it.copy(isRunning = false, isConnected = false, status = "Stopped") }
        appendLog("Stopped.")
    }

    @SuppressLint("MissingPermission")
    fun refreshPairedDevices() {
        if (!hasBluetoothPermission() || adapter == null) {
            _state.update { it.copy(pairedDevices = emptyList()) }
            return
        }

        val paired = adapter.bondedDevices
            .orEmpty()
            .map { device ->
                BluetoothDeviceItem(
                    name = device.name ?: "Unknown device",
                    address = device.address,
                )
            }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.address }))

        _state.update {
            val selectedStillExists = paired.any { device -> device.address == it.selectedDevice?.address }
            it.copy(
                pairedDevices = paired,
                selectedDevice = if (selectedStillExists) it.selectedDevice else paired.firstOrNull(),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runClient(deviceItem: BluetoothDeviceItem) {
        withContext(Dispatchers.IO) {
            try {
                val remoteDevice: BluetoothDevice = adapter!!.getRemoteDevice(deviceItem.address)
                appendLog("Connecting to ${deviceItem.name} (${deviceItem.address})...")
                updateStatus("Connecting")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    adapter.cancelDiscovery()
                }
                val clientSocket = remoteDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = clientSocket
                clientSocket.connect()
                appendLog("Connected. Sending payload...")
                onConnected("Client sending")
                sendPayloadLoop(clientSocket)
            } catch (error: IOException) {
                appendLog("Client error: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                closeSockets()
                markFinished()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runServer() {
        withContext(Dispatchers.IO) {
            try {
                appendLog("Listening for SPP client...")
                updateStatus("Listening")
                val listener = adapter!!.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                serverSocket = listener
                val acceptedSocket = listener.accept()
                socket = acceptedSocket
                serverSocket?.close()
                serverSocket = null
                appendLog("Client connected. Receiving payload...")
                onConnected("Server receiving")
                receivePayloadLoop(acceptedSocket)
            } catch (error: IOException) {
                appendLog("Server error: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                closeSockets()
                markFinished()
            }
        }
    }

    private suspend fun sendPayloadLoop(activeSocket: BluetoothSocket) {
        val output = activeSocket.outputStream
        val buffer = ByteArray(BUFFER_SIZE) { index -> (index and 0xFF).toByte() }
        val start = System.currentTimeMillis()
        var bytes = 0L
        var nextTick = start

        while (testJob?.isActive == true) {
            output.write(buffer)
            bytes += buffer.size

            val now = System.currentTimeMillis()
            if (now >= nextTick) {
                publishStats(bytes, now - start)
                nextTick = now + STATS_INTERVAL_MS
            }
        }
    }

    private suspend fun receivePayloadLoop(activeSocket: BluetoothSocket) {
        val input = activeSocket.inputStream
        val buffer = ByteArray(BUFFER_SIZE)
        val start = System.currentTimeMillis()
        var bytes = 0L
        var nextTick = start

        while (testJob?.isActive == true) {
            val read = input.read(buffer)
            if (read < 0) break
            bytes += read

            val now = System.currentTimeMillis()
            if (now >= nextTick) {
                publishStats(bytes, now - start)
                nextTick = now + STATS_INTERVAL_MS
            }
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
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

    private fun closeSockets() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        socket = null
        serverSocket = null
    }

    private fun appendLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _state.update { state ->
            state.copy(logs = (state.logs + "$timestamp  $message").takeLast(MAX_LOG_LINES))
        }
    }

    companion object {
        private const val SERVICE_NAME = "BLurtoothTool SPP"
        private const val BUFFER_SIZE = 8 * 1024
        private const val STATS_INTERVAL_MS = 1_000L
        private const val MAX_LOG_LINES = 80
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
