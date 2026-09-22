package com.imi.smartedge.sidebar.panel

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class PanelAccessibilityService : AccessibilityService() {

    private lateinit var panelPrefs: PanelPreferences
    private var lastImmersiveState = false
    private var lastPackageName: String? = null

    override fun onCreate() {
        super.onCreate()
        panelPrefs = PanelPreferences(this)
    }

    private fun checkImmersiveMode() {
        if (!panelPrefs.serviceEnabled) return
        val root = rootInActiveWindow ?: return
        
        // Strategy: Check if the main window covers the whole screen area
        // and doesn't have system bars visible. Since we can't directly check system bar visibility
        // easily from here, we look at the window bounds vs screen bounds.
        
        val displayMetrics = resources.displayMetrics
        val screenRect = android.graphics.Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        
        val windowRect = android.graphics.Rect()
        root.getBoundsInScreen(windowRect)
        
        // Many immersive apps (videos/games) have bounds that match screen closely.
        // We use a small tolerance (5%) to account for notches, display cutouts, 
        // or system-level padding that might report a slightly smaller root window.
        val isImmersive = windowRect.width() >= screenRect.width() * 0.95 && 
                         windowRect.height() >= screenRect.height() * 0.95
        
        if (isImmersive != lastImmersiveState) {
            lastImmersiveState = isImmersive
            val intent = Intent(this, FloatingPanelService::class.java).apply {
                action = FloatingPanelService.ACTION_UPDATE_IMMERSIVE
                putExtra("is_immersive", isImmersive)
            }
            startService(intent)
        }
    }

    companion object {
        private const val TAG = "PanelAccessibility"
        const val ACTION_TAKE_SCREENSHOT = "com.imi.smartedge.sidebar.panel.ACTION_TAKE_SCREENSHOT"
        const val ACTION_SHOW_POWER_MENU = "com.imi.smartedge.sidebar.panel.ACTION_SHOW_POWER_MENU"
        const val ACTION_SPLIT_SCREEN = "com.imi.smartedge.sidebar.panel.ACTION_SPLIT_SCREEN"
        const val ACTION_TRIGGER_SHORTCUT = "com.imi.smartedge.sidebar.panel.ACTION_TRIGGER_SHORTCUT"
        const val ACTION_ONE_HANDED = "com.imi.smartedge.sidebar.panel.ACTION_ONE_HANDED"
        const val ACTION_PREVIOUS_APP = "com.imi.smartedge.sidebar.panel.ACTION_PREVIOUS_APP"
        const val ACTION_BACK = "com.imi.smartedge.sidebar.panel.ACTION_BACK"
        const val ACTION_HOME = "com.imi.smartedge.sidebar.panel.ACTION_HOME"
        const val ACTION_RECENTS = "com.imi.smartedge.sidebar.panel.ACTION_RECENTS"
        const val ACTION_NOTIFICATIONS = "com.imi.smartedge.sidebar.panel.ACTION_NOTIFICATIONS"
        const val ACTION_QUICK_SETTINGS = "com.imi.smartedge.sidebar.panel.ACTION_QUICK_SETTINGS"
        const val ACTION_LOCK_SCREEN = "com.imi.smartedge.sidebar.panel.ACTION_LOCK_SCREEN"
        const val ACTION_REFRESH_NOTCH = "com.imi.smartedge.sidebar.panel.ACTION_REFRESH_NOTCH"
        
        const val EXTRA_PKG = "pkg"
        const val EXTRA_MODE = "mode"
        
        @Volatile
        var isRunning = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        updateNotchTrigger()
    }

    // ── Notch trigger ─────────────────────────────────────────────────────────
    // Regular overlays (TYPE_APPLICATION_OVERLAY) sit below the status bar and never receive
    // touches at the camera cutout. An accessibility overlay is placed above it.

    private var notchView: NotchHandleView? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNotchTrigger()
    }

    private fun updateNotchTrigger() {
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val shouldShow = panelPrefs.serviceEnabled && panelPrefs.notchGesturesEnabled && !isLandscape
        if (!shouldShow) {
            removeNotchTrigger()
            return
        }

        val area = notchArea(wm)
        val params = android.view.WindowManager.LayoutParams(
            area.width(),
            area.height(),
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = area.left
            y = area.top
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            val existing = notchView
            if (existing != null && existing.isAttachedToWindow) {
                wm.updateViewLayout(existing, params)
            } else {
                val view = NotchHandleView(this)
                notchView = view
                wm.addView(view, params)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notch trigger", e)
            notchView = null
        }
    }

    private fun removeNotchTrigger() {
        val view = notchView ?: return
        notchView = null
        try {
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(view)
        } catch (e: Exception) {}
    }

    /** Screen area of the top camera cutout plus some padding, or a top-center fallback area. */
    private fun notchArea(wm: android.view.WindowManager): android.graphics.Rect {
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val pad = (12 * density).toInt()
        val minHeight = (28 * density).toInt()

        val cutoutRects: List<android.graphics.Rect> = try {
            when {
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R ->
                    wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRects
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q ->
                    @Suppress("DEPRECATION") wm.defaultDisplay.cutout?.boundingRects
                else -> null
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val top = cutoutRects.filter { it.top <= 0 || it.top < minHeight }.minByOrNull {
            Math.abs(it.centerX() - screenWidth / 2)
        }
        if (top != null && top.width() > 0) {
            return android.graphics.Rect(
                (top.left - pad).coerceAtLeast(0),
                0,
                (top.right + pad).coerceAtMost(screenWidth),
                maxOf(top.bottom + pad / 2, minHeight)
            )
        }
        // No cutout reported: centered strip at the top of the status bar
        val width = (110 * density).toInt()
        return android.graphics.Rect((screenWidth - width) / 2, 0, (screenWidth + width) / 2, minHeight)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TAKE_SCREENSHOT -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                }
            }
            ACTION_SHOW_POWER_MENU -> {
                performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            }
            ACTION_SPLIT_SCREEN -> {
                val pkg = intent.getStringExtra(EXTRA_PKG)
                val mode = intent.getIntExtra(EXTRA_MODE, 1)
                if (pkg != null) {
                    if (mode == SplitScreenHelper.MODE_TOP || mode == SplitScreenHelper.MODE_BOTTOM) {
                        triggerSplitScreen(pkg, mode)
                    } else {
                        // Freeform launch doesn't need the toggle action
                        SplitScreenHelper.launchApp(this, pkg, mode)
                    }
                }
            }
            ACTION_ONE_HANDED -> {
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                handler.post {
                    android.widget.Toast.makeText(this, R.string.msg_one_handed_triggered, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_PREVIOUS_APP -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                }, 200)
            }
            ACTION_REFRESH_NOTCH -> updateNotchTrigger()
            ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ACTION_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            ACTION_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            ACTION_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            ACTION_QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            ACTION_LOCK_SCREEN -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
            }
            ACTION_TRIGGER_SHORTCUT -> {
                val shortcut = intent.getStringExtra("shortcut")
                if (shortcut == "smartedge.shortcut.one_hand") {
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.post {
                        android.widget.Toast.makeText(this, R.string.msg_one_handed_triggered, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    // Attempting standard fallback if the OEM supports it via AccessibilityService
                    // true specific one-handed mode intents are heavily fragmented
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        // In Android 12+, there's no public GLOBAL_ACTION_ONE_HANDED.
                        // We rely on standard gesture dispatch or root if really necessary.
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Triggers split-screen for the given package and mode.
     *
     * Strategy 1 (AOSP): Use GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN to pin the foreground app,
     *   then launch the second app adjacent to it.
     * Strategy 2 (Origin OS / Vivo / OEMs that block the toggle): Skip the toggle and
     *   launch the second app directly with split-screen windowing mode flags. The OEM's
     *   own window manager handles placing it in split.
     */
    private fun triggerSplitScreen(pkg: String, mode: Int) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val isVivo = VivoUtils.isVivo()

        if (isVivo) {
            SplitScreenHelper.launchApp(this, pkg, mode)
        } else {
            // Standard AOSP path: toggle split, wait for animation, then launch second app
            val toggled = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

            // On many AOSP/Pixel versions, we need a significant delay for the system to dock the first app.
            // If toggle failed (e.g. only one app open), we still try to launch adjacent.
            val delay = if (toggled) 1000L else 500L
            handler.postDelayed({
                SplitScreenHelper.launchApp(this, pkg, mode)
            }, delay)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            if (packageName == lastPackageName) return
            lastPackageName = packageName

            val className = event.className?.toString() ?: ""
            
            // Store current foreground package for Context/Game mode
            panelPrefs.currentForegroundPackage = packageName
            
            // Get the current active keyboard package
            val defaultIme = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
            )
            val imePackage = defaultIme?.substringBefore("/") ?: ""
            
            val isSystemPkg = packageName == "android" || packageName == "com.android.systemui"
            
            if (packageName != "com.imi.smartedge.sidebar.panel" && packageName != imePackage && !isSystemPkg) {
                if (panelPrefs.serviceEnabled) {
                    val closeIntent = Intent(this, FloatingPanelService::class.java).apply {
                        action = FloatingPanelService.ACTION_CLOSE_PANEL
                    }
                    startService(closeIntent)

                    // Notify service to update game mode state based on new foreground package
                    val refreshIntent = Intent(this, FloatingPanelService::class.java).apply {
                        action = FloatingPanelService.ACTION_REFRESH
                    }
                    startService(refreshIntent)
                }
            }
        }
        
        // Check for immersive mode on window content changes too, as bounds might change
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            checkImmersiveMode()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        removeNotchTrigger()
        val stopIntent = Intent(this, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.ACTION_STOP
        }
        startService(stopIntent)
        return super.onUnbind(intent)
    }
}
