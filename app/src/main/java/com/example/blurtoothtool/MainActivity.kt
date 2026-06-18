package com.example.blurtoothtool

import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.blurtoothtool.bluetooth.BluetoothDeviceItem
import com.example.blurtoothtool.bluetooth.BluetoothPermissions
import com.example.blurtoothtool.bluetooth.SppTestController
import com.example.blurtoothtool.bluetooth.SppUiState
import com.example.blurtoothtool.bluetooth.TestMode
import com.example.blurtoothtool.bluetooth.ThroughputStats
import com.example.blurtoothtool.ui.theme.BLurtoothToolTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BLurtoothToolTheme {
                val context = LocalContext.current
                val controller = remember { SppTestController(context) }
                val state by controller.state.collectAsState()
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    controller.refreshPermissionsAndDevices()
                }

                DisposableEffect(controller) {
                    onDispose { controller.stop() }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SppTestScreen(
                        state = state,
                        onRequestPermission = {
                            permissionLauncher.launch(BluetoothPermissions.runtimePermissions)
                        },
                        onOpenBluetoothSettings = {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        },
                        onRefreshDevices = controller::refreshPermissionsAndDevices,
                        onModeChange = controller::setMode,
                        onDeviceSelected = controller::selectDevice,
                        onStart = controller::start,
                        onStop = controller::stop,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun SppTestScreen(
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
            if (state.mode == TestMode.ClientSend) {
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
            selected = selectedMode == TestMode.ClientSend,
            enabled = enabled,
            onClick = { onModeChange(TestMode.ClientSend) },
        )
        Divider(modifier = Modifier.padding(vertical = 8.dp))
        ModeRow(
            title = "Server Receive",
            description = "Listen for incoming SPP connection and count received bytes.",
            selected = selectedMode == TestMode.ServerReceive,
            enabled = enabled,
            onClick = { onModeChange(TestMode.ServerReceive) },
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

@Composable
private fun StatsCard(stats: ThroughputStats) {
    AppCard(title = "Throughput") {
        Text(
            text = String.format(Locale.US, "%.3f Mbps", stats.mbps),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        StatusLine("Bytes", stats.bytes.toString())
        StatusLine("Elapsed", String.format(Locale.US, "%.1f s", stats.elapsedMillis / 1_000.0))
    }
}

@Composable
private fun LogsCard(logs: List<String>) {
    AppCard(title = "Logs") {
        if (logs.isEmpty()) {
            Text(
                text = "No logs yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                logs.takeLast(16).forEach { line ->
                    Text(text = line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AppCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, fontWeight = FontWeight.SemiBold)
    }
}

@Preview(showBackground = true)
@Composable
private fun SppTestScreenPreview() {
    BLurtoothToolTheme {
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
