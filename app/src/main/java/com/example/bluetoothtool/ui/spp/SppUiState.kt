package com.example.bluetoothtool.ui.spp

import com.example.bluetoothtool.model.BluetoothDeviceItem
import com.example.bluetoothtool.model.SppBidirectionalThroughputStats
import com.example.bluetoothtool.model.SppTestConfig
import com.example.bluetoothtool.model.SppThroughputStats
import com.example.bluetoothtool.model.TestRole

data class SppUiState(
    val bluetoothAvailable: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val hasBluetoothPermission: Boolean = false,
    val pairedDevices: List<BluetoothDeviceItem> = emptyList(),
    val selectedDevice: BluetoothDeviceItem? = null,
    val config: SppTestConfig = SppTestConfig(),
    val isRunning: Boolean = false,
    val isConnected: Boolean = false,
    val status: String = "Idle",
    val stats: SppThroughputStats = SppThroughputStats(),
    val bidirectionalStats: SppBidirectionalThroughputStats = SppBidirectionalThroughputStats(),
    val logs: List<String> = emptyList(),
) {
    val needsSelectedDevice: Boolean
        get() = config.role == TestRole.Client
}
