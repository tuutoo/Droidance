// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.droidance.config.RuleConfigManager
import com.droidance.core.agent.tools.DeviceInputResult
import com.droidance.core.agent.tools.TextInputEvidence
import com.droidance.core.device.tools.SidecarInputController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** One requested InputConnection commit, with display checks and reversible IME selection. */
@Singleton
class NativeImeTextInput @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: VirtualSpaceManager,
    private val bridge: VirtualSpaceSidecarBridge,
    private val rules: RuleConfigManager,
    private val input: SidecarInputController,
    private val executionContexts: ExecutionContextStore,
    private val driver: NativeInputDriverClient
) {
    private val mutex = Mutex()

    suspend fun type(execution: ExecutionContext, text: String, clear: Boolean, expectedFocus: String,
        focusPoint: Pair<Int, Int>): DeviceInputResult = mutex.withLock {
        val binding = SidecarSessionBinding(execution.mode, execution.displayId, execution.backendSessionId)
        if (executionContexts.current() != execution || manager.activeBinding() != binding ||
            manager.retainedExecution.value?.executionId != execution.executionId)
            return@withLock failed("Frozen execution is no longer current")
        // A virtual task must never take the user's global IME, even for an explicit request.
        // Keep this check before driver setup, settings reads and all keyboard policy changes.
        if (execution.isVirtualSpace) return@withLock failed(
            "Background input cannot switch the system keyboard. Use method=shortcut or paste_key.")
        val policy = Json.parseToJsonElement(rules.readMarkdownSection("device_tools.md", "native_ime_input_policy")).jsonObject
        val timeout = policy.getValue("timeoutMs").jsonPrimitive.long.also { require(it in 1..10_000) }
        val poll = policy.getValue("pollMs").jsonPrimitive.long.also { require(it in 1..timeout) }
        val inputMethods = context.getSystemService(InputMethodManager::class.java)
        val component = ComponentName(context, AgentTextInputMethodService::class.java).flattenToShortString()
        val selectedBefore = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val original = if (selectedBefore == component) withContext(Dispatchers.IO) { driver.command("restore").getString("ime") }
            ?.takeUnless { it == component } else selectedBefore
        val wasEnabled = inputMethods.enabledInputMethodList.any { it.id == component }
        if (original.isNullOrBlank()) return@withLock failed("Initial input method unavailable")
        fun focus() = bridge.getInputFocusState(binding.displayId, binding.runtimeMode, binding.backendSessionId)
        val foreground = bridge.getForegroundComponent(binding.displayId, binding.runtimeMode, binding.backendSessionId)
            ?.substringBefore('/')?.takeIf { it.isNotBlank() }
            ?: return@withLock failed("Execution foreground unavailable")
        if (focus() != expectedFocus) return@withLock failed("Execution input window changed")
        suspend fun command(source: String): Boolean = withContext(Dispatchers.IO) {
            bridge.execShellCommand(binding, source, timeout)?.let { it.exitCode == 0 && it.diagnosticCode == null } == true
        }
        // Component identities come from the platform/project, never task or App-name matching.
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        var result: DeviceInputResult? = null
        var connectionState = "ime_not_started"
        var restored = false
        var focusEvent: com.droidance.core.agent.tools.ExecutedActionEvent? = null
        try {
            withContext(Dispatchers.IO) { driver.command("begin", android.os.Bundle().apply { putString("ime", original) }) }
            val keyboardWasShown = withContext(Dispatchers.IO) { (manager.viewerImeBottomInset(binding) ?: 0) > 0 }
            // A visible keyboard already owns display focus. Focus its observed
            // editor before hiding that keyboard; otherwise bind first, then tap once.
            if (keyboardWasShown) {
                val focused = input.exactTap(focusPoint.first, focusPoint.second)
                if (!focused.success) return@withLock failed("Native IME focus tap failed")
                focusEvent = focused.actionEvent
            }
            if (!withContext(Dispatchers.IO) { manager.setExecutionKeyboard(binding, execution.executionId, true, handoffFocus = !keyboardWasShown) })
                return@withLock failed("Execution keyboard policy unavailable", focusConfirmed = true)
            if (!command("ime enable ${quote(component)}") || !command("ime set ${quote(component)}"))
                return@withLock failed("Native IME selection unavailable", focusConfirmed = true)
            if (Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) != component)
                return@withLock failed("Native IME selection was not retained", focusConfirmed = true)
            val deadline = android.os.SystemClock.elapsedRealtime() + timeout
            while (focus() != expectedFocus && android.os.SystemClock.elapsedRealtime() < deadline) delay(poll)
            if (focus() != expectedFocus) return@withLock failed("Execution input window changed before focusing")
            if (!keyboardWasShown) {
                val focused = input.exactTap(focusPoint.first, focusPoint.second)
                if (!focused.success) return@withLock failed("Native IME focus tap failed")
                focusEvent = focused.actionEvent
            }
            do {
                if (manager.retainedExecution.value?.executionId != execution.executionId)
                    return@withLock failed("Execution generation changed before commit")
                if (focus() != expectedFocus) return@withLock failed("Execution input window changed before commit")
                result = withContext(Dispatchers.IO) {
                    val reply = driver.command("commit", android.os.Bundle().apply {
                        putInt("display", binding.displayId); putString("package", foreground)
                        putString("text", text); putBoolean("clear", clear)
                    })
                    connectionState = reply.getString("state").orEmpty()
                    if (!reply.getBoolean("dispatched")) null else DeviceInputResult(reply.getBoolean("success"),
                        reply.getString("message").orEmpty(), textInputEvidence = TextInputEvidence(reply.getBoolean("focus"),
                            reply.getBoolean("payload"), reply.getBoolean("injected"), reply.getBoolean("changed"), reply.getBoolean("verified")),
                        diagnosticCode = reply.getString("diagnostic"))
                }
                if (result != null) break // A dispatched commit is never retried.
                delay(poll)
            } while (android.os.SystemClock.elapsedRealtime() < deadline)
            if (result?.success == true) {
                var acknowledged = false
                // commitText delivery is asynchronous. Keep the selected IME alive
                // until editor readback acknowledges it or the shared budget ends.
                do {
                    delay(poll)
                    acknowledged = withContext(Dispatchers.IO) { driver.command("delivered").getBoolean("delivered") }
                } while (!acknowledged && android.os.SystemClock.elapsedRealtime() < deadline)
                result = result?.copy(message = result?.message + "; native_editor_ack=$acknowledged")
            }
        } finally {
            withContext(NonCancellable) {
                val selected = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                restored = if (selected == component) command("ime set ${quote(original)}") else selected == original
                // Preserve a newer manual selection rather than restoring over its owner.
                if (!wasEnabled && (restored || selected != component)) command("ime disable ${quote(component)}")
                withContext(Dispatchers.IO) { manager.endExecutionKeyboard(execution.executionId) }
                withContext(Dispatchers.IO) { driver.command("end", android.os.Bundle().apply { putBoolean("restored", restored || selected != component) }) }
            }
        }
        val delivered = result ?: failed("No native editor connection on execution display; $connectionState", focusConfirmed = true)
        delivered.copy(message = delivered.message + "; initial_ime_restored=$restored", actionEvent = focusEvent)
    }

    private fun failed(message: String, focusConfirmed: Boolean = false) = DeviceInputResult(false, message,
        textInputEvidence = TextInputEvidence(focusConfirmed, true, false, false, false),
        diagnosticCode = "NATIVE_IME_INPUT_UNAVAILABLE")
}
