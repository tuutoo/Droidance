// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.attempt

import com.droidance.core.agent.attempt.*

import com.droidance.agent.attempt.*

import com.droidance.agent.RuntimeCapabilityRegistry
import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*
import com.droidance.agent.device.*
import com.droidance.core.device.air.AirModePairingManager
import javax.inject.Inject
import javax.inject.Singleton

/** Only Standard virtual-space sessions can be prepared or restored for new execution. */
@Singleton
class AndroidExecutionAttemptBackendProbe @Inject constructor(
    private val capabilityRegistry: RuntimeCapabilityRegistry,
    private val virtualSpaceReadiness: VirtualSpaceReadiness,
    private val virtualSpaceManager: VirtualSpaceManager,
    private val virtualSpaceSidecarBridge: VirtualSpaceSidecarBridge,
    private val airModePairingManager: AirModePairingManager,
    private val rootConnection: RootConnectionManager,
    private val ruleConfigManager: com.droidance.config.RuleConfigManager
) : ExecutionAttemptBackendProbe {
    /**
     * Readiness can lag briefly, for example right after a stale sidecar was replaced or while the
     * virtual display is recreated. Retry within the task-entry capability wait before reporting
     * the channel unavailable, which ends the task.
     */
    override suspend fun prepare(route: ExecutionAttemptRoute): ExecutionBackendObservation {
        virtualSpaceManager.releaseOutgrownDisplay()
        val deadline = android.os.SystemClock.elapsedRealtime() +
            ruleConfigManager.harnessPolicy().runtimeThresholds.taskEntryCapabilityWaitMs
        while (true) {
            if (supported(route)) {
                val readiness = virtualSpaceReadiness.ensureReadyResult()
                if (readiness.status == VirtualSpaceReadinessStatus.INPUT_PERMISSION_DENIED) {
                    return unavailable(route, AttemptDiagnosticCode.INPUT_PERMISSION_DENIED)
                }
                if (readiness.ready) {
                    val observation = inspect(route)
                    if (observation.backendAvailable) return observation
                }
            }
            if (android.os.SystemClock.elapsedRealtime() >= deadline) return unavailable(route)
            kotlinx.coroutines.delay(BACKEND_PREPARE_RETRY_MS)
        }
    }
    override suspend fun inspect(route: ExecutionAttemptRoute): ExecutionBackendObservation {
        if (!supported(route)) return unavailable(route)
        val binding = virtualSpaceManager.activeBinding()?.takeIf {
            it.runtimeMode == RuntimeMode.STANDARD && it.displayId > 0 &&
                virtualSpaceSidecarBridge.isBoundSessionAlive(it)
        } ?: return unavailable(route)
        return ExecutionBackendObservation(route.expectedBackend, binding.displayId, binding.backendSessionId,
            true, DeviceCapability.entries.filterTo(LinkedHashSet()) { it != DeviceCapability.HUMANIZED_GESTURE })
    }
    private fun supported(route: ExecutionAttemptRoute): Boolean =
        route.runtimeMode == RuntimeMode.STANDARD && route.executionSpace == ExecutionSpace.VIRTUAL_SPACE &&
            capabilityRegistry.currentStrategy(capabilityRegistry.refresh(force = true)).selection.effectiveMode == RuntimeMode.STANDARD
    private fun unavailable(route: ExecutionAttemptRoute, diagnostic: String? = null): ExecutionBackendObservation {
        airModePairingManager.notifyPairingRequiredIfNeeded()
        return ExecutionBackendObservation(route.expectedBackend,
            capabilityRegistry.getVirtualDisplayId() ?: 0, "", false, emptySet(),
            unavailableDiagnosticCode = diagnostic ?: when {
                !virtualSpaceManager.isDeviceSupported() -> AttemptDiagnosticCode.VIRTUAL_DISPLAY_UNSUPPORTED
                route.runtimeMode != RuntimeMode.STANDARD || route.executionSpace != ExecutionSpace.VIRTUAL_SPACE -> AttemptDiagnosticCode.BACKEND_UNAVAILABLE
                rootConnection.currentMethod != DeviceConnectionMethod.WIRELESS -> AttemptDiagnosticCode.BACKEND_UNAVAILABLE
                airModePairingManager.isPairingRequired || !airModePairingManager.isPreviouslyPaired -> AttemptDiagnosticCode.AIR_PAIRING_REQUIRED
                else -> AttemptDiagnosticCode.AIR_CHANNEL_RECOVERY_REQUIRED
            })
    }
}

private const val BACKEND_PREPARE_RETRY_MS = 500L
