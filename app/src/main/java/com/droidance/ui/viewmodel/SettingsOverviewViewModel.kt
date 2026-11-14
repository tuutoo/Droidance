// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.device.air.AirModePairingManager
import com.droidance.core.device.agent.device.DeviceConnectionMethod
import com.droidance.core.device.agent.device.RootConnectionManager
import com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge
import com.droidance.config.AppLanguage
import com.droidance.config.LanguageManager
import com.droidance.settings.GeneralSettingsRepository
import com.droidance.settings.ProviderConfigManager
import com.droidance.settings.SystemSettingsNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class SettingsOverview(
    val model: String = "",
    val executionSpace: com.droidance.core.agent.device.ExecutionSpace = com.droidance.core.agent.device.ExecutionSpace.VIRTUAL_SPACE,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val runtimeMode: RuntimeMode? = null,
    val hasPendingPermissions: Boolean = false,
    val connectionMethod: DeviceConnectionMethod = DeviceConnectionMethod.WIRELESS,
    val deviceConnected: Boolean = false
)

/** Settings-home summaries observe the existing device channel without starting a connection. */
@HiltViewModel
class SettingsOverviewViewModel @Inject constructor(
    private val providers: ProviderConfigManager,
    private val capabilities: com.droidance.agent.RuntimeCapabilityRegistry,
    private val languages: LanguageManager,
    private val generalSettings: GeneralSettingsRepository,
    private val systemSettingsNavigator: SystemSettingsNavigator,
    private val air: AirModePairingManager,
    private val connection: RootConnectionManager,
    private val bridge: VirtualSpaceSidecarBridge
) : ViewModel() {
    private val _overview = MutableStateFlow(SettingsOverview())
    val overview = _overview.asStateFlow()
    val airState = air.state
    val themeMode = generalSettings.themeMode

    init {
        refresh()
        viewModelScope.launch {
            generalSettings.runtimeMode.collect { mode ->
                _overview.value = _overview.value.copy(runtimeMode = mode)
            }
        }
    }

    fun refresh() {
        _overview.value = SettingsOverview(
            model = providers.getDefaultConfig()?.defaultModel.orEmpty(),
            executionSpace = capabilities.requestedSpace(),
            language = languages.getPreference(),
            runtimeMode = generalSettings.runtimeMode(),
            hasPendingPermissions = systemSettingsNavigator.hasPendingDangerousPermissions(),
            connectionMethod = connection.currentMethod,
            deviceConnected = if (connection.currentMethod == DeviceConnectionMethod.WIRELESS)
                air.isConnected || (air.isPreviouslyPaired && !air.isPairingRequired && bridge.isResidentBinderAlive())
                else bridge.isResidentBinderAlive()
        )
    }
}
