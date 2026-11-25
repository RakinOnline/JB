package com.jb.launcher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

object TabsUi {
    fun create(ctx: Context, repo: AppRepo, groups: Groups, done: () -> Unit) {
        val et = EditText(ctx)
        AlertDialog.Builder(ctx).setTitle("New tab").setView(et)
            .setPositiveButton("Create") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) {
                    val list = groups.all()
                    list.add(Group(n, LinkedHashSet()))
                    groups.save(list)
                    pick(ctx, repo, groups, n, done)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    fun pick(ctx: Context, repo: AppRepo, groups: Groups, name: String, done: () -> Unit) {
        val list = groups.all()
        val g = list.firstOrNull { it.name == name } ?: return
        val apps = repo.apps
        val checked = BooleanArray(apps.size) { apps[it].pkg in g.pkgs }
        AlertDialog.Builder(ctx).setTitle("Apps in \"$name\"")
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("OK") { _, _ ->
                g.pkgs.clear()
                for (i in apps.indices) if (checked[i]) g.pkgs.add(apps[i].pkg)
                groups.save(list); done()
            }.setNegativeButton("Cancel", null).show()
    }

    fun edit(ctx: Context, repo: AppRepo, groups: Groups, done: () -> Unit) {
        val list = groups.all()
        if (list.isEmpty()) { android.widget.Toast.makeText(ctx, "No custom tabs yet", android.widget.Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(ctx).setTitle("Edit tabs").setItems(list.map { it.name }.toTypedArray()) { _, w ->
            val g = list[w]
            AlertDialog.Builder(ctx).setTitle(g.name).setItems(arrayOf("Select apps", "Rename", "Delete")) { _, a ->
                when (a) {
                    0 -> pick(ctx, repo, groups, g.name, done)
                    1 -> {
                        val et = EditText(ctx); et.setText(g.name)
                        AlertDialog.Builder(ctx).setTitle("Rename tab").setView(et).setPositiveButton("OK") { _, _ ->
                            val n = et.text.toString().trim()
                            if (n.isNotEmpty()) { g.name = n; groups.save(list); done() }
                        }.setNegativeButton("Cancel", null).show()
                    }
                    else -> { list.removeAt(w); groups.save(list); done() }
                }
            }.show()
        }.show()
    }
}

class Drawer(private val l: Launcher) : FrameLayout(l) {
    var isOpen = false
    var dirty = true
    private var tabKey = "apps"
    private var query = ""
    private var muting = false
    private var dark = true
    private var fg = Color.WHITE
    private var accent = Col.HOLO
    private val col = LinearLayout(l).apply { orientation = LinearLayout.VERTICAL }
    private val header = LinearLayout(l).apply { orientation = LinearLayout.VERTICAL }
    private val tabsRow = LinearLayout(l).apply { orientation = LinearLayout.HORIZONTAL }
    private val content = FrameLayout(l)
    private val strip = LinearLayout(l).apply { orientation = LinearLayout.VERTICAL }
    private val dots = Dots(l)
    private val search = EditText(l)
    private var curPager: ViewPager2? = null
    private var curRv: RecyclerView? = null
    private var curApps: List<AppInfo> = emptyList()
    private var perPage = 20

    init {
        isClickable = true
        addView(col, LayoutParams(-1, -1))
        val row = LinearLayout(l).apply { orientation = LinearLayout.HORIZONTAL }
        val hs = HorizontalScrollView(l).apply { isHorizontalScrollBarEnabled = false; addView(tabsRow) }
        row.addView(hs, LinearLayout.LayoutParams(0, l.dp(48), 1f))
        val ov = OverflowIcon(l)
        ov.setOnClickListener { v ->
            val pm = PopupMenu(l, v)
            pm.menu.add(0, 1, 0, "Launcher settings")
            pm.menu.add(0, 2, 1, "Wallpapers")
            pm.menu.add(0, 3, 2, "New tab")
            pm.menu.add(0, 4, 3, "Edit tabs")
            pm.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { close(); l.openSettings() }
                    2 -> { close(); l.openWallpaper() }
                    3 -> TabsUi.create(l, l.repo, l.groups) { refreshTabs() }
                    else -> TabsUi.edit(l, l.repo, l.groups) { refreshTabs() }
                }
                true
            }
            pm.show()
        }
        row.addView(ov, LinearLayout.LayoutParams(l.dp(48), l.dp(48)))
        header.addView(row, LinearLayout.LayoutParams(-1, l.dp(48)))
        val line = View(l); line.setBackgroundColor(0x33888888)
        header.addView(line, LinearLayout.LayoutParams(-1, l.dp(1)))

        search.hint = "Search apps"
        search.setSingleLine(true)
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.textSize = 16f
        search.setPadding(l.dp(14), l.dp(8), l.dp(14), l.dp(8))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (muting) return
                query = s?.toString()?.trim() ?: ""
                rebuildContent()
            }
        })
        search.setOnEditorActionListener { v, _, _ ->
            val first = if (query.isNotEmpty()) fuzzy(query).firstOrNull() else null
            if (first != null) { close(); l.launch(first, v) }
            true
        }
    }

    // ---------- open / close ----------
    fun open(t: Int = 0) {
        if (!l.prefs.b("d_remember", true)) { tabKey = "apps"; query = ""; dirty = true }
        if (t == 1) tabKey = "widgets"
        if (dirty) rebuild()
        visibility = View.VISIBLE
        alpha = 0f; scaleX = 0.94f; scaleY = 0.94f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(l.dur(160)).setInterpolator(DecelerateInterpolator()).start()
        isOpen = true
        if (l.prefs.b("d_keyboard", false) && l.prefs.s("d_search", "none") != "none") {
            search.requestFocus()
            search.postDelayed({
                (l.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, 0)
            }, 200)
        }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        try {
            (l.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
            search.clearFocus()
        } catch (e: Exception) { }
        animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(l.dur(130))
            .withEndAction { if (!isOpen) visibility = View.GONE }.start()
    }

    fun afterLaunch() {
        if (l.prefs.b("d_close", true)) { tabKey = "apps"; query = ""; dirty = true }
    }

    fun hideForDrag() { isOpen = false; animate().cancel(); alpha = 0f; visibility = View.INVISIBLE }
    fun endDrag() { if (!isOpen && visibility == View.INVISIBLE) { visibility = View.GONE; alpha = 1f } }

    // ---------- build ----------
    private fun refreshTabs() { dirty = true; if (isOpen) { rebuild() } }

    private fun rebuild() {
        val p = l.prefs
        dark = Theme.surfaceDark(l, p, "night_drawer")
        fg = if (dark) Color.WHITE else 0xFF222222.toInt()
        accent = Theme.accent(l, p)
        val bgBase = if (dark) Col.parse(p.s("d_bg", "#000000"), Color.BLACK) else 0xFFF2F2F2.toInt()
        val card = p.b("d_card", false)
        val m = if (card) l.dp(10) else 0
        val lp = col.layoutParams as LayoutParams
        lp.setMargins(m, m, m, m)
        col.layoutParams = lp
        if (card) {
            setBackgroundColor(0x66000000)
            col.background = GradientDrawable().apply {
                setColor(Col.alpha(if (dark) Col.parse(p.s("d_card_color", "#1A1A1A"), 0xFF1A1A1A.toInt()) else Color.WHITE, p.n("d_alpha", 100)))
                cornerRadius = l.dp(18).toFloat()
            }
        } else {
            setBackgroundColor(Col.alpha(bgBase, p.n("d_alpha", 100)))
            col.background = null
        }
        search.setTextColor(fg)
        search.setHintTextColor(if (dark) 0x99FFFFFF.toInt() else 0x99000000.toInt())
        search.background = GradientDrawable().apply { setColor(if (dark) 0x33FFFFFF else 0x14000000); cornerRadius = l.dp(20).toFloat() }
        if (tabKey.startsWith("g:") && l.groups.all().none { "g:" + it.name == tabKey }) tabKey = "apps"

        fillTabs()
        col.removeAllViews()
        val tabsTop = p.s("d_tabs_pos", "top") == "top"
        val sPos = p.s("d_search", "none")
        val searchLp = LinearLayout.LayoutParams(-1, -2).apply { setMargins(l.dp(12), l.dp(6), l.dp(12), l.dp(6)) }
        if (search.parent != null) (search.parent as ViewGroup).removeView(search)
        if (tabsTop) col.addView(header, LinearLayout.LayoutParams(-1, -2))
        if (sPos == "top") col.addView(search, searchLp)
        col.addView(strip, LinearLayout.LayoutParams(-1, -2))
        col.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        if (sPos == "bottom") col.addView(search, searchLp)
        col.addView(dots, LinearLayout.LayoutParams(-1, l.dp(18)))
        if (!tabsTop) col.addView(header, LinearLayout.LayoutParams(-1, -2))
        muting = true; search.setText(query); muting = false
        dots.style = p.s("ind_style", "line")
        dots.active = fg; dots.inactive = if (dark) 0x55FFFFFF else 0x55000000
        rebuildContent()
        dirty = false
    }

    private fun fillTabs() {
        tabsRow.removeAllViews()
        val p = l.prefs
        val tabs = ArrayList<Pair<String, String>>()
        tabs.add("apps" to "APPS")
        for (g in l.groups.all()) tabs.add("g:" + g.name to g.name.uppercase())
        tabs.add("widgets" to "WIDGETS")
        val pill = p.s("d_tab_style", "underline") == "pill"
        val selPref = p.s("d_tab_sel", "#33B5E5")
        val sel = if (selPref.equals("#33B5E5", true)) accent else Col.parse(selPref, accent)
        val pillBg = if (selPref.equals("#33B5E5", true) && p.s("d_tab_bg", "#33B5E5").equals("#33B5E5", true)) accent
            else Col.parse(p.s("d_tab_bg", "#33B5E5"), accent)
        for ((k, t) in tabs) {
            val on = k == tabKey
            val tv = TextView(l).apply {
                text = t; textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER; setSingleLine(true)
            }
            val v: View
            if (pill) {
                tv.setPadding(l.dp(14), 0, l.dp(14), 0)
                tv.setTextColor(if (on) Color.WHITE else fg)
                if (on) tv.background = GradientDrawable().apply { setColor(pillBg); cornerRadius = l.dp(16).toFloat() }
                val box = FrameLayout(l)
                box.addView(tv, FrameLayout.LayoutParams(-2, l.dp(32), Gravity.CENTER))
                v = box
                tabsRow.addView(v, LinearLayout.LayoutParams(-2, l.dp(48)).apply { leftMargin = l.dp(4); rightMargin = l.dp(4) })
            } else {
                tv.setPadding(l.dp(4), 0, l.dp(4), 0)
                tv.setTextColor(if (on) sel else fg)
                val c = LinearLayout(l).apply { orientation = LinearLayout.VERTICAL }
                c.addView(tv, LinearLayout.LayoutParams(-2, 0, 1f))
                val ind = View(l); ind.setBackgroundColor(if (on) sel else 0)
                c.addView(ind, LinearLayout.LayoutParams(-1, l.dp(2)))
                v = c
                tabsRow.addView(v, LinearLayout.LayoutParams(-2, l.dp(48)).apply { leftMargin = l.dp(12); rightMargin = l.dp(8) })
            }
            v.isClickable = true
            v.setOnClickListener { tabKey = k; query = ""; muting = true; search.setText(""); muting = false; fillTabs(); rebuildContent() }
        }
    }

    private fun visibleApps(): List<AppInfo> { val h = l.prefs.hidden; return l.repo.apps.filter { it.pkg !in h } }

    private fun appsFor(key: String): List<AppInfo> {
        val base = visibleApps()
        val groups = l.groups.all()
        if (key == "apps") {
            if (l.prefs.b("d_keep_main", true)) return base
            val g = groups.flatMap { it.pkgs }.toSet()
            return base.filter { it.pkg !in g }
        }
        val grp = groups.firstOrNull { "g:" + it.name == key }
        return base.filter { it.pkg in (grp?.pkgs ?: emptySet<String>()) }
    }

    private fun score(label: String, q: String): Int {
        if (label.startsWith(q)) return 100
        if (label.contains(" $q")) return 80
        if (label.contains(q)) return 60
        val init = label.split(" ", "-", "_").filter { it.isNotEmpty() }.map { it[0] }.joinToString("")
        if (init.startsWith(q)) return 50
        var qi = 0
        for (ch in label) { if (qi < q.length && ch == q[qi]) qi++ }
        return if (qi == q.length) 20 else 0
    }

    private fun fuzzy(q: String): List<AppInfo> {
        val s = q.lowercase().trim()
        return visibleApps().map { it to score(it.label.lowercase(), s) }.filter { it.second > 0 }
            .sortedByDescending { it.second }.map { it.first }
    }

    private fun style(): Style {
        val s = l.prefs.style("d")
        return if (!dark && s.color == Color.WHITE) s.copy(color = 0xFF222222.toInt()) else s
    }

    private fun darkIcons() = Theme.isNight(l, l.prefs) && l.prefs.b("darken_icons", false)

    private fun rebuildContent() {
        content.removeAllViews()
        curPager = null; curRv = null; curApps = emptyList()
        buildStrip()
        when {
            query.isNotEmpty() -> buildApps(fuzzy(query), true)
            tabKey == "widgets" -> buildWidgets()
            else -> buildApps(appsFor(tabKey), false)
        }
        syncDots()
    }

    private fun syncDots() {
        val vp = curPager
        if (vp == null) dots.set(1, 0) else dots.set(vp.adapter?.itemCount ?: 1, vp.currentItem)
    }

    private fun pageCb() = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) { syncDots() }
    }

    private fun appIcon(a: AppInfo, st: Style, px: Int, horizontal: Boolean = false): IconView {
        val v = IconView(l, st, horizontal, Col.alpha(accent, 30))
        v.bindApp(a, l.icons, px, null, darkIcons())
        v.tag = a
        v.setOnClickListener { close(); l.launch(a, it) }
        v.setOnLongClickListener { l.onDrawerLong(it, a); true }
        v.setOnTouchListener(l.touchL)
        return v
    }

    private fun buildStrip() {
        strip.removeAllViews()
        val mode = l.prefs.s("d_strip", "none")
        if (mode == "none" || tabKey != "apps" || query.isNotEmpty()) { strip.visibility = View.GONE; return }
        val h = l.prefs.hidden
        val list: List<AppInfo> = if (mode == "frequent") l.usage.top(5).mapNotNull { l.repo.byKey[it] }.filter { it.pkg !in h }
            else l.repo.recent(5).filter { it.pkg !in h }
        if (list.isEmpty()) { strip.visibility = View.GONE; return }
        val st = style().copy(labels = false)
        val px = (l.icons.basePx(style().scale) * 0.85f).toInt()
        val row = LinearLayout(l).apply { orientation = LinearLayout.HORIZONTAL; setPadding(l.dp(8), l.dp(6), l.dp(8), l.dp(6)) }
        for (a in list) row.addView(appIcon(a, st, px), LinearLayout.LayoutParams(0, -2, 1f))
        strip.addView(row, LinearLayout.LayoutParams(-1, -2))
        val line = View(l); line.setBackgroundColor(0x22888888)
        strip.addView(line, LinearLayout.LayoutParams(-1, l.dp(1)))
        strip.visibility = View.VISIBLE
    }

    private fun buildApps(apps: List<AppInfo>, forceVertical: Boolean) {
        curApps = apps
        val p = l.prefs
        val land = l.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val cols = p.n(if (land) "d_cols_l" else "d_cols", if (land) 6 else 4)
        val rows = p.n(if (land) "d_rows_l" else "d_rows", if (land) 3 else 5)
        val mode = if (forceVertical) "vertical" else p.s("d_style", "horizontal")
        val st = style()
        val px = l.icons.basePx(st.scale)
        perPage = cols * rows
        var view: View
        when (mode) {
            "horizontal" -> {
                val pages = apps.chunked(perPage)
                val vp = ViewPager2(l)
                vp.adapter = object : RecyclerView.Adapter<CellVH>() {
                    override fun getItemCount() = maxOf(1, pages.size)
                    override fun onCreateViewHolder(pv: ViewGroup, t: Int): CellVH {
                        val c = CellLayout(l)
                        c.layoutParams = RecyclerView.LayoutParams(-1, -1)
                        return CellVH(c)
                    }
                    override fun onBindViewHolder(h: CellVH, pos: Int) {
                        val c = h.cell
                        c.removeAllViews()
                        c.cols = cols; c.rows = rows
                        c.setPadding(l.dp(6), l.dp(10), l.dp(6), l.dp(6))
                        (pages.getOrNull(pos) ?: emptyList()).forEachIndexed { i, a ->
                            c.addView(appIcon(a, st, px), CellLayout.LP(i % cols, i / cols))
                        }
                    }
                }
                vp.setPageTransformer(Transformers.get(p.s("d_effect", "simple")))
                vp.registerOnPageChangeCallback(pageCb())
                curPager = vp
                view = vp
            }
            else -> {
                val list = mode == "list"
                val rv = RecyclerView(l)
                rv.layoutManager = if (list) LinearLayoutManager(l) else GridLayoutManager(l, cols)
                rv.setHasFixedSize(true)
                rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = apps.size
                    override fun onCreateViewHolder(pv: ViewGroup, t: Int): RecyclerView.ViewHolder {
                        val v = IconView(l, if (list) st.copy(labels = true) else st, list, Col.alpha(accent, 30))
                        v.layoutParams = RecyclerView.LayoutParams(-1, if (list) l.dp(64) else l.dp(24) + px + l.dp(30))
                        return object : RecyclerView.ViewHolder(v) {}
                    }
                    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                        val v = h.itemView as IconView
                        val a = apps[pos]
                        v.bindApp(a, l.icons, px, null, darkIcons())
                        v.tag = a
                        v.setOnClickListener { close(); l.launch(a, it) }
                        v.setOnLongClickListener { l.onDrawerLong(it, a); true }
                        v.setOnTouchListener(l.touchL)
                    }
                }
                curRv = rv
                view = rv
            }
        }
        content.addView(view, LayoutParams(-1, -1))
        if (p.b("d_fast", false) && !forceVertical && apps.size > 12) {
            val fastPref = p.s("d_fast_color", "#33B5E5")
            val fc = if (fastPref.equals("#33B5E5", true)) accent else Col.parse(fastPref, accent)
            val bar = LetterBar(l, fc, fg)
            bar.onLetter = { ch -> jumpTo(ch) }
            content.addView(bar, LayoutParams(l.dp(22), -1, Gravity.END).apply { topMargin = l.dp(8); bottomMargin = l.dp(8) })
        }
    }

    private fun jumpTo(ch: Char) {
        var idx = 0
        if (ch != '#') {
            idx = curApps.indexOfFirst { (it.label.firstOrNull()?.uppercaseChar() ?: '#') >= ch }
            if (idx < 0) idx = curApps.size - 1
        }
        val vp = curPager
        if (vp != null) vp.setCurrentItem(idx / perPage.coerceAtLeast(1), false)
        else (curRv?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(idx, 0)
    }

    private fun buildWidgets() {
        val pm = l.packageManager
        val all = AppWidgetManager.getInstance(l).installedProviders.sortedBy { it.loadLabel(pm).lowercase() }
        val list = all.filter { it.provider.packageName == l.packageName } + all.filter { it.provider.packageName != l.packageName }
        val pages = list.chunked(6)
        val vp = ViewPager2(l)
        vp.adapter = object : RecyclerView.Adapter<CellVH>() {
            override fun getItemCount() = maxOf(1, pages.size)
            override fun onCreateViewHolder(p: ViewGroup, t: Int): CellVH {
                val c = CellLayout(l)
                c.layoutParams = RecyclerView.LayoutParams(-1, -1)
                return CellVH(c)
            }
            override fun onBindViewHolder(h: CellVH, pos: Int) {
                val c = h.cell
                c.removeAllViews()
                c.cols = 2; c.rows = 3
                c.setPadding(l.dp(10), l.dp(10), l.dp(10), l.dp(10))
                (pages.getOrNull(pos) ?: emptyList()).forEachIndexed { i, info -> c.addView(widgetCell(info), CellLayout.LP(i % 2, i / 2)) }
            }
        }
        vp.setPageTransformer(Transformers.get(l.prefs.s("d_effect", "simple")))
        vp.registerOnPageChangeCallback(pageCb())
        curPager = vp
        content.addView(vp, LayoutParams(-1, -1))
    }

    private fun widgetCell(info: AppWidgetProviderInfo): View {
        val box = LinearLayout(l).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(l.dp(6), l.dp(6), l.dp(6), l.dp(6)); isClickable = true
        }
        val prev = FrameLayout(l).apply {
            clipChildren = true
            background = GradientDrawable().apply { setColor(if (dark) 0xFF1A1A1A.toInt() else 0xFF444444.toInt()); cornerRadius = l.dp(3).toFloat() }
        }
        val res = ownWidgetLayout(info)
        if (res != 0) {
            val v = LayoutInflater.from(l).inflate(res, null)
            v.pivotX = 0f; v.pivotY = 0f; v.scaleX = 0.5f; v.scaleY = 0.5f
            prev.addView(v, FrameLayout.LayoutParams(l.dp(280), l.dp(170)))
        } else {
            val iv = ImageView(l)
            iv.setImageDrawable(info.loadPreviewImage(l, 0) ?: info.loadIcon(l, 0))
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            prev.addView(iv, FrameLayout.LayoutParams(-1, -1))
        }
        box.addView(prev, LinearLayout.LayoutParams(l.dp(140), l.dp(85)))
        val t = TextView(l).apply { text = info.loadLabel(l.packageManager); textSize = 12f; setTextColor(fg); gravity = Gravity.CENTER; maxLines = 1 }
        box.addView(t, LinearLayout.LayoutParams(-2, -2).apply { topMargin = l.dp(4) })
        box.setOnClickListener { close(); l.addWidget(info) }
        return box
    }
}
