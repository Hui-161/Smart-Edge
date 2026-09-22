package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.content.Intent

class NotchHandleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val TAG = "NotchHandleView"
    }

    private val panelPrefs = PanelPreferences(context)
    private val handler = Handler(Looper.getMainLooper())
    private var tapCount = 0
    private val tapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong()

    private val tapRunnable = Runnable {
        val currentCount = tapCount
        tapCount = 0
        when (currentCount) {
            1 -> performAction(panelPrefs.notchTapAction)
            2 -> {
                val action = panelPrefs.notchDoubleTapAction
                if (action != PanelPreferences.ACTION_NONE) {
                    performAction(action)
                } else {
                    performAction(panelPrefs.notchTapAction)
                }
            }
            3 -> {
                val action = panelPrefs.notchTripleTapAction
                if (action != PanelPreferences.ACTION_NONE) {
                    performAction(action)
                } else {
                    val doubleAction = panelPrefs.notchDoubleTapAction
                    if (doubleAction != PanelPreferences.ACTION_NONE) {
                        performAction(doubleAction)
                    } else {
                        performAction(panelPrefs.notchTapAction)
                    }
                }
            }
            else -> if (currentCount > 3) performAction(panelPrefs.notchTripleTapAction)
        }
    }

    private val longPressRunnable = Runnable {
        if (panelPrefs.notchLongPressAction != PanelPreferences.ACTION_NONE) {
            performAction(panelPrefs.notchLongPressAction)
            vibrateHaptic(40)
        }
    }

    private fun performAction(actionId: Int) {
        ActionDispatcher.performAction(
            context = context,
            actionId = actionId,
            panelPrefs = panelPrefs,
            onTriggerPanel = {
                val intent = Intent(context, FloatingPanelService::class.java).apply {
                    action = FloatingPanelService.ACTION_OPEN
                }
                context.startService(intent)
            }
        )
    }

    private fun vibrateHaptic(durationMs: Long = 25) {
        if (!panelPrefs.hapticEnabled) return
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(durationMs)
        }
    }

    private var downTime = 0L
    private var downRawY = 0f
    private var swipedDown = false
    private val swipeDownThreshold = 24 * resources.displayMetrics.density

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downTime = System.currentTimeMillis()
                downRawY = event.rawY
                swipedDown = false
                // A new finger-down continues a pending tap sequence (double/triple tap)
                if (tapCount > 0) handler.removeCallbacks(tapRunnable)
                handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (swipedDown) {
                    swipedDown = false
                    return true
                }
                if (event.action == MotionEvent.ACTION_UP) {
                    val duration = System.currentTimeMillis() - downTime
                    if (duration < ViewConfiguration.getLongPressTimeout()) {
                        // If no double/triple tap actions are set, execute single tap immediately
                        if (panelPrefs.notchDoubleTapAction == PanelPreferences.ACTION_NONE &&
                            panelPrefs.notchTripleTapAction == PanelPreferences.ACTION_NONE) {
                            performAction(panelPrefs.notchTapAction)
                        } else {
                            tapCount++
                            handler.removeCallbacks(tapRunnable)
                            handler.postDelayed(tapRunnable, tapTimeoutMs)
                        }
                        performClick()
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // The trigger covers the top of the status bar: keep pulling down the notification shade working
                if (!swipedDown && event.rawY - downRawY > swipeDownThreshold) {
                    swipedDown = true
                    handler.removeCallbacks(longPressRunnable)
                    ActionDispatcher.performAction(context, PanelPreferences.ACTION_NOTIFICATIONS, panelPrefs)
                    return true
                }
                val dx = Math.abs(event.x - width / 2)
                val dy = Math.abs(event.y - height / 2)
                if (dx > width || dy > height) {
                    handler.removeCallbacks(longPressRunnable)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        /*
        val isDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) {
            val paint = android.graphics.Paint().apply {
                color = Color.RED
                alpha = 80 // Semi-transparent red
                style = android.graphics.Paint.Style.FILL
            }
            // Draw a rectangle covering the entire hit area
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        */
    }

    init {
        // Transparent but clickable
        setBackgroundColor(Color.TRANSPARENT)
    }
}
