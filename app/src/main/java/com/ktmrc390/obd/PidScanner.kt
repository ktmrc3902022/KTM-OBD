package com.ktmrc390.obd

object PidScanner {
    data class ScanResult(val supportedPids: List<Int>, val transcript: List<Pair<String,String>>)

    private val names = mapOf(
        0x04 to "Charge moteur calculée",
        0x05 to "Température liquide refroidissement",
        0x0B to "Pression admission MAP",
        0x0C to "Régime moteur",
        0x0D to "Vitesse véhicule",
        0x0F to "Température air admission",
        0x11 to "Position papillon",
        0x1F to "Temps moteur tournant",
        0x2F to "Niveau carburant",
        0x33 to "Pression barométrique",
        0x42 to "Tension module de commande",
        0x5C to "Température huile moteur",
        0x5E to "Débit carburant moteur"
    )

    fun name(pid: Int): String = names[pid] ?: "PID standard 0x%02X".format(pid)

    fun scan(client: ObdClient): ScanResult {
        val transcript = mutableListOf<Pair<String,String>>()
        val supported = linkedSetOf<Int>()
        var base = 0x00
        while (base <= 0xE0) {
            val cmd = "01%02X".format(base)
            val response = client.command(cmd, 7000)
            transcript += cmd to response
            val mask = parseMask(response, base) ?: break
            for (i in 0 until 32) {
                val bit = 31 - i
                if ((mask and (1L shl bit)) != 0L) supported += base + i + 1
            }
            val nextRangePid = base + 0x20
            if (nextRangePid !in supported) break
            base += 0x20
        }
        return ScanResult(supported.toList().sorted(), transcript)
    }

    private fun parseMask(response: String, base: Int): Long? {
        val compact = response.uppercase().replace(Regex("[^0-9A-F]"), "")
        val marker = "41%02X".format(base)
        val idx = compact.indexOf(marker)
        if (idx < 0 || compact.length < idx + marker.length + 8) return null
        return compact.substring(idx + marker.length, idx + marker.length + 8).toLongOrNull(16)
    }
}