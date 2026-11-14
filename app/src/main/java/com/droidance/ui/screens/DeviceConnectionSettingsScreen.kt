// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidance.R
import com.droidance.core.device.agent.device.DeviceConnectionMethod
import com.droidance.ui.theme.AppShapes
import com.droidance.ui.viewmodel.DeviceConnectionSettingsViewModel
import com.droidance.ui.connection.connectionMethodLabel

@Composable
fun DeviceConnectionSettingsScreen(
    onBack: () -> Unit,
    onOpenWireless: () -> Unit,
    viewModel: DeviceConnectionSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onReturnFromExternal()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val activeName = stringResource(connectionMethodLabel(state.active))
    val selectedConnected = state.selected == state.active && state.connected
    SubScreenScaffold(
        title = stringResource(R.string.air_pairing_title),
        onBack = onBack,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.navigationBarsPadding()) {
                Button(
                    onClick = {
                        when (state.selected) {
                            DeviceConnectionMethod.ROOT -> viewModel.connectRoot()
                            DeviceConnectionMethod.SHIZUKU -> viewModel.connectShizuku()
                            DeviceConnectionMethod.WIRELESS -> viewModel.connectWireless(onOpenWireless)
                        }
                    },
                    enabled = !state.busy && !state.taskRunning &&
                        !(selectedConnected && state.selected != DeviceConnectionMethod.WIRELESS),
                    shape = AppShapes.Card,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 48.dp)
                ) {
                    Text(stringResource(when {
                        state.busy -> R.string.connection_connecting
                        state.taskRunning -> R.string.connection_task_running
                        selectedConnected && state.selected != DeviceConnectionMethod.WIRELESS -> R.string.settings_air_state_connected
                        selectedConnected -> R.string.connection_manage_wireless
                        state.selected == DeviceConnectionMethod.ROOT -> R.string.connection_connect_root
                        state.selected == DeviceConnectionMethod.SHIZUKU -> when {
                            !state.shizuku.installed && !state.shizuku.running -> R.string.connection_shizuku_install
                            !state.shizuku.running || !state.shizuku.supportedVersion || !state.shizuku.shellIdentity -> R.string.connection_shizuku_open
                            state.shizuku.permissionBlocked -> R.string.connection_shizuku_check
                            !state.shizuku.authorized -> R.string.connection_shizuku_authorize
                            else -> R.string.connection_retry
                        }
                        state.wirelessConnected -> R.string.connection_switch_wireless
                        else -> R.string.connection_continue_wireless
                    }), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SettingsSectionHeader(stringResource(R.string.connection_current))
            SettingsCardGroup {
                Text(
                    stringResource(if (state.connected) R.string.connection_method_connected else R.string.connection_method_disconnected, activeName),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                )
            }
            SettingsSectionHeader(stringResource(R.string.connection_method_title))
            SettingsCardGroup {
                ConnectionMethodRow(
                    title = stringResource(R.string.connection_method_wireless),
                    description = stringResource(R.string.connection_method_wireless_desc),
                    selected = state.selected == DeviceConnectionMethod.WIRELESS,
                    active = state.active == DeviceConnectionMethod.WIRELESS && state.connected,
                    enabled = !state.busy && !state.taskRunning,
                    onClick = { viewModel.select(DeviceConnectionMethod.WIRELESS) }
                )
                SettingsInsetDivider(startIndent = 52.dp)
                ConnectionMethodRow(
                    title = stringResource(R.string.connection_method_root),
                    description = stringResource(R.string.connection_method_root_desc),
                    selected = state.selected == DeviceConnectionMethod.ROOT,
                    active = state.active == DeviceConnectionMethod.ROOT && state.connected,
                    enabled = !state.busy && !state.taskRunning,
                    onClick = { viewModel.select(DeviceConnectionMethod.ROOT) }
                )
                SettingsInsetDivider(startIndent = 52.dp)
                ConnectionMethodRow(
                    title = stringResource(R.string.connection_method_shizuku),
                    description = stringResource(R.string.connection_method_shizuku_desc),
                    selected = state.selected == DeviceConnectionMethod.SHIZUKU,
                    active = state.active == DeviceConnectionMethod.SHIZUKU && state.connected,
                    enabled = !state.busy && !state.taskRunning,
                    onClick = { viewModel.select(DeviceConnectionMethod.SHIZUKU) }
                )
            }
            if (state.selected == DeviceConnectionMethod.SHIZUKU) {
                SettingsSectionHeader(stringResource(R.string.connection_method_shizuku))
                SettingsCardGroup {
                    ShizukuStatusRow(R.string.connection_shizuku_service, when {
                        !state.shizuku.installed && !state.shizuku.running -> R.string.connection_shizuku_not_installed
                        !state.shizuku.running -> R.string.connection_shizuku_not_running
                        else -> R.string.connection_shizuku_running
                    })
                    if (state.shizuku.installed || state.shizuku.running) {
                        SettingsInsetDivider()
                        ShizukuStatusRow(R.string.connection_shizuku_permission, when {
                            !state.shizuku.running -> R.string.connection_shizuku_unknown
                            state.shizuku.authorized -> R.string.connection_shizuku_granted
                            state.shizuku.permissionBlocked -> R.string.connection_shizuku_not_allowed
                            else -> R.string.connection_shizuku_pending
                        })
                        SettingsInsetDivider()
                        SettingsNavItem(title = stringResource(R.string.connection_shizuku_open),
                            onClick = viewModel::openShizuku)
                    }
                }
                val hint = when {
                    state.connected && state.active == DeviceConnectionMethod.SHIZUKU && !state.shizuku.running -> R.string.connection_shizuku_resident_available
                    state.shizuku.running && !state.shizuku.supportedVersion -> R.string.connection_shizuku_update_required
                    state.shizuku.running && !state.shizuku.shellIdentity -> R.string.connection_shizuku_shell_required
                    !state.shizuku.installed && !state.shizuku.running -> R.string.connection_shizuku_install_hint
                    !state.shizuku.running -> R.string.connection_shizuku_start_hint
                    else -> null
                }
                hint?.let { Text(stringResource(it), modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (state.taskRunning) Text(stringResource(R.string.connection_switch_task_running),
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.message?.let { message ->
                Text(stringResource(message), modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ShizukuStatusRow(label: Int, value: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(value), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectionMethodRow(title: String, description: String, selected: Boolean, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (active) Text(stringResource(R.string.connection_method_in_use), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
    }
}
