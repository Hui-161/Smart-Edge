package com.imi.smartedge.sidebar.panel

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.imi.smartedge.sidebar.panel.databinding.ActivitySettingsToolsBinding
// import rikka.shizuku.Shizuku
import android.content.pm.PackageManager
import android.Manifest
import android.provider.ContactsContract
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ToolsSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsToolsBinding
    private lateinit var panelPrefs: PanelPreferences
    // private val SHIZUKU_CODE = 1001

    private val pickContactLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        val contact = FavoriteContactsManager.contactFromPickerResult(this, uri) ?: return@registerForActivityResult
        if (!FavoriteContactsManager.addContact(this, contact)) {
            Toast.makeText(this, R.string.feature_contacts_already_added, Toast.LENGTH_SHORT).show()
        }
        renderFavoriteContacts()
        applyOnly()
    }

    private val readContactsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) importStarredContacts()
        else Toast.makeText(this, R.string.feature_contacts_permission_denied, Toast.LENGTH_SHORT).show()
    }

    private val callPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        FavoriteContactsManager.setDirectCallEnabled(this, granted)
        binding.featureDirectCall.isChecked = granted
        if (!granted) Toast.makeText(this, R.string.feature_contacts_permission_denied, Toast.LENGTH_SHORT).show()
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)



        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        panelPrefs = PanelPreferences(this)
        
        loadCurrentSettings()
        setupListeners()
        handleDeepLink()
    }

    private fun handleDeepLink() {
        val targetId = intent.getStringExtra(SettingsMainActivity.EXTRA_SCROLL_TO) ?: return
        val viewId = resources.getIdentifier(targetId, "id", packageName)
        if (viewId != 0) {
            val targetView = findViewById<View>(viewId)
            targetView?.post {
                val rect = android.graphics.Rect()
                targetView.getDrawingRect(rect)
                binding.root.offsetDescendantRectToMyCoords(targetView, rect)
                binding.toolsScrollView.smoothScrollTo(0, rect.top - 200)
                targetView.highlightView()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }

    private fun loadCurrentSettings() {
        binding.featureToolsMaster.isChecked = panelPrefs.showTools
        binding.layoutToolsSubOptions.alpha = if (panelPrefs.showTools) 1.0f else 0.5f
        binding.layoutToolsSubOptions.isEnabled = panelPrefs.showTools
        // binding.divTools.visibility = if (panelPrefs.showTools) View.VISIBLE else View.GONE

        binding.featureSysInfo.isChecked = panelPrefs.showSysInfo
        binding.featurePowerMenu.isChecked = panelPrefs.showPowerMenu
        binding.featureVolumeKeys.isChecked = panelPrefs.showVolumeKeys
        binding.featureBrightnessKeys.isChecked = panelPrefs.showBrightnessKeys
        binding.featureScreenshot.isChecked = panelPrefs.showScreenshotTool
        binding.featureToolsPanel.isChecked = panelPrefs.showToolsPanelButton

        // Edge features
        binding.featureClipboardHistory.isChecked = panelPrefs.clipboardHistoryEnabled
        binding.layoutClipboardOptions.visibility = if (panelPrefs.clipboardHistoryEnabled) View.VISIBLE else View.GONE
        binding.featureContactsButton.isChecked = panelPrefs.showContactsButton
        binding.featureDirectCall.isChecked = FavoriteContactsManager.isDirectCallEnabled(this) &&
                checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        binding.featureExtraDimButton.isChecked = panelPrefs.showExtraDimButton
        binding.featureExtraDimButton.isEnabled = ExtraDimHelper.isSupported()
        renderFavoriteContacts()
        updateExtraDimStatus()

        // Edge panel pages
        binding.featureContactsPage.isChecked = panelPrefs.contactsPageEnabled
        binding.featureToolsPage.isChecked = panelPrefs.toolsPageEnabled
        binding.featureThumbMode.isChecked = panelPrefs.thumbMode
        binding.featureDashboardAllPages.isChecked = panelPrefs.dashboardOnAllPages
        updateToolsPageSummary()
        updateDashboardExtraSummary()
        renderProfiles()
        renderSnippets()
    }

    private fun updateToolsPageSummary() {
        val names = panelPrefs.getToolsPageItems().mapNotNull { EdgeTools.find(it) }.map { getString(it.labelRes) }
        binding.tvToolsPageSummary.text = if (names.isEmpty()) getString(R.string.feature_tools_page_none)
                                          else names.joinToString(", ")
        binding.btnToolsPageItems.isEnabled = panelPrefs.toolsPageEnabled
    }

    private fun showToolsPagePicker() {
        showOrderedToolPicker(R.string.feature_tools_page_choose, panelPrefs.getToolsPageItems()) { ids ->
            panelPrefs.setToolsPageItems(ids)
            updateToolsPageSummary()
            applyOnly()
        }
    }

    private fun updateDashboardExtraSummary() {
        val names = panelPrefs.getDashboardExtraItems().mapNotNull { EdgeTools.find(it) }.map { getString(it.labelRes) }
        binding.tvDashboardExtraSummary.text = if (names.isEmpty()) getString(R.string.feature_tools_page_none)
                                               else names.joinToString(", ")
    }

    /**
     * Choose tools with check boxes and order them with the arrows. Selected tools keep their
     * order at the top, the remaining catalog tools follow below.
     */
    private fun showOrderedToolPicker(titleRes: Int, selected: List<String>, onSave: (List<String>) -> Unit) {
        val order = (selected.filter { EdgeTools.find(it) != null } +
                     EdgeTools.ALL.map { it.id }.filter { it !in selected }).toMutableList()
        val checked = selected.toMutableSet()
        val density = resources.displayMetrics.density
        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), 0)
        }

        fun render() {
            list.removeAllViews()
            order.forEachIndexed { index, id ->
                val tool = EdgeTools.find(id) ?: return@forEachIndexed
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(android.widget.CheckBox(this).apply {
                    text = getString(tool.labelRes)
                    isChecked = id in checked
                    setOnCheckedChangeListener { _, isOn -> if (isOn) checked.add(id) else checked.remove(id) }
                }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(arrowButton(R.string.feature_move_up, 180f, index > 0) {
                    order.add(index - 1, order.removeAt(index)); render()
                })
                row.addView(arrowButton(R.string.feature_move_down, 0f, index < order.size - 1) {
                    order.add(index + 1, order.removeAt(index)); render()
                })
                list.addView(row)
            }
        }
        render()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(android.widget.ScrollView(this).apply { addView(list) })
            .setPositiveButton(R.string.btn_save) { _, _ -> onSave(order.filter { it in checked }) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Small up/down arrow; [rotation] 180 = up, 0 = down (ic_chevron_right rotated by 90 more). */
    private fun arrowButton(descriptionRes: Int, rotation: Float, enabled: Boolean, onClick: () -> Unit): android.widget.ImageButton {
        val size = (40 * resources.displayMetrics.density).toInt()
        return android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = android.content.res.ColorStateList.valueOf(binding.tvExtraDimStatus.currentTextColor)
            this.rotation = rotation + 90f
            background = null
            contentDescription = getString(descriptionRes)
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.25f
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size)
            setOnClickListener { onClick() }
        }
    }

    // ---- App profiles ----

    private fun profileSummary(p: AppProfilesManager.Profile): String {
        val parts = mutableListOf<String>()
        if (p.useTime) parts.add("${AppProfilesManager.formatTime(p.startMinutes)}–${AppProfilesManager.formatTime(p.endMinutes)}")
        if (p.whenModeActive) parts.add(getString(R.string.feature_profile_mode_short))
        val apps = panelPrefs.getPanelAppsForKey(p.panelAppsKey).size
        parts.add(getString(R.string.feature_profile_app_count, apps))
        return parts.joinToString(" · ")
    }

    private fun renderProfiles() {
        val active = AppProfilesManager.activeProfile(this)
        binding.tvActiveProfile.text = getString(R.string.feature_profile_active, active?.name ?: getString(R.string.feature_profile_standard))
        val container = binding.layoutProfiles
        container.removeAllViews()
        AppProfilesManager.getProfiles(this).forEach { profile ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
            }
            row.addView(TextView(this).apply {
                text = "${profile.name}\n${profileSummary(profile)}"
                textSize = 13f
            })
            val actions = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            fun action(labelRes: Int, onClick: () -> Unit) = TextView(this).apply {
                setText(labelRes)
                textSize = 12f
                setTextColor(binding.tvExtraDimStatus.currentTextColor)
                setPadding(0, 12, 40, 12)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            actions.addView(action(R.string.feature_profile_edit_apps) { showProfileAppsPicker(profile) })
            actions.addView(action(R.string.edge_contacts_manage) { showProfileDialog(profile) })
            actions.addView(action(R.string.feature_contacts_remove) {
                AppProfilesManager.remove(this, profile.id)
                renderProfiles()
                applyOnly()
            })
            row.addView(actions)
            container.addView(row)
        }
    }

    /** Add (profile == null) or edit a profile: name, optional time window, optional mode condition. */
    private fun showProfileDialog(profile: AppProfilesManager.Profile?) {
        val density = resources.displayMetrics.density
        var start = profile?.startMinutes ?: 18 * 60
        var end = profile?.endMinutes ?: 7 * 60
        val nameInput = android.widget.EditText(this).apply {
            setHint(R.string.feature_profile_name_hint)
            setSingleLine()
            setText(profile?.name.orEmpty())
        }
        val timeCheck = android.widget.CheckBox(this).apply {
            setText(R.string.feature_profile_use_time)
            isChecked = profile?.useTime ?: true
        }
        val timeButton = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        fun updateTimeText() {
            timeButton.text = "${AppProfilesManager.formatTime(start)} – ${AppProfilesManager.formatTime(end)}"
        }
        updateTimeText()
        timeButton.setOnClickListener {
            android.app.TimePickerDialog(this, { _, h, m ->
                start = h * 60 + m
                android.app.TimePickerDialog(this, { _, h2, m2 ->
                    end = h2 * 60 + m2
                    updateTimeText()
                }, end / 60, end % 60, true).apply { setTitle(R.string.feature_profile_end) }.show()
            }, start / 60, start % 60, true).apply { setTitle(R.string.feature_profile_start) }.show()
        }
        val modeCheck = android.widget.CheckBox(this).apply {
            setText(R.string.feature_profile_when_mode)
            isChecked = profile?.whenModeActive ?: false
        }
        val form = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
            addView(nameInput)
            addView(timeCheck)
            addView(timeButton)
            addView(modeCheck)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (profile == null) R.string.feature_profile_add else R.string.feature_profile_edit)
            .setView(form)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { getString(R.string.feature_profile_default_name) }
                val updated = AppProfilesManager.Profile(
                    profile?.id ?: AppProfilesManager.newId(), name,
                    timeCheck.isChecked, start, end, modeCheck.isChecked
                )
                if (!updated.useTime && !updated.whenModeActive) {
                    Toast.makeText(this, R.string.feature_profile_needs_condition, Toast.LENGTH_LONG).show()
                }
                if (profile == null) AppProfilesManager.add(this, updated, panelPrefs.getStandardPanelApps())
                else AppProfilesManager.update(this, updated)
                renderProfiles()
                applyOnly()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Choose the apps of a profile; tools and shortcuts already in its list are kept. */
    private fun showProfileAppsPicker(profile: AppProfilesManager.Profile) {
        lifecycleScope.launch {
            val allApps = withContext(Dispatchers.IO) { AppRepository(this@ToolsSettingsActivity).getAllApps() }
                .sortedBy { it.appName.lowercase() }
            val current = panelPrefs.getPanelAppsForKey(profile.panelAppsKey)
            val appIds = allApps.map { it.packageName }.toSet()
            val selected = current.filter { it in appIds }.toMutableSet()
            val labels = allApps.map { it.appName }.toTypedArray()
            val checked = allApps.map { it.packageName in selected }.toBooleanArray()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ToolsSettingsActivity)
                .setTitle(getString(R.string.feature_profile_apps_title, profile.name))
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    val pkg = allApps[which].packageName
                    if (isChecked) selected.add(pkg) else selected.remove(pkg)
                }
                .setPositiveButton(R.string.btn_save) { _, _ ->
                    // Keep existing order and non-app entries, append newly selected apps
                    val kept = current.filter { it !in appIds || it in selected }
                    val added = allApps.map { it.packageName }.filter { it in selected && it !in current }
                    panelPrefs.setPanelAppsForKey(profile.panelAppsKey, kept + added)
                    renderProfiles()
                    applyOnly()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun renderSnippets() {
        val container = binding.layoutSnippets
        container.removeAllViews()
        val snippets = ClipboardSnippetsManager.getSnippets(this)
        if (snippets.isEmpty()) {
            container.addView(TextView(this).apply {
                setText(R.string.edge_snippets_empty)
                textSize = 11f
                setPadding(0, 8, 0, 0)
            })
            return
        }
        snippets.forEachIndexed { index, snippet ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener { showSnippetDialog(index, snippet) }
            }
            row.addView(TextView(this).apply {
                text = if (snippet.label.isBlank()) snippet.text else "${snippet.label}\n${snippet.text}"
                textSize = 13f
                maxLines = 3
                setPadding(0, 12, 0, 12)
            }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                setText(R.string.feature_contacts_remove)
                textSize = 12f
                setTextColor(binding.tvExtraDimStatus.currentTextColor) // colorPrimary
                setPadding(24, 16, 8, 16)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    ClipboardSnippetsManager.remove(this@ToolsSettingsActivity, index)
                    renderSnippets()
                    applyOnly()
                }
            })
            container.addView(row)
        }
    }

    /** Add (index = -1) or edit a saved text. */
    private fun showSnippetDialog(index: Int, existing: ClipboardSnippetsManager.Snippet?) {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val labelInput = android.widget.EditText(this).apply {
            setHint(R.string.feature_snippets_label_hint)
            setSingleLine()
            setText(existing?.label.orEmpty())
        }
        val textInput = android.widget.EditText(this).apply {
            setHint(R.string.feature_snippets_text_hint)
            minLines = 2
            setText(existing?.text.orEmpty())
        }
        val form = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(labelInput)
            addView(textInput)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.feature_snippets_add else R.string.feature_snippets_edit)
            .setView(form)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val text = textInput.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                val snippet = ClipboardSnippetsManager.Snippet(labelInput.text.toString().trim(), text)
                if (index >= 0) ClipboardSnippetsManager.update(this, index, snippet)
                else ClipboardSnippetsManager.add(this, snippet)
                renderSnippets()
                applyOnly()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Permission may have been granted via ADB/Shizuku in the meantime
        updateExtraDimStatus()
    }

    private fun updateExtraDimStatus() {
        val (statusRes, showGrant) = when {
            !ExtraDimHelper.isSupported() -> R.string.feature_extra_dim_status_unsupported to false
            ExtraDimHelper.canToggleDirectly(this) -> R.string.feature_extra_dim_status_ok to false
            else -> R.string.feature_extra_dim_status_missing to true
        }
        binding.tvExtraDimStatus.setText(statusRes)
        binding.btnExtraDimGrant.visibility = if (showGrant) View.VISIBLE else View.GONE
    }

    private fun renderFavoriteContacts() {
        val container = binding.layoutFavoriteContacts
        container.removeAllViews()
        val contacts = FavoriteContactsManager.getContacts(this)
        if (contacts.isEmpty()) {
            container.addView(TextView(this).apply {
                setText(R.string.feature_contacts_list_empty)
                textSize = 11f
                setPadding(0, 8, 0, 0)
            })
            return
        }
        contacts.forEachIndexed { index, contact ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = "${contact.name}\n${contact.number}"
                textSize = 13f
            }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(arrowButton(R.string.feature_move_up, 180f, index > 0) {
                FavoriteContactsManager.move(this, index, -1)
                renderFavoriteContacts()
                applyOnly()
            })
            row.addView(arrowButton(R.string.feature_move_down, 0f, index < contacts.size - 1) {
                FavoriteContactsManager.move(this, index, 1)
                renderFavoriteContacts()
                applyOnly()
            })
            row.addView(TextView(this).apply {
                setText(R.string.feature_contacts_remove)
                textSize = 12f
                setTextColor(binding.tvExtraDimStatus.currentTextColor) // colorPrimary
                setPadding(24, 16, 8, 16)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    FavoriteContactsManager.removeContact(this@ToolsSettingsActivity, contact)
                    renderFavoriteContacts()
                    applyOnly()
                }
            })
            container.addView(row)
        }
    }

    private fun importStarredContacts() {
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                FavoriteContactsManager.importStarredContacts(this@ToolsSettingsActivity)
            }
            val message = if (added > 0) getString(R.string.feature_contacts_imported, added)
                          else getString(R.string.feature_contacts_import_none)
            Toast.makeText(this@ToolsSettingsActivity, message, Toast.LENGTH_SHORT).show()
            renderFavoriteContacts()
            applyOnly()
        }
    }

    private fun setupListeners() {
        binding.featureToolsMaster.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showTools = isChecked
            binding.layoutToolsSubOptions.alpha = if (isChecked) 1.0f else 0.5f
            binding.layoutToolsSubOptions.isEnabled = isChecked
            // binding.divTools.visibility = if (isChecked) View.VISIBLE else View.GONE
            applyOnly()
        }

        binding.featureSysInfo.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showSysInfo = isChecked
            applyOnly()
        }

        binding.featurePowerMenu.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showPowerMenu = isChecked
            applyOnly()
        }

        binding.featureVolumeKeys.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showVolumeKeys = isChecked
            applyOnly()
        }

        binding.featureBrightnessKeys.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showBrightnessKeys = isChecked
            applyOnly()
        }

        binding.featureScreenshot.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showScreenshotTool = isChecked
            applyOnly()
        }

        binding.featureToolsPanel.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showToolsPanelButton = isChecked
            applyOnly()
        }

        // ---- Edge features ----
        binding.featureClipboardHistory.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.clipboardHistoryEnabled = isChecked
            if (isChecked) panelPrefs.addApp(FloatingPanelService.TOOL_CLIPBOARD)
            binding.layoutClipboardOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
            applyOnly()
        }

        binding.btnClipboardBackground.setOnClickListener {
            lifecycleScope.launch {
                val granted = withContext(Dispatchers.IO) {
                    ClipboardHistoryManager.grantBackgroundAccess(this@ToolsSettingsActivity)
                }
                val message = if (granted) R.string.feature_clipboard_background_ok else R.string.feature_clipboard_background_failed
                Toast.makeText(this@ToolsSettingsActivity, message, Toast.LENGTH_LONG).show()
            }
        }

        binding.btnClipboardClear.setOnClickListener {
            ClipboardHistoryManager.clearAll(this)
            Toast.makeText(this, R.string.feature_clipboard_cleared, Toast.LENGTH_SHORT).show()
        }

        binding.featureContactsButton.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showContactsButton = isChecked
            applyOnly()
        }

        binding.featureDirectCall.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            } else {
                FavoriteContactsManager.setDirectCallEnabled(this, isChecked)
            }
        }

        binding.btnContactsAdd.setOnClickListener {
            try {
                pickContactLauncher.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.edge_contacts_no_app, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnContactsImport.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                importStarredContacts()
            } else {
                readContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }

        binding.featureExtraDimButton.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showExtraDimButton = isChecked
            applyOnly()
        }

        binding.featureContactsPage.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.contactsPageEnabled = isChecked
            applyOnly()
        }

        binding.featureToolsPage.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.toolsPageEnabled = isChecked
            updateToolsPageSummary()
            applyOnly()
        }

        binding.btnToolsPageItems.setOnClickListener { showToolsPagePicker() }

        binding.featureThumbMode.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.thumbMode = isChecked
            applyOnly()
        }

        binding.featureDashboardAllPages.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.dashboardOnAllPages = isChecked
            applyOnly()
        }

        binding.btnDashboardExtra.setOnClickListener {
            showOrderedToolPicker(R.string.feature_dashboard_extra_choose, panelPrefs.getDashboardExtraItems()) { ids ->
                panelPrefs.setDashboardExtraItems(ids)
                updateDashboardExtraSummary()
                applyOnly()
            }
        }

        binding.btnSnippetAdd.setOnClickListener { showSnippetDialog(-1, null) }

        binding.btnProfileAdd.setOnClickListener { showProfileDialog(null) }

        binding.btnExtraDimGrant.setOnClickListener {
            SecureSettingsDialog.show(this) { updateExtraDimStatus() }
        }
    }

    private fun applyOnly() {
        val intent = Intent(this, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.ACTION_REFRESH
        }
        startService(intent)
    }
}
