package com.example.bluetoothtool.data.repository

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.example.bluetoothtool.data.bluetooth.BleAdvertiserDataSource
import com.example.bluetoothtool.data.bluetooth.BleGattDataSource
import com.example.bluetoothtool.data.bluetooth.BleScannerDataSource
import com.example.bluetoothtool.data.bluetooth.BluetoothEnvironment
import com.example.bluetoothtool.data.bluetooth.BluetoothPermissionChecker
import com.example.bluetoothtool.model.BluetoothDeviceItem
import com.example.bluetoothtool.model.TestMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.IOException

class AndroidBleTestRepository(
    context: Context,
) : BleTestRepository {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val permissionChecker = BluetoothPermissionChecker(appContext)
    private val gattDataSource = BleGattDataSource({ adapter }, appContext)
    private val scannerDataSource = BleScannerDataSource { adapter }
    private val advertiserDataSource = BleAdvertiserDataSource(
        { adapter },
        { BleGattDataSource.BLE_THROUGHPUT_SERVICE_UUID },
    )

    override fun getEnvironment(): BluetoothEnvironment {
        return BluetoothEnvironment(
            bluetoothAvailable = adapter != null,
            bluetoothEnabled = adapter?.isEnabled == true,
            hasBluetoothPermission = permissionChecker.hasAllBlePermissions(),
        )
    }

    override suspend fun runTest(
        mode: TestMode,
        device: BluetoothDeviceItem?,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onMtuChanged: (Int) -> Unit,
        onStats: (bytes: Long, elapsedMillis: Long) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            try {
                when (mode) {
                    TestMode.BleClientSend -> runClient(
                        device = requireNotNull(device) { "BLE client test requires a target device." },
                        activeJob = activeJob,
                        onLog = onLog,
                        onStatus = onStatus,
                        onConnected = onConnected,
                        onMtuChanged = onMtuChanged,
                        onStats = onStats,
                    )

                    TestMode.BleServerReceive -> runServer(
                        activeJob = activeJob,
                        onLog = onLog,
                        onStatus = onStatus,
                        onConnected = onConnected,
                        onMtuChanged = onMtuChanged,
                        onStats = onStats,
                    )

                    else -> onLog("Test mode $mode is not supported by BLE repository.")
                }
            } finally {
                close()
            }
        }
    }

    private suspend fun runClient(
        device: BluetoothDeviceItem,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onMtuChanged: (Int) -> Unit,
        onStats: (bytes: Long, elapsedMillis: Long) -> Unit,
    ) {
        try {
            onLog("Connecting GATT to ${device.name} (${device.address})...")
            onStatus("Connecting")
            val result = gattDataSource.connectAndDiscover(device)
            if (!result.success) {
                onLog("Connection failed: ${result.message}")
                return
            }
            onLog("Connected. MTU=${result.mtu}")
            onMtuChanged(result.mtu)
            onConnected("Client sending")
            gattDataSource.writePayloadLoop(activeJob, onStats)
        } catch (error: IOException) {
            onLog("BLE client error: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun runServer(
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onMtuChanged: (Int) -> Unit,
        onStats: (bytes: Long, elapsedMillis: Long) -> Unit,
    ) {
        try {
            onLog("Setting up GATT server...")
            onStatus("Setting up")
            val setupResult = gattDataSource.setupGattServer(
                onConnectionAccepted = {
                    onLog("BLE client connected.")
                    onConnected("Server receiving")
                    onStatus("Receiving")
                },
                onMtuChanged = onMtuChanged,
            )
            if (!setupResult.success) {
                onLog("GATT server setup failed: ${setupResult.message}")
                return
            }

            onLog("Starting BLE advertising...")
            advertiserDataSource.startAdvertising(
                onSuccess = { msg -> onLog(msg) },
                onError = { msg -> onLog(msg) },
            )

            onLog("Waiting for GATT client connection...")
            onStatus("Listening")

            gattDataSource.receivePayloadLoop(activeJob, onStats)
        } catch (error: IOException) {
            onLog("BLE server error: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun close() {
        gattDataSource.closeClient()
        gattDataSource.closeServer()
        advertiserDataSource.stopAdvertising()
    }
}
