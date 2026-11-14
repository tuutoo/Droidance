// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.agent.device.RuntimeModeProvider
import com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge
import com.droidance.core.agent.debug.DebugLogAppender
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified device shell command channel (§5.5).
 *
 * Runs a shell command over the resident sidecar Binder when it is healthy, and only falls
 * back to the frozen shell transport only when Binder was absent before dispatch.
 * An uncertain Binder result is never replayed. This keeps
 * shell-backed tools working after Wi-Fi / online-ADB drops as long as the resident Binder is
 * alive, treating "online ADB" and "resident Binder" as independent transports. Only the
 * chosen backend, elapsed time and exit status are logged — never the command output, which
 * may carry sensitive values.
 *
 * The channel resolves the current runtime mode itself, so callers just run commands without
 * knowing the transport. The internal delegate constructor lets JVM unit tests drive channel
 * selection directly without starting any Android service; production uses the [Inject]
 * constructor.
 */
@Singleton
class DeviceShellCommandChannel internal constructor(
    private val binderAvailable: () -> Boolean,
    private val binderExec: (String, Long) -> AdbShellExecutor.Result?,
    private val shellAvailable: () -> Boolean,
    private val shellExec: (String, Long) -> AdbShellExecutor.Result,
    private val log: (String) -> Unit,
    private val shellBackend: () -> Backend
) {
    @Inject
    constructor(
        adbShell: AdbShellExecutor,
        sidecarBridge: VirtualSpaceSidecarBridge,
        modeProvider: RuntimeModeProvider,
        debugLogAppender: DebugLogAppender,
        connection: com.droidance.core.device.agent.device.RootConnectionManager
    ) : this(
        binderAvailable = {
            modeProvider.currentMode()?.let(sidecarBridge::isResidentBinderAlive) == true
        },
        binderExec = { command, timeoutMs ->
            sidecarBridge.execShellCommand(command, modeProvider.currentMode(), timeoutMs = timeoutMs)
        },
        shellAvailable = {
            when (modeProvider.currentMode()) {
                RuntimeMode.STANDARD -> adbShell.isAvailable()
                else -> false
            }
        },
        shellExec = { command, timeoutMs ->
            when (modeProvider.currentMode()) {
                RuntimeMode.STANDARD -> adbShell.exec(command, timeoutMs)
                else -> AdbShellExecutor.Result("", "Unsupported execution mode", -1)
            }
        },
        log = { line -> debugLogAppender.appendEvent(line) },
        shellBackend = {
            when (modeProvider.currentMode()) {
                RuntimeMode.STANDARD -> when (connection.effectiveMethod) {
                    com.droidance.core.device.agent.device.DeviceConnectionMethod.WIRELESS -> Backend.AIR_SHELL
                    com.droidance.core.device.agent.device.DeviceConnectionMethod.ROOT -> Backend.ROOT_SHELL
                    com.droidance.core.device.agent.device.DeviceConnectionMethod.SHIZUKU -> Backend.SHIZUKU_SHELL
                }
                else -> Backend.NONE
            }
        }
    )

    enum class Backend { BINDER, AIR_SHELL, ROOT_SHELL, SHIZUKU_SHELL, NONE }

    data class Outcome(
        val backend: Backend,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val stdoutTruncated: Boolean = false
    ) {
        val available: Boolean get() = backend != Backend.NONE
        val success: Boolean get() = available && exitCode == 0

        /** stdout 优先，为空时回退 stderr（与 AdbShellExecutor.Result.output 语义一致）。 */
        val output: String get() = stdout.ifBlank { stderr }
    }

    /** 通道可用性：健康的常驻 Binder 或冻结模式对应的 shell 任一即可。 */
    fun isAvailable(): Boolean = binderAvailable() || shellAvailable()

    fun exec(command: String, timeoutMs: Long = 15_000L): Outcome {
        val startedAt = System.currentTimeMillis()
        if (binderAvailable()) {
            val result = binderExec(command, timeoutMs)
            if (result != null) {
                log("device_shell backend=binder exit=${result.exitCode} ${System.currentTimeMillis() - startedAt}ms")
                return Outcome(Backend.BINDER, result.stdout, result.stderr, result.exitCode, result.stdoutTruncated)
            }
            // Delivery may already have happened. Never replay through another transport.
            return Outcome(Backend.BINDER, "", "SHELL_DELIVERY_UNKNOWN: inspect state before a new command", -1)
        }
        if (shellAvailable()) {
            val backend = shellBackend()
            val result = shellExec(command, timeoutMs)
            log("device_shell backend=${backend.name.lowercase()} exit=${result.exitCode} ${System.currentTimeMillis() - startedAt}ms")
            return Outcome(backend, result.stdout, result.stderr, result.exitCode, result.stdoutTruncated)
        }
        log("device_shell backend=none unavailable")
        return Outcome(Backend.NONE, "", "device shell channel unavailable for the frozen runtime mode", -1)
    }
}
