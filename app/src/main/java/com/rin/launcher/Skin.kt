package com.rin.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Размеры считаются от дизайна 1920x1080 и масштабируются под экран. */
object Ui {
    var s: Float = 1f

    fun init(ctx: Context) {
        val m = ctx.resources.displayMetrics
        s = minOf(m.widthPixels / 1920f, m.heightPixels / 1080f)
        if (s < 0.3f) s = 0.3f
    }

    fun px(v: Int): Int = (v * s).toInt()
    fun pxf(v: Float): Float = v * s
}

fun withAlpha(c: Int, a: Int): Int {
    val aa = if (a < 0) 0 else if (a > 255) 255 else a
    return (c and 0x00FFFFFF) or (aa shl 24)
}

fun mixColor(a: Int, b: Int, t: Float): Int {
    val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
    val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
    val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    return Color.rgb(r, g, bl)
}

class RinTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val top: Int,
    val bottom: Int,
    val blob1: Int,
    val blob2: Int,
    val blob3: Int,
    val accent: Int
)

object Themes {
    private fun t(
        id: String, name: String, dark: Boolean,
        top: Long, bottom: Long, b1: Long, b2: Long, b3: Long, accent: Long
    ): RinTheme = RinTheme(
        id, name, dark, top.toInt(), bottom.toInt(),
        b1.toInt(), b2.toInt(), b3.toInt(), accent.toInt()
    )

    val dark: List<RinTheme> = listOf(
        t("midnight", "Полночь", true, 0xFF0A0F2C, 0xFF1B1F4B, 0xFF3A4BFF, 0xFF8A3FFC, 0xFF00C2FF, 0xFF6C8CFF),
        t("graphite", "Графит", true, 0xFF111214, 0xFF26282C, 0xFF5A5F69, 0xFF3A3D45, 0xFF7B808C, 0xFFB0B6C4),
        t("ocean", "Океан", true, 0xFF021B2B, 0xFF05425F, 0xFF0096C7, 0xFF00B4D8, 0xFF48CAE4, 0xFF4CC9F0),
        t("forest", "Лес", true, 0xFF07140C, 0xFF123524, 0xFF2D6A4F, 0xFF40916C, 0xFF95D5B2, 0xFF52B788),
        t("sunset", "Закат", true, 0xFF1F0A2E, 0xFF4A1942, 0xFFFF6B6B, 0xFFFFA94D, 0xFFC23CFF, 0xFFFF8E5E),
        t("aurora", "Аврора", true, 0xFF04121F, 0xFF0B2E3A, 0xFF00F5A0, 0xFF00D9F5, 0xFF9B5DE5, 0xFF3DF5C4),
        t("bordeaux", "Бордо", true, 0xFF1A0507, 0xFF3D0C14, 0xFFB3122E, 0xFF7A0C1E, 0xFFE63946, 0xFFFF5C6C),
        t("cosmos", "Космос", true, 0xFF05010F, 0xFF150A2E, 0xFF7B2FF7, 0xFFF107A3, 0xFF2D1B69, 0xFFB388FF),
        t("lava", "Лава", true, 0xFF140603, 0xFF3A0F06, 0xFFFF4500, 0xFFFF8C00, 0xFFB22222, 0xFFFF7A33),
        t("sakura", "Сакура", true, 0xFF1A0D1F, 0xFF3B1F3F, 0xFFFF8FB8, 0xFFB84F9E, 0xFF6A3A8C, 0xFFFF9CC8)
    )

    val light: List<RinTheme> = listOf(
        t("cloud", "Облако", false, 0xFFF4F6FB, 0xFFDCE3F2, 0xFF9DB7FF, 0xFFBFD0FF, 0xFFD6E0FF, 0xFF3B6CFF),
        t("mint", "Мята", false, 0xFFF0FBF6, 0xFFCFEFE1, 0xFF7ED9B0, 0xFFA8E8CB, 0xFFBDEFD8, 0xFF1FB57A),
        t("peach", "Персик", false, 0xFFFFF4EC, 0xFFFFD9C2, 0xFFFFB38A, 0xFFFFC9A6, 0xFFFFD9BE, 0xFFFF7A3D),
        t("lavender", "Лаванда", false, 0xFFF6F1FD, 0xFFE0D3F7, 0xFFB79CF2, 0xFFD0BCF7, 0xFFE6D8FB, 0xFF8A5CF0),
        t("sky", "Небо", false, 0xFFEEF7FF, 0xFFC9E6FB, 0xFF7CC3F5, 0xFFA6D8FA, 0xFFBFE3FC, 0xFF1D9BF0),
        t("lemon", "Лимон", false, 0xFFFFFCE8, 0xFFFBF0AE, 0xFFF7DC4C, 0xFFFAE67A, 0xFFFCEFA0, 0xFFE0A800),
        t("rose", "Роза", false, 0xFFFFF0F4, 0xFFFAD0DC, 0xFFF48FB1, 0xFFF8B4CB, 0xFFFBCBDA, 0xFFE8457A),
        t("sand", "Песок", false, 0xFFFAF5EC, 0xFFEBDDC3, 0xFFD9B98A, 0xFFE6CFA6, 0xFFF0DFBE, 0xFFB8803B),
        t("breeze", "Бриз", false, 0xFFEDFAFA, 0xFFC4ECEE, 0xFF6FD3D8, 0xFF9DE2E6, 0xFFB7EBEE, 0xFF14A9B0),
        t("vanilla", "Ваниль", false, 0xFFFFFAF0, 0xFFF5E8CC, 0xFFEBD3A0, 0xFFF1DFB8, 0xFFF7EBCF, 0xFFC99A3E)
    )

