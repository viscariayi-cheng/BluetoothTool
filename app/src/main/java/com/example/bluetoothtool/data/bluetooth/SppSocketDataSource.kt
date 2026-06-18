package com.example.bluetoothtool.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Build
import com.example.bluetoothtool.model.BluetoothDeviceItem
import kotlinx.coroutines.Job
import java.io.IOException
import java.util.UUID

class SppSocketDataSource(
    private val adapterProvider: () -> BluetoothAdapter?,
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

        return remoteDevice.createRfcommSocketToServiceRecord(SPP_UUID).also {
            socket = it
            it.connect()
        }
    }

    @SuppressLint("MissingPermission")
    fun openServerSocket(): BluetoothServerSocket {
        val adapter = requireNotNull(adapterProvider()) { "Bluetooth adapter is unavailable." }
        return adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID).also {
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
        onStats: (bytes: Long, elapsedMillis: Long) -> Unit,
    ) {
        val output = activeSocket.outputStream
        val buffer = ByteArray(BUFFER_SIZE) { index -> (index and 0xFF).toByte() }
        val start = System.currentTimeMillis()
        var bytes = 0L
        var nextTick = start

        while (activeJob()?.isActive == true) {
            output.write(buffer)
            bytes += buffer.size

            val now = System.currentTimeMillis()
            if (now >= nextTick) {
                onStats(bytes, now - start)
                nextTick = now + STATS_INTERVAL_MS
            }
        }
    }

    fun receivePayloadLoop(
        activeSocket: BluetoothSocket,
        activeJob: () -> Job?,
        onStats: (bytes: Long, elapsedMillis: Long) -> Unit,
    ) {
        val input = activeSocket.inputStream
        val buffer = ByteArray(BUFFER_SIZE)
        val start = System.currentTimeMillis()
        var bytes = 0L
        var nextTick = start

        while (activeJob()?.isActive == true) {
            val read = input.read(buffer)
            if (read < 0) break
            bytes += read

            val now = System.currentTimeMillis()
            if (now >= nextTick) {
                onStats(bytes, now - start)
                nextTick = now + STATS_INTERVAL_MS
            }
        }
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
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
