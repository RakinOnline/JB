package com.jb.launcher

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class UnlockRing(ctx: Context) : View(ctx) {
    var onUnlock: (() -> Unit)? = null
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat()
    }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val lockStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
    private var ox = 0f
    private var oy = 0f
    private var drag = false

    private fun cx() = width / 2f
    private fun cy() = height - context.dp(80).toFloat()
    private fun lr() = width * 0.1f
    private fun rMax() = width * 0.45f

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val rMin = w * 0.17f
        val rm = rMax()
        val steps = 10
        val fx = cx() + ox
        val fy = cy() + oy
        for (i in 0 until steps) {
            val r = rMin + (rm - rMin) * i / (steps - 1)
            val n = ((Math.PI * r * 200.0 / 180.0) / (w * 0.03)).toInt().coerceAtLeast(1)
            for (j in 0..n) {
                val a = Math.toRadians(170.0 + 200.0 * j / n)
                val x = cx() + (r * Math.cos(a)).toFloat()
                val y = cy() + (r * Math.sin(a)).toFloat()
                var al = 0.35f + 0.45f * (1f - i / (steps - 1f))
                if (drag) {
                    val d = Math.hypot((x - fx).toDouble(), (y - fy).toDouble()).toFloat()
                    if (d < w * 0.12f) al = Math.min(1f, al + 0.5f * (1f - d / (w * 0.12f)))
                }
                dotP.alpha = (al * 255).toInt()
                c.drawCircle(x, y, w * 0.0065f, dotP)
            }
        }
        c.drawCircle(fx, fy, lr(), ringP)
        drawLock(c, fx, fy, lr())
    }

    private fun drawLock(c: Canvas, cx: Float, cy: Float, r: Float) {
        val bw = r * 0.7f
        val bh = r * 0.5f
        val top = cy - bh / 2 + r * 0.12f
        c.drawRoundRect(RectF(cx - bw / 2, top, cx + bw / 2, top + bh), r * 0.08f, r * 0.08f, fillP)
        lockStroke.strokeWidth = r * 0.11f
        val sw = bw * 0.62f
        c.drawArc(RectF(cx - sw / 2, top - sw * 0.85f, cx + sw / 2, top + sw * 0.15f), 180f, 180f, false, lockStroke)
        val keyP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt() }
        c.drawCircle(cx, top + bh * 0.45f, r * 0.07f, keyP)
    }

    private fun move(e: MotionEvent) {
        ox = e.x - cx(); oy = e.y - cy()
        val len = Math.hypot(ox.toDouble(), oy.toDouble()).toFloat()
        val m = rMax()
        if (len > m) { ox *= m / len; oy *= m / len }
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (Math.hypot((e.x - cx()).toDouble(), (e.y - cy()).toDouble()) < lr() * 1.6f) {
                    drag = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    move(e)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> if (drag) {
                move(e)
                if (Math.hypot(ox.toDouble(), oy.toDouble()) > rMax() * 0.85f) {
                    drag = false
                    ox = 0f; oy = 0f
                    invalidate()
                    onUnlock?.invoke()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (drag) {
                drag = false
                val sx = ox; val sy = oy
                ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = 180
                    addUpdateListener { ox = sx * (it.animatedValue as Float); oy = sy * (it.animatedValue as Float); invalidate() }
                    start()
                }
            }
        }
        return true
    }
}

class LockActivity : Activity() {
    private lateinit var charge: TextView

    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { finish() }
    }
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { updateBattery(i) }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setShowWhenLocked(true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this)
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val time = TextClock(this).apply {
            format12Hour = "h:mm"; format24Hour = "H:mm"
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 80f)
            setTextColor(Color.WHITE)
            includeFontPadding = false
            setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
        }
        val date = TextClock(this).apply {
            format12Hour = "EEE, MMMM d"; format24Hour = "EEE, MMMM d"
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
        }
        charge = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
        }
        top.addView(time, LinearLayout.LayoutParams(-2, -2))
        top.addView(date, LinearLayout.LayoutParams(-2, -2))
        top.addView(charge, LinearLayout.LayoutParams(-2, -2))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(0, dp(40), dp(24), 0) })

        val ring = UnlockRing(this)
        ring.onUnlock = {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (km.isKeyguardLocked) km.requestDismissKeyguard(this, null)
            finish()
        }
        root.addView(ring, FrameLayout.LayoutParams(-1, dp(330), Gravity.BOTTOM).apply { bottomMargin = dp(150) })

        val carrier = try {
            (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName
        } catch (e: Exception) { "" }
        val label = TextView(this).apply {
            text = if (carrier.isNullOrBlank()) "Android" else carrier
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
        }
        root.addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(40) })
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (top.layoutParams as FrameLayout.LayoutParams).topMargin = i.top + dp(24)
            top.requestLayout()
            insets
        }
        registerReceiver(userPresent, IntentFilter(Intent.ACTION_USER_PRESENT))
        val sticky = registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) updateBattery(sticky)
    }

    private fun updateBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0) level * 100 / scale else 0
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charge.text = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging, $pct%"
            BatteryManager.BATTERY_STATUS_FULL -> "Charged"
            else -> if (pct in 1..15) "Charge your phone, $pct%" else ""
        }
    }

    override fun onUserLeaveHint() { super.onUserLeaveHint(); finish() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* swallow */ }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(userPresent) } catch (e: Exception) { }
        try { unregisterReceiver(battery) } catch (e: Exception) { }
    }
}

class LockService : Service() {
    private val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) {
                try {
                    c.startActivity(Intent(c, LockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                } catch (e: Exception) { }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("lock", "Lock screen", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "lock")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("JB lock screen active")
            .build()
        startForeground(1, n)
        registerReceiver(rcv, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { super.onDestroy(); try { unregisterReceiver(rcv) } catch (e: Exception) { } }

    companion object {
        fun sync(ctx: Context) {
            val want = Prefs(ctx).b("lock_enable", false) && Settings.canDrawOverlays(ctx)
            val i = Intent(ctx, LockService::class.java)
            try { if (want) ctx.startForegroundService(i) else ctx.stopService(i) } catch (e: Exception) { }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { LockService.sync(c) }
}
