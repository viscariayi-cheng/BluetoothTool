package com.example.bluetoothtool.model

data class AppSettings(
    val sppServiceUuid: String = DEFAULT_SPP_SERVICE_UUID,
    val bleServiceUuid: String = DEFAULT_BLE_SERVICE_UUID,
    val bleTxCharacteristicUuid: String = DEFAULT_BLE_TX_CHARACTERISTIC_UUID,
    val bleRxCharacteristicUuid: String = DEFAULT_BLE_RX_CHARACTERISTIC_UUID,
    val showUnnamedBleDevices: Boolean = DEFAULT_SHOW_UNNAMED_BLE_DEVICES,
) {
    companion object {
        const val DEFAULT_SPP_SERVICE_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        const val DEFAULT_BLE_SERVICE_UUID = "01000100-0000-1000-8000-009078563412"
        const val DEFAULT_BLE_TX_CHARACTERISTIC_UUID = "02000200-0000-1000-8000-009178563412"
        const val DEFAULT_BLE_RX_CHARACTERISTIC_UUID = "03000300-0000-1000-8000-009278563412"
        const val DEFAULT_SHOW_UNNAMED_BLE_DEVICES = false
    }
}
