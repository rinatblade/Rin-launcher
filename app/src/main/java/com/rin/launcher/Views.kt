package com.rin.launcher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

object Shapes {
    /** Суперэллипс (как у значков iPhone). */
    fun squircle(w: Float, h: Float, inset: Float): Path {
        val p = Path()
        val a = (w - 2f * inset) / 2f
        val b = (h - 2f * inset) / 2f
        val cx = w / 2f
        val cy = h / 2f
        val steps = 96
        for (i in 0..steps) {
            val t = (i.toDouble() / steps) * 2.0 * Math.PI
            val ct = Math.cos(t)
            val st = Math.sin(t)
            val x = cx + (Math.signum(ct) * Math.pow(Math.abs(ct), 0.4) * a).toFloat()
            val y = cy + (Math.signum(st) * Math.pow(Math.abs(st), 0.4) * b).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }
}

private fun isFullBleed(d: Drawable): Boolean {
    try {
        if (d is BitmapDrawable) {
            val b = d.bitmap
            if (b != null && b.width > 8 && b.height > 8) {
                val a1 = Color.alpha(b.getPixel(2, 2))
                val a2 = Color.alpha(b.getPixel(b.width - 3, b.height - 3))
                val a3 = Color.alpha(b.getPixel(2, b.height - 3))
                val a4 = Color.alpha(b.getPixel(b.width - 3, 2))
                return a1 > 230 && a2 > 230 && a3 > 230 && a4 > 230
            }
        }
    } catch (e: Exception) {
        return false
    }
    return false
}

/** Рисует значок приложения в форме суперэллипса, как на iPhone. Можно вызывать из фонового потока. */
fun renderIconBitmap(d: Drawable?, size: Int, seed: Int): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val fs = size.toFloat()
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val hue = (((seed % 360) + 360) % 360).toFloat()
    val c1 = Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.95f))
    val c2 = Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.65f, 0.72f))
    var done = false
    try {
        if (d != null && Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable) {
            val bg = d.background
            val fg = d.foreground
            val pad = (size * 0.25f).toInt()
            if (bg != null) {
                bg.setBounds(-pad, -pad, size + pad, size + pad)
                bg.draw(c)
            }
            if (fg != null) {
                fg.setBounds(-pad, -pad, size + pad, size + pad)
                fg.draw(c)
            }
            done = true
        }
    } catch (e: Exception) {
        done = false
    }
    if (!done) {
        if (d != null && isFullBleed(d)) {
            d.setBounds(0, 0, size, size)
            d.draw(c)
        } else {
            p.shader = LinearGradient(0f, 0f, fs, fs, c1, c2, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, fs, fs, p)
            p.shader = null
            if (d != null) {
                val ins = (size * 0.17f).toInt()
                d.setBounds(ins, ins, size - ins, size - ins)
                d.draw(c)
            }
        }
    }
    // Лёгкий глянец сверху
    p.shader = LinearGradient(0f, 0f, 0f, fs * 0.55f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, fs, fs * 0.55f, p)
    p.shader = null
    // Маска суперэллипсом
    val mp = Paint(Paint.ANTI_ALIAS_FLAG)
    mp.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    c.drawPath(Shapes.squircle(fs, fs, 0f), mp)
    return bmp
}

