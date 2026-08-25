package com.example.bluetoothtool.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.example.bluetoothtool.model.BleBidirectionalThroughputSample
import com.example.bluetoothtool.model.BleThroughputSample
import com.example.bluetoothtool.model.BluetoothDeviceItem
import kotlinx.coroutines.Job
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class BleGattDataSource(
    private val adapterProvider: () -> BluetoothAdapter?,
    private val context: Context,
    private val serviceUuidProvider: () -> UUID,
    private val txCharacteristicUuidProvider: () -> UUID,
    private val rxCharacteristicUuidProvider: () -> UUID,
) {
    companion object {
        const val DEFAULT_ATT_MTU = 23
        const val TARGET_MTU = 517
        const val ATT_HEADER_BYTES = 3
        private const val MAX_ANDROID_CHARACTERISTIC_VALUE_BYTES = 512
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val ADD_SERVICE_TIMEOUT_SECONDS = 5L
        private const val GATT_OPERATION_TIMEOUT_SECONDS = 5L
        private const val STATS_INTERVAL_MS = 1_000L
        private const val POLL_INTERVAL_MS = 20L
        private const val NO_PROGRESS_TIMEOUT_MS = 5_000L
    }

    private var clientGatt: BluetoothGatt? = null
    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var connectedServerDevice: BluetoothDevice? = null
    @Volatile private var clientConnected: Boolean = false
    @Volatile private var negotiatedMtu: Int = 23
    private val serverReceivedBytes = AtomicLong(0L)
    private val firstServerWriteTime = AtomicLong(0L)
    private val clientReceivedBytes = AtomicLong(0L)
    private val firstClientNotificationTime = AtomicLong(0L)
    private val clientWriteLock = Any()
    @Volatile private var pendingClientWrite: CountDownLatch? = null
    @Volatile private var clientWriteStatus: Int = BluetoothGatt.GATT_SUCCESS
    private val descriptorWriteLock = Any()
    @Volatile private var pendingDescriptorWrite: CountDownLatch? = null
    @Volatile private var descriptorWriteStatus: Int = BluetoothGatt.GATT_SUCCESS
    private val serverNotifyLock = Any()
    @Volatile private var pendingServerNotify: CountDownLatch? = null
    @Volatile private var serverNotifyStatus: Int = BluetoothGatt.GATT_SUCCESS
    private val serverTxSubscribers = mutableSetOf<String>()

    data class ClientConnectionResult(
        val success: Boolean,
        val message: String,
        val mtu: Int = DEFAULT_ATT_MTU,
    )

    @SuppressLint("MissingPermission")
    suspend fun connectAndDiscover(deviceItem: BluetoothDeviceItem): ClientConnectionResult {
        val adapter = adapterProvider()
            ?: return ClientConnectionResult(false, "Bluetooth adapter is unavailable.")

        val remoteDevice: BluetoothDevice = adapter.getRemoteDevice(deviceItem.address)
        val latch = CountDownLatch(1)
        val connectError = AtomicReference<String?>(null)
        val discovered = AtomicBoolean(false)
        val mtu = AtomicInteger(DEFAULT_ATT_MTU)
        val awaitingRequestedMtu = AtomicBoolean(false)
        val serviceUuid = serviceUuidProvider()
        val txCharacteristicUuid = txCharacteristicUuidProvider()
        val rxCharacteristicUuid = rxCharacteristicUuidProvider()

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    clientConnected = false
                    clearClientOperations()
                    connectError.set("GATT connection failed (state=$newState, status=$status).")
                    latch.countDown()
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        clientConnected = true
                        if (!gatt.discoverServices()) {
                            connectError.set("Failed to start GATT service discovery.")
                            latch.countDown()
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        clientConnected = false
                        clearClientOperations()
                        connectError.set("GATT disconnected.")
                        latch.countDown()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectError.set("Service discovery failed (status=$status).")
                    latch.countDown()
                    return
                }

                val service = gatt.getService(serviceUuid)
                if (service == null) {
                    connectError.set(
                        "Target GATT service $serviceUuid not found. " +
                            "Discovered services: ${formatDiscoveredServices(gatt)}",
                    )
                    latch.countDown()
                    return
                }

                val remoteTxCharacteristic = service.getCharacteristic(txCharacteristicUuid)
                val remoteRxCharacteristic = service.getCharacteristic(rxCharacteristicUuid)
                if (remoteTxCharacteristic == null && remoteRxCharacteristic == null) {
                    connectError.set(
                        "TX $txCharacteristicUuid and RX $rxCharacteristicUuid characteristics not found. " +
                            "Discovered characteristics: ${formatCharacteristics(service)}",
                    )
                    latch.countDown()
                    return
                }

                txCharacteristic = remoteTxCharacteristic
                rxCharacteristic = remoteRxCharacteristic
                rxCharacteristic?.writeType =
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                discovered.set(true)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    awaitingRequestedMtu.set(true)
                    if (!gatt.requestMtu(TARGET_MTU)) {
                        awaitingRequestedMtu.set(false)
                        negotiatedMtu = mtu.get()
                        latch.countDown()
                    }
                } else {
                    negotiatedMtu = mtu.get()
                    latch.countDown()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
                val effectiveMtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else DEFAULT_ATT_MTU
                mtu.set(effectiveMtu)
                negotiatedMtu = effectiveMtu
                if (discovered.get() && awaitingRequestedMtu.getAndSet(false)) {
                    latch.countDown()
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid == txCharacteristicUuid) {
                    firstClientNotificationTime.compareAndSet(0L, System.currentTimeMillis())
                    clientReceivedBytes.addAndGet(characteristic.value?.size?.toLong() ?: 0L)
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == txCharacteristicUuid) {
                    firstClientNotificationTime.compareAndSet(0L, System.currentTimeMillis())
                    clientReceivedBytes.addAndGet(value.size.toLong())
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (characteristic.uuid == rxCharacteristicUuid) {
                    clientWriteStatus = status
                    pendingClientWrite?.countDown()
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (descriptor.uuid == CCCD_UUID) {
                    descriptorWriteStatus = status
                    pendingDescriptorWrite?.countDown()
                }
            }
        }

        closeClient()
        clientReceivedBytes.set(0L)
        firstClientNotificationTime.set(0L)
        clientGatt = connectGattLe(remoteDevice, callback)

        val timedOut = !latch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (timedOut) {
            closeClient()
            return ClientConnectionResult(false, "GATT connection timed out.")
        }
        val failure = connectError.get()
        if (failure != null) {
            closeClient()
            return ClientConnectionResult(false, failure)
        }
        if (!discovered.get()) {
            closeClient()
            return ClientConnectionResult(false, "Service discovery did not complete.")
        }

        return ClientConnectionResult(true, "Connected & services discovered.", mtu.get())
    }

    @SuppressLint("MissingPermission")
    fun writePayloadLoop(
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStats: (BleThroughputSample) -> Unit,
    ) {
        val gatt = clientGatt ?: return
        val characteristic = rxCharacteristic ?: return
        var buffer = createPayloadBuffer(clientWritePayloadSizeForMtu(negotiatedMtu))
        val counters = BleThroughputCounters()
        val bytes = AtomicLong(0L)
        var firstWriteLogged = false
        var lastProgressTime = 0L
        val ticker = createStatsTicker(
            activeJob = activeJob,
            totalBytes = { bytes.get() },
            counters = counters,
            onStats = onStats,
            hasStarted = { counters.start > 0L },
        )
        ticker.start()

        try {
            while (activeJob()?.isActive == true) {
                val result = writeClientPayload(gatt, characteristic, buffer)
                if (result == ClientWriteResult.ValueTooLong && buffer.size > DEFAULT_ATT_MTU - ATT_HEADER_BYTES) {
                    buffer = createPayloadBuffer((buffer.size / 2).coerceAtLeast(DEFAULT_ATT_MTU - ATT_HEADER_BYTES))
                    onLog("BLE write payload too large; retrying with ${buffer.size} bytes.")
                    continue
                }
                if (result != ClientWriteResult.Success) {
                    if (counters.start > 0L && isNoProgressTimedOut(lastProgressTime)) {
                        onLog("BLE TX stopped: no successful writes for ${NO_PROGRESS_TIMEOUT_MS / 1_000}s.")
                        break
                    }
                    if (counters.start > 0L && !clientConnected) {
                        onLog("BLE TX stopped: GATT client disconnected.")
                        break
                    }
                    Thread.sleep(1)
                    continue
                }
                val now = System.currentTimeMillis()
                if (counters.start == 0L) {
                    counters.start = now
                    counters.lastTickTime = counters.start
                }
                lastProgressTime = now
                if (!firstWriteLogged) {
                    onLog("First BLE write submitted.")
                    firstWriteLogged = true
                }
                bytes.addAndGet(buffer.size.toLong())
            }
        } finally {
            ticker.interrupt()
            ticker.join(STOP_JOIN_TIMEOUT_MS)
            publishThroughputSample(bytes.get(), counters, onStats, force = true)
        }
    }

    fun hasPeerTxCharacteristic(): Boolean = txCharacteristic != null

    fun hasPeerRxCharacteristic(): Boolean = rxCharacteristic != null

    @SuppressLint("MissingPermission")
    fun subscribeToServerTx(): Boolean {
        val gatt = clientGatt ?: return false
        val characteristic = txCharacteristic ?: return false
        val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return false
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            return false
        }
        val latch = CountDownLatch(1)
        synchronized(descriptorWriteLock) {
            pendingDescriptorWrite = latch
            descriptorWriteStatus = -1
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(cccd)) {
                pendingDescriptorWrite = null
                return false
            }
        }

        val completed = latch.await(GATT_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        synchronized(descriptorWriteLock) {
            if (pendingDescriptorWrite === latch) {
                pendingDescriptorWrite = null
            }
        }
        return completed && descriptorWriteStatus == BluetoothGatt.GATT_SUCCESS
    }

    fun receiveNotificationLoop(
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStats: (BleThroughputSample) -> Unit,
    ) {
        receiveAtomicBytesLoop(
            activeJob = activeJob,
            totalBytes = clientReceivedBytes,
            firstByteTime = firstClientNotificationTime,
            firstBytesLog = "First BLE notification received.",
            onLog = onLog,
            onStats = onStats,
        )
    }

    private fun receiveAtomicBytesLoop(
        activeJob: () -> Job?,
        totalBytes: AtomicLong,
        firstByteTime: AtomicLong,
        firstBytesLog: String,
        onLog: (String) -> Unit,
        onStats: (BleThroughputSample) -> Unit,
    ) {
        val counters = BleThroughputCounters()
        var lastObservedBytes = 0L
        var lastProgressTime = 0L
        val ticker = createStatsTicker(
            activeJob = activeJob,
            totalBytes = { totalBytes.get() },
            counters = counters,
            onStats = onStats,
            hasStarted = { counters.start > 0L },
        )
        ticker.start()
        try {
            while (activeJob()?.isActive == true) {
                val currentBytes = totalBytes.get()
                if (currentBytes > lastObservedBytes) {
                    lastObservedBytes = currentBytes
                    lastProgressTime = System.currentTimeMillis()
                }
                if (currentBytes > 0L && counters.start == 0L) {
                    counters.start = firstByteTime.get().takeIf { it > 0L } ?: lastProgressTime
                    counters.lastTickTime = counters.start
                    onLog(firstBytesLog)
                }
                if (counters.start > 0L && isNoProgressTimedOut(lastProgressTime)) {
                    onLog("BLE RX stopped: no received bytes for ${NO_PROGRESS_TIMEOUT_MS / 1_000}s.")
                    break
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        } finally {
            ticker.interrupt()
            ticker.join(STOP_JOIN_TIMEOUT_MS)
            publishThroughputSample(totalBytes.get(), counters, onStats, force = true)
        }
    }

    fun clientBidirectionalPayloadLoop(
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStats: (BleBidirectionalThroughputSample) -> Unit,
    ) {
        val gatt = clientGatt ?: return
        val characteristic = rxCharacteristic ?: return
        var buffer = createPayloadBuffer(clientWritePayloadSizeForMtu(negotiatedMtu))
        val txBytes = AtomicLong(0L)
        val rxBytes = clientReceivedBytes
        val loopActive = AtomicBoolean(true)

        val sender = Thread {
            while (activeJob()?.isActive == true && loopActive.get()) {
                val result = writeClientPayload(gatt, characteristic, buffer)
                if (result == ClientWriteResult.ValueTooLong && buffer.size > DEFAULT_ATT_MTU - ATT_HEADER_BYTES) {
                    buffer = createPayloadBuffer((buffer.size / 2).coerceAtLeast(DEFAULT_ATT_MTU - ATT_HEADER_BYTES))
                    continue
                }
                if (result != ClientWriteResult.Success) {
                    Thread.sleep(1)
                    continue
                }
                txBytes.addAndGet(buffer.size.toLong())
            }
        }

        sender.start()
        publishBidirectionalStats(
            activeJob = activeJob,
            txBytes = txBytes,
            rxBytes = rxBytes,
            onLog = onLog,
            onStats = onStats,
            isActivePeerAlive = { sender.isAlive },
        )
        loopActive.set(false)
        sender.join(STOP_JOIN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun closeClient() {
        val gatt = clientGatt ?: return
        clientGatt = null
        clientConnected = false
        txCharacteristic = null
        rxCharacteristic = null
        clearClientOperations()
        clientReceivedBytes.set(0L)
        firstClientNotificationTime.set(0L)
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
        onLog: (String) -> Unit,
    ): ServerSetupResult {
        val bluetoothManager =
            context.getSystemService(BluetoothManager::class.java)
                ?: return ServerSetupResult(false, "BluetoothManager not available.")

        closeServer()
        connectedServerDevice = null
        negotiatedMtu = DEFAULT_ATT_MTU
        serverReceivedBytes.set(0L)
        firstServerWriteTime.set(0L)
        synchronized(serverTxSubscribers) {
            serverTxSubscribers.clear()
        }
        val serviceAddedLatch = CountDownLatch(1)
        var serviceAddedError: String? = null
        val serviceUuid = serviceUuidProvider()
        val txCharacteristicUuid = txCharacteristicUuidProvider()
        val rxCharacteristicUuid = rxCharacteristicUuidProvider()

        gattServer = bluetoothManager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (service.uuid == serviceUuid && status != BluetoothGatt.GATT_SUCCESS) {
                    serviceAddedError = "GATT service add failed (status=$status)."
                }
                if (service.uuid == serviceUuid) {
                    serviceAddedLatch.countDown()
                }
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedServerDevice = device
                        onLog("GATT server peer connected (${device.address}, status=$status).")
                        onConnectionAccepted()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (connectedServerDevice?.address == device.address) {
                            connectedServerDevice = null
                            clearServerOperations()
                        }
                        synchronized(serverTxSubscribers) {
                            serverTxSubscribers.remove(device.address)
                        }
                        onLog("GATT server peer disconnected (${device.address}, status=$status).")
                    }
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                negotiatedMtu = mtu
                onLog("GATT server MTU changed (${device.address}, mtu=$mtu).")
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
                if (characteristic.uuid == rxCharacteristicUuid && !preparedWrite) {
                    firstServerWriteTime.compareAndSet(0L, System.currentTimeMillis())
                    serverReceivedBytes.addAndGet(value.size.toLong())
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val status = if (characteristic.uuid == txCharacteristicUuid || characteristic.uuid == rxCharacteristicUuid) {
                    BluetoothGatt.GATT_READ_NOT_PERMITTED
                } else {
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                }
                gattServer?.sendResponse(device, requestId, status, 0, null)
                onLog("GATT server characteristic read rejected (${device.address}, characteristic=${characteristic.uuid}, status=$status).")
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor,
            ) {
                val fullValue = when (descriptor.uuid) {
                    CCCD_UUID -> descriptor.value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    CUDD_UUID -> descriptor.value ?: ByteArray(0)
                    else -> null
                }
                if (fullValue == null) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                    onLog("GATT server descriptor read rejected (${device.address}, descriptor=${descriptor.uuid}).")
                    return
                }
                if (offset > fullValue.size) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                    onLog("GATT server descriptor read invalid offset (${device.address}, descriptor=${descriptor.uuid}, offset=$offset).")
                    return
                }
                val value = fullValue.copyOfRange(offset, fullValue.size)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                if (descriptor.uuid == CCCD_UUID) {
                    descriptor.value = value
                    val subscribed = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                        value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                    synchronized(serverTxSubscribers) {
                        if (subscribed) {
                            serverTxSubscribers.add(device.address)
                        } else {
                            serverTxSubscribers.remove(device.address)
                        }
                    }
                    val valueText = value.joinToString(separator = " ") { "%02X".format(it) }
                    if (subscribed) {
                        onLog("GATT server peer subscribed TX (${device.address}, cccd=$valueText).")
                    } else {
                        onLog("GATT server peer unsubscribed TX (${device.address}, cccd=$valueText).")
                    }
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                serverNotifyStatus = status
                pendingServerNotify?.countDown()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onLog("GATT server notification failed (${device.address}, status=$status).")
                }
            }
        })
            ?: return ServerSetupResult(false, "Failed to open GATT server.")

        val rxChar = BluetoothGattCharacteristic(
            rxCharacteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        rxChar.addDescriptor(createUserDescriptionDescriptor("RX characteristic"))

        val txChar = BluetoothGattCharacteristic(
            txCharacteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE,
            0,
        )
        txChar.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        txChar.addDescriptor(createUserDescriptionDescriptor("TX characteristic"))
        txCharacteristic = txChar
        rxCharacteristic = rxChar

        val service = BluetoothGattService(
            serviceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        service.addCharacteristic(rxChar)
        service.addCharacteristic(txChar)

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

        onMtuChanged(DEFAULT_ATT_MTU)
        return ServerSetupResult(true, "GATT server ready (service=$serviceUuid).")
    }

    fun receivePayloadLoop(
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStats: (BleThroughputSample) -> Unit,
    ) {
        if (gattServer == null) return
        receiveAtomicBytesLoop(
            activeJob = activeJob,
            totalBytes = serverReceivedBytes,
            firstByteTime = firstServerWriteTime,
            firstBytesLog = "First BLE write received.",
            onLog = onLog,
            onStats = onStats,
        )
    }

    @SuppressLint("MissingPermission")
    fun notifyPayloadLoop(
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStats: (BleThroughputSample) -> Unit,
    ) {
        val server = gattServer ?: return
        val characteristic = txCharacteristic ?: server
            .services
            .firstOrNull { it.uuid == serviceUuidProvider() }
            ?.getCharacteristic(txCharacteristicUuidProvider())
            ?: return
        var bufferMtu = negotiatedMtu
        var buffer = createPayloadBuffer(characteristicValuePayloadSizeForMtu(bufferMtu))
        val counters = BleThroughputCounters()
        val bytes = AtomicLong(0L)
        var firstNotificationLogged = false
        var lastProgressTime = 0L
        val ticker = createStatsTicker(
            activeJob = activeJob,
            totalBytes = { bytes.get() },
            counters = counters,
            onStats = onStats,
            hasStarted = { counters.start > 0L },
        )
        ticker.start()

        try {
            while (activeJob()?.isActive == true) {
                val device = connectedServerDevice
                if (device == null || !isServerTxSubscribed(device)) {
                    if (counters.start > 0L && isNoProgressTimedOut(lastProgressTime)) {
                        val reason = if (device == null) {
                            "peer disconnected"
                        } else {
                            "peer unsubscribed"
                        }
                        onLog("BLE notification TX stopped: $reason.")
                        break
                    }
                    Thread.sleep(POLL_INTERVAL_MS)
                    continue
                }

                val currentMtu = negotiatedMtu
                if (currentMtu != bufferMtu) {
                    bufferMtu = currentMtu
                    buffer = createPayloadBuffer(characteristicValuePayloadSizeForMtu(bufferMtu))
                    onLog("BLE notification payload updated to ${buffer.size} bytes for MTU $bufferMtu.")
                }

                if (!notifyServerPayload(server, device, characteristic, buffer)) {
                    if (counters.start > 0L && isNoProgressTimedOut(lastProgressTime)) {
                        onLog("BLE notification TX stopped: no successful notifications for ${NO_PROGRESS_TIMEOUT_MS / 1_000}s.")
                        break
                    }
                    Thread.sleep(1)
                    continue
                }
                val now = System.currentTimeMillis()
                if (counters.start == 0L) {
                    counters.start = now
                    counters.lastTickTime = counters.start
                }
                lastProgressTime = now
                if (!firstNotificationLogged) {
                    onLog("First BLE notification submitted.")
                    firstNotificationLogged = true
                }
                bytes.addAndGet(buffer.size.toLong())
            }
        } finally {
            ticker.interrupt()
            ticker.join(STOP_JOIN_TIMEOUT_MS)
            publishThroughputSample(bytes.get(), counters, onStats, force = true)
        }
    }

    fun serverBidirectionalPayloadLoop(
        activeJob: () -> Job?,
        onLog: (String) -> Unit,
        onStats: (BleBidirectionalThroughputSample) -> Unit,
    ) {
        val server = gattServer ?: return
        val characteristic = txCharacteristic ?: server
            .services
            .firstOrNull { it.uuid == serviceUuidProvider() }
            ?.getCharacteristic(txCharacteristicUuidProvider())
            ?: return
        var bufferMtu = negotiatedMtu
        var buffer = createPayloadBuffer(characteristicValuePayloadSizeForMtu(bufferMtu))
        val txBytes = AtomicLong(0L)
        val rxBytes = serverReceivedBytes
        val loopActive = AtomicBoolean(true)

        val sender = Thread {
            while (activeJob()?.isActive == true && loopActive.get()) {
                val device = connectedServerDevice
                if (device == null || !isServerTxSubscribed(device)) {
                    Thread.sleep(POLL_INTERVAL_MS)
                    continue
                }
                val currentMtu = negotiatedMtu
                if (currentMtu != bufferMtu) {
                    bufferMtu = currentMtu
                    buffer = createPayloadBuffer(characteristicValuePayloadSizeForMtu(bufferMtu))
                }
                if (notifyServerPayload(server, device, characteristic, buffer)) {
                    txBytes.addAndGet(buffer.size.toLong())
                } else {
                    Thread.sleep(1)
                }
            }
        }

        sender.start()
        publishBidirectionalStats(
            activeJob = activeJob,
            txBytes = txBytes,
            rxBytes = rxBytes,
            onLog = onLog,
            onStats = onStats,
            isActivePeerAlive = { sender.isAlive },
        )
        loopActive.set(false)
        sender.join(STOP_JOIN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun closeServer() {
        val server = gattServer ?: return
        gattServer = null
        connectedServerDevice = null
        clearServerOperations()
        synchronized(serverTxSubscribers) {
            serverTxSubscribers.clear()
        }
        try {
            server.clearServices()
            server.close()
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private enum class ClientWriteResult {
        Success,
        BusyOrFailed,
        ValueTooLong,
    }

    private fun writeClientPayload(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        buffer: ByteArray,
    ): ClientWriteResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return try {
                if (
                    gatt.writeCharacteristic(
                        characteristic,
                        buffer,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                    ) == BluetoothGatt.GATT_SUCCESS
                ) {
                    ClientWriteResult.Success
                } else {
                    ClientWriteResult.BusyOrFailed
                }
            } catch (error: IllegalArgumentException) {
                if (error.message?.contains("longer", ignoreCase = true) == true) {
                    ClientWriteResult.ValueTooLong
                } else {
                    throw error
                }
            }
        }

        val latch = CountDownLatch(1)
        synchronized(clientWriteLock) {
            pendingClientWrite = latch
            clientWriteStatus = -1
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            characteristic.value = buffer
            if (!gatt.writeCharacteristic(characteristic)) {
                pendingClientWrite = null
                return ClientWriteResult.BusyOrFailed
            }
        }

        if (characteristic.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            synchronized(clientWriteLock) {
                if (pendingClientWrite === latch) {
                    pendingClientWrite = null
                }
            }
            return ClientWriteResult.Success
        }

        val completed = latch.await(GATT_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        synchronized(clientWriteLock) {
            if (pendingClientWrite === latch) {
                pendingClientWrite = null
            }
        }
        return if (completed && clientWriteStatus == BluetoothGatt.GATT_SUCCESS) {
            ClientWriteResult.Success
        } else {
            ClientWriteResult.BusyOrFailed
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyServerPayload(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        buffer: ByteArray,
    ): Boolean {
        val latch = CountDownLatch(1)
        val started = synchronized(serverNotifyLock) {
            pendingServerNotify = latch
            serverNotifyStatus = -1
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, buffer) == BluetoothGatt.GATT_SUCCESS
            } else {
                characteristic.value = buffer
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        }
        if (!started) {
            synchronized(serverNotifyLock) {
                if (pendingServerNotify === latch) {
                    pendingServerNotify = null
                }
            }
            return false
        }

        latch.await(GATT_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        synchronized(serverNotifyLock) {
            if (pendingServerNotify === latch) {
                pendingServerNotify = null
            }
        }
        return serverNotifyStatus == BluetoothGatt.GATT_SUCCESS || serverNotifyStatus == -1
    }

    private fun isServerTxSubscribed(device: BluetoothDevice): Boolean {
        return synchronized(serverTxSubscribers) {
            serverTxSubscribers.contains(device.address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGattLe(
        device: BluetoothDevice,
        callback: BluetoothGattCallback,
    ): BluetoothGatt {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
    }

    private fun clearClientOperations() {
        pendingClientWrite?.countDown()
        pendingClientWrite = null
        pendingDescriptorWrite?.countDown()
        pendingDescriptorWrite = null
    }

    private fun clearServerOperations() {
        pendingServerNotify?.countDown()
        pendingServerNotify = null
    }

    private fun payloadSizeForMtu(mtu: Int): Int {
        return (mtu - ATT_HEADER_BYTES).coerceIn(
            DEFAULT_ATT_MTU - ATT_HEADER_BYTES,
            TARGET_MTU - ATT_HEADER_BYTES,
        )
    }

    private fun clientWritePayloadSizeForMtu(mtu: Int): Int {
        return characteristicValuePayloadSizeForMtu(mtu)
    }

    private fun characteristicValuePayloadSizeForMtu(mtu: Int): Int {
        return payloadSizeForMtu(mtu).coerceAtMost(MAX_ANDROID_CHARACTERISTIC_VALUE_BYTES)
    }

    private fun createPayloadBuffer(size: Int): ByteArray {
        return ByteArray(size) { index -> (index and 0xFF).toByte() }
    }

    private fun formatDiscoveredServices(gatt: BluetoothGatt): String {
        return gatt.services
            .map { it.uuid.toString() }
            .ifEmpty { listOf("none") }
            .joinToString()
    }

    private fun formatCharacteristics(service: BluetoothGattService): String {
        return service.characteristics
            .map { it.uuid.toString() }
            .ifEmpty { listOf("none") }
            .joinToString()
    }

    private fun publishBidirectionalStats(
        activeJob: () -> Job?,
        txBytes: AtomicLong,
        rxBytes: AtomicLong,
        onLog: (String) -> Unit,
        onStats: (BleBidirectionalThroughputSample) -> Unit,
        isActivePeerAlive: () -> Boolean,
    ) {
        var start = 0L
        var nextTick = 0L
        var lastTickTxBytes = 0L
        var lastTickRxBytes = 0L
        var lastTickTime = 0L
        var lastProgressTxBytes = 0L
        var lastProgressRxBytes = 0L
        var lastProgressTime = 0L
        while (activeJob()?.isActive == true && isActivePeerAlive()) {
            val now = System.currentTimeMillis()
            val currentTxBytes = txBytes.get()
            val currentRxBytes = rxBytes.get()
            if (currentTxBytes > lastProgressTxBytes || currentRxBytes > lastProgressRxBytes) {
                lastProgressTxBytes = currentTxBytes
                lastProgressRxBytes = currentRxBytes
                lastProgressTime = now
            }
            if (start == 0L) {
                if (currentTxBytes == 0L && currentRxBytes == 0L) {
                    Thread.sleep(POLL_INTERVAL_MS)
                    continue
                }
                start = now
                lastTickTime = start
                nextTick = start + STATS_INTERVAL_MS
            }
            if (start > 0L && isNoProgressTimedOut(lastProgressTime)) {
                onLog("BLE TX + RX stopped: no byte progress for ${NO_PROGRESS_TIMEOUT_MS / 1_000}s.")
                break
            }
            if (now >= nextTick) {
                onStats(
                    createBleBidirectionalSample(
                        txBytes = currentTxBytes,
                        rxBytes = currentRxBytes,
                        start = start,
                        previousTxBytes = lastTickTxBytes,
                        previousRxBytes = lastTickRxBytes,
                        previousTickTime = lastTickTime,
                        now = now,
                    ),
                )
                lastTickTxBytes = currentTxBytes
                lastTickRxBytes = currentRxBytes
                lastTickTime = now
                nextTick = now + STATS_INTERVAL_MS
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun isNoProgressTimedOut(lastProgressTime: Long): Boolean {
        return lastProgressTime > 0L &&
            System.currentTimeMillis() - lastProgressTime >= NO_PROGRESS_TIMEOUT_MS
    }

    private class BleThroughputCounters(
        @Volatile var start: Long = 0L,
    ) {
        @Volatile var lastTickBytes: Long = 0L
        @Volatile var lastTickTime: Long = start
    }

    private fun createStatsTicker(
        activeJob: () -> Job?,
        totalBytes: () -> Long,
        counters: BleThroughputCounters,
        onStats: (BleThroughputSample) -> Unit,
        hasStarted: () -> Boolean = { true },
    ): Thread {
        return Thread {
            try {
                while (activeJob()?.isActive == true) {
                    Thread.sleep(STATS_INTERVAL_MS)
                    if (hasStarted()) {
                        publishThroughputSample(totalBytes(), counters, onStats)
                    }
                }
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun publishThroughputSample(
        totalBytes: Long,
        counters: BleThroughputCounters,
        onStats: (BleThroughputSample) -> Unit,
        force: Boolean = false,
    ) {
        if (counters.start <= 0L) return
        val now = System.currentTimeMillis()
        if (!force && now <= counters.lastTickTime) return
        if (force && totalBytes == counters.lastTickBytes) return
        onStats(
            createBleThroughputSample(
                totalBytes = totalBytes,
                start = counters.start,
                previousBytes = counters.lastTickBytes,
                previousTickTime = counters.lastTickTime,
                now = now,
            ),
        )
        counters.lastTickBytes = totalBytes
        counters.lastTickTime = now
    }
}

private fun createBleThroughputSample(
    totalBytes: Long,
    start: Long,
    previousBytes: Long,
    previousTickTime: Long,
    now: Long,
) = BleThroughputSample(
    totalBytes = totalBytes,
    elapsedMillis = (now - start).coerceAtLeast(1L),
    intervalBytes = totalBytes - previousBytes,
    intervalMillis = (now - previousTickTime).coerceAtLeast(1L),
)

private fun createBleBidirectionalSample(
    txBytes: Long,
    rxBytes: Long,
    start: Long,
    previousTxBytes: Long,
    previousRxBytes: Long,
    previousTickTime: Long,
    now: Long,
) = BleBidirectionalThroughputSample(
    txBytes = txBytes,
    rxBytes = rxBytes,
    elapsedMillis = (now - start).coerceAtLeast(1L),
    intervalTxBytes = txBytes - previousTxBytes,
    intervalRxBytes = rxBytes - previousRxBytes,
    intervalMillis = (now - previousTickTime).coerceAtLeast(1L),
)

private fun createUserDescriptionDescriptor(description: String): BluetoothGattDescriptor {
    return BluetoothGattDescriptor(
        CUDD_UUID,
        BluetoothGattDescriptor.PERMISSION_READ,
    ).apply {
        value = description.toByteArray(Charsets.UTF_8)
    }
}

private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
private val CUDD_UUID: UUID = UUID.fromString("00002901-0000-1000-8000-00805F9B34FB")
private const val STOP_JOIN_TIMEOUT_MS = 500L
