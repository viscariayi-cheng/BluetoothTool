package com.example.bluetoothtool.domain

import com.example.bluetoothtool.data.repository.SppTestRepository
import com.example.bluetoothtool.model.BluetoothDeviceItem

class GetPairedDevicesUseCase(
    private val repository: SppTestRepository,
) {
    operator fun invoke(): List<BluetoothDeviceItem> = repository.getPairedDevices()
}
