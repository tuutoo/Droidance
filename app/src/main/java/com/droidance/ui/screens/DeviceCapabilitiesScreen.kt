// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import com.droidance.ui.components.droidanceFieldColors
import com.droidance.ui.components.DroidanceDialog

import com.droidance.ui.icons.DroidanceIcons

import android.Manifest
import android.os.Build
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droidance.R
import com.droidance.ui.theme.Neutral0
import com.droidance.ui.theme.Neutral300
import com.droidance.core.device.air.AirModePairingManager
import com.droidance.core.device.agent.device.DeviceConnectionMethod
import com.droidance.ui.connection.connectionMethodLabel
import com.droidance.ui.viewmodel.SettingsUiState

internal data class DeviceCapabilityActions(
    val onBack: () -> Unit,
    val onOpenAirPairing: () -> Unit,
    val onTaskWallMinutesChange: (Int) -> Unit,
    val onToggleDeepLink: () -> Unit
)

internal data class PermissionActions(
    val onOpenNotificationListener: () -> Unit,
    val onRequestRuntimePermissions: (Array<String>) -> Unit,
    val onRequestExactAlarmPermission: () -> Unit,
    val onRequestBatteryOptimizationExemption: () -> Unit
)

@Composable
internal fun DeviceCapabilitiesScreen(
    state: SettingsUiState,
    actions: DeviceCapabilityActions
) {
    SubScreenScaffold(
        title = stringResource(R.string.settings_device_execution),
        onBack = actions.onBack
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            item { ReadinessSection(state, actions) }
            item {
                Spacer(Modifier.height(20.dp))
                ExecutionSettingsSection(state, actions)
            }
        }
    }
}

@Composable
internal fun PermissionsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    actions: PermissionActions
) {
    SubScreenScaffold(
        title = stringResource(R.string.settings_permissions),
        onBack = onBack
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            item { PermissionSection(state, actions) }
        }
    }
}

/** 连接只留一行入口，配对全过程在独立向导页完成（初始化页进的是同一页） */
@Composable
private fun ReadinessSection(
    state: SettingsUiState,
    actions: DeviceCapabilityActions
) {
    Column {
        SettingsSectionHeader(stringResource(R.string.settings_section_connectivity))
        SettingsCardGroup {
            SettingsNavItem(
                title = stringResource(R.string.air_pairing_title),
                subtitle = if (state.connectionMethod != DeviceConnectionMethod.WIRELESS) {
                    stringResource(if (state.deviceConnected) R.string.connection_method_connected else R.string.connection_method_disconnected,
                        stringResource(connectionMethodLabel(state.connectionMethod)))
                } else when (state.airBridgeState) {
                    AirModePairingManager.PairingState.IDLE ->
                        when {
                            state.airDeviceConnected -> stringResource(R.string.connection_method_connected, stringResource(R.string.connection_method_wireless))
                            state.airPairingRequired -> stringResource(R.string.settings_air_state_pairing_required)
                            state.airPreviouslyPaired -> stringResource(R.string.settings_air_state_paired)
                            else -> stringResource(R.string.settings_air_state_unpaired)
                        }
                    AirModePairingManager.PairingState.DISCOVERING -> stringResource(R.string.settings_air_state_discovering)
                    AirModePairingManager.PairingState.AWAITING_CODE -> stringResource(R.string.settings_air_state_awaiting_code)
                    AirModePairingManager.PairingState.PAIRING -> stringResource(R.string.settings_air_state_pairing)
                    AirModePairingManager.PairingState.CONNECTING -> stringResource(R.string.settings_air_state_reconnecting)
                    AirModePairingManager.PairingState.CONNECTED -> stringResource(R.string.connection_method_connected, stringResource(R.string.connection_method_wireless))
                },
                icon = DroidanceIcons.Link,
                onClick = actions.onOpenAirPairing
            )
        }
    }
}

