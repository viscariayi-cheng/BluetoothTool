package com.example.bluetoothtool.ui.ble

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.bluetoothtool.data.repository.AndroidBleTestRepository
import com.example.bluetoothtool.data.settings.AppSettingsRepository
import com.example.bluetoothtool.domain.RefreshBleEnvironmentUseCase
import com.example.bluetoothtool.domain.RunBleTestUseCase
import com.example.bluetoothtool.domain.ScanBleDevicesUseCase
import com.example.bluetoothtool.domain.StopBleTestUseCase
import java.util.UUID

class BleTestViewModelFactory(
    context: Context,
) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(BleTestViewModel::class.java)) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }

        val repository = AndroidBleTestRepository(appContext)
        val settingsRepository = AppSettingsRepository(appContext)
        return BleTestViewModel(
            appContext = appContext,
            refreshBleEnvironment = RefreshBleEnvironmentUseCase(repository),
            scanBleDevices = ScanBleDevicesUseCase(
                com.example.bluetoothtool.data.bluetooth.BleScannerDataSource(
                    adapterProvider = {
                        val manager = appContext.getSystemService(
                            android.bluetooth.BluetoothManager::class.java,
                        )
                        manager?.adapter
                    },
                    serviceUuidProvider = {
                        UUID.fromString(settingsRepository.getSettings().bleServiceUuid)
                    },
                ),
            ),
            runBleTest = RunBleTestUseCase(repository),
            stopBleTest = StopBleTestUseCase(repository),
            settingsRepository = settingsRepository,
        ) as T
    }
}
