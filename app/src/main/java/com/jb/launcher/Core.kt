package com.jb.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

object Col {
    val HOLO = 0xFF33B5E5.toInt()
    fun parse(s: String?, def: Int): Int = try { Color.parseColor(s!!.trim()) } catch (e: Exception) { def }
    fun alpha(c: Int, pct: Int): Int = Color.argb(pct.coerceIn(0, 100) * 255 / 100, Color.red(c), Color.green(c), Color.blue(c))
}

fun fontFace(f: String): Typeface = when (f) {
    "light" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
    "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    "medium" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
    "bold" -> Typeface.create("sans-serif", Typeface.BOLD)
    else -> Typeface.create("sans-serif", Typeface.NORMAL)
}

data class Style(
    val scale: Int, val labels: Boolean, val font: String, val color: Int,
    val shadow: Boolean, val single: Boolean, val lsize: Int
)

object Theme {
    fun accent(ctx: Context, p: Prefs): Int = when (p.s("accent_mode", "holo")) {
        "dynamic" -> if (Build.VERSION.SDK_INT >= 31) ctx.getColor(android.R.color.system_accent1_400) else Col.HOLO
        "custom" -> Col.parse(p.s("accent_hex", "#33B5E5"), Col.HOLO)
        else -> Col.HOLO
    }

    fun color(ctx: Context, p: Prefs, key: String, def: String): Int {
        val s = p.s(key, def)
        if (s.equals("dynamic", true)) return if (Build.VERSION.SDK_INT >= 31) ctx.getColor(android.R.color.system_accent1_100) else Color.WHITE
        return Col.parse(s, Col.parse(def, Color.WHITE))
    }

    fun isNight(ctx: Context, p: Prefs): Boolean = when (p.s("night_mode", "dark")) {
        "dark" -> true
        "light" -> false
        "auto" -> {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val s = p.n("night_start", 19); val e = p.n("night_end", 7)
            if (s > e) (h >= s || h < e) else (h >= s && h < e)
        }
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun surfaceDark(ctx: Context, p: Prefs, key: String): Boolean = isNight(ctx, p) && p.b(key, true)
}

class Prefs(private val ctx: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)
    fun n(k: String, d: Int): Int = try { sp.getString(k, null)?.toIntOrNull() ?: d } catch (e: Exception) { d }
    fun b(k: String, d: Boolean): Boolean = try { sp.getBoolean(k, d) } catch (e: Exception) { d }
    fun s(k: String, d: String): String = try { sp.getString(k, d) ?: d } catch (e: Exception) { d }
    val cols: Int get() = n("grid_cols", 4)
    val rows: Int get() = n("grid_rows", 4)
    var hidden: Set<String>
        get() = HashSet(sp.getStringSet("hidden", emptySet()) ?: emptySet())
        set(v) { sp.edit().putStringSet("hidden", HashSet(v)).apply() }

    fun style(p: String): Style = Style(
        n(p + "_scale", 100), b(p + "_labels", p != "k"), s(p + "_font", "regular"),
        Theme.color(ctx, this, p + "_color", "#FFFFFF"), b(p + "_shadow", true), b(p + "_single", true), n(p + "_lsize", 12)
    )

    fun padDp(key: String, width: Boolean): Int = when (s(key, "small")) {
        "none" -> 0
        "medium" -> if (width) 14 else 12
        "large" -> if (width) 24 else 22
        else -> if (width) 6 else 4
    }

    fun animFactor(): Float = when (s("anim_speed", "launcher")) {
        "relaxed" -> 1.6f
        "fast" -> 0.7f
        "ftl" -> 0.25f
        else -> 1f
    }
}

data class AppInfo(val pkg: String, val cls: String, val label: String) {
    val key: String get() = "$pkg/$cls"
}

class AppRepo(private val ctx: Context) {
    var apps: List<AppInfo> = emptyList(); private set
    var byKey: Map<String, AppInfo> = emptyMap(); private set
    fun reload() {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(i, 0).map {
            AppInfo(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(pm).toString())
        }.sortedBy { it.label.lowercase() }
        apps = list
        byKey = list.associateBy { it.key }
    }

    fun recent(n: Int): List<AppInfo> {
        val pm = ctx.packageManager
        return apps.map { a ->
            val t = try { pm.getPackageInfo(a.pkg, 0).firstInstallTime } catch (e: Exception) { 0L }
            a to t
        }.sortedByDescending { it.second }.take(n).map { it.first }
    }
}

