// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.droidance.R
import com.droidance.core.device.air.AirModeNotificationController
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemSettingsNavigator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val SETTINGS_SUB_SETTINGS = "com.android.settings.SubSettings"
        const val WIRELESS_DEBUGGING_FRAGMENT =
            "com.android.settings.development.WirelessDebuggingFragment"
        const val EXTRA_SHOW_FRAGMENT = ":settings:show_fragment"
        const val EXTRA_SHOW_FRAGMENT_AS_SUBSETTING = ":settings:show_fragment_as_subsetting"
    }

    fun isNotificationListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasPendingDangerousPermissions(): Boolean =
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
            // Shizuku authorization is managed on the device connection page.
            .filter { it != com.droidance.core.device.agent.device.ShizukuConnectionManager.PERMISSION_NAME }
            .any { permission ->
                val info = runCatching { context.packageManager.getPermissionInfo(permission, 0) }.getOrNull()
                info != null &&
                    info.protection == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS &&
                    ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
            }

    fun isPermissionGrantedOrBelow(permission: String, apiLevel: Int): Boolean =
        Build.VERSION.SDK_INT < apiLevel || hasPermission(permission)

    fun hasMediaPermission(permission: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(permission)
        } else {
            hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun openWifiSettings() {
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openDeveloperOptions() {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openWirelessDebuggingSettings() {
        // 先尝试直达「无线调试」详情页（开关 + 使用配对码配对设备）；AOSP 原生不开放
        // SubSettings 给第三方，部分厂商 ROM 开放，失败时一律回退开发者选项页。
        val deepLink = wirelessDebuggingDeepLinkIntent()
        if (deepLink != null && startActivitySafely(deepLink)) return
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun openAirPairingNotificationSettings() {
        val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, AirModeNotificationController.CHANNEL_ID)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startActivitySafely(channelIntent)) return
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun wirelessDebuggingDeepLinkIntent(): Intent? {
        val component = ComponentName(SETTINGS_PACKAGE, SETTINGS_SUB_SETTINGS)
        val info = runCatching {
            context.packageManager.getActivityInfo(
                component,
                PackageManager.MATCH_DISABLED_COMPONENTS
            )
        }.getOrNull() ?: return null
        if (!info.exported || !info.enabled) return null
        return Intent(Intent.ACTION_MAIN).apply {
            setComponent(component)
            putExtra(EXTRA_SHOW_FRAGMENT, WIRELESS_DEBUGGING_FRAGMENT)
            putExtra(EXTRA_SHOW_FRAGMENT_AS_SUBSETTING, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun startActivitySafely(intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }.isSuccess

    fun openNotificationListenerSettings() {
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun requestBatteryOptimizationExemption() {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun shareDebugPackage(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, ContextCompat.getContextForLanguage(context).getString(R.string.settings_debug_share_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, ContextCompat.getContextForLanguage(context).getString(R.string.settings_debug_share_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun getString(@StringRes resId: Int, vararg args: Any): String =
        ContextCompat.getContextForLanguage(context).getString(resId, *args)
}
