package com.example.bluetoothtool.domain

import com.example.bluetoothtool.data.repository.BleTestRepository

class StopBleTestUseCase(
    private val repository: BleTestRepository,
) {
    operator fun invoke() {
        repository.close()
    }
}