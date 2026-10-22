package com.ktmrc390.obd

data class SensorThreshold(
    val key: String,
    val label: String,
    val unit: String,
    val displayMin: Double,
    val normalMin: Double,
    val normalMax: Double,
    val displayMax: Double,
    val critical: Boolean,
    val source: String,
    val note: String
)

object SensorThresholds {
    val base = listOf(
        SensorThreshold(
            key = "coolant",
            label = "T°E",
            unit = "°C",
            displayMin = 0.0,
            normalMin = 70.0,
            normalMax = 110.0,
            displayMax = 120.0,
            critical = true,
            source = "KTM RC 390 2022",
            note = "70 °C = moteur chaud pour les contrôles atelier ; 110 °C = température admissible indiquée par KTM."
        ),
        SensorThreshold(
            key = "voltage",
            label = "V",
            unit = "V",
            displayMin = 10.0,
            normalMin = 13.5,
            normalMax = 15.0,
            displayMax = 16.0,
            critical = true,
            source = "KTM RC 390 2022",
            note = "Spécification KTM à 5 000 tr/min."
        ),
        SensorThreshold(
            key = "rpmIdle",
            label = "RPM",
            unit = "tr/min",
            displayMin = 1000.0,
            normalMin = 1630.0,
            normalMax = 1730.0,
            displayMax = 2500.0,
            critical = false,
            source = "KTM RC 390 2022",
            note = "Valeur valable uniquement au ralenti : 1 680 ± 50 tr/min."
        )
    )
}
