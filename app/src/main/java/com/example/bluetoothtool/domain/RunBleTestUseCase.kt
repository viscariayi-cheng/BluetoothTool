package com.example.bluetoothtool.domain

import com.example.bluetoothtool.data.repository.BleTestRepository
import com.example.bluetoothtool.model.BluetoothDeviceItem
import com.example.bluetoothtool.model.TestMode
import kotlinx.coroutines.Job

class RunBleTestUseCase(
    private val repository: BleTestRepository,
) {
    suspend operator fun invoke(
        mode: TestMode,
        device: BluetoothDeviceItem?,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onMtuChanged: (Int) -> Unit,
        onStats: (bytes: Long, elapsedMillis: Long) -> Unit,
    ) {
        repository.runTest(
            mode = mode,
            device = device,
            activeJob = activeJob,
            onLog = onLog,
            onStatus = onStatus,
            onConnected = onConnected,
            onMtuChanged = onMtuChanged,
            onStats = onStats,
        )
    }
}