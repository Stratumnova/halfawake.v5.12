package com.halfwake.pocket

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var faceView: FaceClockView
    private lateinit var tabContent: FrameLayout
    private lateinit var quickStats: TextView
    private lateinit var tabBar: BottomNavigationView
    private var activeTab = "home"
    private var currentInput = mutableListOf<Int>()
    private var playMode = "game"

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    /** Was showing a generic "Custom sound selected" — now queries the
     * real filename from the URI directly, no new persisted field needed. */
    private fun soundFileLabel(): String {
        val uriStr = AppState.alarmSoundUri ?: return "Using default (no file chosen)"
        return try {
            val uri = Uri.parse(uriStr)
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return "Using: $name"
                }
                "Custom sound selected"
            } ?: "Custom sound selected"
        } catch (e: Exception) {
            "Custom sound selected"
        }
    }

    private val soundPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                AppState.alarmSoundUri = uri.toString()
                if (AppState.appTracker) EventProcessor.process(this, "setting_changed", mapOf("label" to "Alarm sound", "state" to "ON"))
                AppState.save(this)
                renderTab()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppState.load(this)

        faceView = findViewById(R.id.face_clock)
        tabContent = findViewById(R.id.tab_content)
        quickStats = findViewById(R.id.quick_stats)

        // Single tap = quick info (the "tiles" — Battery/Uptime/Screen/Last
        // Active in one line), double tap = jump to Settings ("menus"), as
        // discussed — real GestureDetector, not a platform-limited widget
        // workaround. This is the in-app face specifically; the home-screen
        // widget itself still only has the one tap Android allows it.
        // Was single-tap=quick-stats, double-tap=jump-to-Settings. Double-
        // tap removed entirely (see FaceClockView's onTap doc for why it
        // was actually hurting responsiveness, not just being unused).
        // Touching the face now dismisses first if something's ringing —
        // real fix for "touching the face should stop the alarm."
        faceView.onTap = {
            if (AppState.ringingSource != null) { dismissRinging(); renderTab() }
            else toggleQuickStats()
        }

        tabBar = findViewById(R.id.tab_bar)
        tabBar.setOnItemSelectedListener { item ->
            activeTab = when (item.itemId) {
                R.id.tab_home -> "home"; R.id.tab_play -> "play"; R.id.tab_tools -> "tools"
                R.id.tab_settings -> "settings"; else -> "log"
            }
            renderTab(); true
        }

        requestNotifPermIfNeeded()
        faceView.tick()
        refreshFace()
        refreshWindowTheme()
        renderTab()
        startForegroundTickLoop()
    }

    override fun onResume() {
        super.onResume()
        if (UsageRepository.hasUsageAccess(this)) {
            TickWorker.ensureScheduled(this, AppState.updateIntervalMinutes.toLong())
            WatchStatsWorker.ensureScheduled(this)
            if (DiaryStore.load(this).isEmpty()) TickWorker.runOnce(this)
            if (AppState.watchlist.any { it.packageName.isNotBlank() }) WatchStatsWorker.runOnce(this)
        }
        WidgetRefreshReceiver.ensureScheduled(this)
        sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
            sensorManager.registerListener(motionListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        }
        refreshFace(); renderTab()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(motionListener)
    }

    /** Neither the foreground tick loop nor the stopwatch fast-loop had any
     * way to know the Activity was gone — both were self-recursive Handler
     * callbacks with no lifecycle tie, the classic leak pattern. This flag
     * is checked at the top of both loops' runnables to actually stop them
     * instead of letting them run against a destroyed Activity forever. */
    private var activityDestroyed = false
    override fun onDestroy() {
        super.onDestroy()
        activityDestroyed = true
        sensorManager.unregisterListener(motionListener)
    }

    /** Real accelerometer-spike detection. Threshold is a starting guess —
     * needs real-device tuning once this is live, same as every other
     * numeric default in this project. */
    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager }
    private var lastMotionCheckAt = 0L
    private val motionListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            val now = System.currentTimeMillis()
            if (now - lastMotionCheckAt < 400) return // debounce
            val magnitude = kotlin.math.abs(event.values[0]) + kotlin.math.abs(event.values[1]) + kotlin.math.abs(event.values[2])
            if (magnitude > 28f) {
                lastMotionCheckAt = now
                AppState.startledUntil = now + 1200
                refreshFace()
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    private fun requestNotifPermIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refreshFace() {
        val effective = EffectiveState.compute(this)
        faceView.moodKey = effective.moodKey
        faceView.reasonText = effective.reasonText
        faceView.invalidate()
    }

    /** The "tiles" — real Battery/Uptime/Screen/Last Active in one line,
     * revealed on single tap. This was designed in the Base Spec and
     * never actually built until now — confirmed gap, closed here. */
    private fun toggleQuickStats() {
        if (quickStats.visibility == View.VISIBLE) { quickStats.visibility = View.GONE; return }
        val metrics = UsageRepository.currentMetrics(this)
        val uptimeH = metrics.uptimeMs / 3600000; val uptimeM = (metrics.uptimeMs / 60000) % 60
        val screenH = metrics.foregroundMsToday / 3600000; val screenM = (metrics.foregroundMsToday / 60000) % 60
        val lastActive = metrics.msSinceLastInteraction?.let {
            val m = it / 60000
            if (m < 60) "${m}m ago" else "${m / 60}h ago"
        } ?: "unknown"
        quickStats.text = "Battery ${metrics.batteryPercent}%  ·  Uptime ${uptimeH}h ${uptimeM}m  ·  Screen today ${screenH}h ${screenM}m  ·  Last active $lastActive"
        quickStats.visibility = View.VISIBLE
    }

    private fun refreshWindowTheme() {
        window.decorView.setBackgroundColor(Themes.of(AppState.appTheme).bg)
    }

    // ---------- tab rendering ----------

    private fun renderTab() {
        tabContent.removeAllViews()
        val view = when (activeTab) {
            "home" -> buildHomeTab()
            "play" -> buildPlayTab()
            "tools" -> buildToolsTab()
            "settings" -> buildSettingsTab()
            else -> buildLogTab()
        }
        tabContent.addView(view)
        applyThemeRecursively(view, Themes.of(AppState.appTheme))
    }

    /** App theme was previously saved but never actually applied to any
     * View — real gap. This walks whatever tab content was just built and
     * colors it; TextView/Button/EditText/Switch are all TextView subtypes
     * so this one branch covers them, ViewGroups recurse. */
    private fun applyThemeRecursively(view: View, theme: ThemeColors) {
        when (view) {
            is TextView -> view.setTextColor(theme.fg)
            is ViewGroup -> {
                view.setBackgroundColor(theme.bg)
                for (i in 0 until view.childCount) applyThemeRecursively(view.getChildAt(i), theme)
            }
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase(); textSize = 12f; setPadding(0, 24, 0, 12)
    }

    // ---------- Home ----------

    private fun buildHomeTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (!UsageRepository.hasUsageAccess(this)) {
            col.addView(TextView(this).apply {
                text = "Halfwake needs Usage Access to read screen time and app activity — it never reads what you type, only how long and how often apps are used."
                setPadding(0, 0, 0, 16)
            })
            col.addView(Button(this).apply {
                text = "Grant usage access"
                setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            })
            return col
        }

        val effective = EffectiveState.compute(this)
        val key = effective.moodKey
        val honestReason = effective.reasonText
        // Addon flair first, then the base app's own SystemFlair voice, then
        // the plain honest reason as the last resort — display-only, never
        // logs; the Pool/EventProcessor pathway (TickWorker) is the one
        // real "this counts" pass.
        val flair = NpcBridge.flairFor(this, key) ?: SystemFlair.pick(key)

        col.addView(TextView(this).apply { text = key.uppercase(); textSize = 14f; gravity = Gravity.CENTER })
        col.addView(TextView(this).apply {
            text = flair ?: honestReason
            textSize = 15f; gravity = Gravity.CENTER; setPadding(0, 8, 0, 16)
        })
        if (flair != null) {
            col.addView(TextView(this).apply {
                text = "(honest: $honestReason)"
                textSize = 11f; gravity = Gravity.CENTER; alpha = 0.6f; setPadding(0, 0, 0, 16)
            })
        }

        // ---- Mods: since a real widget can only ever do one tap action
        // (open the app — RemoteViews has no gesture detection), this is
        // where per-addon control actually lives, not on the widget itself.
        col.addView(sectionTitle("Mods"))
        val addons = com.halfwake.pocket.addons.AddonManager.scan(this)
        if (addons.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "No addons found in Android/data/com.halfwake.pocket/files/addons/."
                textSize = 12f; alpha = 0.7f
            })
        } else {
            addons.forEach { addon ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(Switch(this).apply {
                    isChecked = !AppState.disabledAddonIds.contains(addon.id)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) AppState.disabledAddonIds.remove(addon.id) else AppState.disabledAddonIds.add(addon.id)
                        AppState.save(this@MainActivity)
                        LogStore.appendAddon(this@MainActivity, "addon", "tech", "${addon.name} turned ${if (checked) "ON" else "OFF"}.")
                        renderTab()
                    }
                })
                row.addView(TextView(this).apply { text = "${addon.name} (v${addon.version})" })
                col.addView(row)
            }
        }

        // ---- NPC info toggle
        val npcRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        npcRow.addView(Switch(this).apply {
            isChecked = AppState.showNpcInfo
            setOnCheckedChangeListener { _, checked -> AppState.showNpcInfo = checked; AppState.save(this@MainActivity); renderTab() }
        })
        npcRow.addView(TextView(this).apply { text = "Show NPC info" })
        col.addView(npcRow)

        if (AppState.showNpcInfo) {
            col.addView(TextView(this).apply {
                text = if (NpcBridge.hasPersonalityAddon(this@MainActivity))
                    "Mood: $key\nStats/Values: not available yet — this appears once the full memory/token addon is installed. Right now the sample addon only supplies flair lines, no persistent stats."
                else
                    "Mood: $key\nNo personality addon enabled — nothing to report beyond the honest mood above."
                textSize = 12f; setPadding(0, 8, 0, 0); alpha = 0.75f
            })
        }

        return col
    }

    // ---------- Play: Code Breaker + Calculator ----------

    private fun buildPlayTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

        // Was two separate mode-select buttons — now one toggle, same
        // underlying playMode switch either way.
        val toggleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        toggleRow.addView(TextView(this).apply { text = "Calculator"; textSize = 14f; setPadding(0, 0, 12, 0) })
        toggleRow.addView(Switch(this).apply {
            isChecked = playMode == "game"
            setOnCheckedChangeListener { _, checked -> playMode = if (checked) "game" else "calculator"; renderTab() }
        })
        toggleRow.addView(TextView(this).apply { text = "Code Breaker"; textSize = 14f; setPadding(12, 0, 0, 0) })
        col.addView(toggleRow)

        // Display stays mode-specific — what's shown genuinely differs.
        col.addView(if (playMode == "game") buildGameDisplay() else buildCalculatorDisplay())

        // One shared, larger keypad — the actual fix for "buttons are too
        // small." Was two entirely separate digit grids before; same
        // physical 0-9 buttons now, routed by playMode, only the action
        // row beneath still differs per mode.
        col.addView(buildSharedKeypad())

        return col
    }

    private fun bigKeypadButton(label: String): Button = Button(this).apply {
        text = label; textSize = 24f; setPadding(24, 32, 24, 32)
    }

    private fun buildGameDisplay(): View {
        if (AppState.gameSession == null) startNewGame()
        val gs = AppState.gameSession!!
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 16, 0, 0) }

        col.addView(TextView(this).apply {
            text = "Wins: ${AppState.winCount}  ·  Losses: ${AppState.loseCount}"; gravity = Gravity.CENTER
        })

        val board = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        gs.guesses.forEach { (guess, exact, partial) ->
            board.addView(TextView(this).apply { text = guess.joinToString(" "); gravity = Gravity.CENTER; textSize = 16f; setPadding(0, 6, 0, 0) })
            board.addView(TextView(this).apply { text = "$exact exact · $partial close"; gravity = Gravity.CENTER; textSize = 12f; alpha = 0.7f; setPadding(0, 0, 0, 6) })
        }
        col.addView(board)

        col.addView(TextView(this).apply {
            text = if (currentInput.isEmpty()) "____" else currentInput.joinToString(" ")
            gravity = Gravity.CENTER; textSize = 20f; setPadding(0, 16, 0, 16)
        })
        col.addView(TextView(this).apply {
            text = if (gs.finished) "Game over" else "${gs.guessesLeft} guesses left"
            gravity = Gravity.CENTER
        })
        return col
    }

    private fun startNewGame() {
        AppState.gameSession = GameSession(secret = CodeBreakerLogic.newSecret())
        AppState.gameOverrideType = null
        currentInput.clear()
    }

    private fun submitGuess() {
        val gs = AppState.gameSession ?: return
        if (gs.finished || currentInput.size < 4) return
        gs.started = true
        val result = CodeBreakerLogic.score(currentInput.toList(), gs.secret)
        gs.guesses.add(Triple(currentInput.toList(), result.exact, result.partial))
        gs.guessesLeft--
        currentInput.clear()

        val cb = AppState.watchlist.find { it.name == "Code Breaker" }
        if (cb?.watched == true) cb.minutes += 1

        when {
            result.exact == 4 -> {
                gs.finished = true; AppState.winCount++
                AppState.gameOverrideType = "smug"; AppState.gameOverrideExpiresAt = System.currentTimeMillis() + 20 * 60 * 1000
                EventProcessor.process(this, "code_breaker_result", mapOf(
                    "outcome" to "win", "guessCount" to gs.guesses.size.toString(),
                    "exact" to result.exact.toString(), "partial" to result.partial.toString()
                ))
            }
            gs.guessesLeft == 0 -> {
                gs.finished = true; AppState.loseCount++
                AppState.gameOverrideType = "grumpy"; AppState.gameOverrideExpiresAt = System.currentTimeMillis() + 20 * 60 * 1000
                EventProcessor.process(this, "code_breaker_result", mapOf(
                    "outcome" to "loss", "guessCount" to gs.guesses.size.toString(),
                    "exact" to result.exact.toString(), "partial" to result.partial.toString()
                ))
            }
        }
        AppState.save(this)
        refreshFace(); renderTab()
    }

    // ---------- Calculator ----------

    private var calcTokens = mutableListOf<String>()
    private var calcCurrent = "0"; private var calcAcc: Double? = null; private var calcOp: String? = null; private var calcFresh = true

    private fun buildCalculatorDisplay(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 16, 0, 0) }
        val displayText = (calcTokens + calcCurrent).joinToString(" ")
        col.addView(TextView(this).apply { text = displayText; textSize = 26f; gravity = Gravity.CENTER; setPadding(16, 16, 16, 16) })
        return col
    }

    /** Was two completely separate digit grids, one per mode — same 0-9
     * buttons now, meaningfully larger than either previous version, with
     * only the action row beneath routed differently per mode. Operator
     * keys kept in a grid rather than one wide row — 7 buttons side by
     * side would overflow on a real phone width. */
    private fun buildSharedKeypad(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 16, 0, 0) }

        val digitGrid = GridLayout(this).apply { columnCount = 3 }
        for (d in 0..9) {
            digitGrid.addView(bigKeypadButton(d.toString()).apply {
                setOnClickListener {
                    if (playMode == "game") { if (currentInput.size < 4) currentInput.add(d) }
                    else calcPress(d.toString())
                    renderTab()
                }
            })
        }
        col.addView(digitGrid)

        if (playMode == "game") {
            val gs = AppState.gameSession
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            if (gs == null || !gs.finished) {
                actions.addView(bigKeypadButton("Clear").apply { setOnClickListener { currentInput.clear(); renderTab() } })
                actions.addView(bigKeypadButton("Guess").apply { setOnClickListener { submitGuess() } })
            } else {
                actions.addView(bigKeypadButton("New game").apply { setOnClickListener { startNewGame(); renderTab() } })
            }
            col.addView(actions)
        } else {
            val opGrid = GridLayout(this).apply { columnCount = 4 }
            listOf("+", "-", "*", "/", ".", "C", "=").forEach { op ->
                opGrid.addView(bigKeypadButton(op).apply { setOnClickListener { calcPress(op); renderTab() } })
            }
            col.addView(opGrid)
        }
        return col
    }

    private val opSymbols = mapOf("+" to "+", "-" to "−", "*" to "×", "/" to "÷")

    private fun calcPress(key: String) {
        when {
            key.toDoubleOrNull() != null || key == "." -> {
                calcCurrent = if (calcFresh) (if (key == ".") "0." else key) else calcCurrent + key
                calcFresh = false
            }
            key == "C" -> { calcTokens.clear(); calcCurrent = "0"; calcAcc = null; calcOp = null; calcFresh = true }
            key in listOf("+", "-", "*", "/") -> {
                val cur = calcCurrent.toDoubleOrNull() ?: 0.0
                calcAcc = if (calcOp != null) applyOp(calcAcc ?: 0.0, cur, calcOp!!) else cur
                calcTokens = mutableListOf(trimNum(calcAcc!!), opSymbols[key]!!)
                calcOp = key; calcCurrent = "0"; calcFresh = true
            }
            key == "=" -> {
                if (calcOp != null) {
                    val cur = calcCurrent.toDoubleOrNull() ?: 0.0
                    val result = applyOp(calcAcc ?: 0.0, cur, calcOp!!)
                    calcTokens.clear()
                    calcCurrent = trimNum(result)
                    calcAcc = null; calcOp = null; calcFresh = true
                }
            }
        }
    }
    private fun trimNum(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    private fun applyOp(a: Double, b: Double, op: String): Double = when (op) {
        "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> if (b == 0.0) Double.NaN else a / b; else -> b
    }

    // ---------- Tools: stopwatch, timers, alarms ----------

    private fun buildToolsTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

        col.addView(sectionTitle("Stopwatch"))
        val swDisplay = TextView(this).apply {
            text = fmtStopwatch(currentStopwatchMs()); textSize = 22f; gravity = Gravity.CENTER
        }
        stopwatchDisplayView = swDisplay
        col.addView(swDisplay)
        val swBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        swBtns.addView(Button(this).apply {
            text = if (AppState.stopwatchRunning) "Pause" else "Start" // was "Stop" — behavior already paused-not-reset, label was just wrong
            setOnClickListener {
                if (AppState.stopwatchRunning) {
                    AppState.stopwatchElapsedMs += System.currentTimeMillis() - AppState.stopwatchStartedAt
                    AppState.stopwatchRunning = false
                } else {
                    AppState.stopwatchStartedAt = System.currentTimeMillis()
                    AppState.stopwatchRunning = true
                    startStopwatchFastLoop()
                }
                AppState.save(this@MainActivity); renderTab()
            }
        })
        swBtns.addView(Button(this).apply {
            text = "Reset"
            setOnClickListener { AppState.stopwatchRunning = false; AppState.stopwatchElapsedMs = 0; AppState.stopwatchStartedAt = 0; AppState.save(this@MainActivity); renderTab() }
        })
        col.addView(swBtns)
        if (AppState.stopwatchRunning) startStopwatchFastLoop()

        col.addView(sectionTitle("Timers (name them, keep them)"))
        AppState.timers.forEachIndexed { i, tm -> col.addView(buildTimerRow(i, tm)) }
        if (AppState.timers.size < 5) {
            col.addView(Button(this).apply {
                text = "+ Add timer (${AppState.timers.size}/5)"
                setOnClickListener { AppState.timers.add(TimerSlot("Timer ${AppState.timers.size + 1}", 0, 0, false)); AppState.save(this@MainActivity); renderTab() }
            })
        }

        col.addView(sectionTitle("Alarms"))
        AppState.alarms.forEachIndexed { i, al -> col.addView(buildAlarmRow(i, al)) }

        if (AppState.ringingSource != null) {
            col.addView(Button(this).apply {
                text = "Dismiss — ${AppState.ringingSource} ringing"
                setOnClickListener { dismissRinging(); renderTab() }
            })
        }
        return col
    }

    private fun buildTimerRow(i: Int, tm: TimerSlot): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 12) }
        val nameField = EditText(this).apply {
            setText(tm.name)
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { tm.name = text.toString().ifBlank { "Timer ${i+1}" }; AppState.save(this@MainActivity) } }
        }
        row.addView(nameField)
        row.addView(TextView(this).apply { text = fmtMs(tm.remainingMs); textSize = 18f; gravity = Gravity.CENTER })

        if (!tm.running) {
            val adj = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            listOf(1L to "+1m", 5L to "+5m", 10L to "+10m", 20L to "+20m").forEach { (mins, label) ->
                adj.addView(Button(this).apply { text = label; setOnClickListener {
                    tm.remainingMs = (tm.remainingMs + mins * 60000).coerceAtLeast(0); tm.totalMs = tm.remainingMs; AppState.save(this@MainActivity); renderTab()
                } })
            }
            row.addView(adj)
            val sub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            listOf(1L to "-1m", 5L to "-5m", 10L to "-10m", 20L to "-20m").forEach { (mins, label) ->
                sub.addView(Button(this).apply { text = label; setOnClickListener {
                    tm.remainingMs = (tm.remainingMs - mins * 60000).coerceAtLeast(0); tm.totalMs = tm.remainingMs; AppState.save(this@MainActivity); renderTab()
                } })
            }
            row.addView(sub)
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            actions.addView(Button(this).apply { text = "Start"; isEnabled = tm.remainingMs > 0; setOnClickListener { tm.running = true; AppState.save(this@MainActivity); renderTab() } })
            actions.addView(Button(this).apply { text = "Reset"; setOnClickListener { tm.remainingMs = 0; tm.totalMs = 0; AppState.save(this@MainActivity); renderTab() } })
            row.addView(actions)
        } else {
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            actions.addView(Button(this).apply {
                text = "Pause"
                setOnClickListener { tm.running = false; AppState.save(this@MainActivity); renderTab() } // leaves remainingMs alone — Cancel is the one that zeroes it
            })
            actions.addView(Button(this).apply { text = "Cancel"; setOnClickListener { tm.running = false; tm.remainingMs = 0; AppState.save(this@MainActivity); renderTab() } })
            row.addView(actions)
        }
        return row
    }

    /** Parses "HH:MM", returns a human "in Xh Ym" / "in Ym" string for how
     * long until that time next occurs (today if still ahead, tomorrow if
     * it's already passed). Returns null if the string won't parse — the
     * free-text time field can still hold something malformed until the
     * real picker replaces it. */
    private fun timeUntilAlarm(time: String): String? {
        val parts = time.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null

        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        if (target.before(now)) target.add(java.util.Calendar.DAY_OF_YEAR, 1)

        val diffMin = (target.timeInMillis - now.timeInMillis) / 60000
        val hrs = diffMin / 60; val mins = diffMin % 60
        return if (hrs > 0) "in ${hrs}h ${mins}m" else "in ${mins}m"
    }

    private fun buildAlarmRow(i: Int, al: AlarmSlot): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 8) }
        val toggleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // Was a single Switch — real reported bug alongside the UI request:
        // edits to name/time never called renderTab(), so the screen could
        // look like nothing had taken even though AppState was updated
        // correctly underneath. Every mutation below now re-renders
        // immediately, which is the actual fix, not just the button swap.
        toggleRow.addView(Button(this).apply {
            text = "ON"
            alpha = if (al.enabled) 1f else 0.4f
            setOnClickListener {
                if (!al.enabled) {
                    al.enabled = true
                    if (AppState.appTracker) EventProcessor.process(this@MainActivity, "setting_changed", mapOf("label" to "${al.name} (${al.time})", "state" to "ON"))
                    AppState.save(this@MainActivity); renderTab()
                }
            }
        })
        toggleRow.addView(Button(this).apply {
            text = "OFF"
            alpha = if (!al.enabled) 1f else 0.4f
            setOnClickListener {
                if (al.enabled) {
                    al.enabled = false
                    if (AppState.appTracker) EventProcessor.process(this@MainActivity, "setting_changed", mapOf("label" to "${al.name} (${al.time})", "state" to "OFF"))
                    AppState.save(this@MainActivity); renderTab()
                }
            }
        })
        row.addView(toggleRow)

        row.addView(EditText(this).apply {
            setText(al.name)
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { al.name = text.toString().ifBlank { "Alarm ${i+1}" }; AppState.save(this@MainActivity); renderTab() } }
        })
        row.addView(EditText(this).apply {
            setText(al.time)
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { al.time = text.toString(); al.firedKey = null; AppState.save(this@MainActivity); renderTab() } }
        })

        if (al.enabled) {
            timeUntilAlarm(al.time)?.let { until ->
                row.addView(TextView(this).apply { text = until; textSize = 12f; alpha = 0.7f })
            }
        }
        return row
    }

    private fun fmtMs(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format("%d:%02d", total / 60, total % 60)
    }

    private fun currentStopwatchMs(): Long =
        AppState.stopwatchElapsedMs + if (AppState.stopwatchRunning) System.currentTimeMillis() - AppState.stopwatchStartedAt else 0

    private fun fmtStopwatch(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val h = total / 3600000; val m = (total / 60000) % 60; val s = (total / 1000) % 60; val millis = total % 1000
        return String.format("%02d:%02d:%02d.%03d", h, m, s, millis)
    }

    private var stopwatchDisplayView: TextView? = null
    private val stopwatchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stopwatchLoopRunning = false

    /** Runs at ~30fps only while the stopwatch is actually running and the
     * Tools tab is visible — updates just the one TextView, never rebuilds
     * the tab, so milliseconds can refresh smoothly without the flicker or
     * cost of a full re-render 30x a second. */
    private fun startStopwatchFastLoop() {
        if (stopwatchLoopRunning) return
        stopwatchLoopRunning = true
        val runnable = object : Runnable {
            override fun run() {
                if (activityDestroyed || !AppState.stopwatchRunning || activeTab != "tools") { stopwatchLoopRunning = false; return }
                stopwatchDisplayView?.text = fmtStopwatch(currentStopwatchMs())
                stopwatchHandler.postDelayed(this, 33)
            }
        }
        stopwatchHandler.post(runnable)
    }

    private fun dismissRinging() {
        AppState.ringingSource = null; AppState.ringingLabel = null
        AppState.save(this)
        stopAlarmSound()
        refreshFace()
    }

    /** Custom picked file if set (any format MediaPlayer supports — mp3,
     * wav, ogg, m4a all work; that's genuinely "any audio file" for
     * practical purposes). Falls back to the system's own default alarm
     * sound via RingtoneManager if nothing's been chosen in Settings. */
    private var alarmMediaPlayer: android.media.MediaPlayer? = null
    private fun startAlarmSound() {
        stopAlarmSound()
        try {
            val uriString = AppState.alarmSoundUri
            val uri = if (uriString != null) android.net.Uri.parse(uriString)
                else android.media.RingtoneManager.getActualDefaultRingtoneUri(this, android.media.RingtoneManager.TYPE_ALARM)
                    ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val mp = android.media.MediaPlayer()
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.prepare()
            mp.start()
            alarmMediaPlayer = mp
        } catch (e: Exception) {
            // A bad/unreadable file shouldn't crash the ring — it just rings silently, which is
            // still visible (the face state) even if audio fails for some reason on this device.
        }
    }
    private fun stopAlarmSound() {
        try { alarmMediaPlayer?.stop(); alarmMediaPlayer?.release() } catch (e: Exception) {}
        alarmMediaPlayer = null
    }

    /** Runs only while the app is in the foreground — real background
     * firing (app closed) needs AlarmManager-scheduled exact wakeups per
     * timer/alarm, which is a genuine follow-up build, not implemented
     * here yet. Flagging honestly rather than pretending this covers it. */
    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun startForegroundTickLoop() {
        tickHandler.postDelayed({
            if (activityDestroyed) return@postDelayed // was previously unconditional — this is the actual fix
            var changed = false

            // Notice if WidgetTapReceiver cleared ringingSource externally
            // (a widget-tap dismiss) while sound is still playing here —
            // that receiver can't reach into this Activity's MediaPlayer
            // directly, so this is the actual stop-the-sound side of that.
            if (AppState.ringingSource == null && alarmMediaPlayer != null) {
                stopAlarmSound()
                changed = true
            }

            AppState.timers.forEach { tm ->
                if (tm.running) {
                    tm.remainingMs -= 1000
                    changed = true
                    if (tm.remainingMs <= 0) {
                        tm.running = false; tm.remainingMs = 0
                        AppState.ringingSource = "timer"; AppState.ringingLabel = tm.name
                        EventProcessor.process(this, "timer_fired", mapOf("name" to tm.name))
                        startAlarmSound()
                    }
                }
            }
            val cal = java.util.Calendar.getInstance()
            val hhmm = String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
            val dayKey = "${cal.get(java.util.Calendar.DAY_OF_YEAR)}-$hhmm"
            AppState.alarms.forEach { al ->
                if (al.enabled && al.time == hhmm && al.firedKey != dayKey) {
                    al.firedKey = dayKey; changed = true
                    AppState.ringingSource = "alarm"; AppState.ringingLabel = al.name
                    EventProcessor.process(this, "alarm_fired", mapOf("name" to al.name, "time" to al.time))
                    startAlarmSound()
                }
            }
            if (changed) { AppState.save(this); refreshFace(); if (activeTab == "tools") renderTab() }
            startForegroundTickLoop()
        }, 1000)
    }

    // ---------- Settings ----------

    private fun buildSettingsTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val trackerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        trackerRow.addView(Switch(this).apply {
            isChecked = AppState.appTracker
            setOnCheckedChangeListener { _, checked ->
                AppState.appTracker = checked
                // Always fires, regardless of appTracker's own value — the
                // toggle's own state must always be on record. EventProcessor
                // doesn't gate on appTracker itself, so this is correct as-is.
                EventProcessor.process(this@MainActivity, "setting_changed", mapOf("label" to "App Tracker", "state" to if (checked) "ON" else "OFF"))
                AppState.save(this@MainActivity)
            }
        })
        trackerRow.addView(TextView(this).apply { text = "App Tracker — logs setting changes while on" })
        col.addView(trackerRow)

        col.addView(sectionTitle("Widget clock style"))
        col.addView(choiceRow(listOf("analog", "digital"), AppState.widgetClockStyle) { AppState.widgetClockStyle = it; AppState.save(this); refreshFace(); renderTab() })

        col.addView(sectionTitle("Widget theme"))
        col.addView(choiceRow(listOf("light", "dark", "colorpop"), AppState.widgetTheme) { AppState.widgetTheme = it; AppState.save(this); refreshFace(); renderTab() })

        col.addView(sectionTitle("Widget border"))
        col.addView(choiceRow(listOf("gold", "metallic_green", "black", "white"), AppState.widgetBorderColor) {
            AppState.widgetBorderColor = it; AppState.save(this)
            com.halfwake.pocket.widget.HalfwakeWidgetProvider.updateAll(this) // otherwise the change wouldn't show on the home screen until the next ~1min auto-refresh
            renderTab()
        })

        col.addView(sectionTitle("App theme"))
        col.addView(choiceRow(listOf("light", "dark", "colorpop"), AppState.appTheme) { AppState.appTheme = it; AppState.save(this); refreshWindowTheme(); renderTab() })

        col.addView(sectionTitle("Diary update frequency"))
        col.addView(choiceRow(listOf("15", "30", "60"), AppState.updateIntervalMinutes.toString()) {
            AppState.updateIntervalMinutes = it.toInt(); AppState.save(this); TickWorker.ensureScheduled(this, AppState.updateIntervalMinutes.toLong()); renderTab()
        })

        col.addView(sectionTitle("Alarm & timer sound"))
        col.addView(TextView(this).apply { text = soundFileLabel() })
        col.addView(Button(this).apply {
            text = "Choose sound file"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*" }
                soundPickerLauncher.launch(intent)
            }
        })

        col.addView(sectionTitle("Tracked apps"))
        AppState.watchlist.forEach { app ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Switch(this).apply {
                isChecked = app.watched
                setOnCheckedChangeListener { _, checked ->
                    app.watched = checked
                    if (AppState.appTracker) EventProcessor.process(this@MainActivity, "setting_changed", mapOf("label" to "${app.name} tracking", "state" to if (checked) "ON" else "OFF"))
                    AppState.save(this@MainActivity)
                }
            })
            val label = if (app.packageName.isNotBlank()) "${app.name} (${app.minutes}m today)" else "${app.name} (${app.minutes}m, in-app)"
            row.addView(TextView(this).apply { text = label })
            if (app.packageName.isNotBlank()) {
                row.addView(Button(this).apply {
                    text = "Remove"
                    setOnClickListener { AppState.watchlist.remove(app); AppState.save(this@MainActivity); renderTab() }
                })
            }
            col.addView(row)
        }
        col.addView(Button(this).apply {
            text = "+ Add tracked app"
            setOnClickListener { showAddAppDialog() }
        })

        return col
    }

    /** Real installed, launchable apps — not a guessed name list. Picking
     * one links its actual package name, which is what WatchStatsWorker
     * needs to read genuine usage minutes for it. */
    private fun showAddAppDialog() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName } // don't let Halfwake track itself
            .sortedBy { it.second }

        val labels = apps.map { it.second }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add tracked app")
            .setItems(labels) { _, index ->
                val (pkg, label) = apps[index]
                if (AppState.watchlist.none { it.packageName == pkg }) {
                    AppState.watchlist.add(WatchedApp(label, true, 0, pkg))
                    AppState.save(this)
                    if (AppState.appTracker) LogStore.appendTruth(this, "setting", "Setting changed: $label added to tracked apps.", "setting_on")
                    WatchStatsWorker.runOnce(this)
                    renderTab()
                }
            }
            .show()
    }

    private fun choiceRow(options: List<String>, current: String, onPick: (String) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEach { opt ->
            row.addView(Button(this).apply {
                text = opt
                alpha = if (opt == current) 1f else 0.5f
                setOnClickListener { onPick(opt) }
            })
        }
        return row
    }

    // ---------- Log ----------

    private fun buildLogTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filterRow.addView(CheckBox(this).apply { text = "Core"; isChecked = LogFilters.log; setOnCheckedChangeListener { _, c -> LogFilters.log = c; AppState.save(this@MainActivity); renderTab() } })
        filterRow.addView(CheckBox(this).apply { text = "Flair"; isChecked = LogFilters.personality; setOnCheckedChangeListener { _, c -> LogFilters.personality = c; AppState.save(this@MainActivity); renderTab() } })
        filterRow.addView(CheckBox(this).apply { text = "Tech"; isChecked = LogFilters.tech; setOnCheckedChangeListener { _, c -> LogFilters.tech = c; AppState.save(this@MainActivity); renderTab() } })
        col.addView(filterRow)

        val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        LogStore.filtered(this).reversed().forEach { e ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (AppState.appTheme == "colorpop") {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(8, LinearLayout.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(Moods.colorOf(e.category))
                })
            }
            row.addView(TextView(this).apply {
                text = "${fmt.format(Date(e.atMillis))} — ${e.category}-${e.layer}\n${e.text}"
                setPadding(16, 12, 0, 12)
            })
            col.addView(row)
        }

        col.addView(sectionTitle("Copy for a bug report — nothing is sent automatically"))
        val copyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("All" to null, "Core" to "log", "Flair" to "personality", "Tech" to "tech").forEach { (label, layer) ->
            copyRow.addView(Button(this).apply {
                text = "Copy $label"
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Halfwake log", LogStore.formatForCopy(this@MainActivity, layer)))
                    Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
                }
            })
        }
        col.addView(copyRow)
        return col
    }
}
