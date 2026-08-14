package com.halfwake.pocket

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TimerSlot(var name: String, var totalMs: Long, var remainingMs: Long, var running: Boolean)
data class AlarmSlot(var time: String, var enabled: Boolean, var name: String, var firedKey: String?)
/** packageName empty means "not linked to a real installed app" — a
 * placeholder that will never accumulate real minutes. Real tracking
 * only happens for entries with an actual package name attached. */
data class WatchedApp(var name: String, var watched: Boolean, var minutes: Int, var packageName: String = "")

data class GameSession(
    var secret: List<Int>,
    var guesses: MutableList<Triple<List<Int>, Int, Int>> = mutableListOf(),
    var guessesLeft: Int = 6,
    var started: Boolean = false,
    var finished: Boolean = false,
)

/**
 * In-memory app state, mirroring the browser prototype's global variables,
 * with a save()/load() pair so it survives process death. Every numeric
 * default here is a starting value, tunable in a future update per the
 * spec — nothing here should require a rebuild to retune later.
 */
object AppState {
    var appTracker: Boolean = false
    var widgetClockStyle: String = "analog" // "analog" | "digital"
    var widgetTheme: String = "light"
    var widgetBorderColor: String = "gold" // "gold" | "metallic_green" | "black" | "white" — widget-only, see BorderColors
    var appTheme: String = "light"
    var updateIntervalMinutes: Int = 30 // 15 | 30 | 60
    var alarmSoundUri: String? = null // null = default beep

    var baseMoodOverrideForDemo: String? = null // debug/testing aid only

    var timers: MutableList<TimerSlot> = mutableListOf(TimerSlot("Timer 1", 0, 0, false))
    var alarms: MutableList<AlarmSlot> = mutableListOf(
        AlarmSlot("07:00", false, "Alarm 1", null),
        AlarmSlot("12:00", false, "Alarm 2", null),
        AlarmSlot("15:00", false, "Alarm 3", null),
        AlarmSlot("18:00", false, "Alarm 4", null),
        AlarmSlot("22:00", false, "Alarm 5", null),
    )
    // No more fake placeholder entries for apps we have no real link to —
    // those never accumulated real minutes and that's exactly the bug
    // being fixed. "Code Breaker" stays as the one internal, non-package
    // entry (tracked via its own play-time logic). Real external apps get
    // added via Settings → "Add tracked app," which links a genuine
    // package name so WatchStatsWorker can actually read its usage.
    //
    // CopyOnWriteArrayList, not a plain mutableListOf() — this is the one
    // collection genuinely touched from two threads (MainActivity's UI
    // thread add/remove, WatchStatsWorker's background-thread forEach).
    // A plain ArrayList's iterator throws ConcurrentModificationException
    // if the list is structurally changed mid-iteration from another
    // thread; CopyOnWriteArrayList never does, since writes copy the
    // backing array instead of mutating it in place. The list is small
    // (a handful of entries), so that copy cost is negligible.
    var watchlist: MutableList<WatchedApp> = java.util.concurrent.CopyOnWriteArrayList(
        listOf(WatchedApp("Code Breaker", true, 0, ""))
    )

    var winCount: Int = 0
    var loseCount: Int = 0
    var gameSession: GameSession? = null
    var gameOverrideType: String? = null // "smug" | "grumpy" | null
    var gameOverrideExpiresAt: Long = 0

    var ringingSource: String? = null // "timer" | "alarm" | null
    var ringingLabel: String? = null

    var disabledAddonIds: MutableSet<String> = mutableSetOf()
    var showNpcInfo: Boolean = false

    var stopwatchRunning: Boolean = false
    var stopwatchElapsedMs: Long = 0
    var stopwatchStartedAt: Long = 0

    var startledUntil: Long = 0 // real accelerometer-spike detection, see MainActivity's SensorEventListener

    private const val FILE_NAME = "app_state.json"
    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** TickWorker and WatchStatsWorker run in the same process as the rest
     * of the app whenever that process is already alive — calling load()
     * unconditionally from them would overwrite in-memory state the UI
     * thread might be actively mutating at that exact moment (a real,
     * if narrow, race). ensureLoaded() only actually reads from disk once
     * per process — the app's own real startup (MainActivity.onCreate)
     * still calls load() directly, since that IS the moment fresh disk
     * state should win. */
    private var hasLoadedThisProcess = false
    fun ensureLoaded(context: Context) {
        if (!hasLoadedThisProcess) load(context)
    }

