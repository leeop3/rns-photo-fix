package com.example.rnshello

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.OutputStream
import java.util.UUID

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
private const val TAG = "BluetoothService"

class BluetoothService {
    private var socket: BluetoothSocket? = null

    // Use BufferedInputStream like Sideband does — avoids partial reads
    @Volatile private var bufferedInput: BufferedInputStream? = null
    @Volatile private var outputStream: OutputStream? = null

    @Volatile private var deviceAddress: String? = null
    @Volatile private var isConnected = false
    @Volatile private var reconnecting = false

    suspend fun connect(deviceAddress: String): Boolean = withContext(Dispatchers.IO) {
        this@BluetoothService.deviceAddress = deviceAddress
        connectInternal(deviceAddress)
    }

    private fun connectInternal(address: String): Boolean {
        return try {
            try { socket?.close() } catch (_: Exception) {}
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val device = adapter.getRemoteDevice(address)
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            adapter.cancelDiscovery()
            s.connect()
            socket = s
            // Wrap with BufferedInputStream (1024 byte buffer) — same as Sideband
            bufferedInput = BufferedInputStream(s.inputStream, 1024)
            outputStream = s.outputStream
            isConnected = true
            Log.i(TAG, "BT connected to $address")
            true
        } catch (e: Exception) {
            isConnected = false
            Log.e(TAG, "BT connect failed: ${e.message}")
            false
        }
    }

    // Reconnect runs fully async — never blocks the caller
    private fun triggerReconnect() {
        if (reconnecting) return
        reconnecting = true
        isConnected = false
        Thread {
            Log.i(TAG, "BT reconnect started...")
            val address = deviceAddress
            if (address == null) { reconnecting = false; return@Thread }
            var attempts = 0
            while (attempts < 20 && deviceAddress != null) {
                Thread.sleep(2000)
                if (connectInternal(address)) {
                    Log.i(TAG, "BT reconnected after ${attempts + 1} attempts")
                    break
                }
                attempts++
            }
            reconnecting = false
        }.also { it.isDaemon = true }.start()
    }

    // Non-blocking read — only reads what's available, like Sideband
    fun read(maxBytes: Int): ByteArray {
        return try {
            val input = bufferedInput ?: return ByteArray(0)
            val available = input.available()
            if (available > 0) {
                val toRead = minOf(available, maxBytes)
                val buf = ByteArray(toRead)
                val n = input.read(buf, 0, toRead)
                if (n <= 0) {
                    Log.w(TAG, "BT read returned $n despite $available available")
                    ByteArray(0)
                } else {
                    buf.copyOf(n)
                }
            } else {
                // Nothing available — sleep briefly to avoid busy-looping
                Thread.sleep(10)
                ByteArray(0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "BT read error: ${e.message}")
            triggerReconnect()
            ByteArray(0)
        }
    }

    // write() NEVER blocks for reconnect — just throws so Python logs it and moves on
    fun write(data: ByteArray) {
        if (!isConnected) {
            triggerReconnect()
            throw Exception("BT not connected, reconnecting...")
        }
        try {
            outputStream?.write(data)
            outputStream?.flush()  // flush after every write like Sideband
        } catch (e: Exception) {
            Log.w(TAG, "BT write error: ${e.message}")
            triggerReconnect()
            throw e
        }
    }

    fun disconnect() {
        deviceAddress = null
        isConnected = false
        try { socket?.close() } catch (_: Exception) {}
        bufferedInput = null
        outputStream = null
    }
}
