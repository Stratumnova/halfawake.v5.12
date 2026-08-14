package com.halfwake.pocket

import android.content.Context

/**
 * The three consumers of Pool. Each one independently checks, pulls, and
 * confirms — none of them read from or call each other, only from
 * PoolStore. That's the actual point: if Flair's story and Tech's
 * numbers ever disagree, it's because one of THESE functions has a real
 * bug, not because two systems computed different "current state" at
 * slightly different moments.
 */
object EventProcessor {

    /** Call this once per real event. Publishes to Pool, then runs all
     * three consumers against that single entry. */
    fun process(context: Context, eventType: String, data: Map<String, String>) {
        val id = PoolStore.publish(context, eventType, data)
        writeTruth(context, id)
        writeTech(context, id)
        writeFlair(context, id)
    }

    private fun writeTruth(context: Context, id: String) {
        if (!PoolStore.check(context, id)) return
        val entry = PoolStore.pull(context, id) ?: return
        val text = TruthPhrasing.phrase(entry) ?: return
        LogStore.appendTruth(context, entry.eventType, text)
        PoolStore.confirm(context, id, "truth")
    }

    private fun writeTech(context: Context, id: String) {
        if (!PoolStore.check(context, id)) return
        val entry = PoolStore.pull(context, id) ?: return
        val text = TechPhrasing.phrase(entry) ?: return
        LogStore.appendAddon(context, entry.eventType, "tech", text)
        PoolStore.confirm(context, id, "tech")
    }

    private fun writeFlair(context: Context, id: String) {
        if (!PoolStore.check(context, id)) return
        val entry = PoolStore.pull(context, id) ?: return

        // Flair needs the SPECIFIC category, not the generic event type —
        // "mood_tick" alone doesn't tell you which mood, "code_breaker_result"
        // alone doesn't tell you win vs. loss.
        val flairCategory = when (entry.eventType) {
            "mood_tick" -> entry.data["mood"] ?: "content"
            "code_breaker_result" -> if (entry.data["outcome"] == "win") "code_breaker_win" else "code_breaker_loss"
            "timer_fired" -> "timer_fired"
            "alarm_fired" -> "alarm_fired"
            "setting_changed" -> if (entry.data["state"] == "ON") "setting_on" else "setting_off"
            else -> return
        }

        // Base app always has a default flair voice — an addon expands
        // the pool for a category, it's never required just to get any
        // flair at all. Only mood_tick currently has an addon hook —
        // that's the one event type NpcBridge's personality-lines.json is
        // keyed for. Extending addon-flair to game/timer/alarm/setting
        // events too is a real future scope decision, not assumed here.
        val addonLine = if (entry.eventType == "mood_tick") NpcBridge.flairFor(context, flairCategory) else null
        val line = addonLine ?: SystemFlair.pick(flairCategory) ?: return
        LogStore.appendAddon(context, entry.eventType, "personality", line)
        PoolStore.confirm(context, id, "flair")
    }
}

/** Truth: bare fact, no interpretation. One line per event type. */
object TruthPhrasing {
    fun phrase(entry: PoolEntry): String? = when (entry.eventType) {
        "mood_tick" -> entry.data["reason"]
        "code_breaker_result" -> entry.data["outcome"]?.let { outcome ->
            if (outcome == "win") "Code Breaker: Won." else "Code Breaker: Lost."
        }
        "timer_fired" -> "Timer went off (${entry.data["name"]})."
        "alarm_fired" -> "${entry.data["name"]} went off (${entry.data["time"]})."
        "setting_changed" -> "Setting changed: ${entry.data["label"]} turned ${entry.data["state"]}."
        else -> null
    }
}

/** Tech: the real mechanics, addon-independent, always present. */
object TechPhrasing {
    fun phrase(entry: PoolEntry): String? = when (entry.eventType) {
        "mood_tick" -> "Tick fired. Mood: ${entry.data["mood"]}. Addons found: ${entry.data["addonCount"]}. Update interval: ${entry.data["interval"]}m."
        "code_breaker_result" -> "Code Breaker: ${entry.data["guessCount"]} guesses used, final guess ${entry.data["exact"]}/4 exact, ${entry.data["partial"]} partial."
        "timer_fired" -> "Timer '${entry.data["name"]}' completed at programmed duration."
        "alarm_fired" -> "Alarm '${entry.data["name"]}' matched scheduled time ${entry.data["time"]}."
        "setting_changed" -> "Setting '${entry.data["label"]}' set to ${entry.data["state"]}."
        else -> null
    }
}
