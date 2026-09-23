package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.GridLayoutManager
import com.imi.smartedge.sidebar.panel.databinding.SidePanelLayoutBinding

/**
 * High-performance Side Panel using RecyclerView.
 */
class SidePanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onClose: (() -> Unit)? = null
    var onAppsChanged: (() -> Unit)? = null
    var onAddClick: ((Boolean) -> Unit)? = null
    var onScreenshot: (() -> Unit)? = null
    var onFolderOpen: ((String) -> Unit)? = null
    var onBackNavigation: (() -> Unit)? = null
    var onToolClick: ((String) -> Unit)? = null
    /** Swipe away from the screen edge (right-to-left for a right-side panel): show the next page. */
    var onNextPage: (() -> Unit)? = null

    /** Currently shown page (PanelPreferences.PAGE_*). Tools section and picker button belong to the apps page. */
    var currentPage: String = PanelPreferences.PAGE_APPS
        private set

    private val binding: SidePanelLayoutBinding = SidePanelLayoutBinding.inflate(LayoutInflater.from(context), this, true)
    private val adapter: PanelAppsAdapter
    private val panelPrefs = PanelPreferences(context)
    private var currentCols = 1
    private var isPickerOpenInternal = false
    
    // Track folder navigation
    private val navigationStack = java.util.ArrayDeque<String>()

    private val springRotation: SpringAnimation = SpringAnimation(binding.btnClose, SpringAnimation.ROTATION)

    private fun getFinalScaleFactor(): Float {
        return context.getAutoScalingFactor() * panelPrefs.scaleFactor
    }

    private val updateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateSystemInfo()
            updateHandler.postDelayed(this, 3000)
        }
    }

    private fun updateSystemInfo() {
        if (!panelPrefs.showSysInfo) return

        try {
            val mi = android.app.ActivityManager.MemoryInfo()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            activityManager.getMemoryInfo(mi)
            val availableMegs = mi.availMem / 1048576L
            val totalMegs = mi.totalMem / 1048576L
            val usedMegs = totalMegs - availableMegs
            binding.tvRamUsage.text = context.getString(R.string.msg_ram_usage, usedMegs)

            val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            binding.tvBatTemp.text = context.getString(R.string.msg_battery_temp, temp / 10)
        } catch (e: Exception) {}
    }

    private val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
            if (Math.abs(velocityY) > Math.abs(velocityX)) return false
            if (isRight && velocityX > 1200f) {
                onClose?.invoke()
                return true
            } else if (!isRight && velocityX < -1200f) {
                onClose?.invoke()
                return true
            }
            // Swiping inwards switches to the next page (not while editing or inside a folder)
            val inward = if (isRight) velocityX < -1200f else velocityX > 1200f
            if (inward && !isPickerOpenInternal && navigationStack.isEmpty() && onNextPage != null) {
                onNextPage?.invoke()
                return true
            }
            return false
        }
    })

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    init {
        springRotation.spring = SpringForce().apply {
            dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            stiffness = SpringForce.STIFFNESS_MEDIUM
        }

        binding.panelCard.setOnClickListener { }

        adapter = PanelAppsAdapter(
            context,
            onRemove = { removedApp ->
                panelPrefs.removeApp(removedApp.identifier)
                onAppsChanged?.invoke()
            },
            onAddClick = { isEdit -> onAddClick?.invoke(isEdit) },
            onAppLaunched = { onClose?.invoke() },
            onFolderClick = { folderId ->
                navigationStack.push(folderId)
                updateNavigationUI()
                onFolderOpen?.invoke(folderId)
            },
            onToolClick = { toolId ->
                onToolClick?.invoke(toolId)
            }
        )

        binding.btnBack.setOnClickListener {
            if (panelPrefs.hapticEnabled) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
            SpringAnimator.scalePulse(it)
            navigateBack()
        }

        currentCols = panelPrefs.panelColumns
        adapter.setColumns(currentCols)
        binding.rvPanelApps.layoutManager = GridLayoutManager(context, currentCols).apply {
            // The divider between notification apps and pinned apps spans the full width
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = if (adapter.isSeparator(position)) spanCount else 1
            }
        }
        binding.rvPanelApps.adapter = adapter
        applyThumbLayout()

        binding.rvPanelApps.setHasFixedSize(false)
        binding.rvPanelApps.isNestedScrollingEnabled = false
        binding.rvPanelApps.setItemViewCacheSize(0)
        (binding.rvPanelApps.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = true
        binding.rvPanelApps.recycledViewPool.setMaxRecycledViews(0, 0)

        binding.rvPanelApps.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (panelPrefs.rememberScroll && currentPage == PanelPreferences.PAGE_APPS && navigationStack.isEmpty()) {
                    panelPrefs.lastSidebarScroll = recyclerView.computeVerticalScrollOffset()
                }
            }
        })

        val itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN or 
            androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean {
                return adapter.isEditMode
            }

            override fun getMovementFlags(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Int {
                // Divider and notification apps are not part of the pinned order
                val position = viewHolder.bindingAdapterPosition
                val app = adapter.getApps().getOrNull(position)
                if (viewHolder is PanelAppsAdapter.SeparatorViewHolder || app?.isNotification == true) return 0
                return super.getMovementFlags(recyclerView, viewHolder)
            }

            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                if (viewHolder is PanelAppsAdapter.AddViewHolder) return false
                val from = viewHolder.bindingAdapterPosition
                var to = target.bindingAdapterPosition
                
                if (target is PanelAppsAdapter.SeparatorViewHolder ||
                    adapter.getApps().getOrNull(target.bindingAdapterPosition)?.isNotification == true) return false

                if (target is PanelAppsAdapter.AddViewHolder) {
                    // Snap to the last available app position
                    to = adapter.itemCount - 2
                }
                
                if (from == androidx.recyclerview.widget.RecyclerView.NO_POSITION || to == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return false
                if (from == to) return false
                
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}

            /** Horizontal drag distance of the current drag, to detect "drag out to the app drawer". */
            private var dragDx = 0f

            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                    dragDx = dX
                    // Fade the item while it is dragged far enough out to be removed
                    viewHolder.itemView.alpha = if (isDraggedOut(recyclerView)) 0.4f else 1f
                }
            }

            private fun isDraggedOut(recyclerView: androidx.recyclerview.widget.RecyclerView): Boolean {
                val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
                val inward = if (isRight) -dragDx else dragDx // towards the app drawer
                return inward > recyclerView.width * 0.9f
            }

            override fun clearView(recyclerView: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                val draggedOut = isDraggedOut(recyclerView)
                dragDx = 0f
                if (draggedOut) {
                    // Dropped onto the app drawer: remove from the sidebar
                    val app = adapter.getApps().getOrNull(viewHolder.bindingAdapterPosition)
                    if (app != null && !app.isNotification) {
                        panelPrefs.removeApp(app.identifier)
                        onAppsChanged?.invoke()
                        return
                    }
                }
                val apps = adapter.getApps()
                val identifiers = apps.filter { !it.isNotification && it.identifier != AppInfo.SEPARATOR_ID }
                    .map { it.identifier }
                
                panelPrefs.setPanelApps(identifiers)
                updateSideLayout()
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.rvPanelApps)

        updateSideLayout()

        binding.btnClose.setOnClickListener {
            if (panelPrefs.hapticEnabled) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
            SpringAnimator.scalePulse(it)
            onAddClick?.invoke(false)
        }

        binding.btnScreenshot.setOnClickListener {
            if (panelPrefs.hapticEnabled) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
            SpringAnimator.scalePulse(it)
            onScreenshot?.invoke()
        }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val actionRunnables = mutableMapOf<Int, Runnable>()
        
        var indicatorText: android.widget.TextView? = null
        var indicatorFadeRunnable: Runnable? = null

        val showIndicator = { text: String ->
            val root = parent as? android.widget.FrameLayout
            if (root != null) {
                if (indicatorText == null) {
                    val density = context.resources.displayMetrics.density

                    indicatorText = android.widget.TextView(context).apply {
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 14f
                        setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
                        gravity = android.view.Gravity.CENTER
                        
                        // Custom Toast-like rounded background
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

        val performVolumeChange = { direction: Int, view: View ->
            if (panelPrefs.hapticEnabled) view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            SpringAnimator.scalePulse(view)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, 0)
            
            val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val percent = if (max > 0) (current * 100) / max else 0
            showIndicator(context.getString(R.string.msg_volume_percent, percent))
        }

        // Click listeners for single taps
        binding.btnVolumeUp.setOnClickListener { performVolumeChange(android.media.AudioManager.ADJUST_RAISE, it) }
        binding.btnVolumeDown.setOnClickListener { performVolumeChange(android.media.AudioManager.ADJUST_LOWER, it) }

        // Long click for continuous repeat
        val setupVolumeRepeat = { btn: View, direction: Int ->
            btn.setOnLongClickListener { v ->
                val runnable = object : Runnable {
                    override fun run() {
                        performVolumeChange(direction, v)
                        handler.postDelayed(this, 100)
                    }
                }
                actionRunnables[v.id] = runnable
                handler.post(runnable)
                true
            }
        }
        setupVolumeRepeat(binding.btnVolumeUp, android.media.AudioManager.ADJUST_RAISE)
        setupVolumeRepeat(binding.btnVolumeDown, android.media.AudioManager.ADJUST_LOWER)

        val performBrightnessChange = { delta: Int, view: View ->
            if (panelPrefs.hapticEnabled) view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            SpringAnimator.scalePulse(view)
            try {
                if (!android.provider.Settings.System.canWrite(context)) {
                    android.widget.Toast.makeText(context, R.string.msg_requires_write_system_settings, android.widget.Toast.LENGTH_SHORT).show()
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } else {
                    val cResolver = context.contentResolver
                    // 1. Ensure manual mode
                    android.provider.Settings.System.putInt(cResolver, 
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)

                    // 2. Update standard int brightness
                    var brightness = android.provider.Settings.System.getInt(cResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 125)
                    brightness = (brightness + delta).coerceIn(0, 255)
                    android.provider.Settings.System.putInt(cResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, brightness)
                    
                    // 3. Update modern float brightness for slider sync on Android 10+
                    try {
                        android.provider.Settings.System.putFloat(cResolver, "screen_brightness_float", brightness / 255f)
                    } catch (e: Exception) {}

                    val percent = (brightness * 100) / 255
                    showIndicator(context.getString(R.string.msg_brightness_percent, percent))
                }
            } catch (e: Exception) {
                actionRunnables[view.id]?.let { handler.removeCallbacks(it) }
            }
        }

        binding.btnBrightnessUp.setOnClickListener { performBrightnessChange(15, it) }
        binding.btnBrightnessDown.setOnClickListener { performBrightnessChange(-15, it) }

        val setupBrightnessRepeat = { btn: View, direction: Int ->
            btn.setOnLongClickListener { v ->
                val runnable = object : Runnable {
                    override fun run() {
                        performBrightnessChange(direction, v)
                        handler.postDelayed(this, 100)
                    }
                }
                actionRunnables[v.id] = runnable
                handler.post(runnable)
                true
            }
        }
        setupBrightnessRepeat(binding.btnBrightnessUp, 15)
        setupBrightnessRepeat(binding.btnBrightnessDown, -15)

        // Cancel repeat on release
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        val stopRepeatListener = View.OnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) {
                actionRunnables[v.id]?.let { handler.removeCallbacks(it) }
                actionRunnables.remove(v.id)
            }
            false // Let click and long click propagate
        }
        
        binding.btnVolumeUp.setOnTouchListener(stopRepeatListener)
        binding.btnVolumeDown.setOnTouchListener(stopRepeatListener)
        binding.btnBrightnessUp.setOnTouchListener(stopRepeatListener)
        binding.btnBrightnessDown.setOnTouchListener(stopRepeatListener)

        binding.btnReboot.setOnClickListener {
            if (panelPrefs.hapticEnabled) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
            SpringAnimator.scalePulse(it)
            
            if (panelPrefs.useAutomationForGestures && AutomationManager.isAutomationPossible()) {
                AutomationManager.performPowerMenu()
            } else {
                // Trigger System Power Menu via Accessibility Service
                val intent = Intent(context, PanelAccessibilityService::class.java).apply {
                    action = PanelAccessibilityService.ACTION_SHOW_POWER_MENU
                }
                context.startService(intent)
            }
            onClose?.invoke()
        }

        applyTheme()
    }

    fun updateSideLayout() {
        applyThumbLayout()
        val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
        binding.btnClose.rotation = if (isRight) 180f else 0f

        val scale = getFinalScaleFactor()
        val lp = binding.panelCard.layoutParams
        
        // Scale only the icon area, keeping the padding/chrome fixed
        val newWidthDp = if (currentCols == 2) {
            52f + (88f * scale)
        } else {
            32f + (40f * scale)
        }
        
        lp.width = context.dpToPx(newWidthDp.toInt())
        binding.panelCard.layoutParams = lp

        // Calculate maximum allowed height for the RecyclerView to ensure the panel fits on screen
        val displayMetrics = context.resources.displayMetrics
        val screenHeightPx = displayMetrics.heightPixels
        val screenHeightDp = screenHeightPx / displayMetrics.density
        
        // Subtract estimated height of other UI elements (paddings, tools, close button)
        // Top Padding (12) + Bottom Padding (4) + Tools Margin (4) + Close Btn (48) = 68dp
        var nonAppHeightDp = 68f
        
        val isGameMode = false // panelPrefs.getGameApps().contains(panelPrefs.currentForegroundPackage)
        val showSysInfoEffective = panelPrefs.showSysInfo || isGameMode
        
        if (panelPrefs.showTools && navigationStack.isEmpty() &&
            (currentPage == PanelPreferences.PAGE_APPS || panelPrefs.dashboardOnAllPages)) {
            // Compact dashboard: 32dp rows, pairs side by side
            nonAppHeightDp += 12f + 32f
            val perRow = if (currentCols >= 2) 4 else 2
            val extraRows = (panelPrefs.getDashboardExtraItems().size + perRow - 1) / perRow
            nonAppHeightDp += 32f * extraRows
            if (panelPrefs.showPowerMenu) nonAppHeightDp += 32f
            if (showSysInfoEffective) nonAppHeightDp += 24f
            if (panelPrefs.showVolumeKeys) nonAppHeightDp += 32f
            if (panelPrefs.showBrightnessKeys) nonAppHeightDp += 32f
        }
        
        // Maximum allowed height for RV to keep panel within screen (with 24dp safety margin)
        val maxAllowedRvHeightDp = (screenHeightDp - nonAppHeightDp - 24).coerceAtLeast(100f)
        val targetRvHeightDp = panelPrefs.panelMaxHeight.toFloat().coerceAtMost(maxAllowedRvHeightDp)

        // Apply dynamic height using ConstraintLayout's max-height constraint instead of brittle manual item height guessing
        val rvLp = binding.rvPanelApps.layoutParams
        val maxRvHeightPx = context.dpToPx((targetRvHeightDp * scale).toInt())
        
        val constraintSet = androidx.constraintlayout.widget.ConstraintSet()
        constraintSet.clone(binding.panelCard)
        constraintSet.constrainMaxHeight(binding.rvPanelApps.id, maxRvHeightPx)
        // Ensure height works correctly with constraints
        rvLp.height = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT
        (rvLp as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.matchConstraintMaxHeight = maxRvHeightPx
        (rvLp as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.constrainedHeight = true
        
        constraintSet.applyTo(binding.panelCard)
        binding.rvPanelApps.layoutParams = rvLp

        val containerLp = binding.panelContainer.layoutParams as? android.widget.RelativeLayout.LayoutParams    
        if (containerLp != null) {
            if (isRight) {
                containerLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
                containerLp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                containerLp.marginEnd = context.dpToPx(12)
                containerLp.marginStart = 0
            } else {
                containerLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                containerLp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
                containerLp.marginStart = context.dpToPx(12)
                containerLp.marginEnd = 0
            }
            binding.panelContainer.layoutParams = containerLp
        }
    }

    /**
     * Thumb mode: the first item sits at the bottom, next to the screen edge
     * (bottom right for a right-side panel, bottom left for a left-side panel).
     */
    private fun applyThumbLayout() {
        val grid = binding.rvPanelApps.layoutManager as? GridLayoutManager ?: return
        val thumb = panelPrefs.thumbMode
        val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
        if (grid.reverseLayout != thumb) grid.reverseLayout = thumb
        val direction = if (thumb && isRight) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        if (binding.rvPanelApps.layoutDirection != direction) binding.rvPanelApps.layoutDirection = direction
    }

    /** Current width of the sidebar card in pixels (used to place the picker next to it). */
    fun panelWidthPx(): Int {
        val measured = binding.panelCard.width
        return if (measured > 0) measured else binding.panelCard.layoutParams.width.coerceAtLeast(0)
    }

    /** Width the sidebar card would have with the given number of columns. */
    fun panelWidthPxFor(cols: Int): Int {
        val scale = getFinalScaleFactor()
        val widthDp = if (cols == 2) 52f + (88f * scale) else 32f + (40f * scale)
        return context.dpToPx(widthDp.toInt())
    }

    fun scrollToTop() {
        binding.rvPanelApps.scrollToPosition(0)
    }

    fun scrollToBottom() {
        val count = adapter.itemCount
        if (count > 0) binding.rvPanelApps.scrollToPosition(count - 1)
    }

    fun scrollToApp(identifier: String) {
        val apps = adapter.currentList
        val index = apps.indexOfFirst { it.identifier == identifier }
        if (index != -1) {
            binding.rvPanelApps.smoothScrollToPosition(index)
            adapter.highlightItem(identifier)
        }
    }

    fun animatePickerToggle(isOpen: Boolean) {
        isPickerOpenInternal = isOpen
        val targetRotation = if (isOpen) 90f else (if (panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT) 180f else 0f)
        springRotation.animateToFinalPosition(targetRotation)
    }

    fun setColumns(cols: Int) {
        currentCols = cols
        adapter.setColumns(cols)
        (binding.rvPanelApps.layoutManager as? GridLayoutManager)?.spanCount = currentCols
        updateSideLayout()
    }

    fun setEditButtonVisible(visible: Boolean) {
        adapter.setShowAddButton(visible)
        updateSideLayout()
    }

    fun navigateBack() {
        if (navigationStack.isNotEmpty()) {
            navigationStack.pop()
            updateNavigationUI()
            onBackNavigation?.invoke()
        }
    }

    /** Switches the page chrome and updates the dots; the items are set via [setApps]. */
    fun setPage(page: String, pages: List<String>) {
        currentPage = page
        updatePageIndicator(pages.indexOf(page).coerceAtLeast(0), pages.size)
        updateNavigationUI()
    }

    /**
     * Slides the current items out in swipe direction, then calls [loadPage] with a callback
     * that slides the new items in once they are set.
     */
    fun animatePageChange(loadPage: (showNewPage: () -> Unit) -> Unit) {
        val isRight = panelPrefs.panelSide == PanelPreferences.SIDE_RIGHT
        val distance = context.dpToPx(24).toFloat() * (if (isRight) -1 else 1)
        val rv = binding.rvPanelApps
        rv.animate().cancel()
        rv.animate().alpha(0f).translationX(distance).setDuration(110).withEndAction {
            loadPage {
                rv.translationX = -distance
                rv.animate().alpha(1f).translationX(0f).setDuration(160).start()
            }
        }.start()
    }

    /** Resets a page animation that was interrupted (e.g. panel closed mid-swipe). */
    fun resetPageAnimation() {
        binding.rvPanelApps.animate().cancel()
        binding.rvPanelApps.alpha = 1f
        binding.rvPanelApps.translationX = 0f
    }

    private fun updatePageIndicator(activeIndex: Int, count: Int) {
        val container = binding.pageIndicator
        container.removeAllViews()
        if (count <= 1) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val accent = try { Color.parseColor(panelPrefs.accentColor) } catch (e: Exception) { Color.WHITE }
        for (i in 0 until count) {
            val active = i == activeIndex
            val size = context.dpToPx(if (active) 7 else 5)
            container.addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (active) accent else Color.parseColor("#66FFFFFF"))
                }
            }, android.widget.LinearLayout.LayoutParams(size, size).apply {
                marginStart = context.dpToPx(3)
                marginEnd = context.dpToPx(3)
            })
        }
        container.contentDescription = context.getString(R.string.edge_page_indicator, activeIndex + 1, count)
    }

    private fun updateNavigationUI() {
        val inFolder = navigationStack.isNotEmpty()
        val onAppsPage = currentPage == PanelPreferences.PAGE_APPS
        binding.btnBack.visibility = if (inFolder) View.VISIBLE else View.GONE
        binding.btnClose.visibility = if (inFolder || !onAppsPage) View.GONE else View.VISIBLE
        applyTheme()
        updateSideLayout()
    }

    fun resetNavigation() {
        navigationStack.clear()
        updateNavigationUI()
    }

    fun setApps(apps: List<AppInfo>, onComplete: (() -> Unit)? = null) {
        adapter.submitList(apps) {
            updateSideLayout()
            
            // Restore scroll position if enabled (only for root level)
            if (panelPrefs.rememberScroll && !panelPrefs.thumbMode && navigationStack.isEmpty() && currentPage == PanelPreferences.PAGE_APPS) {
                binding.rvPanelApps.post {
                    binding.rvPanelApps.scrollBy(0, panelPrefs.lastSidebarScroll)
                }
            } else {
                binding.rvPanelApps.post {
                    // Open at the pinned apps: notification apps stay above and are reached by
                    // scrolling up (in thumb mode position 0 is already the bottom pinned app)
                    val firstPinned = apps.indexOfFirst { !it.isNotification && it.identifier != AppInfo.SEPARATOR_ID }
                    val grid = binding.rvPanelApps.layoutManager as? GridLayoutManager
                    if (!panelPrefs.thumbMode && firstPinned > 0 && grid != null) {
                        grid.scrollToPositionWithOffset(firstPinned, 0)
                    } else {
                        binding.rvPanelApps.scrollToPosition(0)
                    }
                }
            }
            
            onComplete?.invoke()
        }
    }

    fun updateStyles() {
        if (!isPickerOpenInternal) {
            val isGameMode = false // panelPrefs.getGameApps().contains(panelPrefs.currentForegroundPackage)
            currentCols = if (isGameMode) 2 else panelPrefs.panelColumns
            (binding.rvPanelApps.layoutManager as? GridLayoutManager)?.spanCount = currentCols
            adapter.setColumns(currentCols)
        }
        applyTheme()
        updateSideLayout()
    }

    fun refreshIcons() {
        adapter.refreshIcons()
    }

    fun applyTheme() {
        val inFolder = navigationStack.isNotEmpty()
        val showTools = panelPrefs.showTools && !inFolder &&
                        (currentPage == PanelPreferences.PAGE_APPS || panelPrefs.dashboardOnAllPages)
        val extraCount = renderDashboardExtras()
        binding.toolsContainer.visibility = if (showTools) View.VISIBLE else View.GONE
        
        val showPower = panelPrefs.showPowerMenu
        binding.layoutPowerTools.visibility = if (showPower) View.VISIBLE else View.GONE
        
        val showVolume = panelPrefs.showVolumeKeys
        binding.layoutVolumeTools.visibility = if (showVolume) View.VISIBLE else View.GONE
        
        val showBrightness = panelPrefs.showBrightnessKeys
        binding.layoutBrightnessTools.visibility = if (showBrightness) View.VISIBLE else View.GONE
        
        val showScreenshot = panelPrefs.showScreenshotTool
        binding.btnScreenshot.visibility = if (showScreenshot) View.VISIBLE else View.GONE
        // Dashboard shows icons only (labels stay hidden, the buttons carry content descriptions)

        if (panelPrefs.hideBackground) {
            binding.panelCard.background = null
        } else {
            val theme = panelPrefs.uiTheme
            
            // Revert to original dark-centric colors for floating panel
            val bgColor = when (theme) {
                PanelPreferences.THEME_ORIGIN -> Color.parseColor("#1F1F1F")
                PanelPreferences.THEME_HYPEROS -> Color.parseColor("#E6252525")
                else -> try { Color.parseColor(panelPrefs.panelBackgroundColor) } catch (e: Exception) { Color.parseColor("#E61A1C1E") }
            }
            
            val radius = context.dpToPx(if (theme == PanelPreferences.THEME_HYPEROS) 16 else panelPrefs.panelCornerRadius).toFloat()
            
            val shape = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = radius
                
                if (theme == PanelPreferences.THEME_HYPEROS) {
                    setStroke(context.dpToPx(1), Color.parseColor("#4DFFFFFF"))
                } else if (theme == PanelPreferences.THEME_RICH) {
                    val accent = try { Color.parseColor(panelPrefs.accentColor) } catch (e: Exception) { Color.parseColor("#4A9EFF") }
                    setStroke(context.dpToPx(2), accent)
                } else if (theme == PanelPreferences.THEME_REALME) {
                    val color1 = Color.parseColor("#333333")
                    val color2 = Color.parseColor("#1A1A1A")
                    colors = intArrayOf(color1, color2)
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    setStroke(context.dpToPx(1), Color.parseColor("#33FFFFFF"))
                }
            }
            binding.panelCard.background = shape
            
            // Force white/light icons and text for dark floating panel
            val iconColorList = ColorStateList.valueOf(Color.WHITE)
            binding.btnClose.imageTintList = iconColorList
            binding.btnScreenshot.imageTintList = iconColorList
            binding.btnVolumeUp.imageTintList = iconColorList
            binding.btnVolumeDown.imageTintList = iconColorList
            binding.btnBrightnessUp.imageTintList = iconColorList
            binding.btnBrightnessDown.imageTintList = iconColorList
            binding.btnReboot.imageTintList = iconColorList
            binding.btnBack.imageTintList = iconColorList
            
            binding.tvRamUsage.setTextColor(Color.WHITE)
            binding.tvBatTemp.setTextColor(Color.WHITE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                binding.panelCard.clipToOutline = true
            }
        }
        
        val isGameMode = false // panelPrefs.getGameApps().contains(panelPrefs.currentForegroundPackage)
        val showSysInfoEffective = panelPrefs.showSysInfo || isGameMode
        
        binding.layoutSysInfo.visibility = if (showSysInfoEffective) View.VISIBLE else View.GONE
        
        // Final visibility check for tools container: hide if all sub-elements are gone
        val hasAnyVisibleTool = showPower || showVolume || showBrightness || showScreenshot || showSysInfoEffective || extraCount > 0
        binding.toolsContainer.visibility = if (showTools && hasAnyVisibleTool) View.VISIBLE else View.GONE

        if (showSysInfoEffective) {
            updateSystemInfo()
            updateHandler.removeCallbacks(updateRunnable)
            updateHandler.post(updateRunnable)
        } else {
            updateHandler.removeCallbacks(updateRunnable)
        }
    }

    private var renderedDashboardExtras: String? = null

    /**
     * Builds the additional dashboard buttons chosen in settings as a compact grid
     * (2 per row, 4 with two columns); returns how many are shown.
     */
    private fun renderDashboardExtras(): Int {
        val container = binding.dashboardExtraContainer
        val ids = panelPrefs.getDashboardExtraItems().filter { EdgeTools.find(it) != null }
        val perRow = if (currentCols >= 2) 4 else 2
        val key = ids.joinToString(",") + "|" + perRow
        if (key == renderedDashboardExtras) return ids.size
        renderedDashboardExtras = key
        container.removeAllViews()
        container.visibility = if (ids.isEmpty()) View.GONE else View.VISIBLE
        ids.chunked(perRow).forEach { rowIds ->
            val row = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
            }
            rowIds.forEach { id ->
                val tool = EdgeTools.find(id) ?: return@forEach
                row.addView(android.widget.ImageButton(context).apply {
                    setBackgroundResource(R.drawable.bg_close_btn)
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1AFFFFFF"))
                    setImageResource(tool.iconRes)
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    setPadding(context.dpToPx(6), context.dpToPx(6), context.dpToPx(6), context.dpToPx(6))
                    contentDescription = context.getString(tool.labelRes)
                    setOnClickListener {
                        if (panelPrefs.hapticEnabled) it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                        SpringAnimator.scalePulse(it)
                        onToolClick?.invoke(id)
                    }
                }, android.widget.LinearLayout.LayoutParams(context.dpToPx(28), context.dpToPx(28)).apply {
                    setMargins(context.dpToPx(2), context.dpToPx(2), context.dpToPx(2), context.dpToPx(2))
                })
            }
            container.addView(row)
        }
        return ids.size
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        updateHandler.removeCallbacks(updateRunnable)
    }
}
