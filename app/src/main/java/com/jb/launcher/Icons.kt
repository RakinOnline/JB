package com.jb.launcher

import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.core.content.ContextCompat
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.concurrent.Executors

object Shapes {
    fun path(shape: String, s: Int): Path {
        val f = s.toFloat()
        val p = Path()
        when (shape) {
            "circle" -> p.addCircle(f / 2, f / 2, f / 2, Path.Direction.CW)
            "squircle" -> {
                val n = 64
                for (i in 0..n) {
                    val a = 2 * Math.PI * i / n
                    val cs = Math.cos(a); val sn = Math.sin(a)
                    val x = f / 2 + f / 2 * Math.signum(cs) * Math.pow(Math.abs(cs), 0.5)
                    val y = f / 2 + f / 2 * Math.signum(sn) * Math.pow(Math.abs(sn), 0.5)
                    if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
                }
                p.close()
            }
            "teardrop" -> p.addRoundRect(RectF(0f, 0f, f, f), floatArrayOf(f / 2, f / 2, 0f, 0f, f / 2, f / 2, f / 2, f / 2), Path.Direction.CW)
            "flower" -> {
                p.addCircle(f / 2, f / 2, f * 0.3f, Path.Direction.CW)
                for (i in 0 until 8) {
                    val a = 2 * Math.PI * i / 8
                    p.addCircle(f / 2 + (f * 0.28f * Math.cos(a)).toFloat(), f / 2 + (f * 0.28f * Math.sin(a)).toFloat(), f * 0.21f, Path.Direction.CW)
                }
            }
            "pebble" -> p.addRoundRect(RectF(f * 0.02f, f * 0.02f, f * 0.98f, f * 0.98f),
                floatArrayOf(f * .5f, f * .42f, f * .42f, f * .5f, f * .5f, f * .44f, f * .44f, f * .5f), Path.Direction.CW)
            "hexagon" -> {
                for (i in 0 until 6) {
                    val a = Math.toRadians(60.0 * i - 90)
                    val x = f / 2 + (f / 2 * Math.cos(a)).toFloat(); val y = f / 2 + (f / 2 * Math.sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            "rounded" -> p.addRoundRect(RectF(0f, 0f, f, f), f * 0.18f, f * 0.18f, Path.Direction.CW)
            "default" -> p.addRoundRect(RectF(0f, 0f, f, f), f * 0.14f, f * 0.14f, Path.Direction.CW)
            else -> p.addRect(0f, 0f, f, f, Path.Direction.CW)
        }
        return p
    }
}

class Icons(private val ctx: Context) {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(ctx)
    private var packMap: Map<String, String> = emptyMap()
    private var packRes: Resources? = null
    private var packPkg = ""

    fun basePx(scale: Int): Int = (ctx.dp(56) * scale / 100f).toInt()

    fun configure() {
        val p = prefs.s("icon_pack", "builtin")
        packPkg = p
        packMap = emptyMap(); packRes = null
        if (p != "builtin" && p != "system") loadPack(p)
        cache.evictAll()
    }

    fun clear() = cache.evictAll()

    fun customFile(key: String): File {
        val d = File(ctx.filesDir, "icons")
        d.mkdirs()
        return File(d, key.hashCode().toString() + ".png")
    }

    fun setCustom(key: String, bmp: Bitmap?) {
        val f = customFile(key)
        if (bmp == null) f.delete() else f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cache.evictAll()
    }

    private fun loadPack(p: String) {
        try {
            val r = ctx.packageManager.getResourcesForApplication(p)
            val map = HashMap<String, String>()
            var parser: XmlPullParser? = null
            val id = r.getIdentifier("appfilter", "xml", p)
            if (id != 0) parser = r.getXml(id)
            else try {
                parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(r.assets.open("appfilter.xml"), null)
            } catch (e: Exception) { parser = null }
            if (parser != null) {
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && parser.name == "item") {
                        val comp = parser.getAttributeValue(null, "component")
                        val dr = parser.getAttributeValue(null, "drawable")
                        if (comp != null && dr != null && comp.startsWith("ComponentInfo{"))
                            map[comp.removePrefix("ComponentInfo{").removeSuffix("}")] = dr
                    }
                    ev = parser.next()
                }
            }
            packMap = map; packRes = r
        } catch (e: Exception) { }
    }

    fun cached(pkg: String, cls: String, px: Int): Bitmap? = cache.get("$pkg/$cls@$px")

    fun load(pkg: String, cls: String, px: Int, cb: (Bitmap) -> Unit) {
        val c = cached(pkg, cls, px)
        if (c != null) { cb(c); return }
        pool.execute {
            val b = bitmap(pkg, cls, px)
            main.post { cb(b) }
        }
    }

    fun bitmap(pkg: String, cls: String, px: Int): Bitmap {
        val key = "$pkg/$cls@$px"
        cache.get(key)?.let { return it }
        val b = build(pkg, cls, px)
        cache.put(key, b)
        return b
    }

    private fun build(pkg: String, cls: String, px: Int): Bitmap {
        val key = "$pkg/$cls"
        val cf = customFile(key)
        if (cf.exists()) {
            val src = BitmapFactory.decodeFile(cf.path)
            if (src != null) return Bitmap.createScaledBitmap(src, px, px, true)
        }
        val r = packRes
        if (r != null) {
            val name = packMap[key]
            if (name != null) {
                val id = r.getIdentifier(name, "drawable", packPkg)
                if (id != 0) try { return fromDrawable(r.getDrawable(id, null), px) } catch (e: Exception) { }
            }
        }
        if (packPkg == "builtin") {
            val b = BuiltinIcons.get(ctx, pkg, cls, px)
            if (b != null) return b
        }
        return try {
            fromDrawable(ctx.packageManager.getActivityIcon(ComponentName(pkg, cls)), px)
        } catch (e: Exception) {
            Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        }
    }

    private fun toBitmap(d: Drawable, px: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px); d.draw(Canvas(b))
        return b
    }

