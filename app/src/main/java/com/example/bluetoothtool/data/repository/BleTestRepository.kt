package com.example.bluetoothtool.data.repository

import com.example.bluetoothtool.data.bluetooth.BluetoothEnvironment
import com.example.bluetoothtool.model.BleBidirectionalThroughputSample
import com.example.bluetoothtool.model.BleTestConfig
import com.example.bluetoothtool.model.BleThroughputSample
import com.example.bluetoothtool.model.BluetoothDeviceItem
import kotlinx.coroutines.Job

interface BleTestRepository {
    fun getEnvironment(): BluetoothEnvironment

    suspend fun runTest(
        config: BleTestConfig,
        device: BluetoothDeviceItem?,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onMtuChanged: (Int) -> Unit,
        onStats: (BleThroughputSample) -> Unit,
        onBidirectionalStats: (BleBidirectionalThroughputSample) -> Unit,
    )

    fun close()
}
