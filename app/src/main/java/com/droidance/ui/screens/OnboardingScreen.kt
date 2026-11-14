// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.theme.AppIconSize
import androidx.compose.foundation.shape.CircleShape
import com.droidance.ui.theme.AppShapes
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.droidance.ui.mascot.AnimatedMascot
import com.droidance.ui.mascot.MascotFraming
import com.droidance.R
import com.droidance.ui.theme.DroidancePrimary
import com.droidance.ui.viewmodel.OnboardingViewModel
import com.droidance.ui.viewmodel.ScanResult
import com.droidance.core.device.agent.device.DeviceConnectionMethod

/**
 * 首次启动引导页：品牌 Hero + 一份按依赖排序的极简清单。
 * Notification access is requested by the pairing flow when required.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onNavigateToProviders: () -> Unit = {},
    onNavigateToAirPairing: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scanResult by viewModel.scanResult.collectAsStateWithLifecycle()
    val isComplete by viewModel.isComplete.collectAsStateWithLifecycle()

    LaunchedEffect(isComplete) {
        if (isComplete) onComplete()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (!isScanning && scanResult != null) {
                val isCoreReady = (scanResult?.providerReady == true) && (
                    (scanResult?.deviceReady == true)
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    color = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(
                            onClick = {
                                when {
                                    scanResult?.providerReady != true -> onNavigateToProviders()
                                    !isCoreReady -> onNavigateToAirPairing()
                                    else -> viewModel.completeOnboarding()
                                }
                            },
                            modifier = Modifier
                                .widthIn(max = 560.dp)
                                .fillMaxWidth()
                                .heightIn(min = 54.dp),
                            colors = ButtonDefaults.buttonColors(),
                            shape = AppShapes.Large
                        ) {
                            Text(
                                stringResource(when {
                                    scanResult?.providerReady != true -> R.string.onboarding_setup_model
                                    !isCoreReady -> R.string.air_pairing_title
                                    else -> R.string.onboarding_start
                                }),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(16.dp))
                OnboardingHero()
                Spacer(Modifier.height(28.dp))

                if (isScanning || scanResult == null) {
                    OnboardingLoading()
                }

                AnimatedVisibility(
                    visible = !isScanning && scanResult != null,
                    enter = slideInVertically(initialOffsetY = { it / 6 })
                ) {
                    scanResult?.let { scan ->
                        Column(Modifier.fillMaxWidth()) {
                            PermissionChecklist(
                                scan = scan,
                                onNavigateToProviders = onNavigateToProviders,
                                onNavigateToAirPairing = onNavigateToAirPairing,
                                onRefresh = viewModel::refreshPermissionStatus
                            )
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun OnboardingHero() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The full-body artwork has transparent side margins inside its square canvas.
        Box(
            modifier = Modifier.width(76.dp).height(104.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedMascot(
                modifier = Modifier.requiredSize(104.dp),
                framing = MascotFraming.FULL
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 360.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.titleMedium,
                fontSize = 18.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
private fun OnboardingLoading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.onboarding_scanning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.58f)
        )
    }
}

// ====== 权限清单（必需 / 可选） ======

@Composable
private fun PermissionChecklist(
    scan: ScanResult,
    onNavigateToProviders: () -> Unit,
    onNavigateToAirPairing: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }

    // 从系统设置页返回时刷新权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
                onRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasNotifAccess by remember(refreshKey) { derivedStateOf { isNotifListenerEnabled(context) } }
    val hasBatteryExemption by remember(refreshKey) { derivedStateOf { isBatteryOptimizationIgnored(context) } }

    val runtimePerms = remember { buildRuntimePermissions() }
    val grantedCount by remember(refreshKey) {
        derivedStateOf {
            runtimePerms.count {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
    val allRuntimeGranted = grantedCount == runtimePerms.size

    var permissionsRequested by rememberSaveable { mutableStateOf(false) }
    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsRequested = true; refreshKey++ }

    // ── 必需 ──
    SectionLabel(stringResource(R.string.onboarding_setup_title))
    SettingsCardGroup(modifier = Modifier.fillMaxWidth()) {
        PermissionRow(
            icon = DroidanceIcons.Network,
            name = stringResource(R.string.onboarding_setup_model),
            desc = stringResource(
                if (scan.providerReady) R.string.onboarding_setup_model_ready
                else R.string.onboarding_setup_model_desc
            ),
            granted = scan.providerReady,
            onAction = onNavigateToProviders
        )
        SettingsInsetDivider(startIndent = 62.dp, endIndent = 14.dp)

            PermissionRow(
                icon = DroidanceIcons.Link,
                name = stringResource(R.string.onboarding_setup_air),
                desc = stringResource(
                    if (scan.deviceReady && scan.connectionMethod == DeviceConnectionMethod.SHIZUKU) R.string.onboarding_setup_device_shizuku_ready
                    else if (scan.deviceReady && scan.connectionMethod == DeviceConnectionMethod.ROOT) R.string.onboarding_setup_device_root_ready
                    else if (scan.deviceReady) R.string.onboarding_setup_air_ready
                    else R.string.onboarding_setup_device_desc
                ),
                granted = scan.deviceReady,
                onAction = onNavigateToAirPairing
            )

    }
    Spacer(Modifier.height(20.dp))
    SectionLabel(stringResource(R.string.onboarding_background_recommended))
    SettingsCardGroup(modifier = Modifier.fillMaxWidth()) {
        PermissionRow(
            icon = DroidanceIcons.Battery,
            name = stringResource(R.string.onboarding_setup_battery),
            desc = stringResource(
                if (hasBatteryExemption) R.string.onboarding_setup_battery_ready
                else R.string.onboarding_setup_battery_desc
            ),
            granted = hasBatteryExemption,
            onAction = { openBackgroundExecutionSettings(context) }
        )
    }

    Spacer(Modifier.height(22.dp))

    // ── 可选 ──
    SectionLabel(stringResource(R.string.onboarding_permissions))
    Text(
        stringResource(R.string.onboarding_optional_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)
    )
    SettingsCardGroup(modifier = Modifier.fillMaxWidth()) {
        PermissionRow(
            icon = DroidanceIcons.Eye,
            name = stringResource(R.string.onboarding_notification_access),
            desc = stringResource(R.string.onboarding_notification_access_desc),
            granted = hasNotifAccess,
            onAction = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        )
        SettingsInsetDivider(startIndent = 62.dp, endIndent = 14.dp)
        PermissionRow(
            icon = DroidanceIcons.Sliders,
            name = stringResource(R.string.onboarding_other_permissions),
            desc = "$grantedCount/${runtimePerms.size} · ${stringResource(R.string.onboarding_other_permissions_desc)}",
            granted = allRuntimeGranted,
            actionLabel = stringResource(if (permissionsRequested) R.string.chat_permission_grant_settings_action else R.string.onboarding_grant_all),
            onAction = {
                if (permissionsRequested) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                else runtimeLauncher.launch(runtimePerms.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray())
            }
        )
    }
    if (permissionsRequested && !allRuntimeGranted) Text(stringResource(R.string.onboarding_permissions_settings_hint),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 8.dp)
    )
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    name: String,
    granted: Boolean,
    desc: String? = null,
    actionLabel: String = stringResource(R.string.onboarding_enable),
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = AppShapes.Control,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(AppIconSize.Leading)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!desc.isNullOrBlank()) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        if (granted) {
            Surface(
                modifier = Modifier.size(28.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        DroidanceIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        } else {
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = DroidancePrimary)
            }
        }
    }
}

// ====== 权限检查辅助 ======

private fun isBatteryOptimizationIgnored(context: Context): Boolean =
    context.getSystemService(android.os.PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)

private fun openBackgroundExecutionSettings(context: Context) {
    context.startActivity(
        Intent(
            if (isBatteryOptimizationIgnored(context)) Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            else Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
    )
}

private fun isNotifListenerEnabled(context: Context): Boolean = try {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    nm.isNotificationListenerAccessGranted(
        android.content.ComponentName(context, com.droidance.automation.NotificationWatcher::class.java))
} catch (_: Exception) { false }

private fun buildRuntimePermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        add(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.WRITE_CONTACTS)
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.RECEIVE_SMS)
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.WRITE_CALL_LOG)
    add(Manifest.permission.CALL_PHONE)
    add(Manifest.permission.READ_CALENDAR)
    add(Manifest.permission.WRITE_CALENDAR)
}
