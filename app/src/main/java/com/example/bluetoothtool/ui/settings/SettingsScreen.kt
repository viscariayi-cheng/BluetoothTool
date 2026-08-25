package com.example.bluetoothtool.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.bluetoothtool.model.AppSettings
import com.example.bluetoothtool.ui.common.AppCard
import com.example.bluetoothtool.ui.theme.BluetoothToolTheme

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSppServiceUuidChange: (String) -> Unit,
    onBleServiceUuidChange: (String) -> Unit,
    onBleTxCharacteristicUuidChange: (String) -> Unit,
    onBleRxCharacteristicUuidChange: (String) -> Unit,
    onShowUnnamedBleDevicesChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onResetDefaults: () -> Unit,
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
            UuidSettingsCard(
                state = state,
                onSppServiceUuidChange = onSppServiceUuidChange,
                onBleServiceUuidChange = onBleServiceUuidChange,
                onBleTxCharacteristicUuidChange = onBleTxCharacteristicUuidChange,
                onBleRxCharacteristicUuidChange = onBleRxCharacteristicUuidChange,
            )
            ScanSettingsCard(
                state = state,
                onShowUnnamedBleDevicesChange = onShowUnnamedBleDevicesChange,
            )
            ActionCard(
                state = state,
                onSave = onSave,
                onResetDefaults = onResetDefaults,
            )
        }
    }
}

@Composable
private fun ScanSettingsCard(
    state: SettingsUiState,
    onShowUnnamedBleDevicesChange: (Boolean) -> Unit,
) {
    AppCard(title = "BLE Scan") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Show unnamed devices", fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Turn this off to reduce scan noise and keep named peripherals easier to find.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.showUnnamedBleDevices,
                onCheckedChange = onShowUnnamedBleDevicesChange,
            )
        }
    }
}

@Composable
private fun HeaderSection() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Configure protocol UUIDs used by Android, NuttX, Linux, or other test peers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UuidSettingsCard(
    state: SettingsUiState,
    onSppServiceUuidChange: (String) -> Unit,
    onBleServiceUuidChange: (String) -> Unit,
    onBleTxCharacteristicUuidChange: (String) -> Unit,
    onBleRxCharacteristicUuidChange: (String) -> Unit,
) {
    AppCard(title = "UUIDs") {
        UuidField(
            label = "SPP service UUID",
            value = state.sppServiceUuid,
            onValueChange = onSppServiceUuidChange,
        )
        UuidField(
            label = "BLE GATT service UUID",
            value = state.bleServiceUuid,
            onValueChange = onBleServiceUuidChange,
        )
        UuidField(
            label = "BLE TX characteristic UUID",
            value = state.bleTxCharacteristicUuid,
            onValueChange = onBleTxCharacteristicUuidChange,
        )
        UuidField(
            label = "BLE RX characteristic UUID",
            value = state.bleRxCharacteristicUuid,
            onValueChange = onBleRxCharacteristicUuidChange,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "BLE TX is the server notify/indicate characteristic. BLE RX is the server write command/request characteristic.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UuidField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
    )
}

@Composable
private fun ActionCard(
    state: SettingsUiState,
    onSave: () -> Unit,
    onResetDefaults: () -> Unit,
) {
    AppCard(title = "Actions") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSave) { Text("Save") }
            OutlinedButton(onClick = onResetDefaults) { Text("Reset Defaults") }
        }
        if (state.message.isNotBlank()) {
            Text(
                text = state.message,
                color = if (state.hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    BluetoothToolTheme {
        SettingsScreen(
            state = SettingsUiState(
                sppServiceUuid = AppSettings.DEFAULT_SPP_SERVICE_UUID,
                bleServiceUuid = AppSettings.DEFAULT_BLE_SERVICE_UUID,
                bleTxCharacteristicUuid = AppSettings.DEFAULT_BLE_TX_CHARACTERISTIC_UUID,
                bleRxCharacteristicUuid = AppSettings.DEFAULT_BLE_RX_CHARACTERISTIC_UUID,
                showUnnamedBleDevices = AppSettings.DEFAULT_SHOW_UNNAMED_BLE_DEVICES,
            ),
            onSppServiceUuidChange = {},
            onBleServiceUuidChange = {},
            onBleTxCharacteristicUuidChange = {},
            onBleRxCharacteristicUuidChange = {},
            onShowUnnamedBleDevicesChange = {},
            onSave = {},
            onResetDefaults = {},
        )
    }
}
