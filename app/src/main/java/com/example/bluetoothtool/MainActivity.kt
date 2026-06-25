package com.example.bluetoothtool

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bluetoothtool.data.bluetooth.BluetoothPermissions
import com.example.bluetoothtool.ui.ble.BleTestScreen
import com.example.bluetoothtool.ui.ble.BleTestViewModel
import com.example.bluetoothtool.ui.ble.BleTestViewModelFactory
import com.example.bluetoothtool.ui.spp.SppTestScreen
import com.example.bluetoothtool.ui.spp.SppTestViewModel
import com.example.bluetoothtool.ui.spp.SppTestViewModelFactory
import com.example.bluetoothtool.ui.theme.BluetoothToolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BluetoothToolTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("SPP") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("BLE") },
                )
            }

            when (selectedTab) {
                0 -> SppTab()
                1 -> BleTab()
            }
        }
    }
}

@Composable
private fun SppTab() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val factory = remember(context) { SppTestViewModelFactory(context) }
    val viewModel: SppTestViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshPermissionsAndDevices()
    }

    SppTestScreen(
        state = state,
        onRequestPermission = {
            permissionLauncher.launch(BluetoothPermissions.runtimePermissions)
        },
        onOpenBluetoothSettings = {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        },
        onRefreshDevices = viewModel::refreshPermissionsAndDevices,
        onModeChange = viewModel::setMode,
        onDeviceSelected = viewModel::selectDevice,
        onStart = viewModel::start,
        onStop = viewModel::stop,
        modifier = Modifier,
    )
}

@Composable
private fun BleTab() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val factory = remember { BleTestViewModelFactory(context) }
    val viewModel: BleTestViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshPermissionsAndDevices()
    }

    BleTestScreen(
        state = state,
        onRequestPermission = {
            permissionLauncher.launch(BluetoothPermissions.bleRuntimePermissions)
        },
        onRequestLocationPermission = {
            permissionLauncher.launch(BluetoothPermissions.bleLocationPermissions)
        },
        onOpenBluetoothSettings = {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        },
        onScanDevices = viewModel::scanDevices,
        onStopScan = viewModel::stopScan,
        onModeChange = viewModel::setMode,
        onDeviceSelected = viewModel::selectDevice,
        onStart = viewModel::start,
        onStop = viewModel::stop,
        modifier = Modifier,
    )
}