/** Анимированный фон: плавный градиент и медленно дрейфующие цветные пятна. */
class BackgroundView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cur = IntArray(5)
    private val from = IntArray(5)
    private val to = IntArray(5)
    private val evaluator = ArgbEvaluator()
    private var phase = 0f
    private var drift: ValueAnimator? = null
    private var blend: ValueAnimator? = null
    private var bgShader: Shader? = null
    private val blobShaders = arrayOfNulls<Shader>(3)
    private var blobR = 0f
    private var lastFrame = 0L
    private var isDarkTheme = true
    private var running = true

    var animated: Boolean = true
        set(value) {
            field = value
            updateDrift()
        }

    fun setTheme(t: RinTheme, animate: Boolean) {
        val target = intArrayOf(t.top, t.bottom, t.blob1, t.blob2, t.blob3)
        isDarkTheme = t.dark
        blend?.cancel()
        if (!animate) {
            for (i in 0 until 5) cur[i] = target[i]
            rebuild()
            invalidate()
            return
        }
        for (i in 0 until 5) {
            from[i] = cur[i]
            to[i] = target[i]
        }
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 520
        a.addUpdateListener { v ->
            val f = v.animatedValue as Float
            for (i in 0 until 5) {
                cur[i] = evaluator.evaluate(f, from[i], to[i]) as Int
            }
            rebuild()
            invalidate()
        }
        blend = a
        a.start()
    }

    fun setRunning(r: Boolean) {
        running = r
        updateDrift()
    }

    private fun updateDrift() {
        drift?.cancel()
        drift = null
        if (!animated || !running || !isAttachedToWindow) return
        val a = ValueAnimator.ofFloat(0f, (2.0 * Math.PI).toFloat())
        a.duration = 50000
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { v ->
            phase = v.animatedValue as Float
            val now = SystemClock.uptimeMillis()
            if (now - lastFrame >= 33) {
                lastFrame = now
                invalidate()
            }
        }
        drift = a
        a.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateDrift()
    }

    override fun onDetachedFromWindow() {
        drift?.cancel()
        blend?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        if (width == 0 || height == 0) return
        bgShader = LinearGradient(0f, 0f, 0f, height.toFloat(), cur[0], cur[1], Shader.TileMode.CLAMP)
        blobR = maxOf(width, height) * 0.55f
        val alpha = if (isDarkTheme) 0x77 else 0x99
        for (i in 0 until 3) {
            val c = cur[2 + i]
            blobShaders[i] = RadialGradient(0f, 0f, blobR, withAlpha(c, alpha), withAlpha(c, 0), Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.shader = bgShader
        canvas.drawRect(0f, 0f, w, h, paint)
        for (i in 0 until 3) {
            val sh = blobShaders[i] ?: continue
            val ph = phase.toDouble() + i * 2.1
            val x = w * (0.5f + 0.38f * Math.sin(ph * (1.0 + i * 0.35)).toFloat())
            val y = h * (0.5f + 0.34f * Math.cos(ph * (0.8 + i * 0.3)).toFloat())
            paint.shader = sh
            canvas.save()
            canvas.translate(x, y)
            canvas.drawCircle(0f, 0f, blobR, paint)
            canvas.restore()
        }
        paint.shader = null
    }
}

/** Значок приложения: тень, суперэллипс, обводка и кольцо фокуса. */
class SquircleView(ctx: Context, private val sizePx: Int) : View(ctx) {
    private val margin = (sizePx * 0.1f).toInt()
    val total: Int = sizePx + 2 * margin
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val base: Path = Shapes.squircle(sizePx.toFloat(), sizePx.toFloat(), 0f)

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var ring: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(total, total)
    }

    override fun onDraw(canvas: Canvas) {
        val m = margin.toFloat()
        val s = sizePx.toFloat()
        paint.style = Paint.Style.FILL
        // Мягкая тень
        paint.color = 0x55000000
        canvas.save()
        canvas.translate(m, m + s * 0.05f)
        canvas.scale(0.96f, 0.96f, s / 2f, s / 2f)
        canvas.drawPath(base, paint)
        canvas.restore()
        paint.color = 0x22000000
        canvas.save()
        canvas.translate(m, m + s * 0.09f)
        canvas.drawPath(base, paint)
        canvas.restore()
        val b = bitmap
        if (b != null) canvas.drawBitmap(b, m, m, null)
        // Тонкая обводка
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, Ui.pxf(1.5f))
        paint.color = Skin.border
        canvas.save()
        canvas.translate(m, m)
        canvas.drawPath(base, paint)
        canvas.restore()
        // Кольцо фокуса
        val r = ring
        if (r > 0.01f) {
            val rr = if (r > 1f) 1f else r
            paint.strokeWidth = Ui.pxf(5f)
            paint.color = withAlpha(Skin.accent, (255 * rr).toInt())
            canvas.save()
            canvas.translate(m, m)
            canvas.scale(1f + 0.045f * rr, 1f + 0.045f * rr, s / 2f, s / 2f)
            canvas.drawPath(base, paint)
            canvas.restore()
        }
    }
}

