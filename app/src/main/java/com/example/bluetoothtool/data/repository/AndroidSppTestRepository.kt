package com.example.bluetoothtool.data.repository

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.example.bluetoothtool.data.bluetooth.BluetoothEnvironment
import com.example.bluetoothtool.data.bluetooth.BluetoothPermissionChecker
import com.example.bluetoothtool.data.bluetooth.SppSocketDataSource
import com.example.bluetoothtool.data.settings.AppSettingsRepository
import com.example.bluetoothtool.model.BluetoothDeviceItem
import com.example.bluetoothtool.model.SppBidirectionalThroughputSample
import com.example.bluetoothtool.model.SppTestConfig
import com.example.bluetoothtool.model.SppThroughputSample
import com.example.bluetoothtool.model.TestRole
import com.example.bluetoothtool.model.TrafficDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class AndroidSppTestRepository(
    context: Context,
) : SppTestRepository {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val permissionChecker = BluetoothPermissionChecker(appContext)
    private val settingsRepository = AppSettingsRepository(appContext)
    private val socketDataSource = SppSocketDataSource(
        adapterProvider = { adapter },
        serviceUuidProvider = { UUID.fromString(settingsRepository.getSettings().sppServiceUuid) },
    )

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
        config: SppTestConfig,
        device: BluetoothDeviceItem?,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onStats: (SppThroughputSample) -> Unit,
        onBidirectionalStats: (SppBidirectionalThroughputSample) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            try {
                close()
                val socket = openConnectedSocket(
                    config = config,
                    device = device,
                    activeJob = activeJob,
                    onLog = onLog,
                    onStatus = onStatus,
                ) ?: return@withContext
                runTraffic(
                    config = config,
                    socket = socket,
                    activeJob = activeJob,
                    onLog = onLog,
                    onConnected = onConnected,
                    onStats = onStats,
                    onBidirectionalStats = onBidirectionalStats,
                )
            } finally {
                close()
            }
        }
    }

    private fun openConnectedSocket(
        config: SppTestConfig,
        device: BluetoothDeviceItem?,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
    ) = try {
        onLog("SPP service UUID: ${settingsRepository.getSettings().sppServiceUuid}")
        when (config.role) {
            TestRole.Client -> {
                val selectedDevice = requireNotNull(device) { "Client test requires a paired device." }
                onLog("Connecting to ${selectedDevice.name} (${selectedDevice.address})...")
                onStatus("Connecting")
                socketDataSource.openClientSocket(selectedDevice).also {
                    onLog("Connected.")
                }
            }

            TestRole.Server -> {
                onLog("Listening for SPP client...")
                onStatus("Listening")
                val listener = socketDataSource.openServerSocket()
                onLog("Use Make Discoverable if the client cannot find this device.")
                socketDataSource.acceptServerSocket(listener).also {
                    onLog("Client connected.")
                }
            }
        }
    } catch (error: IOException) {
        if (activeJob()?.isActive == true) {
            onLog("${config.role} connection error: ${error.message ?: error.javaClass.simpleName}")
        } else {
            onLog("${config.role} connection stopped.")
        }
        null
    }

    private fun runTraffic(
        config: SppTestConfig,
        socket: BluetoothSocket,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onConnected: (String) -> Unit,
        onStats: (SppThroughputSample) -> Unit,
        onBidirectionalStats: (SppBidirectionalThroughputSample) -> Unit,
    ) {
        try {
            when (config.trafficDirection) {
                TrafficDirection.Tx -> {
                    onLog("Starting TX payload.")
                    onLog("Peer device must be running RX or TX + RX for this one-way TX test.")
                    onConnected("${config.role} TX")
                    socketDataSource.sendPayloadLoop(socket, activeJob, onStats)
                }

                TrafficDirection.Rx -> {
                    onLog("Starting RX measurement.")
                    onLog("Peer device must be running TX or TX + RX for this one-way RX test.")
                    onConnected("${config.role} RX")
                    socketDataSource.receivePayloadLoop(socket, activeJob, onStats)
                }

                TrafficDirection.TxRx -> {
                    onLog("Starting TX + RX payload.")
                    onLog("Peer device must also be running TX + RX for bidirectional testing.")
                    onConnected("${config.role} TX + RX")
                    socketDataSource.bidirectionalPayloadLoop(socket, activeJob, onBidirectionalStats)
                }
            }
        } catch (error: IOException) {
            onLog(sppTrafficErrorMessage(config, error))
        }
    }

    override fun close() {
        socketDataSource.close()
    }

    private fun sppTrafficErrorMessage(config: SppTestConfig, error: IOException): String {
        val detail = error.message ?: error.javaClass.simpleName
        val base = "${config.role} ${config.trafficDirection} error: $detail"
        if (!detail.contains("Broken pipe", ignoreCase = true)) {
            return base
        }
        val peerDirection = when (config.trafficDirection) {
            TrafficDirection.Tx -> "RX or TX + RX"
            TrafficDirection.Rx -> "TX or TX + RX"
            TrafficDirection.TxRx -> "TX + RX"
        }
        return "$base. Peer closed the SPP socket; check that the peer test is still running $peerDirection."
    }
}
