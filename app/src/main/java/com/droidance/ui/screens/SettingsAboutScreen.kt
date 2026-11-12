// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidance.BuildConfig
import com.droidance.R
import com.droidance.ui.components.DroidanceSnackbar
import kotlinx.coroutines.launch
import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.mascot.MascotMood
import com.droidance.ui.mascot.MascotStage
import com.droidance.ui.viewmodel.UpdateViewModel
import com.droidance.update.model.UpdateCheckError
import com.droidance.update.model.UpdateCheckState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAboutScreen(
    onBack: () -> Unit,
    updateViewModel: UpdateViewModel = hiltViewModel()
) {
    var showLicenses by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val openFailed = stringResource(R.string.ui_open_failed)
    val context = LocalContext.current
    val repositoryUrl = stringResource(R.string.settings_repository_url)
    val dateFormat = remember(context.resources.configuration.locales[0]) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, context.resources.configuration.locales[0])
    }

    val checkState by updateViewModel.checkState.collectAsStateWithLifecycle()
    val availableRelease by updateViewModel.availableRelease.collectAsStateWithLifecycle()
    val autoCheckUpdate by updateViewModel.autoCheckUpdate.collectAsStateWithLifecycle()

    SubScreenScaffold(title = stringResource(R.string.settings_about), onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) { DroidanceSnackbar(it) } }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 24.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MascotStage(
                        modifier = Modifier.size(104.dp),
                        mood = MascotMood.DANCING
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Droidance",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_about_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = stringResource(
                                R.string.settings_about_version,
                                BuildConfig.VERSION_NAME
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            item { SettingsSectionHeader(stringResource(R.string.settings_app_updates)) }
            item {
                SettingsCardGroup {
                    val updateSubtitle = when (val state = checkState) {
                        is UpdateCheckState.Checking -> stringResource(R.string.update_status_checking)
                        is UpdateCheckState.UpToDate -> stringResource(R.string.update_status_latest)
                        is UpdateCheckState.UpdateAvailable -> listOfNotNull(
                            stringResource(R.string.update_status_available, state.release.versionName),
                            if (state.fromCache) stringResource(R.string.update_status_cached,
                                dateFormat.format(Date(state.checkedAt))) else null
                        ).joinToString("\n")
                        is UpdateCheckState.CheckFailed -> listOfNotNull(
                            when (state.reason) {
                                UpdateCheckError.RATE_LIMITED -> stringResource(R.string.update_status_error_rate_limited)
                                UpdateCheckError.ACCESS_DENIED -> stringResource(R.string.update_status_error_access_denied)
                                UpdateCheckError.NETWORK_ERROR -> stringResource(R.string.update_status_error_network)
                                else -> stringResource(R.string.update_status_error_generic)
                            },
                            if (state.retryAt > System.currentTimeMillis()) stringResource(R.string.update_status_retry_after,
                                dateFormat.format(Date(state.retryAt))) else null,
                            if (availableRelease != null && state.lastSuccessfulCheckAt > 0)
                                stringResource(R.string.update_status_cached,
                                    dateFormat.format(Date(state.lastSuccessfulCheckAt))) else null
                        ).joinToString("\n")
                        UpdateCheckState.Disabled -> stringResource(R.string.update_status_disabled)
                        UpdateCheckState.Idle -> null
                    }
                    val updateActionText = if (availableRelease != null) {
                        stringResource(R.string.update_action_view)
                    } else {
                        stringResource(R.string.update_action_check)
                    }
                    SettingsActionItem(
                        title = stringResource(R.string.update_check),
                        subtitle = updateSubtitle,
                        actionText = updateActionText,
                        loading = checkState is UpdateCheckState.Checking,
                        enabled = checkState !is UpdateCheckState.Checking && com.droidance.BuildConfig.UPDATE_REPOSITORY.isNotBlank(),
                        onClick = {
                            if (availableRelease != null) {
                                updateViewModel.showDialog()
                            } else {
                                updateViewModel.checkForUpdates()
                            }
                        }
                    )
                    SettingsInsetDivider()
                    SettingsSwitchItem(
                        title = stringResource(R.string.update_auto_check_title),
                        subtitle = stringResource(R.string.update_auto_check_desc),
                        checked = autoCheckUpdate,
                        onToggle = { updateViewModel.setAutoCheckUpdate(!autoCheckUpdate) }
                    )
                }
            }
            item {
                SettingsCardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.settings_open_link),
                                onClick = { runCatching { uriHandler.openUri(repositoryUrl) } }
                            )
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.settings_github_repository),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = DroidanceIcons.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = repositoryUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            item {
                SettingsCardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp)
                ) {
                    SettingsActionItem(
                        title = stringResource(R.string.settings_open_source_licenses),
                        actionText = stringResource(R.string.settings_licenses_view),
                        onClick = { showLicenses = true }
                    )
                }
            }
        }
    }

    if (showLicenses) ProjectLicensesDialog { showLicenses = false }
}
