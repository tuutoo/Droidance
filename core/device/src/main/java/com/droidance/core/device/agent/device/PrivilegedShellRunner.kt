// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import com.droidance.core.device.air.AirModePairingManager
import javax.inject.Inject
import javax.inject.Singleton

enum class ShellIdentity {
    DEFAULT,
    SHELL
}

@Singleton
class PrivilegedShellRunner @Inject constructor(
    private val airModePairingManager: AirModePairingManager,
    private val root: RootConnectionManager,
    private val shizuku: ShizukuConnectionManager
) {
    fun isAvailable(identity: ShellIdentity = ShellIdentity.SHELL): Boolean =
        if (RuntimeProcessRole.isOwner) true else
        when (identity) {
            ShellIdentity.DEFAULT,
            ShellIdentity.SHELL -> when (root.effectiveMethod) {
                DeviceConnectionMethod.ROOT -> root.useRoot
                DeviceConnectionMethod.SHIZUKU -> shizuku.refresh().ready
                DeviceConnectionMethod.WIRELESS -> isAdbShellReady()
            }
        }

    fun shell(command: String, timeoutMs: Long, service: String = "shell"): String =
        when (root.effectiveMethod) {
            DeviceConnectionMethod.ROOT -> root.shell(command, timeoutMs)
            DeviceConnectionMethod.SHIZUKU -> shizuku.shell(command, timeoutMs)
            DeviceConnectionMethod.WIRELESS -> airModePairingManager.shell(command, timeoutMs, service)
        }

    fun isFailure(output: String): Boolean = output.startsWith("[AIR_") || output.startsWith("[ROOT_") || output.startsWith("[SHIZUKU_")

    fun currentAdbShellUid(): Int? {
        if (RuntimeProcessRole.isOwner) return ADB_SHELL_UID
        if (!airModePairingManager.isConnected) return null
        return airModePairingManager
            .shell("id -u", ADB_UID_PROBE_TIMEOUT_MS)
            .trim()
            .takeUnless { it.startsWith("[AIR_") }
            ?.toIntOrNull()
    }

    fun isAdbShellUid(): Boolean =
        currentAdbShellUid() == ADB_SHELL_UID

    fun isAdbShellReady(): Boolean =
        RuntimeProcessRole.isOwner || (airModePairingManager.isConnected && isAdbShellUid())

    fun describeShellUnavailable(): String {
        if (root.currentMethod == DeviceConnectionMethod.ROOT) return "Root authorization or shell is unavailable"
        if (root.effectiveMethod == DeviceConnectionMethod.SHIZUKU) return "Shizuku authorization or shell is unavailable"
        val uid = currentAdbShellUid()
        return when {
            !airModePairingManager.isConnected ->
                "Air ADB shell is disconnected; wireless debugging pairing is required"
            uid != ADB_SHELL_UID ->
                "Air ADB uid=${uid ?: "unknown"}; required shell uid=$ADB_SHELL_UID"
            else -> "Air ADB shell is unavailable"
        }
    }

    companion object {
        const val ADB_SHELL_UID = 2000
        private const val ADB_UID_PROBE_TIMEOUT_MS = 2_000L
    }
}
