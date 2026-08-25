package com.example.bluetoothtool.data.settings

import android.content.Context
import com.example.bluetoothtool.model.AppSettings
import java.util.UUID

class AppSettingsRepository(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getSettings(): AppSettings {
        return AppSettings(
            sppServiceUuid = getUuid(KEY_SPP_SERVICE_UUID, AppSettings.DEFAULT_SPP_SERVICE_UUID),
            bleServiceUuid = getUuid(KEY_BLE_SERVICE_UUID, AppSettings.DEFAULT_BLE_SERVICE_UUID),
            bleTxCharacteristicUuid = getUuid(
                KEY_BLE_TX_CHARACTERISTIC_UUID,
                AppSettings.DEFAULT_BLE_TX_CHARACTERISTIC_UUID,
            ),
            bleRxCharacteristicUuid = getUuid(
                KEY_BLE_RX_CHARACTERISTIC_UUID,
                AppSettings.DEFAULT_BLE_RX_CHARACTERISTIC_UUID,
                KEY_LEGACY_BLE_WRITE_CHARACTERISTIC_UUID,
            ),
            showUnnamedBleDevices = preferences.getBoolean(
                KEY_SHOW_UNNAMED_BLE_DEVICES,
                AppSettings.DEFAULT_SHOW_UNNAMED_BLE_DEVICES,
            ),
        )
    }

    fun saveSettings(settings: AppSettings) {
        preferences.edit()
            .putString(KEY_SPP_SERVICE_UUID, settings.sppServiceUuid)
            .putString(KEY_BLE_SERVICE_UUID, settings.bleServiceUuid)
            .putString(KEY_BLE_TX_CHARACTERISTIC_UUID, settings.bleTxCharacteristicUuid)
            .putString(KEY_BLE_RX_CHARACTERISTIC_UUID, settings.bleRxCharacteristicUuid)
            .remove(KEY_LEGACY_BLE_WRITE_CHARACTERISTIC_UUID)
            .putBoolean(KEY_SHOW_UNNAMED_BLE_DEVICES, settings.showUnnamedBleDevices)
            .apply()
    }

    fun resetDefaults(): AppSettings {
        val defaults = AppSettings()
        saveSettings(defaults)
        return defaults
    }

    private fun getUuid(key: String, defaultValue: String, fallbackKey: String? = null): String {
        val value = preferences.getString(key, null)
            ?: fallbackKey?.let { preferences.getString(it, null) }
            ?: return defaultValue
        return try {
            UUID.fromString(value).toString().uppercase()
        } catch (_: IllegalArgumentException) {
            defaultValue
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "bluetooth_tool_settings"
        private const val KEY_SPP_SERVICE_UUID = "spp_service_uuid"
        private const val KEY_BLE_SERVICE_UUID = "ble_service_uuid"
        private const val KEY_BLE_TX_CHARACTERISTIC_UUID = "ble_tx_characteristic_uuid"
        private const val KEY_BLE_RX_CHARACTERISTIC_UUID = "ble_rx_characteristic_uuid"
        private const val KEY_LEGACY_BLE_WRITE_CHARACTERISTIC_UUID = "ble_write_characteristic_uuid"
        private const val KEY_SHOW_UNNAMED_BLE_DEVICES = "show_unnamed_ble_devices"
    }
}
