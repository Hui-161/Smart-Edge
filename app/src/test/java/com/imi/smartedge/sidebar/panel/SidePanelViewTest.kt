package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Page swiping, close fling and page dots of the sidebar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SidePanelViewTest {

    private lateinit var context: Context
    private lateinit var prefs: PanelPreferences
    private var nextPage = 0
    private var closed = 0

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences("side_panel_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context = ContextThemeWrapper(app, R.style.Theme_SidePanel_M3)
        prefs = PanelPreferences(context)
    }

    private fun createPanel(): SidePanelView {
        val panel = SidePanelView(context).apply {
            onNextPage = { nextPage++ }
            onClose = { closed++ }
        }
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY)
        )
        panel.layout(0, 0, 400, 1600)
        return panel
    }

    /** Fast horizontal fling from startX to endX. */
    private fun fling(panel: SidePanelView, startX: Float, endX: Float) {
        val down = SystemClock.uptimeMillis()
        val steps = 5
        panel.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, startX, 800f, 0))
        for (i in 1..steps) {
            val x = startX + (endX - startX) * i / steps
            panel.dispatchTouchEvent(MotionEvent.obtain(down, down + i * 10L, MotionEvent.ACTION_MOVE, x, 800f, 0))
        }
        panel.dispatchTouchEvent(MotionEvent.obtain(down, down + steps * 10L + 5, MotionEvent.ACTION_UP, endX, 800f, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    }

    @Test
    fun rightPanel_swipeRightToLeftShowsNextPage() {
        prefs.panelSide = PanelPreferences.SIDE_RIGHT
        val panel = createPanel()
        fling(panel, 350f, 50f)
        assertEquals(1, nextPage)
        assertEquals(0, closed)
    }

    @Test
    fun rightPanel_swipeTowardsEdgeCloses() {
        prefs.panelSide = PanelPreferences.SIDE_RIGHT
        val panel = createPanel()
        fling(panel, 50f, 350f)
        assertEquals(0, nextPage)
        assertEquals(1, closed)
    }

    @Test
    fun leftPanel_swipeInwardsShowsNextPage() {
        prefs.panelSide = PanelPreferences.SIDE_LEFT
        val panel = createPanel()
        fling(panel, 50f, 350f)
        assertEquals(1, nextPage)
        assertEquals(0, closed)
    }

    @Test
    fun pageDots_showActivePageAndHideForSinglePage() {
        val panel = createPanel()
        val dots = panel.findViewById<LinearLayout>(R.id.pageIndicator)
        panel.setPage(PanelPreferences.PAGE_CONTACTS, listOf(PanelPreferences.PAGE_APPS, PanelPreferences.PAGE_CONTACTS, PanelPreferences.PAGE_TOOLS))
        assertEquals(View.VISIBLE, dots.visibility)
        assertEquals(3, dots.childCount)
        // The active dot is drawn larger
        val sizes = (0 until dots.childCount).map { dots.getChildAt(it).layoutParams.width }
        assertEquals(1, sizes.indexOf(sizes.maxOrNull()))

        panel.setPage(PanelPreferences.PAGE_APPS, listOf(PanelPreferences.PAGE_APPS))
        assertEquals(View.GONE, dots.visibility)
    }

    @Test
    fun pickerButtonOnlyOnAppsPage() {
        val panel = createPanel()
        val pickerButton = panel.findViewById<View>(R.id.btnClose)
        val pages = listOf(PanelPreferences.PAGE_APPS, PanelPreferences.PAGE_TOOLS)
        panel.setPage(PanelPreferences.PAGE_TOOLS, pages)
        assertEquals(View.GONE, pickerButton.visibility)
        panel.setPage(PanelPreferences.PAGE_APPS, pages)
        assertEquals(View.VISIBLE, pickerButton.visibility)
    }
}
