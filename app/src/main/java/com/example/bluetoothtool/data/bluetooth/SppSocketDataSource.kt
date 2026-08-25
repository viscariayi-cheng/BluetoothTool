package com.example.bluetoothtool.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Build
import com.example.bluetoothtool.model.BluetoothDeviceItem
import com.example.bluetoothtool.model.SppBidirectionalThroughputSample
import com.example.bluetoothtool.model.SppThroughputSample
import kotlinx.coroutines.Job
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class SppSocketDataSource(
    private val adapterProvider: () -> BluetoothAdapter?,
    private val serviceUuidProvider: () -> UUID,
) {
    private var socket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null

    @SuppressLint("MissingPermission")
    fun openClientSocket(deviceItem: BluetoothDeviceItem): BluetoothSocket {
        val adapter = requireNotNull(adapterProvider()) { "Bluetooth adapter is unavailable." }
        val remoteDevice: BluetoothDevice = adapter.getRemoteDevice(deviceItem.address)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            adapter.cancelDiscovery()
        }

        return remoteDevice.createRfcommSocketToServiceRecord(serviceUuidProvider()).also {
            socket = it
            it.connect()
        }
    }

    @SuppressLint("MissingPermission")
    fun openServerSocket(): BluetoothServerSocket {
        val adapter = requireNotNull(adapterProvider()) { "Bluetooth adapter is unavailable." }
        return adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, serviceUuidProvider()).also {
            serverSocket = it
        }
    }

    fun acceptServerSocket(listener: BluetoothServerSocket): BluetoothSocket {
        return listener.accept().also {
            socket = it
            serverSocket?.close()
            serverSocket = null
        }
    }

    fun sendPayloadLoop(
        activeSocket: BluetoothSocket,
        activeJob: () -> Job?,
        onStats: (SppThroughputSample) -> Unit,
    ) {
        val output = activeSocket.outputStream
        val buffer = ByteArray(BUFFER_SIZE) { index -> (index and 0xFF).toByte() }
        val counters = ThroughputCounters(System.currentTimeMillis())
        val bytes = AtomicLong(0L)
        val ticker = createStatsTicker(
            activeJob = activeJob,
            totalBytes = { bytes.get() },
            counters = counters,
            statsIntervalMs = STATS_INTERVAL_MS,
            onStats = onStats,
        )
        ticker.start()

        try {
            while (activeJob()?.isActive == true) {
                output.write(buffer)
                bytes.addAndGet(buffer.size.toLong())
            }
        } finally {
            ticker.interrupt()
            ticker.join(STOP_JOIN_TIMEOUT_MS)
            publishThroughputSample(bytes.get(), counters, onStats, force = true)
        }
    }

    fun receivePayloadLoop(
        activeSocket: BluetoothSocket,
        activeJob: () -> Job?,
        onStats: (SppThroughputSample) -> Unit,
    ) {
        val input = activeSocket.inputStream
        val buffer = ByteArray(BUFFER_SIZE)
        val counters = ThroughputCounters()
        val bytes = AtomicLong(0L)
        val ticker = createStatsTicker(
            activeJob = activeJob,
            totalBytes = { bytes.get() },
            counters = counters,
            statsIntervalMs = STATS_INTERVAL_MS,
            onStats = onStats,
            hasStarted = { counters.start > 0L },
        )
        ticker.start()

        try {
            while (activeJob()?.isActive == true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (counters.start == 0L) {
                    counters.start = System.currentTimeMillis()
                    counters.lastTickTime = counters.start
                }
                bytes.addAndGet(read.toLong())
            }
        } finally {
            ticker.interrupt()
            ticker.join(STOP_JOIN_TIMEOUT_MS)
            publishThroughputSample(bytes.get(), counters, onStats, force = true)
        }
    }

    fun bidirectionalPayloadLoop(
        activeSocket: BluetoothSocket,
        activeJob: () -> Job?,
        onStats: (SppBidirectionalThroughputSample) -> Unit,
    ) {
        val input = activeSocket.inputStream
        val output = activeSocket.outputStream
        val txBytes = AtomicLong(0L)
        val rxBytes = AtomicLong(0L)
        val start = System.currentTimeMillis()

        val sender = Thread {
            val buffer = ByteArray(BUFFER_SIZE) { index -> (index and 0xFF).toByte() }
            try {
                while (activeJob()?.isActive == true) {
                    output.write(buffer)
                    txBytes.addAndGet(buffer.size.toLong())
                }
            } catch (_: IOException) {
            }
        }

        val receiver = Thread {
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                while (activeJob()?.isActive == true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    rxBytes.addAndGet(read.toLong())
                }
            } catch (_: IOException) {
            }
        }

        sender.start()
        receiver.start()

        var nextTick = start + STATS_INTERVAL_MS
        var lastTickTxBytes = 0L
        var lastTickRxBytes = 0L
        var lastTickTime = start
        while (activeJob()?.isActive == true && sender.isAlive && receiver.isAlive) {
            val now = System.currentTimeMillis()
            if (now >= nextTick) {
                val currentTxBytes = txBytes.get()
                val currentRxBytes = rxBytes.get()
                onStats(
                    createBidirectionalSample(
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
            Thread.sleep(20)
        }

        close()
        sender.join(STOP_JOIN_TIMEOUT_MS)
        receiver.join(STOP_JOIN_TIMEOUT_MS)
        val now = System.currentTimeMillis()
        val currentTxBytes = txBytes.get()
        val currentRxBytes = rxBytes.get()
        onStats(
            createBidirectionalSample(
                txBytes = currentTxBytes,
                rxBytes = currentRxBytes,
                start = start,
                previousTxBytes = lastTickTxBytes,
                previousRxBytes = lastTickRxBytes,
                previousTickTime = lastTickTime,
                now = now,
            ),
        )
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        socket = null
        serverSocket = null
    }

    companion object {
        private const val SERVICE_NAME = "BluetoothTool SPP"
        private const val BUFFER_SIZE = 8 * 1024
        private const val STATS_INTERVAL_MS = 1_000L
        private const val STOP_JOIN_TIMEOUT_MS = 500L
    }
}

private fun createThroughputSample(
    totalBytes: Long,
    start: Long,
    previousBytes: Long,
    previousTickTime: Long,
    now: Long,
) = SppThroughputSample(
    totalBytes = totalBytes,
    elapsedMillis = (now - start).coerceAtLeast(1L),
    intervalBytes = totalBytes - previousBytes,
    intervalMillis = (now - previousTickTime).coerceAtLeast(1L),
)

private class ThroughputCounters(
    @Volatile var start: Long = 0L,
) {
    @Volatile var lastTickBytes: Long = 0L
    @Volatile var lastTickTime: Long = start
}

private fun createStatsTicker(
    activeJob: () -> Job?,
    totalBytes: () -> Long,
    counters: ThroughputCounters,
    statsIntervalMs: Long,
    onStats: (SppThroughputSample) -> Unit,
    hasStarted: () -> Boolean = { true },
): Thread {
    return Thread {
        try {
            while (activeJob()?.isActive == true) {
                Thread.sleep(statsIntervalMs)
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
    counters: ThroughputCounters,
    onStats: (SppThroughputSample) -> Unit,
    force: Boolean = false,
) {
    if (counters.start <= 0L) return
    val now = System.currentTimeMillis()
    if (!force && now <= counters.lastTickTime) return
    if (force && totalBytes == counters.lastTickBytes) return
    onStats(
        createThroughputSample(
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

private fun createBidirectionalSample(
    txBytes: Long,
    rxBytes: Long,
    start: Long,
    previousTxBytes: Long,
    previousRxBytes: Long,
    previousTickTime: Long,
    now: Long,
) = SppBidirectionalThroughputSample(
    txBytes = txBytes,
    rxBytes = rxBytes,
    elapsedMillis = (now - start).coerceAtLeast(1L),
    intervalTxBytes = txBytes - previousTxBytes,
    intervalRxBytes = rxBytes - previousRxBytes,
    intervalMillis = (now - previousTickTime).coerceAtLeast(1L),
)
