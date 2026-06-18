package com.example.bluetoothtool.domain

import com.example.bluetoothtool.data.repository.SppTestRepository
import com.example.bluetoothtool.model.BluetoothDeviceItem
import com.example.bluetoothtool.model.TestMode
import kotlinx.coroutines.Job

class RunSppTestUseCase(
    private val repository: SppTestRepository,
) {
    suspend operator fun invoke(
        mode: TestMode,
        device: BluetoothDeviceItem?,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onStats: (bytes: Long, elapsedMillis: Long) -> Unit,
    ) {
        repository.runTest(
            mode = mode,
            device = device,
            activeJob = activeJob,
            onLog = onLog,
            onStatus = onStatus,
            onConnected = onConnected,
            onStats = onStats,
        )
    }
}
