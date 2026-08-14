package com.halfwake.pocket

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.halfwake.pocket.widget.HalfwakeWidgetProvider

/**
 * The mood-diary tick (15/30/60 min, user-selectable) writes a real Log
 * entry and should stay exactly that infrequent — that's the honest
 * "computed on a tick" philosophy the whole project started with.
 *
 * But the widget's CLOCK should look right at a glance, and a snapshot
 * that's up to an hour stale defeats the point of it being a clock. So
 * this is a separate, much tighter loop that just repaints the widget
 * bitmap — same live mood/time it would show anyway, no new diary entry,
 * no change to the honest record's cadence.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HalfwakeWidgetProvider.updateAll(context)
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 60_000L // 1 minute — a real clock should look current

        fun ensureScheduled(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WidgetRefreshReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Inexact repeating — Android may batch this under Doze/battery
            // saver, so "every minute" is a target, not a hard guarantee.
            // Still vastly tighter than the 15/30/60 min diary tick.
            alarmManager.setInexactRepeating(
                AlarmManager.RTC, System.currentTimeMillis() + REFRESH_INTERVAL_MS,
                REFRESH_INTERVAL_MS, pendingIntent
            )
        }
    }
}
