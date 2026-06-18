package com.example.bluetoothtool.ui.spp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.bluetoothtool.data.repository.AndroidSppTestRepository
import com.example.bluetoothtool.domain.GetPairedDevicesUseCase
import com.example.bluetoothtool.domain.RefreshBluetoothEnvironmentUseCase
import com.example.bluetoothtool.domain.RunSppTestUseCase
import com.example.bluetoothtool.domain.StopSppTestUseCase

class SppTestViewModelFactory(
    context: Context,
) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(SppTestViewModel::class.java)) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }

        val repository = AndroidSppTestRepository(appContext)
        return SppTestViewModel(
            refreshBluetoothEnvironment = RefreshBluetoothEnvironmentUseCase(repository),
            getPairedDevices = GetPairedDevicesUseCase(repository),
            runSppTest = RunSppTestUseCase(repository),
            stopSppTest = StopSppTestUseCase(repository),
        ) as T
    }
}
