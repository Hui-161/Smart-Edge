package com.imi.smartedge.sidebar.panel

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps a local, on-device history of copied text (similar to Samsung's Edge clipboard).
 *
 * Android 10+ only allows reading the clipboard while the app has input focus
 * (e.g. while the side panel overlay is open) or when the READ_CLIPBOARD app-op
 * was granted via Shizuku/Root. Entries are stored only in private SharedPreferences.
 */
object ClipboardHistoryManager {

    private const val TAG = "ClipboardHistory"
    private const val PREFS_NAME = "clipboard_history_prefs"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_LAST_CLIP_TIMESTAMP = "last_clip_timestamp"
    private const val MAX_UNPINNED_ENTRIES = 25
    private const val MAX_TEXT_LENGTH = 5000

    data class Entry(val text: String, val time: Long, val pinned: Boolean)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getEntries(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Entry(obj.getString("text"), obj.optLong("time"), obj.optBoolean("pinned"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse clipboard history", e)
            emptyList()
        }
    }

    private fun saveEntries(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("text", entry.text)
                put("time", entry.time)
                put("pinned", entry.pinned)
            })
        }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    /**
     * Reads the current primary clip and stores it if it is new text.
     * Uses the clip description timestamp first so the clip itself (which triggers
     * the Android 12+ "pasted from clipboard" toast) is only read when it changed.
     */
    fun captureCurrentClip(context: Context) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val description = clipboard.primaryClipDescription ?: return

            if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
                !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) return
            if (isSensitive(description)) return

            val timestamp = description.timestamp
            val lastTimestamp = prefs(context).getLong(KEY_LAST_CLIP_TIMESTAMP, -1L)
            if (timestamp > 0 && timestamp == lastTimestamp) return

            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()

            prefs(context).edit().putLong(KEY_LAST_CLIP_TIMESTAMP, timestamp).apply()
            if (text.isEmpty()) return
            addEntry(context, text.take(MAX_TEXT_LENGTH))
        } catch (e: SecurityException) {
            // No focus and no READ_CLIPBOARD app-op: nothing we can do right now.
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture clipboard", e)
        }
    }

    private fun isSensitive(description: ClipDescription): Boolean {
        val extras: PersistableBundle = description.extras ?: return false
        // ClipDescription.EXTRA_IS_SENSITIVE (API 33), also set by many password managers on older versions
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
    }

    private fun addEntry(context: Context, text: String) {
        val current = getEntries(context).toMutableList()
        val existing = current.firstOrNull { it.text == text }
        current.removeAll { it.text == text }
        current.add(0, Entry(text, System.currentTimeMillis(), existing?.pinned ?: false))

        // Trim only unpinned entries
        var unpinned = 0
        val trimmed = current.filter { entry ->
            if (entry.pinned) true else ++unpinned <= MAX_UNPINNED_ENTRIES
        }
        saveEntries(context, trimmed)
    }

    /** Puts the text back onto the system clipboard. */
    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Smart Edge", text))
        // Our own clip will come back via the listener; it is simply moved to the top.
    }

    fun togglePin(context: Context, text: String) {
        saveEntries(context, getEntries(context).map {
            if (it.text == text) it.copy(pinned = !it.pinned) else it
        })
    }

    fun remove(context: Context, text: String) {
        saveEntries(context, getEntries(context).filterNot { it.text == text })
    }

    /** Removes all entries except pinned ones. */
    fun clearUnpinned(context: Context) {
        saveEntries(context, getEntries(context).filter { it.pinned })
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * Grants the READ_CLIPBOARD app-op via Shizuku/Root so new clips can be recorded
     * even while the panel is closed. Must be called off the main thread.
     */
    fun grantBackgroundAccess(context: Context): Boolean {
        return AutomationManager.execute("appops set ${context.packageName} READ_CLIPBOARD allow")
    }
}
