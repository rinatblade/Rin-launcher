package com.rin.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import java.io.File

class AppEntry(
    val label: String,
    val pkg: String,
    val activity: String,
    val system: Boolean
) {
    var icon: Drawable? = null
    var bmp: Bitmap? = null
    var size: Long = 0L
}

class MenuItem(val label: String, val action: () -> Unit)

class RowRef(val view: FocusLayout, val sub: android.widget.TextView?)

object Apps {
    /** Все приложения, которые можно запустить с главного экрана (Android TV и обычные). */
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val map = LinkedHashMap<String, AppEntry>()
        val cats = arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        for (cat in cats) {
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory(cat)
            val list = try {
                pm.queryIntentActivities(i, 0)
            } catch (e: Exception) {
                emptyList()
            }
            for (ri in list) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName) continue
                if (map.containsKey(pkg)) continue
                val ai = ri.activityInfo.applicationInfo
                val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val label = try {
                    ri.loadLabel(pm).toString()
                } catch (e: Exception) {
                    pkg
                }
                val e = AppEntry(label, pkg, ri.activityInfo.name, sys)
                e.icon = try {
                    ri.loadIcon(pm)
                } catch (ex: Exception) {
                    null
                }
                e.size = try {
                    File(ai.sourceDir).length()
                } catch (ex: Exception) {
                    0L
                }
                map[pkg] = e
            }
        }
        val out = ArrayList<AppEntry>(map.values)
        out.sortBy { it.label.lowercase() }
        return out
    }
}
