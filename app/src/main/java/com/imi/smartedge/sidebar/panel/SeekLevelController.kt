package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Log
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Brightness/volume slide on an edge handle with minimal latency:
 * the finger moves a local value that is drawn immediately on every touch event,
 * while the (slow, binder-based) system writes run on a background thread and are coalesced
 * so only the newest value is written.
 */
class SeekLevelController(
    private val context: Context,
    private val indicator: LevelIndicator
) {

    /** Value of the running slide in slider space (0..1), like the system slider position. */
    var fraction = 0f
        private set
    private var volume = false
    private var active = false
    private var maxVolume = 1
    private var lastVolumeIndex = -1
    private var anchor: EdgeHandleView? = null

    private val writerThread = HandlerThread("SmartEdgeLevelWriter").apply { start() }
    private val writer = Handler(writerThread.looper)
    private val pendingLock = Any()
    private var pendingBrightness: Float? = null
    private var pendingVolume: Int? = null
    private var writeScheduled = false
    @Volatile private var floatKeyWritable = true

    /** Test hooks: run writes synchronously; replace the "Modify system settings" check. */
    internal var writeSynchronously = false
    internal var canWriteSettings: () -> Boolean = { Settings.System.canWrite(context) }

    /** Returns false when the slide cannot work (e.g. brightness without "Modify system settings"). */
    fun start(isVolume: Boolean, handle: EdgeHandleView): Boolean {
        volume = isVolume
        anchor = handle
        if (isVolume) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            lastVolumeIndex = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            fraction = lastVolumeIndex / maxVolume.toFloat()
        } else {
            if (!canWriteSettings()) return false
            val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            fraction = BrightnessCurve.linearToSlider(current / 255f)
            post {
                try {
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot switch to manual brightness", e)
                }
            }
        }
        active = true
        render(keepVisible = true)
        return true
    }

    fun move(delta: Float) {
        if (!active) return
        val next = (fraction + delta).coerceIn(0f, 1f)
        if (next == fraction) return
        fraction = next
        if (volume) {
            val index = (fraction * maxVolume).roundToInt()
            if (index != lastVolumeIndex) {
                lastVolumeIndex = index
                queueWrite { pendingVolume = index }
            }
        } else {
            queueWrite { pendingBrightness = BrightnessCurve.sliderToLinear(fraction) }
        }
        render(keepVisible = true)
    }

    fun end() {
        if (!active) return
        active = false
        render(keepVisible = false)
        anchor = null
    }

    fun release() {
        writerThread.quitSafely()
    }

    /** Percentage shown: real volume step, or the slider position for brightness (as Android shows it). */
    val shownPercent: Int
        get() = if (volume) lastVolumeIndex * 100 / maxVolume else (fraction * 100).roundToInt()

    private fun render(keepVisible: Boolean) {
        val handle = anchor ?: return
        val params = handle.layoutParams as? android.view.WindowManager.LayoutParams
        indicator.show(
            if (volume) LevelIndicator.Kind.VOLUME else LevelIndicator.Kind.BRIGHTNESS,
            shownPercent,
            handle.isRightSide,
            params?.width ?: handle.width,
            params?.y ?: 0,
            fill = fraction,
            autoHide = !keepVisible
        )
    }

    private fun queueWrite(update: () -> Unit) {
        synchronized(pendingLock) {
            update()
            if (writeScheduled) return
            writeScheduled = true
        }
        post { flush() }
    }

    private fun post(block: () -> Unit) {
        if (writeSynchronously) block() else writer.post(block)
    }

    /** Writes only the newest values; runs on the writer thread. */
    private fun flush() {
        val brightness: Float?
        val volumeIndex: Int?
        synchronized(pendingLock) {
            brightness = pendingBrightness
            volumeIndex = pendingVolume
            pendingBrightness = null
            pendingVolume = null
            writeScheduled = false
        }
        val resolver = context.contentResolver
        if (brightness != null) {
            val linear: Float = brightness
            try {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS,
                    (linear * 255).roundToInt().coerceIn(1, 255))
                // Keeps the system brightness slider in sync where apps may write it; newer Android
                // versions reject the key, so it is not retried on every move
                if (floatKeyWritable) {
                    try {
                        Settings.System.putFloat(resolver, "screen_brightness_float", linear)
                    } catch (_: Exception) {
                        floatKeyWritable = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set brightness", e)
            }
        }
        if (volumeIndex != null) {
            try {
                (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .setStreamVolume(AudioManager.STREAM_MUSIC, volumeIndex, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set volume", e)
            }
        }
    }

    companion object {
        private const val TAG = "SeekLevelController"
    }
}

/**
 * Android's brightness slider is not linear: it uses the HLG gamma curve
 * (frameworks/base BrightnessUtils), so equal finger travel feels equally bright.
 */
object BrightnessCurve {
    private const val R = 0.5f
    private const val A = 0.17883277f
    private const val B = 0.28466892f
    private const val C = 0.55991073f

    /** Slider position (0..1) → linear brightness (0..1). */
    fun sliderToLinear(slider: Float): Float {
        val v = slider.coerceIn(0f, 1f)
        val ret = if (v <= R) (v / R) * (v / R) else exp((v - C) / A) + B
        return (ret / 12f).coerceIn(0f, 1f)
    }

    /** Linear brightness (0..1) → slider position (0..1). */
    fun linearToSlider(linear: Float): Float {
        val v = linear.coerceIn(0f, 1f) * 12f
        val ret = if (v <= 1f) R * sqrt(v) else A * ln(v - B) + C
        return ret.coerceIn(0f, 1f)
    }
}
