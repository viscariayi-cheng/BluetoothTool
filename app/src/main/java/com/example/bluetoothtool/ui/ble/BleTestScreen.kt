package com.example.bluetoothtool.ui.ble

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.bluetoothtool.model.BleThroughputStats
import com.example.bluetoothtool.model.BleBidirectionalThroughputStats
import com.example.bluetoothtool.model.TestRole
import com.example.bluetoothtool.model.TrafficDirection
import com.example.bluetoothtool.ui.common.AppCard
import com.example.bluetoothtool.ui.common.LogsCard
import com.example.bluetoothtool.ui.common.StatusLine
import com.example.bluetoothtool.ui.theme.BluetoothToolTheme

// ====================================================================
// BLE GATT Throughput Test Screen
// ====================================================================
// 复用 AppCard / StatusLine / StatsCard / LogsCard 等通用组件。
// BLE 特有元素：
//   · Environment 卡片增加 Location Permission
//   · Scan Device 卡片（替代 SPP 的 Paired Devices）
//   · MTU 信息展示 + 理论上限对比
//   · RSSI 信号强度指示
// ====================================================================

@Composable
fun BleTestScreen(
    state: BleUiState,
    onRequestPermission: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onScanDevices: () -> Unit,
    onStopScan: () -> Unit,
    onRoleChange: (TestRole) -> Unit,
    onTrafficDirectionChange: (TrafficDirection) -> Unit,
    onDeviceSelected: (BleDeviceItem) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HeaderSection()
            StatusCard(
                state = state,
                onRequestPermission = onRequestPermission,
                onRequestLocationPermission = onRequestLocationPermission,
                onOpenBluetoothSettings = onOpenBluetoothSettings,
            )
            RoleCard(
                selectedRole = state.config.role,
                enabled = !state.isRunning,
                onRoleChange = onRoleChange,
            )
            TrafficCard(
                selectedTrafficDirection = state.config.trafficDirection,
                enabled = !state.isRunning,
                onTrafficDirectionChange = onTrafficDirectionChange,
            )
            if (state.needsSelectedDevice) {
                ScanDeviceCard(
                    devices = state.scannedDevices,
                    selectedDevice = state.selectedDevice,
                    isScanning = state.isScanning,
                    enabled = !state.isRunning,
                    onScanDevices = onScanDevices,
                    onStopScan = onStopScan,
                    onDeviceSelected = onDeviceSelected,
                )
            }
            ControlCard(
                state = state,
                onStart = onStart,
                onStop = onStop,
            )
            if (state.isConnected && state.mtu > 0) {
                MtuCard(mtu = state.mtu)
            }
            if (state.config.trafficDirection == TrafficDirection.TxRx) {
                BidirectionalStatsCard(stats = state.bidirectionalStats)
            } else {
                BleStatsCard(
                    title = throughputTitle(state.config.trafficDirection),
                    stats = state.stats,
                )
            }
            // 理论上限提示
            if (state.isConnected) {
                AppCard(title = "Theoretical Max") {
                    StatusLine("LE 1M PHY", "~0.8 Mbps (MTU 517)")
                    StatusLine("LE 2M PHY", "~1.6 Mbps (MTU 517)")
                }
            }
            LogsCard(logs = state.logs)
        }
    }
}

// ====================================================================
// 标题
// ====================================================================
@Composable
private fun HeaderSection() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "BLE GATT Throughput",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Measure BLE GATT throughput with independent connection role and traffic direction.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ====================================================================
// 环境状态 + 权限
// ====================================================================
@Composable
private fun StatusCard(
    state: BleUiState,
    onRequestPermission: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    AppCard(title = "Environment") {
        StatusLine("BLE support", if (state.hasBleSupport) "Supported" else "Not supported")
        StatusLine("Bluetooth adapter", if (state.bluetoothAvailable) "Available" else "Unavailable")
        StatusLine("Bluetooth power", if (state.bluetoothEnabled) "Enabled" else "Disabled")
        StatusLine("BT Permission", if (state.hasBluetoothPermission) "Granted" else "Required")
        StatusLine("Location Permission", if (state.hasLocationPermission) "Granted" else "Required (BLE scan)")
        if (state.config.role == TestRole.Server && state.isRunning) {
            StatusLine("Advertising", if (state.isAdvertising) "Active" else "Starting...")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.hasBluetoothPermission) {
                Button(onClick = onRequestPermission) { Text("Grant BT Permission") }
            }
            if (!state.hasLocationPermission) {
                Button(onClick = onRequestLocationPermission) { Text("Grant Location") }
            }
            if (!state.bluetoothEnabled) {
                OutlinedButton(onClick = onOpenBluetoothSettings) { Text("Open Bluetooth Settings") }
            }
        }
    }
}

