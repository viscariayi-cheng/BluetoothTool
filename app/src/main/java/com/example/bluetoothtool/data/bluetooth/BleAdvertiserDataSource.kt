package com.example.bluetoothtool.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser

class BleAdvertiserDataSource(
    private val adapterProvider: () -> BluetoothAdapter?,
    private val serviceUuidProvider: () -> java.util.UUID,
) {
    @SuppressLint("MissingPermission")
    fun startAdvertising(
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val adapter = adapterProvider()
        if (adapter == null || !adapter.isEnabled) {
            onError("Bluetooth adapter is unavailable or disabled.")
            return
        }

        val advertiser: BluetoothLeAdvertiser = adapter.bluetoothLeAdvertiser ?: run {
            onError("BLE advertising is not supported on this device.")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val serviceUuid = serviceUuidProvider()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(android.os.ParcelUuid(serviceUuid))
            .build()

        advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                onSuccess("BLE advertising started (${settingsInEffect.mode}).")
            }

            override fun onStartFailure(errorCode: Int) {
                val message = when (errorCode) {
                    AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertising data too large."
                    AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers active."
                    AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Advertising already started."
                    AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal advertising error."
                    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Advertising feature not supported."
                    else -> "Advertising start failed (code=$errorCode)."
                }
                onError(message)
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        val adapter = adapterProvider() ?: return
        try {
            adapter.bluetoothLeAdvertiser?.stopAdvertising(NoOpAdvertiseCallback)
        } catch (_: Exception) {
        }
    }

    private object NoOpAdvertiseCallback : AdvertiseCallback()
}