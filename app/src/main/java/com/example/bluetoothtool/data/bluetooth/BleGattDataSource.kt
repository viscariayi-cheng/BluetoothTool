package com.example.bluetoothtool.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.example.bluetoothtool.model.BluetoothDeviceItem
import kotlinx.coroutines.Job
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BleGattDataSource(
    private val adapterProvider: () -> BluetoothAdapter?,
    private val context: Context,
) {
    companion object {
        val BLE_THROUGHPUT_SERVICE_UUID: UUID =
            UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val BLE_THROUGHPUT_CHAR_WRITE_UUID: UUID =
            UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        val BLE_THROUGHPUT_CHAR_NOTIFY_UUID: UUID =
            UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB")

        private const val TARGET_MTU = 512
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val BUFFER_SIZE = TARGET_MTU - 3 // ATT header = 3 bytes
    }

    private var clientGatt: BluetoothGatt? = null
    private var gattServer: BluetoothGattServer? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    // ---- Client: connect GATT and prepare for writing ----
    data class ClientConnectionResult(
        val success: Boolean,
        val message: String,
        val mtu: Int = 23,
    )

    @SuppressLint("MissingPermission")
    suspend fun connectAndDiscover(deviceItem: BluetoothDeviceItem): ClientConnectionResult {
        val adapter = adapterProvider()
            ?: return ClientConnectionResult(false, "Bluetooth adapter is unavailable.")

        val remoteDevice: BluetoothDevice = adapter.getRemoteDevice(deviceItem.address)

        val latch = CountDownLatch(1)
        var connectError: String? = null
        var discovered = false
        var mtu = 23

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        // Defer discovery until connection is fully established
                    }
                } else {
                    connectError = "GATT disconnected (state=$newState, status=$status)"
                    clientGatt?.close()
                    clientGatt = null
                    latch.countDown()
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectError = "Service discovery failed (status=$status)."
                    latch.countDown()
                    return
                }

                val service = gatt.getService(BLE_THROUGHPUT_SERVICE_UUID)
                if (service == null) {
                    connectError = "Target GATT service not found on remote device."
                    latch.countDown()
                    return
                }

                val charWrite = service.getCharacteristic(BLE_THROUGHPUT_CHAR_WRITE_UUID)
                if (charWrite == null) {
                    connectError = "Write characteristic not found."
                    latch.countDown()
                    return
                }

                writeCharacteristic = charWrite
                writeCharacteristic?.writeType =
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                discovered = true

                // Request MTU after service discovery
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    gatt.requestMtu(TARGET_MTU)
                } else {
                    mtu = 23
                    latch.countDown()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
                mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
                latch.countDown()
            }
        }

        clientGatt?.close()
        clientGatt = remoteDevice.connectGatt(context, false, callback)

        val timedOut = !latch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (timedOut) {
            return ClientConnectionResult(false, "GATT connection timed out.")
        }

        if (connectError != null) {
            return ClientConnectionResult(false, connectError!!)
        }

        if (!discovered) {
            return ClientConnectionResult(false, "Service discovery did not complete.")
        }

        return ClientConnectionResult(true, "Connected & services discovered.", mtu)
    }

    @SuppressLint("MissingPermission")
    fun writePayloadLoop(
        activeJob: () -> Job?,
        onStats: (bytes: Long, elapsedMillis: Long) -> Unit,
    ) {
        val gatt = clientGatt ?: return
        val characteristic = writeCharacteristic ?: return

        val buffer = ByteArray(BUFFER_SIZE) { index -> (index and 0xFF).toByte() }
        val start = System.currentTimeMillis()
        var bytes = 0L
        var nextTick = start

        val statsIntervalMs = 1_000L

        while (activeJob()?.isActive == true) {
            characteristic.value = buffer
            val success = gatt.writeCharacteristic(characteristic)
            if (!success) {
                // BLE stack busy; yield briefly
                Thread.sleep(1)
                continue
            }
            bytes += buffer.size

            val now = System.currentTimeMillis()
            if (now >= nextTick) {
                onStats(bytes, now - start)
                nextTick = now + statsIntervalMs
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun closeClient() {
        val gatt = clientGatt ?: return
        clientGatt = null
        writeCharacteristic = null
        try {
            gatt.disconnect()
            gatt.close()
        } catch (_: Exception) {
        }
    }

    // ---- Server: open GATT server, register service, wait for connection ----

    data class ServerSetupResult(
        val success: Boolean,
        val message: String,
    )

    @SuppressLint("MissingPermission")
    fun setupGattServer(onConnectionAccepted: () -> Unit): ServerSetupResult {
        val bluetoothManager =
            context.getSystemService(BluetoothManager::class.java)
                ?: return ServerSetupResult(false, "BluetoothManager not available.")

        gattServer = bluetoothManager.openGattServer(context, object : BluetoothGattServerCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    onConnectionAccepted()
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                // MTU change handled silently; client updates via notify payload if needed.
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                // Client writes data; we read for throughput measurement.
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                // Notification confirmation; ignored for throughput measurement.
            }
        })
            ?: return ServerSetupResult(false, "Failed to open GATT server.")

        // Build custom throughput service
        val writeChar = BluetoothGattCharacteristic(
            BLE_THROUGHPUT_CHAR_WRITE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val notifyChar = BluetoothGattCharacteristic(
            BLE_THROUGHPUT_CHAR_NOTIFY_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )

        notifyCharacteristic = notifyChar

        val service = BluetoothGattService(
            BLE_THROUGHPUT_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        service.addCharacteristic(writeChar)
        service.addCharacteristic(notifyChar)

        val added = gattServer!!.addService(service)
        if (!added) {
            return ServerSetupResult(false, "Failed to add GATT service.")
        }

        return ServerSetupResult(true, "GATT server ready.")
    }

    @SuppressLint("MissingPermission")
    fun notifyPayloadLoop(
        activeJob: () -> Job?,
        onStats: (bytes: Long, elapsedMillis: Long) -> Unit,
    ) {
        val server = gattServer ?: return
        val characteristic = notifyCharacteristic ?: return

        val buffer = ByteArray(BUFFER_SIZE) { index -> (index and 0xFF).toByte() }
        val start = System.currentTimeMillis()
        var bytes = 0L
        var nextTick = start

        val statsIntervalMs = 1_000L

        while (activeJob()?.isActive == true) {
            characteristic.value = buffer
            server.notifyCharacteristicChanged(
                server.connectedDevices.firstOrNull(),
                characteristic,
                false,
            )
            bytes += buffer.size

            val now = System.currentTimeMillis()
            if (now >= nextTick) {
                onStats(bytes, now - start)
                nextTick = now + statsIntervalMs
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun closeServer() {
        val server = gattServer ?: return
        gattServer = null
        notifyCharacteristic = null
        try {
            server.clearServices()
            server.close()
        } catch (_: Exception) {
        }
    }
}