package com.example.bluetoothtool.domain

import com.example.bluetoothtool.data.bluetooth.BleScannerDataSource
import kotlinx.coroutines.flow.Flow

class ScanBleDevicesUseCase(
    private val scannerDataSource: BleScannerDataSource,
) {
    operator fun invoke(): Flow<BleScannerDataSource.ScannedDevice> = scannerDataSource.scan()
}