package com.halfwake.pocket

import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Runs every 24h, per the explicit "check installed apps every 24 hours"
 * requirement. For each watchlist entry that has a real packageName
 * (linked via "Add tracked app" in Settings), reads that app's actual
 * foreground time today from UsageStatsManager — the same official API
 * the mood engine already uses, never anything invasive.
 *
 * Entries with no packageName (currently just "Code Breaker," an internal
 * feature, not an installed app) are left alone — their minutes come from
 * their own logic elsewhere, not from this worker.
 */
class WatchStatsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppState.ensureLoaded(applicationContext) // not load() — see AppState.ensureLoaded's doc for why
        if (!UsageRepository.hasUsageAccess(applicationContext)) return Result.success()

        val usm = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val startOfToday = cal.timeInMillis
        val now = System.currentTimeMillis()

        var changed = false
        AppState.watchlist.forEach { app ->
            if (app.packageName.isBlank() || !app.watched) return@forEach
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfToday, now)
            val totalMs = stats?.filter { it.packageName == app.packageName }?.sumOf { it.totalTimeInForeground } ?: 0L
            val minutes = (totalMs / 60000).toInt()
            if (minutes != app.minutes) {
                app.minutes = minutes
                changed = true
            }
        }

        if (changed) {
            AppState.save(applicationContext)
            LogStore.appendAddon(applicationContext, "watchlist", "tech", "Daily app-usage check completed. ${AppState.watchlist.count { it.packageName.isNotBlank() }} linked app(s) updated.")
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "halfwake_watch_stats"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchStatsWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runOnce(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<WatchStatsWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
