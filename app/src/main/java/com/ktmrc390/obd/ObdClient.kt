package com.ktmrc390.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class ObdClient {
    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    val isConnected: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        close()
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        s.connect()
        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    @Synchronized
    fun command(command: String, timeoutMs: Long = 5000): String {
        val i = input ?: error("OBDLink non connecté")
        val o = output ?: error("OBDLink non connecté")
        while (i.available() > 0) i.read()
        val cmd = command.trim().uppercase()
        o.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        o.flush()
        val result = StringBuilder()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (i.available() > 0) {
                val b = i.read()
                if (b < 0) break
                val c = b.toChar()
                if (c == '>') break
                result.append(c)
            } else Thread.sleep(10)
        }
        return result.toString().replace("\u0000", "").trim()
    }

    fun initialize(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        listOf("ATZ","ATE0","ATL0","ATS0","ATH0","ATSP0").forEachIndexed { index, cmd ->
            val timeout = if (index == 0) 6000L else 3000L
            out += cmd to command(cmd, timeout)
            if (cmd == "ATZ") Thread.sleep(500)
        }
        return out
    }

    fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        input = null; output = null; socket = null
    }
}