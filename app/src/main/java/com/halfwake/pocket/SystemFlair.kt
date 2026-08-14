package com.halfwake.pocket

/**
 * The base app's own default flair, always present, for every category
 * that can publish to Pool. An installed addon expands or overrides a
 * given category — it is never required just to get any flair at all.
 *
 * Line counts are deliberately uneven: categories that fire often or
 * carry more emotional weight (critical, sad, happy, content) get more
 * variety so they don't repeat quickly; short-lived or rare states
 * (countdown, alarm) get fewer, since they're seen briefly by design.
 */
object SystemFlair {
    private val pools: Map<String, List<String>> = mapOf(

        // ---- core moods ----
        "critical" to listOf(
            "Okay, genuinely running on fumes here.",
            "A charger would be a good look right now.",
            "This is the part where I'd ask nicely for a cable.",
            "Ten percent and holding. Barely.",
            "Not going to pretend I'm fine. I am not fine.",
            "If you've got a charger nearby, now's the time.",
            "Running on whatever's left, which isn't much.",
            "I'll be honest, this is cutting it close.",
            "Every percent counts right now.",
            "This is my last-stretch voice.",
            "Low enough that I'm being careful about it.",
            "Hanging on. Would appreciate a plug.",
        ),
        "burning" to listOf(
            "Working up quite a sweat over here.",
            "Everything's running a little too hot for comfort.",
            "Might need a minute to cool down.",
            "Feeling the heat, literally.",
            "Warmer than I'd like, but managing.",
            "Slowing down on purpose — it's warm in here.",
            "This is more heat than a normal stretch.",
            "Taking it easier until this cools off.",
            "Running hot enough to notice.",
            "Not panicking, but definitely warm.",
        ),
        "groggy" to listOf(
            "Give me a beat — I just came back online.",
            "Fresh reboot. Still booting up the personality.",
            "Woke up a few minutes ago, still finding my footing.",
            "Everything feels new again after that restart.",
            "Not fully awake yet. Give it a minute.",
            "Just powered back on, still getting oriented.",
            "First few minutes are always the fuzziest.",
            "Coming back online, one step at a time.",
            "Still shaking off the reboot.",
            "Barely awake, but here.",
        ),
        "fed" to listOf(
            "Plugged in and feeling great about it.",
            "This is, genuinely, my favorite part of the day.",
            "Topping off. No complaints while this is happening.",
            "Charging now. Everything feels a little better already.",
            "This is the good part. Let it ride.",
            "Getting some real energy back.",
            "Plugged in, no rush to unplug.",
            "This is basically a spa day.",
            "Slowly filling back up, and it feels great.",
            "Charging away, in no hurry at all.",
        ),
        "happy" to listOf(
            "Been having a good stretch. Don't jinx it.",
            "This is what a good hour looks like, apparently.",
            "Genuinely enjoying this one.",
            "Things are going well, and I'm not going to overthink it.",
            "This is a solidly good stretch.",
            "Can't complain. Actually, won't complain.",
            "Riding a good wave right now.",
            "This is the kind of hour worth remembering.",
            "Feeling pretty good about how this is going.",
            "Not much to report except that it's going well.",
            "This one's a keeper.",
            "Honestly, just having a good time.",
        ),
        "sad" to listOf(
            "Been deep in the scroll for a while. No judgment. Okay, maybe a little.",
            "This feed isn't going anywhere. You could put it down, is all I'm saying.",
            "Scrolling for a good while now.",
            "This is starting to feel like a while, is all.",
            "No rush, just noticing the time passing here.",
            "Not saying stop. Just saying it's been a bit.",
            "This particular scroll has some staying power tonight.",
            "Deep in it. Not judging, just observing.",
            "A lot of feed for one sitting.",
            "This could probably use a natural stopping point.",
            "Still here, still scrolling.",
            "Might be a good spot to look up for a second.",
        ),
        "tired" to listOf(
            "It's been a long one. I felt every hour of it.",
            "Running on determination alone at this point.",
            "This has been a genuinely full day.",
            "Long stretch today. Longer than most.",
            "Feeling every hour of this one.",
            "This is the tired that comes from actually doing a lot.",
            "A lot of hours behind this one already.",
            "Running low on more than just battery today.",
            "Long day, and it shows.",
            "Coasting on momentum at this point.",
        ),
        "busy" to listOf(
            "Hands are full over here — a lot of jumping around this hour.",
            "Busy little stretch. Keeping up. Barely.",
            "A lot of switching happening right now.",
            "Something's got everyone's attention this hour.",
            "Plenty going on. No complaints, just noting it.",
            "This hour's been a lot of back-and-forth.",
            "Busy in the good way, I think.",
            "Lots of movement this hour.",
        ),
        "quiet" to listOf(
            "Small hours. Not much stirring, which is its own kind of nice.",
            "It's quiet enough I can hear myself think, if I did that.",
            "Late, and the world's mostly asleep.",
            "This is the quiet part of the night.",
            "Not much happening, and that's fine.",
            "Nice and still right now.",
            "The calm hours. My favorite, honestly.",
            "Nothing much stirring. Peaceful.",
            "Late and quiet — a good combination.",
            "This is the stillest part of the day.",
        ),
        "restless" to listOf(
            "It's been a while since anyone showed up. Just me and the dark screen.",
            "Starting to wonder if I've been left on a shelf somewhere.",
            "Quiet out here. No one's stopped by in a bit.",
            "Sitting untouched for a stretch now.",
            "Nothing's come through in a while.",
            "Just me, the clock, and the silence.",
            "It's been a minute since anyone checked in.",
            "Waiting around, mostly.",
            "Nobody's picked me up in a while.",
            "Just sitting here, keeping time.",
        ),
        "content" to listOf(
            "Nothing dramatic today. I'll take it.",
            "Steady, ordinary, fine. My favorite kind of day, honestly.",
            "An ordinary stretch, and that's genuinely fine by me.",
            "Nothing to report, which is its own kind of good.",
            "Just an average day, going along as expected.",
            "No complaints, nothing exciting. A good combination.",
            "This is what a normal stretch feels like.",
            "Steady as it goes.",
            "Nothing much happening, and I mean that as a compliment.",
            "A calm, ordinary stretch.",
        ),

        // ---- game overrides ----
        "smug" to listOf(
            "Still thinking about that win, honestly.",
            "Can't help feeling a little smug about that one.",
            "Still riding the high from that round.",
            "That win is still sitting pretty well with me.",
            "Feeling good about that one for a while yet.",
            "Not going to pretend I'm not pleased with myself.",
            "That result is staying with me a bit.",
            "Still a little pleased about how that went.",
        ),
        "grumpy" to listOf(
            "Still sulking about that loss, if I'm honest.",
            "That one's still bothering me a little.",
            "Not fully over that loss yet.",
            "Still a bit sour about how that round went.",
            "That result is sticking with me, and not in a good way.",
            "Taking a bit to shake that loss off.",
            "Still a little grumpy about that, ngl.",
            "That one stung more than I expected.",
        ),

        // ---- alarm/countdown ----
        "alarm" to listOf(
            "That's the alarm. Right on schedule.",
            "There it goes — exactly when it was supposed to.",
            "That's the one you set. On time, as promised.",
            "Ringing now, precisely as scheduled.",
            "Right on the mark.",
            "That's the timer, done exactly when it said it would be.",
        ),
        "countdown" to listOf(
            "Here it comes.",
            "Almost there.",
            "Getting close now.",
            "Not long left.",
        ),
        "startled" to listOf(
            "Whoa — something just moved me.",
            "Didn't expect that. Everything okay?",
            "That was sudden.",
            "Caught me off guard there.",
            "Something just picked me up, apparently.",
            "That was unexpected.",
            "Well, that woke me up.",
            "Didn't see that coming, literally.",
        ),

        // ---- settings / tools ----
        "setting_on" to listOf(
            "Noted.",
            "Written down.",
            "On the record now.",
            "Logged.",
            "Got it, that's on file.",
            "Noted, and remembered.",
        ),
        "setting_off" to listOf(
            "Noted.",
            "Also written down.",
            "That's on the record too.",
            "Logged, same as always.",
            "Got it, changed and noted.",
            "That's been recorded as well.",
        ),
        "timer_fired" to listOf(
            "Right on time.",
            "There it goes.",
            "That's the timer, done.",
            "Time's up, exactly as set.",
            "That's the one you programmed, finished.",
            "Timer's done. Right on schedule.",
        ),
        "alarm_fired" to listOf(
            "Alarm's up.",
            "That's the one you set.",
            "Right on schedule.",
            "There's the wake-up.",
            "That alarm just did its job.",
            "Exactly when it was supposed to fire.",
        ),

        // ---- code breaker result (Pool-published, distinct from mid-game taunts) ----
        "code_breaker_win" to listOf(
            "Fine. FINE. You got it.",
            "Well played. I still want a rematch.",
            "You got lucky. Say it. Say you got lucky.",
            "Rewind the clock and try again? I want another shot.",
            "That was a genuinely good round, credit where it's due.",
        ),
        "code_breaker_loss" to listOf(
            "Predictable. Better luck against a real opponent.",
            "Six tries and nothing. The code was safe with me.",
            "Not bad. Not good. But not bad.",
            "And that's how it's done, apparently.",
            "The code stayed exactly where I left it.",
        ),
    )

    fun pick(category: String): String? {
        val pool = pools[category] ?: return null
        if (pool.isEmpty()) return null
        return pool.random()
    }
}