// ====================================================================
// 测试模式切换
// ====================================================================
@Composable
private fun RoleCard(
    selectedRole: TestRole,
    enabled: Boolean,
    onRoleChange: (TestRole) -> Unit,
) {
    AppCard(title = "Connection") {
        ModeRow(
            title = "Client",
            description = "Android connects to the selected BLE GATT server.",
            selected = selectedRole == TestRole.Client,
            enabled = enabled,
            onClick = { onRoleChange(TestRole.Client) },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        ModeRow(
            title = "Server",
            description = "Android advertises a GATT service for a peer client to discover.",
            selected = selectedRole == TestRole.Server,
            enabled = enabled,
            onClick = { onRoleChange(TestRole.Server) },
        )
    }
}

@Composable
private fun TrafficCard(
    selectedTrafficDirection: TrafficDirection,
    enabled: Boolean,
    onTrafficDirectionChange: (TrafficDirection) -> Unit,
) {
    AppCard(title = "Traffic Direction") {
        ModeRow(
            title = "TX",
            description = "Android sends payload. Client writes peer RX; server notifies from local TX.",
            selected = selectedTrafficDirection == TrafficDirection.Tx,
            enabled = enabled,
            onClick = { onTrafficDirectionChange(TrafficDirection.Tx) },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        ModeRow(
            title = "RX",
            description = "Android receives payload. Client subscribes peer TX; server measures local RX writes.",
            selected = selectedTrafficDirection == TrafficDirection.Rx,
            enabled = enabled,
            onClick = { onTrafficDirectionChange(TrafficDirection.Rx) },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        ModeRow(
            title = "TX + RX",
            description = "Android sends and receives payload simultaneously.",
            selected = selectedTrafficDirection == TrafficDirection.TxRx,
            enabled = enabled,
            onClick = { onTrafficDirectionChange(TrafficDirection.TxRx) },
        )
    }
}

@Composable
private fun ModeRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ====================================================================
// 扫描设备列表（Client模式）
// ====================================================================
@Composable
private fun ScanDeviceCard(
    devices: List<BleDeviceItem>,
    selectedDevice: BleDeviceItem?,
    isScanning: Boolean,
    enabled: Boolean,
    onScanDevices: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceSelected: (BleDeviceItem) -> Unit,
) {
    AppCard(title = "Scan Devices") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = if (isScanning) onStopScan else onScanDevices,
                enabled = enabled,
            ) {
                Text(if (isScanning) "Stop Scan" else "Scan")
            }
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        if (devices.isEmpty()) {
            Text(
                text = if (isScanning) "Searching for BLE devices..."
                else "No devices found. Tap \"Scan\" to search for nearby BLE peripherals.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppCard
        }
        LazyColumn(
            modifier = Modifier.height(240.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(devices, key = { it.address }) { device ->
                BleDeviceRow(
                    device = device,
                    selected = device.address == selectedDevice?.address,
                    enabled = enabled,
                    onClick = { onDeviceSelected(device) },
                )
            }
        }
    }
}

@Composable
private fun BleDeviceRow(
    device: BleDeviceItem,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(modifier = Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = device.name.ifBlank { "Unknown BLE Device" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            Row {
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(12.dp))
                RssiBadge(rssi = device.rssi)
                if (device.hasTargetService) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "BluetoothTool",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun RssiBadge(rssi: Int) {
    val color = when {
        rssi >= -50 -> MaterialTheme.colorScheme.primary
        rssi >= -70 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = "${rssi} dBm",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
    )
}

// ====================================================================
// 控制区
// ====================================================================
@Composable
private fun ControlCard(
    state: BleUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val canStart = !state.isRunning
            && state.hasBluetoothPermission
            && state.hasLocationPermission
            && state.bluetoothAvailable
            && state.bluetoothEnabled
            && (!state.needsSelectedDevice || state.selectedDevice != null)

    AppCard(title = "Control") {
        StatusLine("Connection", if (state.isConnected) "Connected" else "Disconnected")
        StatusLine("State", state.status)
        if (state.isConnected) StatusLine("MTU", "${state.mtu} bytes")
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onStart, enabled = canStart) { Text("Start") }
            OutlinedButton(onClick = onStop, enabled = state.isRunning) { Text("Stop") }
        }
    }
}

@Composable
private fun BleStatsCard(
    title: String,
    stats: BleThroughputStats,
) {
    AppCard(title = title) {
        Text(
            text = "%.3f Mbps".format(stats.currentMbps),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        StatusLine("Current", "%.3f Mbps".format(stats.currentMbps))
        StatusLine("Average", "%.3f Mbps".format(stats.averageMbps))
        StatusLine("Total Bytes", stats.totalBytes.toString())
        StatusLine("Window Bytes", stats.intervalBytes.toString())
        StatusLine("Elapsed", "%.1f s".format(stats.elapsedMillis / 1_000.0))
    }
}

@Composable
private fun BidirectionalStatsCard(stats: BleBidirectionalThroughputStats) {
    AppCard(title = "Throughput") {
        Text(
            text = "%.3f Mbps".format(stats.currentTotalMbps),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        StatusLine("Current TX", "%.3f Mbps".format(stats.currentTxMbps))
        StatusLine("Current RX", "%.3f Mbps".format(stats.currentRxMbps))
        StatusLine("Current Total", "%.3f Mbps".format(stats.currentTotalMbps))
        StatusLine("Average TX", "%.3f Mbps".format(stats.averageTxMbps))
        StatusLine("Average RX", "%.3f Mbps".format(stats.averageRxMbps))
        StatusLine("Average Total", "%.3f Mbps".format(stats.averageTotalMbps))
        StatusLine("TX Bytes", stats.txBytes.toString())
        StatusLine("RX Bytes", stats.rxBytes.toString())
        StatusLine("Total Bytes", stats.totalBytes.toString())
        StatusLine("Elapsed", "%.1f s".format(stats.elapsedMillis / 1_000.0))
    }
}

// ====================================================================
// MTU 信息
// ====================================================================
@Composable
private fun MtuCard(mtu: Int) {
    AppCard(title = "MTU (Maximum Transmission Unit)") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$mtu bytes",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (mtu >= 247) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = when {
                    mtu <= 23 -> "Default ATT MTU only allows 20-byte payloads; throughput results will be severely limited."
                    mtu >= 247 -> "MTU >= 247 can significantly improve throughput."
                    else -> "Requesting MTU 517 is recommended for best performance."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ====================================================================
// Preview
// ====================================================================

@Preview(showBackground = true, name = "BLE Client — Idle")
@Composable
private fun BleTestScreenPreviewClientIdle() {
    BluetoothToolTheme {
        BleTestScreen(
            state = BleUiState(
                bluetoothAvailable = true,
                bluetoothEnabled = true,
                hasBluetoothPermission = true,
                hasLocationPermission = true,
                scannedDevices = listOf(
                    BleDeviceItem("BLE-Sensor-01", "AA:11:22:33:44:55", rssi = -42),
                    BleDeviceItem("HeartRate Monitor", "BB:AA:CC:DD:EE:FF", rssi = -65),
                    BleDeviceItem("Unknown", "CC:00:11:22:33:44", rssi = -78),
                ),
                selectedDevice = BleDeviceItem("BLE-Sensor-01", "AA:11:22:33:44:55", rssi = -42),
                logs = listOf("12:00:01  Ready"),
            ),
            onRequestPermission = {},
            onRequestLocationPermission = {},
            onOpenBluetoothSettings = {},
            onScanDevices = {},
            onStopScan = {},
            onRoleChange = {},
            onTrafficDirectionChange = {},
            onDeviceSelected = {},
            onStart = {},
            onStop = {},
        )
    }
}

@Preview(showBackground = true, name = "BLE Client — Running")
@Composable
private fun BleTestScreenPreviewClientRunning() {
    BluetoothToolTheme {
        BleTestScreen(
            state = BleUiState(
                bluetoothAvailable = true,
                bluetoothEnabled = true,
                hasBluetoothPermission = true,
                hasLocationPermission = true,
                scannedDevices = listOf(BleDeviceItem("BLE-Sensor-01", "AA:11:22:33:44:55", rssi = -42)),
                selectedDevice = BleDeviceItem("BLE-Sensor-01", "AA:11:22:33:44:55", rssi = -42),
                isRunning = true,
                isConnected = true,
                mtu = 517,
                status = "Sending",
                stats = BleThroughputStats(
                    totalBytes = 5_242_880,
                    elapsedMillis = 8_000,
                    intervalBytes = 655_360,
                    intervalMillis = 1_000,
                ),
                logs = listOf("12:00:05  Connected", "12:00:05  MTU=517", "12:00:06  Sending..."),
            ),
            onRequestPermission = {},
            onRequestLocationPermission = {},
            onOpenBluetoothSettings = {},
            onScanDevices = {},
            onStopScan = {},
            onRoleChange = {},
            onTrafficDirectionChange = {},
            onDeviceSelected = {},
            onStart = {},
            onStop = {},
        )
    }
}

@Preview(showBackground = true, name = "BLE Server — Running")
@Composable
private fun BleTestScreenPreviewServerRunning() {
    BluetoothToolTheme {
        BleTestScreen(
            state = BleUiState(
                bluetoothAvailable = true,
                bluetoothEnabled = true,
                hasBluetoothPermission = true,
                hasLocationPermission = true,
                config = com.example.bluetoothtool.model.BleTestConfig(role = TestRole.Server, trafficDirection = TrafficDirection.Rx),
                isRunning = true,
                isConnected = true,
                isAdvertising = true,
                mtu = 517,
                status = "Receiving",
                stats = BleThroughputStats(
                    totalBytes = 3_145_728,
                    elapsedMillis = 5_000,
                    intervalBytes = 629_145,
                    intervalMillis = 1_000,
                ),
                logs = listOf("12:00:03  Advertising", "12:00:06  Connected", "12:00:07  Pushing..."),
            ),
            onRequestPermission = {},
            onRequestLocationPermission = {},
            onOpenBluetoothSettings = {},
            onScanDevices = {},
            onStopScan = {},
            onRoleChange = {},
            onTrafficDirectionChange = {},
            onDeviceSelected = {},
            onStart = {},
            onStop = {},
        )
    }
}
private fun throughputTitle(trafficDirection: TrafficDirection): String {
    return when (trafficDirection) {
        TrafficDirection.Tx -> "TX Throughput"
        TrafficDirection.Rx -> "RX Throughput"
        TrafficDirection.TxRx -> "Throughput"
    }
}
