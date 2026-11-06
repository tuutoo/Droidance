// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import android.Manifest
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidance.R
import com.droidance.core.device.air.AirModePairingManager
import com.droidance.core.device.agent.device.DeviceConnectionMethod
import com.droidance.core.device.agent.device.RootConnectionManager
import com.droidance.core.device.air.AirPairingFailure
import com.droidance.core.device.air.AirPairingNotificationAvailability
import com.droidance.config.AppLanguage
import com.droidance.config.LanguageManager
import com.droidance.daemon.HeartbeatManager
import com.droidance.debug.DebugExportManager
import com.droidance.settings.ExecutionSettingsPolicy
import com.droidance.settings.ExecutionSettingsRepository
import com.droidance.settings.GeneralSettingsRepository
import com.droidance.settings.ProviderConfigManager
import com.droidance.settings.SystemSettingsNavigator
import com.droidance.settings.AppThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 设置页面 ViewModel
 * 管理服务状态和通用设置（AI Provider 配置已移至独立页面）
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val systemSettingsNavigator: SystemSettingsNavigator,
    private val tokenUsageRepository: com.droidance.config.TokenUsageRepository,
    private val executionSettingsRepository: ExecutionSettingsRepository,
    private val debugExportManager: DebugExportManager,
    private val languageManager: LanguageManager,
    private val providerConfigManager: ProviderConfigManager,
    private val airModePairingManager: AirModePairingManager,
    private val rootConnection: RootConnectionManager,
    private val virtualSpaceSidecarBridge: com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge,
    private val diagnosticsCollector: com.droidance.core.device.debug.DiagnosticsCollector,
    private val debugLogManager: com.droidance.debug.DebugLogManager,
    private val markdownFileManager: com.droidance.memory.MarkdownFileManager,
    private val heartbeatManager: HeartbeatManager,
    private val displayCache: ConversationDisplayCache,
    private val historyDeletion: com.droidance.agent.ConversationHistoryDeletion,
    private val agentRuntime: com.droidance.agent.AgentRuntime,
    private val dataBackupManager: com.droidance.backup.DataBackupManager,
    private val diagnosticsReportExporter: com.droidance.debug.DiagnosticsReportExporter,
    @com.droidance.core.agent.di.ApplicationScope private val appScope: kotlinx.coroutines.CoroutineScope
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    private enum class PendingAirPairingStart { START, REPAIR }
    private var pendingAirPairingStart: PendingAirPairingStart? = null

    init {
        loadSettings()
        checkServiceStatus()
        viewModelScope.launch {
            rootConnection.method.collect { method -> _uiState.update { it.copy(connectionMethod = method) } }
        }
        viewModelScope.launch {
            tokenUsageRepository.records.collect { records ->
                applyTokenStats(_uiState.value.usageRange, records)
            }
        }
        _uiState.update {
            it.copy(
                airPreviouslyPaired = airModePairingManager.isPreviouslyPaired,
                airPairingRequired = airModePairingManager.isPairingRequired,
                airPairingNotificationAvailability = airModePairingManager.notificationAvailability()
            )
        }
        viewModelScope.launch {
            airModePairingManager.state.collect { state ->
                _uiState.update {
                    it.copy(
                        airBridgeState = state,
                        airPreviouslyPaired = airModePairingManager.isPreviouslyPaired,
                        airPairingRequired = airModePairingManager.isPairingRequired
                    )
                }
            }
        }
        viewModelScope.launch {
            airModePairingManager.pairingTrustState.collect {
                _uiState.update { state ->
                    state.copy(
                        airPreviouslyPaired = airModePairingManager.isPreviouslyPaired,
                        airPairingRequired = airModePairingManager.isPairingRequired
                    )
                }
            }
        }
        viewModelScope.launch {
            airModePairingManager.persistenceEnabled.collect { enabled ->
                _uiState.update { it.copy(airPersistenceEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            airModePairingManager.failure.collect { failure ->
                _uiState.update { it.copy(airBridgeFailure = failure) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val alive = virtualSpaceSidecarBridge.isResidentBinderAlive()
                _uiState.update { it.copy(airResidentChannelAvailable = alive, airUsbDebuggingEnabled = airModePairingManager.isUsbDebuggingEnabled()) }
                delay(AIR_RESIDENT_CHANNEL_REFRESH_MS)
            }
        }
    }

    // ====== 通用设置 ======

    fun setHeartbeatInterval(minutes: Float) {
        generalSettingsRepository.setHeartbeatInterval(minutes)
        _uiState.update { it.copy(heartbeatIntervalMinutes = minutes) }
        heartbeatManager.startHeartbeat()
    }

    fun setTaskWallMinutes(value: Int) {
        val minutes = ExecutionSettingsPolicy.normalizeTaskWallMinutes(value)
        executionSettingsRepository.setTaskWallMinutes(minutes)
        _uiState.update { it.copy(taskWallMinutes = minutes) }
    }

    fun toggleDeepLink() {
        val enabled = !_uiState.value.deepLinkEnabled
        executionSettingsRepository.setDeepLinkEnabled(enabled)
        _uiState.update { it.copy(deepLinkEnabled = enabled) }
    }

    fun selectThemeMode(mode: AppThemeMode) {
        _uiState.update { it.copy(themeMode = mode) }
        generalSettingsRepository.setThemeMode(mode)
    }

    fun reconnectAirPairing() { airModePairingManager.reconnect() }
    fun connectAirPairingPort(port: Int) { airModePairingManager.reconnect(port) }
    fun openWirelessDebuggingAndStartAirPairing() {
        startWirelessDebuggingAndAirPairing()
    }

    private fun startWirelessDebuggingAndAirPairing(): Boolean {
        val started = if (airModePairingManager.isPreviouslyPaired) {
            airModePairingManager.reconnect()
        } else {
            airModePairingManager.startDiscovery()
        }
        if (started) openWirelessDebuggingSettings()
        return started
    }

    fun prepareAirPairingAfterNotificationAccess(
        repair: Boolean
    ): AirPairingNotificationAvailability {
        pendingAirPairingStart = if (repair) {
            PendingAirPairingStart.REPAIR
        } else {
            PendingAirPairingStart.START
        }
        val availability = airModePairingManager.notificationAvailability()
        _uiState.update { it.copy(airPairingNotificationAvailability = availability) }
        debugLogManager.appendPersistentEvent(
            "air-pairing",
            "AIR pairing start requested: notification=${availability.name.lowercase()}"
        )
        if (availability == AirPairingNotificationAvailability.AVAILABLE) {
            resumePendingAirPairingAfterNotificationAccess()
        }
        return availability
    }

    fun onAirPairingNotificationPermissionResult(granted: Boolean) {
        debugLogManager.appendPersistentEvent(
            "air-pairing",
            "AIR pairing notification permission result: granted=$granted"
        )
        if (granted) {
            refreshServiceStatus()
            resumePendingAirPairingAfterNotificationAccess()
        } else {
            openAirPairingNotificationSettings()
        }
    }

    fun resumePendingAirPairingAfterNotificationAccess(): Boolean {
        val pendingStart = pendingAirPairingStart ?: return false
        val availability = airModePairingManager.notificationAvailability()
        _uiState.update { it.copy(airPairingNotificationAvailability = availability) }
        if (availability != AirPairingNotificationAvailability.AVAILABLE) return false
        val started = when (pendingStart) {
            PendingAirPairingStart.START -> startWirelessDebuggingAndAirPairing()
            PendingAirPairingStart.REPAIR -> startRequiredAirPairingInternal()
        }
        if (started) pendingAirPairingStart = null
        debugLogManager.appendPersistentEvent(
            "air-pairing",
            "AIR pairing pending start resumed: started=$started"
        )
        return started
    }
    fun startRequiredAirPairing() {
        startRequiredAirPairingInternal()
    }

    private fun startRequiredAirPairingInternal(): Boolean {
        val started = airModePairingManager.startRequiredPairing()
        if (started) {
            openWirelessDebuggingSettings()
        }
        return started
    }
    fun stopAirPairing() { airModePairingManager.disconnect() }
    fun setAdbPersistence(enabled: Boolean) {
        if (_uiState.value.airPersistenceChanging) return
        if (enabled) {
            airModePairingManager.enableAdbPersistence()
            if (airModePairingManager.isUsbDebuggingEnabled() != true) {
                systemSettingsNavigator.openDeveloperOptions()
            }
        } else stopAdbPersistence(resetTrust = false)
    }

    fun openDeveloperOptions() { systemSettingsNavigator.openDeveloperOptions() }

    fun resetAirPairing() { stopAdbPersistence(resetTrust = true) }

    private fun stopAdbPersistence(resetTrust: Boolean) {
        if (_uiState.value.airPersistenceChanging) return
        _uiState.update { it.copy(airPersistenceChanging = true) }
        appScope.launch(Dispatchers.IO) {
            try {
                airModePairingManager.stopAdbPersistence(resetTrust) {
                    virtualSpaceSidecarBridge.stopResidentChannel()
                }
            } finally { _uiState.update { it.copy(airPersistenceChanging = false) } }
        }
    }

    fun refreshLanguage() {
        _uiState.update { it.copy(appLanguage = languageManager.getPreference()) }
    }

    fun selectLanguage(language: AppLanguage) {
        languageManager.setPreference(language)
        _uiState.update { it.copy(appLanguage = language) }
    }

    // ====== 保存/加载 ======

    private fun loadSettings() {
        val themeMode = generalSettingsRepository.themeMode()
        _uiState.value = SettingsUiState(
            heartbeatIntervalMinutes = generalSettingsRepository.heartbeatInterval(),
            themeMode = themeMode,
            taskWallMinutes = executionSettingsRepository.taskWallMinutes(),
            deepLinkEnabled = executionSettingsRepository.isDeepLinkEnabled(),
            appLanguage = languageManager.getPreference(),
            providerReady = isProviderReady()
        )
    }

    // ====== 服务状态检查 ======

    private fun checkServiceStatus() {
        _uiState.update {
            it.copy(
                notificationListenerEnabled = systemSettingsNavigator.isNotificationListenerEnabled(),
                providerReady = isProviderReady(),
                postNotificationsGranted = systemSettingsNavigator.isPermissionGrantedOrBelow(
                    Manifest.permission.POST_NOTIFICATIONS,
                    Build.VERSION_CODES.TIRAMISU
                ),
                readContactsGranted = systemSettingsNavigator.hasPermission(Manifest.permission.READ_CONTACTS),
                writeContactsGranted = systemSettingsNavigator.hasPermission(Manifest.permission.WRITE_CONTACTS),
                readSmsGranted = systemSettingsNavigator.hasPermission(Manifest.permission.READ_SMS),
                sendSmsGranted = systemSettingsNavigator.hasPermission(Manifest.permission.SEND_SMS),
                receiveSmsGranted = systemSettingsNavigator.hasPermission(Manifest.permission.RECEIVE_SMS),
                readCallLogGranted = systemSettingsNavigator.hasPermission(Manifest.permission.READ_CALL_LOG),
                writeCallLogGranted = systemSettingsNavigator.hasPermission(Manifest.permission.WRITE_CALL_LOG),
                callPhoneGranted = systemSettingsNavigator.hasPermission(Manifest.permission.CALL_PHONE),
                readCalendarGranted = systemSettingsNavigator.hasPermission(Manifest.permission.READ_CALENDAR),
                writeCalendarGranted = systemSettingsNavigator.hasPermission(Manifest.permission.WRITE_CALENDAR),
                readMediaImagesGranted = systemSettingsNavigator.hasMediaPermission(Manifest.permission.READ_MEDIA_IMAGES),
                readMediaVideoGranted = systemSettingsNavigator.hasMediaPermission(Manifest.permission.READ_MEDIA_VIDEO),
                readMediaAudioGranted = systemSettingsNavigator.hasMediaPermission(Manifest.permission.READ_MEDIA_AUDIO),
                readExternalStorageGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
                    systemSettingsNavigator.hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE),
                writeExternalStorageGranted = Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
                    systemSettingsNavigator.hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                exactAlarmGranted = systemSettingsNavigator.canScheduleExactAlarms(),
                batteryOptimizationIgnored = systemSettingsNavigator.isIgnoringBatteryOptimizations(),
                airPairingNotificationAvailability = airModePairingManager.notificationAvailability(),
                airPairingRequired = airModePairingManager.isPairingRequired
            )
        }
    }

    private fun isProviderReady(): Boolean {
        return providerConfigManager.getEnabledConfigs().any { config ->
            config.baseUrl.isNotBlank() && providerConfigManager.getCurrentModel(config).isNotBlank()
        }
    }

    fun openWirelessDebuggingSettings() {
        airModePairingManager.refreshPairingNotificationIfAwaiting()
        systemSettingsNavigator.openWirelessDebuggingSettings()
    }

    fun openAirPairingNotificationSettings() {
        systemSettingsNavigator.openAirPairingNotificationSettings()
    }

    fun openNotificationListenerSettings() {
        systemSettingsNavigator.openNotificationListenerSettings()
    }

    fun requestExactAlarmPermission() {
        systemSettingsNavigator.requestExactAlarmPermission()
    }

    fun requestBatteryOptimizationExemption() {
        systemSettingsNavigator.requestBatteryOptimizationExemption()
    }

    fun loadTokenStats(range: UsageRange = _uiState.value.usageRange) {
        applyTokenStats(range, tokenUsageRepository.records.value)
    }

    private fun applyTokenStats(
        range: UsageRange,
        allRecords: List<com.droidance.config.TokenUsageRepository.DailyUsage>
    ) {
        val recentDates = range.days?.let { days ->
            buildSet {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val cal = java.util.Calendar.getInstance()
                repeat(days) {
                    add(fmt.format(cal.time))
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                }
            }
        }
        val records = recentDates?.let { dates -> allRecords.filter { it.date in dates } } ?: allRecords
        val byDate = records.groupBy { it.date }
        val daily = range.days?.let { days ->
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val cal = java.util.Calendar.getInstance()
            (0 until days).map {
                val date = fmt.format(cal.time)
                cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                byDate.toDailyUsage(date)
            }.reversed()
        } ?: byDate.keys.sorted().map { date -> byDate.toDailyUsage(date) }
        _uiState.update {
            it.copy(
                usageRange = range,
                totalPromptTokens = records.sumOf { record -> record.promptTokens },
                totalCompletionTokens = records.sumOf { record -> record.completionTokens },
                totalCalls = records.sumOf { record -> record.calls },
                cachedInputTokens = records.sumOf { record -> record.cachedInputTokens },
                cacheReportedInputTokens = records.sumOf { record -> record.cacheReportedInputTokens },
                cacheReportedCalls = records.sumOf { record -> record.cacheReportedCalls },
                dailyUsage = daily,
                modelUsage = records
                    .groupBy { record -> record.provider to record.model }
                    .map { (identity, entries) ->
                        ModelTokenUsage(
                            provider = identity.first,
                            model = identity.second,
                            promptTokens = entries.sumOf { it.promptTokens },
                            completionTokens = entries.sumOf { it.completionTokens },
                            calls = entries.sumOf { it.calls }
                        )
                    }
                    .sortedByDescending { it.total }
                    .take(10)
            )
        }
    }

    private fun Map<String, List<com.droidance.config.TokenUsageRepository.DailyUsage>>.toDailyUsage(
        date: String
    ): DailyTokenUsage {
        val entries = get(date).orEmpty()
        return DailyTokenUsage(
            date = date,
            promptTokens = entries.sumOf { it.promptTokens },
            completionTokens = entries.sumOf { it.completionTokens },
            cachedInputTokens = entries.sumOf { it.cachedInputTokens },
            cacheReportedInputTokens = entries.sumOf { it.cacheReportedInputTokens },
            cacheReportedCalls = entries.sumOf { it.cacheReportedCalls }
        )
    }

    private val dataBusy get() = _uiState.value.let { it.debugExporting || it.dataClearing != null || it.backupOperation != null }

    fun exportBackup(target: android.net.Uri, password: String) = runBackup(BackupOperation.EXPORT) {
        DataResult(BackupOperation.EXPORT, dataBackupManager.export(target, password))
    }

    fun restoreBackup(source: android.net.Uri, password: String) = runBackup(BackupOperation.RESTORE) {
        DataResult(BackupOperation.RESTORE, dataBackupManager.restore(source, password))
    }

    private fun runBackup(operation: BackupOperation, action: () -> DataResult) {
        if (dataBusy) return
        _uiState.update { it.copy(backupOperation = operation, dataActionMessage = "") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = action()
                _uiState.update { it.copy(dataResult = result) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.droidance.backup.DataBackupManager.BackupException) {
                _uiState.update { it.copy(dataActionMessage = systemSettingsNavigator.getString(backupFailure(e.code))) }
            } finally {
                _uiState.update { it.copy(backupOperation = null) }
            }
        }
    }

    private fun backupFailure(code: String): Int = when (code) {
        "BACKUP_WRONG_PASSWORD" -> R.string.settings_backup_error_password
        "BACKUP_NOT_RECOGNIZED" -> R.string.settings_backup_error_not_backup
        "BACKUP_TRUNCATED", "BACKUP_CORRUPTED" -> R.string.settings_backup_error_corrupted
        "BACKUP_UNSUPPORTED", "BACKUP_NEWER" -> R.string.settings_backup_error_newer
        "BACKUP_BUSY" -> R.string.settings_backup_error_busy
        "BACKUP_RUNTIME_UNAVAILABLE" -> R.string.settings_backup_error_unavailable
        else -> R.string.settings_backup_error_failed
    }

    fun consumeDataResult() {
        _uiState.update { it.copy(dataResult = null) }
    }

    fun restartAfterRestore() = dataBackupManager.restartApplication()

    /** The redacted package by default; the full package only when the user includes task content. */
    fun exportDiagnostics(includeTaskContent: Boolean) {
        if (includeTaskContent) return exportDebugPackage()
        if (dataBusy) return
        _uiState.update { it.copy(debugExporting = true, dataActionMessage = "") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = diagnosticsReportExporter.export()
                withContext(Dispatchers.Main) { systemSettingsNavigator.shareDebugPackage(file) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(dataActionMessage = systemSettingsNavigator.getString(
                        R.string.settings_export_failed,
                        e.message ?: systemSettingsNavigator.getString(R.string.settings_unknown_error)))
                }
            } finally {
                _uiState.update { it.copy(debugExporting = false) }
            }
        }
    }

    private fun exportDebugPackage() {
        if (dataBusy) return
        _uiState.update {
            it.copy(debugExporting = true, dataActionMessage = "")
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = debugExportManager.exportZip()
                withContext(Dispatchers.Main) {
                    systemSettingsNavigator.shareDebugPackage(file)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        dataActionMessage = systemSettingsNavigator.getString(
                            R.string.settings_export_failed,
                            e.message ?: systemSettingsNavigator.getString(R.string.settings_unknown_error)
                        )
                    )
                }
            } finally {
                _uiState.update { it.copy(debugExporting = false) }
            }
        }
    }

    /** 清空所有对话 */
    fun clearAllConversations() {
        if (dataBusy) return
        _uiState.update { it.copy(dataClearing = DataClearTarget.CONVERSATIONS, dataActionMessage = "") }
        appScope.launch(Dispatchers.IO) {
            val result = try { Result.success(historyDeletion.delete().also { displayCache.invalidate(null) }) }
                catch (cancelled: kotlinx.coroutines.CancellationException) {
                    _uiState.update { it.copy(dataClearing = null) }
                    throw cancelled
                } catch (error: Exception) { Result.failure(error) }
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        dataClearing = null,
                        dataActionMessage = systemSettingsNavigator.getString(
                            if (result.isSuccess) R.string.settings_clear_conversations_done
                            else R.string.settings_clear_conversations_failed
                        )
                    )
                }
            }
        }
    }

    /** 清理诊断目录和当前运行日志；保留对话事件、回放附件、其他持久日志、记忆与设置。 */
    fun clearLogData() {
        if (dataBusy) return
        _uiState.update { it.copy(dataClearing = DataClearTarget.LOGS, dataActionMessage = "") }
        appScope.launch(Dispatchers.IO) {
            val result = try {
                agentRuntime.clearLogData()
                Result.success(Unit)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(dataClearing = null) }
                throw cancelled
            } catch (error: Exception) { Result.failure(error) }
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        dataClearing = null,
                        dataActionMessage = systemSettingsNavigator.getString(
                            if (result.isSuccess) R.string.settings_clear_logs_done
                            else R.string.settings_clear_logs_failed
                        )
                    )
                }
            }
        }
    }

    fun refreshServiceStatus() {
        airModePairingManager.refreshPairingNotificationIfAwaiting()
        checkServiceStatus()
    }

    fun consumeDataActionMessage() {
        _uiState.update { it.copy(dataActionMessage = "") }
    }

}

