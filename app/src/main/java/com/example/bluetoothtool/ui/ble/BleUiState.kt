package com.example.bluetoothtool.ui.ble

import com.example.bluetoothtool.model.TestMode
import com.example.bluetoothtool.model.ThroughputStats

data class BleUiState(
    val bluetoothAvailable: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val hasBluetoothPermission: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val hasBleSupport: Boolean = true,
    val scannedDevices: List<BleDeviceItem> = emptyList(),
    val selectedDevice: BleDeviceItem? = null,
    val mode: TestMode = TestMode.BleClientSend,
    val isScanning: Boolean = false,
    val isRunning: Boolean = false,
    val isConnected: Boolean = false,
    val isAdvertising: Boolean = false,
    val mtu: Int = 23,
    val status: String = "Idle",
    val stats: ThroughputStats = ThroughputStats(),
    val logs: List<String> = emptyList(),
)

data class BleDeviceItem(
    val name: String,
    val address: String,
    val rssi: Int = 0,
)
