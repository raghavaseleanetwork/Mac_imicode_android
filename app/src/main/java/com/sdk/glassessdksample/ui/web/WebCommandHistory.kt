package com.sdk.glassessdksample.ui.web

import android.content.Context
import org.json.JSONArray

/**
 * Remembers the commands the user has run in the Web section, so repeating a
 * task is a tap instead of retyping or re-dictating a sentence.
 *
 * Deliberately plain: a small JSON array in SharedPreferences, newest first,
 * capped. Commands are the user's own words about their own browsing, so they
 * never leave the device.
 */
object WebCommandHistory {

    private const val PREFS = "web_browser_prefs"
    private const val KEY = "command_history"
    private const val MAX_ENTRIES = 20

    /** Newest first. */
    fun recent(context: Context, limit: Int = MAX_ENTRIES): List<String> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                .take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Records [command], moving a repeat back to the top rather than duplicating it. */
    fun add(context: Context, command: String) {
        val text = command.trim()
        if (text.isEmpty()) return

        val updated = buildList {
            add(text)
            addAll(recent(context).filterNot { it.equals(text, ignoreCase = true) })
        }.take(MAX_ENTRIES)

        prefs(context).edit()
            .putString(KEY, JSONArray(updated).toString())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
