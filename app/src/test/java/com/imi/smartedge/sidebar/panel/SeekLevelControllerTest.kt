package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Slide on a handle: value follows the finger, system values are written behind it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SeekLevelControllerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var indicator: LevelIndicator
    private lateinit var controller: SeekLevelController
    private lateinit var handle: EdgeHandleView

    @Before
    fun setUp() {
        indicator = LevelIndicator(context)
        controller = SeekLevelController(context, indicator).apply {
            writeSynchronously = true
            canWriteSettings = { true }
        }
        handle = EdgeHandleView(context).apply {
            isRightSide = true
            layoutParams = WindowManager.LayoutParams(40, 300)
        }
    }

    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Test
    fun brightnessCurve_matchesAndroidSlider() {
        assertEquals(0f, BrightnessCurve.sliderToLinear(0f), 0.0001f)
        assertEquals(1f, BrightnessCurve.sliderToLinear(1f), 0.001f)
        // The middle of the system slider is far below half the linear brightness
        assertTrue(BrightnessCurve.sliderToLinear(0.5f) < 0.15f)
        for (i in 0..20) {
            val slider = i / 20f
            assertEquals(slider, BrightnessCurve.linearToSlider(BrightnessCurve.sliderToLinear(slider)), 0.001f)
        }
    }

    @Test
    fun brightness_followsFingerAndIsWritten() {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        assertTrue(controller.start(isVolume = false, handle))
        val startFraction = controller.fraction
        controller.move(0.1f)
        assertEquals(startFraction + 0.1f, controller.fraction, 0.0001f)
        val shown = indicator.shownView as LevelIndicator.LevelView
        assertEquals("indicator shows exactly the finger position", controller.fraction, shown.fill, 0.0001f)

        val written = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        val expected = Math.round(BrightnessCurve.sliderToLinear(controller.fraction) * 255)
        assertEquals(expected, written)
        assertEquals(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE))

        controller.move(5f)
        assertEquals("clamped at 100 %", 1f, controller.fraction, 0f)
        assertEquals(255, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        controller.end()
    }

    @Test
    fun brightness_withoutPermission_doesNotStart() {
        controller.canWriteSettings = { false }
        assertFalse(controller.start(isVolume = false, handle))
        controller.move(0.5f)
        assertEquals(null, indicator.shownView)
    }

    @Test
    fun volume_movesSmoothlyButWritesWholeSteps() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        assertTrue(controller.start(isVolume = true, handle))
        val stepShare = 1f / max

        controller.move(stepShare * 0.3f)
        assertEquals("below half a step the volume stays", 0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        val shown = indicator.shownView as LevelIndicator.LevelView
        assertTrue("but the bar already moves", shown.fill > 0f)

        controller.move(stepShare * 0.4f)
        assertEquals(1, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(100 / max, controller.shownPercent)

        controller.move(-1f)
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        controller.end()
    }
}
