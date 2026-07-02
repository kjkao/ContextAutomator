package com.kjkao.contextautomator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.kjkao.contextautomator.R
import com.kjkao.contextautomator.ContextAutomatorApp
import com.kjkao.contextautomator.MainActivity
import com.kjkao.contextautomator.automation.RuleCooldownBypassStore
import com.kjkao.contextautomator.audio.RingerController
import com.kjkao.contextautomator.data.local.RuleEntity
import com.kjkao.contextautomator.domain.model.ActionType
import com.kjkao.contextautomator.domain.model.TriggerCondition
import com.kjkao.contextautomator.domain.model.TriggerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AutomationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    private lateinit var wifiManager: WifiManager
    private lateinit var locationManager: LocationManager
    private lateinit var ringerController: RingerController
    private lateinit var repository: com.kjkao.contextautomator.data.repo.RuleRepository
    private lateinit var ruleCooldownBypassStore: RuleCooldownBypassStore
    private lateinit var wifiReceiver: ScanResultReceiver
    private val wifiNetworkStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                WifiManager.NETWORK_STATE_CHANGED_ACTION,
                WifiManager.WIFI_STATE_CHANGED_ACTION -> evaluateRules()
            }
        }
    }
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var dndNotificationShown = false
    private var writeSettingsNotificationShown = false
    private val evaluationMutex = Mutex()
    private var lastBluetoothDiscoveryAt = 0L
    private var bypassAllCooldownsPending = false
    private val connectedBluetoothNames = mutableSetOf<String>()
    private val detectedBluetoothNames = mutableSetOf<String>()
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val name = (device.name ?: device.address).trim()
            if (name.isBlank()) return

            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> connectedBluetoothNames.add(name.lowercase())
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> connectedBluetoothNames.remove(name.lowercase())
                BluetoothDevice.ACTION_FOUND -> detectedBluetoothNames.add(name.lowercase())
            }
            evaluateRules()
        }
    }

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        locationManager = applicationContext.getSystemService(LOCATION_SERVICE) as LocationManager
        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        ringerController = RingerController(this)
        val app = application as ContextAutomatorApp
        repository = app.repository
        ruleCooldownBypassStore = app.ruleCooldownBypassStore
        bypassAllCooldownsPending = ruleCooldownBypassStore.consumeBypassAllOnNextServiceStart()
        wifiReceiver = ScanResultReceiver { evaluateRules() }

        registerReceiver(
            wifiReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )
        registerReceiver(wifiNetworkStateReceiver, IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        })
        registerReceiver(bluetoothReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_FOUND)
        })

        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, createNotification())
        ServiceKeepAliveReceiver.scheduleHealthCheck(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTORE_NOTIFICATION) {
            startForeground(NOTIFICATION_ID, createNotification())
            return START_STICKY
        }
        if (intent?.action == ACTION_RUN_TIME_RULE_ALARM) {
            val ruleId = intent.getLongExtra(EXTRA_RULE_ID, -1L)
            if (ruleId <= 0L) return START_NOT_STICKY

            val serviceStartedForAlarmOnly = scanJob == null
            serviceScope.launch {
                handleTimeRuleAlarm(ruleId)
                if (serviceStartedForAlarmOnly) {
                    stopForegroundCompat()
                    stopSelf(startId)
                }
            }
            return START_NOT_STICKY
        }

        if (scanJob == null) {
            scanJob = serviceScope.launch {
                while (isActive) {
                    val rules = repository.getEnabledRules()
                    val triggerTypes = rules.map { it.triggerType }.toSet()

                    if (needsWifiActiveScan(rules)) {
                        wifiManager.startScan()
                    }
                    maybeStartBluetoothDiscovery(triggerTypes)

                    evaluateRules(rules)
                    delay(resolveScanInterval(triggerTypes, rules.isEmpty()))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scanJob?.cancel()
        if (bluetoothAdapter?.isDiscovering == true) {
            runCatching { bluetoothAdapter?.cancelDiscovery() }
        }
        unregisterReceiver(wifiReceiver)
        unregisterReceiver(wifiNetworkStateReceiver)
        unregisterReceiver(bluetoothReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun evaluateRules(rulesOverride: List<RuleEntity>? = null) {
        serviceScope.launch {
            evaluationMutex.withLock {
                val bypassAllCooldowns = bypassAllCooldownsPending
                bypassAllCooldownsPending = false
                val rules = rulesOverride ?: repository.getEnabledRules()
                if (rules.isEmpty()) return@withLock

                val triggerTypes = rules.map { it.triggerType }.toSet()
                val needsWifiScanResults = needsWifiActiveScan(rules)

                val scannedSsids = if (needsWifiScanResults) {
                    wifiManager.scanResults
                        .mapNotNull { it.SSID }
                        .map { it.trim().lowercase() }
                        .filter { it.isNotBlank() }
                        .toSet()
                } else {
                    emptySet()
                }
                val connectedWifiSsid = if (triggerTypes.contains(TriggerType.WIFI_SSID.name)) {
                    wifiManager.connectionInfo?.ssid
                        ?.removePrefix("\"")
                        ?.removeSuffix("\"")
                        ?.trim()
                        ?.lowercase()
                        .orEmpty()
                } else {
                    ""
                }
                val isCharging = if (triggerTypes.contains(TriggerType.CHARGING_STATE.name)) {
                    isDeviceCharging()
                } else {
                    false
                }
                val currentLocation = if (triggerTypes.contains(TriggerType.GEOFENCE.name)) {
                    getCurrentLocation()
                } else {
                    null
                }
                val foregroundPackage = if (triggerTypes.contains(TriggerType.APP_FOREGROUND.name)) {
                    getForegroundPackageName()
                } else {
                    ""
                }

                val matchedRules = rules.filter { rule ->
                    matchesCondition(
                        rule = rule,
                        scannedSsids = scannedSsids,
                        connectedWifiSsid = connectedWifiSsid,
                        isCharging = isCharging,
                        currentLocation = currentLocation,
                        foregroundPackage = foregroundPackage
                    )
                }
                if (matchedRules.isEmpty()) return@withLock

                matchedRules.forEach { matchedRule ->
                    handleMatchedRule(repository, matchedRule, bypassAllCooldowns)
                }
            }
        }
    }

    private fun needsWifiActiveScan(rules: List<RuleEntity>): Boolean {
        return rules.any {
            it.triggerType == TriggerType.WIFI_SSID.name &&
                it.triggerCondition != TriggerCondition.CONNECTED.name
        }
    }

    private fun maybeStartBluetoothDiscovery(triggerTypes: Set<String>) {
        val needsBluetoothDiscovery = triggerTypes.contains(TriggerType.BLUETOOTH_DEVICE.name)
        if (!needsBluetoothDiscovery || !hasBluetoothScanPermission()) return

        val adapter = bluetoothAdapter ?: return
        if (adapter.isDiscovering) return

        val now = System.currentTimeMillis()
        if (now - lastBluetoothDiscoveryAt < BLUETOOTH_DISCOVERY_MIN_INTERVAL_MS) return

        if (adapter.startDiscovery()) {
            lastBluetoothDiscoveryAt = now
        }
    }

    private fun resolveScanInterval(triggerTypes: Set<String>, rulesEmpty: Boolean): Long {
        if (rulesEmpty) return IDLE_SCAN_INTERVAL_MS

        return when {
            triggerTypes.contains(TriggerType.WIFI_SSID.name) ||
                triggerTypes.contains(TriggerType.BLUETOOTH_DEVICE.name) -> SCAN_INTERVAL_MS
            triggerTypes.contains(TriggerType.GEOFENCE.name) ||
                triggerTypes.contains(TriggerType.APP_FOREGROUND.name) -> CONTEXT_SCAN_INTERVAL_MS
            else -> IDLE_SCAN_INTERVAL_MS
        }
    }

    private suspend fun handleMatchedRule(
        repository: com.kjkao.contextautomator.data.repo.RuleRepository,
        rule: RuleEntity,
        bypassAllCooldowns: Boolean
    ) {
        val now = System.currentTimeMillis()
        val bypassCooldown = bypassAllCooldowns || ruleCooldownBypassStore.consumeRuleBypass(rule.id)
        val latestExecution = repository.getLatestRuleExecution(rule.id)
        val isRecentDuplicate = latestExecution != null &&
            latestExecution.executedAt >= now - RULE_EXECUTION_COOLDOWN_MS &&
            latestExecution.actionType == rule.actionType &&
            latestExecution.actionValue == rule.actionValue

        if (isRecentDuplicate && !bypassCooldown) return
        if (ringerController.isActionAlreadyApplied(rule.actionType, rule.actionValue)) return

        val applied = applyAction(rule)
        if (applied) {
            repository.recordRuleExecution(rule, now)
        }
    }

    private suspend fun handleTimeRuleAlarm(ruleId: Long) {
        val rule = repository.getRuleById(ruleId) ?: return
        if (!rule.enabled || rule.triggerType != TriggerType.TIME_RANGE.name) return

        val latestExecution = repository.getLatestRuleExecution(rule.id)
        val todayStartMillis = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        if (latestExecution != null && latestExecution.executedAt >= todayStartMillis) return

        val now = System.currentTimeMillis()
        val alreadyApplied = ringerController.isActionAlreadyApplied(rule.actionType, rule.actionValue)
        val applied = if (alreadyApplied) false else applyAction(rule)
        if (alreadyApplied || applied) {
            repository.recordRuleExecution(rule, now)
        }
    }

    private fun matchesCondition(
        rule: RuleEntity,
        scannedSsids: Set<String>,
        connectedWifiSsid: String,
        isCharging: Boolean,
        currentLocation: Location?,
        foregroundPackage: String
    ): Boolean {
        return when (rule.triggerType) {
            TriggerType.WIFI_SSID.name -> {
                val target = rule.triggerValue.trim().lowercase()
                if (rule.triggerCondition == TriggerCondition.CONNECTED.name) {
                    connectedWifiSsid == target
                } else {
                    scannedSsids.contains(target)
                }
            }
            TriggerType.TIME_RANGE.name -> {
                false
            }
            TriggerType.BLUETOOTH_DEVICE.name -> {
                val target = rule.triggerValue.trim().lowercase()
                if (rule.triggerCondition == TriggerCondition.CONNECTED.name) {
                    connectedBluetoothNames.contains(target)
                } else {
                    detectedBluetoothNames.contains(target)
                }
            }
            TriggerType.CHARGING_STATE.name -> {
                if (rule.triggerCondition == TriggerCondition.CONNECTED.name) {
                    !isCharging
                } else {
                    isCharging
                }
            }
            TriggerType.GEOFENCE.name -> {
                val location = currentLocation ?: return false
                val (lat, lng, radius) = parseGeofence(rule.triggerValue) ?: return false
                val inside = distanceMeters(location.latitude, location.longitude, lat, lng) <= radius
                if (rule.triggerCondition == TriggerCondition.CONNECTED.name) {
                    !inside
                } else {
                    inside
                }
            }
            TriggerType.APP_FOREGROUND.name -> {
                val targetPackage = rule.triggerValue.trim().lowercase()
                val isForeground = foregroundPackage == targetPackage
                if (rule.triggerCondition == TriggerCondition.CONNECTED.name) {
                    !isForeground
                } else {
                    isForeground
                }
            }
            else -> false
        }
    }

    private fun isDeviceCharging(): Boolean {
        val statusIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = statusIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getCurrentLocation(): Location? {
        if (!hasLocationPermission()) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        return providers.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private fun getForegroundPackageName(): String {
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - 5 * 60 * 1000L
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
        return stats
            .maxByOrNull { it.lastTimeUsed }
            ?.packageName
            ?.lowercase()
            .orEmpty()
    }

    private fun parseGeofence(value: String): Triple<Double, Double, Float>? {
        val parts = value.split(",").map { it.trim() }
        if (parts.size != 3) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        val radius = parts[2].toFloatOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || radius <= 0f) return null
        return Triple(lat, lng, radius)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun applyAction(rule: RuleEntity): Boolean {
        return when (rule.actionType) {
            ActionType.RINGER_MODE.name -> {
                val applied = ringerController.applyRingerMode(rule.actionValue)
                if (!applied) {
                    showDndAccessHintNotification()
                } else if (dndNotificationShown) {
                    clearDndAccessHintNotification()
                }
                applied
            }
            ActionType.RING_VOLUME.name -> {
                ringerController.applyRingVolumePercent(rule.actionValue)
                true
            }
            ActionType.SCREEN_BRIGHTNESS.name -> {
                val applied = ringerController.applyScreenBrightness(rule.actionValue)
                if (!applied && !Settings.System.canWrite(this)) {
                    showWriteSettingsHintNotification()
                } else if (applied && writeSettingsNotificationShown) {
                    clearWriteSettingsHintNotification()
                }
                applied
            }
            ActionType.MEDIA_VOLUME.name -> {
                ringerController.applyMediaVolumePercent(rule.actionValue)
                true
            }
            ActionType.SCREEN_TIMEOUT.name -> {
                val applied = ringerController.applyScreenTimeout(rule.actionValue)
                if (!applied && !Settings.System.canWrite(this)) {
                    showWriteSettingsHintNotification()
                } else if (applied && writeSettingsNotificationShown) {
                    clearWriteSettingsHintNotification()
                }
                applied
            }
            else -> false
        }
    }

    private fun showDndAccessHintNotification() {
        dndNotificationShown = true
        val settingsIntent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = createNotification(
            contentText = getString(R.string.notification_dnd_access_needed),
            pendingIntent = pendingIntent
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun showWriteSettingsHintNotification() {
        writeSettingsNotificationShown = true
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:$packageName")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1002,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = createNotification(
            contentText = getString(R.string.notification_write_settings_needed),
            pendingIntent = pendingIntent
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun clearDndAccessHintNotification() {
        dndNotificationShown = false
        if (writeSettingsNotificationShown) {
            showWriteSettingsHintNotification()
        } else {
            restoreDefaultNotification()
        }
    }

    private fun clearWriteSettingsHintNotification() {
        writeSettingsNotificationShown = false
        if (dndNotificationShown) {
            showDndAccessHintNotification()
        } else {
            restoreDefaultNotification()
        }
    }

    private fun restoreDefaultNotification() {
        dndNotificationShown = false
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotification(
        contentText: String = getString(R.string.notification_text),
        pendingIntent: PendingIntent? = null
    ): Notification {
        val deleteIntent = Intent(this, ServiceKeepAliveReceiver::class.java).apply {
            action = ServiceKeepAliveReceiver.ACTION_NOTIFICATION_DISMISSED
        }
        val deletePendingIntent = PendingIntent.getBroadcast(
            this,
            NOTIFICATION_DISMISSED_REQUEST_CODE,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setDeleteIntent(deletePendingIntent)
            .setOngoing(true)
        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
            builder.setAutoCancel(false)
        }
        return builder.build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_RUN_TIME_RULE_ALARM = "com.kjkao.contextautomator.action.RUN_TIME_RULE_ALARM"
        const val ACTION_RESTORE_NOTIFICATION = "com.kjkao.contextautomator.action.RESTORE_NOTIFICATION"
        const val EXTRA_RULE_ID = "extra_rule_id"
        private const val CHANNEL_ID = "wifi_ringer_service"
        private const val NOTIFICATION_ID = 101
        private const val SCAN_INTERVAL_MS = 180_000L
        private const val CONTEXT_SCAN_INTERVAL_MS = 180_000L
        private const val IDLE_SCAN_INTERVAL_MS = 300_000L
        private const val BLUETOOTH_DISCOVERY_MIN_INTERVAL_MS = 10 * 60_000L
        private const val RULE_EXECUTION_COOLDOWN_MS = 30 * 60 * 1000L
        private const val NOTIFICATION_DISMISSED_REQUEST_CODE = 2002
    }
}

