package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Floating list shown next to the side panel. Used for the clipboard history
 * and the favorite contacts ("Edge" features known from Samsung devices).
 */
class QuickListPanelView(context: Context) : FrameLayout(context) {

    enum class Mode { CLIPBOARD, CONTACTS, CONTACT_DETAIL }

    var mode: Mode = Mode.CLIPBOARD
        private set

    /** Contact shown in [Mode.CONTACT_DETAIL]. */
    private var detailContact: FavoriteContactsManager.Contact? = null

    /** Called after an entry was used (copied / call or SMS started). */
    var onEntryUsed: (() -> Unit)? = null
    /** Called to show a short feedback message (e.g. "Copied"). */
    var onMessage: ((String) -> Unit)? = null
    /** Called when the user wants to open the settings; the argument is the view id to scroll to. */
    var onOpenSettings: ((String) -> Unit)? = null

    private val panelPrefs = PanelPreferences(context)
    private val card = LinearLayout(context)
    private val titleView = TextView(context)
    private val headerAction = TextView(context)
    private val scrollView = MaxHeightScrollView(context)
    private val listContainer = LinearLayout(context)
    private val emptyView = TextView(context)

    private val textPrimary = Color.parseColor("#E6FFFFFF")
    private val textSecondary = Color.parseColor("#99FFFFFF")
    private val rowBackground = Color.parseColor("#1AFFFFFF")

