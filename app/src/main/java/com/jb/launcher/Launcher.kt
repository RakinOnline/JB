package com.jb.launcher

import android.accessibilityservice.AccessibilityService
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

class DragData(val item: Item?, val app: AppInfo?)

class Launcher : AppCompatActivity() {
    lateinit var prefs: Prefs
    lateinit var store: Store
    lateinit var repo: AppRepo
    lateinit var icons: Icons
    lateinit var usage: Usage
    lateinit var groups: Groups
    private lateinit var host: WHost
    private lateinit var awm: AppWidgetManager
    private lateinit var root: FrameLayout
    private lateinit var main: LinearLayout
    private lateinit var pager: ViewPager2
    private lateinit var dockBox: FrameLayout
    private lateinit var dockPager: ViewPager2
    private lateinit var dots: Dots
    private lateinit var chevron: ChevronView
    private lateinit var searchBar: FrameLayout
    private lateinit var divider: View
    private lateinit var bar: LinearLayout
    private lateinit var btnInfo: TextView
    private lateinit var btnUninstall: TextView
    private lateinit var btnEdit: TextView
    private lateinit var btnRemove: TextView
    private lateinit var btnResize: TextView
    lateinit var drawer: Drawer
    private lateinit var folderPopup: FolderPopup
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var gest: Gestures
    private var selected: DragData? = null
    private var armed: View? = null
    private var swipeCandidate: Item? = null
    private var popup: PopupWindow? = null
    private var downX = 0f
    private var downY = 0f
    private var topInset = 0
    private var prefSig = ""
    private var layoutSig = ""
    private var pagerSig = ""
    private var transformerName = ""
    private var edgeDir = 0
    private var pendingId = -1
    private var pendingInfo: AppWidgetProviderInfo? = null
    private var editApp: AppInfo? = null
    private val REQ_BIND = 71
    private val REQ_CONFIG = 72
    private val REQ_IMG = 73

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = Prefs(this); store = Store(this); repo = AppRepo(this); icons = Icons(this)
        usage = Usage(this); groups = Groups(prefs.sp)
        gest = Gestures(this) { onGesture(it) }
        awm = AppWidgetManager.getInstance(this); host = WHost(this)
        repo.reload(); icons.configure()
        if (!store.initialized) { host.deleteHost(); DefaultLayout.create(this, store, repo) }
        applyNight()
        applyOrientation()
        buildUi()
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    folderPopup.isOpen -> folderPopup.close()
                    drawer.isOpen -> drawer.close()
                    bar.visibility == View.VISIBLE -> hideBar()
                    curPage() != defaultPage() -> gotoPage(defaultPage(), true)
                }
            }
        })
        prefSig = prefs.sp.all.toString()
        refreshAll()
        gotoPage(defaultPage(), false)
        LockService.sync(this)
    }

    override fun onStart() { super.onStart(); try { host.startListening() } catch (e: Exception) { } }
    override fun onStop() { super.onStop(); try { host.stopListening() } catch (e: Exception) { } }
    override fun onDestroy() { super.onDestroy(); try { unregisterReceiver(pkgReceiver) } catch (e: Exception) { } }

    override fun onResume() {
        super.onResume()
        store.load()
        if (!store.initialized) { host.deleteHost(); repo.reload(); DefaultLayout.create(this, store, repo) }
        val s = prefs.sp.all.toString()
        if (s != prefSig || !store.initialized) {
            prefSig = s
            icons.configure()
            drawer.dirty = true
        }
        applyNight(); applyOrientation(); applySystemUi()
        refreshAll()
        LockService.sync(this)
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        drawer.dirty = true
        refreshAll()
    }

    override fun onNewIntent(intent: Intent) {
        val wasResumed = lifecycle.currentState == Lifecycle.State.RESUMED
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            val busy = drawer.isOpen || folderPopup.isOpen || bar.visibility == View.VISIBLE
            if (drawer.isOpen) drawer.close()
            if (folderPopup.isOpen) folderPopup.close()
            hideBar()
            if (busy) return
            if (curPage() != defaultPage()) gotoPage(defaultPage(), true)
            else if (wasResumed) onGesture("home")
        }
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val p = i.data?.schemeSpecificPart
            val replacing = i.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (i.action == Intent.ACTION_PACKAGE_REMOVED && !replacing) {
                if (p != null && store.items.removeAll { it.kind == 0 && it.pkg == p }) store.save()
            }
            repo.reload(); icons.clear(); drawer.dirty = true
            if (i.action == Intent.ACTION_PACKAGE_ADDED && !replacing && p != null && prefs.b("add_new_apps", true)) {
                val a = repo.apps.firstOrNull { it.pkg == p }
                if (a != null && store.items.none { it.kind == 0 && it.pkg == p }) {
                    val spot = findSpotAny(1, 1, defaultPage(), null, false)
                    if (spot != null) {
                        store.items.add(Item(store.nextId(), 0, a.pkg, a.cls, spot[0], spot[1], spot[2]))
                        store.save()
                    }
                }
            }
            refreshAll()
        }
    }

    // ---------- system ui / theme ----------
    fun dur(ms: Long): Long = (ms * prefs.animFactor()).toLong()
    private fun accent() = Theme.accent(this, prefs)

    private fun applyNight() {
        val night = Theme.isNight(this, prefs) && prefs.b("night_settings", true)
        AppCompatDelegate.setDefaultNightMode(if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
    }

    private fun applyOrientation() {
        requestedOrientation = when (prefs.s("orientation", "portrait")) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "auto" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun applySystemUi() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (prefs.b("show_status", true)) c.show(WindowInsetsCompat.Type.statusBars()) else c.hide(WindowInsetsCompat.Type.statusBars())
        if (prefs.b("hide_nav", false)) c.hide(WindowInsetsCompat.Type.navigationBars()) else c.show(WindowInsetsCompat.Type.navigationBars())
        window.statusBarColor = if (prefs.b("status_transparent", true)) Color.TRANSPARENT else 0xCC000000.toInt()
        c.isAppearanceLightStatusBars = prefs.b("dark_status_icons", false)
    }

    fun haptic() { if (prefs.b("haptic", true)) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }

    // ---------- UI construction ----------
    private fun buildUi() {
        root = FrameLayout(this)
        main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchBar = FrameLayout(this)
        pager = ViewPager2(this).apply {
            adapter = PagesAdapter()
            offscreenPageLimit = 1
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) { dots.set(pageCount(), position % pageCount()); hideBar() }
                override fun onPageScrolled(position: Int, off: Float, px: Int) {
                    val mode = prefs.s("wall_scroll", "on")
                    val n = pageCount()
                    if (mode == "off" || infinite() || n <= 1) return
                    try {
                        val raw = (position + off) / (n - 1)
                        val wm = WallpaperManager.getInstance(this@Launcher)
                        wm.setWallpaperOffsetSteps(1f / (n - 1), 1f)
                        wm.setWallpaperOffsets(windowToken, if (mode == "reverse") 1f - raw else raw, 0.5f)
                    } catch (e: Exception) { }
                }
            })
        }
        dots = Dots(this)
        chevron = ChevronView(this)
        chevron.setOnClickListener { drawer.open(0) }
        divider = View(this).apply { setBackgroundColor(0x55FFFFFF) }
        dockBox = FrameLayout(this)
        dockPager = ViewPager2(this).apply { adapter = DockAdapter(); offscreenPageLimit = 1 }
        dockBox.addView(dockPager, FrameLayout.LayoutParams(-1, -1))
        root.addView(main, FrameLayout.LayoutParams(-1, -1))

        drawer = Drawer(this)
        drawer.visibility = View.GONE
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))
        folderPopup = FolderPopup(this)
        root.addView(folderPopup, FrameLayout.LayoutParams(-1, -1))
        bar = buildBar()
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset = i.top
            main.setPadding(0, i.top, 0, i.bottom)
            drawer.setPadding(0, i.top, 0, i.bottom)
            folderPopup.setPadding(0, i.top, 0, i.bottom)
            bar.translationY = i.top.toFloat()
            insets
        }
        root.setOnDragListener { _, e ->
            val d = e.localState as? DragData
            if (d == null) false else {
                when (e.action) {
                    DragEvent.ACTION_DRAG_LOCATION -> edge(e.x)
                    DragEvent.ACTION_DROP -> {
                        val loc = IntArray(2); root.getLocationOnScreen(loc)
                        handleDrop(d, (loc[0] + e.x).toInt(), (loc[1] + e.y).toInt())
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        handler.removeCallbacks(edgeRunnable); edgeDir = 0
                        hideBar(); drawer.endDrag(); folderPopup.endDrag(); refreshAll()
                    }
                }
                true
            }
        }
    }

    private fun barBtn(text: String, icon: Int, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text; t.textSize = 11f; t.setTextColor(Color.WHITE); t.gravity = Gravity.CENTER
        val d = ContextCompat.getDrawable(this, icon)?.mutate()
        d?.setTint(Color.WHITE)
        t.setCompoundDrawablesWithIntrinsicBounds(null, d, null, null)
        t.compoundDrawablePadding = dp(2)
        t.setPadding(0, dp(6), 0, dp(4))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    private fun buildBar(): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.HORIZONTAL
        b.setBackgroundColor(0xF01A1A1A.toInt())
        b.visibility = View.GONE
        b.elevation = dp(6).toFloat()
        btnInfo = barBtn("App info", android.R.drawable.ic_menu_info_details) { selected?.let { appInfo(it) } }
        btnUninstall = barBtn("Uninstall", android.R.drawable.ic_menu_delete) { selected?.let { uninstall(it) } }
        btnEdit = barBtn("Edit", android.R.drawable.ic_menu_edit) { selected?.let { editDialog(it) } }
        btnRemove = barBtn("Remove", android.R.drawable.ic_menu_close_clear_cancel) { selected?.let { remove(it) } }
        btnResize = barBtn("Resize", android.R.drawable.ic_menu_crop) { selected?.item?.let { hideBar(); resizeDialog(it) } }
        for (t in listOf(btnInfo, btnUninstall, btnEdit, btnRemove, btnResize)) b.addView(t, LinearLayout.LayoutParams(0, -1, 1f))
        return b
    }

    // mode: 0 app, 1 drawer app, 2 widget, 3 folder
    private fun showBar(mode: Int) {
        btnInfo.visibility = if (mode == 0 || mode == 1) View.VISIBLE else View.GONE
        btnUninstall.visibility = if (mode == 0 || mode == 1) View.VISIBLE else View.GONE
        btnEdit.visibility = if (mode != 2) View.VISIBLE else View.GONE
        btnRemove.visibility = if (mode != 1) View.VISIBLE else View.GONE
        btnResize.visibility = if (mode == 2) View.VISIBLE else View.GONE
        bar.visibility = View.VISIBLE
        bar.alpha = 0f
        bar.animate().alpha(1f).setDuration(dur(120)).start()
    }

    private fun hideBar() {
        popup?.dismiss(); popup = null
        if (bar.visibility != View.VISIBLE && selected == null) return
        bar.visibility = View.GONE
        selected = null; armed = null
    }

    private fun pkgOf(d: DragData): String = d.item?.pkg ?: d.app?.pkg ?: ""

    private fun appInfo(d: DragData) {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkgOf(d))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        hideBar()
    }

    private fun uninstall(d: DragData) {
        try { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkgOf(d)))) } catch (e: Exception) { toast("Cannot uninstall") }
        hideBar()
    }

    private fun remove(d: DragData) {
        val item = d.item ?: return
        if (item.kind == 1 && item.widgetId >= 0) try { host.deleteAppWidgetId(item.widgetId) } catch (e: Exception) { }
        if (item.kind == 2) {
            // dissolve: put the apps back on the home screen
            for (k in store.children(item.id)) {
                val spot = findSpotAny(1, 1, item.page.coerceAtLeast(0), null, false)
                if (spot != null) { k.parent = 0; k.page = spot[0]; k.x = spot[1]; k.y = spot[2] } else store.items.remove(k)
            }
        }
        store.items.remove(item); store.save()
        hideBar(); refreshAll()
    }

    // ---------- pages ----------
    fun pageCount(): Int {
        val m = store.items.filter { it.page >= 0 && it.parent == 0L }.maxOfOrNull { it.page } ?: 0
        return maxOf(store.pages, m + 1).coerceAtMost(8)
    }

    private fun infinite(): Boolean = prefs.b("infinite", false) && pageCount() > 1
    fun curPage(): Int = pager.currentItem % pageCount()
    private fun defaultPage(): Int = prefs.n("default_page", 0).coerceIn(0, pageCount() - 1)

    private fun gotoPage(p: Int, smooth: Boolean) {
        val n = pageCount()
        val base = if (infinite()) (pager.currentItem / n) * n else 0
        pager.setCurrentItem(base + p.coerceIn(0, n - 1), smooth)
    }

    inner class PagesAdapter : RecyclerView.Adapter<CellVH>() {
        override fun getItemCount() = if (infinite()) pageCount() * 200 else pageCount()
        override fun onCreateViewHolder(p: ViewGroup, t: Int): CellVH {
            val c = CellLayout(this@Launcher)
            c.layoutParams = RecyclerView.LayoutParams(-1, -1)
            c.cbLong = { showAddMenu() }
            c.cbDown = { swipeCandidate = null; hideBar() }
            c.gestures = gest
            return CellVH(c)
        }
        override fun onBindViewHolder(h: CellVH, pos: Int) {
            val page = pos % pageCount()
            val c = h.cell
            c.removeAllViews()
            c.cols = prefs.cols; c.rows = prefs.rows
            val pw = dp(prefs.padDp("pad_w", true)); val ph = dp(prefs.padDp("pad_h", false))
            c.setPadding(pw, ph, pw, ph)
            val st = prefs.style("h")
            val px = icons.basePx(st.scale)
            for (item in store.items.filter { it.page == page && it.parent == 0L }) {
                val v = createView(item, st, px) ?: continue
                c.addView(v, CellLayout.LP(item.x, item.y, item.sx, item.sy))
            }
        }
    }

    private fun dockN() = prefs.n("dock_n", 5)
    private fun dockMid() = dockN() / 2
    private fun appsBtn() = prefs.b("dock_apps_btn", true)

    inner class DockAdapter : RecyclerView.Adapter<CellVH>() {
        override fun getItemCount() = prefs.n("dock_pages", 1)
        override fun onCreateViewHolder(p: ViewGroup, t: Int): CellVH {
            val c = CellLayout(this@Launcher)
            c.layoutParams = RecyclerView.LayoutParams(-1, -1)
            c.cbDown = { swipeCandidate = null; hideBar() }
            c.gestures = gest
            return CellVH(c)
        }
        override fun onBindViewHolder(h: CellVH, pos: Int) {
            val c = h.cell
            c.removeAllViews()
            c.cols = dockN(); c.rows = 1
            val st = prefs.style("k")
            val px = icons.basePx(st.scale)
            for (item in store.items.filter { it.page == -1 && it.y == pos && it.x < dockN() }) {
                val v = createView(item, st, px) ?: continue
                c.addView(v, CellLayout.LP(item.x, 0))
            }
            if (pos == 0 && appsBtn()) {
                val ab = AppsButton(this@Launcher)
                ab.setOnClickListener { drawer.open(0) }
                c.addView(ab, CellLayout.LP(dockMid(), 0))
            }
        }
    }

    private fun createView(item: Item, st: Style, px: Int): View? {
        val dark = Theme.isNight(this, prefs) && prefs.b("darken_icons", false)
        val hl = Col.alpha(accent(), 30)
        when (item.kind) {
            0 -> {
                val a = repo.byKey[item.pkg + "/" + item.cls] ?: return null
                val v = IconView(this, st, false, hl)
                v.bindApp(a, icons, px, item.label, dark)
                v.tag = item
                v.setOnClickListener { hideBar(); launch(a, it) }
                v.setOnLongClickListener { onItemLong(it, item); true }
                v.setOnTouchListener(touchL)
                return v
            }
            2 -> {
                val kids = store.children(item.id).mapNotNull { repo.byKey[it.pkg + "/" + it.cls] }
                val bmp = FolderRenderer.render(this, prefs, icons, kids, px)
                val v = IconView(this, st, false, hl)
                v.bindBitmap(bmp, if (item.label.isEmpty()) "Folder" else item.label, px, false)
                v.tag = item
                v.setOnClickListener {
                    hideBar()
                    if (prefs.b("f_swipe", false) && kids.isNotEmpty()) launch(kids[0], it) else folderPopup.show(item)
                }
                v.setOnLongClickListener { onItemLong(it, item); true }
                v.setOnTouchListener(touchL)
                return v
            }
            else -> {
                val info = awm.getAppWidgetInfo(item.widgetId) ?: return null
                val hv = host.createView(this, item.widgetId, info)
                hv.tag = item
                val r = prefs.n("widget_radius", 0)
                if (r > 0) {
                    hv.clipToOutline = true
                    hv.outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(r).toFloat()) }
                    }
                }
                if (prefs.b("widget_nopad", false)) hv.setPadding(0, 0, 0, 0)
                hv.addOnLayoutChangeListener { _, l, t, rr, b, _, _, _, _ ->
                    val d = resources.displayMetrics.density
                    val w = ((rr - l) / d).toInt(); val h = ((b - t) / d).toInt()
                    try { hv.updateAppWidgetSize(Bundle(), w, h, w, h) } catch (e: Exception) { }
                }
                if (hv is LHostView) hv.onLong = {
                    if (prefs.b("lock_desktop", false)) toast("Desktop is locked") else {
                        haptic(); selected = DragData(item, null); showBar(2)
                    }
                }
                return hv
            }
        }
    }

    // ---------- item interaction ----------
    fun onItemLong(v: View, item: Item) {
        if (prefs.b("lock_desktop", false)) { toast("Desktop is locked"); return }
        haptic()
        selected = DragData(item, null)
        armed = v
        v.parent?.requestDisallowInterceptTouchEvent(true)
        if (prefs.s("popup_menu", "bar") == "popup" && item.kind == 0) showPopup(v, item)
        else showBar(if (item.kind == 2) 3 else 0)
    }

    fun onDrawerLong(v: View, a: AppInfo) {
        haptic()
        selected = DragData(null, a)
        armed = v
        v.parent?.requestDisallowInterceptTouchEvent(true)
        showBar(1)
    }

    val touchL = object : View.OnTouchListener {
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; swipeCandidate = v.tag as? Item }
                MotionEvent.ACTION_MOVE ->
                    if (armed === v && Math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) > dp(8)) {
                        armed = null
                        startDrag(v)
                    }
            }
            return false
        }
    }

    private fun startDrag(v: View) {
        val d = selected ?: return
        popup?.dismiss(); popup = null
        if (d.app != null) drawer.hideForDrag()
        else {
            if ((d.item?.parent ?: 0L) != 0L) folderPopup.hideForDrag()
            v.visibility = View.INVISIBLE
        }
        v.startDragAndDrop(ClipData.newPlainText("x", "x"), View.DragShadowBuilder(v), d, 0)
    }

    private fun showPopup(v: View, item: Item) {
        val rows = ArrayList<Pair<String, () -> Unit>>()
        try {
            val la = getSystemService(LauncherApps::class.java)
            val q = LauncherApps.ShortcutQuery().setPackage(item.pkg).setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            la.getShortcuts(q, Process.myUserHandle())?.take(4)?.forEach { sc ->
                rows.add((sc.shortLabel?.toString() ?: "Shortcut") to { try { la.startShortcut(sc, null, null) } catch (e: Exception) { } })
            }
        } catch (e: Exception) { }
        val d = DragData(item, null)
        rows.add("App info" to { appInfo(d) })
        rows.add("Uninstall" to { uninstall(d) })
        rows.add("Edit" to { hideBar(); editDialog(d) })
        rows.add("Remove" to { remove(d) })
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = GradientDrawable().apply { setColor(0xF0222222.toInt()); cornerRadius = dp(8).toFloat() }
        box.setPadding(0, dp(6), 0, dp(6))
        val pw = PopupWindow(box, dp(190), -2)
        for ((t, a) in rows) {
            val tv = TextView(this)
            tv.text = t; tv.textSize = 15f; tv.setTextColor(Color.WHITE)
            tv.setPadding(dp(16), dp(10), dp(16), dp(10))
            tv.setOnClickListener { pw.dismiss(); popup = null; a() }
            box.addView(tv, LinearLayout.LayoutParams(-1, -2))
        }
        pw.isOutsideTouchable = true
        pw.isFocusable = false
        pw.elevation = dp(8).toFloat()
        popup = pw
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        val below = loc[1] < resources.displayMetrics.heightPixels / 2
        pw.showAtLocation(root, Gravity.NO_GRAVITY, (loc[0] + v.width / 2 - dp(95)).coerceIn(dp(8), resources.displayMetrics.widthPixels - dp(198)),
            if (below) loc[1] + v.height else loc[1] - dp(48) * rows.size - dp(12))
    }

    // ---------- refresh ----------
    fun refreshAll() {
        cleanup()
        fixOverflow()
        applyLayoutIfNeeded()
        applySearch()
        applyDock()
        val dsig = prefs.s("infinite", "") + prefs.b("infinite", false) + pageCount() + prefs.cols + prefs.rows
        val keep = if (pagerSig.isEmpty()) defaultPage() else curPage()
        pager.adapter?.notifyDataSetChanged()
        if (dsig != pagerSig) {
            pagerSig = dsig
            val n = pageCount()
            pager.setCurrentItem((if (infinite()) n * 100 else 0) + keep.coerceIn(0, n - 1), false)
        }
        val tn = prefs.s("scroll_effect", "simple")
        if (tn != transformerName) { transformerName = tn; pager.setPageTransformer(Transformers.get(tn)) }
        dockPager.adapter?.notifyDataSetChanged()
        val ac = accent()
        dots.style = prefs.s("ind_style", "line")
        dots.active = if (dots.style == "colordots") ac else Col.parse(prefs.s("ind_active", "#FFFFFF"), Color.WHITE)
        dots.inactive = Col.parse(prefs.s("ind_inactive", "#66FFFFFF"), 0x66FFFFFF)
        dots.set(pageCount(), curPage())
        folderPopup.refresh()
        divider.setBackgroundColor(0x55FFFFFF)
    }

    private fun cleanup() {
        var changed = false
        val it0 = store.items.iterator()
        while (it0.hasNext()) {
            val i = it0.next()
            if (i.kind == 1 && awm.getAppWidgetInfo(i.widgetId) == null) { it0.remove(); changed = true }
        }
        for (f in store.items.filter { it.kind == 2 }) {
            val kids = store.children(f.id)
            if (kids.isEmpty()) { store.items.remove(f); changed = true }
            else if (kids.size == 1) {
                val k = kids[0]
                k.parent = 0; k.page = f.page; k.x = f.x; k.y = f.y
                store.items.remove(f); changed = true
            }
        }
        if (changed) store.save()
    }

    private fun applyLayoutIfNeeded() {
        val sig = listOf(prefs.s("search_pos", "top"), prefs.b("dock_on", true), prefs.b("swipe_indicator", false), prefs.b("dock_divider", true)).toString()
        if (sig == layoutSig) { updateDockHeight(); return }
        layoutSig = sig
        val cur = if (pagerSig.isEmpty()) 0 else pager.currentItem
        main.removeAllViews()
        val pos = prefs.s("search_pos", "top")
        fun addSearch() = main.addView(searchBar, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(14), dp(6), dp(14), dp(2)) })
        if (pos == "top") addSearch()
        main.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        main.addView(dots, LinearLayout.LayoutParams(-1, dp(16)))
        if (pos == "above") addSearch()
        if (prefs.b("swipe_indicator", false)) main.addView(chevron, LinearLayout.LayoutParams(-1, dp(14)))
        if (prefs.b("dock_on", true)) {
            if (prefs.b("dock_divider", true)) main.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(10), 0, dp(10), 0) })
            main.addView(dockBox, LinearLayout.LayoutParams(-1, dockHeight()))
        }
        if (pos == "below" || pos == "bottom") addSearch()
        pager.post { pager.setCurrentItem(cur, false) }
        updateDockHeight()
    }

    private fun dockHeight(): Int {
        val st = prefs.style("k")
        return icons.basePx(st.scale) + dp(if (st.labels) 40 else 22) + dp(prefs.padDp("dock_pad_h", false)) * 2
    }

    private fun updateDockHeight() {
        val lp = dockBox.layoutParams
        if (lp != null && lp.height != dockHeight()) { lp.height = dockHeight(); dockBox.layoutParams = lp }
    }

    private fun applyDock() {
        val shape = prefs.s("dock_shape", "none")
        val color = Col.alpha(Col.parse(prefs.s("dock_color", "#000000"), Color.BLACK), prefs.n("dock_alpha", 0))
        dockBox.background = when (shape) {
            "rect" -> GradientDrawable().apply { setColor(color) }
            "rounded" -> GradientDrawable().apply { setColor(color); cornerRadius = dp(18).toFloat() }
            "pill" -> GradientDrawable().apply { setColor(color); cornerRadius = dp(60).toFloat() }
            "arc" -> GradientDrawable().apply { setColor(color); val r = dp(40).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            else -> null
        }
        val pw = dp(prefs.padDp("dock_pad_w", true))
        dockBox.setPadding(pw, 0, pw, 0)
    }

    private fun applySearch() {
        val p = prefs
        searchBar.removeAllViews()
        if (p.s("search_pos", "top") == "none") { searchBar.visibility = View.GONE; return }
        searchBar.visibility = View.VISIBLE
        val dark = Theme.surfaceDark(this, p, "night_search")
        val tc = if (dark) Color.WHITE else 0xFF222222.toInt()
        val fill = Col.parse(p.s("search_fill", "#77777A"), 0xFF77777A.toInt())
        searchBar.background = GradientDrawable().apply {
            setColor(Col.alpha(if (dark) fill else Color.WHITE, p.n("search_alpha", 80)))
            cornerRadius = when (p.s("search_shape", "rounded")) {
                "pill" -> dp(24).toFloat(); "sharp" -> 0f; else -> dp(p.n("search_radius", 2)).toFloat()
            }
            val bw = p.n("search_bw", 0)
            if (bw > 0) setStroke(dp(bw), Col.parse(p.s("search_border", "#FFFFFF"), Color.WHITE))
        }
        val left: View? = when (p.s("search_left", "g_text")) {
            "g_text" -> TextView(this).apply { text = "Google"; textSize = 24f; setTextColor(tc); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); gravity = Gravity.CENTER_VERTICAL }
            "g_color" -> TextView(this).apply { text = "G"; textSize = 26f; setTextColor(0xFF4285F4.toInt()); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL }
            "g_mono" -> TextView(this).apply { text = "G"; textSize = 26f; setTextColor(tc); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL }
            "s_icon" -> SearchGlyph(this, "search").apply { tint = tc }
            else -> null
        }
        if (left != null) searchBar.addView(left, FrameLayout.LayoutParams(if (left is SearchGlyph) dp(44) else -2, -1).apply { leftMargin = dp(if (left is SearchGlyph) 6 else 16) })
        val right: View? = when (p.s("search_right", "mic")) {
            "mic" -> MicView(this).apply { tint = tc; setOnClickListener { try { startActivity(Intent(RecognizerIntent.ACTION_WEB_SEARCH)) } catch (e: Exception) { toast("No voice search") } } }
            "lens" -> SearchGlyph(this, "lens").apply { tint = tc; setOnClickListener { openUrl("https://lens.google.com/") } }
            "assist" -> SearchGlyph(this, "assist").apply { setOnClickListener { runAction("voice") } }
            else -> null
        }
        if (right != null) searchBar.addView(right, FrameLayout.LayoutParams(dp(44), -1, Gravity.END))
        searchBar.setOnClickListener { searchTap() }
    }

    private fun searchTap() {
        try {
            when (prefs.s("search_provider", "search")) {
                "google" -> startActivity(packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox"))
                "assistant" -> runAction("voice")
                "custom" -> openUrl(prefs.s("search_url", "https://www.google.com"))
                else -> startActivity(Intent(Intent.ACTION_WEB_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) { openUrl("https://www.google.com") }
    }

    private fun openUrl(u: String) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { } }

    // ---------- placement ----------
    private fun isFree(page: Int, x: Int, y: Int, sx: Int, sy: Int, ignore: Item?, widget: Boolean): Boolean {
        if (widget && prefs.b("widget_overlap", false)) return true
        return store.items.none { it !== ignore && it.page == page && it.parent == 0L && x < it.x + it.sx && x + sx > it.x && y < it.y + it.sy && y + sy > it.y }
    }

    private fun findFree(page: Int, tx: Int, ty: Int, ignore: Item?): IntArray? {
        var best: IntArray? = null
        var bd = Int.MAX_VALUE
        for (y in 0 until prefs.rows) for (x in 0 until prefs.cols) {
            if (isFree(page, x, y, 1, 1, ignore, false)) {
                val dd = (x - tx) * (x - tx) + (y - ty) * (y - ty)
                if (dd < bd) { bd = dd; best = intArrayOf(x, y) }
            }
        }
        return best
    }

    private fun findSpotAny(sx: Int, sy: Int, start: Int, ignore: Item?, widget: Boolean): IntArray? {
        for (k in 0 until 8) {
            val pg = (start + k) % 8
            for (y in 0..(prefs.rows - sy)) for (x in 0..(prefs.cols - sx)) if (isFree(pg, x, y, sx, sy, ignore, widget)) return intArrayOf(pg, x, y)
        }
        return null
    }

    private fun dockFree(pg: Int, s: Int, ignore: Item?): Boolean =
        !(pg == 0 && appsBtn() && s == dockMid()) && store.items.none { it !== ignore && it.page == -1 && it.y == pg && it.x == s }

    private fun fixOverflow() {
        var changed = false
        for (i in store.items.filter { it.page >= 0 && it.parent == 0L }) {
            if (i.x + i.sx > prefs.cols || i.y + i.sy > prefs.rows) {
                i.sx = minOf(i.sx, prefs.cols); i.sy = minOf(i.sy, prefs.rows)
                val spot = findSpotAny(i.sx, i.sy, i.page, i, i.kind == 1)
                if (spot != null) { i.page = spot[0]; i.x = spot[1]; i.y = spot[2]; changed = true }
            }
        }
        val n = dockN(); val pgs = prefs.n("dock_pages", 1)
        for (i in store.items.filter { it.page == -1 }) {
            val bad = i.x >= n || i.y >= pgs || (i.y == 0 && appsBtn() && i.x == dockMid())
            if (bad) {
                var done = false
                for (pg in 0 until pgs) { for (s in 0 until n) if (dockFree(pg, s, i)) { i.y = pg; i.x = s; done = true; break }; if (done) break }
                if (!done) {
                    val spot = findSpotAny(1, 1, 0, i, false)
                    if (spot != null) { i.page = spot[0]; i.x = spot[1]; i.y = spot[2] }
                }
                changed = true
            }
        }
        if (changed) store.save()
    }

    private fun currentCell(): CellLayout? {
        val rv = pager.getChildAt(0) as RecyclerView
        return rv.findViewHolderForAdapterPosition(pager.currentItem)?.itemView as? CellLayout
    }

    private fun currentDockCell(): CellLayout? {
        val rv = dockPager.getChildAt(0) as RecyclerView
        return rv.findViewHolderForAdapterPosition(dockPager.currentItem)?.itemView as? CellLayout
    }

    private fun hit(v: View, sx: Int, sy: Int): Boolean {
        if (!v.isShown) return false
        val l = IntArray(2); v.getLocationOnScreen(l)
        return sx >= l[0] && sx <= l[0] + v.width && sy >= l[1] && sy <= l[1] + v.height
    }

    private fun handleDrop(d: DragData, sx: Int, sy: Int) {
        if (bar.isShown) {
            if (hit(btnInfo, sx, sy)) { appInfo(d); return }
            if (hit(btnUninstall, sx, sy)) { uninstall(d); return }
            if (hit(btnEdit, sx, sy)) { hideBar(); editDialog(d); return }
            if (hit(btnRemove, sx, sy)) { remove(d); return }
        }
        if (prefs.b("lock_desktop", false)) { toast("Desktop is locked"); return }
        if (prefs.b("dock_on", true) && hit(dockBox, sx, sy)) {
            val cell = currentDockCell() ?: return
            val cl = IntArray(2); cell.getLocationOnScreen(cl)
            val c = cell.cellAt((sx - cl[0]).toFloat(), 0f)
            placeOnDock(d, dockPager.currentItem, c[0])
            return
        }
        val cell = currentCell() ?: return
        val cl = IntArray(2); cell.getLocationOnScreen(cl)
        if (sy < cl[1] || sy > cl[1] + cell.height) return
        val c = cell.cellAt((sx - cl[0]).toFloat(), (sy - cl[1]).toFloat())
        placeOnPage(d, curPage(), c[0], c[1])
    }

    private fun placeOnPage(d: DragData, page: Int, x: Int, y: Int) {
        val src = d.item
        val occ = store.items.firstOrNull {
            it !== src && it.parent == 0L && it.page == page && x >= it.x && x < it.x + it.sx && y >= it.y && y < it.y + it.sy
        }
        val dragApp = src == null || src.kind == 0
        fun obtain(): Item = src ?: Item(store.nextId(), 0, d.app!!.pkg, d.app.cls, page, x, y).also { store.items.add(it) }
        if (occ != null && dragApp && !prefs.b("overlap_place", false)) {
            if (occ.kind == 2) {
                val item = obtain()
                item.parent = occ.id; item.page = -3
                store.save(); return
            }
            if (occ.kind == 0) {
                val folder = Item(store.nextId(), 2, "", "", page, occ.x, occ.y)
                folder.label = "Folder"
                store.items.add(folder)
                val item = obtain()
                occ.parent = folder.id; occ.page = -3
                item.parent = folder.id; item.page = -3
                store.save(); return
            }
        }
        if (occ != null && prefs.b("overlap_place", false)) {
            val item = obtain(); item.parent = 0; item.page = page; item.x = x; item.y = y
            store.save(); return
        }
        val spot = findFree(page, x, y, src)
        if (spot == null) { toast("No more room on this screen"); return }
        val item = obtain()
        item.parent = 0; item.page = page; item.x = spot[0]; item.y = spot[1]
        store.save()
    }

    private fun placeOnDock(d: DragData, pg: Int, slot: Int) {
        if (d.item?.kind == 2) { toast("Folders can't go in the dock"); return }
        var best = -1
        var bd = Int.MAX_VALUE
        for (s in 0 until dockN()) if (dockFree(pg, s, d.item)) {
            val dd = Math.abs(s - slot)
            if (dd < bd) { bd = dd; best = s }
        }
        if (best < 0) { toast("Dock page is full"); return }
        val item = d.item ?: Item(store.nextId(), 0, d.app!!.pkg, d.app.cls, -1, best, pg).also { store.items.add(it) }
        item.parent = 0; item.page = -1; item.x = best; item.y = pg
        store.save()
    }

    private val edgeRunnable = Runnable {
        if (edgeDir != 0) {
            val n = pageCount()
            if (infinite()) pager.setCurrentItem(pager.currentItem + edgeDir, true)
            else {
                var t = pager.currentItem + edgeDir
                if (t >= n && n < 8) { store.pages = n + 1; pager.adapter?.notifyDataSetChanged() }
                t = t.coerceIn(0, pageCount() - 1)
                if (t != pager.currentItem) pager.setCurrentItem(t, true)
            }
            edgeDir = 0
        }
    }

    private fun edge(x: Float) {
        val dir = when {
            x < dp(36) -> -1
            x > root.width - dp(36) -> 1
            else -> 0
        }
        if (dir == 0) { if (edgeDir != 0) { handler.removeCallbacks(edgeRunnable); edgeDir = 0 } }
        else if (edgeDir != dir) {
            edgeDir = dir
            handler.removeCallbacks(edgeRunnable)
            handler.postDelayed(edgeRunnable, 600)
        }
    }

    // ---------- widgets ----------
    fun addWidget(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        if (awm.bindAppWidgetIdIfAllowed(id, info.provider)) configureOrPlace(id, info)
        else {
            pendingId = id; pendingInfo = info
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_BIND)
        }
    }

    private fun configureOrPlace(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            pendingId = id; pendingInfo = info
            try { host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null) }
            catch (e: Exception) { placeWidget(id, info) }
        } else placeWidget(id, info)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_BIND || req == REQ_CONFIG) {
            val info = pendingInfo
            if (res == RESULT_OK && info != null) {
                if (req == REQ_BIND) configureOrPlace(pendingId, info) else placeWidget(pendingId, info)
            } else if (pendingId >= 0) { host.deleteAppWidgetId(pendingId); pendingId = -1 }
        } else if (req == REQ_IMG && res == RESULT_OK) {
            val uri = data?.data
            val app = editApp
            if (uri != null && app != null) try {
                contentResolver.openInputStream(uri)?.use { ins ->
                    val src = BitmapFactory.decodeStream(ins)
                    if (src != null) icons.setCustom(app.key, android.graphics.Bitmap.createScaledBitmap(src, 192, 192, true))
                }
                drawer.dirty = true; refreshAll()
            } catch (e: Exception) { toast("Couldn't load that image") }
        }
    }

    private fun placeWidget(id: Int, info: AppWidgetProviderInfo) {
        val cols = prefs.cols; val rows = prefs.rows
        val cw = (pager.width - dp(16)) / cols.toFloat()
        val ch = (pager.height - dp(8)) / rows.toFloat()
        var sx = Math.ceil((info.minWidth / cw).toDouble()).toInt().coerceIn(1, cols)
        var sy = Math.ceil((info.minHeight / ch).toDouble()).toInt().coerceIn(1, rows)
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0) {
            sx = info.targetCellWidth.coerceIn(1, cols); sy = info.targetCellHeight.coerceIn(1, rows)
        }
        val spot = findSpotAny(sx, sy, curPage(), null, true)
        if (spot == null) { toast("No more room"); host.deleteAppWidgetId(id); return }
        store.items.add(Item(store.nextId(), 1, info.provider.packageName, info.provider.className, spot[0], spot[1], spot[2], sx, sy, id))
        store.save()
        pendingId = -1
        refreshAll()
        gotoPage(spot[0], false)
    }

    private fun resizeDialog(item: Item) {
        var w = item.sx
        var h = item.sy
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), 0) }
        fun row(label: String, get: () -> Int, set: (Int) -> Unit, max: Int): View {
            val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
            val t = TextView(this); t.textSize = 16f
            fun upd() { t.text = "$label: ${get()}" }
            val minus = Button(this).apply { text = "-"; setOnClickListener { if (get() > 1) { set(get() - 1); upd() } } }
            val plus = Button(this).apply { text = "+"; setOnClickListener { if (get() < max) { set(get() + 1); upd() } } }
            upd()
            r.addView(t, LinearLayout.LayoutParams(0, -2, 1f)); r.addView(minus); r.addView(plus)
            return r
        }
        box.addView(row("Width (cells)", { w }, { w = it }, prefs.cols))
        box.addView(row("Height (cells)", { h }, { h = it }, prefs.rows))
        AlertDialog.Builder(this).setTitle("Resize widget").setView(box)
            .setPositiveButton("OK") { _, _ ->
                if (item.x + w <= prefs.cols && item.y + h <= prefs.rows && isFree(item.page, item.x, item.y, w, h, item, true)) {
                    item.sx = w; item.sy = h; store.save(); refreshAll()
                } else toast("Not enough room here")
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- edit / rename / pages ----------
    fun renameFolder(f: Item) {
        val et = EditText(this); et.setText(f.label)
        AlertDialog.Builder(this).setTitle("Folder name").setView(et).setPositiveButton("OK") { _, _ ->
            f.label = et.text.toString().trim(); store.save(); refreshAll()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun editDialog(d: DragData) {
        val item = d.item
        val app: AppInfo? = d.app ?: item?.let { repo.byKey[it.pkg + "/" + it.cls] }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), 0) }
        val et = EditText(this)
        if (item != null && item.kind != 1) {
            et.hint = "Label"
            et.setText(if (item.label.isNotEmpty()) item.label else (app?.label ?: ""))
            box.addView(et)
        }
        if (app != null) {
            box.addView(Button(this).apply {
                text = "Choose icon image..."
                setOnClickListener {
                    editApp = app
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), "Pick icon"), REQ_IMG)
                }
            })
            box.addView(Button(this).apply {
                text = "Reset icon"
                setOnClickListener { icons.setCustom(app.key, null); drawer.dirty = true; refreshAll(); toast("Icon reset") }
            })
        }
        if (item != null && item.kind == 0) {
            val sw = Button(this)
            fun upd() { sw.text = "Swipe-up app: " + (repo.byKey[item.swipe]?.label ?: "none") }
            upd()
            sw.setOnClickListener {
                val names = arrayOf("None") + repo.apps.map { it.label }
                AlertDialog.Builder(this).setTitle("Swipe-up app").setItems(names) { _, w ->
                    item.swipe = if (w == 0) "" else repo.apps[w - 1].key
                    store.save(); upd()
                }.show()
            }
            box.addView(sw)
        }
        AlertDialog.Builder(this).setTitle("Edit").setView(box).setPositiveButton("OK") { _, _ ->
            if (item != null && item.kind != 1) {
                val t = et.text.toString().trim()
                item.label = if (item.kind == 0 && t == app?.label) "" else t
                store.save()
            }
            refreshAll()
        }.setNegativeButton("Close", null).show()
    }

    private fun pagesDialog() {
        val n = pageCount()
        val names = ArrayList<String>()
        for (i in 0 until n) names.add("Page ${i + 1}" + if (i == defaultPage()) "  (default)" else "")
        if (n < 8) names.add("+ Add page")
        AlertDialog.Builder(this).setTitle("Home screen pages").setItems(names.toTypedArray()) { _, w ->
            if (w >= n) { store.pages = n + 1; refreshAll(); pagesDialog(); return@setItems }
            AlertDialog.Builder(this).setTitle("Page ${w + 1}").setItems(arrayOf("Set as default", "Move left", "Move right", "Delete")) { _, a ->
                when (a) {
                    0 -> { prefs.sp.edit().putString("default_page", w.toString()).apply(); toast("Default page set") }
                    1 -> if (w > 0) swapPages(w, w - 1)
                    2 -> if (w < n - 1) swapPages(w, w + 1)
                    else -> if (n > 1) AlertDialog.Builder(this).setTitle("Delete page ${w + 1}?")
                        .setMessage("Everything on this page is removed.")
                        .setPositiveButton("Delete") { _, _ -> deletePage(w) }.setNegativeButton("Cancel", null).show()
                }
            }.show()
        }.show()
    }

    private fun swapPages(a: Int, b: Int) {
        for (i in store.items) if (i.page == a) i.page = b else if (i.page == b) i.page = a
        store.save(); refreshAll()
    }

    private fun deletePage(p: Int) {
        val gone = store.items.filter { it.page == p }
        for (g in gone) {
            if (g.kind == 1) try { host.deleteAppWidgetId(g.widgetId) } catch (e: Exception) { }
            if (g.kind == 2) store.items.removeAll { it.parent == g.id }
        }
        store.items.removeAll { it.page == p }
        for (i in store.items) if (i.page > p) i.page -= 1
        store.pages = pageCount() - 1
        val d = prefs.n("default_page", 0)
        if (d >= p && d > 0) prefs.sp.edit().putString("default_page", (d - 1).toString()).apply()
        store.save(); refreshAll()
    }

    // ---------- actions ----------
    fun launch(a: AppInfo, v: View) {
        try {
            usage.hit(a.key)
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(a.pkg, a.cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val opts: Bundle? = when (prefs.s("launch_anim", "zoom")) {
                "slide" -> ActivityOptionsCompat.makeCustomAnimation(this, R.anim.slide_up, R.anim.hold).toBundle()
                "fade" -> ActivityOptionsCompat.makeCustomAnimation(this, R.anim.fade_in, R.anim.hold).toBundle()
                "reveal" -> ActivityOptionsCompat.makeClipRevealAnimation(v, 0, 0, v.width, v.height).toBundle()
                "system" -> null
                else -> ActivityOptionsCompat.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle()
            }
            startActivity(i, opts)
            drawer.afterLaunch()
            if (prefs.b("dock_auto_close", true) && dockPager.currentItem != 0) dockPager.setCurrentItem(0, false)
        } catch (e: Exception) { toast("Can't open " + a.label) }
    }

    fun launchKey(key: String): Boolean {
        val a = repo.byKey[key] ?: return false
        launch(a, root)
        return true
    }

    fun openSettings() { startActivity(Intent(this, SettingsActivity::class.java)) }

    fun openWallpaper() {
        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Select wallpaper")) } catch (e: Exception) { }
    }

    private fun showAddMenu() {
        AlertDialog.Builder(this).setItems(arrayOf("Widgets", "Wallpapers", "Edit pages", "Launcher settings")) { _, w ->
            when (w) { 0 -> drawer.open(1); 1 -> openWallpaper(); 2 -> pagesDialog(); else -> openSettings() }
        }.show()
    }

    private fun onGesture(name: String) {
        if (name == "up") {
            val c = swipeCandidate
            if (c != null) {
                if (c.kind == 2 && prefs.b("f_swipe", false)) { folderPopup.show(c); return }
                if (c.swipe.isNotEmpty() && launchKey(c.swipe)) return
            }
        }
        val def = when (name) { "up" -> "drawer"; "down" -> "notif"; else -> "none" }
        runAction(prefs.s("g_$name", def))
    }

    private fun accessAction(code: Int) {
        val s = JbAccess.instance
        if (s == null) {
            toast("Enable the JB Launcher accessibility service")
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { }
        } else s.performGlobalAction(code)
    }

    fun runAction(a: String) {
        when {
            a == "none" -> {}
            a == "drawer" -> drawer.open(0)
            a == "search" -> searchTap()
            a == "settings" -> openSettings()
            a == "voice" -> try { startActivity(Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: Exception) { try { startActivity(Intent(Intent.ACTION_VOICE_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { } }
            a == "notif" -> try {
                Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(getSystemService("statusbar"))
            } catch (e: Exception) { accessAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) }
            a == "qs" -> try {
                Class.forName("android.app.StatusBarManager").getMethod("expandSettingsPanel").invoke(getSystemService("statusbar"))
            } catch (e: Exception) { accessAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) }
            a == "recents" -> accessAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            a == "lock" -> if (Build.VERSION.SDK_INT >= 28) accessAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                else startActivity(Intent(this, LockActivity::class.java))
            a.startsWith("app:") -> launchKey(a.removePrefix("app:"))
            a.startsWith("act:") -> try {
                val cn = ComponentName.unflattenFromString(a.removePrefix("act:"))
                if (cn != null) startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { toast("Can't open that activity") }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