    fun save(context: Context) {
        val o = JSONObject()
        o.put("appTracker", appTracker)
        o.put("widgetClockStyle", widgetClockStyle)
        o.put("widgetTheme", widgetTheme)
        o.put("widgetBorderColor", widgetBorderColor)
        o.put("appTheme", appTheme)
        o.put("updateIntervalMinutes", updateIntervalMinutes)
        o.put("alarmSoundUri", alarmSoundUri)
        o.put("winCount", winCount)
        o.put("loseCount", loseCount)

        val timersArr = JSONArray()
        timers.forEach { t ->
            timersArr.put(JSONObject().apply {
                put("name", t.name); put("totalMs", t.totalMs); put("remainingMs", t.remainingMs); put("running", t.running)
            })
        }
        o.put("timers", timersArr)

        val alarmsArr = JSONArray()
        alarms.forEach { a ->
            alarmsArr.put(JSONObject().apply {
                put("time", a.time); put("enabled", a.enabled); put("name", a.name); put("firedKey", a.firedKey ?: JSONObject.NULL)
            })
        }
        o.put("alarms", alarmsArr)

        val watchArr = JSONArray()
        watchlist.forEach { w ->
            watchArr.put(JSONObject().apply { put("name", w.name); put("watched", w.watched); put("minutes", w.minutes); put("packageName", w.packageName) })
        }
        o.put("watchlist", watchArr)

        o.put("logFilterLog", LogFilters.log)
        o.put("logFilterPersonality", LogFilters.personality)
        o.put("logFilterTech", LogFilters.tech)
        o.put("showNpcInfo", showNpcInfo)
        o.put("disabledAddonIds", JSONArray(disabledAddonIds.toList()))
        o.put("stopwatchRunning", stopwatchRunning)
        o.put("stopwatchElapsedMs", stopwatchElapsedMs)
        o.put("stopwatchStartedAt", stopwatchStartedAt)

        file(context).writeText(o.toString())
    }

    fun load(context: Context) {
        hasLoadedThisProcess = true
        val f = file(context)
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            appTracker = o.optBoolean("appTracker", appTracker)
            widgetClockStyle = o.optString("widgetClockStyle", widgetClockStyle)
            widgetTheme = o.optString("widgetTheme", widgetTheme)
            widgetBorderColor = o.optString("widgetBorderColor", widgetBorderColor)
            appTheme = o.optString("appTheme", appTheme)
            updateIntervalMinutes = o.optInt("updateIntervalMinutes", updateIntervalMinutes)
            alarmSoundUri = if (o.isNull("alarmSoundUri")) null else o.optString("alarmSoundUri", null)
            winCount = o.optInt("winCount", 0)
            loseCount = o.optInt("loseCount", 0)

            o.optJSONArray("timers")?.let { arr ->
                timers = (0 until arr.length()).map { i ->
                    val t = arr.getJSONObject(i)
                    TimerSlot(t.getString("name"), t.getLong("totalMs"), t.getLong("remainingMs"), t.getBoolean("running"))
                }.toMutableList()
            }
            o.optJSONArray("alarms")?.let { arr ->
                alarms = (0 until arr.length()).map { i ->
                    val a = arr.getJSONObject(i)
                    AlarmSlot(a.getString("time"), a.getBoolean("enabled"), a.getString("name"), if (a.isNull("firedKey")) null else a.getString("firedKey"))
                }.toMutableList()
            }
            o.optJSONArray("watchlist")?.let { arr ->
                watchlist = java.util.concurrent.CopyOnWriteArrayList(
                    (0 until arr.length()).map { i ->
                        val w = arr.getJSONObject(i)
                        WatchedApp(w.getString("name"), w.getBoolean("watched"), w.getInt("minutes"), w.optString("packageName", ""))
                    }
                )
            }

            LogFilters.log = o.optBoolean("logFilterLog", true)
            LogFilters.personality = o.optBoolean("logFilterPersonality", true)
            LogFilters.tech = o.optBoolean("logFilterTech", false)
            showNpcInfo = o.optBoolean("showNpcInfo", false)
            o.optJSONArray("disabledAddonIds")?.let { arr ->
                disabledAddonIds = (0 until arr.length()).map { arr.getString(it) }.toMutableSet()
            }
            stopwatchRunning = o.optBoolean("stopwatchRunning", false)
            stopwatchElapsedMs = o.optLong("stopwatchElapsedMs", 0)
            stopwatchStartedAt = o.optLong("stopwatchStartedAt", 0)
        } catch (e: Exception) {
            // Corrupt state file: fall back to defaults already set above rather than crash.
        }
    }
}
