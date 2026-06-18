package com.example.bluetoothtool.ui.spp

import com.example.bluetoothtool.model.BluetoothDeviceItem
import com.example.bluetoothtool.model.TestMode
import com.example.bluetoothtool.model.ThroughputStats

data class SppUiState(
    val bluetoothAvailable: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val hasBluetoothPermission: Boolean = false,
    val pairedDevices: List<BluetoothDeviceItem> = emptyList(),
    val selectedDevice: BluetoothDeviceItem? = null,
    val mode: TestMode = TestMode.ClientSend,
    val isRunning: Boolean = false,
    val isConnected: Boolean = false,
    val status: String = "Idle",
    val stats: ThroughputStats = ThroughputStats(),
    val logs: List<String> = emptyList(),
)
