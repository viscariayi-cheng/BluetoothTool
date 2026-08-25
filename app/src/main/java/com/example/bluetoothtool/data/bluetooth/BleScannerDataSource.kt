package com.example.bluetoothtool.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

class BleScannerDataSource(
    private val adapterProvider: () -> BluetoothAdapter?,
    private val serviceUuidProvider: () -> UUID,
) {
    data class ScannedDevice(
        val name: String,
        val address: String,
        val rssi: Int,
        val hasTargetService: Boolean,
    )

    @SuppressLint("MissingPermission")
    fun scan(): Flow<ScannedDevice> = callbackFlow {
        val adapter = adapterProvider()
        if (adapter == null || !adapter.isEnabled) {
            close()
            return@callbackFlow
        }

        val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner ?: run {
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emitResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::emitResult)
            }

            override fun onScanFailed(errorCode: Int) {
                // Scan failures are non-fatal; just let the flow continue.
            }

            private fun emitResult(result: ScanResult) {
                val device = result.device
                val hasTargetService = result.hasTargetService(serviceUuidProvider())
                val name = result.scanRecord?.deviceName
                    ?: device.name
                    ?: if (hasTargetService) "BluetoothTool BLE Device" else "Unknown BLE Device"
                trySend(
                    ScannedDevice(
                        name = name,
                        address = device.address,
                        rssi = result.rssi,
                        hasTargetService = hasTargetService,
                    ),
                )
            }
        }

        val settings = createScanSettings(adapter)
        scanner.startScan(null, settings, callback)

        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
        }
    }

    private fun createScanSettings(adapter: BluetoothAdapter): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && adapter.isLeExtendedAdvertisingSupported) {
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()
    }

    private fun ScanResult.hasTargetService(serviceUuid: UUID): Boolean {
        val target = ParcelUuid(serviceUuid)
        return scanRecord?.serviceUuids?.contains(target) == true
    }
}
