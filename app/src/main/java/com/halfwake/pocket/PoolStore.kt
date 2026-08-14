package com.halfwake.pocket

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Pool is the actual ledger now, not a staging area. Every event that
 * matters publishes ONE entry here — raw facts only, no prose, no
 * interpretation. Truth, Tech, and Flair each independently check/pull/
 * confirm against the SAME entry. None of them ever call each other.
 *
 * This is the generalized fix for a bug class already found once this
 * session: two functions independently re-deriving "the same" value from
 * live state and silently disagreeing because state moved between calls.
 * Publishing once and letting every reader pull the identical snapshot
 * makes that class of drift structurally impossible here.
 *
 * Kept for 1 day (v5.1 — was permanent/unbounded, changed after review:
 * Pool's real job is short-term Truth/Tech/Flair cross-checking, not
 * long-term browsing, so a short window is genuinely enough). This is
 * still what the copy/bug-report feature can read directly, and still
 * what lets someone notice "Flair said X but Tech said Y for the same
 * entry" — just within a shorter window than the permanent Log below.
 */
data class PoolEntry(
    val id: String,
    val eventType: String,              // "mood_tick" | "code_breaker_result" | "timer_fired" | "alarm_fired" | "setting_changed"
    val atMillis: Long,
    val data: Map<String, String>,      // raw facts only — nothing rendered, nothing interpreted
    val confirmedBy: MutableSet<String> // "truth" | "tech" | "flair", tracked for visibility, not for cleanup
)

object PoolStore {
    private const val FILE_NAME = "pool.json"

    // Kept short on purpose: Pool's job is short-term Truth/Tech/Flair
    // cross-checking, not long-term browsing — that's what Log is for.
    // Time-based, not count-based — a raw entry cap doesn't map cleanly
    // to "how many days of history," and usage volume varies too much
    // for a fixed count to mean the same thing across different users.
    private const val RETENTION_MS = 1L * 24 * 60 * 60 * 1000 // 1 day

    // v5.1: was reading + fully re-parsing the whole file on every single
    // check()/pull()/confirm() call — 10 reads + 4 writes per one event.
    // Cached once per process now; only actual writes touch disk.
    private var cache: MutableList<PoolEntry>? = null

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun cached(context: Context): MutableList<PoolEntry> {
        cache?.let { return it }
        val f = file(context)
        val loaded = if (!f.exists()) mutableListOf() else try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val dataObj = o.getJSONObject("data")
                val dataMap = dataObj.keys().asSequence().associateWith { dataObj.getString(it) }
                val confirmedArr = o.getJSONArray("confirmedBy")
                val confirmed = (0 until confirmedArr.length()).map { confirmedArr.getString(it) }.toMutableSet()
                PoolEntry(o.getString("id"), o.getString("eventType"), o.getLong("atMillis"), dataMap, confirmed)
            }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
        cache = loaded
        return loaded
    }

    private fun persist(context: Context) {
        val entries = cache ?: return
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        entries.removeAll { it.atMillis < cutoff }
        try {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject().apply {
                    put("id", e.id); put("eventType", e.eventType); put("atMillis", e.atMillis)
                    put("data", JSONObject(e.data))
                    put("confirmedBy", JSONArray(e.confirmedBy.toList()))
                })
            }
            file(context).writeText(arr.toString())
        } catch (e: Exception) {
            // Disk write failed — cache still holds the correct in-memory
            // state for the rest of this process's life, so nothing is
            // lost yet; only this one persist-to-disk attempt was skipped.
        }
    }

    /** One publish per real event. Returns the id every subsequent
     * check/pull/confirm call uses. */
    fun publish(context: Context, eventType: String, data: Map<String, String>): String {
        val id = UUID.randomUUID().toString()
        cached(context).add(PoolEntry(id, eventType, System.currentTimeMillis(), data, mutableSetOf()))
        persist(context)
        return id
    }

    fun check(context: Context, id: String): Boolean = cached(context).any { it.id == id }

    fun pull(context: Context, id: String): PoolEntry? = cached(context).find { it.id == id }

    fun confirm(context: Context, id: String, consumer: String) {
        cached(context).find { it.id == id }?.confirmedBy?.add(consumer)
        persist(context)
    }

    fun all(context: Context): List<PoolEntry> = cached(context)
}