/** Элемент, который подсвечивается и увеличивается при фокусе (пульт). */
class FocusLayout(
    ctx: Context,
    private val radius: Float,
    private val highlight: Boolean,
    private val focusScale: Float,
    private val fill: Boolean
) : LinearLayout(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var anim: ValueAnimator? = null
    var amount: Float = 0f
    var onAmount: ((Float) -> Unit)? = null

    init {
        isFocusable = true
        isClickable = true
        setWillNotDraw(false)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        animateTo(if (gainFocus) 1f else 0f)
    }

    private fun animateTo(target: Float) {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(amount, target)
        a.duration = if (target > 0f) 240L else 170L
        a.interpolator = if (target > 0f) OvershootInterpolator(1.5f) else DecelerateInterpolator()
        a.addUpdateListener { v -> applyAmount(v.animatedValue as Float) }
        anim = a
        a.start()
    }

    private fun applyAmount(v: Float) {
        amount = v
        val sc = 1f + (focusScale - 1f) * v
        scaleX = sc
        scaleY = sc
        val z = if (v < 0f) 0f else if (v > 1f) 1f else v
        translationZ = Ui.pxf(30f) * z
        onAmount?.invoke(v)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (fill) {
            paint.style = Paint.Style.FILL
            paint.color = Skin.rowFill
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        if (highlight && amount > 0.01f) {
            val a = if (amount > 1f) 1f else amount
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(Skin.accent, (0x55 * a).toInt())
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Ui.pxf(3f)
            paint.color = withAlpha(Skin.accent, (255 * a).toInt())
            rect.inset(Ui.pxf(1.5f), Ui.pxf(1.5f))
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        super.onDraw(canvas)
    }
}

/** Полупрозрачная «стеклянная» панель. panel=true — более плотная, для окон. */
class GlassLayout(ctx: Context, private val radius: Float, private val panel: Boolean) : LinearLayout(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.style = Paint.Style.FILL
        paint.color = if (panel) Skin.panelFill else Skin.glass
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.pxf(1.5f)
        paint.color = Skin.glassStroke
        rect.inset(Ui.pxf(0.75f), Ui.pxf(0.75f))
        canvas.drawRoundRect(rect, radius, radius, paint)
        super.onDraw(canvas)
    }
}

/** Простая сетка: все ячейки одного размера, лежат в одном контейнере (чтобы фокус поднимался поверх соседей). */
class SimpleGrid(ctx: Context, var cols: Int, var cellW: Int, var cellH: Int) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val n = childCount
        val rows = (n + cols - 1) / cols
        for (i in 0 until n) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, rows * cellH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val total = cols * cellW
        val startX = ((r - l) - total) / 2
        for (i in 0 until childCount) {
            val col = i % cols
            val row = i / cols
            val x = startX + col * cellW
            val y = row * cellH
            getChildAt(i).layout(x, y, x + cellW, y + cellH)
        }
    }
}

