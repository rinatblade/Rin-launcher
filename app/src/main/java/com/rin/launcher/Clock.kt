package com.rin.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object ClockOpts {
    val styleNames: List<String> = listOf("Классика", "Экран блокировки", "Компакт", "Столбцом", "Аналоговые")
    val sizeNames: List<String> = listOf("Мини", "Малые", "Средние", "Крупные", "Огромные")
    val sizeF: FloatArray = floatArrayOf(0.6f, 0.8f, 1.0f, 1.3f, 1.6f)
    val alphaNames: List<String> = listOf("100%", "85%", "70%", "50%", "30%")
    val alphaV: FloatArray = floatArrayOf(1f, 0.85f, 0.7f, 0.5f, 0.3f)
    val posNames: List<String> = listOf("Слева", "По центру", "Справа")

    /** 0 = авто (по теме), 1 = цвет акцента темы, дальше обычные цвета. */
    val colors: IntArray = intArrayOf(
        0, 1,
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFD60A.toInt(), 0xFFFF9F0A.toInt(),
        0xFFFF453A.toInt(), 0xFFFF6B9D.toInt(), 0xFFBF5AF2.toInt(), 0xFF0A84FF.toInt(),
        0xFF64D2FF.toInt(), 0xFF30D158.toInt()
    )

    fun resolve(idx: Int): Int {
        val i = if (idx < 0 || idx >= colors.size) 0 else idx
        val c = colors[i]
        return if (c == 0) Skin.text1 else if (c == 1) Skin.accent else c
    }
}

/** Часы главного экрана. Стиль, размер, цвет и прозрачность берутся из настроек. */
class ClockView(ctx: Context, private val prefs: Prefs) : LinearLayout(ctx) {

    private class Piece(val tv: TextView, val pattern: String, val isTime: Boolean)

