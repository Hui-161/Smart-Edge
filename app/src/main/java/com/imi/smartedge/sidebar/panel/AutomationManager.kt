package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.DataOutputStream

object AutomationManager {
    private const val TAG = "AutomationManager"

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val reader = process.inputStream.bufferedReader()
            val output = reader.readLine()
            process.waitFor() == 0 && output != null && output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    fun requestRootPermission(onResult: (Boolean) -> Unit) {
        // Run a simple command to trigger the SU dialog
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val thread = Thread {
                try {
                    val exitCode = process.waitFor()
                    onResult(exitCode == 0)
                } catch (e: Exception) {
                    onResult(false)
                }
            }
            thread.start()
        } catch (e: Exception) {
            onResult(false)
        }
    }

    fun isAutomationPossible(): Boolean {
        return isShizukuAvailable() || isRootAvailable()
    }

    fun checkRootAndRequestPermission(context: Context, onResult: (Boolean) -> Unit) {
        if (isRootAvailable()) {
            onResult(true)
            return
        }

        // Check if 'su' binary exists
        val suExists = try {
            Runtime.getRuntime().exec("which su").waitFor() == 0
        } catch (e: Exception) {
            false
        }

        if (suExists) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(R.string.msg_root_detected_title)
                .setIcon(android.R.drawable.ic_dialog_info)
                .setMessage(R.string.msg_root_detected_msg)
                .setPositiveButton(R.string.msg_grant_permission) { _, _ ->
                    requestRootPermission(onResult)
                }
                .setNegativeButton(R.string.msg_not_now) { _, _ ->
                    onResult(false)
                }
                .show()
        } else {
            onResult(false)
        }
    }

    fun execute(command: String): Boolean {
        if (isShizukuAvailable()) {
            return try {
                val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
                process.waitFor() == 0
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku execution failed: $command", e)
                false
            }
        } else if (isRootAvailable()) {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                process.waitFor() == 0
            } catch (e: Exception) {
                Log.e(TAG, "Root execution failed: $command", e)
                false
            }
        }
        return false
    }

    /** Runs a shell command via Shizuku/Root and returns its trimmed stdout, or null on failure. */
    fun executeForOutput(command: String): String? {
        return try {
            val process = when {
                isShizukuAvailable() -> Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
                isRootAvailable() -> Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                else -> return null
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.waitFor() == 0) output else null
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            null
        }
    }

    fun performBack() = execute("input keyevent 4")
    fun performHome() = execute("input keyevent 3")
    fun performRecents() = execute("input keyevent 187")
    fun performNotifications() = execute("cmd statusbar expand-notifications")
    fun performQuickSettings() = execute("cmd statusbar expand-settings")
    fun performSplitScreen() = execute("cmd statusbar toggle-split-screen")
    
    fun performPowerMenu() = execute("input keyevent 26 --longpress") // Long press power for menu

    fun performLockScreen() {
        // Locking is tricky via shell. input keyevent 26 (power) is the best fallback
        execute("input keyevent 26")
    }

    fun performPreviousApp() {
        // Previous app is usually two recents taps
        performRecents()
        Thread.sleep(200)
        performRecents()
    }

    fun takeScreenshot() {
        // Shell screenshot command varies, but 'screencap' is common.
        // However, triggering the system screenshot UI is better.
        execute("input keyevent 120") // KEYCODE_SYSRQ/Screenshot
    }
}
