package com.jb.launcher

import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CellVH(val cell: CellLayout) : RecyclerView.ViewHolder(cell)

/** Detects single/two-finger swipes, pinch, rotate, double tap and double-tap+swipe. */
class Gestures(private val ctx: Context, private val fire: (String) -> Unit) {
    private var x0 = 0f; private var y0 = 0f; private var t0 = 0L
    private var lastUp = 0L; private var lastTap = false; private var dtMode = false
    private var emptyDown = false; private var two = false
    private var d0 = 0f; private var a0 = 0f; private var cy0 = 0f
    private var d1 = 0f; private var a1 = 0f; private var cy1 = 0f
    private val slop = ctx.dp(12)
    private val swipeMin = ctx.dp(70)

    private fun measure(e: MotionEvent, first: Boolean) {
        val dx = e.getX(1) - e.getX(0); val dy = e.getY(1) - e.getY(0)
        val d = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val a = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val cy = (e.getY(0) + e.getY(1)) / 2f
        if (first) { d0 = d; a0 = a; cy0 = cy }
        d1 = d; a1 = a; cy1 = cy
    }

    fun feed(e: MotionEvent, empty: Boolean) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x; y0 = e.y; t0 = e.eventTime; two = false; emptyDown = empty
                dtMode = e.eventTime - lastUp < 300 && lastTap
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) { two = true; measure(e, true) }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2 && two) measure(e, false)
            MotionEvent.ACTION_UP -> finish(e)
            MotionEvent.ACTION_CANCEL -> { lastTap = false; two = false }
        }
    }

    private fun finish(e: MotionEvent) {
        if (two) {
            two = false; lastTap = false
            val ratio = if (d0 > 0) d1 / d0 else 1f
            var da = a1 - a0
            while (da > 180) da -= 360
            while (da < -180) da += 360
            val dcy = cy1 - cy0
            when {
                ratio < 0.7f -> fire("pinch_in")
                ratio > 1.4f -> fire("pinch_out")
                da > 30 -> fire("rot_cw")
                da < -30 -> fire("rot_ccw")
                dcy < -swipeMin -> fire("up2")
                dcy > swipeMin -> fire("down2")
            }
            return
        }
        val dx = e.x - x0; val dy = e.y - y0
        val moved = Math.hypot(dx.toDouble(), dy.toDouble()) > slop
        if (!moved && e.eventTime - t0 < 250) {
            if (emptyDown) {
                if (dtMode) { fire("double"); lastTap = false } else { lastTap = true; lastUp = e.eventTime }
            } else lastTap = false
        } else if (Math.abs(dy) > swipeMin && Math.abs(dy) > Math.abs(dx) * 1.5f) {
            val n = if (dtMode) (if (dy < 0) "dt_up" else "dt_down") else (if (dy < 0) "up" else "down")
            lastTap = false
            fire(n)
        } else lastTap = false
    }
}

class CellLayout(ctx: Context) : ViewGroup(ctx) {
    var cols = 4
    var rows = 4
    var cbLong: (() -> Unit)? = null
    var cbDown: (() -> Unit)? = null
    var gestures: Gestures? = null

    class LP(var cx: Int, var cy: Int, var sx: Int = 1, var sy: Int = 1) : ViewGroup.LayoutParams(-1, -1)

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onLongPress(e: MotionEvent) { cbLong?.invoke() }
    })

    private fun hitChild(x: Float, y: Float): Boolean {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x >= c.left && x <= c.right && y >= c.top && y <= c.bottom) return true
        }
        return false
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) cbDown?.invoke()
        gestures?.feed(e, if (e.actionMasked == MotionEvent.ACTION_DOWN) !hitChild(e.x, e.y) else false)
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) gestures?.feed(e, false)
        gd.onTouchEvent(e)
        return true
    }

    private fun cw(w: Int) = (w - paddingLeft - paddingRight) / cols
    private fun ch(h: Int) = (h - paddingTop - paddingBottom) / rows

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LP(0, 0)
    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LP

    override fun onMeasure(wms: Int, hms: Int) {
        val w = MeasureSpec.getSize(wms)
        val h = MeasureSpec.getSize(hms)
        setMeasuredDimension(w, h)
        val cw = cw(w); val ch = ch(h)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as LP
            c.measure(MeasureSpec.makeMeasureSpec(cw * lp.sx, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch * lp.sy, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cw(r - l); val ch = ch(b - t)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as LP
            val x = paddingLeft + lp.cx * cw
            val y = paddingTop + lp.cy * ch
            c.layout(x, y, x + cw * lp.sx, y + ch * lp.sy)
        }
    }

    fun cellAt(px: Float, py: Float): IntArray {
        val cw = cw(width).coerceAtLeast(1); val ch = ch(height).coerceAtLeast(1)
        val x = ((px - paddingLeft) / cw).toInt().coerceIn(0, cols - 1)
        val y = ((py - paddingTop) / ch).toInt().coerceIn(0, rows - 1)
        return intArrayOf(x, y)
    }
}

