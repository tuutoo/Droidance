// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidance.R
import com.droidance.agent.AgentRuntime
import com.droidance.core.agent.AgentState
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.device.agent.device.DeviceConnectionMethod
import com.droidance.core.device.agent.device.RootConnectionManager
import com.droidance.core.device.agent.device.ShizukuConnectionManager
import com.droidance.core.device.agent.device.ShizukuConnectionState
import com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge
import com.droidance.core.device.air.AirModePairingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DeviceConnectionSettingsState(
    val selected: DeviceConnectionMethod = DeviceConnectionMethod.WIRELESS,
    val active: DeviceConnectionMethod = DeviceConnectionMethod.WIRELESS,
    val connected: Boolean = false,
    val wirelessConnected: Boolean = false,
    val shizuku: ShizukuConnectionState = ShizukuConnectionState(),
    val taskRunning: Boolean = false,
    val busy: Boolean = false,
    val message: Int? = null
)

@HiltViewModel
class DeviceConnectionSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val root: RootConnectionManager,
    private val air: AirModePairingManager,
    private val shizuku: ShizukuConnectionManager,
    private val bridge: VirtualSpaceSidecarBridge,
    private val agent: AgentRuntime
) : ViewModel() {
    private val _state = MutableStateFlow(DeviceConnectionSettingsState(selected = root.currentMethod, active = root.currentMethod))
    val state = _state.asStateFlow()
    private var pendingWireless = false

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                if (pendingWireless && air.isConnected) finishWirelessSwitch()
                delay(1_000)
            }
        }
    }

    fun select(method: DeviceConnectionMethod) {
        if (taskRunning()) { showTaskRunning(); return }
        if (!_state.value.busy) {
            if (method != DeviceConnectionMethod.WIRELESS) pendingWireless = false
            _state.update { it.copy(selected = method, message = null) }
        }
    }

    fun connectRoot() = connectPrivileged(DeviceConnectionMethod.ROOT)

    fun connectShizuku() {
        if (taskRunning()) { showTaskRunning(); return }
        if (_state.value.busy) return
        val current = shizuku.refresh()
        when {
            !current.installed && !current.running -> openShizukuDownload()
            !current.running || !current.supportedVersion || !current.shellIdentity || current.permissionBlocked -> openShizuku()
            else -> connectPrivileged(DeviceConnectionMethod.SHIZUKU)
        }
    }

    private fun connectPrivileged(method: DeviceConnectionMethod) {
        if (taskRunning()) { showTaskRunning(); return }
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val authorized = when (method) {
                    DeviceConnectionMethod.ROOT -> withContext(Dispatchers.IO) { root.authorize() }
                    DeviceConnectionMethod.SHIZUKU -> shizuku.requestAuthorization()
                    DeviceConnectionMethod.WIRELESS -> false
                }
                if (!authorized) {
                    _state.update { it.copy(message = if (method == DeviceConnectionMethod.ROOT)
                        R.string.connection_root_failed else R.string.connection_shizuku_denied) }
                    return@launch
                }
                if (taskRunning()) { showTaskRunning(); return@launch }
                val ready = withContext(Dispatchers.IO) {
                    // Verify the chosen bootstrap even when an existing resident Binder is reusable.
                    if (method == DeviceConnectionMethod.SHIZUKU && shizuku.shell("id -u", 5_000).trim() != "2000") {
                        false
                    } else {
                        root.beginCandidate(method)
                        try {
                            val metrics = context.resources.displayMetrics
                            bridge.ensureResidentChannel(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1),
                                metrics.densityDpi.coerceAtLeast(1), RuntimeMode.STANDARD)
                        } finally { root.endCandidate() }
                    }
                }
                if (ready && !taskRunning()) {
                    root.select(method)
                    withContext(Dispatchers.IO) {
                        air.disconnect()
                        if (method != DeviceConnectionMethod.SHIZUKU) shizuku.releaseUserService()
                    }
                    pendingWireless = false
                } else if (taskRunning()) showTaskRunning()
                else _state.update { it.copy(message = if (method == DeviceConnectionMethod.ROOT)
                    R.string.connection_root_start_failed else R.string.connection_shizuku_start_failed) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                android.util.Log.w("DeviceConnection", "Connection failed: ${error.javaClass.simpleName}")
                _state.update { it.copy(message = if (method == DeviceConnectionMethod.ROOT)
                    R.string.connection_root_start_failed else R.string.connection_shizuku_start_failed) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
            refresh()
        }
    }

    fun openShizuku() {
        if (!shizuku.openManager()) _state.update { it.copy(message = R.string.connection_settings_unavailable) }
    }

    private fun openShizukuDownload() {
        if (!shizuku.openDownload()) _state.update { it.copy(message = R.string.connection_settings_unavailable) }
    }

    fun connectWireless(openPairing: () -> Unit) {
        if (taskRunning()) { showTaskRunning(); return }
        if (_state.value.busy) return
        if (root.currentMethod == DeviceConnectionMethod.WIRELESS && _state.value.connected) {
            openPairing()
            return
        }
        pendingWireless = true
        if (air.isConnected) viewModelScope.launch { finishWirelessSwitch() } else openPairing()
    }

    fun onReturnFromExternal() {
        viewModelScope.launch { refresh() }
        if (!pendingWireless) return
        if (air.isConnected) {
            viewModelScope.launch { finishWirelessSwitch() }
            return
        }
        pendingWireless = false
        if (root.currentMethod != DeviceConnectionMethod.WIRELESS) {
            viewModelScope.launch { withContext(Dispatchers.IO) { air.disconnect() }; refresh() }
        }
    }

    private suspend fun finishWirelessSwitch() {
        if (!pendingWireless || _state.value.busy) return
        if (taskRunning()) { pendingWireless = false; showTaskRunning(); return }
        _state.update { it.copy(busy = true) }
        try {
            val shellReady = withContext(Dispatchers.IO) { air.shell("id -u", 2_000L).trim() == "2000" }
            if (shellReady && !taskRunning()) {
                root.select(DeviceConnectionMethod.WIRELESS)
                withContext(Dispatchers.IO) { shizuku.releaseUserService() }
                pendingWireless = false
            } else if (taskRunning()) {
                pendingWireless = false
                showTaskRunning()
            }
        } finally { _state.update { it.copy(busy = false) } }
        refresh()
    }

    private suspend fun refresh() {
        val active = root.currentMethod
        val wireless = air.isConnected
        val (connected, shizukuState) = withContext(Dispatchers.IO) {
            val connected = when (active) {
                DeviceConnectionMethod.ROOT, DeviceConnectionMethod.SHIZUKU -> bridge.isResidentBinderAlive()
                DeviceConnectionMethod.WIRELESS -> wireless ||
                    (air.isPreviouslyPaired && !air.isPairingRequired && bridge.isResidentBinderAlive())
            }
            connected to shizuku.refresh()
        }
        _state.update { it.copy(active = active, connected = connected, wirelessConnected = wireless,
            shizuku = shizukuState, taskRunning = taskRunning()) }
    }

    private fun taskRunning(): Boolean = agent.state.value !in setOf(AgentState.IDLE, AgentState.ERROR)
    private fun showTaskRunning() { _state.update { it.copy(message = R.string.connection_switch_task_running) } }
}
