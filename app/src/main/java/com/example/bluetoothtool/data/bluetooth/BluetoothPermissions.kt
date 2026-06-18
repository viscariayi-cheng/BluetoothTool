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
}
