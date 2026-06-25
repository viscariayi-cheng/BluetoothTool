package com.example.bluetoothtool.domain

import com.example.bluetoothtool.data.bluetooth.BluetoothEnvironment
import com.example.bluetoothtool.data.repository.SppTestRepository

class RefreshSppEnvironmentUseCase(
    private val repository: SppTestRepository,
) {
    operator fun invoke(): BluetoothEnvironment = repository.getEnvironment()
}
