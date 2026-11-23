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

private class IconInfo(val fullBleed: Boolean, val lum: Float, val aspect: Float)

private fun drawableToBitmap(d: Drawable, maxSide: Int): Bitmap {
    var w = d.intrinsicWidth
    var h = d.intrinsicHeight
    if (w <= 0 || h <= 0) {
        w = maxSide
        h = maxSide
    }
    val sc = maxSide.toFloat() / maxOf(w, h).toFloat()
    val bw = maxOf(1, (w * sc).toInt())
    val bh = maxOf(1, (h * sc).toInt())
    val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    d.setBounds(0, 0, bw, bh)
    d.draw(c)
    return b
}

private fun analyzeIcon(b: Bitmap): IconInfo {
    val s = Bitmap.createScaledBitmap(b, 24, 24, true)
    var corners = 0
    if (Color.alpha(s.getPixel(0, 0)) > 230) corners++
    if (Color.alpha(s.getPixel(23, 0)) > 230) corners++
    if (Color.alpha(s.getPixel(0, 23)) > 230) corners++
    if (Color.alpha(s.getPixel(23, 23)) > 230) corners++
    var sum = 0f
    var cnt = 0f
    for (y in 0 until 24) {
        for (x in 0 until 24) {
            val px = s.getPixel(x, y)
            if (Color.alpha(px) > 40) {
                sum += (0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)) / 255f
                cnt += 1f
            }
        }
    }
    val lum = if (cnt > 0f) sum / cnt else 0.5f
    return IconInfo(corners == 4, lum, b.width.toFloat() / b.height.toFloat())
}

/**
 * Рисует значок приложения как на iPhone: суперэллипс, мягкий глянец, тонкая внутренняя обводка.
 * Квадратные значки заполняют плитку целиком, значки с прозрачностью кладутся на светлую или цветную подложку,
 * широкие баннеры (как у приложений Android TV) вписываются на размытый фон из самих себя.
 * Можно вызывать из фонового потока.
 */
