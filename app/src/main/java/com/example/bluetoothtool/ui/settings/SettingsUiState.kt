package com.example.bluetoothtool.ui.settings

data class SettingsUiState(
    val sppServiceUuid: String = "",
    val bleServiceUuid: String = "",
    val bleTxCharacteristicUuid: String = "",
    val bleRxCharacteristicUuid: String = "",
    val showUnnamedBleDevices: Boolean = false,
    val message: String = "",
    val hasError: Boolean = false,
)
