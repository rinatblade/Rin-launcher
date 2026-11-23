package com.rin.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/** Готовый градиентный фон. dark = true, если на нём читается белый текст. */
class Wall(
    val id: String,
    val name: String,
    val dark: Boolean,
    val top: Int,
    val bottom: Int,
    val b1: Int,
    val b2: Int,
    val b3: Int
)

object Walls {
    private fun w(
        id: String, name: String, dark: Boolean,
        top: Long, bottom: Long, b1: Long, b2: Long, b3: Long
    ): Wall = Wall(id, name, dark, top.toInt(), bottom.toInt(), b1.toInt(), b2.toInt(), b3.toInt())

    val all: List<Wall> = listOf(
        w("sunset", "Закат", true, 0xFF3A1C71, 0xFFD76D77, 0xFFFFAF7B, 0xFFD76D77, 0xFF7B4397),
        w("flamingo", "Фламинго", true, 0xFF41295A, 0xFF2F0743, 0xFFF857A6, 0xFFFF5858, 0xFF8E2DE2),
        w("lagoon", "Лагуна", true, 0xFF0F2027, 0xFF2C5364, 0xFF00C6FF, 0xFF0072FF, 0xFF3CDBC0),
        w("aurora2", "Северное сияние", true, 0xFF0B1A2B, 0xFF0F3D3E, 0xFF00FFA3, 0xFF00C8FF, 0xFF8A5CFF),
        w("nightcity", "Ночной город", true, 0xFF0F0C29, 0xFF302B63, 0xFF24243E, 0xFFFF3CAC, 0xFF784BA0),
        w("raspberry", "Малина", true, 0xFF3B0A2A, 0xFF8E1B5A, 0xFFFF3D81, 0xFFC2185B, 0xFF7B1FA2),
        w("fog", "Лесной туман", true, 0xFF0B1F17, 0xFF2A5A45, 0xFF52B788, 0xFF95D5B2, 0xFF1B4332),
        w("golden", "Золотой час", true, 0xFF2B1B0E, 0xFF7A4A1E, 0xFFFFB347, 0xFFFF8C42, 0xFFC97B2D),
        w("space", "Космос", true, 0xFF02030A, 0xFF1A1040, 0xFF5B3DF5, 0xFF00D4FF, 0xFFFF4FD8),
        w("graphite2", "Графит", true, 0xFF0E0F12, 0xFF2B2D33, 0xFF5A5F69, 0xFF3A3D45, 0xFF7B808C),
        w("cloudy", "Облачко", false, 0xFFF6F9FF, 0xFFCFE0FF, 0xFFA5C4FF, 0xFFD9C8FF, 0xFFBFF0FF),
        w("peach2", "Персик", false, 0xFFFFE9DC, 0xFFFFB59A, 0xFFFF9A8B, 0xFFFFD1A8, 0xFFFFB3C7),
        w("icemint", "Мятный лёд", false, 0xFFE8FFF6, 0xFF9FE8D0, 0xFF6EE7B7, 0xFFB5F5E5, 0xFFA5E4FF),
        w("lemonade", "Лимонад", false, 0xFFFFFBD6, 0xFFFFE066, 0xFFFFD54A, 0xFFFFF2A8, 0xFFFFC48A),
        w("candy", "Сладкая вата", false, 0xFFFFE3F1, 0xFFD9C4FF, 0xFFFF9CCB, 0xFFC7A6FF, 0xFFA8D8FF),
        w("azure", "Лазурь", false, 0xFFDFF4FF, 0xFF7CCBFF, 0xFF4FB3FF, 0xFF9AE0FF, 0xFFC9F0FF)
    )

    fun byId(id: String): Wall {
        for (x in all) {
            if (x.id == id) return x
        }
        return all[0]
    }
}

/** Своя картинка для фона: копия хранится внутри приложения. */
object WallStore {
    fun file(ctx: Context): File = File(ctx.filesDir, "wall.jpg")

    fun exists(ctx: Context): Boolean = file(ctx).exists()

    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    fun importFrom(ctx: Context, uri: Uri): Boolean {
        try {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            val s1 = ctx.contentResolver.openInputStream(uri) ?: return false
            s1.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return false

            var sample = 1
            while (opts.outWidth / (sample * 2) >= 1920 && opts.outHeight / (sample * 2) >= 1080) {
                sample *= 2
            }
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            val s2 = ctx.contentResolver.openInputStream(uri) ?: return false
            var bmp: Bitmap = s2.use { BitmapFactory.decodeStream(it, null, o2) } ?: return false

            var rot = 0
            try {
                val s3 = ctx.contentResolver.openInputStream(uri)
                if (s3 != null) {
                    rot = s3.use {
                        val ex = ExifInterface(it)
                        when (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                    }
                }
            } catch (e: Exception) {
                rot = 0
            }
            if (rot != 0) {
                val m = Matrix()
                m.postRotate(rot.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            // Не храним больше, чем нужно для экрана 1920x1080
            val sc = maxOf(1920f / bmp.width, 1080f / bmp.height)
            if (sc < 1f) {
                bmp = Bitmap.createScaledBitmap(
                    bmp,
                    maxOf(1, (bmp.width * sc).toInt()),
                    maxOf(1, (bmp.height * sc).toInt()),
                    true
                )
            }
            FileOutputStream(file(ctx)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            return true
        } catch (e: Exception) {
            return false
        } catch (e: OutOfMemoryError) {
            return false
        }
    }

    /** blur: 0 нет, 1 слабое, 2 сильное. */
    fun load(ctx: Context, blur: Int): Bitmap? {
        try {
            val f = file(ctx)
            if (!f.exists()) return null
            var b: Bitmap = BitmapFactory.decodeFile(f.path) ?: return null
            if (blur > 0) {
                val d = if (blur == 1) 10 else 26
                val small = Bitmap.createScaledBitmap(b, maxOf(8, b.width / d), maxOf(8, b.height / d), true)
                b = Bitmap.createScaledBitmap(small, b.width, b.height, true)
            }
            return b
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    fun luminance(b: Bitmap): Float {
        val s = Bitmap.createScaledBitmap(b, 16, 9, true)
        var sum = 0f
        for (y in 0 until 9) {
            for (x in 0 until 16) {
                val c = s.getPixel(x, y)
                sum += (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
            }
        }
        return sum / (16f * 9f)
    }
}
