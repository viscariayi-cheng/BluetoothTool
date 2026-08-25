package com.example.bluetoothtool.ui.ble

import com.example.bluetoothtool.model.BleBidirectionalThroughputStats
import com.example.bluetoothtool.model.BleTestConfig
import com.example.bluetoothtool.model.BleThroughputStats
import com.example.bluetoothtool.model.TestRole

data class BleUiState(
    val bluetoothAvailable: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val hasBluetoothPermission: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val hasBleSupport: Boolean = true,
    val scannedDevices: List<BleDeviceItem> = emptyList(),
    val selectedDevice: BleDeviceItem? = null,
    val config: BleTestConfig = BleTestConfig(),
    val isScanning: Boolean = false,
    val isRunning: Boolean = false,
    val isConnected: Boolean = false,
    val isAdvertising: Boolean = false,
    val mtu: Int = 23,
    val status: String = "Idle",
    val stats: BleThroughputStats = BleThroughputStats(),
    val bidirectionalStats: BleBidirectionalThroughputStats = BleBidirectionalThroughputStats(),
    val logs: List<String> = emptyList(),
) {
    val needsSelectedDevice: Boolean
        get() = config.role == TestRole.Client
}

data class BleDeviceItem(
    val name: String,
    val address: String,
    val rssi: Int = 0,
    val hasTargetService: Boolean = false,
)
