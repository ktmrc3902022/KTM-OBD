package com.ktmrc390.obd

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class CanCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.ktmrc390.obd.CAN_START"
        const val ACTION_STOP = "com.ktmrc390.obd.CAN_STOP"
        const val ACTION_MARKER = "com.ktmrc390.obd.CAN_MARKER"

        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"

        const val PREFS = "can_capture_state"
        const val KEY_RUNNING = "running"
        const val KEY_STATUS = "status"
        const val KEY_REPORT_PATH = "report_path"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_MARKER_COUNT = "marker_count"

        private const val CHANNEL_ID = "rc390_can_capture"
        private const val NOTIFICATION_ID = 390
        private const val CAN_MAX_LINES = 30000

        fun isRunning(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_RUNNING, false)

        fun status(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STATUS, "") ?: ""

        fun reportFile(context: Context): File? {
            val path = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_REPORT_PATH, null) ?: return null
            return File(path).takeIf { it.exists() }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val obd = ObdClient()
    private val fileLock = Any()

    @Volatile private var running = false
    private var writer: BufferedWriter? = null
    private var captureStartElapsed = 0L
    private var markerCount = 0
    private var storedLines = 0
    private var droppedLines = 0
    private var linesSinceFlush = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "OBDLink LX"
                if (!address.isNullOrBlank() && !running) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification("Préparation de la capture CAN")
                    )
                    running = true
                    setState(true, "Préparation capture CAN…", null, 0)
                    executor.execute { runCapture(address, name) }
                }
            }

            ACTION_MARKER -> addMarker()
            ACTION_STOP -> {
                setStatus("Arrêt capture CAN…")
                obd.stopMonitor()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun runCapture(address: String, deviceName: String) {
        var reportFile: File? = null
        try {
            val dir = File(filesDir, "captures").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            reportFile = File(dir, "RC390_V003_001_CAN_${stamp}.txt")
            writer = FileOutputStream(reportFile, false).bufferedWriter(Charsets.UTF_8)

            writeLine("RC390_V003_001 — capture CAN brute")
            writeLine("Début : ${Date()}")
            writeLine("Appareil : $deviceName")
            writeLine("Adresse Bluetooth : $address")
            writeLine("")

            setState(true, "Connexion OBDLink…", reportFile.absolutePath, 0)

            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: error("Bluetooth indisponible")
            val device = adapter.getRemoteDevice(address)

            obd.connect(device)
            writeLine("# Bluetooth connecté : $deviceName")

            obd.initialize().forEach { (cmd, response) ->
                writeLine("# $cmd -> ${oneLine(response)}")
            }

            val probe = obd.command("0100", 8000)
            writeLine("# 0100 -> ${oneLine(probe)}")
            if (!probe.uppercase().contains("4100")) {
                error("ECU non joignable")
            }

            obd.prepareCanMonitor().forEach { (cmd, response) ->
                writeLine("# $cmd -> ${oneLine(response)}")
            }

            markerCount = 0
            storedLines = 0
            droppedLines = 0
            linesSinceFlush = 0
            captureStartElapsed = SystemClock.elapsedRealtime()

            setState(true, "Capture CAN en cours", reportFile.absolutePath, markerCount)
            updateNotification("Capture CAN en cours")

            val received = obd.monitorAll { rawLine ->
                val elapsed = SystemClock.elapsedRealtime() - captureStartElapsed
                synchronized(fileLock) {
                    if (storedLines < CAN_MAX_LINES) {
                        writer?.apply {
                            write("$elapsed ms | $rawLine")
                            newLine()
                        }
                        storedLines += 1
                        linesSinceFlush += 1
                        if (linesSinceFlush >= 50) {
                            writer?.flush()
                            linesSinceFlush = 0
                        }
                    } else {
                        droppedLines += 1
                    }
                }
            }

            writeLine("")
            writeLine(
                "# FIN CAPTURE — lignes reçues: $received, " +
                    "stockées: $storedLines, ignorées: $droppedLines"
            )
            writeLine("Fin : ${Date()}")
            setState(false, "Capture CAN terminée", reportFile.absolutePath, markerCount)
        } catch (e: Exception) {
            try {
                writeLine("")
                writeLine("# ERREUR CAN : ${e.message}")
                writeLine("Fin : ${Date()}")
            } catch (_: Exception) {
            }
            setState(false, "Erreur capture CAN : ${e.message}", reportFile?.absolutePath, markerCount)
        } finally {
            try { obd.stopMonitor() } catch (_: Exception) {}
            try { obd.close() } catch (_: Exception) {}
            synchronized(fileLock) {
                try { writer?.flush() } catch (_: Exception) {}
                try { writer?.close() } catch (_: Exception) {}
                writer = null
            }
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun addMarker() {
        if (!running || captureStartElapsed == 0L) return
        markerCount += 1
        val elapsed = SystemClock.elapsedRealtime() - captureStartElapsed
        writeLine("$elapsed ms | # MARQUEUR $markerCount", flush = true)
        setState(true, "Capture CAN en cours — marqueur $markerCount", currentReportPath(), markerCount)
        updateNotification("Capture CAN — marqueur $markerCount")
    }

    private fun writeLine(text: String, flush: Boolean = false) {
        synchronized(fileLock) {
            writer?.apply {
                write(text)
                newLine()
                if (flush) flush()
            }
        }
    }

    private fun oneLine(value: String): String =
        value.replace("\r", " ").replace("\n", " ").trim()

    private fun currentReportPath(): String? =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REPORT_PATH, null)

    private fun setStatus(status: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, status)
            .apply()
        updateNotification(status)
    }

    private fun setState(
        isRunning: Boolean,
        status: String,
        reportPath: String?,
        markers: Int
    ) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RUNNING, isRunning)
            .putString(KEY_STATUS, status)
            .putString(KEY_REPORT_PATH, reportPath)
            .putLong(KEY_STARTED_AT, if (isRunning) System.currentTimeMillis() else 0L)
            .putInt(KEY_MARKER_COUNT, markers)
            .apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Capture CAN RC390",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Maintient la capture CAN active en arrière-plan"
                }
            )
        }
    }

    private fun buildNotification(text: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("RC390 — capture CAN")
                .setContentText(text)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("RC390 — capture CAN")
                .setContentText(text)
                .setOngoing(true)
                .build()
        }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        try { obd.stopMonitor() } catch (_: Exception) {}
        try { obd.close() } catch (_: Exception) {}
        executor.shutdownNow()
        super.onDestroy()
    }
}
