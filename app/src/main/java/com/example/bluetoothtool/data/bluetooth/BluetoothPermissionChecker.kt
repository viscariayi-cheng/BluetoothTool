package com.example.bluetoothtool.data.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class BluetoothPermissionChecker(
    private val context: Context,
) {
    fun hasBluetoothPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    fun hasBluetoothScanPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }

    fun hasLocationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAllBlePermissions(): Boolean {
        return hasBluetoothScanPermission() && hasLocationPermission()
    }
}
