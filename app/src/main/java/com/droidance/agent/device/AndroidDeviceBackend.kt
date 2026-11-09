// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import com.droidance.core.device.tools.DeviceScreenFrame

import com.droidance.core.agent.tools.DeviceInputResult

import android.content.Context
import com.droidance.core.device.tools.AdbShellExecutor
import com.droidance.core.device.tools.SidecarInputController
import com.droidance.core.device.tools.SidecarScreenCapture
import com.droidance.core.agent.tools.TapPrecision
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidDeviceBackend @Inject constructor(
    private val executionContextStore: ExecutionContextStore,
    private val sidecarBridge: VirtualSpaceSidecarBridge,
    private val virtualSpaceManager: VirtualSpaceManager,
    private val sidecarInput: SidecarInputController,
    private val sidecarCapture: SidecarScreenCapture,
    private val nativeImeTextInput: NativeImeTextInput
) : DeviceBackend {
    override val activeDisplayId: Int?
        get() = executionContextStore.current()?.displayId?.takeUnless { it == 0 }

    override suspend fun captureScreen(forceFresh: Boolean): DeviceScreenFrame? =
        sidecarCapture.captureBoundFrame(executionContextStore.requireCurrent(), forceFresh)

    override suspend fun samplePixels(points: List<Pair<Int, Int>>): FreshPixelSample? =
        kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
            frozenSidecarBinding(executionContextStore.requireCurrent())?.let { sidecarBridge.samplePixels(it, points) }
        }
    override suspend fun sampleScreen(): com.droidance.core.device.tools.VisualFrameSampler.Frame? =
        kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
            frozenSidecarBinding(executionContextStore.requireCurrent())?.let(sidecarBridge::sampleFrame)
        }

    override fun lastScreenFrame(): DeviceScreenFrame? =
        sidecarCapture.getLastCapturedFrame()?.takeIf { it.displayId == activeDisplayId }

    override fun invalidateScreenCache() = sidecarCapture.invalidateFrameCache()

    override fun screenFailureMessage(): String = sidecarCapture.failureMessage()


    override suspend fun tap(x: Int, y: Int, precision: TapPrecision): DeviceInputResult =
        when (precision) {
            TapPrecision.NORMAL -> sidecarInput.tap(x, y)
            TapPrecision.PRECISE -> sidecarInput.preciseTap(x, y)
            TapPrecision.EXACT -> sidecarInput.exactTap(x, y)
        }

    override suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int
    ): DeviceInputResult = sidecarInput.swipe(x1, y1, x2, y2, durationMs)

    override suspend fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) =
        sidecarInput.drag(x1, y1, x2, y2, durationMs)

    override suspend fun tracePath(points: List<Pair<Int, Int>>, durationMs: Int) =
        sidecarInput.traceAlongPath(points.map { android.graphics.Point(it.first, it.second) }, durationMs)

    override suspend fun pressKey(keyCode: Int): DeviceInputResult = sidecarInput.keyevent(keyCode)

    override suspend fun setKeyboardVisible(visible: Boolean): DeviceInputResult =
        kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
            if (visible) return@runInterruptible DeviceInputResult(false,
                "Background execution keeps the user's keyboard unchanged. Use screen_act type_text with shortcut or paste_key.",
                diagnosticCode = "BACKGROUND_IME_DISABLED")
            val execution = executionContextStore.requireCurrent()
            val binding = frozenSidecarBinding(execution)
                ?: return@runInterruptible DeviceInputResult(false, "Frozen keyboard backend unavailable",
                    diagnosticCode = "BACKEND_SESSION_MISMATCH")
            val ready = virtualSpaceManager.setExecutionKeyboard(binding, execution.executionId, visible)
            DeviceInputResult(ready, "Native keyboard allowed=$visible; request_accepted=$ready. " +
                "Inspect the current frame; tap the observed editor if the keyboard is not yet shown.",
                diagnosticCode = if (ready) null else "NATIVE_KEYBOARD_UNAVAILABLE")
        }

    override suspend fun awaitInputFocus(timeoutMs: Long): InputFocusReadiness =
        sidecarInput.awaitInputFocus(timeoutMs)


    override suspend fun typeText(
        text: String,
        clearExisting: Boolean,
        expectedFocusTarget: String,
        method: com.droidance.core.agent.tools.TextInputMethod,
        focusPoint: Pair<Int, Int>?
    ): DeviceInputResult = if (method == com.droidance.core.agent.tools.TextInputMethod.IME) {
        nativeImeTextInput.type(executionContextStore.requireCurrent(), text, clearExisting, expectedFocusTarget, checkNotNull(focusPoint))
    } else sidecarInput.inputText(text, clearExisting, expectedFocusTarget, method)

    override suspend fun openPackage(packageName: String, launchMode: AppLaunchMode, intentFlags: Int): DeviceAppLaunchOutcome {
        val binding = frozenSidecarBinding(executionContextStore.requireCurrent())
            ?: return launchUnavailable("BACKEND_SESSION_MISMATCH")
        if (virtualSpaceManager.activeBinding() != binding) return launchUnavailable("BACKEND_SESSION_MISMATCH")
        val result = virtualSpaceManager.launchApp(packageName, launchMode, intentFlags)
        return DeviceAppLaunchOutcome(result.commandSent, result.frameReady, "bound_sidecar", result.detail,
            if (result.commandSent) null else "APP_LAUNCH_FAILED")
    }
    override suspend fun openIntentCommand(command: String) = shell(command, 15_000)
    override suspend fun forceStopApp(packageName: String): DeviceShellOutcome {
        val binding = frozenSidecarBinding(executionContextStore.requireCurrent())
            ?: return unavailable("BACKEND_SESSION_MISMATCH", "The frozen launch backend is unavailable")
        val result = sidecarBridge.forceStopPackage(binding, packageName)
        return DeviceShellOutcome("bound_sidecar", if (result.ok) 0 else -1, result.detail,
            if (result.ok) "" else result.detail, if (result.ok) null else "APP_RESET_FAILED")
    }
    override suspend fun querySystem(request: SystemStateQuery): SystemQueryOutcome {
        val binding = frozenSidecarBinding(executionContextStore.requireCurrent())
        return binding?.let { sidecarBridge.querySystem(it, request) }
            ?: SystemQueryOutcome("none", -1, SystemQueryOutput.read(java.io.StringReader(""), request.lineFilter, request.contextLines), "Frozen query backend unavailable", "BACKEND_SESSION_MISMATCH")
    }
    override suspend fun readClipboard(): com.droidance.core.agent.event.ToolExecutionResult {
        val binding = frozenSidecarBinding(executionContextStore.requireCurrent())
            ?: return com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("BACKEND_SESSION_MISMATCH", false)
        return kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
            sidecarBridge.readClipboard(binding)
        }
    }
    override suspend fun shell(command: String, timeoutMs: Long): DeviceShellOutcome {
        val binding = frozenSidecarBinding(executionContextStore.requireCurrent())
            ?: return unavailable("BACKEND_SESSION_MISMATCH", "The frozen device backend is unavailable")
        val result = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
            sidecarBridge.execShellCommand(binding, command, timeoutMs)
        } ?: return unavailable("BACKEND_CHANNEL_UNAVAILABLE", "The frozen device channel is unavailable")
        return DeviceShellOutcome("bound_sidecar_device_shell", result.exitCode, result.stdout, result.stderr,
            result.diagnosticCode, result.executionStatus, result.stdoutTruncated, result.stderrTruncated,
            result.stdoutBytes, result.stderrBytes, result.executionUid)
    }
    private fun frozenSidecarBinding(context: ExecutionContext): SidecarSessionBinding? {
        if (context.mode != RuntimeMode.STANDARD || !context.isVirtualSpace || context.displayId <= 0) return null
        return SidecarSessionBinding(context.mode, context.displayId, context.backendSessionId)
            .takeIf(sidecarBridge::isBoundSessionAlive)
    }

    private fun launchUnavailable(code: String): DeviceAppLaunchOutcome = DeviceAppLaunchOutcome(
        accepted = false,
        backend = "none",
        detail = "The frozen app launch backend is unavailable.",
        diagnosticCode = code
    )

    private fun unavailable(code: String, message: String): DeviceShellOutcome =
        DeviceShellOutcome(
            backend = "none",
            exitCode = -1,
            stdout = "",
            stderr = message,
            diagnosticCode = code
        )
}