fun renderIconBitmap(d: Drawable?, size: Int, seed: Int): Bitmap {
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    val fs = size.toFloat()
    val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    val hue = (((seed % 360) + 360) % 360).toFloat()
    val col1 = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.92f))
    val col2 = Color.HSVToColor(floatArrayOf((hue + 30f) % 360f, 0.78f, 0.62f))

    var src: Bitmap? = null
    var adaptive = false
    if (d != null) {
        try {
            val dd: Drawable = d.constantState?.newDrawable()?.mutate() ?: d
            if (Build.VERSION.SDK_INT >= 26 && dd is AdaptiveIconDrawable) {
                val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val cc = Canvas(b)
                val pad = (size * 0.25f).toInt()
                val bgd = dd.background
                val fgd = dd.foreground
                if (bgd != null) {
                    bgd.setBounds(-pad, -pad, size + pad, size + pad)
                    bgd.draw(cc)
                }
                if (fgd != null) {
                    fgd.setBounds(-pad, -pad, size + pad, size + pad)
                    fgd.draw(cc)
                }
                src = b
                adaptive = true
            } else {
                src = drawableToBitmap(dd, size)
            }
        } catch (e: Exception) {
            src = null
        }
    }

    val s = src
    if (s == null) {
        p.shader = LinearGradient(0f, 0f, fs, fs, col1, col2, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fs, fs, p)
        p.shader = null
    } else if (adaptive) {
        c.drawBitmap(s, 0f, 0f, p)
    } else {
        val info = analyzeIcon(s)
        val squareish = info.aspect >= 0.8f && info.aspect <= 1.25f
        if (info.fullBleed && squareish) {
            val side = minOf(s.width, s.height)
            val sr = Rect((s.width - side) / 2, (s.height - side) / 2, (s.width + side) / 2, (s.height + side) / 2)
            c.drawBitmap(s, sr, RectF(0f, 0f, fs, fs), p)
        } else {
            if (!squareish) {
                val tiny = Bitmap.createScaledBitmap(s, 6, 6, true)
                val back = Bitmap.createScaledBitmap(tiny, size, size, true)
                c.drawBitmap(back, 0f, 0f, p)
                p.color = 0x26000000
                c.drawRect(0f, 0f, fs, fs, p)
            } else if (info.lum > 0.65f) {
                p.shader = LinearGradient(0f, 0f, fs, fs, col1, col2, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, fs, fs, p)
                p.shader = null
            } else {
                p.shader = LinearGradient(0f, 0f, 0f, fs, 0xFFFFFFFF.toInt(), 0xFFE2E6EE.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, fs, fs, p)
                p.shader = null
            }
            val box = fs * (if (squareish) 0.66f else 0.9f)
            val sc = minOf(box / s.width.toFloat(), box / s.height.toFloat())
            val dw = s.width * sc
            val dh = s.height * sc
            val dst = RectF((fs - dw) / 2f, (fs - dh) / 2f, (fs + dw) / 2f, (fs + dh) / 2f)
            c.drawBitmap(s, null, dst, p)
        }
    }

    // Глянец сверху и лёгкое затемнение снизу
    p.shader = LinearGradient(0f, 0f, 0f, fs * 0.55f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, fs, fs * 0.55f, p)
    p.shader = LinearGradient(0f, fs * 0.55f, 0f, fs, 0x00000000, 0x16000000, Shader.TileMode.CLAMP)
    c.drawRect(0f, fs * 0.55f, fs, fs, p)
    p.shader = null

    // Маска суперэллипсом
    val mp = Paint(Paint.ANTI_ALIAS_FLAG)
    mp.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    c.drawPath(Shapes.squircle(fs, fs, 0f), mp)

    // Тонкая внутренняя обводка
    val sw = maxOf(1f, fs * 0.012f)
    p.style = Paint.Style.STROKE
    p.strokeWidth = sw
    p.color = 0x33FFFFFF
    c.drawPath(Shapes.squircle(fs, fs, sw / 2f), p)
    return out
}

