package com.rin.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** Кружок выбора цвета часов. 0 — авто (чёрно-белый), 1 — цвет акцента темы. */
class ColorDot(ctx: Context, private val colorIdx: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val cy = height / 2f
        val r = w / 2f - Ui.pxf(2f)
        rect.set(cx - r, cy - r, cx + r, cy + r)
        p.style = Paint.Style.FILL
        if (colorIdx == 0) {
            p.color = 0xFF1C1C1E.toInt()
            canvas.drawArc(rect, 90f, 180f, true, p)
            p.color = Color.WHITE
            canvas.drawArc(rect, 270f, 180f, true, p)
        } else {
            p.color = ClockOpts.resolve(colorIdx)
            canvas.drawCircle(cx, cy, r, p)
        }
        if (colorIdx == 1) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = Ui.pxf(3.5f)
            p.color = Color.WHITE
            canvas.drawCircle(cx, cy, r * 0.5f, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = Ui.pxf(2f)
        p.color = Skin.border
        canvas.drawCircle(cx, cy, r, p)
    }
}

/** Переключатель-галочка в списке приложений. */
class CheckDot(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    var on: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val cy = height / 2f
        val r = minOf(w, height.toFloat()) / 2f - Ui.pxf(3f)
        if (on) {
            p.style = Paint.Style.FILL
            p.color = Skin.accent
            canvas.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = Ui.pxf(4.5f)
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            p.color = Color.WHITE
            path.reset()
            path.moveTo(cx - r * 0.42f, cy + r * 0.02f)
            path.lineTo(cx - r * 0.1f, cy + r * 0.36f)
            path.lineTo(cx + r * 0.46f, cy - r * 0.3f)
            canvas.drawPath(path, p)
        } else {
            p.style = Paint.Style.STROKE
            p.strokeWidth = Ui.pxf(3.5f)
            p.color = Skin.track
            canvas.drawCircle(cx, cy, r, p)
        }
    }
}

/** Миниатюра картинки со скруглёнными углами (кадрируется по центру). */
class PhotoView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val m = Matrix()
    private var shader: BitmapShader? = null

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            shader = if (value != null) BitmapShader(value, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) else null
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = Ui.pxf(22f)
        rect.set(0f, 0f, w, h)
        val b = bitmap
        val sh = shader
        if (b == null || sh == null) {
            p.shader = null
            p.style = Paint.Style.FILL
            p.color = Skin.track
            canvas.drawRoundRect(rect, rad, rad, p)
            return
        }
        val sc = maxOf(w / b.width, h / b.height)
        m.reset()
        m.setScale(sc, sc)
        m.postTranslate((w - b.width * sc) / 2f, (h - b.height * sc) / 2f)
        sh.setLocalMatrix(m)
        p.style = Paint.Style.FILL
        p.shader = sh
        canvas.drawRoundRect(rect, rad, rad, p)
        p.shader = null
    }
}