class IconView(ctx: Context, st: Style, private val horizontal: Boolean = false, hl: Int = 0x4D33B5E5) : LinearLayout(ctx) {
    val img = ImageView(ctx)
    val label = TextView(ctx)

    init {
        orientation = if (horizontal) HORIZONTAL else VERTICAL
        gravity = if (horizontal) (Gravity.CENTER_VERTICAL or Gravity.START) else Gravity.CENTER
        addView(img, LayoutParams(1, 1))
        label.setTextColor(st.color)
        label.typeface = fontFace(st.font)
        label.textSize = st.lsize.toFloat()
        label.maxLines = if (st.single) 1 else 2
        label.ellipsize = TextUtils.TruncateAt.END
        label.gravity = if (horizontal) Gravity.START else Gravity.CENTER
        if (st.shadow) label.setShadowLayer(3f, 0f, 1f, Color.BLACK)
        if (st.labels) {
            if (horizontal) addView(label, LayoutParams(0, -2, 1f).apply { leftMargin = ctx.dp(14) })
            else addView(label, LayoutParams(-2, -2).apply { topMargin = ctx.dp(4) })
        }
        if (horizontal) setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(hl))
        background = sl
    }

    private fun sizeImg(px: Int) {
        val lp = img.layoutParams
        lp.width = px; lp.height = px
        img.layoutParams = lp
    }

    private fun darken(on: Boolean) {
        img.colorFilter = if (on) ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.7f, 0.7f, 0.7f, 1f) }) else null
    }

    fun bindApp(a: AppInfo, icons: Icons, px: Int, text: String? = null, dark: Boolean = false) {
        sizeImg(px)
        label.text = if (text.isNullOrEmpty()) a.label else text
        darken(dark)
        val tag = a.key + "@" + px
        img.tag = tag
        val c = icons.cached(a.pkg, a.cls, px)
        if (c != null) img.setImageBitmap(c) else {
            img.setImageDrawable(null)
            icons.load(a.pkg, a.cls, px) { b -> if (img.tag == tag) img.setImageBitmap(b) }
        }
    }

    fun bindBitmap(b: Bitmap, text: String, px: Int, dark: Boolean = false) {
        sizeImg(px)
        label.text = text
        darken(dark)
        img.tag = null
        img.setImageBitmap(b)
    }
}

class Dots(ctx: Context) : View(ctx) {
    private var count = 1
    private var idx = 0
    var style = "line"
    var active = Color.WHITE
    var inactive = 0x66FFFFFF
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    fun set(c: Int, i: Int) { count = c; idx = i; invalidate() }
    override fun onDraw(cv: Canvas) {
        if (count <= 1 || style == "none") return
        val d = context.dp(1).toFloat()
        if (style == "line") {
            val w = d * 14; val h = d * 3; val gap = d * 5
            var x = (width - (count * w + (count - 1) * gap)) / 2f
            val y = (height - h) / 2f
            for (i in 0 until count) {
                p.color = if (i == idx) active else inactive
                cv.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, p)
                x += w + gap
            }
        } else {
            val r = d * 3.5f; val gap = d * 8
            var x = (width - ((count - 1) * (2 * r + gap) + 2 * r)) / 2f + r
            for (i in 0 until count) {
                p.color = if (i == idx) active else inactive
                cv.drawCircle(x, height / 2f, if (i == idx) r else r * 0.8f, p)
                x += 2 * r + gap
            }
        }
    }
}

class MicView(ctx: Context) : View(ctx) {
    var tint = Color.WHITE
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        p.color = tint
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val s = Math.min(w, h)
        p.style = Paint.Style.FILL
        c.drawRoundRect(cx - s * 0.13f, h / 2 - s * 0.32f, cx + s * 0.13f, h / 2 + s * 0.08f, s * 0.13f, s * 0.13f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.06f; p.strokeCap = Paint.Cap.ROUND
        c.drawArc(RectF(cx - s * 0.22f, h / 2 - s * 0.2f, cx + s * 0.22f, h / 2 + s * 0.2f), 20f, 140f, false, p)
        c.drawLine(cx, h / 2 + s * 0.2f, cx, h / 2 + s * 0.32f, p)
    }
}

/** kind: "search", "lens", "assist" */
class SearchGlyph(ctx: Context, private val kind: String) : View(ctx) {
    var tint = Color.WHITE
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        p.color = tint
        val w = width.toFloat(); val h = height.toFloat(); val s = Math.min(w, h)
        val cx = w / 2; val cy = h / 2
        p.strokeWidth = s * 0.07f
        when (kind) {
            "search" -> {
                p.style = Paint.Style.STROKE
                c.drawCircle(cx - s * 0.05f, cy - s * 0.05f, s * 0.2f, p)
                c.drawLine(cx + s * 0.1f, cy + s * 0.1f, cx + s * 0.25f, cy + s * 0.25f, p)
            }
            "lens" -> {
                p.style = Paint.Style.STROKE
                c.drawRoundRect(RectF(cx - s * 0.24f, cy - s * 0.24f, cx + s * 0.24f, cy + s * 0.24f), s * 0.08f, s * 0.08f, p)
                c.drawCircle(cx, cy, s * 0.1f, p)
            }
            else -> {
                p.style = Paint.Style.FILL
                val cols = intArrayOf(0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC05.toInt(), 0xFF34A853.toInt())
                for (i in 0 until 4) { p.color = cols[i]; c.drawCircle(cx - s * 0.2f + i * s * 0.133f, cy, s * 0.06f, p) }
            }
        }
    }
}

