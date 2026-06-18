package com.example.bluetoothtool.data.bluetooth

data class BluetoothEnvironment(
    val bluetoothAvailable: Boolean,
    val bluetoothEnabled: Boolean,
    val hasBluetoothPermission: Boolean,
)
