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
import com.example.bluetoothtool.model.TestMode
import com.example.bluetoothtool.model.ThroughputStats
import com.example.bluetoothtool.ui.common.AppCard
import com.example.bluetoothtool.ui.common.LogsCard
import com.example.bluetoothtool.ui.common.StatsCard
import com.example.bluetoothtool.ui.common.StatusLine
import com.example.bluetoothtool.ui.theme.BluetoothToolTheme

@Composable
fun SppTestScreen(
    state: SppUiState,
    onRequestPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onRefreshDevices: () -> Unit,
    onModeChange: (TestMode) -> Unit,
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
                onRefreshDevices = onRefreshDevices,
            )
            ModeCard(
                selectedMode = state.mode,
                enabled = !state.isRunning,
                onModeChange = onModeChange,
            )
            if (state.mode == TestMode.SppClientSend) {
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
            StatsCard(stats = state.stats)
            LogsCard(logs = state.logs)
        }
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
            text = "Client sends fixed RFCOMM payloads. Server receives and calculates real-time Mbps.",
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
            OutlinedButton(onClick = onRefreshDevices) {
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun ModeCard(
    selectedMode: TestMode,
    enabled: Boolean,
    onModeChange: (TestMode) -> Unit,
) {
    AppCard(title = "Mode") {
        ModeRow(
            title = "Client Send",
            description = "Connect to another device and continuously write payload.",
            selected = selectedMode == TestMode.SppClientSend,
            enabled = enabled,
            onClick = { onModeChange(TestMode.SppClientSend) },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        ModeRow(
            title = "Server Receive",
            description = "Listen for incoming SPP connection and count received bytes.",
            selected = selectedMode == TestMode.SppServerReceive,
            enabled = enabled,
            onClick = { onModeChange(TestMode.SppServerReceive) },
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
                enabled = !state.isRunning && state.hasBluetoothPermission && state.bluetoothAvailable && state.bluetoothEnabled,
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
                stats = ThroughputStats(bytes = 2_048_000, elapsedMillis = 5_000),
                logs = listOf("20:00:00  Ready"),
            ),
            onRequestPermission = {},
            onOpenBluetoothSettings = {},
            onRefreshDevices = {},
            onModeChange = {},
            onDeviceSelected = {},
            onStart = {},
            onStop = {},
        )
    }
}
