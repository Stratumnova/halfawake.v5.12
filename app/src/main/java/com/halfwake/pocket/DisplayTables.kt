package com.halfwake.pocket

import android.graphics.Color

data class FaceParams(val brow: Float, val eyeOpen: Float, val mouth: String, val decor: String = "none", val xEyes: Boolean = false)
data class ThemeColors(val bg: Int, val fg: Int, val muted: Int, val accent: Int)

/** MOODS and FACES must always share an identical key set — this was a
 * real crash once (an "alarm" mood existed in one table but not the
 * other) and is now a standing rule, not just a preference. */
object Moods {
    private val colors: Map<String, Int> = mapOf(
        Mood.CRITICAL.id to Color.parseColor("#e53935"),
        Mood.BURNING.id to Color.parseColor("#ef6c00"),
        Mood.GROGGY.id to Color.parseColor("#9575cd"),
        Mood.FED.id to Color.parseColor("#43a047"),
        Mood.HAPPY.id to Color.parseColor("#ffca28"),
        Mood.SAD.id to Color.parseColor("#5c6bc0"),
        Mood.TIRED.id to Color.parseColor("#8e6bbf"),
        Mood.BUSY.id to Color.parseColor("#ff7043"),
        Mood.QUIET.id to Color.parseColor("#3949ab"),
        Mood.RESTLESS.id to Color.parseColor("#7c4dff"),
        Mood.CONTENT.id to Color.parseColor("#7cb342"),
        "smug" to Color.parseColor("#ffb300"),
        "grumpy" to Color.parseColor("#d84315"),
        "alarm" to Color.parseColor("#e53935"),
        "countdown" to Color.parseColor("#fb8c00"),
        "startled" to Color.parseColor("#ffa726"),
    )
    fun colorOf(moodKey: String): Int = colors[moodKey] ?: colors[Mood.CONTENT.id]!!
}

object Faces {
    private val params: Map<String, FaceParams> = mapOf(
        Mood.CRITICAL.id to FaceParams(-0.5f, 1.1f, "o", "sweat"),
        Mood.BURNING.id to FaceParams(-0.7f, 0.6f, "frown", "heat"),
        Mood.GROGGY.id to FaceParams(-0.1f, 0.35f, "flat", "zzz"),
        Mood.FED.id to FaceParams(0.35f, 1.0f, "smile", "spark"),
        Mood.HAPPY.id to FaceParams(0.5f, 1.0f, "bigsmile", "sparkle"),
        Mood.SAD.id to FaceParams(-0.4f, 0.85f, "frown", "tear"),
        Mood.TIRED.id to FaceParams(-0.15f, 0.3f, "flatfrown", "bags"),
        Mood.BUSY.id to FaceParams(0.15f, 1.0f, "neutral", "motion"),
        Mood.QUIET.id to FaceParams(0.05f, 0.05f, "flatsmall", "moon"),
        Mood.RESTLESS.id to FaceParams(-0.25f, 1.05f, "wavy", "squiggle"),
        Mood.CONTENT.id to FaceParams(0.2f, 1.0f, "gentlesmile"),
        "smug" to FaceParams(0.15f, 0.85f, "smirk"),
        "grumpy" to FaceParams(-0.6f, 0.7f, "frown", "cloud"),
        "alarm" to FaceParams(-0.6f, 1.0f, "sour", xEyes = true),
        "countdown" to FaceParams(-0.3f, 1.3f, "o"),
        "startled" to FaceParams(0.4f, 1.5f, "o"),
    )
    fun of(moodKey: String): FaceParams = params[moodKey] ?: params[Mood.CONTENT.id]!!
}

object Themes {
    private val palettes: Map<String, ThemeColors> = mapOf(
        "light" to ThemeColors(Color.parseColor("#f4f1ec"), Color.parseColor("#23211d"), Color.parseColor("#8a8578"), Color.parseColor("#6b7a5e")),
        "dark" to ThemeColors(Color.parseColor("#1e2126"), Color.parseColor("#f4f1ec"), Color.parseColor("#9aa0a8"), Color.parseColor("#7a92a8")),
        "colorpop" to ThemeColors(Color.parseColor("#fff8f0"), Color.parseColor("#1a1a1a"), Color.parseColor("#7a4a9e"), Color.parseColor("#e0457b")),
    )
    fun of(name: String): ThemeColors = palettes[name] ?: palettes["light"]!!
}

/** Widget-only — so the widget doesn't blend into whatever wallpaper it
 * sits on. Never applied to the in-app live view, which already has its
 * own defined background. */
object BorderColors {
    private val colors: Map<String, Int> = mapOf(
        "gold" to Color.parseColor("#d4af37"),
        "metallic_green" to Color.parseColor("#4a7a5e"),
        "black" to Color.parseColor("#1a1a1a"),
        "white" to Color.parseColor("#f5f5f5"),
    )
    fun of(name: String): Int = colors[name] ?: colors["gold"]!!
}

/** One color per alarm slot (index 0-4), deliberately chosen to stay
 * distinct from every BorderColors option above so an arrow never
 * disappears into the border it's sitting on. */
object AlarmArrowColors {
    val colors = listOf(
        Color.parseColor("#e53935"), // Alarm 1 — red
        Color.parseColor("#1e88e5"), // Alarm 2 — blue
        Color.parseColor("#43a047"), // Alarm 3 — vivid green (kept clearly apart from the metallic-green border option)
        Color.parseColor("#fb8c00"), // Alarm 4 — orange
        Color.parseColor("#8e24aa"), // Alarm 5 — purple
    )
}

/** Was a fixed theme.fg/theme.bg pair for every numeral regardless of
 * what mood color was actually behind it — real contrast bug, got worse
 * after Round 1's more saturated palette. This picks black or white
 * based on the actual color sitting behind each numeral, always. */
object Contrast {
    private val darkInk = Color.parseColor("#1a1a1a")
    private val lightInk = Color.parseColor("#f5f5f5")
    fun of(bg: Int): Int {
        val luminance = 0.299 * Color.red(bg) / 255.0 + 0.587 * Color.green(bg) / 255.0 + 0.114 * Color.blue(bg) / 255.0
        return if (luminance > 0.55) darkInk else lightInk
    }
    /** The mood fill isn't one flat color — it's solid on one half, faded
     * (30% alpha over the theme background) on the other. This blends the
     * two so contrast can be computed against what's actually visible. */
    fun blend(bg: Int, fg: Int, alpha: Float): Int {
        val r = (Color.red(bg) * (1 - alpha) + Color.red(fg) * alpha).toInt()
        val g = (Color.green(bg) * (1 - alpha) + Color.green(fg) * alpha).toInt()
        val b = (Color.blue(bg) * (1 - alpha) + Color.blue(fg) * alpha).toInt()
        return Color.rgb(r, g, b)
    }
}

