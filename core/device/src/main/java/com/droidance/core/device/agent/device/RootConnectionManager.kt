// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DeviceConnectionMethod { WIRELESS, ROOT, SHIZUKU }

/** Persists the chosen bootstrap transport. Device execution still uses the shell sidecar. */
@Singleton
class RootConnectionManager @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("droidance_settings", Context.MODE_PRIVATE)
    private val _method = MutableStateFlow(
        readMethod()
    )
    val method = _method.asStateFlow()
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_METHOD) {
            _method.value = readMethod()
        }
    }
    // app_process has no ActivityThread main handler for SharedPreferences listener delivery.
    init { if (!RuntimeProcessRole.isOwner) prefs.registerOnSharedPreferenceChangeListener(preferenceListener) }
    val currentMethod: DeviceConnectionMethod
        get() = if (RuntimeProcessRole.isOwner) {
            readMethod()
        } else _method.value
    private val readers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "root-shell-reader").apply { isDaemon = true }
    }
    @Volatile var rootReady: Boolean = false
        private set
    @Volatile private var candidateMethod: DeviceConnectionMethod? = null
    @Volatile private var shellIdentity = SHELL_IDENTITY

    val effectiveMethod: DeviceConnectionMethod get() = candidateMethod ?: currentMethod
    val useRoot: Boolean get() = rootReady && effectiveMethod == DeviceConnectionMethod.ROOT

    private fun readMethod(): DeviceConnectionMethod = DeviceConnectionMethod.entries
        .firstOrNull { it.name == prefs.getString(KEY_METHOD, null) } ?: DeviceConnectionMethod.WIRELESS

    /** Request authorization only on an explicit connect or once when restoring a saved choice. */
    @Synchronized fun authorize(): Boolean {
        val help = runSu("su --help", AUTH_TIMEOUT_MS)
        val identity = SHELL_IDENTITY + if (help.contains("--drop-cap")) " -d" else ""
        val probe = "id -u; id -g; id -G; awk '/^Cap(Prm|Eff):/ {print \$2}' /proc/\$\$/status"
        val output = runSu("id -u; $identity 2000 -c ${quote(probe)}", AUTH_TIMEOUT_MS)
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        rootReady = lines.size == 6 && lines[0] == "0" && lines[1] == "2000" && lines[2] == "2000" &&
            lines[3].split(' ').any { it == "3003" } && lines[4].toLongOrNull(16) == 0L &&
            lines[5].toLongOrNull(16) == 0L
        if (rootReady) shellIdentity = identity
        return rootReady
    }

    fun beginCandidate(method: DeviceConnectionMethod) { candidateMethod = method }
    fun endCandidate() { candidateMethod = null }

    fun select(method: DeviceConnectionMethod) {
        prefs.edit().putString(KEY_METHOD, method.name).apply()
        _method.value = method
    }

    /** Execute as shell, preserving the same Binder identity and device behavior as wireless ADB. */
    fun shell(command: String, timeoutMs: Long): String {
        if (!useRoot) return "[ROOT_DISCONNECTED]"
        val marker = "__DROIDANCE_ROOT_${System.nanoTime()}__"
        val script = "$command\nprintf '\\n$marker\\n'\nexit 0"
        val output = runSu("$shellIdentity 2000 -c ${quote(script)}", timeoutMs)
        val markerIndex = output.lastIndexOf(marker)
        if (output.startsWith("[ROOT_") || markerIndex < 0) {
            rootReady = false
            return if (output.startsWith("[ROOT_")) output else "[ROOT_ERROR] shell did not complete"
        }
        return output.substring(0, markerIndex).trimEnd('\n', '\r')
    }

    private fun runSu(command: String, timeoutMs: Long): String {
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (error: Exception) {
            return "[ROOT_ERROR] ${error.javaClass.simpleName}"
        }
        val output = readers.submit<String> {
            process.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder()
                val tail = StringBuilder()
                var total = 0L
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    total += count
                    val remaining = MAX_OUTPUT_CHARS - TAIL_OUTPUT_CHARS - text.length
                    if (remaining > 0) text.append(buffer, 0, minOf(count, remaining))
                    tail.append(buffer, 0, count)
                    if (tail.length > TAIL_OUTPUT_CHARS) tail.delete(0, tail.length - TAIL_OUTPUT_CHARS)
                }
                val extra = (total - text.length).coerceAtMost(TAIL_OUTPUT_CHARS.toLong()).toInt()
                text.toString() + if (extra > 0) tail.takeLast(extra) else ""
            }
        }
        return try {
            if (!process.waitFor(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                output.cancel(true)
                "[ROOT_TIMEOUT]"
            } else {
                val text = output.get(2, TimeUnit.SECONDS)
                if (process.exitValue() == 0) text else "[ROOT_ERROR] ${text.take(300)}"
            }
        } catch (interrupted: InterruptedException) {
            process.destroyForcibly()
            output.cancel(true)
            Thread.currentThread().interrupt()
            "[ROOT_ERROR] interrupted"
        } catch (error: Exception) {
            process.destroyForcibly()
            output.cancel(true)
            "[ROOT_ERROR] ${error.javaClass.simpleName}"
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val KEY_METHOD = "device_connection_method"
        // The Android shell's supplementary groups include inet for DNS and network access.
        const val SHELL_IDENTITY = "su -g 2000 -G 1004 -G 1007 -G 1011 -G 1015 -G 1028 " +
            "-G 1078 -G 1079 -G 3001 -G 3002 -G 3003 -G 3006 -G 3009 -G 3011 -G 3012"
        const val AUTH_TIMEOUT_MS = 30_000L
        const val MAX_OUTPUT_CHARS = 128_000
        const val TAIL_OUTPUT_CHARS = 4_096
    }
}
