package com.imi.smartedge.sidebar.panel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FloatingPanelService : Service() {

    private lateinit var windowManager: WindowManager
    private var edgeHandleView: EdgeHandleView? = null
    /** Left handle when handles are shown on both edges (edgeHandleView is then the right one). */
    private var secondaryHandleView: EdgeHandleView? = null
    private var sidePanelView: SidePanelView? = null
    private var pickerPanelView: AppPickerPanelView? = null
    private var quickListView: QuickListPanelView? = null
    private var isQuickListOpen = false
    
    private var rootLayout: android.widget.FrameLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null

    private val activeTorches = mutableSetOf<String>()
    private var isFlashlightOn = false
    private var lastManualToggleTime = 0L
    private var cameraManager: android.hardware.camera2.CameraManager? = null
    private val torchCallback = object : android.hardware.camera2.CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            super.onTorchModeChanged(cameraId, enabled)
            // Ignore system callbacks for a short period after a manual toggle
            if (System.currentTimeMillis() - lastManualToggleTime > 500) {
                if (enabled) activeTorches.add(cameraId) else activeTorches.remove(cameraId)
                isFlashlightOn = activeTorches.isNotEmpty()
                Log.d(TAG, "External torch change: $enabled for camera $cameraId. Master state: $isFlashlightOn")
            }
        }
    }
    
    private var dragOverlay: android.widget.FrameLayout? = null
    private var dragOverlayParams: WindowManager.LayoutParams? = null

    private var isPanelOpen = false
    private var isPickerOpen = false
    private var isImmersiveMode = false
    private var currentFolderId: String? = null
    private lateinit var panelPrefs: PanelPreferences
    private var lastPickerToggleTime = 0L
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())

    private val clipboardListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        // Only delivered while we may read the clipboard (focused overlay or READ_CLIPBOARD app-op)
        if (panelPrefs.clipboardHistoryEnabled) {
            ClipboardHistoryManager.captureCurrentClip(this)
            if (isQuickListOpen && quickListView?.mode == QuickListPanelView.Mode.CLIPBOARD) {
                quickListView?.reload()
            }
        }
    }

    private val packageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == Intent.ACTION_PACKAGE_ADDED || 
                action == Intent.ACTION_PACKAGE_REMOVED || 
                action == Intent.ACTION_PACKAGE_REPLACED) {
                
                val packageName = intent.data?.schemeSpecificPart
                if (packageName != null) {
                    // Invalidate system icon cache for this app
                    AppRepository.clearSystemIconCache(packageName)
                    
                    // If it was removed, remove it from pinned apps too
                    if (action == Intent.ACTION_PACKAGE_REMOVED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        panelPrefs.removeApp(packageName)
                    }

                    // Refresh lists if picker or panel is open
                    if (isPanelOpen) {
                        refreshApps()
                    }
                    if (isPickerOpen) {
                        pickerPanelView?.invalidateAppList()
                        pickerPanelView?.loadApps(forceRefresh = true)
                    }
                }
            }
        }
    }

    private val systemDialogsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                val reason = intent.getStringExtra("reason")
                if (reason == "homekey" || reason == "recentapps") {
                    closePanel()
                }
            }
        }
    }

    companion object {
        const val TAG = "FloatingPanelService"
        var isRunning = false
            private set
            
        const val CHANNEL_ID = "side_panel_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.imi.smartedge.sidebar.panel.STOP"
        const val ACTION_OPEN = "com.imi.smartedge.sidebar.panel.OPEN"
        const val ACTION_OPEN_HUB = "com.imi.smartedge.sidebar.panel.OPEN_HUB"
        const val ACTION_REFRESH = "com.imi.smartedge.sidebar.panel.REFRESH"
        const val ACTION_CLOSE_PANEL = "com.imi.smartedge.sidebar.panel.CLOSE_PANEL"
        const val ACTION_SHOW_TEMP = "com.imi.smartedge.sidebar.panel.SHOW_TEMP"
        const val ACTION_TOGGLE = "com.imi.smartedge.sidebar.panel.TOGGLE"
        const val ACTION_SCREENSHOT = "com.imi.smartedge.sidebar.panel.SCREENSHOT"
        const val ACTION_UPDATE_IMMERSIVE = "com.imi.smartedge.sidebar.panel.UPDATE_IMMERSIVE"
        const val ACTION_TOGGLE_FLASHLIGHT = "com.imi.smartedge.sidebar.panel.TOGGLE_FLASHLIGHT"
        const val ACTION_LAUNCH_CAMERA = "com.imi.smartedge.sidebar.panel.LAUNCH_CAMERA"
        const val ACTION_TOGGLE_ROTATION = "com.imi.smartedge.sidebar.panel.TOGGLE_ROTATION"
        const val ACTION_OPEN_FAV_APP = "com.imi.smartedge.sidebar.panel.OPEN_FAV_APP"
        const val ACTION_TOGGLE_EXTRA_DIM = "com.imi.smartedge.sidebar.panel.TOGGLE_EXTRA_DIM"

        const val TOOL_CLIPBOARD = "smartedge.tool.clipboard"
        const val TOOL_CONTACTS = "smartedge.tool.contacts"
        const val TOOL_EXTRA_DIM = "smartedge.tool.extra_dim"
    }

    override fun attachBaseContext(newBase: Context) {
        // Apply the in-app language (not only the system language) to panel texts
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(this, android.content.ComponentName(this, PanelTileService::class.java))
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        panelPrefs = PanelPreferences(this)
        
        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            cameraManager?.registerTorchCallback(torchCallback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register torch callback", e)
        }

        panelPrefs.migrateToolButtonsToAppList()

        // One-time migration for new defaults
        if (!panelPrefs.toolsFolderMigrated) {
            panelPrefs.showTools = true
            panelPrefs.showToolsPanelButton = true
            panelPrefs.toolsFolderMigrated = true
        }

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        initSidePanel()
        initPickerPanel()
        initQuickList()

        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .addPrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register clipboard listener", e)
        }
        
        // Force enable notch gestures for debugging if we're in a debug build
        // val isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // if (isDebug) {
        //    panelPrefs.notchGesturesEnabled = true
        // }

        if (panelPrefs.serviceEnabled) {
            addEdgeHandle()
            refreshNotchTrigger()
        }

        val filter = android.content.IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemDialogsReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(systemDialogsReceiver, filter)
        }

        val pkgFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, pkgFilter)

        serviceScope.launch {
            if (panelPrefs.getPanelApps().isEmpty()) {
                val topApps = AppRepository(this@FloatingPanelService).getTop5Apps()
                panelPrefs.setPanelApps(topApps)
                refreshApps()
            }
        }

        NotificationTrackingService.onNotificationsChanged = {
            if (isPanelOpen) {
                refreshApps()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        // If service is disabled, we only allow ACTION_TOGGLE or ACTION_STOP to proceed.
        // Any other action should stop the service.
        if (!panelPrefs.serviceEnabled && action != ACTION_TOGGLE && action != ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null || action == null) {
            if (panelPrefs.serviceEnabled) {
                addEdgeHandle()
            }
        }
        
        when (action) {
            ACTION_TOGGLE -> {
                val newState = intent?.getBooleanExtra("target_state", !panelPrefs.serviceEnabled) ?: !panelPrefs.serviceEnabled
                panelPrefs.setServiceEnabled(newState, commit = true)
                
                // Request Tile Update explicitly
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    TileService.requestListeningState(this, android.content.ComponentName(this, PanelTileService::class.java))
                }
                
                if (newState) {
                    addEdgeHandle()
                    refreshNotchTrigger()
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_OPEN -> {
                openPanel()
            }
            ACTION_OPEN_HUB -> {
                togglePicker(false)
            }
            PanelAccessibilityService.ACTION_TAKE_SCREENSHOT -> {
                handler.postDelayed({ triggerScreenshot() }, 200)
            }
            ACTION_REFRESH -> {
                serviceScope.launch {
                    if (panelPrefs.getPanelApps().isEmpty()) {
                        val topApps = AppRepository(this@FloatingPanelService).getTop5Apps()
                        panelPrefs.setPanelApps(topApps)
                    }
                    val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val shouldShowHandle = if (isLandscape && !panelPrefs.showInLandscape) false
                                          else if (panelPrefs.onlyOnHome && !isCurrentPackageLauncher()) false
                                          else true

                    if (!shouldShowHandle) {
                        // Remove from WM to be sure they don't block touches
                        removeAllHandles()
                    } else {
                        addEdgeHandle(forceRecreate = false)
                        setHandlesVisibility(if (isPanelOpen) View.GONE else View.VISIBLE)
                    }
                    // Notch trigger lives in the accessibility service (it must sit above the status bar)
                    refreshNotchTrigger()

                    // Update game mode state
                    val currentPkg = panelPrefs.currentForegroundPackage
                    val isGame = panelPrefs.getGameApps().contains(currentPkg)
                    handles().forEach { it.isGameActive = isGame }
                    
                    sidePanelView?.updateStyles()
                    sidePanelView?.refreshIcons()
                    
                    // Update side/picker gravity in case it changed
                    val isRightSide = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
                    sidePanelView?.let { panel ->
                        val lp = panel.layoutParams as? android.widget.FrameLayout.LayoutParams
                        if (lp != null) {
                            lp.gravity = if (isRightSide) Gravity.END or Gravity.CENTER_VERTICAL
                                         else Gravity.START or Gravity.CENTER_VERTICAL
                            panel.layoutParams = lp
                        }
                    }
                    pickerPanelView?.let { picker ->
                        val lp = picker.layoutParams as? android.widget.FrameLayout.LayoutParams
                        if (lp != null) {
                            lp.gravity = if (isRightSide) Gravity.END or Gravity.CENTER_VERTICAL
                                         else Gravity.START or Gravity.CENTER_VERTICAL
                            picker.layoutParams = lp
                        }
                    }

                    pickerPanelView?.applyTheme()
                    pickerPanelView?.clearIcons()
                    updateBlur(isPanelOpen)
                    
                    if (!isPanelOpen) {
                        isPickerOpen = false
                        pickerPanelView?.visibility = View.GONE
                        sidePanelView?.animatePickerToggle(false)
                    } else if (isPickerOpen) {
                        pickerPanelView?.loadApps() 
                    }
                    refreshApps()
                }
            }
            ACTION_CLOSE_PANEL -> closePanel(immediate = false)
            ACTION_SCREENSHOT -> {
                handler.postDelayed({ triggerScreenshot() }, 200)
            }
            ACTION_UPDATE_IMMERSIVE -> {
                isImmersiveMode = intent?.getBooleanExtra("is_immersive", false) ?: false
                handles().forEach { it.isImmersiveMode = isImmersiveMode }
            }
            ACTION_SHOW_TEMP -> {
                addEdgeHandle(forceRecreate = false)
                handles().forEach { it.showTemporarily() }
            }
            ACTION_TOGGLE_FLASHLIGHT -> toggleFlashlight()
            ACTION_LAUNCH_CAMERA -> launchCamera()
            ACTION_TOGGLE_ROTATION -> toggleAutoRotation()
            ACTION_OPEN_FAV_APP -> openFavoriteApp()
            ACTION_TOGGLE_EXTRA_DIM -> toggleExtraDim()
        }
        return if (panelPrefs.serviceEnabled) START_STICKY else START_NOT_STICKY
    }

    fun triggerScreenshot() {
        closePanel()
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, PanelAccessibilityService::class.java).apply {
                action = PanelAccessibilityService.ACTION_TAKE_SCREENSHOT
            }
            startService(intent)
        }, 300)
    }

    private fun toggleFlashlight() {
        try {
            val manager = cameraManager ?: getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            
            if (cameraId == null) {
                Log.e(TAG, "No flashlight-capable camera found!")
                return
            }

            // Redundant check: if our master boolean says ON, or if we have ANY active torch IDs
            val currentState = isFlashlightOn || activeTorches.isNotEmpty()
            val newState = !currentState
            
            Log.d(TAG, "toggleFlashlight: Toggling to $newState (current state: $currentState, activeTorches: $activeTorches)")
            
            lastManualToggleTime = System.currentTimeMillis()
            isFlashlightOn = newState
            if (newState) activeTorches.add(cameraId) else activeTorches.clear()
            
            manager.setTorchMode(cameraId, newState)
            showIndicator(if (newState) getString(R.string.indicator_flashlight_on) else getString(R.string.indicator_flashlight_off))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight", e)
        }
    }

    private fun launchCamera() {
        try {
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            closePanel(immediate = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch camera", e)
        }
    }

    private fun toggleAutoRotation() {
        try {
            if (!android.provider.Settings.System.canWrite(this)) {
                showIndicator(getString(R.string.msg_requires_write_settings))
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            }
            
            val current = android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0)
            val newState = if (current == 1) 0 else 1
            android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, newState)
            showIndicator(if (newState == 1) getString(R.string.indicator_rotation_on) else getString(R.string.indicator_rotation_off))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle rotation", e)
        }
    }

    private fun openFavoriteApp() {
        val pkg = panelPrefs.favoriteAppPackage
        if (pkg.isEmpty()) {
            showIndicator(getString(R.string.toast_fav_app_unset))
            return
        }
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                closePanel(immediate = true)
            } else {
                showIndicator(getString(R.string.toast_app_not_found))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open favorite app", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            cameraManager?.unregisterTorchCallback(torchCallback)
        } catch (e: Exception) {}
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(this, android.content.ComponentName(this, PanelTileService::class.java))
        }
        NotificationTrackingService.onNotificationsChanged = null
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .removePrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {}
        serviceScope.cancel()
        try {
            unregisterReceiver(systemDialogsReceiver)
            unregisterReceiver(packageReceiver)
        } catch (e: Exception) {}
        removeAllHandles()
        removeView(rootLayout)
        refreshNotchTrigger() // hides it when the service was switched off
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        
        // Always close panel on orientation change to prevent layout corruption
        if (isPanelOpen) {
            closePanel(immediate = true)
        }

        if (panelPrefs.serviceEnabled) {
            val isLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            if (isLandscape && !panelPrefs.showInLandscape) {
                setHandlesVisibility(View.GONE)
            } else {
                // Re-add the handle to guarantee WindowManager bounds are perfectly mapped to the new orientation
                addEdgeHandle()
                setHandlesVisibility(View.VISIBLE)
            }
        }
    }

    private fun removeView(view: View?) {
        if (view == null) return
        try {
            // More aggressive removal to ensure no 'permanent' ghosts remain
            if (view.isAttachedToWindow) {
                windowManager.removeViewImmediate(view)
            } else {
                // Try removing anyway to catch any edge cases
                windowManager.removeView(view)
            }
        } catch (e: Exception) {
            // View was likely already removed or never attached
        }
    }

    private fun isCurrentPackageLauncher(): Boolean {
        val currentPkg = panelPrefs.currentForegroundPackage
        if (currentPkg.isEmpty() || currentPkg == packageName) return true // Assume home if unknown or if in our own app

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        val homePkg = resolveInfo?.activityInfo?.packageName
        
        // Also check all installed launchers as some devices have multiple or third-party ones
        val allLaunchers = packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
        
        return currentPkg == homePkg || allLaunchers.contains(currentPkg) || currentPkg == "com.android.systemui"
    }

    /** Asks the accessibility service to show/hide/reposition the notch trigger. */
    private fun refreshNotchTrigger() {
        if (!PanelAccessibilityService.isRunning) return
        try {
            startService(Intent(this, PanelAccessibilityService::class.java).apply {
                action = PanelAccessibilityService.ACTION_REFRESH_NOTCH
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh notch trigger", e)
        }
    }

    private fun addEdgeHandle(forceRecreate: Boolean = false) {
        val anyTriggerEnabled = panelPrefs.gesturesEnabled || 
                                panelPrefs.tapToOpen || 
                                panelPrefs.doubleTapToOpen || 
                                panelPrefs.tripleTapToOpen

        // --- MANDATORY ENGINE CHECK ---
        // If neither Accessibility is ON nor Native Automation is POSSIBLE, we must NOT show the handle.
        val hasActiveEngine = isAccessibilityServiceEnabled() || (panelPrefs.useAutomationForGestures && AutomationManager.isAutomationPossible())

        if (!anyTriggerEnabled || (panelPrefs.onlyOnHome && !isCurrentPackageLauncher()) || !hasActiveEngine) {
            removeAllHandles()
            return
        }

        if (panelPrefs.handleBothSides) {
            // Right handle: slide = brightness, left handle: slide = volume
            edgeHandleView = ensureEdgeHandle(edgeHandleView, isRight = true, bothSides = true, forceRecreate = forceRecreate)
            secondaryHandleView = ensureEdgeHandle(secondaryHandleView, isRight = false, bothSides = true, forceRecreate = forceRecreate)
        } else {
            removeView(secondaryHandleView)
            secondaryHandleView = null
            val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
            edgeHandleView = ensureEdgeHandle(edgeHandleView, isRight, bothSides = false, forceRecreate = forceRecreate)
        }
    }

    /** Opens the panel on the edge whose handle was used (both-sides mode). */
    private fun usePanelSide(side: String) {
        if (panelPrefs.panelSide == side) return
        panelPrefs.panelSide = side
        sidePanelView?.updateSideLayout()
        val gravity = if (side == PanelPreferences.SIDE_RIGHT) Gravity.END or Gravity.CENTER_VERTICAL
                      else Gravity.START or Gravity.CENTER_VERTICAL
        (sidePanelView?.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
            lp.gravity = gravity
            sidePanelView?.layoutParams = lp
        }
        (pickerPanelView?.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
            lp.gravity = gravity
            pickerPanelView?.layoutParams = lp
        }
    }

    private fun ensureEdgeHandle(existing: EdgeHandleView?, isRight: Boolean, bothSides: Boolean, forceRecreate: Boolean): EdgeHandleView? {
        var edgeHandleView = existing
        val isPillVisible = panelPrefs.showPill
        val seekTarget = when {
            !bothSides -> EdgeHandleView.SEEK_AUTO
            isRight -> EdgeHandleView.SEEK_BRIGHTNESS
            else -> EdgeHandleView.SEEK_VOLUME
        }

        if (edgeHandleView != null && !forceRecreate) {
            edgeHandleView.allowSideFlip = !bothSides
            edgeHandleView.seekTarget = seekTarget
            val params = edgeHandleView.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                // 1. Update gravity if side changed
                val newGravity = if (isRight) Gravity.END or Gravity.CENTER_VERTICAL
                                 else Gravity.START or Gravity.CENTER_VERTICAL
                params.gravity = newGravity

                // 2. Update size and position
                val density = resources.displayMetrics.density
                val screenH = resources.displayMetrics.heightPixels
                val safeMargin = (10 * density).toInt()
                val h = if (isPillVisible) (panelPrefs.handleHeight * density).toInt()
                        else (panelPrefs.handleHeight * 1.5f * density).toInt()
                val maxOffset = (screenH / 2) - (h / 2) - safeMargin
                val requestedOffset = (panelPrefs.handleVerticalOffset * density).toInt()

                params.width = (panelPrefs.handleWidth * density).toInt()
                params.height = h
                params.y = requestedOffset.coerceIn(-maxOffset, maxOffset)
                
                try {
                    windowManager.updateViewLayout(edgeHandleView, params)
                } catch (e: Exception) {}
            }
            
            edgeHandleView?.updateState(
                isRight, 
                isPillVisible, 
                this.isImmersiveMode, 
                panelPrefs.panelOpacity
            )
            return edgeHandleView
        }

        removeView(edgeHandleView)
        edgeHandleView = null

        edgeHandleView = EdgeHandleView(this).apply {
            allowSideFlip = !bothSides
            this.seekTarget = seekTarget
            onTrigger = {
                // Read the current mode: the handle is reused when the side setting changes
                if (panelPrefs.handleBothSides) usePanelSide(if (isRightSide) PanelPreferences.SIDE_RIGHT else PanelPreferences.SIDE_LEFT)
                refreshApps {
                    openPanel()
                }
            }
            onAdjustBrightness = { delta ->
                adjustBrightness(delta)
            }
            onAdjustVolume = { delta ->
                adjustVolume(delta)
            }
            onPositionChanged = {
                // Keep the other edge's handle at the same height
                if (panelPrefs.handleBothSides) addEdgeHandle()
            }
            onSideChanged = { newSide ->
                // Pill was dragged to the opposite edge — sync the whole service UI
                sidePanelView?.updateSideLayout()
            }
            isRightSide = isRight
            showPill = isPillVisible
            isImmersiveMode = this@FloatingPanelService.isImmersiveMode
            alpha = panelPrefs.panelOpacity / 100f
        }

        val handleWidth = panelPrefs.handleWidth // Use user-defined width
        val handleHeight = if (isPillVisible) dpToPx(panelPrefs.handleHeight) 
                           else dpToPx((panelPrefs.handleHeight * 1.5f).toInt())

        // Fix: Use FLAG_LAYOUT_NO_LIMITS carefully or ensure GRAVITY_CENTER doesn't overflow
        val params = WindowManager.LayoutParams(
            dpToPx(handleWidth),
            handleHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (isRight) Gravity.END or Gravity.CENTER_VERTICAL
                      else Gravity.START or Gravity.CENTER_VERTICAL
            
            // Calculate absolute max offset to keep handle on screen
            val screenH = resources.displayMetrics.heightPixels
            val safeMargin = dpToPx(10) // Keep away from extreme top/bottom edges
            val maxOffset = (screenH / 2) - (handleHeight / 2) - safeMargin
            
            val requestedOffset = dpToPx(panelPrefs.handleVerticalOffset)
            y = requestedOffset.coerceIn(-maxOffset, maxOffset)
            
            // Log.d(TAG, "Handle Params: width=$width, height=$height, y=$y (requested=$requestedOffset, max=$maxOffset)")
        }

        windowManager.addView(edgeHandleView, params)
        return edgeHandleView
    }

    private fun initSidePanel() {
        sidePanelView = SidePanelView(this).apply {
            onClose = { closePanel() }
            onAppsChanged = { refreshApps() }
            onAddClick = { isEdit -> togglePicker(isEdit) }
            onScreenshot = { 
                closePanel()
                Handler(Looper.getMainLooper()).postDelayed({
                    triggerScreenshot()
                }, 300)
            }
            onFolderOpen = { folderId ->
                currentFolderId = folderId
                refreshApps()
            }
            onBackNavigation = {
                currentFolderId = null // Simple logic for now: only 1-level folders
                refreshApps()
            }
            onNextPage = { switchToNextPage() }
            onToolClick = { toolId ->
                when (toolId) {
                    "smartedge.tool.screenshot" -> triggerScreenshot()
                    "smartedge.tool.volume_up" -> adjustVolume(android.media.AudioManager.ADJUST_RAISE)
                    "smartedge.tool.volume_down" -> adjustVolume(android.media.AudioManager.ADJUST_LOWER)
                    "smartedge.tool.brightness_up" -> adjustBrightness(15)
                    "smartedge.tool.brightness_down" -> adjustBrightness(-15)
                    TOOL_CLIPBOARD -> toggleQuickList(QuickListPanelView.Mode.CLIPBOARD)
                    TOOL_CONTACTS -> toggleQuickList(QuickListPanelView.Mode.CONTACTS)
                    TOOL_EXTRA_DIM -> toggleExtraDim()
                    EdgeTools.FLASHLIGHT -> toggleFlashlight()
                    EdgeTools.ROTATION -> toggleAutoRotation()
                    EdgeTools.CAMERA -> launchCamera()
                    EdgeTools.LOCK_SCREEN -> runPanelAction(PanelPreferences.ACTION_LOCK_SCREEN)
                    EdgeTools.POWER_MENU -> runPanelAction(PanelPreferences.ACTION_POWER_MENU)
                    EdgeTools.NOTIFICATIONS -> runPanelAction(PanelPreferences.ACTION_NOTIFICATIONS)
                    EdgeTools.QUICK_SETTINGS -> runPanelAction(PanelPreferences.ACTION_QUICK_SETTINGS)
                    EdgeTools.CONTACTS_SETUP -> openToolsSettings("feature_contacts_button")
                    else -> if (toolId.startsWith(EdgeTools.CONTACT_PREFIX)) {
                        val index = toolId.removePrefix(EdgeTools.CONTACT_PREFIX).toIntOrNull()
                        val contact = index?.let { FavoriteContactsManager.getContacts(this@FloatingPanelService).getOrNull(it) }
                        if (contact != null) openQuickList(QuickListPanelView.Mode.CONTACT_DETAIL, contact)
                    }
                }
            }
            visibility = View.GONE 
        }
        refreshApps()
    }

    private fun initPickerPanel() {
        pickerPanelView = AppPickerPanelView(this).apply {
            onClose = { closePicker() }
            onAppLaunched = { closePanel() }
            onToggleApp = { app, isSelected ->
                if (isSelected) {
                    panelPrefs.addApp(app.identifier)
                    refreshApps {
                        if (isPickerOpen) {
                            sidePanelView?.scrollToApp(app.identifier)
                        }
                    }
                } else {
                    panelPrefs.removeApp(app.identifier)
                    refreshApps()
                }
            }
            visibility = View.GONE 
        }
    }

    private fun handles(): List<EdgeHandleView> = listOfNotNull(edgeHandleView, secondaryHandleView)

    private fun setHandlesVisibility(visibility: Int) {
        handles().forEach { it.visibility = visibility }
    }

    private fun removeAllHandles() {
        removeView(edgeHandleView)
        edgeHandleView = null
        removeView(secondaryHandleView)
        secondaryHandleView = null
    }

    private fun initQuickList() {
        quickListView = QuickListPanelView(this).apply {
            onEntryUsed = { closePanel() }
            // Toast instead of the in-panel indicator, which disappears together with the panel
            onMessage = { android.widget.Toast.makeText(this@FloatingPanelService, it, android.widget.Toast.LENGTH_SHORT).show() }
            onOpenSettings = { target -> openToolsSettings(target) }
            visibility = View.GONE
        }
    }

    private fun openToolsSettings(scrollToViewId: String) {
        closePanel(immediate = true)
        val intent = Intent(this, ToolsSettingsActivity::class.java).apply {
            putExtra(SettingsMainActivity.EXTRA_SCROLL_TO, scrollToViewId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /** Runs a system action (lock screen, notifications, ...) and closes the panel first. */
    private fun runPanelAction(actionId: Int) {
        closePanel(immediate = true)
        handler.postDelayed({ ActionDispatcher.performAction(this, actionId, panelPrefs) }, 150)
    }

    /** Page currently shown, falling back to the apps page if the saved one was disabled. */
    private fun currentPage(): String {
        val page = panelPrefs.lastPanelPage
        return if (page in panelPrefs.getEnabledPages()) page else PanelPreferences.PAGE_APPS
    }

    private fun switchToNextPage() {
        val pages = panelPrefs.getEnabledPages()
        if (pages.size <= 1) return
        val next = pages[(pages.indexOf(currentPage()) + 1) % pages.size]
        panelPrefs.lastPanelPage = next
        closeQuickList()
        if (panelPrefs.hapticEnabled) {
            sidePanelView?.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        }
        sidePanelView?.animatePageChange { showNewPage -> refreshApps(showNewPage) }
    }

    private fun toggleQuickList(mode: QuickListPanelView.Mode) {
        if (isQuickListOpen && quickListView?.mode == mode) {
            closeQuickList()
        } else {
            openQuickList(mode)
        }
    }

    private fun openQuickList(mode: QuickListPanelView.Mode, contact: FavoriteContactsManager.Contact? = null) {
        val list = quickListView ?: return
        if (isPickerOpen) closePicker()
        if (mode == QuickListPanelView.Mode.CLIPBOARD && panelPrefs.clipboardHistoryEnabled) {
            // The overlay has focus now, so the latest clip is readable
            ClipboardHistoryManager.captureCurrentClip(this)
        }

        val wasOpen = isQuickListOpen
        isQuickListOpen = true
        list.show(mode, contact)

        val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
        val displayMetrics = resources.displayMetrics
        val lp = android.widget.FrameLayout.LayoutParams(dpToPx(260), android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (isRight) Gravity.CENTER_VERTICAL or Gravity.END
                     else Gravity.CENTER_VERTICAL or Gravity.START
        // Same horizontal alignment as the app picker
        val sidebarWidthPx = sidePanelView?.panelWidthPx()?.takeIf { it > 0 } ?: dpToPx(72)
        val gapPx = sidebarWidthPx + ((12 + panelPrefs.pickerGap) * displayMetrics.density).toInt()
        if (isRight) lp.marginEnd = gapPx else lp.marginStart = gapPx
        list.layoutParams = lp
        val maxHeightDp = Math.max(300f, panelPrefs.pickerMaxHeight.toFloat())
        list.setMaxListHeight((maxHeightDp * displayMetrics.density).toInt() - dpToPx(70))

        if (wasOpen) return // Only the content changed
        list.alpha = 0f
        list.visibility = View.VISIBLE
        list.post {
            val listWidth = list.width.toFloat()
            if (listWidth <= 0) return@post
            val stiffness = panelPrefs.animSpeed.toFloat()
            val useSlide = panelPrefs.pickerAnimType == PanelPreferences.ANIM_TYPE_SLIDE
            SpringAnimator.animateOpen(list, if (isRight) -listWidth else listWidth, isPicker = true, stiffness = stiffness, slide = useSlide)
        }
    }

    private fun closeQuickList(immediate: Boolean = false) {
        if (!isQuickListOpen) return
        isQuickListOpen = false
        val list = quickListView ?: return
        if (immediate) {
            list.visibility = View.GONE
            return
        }
        val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
        val listWidth = list.width.toFloat()
        val stiffness = panelPrefs.animSpeed.toFloat()
        val useSlide = panelPrefs.pickerAnimType == PanelPreferences.ANIM_TYPE_SLIDE
        SpringAnimator.animateClose(list, if (isRight) listWidth else -listWidth, isPicker = true, stiffness = stiffness, slide = useSlide) {
            if (!isQuickListOpen) list.visibility = View.GONE
        }
    }

    private fun toggleExtraDim() {
        if (!ExtraDimHelper.isSupported()) {
            notifyUser(getString(R.string.edge_extra_dim_unsupported))
            return
        }
        serviceScope.launch {
            val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                ExtraDimHelper.toggle(this@FloatingPanelService)
            }
            when (result) {
                ExtraDimHelper.Result.ENABLED -> notifyUser(getString(R.string.edge_extra_dim_on))
                ExtraDimHelper.Result.DISABLED -> notifyUser(getString(R.string.edge_extra_dim_off))
                ExtraDimHelper.Result.OPENED_SETTINGS -> {
                    closePanel(immediate = true)
                    android.widget.Toast.makeText(this@FloatingPanelService, R.string.edge_extra_dim_settings_hint, android.widget.Toast.LENGTH_LONG).show()
                }
                ExtraDimHelper.Result.UNSUPPORTED -> notifyUser(getString(R.string.edge_extra_dim_unsupported))
            }
        }
    }

    /** Shows the in-panel indicator while the panel is open, otherwise a toast. */
    private fun notifyUser(text: String) {
        if (isPanelOpen && rootLayout?.parent != null) {
            showIndicator(text)
        } else {
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val sideRect = android.graphics.Rect()
    private val pickerRect = android.graphics.Rect()
    private val quickListRect = android.graphics.Rect()

    private fun initRootLayout() {
        if (rootLayout != null) return

        rootLayout = object : android.widget.FrameLayout(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                // Android 10+ only allows clipboard reads while one of our windows has focus
                if (hasWindowFocus && panelPrefs.clipboardHistoryEnabled) {
                    ClipboardHistoryManager.captureCurrentClip(this@FloatingPanelService)
                }
            }

            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action == android.view.KeyEvent.ACTION_UP && event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (isPickerOpen) {
                        val view = findFocus()
                        if (view is android.widget.EditText && view.hasFocus()) {
                            view.clearFocus()
                            this.requestFocus()
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            imm.hideSoftInputFromWindow(view.windowToken, 0)
                            return true
                        }
                        closePicker()
                        return true
                    } else if (isQuickListOpen) {
                        closeQuickList()
                        return true
                    } else {
                        closePanel()
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true

            // Tap on the background (e.g. the home screen behind the panel): close everything,
            // including an open app drawer or list, and return to the normal Android UI
            setOnClickListener {
                val view = findFocus()
                if (view is android.widget.EditText && view.hasFocus()) {
                    view.clearFocus()
                    this.requestFocus() // take focus away from EditText
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(view.windowToken, 0)
                }
                closePanel()
            }

            // This ensures we can detect if the touch was inside or outside our children
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    val x = event.x.toInt()
                    val y = event.y.toInt()

                    val insideSide = sidePanelView?.let { v ->
                        v.getHitRect(sideRect)
                        sideRect.contains(x, y)
                    } ?: false

                    val insidePicker = if (isPickerOpen) {
                        pickerPanelView?.let { v ->
                            v.getHitRect(pickerRect)
                            pickerRect.contains(x, y)
                        } ?: false
                    } else false

                    val insideQuickList = if (isQuickListOpen) {
                        quickListView?.let { v ->
                            v.getHitRect(quickListRect)
                            quickListRect.contains(x, y)
                        } ?: false
                    } else false

                    if (insideSide || insidePicker || insideQuickList) {
                        // Let the touch pass through to the panel/picker
                        return@setOnTouchListener false
                    }
                }
                // Return false to allow setOnClickListener to handle the tap
                false
            }

            setOnDragListener { v, event ->
                when (event.action) {
                    android.view.DragEvent.ACTION_DRAG_STARTED -> {
                        showDragOverlay(true)
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                        updateDragOverlay(event.y, v.height)
                        true
                    }
                    android.view.DragEvent.ACTION_DROP -> {
                        showDragOverlay(false) // Hide immediately on drop
                        val packageName = event.localState as? String
                        if (packageName != null) {
                            val dropY = event.y
                            val screenHeight = v.height
                            
                            val mode = when {
                                dropY < screenHeight * 0.30 -> SplitScreenHelper.MODE_TOP
                                dropY > screenHeight * 0.70 -> SplitScreenHelper.MODE_BOTTOM
                                else -> SplitScreenHelper.MODE_FREEFORM
                            }
                            
                            // Don't close panel IMMEDIATELY here, wait for DRAG_ENDED
                            // or it can leave a stuck drag shadow on some Android versions.
                            
                            // Delegate to Accessibility Service for higher privilege launch
                            val splitIntent = Intent(this@FloatingPanelService, PanelAccessibilityService::class.java).apply {
                                action = PanelAccessibilityService.ACTION_SPLIT_SCREEN
                                putExtra(PanelAccessibilityService.EXTRA_PKG, packageName)
                                putExtra(PanelAccessibilityService.EXTRA_MODE, mode)
                            }
                            startService(splitIntent)
                        }
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_ENDED -> {
                        showDragOverlay(false) // Safety cleanup
                        // If drop was successful, we close the panel with a small delay
                        // to ensure the system has finished the drag operation completely.
                        if (event.result) {
                            v.postDelayed({
                                closePanel(immediate = true)
                            }, 100)
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        rootParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        
        rootLayout?.addView(sidePanelView)
        rootLayout?.addView(pickerPanelView)
        rootLayout?.addView(quickListView)
    }

    private fun openPanel() {
        if (isPanelOpen || !panelPrefs.serviceEnabled) return
        isPanelOpen = true
        sidePanelView?.resetPageAnimation()
        refreshApps() // Load apps in background while panel opens
        initRootLayout()
        if (rootLayout?.parent == null) {
            windowManager.addView(rootLayout, rootParams)
        }
        updateBlur(true)
        sidePanelView?.updateStyles() // Evaluate Game Mode columns & update layout
        sidePanelView?.let { panel ->
            val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
            val lp = panel.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.width = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            lp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = if (isRight) Gravity.END or Gravity.CENTER_VERTICAL
                         else Gravity.START or Gravity.CENTER_VERTICAL
            panel.layoutParams = lp
            panel.alpha = 0f
            panel.translationX = if (isRight) 1000f else -1000f
            panel.visibility = View.VISIBLE
            panel.post {
                val panelWidth = panel.width.toFloat()
                val stiffness = panelPrefs.animSpeed.toFloat()
                SpringAnimator.animateOpen(panel, if (isRight) panelWidth else -panelWidth, stiffness = stiffness)
            }
        }
        setHandlesVisibility(View.GONE)
    }

    private fun updateBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val shouldBlur = enabled && panelPrefs.blurEnabled
        val blurRadius = panelPrefs.blurAmount
        rootParams?.let { params ->
            if (shouldBlur) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.blurBehindRadius = blurRadius
            } else {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                params.blurBehindRadius = 0
            }
            if (rootLayout?.parent != null) {
                windowManager.updateViewLayout(rootLayout, params)
            }
        }
    }

    fun closePanel(immediate: Boolean = false) {
        // Safety: Don't close if user is still interacting with the trigger handle
        if (handles().any { it.isPressed }) return

        val wasOpen = isPanelOpen
        isPanelOpen = false
        
        if (immediate) {
            if (isPickerOpen) {
                isPickerOpen = false
                pickerPanelView?.visibility = View.GONE
            }
            closeQuickList(immediate = true)
            sidePanelView?.visibility = View.GONE
            updateBlur(false)
            if (rootLayout?.parent != null) {
                try { windowManager.removeViewImmediate(rootLayout) } catch (e: Exception) {}
            }
            setHandlesVisibility(View.VISIBLE)
            sidePanelView?.animatePickerToggle(false)
            
            if (!panelPrefs.serviceEnabled) {
                stopSelf()
            }
            return
        }

        if (!wasOpen) {
            // Safety: if panel is already marked closed but rootLayout is somehow still attached, kill it
            if (rootLayout?.parent != null) {
                try { windowManager.removeView(rootLayout) } catch (e: Exception) {}
            }
            setHandlesVisibility(View.VISIBLE)
            return
        }

        if (isPickerOpen) closePicker()
        closeQuickList()
        sidePanelView?.let { panel ->
            val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
            val panelWidth = panel.width.toFloat()
            val stiffness = panelPrefs.animSpeed.toFloat()
            SpringAnimator.animateClose(panel, if (isRight) panelWidth else -panelWidth, stiffness = stiffness) {
                panel.visibility = View.GONE
                updateBlur(false)
                if (rootLayout?.parent != null) {
                    try { windowManager.removeView(rootLayout) } catch (e: Exception) {}
                }
                setHandlesVisibility(View.VISIBLE)
                panel.animatePickerToggle(false) 
                
                // If service is NOT enabled in prefs, stop it now (Test mode over)
                if (!panelPrefs.serviceEnabled) {
                    stopSelf()
                }
            }
        }
    }

    private fun togglePicker(enableEditMode: Boolean = true) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPickerToggleTime < 600) return
        lastPickerToggleTime = currentTime
        if (isPickerOpen) {
            val currentModeIsEdit = pickerPanelView?.isEditMode ?: false
            if (enableEditMode && !currentModeIsEdit) {
                pickerPanelView?.setEditMode(true)
            } else {
                closePicker()
            }
        } else {
            openPicker(enableEditMode = enableEditMode)
        }
    }

    /** True if sidebar (with [columns]) + gap + 240dp picker + margins fit on the screen. */
    private fun pickerFitsNextTo(columns: Int): Boolean {
        val panelWidth = sidePanelView?.panelWidthPxFor(columns) ?: return false
        val needed = panelWidth + dpToPx(12 + panelPrefs.pickerGap + 240 + 8)
        return needed <= resources.displayMetrics.widthPixels
    }

    private fun openPicker(enableEditMode: Boolean = false) {
        if (isPickerOpen) return
        closeQuickList(immediate = true)
        isPickerOpen = true
        // Keep two columns next to the app drawer when the screen is wide enough
        val pickerColumns = if (panelPrefs.panelColumns >= 2 && pickerFitsNextTo(2)) 2 else 1
        sidePanelView?.setColumns(pickerColumns)
        sidePanelView?.setEditButtonVisible(true)
        sidePanelView?.scrollToBottom()
        sidePanelView?.animatePickerToggle(true)
        pickerPanelView?.let { picker ->
            picker.setEditMode(enableEditMode)
            picker.resetSearch()
            picker.loadApps()
            picker.setOnClickListener { }
            val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
            val density = resources.displayMetrics.density
            val sidePanelWidthPx = sidePanelView?.panelWidthPxFor(pickerColumns) ?: dpToPx(72)
            val sidePanelMarginDp = 12
            
            // Dynamic Height calculation for Picker Panel based on Screen Height
            val displayMetrics = resources.displayMetrics
            val screenHeightPx = displayMetrics.heightPixels
            val screenHeightDp = screenHeightPx / displayMetrics.density
            
            // Max height for picker: User preference, with a sane minimum for usability
            val maxAllowedHeightDp = Math.max(300f, panelPrefs.pickerMaxHeight.toFloat())
            val maxPickerHeightPx = (maxAllowedHeightDp * displayMetrics.density).toInt()

            val lp = android.widget.FrameLayout.LayoutParams(dpToPx(240), android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
            
            lp.gravity = if (isRight) Gravity.CENTER_VERTICAL or Gravity.END
                         else Gravity.CENTER_VERTICAL or Gravity.START
            
            // Fixed alignment calculation: Sidepanel occupies (margin + width) space
            val gapPx = sidePanelWidthPx + ((sidePanelMarginDp + panelPrefs.pickerGap) * displayMetrics.density).toInt()
            if (isRight) lp.marginEnd = gapPx else lp.marginStart = gapPx
            
            picker.layoutParams = lp
            // Force the internal RecyclerView to not exceed a certain height
            picker.setMaxRecyclerViewHeight(maxPickerHeightPx - dpToPx(80)) // Subtract header space (approx 80dp)
            
            picker.alpha = 0f
            picker.visibility = View.VISIBLE
            picker.handleKeyboard()
            picker.post {
                val pickerWidth = picker.width.toFloat()
                if (pickerWidth <= 0) return@post // Wait for layout if not ready
                val startX = if (isRight) -pickerWidth else pickerWidth
                val stiffness = panelPrefs.animSpeed.toFloat()
                val useSlide = panelPrefs.pickerAnimType == PanelPreferences.ANIM_TYPE_SLIDE
                SpringAnimator.animateOpen(picker, startX, isPicker = true, stiffness = stiffness, slide = useSlide)
            }
        }
    }

    private fun closePicker() {
        if (!isPickerOpen) return
        isPickerOpen = false
        pickerPanelView?.hideKeyboard()
        rootLayout?.requestFocus()
        sidePanelView?.animatePickerToggle(false)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isPickerOpen) {
                val originalCols = panelPrefs.panelColumns
                sidePanelView?.setEditButtonVisible(false) 
                sidePanelView?.setColumns(originalCols)
            }
        }, 250)
        pickerPanelView?.let { picker ->
            picker.setEditMode(false)
            picker.invalidateAppList()
            val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
            val pickerWidth = picker.width.toFloat()
            val stiffness = panelPrefs.animSpeed.toFloat()
            val useSlide = panelPrefs.pickerAnimType == PanelPreferences.ANIM_TYPE_SLIDE
            SpringAnimator.animateClose(picker, if (isRight) pickerWidth else -pickerWidth, isPicker = true, stiffness = stiffness, slide = useSlide) {
                if (!isPickerOpen) {
                    picker.visibility = View.GONE
                }
            }
        }
    }

    private fun refreshApps(onComplete: (() -> Unit)? = null) {
        serviceScope.launch {
            val repository = AppRepository(this@FloatingPanelService)
            val page = currentPage()
            sidePanelView?.setPage(page, panelPrefs.getEnabledPages())
            
            val apps = if (currentFolderId != null) {
                when (currentFolderId) {
                    "smartedge.folder.tools" -> {
                        val tools = mutableListOf<AppInfo>()
                        
                        // Always include screenshot in the folder if the folder is active
                        tools.add(AppInfo("smartedge.tool.screenshot", getString(R.string.action_screenshot), type = AppInfo.Type.TOOL))
                        
                        // Add Volume tools
                        tools.add(AppInfo("smartedge.tool.volume_up", getString(R.string.msg_tool_volume_up), type = AppInfo.Type.TOOL))
                        tools.add(AppInfo("smartedge.tool.volume_down", getString(R.string.msg_tool_volume_down), type = AppInfo.Type.TOOL))
                        
                        // Add Brightness tools
                        tools.add(AppInfo("smartedge.tool.brightness_up", getString(R.string.msg_tool_brightness_up), type = AppInfo.Type.TOOL))
                        tools.add(AppInfo("smartedge.tool.brightness_down", getString(R.string.msg_tool_brightness_down), type = AppInfo.Type.TOOL))
                        
                        // Always include power menu in the folder if the folder is active
                        tools.add(AppInfo("smartedge.shortcut.reboot", getString(R.string.action_power_menu), type = AppInfo.Type.SHORTCUT))

                        // Edge features
                        tools.add(AppInfo(TOOL_CLIPBOARD, getString(R.string.edge_tool_clipboard), type = AppInfo.Type.TOOL))
                        tools.add(AppInfo(TOOL_CONTACTS, getString(R.string.edge_tool_contacts), type = AppInfo.Type.TOOL))
                        if (ExtraDimHelper.isSupported()) {
                            tools.add(AppInfo(TOOL_EXTRA_DIM, getString(R.string.edge_tool_extra_dim), type = AppInfo.Type.TOOL))
                        }
                        
                        tools
                    }
                    else -> emptyList<AppInfo>()
                }
            } else if (page == PanelPreferences.PAGE_CONTACTS) {
                contactsPageItems()
            } else if (page == PanelPreferences.PAGE_TOOLS) {
                toolsPageItems()
            } else {
                val baseApps = repository.getPanelApps().toMutableList()
                
                // Tools folder and edge buttons are regular entries of the app list now
                // (see PanelPreferences.migrateToolButtonsToAppList)
                baseApps
            }
            
            sidePanelView?.setApps(apps, onComplete)
        }
    }

    private fun contactsPageItems(): List<AppInfo> {
        val contacts = FavoriteContactsManager.getContacts(this)
        if (contacts.isEmpty()) {
            return listOf(AppInfo(EdgeTools.CONTACTS_SETUP, getString(R.string.feature_contacts_add), type = AppInfo.Type.TOOL))
        }
        return contacts.mapIndexed { index, contact ->
            AppInfo(EdgeTools.CONTACT_PREFIX + index, contact.name, type = AppInfo.Type.TOOL)
        }
    }

    private fun toolsPageItems(): List<AppInfo> {
        return panelPrefs.getToolsPageItems().mapNotNull { id ->
            val tool = EdgeTools.find(id) ?: return@mapNotNull null
            if (id == TOOL_EXTRA_DIM && !ExtraDimHelper.isSupported()) return@mapNotNull null
            AppInfo(tool.id, getString(tool.labelRes), type = AppInfo.Type.TOOL)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.panel_notification_channel),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.panel_notification_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val stopIntent = Intent(this, FloatingPanelService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Use ToggleActivity to ensure the notification shade collapses automatically
        val openIntent = Intent(this, ToggleActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openMainIntent = Intent(this, MainActivity::class.java)
        val openMainPending = PendingIntent.getActivity(
            this, 0, openMainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.panel_running))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openMainPending)
            .addAction(android.R.drawable.ic_menu_view,
                getString(R.string.msg_open_sidebar), openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.stop_panel), stopPending)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private var indicatorText: android.widget.TextView? = null
    private var indicatorFadeRunnable: Runnable? = null

    private var tvTopZone: android.widget.TextView? = null
    private var tvBottomZone: android.widget.TextView? = null
    private var tvFreeformZone: android.widget.TextView? = null

    private fun initDragOverlay() {
        if (dragOverlay != null) return
        
        val density = resources.displayMetrics.density
        dragOverlay = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#4D000000")) // 30% Dim
        }

        val createZone = { text: String, grav: Int ->
            android.widget.TextView(this).apply {
                this.text = text
                setTextColor(android.graphics.Color.WHITE)
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#33FFFFFF"))
                    setStroke(dpToPx(2), android.graphics.Color.parseColor("#80FFFFFF"))
                    cornerRadius = dpToPx(16).toFloat()
                }
                alpha = 0.5f
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    0
                ).apply {
                    gravity = grav
                    val m = dpToPx(20)
                    setMargins(m, m, m, m)
                }
            }
        }

        tvTopZone = createZone(getString(R.string.msg_drop_zone_top_split), Gravity.TOP).apply { 
            layoutParams.height = (resources.displayMetrics.heightPixels * 0.28).toInt()
        }
        tvBottomZone = createZone(getString(R.string.msg_drop_zone_bottom_split), Gravity.BOTTOM).apply { 
            layoutParams.height = (resources.displayMetrics.heightPixels * 0.28).toInt()
        }
        tvFreeformZone = createZone(getString(R.string.msg_drop_zone_freeform), Gravity.CENTER).apply { 
            layoutParams.height = (resources.displayMetrics.heightPixels * 0.30).toInt()
        }

        dragOverlay?.addView(tvTopZone)
        dragOverlay?.addView(tvBottomZone)
        dragOverlay?.addView(tvFreeformZone)

        dragOverlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun showDragOverlay(show: Boolean) {
        if (show) {
            initDragOverlay()
            val overlay = dragOverlay ?: return
            if (!overlay.isAttachedToWindow && dragOverlayParams != null) {
                try {
                    windowManager.addView(overlay, dragOverlayParams)
                } catch (e: Exception) { e.printStackTrace() }
            }
            overlay.visibility = View.VISIBLE
            overlay.animate().cancel()
            overlay.alpha = 0f
            overlay.animate().alpha(1f).setDuration(200).start()
        } else {
            val overlay = dragOverlay ?: return
            overlay.animate().cancel()
            overlay.animate().alpha(0f).setDuration(200).withEndAction {
                overlay.visibility = View.GONE
                if (overlay.isAttachedToWindow) {
                    try {
                        windowManager.removeView(overlay)
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }.start()
        }
    }

    private fun updateDragOverlay(y: Float, screenHeight: Int) {
        val reset = { v: View? -> 
            v?.alpha = 0.5f
            (v?.background as? android.graphics.drawable.GradientDrawable)?.setColor(android.graphics.Color.parseColor("#33FFFFFF"))
        }
        val highlight = { v: View? -> 
            v?.alpha = 1.0f
            (v?.background as? android.graphics.drawable.GradientDrawable)?.setColor(android.graphics.Color.parseColor("#804A9EFF"))
        }

        reset(tvTopZone)
        reset(tvBottomZone)
        reset(tvFreeformZone)

        when {
            y < screenHeight * 0.30 -> highlight(tvTopZone)
            y > screenHeight * 0.70 -> highlight(tvBottomZone)
            else -> highlight(tvFreeformZone)
        }
    }

    fun adjustVolume(delta: Int) {
        if (delta == 0) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val direction = if (delta > 0) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
        
        // Repeat the adjustment for the magnitude of delta to maintain speed
        repeat(Math.abs(delta)) {
            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, 0)
        }
        
        val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val percent = if (max > 0) (current * 100) / max else 0
        showIndicator(getString(R.string.msg_volume_percent, percent))
    }

    fun adjustBrightness(delta: Int) {
        if (delta == 0) return
        try {
            if (!android.provider.Settings.System.canWrite(this)) {
                android.widget.Toast.makeText(this, R.string.msg_requires_write_system_settings, android.widget.Toast.LENGTH_SHORT).show()
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            }

            val cResolver = contentResolver
            // 1. Ensure manual mode to allow manual override
            android.provider.Settings.System.putInt(cResolver, 
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)

            // 2. Update standard int brightness (0-255)
            var brightness = android.provider.Settings.System.getInt(cResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 125)
            brightness = (brightness + delta).coerceIn(0, 255)
            android.provider.Settings.System.putInt(cResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, brightness)
            
            // 3. Update modern float brightness for slider sync on Android 10+
            val floatVal = brightness / 255f
            try {
                android.provider.Settings.System.putFloat(cResolver, "screen_brightness_float", floatVal)
            } catch (e: Exception) {
                try {
                    android.provider.Settings.System.putString(cResolver, "screen_brightness_float", floatVal.toString())
                } catch (e2: Exception) {}
            }

            // Force a notification change to refresh system UI slider
            try {
                cResolver.notifyChange(android.provider.Settings.System.getUriFor(android.provider.Settings.System.SCREEN_BRIGHTNESS), null)
                cResolver.notifyChange(android.provider.Settings.System.getUriFor("screen_brightness_float"), null)
            } catch (e: Exception) {}

            val percent = (brightness * 100) / 255
            showIndicator(getString(R.string.msg_brightness_percent, percent))
        } catch (e: Exception) {
            android.util.Log.e("FloatingPanelService", "Failed to adjust brightness", e)
        }
    }

    private fun showIndicator(text: String) {
        val root = rootLayout
        if (root != null) {
            if (indicatorText == null) {
                val density = resources.displayMetrics.density

                indicatorText = android.widget.TextView(this).apply {
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 14f
                    setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
                    gravity = android.view.Gravity.CENTER
                    
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#E6303030"))
                        cornerRadius = 24f * density
                    }

                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                        bottomMargin = (90 * density).toInt()
                    }
                    elevation = 8f * density
                }
                root.addView(indicatorText)
            }

            indicatorText?.text = text
            indicatorText?.visibility = View.VISIBLE
            indicatorText?.alpha = 1f
            indicatorText?.animate()?.cancel()
            
            indicatorFadeRunnable?.let { handler.removeCallbacks(it) }
            indicatorFadeRunnable = Runnable {
                indicatorText?.animate()
                    ?.alpha(0f)
                    ?.setDuration(300)
                    ?.withEndAction { indicatorText?.visibility = View.GONE }
                    ?.start()
            }
            handler.postDelayed(indicatorFadeRunnable!!, 1500)
        }
    }
}
