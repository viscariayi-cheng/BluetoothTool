package com.example.bluetoothtool.ui.spp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.example.bluetoothtool.model.BluetoothDeviceItem
import com.example.bluetoothtool.model.SppBidirectionalThroughputStats
import com.example.bluetoothtool.model.SppThroughputStats
import com.example.bluetoothtool.model.TestRole
import com.example.bluetoothtool.model.TrafficDirection
import com.example.bluetoothtool.ui.common.AppCard
import com.example.bluetoothtool.ui.common.LogsCard
import com.example.bluetoothtool.ui.common.StatusLine
import com.example.bluetoothtool.ui.theme.BluetoothToolTheme

@Composable
fun SppTestScreen(
    state: SppUiState,
    onRequestPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    onRefreshDevices: () -> Unit,
    onRoleChange: (TestRole) -> Unit,
    onTrafficDirectionChange: (TrafficDirection) -> Unit,
    onDeviceSelected: (BluetoothDeviceItem) -> Unit,
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
                onOpenBluetoothSettings = onOpenBluetoothSettings,
                onMakeDiscoverable = onMakeDiscoverable,
                onRefreshDevices = onRefreshDevices,
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
                DeviceCard(
                    devices = state.pairedDevices,
                    selectedDevice = state.selectedDevice,
                    enabled = !state.isRunning,
                    onDeviceSelected = onDeviceSelected,
                )
            }
            ControlCard(
                state = state,
                onStart = onStart,
                onStop = onStop,
            )
            if (state.config.trafficDirection == TrafficDirection.TxRx) {
                BidirectionalStatsCard(stats = state.bidirectionalStats)
            } else {
                SppStatsCard(
                    title = throughputTitle(state.config.trafficDirection),
                    stats = state.stats,
                )
            }
            LogsCard(logs = state.logs)
        }
    }
}

private fun throughputTitle(trafficDirection: TrafficDirection): String {
    return when (trafficDirection) {
        TrafficDirection.Tx -> "TX Throughput"
        TrafficDirection.Rx -> "RX Throughput"
        TrafficDirection.TxRx -> "Throughput"
    }
}

@Composable
private fun HeaderSection() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Bluetooth SPP Throughput",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Measure SPP throughput with independent connection role and traffic direction.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(
    state: SppUiState,
    onRequestPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    onRefreshDevices: () -> Unit,
) {
    AppCard(title = "Environment") {
        StatusLine("Bluetooth adapter", if (state.bluetoothAvailable) "Available" else "Unavailable")
        StatusLine("Bluetooth power", if (state.bluetoothEnabled) "Enabled" else "Disabled")
        StatusLine("Permission", if (state.hasBluetoothPermission) "Granted" else "Required")
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.hasBluetoothPermission) {
                Button(onClick = onRequestPermission) {
                    Text("Grant Permission")
                }
            }
            if (!state.bluetoothEnabled) {
                OutlinedButton(onClick = onOpenBluetoothSettings) {
                    Text("Bluetooth Settings")
                }
            }
            if (state.bluetoothEnabled && state.hasBluetoothPermission) {
                OutlinedButton(onClick = onMakeDiscoverable) {
                    Text("Make Discoverable")
                }
            }
            OutlinedButton(onClick = onRefreshDevices) {
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun RoleCard(
    selectedRole: TestRole,
    enabled: Boolean,
    onRoleChange: (TestRole) -> Unit,
) {
    AppCard(title = "Connection") {
        ModeRow(
            title = "Client",
            description = "Android connects to the selected paired device.",
            selected = selectedRole == TestRole.Client,
            enabled = enabled,
            onClick = { onRoleChange(TestRole.Client) },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        ModeRow(
            title = "Server",
            description = "Android waits for an incoming SPP connection.",
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
            description = "Android sends payload continuously.",
            selected = selectedTrafficDirection == TrafficDirection.Tx,
            enabled = enabled,
            onClick = { onTrafficDirectionChange(TrafficDirection.Tx) },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        ModeRow(
            title = "RX",
            description = "Android receives payload and measures throughput.",
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
private fun SppStatsCard(
    title: String,
    stats: SppThroughputStats,
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
private fun BidirectionalStatsCard(stats: SppBidirectionalThroughputStats) {
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

@Composable
private fun DeviceCard(
    devices: List<BluetoothDeviceItem>,
    selectedDevice: BluetoothDeviceItem?,
    enabled: Boolean,
    onDeviceSelected: (BluetoothDeviceItem) -> Unit,
) {
    AppCard(title = "Paired Devices") {
        if (devices.isEmpty()) {
            Text(
                text = "No paired devices. Pair the target device in system Bluetooth settings first.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppCard
        }

        LazyColumn(
            modifier = Modifier.height(220.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(devices, key = { it.address }) { device ->
                DeviceRow(
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
private fun DeviceRow(
    device: BluetoothDeviceItem,
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
                text = device.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ControlCard(
    state: SppUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    AppCard(title = "Control") {
        StatusLine("Connection", if (state.isConnected) "Connected" else "Disconnected")
        StatusLine("State", state.status)
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onStart,
                enabled = !state.isRunning &&
                    state.hasBluetoothPermission &&
                    state.bluetoothAvailable &&
                    state.bluetoothEnabled &&
                    (!state.needsSelectedDevice || state.selectedDevice != null),
            ) {
                Text("Start")
            }
            OutlinedButton(
                onClick = onStop,
                enabled = state.isRunning,
            ) {
                Text("Stop")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SppTestScreenPreview() {
    BluetoothToolTheme {
        SppTestScreen(
            state = SppUiState(
                bluetoothAvailable = true,
                bluetoothEnabled = true,
                hasBluetoothPermission = true,
                pairedDevices = listOf(
                    BluetoothDeviceItem("HC-05", "00:11:22:33:44:55"),
                    BluetoothDeviceItem("Test Phone", "AA:BB:CC:DD:EE:FF"),
                ),
                selectedDevice = BluetoothDeviceItem("HC-05", "00:11:22:33:44:55"),
                stats = SppThroughputStats(
                    totalBytes = 2_048_000,
                    elapsedMillis = 5_000,
                    intervalBytes = 410_000,
                    intervalMillis = 1_000,
                ),
                logs = listOf("20:00:00  Ready"),
            ),
            onRequestPermission = {},
            onOpenBluetoothSettings = {},
            onMakeDiscoverable = {},
            onRefreshDevices = {},
            onRoleChange = {},
            onTrafficDirectionChange = {},
            onDeviceSelected = {},
            onStart = {},
            onStop = {},
        )
    }
}
