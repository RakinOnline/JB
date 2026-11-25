package com.jb.launcher

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

class FolderPopup(private val l: Launcher) : FrameLayout(l) {
    var isOpen = false
    private var folderId = 0L

    init {
        visibility = View.GONE
        isClickable = true
        setOnClickListener { close() }
    }

    fun show(f: Item) {
        folderId = f.id
        build()
        visibility = View.VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(l.dur(150)).start()
        isOpen = true
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        animate().alpha(0f).setDuration(l.dur(120)).withEndAction { if (!isOpen) visibility = View.GONE }.start()
    }

    fun refresh() {
        if (!isOpen) return
        if (l.store.items.none { it.id == folderId }) { close(); return }
        build()
    }

    fun hideForDrag() { isOpen = false; animate().cancel(); alpha = 0f; visibility = View.INVISIBLE }
    fun endDrag() { if (!isOpen && visibility == View.INVISIBLE) { visibility = View.GONE; alpha = 1f } }

    private fun build() {
        removeAllViews()
        val p = l.prefs
        val f = l.store.items.firstOrNull { it.id == folderId } ?: run { close(); return }
        val kids = l.store.children(folderId).filter { l.repo.byKey.containsKey(it.pkg + "/" + it.cls) }
        val dark = Theme.surfaceDark(l, p, "night_folder")
        setBackgroundColor(Col.alpha(Color.BLACK, p.n("f_dim", 40)))
        val full = p.s("f_open", "popup") == "full"
        val card = LinearLayout(l)
        card.orientation = LinearLayout.VERTICAL
        card.isClickable = true
        val cc = if (dark) Col.parse(p.s("f_card", "#222222"), 0xFF222222.toInt()) else Color.WHITE
        card.background = GradientDrawable().apply {
            setColor(Col.alpha(cc, p.n("f_card_alpha", 100)))
            cornerRadius = if (full) 0f else l.dp(p.n("f_radius", 16)).toFloat()
        }
        card.setPadding(l.dp(12), l.dp(12), l.dp(12), l.dp(12))
        if (p.b("f_title", true)) {
            var tc = Theme.color(l, p, "f_title_color", "#FFFFFF")
            if (!dark && tc == Color.WHITE) tc = 0xFF222222.toInt()
            val t = TextView(l).apply {
                text = if (f.label.isEmpty()) "Folder" else f.label
                textSize = 18f; setTextColor(tc); typeface = fontFace(p.s("f_title_font", "regular"))
                gravity = Gravity.CENTER; setPadding(0, l.dp(4), 0, l.dp(10))
                setOnClickListener { l.renameFolder(f) }
            }
            card.addView(t, LinearLayout.LayoutParams(-1, -2))
        }
        var st = p.style("f")
        if (!dark && st.color == Color.WHITE) st = st.copy(color = 0xFF222222.toInt())
        val px = l.icons.basePx(st.scale)
        val cols = p.n("f_cols", 3); val rows = p.n("f_rows", 4)
        val rowH = px + l.dp(if (st.labels) 36 else 16)
        val horizontal = p.s("f_scroll", "horizontal") == "horizontal"
        val contentLp = if (full) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(-1, rows * rowH)

        if (horizontal) {
            val pages = kids.chunked(cols * rows)
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
                    c.removeAllViews(); c.cols = cols; c.rows = rows
                    (pages.getOrNull(pos) ?: emptyList()).forEachIndexed { i, it ->
                        c.addView(child(it, st, px), CellLayout.LP(i % cols, i / cols))
                    }
                }
            }
            card.addView(vp, contentLp)
        } else {
            val rv = RecyclerView(l)
            rv.layoutManager = GridLayoutManager(l, cols)
            rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = kids.size
                override fun onCreateViewHolder(pv: ViewGroup, t: Int): RecyclerView.ViewHolder {
                    val v = FrameLayout(l)
                    v.layoutParams = RecyclerView.LayoutParams(-1, rowH)
                    return object : RecyclerView.ViewHolder(v) {}
                }
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                    val v = h.itemView as FrameLayout
                    v.removeAllViews()
                    v.addView(child(kids[pos], st, px), FrameLayout.LayoutParams(-1, -1))
                }
            }
            card.addView(rv, contentLp)
        }

        if (full) {
            addView(card, LayoutParams(-1, -1))
        } else {
            val w = minOf(resources.displayMetrics.widthPixels - l.dp(24), cols * (px + l.dp(28)) + l.dp(24))
            addView(card, LayoutParams(w, -2, Gravity.CENTER))
        }
    }

    private fun child(item: Item, st: Style, px: Int): View {
        val a = l.repo.byKey[item.pkg + "/" + item.cls]!!
        val v = IconView(l, st)
        v.bindApp(a, l.icons, px, item.label, Theme.isNight(l, l.prefs) && l.prefs.b("darken_icons", false))
        v.tag = item
        v.setOnClickListener {
            if (l.prefs.b("f_autoclose", true)) close()
            l.launch(a, it)
        }
        v.setOnLongClickListener { l.onItemLong(it, item); true }
        v.setOnTouchListener(l.touchL)
        return v
    }
}
