package com.kjkao.contextautomator

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kjkao.contextautomator.databinding.ActivityMainBinding
import com.kjkao.contextautomator.data.local.RuleExecutionHistoryEntity
import com.kjkao.contextautomator.domain.model.ActionType
import com.kjkao.contextautomator.service.AutomationService
import com.kjkao.contextautomator.service.ServiceKeepAliveReceiver
import com.kjkao.contextautomator.ui.main.RuleHistoryAdapter
import com.kjkao.contextautomator.ui.main.MainViewModel
import com.kjkao.contextautomator.ui.main.MainViewModelFactory
import com.kjkao.contextautomator.ui.main.RuleAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ruleAdapter: RuleAdapter
    private var isServiceRunning = false
    private var dndSettingsOpened = false

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(
            (application as ContextAutomatorApp).repository,
            (application as ContextAutomatorApp).timeRuleAlarmScheduler,
            (application as ContextAutomatorApp).ruleCooldownBypassStore
        )
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val allGranted = result.values.all { it }
            if (allGranted) {
                ensureSpecialPermissionsAndStart()
            } else {
                showRuntimePermissionSettingsDialog()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRuleUi()

        binding.serviceToggleButton.setOnClickListener {
            if (isServiceRunning) {
                stopAutomation()
            } else {
                ensureRuntimePermissionsAndStart()
            }
        }

        binding.addRuleButton.setOnClickListener {
            startActivity(RuleEditorActivity.createIntent(this))
        }
        binding.viewHistoryButton.setOnClickListener {
            lifecycleScope.launch {
                showRuleHistoryDialog(viewModel.refreshExecutionHistory())
            }
        }
        binding.emptyStateAddRuleButton.setOnClickListener {
            startActivity(RuleEditorActivity.createIntent(this))
        }
        binding.openDndSettingsButton.setOnClickListener {
            openDndSettings()
        }

        viewModel.uiState.observe(this) { state ->
            binding.statusText.text = state.status
            binding.ruleCountNumberText.text = state.ruleCount.toString()
            binding.ruleCountText.text = getString(R.string.rule_count_format, state.ruleCount)
            binding.emptyStateContainer.isVisible = state.rules.isEmpty()
            binding.ruleRecyclerView.isVisible = state.rules.isNotEmpty()
            ruleAdapter.submitList(state.rules)
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
        refreshServiceStateFromSystem()
        refreshDndAccessUi()
    }

    override fun onResume() {
        super.onResume()
        refreshDndAccessUi(showGrantedToast = dndSettingsOpened)
        dndSettingsOpened = false
    }

    private fun ensureRuntimePermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.NEARBY_WIFI_DEVICES
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.BLUETOOTH_SCAN
            }
        }

        if (needed.isEmpty()) {
            ensureSpecialPermissionsAndStart()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun ensureSpecialPermissionsAndStart() {
        lifecycleScope.launch {
            val needsWriteSettings = viewModel.uiState.value
                ?.rules
                .orEmpty()
                .any {
                    it.enabled &&
                        (it.actionType == ActionType.SCREEN_BRIGHTNESS.name || it.actionType == ActionType.SCREEN_TIMEOUT.name)
                }
            if (needsWriteSettings && !Settings.System.canWrite(this@MainActivity)) {
                showWriteSettingsPermissionDialog()
                return@launch
            }
            startScanService()
        }
    }

    private fun startScanService() {
        val app = application as ContextAutomatorApp
        app.ruleCooldownBypassStore.requestBypassAllOnNextServiceStart()
        startForegroundService(Intent(this, AutomationService::class.java))
        app.automationStateStore.setAutomationEnabled(true)
        lifecycleScope.launch {
            app.timeRuleAlarmScheduler.syncAllRules(app.repository.getAllRules())
        }
        viewModel.setStatus("Service running")
        updateServiceUi(true)
    }

    private fun stopAutomation() {
        stopService(Intent(this, AutomationService::class.java))
        val app = application as ContextAutomatorApp
        app.automationStateStore.setAutomationEnabled(false)
        ServiceKeepAliveReceiver.cancelHealthCheck(this)
        lifecycleScope.launch {
            app.timeRuleAlarmScheduler.cancelAllTimeRules(app.repository.getAllRules())
        }
        viewModel.setStatus("Service stopped")
        updateServiceUi(false)
    }

    private fun refreshServiceStateFromSystem() {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val running = manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == AutomationService::class.java.name
        }
        updateServiceUi(running)
    }

    private fun updateServiceUi(running: Boolean) {
        isServiceRunning = running
        binding.serviceToggleButton.text = if (running) {
            getString(R.string.service_stop_short)
        } else {
            getString(R.string.service_start_short)
        }
        binding.serviceStateText.text = if (running) {
            getString(R.string.main_service_running)
        } else {
            getString(R.string.main_service_stopped)
        }
        val color = if (running) 0xFF1B9E3EFF.toInt() else 0xFF9AA0A6.toInt()
        binding.serviceIndicator.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun setupRuleUi() {
        ruleAdapter = RuleAdapter(
            onDeleteClicked = { rule -> confirmDelete(rule.id) },
            onEditClicked = { rule -> startActivity(RuleEditorActivity.createIntent(this, rule)) },
            onEnabledChanged = { rule, enabled -> viewModel.setRuleEnabled(rule, enabled) }
        )
        binding.ruleRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.ruleRecyclerView.adapter = ruleAdapter
    }

    private fun refreshDndAccessUi(showGrantedToast: Boolean = false) {
        val needsAccess = !hasDndAccess()
        binding.dndPermissionCard.isVisible = needsAccess
        if (showGrantedToast && !needsAccess) {
            Toast.makeText(this, getString(R.string.dnd_access_enabled_message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasDndAccess(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return manager.isNotificationPolicyAccessGranted
    }

    private fun openDndSettings() {
        dndSettingsOpened = true
        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    private fun showRuntimePermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.runtime_permission_dialog_title)
            .setMessage(R.string.runtime_permission_dialog_message)
            .setPositiveButton(R.string.dnd_access_open_settings) { _, _ ->
                openAppPermissionSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showWriteSettingsPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.write_settings_permission_dialog_title)
            .setMessage(R.string.write_settings_permission_dialog_message)
            .setPositiveButton(R.string.dnd_access_open_settings) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
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

    private fun confirmDelete(ruleId: Long) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_rule_confirm_title)
            .setMessage(R.string.delete_rule_confirm_message)
            .setPositiveButton(R.string.delete_rule) { _, _ ->
                viewModel.deleteRule(ruleId)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRuleHistoryDialog(history: List<RuleExecutionHistoryEntity>) {
        val contentView = LayoutInflater.from(this).inflate(R.layout.dialog_rule_history, null)
        val recyclerView = contentView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.historyRecyclerView)
        val emptyText = contentView.findViewById<TextView>(R.id.historyEmptyText)

        val adapter = RuleHistoryAdapter(::formatActionSummary)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        adapter.submitList(history)
        emptyText.isVisible = history.isEmpty()
        recyclerView.isVisible = history.isNotEmpty()

        AlertDialog.Builder(this)
            .setTitle(R.string.rule_history_title)
            .setView(contentView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatActionSummary(actionType: String, actionValue: Int): String {
        return when (actionType) {
            ActionType.RINGER_MODE.name -> when (actionValue) {
                AudioManager.RINGER_MODE_NORMAL -> getString(R.string.action_ringer_normal)
                AudioManager.RINGER_MODE_VIBRATE -> getString(R.string.action_ringer_vibrate)
                AudioManager.RINGER_MODE_SILENT -> getString(R.string.action_ringer_silent)
                else -> getString(R.string.action_ringer_raw, actionValue)
            }
            ActionType.RING_VOLUME.name -> getString(R.string.action_volume_summary, actionValue)
            ActionType.MEDIA_VOLUME.name -> getString(R.string.action_media_volume_summary, actionValue)
            ActionType.SCREEN_BRIGHTNESS.name -> getString(R.string.action_brightness_summary, actionValue)
            ActionType.SCREEN_TIMEOUT.name -> getString(R.string.action_timeout_summary, actionValue)
            else -> getString(R.string.action_unknown)
        }
    }
}

