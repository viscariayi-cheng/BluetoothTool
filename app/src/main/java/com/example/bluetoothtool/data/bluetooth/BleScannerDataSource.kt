package com.example.bluetoothtool.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class BleScannerDataSource(
    private val adapterProvider: () -> BluetoothAdapter?,
) {
    data class ScannedDevice(
        val name: String,
        val address: String,
        val rssi: Int,
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
                val device = result.device
                trySend(
                    ScannedDevice(
                        name = device.name ?: "Unknown BLE Device",
                        address = device.address,
                        rssi = result.rssi,
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                // Scan failures are non-fatal; just let the flow continue.
            }
        }

        scanner.startScan(callback)

        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
        }
    }
}