package com.kjkao.contextautomator

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.kjkao.contextautomator.data.local.RuleEntity
import com.kjkao.contextautomator.databinding.ActivityRuleEditorBinding
import com.kjkao.contextautomator.domain.model.ActionType
import com.kjkao.contextautomator.domain.model.TriggerCondition
import com.kjkao.contextautomator.domain.model.TriggerType
import com.google.android.material.slider.Slider
import com.kjkao.contextautomator.ui.main.MainViewModel
import com.kjkao.contextautomator.ui.main.MainViewModelFactory
import java.util.Locale

class RuleEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRuleEditorBinding
    private lateinit var wifiManager: WifiManager
    private var lastStatus: String? = null
    private var awaitingSaveResult = false
    private var suppressValidation = false
    private val editingRule by lazy { intent.toRuleEntityOrNull() }

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(
            (application as ContextAutomatorApp).repository,
            (application as ContextAutomatorApp).timeRuleAlarmScheduler
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRuleEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        title = if (editingRule == null) getString(R.string.add_rule) else getString(R.string.edit_rule_title)
        setupToolbar()
        binding.ruleEditorTitleText.text = title
        binding.ruleEditorIntroText.setText(
            if (editingRule == null) R.string.rule_editor_intro_add else R.string.rule_editor_intro_edit
        )

        setupEditorUi()
        bindRuleData()
        setupValidationListeners()
        observeSaveStatus()
        refreshFormValidation(showErrors = false)

        binding.saveRuleButton.setOnClickListener {
            awaitingSaveResult = true
            if (!refreshFormValidation(showErrors = true)) {
                awaitingSaveResult = false
                return@setOnClickListener
            }
            saveRule()
        }
        binding.cancelRuleButton.setOnClickListener {
            finish()
        }
        binding.editorOpenDndSettingsButton.setOnClickListener {
            openDndSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        updateDndPromptVisibility(selectedActionType(binding.actionTypeSpinner.selectedItemPosition))
    }

    private fun setupToolbar() {
        binding.ruleEditorToolbar.title = title
        binding.ruleEditorToolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupEditorUi() {
        val triggerOptions = listOf(
            getString(R.string.trigger_wifi),
            getString(R.string.trigger_time),
            getString(R.string.trigger_bluetooth),
            getString(R.string.trigger_charging),
            getString(R.string.trigger_geofence),
            getString(R.string.trigger_app_foreground)
        )
        binding.triggerTypeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, triggerOptions)

        val actionOptions = listOf(
            getString(R.string.action_ringer),
            getString(R.string.action_volume),
            getString(R.string.action_media_volume),
            getString(R.string.action_brightness),
            getString(R.string.action_timeout)
        )
        binding.actionTypeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, actionOptions)

        val ringerOptions = listOf(
            getString(R.string.rule_editor_ringer_normal),
            getString(R.string.rule_editor_ringer_vibrate),
            getString(R.string.rule_editor_ringer_silent)
        )
        binding.actionRingerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ringerOptions)

        binding.triggerTypeSpinner.setOnItemSelectedListener { _, _, position, _ ->
            val triggerType = selectedTriggerType(position)
            binding.triggerConditionSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                conditionOptionsFor(triggerType)
            )
            if (!applyExistingConditionSelection(triggerType)) {
                binding.triggerConditionSpinner.setSelection(0)
            }
            updateTriggerInputVisibility(triggerType)
            refreshFormValidation(showErrors = false)
        }

        binding.triggerConditionSpinner.setOnItemSelectedListener { _, _, _, _ ->
            refreshFormValidation(showErrors = false)
        }

        binding.actionTypeSpinner.setOnItemSelectedListener { _, _, position, _ ->
            updateActionInput(selectedActionType(position), preserveCurrentValue())
            refreshFormValidation(showErrors = false)
        }

        binding.actionRingerSpinner.setOnItemSelectedListener { _, _, _, _ ->
            refreshFormValidation(showErrors = false)
        }

        binding.actionValueSlider.addOnChangeListener(Slider.OnChangeListener { _, _, fromUser ->
            if (fromUser) {
                updateActionPreview(selectedActionType(binding.actionTypeSpinner.selectedItemPosition))
                refreshFormValidation(showErrors = false)
            }
        })

        binding.pickWifiButton.setOnClickListener {
            when (selectedTriggerType(binding.triggerTypeSpinner.selectedItemPosition)) {
                TriggerType.WIFI_SSID -> pickWifiInto(binding.triggerValueInput)
                TriggerType.GEOFENCE -> pickCurrentLocationInto(binding.triggerValueInput)
                else -> Unit
            }
        }
        binding.pickBluetoothButton.setOnClickListener {
            when (selectedTriggerType(binding.triggerTypeSpinner.selectedItemPosition)) {
                TriggerType.BLUETOOTH_DEVICE -> pickBluetoothInto(binding.triggerValueInput)
                TriggerType.APP_FOREGROUND -> pickAppInto(binding.triggerValueInput)
                else -> Unit
            }
        }
        setupTimeInput(binding.timeStartInput)

        updateActionInput(ActionType.RINGER_MODE, 2)
    }

    private fun bindRuleData() {
        val rule = editingRule
        if (rule == null) {
            binding.statusCard.visibility = View.GONE
            binding.triggerTypeSpinner.setSelection(0)
            binding.actionTypeSpinner.setSelection(0)
            updateActionInput(ActionType.RINGER_MODE, 2)
            updateTriggerInputVisibility(TriggerType.WIFI_SSID)
            return
        }

        suppressValidation = true
        binding.statusCard.visibility = View.VISIBLE
        binding.enabledSwitch.isChecked = rule.enabled
        binding.triggerTypeSpinner.setSelection(triggerTypePosition(rule.triggerType))
        binding.triggerValueInput.setText(rule.triggerValue)
        val triggerMinute = rule.timeStartMinutes ?: rule.timeEndMinutes
        val triggerTimeText = toTimeText(triggerMinute)
        binding.timeStartInput.setText(triggerTimeText)
        binding.timeEndInput.setText(triggerTimeText)
        binding.actionTypeSpinner.setSelection(actionTypePosition(rule.actionType))
        updateActionInput(selectedActionType(binding.actionTypeSpinner.selectedItemPosition), rule.actionValue)
        updateTriggerInputVisibility(selectedTriggerType(binding.triggerTypeSpinner.selectedItemPosition))
        suppressValidation = false
    }

    private fun setupValidationListeners() {
        binding.triggerValueInput.doAfterTextChanged {
            refreshFormValidation(showErrors = false)
        }
        binding.timeStartInput.doAfterTextChanged {
            refreshFormValidation(showErrors = false)
        }
        binding.timeEndInput.doAfterTextChanged {
            refreshFormValidation(showErrors = false)
        }
    }

    private fun applyExistingConditionSelection(triggerType: TriggerType): Boolean {
        val rule = editingRule ?: return false
        if (selectedTriggerType(binding.triggerTypeSpinner.selectedItemPosition) != triggerType) {
            return false
        }
        binding.triggerConditionSpinner.setSelection(
            if (rule.triggerCondition == TriggerCondition.CONNECTED.name) 1 else 0
        )
        return true
    }

    private fun observeSaveStatus() {
        viewModel.uiState.observe(this) { state ->
            if (state.status == lastStatus) return@observe
            lastStatus = state.status
            if (!awaitingSaveResult) return@observe

            if (state.status == "Rule added" || state.status == "Rule updated") {
                setResult(Activity.RESULT_OK)
                Toast.makeText(this, state.status, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                awaitingSaveResult = false
                Toast.makeText(this, state.status, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveRule() {
        val currentRule = editingRule
        val actionValueRaw = selectedActionValue().toString()
        val timePoint = binding.timeStartInput.text?.toString().orEmpty()
        if (currentRule == null) {
            viewModel.addRule(
                triggerType = selectedTriggerType(binding.triggerTypeSpinner.selectedItemPosition),
                triggerCondition = selectedTriggerCondition(binding.triggerConditionSpinner.selectedItemPosition),
                triggerValue = binding.triggerValueInput.text?.toString().orEmpty(),
                timeStart = timePoint,
                actionType = selectedActionType(binding.actionTypeSpinner.selectedItemPosition),
                actionValueRaw = actionValueRaw
            )
            return
        }

        viewModel.updateRule(
            rule = currentRule,
            triggerType = selectedTriggerType(binding.triggerTypeSpinner.selectedItemPosition),
            triggerCondition = selectedTriggerCondition(binding.triggerConditionSpinner.selectedItemPosition),
            triggerValue = binding.triggerValueInput.text?.toString().orEmpty(),
            timeStart = timePoint,
            actionType = selectedActionType(binding.actionTypeSpinner.selectedItemPosition),
            actionValueRaw = actionValueRaw,
            enabled = binding.enabledSwitch.isChecked
        )
    }

    private fun selectedTriggerType(position: Int): TriggerType {
        return when (position) {
            1 -> TriggerType.TIME_RANGE
            2 -> TriggerType.BLUETOOTH_DEVICE
            3 -> TriggerType.CHARGING_STATE
            4 -> TriggerType.GEOFENCE
            5 -> TriggerType.APP_FOREGROUND
            else -> TriggerType.WIFI_SSID
        }
    }

    private fun selectedTriggerCondition(position: Int): TriggerCondition {
        return when (position) {
            1 -> TriggerCondition.CONNECTED
            else -> TriggerCondition.DETECTED
        }
    }

    private fun selectedActionType(position: Int): ActionType {
        return when (position) {
            1 -> ActionType.RING_VOLUME
            2 -> ActionType.MEDIA_VOLUME
            3 -> ActionType.SCREEN_BRIGHTNESS
            4 -> ActionType.SCREEN_TIMEOUT
            else -> ActionType.RINGER_MODE
        }
    }

    private fun triggerTypePosition(triggerType: String): Int {
        return when (triggerType) {
            TriggerType.TIME_RANGE.name -> 1
            TriggerType.BLUETOOTH_DEVICE.name -> 2
            TriggerType.CHARGING_STATE.name -> 3
            TriggerType.GEOFENCE.name -> 4
            TriggerType.APP_FOREGROUND.name -> 5
            else -> 0
        }
    }

    private fun actionTypePosition(actionType: String): Int {
        return when (actionType) {
            ActionType.RING_VOLUME.name -> 1
            ActionType.MEDIA_VOLUME.name -> 2
            ActionType.SCREEN_BRIGHTNESS.name -> 3
            ActionType.SCREEN_TIMEOUT.name -> 4
            else -> 0
        }
    }

    private fun toTimeText(minuteOfDay: Int?): String {
        if (minuteOfDay == null) return ""
        val hour = minuteOfDay / 60
        val minute = minuteOfDay % 60
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }

    private fun setupTimeInput(input: EditText) {
        input.inputType = InputType.TYPE_NULL
        input.keyListener = null
        input.setOnClickListener {
            showTimePicker(input)
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showTimePicker(input)
            }
        }
    }

    private fun showTimePicker(input: EditText) {
        val initialMinute = parseMinuteOfDay(input.text?.toString().orEmpty()) ?: 0
        val initialHour = initialMinute / 60
        val initialMinuteOfHour = initialMinute % 60
        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                input.setText(toTimeText(hourOfDay * 60 + minute))
            },
            initialHour,
            initialMinuteOfHour,
            true
        ).show()
    }

    private fun updateTriggerInputVisibility(triggerType: TriggerType) {
        val isTime = triggerType == TriggerType.TIME_RANGE
        val isCharging = triggerType == TriggerType.CHARGING_STATE
        binding.triggerConditionLabelText.visibility = if (isTime) View.GONE else View.VISIBLE
        binding.triggerConditionSpinner.visibility = if (isTime) View.GONE else View.VISIBLE
        binding.triggerValueLabelText.visibility = if (isTime || isCharging) View.GONE else View.VISIBLE
        binding.triggerValueLayout.visibility = if (isTime || isCharging) View.GONE else View.VISIBLE
        binding.triggerPickButtonsRow.visibility = if (isTime || isCharging) View.GONE else View.VISIBLE
        binding.timeStartLabelText.visibility = if (isTime) View.VISIBLE else View.GONE
        binding.timeStartLayout.visibility = if (isTime) View.VISIBLE else View.GONE
        binding.timeEndLabelText.visibility = View.GONE
        binding.timeEndLayout.visibility = View.GONE
        binding.timeStartLayout.helperText = getString(R.string.rule_editor_time_helper)
        binding.timeEndLayout.helperText = null

        when (triggerType) {
            TriggerType.WIFI_SSID -> {
                binding.triggerSectionHintText.setText(R.string.rule_editor_trigger_wifi_help)
                binding.pickWifiButton.visibility = View.VISIBLE
                binding.pickBluetoothButton.visibility = View.GONE
                binding.pickWifiButton.text = getString(R.string.pick_wifi)
                binding.triggerValueLayout.hint = getString(R.string.trigger_value_hint)
                binding.triggerValueLayout.helperText = getString(R.string.rule_editor_example_wifi)
            }
            TriggerType.BLUETOOTH_DEVICE -> {
                binding.triggerSectionHintText.setText(R.string.rule_editor_trigger_bluetooth_help)
                binding.pickWifiButton.visibility = View.GONE
                binding.pickBluetoothButton.visibility = View.VISIBLE
                binding.pickBluetoothButton.text = getString(R.string.pick_bluetooth)
                binding.triggerValueLayout.hint = getString(R.string.trigger_value_hint)
                binding.triggerValueLayout.helperText = getString(R.string.rule_editor_example_bluetooth)
            }
            TriggerType.GEOFENCE -> {
                binding.triggerSectionHintText.setText(R.string.rule_editor_trigger_geofence_help)
                binding.pickWifiButton.visibility = View.VISIBLE
                binding.pickBluetoothButton.visibility = View.GONE
                binding.pickWifiButton.text = getString(R.string.pick_current_location)
                binding.triggerValueLayout.hint = getString(R.string.location_value_hint)
                binding.triggerValueLayout.helperText = getString(R.string.rule_editor_example_geofence)
            }
            TriggerType.APP_FOREGROUND -> {
                binding.triggerSectionHintText.setText(R.string.rule_editor_trigger_app_help)
                binding.pickWifiButton.visibility = View.GONE
                binding.pickBluetoothButton.visibility = View.VISIBLE
                binding.pickBluetoothButton.text = getString(R.string.pick_installed_app)
                binding.triggerValueLayout.hint = getString(R.string.app_value_hint)
                binding.triggerValueLayout.helperText = getString(R.string.rule_editor_example_app)
            }
            TriggerType.CHARGING_STATE -> {
                binding.triggerSectionHintText.setText(R.string.rule_editor_trigger_charging_help)
                binding.pickWifiButton.visibility = View.GONE
                binding.pickBluetoothButton.visibility = View.GONE
                binding.triggerValueLayout.hint = getString(R.string.charging_no_value_hint)
                binding.triggerValueLayout.helperText = getString(R.string.rule_editor_no_trigger_value_needed)
            }
            TriggerType.TIME_RANGE -> {
                binding.triggerSectionHintText.setText(R.string.rule_editor_trigger_time_help)
                binding.pickWifiButton.visibility = View.GONE
                binding.pickBluetoothButton.visibility = View.GONE
                binding.triggerValueLayout.hint = getString(R.string.trigger_value_hint)
                binding.triggerValueLayout.helperText = null
            }
            else -> {
                binding.triggerSectionHintText.setText(R.string.rule_editor_trigger_section_hint)
                binding.pickWifiButton.visibility = View.GONE
                binding.pickBluetoothButton.visibility = View.GONE
                binding.triggerValueLayout.hint = getString(R.string.trigger_value_hint)
                binding.triggerValueLayout.helperText = null
            }
        }
    }

    private fun updateActionInput(actionType: ActionType, actionValue: Int) {
        updateDndPromptVisibility(actionType)
        when (actionType) {
            ActionType.RINGER_MODE -> {
                binding.actionSectionHintText.setText(R.string.rule_editor_action_ringer_help)
                binding.actionRingerSpinner.visibility = View.VISIBLE
                binding.actionSliderContainer.visibility = View.GONE
                binding.actionRingerSpinner.setSelection(
                    when (actionValue) {
                        1 -> 1
                        0 -> 2
                        else -> 0
                    }
                )
            }
            ActionType.RING_VOLUME -> {
                showActionSlider(actionType, actionValue.coerceIn(0, 100), 0f, 100f, 1f)
                binding.actionSectionHintText.setText(R.string.rule_editor_action_ring_volume_help)
            }
            ActionType.MEDIA_VOLUME -> {
                showActionSlider(actionType, actionValue.coerceIn(0, 100), 0f, 100f, 1f)
                binding.actionSectionHintText.setText(R.string.rule_editor_action_media_volume_help)
            }
            ActionType.SCREEN_BRIGHTNESS -> {
                showActionSlider(actionType, actionValue.coerceIn(0, 255), 0f, 255f, 1f)
                binding.actionSectionHintText.setText(R.string.rule_editor_action_brightness_help)
            }
            ActionType.SCREEN_TIMEOUT -> {
                val normalized = actionValue.coerceIn(15, 600)
                showActionSlider(actionType, normalized, 15f, 600f, 15f)
                binding.actionSectionHintText.setText(R.string.rule_editor_action_timeout_help)
            }
        }
        updateActionPreview(actionType)
    }

    private fun updateDndPromptVisibility(actionType: ActionType) {
        val shouldShow = actionType == ActionType.RINGER_MODE && !hasDndAccess()
        binding.editorDndWarningCard.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun hasDndAccess(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return manager.isNotificationPolicyAccessGranted
    }

    private fun openDndSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    private fun showActionSlider(actionType: ActionType, value: Int, valueFrom: Float, valueTo: Float, stepSize: Float) {
        binding.actionRingerSpinner.visibility = View.GONE
        binding.actionSliderContainer.visibility = View.VISIBLE
        binding.actionValueSlider.valueFrom = valueFrom
        binding.actionValueSlider.valueTo = valueTo
        binding.actionValueSlider.stepSize = stepSize
        binding.actionValueSlider.value = value.toFloat()
        updateActionPreview(actionType)
    }

    private fun updateActionPreview(actionType: ActionType) {
        val value = selectedActionValue()
        binding.actionValuePreviewText.text = when (actionType) {
            ActionType.RING_VOLUME, ActionType.MEDIA_VOLUME -> getString(R.string.rule_editor_slider_percent_value, value)
            ActionType.SCREEN_BRIGHTNESS -> getString(R.string.rule_editor_slider_brightness_value, value)
            ActionType.SCREEN_TIMEOUT -> getString(R.string.rule_editor_slider_timeout_value, value)
            else -> ""
        }
    }

    private fun selectedActionValue(): Int {
        return when (selectedActionType(binding.actionTypeSpinner.selectedItemPosition)) {
            ActionType.RINGER_MODE -> when (binding.actionRingerSpinner.selectedItemPosition) {
                1 -> 1
                2 -> 0
                else -> 2
            }
            else -> binding.actionValueSlider.value.toInt()
        }
    }

    private fun preserveCurrentValue(): Int {
        return if (suppressValidation) {
            editingRule?.actionValue ?: 2
        } else {
            selectedActionValue()
        }
    }

    private fun refreshFormValidation(showErrors: Boolean): Boolean {
        if (suppressValidation) return true

        val triggerType = selectedTriggerType(binding.triggerTypeSpinner.selectedItemPosition)
        val triggerValue = binding.triggerValueInput.text?.toString().orEmpty().trim()
        val timeStart = binding.timeStartInput.text?.toString().orEmpty().trim()

        var isValid = true

        val triggerError = when {
            requiresTriggerValue(triggerType) && triggerValue.isBlank() -> getString(R.string.rule_editor_error_trigger_required)
            triggerType == TriggerType.GEOFENCE && triggerValue.isNotBlank() && !isGeofenceFormatValid(triggerValue) -> getString(R.string.rule_editor_error_geofence)
            else -> null
        }
        binding.triggerValueLayout.error = if (showErrors) triggerError else null
        if (triggerError != null) isValid = false

        val startMinutes = if (triggerType == TriggerType.TIME_RANGE) parseMinuteOfDay(timeStart) else 0
        val startError = if (triggerType == TriggerType.TIME_RANGE && startMinutes == null) getString(R.string.rule_editor_error_time) else null
        binding.timeStartLayout.error = if (showErrors) startError else null
        binding.timeEndLayout.error = null
        if (startError != null) isValid = false

        binding.saveRuleButton.isEnabled = isValid
        return isValid
    }

    private fun parseMinuteOfDay(text: String): Int? {
        val parts = text.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun requiresTriggerValue(triggerType: TriggerType): Boolean {
        return triggerType != TriggerType.TIME_RANGE && triggerType != TriggerType.CHARGING_STATE
    }

    private fun isGeofenceFormatValid(value: String): Boolean {
        val parts = value.split(",").map { it.trim() }
        if (parts.size != 3) return false
        val lat = parts[0].toDoubleOrNull() ?: return false
        val lng = parts[1].toDoubleOrNull() ?: return false
        val radius = parts[2].toFloatOrNull() ?: return false
        return lat in -90.0..90.0 && lng in -180.0..180.0 && radius > 0f
    }

    private fun conditionOptionsFor(triggerType: TriggerType): List<String> {
        return when (triggerType) {
            TriggerType.CHARGING_STATE -> listOf(
                getString(R.string.condition_charging),
                getString(R.string.condition_not_charging)
            )
            TriggerType.GEOFENCE -> listOf(
                getString(R.string.condition_inside),
                getString(R.string.condition_outside)
            )
            TriggerType.APP_FOREGROUND -> listOf(
                getString(R.string.condition_foreground),
                getString(R.string.condition_not_foreground)
            )
            else -> listOf(
                getString(R.string.condition_detected),
                getString(R.string.condition_connected)
            )
        }
    }

    private fun pickWifiInto(targetInput: EditText) {
        if (!hasWifiPermissions()) {
            showWifiPermissionSettingsDialog()
            return
        }

        wifiManager.startScan()
        val candidates = wifiManager.scanResults
            .mapNotNull { it.SSID?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        if (candidates.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_wifi_found), Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pick_wifi)
            .setItems(candidates.toTypedArray()) { _, which ->
                targetInput.setText(candidates[which])
            }
            .show()
    }

    private fun showWifiPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wifi_permission_dialog_title)
            .setMessage(R.string.wifi_permission_dialog_message)
            .setPositiveButton(R.string.dnd_access_open_settings) { _, _ ->
                openAppPermissionSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAppPermissionSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun pickBluetoothInto(targetInput: EditText) {
        if (!hasBluetoothPermission()) {
            showBluetoothPermissionSettingsDialog()
            return
        }

        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        val candidates = adapter?.bondedDevices
            ?.mapNotNull { device ->
                val name = device.name?.trim()
                if (name.isNullOrBlank()) null else name
            }
            ?.distinct()
            ?.sorted()
            .orEmpty()

        if (candidates.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_bluetooth_found), Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pick_bluetooth)
            .setItems(candidates.toTypedArray()) { _, which ->
                targetInput.setText(candidates[which])
            }
            .show()
    }

    private fun showBluetoothPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bluetooth_permission_dialog_title)
            .setMessage(R.string.bluetooth_permission_dialog_message)
            .setPositiveButton(R.string.dnd_access_open_settings) { _, _ ->
                openAppPermissionSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickCurrentLocationInto(targetInput: EditText) {
        if (!hasWifiPermissions()) {
            showWifiPermissionSettingsDialog()
            return
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val location = providers.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

        if (location == null) {
            Toast.makeText(this, getString(R.string.location_unavailable), Toast.LENGTH_SHORT).show()
            return
        }

        targetInput.setText(String.format("%.6f,%.6f,200", location.latitude, location.longitude))
    }

    private fun pickAppInto(targetInput: EditText) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .map { info ->
                val label = info.loadLabel(packageManager).toString()
                label to info.activityInfo.packageName
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        if (apps.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_app_found), Toast.LENGTH_SHORT).show()
            return
        }

        if (!hasUsageAccessPermission()) {
            showUsageAccessSettingsDialog()
            return
        }

        val labels = apps.map { (label, pkg) -> "$label ($pkg)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_installed_app)
            .setItems(labels) { _, which ->
                targetInput.setText(apps[which].second)
            }
            .show()
    }

    private fun showUsageAccessSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.usage_access_dialog_title)
            .setMessage(R.string.usage_access_dialog_message)
            .setPositiveButton(R.string.dnd_access_open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun hasWifiPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connectGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val scanGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            return connectGranted && scanGranted
        }
        return true
    }

    private fun hasUsageAccessPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun Spinner.setOnItemSelectedListener(
        block: (parent: AdapterView<*>?, view: View?, position: Int, id: Long) -> Unit
    ) {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                block(parent, view, position, id)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    companion object {
        private const val EXTRA_RULE_ID = "rule_id"
        private const val EXTRA_TRIGGER_TYPE = "trigger_type"
        private const val EXTRA_TRIGGER_CONDITION = "trigger_condition"
        private const val EXTRA_TRIGGER_VALUE = "trigger_value"
        private const val EXTRA_TIME_START = "time_start"
        private const val EXTRA_TIME_END = "time_end"
        private const val EXTRA_ACTION_TYPE = "action_type"
        private const val EXTRA_ACTION_VALUE = "action_value"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_CREATED_AT = "created_at"
        private const val EXTRA_UPDATED_AT = "updated_at"

        fun createIntent(context: Context, rule: RuleEntity? = null): Intent {
            return Intent(context, RuleEditorActivity::class.java).apply {
                if (rule != null) {
                    putExtra(EXTRA_RULE_ID, rule.id)
                    putExtra(EXTRA_TRIGGER_TYPE, rule.triggerType)
                    putExtra(EXTRA_TRIGGER_CONDITION, rule.triggerCondition)
                    putExtra(EXTRA_TRIGGER_VALUE, rule.triggerValue)
                    putExtra(EXTRA_TIME_START, rule.timeStartMinutes)
                    putExtra(EXTRA_TIME_END, rule.timeEndMinutes)
                    putExtra(EXTRA_ACTION_TYPE, rule.actionType)
                    putExtra(EXTRA_ACTION_VALUE, rule.actionValue)
                    putExtra(EXTRA_ENABLED, rule.enabled)
                    putExtra(EXTRA_CREATED_AT, rule.createdAt)
                    putExtra(EXTRA_UPDATED_AT, rule.updatedAt)
                }
            }
        }

        private fun Intent.toRuleEntityOrNull(): RuleEntity? {
            if (!hasExtra(EXTRA_RULE_ID)) return null
            return RuleEntity(
                id = getLongExtra(EXTRA_RULE_ID, 0L),
                triggerType = getStringExtra(EXTRA_TRIGGER_TYPE).orEmpty(),
                triggerCondition = getStringExtra(EXTRA_TRIGGER_CONDITION).orEmpty(),
                triggerValue = getStringExtra(EXTRA_TRIGGER_VALUE).orEmpty(),
                timeStartMinutes = if (hasExtra(EXTRA_TIME_START)) getIntExtra(EXTRA_TIME_START, 0) else null,
                timeEndMinutes = if (hasExtra(EXTRA_TIME_END)) getIntExtra(EXTRA_TIME_END, 0) else null,
                actionType = getStringExtra(EXTRA_ACTION_TYPE).orEmpty(),
                actionValue = getIntExtra(EXTRA_ACTION_VALUE, 0),
                enabled = getBooleanExtra(EXTRA_ENABLED, true),
                createdAt = getLongExtra(EXTRA_CREATED_AT, System.currentTimeMillis()),
                updatedAt = getLongExtra(EXTRA_UPDATED_AT, System.currentTimeMillis())
            )
        }
    }
}
