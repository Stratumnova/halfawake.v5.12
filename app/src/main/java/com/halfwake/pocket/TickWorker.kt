package com.halfwake.pocket

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.halfwake.pocket.widget.HalfwakeWidgetProvider
import java.util.concurrent.TimeUnit

class TickWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppState.ensureLoaded(applicationContext) // not load() — see AppState.ensureLoaded's doc for why

        val metrics = UsageRepository.currentMetrics(applicationContext)
        val result = computeMood(metrics)
        val addonCount = com.halfwake.pocket.addons.AddonManager.scan(applicationContext).size

        // Pool pathway: publish once, let Truth/Tech/Flair each independently
        // tap the same entry. Superseded EffectiveState.displayLine() for
        // this path — that function still exists (harmless, unused here now)
        // in case anything else ever needs a one-shot honest line.
        EventProcessor.process(
            applicationContext, "mood_tick",
            mapOf(
                "mood" to result.mood.id,
                "reason" to result.reason,
                "addonCount" to addonCount.toString(),
                "interval" to AppState.updateIntervalMinutes.toString(),
            )
        )

        // DiaryStore stays as the simple "is this the first tick ever" check
        // MainActivity relies on — kept, not removed, per "only touch what
        // needs touching." Line field holds the honest reason directly now.
        DiaryStore.append(
            applicationContext,
            DiaryEntry(atMillis = System.currentTimeMillis(), mood = result.mood.id, reason = result.reason, line = result.reason)
        )

        if (result.mood == Mood.CRITICAL) {
            NotificationHelper.notifyCritical(applicationContext, metrics.batteryPercent)
        }

        AppState.save(applicationContext)
        HalfwakeWidgetProvider.updateAll(applicationContext)

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "halfwake_tick"

        fun ensureScheduled(context: Context, minutes: Long = AppState.updateIntervalMinutes.toLong()) {
            val request = PeriodicWorkRequestBuilder<TickWorker>(minutes, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE, // REPLACE, not KEEP — lets a changed interval actually take effect
                request
            )
        }

        /** Runs a tick immediately — used on first launch so the diary isn't empty. */
        fun runOnce(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<TickWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