@Composable
private fun PermissionSection(
    state: SettingsUiState,
    actions: PermissionActions
) {
    val runtimeRows = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                runtimePermissionRow(
                    name = stringResource(R.string.settings_permission_group_notifications),
                    description = stringResource(R.string.settings_permission_group_notifications_desc),
                    active = state.postNotificationsGranted,
                    permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                    actions = actions,
                    icon = DroidanceIcons.Notifications
                )
            )
        }
        add(
            runtimePermissionRow(
                name = stringResource(R.string.settings_permission_group_contacts),
                description = stringResource(R.string.settings_permission_group_contacts_desc),
                active = state.readContactsGranted && state.writeContactsGranted,
                permissions = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
                actions = actions,
                icon = DroidanceIcons.Person
            )
        )
        add(
            runtimePermissionRow(
                name = stringResource(R.string.settings_permission_group_messages_calls),
                description = stringResource(R.string.settings_permission_group_messages_calls_desc),
                active = state.readSmsGranted && state.sendSmsGranted && state.receiveSmsGranted &&
                    state.readCallLogGranted && state.writeCallLogGranted && state.callPhoneGranted,
                permissions = listOf(
                    Manifest.permission.READ_SMS,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.WRITE_CALL_LOG,
                    Manifest.permission.CALL_PHONE
                ),
                actions = actions,
                icon = DroidanceIcons.Phone
            )
        )
        add(
            runtimePermissionRow(
                name = stringResource(R.string.settings_permission_group_calendar),
                description = stringResource(R.string.settings_permission_group_calendar_desc),
                active = state.readCalendarGranted && state.writeCalendarGranted,
                permissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                actions = actions,
                icon = DroidanceIcons.Calendar
            )
        )
        val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            buildList {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
        val mediaGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            state.readMediaImagesGranted && state.readMediaVideoGranted && state.readMediaAudioGranted
        } else {
            state.readExternalStorageGranted &&
                (Build.VERSION.SDK_INT > Build.VERSION_CODES.P || state.writeExternalStorageGranted)
        }
        add(
            runtimePermissionRow(
                name = stringResource(R.string.settings_permission_group_media),
                description = stringResource(R.string.settings_permission_group_media_desc),
                active = mediaGranted,
                permissions = mediaPermissions,
                actions = actions,
                icon = DroidanceIcons.Image
            )
        )
    }
    val specialRows = buildList {
        add(
            PermissionRowSpec(
                stringResource(R.string.settings_notification_listener),
                state.notificationListenerEnabled,
                stringResource(R.string.settings_permission_notification_listener_desc),
                actions.onOpenNotificationListener,
                icon = DroidanceIcons.MessageCheck
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(
                PermissionRowSpec(
                    stringResource(R.string.settings_permission_exact_alarm),
                    state.exactAlarmGranted,
                    stringResource(R.string.settings_permission_exact_alarm_desc),
                    actions.onRequestExactAlarmPermission,
                    icon = DroidanceIcons.Alarm
                )
            )
        }
        add(
            PermissionRowSpec(
                stringResource(R.string.settings_permission_battery_optimization),
                state.batteryOptimizationIgnored,
                stringResource(R.string.settings_permission_battery_optimization_desc),
                actions.onRequestBatteryOptimizationExemption,
                icon = DroidanceIcons.BatteryCharging
            )
        )
    }
    val allRuntimePermissions = runtimeRows.flatMap { it.permissions }.distinct().toTypedArray()
    val allRuntimeGranted = runtimeRows.all { it.active }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsCardGroup {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_permission_request_all_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_permission_request_all_desc),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (allRuntimeGranted) {
                    Text(stringResource(R.string.settings_authorized), style = MaterialTheme.typography.labelMedium)
                } else {
                    Button(onClick = { actions.onRequestRuntimePermissions(allRuntimePermissions) }) {
                        Text(stringResource(R.string.settings_permission_request_all_short))
                    }
                }
            }
        }
        SettingsCardGroup {
            (runtimeRows + specialRows).forEachIndexed { index, row ->
                if (index > 0) SettingsInsetDivider()
                PermissionStatusItem(
                    name = row.name,
                    active = row.active,
                    onAction = row.onAction,
                    description = row.description,
                    actionText = stringResource(R.string.settings_permission_authorize_action),
                    icon = row.icon
                )
            }
        }
    }
}

