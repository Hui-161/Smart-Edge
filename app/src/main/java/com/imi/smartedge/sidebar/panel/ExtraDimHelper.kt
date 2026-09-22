package com.imi.smartedge.sidebar.panel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Controls Android's "Extra dim" accessibility feature (Android 12+, also used by One UI 4+).
 *
 * Toggling works directly when WRITE_SECURE_SETTINGS was granted (ADB/Shizuku/Root,
 * see [SecureSettingsDialog]). Otherwise it falls back to a Shizuku/Root shell command
 * and finally to opening the system's Extra dim settings page.
 */
object ExtraDimHelper {

    private const val TAG = "ExtraDimHelper"
    private const val KEY_ACTIVATED = "reduce_bright_colors_activated"
    private const val ACTION_EXTRA_DIM_SETTINGS = "android.settings.REDUCE_BRIGHT_COLORS_SETTINGS"
    private const val PREFS_NAME = "extra_dim_prefs"
    private const val KEY_LAST_STATE = "last_state"

    enum class Result { ENABLED, DISABLED, OPENED_SETTINGS, UNSUPPORTED }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun canToggleDirectly(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Current Extra dim state. Apps targeting Android 12+ may not read this key through
     * Settings.Secure (it is not marked @Readable and throws a SecurityException), so the
     * state is read from the ColorDisplayManager service, via Shizuku/Root, or finally
     * taken from the last value this app wrote.
     */
    fun isActive(context: Context): Boolean {
        try {
            return Settings.Secure.getInt(context.contentResolver, KEY_ACTIVATED, 0) == 1
        } catch (e: SecurityException) {
            // Expected on Android 12+ for third-party apps
        }
        readFromColorDisplayService(context)?.let { return it }
        AutomationManager.executeForOutput("settings get secure $KEY_ACTIVATED")?.let { output ->
            return output == "1"
        }
        return prefs(context).getBoolean(KEY_LAST_STATE, false)
    }

    /** Toggles Extra dim. May run a shell command, so call it off the main thread. */
    fun toggle(context: Context): Result {
        if (!isSupported()) return Result.UNSUPPORTED
        return try {
            val newState = !isActive(context)
            val value = if (newState) 1 else 0

            var success = false
            if (canToggleDirectly(context)) {
                success = try {
                    Settings.Secure.putInt(context.contentResolver, KEY_ACTIVATED, value)
                } catch (e: Exception) {
                    Log.e(TAG, "Writing Extra dim setting failed", e)
                    false
                }
            }
            if (!success && AutomationManager.isAutomationPossible()) {
                success = AutomationManager.execute("settings put secure $KEY_ACTIVATED $value")
            }

            if (success) {
                prefs(context).edit().putBoolean(KEY_LAST_STATE, newState).apply()
                if (newState) Result.ENABLED else Result.DISABLED
            } else {
                openSettings(context)
                Result.OPENED_SETTINGS
            }
        } catch (e: Exception) {
            // Never crash the panel service because of a vendor-specific settings implementation
            Log.e(TAG, "Toggling Extra dim failed", e)
            openSettings(context)
            Result.OPENED_SETTINGS
        }
    }

    /** Hidden ColorDisplayManager API, which does not require a permission. */
    @android.annotation.SuppressLint("WrongConstant") // Context.COLOR_DISPLAY_SERVICE is hidden
    private fun readFromColorDisplayService(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            val manager = context.getSystemService("color_display") ?: return null
            org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                manager.javaClass, manager, "isReduceBrightColorsActivated"
            ) as? Boolean
        } catch (e: Throwable) {
            Log.w(TAG, "ColorDisplayManager not accessible", e)
            null
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun openSettings(context: Context) {
        val intents = listOf(
            Intent(ACTION_EXTRA_DIM_SETTINGS),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Could not open ${intent.action}", e)
            }
        }
    }
}
