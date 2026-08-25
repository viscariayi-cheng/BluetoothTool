package com.example.bluetoothtool.domain

import com.example.bluetoothtool.data.repository.BleTestRepository
import com.example.bluetoothtool.model.BleBidirectionalThroughputSample
import com.example.bluetoothtool.model.BleTestConfig
import com.example.bluetoothtool.model.BleThroughputSample
import com.example.bluetoothtool.model.BluetoothDeviceItem
import kotlinx.coroutines.Job

class RunBleTestUseCase(
    private val repository: BleTestRepository,
) {
    suspend operator fun invoke(
        config: BleTestConfig,
        device: BluetoothDeviceItem?,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onMtuChanged: (Int) -> Unit,
        onStats: (BleThroughputSample) -> Unit,
        onBidirectionalStats: (BleBidirectionalThroughputSample) -> Unit,
    ) {
        repository.runTest(
            config = config,
            device = device,
            activeJob = activeJob,
            onLog = onLog,
            onStatus = onStatus,
            onConnected = onConnected,
            onMtuChanged = onMtuChanged,
            onStats = onStats,
            onBidirectionalStats = onBidirectionalStats,
        )
    }
}