private data class PermissionRowSpec(
    val name: String,
    val active: Boolean,
    val description: String,
    val onAction: (() -> Unit)? = null,
    val permissions: List<String> = emptyList(),
    val icon: ImageVector? = null
)

private fun runtimePermissionRow(
    name: String,
    description: String,
    active: Boolean,
    permissions: List<String>,
    actions: PermissionActions,
    icon: ImageVector? = null
): PermissionRowSpec = PermissionRowSpec(
    name = name,
    active = active,
    description = description,
    onAction = { actions.onRequestRuntimePermissions(permissions.toTypedArray()) },
    permissions = permissions,
    icon = icon
)

@Composable
private fun ExecutionSettingsSection(
    state: SettingsUiState,
    actions: DeviceCapabilityActions
) {
    SettingsCardGroup {
        SettingsSwitchItem(
            title = stringResource(R.string.settings_enable_deeplink),
            subtitle = stringResource(R.string.settings_enable_deeplink_subtitle),
            checked = state.deepLinkEnabled,
            onToggle = actions.onToggleDeepLink
        )
        SettingsInsetDivider()
        ExecutionLimitSetting(
            title = stringResource(R.string.settings_task_wall),
            subtitle = stringResource(R.string.settings_task_wall_subtitle),
            value = state.taskWallMinutes,
            unit = stringResource(R.string.settings_limit_minutes),
            onValueChange = actions.onTaskWallMinutesChange
        )
    }
}

@Composable
private fun ExecutionLimitSetting(
    title: String,
    subtitle: String,
    value: Int,
    unit: String,
    onValueChange: (Int) -> Unit
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val openEditor = { input = value.takeIf { it > 0 }?.toString().orEmpty(); editing = true }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = value > 0,
                onCheckedChange = { if (it) openEditor() else onValueChange(0) },
                colors = com.droidance.ui.components.droidanceSwitchColors())
        }
        if (value > 0) {
            TextButton(onClick = openEditor) { Text(stringResource(R.string.settings_limit_value, value, unit)) }
        } else {
            Text(stringResource(R.string.settings_limit_unlimited),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
    if (editing) {
        val number = input.toIntOrNull()
        val valid = number != null && number > 0
        DroidanceDialog(onDismissRequest = { editing = false }, title = title,
            confirmLabel = stringResource(R.string.common_save), confirmEnabled = valid,
            onConfirm = { onValueChange(requireNotNull(number)); editing = false },
            dismissLabel = stringResource(R.string.common_cancel)) {
            OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                label = { Text(unit) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = input.isNotEmpty() && !valid, modifier = Modifier.fillMaxWidth(),
                colors = droidanceFieldColors(), shape = com.droidance.ui.theme.AppShapes.Control)
        }
    }
}

/** 轻量图标块：30dp 圆角方形淡灰底 + 单色 outlined 图标，Notion 式行首视觉锚点 */
@Composable
internal fun SettingsIconTile(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
internal fun PermissionStatusItem(
    name: String,
    active: Boolean,
    onAction: (() -> Unit)?,
    description: String?,
    actionText: String? = null,
    alwaysShowAction: Boolean = false,
    statusText: String? = null,
    icon: ImageVector? = null
) {
    val action = onAction?.takeIf { !active || alwaysShowAction }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (action != null) it.clickable(onClick = action) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            SettingsIconTile(icon)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (action != null) {
            Spacer(Modifier.width(12.dp))
            SettingsPillLabel(actionText ?: stringResource(R.string.settings_enable))
        } else {
            Text(
                text = statusText ?: stringResource(
                    if (active) R.string.settings_authorized else R.string.settings_permission_not_authorized
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}
