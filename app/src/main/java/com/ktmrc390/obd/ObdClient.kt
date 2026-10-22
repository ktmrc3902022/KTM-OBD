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

    @Volatile
    private var monitorActive = false

    @Volatile
    private var monitorStopRequestedAt = 0L

    val isConnected: Boolean get() = socket?.isConnected == true
    val isMonitoring: Boolean get() = monitorActive

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
        if (monitorActive) error("Capture CAN en cours")
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
            } else {
                Thread.sleep(10)
            }
        }

        return result.toString().replace("\u0000", "").trim()
    }

    fun initialize(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEachIndexed { index, cmd ->
            val timeout = if (index == 0) 6000L else 3000L
            out += cmd to command(cmd, timeout)
            if (cmd == "ATZ") Thread.sleep(500)
        }
        return out
    }

    fun prepareCanMonitor(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        listOf(
            "ATDP",
            "ATDPN",
            "ATH1",
            "ATS0",
            "ATCAF0",
            "ATAL",
            "STCMM0",
            "STFAC",
            "ATCF000",
            "ATCM000",
            "STFPA000,000"
        ).forEach { cmd ->
            out += cmd to command(cmd, 3000)
        }
        return out
    }

    /**
     * Capture le CAN brut avec la commande native OBDLink/STN "STM".
     *
     * Le STN1155 v5.6.19 de l'OBDLink LX utilisé sur la RC390 n'accepte pas
     * la syntaxe récente "STM n" : il répond alors simplement "?".
     * Sur ce firmware, STM reste en écoute continue jusqu'à réception d'un
     * caractère. stopMonitor() envoie donc un CR pour rendre le prompt.
     */
    fun monitorAll(onLine: (String) -> Unit): Int {
        if (monitorActive) error("Capture CAN déjà en cours")

        val i = input ?: error("OBDLink non connecté")
        val o = output ?: error("OBDLink non connecté")

        while (i.available() > 0) i.read()

        monitorStopRequestedAt = 0L
        monitorActive = true
        var totalLineCount = 0

        try {
            o.write("STM\r".toByteArray(Charsets.US_ASCII))
            o.flush()

            val line = StringBuilder()
            var promptSeen = false

            while (!promptSeen) {
                if (i.available() > 0) {
                    val b = i.read()
                    if (b < 0) break

                    val c = b.toChar()
                    if (c == '>') {
                        emitMonitorLine(line, onLine)?.let { totalLineCount += it }
                        promptSeen = true
                    } else if (c == '\r' || c == '\n') {
                        emitMonitorLine(line, onLine)?.let { totalLineCount += it }
                    } else if (c != '\u0000') {
                        line.append(c)
                    }
                } else {
                    if (monitorStopRequestedAt != 0L &&
                        System.currentTimeMillis() - monitorStopRequestedAt > 3000L
                    ) {
                        break
                    }
                    Thread.sleep(1)
                }
            }
        } finally {
            monitorActive = false
            monitorStopRequestedAt = 0L
        }

        return totalLineCount
    }

    private fun emitMonitorLine(line: StringBuilder, onLine: (String) -> Unit): Int? {
        if (line.isEmpty()) return null

        val value = line.toString().trim()
        line.setLength(0)
        if (value.isBlank()) return null

        val upper = value.uppercase()
        if (upper == "STM" || upper == "STOPPED") return null
        if (upper == "?") {
            throw IllegalStateException("Commande STM refusée par l'OBDLink")
        }
        if (upper == "BUFFER FULL") {
            onLine("# OBDLINK BUFFER FULL — relance automatique")
            return null
        }

        onLine(formatRawCanLine(value))
        return 1
    }

    private fun formatRawCanLine(value: String): String {
        val compact = value.replace(" ", "").uppercase()
        if (!compact.matches(Regex("^[0-9A-F]+$"))) return value
        if (compact.length < 3 || compact.length > 19 || compact.length % 2 == 0) return value

        val id = compact.substring(0, 3)
        val payload = compact.substring(3)
            .chunked(2)
            .joinToString(" ")
        return if (payload.isBlank()) id else "$id $payload"
    }

    fun stopMonitor() {
        if (!monitorActive) return

        monitorStopRequestedAt = System.currentTimeMillis()
        try {
            output?.write("\r".toByteArray(Charsets.US_ASCII))
            output?.flush()
        } catch (_: Exception) {
        }
    }

    fun restoreAfterCanMonitor(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        listOf("ATCAF1", "ATH0", "ATS0", "ATL0", "ATSP0").forEach { cmd ->
            out += cmd to command(cmd, 3000)
        }
        return out
    }

    fun close() {
        try { stopMonitor() } catch (_: Exception) {}
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        monitorActive = false
        monitorStopRequestedAt = 0L
        input = null
        output = null
        socket = null
    }
}
