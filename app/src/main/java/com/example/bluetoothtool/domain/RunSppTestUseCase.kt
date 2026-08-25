package com.example.bluetoothtool.domain

import com.example.bluetoothtool.data.repository.SppTestRepository
import com.example.bluetoothtool.model.BluetoothDeviceItem
import com.example.bluetoothtool.model.SppBidirectionalThroughputSample
import com.example.bluetoothtool.model.SppTestConfig
import com.example.bluetoothtool.model.SppThroughputSample
import kotlinx.coroutines.Job

class RunSppTestUseCase(
    private val repository: SppTestRepository,
) {
    suspend operator fun invoke(
        config: SppTestConfig,
        device: BluetoothDeviceItem?,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onStats: (SppThroughputSample) -> Unit,
        onBidirectionalStats: (SppBidirectionalThroughputSample) -> Unit,
    ) {
        repository.runTest(
            config = config,
            device = device,
            activeJob = activeJob,
            onLog = onLog,
            onStatus = onStatus,
            onConnected = onConnected,
            onStats = onStats,
            onBidirectionalStats = onBidirectionalStats,
        )
    }
}
