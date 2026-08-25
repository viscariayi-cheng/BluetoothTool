package com.example.bluetoothtool.data.repository

import com.example.bluetoothtool.data.bluetooth.BluetoothEnvironment
import com.example.bluetoothtool.model.BluetoothDeviceItem
import com.example.bluetoothtool.model.SppBidirectionalThroughputSample
import com.example.bluetoothtool.model.SppTestConfig
import com.example.bluetoothtool.model.SppThroughputSample
import kotlinx.coroutines.Job

interface SppTestRepository {
    fun getEnvironment(): BluetoothEnvironment

    fun getPairedDevices(): List<BluetoothDeviceItem>

    suspend fun runTest(
        config: SppTestConfig,
        device: BluetoothDeviceItem?,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onStats: (SppThroughputSample) -> Unit,
        onBidirectionalStats: (SppBidirectionalThroughputSample) -> Unit,
    )

    fun close()
}
