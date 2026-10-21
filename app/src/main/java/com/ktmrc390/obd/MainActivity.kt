package com.ktmrc390.obd

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val obd = ObdClient()

    private lateinit var deviceSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var connectButton: Button
    private lateinit var scanButton: Button
    private lateinit var dtcButton: Button
    private lateinit var sendButton: Button
    private lateinit var commandInput: EditText
    private lateinit var consolePanel: View
    private lateinit var graphPanel: View
    private lateinit var racingPanel: View
    private lateinit var graphBarsContainer: LinearLayout
    private lateinit var racingState: TextView
    private lateinit var racingAlerts: TextView
    private val devices = mutableListOf<BluetoothDevice>()
    private var currentDevice: BluetoothDevice? = null

    private val bluetoothAdapter: BluetoothAdapter?
        get() = BluetoothAdapter.getDefaultAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        connectButton = findViewById(R.id.connectButton)
        scanButton = findViewById(R.id.scanButton)
        dtcButton = findViewById(R.id.dtcButton)
        sendButton = findViewById(R.id.sendButton)
        commandInput = findViewById(R.id.commandInput)
        consolePanel = findViewById(R.id.consolePanel)
        graphPanel = findViewById(R.id.graphPanel)
        racingPanel = findViewById(R.id.racingPanel)
        graphBarsContainer = findViewById(R.id.graphBarsContainer)
        racingState = findViewById(R.id.racingState)
        racingAlerts = findViewById(R.id.racingAlerts)

        findViewById<Button>(R.id.navConsole).setOnClickListener { showPanel("console") }
        findViewById<Button>(R.id.navGraphique).setOnClickListener { showPanel("graph") }
        findViewById<Button>(R.id.navRacing).setOnClickListener { showPanel("racing") }
        populateGraphBars()
        findViewById<Button>(R.id.refreshButton).setOnClickListener { loadBondedDevices() }
        connectButton.setOnClickListener { toggleConnection() }
        scanButton.setOnClickListener { scanEcu() }
        dtcButton.setOnClickListener { readDtcs() }
        sendButton.setOnClickListener { sendRawCommand() }
        findViewById<Button>(R.id.copyButton).setOnClickListener { copyReport() }
        findViewById<Button>(R.id.shareButton).setOnClickListener { shareReport() }
        appendLog("RC390_V002_000")
        appendLog("Connexion directe Bluetooth SPP avec OBDLink LX.")
        ensureBluetoothPermission()
    }

    private fun showPanel(panel: String) {
        consolePanel.visibility = if (panel == "console") View.VISIBLE else View.GONE
        graphPanel.visibility = if (panel == "graph") View.VISIBLE else View.GONE
        racingPanel.visibility = if (panel == "racing") View.VISIBLE else View.GONE
    }

    private fun populateGraphBars() {
        graphBarsContainer.removeAllViews()
        SensorThresholds.base.forEach { sensor ->
            val bar = SensorBarView(this).apply {
                spec = sensor
                sensorValue = null
            }
            val lp = LinearLayout.LayoutParams(140, LinearLayout.LayoutParams.MATCH_PARENT)
            lp.setMargins(8, 0, 8, 0)
            graphBarsContainer.addView(bar, lp)
        }
    }

    private fun ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 100)
        } else loadBondedDevices()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadBondedDevices()
        else toast("Permission Bluetooth nécessaire")
    }

    @SuppressLint("MissingPermission")
    private fun loadBondedDevices() {
        val adapter = bluetoothAdapter ?: run { toast("Bluetooth indisponible"); return }
        if (!adapter.isEnabled) { toast("Active le Bluetooth puis Actualiser"); return }
        devices.clear()
        devices += adapter.bondedDevices.sortedBy { it.name ?: it.address }
        val labels = devices.map { (it.name ?: "Appareil Bluetooth") + " — " + it.address }
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels.ifEmpty { listOf("Aucun appareil appairé") })
        appendLog("Appareils appairés : " + devices.size)
    }

    @SuppressLint("MissingPermission")
    private fun toggleConnection() {
        if (obd.isConnected) {
            obd.close(); setConnected(false); appendLog("Déconnecté."); return
        }
        val device = devices.getOrNull(deviceSpinner.selectedItemPosition)
        if (device == null) { toast("OBDLink LX non trouvé. Appaire-le d’abord dans Android."); return }
        currentDevice = device
        val label = device.name ?: device.address
        setBusy(true, "Connexion à " + label + "…")
        executor.execute {
            try {
                obd.connect(device)
                postLog("Bluetooth connecté : " + label)
                obd.initialize().forEach { pair ->
                    postLog("> " + pair.first)
                    postLog(pair.second.ifBlank { "(réponse vide)" })
                }
                val probe = obd.command("0100", 8000)
                postLog("> 0100")
                postLog(probe.ifBlank { "(réponse vide)" })
                mainHandler.post { setConnected(true); statusText.text = "Connecté — ECU joignable" }
            } catch (e: Exception) {
                obd.close(); postLog("ERREUR connexion : " + e.message)
                mainHandler.post { setConnected(false); statusText.text = "Échec connexion" }
            } finally { mainHandler.post { setBusy(false, null) } }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureLiveConnection(): Boolean {
        val device = currentDevice ?: devices.getOrNull(deviceSpinner.selectedItemPosition) ?: return false

        try {
            val probe = obd.command("0100", 5000)
            if (probe.uppercase().contains("4100")) return true
        } catch (_: Exception) {
            // La socket Android peut rester "connected" alors que le lien RFCOMM est mort.
        }

        postLog("Liaison Bluetooth interrompue — reconnexion automatique…")
        return try {
            obd.close()
            obd.connect(device)
            postLog("Bluetooth reconnecté : " + (device.name ?: device.address))
            obd.initialize().forEach { pair ->
                postLog("> " + pair.first)
                postLog(pair.second.ifBlank { "(réponse vide)" })
            }
            val probe = obd.command("0100", 8000)
            postLog("> 0100 (test après reconnexion)")
            postLog(probe.ifBlank { "(réponse vide)" })
            probe.uppercase().contains("4100")
        } catch (e: Exception) {
            postLog("ERREUR reconnexion : " + e.message)
            false
        }
    }

    private fun scanEcu() {
        if (!obd.isConnected) return
        setBusy(true, "Scan des PID supportés…")
        executor.execute {
            try {
                if (!ensureLiveConnection()) error("ECU inaccessible après tentative de reconnexion")
                val result = PidScanner.scan(obd)
                postLog("=== SCAN PID SUPPORTÉS ===")
                result.transcript.forEach { pair -> postLog("> " + pair.first); postLog(pair.second.ifBlank { "(réponse vide)" }) }
                postLog("PID annoncés par l’ECU : " + result.supportedPids.size)
                result.supportedPids.forEach { pid -> postLog("01%02X  ".format(pid) + PidScanner.name(pid)) }
                postLog("=== LECTURE BRUTE DES PID ===")
                result.supportedPids
                    .filter { it !in setOf(0x20,0x40,0x60,0x80,0xA0,0xC0,0xE0) }
                    .forEach { pid ->
                        val cmd = "01%02X".format(pid)
                        val response = try { obd.command(cmd, 3500) } catch (e: Exception) { "ERREUR: " + e.message }
                        postLog("> " + cmd + "  " + PidScanner.name(pid))
                        postLog(response.ifBlank { "(réponse vide)" })
                    }
                val vin = try { obd.command("0902", 7000) } catch (_: Exception) { "" }
                postLog("> 0902  VIN")
                postLog(vin.ifBlank { "(non disponible)" })
                postLog("=== FIN DU SCAN ===")
            } catch (e: Exception) { postLog("ERREUR scan : " + e.message) }
            finally { mainHandler.post { setBusy(false, null) } }
        }
    }

    private fun readDtcs() {
        if (!obd.isConnected) return
        setBusy(true, "Lecture des DTC…")
        executor.execute {
            try {
                if (!ensureLiveConnection()) error("ECU inaccessible après tentative de reconnexion")
                val response = obd.command("03", 7000)
                postLog("> 03  DTC mémorisés")
                postLog(response.ifBlank { "(réponse vide)" })
                postLog("V0 : aucun effacement automatique des défauts.")
            } catch (e: Exception) { postLog("ERREUR DTC : " + e.message) }
            finally { mainHandler.post { setBusy(false, null) } }
        }
    }

    private fun sendRawCommand() {
        val cmd = commandInput.text.toString().trim()
        if (cmd.isBlank() || !obd.isConnected) return
        setBusy(true, "Commande " + cmd + "…")
        executor.execute {
            try {
                if (!ensureLiveConnection()) error("ECU inaccessible après tentative de reconnexion")
                val response = obd.command(cmd, 8000)
                postLog("> " + cmd.uppercase())
                postLog(response.ifBlank { "(réponse vide)" })
            } catch (e: Exception) { postLog("ERREUR commande : " + e.message) }
            finally { mainHandler.post { setBusy(false, null) } }
        }
    }

    private fun setConnected(connected: Boolean) {
        connectButton.text = if (connected) "Déconnexion" else "Connexion"
        scanButton.isEnabled = connected
        dtcButton.isEnabled = connected
        sendButton.isEnabled = connected
        if (!connected) statusText.text = "Déconnecté"
    }

    private fun setBusy(busy: Boolean, message: String?) {
        if (message != null) statusText.text = message
        connectButton.isEnabled = !busy
        scanButton.isEnabled = !busy && obd.isConnected
        dtcButton.isEnabled = !busy && obd.isConnected
        sendButton.isEnabled = !busy && obd.isConnected
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date())
        logText.append("[" + time + "] " + message + "\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun postLog(message: String) { mainHandler.post { appendLog(message) } }
    private fun reportText(): String = "RC390_V002_000 — rapport " + Date() + "\n\n" + logText.text

    private fun copyReport() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("RC390_V002_000 report", reportText()))
        toast("Rapport copié")
    }

    private fun shareReport() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "RC390_V002_000 — rapport")
            putExtra(Intent.EXTRA_TEXT, reportText())
        }
        startActivity(Intent.createChooser(intent, "Partager le rapport"))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        obd.close(); executor.shutdownNow(); super.onDestroy()
    }
}