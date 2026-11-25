package com.jb.launcher

import android.app.PendingIntent
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.RemoteViews

class WHost(ctx: Context) : AppWidgetHost(ctx, 1024) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        LHostView(context)
}

class LHostView(ctx: Context) : AppWidgetHostView(ctx) {
    var onLong: (() -> Unit)? = null
    private var fired = false
    private var x0 = 0f
    private var y0 = 0f
    private val lp = Runnable {
        fired = true
        parent?.requestDisallowInterceptTouchEvent(true)
        onLong?.invoke()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false; x0 = ev.x; y0 = ev.y
                postDelayed(lp, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (Math.hypot((ev.x - x0).toDouble(), (ev.y - y0).toDouble()) > 24) removeCallbacks(lp)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(lp)
        }
        return fired
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (fired) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) fired = false
            return true
        }
        return super.onTouchEvent(ev)
    }
}

open class ClockProvider : AppWidgetProvider() {
    open fun layoutRes(): Int = R.layout.widget_thin
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val rv = RemoteViews(ctx.packageName, layoutRes())
            val pi = PendingIntent.getActivity(ctx, 0,
                Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE)
            rv.setOnClickPendingIntent(R.id.root, pi)
            m.updateAppWidget(id, rv)
        }
    }
}

class ThinClockProvider : ClockProvider() { override fun layoutRes() = R.layout.widget_thin }
class DigitalClockProvider : ClockProvider() { override fun layoutRes() = R.layout.widget_digital }
class AnalogClockProvider : ClockProvider() { override fun layoutRes() = R.layout.widget_analog }
class DateProvider : ClockProvider() { override fun layoutRes() = R.layout.widget_date }

fun ownWidgetLayout(info: AppWidgetProviderInfo): Int = when (info.provider.className) {
    ThinClockProvider::class.java.name -> R.layout.widget_thin
    DigitalClockProvider::class.java.name -> R.layout.widget_digital
    AnalogClockProvider::class.java.name -> R.layout.widget_analog
    DateProvider::class.java.name -> R.layout.widget_date
    else -> 0
}
