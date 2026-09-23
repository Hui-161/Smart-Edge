package com.imi.smartedge.sidebar.panel

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

object SplitScreenHelper {
    private const val TAG = "SplitScreenHelper"

    // Windowing modes from Android Source
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_PINNED = 2
    private const val WINDOWING_MODE_SPLIT_SCREEN_PRIMARY = 3
    private const val WINDOWING_MODE_SPLIT_SCREEN_SECONDARY = 4
    private const val WINDOWING_MODE_FREEFORM = 5

    fun launchApp(context: Context, packageName: String, mode: Int) {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return
        
        // 1. Critical Flags for Multi-Window
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        
        val isSplit = mode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY ||
                      mode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
        
        if (isSplit) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: the split-screen windowing modes 3/4 no longer exist (the system shell
                // owns split screen). Forcing them makes the app start fullscreen, so only
                // "launch adjacent" is used: it opens the app in the other half of an active split.
                try {
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Adjacent launch failed: ${e.message}")
                }
                return
            }
        }

        val options = ActivityOptions.makeBasic()

        try {
            // 2. Force Windowing Mode via Hidden API
            HiddenApiBypass.invoke(
                ActivityOptions::class.java,
                options,
                "setLaunchWindowingMode",
                mode
            )

            // 3. Force via Intent Extras (Commonly respected by many OEMs and AOSP)
            launchIntent.putExtra("android.intent.extra.WINDOWING_MODE", mode)
            launchIntent.putExtra("android.intent.extra.LAUNCH_WINDOWING_MODE", mode)
            
            // 4. Force specific Screen Bounds
            // AOSP/Pixel often requires explicit bounds to correctly place the second app
            val dm = context.resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            
            val rect = when (mode) {
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY   -> android.graphics.Rect(0, 0, w, h / 2)
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY -> android.graphics.Rect(0, h / 2, w, h)
                WINDOWING_MODE_FREEFORM               -> android.graphics.Rect(w / 10, h / 10, w * 9 / 10, h * 9 / 10)
                else -> null
            }
            
            if (rect != null) {
                options.launchBounds = rect
            }

            Log.d(TAG, "Launching $packageName: mode=$mode, bounds=$rect")
            context.startActivity(launchIntent, options.toBundle())
            
        } catch (e: Exception) {
            Log.e(TAG, "Split launch failed: ${e.message}")
            try {
                context.startActivity(launchIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback launch also failed: ${e2.message}")
            }
        }
    }

    /**
     * Android 12+ with Shizuku/Root: split the app the user was in with [packageName] using the
     * system UI's WMShell shell command. [packageName] ends up at the top when [draggedToTop].
     * Blocking (shell calls + short waits), so call it off the main thread. Returns false if the
     * split could not be created.
     */
    fun splitWithShell(context: Context, packageName: String, draggedToTop: Boolean): Boolean {
        val recents = AutomationManager.executeForOutput("dumpsys activity recents") ?: return false
        // e.g. "* Recent #0: Task{3b1c2d9 #52 type=standard A=10154:com.android.chrome U=0 ..."
        val taskRegex = Regex("""#(\d+) type=standard [AI]=(?:\d+:)?(\S+)""")
        val previousTaskId = recents.lineSequence()
            .filter { it.contains("Recent #") }
            .mapNotNull { taskRegex.find(it) }
            .map { it.groupValues[1] to it.groupValues[2] }
            .firstOrNull { (_, pkg) -> pkg != context.packageName && pkg != packageName }
            ?.first ?: return false

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Launch for split failed: ${e.message}")
            return false
        }
        Thread.sleep(800) // let the dragged app come to the front

        // Side stage position of the previous app: 0 = top/left, 1 = bottom/right
        val sidePosition = if (draggedToTop) 1 else 0
        return AutomationManager.execute(
            "dumpsys activity service SystemUIService WMShell splitscreen moveToSideStage $previousTaskId $sidePosition"
        )
    }

    const val MODE_TOP = WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
    const val MODE_BOTTOM = WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
    const val MODE_FREEFORM = WINDOWING_MODE_FREEFORM
}
