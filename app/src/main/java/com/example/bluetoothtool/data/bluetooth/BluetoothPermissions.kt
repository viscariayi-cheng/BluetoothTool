package com.example.bluetoothtool.data.bluetooth

import android.Manifest
import android.os.Build

object BluetoothPermissions {
    val runtimePermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    val bleRuntimePermissions: Array<String>
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                )
            } else {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            }
        }

    val bleLocationPermissions: Array<String>
        get() = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}
