// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.device.agent.device

import android.os.Bundle
import android.os.Process
import android.system.Os
import androidx.annotation.Keep
import kotlin.system.exitProcess

/** Short-lived bootstrap helper. Device execution stays in the existing shell sidecar. */
@Keep
class ShizukuShellService : IShizukuShellService.Stub() {
    private val commands = ManagedShellCommands()

    override fun getUid(): Int = Process.myUid()

    override fun execute(command: String, timeoutMs: Long, executionId: String): Bundle {
        check(Process.myUid() == 2000 && Os.getgid() == 2000) { "Shell identity required" }
        val result = commands.execute(executionId, command, timeoutMs)
        return Bundle().apply {
            putString("stdout", result.stdout)
            putString("stderr", result.stderr)
            putInt("exit_code", result.exitCode)
            putString("execution_status", result.executionStatus)
            putString("diagnostic_code", result.diagnosticCode)
            putBoolean("truncated", result.stdoutTruncated || result.stderrTruncated)
        }
    }

    override fun destroy() = exitProcess(0)
}
