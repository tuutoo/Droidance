// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.connection

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidance.R
import com.droidance.agent.RuntimeCapabilityRegistry
import com.droidance.core.device.air.AirModePairingManager
import com.droidance.core.device.air.AirModePairingManager.PairingState
import com.droidance.core.device.agent.device.DeviceConnectionMethod
import com.droidance.core.device.agent.device.RootConnectionManager
import com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge
import com.droidance.settings.SystemSettingsNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
internal class DeviceConnectionViewModel @Inject constructor(
    private val air: AirModePairingManager,
    private val capabilities: RuntimeCapabilityRegistry,
    private val root: RootConnectionManager,
    private val shizuku: com.droidance.core.device.agent.device.ShizukuConnectionManager,
    private val bridge: VirtualSpaceSidecarBridge,
    private val settings: SystemSettingsNavigator
) : ViewModel() {
    private val _state = MutableStateFlow(DeviceConnectionUiState())
    val state = _state.asStateFlow()
    private val messages = Channel<Int>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()

    /** The caller runs this only while the app is resumed; no background UI polling. */
    suspend fun monitor() {
        _state.update { it.checking() }
        try {
            withContext(Dispatchers.IO) { runCatching { air.ensureConnectedIfPaired() } }
            while (currentCoroutineContext().isActive) {
                refresh()
                delay(1_000)
            }
        } finally {
            // Time spent in another app cannot establish a continuous connection failure.
            _state.update { it.checking() }
        }
    }

    private suspend fun refresh() {
        val next = withContext(Dispatchers.IO) {
            runCatching {
                val device = capabilities.refresh(force = true)
                if (root.currentMethod == DeviceConnectionMethod.SHIZUKU) {
                    return@runCatching shizukuConnectionStatus(shizuku.refresh(), bridge.isResidentBinderAlive())
                }
                if (root.currentMethod == DeviceConnectionMethod.ROOT) {
                    return@runCatching if (bridge.isResidentBinderAlive()) DeviceConnectionStatus.READY
                    else DeviceConnectionStatus.ROOT_UNAVAILABLE
                }
                val shell = air.shellHealthSnapshot()
                DeviceConnectionSnapshot(
                    transportAvailable = device.adbBridgeAvailable || device.virtualSpaceBridgeAvailable || shell.ready,
                    pairingState = shell.pairingState,
                    previouslyPaired = shell.previouslyPaired,
                    pairingRequired = air.isPairingRequired,
                    wifiAvailable = shell.wifiTransportAvailable,
                    wirelessDebuggingEnabled = shell.wirelessDebuggingEnabled,
                    connectionFailed = air.failure.value != null,
                    persistenceEnabled = shell.persistenceEnabled,
                    localPortAvailable = air.hasLocalAdbPort,
                    usbDebuggingEnabled = shell.usbDebuggingEnabled
                ).status()
            }.getOrDefault(DeviceConnectionStatus.CHECKING)
        }
        val previous = _state.value
        _state.update { it.observe(next, SystemClock.elapsedRealtime()) }
        if (previous.shouldNotifyRecovery(next)) {
            messages.trySend(R.string.connection_restored)
        }
    }

    fun dismiss() { _state.update { it.dismiss() } }
    fun showDetails() { _state.update { it.showDetails() } }

    fun resolve(onOpenPairing: () -> Unit) {
        val action = _state.value.status.action
        dismiss()
        when (action) {
            DeviceConnectionAction.PAIR -> onOpenPairing()
            DeviceConnectionAction.SHIZUKU_MANAGER -> openSettings { check(shizuku.openManager()) }
            DeviceConnectionAction.SHIZUKU_DOWNLOAD -> openSettings { check(shizuku.openDownload()) }
            DeviceConnectionAction.DEVELOPER_SETTINGS -> openSettings(settings::openDeveloperOptions)
            DeviceConnectionAction.WIRELESS_SETTINGS -> openSettings(settings::openWirelessDebuggingSettings)
            DeviceConnectionAction.WIFI_SETTINGS -> openSettings(settings::openWifiSettings)
            DeviceConnectionAction.RETRY -> viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    if (air.state.value == PairingState.CONNECTING) air.disconnect()
                    air.reconnect()
                }
                refresh()
            }
            DeviceConnectionAction.WAIT -> Unit
        }
    }

    private fun openSettings(open: () -> Unit) {
        if (runCatching(open).isFailure) messages.trySend(R.string.connection_settings_unavailable)
    }
}