class OverflowIcon(ctx: Context, private val col: Int = Color.WHITE) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
    init {
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0x4D33B5E5))
        background = sl
        isClickable = true
    }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = context.dp(2).toFloat()
        val g = context.dp(7).toFloat()
        c.drawCircle(cx, cy - g, r, p); c.drawCircle(cx, cy, r, p); c.drawCircle(cx, cy + g, r, p)
    }
}

class AppsButton(ctx: Context) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat() / 1.2f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    init {
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0x4D33B5E5))
        background = sl
        isClickable = true
    }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = Math.min(width, height) * 0.36f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; style = Paint.Style.STROKE; strokeWidth = ring.strokeWidth }
        c.drawCircle(cx, cy + 1, r, shadow)
        c.drawCircle(cx, cy, r, ring)
        val g = r * 0.38f; val dr = r * 0.12f
        for (i in -1..1) for (j in -1..1) c.drawCircle(cx + i * g, cy + j * g, dr, dot)
    }
}

class ChevronView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val s = context.dp(6).toFloat()
        c.drawLine(cx - s, cy + s / 2, cx, cy - s / 2, p)
        c.drawLine(cx, cy - s / 2, cx + s, cy + s / 2, p)
    }
}

class LetterBar(ctx: Context, private val accent: Int, private val fg: Int) : View(ctx) {
    private val letters = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    var onLetter: ((Char) -> Unit)? = null
    private var cur = -1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = ctx.dp(11).toFloat() }
    override fun onDraw(c: Canvas) {
        val step = height / letters.length.toFloat()
        for (i in letters.indices) {
            p.color = if (i == cur) accent else fg
            c.drawText(letters[i].toString(), width / 2f, step * i + step / 2 + p.textSize / 3, p)
        }
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = (e.y / height * letters.length).toInt().coerceIn(0, letters.length - 1)
                if (i != cur) { cur = i; invalidate(); onLetter?.invoke(letters[i]) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { cur = -1; invalidate() }
        }
        return true
    }
}

object FolderRenderer {
    fun render(ctx: Context, p: Prefs, icons: Icons, kids: List<AppInfo>, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val bgc = Col.alpha(Col.parse(p.s("f_bg", "#FFFFFF"), Color.WHITE), p.n("f_alpha", 30))
        val shape = p.s("f_shape", "circle")
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgc }
        val f = px.toFloat()
        when (shape) {
            "circle" -> c.drawCircle(f / 2, f / 2, f * 0.48f, bg)
            "squircle" -> c.drawPath(Shapes.path("squircle", px), bg)
            "rounded" -> c.drawPath(Shapes.path("rounded", px), bg)
            "disc" -> {
                c.drawCircle(f / 2, f / 2, f * 0.48f, bg)
                c.drawCircle(f / 2, f / 2, f * 0.48f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x88FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = f * 0.03f })
            }
        }
        fun draw(a: AppInfo, size: Int, cx: Float, cy: Float, rot: Float = 0f) {
            val b = icons.bitmap(a.pkg, a.cls, size)
            c.save(); c.rotate(rot, cx, cy)
            c.drawBitmap(b, cx - size / 2f, cy - size / 2f, paint)
            c.restore()
        }
        val show = kids.take(4)
        when (p.s("f_preview", "grid")) {
            "stack" -> for (i in show.indices.reversed().filter { it < 3 }) {
                val s = (f * (0.6f - i * 0.06f)).toInt()
                draw(show[i], s, f / 2, f * 0.55f - i * f * 0.07f)
            }
            "fan" -> {
                val angles = floatArrayOf(-22f, 0f, 22f)
                for (i in show.take(3).indices.reversed()) draw(show[i], (f * 0.52f).toInt(), f / 2 + angles[i] * f * 0.006f, f * 0.5f, angles[i])
            }
            "line" -> {
                val s = (f * 0.3f).toInt()
                val n = Math.min(show.size, 3)
                for (i in 0 until n) draw(show[i], s, f / 2 + (i - (n - 1) / 2f) * s * 1.05f, f / 2)
            }
            "first" -> if (show.isNotEmpty()) draw(show[0], (f * 0.66f).toInt(), f / 2, f / 2)
            else -> {
                val s = (f * 0.34f).toInt()
                for (i in show.indices) draw(show[i], s, f * (0.31f + (i % 2) * 0.38f), f * (0.31f + (i / 2) * 0.38f))
            }
        }
        return bmp
    }
}
