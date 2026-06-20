package com.example.bluetoothtool.data.repository

import com.example.bluetoothtool.data.bluetooth.BluetoothEnvironment
import com.example.bluetoothtool.model.BluetoothDeviceItem
import com.example.bluetoothtool.model.TestMode
import kotlinx.coroutines.Job

interface BleTestRepository {
    fun getEnvironment(): BluetoothEnvironment

    suspend fun runTest(
        mode: TestMode,
        device: BluetoothDeviceItem?,
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        onConnected: (String) -> Unit,
        onMtuChanged: (Int) -> Unit,
        onStats: (bytes: Long, elapsedMillis: Long) -> Unit,
    )

    fun close()
}