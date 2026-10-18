package com.imi.smartedge.sidebar.panel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * Android-style vertical level slider (like the system volume/brightness dialog) that shows the
 * value set by sliding on an edge handle. Lives in its own overlay window, so it is visible while
 * the sidebar is closed, and is removed again after a short time.
 */
class LevelIndicator(private val context: Context) {

    enum class Kind { BRIGHTNESS, VOLUME }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: LevelView? = null
    private val hideRunnable = Runnable { hide() }

    /** The slider currently on screen (null when hidden). */
    internal val shownView: View? get() = view

    /**
     * Shows [percent] (0–100) next to the screen edge.
     * @param rightSide edge the slider sits at
     * @param edgeOffsetPx distance from that edge (e.g. the handle width)
     * @param centerYOffsetPx vertical offset from the screen center (same as the handle's y)
     */
    fun show(kind: Kind, percent: Int, rightSide: Boolean, edgeOffsetPx: Int, centerYOffsetPx: Int) {
        val density = context.resources.displayMetrics.density
        val v = view ?: LevelView(context).also { created ->
            val params = WindowManager.LayoutParams(
                (LevelView.WIDTH_DP * density).toInt(),
                (LevelView.HEIGHT_DP * density).toInt(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Purely visual: never takes focus or touches
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            try {
                windowManager.addView(created, params)
            } catch (e: Exception) {
                android.util.Log.e("LevelIndicator", "Cannot show level overlay", e)
                return
            }
            created.alpha = 0f
            created.animate().alpha(1f).setDuration(120).start()
            view = created
        }

        (v.layoutParams as? WindowManager.LayoutParams)?.let { params ->
            val gravity = (if (rightSide) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            val x = edgeOffsetPx + (12 * density).toInt()
            val maxY = (context.resources.displayMetrics.heightPixels - params.height) / 2
            val y = centerYOffsetPx.coerceIn(-maxY, maxY)
            if (params.gravity != gravity || params.x != x || params.y != y) {
                params.gravity = gravity
                params.x = x
                params.y = y
                try { windowManager.updateViewLayout(v, params) } catch (_: Exception) {}
            }
        }

        v.animate().cancel()
        v.alpha = 1f
        v.setLevel(kind, percent.coerceIn(0, 100))
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        val v = view ?: return
        v.animate().alpha(0f).setDuration(200).withEndAction { remove(v) }.start()
    }

    /** Removes the window immediately (service shutdown). */
    fun release() {
        handler.removeCallbacks(hideRunnable)
        view?.let { remove(it) }
    }

    private fun remove(v: LevelView) {
        if (view === v) view = null
        try { windowManager.removeViewImmediate(v) } catch (_: Exception) {}
    }

    companion object {
        const val HIDE_DELAY_MS = 1200L
    }

    /** The slider itself: rounded track, fill from the bottom, icon and percentage. */
    internal class LevelView(context: Context) : View(context) {

        companion object {
            const val WIDTH_DP = 52
            const val HEIGHT_DP = 196
        }

        private val density = resources.displayMetrics.density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor(context) }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor(context) }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 14 * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val rect = RectF()
        private var icon: Drawable? = null
        private var animator: ValueAnimator? = null

        var kind: Kind = Kind.BRIGHTNESS
            private set
        var percent: Int = 0
            private set
        /** Animated fill (0..1) */
        private var shownFraction = 0f

        fun setLevel(kind: Kind, percent: Int) {
            val kindChanged = kind != this.kind || icon == null
            this.kind = kind
            this.percent = percent
            val iconRes = when {
                kind == Kind.BRIGHTNESS -> R.drawable.ic_level_brightness
                percent == 0 -> R.drawable.ic_level_volume_off
                else -> R.drawable.ic_level_volume
            }
            icon = ContextCompat.getDrawable(context, iconRes)?.mutate()
            contentDescription = context.getString(
                if (kind == Kind.BRIGHTNESS) R.string.msg_brightness_percent else R.string.msg_volume_percent,
                percent
            )

            val target = percent / 100f
            animator?.cancel()
            if (kindChanged || !isAttachedToWindow) {
                shownFraction = target
                invalidate()
            } else {
                animator = ValueAnimator.ofFloat(shownFraction, target).apply {
                    duration = 120
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { shownFraction = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = w / 2f
            val textArea = 28 * density
            val trackTop = textArea

            // Percentage above the track
            canvas.drawText("$percent %", w / 2f, textArea - 10 * density, textPaint)

            // Track
            rect.set(0f, trackTop, w, h)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)

            // Fill from the bottom; like Android it starts as a circle around the icon
            val trackHeight = h - trackTop
            if (shownFraction > 0f) {
                val fillHeight = (w + (trackHeight - w) * shownFraction).coerceAtMost(trackHeight)
                rect.set(0f, h - fillHeight, w, h)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
            }

            // Icon at the bottom: dark on the fill, light on the empty track
            icon?.let { d ->
                val size = (24 * density).toInt()
                val cx = (w / 2f).toInt()
                val cy = (h - w / 2f).toInt()
                d.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
                d.setTint(if (shownFraction > 0f) onAccentColor(context) else Color.WHITE)
                d.draw(canvas)
            }
        }

        private fun accentColor(context: Context): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.getColor(context, android.R.color.system_accent1_200)
            } else Color.parseColor("#A8C7FA")

        private fun onAccentColor(context: Context): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.getColor(context, android.R.color.system_accent1_800)
            } else Color.parseColor("#062E6F")

        private fun trackColor(context: Context): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Same dark surface the system volume dialog uses, slightly translucent
                (ContextCompat.getColor(context, android.R.color.system_neutral1_800) and 0x00FFFFFF) or (0xE6 shl 24)
            } else Color.parseColor("#E6303030")
    }
}
