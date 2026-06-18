package com.example.bluetoothtool.domain

import com.example.bluetoothtool.data.repository.SppTestRepository

class StopSppTestUseCase(
    private val repository: SppTestRepository,
) {
    operator fun invoke() {
        repository.close()
    }
}
