package com.example.bluetoothtool.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Build
import android.os.ParcelUuid

class BleAdvertiserDataSource(
    private val adapterProvider: () -> BluetoothAdapter?,
    private val serviceUuidProvider: () -> java.util.UUID,
) {
    private var activeAdvertiseCallback: AdvertiseCallback? = null
    private var activeAdvertisingSetCallback: AdvertisingSetCallback? = null

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

        stopAdvertising()

        startLegacyAdvertising(
            advertiser = advertiser,
            adapter = adapter,
            includeDeviceName = true,
            onSuccess = onSuccess,
            onError = onError,
        )
    }

    @SuppressLint("MissingPermission")
    private fun startExtendedAdvertising(
        advertiser: BluetoothLeAdvertiser,
        adapter: BluetoothAdapter,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(true)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()

        val serviceUuid = serviceUuidProvider()
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()

        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                advertisingSet: AdvertisingSet?,
                txPower: Int,
                status: Int,
            ) {
                if (status == ADVERTISE_SUCCESS) {
                    onSuccess(
                        "BLE extended advertising started (maxData=${adapter.leMaximumAdvertisingDataLength}, txPower=$txPower).",
                    )
                } else {
                    activeAdvertisingSetCallback = null
                    startLegacyAdvertising(
                        advertiser = advertiser,
                        adapter = adapter,
                        includeDeviceName = true,
                        onSuccess = onSuccess,
                        onError = { legacyError ->
                            onError("${extendedAdvertisingErrorMessage(status)} Fallback failed: $legacyError")
                        },
                    )
                }
            }
        }

        activeAdvertisingSetCallback = callback
        advertiser.startAdvertisingSet(
            parameters,
            advertiseData,
            null,
            null,
            null,
            0,
            0,
            callback,
        )
    }

    @SuppressLint("MissingPermission")
    private fun startLegacyAdvertising(
        advertiser: BluetoothLeAdvertiser,
        adapter: BluetoothAdapter,
        includeDeviceName: Boolean,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val serviceUuid = serviceUuidProvider()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(includeDeviceName)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                val namePart = if (includeDeviceName) "with scan response name" else "without device name"
                onSuccess("BLE legacy advertising started (${settingsInEffect.mode}, service=$serviceUuid, $namePart).")
            }

            override fun onStartFailure(errorCode: Int) {
                activeAdvertiseCallback = null
                if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE && includeDeviceName) {
                    startLegacyAdvertising(
                        advertiser = advertiser,
                        adapter = adapter,
                        includeDeviceName = false,
                        onSuccess = onSuccess,
                        onError = onError,
                    )
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && adapter.isLeExtendedAdvertisingSupported) {
                    startExtendedAdvertising(
                        advertiser = advertiser,
                        adapter = adapter,
                        onSuccess = onSuccess,
                        onError = { extendedError ->
                            onError("${legacyAdvertisingErrorMessage(errorCode)} Extended fallback failed: $extendedError")
                        },
                    )
                    return
                }
                onError(legacyAdvertisingErrorMessage(errorCode))
            }
        }
        activeAdvertiseCallback = callback
        advertiser.startAdvertising(settings, data, scanResponse, callback)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        val adapter = adapterProvider() ?: return
        val legacyCallback = activeAdvertiseCallback
        val extendedCallback = activeAdvertisingSetCallback
        activeAdvertiseCallback = null
        activeAdvertisingSetCallback = null
        try {
            if (legacyCallback != null) {
                adapter.bluetoothLeAdvertiser?.stopAdvertising(legacyCallback)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && extendedCallback != null) {
                adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(extendedCallback)
            }
        } catch (_: Exception) {
        }
    }

    private fun legacyAdvertisingErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertising data too large."
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers active."
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Advertising already started."
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal advertising error."
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Advertising feature not supported."
            else -> "Advertising start failed (code=$errorCode)."
        }
    }

    private fun extendedAdvertisingErrorMessage(status: Int): String {
        return when (status) {
            AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Extended advertising data too large."
            AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers active."
            AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Extended advertising already started."
            AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal extended advertising error."
            AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Extended advertising not supported."
            else -> "Extended advertising start failed (code=$status)."
        }
    }
}
