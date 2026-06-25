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
import java.util.concurrent.atomic.AtomicLong

class BleGattDataSource(
    private val adapterProvider: () -> BluetoothAdapter?,
    private val context: Context,
) {
    companion object {
        val BLE_THROUGHPUT_SERVICE_UUID: UUID =
            UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val BLE_THROUGHPUT_CHAR_WRITE_UUID: UUID =
            UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")

        private const val TARGET_MTU = 512
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val ADD_SERVICE_TIMEOUT_SECONDS = 5L
        private const val STATS_INTERVAL_MS = 1_000L
    }

    private var clientGatt: BluetoothGatt? = null
    private var gattServer: BluetoothGattServer? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedServerDevice: BluetoothDevice? = null
    private var negotiatedMtu: Int = 23
    private val serverReceivedBytes = AtomicLong(0L)

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
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectError = "GATT connection failed (state=$newState, status=$status)."
                    latch.countDown()
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (!gatt.discoverServices()) {
                            connectError = "Failed to start GATT service discovery."
                            latch.countDown()
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connectError = "GATT disconnected."
                        latch.countDown()
                    }
                }
            }

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

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    if (!gatt.requestMtu(TARGET_MTU)) {
                        negotiatedMtu = mtu
                        latch.countDown()
                    }
                } else {
                    negotiatedMtu = mtu
                    latch.countDown()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
                mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
                negotiatedMtu = mtu
                latch.countDown()
            }
        }

        closeClient()
        clientGatt = remoteDevice.connectGatt(context, false, callback)

        val timedOut = !latch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (timedOut) {
            closeClient()
            return ClientConnectionResult(false, "GATT connection timed out.")
        }
        if (connectError != null) {
            closeClient()
            return ClientConnectionResult(false, connectError!!)
        }
        if (!discovered) {
            closeClient()
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
        val buffer = ByteArray(payloadSizeForMtu(negotiatedMtu)) { index -> (index and 0xFF).toByte() }
        val start = System.currentTimeMillis()
        var bytes = 0L
        var nextTick = start

        while (activeJob()?.isActive == true) {
            characteristic.value = buffer
            val success = gatt.writeCharacteristic(characteristic)
            if (!success) {
                Thread.sleep(1)
                continue
            }
            bytes += buffer.size

            val now = System.currentTimeMillis()
            if (now >= nextTick) {
                onStats(bytes, now - start)
                nextTick = now + STATS_INTERVAL_MS
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

    data class ServerSetupResult(
        val success: Boolean,
        val message: String,
    )

    @SuppressLint("MissingPermission")
    fun setupGattServer(
        onConnectionAccepted: () -> Unit,
        onMtuChanged: (Int) -> Unit,
    ): ServerSetupResult {
        val bluetoothManager =
            context.getSystemService(BluetoothManager::class.java)
                ?: return ServerSetupResult(false, "BluetoothManager not available.")

        closeServer()
        connectedServerDevice = null
        negotiatedMtu = 23
        serverReceivedBytes.set(0L)
        val serviceAddedLatch = CountDownLatch(1)
        var serviceAddedError: String? = null

        gattServer = bluetoothManager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (service.uuid == BLE_THROUGHPUT_SERVICE_UUID && status != BluetoothGatt.GATT_SUCCESS) {
                    serviceAddedError = "GATT service add failed (status=$status)."
                }
                if (service.uuid == BLE_THROUGHPUT_SERVICE_UUID) {
                    serviceAddedLatch.countDown()
                }
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedServerDevice = device
                        onConnectionAccepted()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (connectedServerDevice?.address == device.address) {
                            connectedServerDevice = null
                        }
                    }
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                negotiatedMtu = mtu
                onMtuChanged(mtu)
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
                if (characteristic.uuid == BLE_THROUGHPUT_CHAR_WRITE_UUID && !preparedWrite) {
                    serverReceivedBytes.addAndGet(value.size.toLong())
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        })
            ?: return ServerSetupResult(false, "Failed to open GATT server.")

        val writeChar = BluetoothGattCharacteristic(
            BLE_THROUGHPUT_CHAR_WRITE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val service = BluetoothGattService(
            BLE_THROUGHPUT_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        service.addCharacteristic(writeChar)

        val added = gattServer?.addService(service) == true
        if (!added) {
            closeServer()
            return ServerSetupResult(false, "Failed to add GATT service.")
        }
        if (!serviceAddedLatch.await(ADD_SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            closeServer()
            return ServerSetupResult(false, "Timed out while adding GATT service.")
        }
        if (serviceAddedError != null) {
            closeServer()
            return ServerSetupResult(false, serviceAddedError!!)
        }

        return ServerSetupResult(true, "GATT server ready.")
    }

    fun receivePayloadLoop(
        activeJob: () -> Job?,
        onStats: (bytes: Long, elapsedMillis: Long) -> Unit,
    ) {
        if (gattServer == null) return

        val start = System.currentTimeMillis()
        var nextTick = start

        while (activeJob()?.isActive == true) {
            val now = System.currentTimeMillis()
            if (now >= nextTick) {
                onStats(serverReceivedBytes.get(), now - start)
                nextTick = now + STATS_INTERVAL_MS
            }
            Thread.sleep(20)
        }
    }

    @SuppressLint("MissingPermission")
    fun closeServer() {
        val server = gattServer ?: return
        gattServer = null
        connectedServerDevice = null
        try {
            server.clearServices()
            server.close()
        } catch (_: Exception) {
        }
    }

    private fun payloadSizeForMtu(mtu: Int): Int {
        return (mtu - 3).coerceIn(20, TARGET_MTU - 3)
    }
}