/** Индикатор заполнения (память, хранилище). */
class BarView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var shown = 0f
    private var anim: ValueAnimator? = null

    fun setProgress(target: Float, animate: Boolean) {
        val t = if (target < 0f) 0f else if (target > 1f) 1f else target
        anim?.cancel()
        if (!animate) {
            shown = t
            invalidate()
            return
        }
        val a = ValueAnimator.ofFloat(shown, t)
        a.duration = 700
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { v ->
            shown = v.animatedValue as Float
            invalidate()
        }
        anim = a
        a.start()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        rect.set(0f, 0f, w, h)
        paint.style = Paint.Style.FILL
        paint.color = Skin.track
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
        if (shown > 0.005f) {
            rect.set(0f, 0f, maxOf(h, w * shown), h)
            paint.shader = LinearGradient(0f, 0f, w, 0f, Skin.accent, mixColor(Skin.accent, Color.WHITE, 0.35f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
            paint.shader = null
        }
    }
}

/** Рисованные значки для встроенных функций. */
class GlyphView(
    ctx: Context,
    private val kind: Int,
    private val c1: Int,
    private val c2: Int,
    private val text: String?
) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rf = RectF()

    companion object {
        const val CLEAN = 0
        const val THEME = 1
        const val TEXT = 2
        const val GEAR = 3
        const val BOLT = 4
        const val GRID = 5
        const val WIFI = 6
        const val SCREEN = 7
        const val SOUND = 8
        const val CLOCK = 9
        const val GLOBE = 10
        const val STORAGE = 11
        const val HOME = 12
    }

    private fun star(c: Canvas, cx: Float, cy: Float, r: Float) {
        path.reset()
        path.moveTo(cx, cy - r)
        path.quadTo(cx, cy, cx + r, cy)
        path.quadTo(cx, cy, cx, cy + r)
        path.quadTo(cx, cy, cx - r, cy)
        path.quadTo(cx, cy, cx, cy - r)
        path.close()
        c.drawPath(path, p)
    }

    private fun poly(c: Canvas, vararg pts: Float) {
        path.reset()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) {
            path.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        path.close()
        c.drawPath(path, p)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, w, h, c1, c2, Shader.TileMode.CLAMP)
        canvas.drawPath(Shapes.squircle(w, h, 0f), p)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawPath(Shapes.squircle(w, h, 0f), p)
        p.shader = null

        val u = w / 100f
        canvas.save()
        canvas.scale(u, u)
        p.color = Color.WHITE
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        when (kind) {
            CLEAN -> {
                star(canvas, 44f, 54f, 27f)
                star(canvas, 75f, 26f, 11f)
                star(canvas, 73f, 78f, 9f)
            }
            THEME -> {
                canvas.drawCircle(50f, 50f, 31f, p)
                val cols = intArrayOf(0xFFFF5A5F.toInt(), 0xFFFFC107.toInt(), 0xFF4CAF50.toInt(), 0xFF2196F3.toInt())
                val xs = floatArrayOf(37f, 58f, 66f, 45f)
                val ys = floatArrayOf(40f, 35f, 55f, 64f)
                for (i in 0 until 4) {
                    p.color = cols[i]
                    canvas.drawCircle(xs[i], ys[i], 7f, p)
                }
            }
            TEXT -> {
                val tx = text ?: ""
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.DEFAULT_BOLD
                p.textSize = if (tx.length <= 2) 46f else 30f
                canvas.drawText(tx, 50f, 50f - (p.ascent() + p.descent()) / 2f, p)
            }
            GEAR -> {
                for (i in 0 until 8) {
                    canvas.save()
                    canvas.rotate(i * 45f, 50f, 50f)
                    rf.set(43f, 16f, 57f, 34f)
                    canvas.drawRoundRect(rf, 4f, 4f, p)
                    canvas.restore()
                }
                canvas.drawCircle(50f, 50f, 25f, p)
                p.color = mixColor(c1, c2, 0.5f)
                canvas.drawCircle(50f, 50f, 10f, p)
            }
            BOLT -> {
                poly(canvas, 57f, 12f, 28f, 56f, 46f, 56f, 41f, 88f, 72f, 41f, 53f, 41f)
            }
            GRID -> {
                val o = floatArrayOf(22f, 54f)
                for (a in 0 until 2) {
                    for (b in 0 until 2) {
                        rf.set(o[a], o[b], o[a] + 24f, o[b] + 24f)
                        canvas.drawRoundRect(rf, 7f, 7f, p)
                    }
                }
            }
            WIFI -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 7f
                val rs = floatArrayOf(16f, 30f, 44f)
                for (r in rs) {
                    rf.set(50f - r, 72f - r, 50f + r, 72f + r)
                    canvas.drawArc(rf, 225f, 90f, false, p)
                }
                p.style = Paint.Style.FILL
                canvas.drawCircle(50f, 72f, 5.5f, p)
            }
            SCREEN -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 6f
                rf.set(20f, 26f, 80f, 64f)
                canvas.drawRoundRect(rf, 7f, 7f, p)
                canvas.drawLine(50f, 64f, 50f, 76f, p)
                canvas.drawLine(36f, 78f, 64f, 78f, p)
            }
            SOUND -> {
                poly(canvas, 22f, 40f, 36f, 40f, 52f, 25f, 52f, 75f, 36f, 60f, 22f, 60f)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 6f
                rf.set(40f, 36f, 68f, 64f)
                canvas.drawArc(rf, -50f, 100f, false, p)
                rf.set(30f, 26f, 78f, 74f)
                canvas.drawArc(rf, -50f, 100f, false, p)
            }
            CLOCK -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 6f
                canvas.drawCircle(50f, 50f, 30f, p)
                canvas.drawLine(50f, 50f, 50f, 32f, p)
                canvas.drawLine(50f, 50f, 63f, 57f, p)
            }
            GLOBE -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 5f
                canvas.drawCircle(50f, 50f, 30f, p)
                rf.set(36f, 20f, 64f, 80f)
                canvas.drawOval(rf, p)
                canvas.drawLine(20f, 50f, 80f, 50f, p)
            }
            STORAGE -> {
                rf.set(22f, 24f, 78f, 40f)
                canvas.drawRoundRect(rf, 6f, 6f, p)
                rf.set(22f, 44f, 78f, 60f)
                canvas.drawRoundRect(rf, 6f, 6f, p)
                rf.set(22f, 64f, 78f, 80f)
                canvas.drawRoundRect(rf, 6f, 6f, p)
            }
            HOME -> {
                poly(canvas, 50f, 20f, 80f, 46f, 71f, 46f, 71f, 78f, 29f, 78f, 29f, 46f, 20f, 46f)
            }
            else -> {
                canvas.drawCircle(50f, 50f, 20f, p)
            }
        }
        canvas.restore()
    }
}

