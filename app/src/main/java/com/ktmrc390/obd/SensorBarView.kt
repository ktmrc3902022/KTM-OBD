package com.ktmrc390.obd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

class SensorBarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textAlign = Paint.Align.CENTER
    }

    var spec: SensorThreshold? = null
        set(value) {
            field = value
            invalidate()
        }

    var sensorValue: Double? = null
        set(value) {
            field = value
            invalidate()
        }

    init {
        minimumWidth = 120
        minimumHeight = 520
        setPadding(12, 12, 12, 12)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(140, widthMeasureSpec), resolveSize(560, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = spec ?: return

        val w = width.toFloat()
        val h = height.toFloat()
        val top = 70f
        val bottom = h - 95f
        val left = w * 0.28f
        val right = w * 0.72f
        val full = s.displayMax - s.displayMin
        if (full <= 0.0) return

        fun yFor(v: Double): Float {
            val clamped = max(s.displayMin, min(s.displayMax, v))
            val ratio = (clamped - s.displayMin) / full
            return (bottom - ratio * (bottom - top)).toFloat()
        }

        val yLow = yFor(s.normalMin)
        val yHigh = yFor(s.normalMax)

        paint.color = Color.rgb(211, 47, 47)
        canvas.drawRect(left, top, right, yHigh, paint)
        paint.color = Color.rgb(46, 160, 67)
        canvas.drawRect(left, yHigh, right, yLow, paint)
        paint.color = Color.rgb(255, 140, 0)
        canvas.drawRect(left, yLow, right, bottom, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.DKGRAY
        canvas.drawRect(RectF(left, top, right, bottom), paint)
        paint.style = Paint.Style.FILL

        textPaint.textSize = 27f
        textPaint.isFakeBoldText = true
        canvas.drawText(s.label, w / 2f, 32f, textPaint)

        textPaint.textSize = 22f
        textPaint.isFakeBoldText = false
        val valueText = sensorValue?.let { formatValue(it) + " " + s.unit } ?: "-- " + s.unit
        canvas.drawText(valueText, w / 2f, h - 55f, textPaint)

        sensorValue?.let { v ->
            val y = yFor(v)
            paint.color = Color.BLACK
            canvas.drawCircle(w / 2f, y, 9f, paint)
            canvas.drawLine(left - 8f, y, right + 8f, y, paint)
        }
    }

    private fun formatValue(v: Double): String =
        if (kotlin.math.abs(v - v.toInt()) < 0.05) v.toInt().toString() else "%.1f".format(v)
}