    private fun bbox(b: Bitmap): Rect {
        var l = b.width; var t = b.height; var r = 0; var bt = 0
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        for (y in 0 until b.height) for (x in 0 until b.width) {
            if ((px[y * b.width + x] ushr 24) > 16) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y
            }
        }
        return if (r <= l || bt <= t) Rect(0, 0, b.width, b.height) else Rect(l, t, r + 1, bt + 1)
    }

    private fun avgColor(b: Bitmap): Int {
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0L
        val step = Math.max(1, b.width / 24)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                if ((c ushr 24) > 128) { rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); n++ }
                x += step
            }
            y += step
        }
        if (n == 0L) return 0xFF607D8B.toInt()
        return Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
    }

    private fun fromDrawable(d: Drawable, px: Int): Bitmap {
        val shape = prefs.s("icon_shape", "default")
        val reshape = prefs.b("reshape_legacy", false)
        val norm = prefs.b("icon_norm", false)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (d is AdaptiveIconDrawable) {
            val full = (px * 108f / 72f).toInt()
            val off = (full - px) / 2f
            c.save()
            c.clipPath(Shapes.path(shape, px))
            c.translate(-off, -off)
            d.background?.let { it.setBounds(0, 0, full, full); it.draw(c) }
            d.foreground?.let { it.setBounds(0, 0, full, full); it.draw(c) }
            c.restore()
            return out
        }
        val src = toBitmap(d, px)
        if (reshape && shape != "default") {
            c.save(); c.clipPath(Shapes.path(shape, px)); c.drawColor(avgColor(src)); c.restore()
            val bb = bbox(src)
            val scale = px * 0.62f / Math.max(bb.width(), bb.height())
            val w = bb.width() * scale; val h = bb.height() * scale
            c.drawBitmap(src, bb, RectF((px - w) / 2, (px - h) / 2, (px + w) / 2, (px + h) / 2), paint)
        } else if (norm) {
            val bb = bbox(src)
            val scale = px * 0.82f / Math.max(bb.width(), bb.height())
            val w = bb.width() * scale; val h = bb.height() * scale
            c.drawBitmap(src, bb, RectF((px - w) / 2, (px - h) / 2, (px + w) / 2, (px + h) / 2), paint)
        } else {
            c.drawBitmap(src, 0f, 0f, paint)
        }
        return out
    }
}

