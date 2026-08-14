package com.halfwake.pocket

import android.content.Context

object EffectiveState {

    fun activeCountdownTimer(): TimerSlot? =
        AppState.timers.firstOrNull { it.running && it.remainingMs in 1..10000 }

    /** Same result as calling moodKey() then reasonText() separately, but
     * does the real UsageRepository/computeMood work exactly once instead
     * of twice — those two functions previously each independently
     * re-queried UsageStatsManager, which is a real system call. Anything
     * calling both together (refreshFace(), buildHomeTab()) should use
     * this instead. moodKey()/reasonText() are left untouched for any
     * other call site that only needs one or the other. */
    data class Effective(val moodKey: String, val reasonText: String)

    fun compute(context: Context): Effective {
        val now = System.currentTimeMillis()
        if (AppState.ringingSource != null) {
            val text = if (AppState.ringingSource == "timer") "Timer's up — ${AppState.ringingLabel}."
                else "Alarm going off — ${AppState.ringingLabel}."
            return Effective("alarm", text)
        }
        val cd = activeCountdownTimer()
        if (cd != null) return Effective("countdown", "${(cd.remainingMs / 1000) + 1}s left on ${cd.name}.")
        if (now < AppState.startledUntil) return Effective("startled", "Whoa — something just moved me.")
        if (AppState.gameOverrideType != null && now < AppState.gameOverrideExpiresAt) {
            val key = AppState.gameOverrideType!!
            return Effective(key, MoodEngine_reasonFallback(key))
        }
        val gs = AppState.gameSession
        if (gs != null && gs.started && !gs.finished) {
            val used = 6 - gs.guessesLeft
            return Effective("restless", "Left a code half-cracked. $used guess${if (used == 1) "" else "es"} in, none of it finished.")
        }
        val metrics = UsageRepository.currentMetrics(context)
        val result = computeMood(metrics)
        return Effective(result.mood.id, result.reason)
    }

    // smug/grumpy have no metric-based reason — this is their short built-in text.
    private fun MoodEngine_reasonFallback(key: String): String = when (key) {
        "smug" -> "Still smug about that."
        "grumpy" -> "Still a little grumpy about that."
        else -> key
    }

    // Thin wrappers — compute() is the single real implementation of the
    // priority chain now. These exist only so any call site that needs
    // just one value doesn't have to unpack an Effective. Do NOT
    // reimplement the chain here again — that's exactly the divergence
    // that caused the v3→v4 fix (compute() and these used to independently
    // duplicate the same logic and quietly disagreed about smug/grumpy).
    fun moodKey(context: Context): String = compute(context).moodKey
    fun reasonText(context: Context): String = compute(context).reasonText
}
