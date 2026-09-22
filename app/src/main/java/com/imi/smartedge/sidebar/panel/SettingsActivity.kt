package com.imi.smartedge.sidebar.panel

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.imi.smartedge.sidebar.panel.databinding.ActivitySettingsM3Binding

/**
 * Settings screen for panel configuration.
 * Includes real-time preview and premium dashboard.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsM3Binding
    private lateinit var panelPrefs: PanelPreferences

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsM3Binding.inflate(layoutInflater)
        setContentView(binding.root)



        supportActionBar?.apply {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }

        panelPrefs = PanelPreferences(this)
        
        loadCurrentSettings()
        setupListeners()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun loadCurrentSettings() {
        if (panelPrefs.panelSide == PanelPreferences.SIDE_LEFT) {
            binding.rgPanelSide.check(R.id.rbLeft)
        } else {
            binding.rgPanelSide.check(R.id.rbRight)
        }

        binding.switchAutoStart.isChecked = panelPrefs.autoStart
        binding.switchGestures.isChecked = panelPrefs.gesturesEnabled
        binding.tvTapGesturesValue.text = when {
            panelPrefs.tripleTapToOpen -> getString(R.string.msg_triple_tap)
            panelPrefs.doubleTapToOpen -> getString(R.string.msg_double_tap)
            panelPrefs.tapToOpen -> getString(R.string.msg_single_tap)
            else -> getString(R.string.anim_feel_disabled)
        }
        binding.switchShowPill.isChecked = panelPrefs.showPill
        binding.switchHaptic.isChecked = panelPrefs.hapticEnabled
        binding.switchShowLogs.isChecked = panelPrefs.showLogs

        val animSpeed = panelPrefs.animSpeed
        binding.tvAnimFeelValue.text = when (animSpeed) {
            200 -> getString(R.string.anim_feel_calm)
            400 -> getString(R.string.anim_feel_balanced)
            700 -> getString(R.string.anim_feel_snappy)
            1000 -> getString(R.string.anim_feel_instant)
            0 -> getString(R.string.anim_feel_disabled)
            else -> getString(R.string.anim_feel_balanced)
        }

        binding.switchBlur.isChecked = panelPrefs.blurEnabled
        binding.sbBlurAmount.value = panelPrefs.blurAmount.toFloat()
        binding.tvBlurAmountValue.text = panelPrefs.blurAmount.toString()
        binding.layoutBlurAmount.visibility = if (panelPrefs.blurEnabled) View.VISIBLE else View.GONE
        
        binding.switchColumns.isChecked = panelPrefs.panelColumns == 2
        binding.sbOpacity.value = panelPrefs.panelOpacity.toFloat()
        binding.tvOpacityValue.text = "${panelPrefs.panelOpacity}%"
        
        binding.sbPanelRadius.value = panelPrefs.panelCornerRadius.toFloat()
        binding.tvRadiusValue.text = "${panelPrefs.panelCornerRadius}dp"
        
        binding.sbHandleHeight.value = panelPrefs.handleHeight.toFloat()
        binding.tvHeightValue.text = "${panelPrefs.handleHeight}dp"
        
        binding.sbHandleWidth.value = panelPrefs.handleWidth.toFloat()
        binding.tvWidthValue.text = "${panelPrefs.handleWidth}dp"
        
        binding.sbHandleOffset.value = panelPrefs.handleVerticalOffset.toFloat()
        binding.tvOffsetValue.text = "${panelPrefs.handleVerticalOffset}dp"

        binding.sbPickerGap.value = panelPrefs.pickerGap.toFloat()
        binding.tvPickerGapValue.text = "${panelPrefs.pickerGap}dp"

        binding.tvUIStyleValue.text = when (panelPrefs.uiTheme) {
            PanelPreferences.THEME_HYPEROS -> getString(R.string.theme_hyperos)
            PanelPreferences.THEME_REALME -> getString(R.string.theme_realme)
            PanelPreferences.THEME_RICH -> getString(R.string.theme_rich)
            else -> getString(R.string.theme_origin)
        }

        binding.tvIconShapeValue.text = when (panelPrefs.iconShape) {
            PanelPreferences.SHAPE_SQUIRCLE -> getString(R.string.msg_shape_squircle)
            PanelPreferences.SHAPE_SQUARE -> getString(R.string.msg_shape_square)
            PanelPreferences.SHAPE_CIRCLE -> getString(R.string.msg_shape_circle)
            else -> getString(R.string.icon_pack_default)
        }

        binding.switchTools.isChecked = panelPrefs.showTools
        binding.switchHideBg.isChecked = panelPrefs.hideBackground
        binding.switchUseCustomAccent.isChecked = panelPrefs.useCustomAccent

        val pack = panelPrefs.selectedIconPack
        binding.tvCurrentIconPack.text = if (pack == "none") getString(R.string.icon_pack_default) else pack

        try {
            val accentColor = Color.parseColor(panelPrefs.accentColor)
            binding.btnPickAccent.backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
            
            val bgColor = Color.parseColor(panelPrefs.panelBackgroundColor)
            binding.btnPickBg.backgroundTintList = android.content.res.ColorStateList.valueOf(bgColor)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        updateSupportUI()
    }

    private fun updateSupportUI() {
        // All features unlocked by default for F-Droid version
        binding.sbHandleOffset.isEnabled = true
        binding.sbBlurAmount.isEnabled = true
        
        binding.layoutUIStyle.isEnabled = true
        binding.layoutUIStyle.alpha = 1.0f
        
        binding.layoutIconShape.isEnabled = true
        binding.layoutIconShape.alpha = 1.0f

        binding.switchTools.isEnabled = true
        binding.switchHideBg.isEnabled = true
        binding.switchColumns.isEnabled = true
        
        binding.switchUseCustomAccent.isEnabled = true
        binding.sbPanelRadius.isEnabled = true
        binding.btnResetUIColors.isEnabled = true
        
        binding.btnPickAccent.isEnabled = true
        binding.btnPickBg.isEnabled = true
        binding.btnSelectIconPack.isEnabled = true

        binding.tvSupportStatus.text = getString(R.string.msg_support_development)
        binding.btnGoPremium.text = getString(R.string.msg_donate)
        binding.btnGoPremium.visibility = View.VISIBLE
    }

    private fun setupListeners() {
        binding.rgPanelSide.setOnCheckedChangeListener { _, checkedId ->
            panelPrefs.panelSide = if (checkedId == R.id.rbLeft)
                PanelPreferences.SIDE_LEFT else PanelPreferences.SIDE_RIGHT
            applyAndShow()
        }

        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.autoStart = isChecked
        }

        binding.switchGestures.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.gesturesEnabled = isChecked
            applyOnly()
        }

        binding.btnAccessibility.setOnClickListener {
            AccessibilityGuideDialog.newInstance()
                .show(supportFragmentManager, AccessibilityGuideDialog.TAG)
        }

        binding.layoutTapGestures.setOnClickListener {
            val options = arrayOf(getString(R.string.anim_feel_disabled), getString(R.string.msg_single_tap), getString(R.string.msg_double_tap), getString(R.string.msg_triple_tap))
            var selectedIndex = 0
            if (panelPrefs.tapToOpen) selectedIndex = 1
            if (panelPrefs.doubleTapToOpen) selectedIndex = 2
            if (panelPrefs.tripleTapToOpen) selectedIndex = 3

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.msg_tap_to_open)
                .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                    panelPrefs.tapToOpen = (which == 1)
                    panelPrefs.doubleTapToOpen = (which == 2)
                    panelPrefs.tripleTapToOpen = (which == 3)
                    binding.tvTapGesturesValue.text = options[which]
                    applyOnly()
                    dialog.dismiss()
                }
                .show()
        }

        binding.switchShowPill.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showPill = isChecked
            applyOnly()
        }

        binding.switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.hapticEnabled = isChecked
        }

        binding.switchShowLogs.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showLogs = isChecked
        }

        binding.layoutAnimFeel.setOnClickListener {
            val options = arrayOf(getString(R.string.anim_feel_calm), getString(R.string.anim_feel_balanced), getString(R.string.anim_feel_snappy), getString(R.string.anim_feel_instant), getString(R.string.anim_feel_disabled))
            val values = intArrayOf(200, 400, 700, 1000, 0)

            var selectedIndex = values.indexOf(panelPrefs.animSpeed)
            if (selectedIndex == -1) selectedIndex = 1 // Default to Balanced

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_anim_feel)
                .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                    panelPrefs.animSpeed = values[which]
                    binding.tvAnimFeelValue.text = options[which]
                    applyOnly()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.msg_cancel, null)
                .show()
        }

        binding.switchBlur.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.blurEnabled = isChecked
            binding.layoutBlurAmount.visibility = if (isChecked) View.VISIBLE else View.GONE
            applyOnly()
        }

        binding.sbBlurAmount.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val amount = value.toInt()
                panelPrefs.blurAmount = amount
                binding.tvBlurAmountValue.text = amount.toString()
            }
        }
        binding.sbBlurAmount.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                applyOnly()
            }
        })

        binding.btnResetBlur.setOnClickListener {
            val default = 15
            panelPrefs.blurAmount = default
            binding.sbBlurAmount.value = default.toFloat()
            binding.tvBlurAmountValue.text = default.toString()
            applyOnly()
        }

        binding.switchColumns.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.panelColumns = if (isChecked) 2 else 1
            applyOnly()
        }

        binding.sbOpacity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val progress = value.toInt()
                panelPrefs.panelOpacity = progress
                binding.tvOpacityValue.text = "$progress%"
            }
        }
        binding.sbOpacity.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                applyOnly()
            }
        })

        binding.btnResetOpacity.setOnClickListener {
            val default = 100
            panelPrefs.panelOpacity = default
            binding.sbOpacity.value = default.toFloat()
            binding.tvOpacityValue.text = "$default%"
            applyOnly()
        }

        binding.sbHandleHeight.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val progress = value.toInt()
                panelPrefs.handleHeight = progress
                binding.tvHeightValue.text = "${progress}dp"
            }
        }
        binding.sbHandleHeight.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                applyOnly()
            }
        })

        binding.btnResetHeight.setOnClickListener {
            val default = 80
            panelPrefs.handleHeight = default
            binding.sbHandleHeight.value = default.toFloat()
            binding.tvHeightValue.text = "${default}dp"
            applyOnly()
        }

        binding.sbHandleWidth.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val progress = value.toInt()
                panelPrefs.handleWidth = progress
                binding.tvWidthValue.text = "${progress}dp"
            }
        }
        binding.sbHandleWidth.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                applyOnly()
            }
        })

        binding.btnResetWidth.setOnClickListener {
            val default = 24
            panelPrefs.handleWidth = default
            binding.sbHandleWidth.value = default.toFloat()
            binding.tvWidthValue.text = "${default}dp"
            applyOnly()
        }

        binding.btnGoPremium.setOnClickListener {
            val intent = Intent(this, SupportActivity::class.java)
            startActivity(intent)
        }

        binding.sbHandleOffset.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val offset = value.toInt()
                panelPrefs.handleVerticalOffset = offset
                binding.tvOffsetValue.text = "${offset}dp"
            }
        }
        binding.sbHandleOffset.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                applyAndShow() // Vertical offset needs a full WindowManager update
            }
        })

        binding.btnResetOffset.setOnClickListener {
            val default = 0
            panelPrefs.handleVerticalOffset = default
            binding.sbHandleOffset.value = default.toFloat()
            binding.tvOffsetValue.text = "${default}dp"
            applyAndShow()
        }

        binding.sbPickerGap.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val gap = value.toInt()
                panelPrefs.pickerGap = gap
                binding.tvPickerGapValue.text = "${gap}dp"
            }
        }
        binding.sbPickerGap.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                applyOnly() // Just save, don't trigger a full preview refresh
            }
        })

        binding.btnResetPickerGap.setOnClickListener {
            val default = 20
            panelPrefs.pickerGap = default
            binding.sbPickerGap.value = default.toFloat()
            binding.tvPickerGapValue.text = "${default}dp"
            applyOnly()
        }

        binding.sbPanelRadius.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val progress = value.toInt()
                panelPrefs.panelCornerRadius = progress
                binding.tvRadiusValue.text = "${progress}dp"
            }
        }
        binding.sbPanelRadius.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                applyOnly()
            }
        })

        binding.btnResetRadius.setOnClickListener {
            val default = 20
            panelPrefs.panelCornerRadius = default
            binding.sbPanelRadius.value = default.toFloat()
            binding.tvRadiusValue.text = "${default}dp"
            applyOnly()
        }

        binding.layoutUIStyle.setOnClickListener {
            val options = arrayOf(getString(R.string.theme_origin), getString(R.string.theme_hyperos), getString(R.string.theme_realme), getString(R.string.theme_rich))
            val values = arrayOf(
                PanelPreferences.THEME_ORIGIN,
                PanelPreferences.THEME_HYPEROS,
                PanelPreferences.THEME_REALME,
                PanelPreferences.THEME_RICH
            )
            
            val selectedIndex = values.indexOf(panelPrefs.uiTheme).let { if (it == -1) 0 else it }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ui_style_theme)
                .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                    panelPrefs.uiTheme = values[which]
                    binding.tvUIStyleValue.text = options[which]
                    
                    // Auto-disable custom accent for Origin theme to match standard look
                    if (panelPrefs.uiTheme == PanelPreferences.THEME_ORIGIN) {
                        panelPrefs.useCustomAccent = false
                        binding.switchUseCustomAccent.isChecked = false
                    }
                    
                    updateSupportUI()
                    applyAndShow()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.msg_cancel, null)
                .show()
        }

        binding.layoutIconShape.setOnClickListener {
            val options = arrayOf(getString(R.string.icon_pack_default), getString(R.string.msg_shape_circle), getString(R.string.msg_shape_squircle), getString(R.string.msg_shape_square))
            val values = arrayOf(
                PanelPreferences.SHAPE_SYSTEM,
                PanelPreferences.SHAPE_CIRCLE,
                PanelPreferences.SHAPE_SQUIRCLE,
                PanelPreferences.SHAPE_SQUARE
            )
            
            val selectedIndex = values.indexOf(panelPrefs.iconShape).let { if (it == -1) 0 else it }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.icon_shape)
                .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                    panelPrefs.iconShape = values[which]
                    binding.tvIconShapeValue.text = options[which]
                    applyAndShow()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.msg_cancel, null)
                .show()
        }

        binding.switchUseCustomAccent.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.useCustomAccent = isChecked
            applyOnly()
        }

        binding.btnSelectIconPack.setOnClickListener {
            IconPackPickerDialog.show(this) {
                // No specific UI to update here as it's a simple list
            }
        }

        binding.switchTools.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.showTools = isChecked
            applyOnly()
        }

        binding.switchHideBg.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.hideBackground = isChecked
            applyOnly()
        }

        binding.btnResetDefaults.setOnClickListener {
            panelPrefs.resetToDefaults()
            loadCurrentSettings() 
            applyAndShow()
            binding.root.showModernToast(getString(R.string.msg_settings_reset_defaults))
        }

        binding.btnResetUIColors.setOnClickListener {
            panelPrefs.resetUIColors()
            loadCurrentSettings() 
            applyOnly()
            binding.root.showModernToast(getString(R.string.msg_ui_colors_restored))
        }

        binding.btnPickAccent.setOnClickListener {
            if (panelPrefs.uiTheme == PanelPreferences.THEME_ORIGIN) {
                binding.root.showModernToast(getString(R.string.msg_accent_locked_origin))
                return@setOnClickListener
            }
            openColorPicker(Color.parseColor(panelPrefs.accentColor)) { newColor ->
                val hex = String.format("#%06X", (0xFFFFFF and newColor))
                panelPrefs.accentColor = hex
                loadCurrentSettings()
                applyOnly()
            }
        }

        binding.btnPickBg.setOnClickListener {
            if (panelPrefs.uiTheme == PanelPreferences.THEME_ORIGIN) {
                binding.root.showModernToast(getString(R.string.msg_bg_locked_origin))
                return@setOnClickListener
            }
            openColorPicker(Color.parseColor(panelPrefs.panelBackgroundColor)) { newColor ->
                val hex = String.format("#E6%06X", (0xFFFFFF and newColor))
                panelPrefs.panelBackgroundColor = hex
                loadCurrentSettings()
                applyOnly()
            }
        }

        binding.switchUseCustomAccent.setOnTouchListener { _, _ ->
            if (panelPrefs.uiTheme == PanelPreferences.THEME_ORIGIN) {
                binding.root.showModernToast(getString(R.string.msg_custom_accent_disabled_origin))
                true 
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadCurrentSettings() 
    }

    private fun applyOnly() {
        val intent = Intent(this, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.ACTION_REFRESH
        }
        startService(intent)
    }

    private fun applyAndShow() {
        val stop = Intent(this, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.ACTION_STOP
        }
        startService(stop)
        binding.root.postDelayed({
            val start = Intent(this, FloatingPanelService::class.java).apply {
                action = FloatingPanelService.ACTION_SHOW_TEMP
            }
            startForegroundService(start)
        }, 300)
    }
}
