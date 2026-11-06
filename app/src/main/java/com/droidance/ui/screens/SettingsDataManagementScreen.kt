// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import com.droidance.ui.components.DroidanceSnackbar

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidance.R
import com.droidance.ui.viewmodel.SettingsViewModel
import com.droidance.ui.viewmodel.UsageRange

// ── 数据管理（用量 + 导出 + 清理）───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDataManagementScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val total = uiState.totalPromptTokens + uiState.totalCompletionTokens
    var pendingClear by remember { mutableStateOf<String?>(null) }
    var pendingBackup by remember { mutableStateOf(false) }
    var pendingDiagnostics by remember { mutableStateOf(false) }
    var restoreSource by remember { mutableStateOf<android.net.Uri?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val dataBusy = uiState.debugExporting || uiState.dataClearing != null || uiState.backupOperation != null

    val backupTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = backupPassword
        backupPassword = ""
        if (uri != null) viewModel.exportBackup(uri, password)
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        restoreSource = uri
    }

    LaunchedEffect(uiState.dataActionMessage) {
        val message = uiState.dataActionMessage.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeDataActionMessage()
    }

    if (pendingBackup) {
        BackupPasswordDialog(
            onDismiss = { pendingBackup = false },
            onConfirm = { password ->
                pendingBackup = false
                backupPassword = password
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
                backupTarget.launch("Droidance-backup-$stamp.droidance")
            }
        )
    }

    restoreSource?.let { source ->
        RestorePasswordDialog(
            onDismiss = { restoreSource = null },
            onConfirm = { password ->
                restoreSource = null
                viewModel.restoreBackup(source, password)
            }
        )
    }

    if (pendingDiagnostics) {
        DiagnosticsDialog(
            onDismiss = { pendingDiagnostics = false },
            onConfirm = { includeTaskContent ->
                pendingDiagnostics = false
                viewModel.exportDiagnostics(includeTaskContent)
            }
        )
    }

    uiState.dataResult?.let { result ->
        val contents = result.contents
        val restored = result.operation == com.droidance.ui.viewmodel.BackupOperation.RESTORE
        val counts = arrayOf(contents.conversations, contents.attachments, contents.skills, contents.memoryFiles, contents.settings)
        com.droidance.ui.components.DroidanceDialog(
            onDismissRequest = { if (!restored) viewModel.consumeDataResult() },
            title = stringResource(if (restored) R.string.settings_restore_done_title else R.string.settings_backup_done_title),
            text = stringResource(if (restored) R.string.settings_restore_done_message else R.string.settings_backup_done_message, *counts),
            confirmLabel = stringResource(if (restored) R.string.settings_restart else R.string.common_close),
            onConfirm = { if (restored) viewModel.restartAfterRestore() else viewModel.consumeDataResult() },
            dismissEnabled = !restored
        )
    }

    pendingClear?.let { kind ->
        val isConversations = kind == "conversations"
        com.droidance.ui.components.GlassAlertDialog(
            onDismissRequest = { pendingClear = null },
            title = stringResource(if (isConversations) R.string.settings_clear_conversations_title else R.string.settings_clear_logs_title),
            text = stringResource(if (isConversations) R.string.settings_clear_conversations_message else R.string.settings_clear_logs_message),
            confirmLabel = stringResource(R.string.settings_clear_confirm),
            destructive = true,
            onConfirm = {
                if (isConversations) viewModel.clearAllConversations() else viewModel.clearLogData()
                pendingClear = null
            },
            dismissLabel = stringResource(R.string.common_cancel),
            onDismiss = { pendingClear = null }
        )
    }

    val rangeTitle = stringResource(
        when (uiState.usageRange) {
            UsageRange.SEVEN_DAYS -> R.string.settings_usage_7_days
            UsageRange.THIRTY_DAYS -> R.string.settings_usage_30_days
            UsageRange.ALL -> R.string.settings_usage_all
        }
    )
    val cacheHitRate = if (uiState.cacheReportedCalls == 0L) {
        "—"
    } else if (uiState.cacheReportedInputTokens == 0L) {
        "0%"
    } else {
        String.format(
            java.util.Locale.getDefault(),
            "%.1f%%",
            uiState.cachedInputTokens * 100.0 / uiState.cacheReportedInputTokens
        )
    }

    SubScreenScaffold(
        title = stringResource(R.string.settings_data_controls),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) { DroidanceSnackbar(it) } }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            item { UsageSectionHeader(stringResource(R.string.settings_usage_range)) }
            item {
                SettingsCardGroup {
                    UsageRangeSelector(
                        selected = uiState.usageRange,
                        onSelect = viewModel::loadTokenStats
                    )
                }
            }
            item { UsageSectionHeader(rangeTitle) }
            item {
                SettingsCardGroup {
                    UsageBarChart(
                        daily = uiState.dailyUsage,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                }
            }
            item { UsageSectionHeader(stringResource(R.string.settings_period_usage)) }
            item {
                SettingsCardGroup {
                    UsageValueItem(stringResource(R.string.settings_total_calls), stringResource(R.string.settings_calls, uiState.totalCalls))
                    SettingsInsetDivider()
                    UsageValueItem(stringResource(R.string.settings_prompt_tokens), formatTokens(uiState.totalPromptTokens))
                    SettingsInsetDivider()
                    UsageValueItem(stringResource(R.string.settings_completion_tokens), formatTokens(uiState.totalCompletionTokens))
                    SettingsInsetDivider()
                    UsageValueItem(stringResource(R.string.settings_cache_hit_rate), cacheHitRate)
                    SettingsInsetDivider()
                    UsageValueItem(stringResource(R.string.settings_total_tokens), formatTokens(total))
                }
            }
            if (uiState.modelUsage.isNotEmpty()) {
                item { UsageSectionHeader(stringResource(R.string.settings_model_usage)) }
                item {
                    SettingsCardGroup {
                        uiState.modelUsage.forEachIndexed { index, usage ->
                            if (index > 0) SettingsInsetDivider()
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = usage.model.ifBlank { usage.provider },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (usage.provider.isNotBlank() && usage.provider != usage.model && usage.model.isNotBlank()) {
                                    Text(
                                        text = usage.provider,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.settings_total_tokens),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = formatTokens(usage.total),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.settings_total_calls),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = stringResource(R.string.settings_calls, usage.calls),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { UsageSectionHeader(stringResource(R.string.settings_data_management)) }
            item {
                SettingsCardGroup {
                    SettingsActionItem(
                        title = stringResource(R.string.settings_backup_data),
                        subtitle = stringResource(R.string.settings_backup_data_detail),
                        actionText = stringResource(R.string.settings_export_action),
                        enabled = !dataBusy,
                        loading = uiState.backupOperation == com.droidance.ui.viewmodel.BackupOperation.EXPORT,
                        onClick = { pendingBackup = true }
                    )
                    SettingsInsetDivider()
                    SettingsActionItem(
                        title = stringResource(R.string.settings_restore_data),
                        subtitle = stringResource(R.string.settings_restore_data_detail),
                        actionText = stringResource(R.string.settings_import_action),
                        enabled = !dataBusy,
                        loading = uiState.backupOperation == com.droidance.ui.viewmodel.BackupOperation.RESTORE,
                        onClick = { restorePicker.launch(arrayOf("*/*")) }
                    )
                    SettingsInsetDivider()
                    SettingsActionItem(
                        title = stringResource(R.string.settings_export_diagnostics),
                        subtitle = stringResource(R.string.settings_export_diagnostics_detail),
                        actionText = stringResource(R.string.settings_export_action),
                        enabled = !dataBusy,
                        loading = uiState.debugExporting,
                        onClick = { pendingDiagnostics = true }
                    )
                    SettingsInsetDivider()
                    SettingsActionItem(
                        title = stringResource(R.string.settings_clear_logs),
                        subtitle = stringResource(R.string.settings_clear_logs_detail),
                        actionText = stringResource(R.string.settings_clear_confirm),
                        enabled = !dataBusy,
                        loading = uiState.dataClearing == com.droidance.ui.viewmodel.DataClearTarget.LOGS,
                        onClick = { pendingClear = "logs" }
                    )
                    SettingsInsetDivider()
                    SettingsActionItem(
                        title = stringResource(R.string.settings_clear_conversations),
                        subtitle = stringResource(R.string.settings_clear_conversations_detail),
                        actionText = stringResource(R.string.settings_clear_confirm),
                        enabled = !dataBusy,
                        loading = uiState.dataClearing == com.droidance.ui.viewmodel.DataClearTarget.CONVERSATIONS,
                        onClick = { pendingClear = "conversations" }
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .padding(horizontal = 32.dp)
            .padding(top = 28.dp, bottom = 8.dp)
    )
}

@Composable
private fun UsageValueItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

private const val MIN_BACKUP_PASSWORD = 6

@Composable
private fun BackupPasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val tooShort = password.isNotEmpty() && password.length < MIN_BACKUP_PASSWORD
    val mismatch = confirmation.isNotEmpty() && confirmation != password
    com.droidance.ui.components.DroidanceDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_backup_title),
        text = stringResource(R.string.settings_backup_message),
        confirmLabel = stringResource(R.string.settings_backup_choose_location),
        onConfirm = { onConfirm(password) },
        confirmEnabled = password.length >= MIN_BACKUP_PASSWORD && password == confirmation,
        dismissLabel = stringResource(R.string.common_cancel),
        onDismiss = onDismiss
    ) {
        PasswordField(password, { password = it }, R.string.settings_backup_password,
            R.string.settings_backup_password_short.takeIf { tooShort })
        PasswordField(confirmation, { confirmation = it }, R.string.settings_backup_password_confirm,
            R.string.settings_backup_password_mismatch.takeIf { mismatch })
    }
}

@Composable
private fun RestorePasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    com.droidance.ui.components.DroidanceDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_restore_title),
        text = stringResource(R.string.settings_restore_message),
        confirmLabel = stringResource(R.string.settings_restore_confirm),
        onConfirm = { onConfirm(password) },
        confirmEnabled = password.isNotEmpty(),
        destructive = true,
        dismissLabel = stringResource(R.string.common_cancel),
        onDismiss = onDismiss
    ) {
        PasswordField(password, { password = it }, R.string.settings_backup_password, null)
    }
}

@Composable
private fun DiagnosticsDialog(onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var includeTaskContent by remember { mutableStateOf(false) }
    com.droidance.ui.components.DroidanceDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_diagnostics_title),
        text = stringResource(R.string.settings_diagnostics_message),
        confirmLabel = stringResource(R.string.settings_export_share),
        onConfirm = { onConfirm(includeTaskContent) },
        dismissLabel = stringResource(R.string.common_cancel),
        onDismiss = onDismiss
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(
                value = includeTaskContent,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = { includeTaskContent = it }
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.settings_diagnostics_include_content), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_diagnostics_include_content_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = includeTaskContent,
                onCheckedChange = null,
                modifier = Modifier.scale(SETTINGS_SWITCH_SCALE),
                colors = com.droidance.ui.components.droidanceSwitchColors()
            )
        }
    }
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit,
                          @androidx.annotation.StringRes label: Int, @androidx.annotation.StringRes error: Int?) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it)) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        colors = com.droidance.ui.components.droidanceFieldColors(),
        shape = com.droidance.ui.theme.AppShapes.Control,
        modifier = Modifier.fillMaxWidth()
    )
}
