package com.halfwake.pocket.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View.MeasureSpec
import android.widget.RemoteViews
import com.halfwake.pocket.EffectiveState
import com.halfwake.pocket.FaceClockView
import com.halfwake.pocket.MainActivity
import com.halfwake.pocket.R

/**
 * RemoteViews cannot host a custom animated View — no live blinking, no
 * touch-tracking pupils, no continuously moving hands. What it can show is
 * a static image, refreshed each tick. So: render FaceClockView once,
 * capture it to a Bitmap, hand that to the widget. The live version only
 * exists once the app itself is open.
 */
class HalfwakeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> updateWidget(context, mgr, id) }
    }

    companion object {
        private const val SIZE_PX = 360

        fun renderSnapshot(context: Context): Bitmap {
            val view = FaceClockView(context)
            view.live = false
            val effective = EffectiveState.compute(context) // was 2 separate calls (moodKey + reasonText) — same redundant-query class the v3 fix addressed for MainActivity, just missed here
            view.moodKey = effective.moodKey
            view.reasonText = effective.reasonText

            val spec = MeasureSpec.makeMeasureSpec(SIZE_PX, MeasureSpec.EXACTLY)
            view.measure(spec, spec)
            view.layout(0, 0, SIZE_PX, SIZE_PX)

            val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            return bitmap
        }

        fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_halfwake)
            views.setImageViewBitmap(R.id.widget_face, renderSnapshot(context))

            views.setOnClickPendingIntent(R.id.widget_face, WidgetTapReceiver.pendingIntent(context))

            mgr.updateAppWidget(id, views)
        }

        /** Called by TickWorker right after a new diary entry is written. */
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, HalfwakeWidgetProvider::class.java))
            ids.forEach { id -> updateWidget(context, mgr, id) }
        }
    }
}
