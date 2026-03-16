package com.example.rnshello

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID

class BluetoothService {
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null

    // UUID for SPP (Serial Port Profile)
    private val uuid: UUID = UUID.fromString("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")

    fun connect(deviceAddress: String) {
        // Connection logic here
    }

    fun disconnect() {
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun write(data: ByteArray) {
        if (bluetoothSocket?.isConnected == true) {
            try {
                val outputStream = bluetoothSocket!!.outputStream
                val packetSize = 83
                var offset = 0
                while (offset < data.size) {
                    val length = Math.min(packetSize, data.size - offset)
                    outputStream.write(data, offset, length)
                    outputStream.flush()
                    if (length < packetSize) break  // Exit if the last packet is smaller than packetSize
                    offset += length
                    Thread.sleep(100) // Delay logic for packet transmission
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}