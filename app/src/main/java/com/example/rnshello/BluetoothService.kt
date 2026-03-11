package com.example.rnshello

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

class BluetoothService {
    private var socket: BluetoothSocket? = null
    var inputStream: InputStream? = null
    var outputStream: OutputStream? = null

    suspend fun connect(deviceAddress: String): Boolean = withContext(Dispatchers.IO) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val device = adapter.getRemoteDevice(deviceAddress)
        adapter.cancelDiscovery()
        // RNode BT SPP often rejects the first connection attempt — retry up to 3 times
        repeat(3) { attempt ->
            try {
                socket?.close()
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket!!.connect()
                inputStream = socket!!.inputStream
                outputStream = socket!!.outputStream
                return@withContext true
            } catch (e: Exception) {
                e.printStackTrace()
                if (attempt < 2) Thread.sleep(1200)
            }
        }
        false
    }

    fun read(maxBytes: Int): ByteArray {
        val buf = ByteArray(maxBytes)
        val n = inputStream?.read(buf) ?: 0
        return buf.copyOf(n)
    }

    fun write(data: ByteArray) { outputStream?.write(data) }
    fun disconnect() { socket?.close() }
}
