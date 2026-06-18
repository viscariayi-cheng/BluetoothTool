package com.example.blurtoothtool.bluetooth

data class BluetoothDeviceItem(
    val name: String,
    val address: String,
)

enum class TestMode {
    ClientSend,
    ServerReceive,
}

data class ThroughputStats(
    val bytes: Long = 0,
    val elapsedMillis: Long = 0,
) {
    val mbps: Double
        get() = if (elapsedMillis <= 0L) 0.0 else (bytes * 8.0 / 1_000_000.0) / (elapsedMillis / 1_000.0)
}

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
