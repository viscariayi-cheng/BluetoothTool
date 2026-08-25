package com.example.bluetoothtool.ui.settings

import androidx.lifecycle.ViewModel
import com.example.bluetoothtool.data.settings.AppSettingsRepository
import com.example.bluetoothtool.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class SettingsViewModel(
    private val repository: AppSettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(repository.getSettings().toUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun updateSppServiceUuid(value: String) {
        _state.update { it.copy(sppServiceUuid = value, message = "", hasError = false) }
    }

    fun updateBleServiceUuid(value: String) {
        _state.update { it.copy(bleServiceUuid = value, message = "", hasError = false) }
    }

    fun updateBleTxCharacteristicUuid(value: String) {
        _state.update { it.copy(bleTxCharacteristicUuid = value, message = "", hasError = false) }
    }

    fun updateBleRxCharacteristicUuid(value: String) {
        _state.update { it.copy(bleRxCharacteristicUuid = value, message = "", hasError = false) }
    }

    fun updateShowUnnamedBleDevices(value: Boolean) {
        _state.update { it.copy(showUnnamedBleDevices = value, message = "", hasError = false) }
    }

    fun save() {
        val current = _state.value
        val normalized = try {
            AppSettings(
                sppServiceUuid = normalizeUuid(current.sppServiceUuid),
                bleServiceUuid = normalizeUuid(current.bleServiceUuid),
                bleTxCharacteristicUuid = normalizeUuid(current.bleTxCharacteristicUuid),
                bleRxCharacteristicUuid = normalizeUuid(current.bleRxCharacteristicUuid),
                showUnnamedBleDevices = current.showUnnamedBleDevices,
            )
        } catch (_: IllegalArgumentException) {
            _state.update {
                it.copy(
                    message = "Invalid UUID. Use canonical 128-bit format, e.g. 0000FFE0-0000-1000-8000-00805F9B34FB.",
                    hasError = true,
                )
            }
            return
        }

        repository.saveSettings(normalized)
        _state.value = normalized.toUiState(message = "Saved. Restart any running test to apply changes.")
    }

    fun resetDefaults() {
        val defaults = repository.resetDefaults()
        _state.value = defaults.toUiState(message = "Defaults restored.")
    }

    private fun normalizeUuid(value: String): String {
        return UUID.fromString(value.trim()).toString().uppercase()
    }

    private fun AppSettings.toUiState(message: String = ""): SettingsUiState {
        return SettingsUiState(
            sppServiceUuid = sppServiceUuid,
            bleServiceUuid = bleServiceUuid,
            bleTxCharacteristicUuid = bleTxCharacteristicUuid,
            bleRxCharacteristicUuid = bleRxCharacteristicUuid,
            showUnnamedBleDevices = showUnnamedBleDevices,
            message = message,
            hasError = false,
        )
    }
}
