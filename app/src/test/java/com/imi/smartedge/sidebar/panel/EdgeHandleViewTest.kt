package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Simulates real touch sequences on the edge handle: single/double/triple tap, slow double tap,
 * hold, and the slide target per edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EdgeHandleViewTest {

    private lateinit var context: Context
    private lateinit var prefs: PanelPreferences
    private lateinit var handle: EdgeHandleView
    private var triggers = 0
    private val volumeChanges = mutableListOf<Int>()
    private val brightnessChanges = mutableListOf<Int>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("side_panel_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = PanelPreferences(context)
        prefs.hapticEnabled = false
        handle = EdgeHandleView(context).apply {
            onTrigger = { triggers++ }
            onAdjustVolume = { volumeChanges.add(it) }
            onAdjustBrightness = { brightnessChanges.add(it) }
        }
        handle.layout(0, 0, 60, 300)
        idle(10)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun touch(action: Int, x: Float = 30f, y: Float = 150f) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        handle.onTouchEvent(event)
        event.recycle()
    }

    private fun tap(holdMs: Long = 60) {
        touch(MotionEvent.ACTION_DOWN)
        idle(holdMs)
        touch(MotionEvent.ACTION_UP)
    }

    private fun startedServiceActions(): List<String?> {
        val app = shadowOf(context as android.app.Application)
        val actions = mutableListOf<String?>()
        while (true) {
            val intent = app.nextStartedService ?: break
            actions.add(intent.action)
        }
        return actions
    }

    @Test
    fun freshInstall_singleTapOpensPanelImmediately() {
        tap()
        assertEquals("single tap should open the sidebar by default", 1, triggers)
    }

    @Test
    fun singleTapWithoutMultiTapActions_doesNotWaitForTimeout() {
        prefs.tapAction = PanelPreferences.ACTION_OPEN_LAUNCHER
        tap()
        assertEquals(1, triggers)
        idle(1000)
        assertEquals(1, triggers)
    }

    @Test
    fun doubleTap_runsDoubleTapActionOnly() {
        prefs.tapAction = PanelPreferences.ACTION_FLASHLIGHT
        prefs.doubleTapAction = PanelPreferences.ACTION_OPEN_LAUNCHER
        tap()
        idle(120)
        tap()
        idle(1000)
        assertEquals(1, triggers)
        assertTrue(startedServiceActions().none { it == FloatingPanelService.ACTION_TOGGLE_FLASHLIGHT })
    }

    @Test
    fun slowDoubleTap_isStillRecognized() {
        prefs.tapAction = PanelPreferences.ACTION_FLASHLIGHT
        prefs.doubleTapAction = PanelPreferences.ACTION_OPEN_LAUNCHER
        tap()
        idle(250)            // second finger-down just inside the double-tap window
        tap(holdMs = 150)    // ... but released after the window of the first tap ended
        idle(1000)
        assertEquals("slow double tap must not fall back to two single taps", 1, triggers)
        assertTrue(startedServiceActions().none { it == FloatingPanelService.ACTION_TOGGLE_FLASHLIGHT })
    }

    @Test
    fun singleTapWithDoubleTapConfigured_runsSingleActionAfterTimeout() {
        prefs.tapAction = PanelPreferences.ACTION_FLASHLIGHT
        prefs.doubleTapAction = PanelPreferences.ACTION_OPEN_LAUNCHER
        tap()
        idle(1000)
        assertEquals(0, triggers)
        assertTrue(startedServiceActions().contains(FloatingPanelService.ACTION_TOGGLE_FLASHLIGHT))
    }

    @Test
    fun tripleTap_runsTripleTapAction() {
        prefs.tapAction = PanelPreferences.ACTION_NONE
        prefs.doubleTapAction = PanelPreferences.ACTION_FLASHLIGHT
        prefs.tripleTapAction = PanelPreferences.ACTION_OPEN_LAUNCHER
        tap(); idle(100); tap(); idle(100); tap()
        idle(1000)
        assertEquals(1, triggers)
        assertTrue(startedServiceActions().none { it == FloatingPanelService.ACTION_TOGGLE_FLASHLIGHT })
    }

    @Test
    fun hold_runsLongPressAction() {
        prefs.longPressAction = PanelPreferences.ACTION_OPEN_LAUNCHER
        touch(MotionEvent.ACTION_DOWN)
        idle(800)
        touch(MotionEvent.ACTION_UP)
        idle(1000)
        assertEquals("hold should run the long-press action once, not an extra tap", 1, triggers)
    }

    @Test
    fun bothSides_leftHandleSlidesVolumeOnly() {
        handle.seekTarget = EdgeHandleView.SEEK_VOLUME
        handle.allowSideFlip = false
        slideUp()
        assertTrue("left handle should change the volume", volumeChanges.isNotEmpty())
        assertTrue(brightnessChanges.isEmpty())
        assertEquals("a slide is not a tap", 0, triggers)
    }

    @Test
    fun bothSides_rightHandleSlidesBrightnessOnly() {
        handle.seekTarget = EdgeHandleView.SEEK_BRIGHTNESS
        slideUp()
        assertTrue("right handle should change the brightness", brightnessChanges.isNotEmpty())
        assertTrue(volumeChanges.isEmpty())
    }

    @Test
    fun tapFollowedBySlide_doesNotFireStrayTapAction() {
        prefs.tapAction = PanelPreferences.ACTION_OPEN_LAUNCHER
        prefs.doubleTapAction = PanelPreferences.ACTION_FLASHLIGHT
        handle.seekTarget = EdgeHandleView.SEEK_BRIGHTNESS
        tap()
        idle(100)
        slideUp()
        assertTrue(brightnessChanges.isNotEmpty())
        assertEquals("the interrupted tap must not open the panel after the slide", 0, triggers)
        assertTrue(startedServiceActions().none { it == FloatingPanelService.ACTION_TOGGLE_FLASHLIGHT })
    }

    private fun slideUp() {
        touch(MotionEvent.ACTION_DOWN, y = 250f)
        for (step in 1..20) {
            touch(MotionEvent.ACTION_MOVE, y = 250f - step * 10f)
        }
        touch(MotionEvent.ACTION_UP, y = 50f)
        idle(1000)
    }
}