// kind: 0 app, 1 widget, 2 folder.  page >= 0 home, -1 dock (x = slot, y = dock page), -3 inside a folder (parent = folder id)
data class Item(
    var id: Long, var kind: Int, var pkg: String, var cls: String,
    var page: Int, var x: Int, var y: Int, var sx: Int = 1, var sy: Int = 1, var widgetId: Int = -1,
    var parent: Long = 0L, var label: String = "", var swipe: String = ""
)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("layout", 0)
    val items = mutableListOf<Item>()
    val initialized: Boolean get() = sp.getBoolean("init", false)
    var pages: Int
        get() = sp.getInt("pages", 3)
        set(v) { sp.edit().putInt("pages", v.coerceIn(1, 8)).apply() }
    init { load() }

    fun load() {
        items.clear()
        val s = sp.getString("items", null) ?: return
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                items.add(Item(o.getLong("id"), o.getInt("k"), o.getString("p"), o.getString("c"),
                    o.getInt("pg"), o.getInt("x"), o.getInt("y"), o.optInt("sx", 1), o.optInt("sy", 1), o.optInt("w", -1),
                    o.optLong("par", 0L), o.optString("lb", ""), o.optString("sw", "")))
            }
        } catch (e: Exception) { }
    }

    fun save() {
        val a = JSONArray()
        for (i in items) a.put(JSONObject().put("id", i.id).put("k", i.kind).put("p", i.pkg).put("c", i.cls)
            .put("pg", i.page).put("x", i.x).put("y", i.y).put("sx", i.sx).put("sy", i.sy).put("w", i.widgetId)
            .put("par", i.parent).put("lb", i.label).put("sw", i.swipe))
        sp.edit().putString("items", a.toString()).putBoolean("init", true).apply()
    }

    fun rawItems(): String = sp.getString("items", "[]") ?: "[]"
    fun restoreRaw(items: String, pages: Int) { sp.edit().putString("items", items).putInt("pages", pages).putBoolean("init", true).apply() }
    fun reset() { sp.edit().clear().apply() }
    fun nextId(): Long = (items.maxOfOrNull { it.id } ?: 0L) + 1
    fun children(folderId: Long): List<Item> = items.filter { it.parent == folderId }
}

class Usage(ctx: Context) {
    private val sp = ctx.getSharedPreferences("usage", 0)
    fun hit(key: String) { sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply() }
    fun top(n: Int): List<String> = sp.all.entries.filter { (it.value as? Int ?: 0) > 0 }
        .sortedByDescending { it.value as Int }.take(n).map { it.key }
}

class Group(var name: String, val pkgs: MutableSet<String>)

class Groups(private val sp: SharedPreferences) {
    fun all(): MutableList<Group> {
        val out = mutableListOf<Group>()
        try {
            val a = JSONArray(sp.getString("groups", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val p = o.getJSONArray("p")
                val set = LinkedHashSet<String>()
                for (j in 0 until p.length()) set.add(p.getString(j))
                out.add(Group(o.getString("n"), set))
            }
        } catch (e: Exception) { }
        return out
    }

    fun save(list: List<Group>) {
        val a = JSONArray()
        for (g in list) a.put(JSONObject().put("n", g.name).put("p", JSONArray(g.pkgs.toList())))
        sp.edit().putString("groups", a.toString()).apply()
    }
}

object DefaultLayout {
    fun create(ctx: Context, store: Store, repo: AppRepo) {
        val pm = ctx.packageManager
        store.items.clear()
        store.pages = 3
        val used = HashSet<String>()
        fun find(i: Intent): AppInfo? {
            val ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
            val ai = ri.activityInfo ?: return null
            if (ai.packageName == "android") return null
            return repo.apps.firstOrNull { it.pkg == ai.packageName && it.cls == ai.name }
                ?: repo.apps.firstOrNull { it.pkg == ai.packageName }
        }
        fun cat(c: String) = Intent(Intent.ACTION_MAIN).addCategory(c)
        fun dock(slot: Int, a: AppInfo?) {
            if (a == null || !used.add(a.key)) return
            store.items.add(Item(store.nextId(), 0, a.pkg, a.cls, -1, slot, 0))
        }
        dock(0, find(Intent(Intent.ACTION_DIAL)))
        dock(1, find(cat(Intent.CATEGORY_APP_MESSAGING)))
        dock(3, find(cat(Intent.CATEGORY_APP_BROWSER)))
        dock(4, find(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)))
        val home = ArrayList<AppInfo?>()
        home.add(find(cat(Intent.CATEGORY_APP_GALLERY)))
        home.add(find(cat(Intent.CATEGORY_APP_CONTACTS)))
        home.add(find(Intent(Settings.ACTION_SETTINGS)))
        home.add(find(Intent(AlarmClock.ACTION_SHOW_ALARMS)))
        home.add(find(cat(Intent.CATEGORY_APP_CALENDAR)))
        home.add(find(cat(Intent.CATEGORY_APP_EMAIL)))
        home.add(find(cat(Intent.CATEGORY_APP_MUSIC)))
        home.add(find(cat(Intent.CATEGORY_APP_MAPS)))
        home.add(find(cat(Intent.CATEGORY_APP_CALCULATOR)))
        for (p in listOf("com.android.vending", "com.android.chrome", "com.google.android.youtube",
            "com.whatsapp", "org.mozilla.firefox", "com.instagram.android", "com.discord", "com.reddit.frontpage", ctx.packageName)) {
            home.add(repo.apps.firstOrNull { it.pkg == p })
        }
        var idx = 0
        for (a in home) {
            if (a == null || !used.add(a.key) || idx >= 16) continue
            store.items.add(Item(store.nextId(), 0, a.pkg, a.cls, 0, idx % 4, idx / 4))
            idx++
        }
        store.save()
    }
}
