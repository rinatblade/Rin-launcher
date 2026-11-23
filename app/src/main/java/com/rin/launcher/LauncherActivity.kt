package com.rin.launcher

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.content.ContentUris
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.io.File

class LauncherActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var bg: BackgroundView
    private lateinit var content: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var grid: SimpleGrid

    private val handler = Handler(Looper.getMainLooper())
    private var apps: List<AppEntry> = emptyList()
    private val panels = ArrayList<View>()
    private var receiver: BroadcastReceiver? = null
    private var savedFocus: View? = null
    private lateinit var clock: ClockView
    private lateinit var emptyHint: TextView

    private var memText: TextView? = null
    private var memBar: BarView? = null
    private var stoText: TextView? = null
    private var stoBar: BarView? = null

    private val reloadRunnable = Runnable { reloadApps() }

    // ------------------------------------------------------------------ жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        prefs = Prefs(this)
        Skin.theme = Themes.byId(prefs.themeId)
        val fi = prefs.fontIdx
        val idx = if (fi < 0 || fi >= Fonts.all.size) 0 else fi
        Skin.typeface = Fonts.load(this, Fonts.all[idx])

        root = FrameLayout(this)
        bg = BackgroundView(this)
        bg.animated = prefs.animations
        bg.setTheme(Skin.theme, false)
        root.addView(bg, FrameLayout.LayoutParams(-1, -1))
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        overlay = FrameLayout(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        buildHome()
        applyBackground(false)
        setContentView(root)
        registerPackageReceiver()
        reloadApps()
    }

    override fun onResume() {
        super.onResume()
        bg.setRunning(true)
        clock.update()
        content.animate().cancel()
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
    }

    override fun onPause() {
        bg.setRunning(false)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val r = receiver
        if (r != null) {
            try {
                unregisterReceiver(r)
            } catch (e: Exception) {
            }
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null && Intent.ACTION_MAIN == intent.action) {
            closeAllPanels()
            scroll.smoothScrollTo(0, 0)
            focusFirst()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!popPanel()) {
            scroll.smoothScrollTo(0, 0)
            focusFirst()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (panels.isEmpty()) openSettings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun registerPackageReceiver() {
        val f = IntentFilter()
        f.addAction(Intent.ACTION_PACKAGE_ADDED)
        f.addAction(Intent.ACTION_PACKAGE_REMOVED)
        f.addAction(Intent.ACTION_PACKAGE_REPLACED)
        f.addDataScheme("package")
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                handler.removeCallbacks(reloadRunnable)
                handler.postDelayed(reloadRunnable, 700)
            }
        }
        registerReceiver(r, f)
        receiver = r
    }

    // ------------------------------------------------------------------ главный экран

    private fun iconDesign(): Int = when (prefs.iconSize) {
        0 -> 124
        2 -> 168
        else -> 146
    }

    private fun gridCols(): Int = when (prefs.iconSize) {
        0 -> 8
        2 -> 6
        else -> 7
    }

    private fun buildHome() {
        clock = ClockView(this, prefs)
        content.addView(clock, clockLp())

        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.isVerticalFadingEdgeEnabled = true
        scroll.setFadingEdgeLength(Ui.px(48))
        scroll.clipToPadding = false
        scroll.setPadding(0, Ui.px(24), 0, Ui.px(24))
        grid = SimpleGrid(this, gridCols(), Ui.px(240), Ui.px(240))
        scroll.addView(grid, ViewGroup.LayoutParams(-1, -2))
        val slp = FrameLayout.LayoutParams(-1, -1)
        slp.topMargin = Ui.px(226)
        slp.bottomMargin = Ui.px(212)
        content.addView(scroll, slp)

        emptyHint = label(this, "На главном экране нет приложений. Откройте «Приложения» в доке и отметьте нужные", 30, 2, false)
        emptyHint.gravity = Gravity.CENTER
        emptyHint.visibility = View.GONE
        content.addView(emptyHint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        buildDock()
        relayoutClock(false)
    }

    private fun buildDock() {
        val dock = GlassLayout(this, Ui.pxf(50f), false)
        dock.orientation = LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER
        dock.setPadding(Ui.px(30), Ui.px(14), Ui.px(30), Ui.px(12))

        dock.addView(dockItem(GlyphView.CLEAN, 0xFF34C8A0, 0xFF1E8F8F, null, "Очистка") { openCleaner() }, dockLp())
        dock.addView(dockItem(GlyphView.GRID, 0xFFFF7A9C, 0xFFE0446E, null, "Приложения") { openHomeApps() }, dockLp())
        dock.addView(dockItem(GlyphView.WALL, 0xFF5AC8FA, 0xFF007AFF, null, "Фон") { openBackground() }, dockLp())
        dock.addView(dockItem(GlyphView.THEME, 0xFFB06CFF, 0xFF5B5BFF, null, "Темы") { openThemes() }, dockLp())
        dock.addView(dockItem(GlyphView.TEXT, 0xFF4FA8FF, 0xFF2B63E0, "Аа", "Шрифты") { openFonts() }, dockLp())
        dock.addView(dockItem(GlyphView.CLOCK, 0xFFFF9F0A, 0xFFFF6A00, null, "Часы") { openClock() }, dockLp())
        dock.addView(dockItem(GlyphView.GEAR, 0xFF9AA3B2, 0xFF5B6475, null, "Настройки") { openSettings() }, dockLp())

        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = Ui.px(34)
        content.addView(dock, lp)
    }

    private fun dockLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.leftMargin = Ui.px(14)
        lp.rightMargin = Ui.px(14)
        return lp
    }

    private fun dockItem(kind: Int, c1: Long, c2: Long, text: String?, title: String, action: () -> Unit): FocusLayout {
        val cell = FocusLayout(this, Ui.pxf(34f), true, 1.14f, false)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER_HORIZONTAL
        cell.setPadding(Ui.px(16), Ui.px(12), Ui.px(16), Ui.px(10))
        cell.addView(GlyphView(this, kind, c1.toInt(), c2.toInt(), text), LinearLayout.LayoutParams(Ui.px(96), Ui.px(96)))
        val tv = label(this, title, 23, 1, false)
        tv.gravity = Gravity.CENTER
        val tlp = LinearLayout.LayoutParams(-2, -2)
        tlp.topMargin = Ui.px(8)
        cell.addView(tv, tlp)
        cell.setOnClickListener { action() }
        return cell
    }

    // ------------------------------------------------------------------ приложения

    private fun reloadApps() {
        val size = Ui.px(iconDesign())
        Thread {
            val list = Apps.load(this)
            for (e in list) {
                e.bmp = renderIconBitmap(e.icon, size, e.pkg.hashCode())
            }
            runOnUiThread {
                if (!isDestroyed) {
                    apps = list
                    populateGrid()
                }
            }
        }.start()
    }

    private fun populateGrid() {
        val hidden = prefs.hidden
        val visible = ArrayList<AppEntry>()
        for (e in apps) {
            if (!hidden.contains(e.pkg)) visible.add(e)
        }
        val sq = Ui.px(iconDesign())
        val total = (sq * 1.2f).toInt()
        val cols = gridCols()
        val screenW = resources.displayMetrics.widthPixels
        grid.removeAllViews()
        grid.cols = cols
        grid.cellW = (screenW - 2 * Ui.px(96)) / cols
        grid.cellH = total + Ui.px(70)
        var i = 0
        for (e in visible) {
            val cell = makeCell(e, sq)
            grid.addView(cell)
            if (prefs.animations) entryAnim(cell, i)
            i++
        }
        grid.requestLayout()
        emptyHint.visibility = if (visible.isEmpty() && apps.isNotEmpty()) View.VISIBLE else View.GONE
        if (panels.isEmpty()) {
            scroll.post { focusFirst() }
        }
    }

    private fun focusFirst() {
        if (panels.isNotEmpty()) return
        if (grid.childCount > 0) {
            grid.getChildAt(0).requestFocus()
        } else {
            val f = firstFocusable(content)
            if (f != null) f.requestFocus()
        }
    }

    private fun makeCell(e: AppEntry, sq: Int): FocusLayout {
        val cell = FocusLayout(this, 0f, false, 1.14f, false)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER_HORIZONTAL
        val iv = SquircleView(this, sq)
        iv.bitmap = e.bmp
        cell.addView(iv, LinearLayout.LayoutParams(iv.total, iv.total))
        val tv = label(this, e.label, 26, 1, false)
        tv.maxLines = 1
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.gravity = Gravity.CENTER
        val tlp = LinearLayout.LayoutParams(grid.cellW - Ui.px(14), -2)
        tlp.topMargin = Ui.px(2)
        cell.addView(tv, tlp)
        cell.onAmount = { v -> iv.ring = v }
        cell.setOnClickListener { launchApp(e) }
        cell.setOnLongClickListener {
            showAppMenu(e)
            true
        }
        return cell
    }

    private fun entryAnim(v: View, i: Int) {
        v.alpha = 0f
        v.translationY = Ui.pxf(50f)
        v.scaleX = 0.86f
        v.scaleY = 0.86f
        val delay = if (i * 26L > 700L) 700L else i * 26L
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(delay).setDuration(420)
            .setInterpolator(OvershootInterpolator(1.05f)).start()
    }

    private fun launchApp(e: AppEntry) {
        val i = Intent(Intent.ACTION_MAIN)
        i.setClassName(e.pkg, e.activity)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val go = Runnable {
            try {
                startActivity(i)
            } catch (ex: Exception) {
                content.alpha = 1f
                content.scaleX = 1f
                content.scaleY = 1f
                toast("Не удалось открыть «" + e.label + "»")
            }
        }
        if (prefs.animations) {
            content.animate().alpha(0f).scaleX(1.08f).scaleY(1.08f).setDuration(180)
                .setInterpolator(DecelerateInterpolator()).withEndAction(go).start()
        } else {
            go.run()
        }
    }

    private fun showAppMenu(e: AppEntry) {
        val items = ArrayList<MenuItem>()
        items.add(MenuItem("Открыть") { launchApp(e) })
        items.add(MenuItem("Сведения (кэш, отключение)") { openAppInfo(e.pkg) })
        if (!e.system) items.add(MenuItem("Удалить") { uninstall(e.pkg) })
        items.add(MenuItem("Скрыть с главного экрана") {
            val h = prefs.hidden.toMutableSet()
            h.add(e.pkg)
            prefs.hidden = h
            populateGrid()
            toast("Скрыто: " + e.label + ". Вернуть можно в настройках лаунчера")
        })
        showMenu(e.label, items)
    }

    private fun openAppInfo(pkg: String) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            toast("Не удалось открыть сведения о приложении")
        }
    }

    private fun uninstall(pkg: String) {
        try {
            val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            toast("Не удалось запустить удаление")
        }
    }

    // ------------------------------------------------------------------ окна

    private fun pushPanel(p: View, initialFocus: View?) {
        if (panels.isEmpty()) {
            savedFocus = currentFocus
        } else {
            (panels[panels.size - 1] as ViewGroup).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        panels.add(p)
        overlay.addView(p, FrameLayout.LayoutParams(-1, -1))
        p.alpha = 0f
        p.animate().alpha(1f).setDuration(200).start()
        if (p is PanelFrame) {
            p.card.translationY = Ui.pxf(80f)
            p.card.scaleX = 0.96f
            p.card.scaleY = 0.96f
            p.card.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(OvershootInterpolator(0.9f)).start()
        }
        p.post {
            val f = initialFocus ?: firstFocusable(p)
            if (f != null) f.requestFocus()
        }
    }

    private fun popPanel(): Boolean {
        if (panels.isEmpty()) return false
        val p = panels.removeAt(panels.size - 1)
        if (p is PanelFrame) {
            val oc = p.onClose
            if (oc != null) oc()
        }
        p.animate().alpha(0f).setDuration(160).withEndAction { overlay.removeView(p) }.start()
        if (panels.isEmpty()) {
            content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            val sf = savedFocus
            savedFocus = null
            if (sf != null && sf.isAttachedToWindow) sf.requestFocus() else focusFirst()
        } else {
            val top = panels[panels.size - 1] as ViewGroup
            top.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            val f = firstFocusable(top)
            if (f != null) f.requestFocus()
        }
        return true
    }

    private fun closeAllPanels() {
        while (popPanel()) {
        }
    }

    private fun showMenu(title: String, items: List<MenuItem>) {
        val h = Ui.px(200 + 98 * (items.size + 1))
        val frame = PanelFrame(this, title, "", Ui.px(780), h)
        for (it in items) {
            val item = it
            val r = FocusLayout(this, Ui.pxf(24f), true, 1.03f, true)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(Ui.px(28), Ui.px(20), Ui.px(28), Ui.px(20))
            r.addView(label(this, item.label, 31, 1, false))
            r.setOnClickListener {
                popPanel()
                item.action()
            }
            frame.body.addView(r, rowLp())
        }
        val cancel = FocusLayout(this, Ui.pxf(24f), true, 1.03f, true)
        cancel.orientation = LinearLayout.HORIZONTAL
        cancel.setPadding(Ui.px(28), Ui.px(20), Ui.px(28), Ui.px(20))
        cancel.addView(label(this, "Отмена", 31, 2, false))
        cancel.setOnClickListener { popPanel() }
        frame.body.addView(cancel, rowLp())
        pushPanel(frame, null)
    }

    private fun showInfo(title: String, text: String) {
        val frame = PanelFrame(this, title, "", Ui.px(1340), Ui.px(820))
        val tv = label(this, text, 28, 1, false)
        tv.setLineSpacing(0f, 1.18f)
        frame.body.addView(tv, LinearLayout.LayoutParams(-1, -2))
        val close = FocusLayout(this, Ui.pxf(24f), true, 1.03f, true)
        close.orientation = LinearLayout.HORIZONTAL
        close.setPadding(Ui.px(28), Ui.px(18), Ui.px(28), Ui.px(18))
        close.addView(label(this, "Понятно", 30, 3, true))
        close.setOnClickListener { popPanel() }
        val clp = LinearLayout.LayoutParams(-2, -2)
        clp.topMargin = Ui.px(28)
        frame.body.addView(close, clp)
        pushPanel(frame, close)
    }

    private fun rowLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = Ui.px(12)
        return lp
    }

    private fun row(kind: Int, c1: Long, c2: Long, text: String?, title: String, sub: String?, onClick: () -> Unit): RowRef {
        val r = FocusLayout(this, Ui.pxf(26f), true, 1.02f, true)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(Ui.px(24), Ui.px(14), Ui.px(24), Ui.px(14))
        r.addView(GlyphView(this, kind, c1.toInt(), c2.toInt(), text), LinearLayout.LayoutParams(Ui.px(78), Ui.px(78)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(label(this, title, 32, 1, true))
        var subView: TextView? = null
        if (sub != null) {
            val sv = label(this, sub, 24, 2, false)
            val slp = LinearLayout.LayoutParams(-2, -2)
            slp.topMargin = Ui.px(4)
            col.addView(sv, slp)
            subView = sv
        }
        val lp = LinearLayout.LayoutParams(0, -2, 1f)
        lp.leftMargin = Ui.px(24)
        r.addView(col, lp)
        r.setOnClickListener { onClick() }
        return RowRef(r, subView)
    }

    private fun toast(msg: String) {
        val g = GlassLayout(this, Ui.pxf(40f), true)
        g.orientation = LinearLayout.HORIZONTAL
        g.setPadding(Ui.px(40), Ui.px(20), Ui.px(40), Ui.px(20))
        g.addView(label(this, msg, 28, 1, false))
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        lp.topMargin = Ui.px(54)
        root.addView(g, lp)
        g.alpha = 0f
        g.translationY = -Ui.pxf(30f)
        g.animate().alpha(1f).translationY(0f).setDuration(260).start()
        handler.postDelayed({
            g.animate().alpha(0f).setDuration(260).withEndAction { root.removeView(g) }.start()
        }, 2600)
    }

    // ------------------------------------------------------------------ темы и шрифты

    private fun applyTheme(t: RinTheme) {
        prefs.themeId = t.id
        Skin.theme = t
        if (prefs.bgMode == 0) {
            Skin.bgDark = null
            bg.setTheme(t, prefs.animations)
        }
        Style.restyle(root)
        clock.refreshStyle()
    }

    private fun applyFont(i: Int) {
        prefs.fontIdx = i
        Skin.typeface = Fonts.load(this, Fonts.all[i])
        Style.restyle(root)
        clock.refreshStyle()
        relayoutClock(false)
    }

    private fun themeSection(frame: PanelFrame, title: String, list: List<RinTheme>, cells: ArrayList<Pair<String, View>>) {
        val head = label(this, title, 30, 3, true)
        val hlp = LinearLayout.LayoutParams(-2, -2)
        hlp.topMargin = Ui.px(10)
        hlp.bottomMargin = Ui.px(10)
        hlp.leftMargin = Ui.px(10)
        frame.body.addView(head, hlp)
        val g = SimpleGrid(this, 5, Ui.px(270), Ui.px(196))
        for (t in list) {
            val theme = t
            val cell = FocusLayout(this, Ui.pxf(30f), true, 1.06f, false)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER_HORIZONTAL
            cell.setPadding(Ui.px(10), Ui.px(12), Ui.px(10), Ui.px(8))
            cell.addView(ThemeSwatch(this, theme), LinearLayout.LayoutParams(Ui.px(230), Ui.px(124)))
            val tv = label(this, theme.name, 26, 1, false)
            val tlp = LinearLayout.LayoutParams(-2, -2)
            tlp.topMargin = Ui.px(10)
            cell.addView(tv, tlp)
            cell.setOnClickListener { applyTheme(theme) }
            g.addView(cell)
            cells.add(Pair(theme.id, cell))
        }
        frame.body.addView(g, LinearLayout.LayoutParams(-1, -2))
    }

    private fun openThemes() {
        val frame = PanelFrame(this, "Темы", "10 тёмных и 10 светлых — выбор применяется сразу", Ui.px(1560), Ui.px(930))
        val cells = ArrayList<Pair<String, View>>()
        themeSection(frame, "Тёмные темы", Themes.dark, cells)
        themeSection(frame, "Светлые темы", Themes.light, cells)
        var focus: View? = null
        for (c in cells) {
            if (c.first == Skin.theme.id) focus = c.second
        }
        pushPanel(frame, focus)
    }

    private fun openFonts() {
        val frame = PanelFrame(this, "Шрифты", "40 шрифтов с поддержкой русского языка", Ui.px(1440), Ui.px(930))
        val rows = ArrayList<View>()
        val samples = ArrayList<TextView>()
        val marks = ArrayList<TextView>()
        for (i in Fonts.all.indices) {
            val idx = i
            val f = Fonts.all[i]
            val r = FocusLayout(this, Ui.pxf(24f), true, 1.02f, true)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(Ui.px(26), Ui.px(14), Ui.px(26), Ui.px(14))
            val num = label(this, (i + 1).toString() + ". " + f.name, 24, 2, false)
            num.maxLines = 1
            num.ellipsize = TextUtils.TruncateAt.END
            r.addView(num, LinearLayout.LayoutParams(Ui.px(430), -2))
            val sample = label(this, "Привет, Rin! Фильмы и музыка 0123", 36, 1, false, true)
            sample.maxLines = 1
            sample.ellipsize = TextUtils.TruncateAt.END
            r.addView(sample, LinearLayout.LayoutParams(0, -2, 1f))
            val mark = label(this, "✓", 40, 3, true)
            mark.visibility = if (i == prefs.fontIdx) View.VISIBLE else View.INVISIBLE
            r.addView(mark, LinearLayout.LayoutParams(-2, -2))
            r.setOnClickListener {
                applyFont(idx)
                for (m in 0 until marks.size) {
                    marks[m].visibility = if (m == idx) View.VISIBLE else View.INVISIBLE
                }
            }
            frame.body.addView(r, rowLp())
            rows.add(r)
            samples.add(sample)
            marks.add(mark)
        }
        Thread {
            for (i in Fonts.all.indices) {
                val tf = Fonts.load(this, Fonts.all[i])
                val tv = samples[i]
                runOnUiThread {
                    if (!isDestroyed) tv.typeface = tf
                }
            }
        }.start()
        val cur = if (prefs.fontIdx >= 0 && prefs.fontIdx < rows.size) prefs.fontIdx else 0
        pushPanel(frame, rows[cur])
    }

    // ------------------------------------------------------------------ очистка и ускорение

    private fun avail(): Long {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem
    }

    private fun refreshStats(animate: Boolean) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val usedMem = mi.totalMem - mi.availMem
        memText?.text = "Оперативная память: занято " + fmtSize(usedMem) + " из " + fmtSize(mi.totalMem)
        memBar?.setProgress(usedMem.toFloat() / mi.totalMem.toFloat(), animate)
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            val total = st.blockCountLong * st.blockSizeLong
            val free = st.availableBlocksLong * st.blockSizeLong
            stoText?.text = "Хранилище: занято " + fmtSize(total - free) + " из " + fmtSize(total)
            stoBar?.setProgress((total - free).toFloat() / total.toFloat(), animate)
        } catch (e: Exception) {
            stoText?.text = "Хранилище: нет данных"
        }
    }

    private fun isProtected(ai: ApplicationInfo): Boolean {
        val p = ai.packageName
        if (p == packageName || p == "android") return true
        if (p.startsWith("com.android.systemui") || p.startsWith("com.google.android.gms") ||
            p.startsWith("com.google.android.gsf") || p.startsWith("com.android.providers") ||
            p.startsWith("com.android.inputmethod") || p.startsWith("com.google.android.inputmethod") ||
            p.startsWith("com.google.android.tv") || p.startsWith("com.google.android.leanbacklauncher")
        ) return true
        if ((ai.flags and ApplicationInfo.FLAG_PERSISTENT) != 0) return true
        return false
    }

    private fun killBackground(): Int {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        var n = 0
        for (ai in packageManager.getInstalledApplications(0)) {
            if (isProtected(ai)) continue
            try {
                am.killBackgroundProcesses(ai.packageName)
                n++
            } catch (e: Exception) {
            }
        }
        return n
    }

    private fun boostNow(after: () -> Unit) {
        toast("Освобождаю память…")
        Thread {
            val before = avail()
            val n = killBackground()
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
            }
            val now = avail()
            val freed = if (now > before) now - before else 0L
            runOnUiThread {
                if (!isDestroyed) {
                    toast("Готово: освобождено " + fmtSize(freed) + ", проверено приложений: " + n)
                    refreshStats(true)
                    after()
                }
            }
        }.start()
    }

    private fun quickBoost() {
        boostNow { }
    }

    private fun getAnimScale(): Float {
        return try {
            Settings.Global.getFloat(contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
        } catch (e: Exception) {
            1f
        }
    }

    private fun setAnimScale(v: Float): Boolean {
        return try {
            Settings.Global.putFloat(contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, v)
            Settings.Global.putFloat(contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, v)
            Settings.Global.putFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, v)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun animScaleText(v: Float): String {
        return if (v < 0.05f) "выключены" else if (v < 0.75f) "быстрые (×0.5)" else "обычные (×1.0)"
    }

    private fun adbText(): String {
        return "Эти действия Android разрешает только с компьютера (по ADB):\n\n" +
            "• Очистить кэш всех приложений сразу:\nadb shell pm trim-caches 999G\n\n" +
            "• Отключить ненужное системное приложение (обратимо):\nadb shell pm disable-user --user 0 имя.пакета\n\n" +
            "• Вернуть отключённое:\nadb shell pm enable имя.пакета\n\n" +
            "• Разрешить лаунчеру менять скорость анимаций системы:\nadb shell pm grant com.rin.launcher android.permission.WRITE_SECURE_SETTINGS\n\n" +
            "• Сделать Rin Launcher главным экраном:\nadb shell cmd package set-home-activity com.rin.launcher/.LauncherActivity\n\n" +
            "Как подключиться: Настройки → Настройки устройства → Об устройстве → 7 раз нажать «Сборка». " +
            "Затем «Для разработчиков» → включить «Отладку по USB» или «Отладку по сети» и выполнить на компьютере: adb connect IP-приставки:5555"
    }

    private fun openCleaner() {
        val frame = PanelFrame(this, "Очистка и ускорение", "Память, хранилище, приложения", Ui.px(1440), Ui.px(930))

        val stats = GlassLayout(this, Ui.pxf(30f), false)
        stats.orientation = LinearLayout.VERTICAL
        stats.setPadding(Ui.px(30), Ui.px(24), Ui.px(30), Ui.px(24))
        val mt = label(this, "", 27, 1, false)
        val mb = BarView(this)
        val stt = label(this, "", 27, 1, false)
        val sb = BarView(this)
        memText = mt
        memBar = mb
        stoText = stt
        stoBar = sb
        stats.addView(mt)
        val b1 = LinearLayout.LayoutParams(-1, Ui.px(16))
        b1.topMargin = Ui.px(10)
        b1.bottomMargin = Ui.px(22)
        stats.addView(mb, b1)
        stats.addView(stt)
        val b2 = LinearLayout.LayoutParams(-1, Ui.px(16))
        b2.topMargin = Ui.px(10)
        stats.addView(sb, b2)
        val slp = LinearLayout.LayoutParams(-1, -2)
        slp.bottomMargin = Ui.px(16)
        frame.body.addView(stats, slp)
        refreshStats(false)
        stats.post { refreshStats(true) }

        val boost = row(GlyphView.BOLT, 0xFFFFB347, 0xFFFF6A3D, null, "Освободить оперативную память",
            "Завершить фоновые процессы приложений") { boostNow { } }
        frame.body.addView(boost.view, rowLp())

        val mine = row(GlyphView.GRID, 0xFF34C8A0, 0xFF1E8F8F, null, "Удалить ненужные приложения",
            "Мои приложения по размеру — удалить, остановить, очистить кэш") { openAppManager(false) }
        frame.body.addView(mine.view, rowLp())

        val sys = row(GlyphView.GEAR, 0xFF9AA3B2, 0xFF5B6475, null, "Системные приложения",
            "Остановить, очистить кэш или отключить через сведения о приложении") { openAppManager(true) }
        frame.body.addView(sys.view, rowLp())

        val anim = row(GlyphView.SCREEN, 0xFF4FA8FF, 0xFF2B63E0, null, "Ускорить интерфейс системы",
            "Анимации системы: " + animScaleText(getAnimScale())) {
            // обработчик ставится ниже, когда есть ссылка на строку
        }
        anim.view.setOnClickListener {
            val cur = getAnimScale()
            val next = if (cur > 0.75f) 0.5f else if (cur > 0.25f) 0f else 1f
            if (setAnimScale(next)) {
                anim.sub?.text = "Анимации системы: " + animScaleText(next)
                toast("Анимации системы: " + animScaleText(next))
            } else {
                showInfo("Нужно одно разрешение",
                    "Чтобы менять скорость анимаций системы, выдайте лаунчеру разрешение один раз с компьютера:\n\n" +
                        "adb shell pm grant com.rin.launcher android.permission.WRITE_SECURE_SETTINGS\n\n" +
                        "После этого эта кнопка будет переключать анимации: обычные → быстрые → выключены.\n\n" +
                        "Или откройте «Для разработчиков» и поменяйте масштаб анимаций вручную.")
            }
        }
        frame.body.addView(anim.view, rowLp())

        val cache = row(GlyphView.STORAGE, 0xFFB06CFF, 0xFF5B5BFF, null, "Очистить кэш лаунчера",
            "Освободить временные файлы Rin Launcher") {
            var n = 0L
            try {
                n += dirSize(cacheDir)
                cacheDir.deleteRecursively()
                val ext = externalCacheDir
                if (ext != null) {
                    n += dirSize(ext)
                    ext.deleteRecursively()
                }
            } catch (e: Exception) {
            }
            toast("Кэш лаунчера очищен: " + fmtSize(n))
            refreshStats(true)
        }
        frame.body.addView(cache.view, rowLp())

        val adb = row(GlyphView.TEXT, 0xFF2D2F36, 0xFF4B4F5C, "ADB", "Что умеет только компьютер (ADB)",
            "Очистка кэша всех приложений, отключение системных приложений") {
            showInfo("Команды ADB", adbText())
        }
        frame.body.addView(adb.view, rowLp())

        pushPanel(frame, null)
    }

    private fun dirSize(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) return f.length()
        var s = 0L
        val list = f.listFiles() ?: return 0L
        for (c in list) s += dirSize(c)
        return s
    }

    private class PkgRow(val label: String, val pkg: String, val size: Long, val system: Boolean, val icon: Drawable?)

    private fun openAppManager(system: Boolean) {
        val title = if (system) "Системные приложения" else "Мои приложения"
        val sub = if (system) "Размер — по файлу приложения. Отключить: Сведения → Отключить" else "Размер — по файлу приложения, без данных и кэша"
        val frame = PanelFrame(this, title, sub, Ui.px(1500), Ui.px(930))
        val loading = label(this, "Загрузка списка…", 30, 2, false)
        frame.body.addView(loading)
        pushPanel(frame, null)
        val known = HashSet<String>()
        for (a in apps) known.add(a.pkg)
        Thread {
            val pm = packageManager
            val rows = ArrayList<PkgRow>()
            for (ai in pm.getInstalledApplications(0)) {
                if (ai.packageName == packageName) continue
                val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSys != system) continue
                if (system && !known.contains(ai.packageName)) continue
                val sz = try {
                    File(ai.sourceDir).length()
                } catch (e: Exception) {
                    0L
                }
                val lab = try {
                    ai.loadLabel(pm).toString()
                } catch (e: Exception) {
                    ai.packageName
                }
                val ic = try {
                    ai.loadIcon(pm)
                } catch (e: Exception) {
                    null
                }
                rows.add(PkgRow(lab, ai.packageName, sz, isSys, ic))
            }
            rows.sortByDescending { it.size }
            val small = Ui.px(64)
            val bmps = ArrayList<android.graphics.Bitmap>()
            for (r in rows) bmps.add(renderIconBitmap(r.icon, small, r.pkg.hashCode()))
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                frame.body.removeAllViews()
                if (rows.isEmpty()) {
                    frame.body.addView(label(this, "Здесь пока ничего нет", 30, 2, false))
                    return@runOnUiThread
                }
                var first: View? = null
                for (i in rows.indices) {
                    val r = rows[i]
                    val line = FocusLayout(this, Ui.pxf(24f), true, 1.02f, true)
                    line.orientation = LinearLayout.HORIZONTAL
                    line.gravity = Gravity.CENTER_VERTICAL
                    line.setPadding(Ui.px(22), Ui.px(8), Ui.px(26), Ui.px(8))
                    val iv = SquircleView(this, small)
                    iv.bitmap = bmps[i]
                    line.addView(iv, LinearLayout.LayoutParams(iv.total, iv.total))
                    val col = LinearLayout(this)
                    col.orientation = LinearLayout.VERTICAL
                    val nm = label(this, r.label, 30, 1, true)
                    nm.maxLines = 1
                    nm.ellipsize = TextUtils.TruncateAt.END
                    col.addView(nm)
                    val pk = label(this, r.pkg, 20, 2, false)
                    pk.maxLines = 1
                    pk.ellipsize = TextUtils.TruncateAt.END
                    col.addView(pk)
                    val clp = LinearLayout.LayoutParams(0, -2, 1f)
                    clp.leftMargin = Ui.px(16)
                    line.addView(col, clp)
                    line.addView(label(this, fmtSize(r.size), 28, 3, true))
                    line.setOnClickListener { showPkgMenu(r) }
                    frame.body.addView(line, rowLp())
                    if (first == null) first = line
                }
                first?.requestFocus()
            }
        }.start()
    }

    private fun showPkgMenu(r: PkgRow) {
        val items = ArrayList<MenuItem>()
        items.add(MenuItem("Сведения (кэш, данные, отключение)") { openAppInfo(r.pkg) })
        items.add(MenuItem("Остановить фоновые процессы") {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.killBackgroundProcesses(r.pkg)
                toast("Остановлено: " + r.label)
                refreshStats(true)
            } catch (e: Exception) {
                toast("Не удалось остановить")
            }
        })
        if (!r.system) items.add(MenuItem("Удалить") { uninstall(r.pkg) })
        showMenu(r.label, items)
    }

    // ------------------------------------------------------------------ настройки

    private fun openSystem(action: String) {
        try {
            val i = Intent(action)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            try {
                val i2 = Intent(Settings.ACTION_SETTINGS)
                i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i2)
            } catch (e2: Exception) {
                toast("Настройки недоступны")
            }
        }
    }

    private fun iconSizeName(): String = when (prefs.iconSize) {
        0 -> "Мелкие"
        2 -> "Крупные"
        else -> "Средние"
    }

    private fun openSettings() {
        val frame = PanelFrame(this, "Настройки", "Приставка и внешний вид лаунчера", Ui.px(1400), Ui.px(930))

        fun sysRow(kind: Int, c1: Long, c2: Long, text: String?, title: String, sub: String, action: String) {
            val r = row(kind, c1, c2, text, title, sub) { openSystem(action) }
            frame.body.addView(r.view, rowLp())
        }

        val head1 = label(this, "Лаунчер", 30, 3, true)
        val h1lp = LinearLayout.LayoutParams(-2, -2)
        h1lp.leftMargin = Ui.px(10)
        h1lp.bottomMargin = Ui.px(10)
        frame.body.addView(head1, h1lp)

        val size = row(GlyphView.GRID, 0xFF34C8A0, 0xFF1E8F8F, null, "Размер значков", iconSizeName()) { }
        size.view.setOnClickListener {
            prefs.iconSize = (prefs.iconSize + 1) % 3
            size.sub?.text = iconSizeName()
            reloadApps()
        }
        frame.body.addView(size.view, rowLp())

        val animRow = row(GlyphView.BOLT, 0xFFFFB347, 0xFFFF6A3D, null, "Анимации лаунчера",
            if (prefs.animations) "Включены" else "Выключены") { }
        animRow.view.setOnClickListener {
            prefs.animations = !prefs.animations
            bg.animated = prefs.animations
            animRow.sub?.text = if (prefs.animations) "Включены" else "Выключены"
        }
        frame.body.addView(animRow.view, rowLp())

        val appsRow = row(GlyphView.GRID, 0xFFFF7A9C, 0xFFE0446E, null, "Приложения на главном экране",
            "Оставить только нужные, остальные скрыть") { openHomeApps() }
        frame.body.addView(appsRow.view, rowLp())

        val bgRow = row(GlyphView.WALL, 0xFF5AC8FA, 0xFF007AFF, null, "Фон",
            "Градиенты и своя картинка") { openBackground() }
        frame.body.addView(bgRow.view, rowLp())

        val clockRow = row(GlyphView.CLOCK, 0xFFFF9F0A, 0xFFFF6A00, null, "Часы",
            "Стиль, размер, цвет, прозрачность") { openClock() }
        frame.body.addView(clockRow.view, rowLp())

        val home = row(GlyphView.HOME, 0xFFFF7A9C, 0xFFE0446E, null, "Сделать главным экраном",
            "Чтобы кнопка «Домой» открывала Rin Launcher") {
            try {
                val i = Intent(Settings.ACTION_HOME_SETTINGS)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (e: Exception) {
                showInfo("Главный экран",
                    "Нажмите кнопку «Домой» на пульте. Если система предложит выбрать главный экран, выберите Rin Launcher и «Всегда».\n\n" +
                        "Если выбор не появился, выполните на компьютере по ADB:\n\n" +
                        "adb shell cmd package set-home-activity com.rin.launcher/.LauncherActivity")
            }
        }
        frame.body.addView(home.view, rowLp())

        val head2 = label(this, "Приставка", 30, 3, true)
        val h2lp = LinearLayout.LayoutParams(-2, -2)
        h2lp.leftMargin = Ui.px(10)
        h2lp.topMargin = Ui.px(18)
        h2lp.bottomMargin = Ui.px(10)
        frame.body.addView(head2, h2lp)

        sysRow(GlyphView.WIFI, 0xFF4FA8FF, 0xFF2B63E0, null, "Сеть и Wi-Fi", "Подключение к интернету", Settings.ACTION_WIFI_SETTINGS)
        sysRow(GlyphView.TEXT, 0xFF3D7BFF, 0xFF1F3FBF, "BT", "Bluetooth и пульт", "Пульты, наушники, клавиатуры", Settings.ACTION_BLUETOOTH_SETTINGS)
        sysRow(GlyphView.SCREEN, 0xFF34C8A0, 0xFF1E8F8F, null, "Экран", "Разрешение, формат, яркость", Settings.ACTION_DISPLAY_SETTINGS)
        sysRow(GlyphView.SOUND, 0xFFFFB347, 0xFFFF6A3D, null, "Звук", "Громкость и аудиовыход", Settings.ACTION_SOUND_SETTINGS)
        sysRow(GlyphView.GRID, 0xFFB06CFF, 0xFF5B5BFF, null, "Все приложения", "Сведения, кэш, отключение", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
        sysRow(GlyphView.STORAGE, 0xFF9AA3B2, 0xFF5B6475, null, "Хранилище", "Свободное место", Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        sysRow(GlyphView.GLOBE, 0xFF4FC3F7, 0xFF0288D1, null, "Язык", "Язык системы и клавиатуры", Settings.ACTION_LOCALE_SETTINGS)
        sysRow(GlyphView.CLOCK, 0xFFFF8A65, 0xFFD84315, null, "Дата и время", "Часовой пояс", Settings.ACTION_DATE_SETTINGS)
        sysRow(GlyphView.TEXT, 0xFF2D2F36, 0xFF4B4F5C, "</>", "Для разработчиков", "Отладка по сети, масштаб анимаций", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        sysRow(GlyphView.GEAR, 0xFF9AA3B2, 0xFF5B6475, null, "Все настройки системы", "Открыть стандартные настройки", Settings.ACTION_SETTINGS)

        pushPanel(frame, null)
    }

    // ------------------------------------------------------------------ вспомогательное для окон выбора

    private fun sectionHead(frame: PanelFrame, text: String) {
        val h = label(this, text, 29, 3, true)
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.topMargin = Ui.px(14)
        lp.bottomMargin = Ui.px(10)
        lp.leftMargin = Ui.px(10)
        frame.body.addView(h, lp)
    }

    private fun chipLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.leftMargin = Ui.px(10)
        lp.bottomMargin = Ui.px(6)
        return lp
    }

    /** Ряд вариантов: выбранный подсвечен, остальные можно выбрать пультом. */
    private fun chips(names: List<String>, sel: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val cells = ArrayList<FocusLayout>()
        for (i in names.indices) {
            val idx = i
            val c = FocusLayout(this, Ui.pxf(22f), true, 1.05f, true)
            c.gravity = Gravity.CENTER
            c.setPadding(Ui.px(26), Ui.px(14), Ui.px(26), Ui.px(14))
            c.addView(label(this, names[i], 27, 1, false))
            c.marked = (i == sel)
            c.setOnClickListener {
                for (k in cells.indices) cells[k].marked = (k == idx)
                onPick(idx)
            }
            cells.add(c)
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = Ui.px(12)
            row.addView(c, lp)
        }
        return row
    }

    private fun colorChips(sel: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val cells = ArrayList<FocusLayout>()
        for (i in ClockOpts.colors.indices) {
            val idx = i
            val c = FocusLayout(this, Ui.pxf(50f), true, 1.12f, true)
            c.gravity = Gravity.CENTER
            c.setPadding(Ui.px(10), Ui.px(10), Ui.px(10), Ui.px(10))
            c.addView(ColorDot(this, i), LinearLayout.LayoutParams(Ui.px(52), Ui.px(52)))
            c.marked = (i == sel)
            c.setOnClickListener {
                for (k in cells.indices) cells[k].marked = (k == idx)
                onPick(idx)
            }
            cells.add(c)
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = Ui.px(8)
            row.addView(c, lp)
        }
        return row
    }

    // ------------------------------------------------------------------ часы

    private fun clockLp(): FrameLayout.LayoutParams {
        val g = when (prefs.clockPos) {
            0 -> Gravity.START
            1 -> Gravity.CENTER_HORIZONTAL
            else -> Gravity.END
        }
        val lp = FrameLayout.LayoutParams(-2, -2, g or Gravity.TOP)
        lp.leftMargin = Ui.px(96)
        lp.rightMargin = Ui.px(96)
        lp.topMargin = Ui.px(40)
        return lp
    }

    /** Пересобрать часы и сдвинуть сетку приложений, чтобы большие часы ничего не закрывали. */
    private fun relayoutClock(rebuild: Boolean) {
        if (rebuild) clock.rebuild()
        clock.layoutParams = clockLp()
        val sw = resources.displayMetrics.widthPixels
        clock.measure(
            View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        var top = Ui.px(40) + clock.measuredHeight + Ui.px(16)
        val minTop = Ui.px(150)
        val maxTop = Ui.px(520)
        if (top < minTop) top = minTop
        if (top > maxTop) top = maxTop
        val slp = scroll.layoutParams as FrameLayout.LayoutParams
        slp.topMargin = top
        scroll.layoutParams = slp
    }

    private fun openClock() {
        val frame = PanelFrame(this, "Часы", "Часы видны сверху. Изменения применяются сразу",
            Ui.px(1500), Ui.px(660), 0x22000000, true)

        sectionHead(frame, "Стиль")
        frame.body.addView(chips(ClockOpts.styleNames, prefs.clockStyle) { i ->
            prefs.clockStyle = i
            relayoutClock(true)
        }, chipLp())

        sectionHead(frame, "Размер")
        frame.body.addView(chips(ClockOpts.sizeNames, prefs.clockSize) { i ->
            prefs.clockSize = i
            relayoutClock(true)
        }, chipLp())

        sectionHead(frame, "Прозрачность")
        frame.body.addView(chips(ClockOpts.alphaNames, prefs.clockAlpha) { i ->
            prefs.clockAlpha = i
            clock.refreshStyle()
        }, chipLp())

        sectionHead(frame, "Цвет (первый — по теме, второй — акцент темы)")
        frame.body.addView(colorChips(prefs.clockColor) { i ->
            prefs.clockColor = i
            clock.refreshStyle()
        }, chipLp())

        sectionHead(frame, "Положение")
        frame.body.addView(chips(ClockOpts.posNames, prefs.clockPos) { i ->
            prefs.clockPos = i
            relayoutClock(true)
        }, chipLp())

        sectionHead(frame, "Дата")
        frame.body.addView(chips(listOf("Показывать", "Скрыть"), if (prefs.clockDate) 0 else 1) { i ->
            prefs.clockDate = (i == 0)
            relayoutClock(true)
        }, chipLp())

        sectionHead(frame, "Формат времени")
        frame.body.addView(chips(listOf("24 часа", "12 часов"), if (prefs.clock24) 0 else 1) { i ->
            prefs.clock24 = (i == 0)
            relayoutClock(true)
        }, chipLp())

        sectionHead(frame, "Начертание")
        frame.body.addView(chips(listOf("Обычное", "Жирное"), if (prefs.clockBold) 1 else 0) { i ->
            prefs.clockBold = (i == 1)
            clock.refreshStyle()
            relayoutClock(false)
        }, chipLp())

        pushPanel(frame, null)
    }

    // ------------------------------------------------------------------ фон

    private fun afterBgChange() {
        Style.restyle(root)
        clock.refreshStyle()
    }

    private fun dimValue(): Float = when (prefs.bgDim) {
        1 -> 0.2f
        2 -> 0.4f
        3 -> 0.6f
        else -> 0f
    }

    private fun applyBackground(animate: Boolean) {
        val anim = animate && prefs.animations
        when (prefs.bgMode) {
            1 -> {
                val w = Walls.byId(prefs.wallId)
                Skin.bgDark = w.dark
                bg.setImage(null, 0f, false)
                bg.setPalette(w.top, w.bottom, w.b1, w.b2, w.b3, w.dark, anim)
                afterBgChange()
            }
            2 -> loadImageBackground(anim)
            else -> {
                Skin.bgDark = null
                bg.setImage(null, 0f, false)
                bg.setTheme(Skin.theme, anim)
                afterBgChange()
            }
        }
    }

    private fun loadImageBackground(animate: Boolean) {
        val blur = prefs.bgBlur
        val dimF = dimValue()
        Thread {
            val b = WallStore.load(this, blur)
            val lum = if (b != null) WallStore.luminance(b) * (1f - dimF) else 0f
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (b == null) {
                    prefs.bgMode = 0
                    applyBackground(false)
                } else {
                    Skin.bgDark = lum < 0.62f
                    bg.setImage(b, dimF, animate)
                    afterBgChange()
                }
            }
        }.start()
    }

    private fun swatchOf(w: Wall): RinTheme = RinTheme(w.id, w.name, w.dark, w.top, w.bottom, w.b1, w.b2, w.b3, w.b1)

    private fun bgCell(sw: RinTheme, name: String, selected: () -> Boolean, onPick: () -> Unit): FocusLayout {
        val cell = FocusLayout(this, Ui.pxf(30f), true, 1.06f, false)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER_HORIZONTAL
        cell.setPadding(Ui.px(10), Ui.px(12), Ui.px(10), Ui.px(8))
        cell.addView(ThemeSwatch(this, sw, selected), LinearLayout.LayoutParams(Ui.px(230), Ui.px(124)))
        val tv = label(this, name, 26, 1, false)
        val tlp = LinearLayout.LayoutParams(-2, -2)
        tlp.topMargin = Ui.px(10)
        cell.addView(tv, tlp)
        cell.setOnClickListener { onPick() }
        return cell
    }

    private fun openBackground() {
        val frame = PanelFrame(this, "Фон", "Градиенты или своя картинка. Изменения видно сразу",
            Ui.px(1560), Ui.px(700), 0x22000000, true)

        sectionHead(frame, "Градиенты")
        val g = SimpleGrid(this, 5, Ui.px(270), Ui.px(196))
        g.addView(bgCell(Skin.theme, "Как у темы", { prefs.bgMode == 0 }) {
            prefs.bgMode = 0
            applyBackground(true)
            Style.restyle(frame.body)
        })
        for (w in Walls.all) {
            val wall = w
            g.addView(bgCell(swatchOf(wall), wall.name, { prefs.bgMode == 1 && prefs.wallId == wall.id }) {
                prefs.bgMode = 1
                prefs.wallId = wall.id
                applyBackground(true)
                Style.restyle(frame.body)
            })
        }
        frame.body.addView(g, LinearLayout.LayoutParams(-1, -2))

        sectionHead(frame, "Своя картинка")
        val pick = row(GlyphView.WALL, 0xFF5AC8FA, 0xFF007AFF, null, "Выбрать из галереи",
            "Фото и картинки, которые есть на приставке") { openGallery() }
        frame.body.addView(pick.view, rowLp())

        val files = row(GlyphView.STORAGE, 0xFF9AA3B2, 0xFF5B6475, null, "Выбрать через файловый менеджер",
            "Если нужного файла нет в галерее") { pickWithSystem() }
        frame.body.addView(files.view, rowLp())

        if (WallStore.exists(this)) {
            val clr = row(GlyphView.CLEAN, 0xFFFF7A9C, 0xFFE0446E, null, "Убрать свою картинку",
                "Вернуться к градиенту") { }
            clr.view.setOnClickListener {
                WallStore.clear(this)
                if (prefs.bgMode == 2) {
                    prefs.bgMode = 0
                    applyBackground(true)
                }
                frame.body.removeView(clr.view)
                pick.view.requestFocus()
                toast("Картинка удалена")
            }
            frame.body.addView(clr.view, rowLp())
        }

        sectionHead(frame, "Затемнение картинки")
        frame.body.addView(chips(listOf("Нет", "20%", "40%", "60%"), prefs.bgDim) { i ->
            prefs.bgDim = i
            if (prefs.bgMode == 2) applyBackground(false)
        }, chipLp())

        sectionHead(frame, "Размытие картинки")
        frame.body.addView(chips(listOf("Нет", "Слабое", "Сильное"), prefs.bgBlur) { i ->
            prefs.bgBlur = i
            if (prefs.bgMode == 2) applyBackground(false)
        }, chipLp())

        pushPanel(frame, null)
    }

    private fun galleryPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    private fun openGallery() {
        if (checkSelfPermission(galleryPermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(galleryPermission()), REQ_PERM)
            return
        }
        showGallery()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showGallery()
            } else {
                toast("Нет доступа к картинкам. Разрешите доступ или выберите через файловый менеджер")
            }
        }
    }

    private fun pickWithSystem() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.type = "image/*"
            i.addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            toast("На приставке нет файлового менеджера. Используйте «Выбрать из галереи»")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val u = data?.data
            if (u != null) importUri(u)
        }
    }

    private fun importUri(uri: Uri) {
        toast("Загружаю картинку…")
        Thread {
            val ok = WallStore.importFrom(this, uri)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (ok) {
                    prefs.bgMode = 2
                    applyBackground(true)
                    toast("Фон обновлён")
                } else {
                    toast("Не удалось открыть эту картинку")
                }
            }
        }.start()
    }

    private fun queryPictures(): List<Uri> {
        val out = ArrayList<Uri>()
        try {
            val cur = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )
            if (cur != null) {
                cur.use {
                    while (it.moveToNext() && out.size < 160) {
                        out.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.getLong(0)))
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun decodeThumb(uri: Uri, target: Int): Bitmap? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            val s1 = contentResolver.openInputStream(uri) ?: return null
            s1.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0) return null
            var sample = 1
            while (o.outWidth / (sample * 2) >= target) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            val s2 = contentResolver.openInputStream(uri) ?: return null
            return s2.use { BitmapFactory.decodeStream(it, null, o2) }
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    private fun showGallery() {
        val frame = PanelFrame(this, "Галерея", "Выберите картинку для фона", Ui.px(1500), Ui.px(900))
        frame.body.addView(label(this, "Загрузка…", 30, 2, false))
        val pool: ExecutorService = Executors.newFixedThreadPool(2)
        frame.onClose = { pool.shutdownNow() }
        pushPanel(frame, null)
        Thread {
            val uris = queryPictures()
            runOnUiThread {
                if (isDestroyed || pool.isShutdown) return@runOnUiThread
                frame.body.removeAllViews()
                if (uris.isEmpty()) {
                    frame.body.addView(label(this,
                        "Картинок не найдено. Скопируйте фото в папку Pictures или Download на приставке, либо выберите через файловый менеджер.",
                        28, 2, false))
                    return@runOnUiThread
                }
                val g = SimpleGrid(this, 4, Ui.px(340), Ui.px(214))
                var first: View? = null
                for (u in uris) {
                    val uri = u
                    val cell = FocusLayout(this, Ui.pxf(26f), true, 1.05f, false)
                    cell.gravity = Gravity.CENTER
                    cell.setPadding(Ui.px(10), Ui.px(10), Ui.px(10), Ui.px(10))
                    val pv = PhotoView(this)
                    cell.addView(pv, LinearLayout.LayoutParams(Ui.px(300), Ui.px(176)))
                    cell.setOnClickListener {
                        popPanel()
                        importUri(uri)
                    }
                    g.addView(cell)
                    if (first == null) first = cell
                    pool.execute {
                        val bmp = decodeThumb(uri, 420)
                        if (bmp != null) {
                            runOnUiThread {
                                if (!isDestroyed) pv.bitmap = bmp
                            }
                        }
                    }
                }
                frame.body.addView(g, LinearLayout.LayoutParams(-1, -2))
                val f0 = first
                if (f0 != null) f0.requestFocus()
            }
        }.start()
    }

    // ------------------------------------------------------------------ приложения на главном экране

    private fun openHomeApps() {
        val frame = PanelFrame(this, "Приложения на главном экране",
            "Отметьте нужные. Остальные пропадут с экрана, но не удалятся", Ui.px(1500), Ui.px(930))
        val allList = ArrayList<AppEntry>(apps)
        val hidden = HashSet<String>(prefs.hidden)
        val dots = HashMap<String, CheckDot>()

        if (allList.isEmpty()) {
            frame.body.addView(label(this, "Список приложений ещё загружается. Закройте окно и откройте снова", 28, 2, false))
            pushPanel(frame, null)
            return
        }

        val counter = label(this, "", 27, 3, true)
        val clp = LinearLayout.LayoutParams(-2, -2)
        clp.leftMargin = Ui.px(10)
        clp.bottomMargin = Ui.px(12)
        frame.body.addView(counter, clp)

        fun refreshCount() {
            var shown = 0
            for (e in allList) {
                if (!hidden.contains(e.pkg)) shown++
            }
            counter.text = "На экране: " + shown + " из " + allList.size
        }

        fun commit() {
            prefs.hidden = HashSet<String>(hidden)
            for (e in allList) {
                val d = dots[e.pkg]
                if (d != null) d.on = !hidden.contains(e.pkg)
            }
            refreshCount()
        }

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL

        fun action(title: String, onTap: () -> Unit) {
            val b = FocusLayout(this, Ui.pxf(24f), true, 1.04f, true)
            b.gravity = Gravity.CENTER
            b.setPadding(Ui.px(26), Ui.px(16), Ui.px(26), Ui.px(16))
            b.addView(label(this, title, 27, 3, true))
            b.setOnClickListener {
                onTap()
                commit()
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = Ui.px(14)
            actions.addView(b, lp)
        }

        action("Показать все") { hidden.clear() }
        action("Скрыть системные") {
            for (e in allList) {
                if (e.system) hidden.add(e.pkg)
            }
        }
        action("Скрыть все") {
            for (e in allList) hidden.add(e.pkg)
        }
        val alp = LinearLayout.LayoutParams(-2, -2)
        alp.bottomMargin = Ui.px(14)
        frame.body.addView(actions, alp)

        val small = Ui.px(64)
        var first: View? = null
        for (e in allList) {
            val app = e
            val line = FocusLayout(this, Ui.pxf(24f), true, 1.02f, true)
            line.gravity = Gravity.CENTER_VERTICAL
            line.setPadding(Ui.px(22), Ui.px(8), Ui.px(26), Ui.px(8))
            val iv = SquircleView(this, small)
            val big = app.bmp
            iv.bitmap = if (big != null) Bitmap.createScaledBitmap(big, small, small, true) else null
            line.addView(iv, LinearLayout.LayoutParams(iv.total, iv.total))

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val nm = label(this, app.label, 30, 1, true)
            nm.maxLines = 1
            nm.ellipsize = TextUtils.TruncateAt.END
            col.addView(nm)
            val sub = label(this, (if (app.system) "Системное · " else "") + app.pkg, 20, 2, false)
            sub.maxLines = 1
            sub.ellipsize = TextUtils.TruncateAt.END
            col.addView(sub)
            val colLp = LinearLayout.LayoutParams(0, -2, 1f)
            colLp.leftMargin = Ui.px(16)
            line.addView(col, colLp)

            val dot = CheckDot(this)
            dot.on = !hidden.contains(app.pkg)
            dots[app.pkg] = dot
            line.addView(dot, LinearLayout.LayoutParams(Ui.px(46), Ui.px(46)))

            line.setOnClickListener {
                if (hidden.contains(app.pkg)) {
                    hidden.remove(app.pkg)
                } else {
                    hidden.add(app.pkg)
                }
                commit()
            }
            frame.body.addView(line, rowLp())
            if (first == null) first = line
        }
        refreshCount()
        frame.onClose = { populateGrid() }
        pushPanel(frame, first)
    }

    companion object {
        private const val REQ_PICK = 4711
        private const val REQ_PERM = 4712
    }
}
