// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.connection

import com.droidance.core.device.air.AirModePairingManager.PairingState

internal enum class DeviceConnectionAction { PAIR, DEVELOPER_SETTINGS, WIRELESS_SETTINGS, WIFI_SETTINGS, RETRY, SHIZUKU_MANAGER, SHIZUKU_DOWNLOAD, WAIT }

internal enum class DeviceConnectionStatus(
    val action: DeviceConnectionAction = DeviceConnectionAction.WAIT,
    val needsAttention: Boolean = false
) {
    CHECKING,
    READY,
    RECONNECTING,
    PAIRING(DeviceConnectionAction.PAIR),
    UNPAIRED(DeviceConnectionAction.PAIR, true),
    ROOT_UNAVAILABLE(DeviceConnectionAction.PAIR, true),
    SHIZUKU_NOT_INSTALLED(DeviceConnectionAction.SHIZUKU_DOWNLOAD, true),
    SHIZUKU_STOPPED(DeviceConnectionAction.SHIZUKU_MANAGER, true),
    SHIZUKU_AUTH_REQUIRED(DeviceConnectionAction.PAIR, true),
    SHIZUKU_UPDATE_REQUIRED(DeviceConnectionAction.SHIZUKU_MANAGER, true),
    SHIZUKU_SHELL_REQUIRED(DeviceConnectionAction.PAIR, true),
    SHIZUKU_UNAVAILABLE(DeviceConnectionAction.PAIR, true),
    PAIRING_REQUIRED(DeviceConnectionAction.PAIR, true),
    WIFI_UNAVAILABLE(DeviceConnectionAction.WIFI_SETTINGS, true),
    USB_DISABLED(DeviceConnectionAction.DEVELOPER_SETTINGS, true),
    WIRELESS_DISABLED(DeviceConnectionAction.WIRELESS_SETTINGS, true),
    LOCAL_UNAVAILABLE(DeviceConnectionAction.RETRY, true),
    UNAVAILABLE(DeviceConnectionAction.RETRY, true)
}

internal fun shizukuConnectionStatus(
    service: com.droidance.core.device.agent.device.ShizukuConnectionState,
    residentAvailable: Boolean
): DeviceConnectionStatus = when {
    residentAvailable -> DeviceConnectionStatus.READY
    !service.installed && !service.running -> DeviceConnectionStatus.SHIZUKU_NOT_INSTALLED
    !service.running -> DeviceConnectionStatus.SHIZUKU_STOPPED
    !service.supportedVersion -> DeviceConnectionStatus.SHIZUKU_UPDATE_REQUIRED
    !service.shellIdentity -> DeviceConnectionStatus.SHIZUKU_SHELL_REQUIRED
    !service.authorized -> DeviceConnectionStatus.SHIZUKU_AUTH_REQUIRED
    else -> DeviceConnectionStatus.SHIZUKU_UNAVAILABLE
}

internal data class DeviceConnectionSnapshot(
    val transportAvailable: Boolean,
    val pairingState: PairingState,
    val previouslyPaired: Boolean,
    val pairingRequired: Boolean,
    val wifiAvailable: Boolean?,
    val wirelessDebuggingEnabled: Boolean?,
    val connectionFailed: Boolean,
    val persistenceEnabled: Boolean = false,
    val localPortAvailable: Boolean = false,
    val usbDebuggingEnabled: Boolean? = null
) {
    fun status(): DeviceConnectionStatus = when {
        // A resident Binder can remain usable after Wi-Fi or the live ADB shell disappears.
        transportAvailable -> DeviceConnectionStatus.READY
        pairingState in setOf(PairingState.DISCOVERING, PairingState.AWAITING_CODE, PairingState.PAIRING) ->
            DeviceConnectionStatus.PAIRING
        pairingRequired -> DeviceConnectionStatus.PAIRING_REQUIRED
        !previouslyPaired -> DeviceConnectionStatus.UNPAIRED
        persistenceEnabled && usbDebuggingEnabled == false -> DeviceConnectionStatus.USB_DISABLED
        localPortAvailable && pairingState == PairingState.CONNECTING && !connectionFailed -> DeviceConnectionStatus.RECONNECTING
        localPortAvailable -> DeviceConnectionStatus.LOCAL_UNAVAILABLE
        wifiAvailable == null -> DeviceConnectionStatus.CHECKING
        wifiAvailable == false -> DeviceConnectionStatus.WIFI_UNAVAILABLE
        wirelessDebuggingEnabled == false -> DeviceConnectionStatus.WIRELESS_DISABLED
        connectionFailed -> DeviceConnectionStatus.UNAVAILABLE
        pairingState == PairingState.CONNECTING -> DeviceConnectionStatus.RECONNECTING
        else -> DeviceConnectionStatus.UNAVAILABLE
    }
}

/** One prompt per outage, shared across navigation by the app-level ViewModel. */
internal data class DeviceConnectionUiState(
    val status: DeviceConnectionStatus = DeviceConnectionStatus.CHECKING,
    val dialogVisible: Boolean = false,
    val prompted: Boolean = false,
    private val pendingStatus: DeviceConnectionStatus? = null,
    private val pendingSinceMs: Long = 0L
) {
    companion object {
        const val FAILURE_CONFIRMATION_MS = 5_000L
    }

    val noticeVisible: Boolean
        get() = status != DeviceConnectionStatus.CHECKING && status != DeviceConnectionStatus.READY

    /** Inline automatic reconnects need no recovery message unless the user was prompted. */
    fun shouldNotifyRecovery(next: DeviceConnectionStatus): Boolean =
        next == DeviceConnectionStatus.READY && noticeVisible && prompted

    /** Only consecutive foreground observations can confirm a failure. Recovery is immediate. */
    fun observe(next: DeviceConnectionStatus, nowMs: Long): DeviceConnectionUiState {
        if (next == DeviceConnectionStatus.READY) return DeviceConnectionUiState(next)
        val sinceMs = if (pendingStatus == next) pendingSinceMs else nowMs
        if (next.needsAttention && nowMs - sinceMs < FAILURE_CONFIRMATION_MS) {
            return copy(
                status = DeviceConnectionStatus.CHECKING,
                dialogVisible = false,
                pendingStatus = next,
                pendingSinceMs = sinceMs
            )
        }
        return copy(
            status = next,
            dialogVisible = when {
                next == DeviceConnectionStatus.CHECKING -> false
                next != status && !next.needsAttention -> false
                else -> dialogVisible || (next.needsAttention && !prompted)
            },
            prompted = prompted || next.needsAttention,
            pendingStatus = next.takeIf { it.needsAttention },
            pendingSinceMs = if (next.needsAttention) sinceMs else 0L
        )
    }

    fun checking(): DeviceConnectionUiState = copy(
        status = DeviceConnectionStatus.CHECKING,
        dialogVisible = false,
        pendingStatus = null,
        pendingSinceMs = 0L
    )

    fun dismiss(): DeviceConnectionUiState = copy(dialogVisible = false, prompted = true)

    fun showDetails(): DeviceConnectionUiState =
        if (noticeVisible) copy(dialogVisible = true, prompted = true) else this
}