/**
 * 设置页面 UI 状态（简化版，AI 配置已移至 ProviderConfigManager）
 */
enum class DataClearTarget { CONVERSATIONS, LOGS }

data class SettingsUiState(
    // 服务状态
    val notificationListenerEnabled: Boolean = false,
    val postNotificationsGranted: Boolean = false,
    val readContactsGranted: Boolean = false,
    val writeContactsGranted: Boolean = false,
    val readSmsGranted: Boolean = false,
    val sendSmsGranted: Boolean = false,
    val receiveSmsGranted: Boolean = false,
    val readCallLogGranted: Boolean = false,
    val writeCallLogGranted: Boolean = false,
    val callPhoneGranted: Boolean = false,
    val readCalendarGranted: Boolean = false,
    val writeCalendarGranted: Boolean = false,
    val readMediaImagesGranted: Boolean = false,
    val readMediaVideoGranted: Boolean = false,
    val readMediaAudioGranted: Boolean = false,
    val readExternalStorageGranted: Boolean = false,
    val writeExternalStorageGranted: Boolean = false,
    val exactAlarmGranted: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,

    // 通用设置
    val heartbeatIntervalMinutes: Float = 15f,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val taskWallMinutes: Int = ExecutionSettingsPolicy.DEFAULT_TASK_WALL_MINUTES,
    val deepLinkEnabled: Boolean = ExecutionSettingsPolicy.DEFAULT_ENABLE_DEEPLINK,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,

    // Token 用量统计
    val usageRange: UsageRange = UsageRange.SEVEN_DAYS,
    val totalPromptTokens: Long = 0,
    val totalCompletionTokens: Long = 0,
    val totalCalls: Long = 0,
    val cachedInputTokens: Long = 0,
    val cacheReportedInputTokens: Long = 0,
    val cacheReportedCalls: Long = 0,
    val dailyUsage: List<DailyTokenUsage> = emptyList(),
    val modelUsage: List<ModelTokenUsage> = emptyList(),

    // 设备能力
    val providerReady: Boolean = false,

    // AIR 无线调试
    val connectionMethod: DeviceConnectionMethod = DeviceConnectionMethod.WIRELESS,
    val airBridgeState: AirModePairingManager.PairingState = AirModePairingManager.PairingState.IDLE,
    val airPreviouslyPaired: Boolean = false,
    val airPairingRequired: Boolean = false,
    val airPairingNotificationAvailability: AirPairingNotificationAvailability =
        AirPairingNotificationAvailability.AVAILABLE,
    val airBridgeFailure: AirPairingFailure? = null,
    val airResidentChannelAvailable: Boolean = false,
    val airPersistenceEnabled: Boolean = false,
    val airPersistenceChanging: Boolean = false,
    val airUsbDebuggingEnabled: Boolean? = null,

    // Debug 导出
    val debugExporting: Boolean = false,
    val dataClearing: DataClearTarget? = null,
    val backupOperation: BackupOperation? = null,
    val dataResult: DataResult? = null,
    val dataActionMessage: String = ""
) {
    /** The resident sidecar keeps device control after Wi-Fi loss closes the live ADB connection. */
    val airDeviceConnected: Boolean
        get() = airBridgeState == AirModePairingManager.PairingState.CONNECTED ||
            (connectionMethod == DeviceConnectionMethod.WIRELESS && airBridgeState == AirModePairingManager.PairingState.IDLE &&
                airPreviouslyPaired && !airPairingRequired && airResidentChannelAvailable)

    val deviceConnected: Boolean
        get() = if (connectionMethod == DeviceConnectionMethod.ROOT) airResidentChannelAvailable else airDeviceConnected
}

private const val AIR_RESIDENT_CHANNEL_REFRESH_MS = 2_000L

enum class UsageRange(val days: Int?) {
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
    ALL(null)
}

/** 用量页条形图的每日汇总（跨 Provider） */
data class DailyTokenUsage(
    val date: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedInputTokens: Long = 0,
    val cacheReportedInputTokens: Long = 0,
    val cacheReportedCalls: Long = 0
) {
    val total: Long get() = promptTokens + completionTokens

    /** null 表示该日没有 Provider 返回可验证的缓存字段。 */
    val cacheHitRate: Float?
        get() = when {
            cacheReportedCalls <= 0L -> null
            cacheReportedInputTokens <= 0L -> 0f
            else -> (cachedInputTokens.toFloat() / cacheReportedInputTokens.toFloat()).coerceIn(0f, 1f)
        }
}

data class ModelTokenUsage(
    val provider: String,
    val model: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val calls: Long
) {
    val total: Long get() = promptTokens + completionTokens
}

enum class BackupOperation { EXPORT, RESTORE }

/** What a finished backup or restore carried, shown to the user. */
data class DataResult(val operation: BackupOperation, val contents: com.droidance.backup.BackupContents)
