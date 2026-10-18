package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Level slider shown while sliding on an edge handle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LevelIndicatorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun LevelIndicator.views(): List<LevelIndicator.LevelView> =
        listOfNotNull(shownView as? LevelIndicator.LevelView)

    @Test
    fun showsValueNextToTheHandleAndHidesAgain() {
        val indicator = LevelIndicator(context)
        indicator.show(LevelIndicator.Kind.BRIGHTNESS, 42, rightSide = true, edgeOffsetPx = 30, centerYOffsetPx = -100)
        shadowOf(Looper.getMainLooper()).idle()
        val view = indicator.views().single()
        assertTrue(view.isAttachedToWindow)
        assertEquals(42, view.percent)
        assertEquals(LevelIndicator.Kind.BRIGHTNESS, view.kind)
        val params = view.layoutParams as WindowManager.LayoutParams
        assertEquals(Gravity.END or Gravity.CENTER_VERTICAL, params.gravity)
        assertEquals(-100, params.y)
        assertTrue("never steals touches", params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)

        // Further slides update the same window
        indicator.show(LevelIndicator.Kind.BRIGHTNESS, 150, rightSide = true, edgeOffsetPx = 30, centerYOffsetPx = -100)
        assertEquals(1, indicator.views().size)
        assertEquals("clamped to 100", 100, indicator.views().single().percent)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LevelIndicator.HIDE_DELAY_MS + 500))
        assertTrue("window removed after the delay", indicator.views().isEmpty())
    }

    @Test
    fun volumeOnLeftSideShowsMutedValue() {
        val indicator = LevelIndicator(context)
        indicator.show(LevelIndicator.Kind.VOLUME, 0, rightSide = false, edgeOffsetPx = 30, centerYOffsetPx = 0)
        val view = indicator.views().single()
        assertEquals(Gravity.START or Gravity.CENTER_VERTICAL, (view.layoutParams as WindowManager.LayoutParams).gravity)
        assertNotNull(view.contentDescription)
        assertEquals(context.getString(R.string.msg_volume_percent, 0), view.contentDescription)
        indicator.release()
        assertTrue(indicator.views().isEmpty())
    }
}