    val all: List<RinTheme> = dark + light

    fun byId(id: String): RinTheme {
        for (x in all) {
            if (x.id == id) return x
        }
        return all[0]
    }
}

/** Текущая тема и шрифт. Все самописные View берут цвета отсюда при отрисовке. */
object Skin {
    var theme: RinTheme = Themes.all[0]
    var typeface: Typeface = Typeface.DEFAULT

    val text1: Int
        get() = if (theme.dark) Color.WHITE else 0xFF1C1C1E.toInt()
    val text2: Int
        get() = if (theme.dark) 0xB3FFFFFF.toInt() else 0x99000000.toInt()
    val accent: Int
        get() = theme.accent
    val glass: Int
        get() = if (theme.dark) 0x30FFFFFF else 0xA6FFFFFF.toInt()
    val glassStroke: Int
        get() = if (theme.dark) 0x40FFFFFF else 0x22000000
    val rowFill: Int
        get() = if (theme.dark) 0x16FFFFFF else 0x0F000000
    val track: Int
        get() = if (theme.dark) 0x33FFFFFF else 0x22000000
    val panelFill: Int
        get() = if (theme.dark) withAlpha(mixColor(theme.bottom, Color.BLACK, 0.55f), 0xF2) else 0xF4FFFFFF.toInt()
    val border: Int
        get() = if (theme.dark) 0x33FFFFFF else 0x22000000
}

/** Роли текста: 1 основной, 2 второстепенный, 3 акцентный. +10 жирный, +100 не менять шрифт. */
object Style {
    fun applyText(tv: TextView) {
        val t = tv.tag as? Int ?: return
        val role = t % 10
        val bold = (t % 100) >= 10
        val keep = t >= 100
        val color = when (role) {
            1 -> Skin.text1
            2 -> Skin.text2
            else -> Skin.accent
        }
        tv.setTextColor(color)
        if (!keep) {
            tv.setTypeface(Skin.typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    fun restyle(v: View) {
        if (v is TextView) applyText(v)
        v.invalidate()
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                restyle(v.getChildAt(i))
            }
        }
    }

    fun styled(tv: TextView, size: Int, role: Int, bold: Boolean, keepFace: Boolean): TextView {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * Ui.s)
        tv.includeFontPadding = false
        tv.tag = role + (if (bold) 10 else 0) + (if (keepFace) 100 else 0)
        applyText(tv)
        return tv
    }
}

fun label(ctx: Context, text: String, size: Int, role: Int, bold: Boolean = false, keepFace: Boolean = false): TextView {
    val tv = TextView(ctx)
    tv.text = text
    return Style.styled(tv, size, role, bold, keepFace)
}

fun fmtSize(b: Long): String {
    val mb = b / 1048576.0
    return if (mb >= 1024.0) String.format(java.util.Locale.US, "%.1f ГБ", mb / 1024.0)
    else String.format(java.util.Locale.US, "%.0f МБ", mb)
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("rin_launcher", Context.MODE_PRIVATE)

    var themeId: String
        get() = sp.getString("theme", "midnight") ?: "midnight"
        set(v) { sp.edit().putString("theme", v).apply() }

    var fontIdx: Int
        get() = sp.getInt("font", 0)
        set(v) { sp.edit().putInt("font", v).apply() }

    var animations: Boolean
        get() = sp.getBoolean("anim", true)
        set(v) { sp.edit().putBoolean("anim", v).apply() }

    /** 0 мелкие, 1 средние, 2 крупные */
    var iconSize: Int
        get() = sp.getInt("icon_size", 1)
        set(v) { sp.edit().putInt("icon_size", v).apply() }

    var hidden: Set<String>
        get() = HashSet<String>(sp.getStringSet("hidden", emptySet<String>()) ?: emptySet<String>())
        set(v) { sp.edit().putStringSet("hidden", HashSet<String>(v)).apply() }
}
