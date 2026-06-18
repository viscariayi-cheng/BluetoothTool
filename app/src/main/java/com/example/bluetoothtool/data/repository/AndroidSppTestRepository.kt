package com.example.bluetoothtool.data.repository

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.example.bluetoothtool.data.bluetooth.BluetoothDiscoverabilityDataSource
import com.example.bluetoothtool.data.bluetooth.BluetoothEnvironment
import com.example.bluetoothtool.data.bluetooth.BluetoothPermissionChecker
import com.example.bluetoothtool.data.bluetooth.SppSocketDataSource
import com.example.bluetoothtool.model.BluetoothDeviceItem
import com.example.bluetoothtool.model.TestMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.IOException

class AndroidSppTestRepository(
    context: Context,
) : SppTestRepository {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val permissionChecker = BluetoothPermissionChecker(appContext)
    private val socketDataSource = SppSocketDataSource { adapter }
    private val discoverabilityDataSource = BluetoothDiscoverabilityDataSource { adapter }

    override fun getEnvironment(): BluetoothEnvironment {
        return BluetoothEnvironment(
            bluetoothAvailable = adapter != null,
            bluetoothEnabled = adapter?.isEnabled == true,
            hasBluetoothPermission = permissionChecker.hasBluetoothPermission(),
        )
    }

    @SuppressLint("MissingPermission")
    override fun getPairedDevices(): List<BluetoothDeviceItem> {
        if (!permissionChecker.hasBluetoothPermission() || adapter == null) {
            return emptyList()
        }

        return adapter.bondedDevices
            .orEmpty()
            .map { device ->
                BluetoothDeviceItem(
                    name = device.name ?: "Unknown device",
                    address = device.address,
                )
            }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.address }))
    }

    override suspend fun runTest(
        mode: TestMode,
        device: BluetoothDeviceItem?,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onStats: (bytes: Long, elapsedMillis: Long) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            try {
                when (mode) {
                    TestMode.ClientSend -> runClient(
                        device = requireNotNull(device) { "Client test requires a paired device." },
                        activeJob = activeJob,
                        onLog = onLog,
                        onStatus = onStatus,
                        onConnected = onConnected,
                        onStats = onStats,
                    )

                    TestMode.ServerReceive -> runServer(
                        activeJob = activeJob,
                        onLog = onLog,
                        onStatus = onStatus,
                        onConnected = onConnected,
                        onStats = onStats,
                    )
                }
            } finally {
                close()
            }
        }
    }

    private fun runClient(
        device: BluetoothDeviceItem,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onStats: (bytes: Long, elapsedMillis: Long) -> Unit,
    ) {
        try {
            onLog("Connecting to ${device.name} (${device.address})...")
            onStatus("Connecting")
            val socket = socketDataSource.openClientSocket(device)
            onLog("Connected. Sending payload...")
            onConnected("Client sending")
            socketDataSource.sendPayloadLoop(socket, activeJob, onStats)
        } catch (error: IOException) {
            onLog("Client error: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun runServer(
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onStats: (bytes: Long, elapsedMillis: Long) -> Unit,
    ) {
        try {
            onLog("Listening for SPP client...")
            onStatus("Listening")
            val listener = socketDataSource.openServerSocket()
            val scanModeResult = discoverabilityDataSource.makeConnectableDiscoverable()
            onLog(scanModeResult.message)
            val socket = socketDataSource.acceptServerSocket(listener)
            onLog("Client connected. Receiving payload...")
            onConnected("Server receiving")
            socketDataSource.receivePayloadLoop(socket, activeJob, onStats)
        } catch (error: IOException) {
            onLog("Server error: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun close() {
        socketDataSource.close()
        discoverabilityDataSource.restorePreviousScanMode()
    }
}
