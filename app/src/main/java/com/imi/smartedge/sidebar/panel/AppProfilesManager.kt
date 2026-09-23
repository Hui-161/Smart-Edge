package com.imi.smartedge.sidebar.panel

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * App profiles: a different app list in the sidebar depending on the time of day and/or
 * whether an Android mode ("Do not disturb", Bedtime, Driving, ... = interruption filter) is active.
 * The first profile whose conditions match is active; otherwise the standard list is used.
 */
object AppProfilesManager {

    private const val TAG = "AppProfiles"
    private const val PREFS_NAME = "app_profiles_prefs"
    private const val KEY_PROFILES = "profiles"
    const val PANEL_APPS_KEY_PREFIX = "panel_apps_profile_"

    data class Profile(
        val id: String,
        val name: String,
        val useTime: Boolean,
        val startMinutes: Int,   // minutes after midnight
        val endMinutes: Int,
        val whenModeActive: Boolean
    ) {
        val panelAppsKey: String get() = PANEL_APPS_KEY_PREFIX + id
    }

    /** Test hooks: override "now" and the mode state. */
    internal var clockMinutes: (() -> Int)? = null
    internal var modeActiveOverride: (() -> Boolean)? = null

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getProfiles(context: Context): List<Profile> {
        val raw = prefs(context).getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Profile(
                    o.getString("id"), o.optString("name"),
                    o.optBoolean("useTime"), o.optInt("start"), o.optInt("end"),
                    o.optBoolean("mode")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse profiles", e)
            emptyList()
        }
    }

    private fun save(context: Context, profiles: List<Profile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id); put("name", p.name)
                put("useTime", p.useTime); put("start", p.startMinutes); put("end", p.endMinutes)
                put("mode", p.whenModeActive)
            })
        }
        prefs(context).edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    /** Adds a profile; its app list starts as a copy of [initialApps]. */
    fun add(context: Context, profile: Profile, initialApps: List<String>) {
        save(context, getProfiles(context) + profile)
        PanelPreferences(context).setPanelAppsForKey(profile.panelAppsKey, initialApps)
    }

    fun update(context: Context, profile: Profile) {
        save(context, getProfiles(context).map { if (it.id == profile.id) profile else it })
    }

    fun remove(context: Context, id: String) {
        save(context, getProfiles(context).filterNot { it.id == id })
    }

    fun newId(): String = System.currentTimeMillis().toString(36)

    fun activeProfile(context: Context): Profile? {
        val profiles = getProfiles(context)
        if (profiles.isEmpty()) return null
        val now = clockMinutes?.invoke() ?: Calendar.getInstance().let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }
        val modeActive by lazy { modeActiveOverride?.invoke() ?: isModeActive(context) }
        return profiles.firstOrNull { p ->
            if (!p.useTime && !p.whenModeActive) return@firstOrNull false
            val timeOk = !p.useTime || inWindow(now, p.startMinutes, p.endMinutes)
            timeOk && (!p.whenModeActive || modeActive)
        }
    }

    /** Window may cross midnight (e.g. 22:00-06:00). */
    fun inWindow(now: Int, start: Int, end: Int): Boolean =
        if (start <= end) now in start until end else now >= start || now < end

    /** True while "Do not disturb" or another mode that filters interruptions is active. */
    fun isModeActive(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        val filter = nm.currentInterruptionFilter
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
               filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    fun formatTime(minutes: Int): String = String.format("%02d:%02d", minutes / 60, minutes % 60)
}
