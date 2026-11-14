// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.connection

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droidance.R
import com.droidance.ui.components.DroidanceDialog
import com.droidance.ui.icons.DroidanceIcons

@Composable
internal fun DeviceConnectionNotice(state: DeviceConnectionUiState, onClick: () -> Unit) {
    if (!state.noticeVisible) return
    val presentation = presentation(state.status)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(presentation.icon, contentDescription = null,
            modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(presentation.title), modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        TextButton(onClick = onClick) {
            Text(stringResource(if (state.status.needsAttention) R.string.connection_resolve else R.string.connection_view),
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun DeviceConnectionDialog(
    status: DeviceConnectionStatus,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val presentation = presentation(status)
    DroidanceDialog(
        onDismissRequest = onDismiss,
        title = stringResource(presentation.title),
        text = stringResource(presentation.description),
        confirmLabel = stringResource(presentation.confirm),
        onConfirm = onConfirm,
        dismissLabel = stringResource(R.string.connection_later)
    )
}

private data class ConnectionPresentation(
    @StringRes val title: Int,
    @StringRes val description: Int,
    @StringRes val confirm: Int,
    val icon: ImageVector
)

private fun presentation(status: DeviceConnectionStatus): ConnectionPresentation = when (status) {
    DeviceConnectionStatus.SHIZUKU_NOT_INSTALLED -> ConnectionPresentation(
        R.string.connection_unpaired_title, R.string.connection_shizuku_install_hint,
        R.string.connection_shizuku_install, DroidanceIcons.Link)
    DeviceConnectionStatus.SHIZUKU_STOPPED -> ConnectionPresentation(
        R.string.connection_shizuku_stopped_title, R.string.connection_shizuku_start_hint,
        R.string.connection_shizuku_open, DroidanceIcons.Link)
    DeviceConnectionStatus.SHIZUKU_AUTH_REQUIRED -> ConnectionPresentation(
        R.string.connection_shizuku_auth_title, R.string.connection_shizuku_denied,
        R.string.air_pairing_title, DroidanceIcons.Link)
    DeviceConnectionStatus.SHIZUKU_UPDATE_REQUIRED -> ConnectionPresentation(
        R.string.connection_shizuku_unsupported_title, R.string.connection_shizuku_update_required,
        R.string.connection_shizuku_open, DroidanceIcons.Link)
    DeviceConnectionStatus.SHIZUKU_SHELL_REQUIRED -> ConnectionPresentation(
        R.string.connection_shizuku_unsupported_title, R.string.connection_shizuku_shell_required,
        R.string.air_pairing_title, DroidanceIcons.Link)
    DeviceConnectionStatus.SHIZUKU_UNAVAILABLE -> ConnectionPresentation(
        R.string.connection_shizuku_unavailable_title, R.string.connection_shizuku_start_failed,
        R.string.air_pairing_title, DroidanceIcons.Link)
    DeviceConnectionStatus.ROOT_UNAVAILABLE -> ConnectionPresentation(
        R.string.connection_root_unavailable_title, R.string.connection_root_unavailable_description,
        R.string.air_pairing_title, DroidanceIcons.Link)
    DeviceConnectionStatus.UNPAIRED -> ConnectionPresentation(
        R.string.connection_unpaired_title, R.string.connection_unpaired_description,
        R.string.air_pairing_start, DroidanceIcons.Link)
    DeviceConnectionStatus.PAIRING_REQUIRED -> ConnectionPresentation(
        R.string.connection_pairing_required_title, R.string.connection_pairing_required_description,
        R.string.settings_air_repair, DroidanceIcons.Link)
    DeviceConnectionStatus.WIFI_UNAVAILABLE -> ConnectionPresentation(
        R.string.connection_wifi_title, R.string.connection_wifi_description,
        R.string.connection_open_wifi, DroidanceIcons.WifiOff)
    DeviceConnectionStatus.LOCAL_UNAVAILABLE -> ConnectionPresentation(
        R.string.connection_local_unavailable_title,
        com.droidance.core.device.R.string.settings_air_local_adb_connection_failed,
        R.string.connection_retry, DroidanceIcons.Refresh)
    DeviceConnectionStatus.USB_DISABLED -> ConnectionPresentation(
        R.string.air_adb_persistence_usb_title, R.string.air_adb_persistence_usb_required,
        R.string.connection_open_settings, DroidanceIcons.Link)
    DeviceConnectionStatus.WIRELESS_DISABLED -> ConnectionPresentation(
        R.string.connection_wireless_disabled_title, R.string.connection_wireless_disabled_description,
        R.string.connection_open_settings, DroidanceIcons.WifiOff)
    DeviceConnectionStatus.RECONNECTING -> ConnectionPresentation(
        R.string.connection_reconnecting_title, R.string.settings_air_reconnecting_hint,
        R.string.connection_keep_waiting, DroidanceIcons.Refresh)
    DeviceConnectionStatus.PAIRING -> ConnectionPresentation(
        R.string.connection_pairing_title, R.string.connection_pairing_description,
        R.string.connection_view_pairing, DroidanceIcons.Link)
    else -> ConnectionPresentation(
        R.string.connection_unavailable_title, R.string.connection_unavailable_description,
        R.string.connection_retry, DroidanceIcons.WifiOff)
}
