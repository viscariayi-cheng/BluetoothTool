package com.example.bluetoothtool

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bluetoothtool.data.bluetooth.BluetoothPermissions
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
                val factory = remember { SppTestViewModelFactory(applicationContext) }
                val viewModel: SppTestViewModel = viewModel(factory = factory)
                val state by viewModel.state.collectAsState()
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    viewModel.refreshPermissionsAndDevices()
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SppTestScreen(
                        state = state,
                        onRequestPermission = {
                            permissionLauncher.launch(BluetoothPermissions.runtimePermissions)
                        },
                        onOpenBluetoothSettings = {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        },
                        onRefreshDevices = viewModel::refreshPermissionsAndDevices,
                        onModeChange = viewModel::setMode,
                        onDeviceSelected = viewModel::selectDevice,
                        onStart = viewModel::start,
                        onStop = viewModel::stop,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