    private val pieces = ArrayList<Piece>()
    private var analog: AnalogClockView? = null
    private val ru = Locale("ru", "RU")
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            update()
            if (running) postDelayed(this, nextDelay())
        }
    }

    init {
        orientation = VERTICAL
        rebuild()
    }

    private fun nextDelay(): Long {
        val now = System.currentTimeMillis()
        return if (analog != null) 1000L - (now % 1000L) + 20L else 60000L - (now % 60000L) + 50L
    }

    fun rebuild() {
        removeAllViews()
        pieces.clear()
        analog = null
        val si = prefs.clockStyle.coerceIn(0, 4)
        val f = ClockOpts.sizeF[prefs.clockSize.coerceIn(0, 4)]
        gravity = when (prefs.clockPos) {
            0 -> Gravity.START
            1 -> Gravity.CENTER_HORIZONTAL
            else -> Gravity.END
        }
        val timeP = if (prefs.clock24) "HH:mm" else "h:mm"
        val hourP = if (prefs.clock24) "HH" else "h"
        val dateP = "EEEE, d MMMM"
        val showDate = prefs.clockDate

        when (si) {
            0 -> {
                addPiece(this, timeP, 104f * f, true, 0f, 0f)
                if (showDate) addPiece(this, dateP, 30f * f, false, 4f * f, 0f)
            }
            1 -> {
                if (showDate) addPiece(this, dateP, 32f * f, false, 0f, 0f)
                addPiece(this, timeP, 132f * f, true, -10f * f, 0f)
            }
            2 -> {
                val row = LinearLayout(context)
                row.orientation = HORIZONTAL
                row.gravity = Gravity.BOTTOM
                addPiece(row, timeP, 62f * f, true, 0f, 0f)
                if (showDate) addPiece(row, dateP, 28f * f, false, 0f, 18f * f)
                addView(row, LinearLayout.LayoutParams(-2, -2))
            }
            3 -> {
                addPiece(this, hourP, 120f * f, true, 0f, 0f)
                addPiece(this, "mm", 120f * f, true, -30f * f, 0f)
                if (showDate) addPiece(this, dateP, 30f * f, false, 6f * f, 0f)
            }
            else -> {
                val a = AnalogClockView(context, Ui.px((200f * f).toInt()))
                analog = a
                addView(a, LinearLayout.LayoutParams(a.dia, a.dia))
                if (showDate) addPiece(this, dateP, 28f * f, false, 12f * f, 0f)
            }
        }
        refreshStyle()
        update()
        if (running) {
            removeCallbacks(tick)
            post(tick)
        }
    }

    private fun addPiece(parent: LinearLayout, pattern: String, size: Float, isTime: Boolean, top: Float, left: Float) {
        val tv = TextView(context)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * Ui.s)
        tv.includeFontPadding = false
        tv.maxLines = 1
        tv.setShadowLayer(Ui.pxf(10f), 0f, Ui.pxf(2f), 0x33000000)
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.topMargin = (top * Ui.s).toInt()
        lp.leftMargin = (left * Ui.s).toInt()
        parent.addView(tv, lp)
        pieces.add(Piece(tv, pattern, isTime))
    }

    /** Применить цвет, прозрачность и шрифт (после смены темы, фона или шрифта). */
    fun refreshStyle() {
        val base = ClockOpts.resolve(prefs.clockColor)
        val a = ClockOpts.alphaV[prefs.clockAlpha.coerceIn(0, 4)]
        val tf = Skin.typeface
        for (p in pieces) {
            val mul = if (p.isTime) 1f else 0.85f
            p.tv.setTextColor(withAlpha(base, (Color.alpha(base) * a * mul).toInt()))
            p.tv.setTypeface(tf, if (p.isTime && prefs.clockBold) Typeface.BOLD else Typeface.NORMAL)
        }
        val an = analog
        if (an != null) {
            an.tint = base
            an.alphaMul = a
            an.invalidate()
        }
    }

    fun update() {
        val now = Date()
        for (p in pieces) {
            var s = SimpleDateFormat(p.pattern, ru).format(now)
            if (!p.isTime && s.isNotEmpty()) {
                s = s.substring(0, 1).uppercase(ru) + s.substring(1)
            }
            if (p.tv.text.toString() != s) p.tv.text = s
        }
        val an = analog
        if (an != null) {
            val c = Calendar.getInstance()
            an.setTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        removeCallbacks(tick)
        post(tick)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}

/** Аналоговые часы в стиле iOS: стеклянный циферблат, белые стрелки, оранжевая секундная. */
class AnalogClockView(ctx: Context, val dia: Int) : View(ctx) {
    var tint: Int = Color.WHITE
    var alphaMul: Float = 1f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hh = 0f
    private var mm = 0f
    private var ss = 0f

    fun setTime(h: Int, m: Int, s: Int) {
        hh = (h % 12) + m / 60f
        mm = m + s / 60f
        ss = s.toFloat()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dia, dia)
    }

    private fun col(c: Int, mul: Float): Int = withAlpha(c, (255f * alphaMul * mul).toInt())

    private fun hand(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float, tail: Float, wd: Float, color: Int) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = wd
        p.color = color
        c.save()
        c.rotate(deg, cx, cy)
        c.drawLine(cx, cy + tail, cx, cy - len, p)
        c.restore()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val cy = height / 2f
        val r = w / 2f - Ui.pxf(3f)
        val orange = 0xFFFF9F0A.toInt()

        p.strokeCap = Paint.Cap.ROUND
        p.style = Paint.Style.FILL
        p.color = Skin.glass
        canvas.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = Ui.pxf(2f)
        p.color = Skin.glassStroke
        canvas.drawCircle(cx, cy, r, p)

        for (i in 0 until 60) {
            val major = i % 5 == 0
            p.style = Paint.Style.STROKE
            p.strokeWidth = if (major) r * 0.03f else r * 0.015f
            p.color = col(tint, if (major) 0.95f else 0.45f)
            val len = if (major) r * 0.1f else r * 0.05f
            canvas.save()
            canvas.rotate(i * 6f, cx, cy)
            canvas.drawLine(cx, cy - r * 0.92f, cx, cy - r * 0.92f + len, p)
            canvas.restore()
        }

        hand(canvas, cx, cy, hh * 30f, r * 0.5f, r * 0.08f, r * 0.075f, col(tint, 1f))
        hand(canvas, cx, cy, mm * 6f, r * 0.78f, r * 0.08f, r * 0.05f, col(tint, 1f))
        hand(canvas, cx, cy, ss * 6f, r * 0.86f, r * 0.18f, r * 0.022f, col(orange, 1f))
        p.style = Paint.Style.FILL
        p.color = col(orange, 1f)
        canvas.drawCircle(cx, cy, r * 0.055f, p)
    }
}