    init {
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(14), dp(14), dp(14))
        card.isClickable = true // Keep taps inside the card from closing the panel

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView.apply {
            setTextColor(textPrimary)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        headerAction.apply {
            setTextColor(textSecondary)
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundedBackground(rowBackground, 12)
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(headerAction, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        card.addView(header)

        listContainer.orientation = LinearLayout.VERTICAL
        scrollView.isVerticalScrollBarEnabled = false
        scrollView.addView(listContainer)
        card.addView(scrollView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        emptyView.apply {
            setTextColor(textSecondary)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }

        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        applyTheme()
    }

    fun applyTheme() {
        val bgColor = try {
            Color.parseColor(panelPrefs.panelBackgroundColor)
        } catch (e: Exception) {
            Color.parseColor(PanelPreferences.DEFAULT_PANEL_BG)
        }
        card.background = roundedBackground(bgColor, panelPrefs.panelCornerRadius)
        elevation = dp(8).toFloat()
    }

    /** Limits the list height so the card never exceeds the screen. */
    fun setMaxListHeight(maxHeightPx: Int) {
        scrollView.maxHeightPx = maxHeightPx
        scrollView.requestLayout()
    }

    fun show(newMode: Mode, contact: FavoriteContactsManager.Contact? = null) {
        mode = newMode
        detailContact = contact
        applyTheme()
        reload()
        scrollView.scrollTo(0, 0)
    }

    fun reload() {
        listContainer.removeAllViews()
        when (mode) {
            Mode.CLIPBOARD -> bindClipboard()
            Mode.CONTACTS -> bindContacts()
            Mode.CONTACT_DETAIL -> bindContactDetail()
        }
    }

    // ---- Clipboard ----

    private fun bindClipboard() {
        titleView.text = context.getString(R.string.edge_clipboard_title)
        val historyEnabled = panelPrefs.clipboardHistoryEnabled
        val entries = if (historyEnabled) ClipboardHistoryManager.getEntries(context) else emptyList()
        val snippets = ClipboardSnippetsManager.getSnippets(context)

        headerAction.text = context.getString(R.string.edge_clipboard_clear)
        headerAction.visibility = if (entries.any { !it.pinned }) View.VISIBLE else View.GONE
        headerAction.setOnClickListener {
            haptic(it)
            ClipboardHistoryManager.clearUnpinned(context)
            reload()
        }

        // Permanently saved texts (IBAN, phone number, ...)
        addSectionHeader(context.getString(R.string.edge_snippets_title), context.getString(R.string.edge_contacts_manage)) {
            onOpenSettings?.invoke("btn_snippet_add")
        }
        if (snippets.isEmpty()) {
            addHint(context.getString(R.string.edge_snippets_empty))
        }
        snippets.forEach { snippet ->
            val row = createRow()
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            if (snippet.label.isNotBlank()) {
                texts.addView(TextView(context).apply {
                    text = snippet.label
                    setTextColor(textSecondary)
                    textSize = 11f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            texts.addView(TextView(context).apply {
                text = snippet.text.replace('\n', ' ')
                setTextColor(textPrimary)
                textSize = 13f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_copy)
                imageTintList = ColorStateList.valueOf(textSecondary)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
            row.setOnClickListener {
                haptic(it)
                ClipboardSnippetsManager.copy(context, snippet)
                onMessage?.invoke(context.getString(R.string.edge_clipboard_copied))
                onEntryUsed?.invoke()
            }
            addRow(row)
        }

        if (!historyEnabled) {
            addSectionHeader(context.getString(R.string.edge_history_title), null, null)
            addHint(context.getString(R.string.edge_clipboard_disabled))
            return
        }

        addSectionHeader(context.getString(R.string.edge_history_title), null, null)
        if (entries.isEmpty()) {
            addHint(context.getString(R.string.edge_clipboard_empty))
            return
        }

        entries.forEach { entry ->
            val row = createRow()
            val text = TextView(context).apply {
                this.text = entry.text.replace('\n', ' ')
                setTextColor(textPrimary)
                textSize = 13f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }
            row.addView(text, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

            val pinIcon = if (entry.pinned) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
            row.addView(createIconButton(pinIcon, R.string.edge_clipboard_pin, tint = !entry.pinned) {
                ClipboardHistoryManager.togglePin(context, entry.text)
                reload()
            })
            row.addView(createIconButton(android.R.drawable.ic_menu_delete, R.string.edge_clipboard_delete) {
                ClipboardHistoryManager.remove(context, entry.text)
                reload()
            })

            row.setOnClickListener {
                haptic(it)
                ClipboardHistoryManager.copyToClipboard(context, entry.text)
                onMessage?.invoke(context.getString(R.string.edge_clipboard_copied))
                onEntryUsed?.invoke()
            }
            addRow(row)
        }
    }

    // ---- Contacts ----

    private fun bindContacts() {
        titleView.text = context.getString(R.string.edge_contacts_title)
        headerAction.text = context.getString(R.string.edge_contacts_manage)
        headerAction.visibility = View.VISIBLE
        headerAction.setOnClickListener {
            haptic(it)
            onOpenSettings?.invoke("feature_contacts_button")
        }

        val contacts = FavoriteContactsManager.getContacts(context)
        if (contacts.isEmpty()) {
            showEmpty(context.getString(R.string.edge_contacts_empty))
            return
        }

        contacts.forEach { contact ->
            val row = createRow()

            val avatar = TextView(context).apply {
                text = contact.name.trim().firstOrNull()?.uppercase() ?: "#"
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(avatarColor(contact.name))
                }
            }
            row.addView(avatar, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })

            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply {
                text = contact.name
                setTextColor(textPrimary)
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            texts.addView(TextView(context).apply {
                text = contact.number
                setTextColor(textSecondary)
                textSize = 11f
                maxLines = 1
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

            row.addView(createIconButton(android.R.drawable.sym_action_call, R.string.edge_contacts_call) {
                if (FavoriteContactsManager.call(context, contact)) onEntryUsed?.invoke()
                else onMessage?.invoke(context.getString(R.string.edge_contacts_no_app))
            })
            row.addView(createIconButton(android.R.drawable.sym_action_chat, R.string.edge_contacts_sms) {
                if (FavoriteContactsManager.sendSms(context, contact)) onEntryUsed?.invoke()
                else onMessage?.invoke(context.getString(R.string.edge_contacts_no_app))
            })

            // Tapping the row itself starts a call, like the Samsung People Edge
            row.setOnClickListener {
                haptic(it)
                if (FavoriteContactsManager.call(context, contact)) onEntryUsed?.invoke()
                else onMessage?.invoke(context.getString(R.string.edge_contacts_no_app))
            }
            addRow(row)
        }
    }

    // ---- Single contact (from the contacts page of the sidebar) ----

    private fun bindContactDetail() {
        val contact = detailContact ?: return
        titleView.text = contact.name
        headerAction.visibility = View.GONE

        addHint(contact.number)
        addActionRow(android.R.drawable.sym_action_call, context.getString(R.string.edge_contacts_call)) {
            if (FavoriteContactsManager.call(context, contact)) onEntryUsed?.invoke()
            else onMessage?.invoke(context.getString(R.string.edge_contacts_no_app))
        }
        addActionRow(android.R.drawable.sym_action_chat, context.getString(R.string.edge_contacts_sms)) {
            if (FavoriteContactsManager.sendSms(context, contact)) onEntryUsed?.invoke()
            else onMessage?.invoke(context.getString(R.string.edge_contacts_no_app))
        }
    }

    private fun addActionRow(iconRes: Int, label: String, onClick: () -> Unit) {
        val row = createRow()
        row.addView(ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(textPrimary)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) })
        row.addView(TextView(context).apply {
            text = label
            setTextColor(textPrimary)
            textSize = 14f
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            haptic(it)
            onClick()
        }
        addRow(row)
    }

    // ---- Helpers ----

    private fun addSectionHeader(title: String, actionLabel: String?, onAction: (() -> Unit)?) {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(6), dp(2), dp(4))
        }
        header.addView(TextView(context).apply {
            text = title
            setTextColor(textSecondary)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = true
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (actionLabel != null && onAction != null) {
            header.addView(TextView(context).apply {
                text = actionLabel
                setTextColor(textSecondary)
                textSize = 11f
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = roundedBackground(rowBackground, 10)
                setOnClickListener {
                    haptic(it)
                    onAction()
                }
            })
        }
        listContainer.addView(header)
    }

    private fun addHint(message: String) {
        listContainer.addView(TextView(context).apply {
            text = message
            setTextColor(textSecondary)
            textSize = 12f
            setPadding(dp(4), dp(2), dp(4), dp(8))
        })
    }

    private fun showEmpty(message: String) {
        emptyView.text = message
        (emptyView.parent as? LinearLayout)?.removeView(emptyView)
        listContainer.addView(emptyView)
    }

    private fun createRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(4), dp(8))
        background = roundedBackground(rowBackground, 14)
        isClickable = true
        isFocusable = true
    }

    private fun addRow(row: View) {
        listContainer.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
    }

    private fun createIconButton(iconRes: Int, descriptionRes: Int, tint: Boolean = true, onClick: () -> Unit): ImageView {
        return ImageView(context).apply {
            setImageResource(iconRes)
            if (tint) imageTintList = ColorStateList.valueOf(textPrimary)
            contentDescription = context.getString(descriptionRes)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                haptic(it)
                onClick()
            }
        }
    }

    private fun avatarColor(name: String): Int {
        val palette = intArrayOf(
            Color.parseColor("#5C6BC0"), Color.parseColor("#26A69A"), Color.parseColor("#EF5350"),
            Color.parseColor("#AB47BC"), Color.parseColor("#FFA726"), Color.parseColor("#42A5F5")
        )
        return palette[Math.floorMod(name.hashCode(), palette.size)]
    }

    private fun roundedBackground(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun haptic(view: View) {
        if (panelPrefs.hapticEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    private fun dp(value: Int): Int = context.dpToPx(value)
}

private class MaxHeightScrollView(context: Context) : ScrollView(context) {
    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limited = if (maxHeightPx == Int.MAX_VALUE) heightMeasureSpec
                      else MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, limited)
    }
}
