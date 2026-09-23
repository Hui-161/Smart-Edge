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
        listOf("clipboard_history_prefs", "clipboard_snippets_prefs", "favorite_contacts_prefs", "side_panel_prefs").forEach {
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
}