object BuiltinIcons {
    fun get(ctx: Context, pkg: String, cls: String, px: Int): Bitmap? {
        val id = ctx.resources.getIdentifier("ic_" + pkg.replace('.', '_').lowercase(), "drawable", ctx.packageName)
        if (id != 0) {
            val d = ContextCompat.getDrawable(ctx, id)
            if (d != null) {
                val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, px, px); d.draw(Canvas(b)); return b
            }
        }
        val p = pkg.lowercase()
        val c = cls.lowercase()
        val kind = when {
            "dialtacts" in c || "dialer" in p || "dialer" in c -> 0
            "messag" in p || "mms" in p -> 1
            "camera" in p -> 2
            "gallery" in p -> 3
            p == "com.android.settings" -> 4
            "deskclock" in p || p.endsWith(".clock") -> 5
            "calculator" in p -> 6
            "calendar" in p -> 7
            "contacts" in p -> 8
            p == "com.android.email" || p.endsWith(".email") -> 9
            "music" in p -> 10
            "documentsui" in p || "filemanager" in p || "myfiles" in p -> 11
            "providers.downloads" in p || p.endsWith(".downloads") -> 12
            p == "com.android.browser" -> 13
            else -> return null
        }
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        draw(kind, Canvas(b), px.toFloat())
        return b
    }

    private fun fill(col: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
    private fun stroke(col: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col; style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND
    }
    private fun grad(top: Int, bot: Int, s: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, s, top, bot, Shader.TileMode.CLAMP)
    }

    private fun draw(k: Int, c: Canvas, s: Float) {
        fun R(l: Float, t: Float, r: Float, b: Float) = RectF(l * s, t * s, r * s, b * s)
        when (k) {
            0 -> { // phone
                c.save(); c.scale(1.3f, 1.3f, s / 2, s / 2)
                val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, s, s, 0xFF4FC3F7.toInt(), 0xFF0277BD.toInt(), Shader.TileMode.CLAMP)
                    style = Paint.Style.STROKE; strokeWidth = s * 0.17f; strokeCap = Paint.Cap.ROUND
                }
                c.drawArc(R(0.36f, 0.02f, 1.04f, 0.70f), 100f, 90f, false, sp)
                val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = sp.shader }
                c.drawCircle(0.641f * s, 0.695f * s, 0.1f * s, fp)
                c.drawCircle(0.365f * s, 0.301f * s, 0.1f * s, fp)
                c.restore()
            }
            1 -> { // messages
                c.drawRoundRect(R(0.1f, 0.14f, 0.9f, 0.72f), s * 0.1f, s * 0.1f, grad(0xFFB5E32B.toInt(), 0xFF6A9A00.toInt(), s))
                val t = Path().apply { moveTo(s * 0.2f, s * 0.68f); lineTo(s * 0.2f, s * 0.9f); lineTo(s * 0.42f, s * 0.68f); close() }
                c.drawPath(t, fill(0xFF7AA800.toInt()))
                c.drawCircle(s * 0.36f, s * 0.4f, s * 0.045f, fill(0xFF2B4A00.toInt()))
                c.drawCircle(s * 0.64f, s * 0.4f, s * 0.045f, fill(0xFF2B4A00.toInt()))
                c.drawArc(R(0.3f, 0.34f, 0.7f, 0.62f), 20f, 140f, false, stroke(0xFF2B4A00.toInt(), s * 0.045f))
            }
            2 -> { // camera
                c.drawRoundRect(R(0.32f, 0.16f, 0.68f, 0.32f), s * 0.04f, s * 0.04f, fill(0xFF555555.toInt()))
                c.drawRoundRect(R(0.08f, 0.26f, 0.92f, 0.84f), s * 0.08f, s * 0.08f, grad(0xFF666666.toInt(), 0xFF2B2B2B.toInt(), s))
                c.drawCircle(s * 0.5f, s * 0.55f, s * 0.24f, fill(0xFF111111.toInt()))
                c.drawCircle(s * 0.5f, s * 0.55f, s * 0.18f, fill(0xFF1E88E5.toInt()))
                c.drawCircle(s * 0.44f, s * 0.49f, s * 0.05f, fill(0x99FFFFFF.toInt()))
                c.drawRect(R(0.76f, 0.32f, 0.86f, 0.38f), fill(0xFFFFC107.toInt()))
            }
            3 -> { // gallery
                c.drawRoundRect(R(0.08f, 0.1f, 0.92f, 0.9f), s * 0.06f, s * 0.06f, fill(0xFF333333.toInt()))
                val cols = intArrayOf(0xFF33B5E5.toInt(), 0xFFAA66CC.toInt(), 0xFF99CC00.toInt(), 0xFFFFBB33.toInt())
                for (i in 0 until 4) {
                    val x = 0.15f + (i % 2) * 0.36f; val y = 0.17f + (i / 2) * 0.36f
                    c.drawRect(R(x, y, x + 0.33f, y + 0.33f), fill(cols[i]))
                }
            }
            4 -> { // settings
                c.drawRoundRect(R(0.1f, 0.1f, 0.9f, 0.9f), s * 0.06f, s * 0.06f, fill(0xFF2A3A44.toInt()))
                c.drawRoundRect(R(0.1f, 0.1f, 0.9f, 0.9f), s * 0.06f, s * 0.06f, stroke(0xFF33B5E5.toInt(), s * 0.04f))
                val ys = floatArrayOf(0.3f, 0.5f, 0.7f); val xs = floatArrayOf(0.35f, 0.62f, 0.45f)
                for (i in 0 until 3) {
                    c.drawLine(s * 0.2f, s * ys[i], s * 0.8f, s * ys[i], stroke(0xFF88CCEE.toInt(), s * 0.03f))
                    c.drawCircle(s * xs[i], s * ys[i], s * 0.06f, fill(0xFF33B5E5.toInt()))
                }
            }
            5 -> { // clock
                c.drawCircle(s * 0.5f, s * 0.5f, s * 0.42f, fill(0xFF222222.toInt()))
                c.drawCircle(s * 0.5f, s * 0.5f, s * 0.42f, stroke(0xFFCCCCCC.toInt(), s * 0.04f))
                val h = stroke(Color.WHITE, s * 0.045f)
                c.drawLine(s * 0.5f, s * 0.5f, s * 0.5f, s * 0.24f, h)
                c.drawLine(s * 0.5f, s * 0.5f, s * 0.68f, s * 0.58f, h)
                c.drawCircle(s * 0.5f, s * 0.5f, s * 0.03f, fill(Color.WHITE))
            }
            6 -> { // calculator
                c.drawRoundRect(R(0.12f, 0.1f, 0.88f, 0.9f), s * 0.06f, s * 0.06f, fill(0xFF3A3A3A.toInt()))
                c.drawRect(R(0.2f, 0.17f, 0.8f, 0.34f), fill(0xFFB8D8A0.toInt()))
                for (i in 0 until 9) {
                    val x = 0.2f + (i % 3) * 0.215f; val y = 0.42f + (i / 3) * 0.17f
                    c.drawRoundRect(R(x, y, x + 0.17f, y + 0.13f), s * 0.02f, s * 0.02f, fill(if (i % 3 == 2) 0xFFFF8800.toInt() else 0xFFE0E0E0.toInt()))
                }
            }
            7 -> { // calendar
                c.drawRoundRect(R(0.14f, 0.12f, 0.86f, 0.88f), s * 0.05f, s * 0.05f, fill(Color.WHITE))
                c.drawRoundRect(R(0.14f, 0.12f, 0.86f, 0.3f), s * 0.05f, s * 0.05f, fill(0xFF33B5E5.toInt()))
                val tp = fill(0xFF444444.toInt()).apply { textAlign = Paint.Align.CENTER; textSize = s * 0.36f; typeface = Typeface.DEFAULT_BOLD }
                c.drawText("31", s * 0.5f, s * 0.75f, tp)
            }
            8 -> { // contacts
                c.drawRoundRect(R(0.1f, 0.1f, 0.9f, 0.9f), s * 0.06f, s * 0.06f, grad(0xFF4FB5E8.toInt(), 0xFF1E7FB5.toInt(), s))
                c.drawCircle(s * 0.5f, s * 0.38f, s * 0.13f, fill(Color.WHITE))
                c.drawArc(R(0.25f, 0.52f, 0.75f, 1.02f), 180f, 180f, true, fill(Color.WHITE))
            }
            9 -> { // email
                c.drawRoundRect(R(0.1f, 0.22f, 0.9f, 0.78f), s * 0.04f, s * 0.04f, fill(0xFFF5F5F5.toInt()))
                val p = Path().apply { moveTo(s * 0.1f, s * 0.25f); lineTo(s * 0.5f, s * 0.55f); lineTo(s * 0.9f, s * 0.25f) }
                c.drawPath(p, stroke(0xFFBBBBBB.toInt(), s * 0.02f))
                val tp = fill(0xFFFF8800.toInt()).apply { textAlign = Paint.Align.CENTER; textSize = s * 0.28f; typeface = Typeface.DEFAULT_BOLD }
                c.drawText("@", s * 0.5f, s * 0.7f, tp)
            }
            10 -> { // music
                c.drawArc(R(0.18f, 0.18f, 0.82f, 0.82f), 180f, 180f, false, stroke(0xFFFF8800.toInt(), s * 0.08f))
                c.drawRoundRect(R(0.13f, 0.5f, 0.3f, 0.78f), s * 0.04f, s * 0.04f, fill(0xFFE65100.toInt()))
                c.drawRoundRect(R(0.7f, 0.5f, 0.87f, 0.78f), s * 0.04f, s * 0.04f, fill(0xFFE65100.toInt()))
            }
            11 -> { // files
                c.drawRoundRect(R(0.1f, 0.2f, 0.5f, 0.42f), s * 0.03f, s * 0.03f, fill(0xFFD7A86E.toInt()))
                c.drawRoundRect(R(0.1f, 0.3f, 0.9f, 0.8f), s * 0.04f, s * 0.04f, grad(0xFFF3CF9B.toInt(), 0xFFE0B070.toInt(), s))
            }
            12 -> { // downloads
                c.drawRoundRect(R(0.12f, 0.12f, 0.88f, 0.88f), s * 0.06f, s * 0.06f, fill(0xFF333333.toInt()))
                val g = fill(0xFF99CC00.toInt())
                c.drawRect(R(0.44f, 0.22f, 0.56f, 0.5f), g)
                val t = Path().apply { moveTo(s * 0.3f, s * 0.48f); lineTo(s * 0.7f, s * 0.48f); lineTo(s * 0.5f, s * 0.68f); close() }
                c.drawPath(t, g)
                c.drawRect(R(0.28f, 0.74f, 0.72f, 0.79f), g)
            }
            13 -> { // globe
                c.drawCircle(s * 0.5f, s * 0.5f, s * 0.4f, grad(0xFF4FC3F7.toInt(), 0xFF01579B.toInt(), s))
                val w = stroke(0x88FFFFFF.toInt(), s * 0.02f)
                c.drawOval(R(0.3f, 0.1f, 0.7f, 0.9f), w)
                c.drawLine(s * 0.12f, s * 0.5f, s * 0.88f, s * 0.5f, w)
                c.drawLine(s * 0.18f, s * 0.3f, s * 0.82f, s * 0.3f, w)
                c.drawLine(s * 0.18f, s * 0.7f, s * 0.82f, s * 0.7f, w)
            }
        }
    }
}
