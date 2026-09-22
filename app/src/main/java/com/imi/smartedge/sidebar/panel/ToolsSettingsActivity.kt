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
        contacts.forEach { contact ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = "${contact.name}\n${contact.number}"
                textSize = 13f
            }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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