/** Фон: плавный градиент с дрейфующими пятнами или своя картинка (с затемнением). */
class BackgroundView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cur = IntArray(5)
    private val from = IntArray(5)
    private val to = IntArray(5)
    private val evaluator = ArgbEvaluator()
    private var phase = 0f
    private var drift: ValueAnimator? = null
    private var blend: ValueAnimator? = null
    private var fade: ValueAnimator? = null
    private var bgShader: Shader? = null
    private val blobShaders = arrayOfNulls<Shader>(3)
    private var blobR = 0f
    private var lastFrame = 0L
    private var isDarkPalette = true
    private var running = true
    private var image: Bitmap? = null
    private var imageAlpha = 1f
    private var dim = 0f
    private val srcRect = Rect()
    private val dstRect = Rect()

    var animated: Boolean = true
        set(value) {
            field = value
            updateDrift()
        }

    fun setTheme(t: RinTheme, animate: Boolean) {
        setPalette(t.top, t.bottom, t.blob1, t.blob2, t.blob3, t.dark, animate)
    }

    fun setPalette(top: Int, bottom: Int, b1: Int, b2: Int, b3: Int, dark: Boolean, animate: Boolean) {
        val target = intArrayOf(top, bottom, b1, b2, b3)
        isDarkPalette = dark
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

    /** Своя картинка поверх градиента. null — убрать картинку. */
    fun setImage(b: Bitmap?, dimAmount: Float, animate: Boolean) {
        dim = dimAmount
        fade?.cancel()
        image = b
        updateDrift()
        if (b == null) {
            imageAlpha = 1f
            invalidate()
            return
        }
        if (!animate) {
            imageAlpha = 1f
            invalidate()
            return
        }
        imageAlpha = 0f
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 600
        a.addUpdateListener { v ->
            imageAlpha = v.animatedValue as Float
            invalidate()
        }
        fade = a
        a.start()
    }

    fun setRunning(r: Boolean) {
        running = r
        updateDrift()
    }

    private fun updateDrift() {
        drift?.cancel()
        drift = null
        if (!animated || !running || !isAttachedToWindow || image != null) return
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
        fade?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        if (width == 0 || height == 0) return
        bgShader = LinearGradient(0f, 0f, width * 0.45f, height.toFloat(), cur[0], cur[1], Shader.TileMode.CLAMP)
        blobR = maxOf(width, height) * 0.55f
        val alpha = if (isDarkPalette) 0x77 else 0x99
        for (i in 0 until 3) {
            val c = cur[2 + i]
            blobShaders[i] = RadialGradient(0f, 0f, blobR, withAlpha(c, alpha), withAlpha(c, 0), Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val img = image
        val covered = img != null && imageAlpha >= 0.999f
        if (!covered) {
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
        if (img != null) {
            val bw = img.width.toFloat()
            val bh = img.height.toFloat()
            val scale = maxOf(w / bw, h / bh)
            val sw = w / scale
            val sh = h / scale
            srcRect.set(((bw - sw) / 2f).toInt(), ((bh - sh) / 2f).toInt(), ((bw + sw) / 2f).toInt(), ((bh + sh) / 2f).toInt())
            dstRect.set(0, 0, width, height)
            paint.shader = null
            paint.alpha = (255f * imageAlpha).toInt()
            canvas.drawBitmap(img, srcRect, dstRect, paint)
            paint.alpha = 255
            if (dim > 0.001f) {
                paint.color = Color.argb((255f * dim * imageAlpha).toInt(), 0, 0, 0)
                canvas.drawRect(0f, 0f, w, h, paint)
            }
        }
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

    /** Отмечен (выбранный вариант в списке вариантов). */
    var marked: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

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
        if (marked) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(Skin.accent, 0x3A)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Ui.pxf(2.5f)
            paint.color = withAlpha(Skin.accent, 0xCC)
            rect.inset(Ui.pxf(1.25f), Ui.pxf(1.25f))
            canvas.drawRoundRect(rect, radius, radius, paint)
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
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
        const val WALL = 13
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
            WALL -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 5f
                rf.set(20f, 24f, 80f, 76f)
                canvas.drawRoundRect(rf, 8f, 8f, p)
                p.style = Paint.Style.FILL
                canvas.drawCircle(38f, 42f, 6f, p)
                poly(canvas, 22f, 72f, 40f, 54f, 52f, 64f, 64f, 50f, 78f, 72f)
            }
            else -> {
                canvas.drawCircle(50f, 50f, 20f, p)
            }
        }
        canvas.restore()
    }
}

/** Миниатюра темы в списке тем. */
class ThemeSwatch(ctx: Context, private val theme: RinTheme, private val selected: (() -> Boolean)? = null) : View(ctx) {
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
        val sel = selected?.invoke() ?: (Skin.theme.id == theme.id)
        if (sel) {
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
class PanelFrame(
    ctx: Context, title: String, subtitle: String, cardW: Int, cardH: Int,
    dimColor: Int = 0xAA000000.toInt(), bottom: Boolean = false
) : FrameLayout(ctx) {
    val card: GlassLayout = GlassLayout(ctx, Ui.pxf(44f), true)
    val body: LinearLayout = LinearLayout(ctx)
    val scroll: ScrollView = ScrollView(ctx)

    /** Вызывается, когда окно закрывается. */
    var onClose: (() -> Unit)? = null

    init {
        val dim = View(ctx)
        dim.setBackgroundColor(dimColor)
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
        val clp = LayoutParams(cardW, cardH, if (bottom) (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) else Gravity.CENTER)
        if (bottom) clp.bottomMargin = Ui.px(36)
        addView(card, clp)
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
