package com.jb.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Backup {
    fun export(ctx: Context): String {
        val o = JSONObject()
        val arr = JSONArray()
        for ((k, v) in PreferenceManager.getDefaultSharedPreferences(ctx).all) {
            if (k.startsWith("_")) continue
            val e = JSONObject().put("k", k)
            when (v) {
                is String -> e.put("t", "s").put("v", v)
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            arr.put(e)
        }
        o.put("prefs", arr)
        val st = Store(ctx)
        o.put("items", st.rawItems()).put("pages", st.pages)
        return o.toString()
    }

    fun import(ctx: Context, json: String): Boolean = try {
        val o = JSONObject(json)
        val ed = PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear()
        val arr = o.getJSONArray("prefs")
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val k = e.getString("k")
            when (e.getString("t")) {
                "s" -> ed.putString(k, e.getString("v"))
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "set" -> {
                    val a = e.getJSONArray("v"); val s = HashSet<String>()
                    for (j in 0 until a.length()) s.add(a.getString(j))
                    ed.putStringSet(k, s)
                }
            }
        }
        ed.apply()
        Store(ctx).restoreRaw(o.getString("items"), o.optInt("pages", 3))
        true
    } catch (e: Exception) { false }
}

class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    private var volDownAt = 0L

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = Prefs(this)
        requestedOrientation = when (p.s("orientation", "portrait")) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "auto" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        title = "Launcher settings"
        if (b == null) supportFragmentManager.beginTransaction().replace(android.R.id.content, Frag()).commit()
    }

    override fun onResume() { super.onResume(); LockService.sync(this) }

    override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean {
        val f = Frag()
        f.arguments = Bundle().apply { putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key) }
        supportFragmentManager.beginTransaction().replace(android.R.id.content, f).addToBackStack(pref.key).commit()
        title = pref.title
        return true
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN && e.repeatCount == 0) volDownAt = SystemClock.uptimeMillis()
        return super.onKeyDown(code, e)
    }

    override fun onKeyUp(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN && volDownAt > 0 && SystemClock.uptimeMillis() - volDownAt >= 2000) {
            val p = Prefs(this)
            val on = !p.b("labs", false)
            p.sp.edit().putBoolean("labs", on).apply()
            Toast.makeText(this, if (on) "Labs unlocked" else "Labs hidden", Toast.LENGTH_SHORT).show()
            supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            supportFragmentManager.beginTransaction().replace(android.R.id.content, Frag()).commit()
        }
        volDownAt = 0
        return super.onKeyUp(code, e)
    }

    class Frag : PreferenceFragmentCompat() {
        private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) try {
                requireContext().contentResolver.openOutputStream(uri)?.use { it.write(Backup.export(requireContext()).toByteArray()) }
                toast("Backup saved")
            } catch (e: Exception) { toast("Couldn't save backup") }
        }
        private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) try {
                val txt = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (Backup.import(requireContext(), txt)) { toast("Backup restored - go back to the home screen"); requireActivity().recreate() }
                else toast("That isn't a valid backup")
            } catch (e: Exception) { toast("Couldn't read backup") }
        }

        private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

        private fun actionLabel(ctx: Context, v: String): String {
            if (v.startsWith("app:")) {
                val r = AppRepo(ctx); r.reload()
                return "App: " + (r.byKey[v.removePrefix("app:")]?.label ?: v.removePrefix("app:"))
            }
            if (v.startsWith("act:")) return "Activity: " + v.removePrefix("act:")
            return when (v) {
                "drawer" -> "App drawer"; "search" -> "Search"; "notif" -> "Expand notifications"
                "qs" -> "Expand quick settings"; "recents" -> "Recent apps (accessibility)"
                "lock" -> "Screen lock (accessibility)"; "voice" -> "Voice search / Assistant"
                "settings" -> "Launcher settings"; else -> "None"
            }
        }

        private fun gesturePref(key: String, def: String) {
            val pref = findPreference<Preference>(key) ?: return
            val ctx = requireContext()
            val sp = Prefs(ctx).sp
            pref.summary = actionLabel(ctx, sp.getString(key, def) ?: def)
            pref.setOnPreferenceClickListener {
                val vals = arrayOf("none", "drawer", "search", "notif", "qs", "recents", "lock", "voice", "settings", "app", "act")
                val names = arrayOf("None", "App drawer", "Search", "Expand notifications", "Expand quick settings",
                    "Recent apps (needs accessibility)", "Screen lock (needs accessibility)", "Voice search / Assistant",
                    "Launcher settings", "Launch app...", "Launch hidden activity...")
                fun set(v: String) { sp.edit().putString(key, v).apply(); pref.summary = actionLabel(ctx, v) }
                AlertDialog.Builder(requireActivity()).setTitle(pref.title).setItems(names) { _, w ->
                    when (vals[w]) {
                        "app" -> {
                            val repo = AppRepo(ctx); repo.reload()
                            AlertDialog.Builder(requireActivity()).setTitle("Launch app")
                                .setItems(repo.apps.map { it.label }.toTypedArray()) { _, i -> set("app:" + repo.apps[i].key) }.show()
                        }
                        "act" -> {
                            val et = EditText(ctx); et.hint = "com.package/com.package.Activity"
                            AlertDialog.Builder(requireActivity()).setTitle("Hidden activity").setView(et)
                                .setPositiveButton("OK") { _, _ -> if (et.text.isNotBlank()) set("act:" + et.text.toString().trim()) }
                                .setNegativeButton("Cancel", null).show()
                        }
                        else -> set(vals[w])
                    }
                }.show()
                true
            }
        }

        override fun onCreatePreferences(state: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
            val ctx = requireContext()
            val prefs = Prefs(ctx)
            fun click(key: String, f: () -> Unit) { findPreference<Preference>(key)?.setOnPreferenceClickListener { f(); true } }

            findPreference<Preference>("s_labs")?.isVisible = prefs.b("labs", false)

            findPreference<ListPreference>("icon_pack")?.let { lp ->
                val names = arrayListOf("Jelly Bean (built-in)", "Apps' own icons")
                val vals = arrayListOf("builtin", "system")
                val pm = ctx.packageManager
                val seen = HashSet<String>()
                for (a in arrayOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.anddoes.launcher.THEME")) {
                    for (ri in pm.queryIntentActivities(Intent(a), 0)) {
                        if (seen.add(ri.activityInfo.packageName)) { names.add(ri.loadLabel(pm).toString()); vals.add(ri.activityInfo.packageName) }
                    }
                }
                lp.entries = names.toTypedArray(); lp.entryValues = vals.toTypedArray()
            }
            if (prefs.b("labs_grid20", false)) for (k in arrayOf("grid_cols", "grid_rows")) {
                findPreference<ListPreference>(k)?.let { lp ->
                    val v = (2..20).map { it.toString() }.toTypedArray()
                    lp.entries = v; lp.entryValues = v
                }
            }

            for ((k, d) in listOf("g_home" to "none", "g_up" to "drawer", "g_down" to "notif", "g_up2" to "none", "g_down2" to "none",
                "g_double" to "none", "g_dt_up" to "none", "g_dt_down" to "none", "g_pinch_in" to "none", "g_pinch_out" to "none",
                "g_rot_cw" to "none", "g_rot_ccw" to "none")) gesturePref(k, d)
            click("access") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

            click("pages") { toast("On the home screen: long-press an empty spot, then Edit pages") }
            click("hidden") {
                val repo = AppRepo(ctx); repo.reload()
                val apps = repo.apps
                val hidden = prefs.hidden
                val checked = BooleanArray(apps.size) { apps[it].pkg in hidden }
                AlertDialog.Builder(requireActivity()).setTitle("Hide apps")
                    .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
                    .setPositiveButton("OK") { _, _ -> prefs.hidden = apps.indices.filter { checked[it] }.map { apps[it].pkg }.toSet() }
                    .setNegativeButton("Cancel", null).show()
            }
            click("tabs_new") { val r = AppRepo(ctx); r.reload(); TabsUi.create(requireActivity(), r, Groups(prefs.sp)) { touch(prefs) } }
            click("tabs_edit") { val r = AppRepo(ctx); r.reload(); TabsUi.edit(requireActivity(), r, Groups(prefs.sp)) { touch(prefs) } }

            findPreference<Preference>("lock_enable")?.setOnPreferenceChangeListener { _, v ->
                if (v == true) {
                    if (!Settings.canDrawOverlays(ctx)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                    if (Build.VERSION.SDK_INT >= 33) requireActivity().requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                true
            }
            click("lock_preview") { startActivity(Intent(ctx, LockActivity::class.java)) }
            click("set_default") {
                try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }

            click("b_save") { saveLauncher.launch("jb-launcher-backup.jbl") }
            click("b_restore") { restoreLauncher.launch(arrayOf("*/*")) }
            click("b_share") {
                try {
                    val dir = File(ctx.cacheDir, "backups"); dir.mkdirs()
                    val f = File(dir, "jb-launcher-backup.jbl")
                    f.writeText(Backup.export(ctx))
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share backup"))
                } catch (e: Exception) { toast("Couldn't share backup") }
            }
            click("restart") { touch(prefs); toast("Launcher will reload when you go home") }
            click("reset") {
                AlertDialog.Builder(requireActivity()).setTitle("Reset home screen?")
                    .setMessage("Removes everything you placed and restores the default layout.")
                    .setPositiveButton("Reset") { _, _ -> Store(ctx).reset(); touch(prefs) }
                    .setNegativeButton("Cancel", null).show()
            }
            click("reset_all") {
                AlertDialog.Builder(requireActivity()).setTitle("Reset everything?")
                    .setMessage("All settings, tabs and the home layout go back to defaults.")
                    .setPositiveButton("Reset") { _, _ ->
                        prefs.sp.edit().clear().apply(); Store(ctx).reset()
                        ctx.getSharedPreferences("usage", 0).edit().clear().apply()
                        requireActivity().recreate()
                    }.setNegativeButton("Cancel", null).show()
            }
            click("wizard") { wizard() }
            click("labs_info") {
                val st = Store(ctx)
                AlertDialog.Builder(requireActivity()).setTitle("Debug info").setMessage(
                    "Device: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                        "Items: ${st.items.size}\nPages: ${st.pages}\nPrefs: ${prefs.sp.all.size}").setPositiveButton("OK", null).show()
            }
        }

        private fun touch(p: Prefs) { p.sp.edit().putString("_restart", System.currentTimeMillis().toString()).apply() }

        private fun wizard() {
            val ctx = requireContext()
            val p = Prefs(ctx)
            AlertDialog.Builder(requireActivity()).setTitle("Home screen grid").setItems(arrayOf("4 x 4", "5 x 5", "6 x 5", "5 x 4")) { _, g ->
                val c = intArrayOf(4, 5, 6, 5)[g]; val r = intArrayOf(4, 5, 5, 4)[g]
                AlertDialog.Builder(requireActivity()).setTitle("Icons").setItems(arrayOf("Jelly Bean icons", "Apps' own icons")) { _, ic ->
                    AlertDialog.Builder(requireActivity()).setTitle("App drawer").setItems(arrayOf("Horizontal pages", "Vertical scroll", "List")) { _, d ->
                        p.sp.edit().putString("grid_cols", c.toString()).putString("grid_rows", r.toString())
                            .putString("icon_pack", if (ic == 0) "builtin" else "system")
                            .putString("d_style", arrayOf("horizontal", "vertical", "list")[d]).apply()
                        Store(ctx).reset(); touch(p)
                        toast("Done - go to the home screen")
                    }.show()
                }.show()
            }.show()
        }
    }
}
