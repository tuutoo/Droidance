// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidance.core.device.config.InstalledAppsManager
import com.droidance.core.device.air.AirModePairingManager
import com.droidance.core.device.agent.device.DeviceConnectionMethod
import com.droidance.core.device.agent.device.RootConnectionManager
import com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge
import com.droidance.settings.ProviderConfigManager
import com.droidance.core.knowledge.agent.device.DeviceProfileStore
import com.droidance.core.device.tools.AdbShellExecutor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceProfileStore: DeviceProfileStore,
    private val installedAppsManager: InstalledAppsManager,
    private val providerConfigManager: ProviderConfigManager,
    private val airModePairingManager: AirModePairingManager,
    private val rootConnection: RootConnectionManager,
    private val sidecarBridge: VirtualSpaceSidecarBridge,
    private val generalSettings: com.droidance.settings.GeneralSettingsRepository
) : ViewModel() {

    private val _scanResult = MutableStateFlow<ScanResult?>(null)
    val scanResult: StateFlow<ScanResult?> = _scanResult

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning


    private val _isComplete = MutableStateFlow(false)
    val isComplete: StateFlow<Boolean> = _isComplete

    init { startScan() }


    fun startScan() {
        viewModelScope.launch {
            _isScanning.value = true
            val result = withContext(Dispatchers.IO) { doScan() }
            _scanResult.value = result
            _isScanning.value = false
        }
    }

    private fun doScan(): ScanResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} | " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | Battery $battery%"


        // 通知权限
        val notifStatus = try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (nm.isNotificationListenerAccessGranted(
                    android.content.ComponentName(context, com.droidance.automation.NotificationWatcher::class.java)
                )) "granted" else "not granted"
        } catch (_: Exception) { "unknown" }

        // 已安装用户应用 —— 使用 MATCH_UNINSTALLED_PACKAGES 确保 Android 11+ 能看到全部包
        // 需要 Manifest 中声明 QUERY_ALL_PACKAGES
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
        val allApps = try {
            pm.getInstalledApplications(flags)
        } catch (_: Exception) {
            pm.getInstalledApplications(0)
        }
        val userApps = allApps
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { app -> runCatching { pm.getApplicationLabel(app).toString() }.getOrElse { app.packageName } }
            .map { app ->
                val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrElse { app.packageName }
                "$label → ${app.packageName}"
            }

        val enabledProviders = providerConfigManager.getEnabledConfigs()
        val providerReady = enabledProviders.any { config ->
            config.defaultModel.isNotBlank() || config.models.isNotEmpty()
        }
        val visionReady = providerReady
        val deviceReady = deviceConnected()

        return ScanResult(
            deviceInfo  = deviceInfo,
            notifStatus = notifStatus,
            userApps    = userApps,
            providerReady = providerReady,
            deviceReady = deviceReady,
            connectionMethod = rootConnection.currentMethod,
            visionReady = visionReady
        )
    }

    // ====== 权限状态刷新（用户从系统设置返回时调用） ======

    fun refreshPermissionStatus() {
        val current = _scanResult.value ?: return

        val notifGranted = try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.isNotificationListenerAccessGranted(
                android.content.ComponentName(context, com.droidance.automation.NotificationWatcher::class.java)
            )
        } catch (_: Exception) { false }

        val enabledProviders = providerConfigManager.getEnabledConfigs()
        val providerReady = enabledProviders.any { config ->
            config.defaultModel.isNotBlank() || config.models.isNotEmpty()
        }
        val visionReady = providerReady

        _scanResult.value = current.copy(
            notifStatus = if (notifGranted) "granted" else "not granted",
            providerReady = providerReady,
            deviceReady = deviceConnected(),
            connectionMethod = rootConnection.currentMethod,
            visionReady = visionReady
        )
    }

    private fun deviceConnected(): Boolean =
        if (rootConnection.currentMethod != DeviceConnectionMethod.WIRELESS) sidecarBridge.isResidentBinderAlive()
        else airModePairingManager.isConnected ||
            (airModePairingManager.isPreviouslyPaired && !airModePairingManager.isPairingRequired &&
                sidecarBridge.isResidentBinderAlive())

    // ====== 完成引导，写入 DEVICE.md ======

    fun completeOnboarding() {
        val scan = _scanResult.value ?: return
        viewModelScope.launch {
            deviceProfileStore.save(buildDeviceProfile(scan))
            _isComplete.value = true
        }
    }

    private fun buildDeviceProfile(scan: ScanResult): String {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

        return buildString {
            appendLine("# Device Profile")
            appendLine("Generated at: $now")
            appendLine()
            appendLine("## Device")
            appendLine(scan.deviceInfo)
            appendLine()
            appendLine("## Runtime capabilities")
            appendLine("- Notification access: ${scan.notifStatus}")
            appendLine()
            appendLine("## Installed user applications (${scan.userApps.size})")
            scan.userApps.forEach { appendLine("- $it") }
        }
    }
}

data class ScanResult(
    val deviceInfo: String,
    val notifStatus: String,
    val userApps: List<String>,
    val providerReady: Boolean,
    val deviceReady: Boolean,
    val connectionMethod: DeviceConnectionMethod,
    val visionReady: Boolean
)
