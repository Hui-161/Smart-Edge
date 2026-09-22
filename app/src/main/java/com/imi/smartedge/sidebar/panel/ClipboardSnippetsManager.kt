package com.imi.smartedge.sidebar.panel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Permanently saved texts (e.g. IBAN, phone number) shown above the clipboard history.
 * Stored only on this device and excluded from cloud backups (see res/xml/backup_rules.xml).
 */
object ClipboardSnippetsManager {

    private const val TAG = "ClipboardSnippets"
    private const val PREFS_NAME = "clipboard_snippets_prefs"
    private const val KEY_SNIPPETS = "snippets"

    data class Snippet(val label: String, val text: String)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSnippets(context: Context): List<Snippet> {
        val raw = prefs(context).getString(KEY_SNIPPETS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Snippet(obj.optString("label"), obj.getString("text"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse snippets", e)
            emptyList()
        }
    }

    private fun save(context: Context, snippets: List<Snippet>) {
        val array = JSONArray()
        snippets.forEach { snippet ->
            array.put(JSONObject().apply {
                put("label", snippet.label)
                put("text", snippet.text)
            })
        }
        prefs(context).edit().putString(KEY_SNIPPETS, array.toString()).apply()
    }

    fun add(context: Context, snippet: Snippet) {
        save(context, getSnippets(context) + snippet)
    }

    fun update(context: Context, index: Int, snippet: Snippet) {
        val list = getSnippets(context).toMutableList()
        if (index !in list.indices) return
        list[index] = snippet
        save(context, list)
    }

    fun remove(context: Context, index: Int) {
        val list = getSnippets(context).toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        save(context, list)
    }

    /**
     * Copies the snippet marked as sensitive: Android 13+ then hides the preview, and the
     * clipboard history skips it (it is saved permanently anyway).
     */
    fun copy(context: Context, snippet: Snippet) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText(snippet.label.ifBlank { "Smart Edge" }, snippet.text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)
    }
}
