package com.example.bluetoothtool.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

class BluetoothDiscoverabilityDataSource(
    private val adapterProvider: () -> BluetoothAdapter?,
) {
    private var previousScanMode: Int? = null

    @SuppressLint("MissingPermission")
    fun makeConnectableDiscoverable(): ScanModeUpdateResult {
        val adapter = adapterProvider()
            ?: return ScanModeUpdateResult(success = false, message = "Bluetooth adapter is unavailable.")

        val targetMode = BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
        val currentMode = adapter.scanMode
        if (currentMode == targetMode) {
            return ScanModeUpdateResult(success = true, message = "Bluetooth scan mode is already connectable discoverable.")
        }

        if (previousScanMode == null) {
            previousScanMode = currentMode
        }

        return setScanMode(adapter, targetMode)
    }

    fun restorePreviousScanMode() {
        val adapter = adapterProvider() ?: return
        val mode = previousScanMode ?: return
        setScanMode(adapter, mode)
        previousScanMode = null
    }

    private fun setScanMode(adapter: BluetoothAdapter, mode: Int): ScanModeUpdateResult {
        return try {
            val method = findSetScanModeMethod(adapter)
                ?: return ScanModeUpdateResult(
                    success = false,
                    message = "BluetoothAdapter.setScanMode is not available from this runtime.",
                )

            val result = if (method.parameterTypes.size == 2) {
                method.invoke(adapter, mode, DISCOVERABLE_DURATION_SECONDS)
            } else {
                method.invoke(adapter, mode)
            }
            val success = result as? Boolean ?: true
            ScanModeUpdateResult(
                success = success,
                message = if (success) {
                    "Requested connectable discoverable scan mode."
                } else {
                    "BluetoothAdapter.setScanMode returned false."
                },
            )
        } catch (error: InvocationTargetException) {
            val cause = error.targetException ?: error
            ScanModeUpdateResult(
                success = false,
                message = "Failed to set Bluetooth scan mode: ${cause.message ?: cause.javaClass.simpleName}",
            )
        } catch (error: ReflectiveOperationException) {
            ScanModeUpdateResult(
                success = false,
                message = "Failed to access BluetoothAdapter.setScanMode: ${error.message ?: error.javaClass.simpleName}",
            )
        } catch (error: SecurityException) {
            ScanModeUpdateResult(
                success = false,
                message = "Missing privileged permission to set Bluetooth scan mode: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun findSetScanModeMethod(adapter: BluetoothAdapter): Method? {
        val adapterClass = adapter.javaClass
        return runCatching {
            adapterClass.getMethod(
                SET_SCAN_MODE_METHOD,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull()
            ?: runCatching {
                adapterClass.getMethod(SET_SCAN_MODE_METHOD, Int::class.javaPrimitiveType)
            }.getOrNull()
    }

    companion object {
        private const val SET_SCAN_MODE_METHOD = "setScanMode"
        private const val DISCOVERABLE_DURATION_SECONDS = 0
    }
}
