package com.example.bluetoothtool.data.repository

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.example.bluetoothtool.data.bluetooth.BleAdvertiserDataSource
import com.example.bluetoothtool.data.bluetooth.BleGattDataSource
import com.example.bluetoothtool.data.bluetooth.BleScannerDataSource
import com.example.bluetoothtool.data.bluetooth.BluetoothEnvironment
import com.example.bluetoothtool.data.bluetooth.BluetoothPermissionChecker
import com.example.bluetoothtool.data.settings.AppSettingsRepository
import com.example.bluetoothtool.model.BleBidirectionalThroughputSample
import com.example.bluetoothtool.model.BleTestConfig
import com.example.bluetoothtool.model.BleThroughputSample
import com.example.bluetoothtool.model.BluetoothDeviceItem
import com.example.bluetoothtool.model.TestRole
import com.example.bluetoothtool.model.TrafficDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class AndroidBleTestRepository(
    context: Context,
) : BleTestRepository {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val permissionChecker = BluetoothPermissionChecker(appContext)
    private val settingsRepository = AppSettingsRepository(appContext)
    private val gattDataSource = BleGattDataSource(
        adapterProvider = { adapter },
        context = appContext,
        serviceUuidProvider = { UUID.fromString(settingsRepository.getSettings().bleServiceUuid) },
        txCharacteristicUuidProvider = {
            UUID.fromString(settingsRepository.getSettings().bleTxCharacteristicUuid)
        },
        rxCharacteristicUuidProvider = {
            UUID.fromString(settingsRepository.getSettings().bleRxCharacteristicUuid)
        },
    )
    private val scannerDataSource = BleScannerDataSource(
        adapterProvider = { adapter },
        serviceUuidProvider = { UUID.fromString(settingsRepository.getSettings().bleServiceUuid) },
    )
    private val advertiserDataSource = BleAdvertiserDataSource(
        { adapter },
        { UUID.fromString(settingsRepository.getSettings().bleServiceUuid) },
    )

    override fun getEnvironment(): BluetoothEnvironment {
        return BluetoothEnvironment(
            bluetoothAvailable = adapter != null,
            bluetoothEnabled = adapter?.isEnabled == true,
            hasBluetoothPermission = permissionChecker.hasAllBlePermissions(),
        )
    }

    override suspend fun runTest(
        config: BleTestConfig,
        device: BluetoothDeviceItem?,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onMtuChanged: (Int) -> Unit,
        onStats: (BleThroughputSample) -> Unit,
        onBidirectionalStats: (BleBidirectionalThroughputSample) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            try {
                close()
                when (config.role) {
                    TestRole.Client -> runClient(
                        config = config,
                        device = requireNotNull(device) { "BLE client test requires a target device." },
                        activeJob = activeJob,
                        onLog = onLog,
                        onStatus = onStatus,
                        onConnected = onConnected,
                        onMtuChanged = onMtuChanged,
                        onStats = onStats,
                        onBidirectionalStats = onBidirectionalStats,
                    )

                    TestRole.Server -> runServer(
                        config = config,
                        activeJob = activeJob,
                        onLog = onLog,
                        onStatus = onStatus,
                        onConnected = onConnected,
                        onMtuChanged = onMtuChanged,
                        onStats = onStats,
                        onBidirectionalStats = onBidirectionalStats,
                    )
                }
            } finally {
                close()
            }
        }
    }

    private suspend fun runClient(
        config: BleTestConfig,
        device: BluetoothDeviceItem,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onMtuChanged: (Int) -> Unit,
        onStats: (BleThroughputSample) -> Unit,
        onBidirectionalStats: (BleBidirectionalThroughputSample) -> Unit,
    ) {
        try {
            onLog("Connecting GATT to ${device.name} (${device.address})...")
            logGattUuids(onLog)
            onStatus("Connecting")
            val result = gattDataSource.connectAndDiscover(device)
            if (!result.success) {
                onLog("Connection failed: ${result.message}")
                return
            }
            onLog("Connected. MTU=${result.mtu}, ATT payload=${(result.mtu - 3).coerceAtLeast(20)} bytes")
            if (result.mtu <= 23) {
                onLog("MTU stayed at 23; BLE GATT throughput will be limited by 20-byte ATT payloads.")
            }
            onMtuChanged(result.mtu)
            when (config.trafficDirection) {
                TrafficDirection.Tx -> {
                    if (!gattDataSource.hasPeerRxCharacteristic()) {
                        onLog("Peer RX characteristic not found; cannot run Android TX.")
                        return
                    }
                    onLog("Starting TX writes to peer RX characteristic.")
                    onLog("Peer device must be running RX or TX + RX for this one-way TX test.")
                    onConnected("Client TX")
                    onStatus("Sending")
                    gattDataSource.writePayloadLoop(activeJob, onLog, onStats)
                }

                TrafficDirection.Rx -> {
                    if (!gattDataSource.hasPeerTxCharacteristic()) {
                        onLog("Peer TX characteristic not found; cannot run Android RX.")
                        return
                    }
                    onLog("Subscribing to peer TX characteristic.")
                    if (!gattDataSource.subscribeToServerTx()) {
                        onLog("Failed to subscribe to peer TX characteristic.")
                        return
                    }
                    onConnected("Client RX")
                    onStatus("Receiving")
                    onLog("Peer device must be running TX or TX + RX for this one-way RX test.")
                    gattDataSource.receiveNotificationLoop(activeJob, onLog, onStats)
                }

                TrafficDirection.TxRx -> {
                    if (!gattDataSource.hasPeerTxCharacteristic() || !gattDataSource.hasPeerRxCharacteristic()) {
                        onLog("Peer TX and RX characteristics are both required for Android TX + RX.")
                        return
                    }
                    onLog("Subscribing to peer TX characteristic and writing peer RX characteristic.")
                    if (!gattDataSource.subscribeToServerTx()) {
                        onLog("Failed to subscribe to peer TX characteristic.")
                        return
                    }
                    onConnected("Client TX + RX")
                    onStatus("Sending + Receiving")
                    onLog("Peer device must also be running TX + RX for bidirectional testing.")
                    gattDataSource.clientBidirectionalPayloadLoop(activeJob, onLog, onBidirectionalStats)
                }
            }
        } catch (error: IOException) {
            onLog("BLE client error: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun runServer(
        config: BleTestConfig,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onMtuChanged: (Int) -> Unit,
        onStats: (BleThroughputSample) -> Unit,
        onBidirectionalStats: (BleBidirectionalThroughputSample) -> Unit,
    ) {
        try {
            onLog("Setting up GATT server...")
            logGattUuids(onLog)
            onStatus("Setting up")
            val setupResult = gattDataSource.setupGattServer(
                onConnectionAccepted = {
                    onLog("BLE client connected.")
                    onConnected("Server ${trafficLabel(config.trafficDirection)}")
                    onStatus(trafficStatus(config.trafficDirection))
                },
                onMtuChanged = onMtuChanged,
                onLog = onLog,
            )
            if (!setupResult.success) {
                onLog("GATT server setup failed: ${setupResult.message}")
                return
            }
            onLog(setupResult.message)

            onLog("Starting BLE advertising...")
            advertiserDataSource.startAdvertising(
                onSuccess = { msg -> onLog(msg) },
                onError = { msg -> onLog(msg) },
            )

            onLog("Waiting for GATT client connection...")
            onStatus("Listening")

            when (config.trafficDirection) {
                TrafficDirection.Tx -> {
                    onLog("Starting TX notifications from local TX characteristic.")
                    onLog("Waiting for peer to subscribe; peer must be running RX or TX + RX.")
                    gattDataSource.notifyPayloadLoop(activeJob, onLog, onStats)
                }

                TrafficDirection.Rx -> {
                    onLog("Starting RX measurement on local RX characteristic.")
                    onLog("Peer device must be running TX or TX + RX for this one-way RX test.")
                    gattDataSource.receivePayloadLoop(activeJob, onLog, onStats)
                }

                TrafficDirection.TxRx -> {
                    onLog("Starting TX notifications and RX measurement.")
                    onLog("Waiting for peer subscription; peer must also be running TX + RX.")
                    gattDataSource.serverBidirectionalPayloadLoop(activeJob, onLog, onBidirectionalStats)
                }
            }
        } catch (error: IOException) {
            onLog("BLE server error: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun close() {
        gattDataSource.closeClient()
        gattDataSource.closeServer()
        advertiserDataSource.stopAdvertising()
    }

    private fun logGattUuids(onLog: (String) -> Unit) {
        val settings = settingsRepository.getSettings()
        onLog("BLE service UUID: ${settings.bleServiceUuid}")
        onLog("BLE TX characteristic UUID: ${settings.bleTxCharacteristicUuid}")
        onLog("BLE RX characteristic UUID: ${settings.bleRxCharacteristicUuid}")
    }

    private fun trafficLabel(trafficDirection: TrafficDirection): String {
        return when (trafficDirection) {
            TrafficDirection.Tx -> "TX"
            TrafficDirection.Rx -> "RX"
            TrafficDirection.TxRx -> "TX + RX"
        }
    }

    private fun trafficStatus(trafficDirection: TrafficDirection): String {
        return when (trafficDirection) {
            TrafficDirection.Tx -> "Sending"
            TrafficDirection.Rx -> "Receiving"
            TrafficDirection.TxRx -> "Sending + Receiving"
        }
    }
}