/** Миниатюра темы в списке тем. */
class ThemeSwatch(ctx: Context, private val theme: RinTheme) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = Ui.pxf(26f)
        rect.set(0f, 0f, w, h)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, h, theme.top, theme.bottom, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, rad, rad, p)
        p.shader = null
        p.color = withAlpha(theme.blob1, 0xCC)
        canvas.drawCircle(w * 0.28f, h * 0.42f, h * 0.22f, p)
        p.color = withAlpha(theme.blob2, 0xCC)
        canvas.drawCircle(w * 0.55f, h * 0.60f, h * 0.18f, p)
        p.color = withAlpha(theme.blob3, 0xCC)
        canvas.drawCircle(w * 0.75f, h * 0.34f, h * 0.15f, p)
        // Акцентная точка
        p.color = theme.accent
        canvas.drawCircle(w * 0.86f, h * 0.82f, h * 0.075f, p)
        // Рамка
        p.style = Paint.Style.STROKE
        p.strokeWidth = Ui.pxf(2f)
        p.color = if (theme.dark) 0x44FFFFFF else 0x33000000
        canvas.drawRoundRect(rect, rad, rad, p)
        if (Skin.theme.id == theme.id) {
            p.strokeWidth = Ui.pxf(5f)
            p.color = Skin.accent
            rect.inset(Ui.pxf(2f), Ui.pxf(2f))
            canvas.drawRoundRect(rect, rad, rad, p)
            // Галочка
            val cx = w * 0.14f
            val cy = h * 0.22f
            val r = h * 0.13f
            p.style = Paint.Style.FILL
            p.color = Skin.accent
            canvas.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = Ui.pxf(4f)
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            p.color = Color.WHITE
            val path = Path()
            path.moveTo(cx - r * 0.45f, cy)
            path.lineTo(cx - r * 0.08f, cy + r * 0.38f)
            path.lineTo(cx + r * 0.5f, cy - r * 0.34f)
            canvas.drawPath(path, p)
        }
    }
}

/** Окно поверх главного экрана: затемнение + карточка с заголовком и прокручиваемым содержимым. */
class PanelFrame(ctx: Context, title: String, subtitle: String, cardW: Int, cardH: Int) : FrameLayout(ctx) {
    val card: GlassLayout = GlassLayout(ctx, Ui.pxf(44f), true)
    val body: LinearLayout = LinearLayout(ctx)
    val scroll: ScrollView = ScrollView(ctx)

    init {
        val dim = View(ctx)
        dim.setBackgroundColor(0xAA000000.toInt())
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        card.orientation = LinearLayout.VERTICAL
        card.setPadding(Ui.px(52), Ui.px(40), Ui.px(52), Ui.px(36))
        card.addView(label(ctx, title, 50, 1, true))
        if (subtitle.isNotEmpty()) {
            val st = label(ctx, subtitle, 25, 2, false)
            card.addView(st, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = Ui.px(8) })
        }
        body.orientation = LinearLayout.VERTICAL
        scroll.isVerticalScrollBarEnabled = false
        scroll.isVerticalFadingEdgeEnabled = true
        scroll.setFadingEdgeLength(Ui.px(40))
        scroll.clipToPadding = false
        scroll.setPadding(Ui.px(10), Ui.px(14), Ui.px(10), Ui.px(14))
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val slp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        slp.topMargin = Ui.px(18)
        card.addView(scroll, slp)
        addView(card, LayoutParams(cardW, cardH, Gravity.CENTER))
    }
}

fun firstFocusable(v: View): View? {
    if (v.visibility != View.VISIBLE) return null
    if (v.isFocusable && v !is ScrollView) return v
    if (v is ViewGroup) {
        for (i in 0 until v.childCount) {
            val r = firstFocusable(v.getChildAt(i))
            if (r != null) return r
        }
    }
    return null
}
