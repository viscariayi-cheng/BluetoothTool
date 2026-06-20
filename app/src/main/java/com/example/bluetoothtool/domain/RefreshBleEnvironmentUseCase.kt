package com.example.bluetoothtool.domain

import com.example.bluetoothtool.data.bluetooth.BluetoothEnvironment
import com.example.bluetoothtool.data.repository.BleTestRepository

class RefreshBleEnvironmentUseCase(
    private val repository: BleTestRepository,
) {
    operator fun invoke(): BluetoothEnvironment = repository.getEnvironment()
}