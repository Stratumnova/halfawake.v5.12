package com.halfwake.pocket.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.halfwake.pocket.AppState
import com.halfwake.pocket.MainActivity

/**
 * Real widget interaction, not a single fixed action: if something is
 * ringing, a tap on the widget dismisses it directly, no need to open the
 * app first. If nothing's ringing, it opens the app like before.
 *
 * Sound itself can currently only ever be playing while MainActivity is
 * already open (the documented foreground-only limitation on timers/
 * alarms), so clearing AppState here is safe — the app's own foreground
 * loop notices the state changed and stops the MediaPlayer itself.
 */
class WidgetTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppState.ensureLoaded(context) // was load() — that was the actual v5 regression: this receiver can run while the app process is already alive, and load() would've clobbered any unsaved in-memory state
        if (AppState.ringingSource != null) {
            AppState.ringingSource = null
            AppState.ringingLabel = null
            AppState.save(context)
            HalfwakeWidgetProvider.updateAll(context)
        } else {
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(openAppIntent)
        }
    }

    companion object {
        fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WidgetTapReceiver::class.java)
            return PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
