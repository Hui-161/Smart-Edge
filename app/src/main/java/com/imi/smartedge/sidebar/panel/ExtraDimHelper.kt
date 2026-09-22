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

    enum class Result { ENABLED, DISABLED, OPENED_SETTINGS, UNSUPPORTED }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun canToggleDirectly(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isActive(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_ACTIVATED, 0) == 1

    /** Toggles Extra dim. May run a shell command, so call it off the main thread. */
    fun toggle(context: Context): Result {
        if (!isSupported()) return Result.UNSUPPORTED
        val newState = !isActive(context)
        val value = if (newState) 1 else 0

        val success = if (canToggleDirectly(context)) {
            try {
                Settings.Secure.putInt(context.contentResolver, KEY_ACTIVATED, value)
            } catch (e: SecurityException) {
                Log.e(TAG, "WRITE_SECURE_SETTINGS rejected", e)
                false
            }
        } else if (AutomationManager.isAutomationPossible()) {
            AutomationManager.execute("settings put secure $KEY_ACTIVATED $value")
        } else {
            false
        }

        if (success) return if (newState) Result.ENABLED else Result.DISABLED
        openSettings(context)
        return Result.OPENED_SETTINGS
    }

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
