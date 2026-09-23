package com.imi.smartedge.sidebar.panel

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Storage logic of clipboard history, saved texts, favorite contacts and panel pages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EdgeDataManagersTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppProfilesManager.clockMinutes = null
        AppProfilesManager.modeActiveOverride = null
        listOf("clipboard_history_prefs", "clipboard_snippets_prefs", "favorite_contacts_prefs", "side_panel_prefs", "app_profiles_prefs").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun copy(text: String) {
        // Real copies happen at different times; Robolectric's clock stands still unless advanced
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("test", text))
    }

    @Test
    fun clipboardHistory_recordsNewestFirstWithoutDuplicates() {
        copy("first"); ClipboardHistoryManager.captureCurrentClip(context)
        copy("second"); ClipboardHistoryManager.captureCurrentClip(context)
        copy("first"); ClipboardHistoryManager.captureCurrentClip(context)
        val texts = ClipboardHistoryManager.getEntries(context).map { it.text }
        assertEquals(listOf("first", "second"), texts)
    }

    @Test
    fun clipboardHistory_clearKeepsPinned() {
        copy("keep"); ClipboardHistoryManager.captureCurrentClip(context)
        copy("drop"); ClipboardHistoryManager.captureCurrentClip(context)
        ClipboardHistoryManager.togglePin(context, "keep")
        ClipboardHistoryManager.clearUnpinned(context)
        assertEquals(listOf("keep"), ClipboardHistoryManager.getEntries(context).map { it.text })
    }

    @Test
    fun savedText_isCopiedAsSensitiveAndSkippedByHistory() {
        ClipboardSnippetsManager.add(context, ClipboardSnippetsManager.Snippet("IBAN", "DE00 0000 0000 0000 0000 00"))
        val snippet = ClipboardSnippetsManager.getSnippets(context).single()
        ClipboardSnippetsManager.copy(context, snippet)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(snippet.text, clipboard.primaryClip?.getItemAt(0)?.text.toString())
        ClipboardHistoryManager.captureCurrentClip(context)
        assertTrue("saved texts must not be duplicated into the history", ClipboardHistoryManager.getEntries(context).isEmpty())
    }

    @Test
    fun savedTexts_updateAndRemove() {
        ClipboardSnippetsManager.add(context, ClipboardSnippetsManager.Snippet("A", "1"))
        ClipboardSnippetsManager.add(context, ClipboardSnippetsManager.Snippet("B", "2"))
        ClipboardSnippetsManager.update(context, 0, ClipboardSnippetsManager.Snippet("A2", "11"))
        ClipboardSnippetsManager.remove(context, 1)
        assertEquals(listOf(ClipboardSnippetsManager.Snippet("A2", "11")), ClipboardSnippetsManager.getSnippets(context))
    }

    @Test
    fun favoriteContacts_rejectSameNumberInOtherFormat() {
        assertTrue(FavoriteContactsManager.addContact(context, FavoriteContactsManager.Contact("Max", "+49 170 1234567")))
        assertFalse(FavoriteContactsManager.addContact(context, FavoriteContactsManager.Contact("Max 2", "+491701234567")))
        assertEquals(1, FavoriteContactsManager.getContacts(context).size)
    }

    @Test
    fun panelPages_followSettingsAndDefaults() {
        val prefs = PanelPreferences(context)
        assertEquals(listOf(PanelPreferences.PAGE_APPS, PanelPreferences.PAGE_CONTACTS, PanelPreferences.PAGE_TOOLS), prefs.getEnabledPages())
        prefs.contactsPageEnabled = false
        assertEquals(listOf(PanelPreferences.PAGE_APPS, PanelPreferences.PAGE_TOOLS), prefs.getEnabledPages())
        assertEquals(EdgeTools.DEFAULT_TOOLS_PAGE, prefs.getToolsPageItems())
        prefs.setToolsPageItems(emptyList())
        assertTrue(prefs.getToolsPageItems().isEmpty())
        assertTrue("every default tool exists in the catalog", EdgeTools.DEFAULT_TOOLS_PAGE.all { EdgeTools.find(it) != null })
    }

    @Test
    fun extraDim_toggleNeverThrows() {
        // Without secure-settings permission it must fall back to opening the settings page
        val result = ExtraDimHelper.toggle(context)
        assertTrue(result == ExtraDimHelper.Result.OPENED_SETTINGS ||
                   result == ExtraDimHelper.Result.ENABLED ||
                   result == ExtraDimHelper.Result.DISABLED)
    }

    @Test
    fun favoriteContacts_canBeReordered() {
        FavoriteContactsManager.addContact(context, FavoriteContactsManager.Contact("A", "1"))
        FavoriteContactsManager.addContact(context, FavoriteContactsManager.Contact("B", "2"))
        FavoriteContactsManager.addContact(context, FavoriteContactsManager.Contact("C", "3"))
        FavoriteContactsManager.move(context, 2, -1)
        FavoriteContactsManager.move(context, 0, -1) // no-op at the top
        assertEquals(listOf("A", "C", "B"), FavoriteContactsManager.getContacts(context).map { it.name })
    }

    @Test
    fun dashboardExtraItems_keepOrder() {
        val prefs = PanelPreferences(context)
        assertTrue(prefs.getDashboardExtraItems().isEmpty())
        prefs.setDashboardExtraItems(listOf(EdgeTools.FLASHLIGHT, FloatingPanelService.TOOL_CLIPBOARD))
        assertEquals(listOf(EdgeTools.FLASHLIGHT, FloatingPanelService.TOOL_CLIPBOARD), prefs.getDashboardExtraItems())
    }

    @Test
    fun profiles_timeWindowAcrossMidnight() {
        assertTrue(AppProfilesManager.inWindow(23 * 60, 22 * 60, 6 * 60))
        assertTrue(AppProfilesManager.inWindow(5 * 60, 22 * 60, 6 * 60))
        assertFalse(AppProfilesManager.inWindow(12 * 60, 22 * 60, 6 * 60))
        assertTrue(AppProfilesManager.inWindow(9 * 60, 8 * 60, 17 * 60))
        assertFalse(AppProfilesManager.inWindow(17 * 60, 8 * 60, 17 * 60))
    }

    @Test
    fun profiles_switchAppListByTimeAndMode() {
        val prefs = PanelPreferences(context)
        prefs.setPanelApps(listOf("com.standard"))
        val evening = AppProfilesManager.Profile("e", "Evening", true, 18 * 60, 23 * 60, false)
        val focus = AppProfilesManager.Profile("f", "Focus", false, 0, 0, true)
        AppProfilesManager.add(context, evening, listOf("com.evening"))
        AppProfilesManager.add(context, focus, listOf("com.focus"))

        AppProfilesManager.modeActiveOverride = { false }
        AppProfilesManager.clockMinutes = { 12 * 60 }
        assertEquals(listOf("com.standard"), prefs.getPanelApps())

        AppProfilesManager.clockMinutes = { 19 * 60 }
        assertEquals(listOf("com.evening"), prefs.getPanelApps())
        prefs.addApp("com.new")   // editing changes the active profile only
        assertEquals(listOf("com.standard"), prefs.getStandardPanelApps())

        AppProfilesManager.clockMinutes = { 12 * 60 }
        AppProfilesManager.modeActiveOverride = { true }
        assertEquals(listOf("com.focus"), prefs.getPanelApps())
    }

    @Test
    fun toolButtons_areRegularAppListEntries() {
        val prefs = PanelPreferences(context)
        prefs.setPanelApps(listOf("com.app"))
        prefs.showContactsButton = true
        assertTrue(prefs.getPanelApps().contains(FloatingPanelService.TOOL_CONTACTS))
        prefs.removeApp(FloatingPanelService.TOOL_CONTACTS)   // removed in the sidebar
        assertFalse("switch follows the app list", prefs.showContactsButton)
    }

    @Test
    fun toolButtonsMigration_addsFormerlyEnabledButtonsOnce() {
        val raw = context.getSharedPreferences("side_panel_prefs", Context.MODE_PRIVATE)
        raw.edit().putString("panel_apps", "com.app").putBoolean("show_extra_dim_button", true)
            .putBoolean("show_tools_panel_button", false).commit()
        val prefs = PanelPreferences(context)
        prefs.migrateToolButtonsToAppList()
        assertEquals(listOf(FloatingPanelService.TOOL_EXTRA_DIM, "com.app"), prefs.getPanelApps())
        prefs.removeApp(FloatingPanelService.TOOL_EXTRA_DIM)
        prefs.migrateToolButtonsToAppList() // must not add it again
        assertEquals(listOf("com.app"), prefs.getPanelApps())
    }
}
