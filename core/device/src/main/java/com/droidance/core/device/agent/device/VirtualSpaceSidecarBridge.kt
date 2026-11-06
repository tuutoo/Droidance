// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.device.tools.LauncherIntentCommand

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.RemoteException
import android.view.SurfaceControl
import com.droidance.core.device.tools.AdbShellExecutor
import com.droidance.core.agent.tools.TextInputEvidence
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolEvidenceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VirtualSpaceSidecarBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: PrivilegedShellRunner,
    private val rootConnection: RootConnectionManager,
    private val adbShell: AdbShellExecutor,
    private val frameInspector: VirtualSpaceFrameInspector
) {
    data class FrameSnapshot(
        val png: ByteArray,
        val producedAtMs: Long,
        val foregroundComponent: String? = null,
        val pixelReference: com.droidance.core.agent.tools.ObservationPixelReference? = null,
        val timings: Map<String, Long> = emptyMap()
    )
    data class CommandResult(
        val ok: Boolean,
        val detail: String = "",
        val values: Map<String, String> = emptyMap(),
        val channelUnavailable: Boolean = false,
        val textInputEvidence: TextInputEvidence? = null
    ) {
        /** An explicit platform permission rejection, independent of device brand. */
        val inputPermissionDenied: Boolean
            get() = !ok && detail.contains("INPUT_INJECTION_DENIED:")
    }

    private val serviceLock = Any()

    private val directServices = ConcurrentHashMap<Int, IVirtualSpaceSidecar>()
    private val displayModes = ConcurrentHashMap<Int, RuntimeMode>()
    private val displaySizes = ConcurrentHashMap<Int, Pair<Int, Int>>()
    private val protocolCompatibility = ConcurrentHashMap<IBinder, Boolean>()
    private val runtimeIdentities = ConcurrentHashMap<IBinder, String>()

    // sidecar 是独立 app_process 子进程；AIDL 是同步阻塞的双向 Binder transact，进程卡住时
    // 调用方线程会被无限期挂起——既不抛异常也不返回。这曾是"后台任务卡住上百分钟只能手动取消"的
    // 真凶（sidecar 端一旦出现异常，调用方拿不到任何反馈）。所有跨进程调用统一走 callWithTimeout
    // 做有界超时，让卡死在几秒内变成明确失败，而不是无限期挂起。
    private val sidecarCallExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "sidecar-call-timeout").apply { isDaemon = true }
    }

    private fun <T> callWithTimeout(
        timeoutMs: Long = frameInspector.sidecarCallTimeoutMs(),
        markSidecarSuspectOnTimeout: Boolean = true,
        suspectService: IVirtualSpaceSidecar? = null,
        op: () -> T
    ): Result<T> {
        val future = sidecarCallExecutor.submit<T> { op() }
        return try {
            Result.success(future.get(timeoutMs, TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) {
            future.cancel(true)
            if (markSidecarSuspectOnTimeout) markSidecarSuspect(suspectService)
            Result.failure(TimeoutException("sidecar call timed out after ${timeoutMs}ms"))
        } catch (interrupted: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            Result.failure(interrupted)
        } catch (e: Exception) {
            val failure = e.cause ?: e
            if (markSidecarSuspectOnTimeout && isBinderTransportFailure(failure)) {
                markSidecarSuspect(suspectService)
            }
            Result.failure(failure)
        }
    }

    private fun isBinderTransportFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is RemoteException) return true
            current = current.cause
        }
        return false
    }

    /** 超时或 Binder 传输失败后清掉缓存引用，避免后续调用继续撞向同一个死连接。 */
    private fun markSidecarSuspect(service: IVirtualSpaceSidecar?) {
        if (service == null) return
        val binder = service.asBinder()
        directServices.entries
            .filter { it.value.asBinder() == binder }
            .forEach { directServices.remove(it.key, it.value) }
        protocolCompatibility.remove(binder)
        SidecarBinderRegistry.clear(binder)
    }

    private val shellUid = 2000
    private val sidecarServiceName = VirtualSpaceSidecarService.SERVICE_NAME
    private val sidecarLogTag = "DroidanceVSpace"

    fun isShellBridgeAvailable(): Boolean =
        shellRunner.isAvailable(ShellIdentity.SHELL) ||
            isRegisteredServiceAlive(RuntimeMode.STANDARD)

    /** Resident shell Binder availability is independent of the online ADB connection. */
    fun isResidentBinderAlive(): Boolean =
        isRegisteredServiceAlive(RuntimeMode.STANDARD)

    fun startAgentRuntime(packageName: String, installationId: String, storage: android.os.ParcelFileDescriptor): IAgentRuntimeOwner {
        val metrics = context.resources.displayMetrics
        if (!ensureResidentChannel(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            RuntimeMode.STANDARD)) throw RuntimeUnavailableException()
        val service = serviceOrNull(RuntimeMode.STANDARD) ?: throw RuntimeUnavailableException()
        val binder = service.startAgentRuntime(packageName, installationId, storage)
            ?: throw RuntimeUnavailableException()
        return IAgentRuntimeOwner.Stub.asInterface(binder)
    }

    fun isResidentBinderAlive(mode: RuntimeMode): Boolean =
        isRegisteredServiceAlive(mode)

    fun isChannelAvailableForMode(mode: RuntimeMode?): Boolean =
        when (mode) {
            RuntimeMode.STANDARD -> shellRunner.isAvailable(ShellIdentity.SHELL) ||
                isRegisteredServiceAlive(RuntimeMode.STANDARD)
            else -> false
        }

    fun ensureStarted(width: Int, height: Int, densityDpi: Int, mode: RuntimeMode?): CommandResult =
        synchronized(serviceLock) {
            val selectedMode = mode?.takeIf { it == RuntimeMode.STANDARD } ?: return@synchronized invalid("Standard mode required")
            if (RuntimeProcessRole.isOwner) {
                val service = serviceOrNull(selectedMode) ?: return@synchronized invalid("Resident device service is unavailable")
                return@synchronized startService(service, width, height, densityDpi, "resident_runtime", selectedMode)
            }
            // Allocate a virtual display only when execution starts on the resident shell Binder.
            if (!reconcileDuplicateResidentSidecars(selectedMode)) {
                return@synchronized invalid(
                    lastSidecarStartDiagnostic.ifBlank {
                        "duplicate resident sidecars could not be reconciled"
                    }
                )
            }
            replaceIncompatibleResidentSidecar(selectedMode, width, height, densityDpi)
            serviceOrNull(selectedMode)?.let { service ->
                val registeredStart = startService(
                    service,
                    width,
                    height,
                    densityDpi,
                    "registered_binder_${selectedMode.name.lowercase()}",
                    selectedMode
                )
                if (registeredStart.ok || !registeredStart.channelUnavailable) {
                    return@synchronized registeredStart
                }
            }
            if (residentSidecarProcessExists(
                    selectedMode,
                    currentProtocolOnly = false,
                )) {
                if (awaitBinder(selectedMode, RESIDENT_REBIND_RETRIES)) {
                    serviceOrNull(selectedMode)?.let { service ->
                        return@synchronized startService(
                            service,
                            width,
                            height,
                            densityDpi,
                            "rebound_binder_${selectedMode.name.lowercase()}",
                            selectedMode
                        )
                    }
                }
                if (!stopResidentSidecarProcess(
                        selectedMode,
                        rawServiceOrNull(selectedMode),
                        currentProtocolOnly = false,
                    )) {
                    return@synchronized invalid("resident sidecar protocol unavailable and restart failed")
                }
            }

            awaitSupervisorRestart(selectedMode)?.let { ready ->
                if (!ready) return@synchronized invalid(lastSidecarStartDiagnostic)
                serviceOrNull(selectedMode)?.let { service ->
                    return@synchronized startService(service, width, height, densityDpi, "supervisor_restart", selectedMode)
                }
                return@synchronized invalid("supervisor restarted without a usable resident Binder")
            }
            return@synchronized when (selectedMode) {
                RuntimeMode.STANDARD -> startShellAppProcess(width, height, densityDpi)
                else -> invalid("Unsupported execution mode")
            }
        }

    /**
     * Ensures the single full-capability sidecar process and Binder are resident without creating
     * a virtual display. Virtual-space startup later asks the same service to allocate a display.
     */
    /** sidecar 启动失败/binder 未到的诊断(含 sidecar 端日志)，供注入诊断回填，让真因在 debug 包可见。 */
    @Volatile
    var lastSidecarStartDiagnostic: String = ""
        private set

    /**
     * Replaces a resident sidecar that still runs a previous APK when the AIR shell cannot. Returns
     * true once a sidecar from the installed APK answers; false leaves the channel unavailable.
     */
    fun replaceStaleResidentSidecar(width: Int, height: Int, densityDpi: Int): Boolean =
        synchronized(serviceLock) {
            if (isRegisteredServiceAlive(RuntimeMode.STANDARD)) return@synchronized true
            if (shellRunner.isAvailable(ShellIdentity.SHELL)) return@synchronized false
            val stale = rawServiceOrNull(RuntimeMode.STANDARD) ?: return@synchronized false
            relaunchThroughSameProtocolSidecar(stale, width, height, densityDpi)
        }

    fun ensureResidentChannel(width: Int, height: Int, densityDpi: Int, mode: RuntimeMode?): Boolean {
        if (RuntimeProcessRole.isOwner) return isRegisteredServiceAlive(RuntimeMode.STANDARD)
        val selectedMode = mode?.takeIf { it == RuntimeMode.STANDARD } ?: return false
        return synchronized(serviceLock) {
            if (!reconcileDuplicateResidentSidecars(selectedMode)) return@synchronized false
            if (isRegisteredServiceAlive(selectedMode)) return@synchronized true
            replaceIncompatibleResidentSidecar(selectedMode, width, height, densityDpi)
            if (isRegisteredServiceAlive(selectedMode)) return@synchronized true
            if (residentSidecarProcessExists(
                    selectedMode,
                    currentProtocolOnly = false,
                )) {
                if (awaitBinder(selectedMode, RESIDENT_REBIND_RETRIES)) return@synchronized true
                if (!stopResidentSidecarProcess(
                        selectedMode,
                        rawServiceOrNull(selectedMode),
                        currentProtocolOnly = false,
                    )) {
                    return@synchronized false
                }
            }
            awaitSupervisorRestart(selectedMode)?.let { return@synchronized it }
            val cmd = buildAppProcessCommand(width, height, densityDpi)
            val ok = when (selectedMode) {
                RuntimeMode.STANDARD -> {
                    if (!shellRunner.isAvailable(ShellIdentity.SHELL)) {
                        lastSidecarStartDiagnostic = "Device shell unavailable: ${shellRunner.describeShellUnavailable()}"
                        return@synchronized false
                    }
                    // sidecar 启动必须用 shell,v2(无 PTY)：旧 shell: 协议分配 PTY 会让 app_process 卡死在 main 之前。
                    val launch = shellRunner.shell(cmd, ADB_START_TIMEOUT_MS, "shell,v2")
                    if (shellRunner.isFailure(launch)) {
                        lastSidecarStartDiagnostic = "Device shell launch failed: ${launch.take(160)}"
                        false
                    } else {
                        awaitBinder(RuntimeMode.STANDARD)
                    }
                }
                else -> false
            }
            if (!ok) {
                lastSidecarStartDiagnostic =
                    "binder not received in ${ADB_BIND_RETRIES * BIND_POLL_MS}ms; " +
                        "app-received-broadcasts=${SidecarBinderRegistry.receivedCount}; sidecar-log=[${readSidecarLog()}]"
            }
            ok
        }
    }

    /** Debug-export evidence of why the sidecar last stopped; needs either live shell channel. */
    fun readSidecarEvidence(): String = runCatching {
        val command = ResidentSidecarProcessCommands.evidenceCommand(sidecarStorageDirectory())
        val result = when {
            isResidentBinderAlive() -> execShellCommand(command, RuntimeMode.STANDARD, timeoutMs = EVIDENCE_TIMEOUT_MS)
            adbShell.isAvailable() -> adbShell.exec(command, EVIDENCE_TIMEOUT_MS)
            else -> null
        }
        result?.output ?: "sidecar evidence unavailable: no live shell channel"
    }.getOrElse { "sidecar evidence failed: ${it.javaClass.simpleName}" }

    /** Stops the sidecar and its supervisor; a graceful exit also works without the live ADB shell. */
    fun stopResidentChannel() {
        synchronized(serviceLock) {
            val service = rawServiceOrNull(RuntimeMode.STANDARD)
            stopResidentSidecarProcess(
                RuntimeMode.STANDARD,
                service,
                allowGracefulDestroy = service != null,
                currentProtocolOnly = false
            )
            clearStoppedSidecar(service?.asBinder())
        }
    }

    private fun awaitBinder(
        mode: RuntimeMode,
        retries: Int = ADB_BIND_RETRIES,
    ): Boolean {
        repeat(retries) {
            Thread.sleep(BIND_POLL_MS)
            if (isRegisteredServiceAlive(mode)) return true
        }
        return false
    }

    /** A live supervisor owns crash recovery; launching another during its backoff duplicates it. */
    private fun awaitSupervisorRestart(mode: RuntimeMode): Boolean? {
        fun supervisorAlive(): Boolean = shellRunner.isAvailable(ShellIdentity.SHELL) &&
            ResidentSidecarProcessCommands.parsePids(shellRunner.shell(
                ResidentSidecarProcessCommands.listSupervisorPidsCommand(context.packageName), PROCESS_PROBE_TIMEOUT_MS, "shell,v2"
            )).isNotEmpty()
        if (!supervisorAlive()) return null
        if (awaitBinder(mode, RESIDENT_REBIND_RETRIES)) return true
        if (!supervisorAlive()) return null
        lastSidecarStartDiagnostic = "resident sidecar supervisor is still recovering; duplicate launch withheld"
        return false
    }

    private fun residentSidecarProcessExists(
        mode: RuntimeMode,
        currentProtocolOnly: Boolean = true,
    ): Boolean = residentSidecarPids(
        mode,
        currentProtocolOnly = currentProtocolOnly,
    )?.isNotEmpty() == true

    /**
     * 同一运行模式只能有一个常驻 sidecar。多个同协议进程会各自周期性广播 Binder，
     * 后到的广播覆盖 registry，导致当前执行绑定的 session 被误判为已变化并中断任务。
     * 在建立新的执行绑定前统一清掉重复进程；单进程和无法探测时保持原有路径。
     */
    private fun reconcileDuplicateResidentSidecars(
        mode: RuntimeMode,
        failIfProbeUnavailable: Boolean = false
    ): Boolean {
        val pids = residentSidecarPids(mode)
            ?: return !failIfProbeUnavailable
        if (pids.size <= 1) return true
        lastSidecarStartDiagnostic =
            "duplicate resident sidecars detected for ${mode.name.lowercase()}: " +
                "pids=${pids.joinToString(",")}; restarting duplicate processes"
        // Keep one canonical process alive. Killing every process and relying on a later
        // ensure() to recreate it creates a window with no Binder; during that window another
        // pairing/readiness callback can start a second process and recreate the same duplicate.
        // The highest PID is the newest app_process on Android, so retain it and kill only stale
        // siblings in this role.
        val canonicalPid = pids.maxOrNull()
        val duplicatePids = pids.filter { it != canonicalPid }
        val stopped = terminateSpecificResidentSidecarProcesses(
            mode = mode,
            pids = duplicatePids,
        )
        if (!stopped) {
            lastSidecarStartDiagnostic += "; cleanup failed"
        }
        return stopped
    }

    private fun terminateSpecificResidentSidecarProcesses(
        mode: RuntimeMode,
        pids: Collection<Int>,
    ): Boolean {
        val distinctPids = pids.filter { it > 1 }.distinct()
        if (distinctPids.isEmpty()) return true
        val command = ResidentSidecarProcessCommands.stopCommand(distinctPids, force = true, packageName = context.packageName)
            ?: return true
        val accepted = when (mode) {
            RuntimeMode.STANDARD -> {
                if (!shellRunner.isAvailable(ShellIdentity.SHELL)) return false
                !shellRunner.isFailure(shellRunner.shell(command, PROCESS_PROBE_TIMEOUT_MS, "shell,v2"))
            }
            else -> return false
        }
        if (!accepted) return false
        repeat(PROCESS_EXIT_RETRIES) {
            val remaining = residentSidecarPids(mode)
                ?: return false
            if (remaining.none { it in distinctPids }) return true
            Thread.sleep(PROCESS_EXIT_POLL_MS)
        }
        return residentSidecarPids(mode)
            ?.none { it in distinctPids } == true
    }

    private fun residentSidecarPids(
        mode: RuntimeMode,
        currentProtocolOnly: Boolean = true,
    ): List<Int>? {
        if (mode != RuntimeMode.STANDARD) return null
        val command = ResidentSidecarProcessCommands.listPidsCommand(
            uid = shellUid,
            packageName = context.packageName,
            protocolVersion = SIDECAR_PROTOCOL_VERSION.takeIf { currentProtocolOnly }
        )
        val output = when (mode) {
            RuntimeMode.STANDARD -> {
                if (!shellRunner.isAvailable(ShellIdentity.SHELL)) return null
                shellRunner.shell(command, PROCESS_PROBE_TIMEOUT_MS, "shell,v2")
            }
            else -> return null
        }
        if (shellRunner.isFailure(output)) return null
        return ResidentSidecarProcessCommands.parsePids(output)
    }

    // 启动 App 必须走 `am start`，不能用 sidecar 进程内 context.startActivity：
    // shell 身份(uid=2000)进程内 startActivity 的 callingPackage 取自 base context(=android)，
    // 被 AMS 拒为 "Permission Denial: package=android does not belong to uid=2000"。
    // am 进程天然以 com.android.shell 身份运行(=adb shell am start --display)，callingPackage 合法。
    // Launches use the selected shell channel when no Binder was available before dispatch.
    fun launchPackage(
        displayId: Int,
        packageName: String,
        component: String? = null,
        mode: RuntimeMode? = null
    ): CommandResult {
        val comp = component?.trim()?.takeIf { it.isNotEmpty() }
        if (comp == null && packageName.isBlank()) {
            return invalid("launchPackage: component and packageName both empty")
        }
        return runAmStart(
            command = amStartCommand(displayId, comp, packageName.trim(), launcherEntry = true),
            mode = mode,
        )
    }

    /**
     * Executes an app launch on the exact sidecar binder selected before the model round.
     * Unlike the legacy overload, this path never falls back to a different shell or mode.
     */
    fun launchPackage(
        binding: SidecarSessionBinding,
        packageName: String,
        component: String? = null,
        intentFlags: Int = DEFAULT_LAUNCH_FLAGS
    ): CommandResult {
        val comp = component?.trim()?.takeIf { it.isNotEmpty() }
        if (comp == null && packageName.isBlank()) {
            return invalid("launchPackage: component and packageName both empty")
        }
        return runBoundAmStart(
            binding = binding,
            command = amStartCommand(binding.displayId, comp, packageName.trim(), intentFlags, launcherEntry = true)
        )
    }

    fun forceStopPackage(binding: SidecarSessionBinding, packageName: String): CommandResult {
        val normalized = packageName.trim()
        if (!PACKAGE_NAME.matches(normalized)) return invalid("forceStopPackage: invalid packageName")
        val result = execShellCommand(
            binding = binding,
            command = "am force-stop ${adbShell.escapeForShell(normalized)}"
        ) ?: return invalid("bound sidecar shell unavailable", channelUnavailable = true)
        return CommandResult(
            ok = result.success,
            detail = result.output.trim().ifBlank {
                if (result.success) "force-stop ok" else "force-stop failed"
            }.take(200),
            channelUnavailable = !result.success
        )
    }

    fun updateRuntimeIndicator(
        binding: SidecarSessionBinding,
        visible: Boolean,
        label: String,
        actionLabel: String,
        paused: Boolean,
        callback: IRuntimeIndicatorCallback
    ): CommandResult = callBound("update runtime indicator", binding) { service ->
        service.setRuntimeIndicatorCallback(callback)
        service.updateRuntimeIndicator(visible, label, actionLabel, paused)
    }

    fun updateRuntimeIsland(
        binding: SidecarSessionBinding,
        visible: Boolean,
        progressText: String,
        currentActionText: String,
        takeoverLabel: String,
        stopLabel: String,
        paused: Boolean,
        callback: IRuntimeIndicatorCallback
    ): CommandResult = callBound("update runtime island", binding) { service ->
        service.setRuntimeIndicatorCallback(callback)
        service.updateRuntimeIsland(
            visible,
            progressText,
            currentActionText,
            takeoverLabel,
            stopLabel,
            paused
        )
    }

    fun updateRuntimeIslandBitmap(
        binding: SidecarSessionBinding,
        visible: Boolean,
        islandPng: ByteArray,
        topOffsetPx: Int,
        callback: IRuntimeIndicatorCallback
    ): CommandResult = callBound("update runtime island bitmap", binding) { service ->
        service.setRuntimeIndicatorCallback(callback)
        service.updateRuntimeIslandBitmap(visible, islandPng, topOffsetPx)
    }

    fun updateRuntimeCursor(
        binding: SidecarSessionBinding,
        visible: Boolean,
        actionType: Int,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int
    ): CommandResult = callBound("update runtime cursor", binding) { service ->
        service.updateRuntimeCursor(
            visible,
            actionType,
            startX,
            startY,
            endX,
            endY,
            durationMs
        )
    }

    fun updateRuntimeCursorBitmap(
        binding: SidecarSessionBinding,
        visible: Boolean,
        actionType: Int,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int,
        startedAtMs: Long,
        finishedAtMs: Long,
        motionEnabled: Boolean,
        pointerPng: ByteArray
    ): CommandResult = callBound("update runtime cursor bitmap", binding) { service ->
        service.updateRuntimeCursorBitmap(
            visible,
            actionType,
            startX,
            startY,
            endX,
            endY,
            durationMs,
            startedAtMs,
            finishedAtMs,
            motionEnabled,
            pointerPng
        )
    }

    fun updateRuntimeCursorBitmapOnDisplay(
        binding: SidecarSessionBinding,
        targetDisplayId: Int,
        visible: Boolean,
        actionType: Int,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int,
        startedAtMs: Long,
        finishedAtMs: Long,
        motionEnabled: Boolean,
        pointerPng: ByteArray
    ): CommandResult = callBound("update runtime cursor bitmap on display", binding) { service ->
        service.updateRuntimeCursorBitmapOnDisplay(
            targetDisplayId,
            visible,
            actionType,
            startX,
            startY,
            endX,
            endY,
            durationMs,
            startedAtMs,
            finishedAtMs,
            motionEnabled,
            pointerPng
        )
    }

    fun runtimeIslandInputSurface(binding: SidecarSessionBinding): SurfaceControl? =
        callBoundValue(binding) { service -> service.runtimeIslandInputSurface }.getOrNull()

    fun updateRuntimeEdgeLight(
        binding: SidecarSessionBinding,
        visible: Boolean,
        density: Float,
        cornerRadii: FloatArray,
        motionEnabled: Boolean
    ): CommandResult = callBound("update runtime edge light", binding) { service ->
        service.updateRuntimeEdgeLight(visible, density, cornerRadii, motionEnabled)
    }

    fun setRuntimeSurfaceSuppressed(
        binding: SidecarSessionBinding,
        suppressed: Boolean
    ): CommandResult = callBound("set runtime surface suppressed", binding) { service ->
        service.setRuntimeSurfaceSuppressed(suppressed)
    }

    fun launchActivity(displayId: Int, component: String, mode: RuntimeMode? = null): CommandResult {
        val comp = component.trim()
        if (comp.isEmpty()) return invalid("launchActivity: component empty")
        return runAmStart(
            command = amStartCommand(displayId, comp, ""),
            mode = mode,
        )
    }

    fun launchActivity(binding: SidecarSessionBinding, component: String): CommandResult {
        val comp = component.trim()
        if (comp.isEmpty()) return invalid("launchActivity: component empty")
        return runBoundAmStart(binding, amStartCommand(binding.displayId, comp, ""))
    }

    fun launchActivityOnDisplay(
        binding: SidecarSessionBinding,
        component: String,
        targetDisplayId: Int = 0,
        intentFlags: Int = DEFAULT_LAUNCH_FLAGS
    ): CommandResult {
        val comp = component.trim()
        if (comp.isEmpty()) return invalid("launchActivityOnDisplay: component empty")
        if (targetDisplayId < 0) return invalid("launchActivityOnDisplay: invalid displayId")
        return runBoundAmStart(binding, amStartCommand(targetDisplayId, comp, "", intentFlags))
    }

    private fun amStartCommand(
        displayId: Int,
        component: String?,
        packageName: String,
        intentFlags: Int = DEFAULT_LAUNCH_FLAGS,
        launcherEntry: Boolean = false
    ): String {
        val flagsHex = "0x${intentFlags.toUInt().toString(16)}"
        if (launcherEntry) return LauncherIntentCommand.build(displayId, intentFlags,
            component, packageName, adbShell::escapeForShell)
        val base = "am start --display $displayId -f $flagsHex"
        if (!component.isNullOrEmpty()) {
            return "$base -n ${adbShell.escapeForShell(component)}"
        }
        // 只有包名时，在 shell 内解析 launcher activity 再启动。
        val resolved = "cmd package resolve-activity --brief " +
            "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
            "${adbShell.escapeForShell(packageName)} | tail -n 1"
        return "$base -n \"\$($resolved)\""
    }

    private fun runAmStart(
        command: String,
        mode: RuntimeMode?,
    ): CommandResult {
        // Only absence before dispatch permits the frozen transport fallback.
        // An uncertain resident result is non-null and must never replay am start.
        val result = execShellCommand(command, mode) ?: when (mode) {
            RuntimeMode.STANDARD -> adbShell.exec(command, AM_START_TIMEOUT_MS)
            null -> return invalid("runtime mode unavailable for am start", channelUnavailable = true)
            else -> return invalid("Unsupported execution mode")
        }
        val out = result.output.trim()
        val lower = out.lowercase()
        val explicitError = lower.contains("error:") ||
            lower.contains("error type") ||
            lower.contains("exception") ||
            lower.contains("does not exist") ||
            lower.contains("permission deni") ||
            lower.contains("unable to resolve")
        val ok = result.success && !explicitError
        return CommandResult(ok = ok, detail = out.ifBlank { if (ok) "am start ok" else "am start failed" }.take(200))
    }

    private fun runBoundAmStart(
        binding: SidecarSessionBinding,
        command: String
    ): CommandResult {
        val result = execShellCommand(binding, command)
            ?: return invalid("bound sidecar shell unavailable", channelUnavailable = true)
        val out = result.output.trim()
        val lower = out.lowercase()
        val explicitError = lower.contains("error:") ||
            lower.contains("error type") ||
            lower.contains("exception") ||
            lower.contains("does not exist") ||
            lower.contains("permission deni") ||
            lower.contains("unable to resolve")
        val ok = result.success && !explicitError
        return CommandResult(
            ok = ok,
            detail = out.ifBlank { if (ok) "am start ok" else "am start failed" }.take(200),
            channelUnavailable = !ok && !result.success
        )
    }

    fun injectNative(displayId: Int, nativeArgs: String, mode: RuntimeMode? = null): CommandResult {
        val parts = nativeArgs.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (displayId <= 0) return invalid("Virtual display required")
        return when (parts.firstOrNull()) {
            "tap" -> if (parts.size >= 3) {
                call("tap", mode) {
                    it.tap(displayId, parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0)
                }
            } else invalid("tap args missing")
            "doubletap" -> if (parts.size >= 3) {
                call("doubletap", mode) {
                    it.doubleTap(displayId, parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0)
                }
            } else invalid("doubletap args missing")
            "longpress" -> if (parts.size >= 4) {
                call("longpress", mode) {
                    it.longPress(
                        displayId,
                        parts[1].toIntOrNull() ?: 0,
                        parts[2].toIntOrNull() ?: 0,
                        parts[3].toIntOrNull() ?: 500
                    )
                }
            } else invalid("longpress args missing")
            "swipe", "drag" -> if (parts.size >= 5) {
                call("swipe", mode) {
                    it.swipe(
                        displayId,
                        parts[1].toIntOrNull() ?: 0,
                        parts[2].toIntOrNull() ?: 0,
                        parts[3].toIntOrNull() ?: 0,
                        parts[4].toIntOrNull() ?: 0,
                        parts.getOrNull(5)?.toIntOrNull() ?: 500
                    )
                }
            } else invalid("swipe args missing")
            else -> invalid("unsupported sidecar input op=${parts.firstOrNull().orEmpty()}")
        }
    }

    /** Executes input only when the exact binder and display frozen for the attempt still match. */
    fun injectNative(binding: SidecarSessionBinding, nativeArgs: String): CommandResult {
        val parts = nativeArgs.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when (parts.firstOrNull()) {
            "tap" -> if (parts.size >= 3) {
                callBound("tap", binding) {
                    it.tap(
                        binding.displayId,
                        parts[1].toIntOrNull() ?: 0,
                        parts[2].toIntOrNull() ?: 0
                    )
                }
            } else invalid("tap args missing")
            "doubletap" -> if (parts.size >= 3) {
                callBound("doubletap", binding) {
                    it.doubleTap(
                        binding.displayId,
                        parts[1].toIntOrNull() ?: 0,
                        parts[2].toIntOrNull() ?: 0
                    )
                }
            } else invalid("doubletap args missing")
            "longpress" -> if (parts.size >= 4) {
                callBound("longpress", binding) {
                    it.longPress(
                        binding.displayId,
                        parts[1].toIntOrNull() ?: 0,
                        parts[2].toIntOrNull() ?: 0,
                        parts[3].toIntOrNull() ?: 500
                    )
                }
            } else invalid("longpress args missing")
            "swipe", "drag" -> if (parts.size >= 5) {
                callBound("swipe", binding) {
                    it.swipe(
                        binding.displayId,
                        parts[1].toIntOrNull() ?: 0,
                        parts[2].toIntOrNull() ?: 0,
                        parts[3].toIntOrNull() ?: 0,
                        parts[4].toIntOrNull() ?: 0,
                        parts.getOrNull(5)?.toIntOrNull() ?: 500
                    )
                }
            } else invalid("swipe args missing")
            else -> invalid("unsupported sidecar input op=${parts.firstOrNull().orEmpty()}")
        }
    }

    fun injectTouch(
        displayId: Int,
        action: Int,
        x: Int,
        y: Int,
        downTimeMs: Long,
        eventTimeMs: Long,
        mode: RuntimeMode
    ): CommandResult = call("touch", mode) { service ->
        service.touch(displayId, action, x, y, downTimeMs, eventTimeMs)
    }

    fun injectTouch(
        binding: SidecarSessionBinding,
        action: Int,
        x: Int,
        y: Int,
        downTimeMs: Long,
        eventTimeMs: Long
    ): CommandResult = callBound("touch", binding) { service ->
        service.touch(binding.displayId, action, x, y, downTimeMs, eventTimeMs)
    }

    fun injectKey(displayId: Int, keycode: Int, mode: RuntimeMode? = null): CommandResult =
        call("key", mode) { service ->
            service.key(displayId, keycode)
        }

    /**
     * 最近一次注入实际走了哪条通道（反射 / input 命令）+ 成败 + 失败原因。
     * 反射失败静默回退 input 命令时对外消息与成功完全一致，只有这条证据能区分两者。
     */
    fun lastInputEvidence(mode: RuntimeMode? = null): String {
        val service = serviceOrNull(mode) ?: return ""
        return callWithTimeout(SIDECAR_PROTOCOL_TIMEOUT_MS, markSidecarSuspectOnTimeout = false) {
            service.lastInputEvidence.orEmpty()
        }.getOrDefault("")
    }

    /**
     * 任务开始时主动唤醒虚拟屏电源组。必须在 onScreenLocked 的 surface 重挂之前调用：
     * 电源组还在 DOZING 时屏上没有窗口在绘，重挂等不到新帧就会把虚拟空间自己判成不可用。
     */
    fun ensureDisplayInteractive(displayId: Int, mode: RuntimeMode?): CommandResult =
        call("ensureDisplayInteractive", mode) { service ->
            service.ensureDisplayInteractive(displayId)
        }

    fun onScreenLocked(mode: RuntimeMode?): CommandResult =
        call("screenLocked", mode) { service -> service.onScreenLocked() }

    fun onScreenUnlocked(mode: RuntimeMode?): CommandResult =
        call("screenUnlocked", mode) { service -> service.onScreenUnlocked() }

    fun injectKey(binding: SidecarSessionBinding, keycode: Int): CommandResult =
        callBound("key", binding) { service ->
            service.key(binding.displayId, keycode)
        }

    fun injectKeyCombo(displayId: Int, keycodes: List<Int>, mode: RuntimeMode? = null): CommandResult =
        call("keyCombo", mode) { service ->
            service.keyCombo(displayId, keycodes.toIntArray())
        }

    fun injectKeyCombo(binding: SidecarSessionBinding, keycodes: List<Int>): CommandResult {
        if (keycodes.isEmpty()) return invalid("keyCombo requires at least one keycode")
        return callBound("keyCombo", binding) { service ->
            service.keyCombo(binding.displayId, keycodes.toIntArray())
        }
    }

    fun injectText(
        displayId: Int,
        text: String,
        clearExisting: Boolean,
        mode: RuntimeMode,
        expectedBackendSessionId: String,
        expectedFocusTarget: String,
        method: com.droidance.core.agent.tools.TextInputMethod
    ): CommandResult {
        val resolvedMode = displayModes[displayId] ?: mode
        if (resolvedMode != mode) return invalid("text display mode changed")
        val service = serviceOrNull(mode)
            ?: return invalid("sidecar binder unavailable for text", channelUnavailable = true)
        if (!matchesExpectedSession(displayId, mode, service, expectedBackendSessionId)) {
            return invalid("text backend session changed", channelUnavailable = true)
        }
        // Text replacement is a compound transaction (focus probe, selection/clear, clipboard
        // verification, paste and destination read-back). It can legitimately outlive the short
        // control-call budget on a slow IME. Keep the operation bounded, but do not evict a live
        // Binder merely because this long-running transaction crossed the control timeout.
        val result = callWithTimeout(
            timeoutMs = SIDECAR_TEXT_CALL_TIMEOUT_MS,
            markSidecarSuspectOnTimeout = false,
            suspectService = service
        ) {
            service.text(displayId, text, clearExisting, expectedFocusTarget, method.wireName)
        }
        return result.fold(
            onSuccess = { bundle ->
                val evidence = TextInputEvidence(
                    focusConfirmed = bundle.getBoolean("focusConfirmed"),
                    payloadConfirmed = bundle.getBoolean("payloadConfirmed"),
                    eventInjected = bundle.getBoolean("eventInjected"),
                    screenChanged = false,
                    deliveryVerified = false,
                    readback = com.droidance.core.agent.tools.TextInputReadback(
                        displayId = bundle.getInt("destinationDisplayId", -1),
                        backendSessionId = expectedBackendSessionId,
                        targetBefore = bundle.getString("targetBefore").orEmpty(),
                        targetAfter = bundle.getString("targetAfter").orEmpty(),
                        payloadHash = bundle.getString("payloadHash").orEmpty(),
                        replacement = bundle.getBoolean("replacement"),
                        textMatched = bundle.getBoolean("destinationTextMatched")
                    ),
                    injectionDurationMs = bundle.getLong("injectionDurationMs"),
                    readbackDurationMs = bundle.getLong("readbackDurationMs")
                )
                val detail = bundle.getString("detail").orEmpty()
                CommandResult(
                    ok = evidence.eventInjected,
                    detail = detail,
                    values = mapOf("outcome" to evidence.outcome.name),
                    textInputEvidence = evidence
                )
            },
            onFailure = { error ->
                val binderUnavailable = !service.asBinder().isBinderAlive || isBinderTransportFailure(error)
                invalid(
                    "text failed: ${error.message.orEmpty()}",
                    channelUnavailable = binderUnavailable
                )
            }
        )
    }

    fun injectText(binding: SidecarSessionBinding, text: String, clearExisting: Boolean): CommandResult {
        val result = callBoundValue(binding) { service ->
            service.text(binding.displayId, text, clearExisting, null, com.droidance.core.agent.tools.TextInputMethod.SHORTCUT.wireName)
        }
        return result.fold(
            onSuccess = { bundle ->
                val evidence = TextInputEvidence(
                    focusConfirmed = bundle.getBoolean("focusConfirmed"),
                    payloadConfirmed = bundle.getBoolean("payloadConfirmed"),
                    eventInjected = bundle.getBoolean("eventInjected"),
                    screenChanged = false,
                    deliveryVerified = false
                )
                CommandResult(
                    ok = evidence.eventInjected,
                    detail = bundle.getString("detail").orEmpty(),
                    values = mapOf("outcome" to evidence.outcome.name),
                    textInputEvidence = evidence
                )
            },
            onFailure = { error ->
                invalid("bound text failed: ${error.message.orEmpty()}", channelUnavailable = true)
            }
        )
    }

    fun getForegroundComponent(
        displayId: Int,
        mode: RuntimeMode,
        expectedBackendSessionId: String
    ): String? =
        callForDisplay(displayId, mode, expectedBackendSessionId) { service ->
            service.getForegroundComponent(displayId)
        }?.trim()?.takeIf { it.isNotBlank() }

    fun getInputFocusState(
        displayId: Int,
        mode: RuntimeMode,
        expectedBackendSessionId: String
    ): String? =
        callForDisplay(displayId, mode, expectedBackendSessionId) { service ->
            service.getInputFocusState(displayId)
        }?.trim()?.takeIf { it.isNotBlank() }

    /**
     * 动作后的短探针只补充输入焦点证据；超时代表证据未知，不应把正常的常驻 sidecar 判死。
     */
    suspend fun awaitInputFocus(
        displayId: Int, mode: RuntimeMode, expectedBackendSessionId: String, timeoutMs: Long
    ): InputFocusReadiness {
        val started = android.os.SystemClock.elapsedRealtime()
        val service = serviceOrNull(mode)
            ?: return InputFocusReadiness(null, "INPUT_BACKEND_UNAVAILABLE")
        if ((displayModes[displayId] ?: mode) != mode ||
            !matchesExpectedSession(displayId, mode, service, expectedBackendSessionId))
            return InputFocusReadiness(null, "INPUT_BACKEND_SESSION_CHANGED")
        val token = java.util.UUID.randomUUID().toString()
        val query = try {
            kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                callWithTimeout(timeoutMs.coerceIn(1, 5000) + 250,
                    markSidecarSuspectOnTimeout = false, suspectService = service) {
                    service.awaitInputFocus(displayId, timeoutMs, token)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            runCatching { service.cancelInputFocus(token) }
            throw cancelled
        }
        val result = query.getOrNull()
        if (result == null) runCatching { service.cancelInputFocus(token) }
        if (!matchesExpectedSession(displayId, mode, service, expectedBackendSessionId))
            return InputFocusReadiness(null, "INPUT_BACKEND_SESSION_CHANGED")
        val failure = query.exceptionOrNull()
        val diagnostic = result?.getString("diagnostic") ?: when {
            failure is TimeoutException -> "INPUT_FOCUS_BINDER_TIMEOUT"
            failure != null -> "INPUT_FOCUS_QUERY_FAILED"
            else -> "INPUT_FOCUS_EMPTY_RESPONSE"
        }
        return InputFocusReadiness(result?.getString("target"), diagnostic,
            android.os.SystemClock.elapsedRealtime() - started,
            queryExitCode = result?.takeIf { it.containsKey("queryExitCode") }?.getInt("queryExitCode"),
            queryDiagnosticCode = result?.getString("queryDiagnosticCode"),
            queryExecutionStatus = result?.getString("queryExecutionStatus"),
            queryStderr = result?.getString("queryStderr"),
            queryAttempts = result?.getInt("queryAttempts", 0) ?: 0,
            queryOutputTruncated = result?.getBoolean("queryOutputTruncated", false) ?: false,
            binderFailure = failure?.javaClass?.simpleName,
            queryOriginalChars = result?.getLong("queryOriginalChars", 0) ?: 0,
            querySelectedChars = result?.getLong("querySelectedChars", 0) ?: 0,
            requestedDisplayId = result?.takeIf { it.containsKey("requestedDisplayId") }?.getInt("requestedDisplayId"),
            targetKind = result?.getString("targetKind"), focusedWindowName = result?.getString("focusedWindowName"),
            queryCapturedElapsedMs = result?.takeIf { it.containsKey("queryCapturedElapsedMs") }?.getLong("queryCapturedElapsedMs"))
    }

    fun backendSessionId(displayId: Int, mode: RuntimeMode): String {
        val service = serviceOrNull(mode)
        return backendSessionId(displayId, mode, service)
    }

    private fun matchesExpectedSession(
        displayId: Int,
        mode: RuntimeMode,
        service: IVirtualSpaceSidecar,
        expectedSessionId: String?
    ): Boolean {
        if (mode != RuntimeMode.STANDARD || displayId <= 0) return false
        if (expectedSessionId == null) return true
        return SidecarSessionBinding(mode, displayId, expectedSessionId).matches(
            mode,
            displayId,
            backendSessionId(displayId, mode, service)
        )
    }

    /**
     * Resolves the single resident sidecar identity for either display 0 or its active virtual
     * display. Device execution freezes this Binder identity before the model round is committed.
     */
    fun runtimeSurfaceBinding(displayId: Int, mode: RuntimeMode): SidecarSessionBinding? {
        if (displayId < 0) return null
        val recordedMode = displayModes[displayId]
        if (displayId != 0 && recordedMode != null && recordedMode != mode) return null
        val service = serviceOrNull(mode)
            ?: return null
        return SidecarSessionBinding(
            runtimeMode = mode,
            displayId = displayId,
            backendSessionId = backendSessionId(displayId, mode, service)
        )
    }

    fun runtimeIslandSurface(binding: SidecarSessionBinding): SurfaceControl? =
        callBoundValue(binding, markSidecarSuspectOnFailure = false) { service ->
            service.runtimeIslandSurface
        }.getOrNull()

    fun isBoundSessionAlive(binding: SidecarSessionBinding): Boolean =
        binding.runtimeMode == RuntimeMode.STANDARD && binding.displayId > 0 && callBoundValue(binding, markSidecarSuspectOnFailure = true) { service ->
            service.getStatus()
            binding.displayId == 0 || service.displayId == binding.displayId
        }.getOrDefault(false)



    fun inspectDisplayContent(binding: SidecarSessionBinding): VirtualDisplayContentState =
        callBoundValue(binding, markSidecarSuspectOnFailure = false) { service ->
            val result = service.inspectDisplayContent(binding.displayId)
                ?: return@callBoundValue VirtualDisplayContentState.UNAVAILABLE
            VirtualDisplayContentState.fromFacts(
                requestedDisplayId = binding.displayId,
                displayId = result.getInt("displayId", -1),
                displayValid = result.getBoolean("displayValid", false) && service.displayId == binding.displayId,
                querySucceeded = result.getBoolean("querySucceeded", false),
                activityCount = result.takeIf { it.containsKey("activityCount") }?.getInt("activityCount")
            )
        }.getOrDefault(VirtualDisplayContentState.UNAVAILABLE)

    fun captureLatestPng(
        displayId: Int,
        timeoutMs: Long = frameInspector.sidecarCaptureTimeoutMs(),
        markSidecarSuspectOnTimeout: Boolean = false,
        mode: RuntimeMode? = null,
        expectedBackendSessionId: String? = null
    ): ByteArray? {
        val resolvedMode = mode ?: displayModes[displayId] ?: return null
        val service = serviceOrNull(resolvedMode)
            ?: return null
        if (!matchesExpectedSession(displayId, resolvedMode, service, expectedBackendSessionId)) {
            return null
        }
        val boundedTimeoutMs = timeoutMs.coerceIn(1L, frameInspector.sidecarCaptureTimeoutMs())
        return callWithTimeout(
            timeoutMs = boundedTimeoutMs,
            markSidecarSuspectOnTimeout = markSidecarSuspectOnTimeout,
            suspectService = service
        ) { service.capturePreviewJpeg(displayId) }
            .getOrNull()
            ?.let { decodeTransferJpeg(it, displayId) }
    }

    fun captureLatestPngFrame(
        displayId: Int,
        timeoutMs: Long = frameInspector.sidecarCaptureTimeoutMs(),
        mode: RuntimeMode? = null,
        expectedBackendSessionId: String? = null
    ): FrameSnapshot? {
        val resolvedMode = mode ?: displayModes[displayId] ?: return null
        val service = serviceOrNull(resolvedMode)
            ?: return null
        if (!matchesExpectedSession(displayId, resolvedMode, service, expectedBackendSessionId)) {
            return null
        }
        val frame = callWithTimeout(
            timeoutMs = timeoutMs.coerceIn(1L, frameInspector.sidecarCaptureTimeoutMs()),
            markSidecarSuspectOnTimeout = false,
            suspectService = service
        ) { service.capturePreviewFrame(displayId) }.getOrNull() ?: return null
        val jpeg = frame.getByteArray("jpeg") ?: return null
        val png = decodeTransferJpeg(jpeg, displayId) ?: return null
        val producedAtMs = frame.getLong("frameAtMs")
        if (producedAtMs <= 0L) return null
        return FrameSnapshot(png = png, producedAtMs = producedAtMs)
    }

    fun captureLatestPreviewJpeg(
        displayId: Int,
        timeoutMs: Long = frameInspector.sidecarCaptureTimeoutMs(),
        mode: RuntimeMode? = null,
        expectedBackendSessionId: String? = null
    ): ByteArray? {
        val resolvedMode = mode ?: displayModes[displayId] ?: return null
        val service = serviceOrNull(resolvedMode) ?: return null
        if (
            expectedBackendSessionId != null &&
            backendSessionId(displayId, resolvedMode, service) != expectedBackendSessionId
        ) {
            return null
        }
        return callWithTimeout(
            timeoutMs = timeoutMs.coerceIn(1L, frameInspector.sidecarCaptureTimeoutMs()),
            markSidecarSuspectOnTimeout = false,
            suspectService = service
        ) { service.capturePreviewJpeg(displayId) }
            .getOrNull()
            ?.takeIf { it.looksLikeJpeg() }
    }

    fun attachViewerMirror(
        binding: SidecarSessionBinding,
        parent: android.view.SurfaceControl,
        width: Int,
        height: Int,
        owner: String,
        lifetime: IBinder
    ): CommandResult = callBound("attach viewer mirror", binding) {
        it.attachViewerMirror(binding.displayId, parent, width, height, owner, lifetime)
    }

    fun detachViewerMirror(binding: SidecarSessionBinding, owner: String): CommandResult =
        callBound("detach viewer mirror", binding) { it.detachViewerMirror(binding.displayId, owner) }

    fun setViewerImeEnabled(binding: SidecarSessionBinding, enabled: Boolean, owner: String, lifetime: IBinder): CommandResult =
        callBound("set viewer IME policy", binding) { it.setViewerImeEnabled(binding.displayId, enabled, owner, lifetime) }

    fun setExecutionImeEnabled(binding: SidecarSessionBinding, enabled: Boolean, owner: String, lifetime: IBinder): CommandResult =
        callBound("set execution IME policy", binding) { it.setExecutionImeEnabled(binding.displayId, enabled, owner, lifetime) }

    fun viewerImeBottomInset(binding: SidecarSessionBinding): Int? =
        callBoundValue(binding, markSidecarSuspectOnFailure = false) { it.getViewerImeBottomInset(binding.displayId) }
            .getOrNull()?.takeIf { it >= 0 }

    fun endViewerInput(binding: SidecarSessionBinding): CommandResult =
        callBound("end viewer input", binding) { it.endViewerInput(binding.displayId) }

    fun configureDisplayInsets(binding: SidecarSessionBinding, geometry: android.os.Bundle): CommandResult =
        callBoundValue(binding) { service ->
            val result = service.configureDisplayInsets(binding.displayId, geometry)
            CommandResult(result?.getBoolean("ok") == true, result?.getString("detail").orEmpty())
        }.getOrElse { invalid(it.message ?: "display insets unavailable") }

    fun samplePixels(binding: SidecarSessionBinding, points: List<Pair<Int, Int>>): FreshPixelSample? {
        val coordinates = points.flatMap { listOf(it.first, it.second) }.toIntArray()
        val sample = callBoundValue(binding, markSidecarSuspectOnFailure = false) {
            it.captureFreshSample(binding.displayId, coordinates)
        }.getOrNull() ?: return null
        if (!isBoundSessionAlive(binding)) return null
        val colors = sample.getIntArray("colors")?.takeIf { it.size == points.size } ?: return null
        return FreshPixelSample(colors, sample.getInt("width"), sample.getInt("height"),
            sample.getInt("displayId"), sample.getLong("capturedAtMs"), sample.getLong("capturedElapsedMs"),
            binding.backendSessionId, sample.getInt("rotation"))
    }

    fun sampleFrame(binding: SidecarSessionBinding): com.droidance.core.device.tools.VisualFrameSampler.Frame? {
        val sample = callBoundValue(binding, markSidecarSuspectOnFailure = false) { service ->
            service.sampleFrame(binding.displayId)
        }.getOrNull() ?: return null
        val pixels = sample.getByteArray("pixels")?.takeIf { it.size == 64 * 64 } ?: return null
        if (!sample.getBoolean("freshSnapshot", false)) return null
        return com.droidance.core.device.tools.VisualFrameSampler.Frame(pixels, sample.getLong("frameId"))
    }

    /** Captures only from the exact sidecar binder selected for the committed attempt. */
    fun capturePng(binding: SidecarSessionBinding): ByteArray? =
        callBoundValue(binding, markSidecarSuspectOnFailure = false) { service ->
            service.capturePreviewJpeg(binding.displayId)
        }
            .getOrNull()
            ?.let { bytes ->
                decodeTransferJpeg(bytes, binding.displayId)
            }

    @Volatile private var frameFailure: Pair<SidecarSessionBinding, String>? = null

    fun captureFailureDetail(binding: SidecarSessionBinding): String =
        frameFailure?.takeIf { it.first == binding }?.second.orEmpty()

    fun captureFrame(binding: SidecarSessionBinding, forceFresh: Boolean, sampleSide: Int): FrameSnapshot? {
        if (binding.displayId == 0) return capturePng(binding)?.let {
            FrameSnapshot(it, System.currentTimeMillis())
        }
        // Consume and close inside the timed worker. A late Binder reply after cancellation
        // must not leave a descriptor stranded in an abandoned Future result.
        val result = callBoundValue(binding, markSidecarSuspectOnFailure = false) { service ->
            val captured = service.captureFreshFrame(binding.displayId, sampleSide)
                ?: throw IllegalStateException(service.lastError.ifBlank { "No fresh frame returned" })
            captured.use { frame ->
                if (Thread.currentThread().isInterrupted) return@use null
                val facts = frame.metadata
                val producedAtMs = facts.getLong("frameAtMs")
                if (producedAtMs <= 0) return@use null
                val rawSize = facts.getInt("referenceWidth") to facts.getInt("referenceHeight")
                if (rawSize.first <= 0 || rawSize.second <= 0 ||
                    displaySizes[binding.displayId]?.let { it != rawSize } == true) return@use null
                val colors = facts.getIntArray("referenceColors") ?: return@use null
                val coordinates = facts.getIntArray("referenceCoordinates") ?: return@use null
                if (colors.size != sampleSide * sampleSide || coordinates.size != colors.size * 2) return@use null
                val reference = com.droidance.core.agent.tools.ObservationPixelReference(colors.indices.map { index ->
                    val x = coordinates[index * 2]; val y = coordinates[index * 2 + 1]
                    com.droidance.core.agent.tools.ObservationMappedSample(x, y, x, y)
                }, colors)
                val readStarted = android.os.SystemClock.elapsedRealtime()
                val png = frame.readEncodedImage()
                if (Thread.currentThread().isInterrupted) return@use null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
                if (bounds.outWidth != rawSize.first || bounds.outHeight != rawSize.second ||
                    bounds.outMimeType !in setOf("image/jpeg", "image/png")) return@use null
                FrameSnapshot(png, producedAtMs, facts.getString("foregroundComponent"), reference, buildMap {
                    put("observation_transfer_read_ms", android.os.SystemClock.elapsedRealtime() - readStarted)
                    put("observation_native_png_bytes", png.size.toLong())
                    put("observation_native_width", rawSize.first.toLong())
                    put("observation_native_height", rawSize.second.toLong())
                    listOf("foregroundMs" to "observation_foreground_ms", "captureMs" to "observation_device_capture_ms",
                        "encodeMs" to "observation_device_encode_ms", "transferMs" to "observation_device_transfer_ms")
                        .forEach { (field, metric) -> if (facts.containsKey(field)) put(metric, facts.getLong(field)) }
                })
            }
        }
        frameFailure = binding to (result.exceptionOrNull()?.let {
            "${it.javaClass.simpleName}: ${it.message.orEmpty().take(500)}"
        } ?: if (result.getOrNull() == null) "Frame metadata or image validation failed" else "")
        return result.getOrNull()
    }

    /**
     * 主屏截图优先由 sidecar 通过 WindowManager Binder 直取 HardwareBuffer；直取失败时 sidecar
     * 内部才调用 screencap。binder 不可用或截图失败返回 null，由 SidecarScreenCapture 继续兜底。
     */


    /**
     * 本机(sidecar 进程内 fork sh -c)执行一条 shell 命令，不经 ADB/AIR，与 WiFi 无关。用于
     * am start 这类必须走真正 shell 命令的操作（sidecar 进程内 startActivity 会被 AMS 拒绝，
     * 见下方 runAmStart 处的说明）。仅派发前 sidecar 不可用返回 null。
     * Never replay an uncertain dispatched command or substitute a different shell session.
     */
    fun execShellCommand(
        command: String,
        mode: RuntimeMode? = null,
        timeoutMs: Long = frameInspector.sidecarCallTimeoutMs()
    ): AdbShellExecutor.Result? {
        val service = serviceOrNull(mode) ?: return null
        val token = java.util.UUID.randomUUID().toString()
        val encoded = callWithTimeout(timeoutMs.coerceIn(1, 120000) + 2000, suspectService = service) {
            service.execShellCommand(command, timeoutMs.coerceIn(1, 120000), token)
        }.getOrNull()
        if (encoded == null) runCatching { service.cancelShellCommand(token) }
        return encoded?.let(::decodeShellResult)
            ?: AdbShellExecutor.Result("", "", -1, com.droidance.core.agent.event.ToolExecutionStatus.INTERRUPTED, "SHELL_DELIVERY_UNKNOWN")
    }

    /** Uses the committed sidecar, propagates timeout, and revokes uncertain calls without replay. */
    fun execShellCommand(
        binding: SidecarSessionBinding,
        command: String,
        timeoutMs: Long = frameInspector.sidecarCallTimeoutMs()
    ): AdbShellExecutor.Result? {
        val token = java.util.UUID.randomUUID().toString()
        var dispatched: IVirtualSpaceSidecar? = null
        val encoded = callBoundValue(binding, timeoutMs.coerceIn(1, 120000) + 2000) { service ->
            dispatched = service
            service.execShellCommand(command, timeoutMs.coerceIn(1, 120000), token)
        }.getOrNull()
        if (encoded == null) runCatching { dispatched?.cancelShellCommand(token) }
        if (encoded == null && dispatched == null) return null
        return encoded?.let(::decodeShellResult)
            ?: AdbShellExecutor.Result("", "", -1, com.droidance.core.agent.event.ToolExecutionStatus.INTERRUPTED, "SHELL_DELIVERY_UNKNOWN")
    }

    /** Selects a typed native query before IPC truncation on the exact committed session. */
    fun querySystem(binding: SidecarSessionBinding, request: SystemStateQuery): SystemQueryOutcome? {
        val token = java.util.UUID.randomUUID().toString()
        var dispatched: IVirtualSpaceSidecar? = null
        val reply = callBoundValue(binding, 12_000) { service ->
            dispatched = service
            service.querySystem(request.service, request.kind.name, request.lineFilter, request.contextLines, token)
        }.getOrNull()
        if (reply == null) runCatching { dispatched?.cancelShellCommand(token) }
        val empty = SystemQueryOutput.Result("", 0, 0, 0, 0, false, null)
        if (reply == null) return SystemQueryOutcome("bound_sidecar", -1, empty,
            "SYSTEM_QUERY_DELIVERY_UNKNOWN", "SYSTEM_QUERY_DELIVERY_UNKNOWN")
        val exitCode = reply.getInt("exit_code", -1)
        val stderr = reply.getString("stderr").orEmpty()
        val keys = listOf("stdout", "original_chars", "selected_chars", "original_lines", "selected_lines", "truncated")
        if (!keys.all(reply::containsKey)) return SystemQueryOutcome("bound_sidecar", -1, empty, stderr,
            if (exitCode == 0) "SYSTEM_QUERY_READ_FAILED" else "SYSTEM_QUERY_FAILED")
        return SystemQueryOutcome("bound_sidecar", exitCode, SystemQueryOutput.Result(
            reply.getString("stdout").orEmpty(), reply.getLong("original_chars"), reply.getLong("selected_chars"),
            reply.getInt("original_lines"), reply.getInt("selected_lines"), reply.getBoolean("truncated"),
            reply.getString("diagnostic_code")), stderr)
    }

    /** Clipboard scope belongs to the reader's user/device; display only identifies this execution. */
    fun readClipboard(binding: SidecarSessionBinding): ToolExecutionResult {
        var dispatched = false
        val attempt = callBoundValue(binding) { service ->
            dispatched = true
            service.readClipboard()
        }
        val error = attempt.exceptionOrNull()
        if (error is CancellationException) throw error
        val reply = attempt.getOrNull()
        val requiredFacts = listOf("reader_uid", "reader_user_id", "clipboard_requested_device_id",
            "captured_at_ms", "captured_elapsed_ms", "item_count", "items")
        val diagnostic = when {
            reply != null -> reply.getString("diagnostic_code")
                ?: if (reply.getString("status") != "readable" || !requiredFacts.all(reply::containsKey))
                    "CLIPBOARD_READ_FAILED" else null
            dispatched -> "CLIPBOARD_READ_DELIVERY_UNKNOWN"
            else -> "BACKEND_SESSION_MISMATCH"
        }
        val facts = buildJsonObject {
            put("source_kind", JsonPrimitive("android_clipboard"))
            put("scope", JsonPrimitive("reader_user_clipboard"))
            put("backend", JsonPrimitive(if (dispatched) "bound_sidecar" else "none"))
            put("execution_display_id", JsonPrimitive(binding.displayId))
            put("status", JsonPrimitive(reply?.getString("status") ?: "unknown"))
            for (key in listOf("reader_uid", "reader_user_id", "clipboard_requested_device_id", "item_count")) {
                put(key, if (reply?.containsKey(key) == true) JsonPrimitive(reply.getInt(key)) else JsonNull)
            }
            for (key in listOf("captured_at_ms", "captured_elapsed_ms", "clip_timestamp_ms")) {
                put(key, if (reply?.containsKey(key) == true) JsonPrimitive(reply.getLong(key)) else JsonNull)
            }
            put("label", reply?.getString("label")?.let(::JsonPrimitive) ?: JsonNull)
            put("mime_types", reply?.getStringArrayList("mime_types")?.let { types ->
                JsonArray(types.map(::JsonPrimitive))
            } ?: JsonNull)
            @Suppress("DEPRECATION")
            val items = reply?.getParcelableArrayList<android.os.Bundle>("items")
            put("items", items?.let { entries -> JsonArray(entries.map { item ->
                buildJsonObject {
                    put("index", JsonPrimitive(item.getInt("index")))
                    put("text", item.getString("text")?.let(::JsonPrimitive) ?: JsonNull)
                    put("has_uri", JsonPrimitive(item.getBoolean("has_uri")))
                    put("has_intent", JsonPrimitive(item.getBoolean("has_intent")))
                }
            }) } ?: JsonNull)
            diagnostic?.let { put("diagnostic_code", JsonPrimitive(it)) }
            error?.let { put("error_type", JsonPrimitive(it.javaClass.simpleName)) }
        }
        val status = when {
            !dispatched -> ToolExecutionStatus.NOT_EXECUTED
            reply == null -> ToolExecutionStatus.INTERRUPTED
            diagnostic != null -> ToolExecutionStatus.FAILED
            else -> ToolExecutionStatus.SUCCEEDED
        }
        return ToolExecutionResult(facts.toString(), status,
            if (status == ToolExecutionStatus.SUCCEEDED) ToolEvidenceStatus.NOT_REQUIRED else ToolEvidenceStatus.UNKNOWN,
            diagnosticCode = diagnostic, data = facts)
    }

    private fun decodeShellResult(reply: android.os.Bundle): AdbShellExecutor.Result? {
        if (!reply.containsKey("exit_code")) return null
        val status = runCatching { com.droidance.core.agent.event.ToolExecutionStatus.valueOf(
            reply.getString("execution_status").orEmpty()) }.getOrNull() ?: return null
        return AdbShellExecutor.Result(reply.getString("stdout").orEmpty(), reply.getString("stderr").orEmpty(),
            reply.getInt("exit_code"), status, reply.getString("diagnostic_code"),
            reply.getBoolean("stdout_truncated"), reply.getBoolean("stderr_truncated"),
            reply.getLong("stdout_bytes"), reply.getLong("stderr_bytes"),
            reply.getInt("uid").takeIf { reply.containsKey("uid") })
    }

    fun shutdown(mode: RuntimeMode): CommandResult {
        val service = serviceOrNull(mode) ?: return invalid("sidecar binder unavailable")
        val result = callWithTimeout(frameInspector.sidecarCallTimeoutMs(), suspectService = service) {
            val ok = service.shutdown()
            CommandResult(ok = ok, detail = if (ok) "shutdown" else service.lastError.orEmpty())
        }
        return result.getOrElse { e -> invalid(e.message ?: "sidecar shutdown failed") }
            .also { commandResult ->
                if (commandResult.ok) {
                    displayModes.entries
                        .filter { it.value == mode }
                        .map { it.key }
                        .forEach { displayId ->
                            displayModes.remove(displayId)
                            displaySizes.remove(displayId)
                        }
                }
            }
    }

    private fun startService(
        service: IVirtualSpaceSidecar,
        width: Int,
        height: Int,
        densityDpi: Int,
        backend: String,
        mode: RuntimeMode
    ): CommandResult {
        val result = callWithTimeout(SIDECAR_START_TIMEOUT_MS, suspectService = service) {
            val ok = service.start(width, height, densityDpi)
            val displayId = service.displayId
            if (!ok || displayId < 0) {
                invalid(service.lastError.ifBlank { "sidecar start failed" })
            } else {
                directServices[directServiceKey(service)] = service
                displayModes[displayId] = mode
                displaySizes[displayId] = width to height
                CommandResult(
                    ok = true,
                    detail = service.status,
                    values = mapOf(
                        "displayId" to displayId.toString(),
                        "uid" to service.uid.toString(),
                        "backend" to backend
                    )
                )
            }
        }
        // 失败原因必须带够：异常类名 + 消息 + sidecar 端 lastError。
        // 只报 "sidecar start exception" 时，createVirtualDisplay 的参数校验异常（例如
        // ALWAYS_UNLOCKED 缺了 OWN_DISPLAY_GROUP）在 debug 包里完全看不出来。
        return result.getOrElse { e ->
            val sidecarError = runCatching { service.lastError }.getOrNull().orEmpty()
            invalid(
                listOfNotNull(
                    "sidecar start ${e.javaClass.simpleName}",
                    e.message?.takeIf { it.isNotBlank() },
                    sidecarError.takeIf { it.isNotBlank() }
                ).joinToString(": "),
                channelUnavailable = isBinderTransportFailure(e)
            )
        }
    }

    private fun call(
        op: String,
        mode: RuntimeMode? = null,
        action: (IVirtualSpaceSidecar) -> Boolean
    ): CommandResult {
        val service = serviceOrNull(mode)
            ?: return invalid("sidecar binder unavailable for $op", channelUnavailable = true)
        val result = callWithTimeout(frameInspector.sidecarCallTimeoutMs(), suspectService = service) { action(service) }
        return result.fold(
            onSuccess = { ok ->
                CommandResult(ok = ok, detail = if (ok) "$op ok" else runCatching { service.lastError }.getOrDefault(""))
            },
            onFailure = { e -> invalid("$op failed: ${e.message.orEmpty()}", channelUnavailable = true) }
        )
    }

    private fun callBound(
        op: String,
        binding: SidecarSessionBinding,
        action: (IVirtualSpaceSidecar) -> Boolean
    ): CommandResult =
        callBoundValue(binding) { service ->
            val ok = action(service)
            ok to if (ok) {
                ""
            } else {
                runCatching { service.lastError.ifBlank { "sidecar rejected $op" } }
                    .getOrDefault("sidecar rejected $op")
            }
        }.fold(
            onSuccess = { (ok, detail) ->
                CommandResult(
                    ok = ok,
                    detail = if (ok) "$op ok" else detail.ifBlank { "sidecar rejected $op" }
                )
            },
            onFailure = { error ->
                invalid("bound $op failed: ${error.message.orEmpty()}", channelUnavailable = true)
            }
        )

    /**
     * Resolves a service in the frozen mode, compares the frozen identity while holding that
     * service reference, and uses that same reference for the Binder transaction. A replacement
     * binder therefore cannot become an implicit fallback between verification and execution.
     */
    private fun <T> callBoundValue(
        binding: SidecarSessionBinding,
        timeoutMs: Long = frameInspector.sidecarCallTimeoutMs(),
        markSidecarSuspectOnFailure: Boolean = true,
        action: (IVirtualSpaceSidecar) -> T
    ): Result<T> {
        // displayModes 只是本地簿记；display 重建/模式切换期间它可能短暂缺失（null），
        // 此时放行，交给下面的 serviceOrNull + backendSessionId 冻结身份校验兜底；
        // 只有记录明确属于另一模式才 fail-closed 拦截，避免运行岛动画被误拦持续刷屏。
        val recordedMode = displayModes[binding.displayId]
        if (binding.displayId != 0 && recordedMode != null && recordedMode != binding.runtimeMode) {
            return Result.failure(IllegalStateException("bound display mode changed"))
        }
        val service = serviceOrNull(
            binding.runtimeMode,
        )
            ?: return Result.failure(IllegalStateException("bound sidecar binder unavailable"))
        if (!matchesExpectedSession(binding.displayId, binding.runtimeMode, service, binding.backendSessionId)) {
            return Result.failure(IllegalStateException("bound sidecar session changed"))
        }
        return callWithTimeout(
            timeoutMs = timeoutMs,
            markSidecarSuspectOnTimeout = markSidecarSuspectOnFailure,
            suspectService = service
        ) {
            action(service)
        }
    }

    /**
     * PNG 帧不能直接走 Binder：真机 1080x2376 的全尺寸 PNG 可超过 2MB，而 Binder 单次事务约
     * 1MB。sidecar 已有受控 JPEG 预览事务；在 app 进程内还原到实际 display 尺寸后继续以 PNG
     * 向上层提供，既保留坐标物理尺寸，又不让大帧传输把控制 Binder 误判成死亡。
     */
    private fun decodeTransferJpeg(bytes: ByteArray, displayId: Int): ByteArray? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val expected = displaySizes[displayId]
        val bitmap = if (expected != null && (source.width != expected.first || source.height != expected.second)) {
            Bitmap.createScaledBitmap(source, expected.first, expected.second, true).also { source.recycle() }
        } else {
            source
        }
        return try {
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun <T> callForDisplay(
        displayId: Int,
        mode: RuntimeMode,
        expectedBackendSessionId: String,
        action: (IVirtualSpaceSidecar) -> T
    ): T? {
        val resolvedMode = displayModes[displayId] ?: mode
        if (resolvedMode != mode) return null
        val service = serviceOrNull(mode)
            ?: return null
        if (!matchesExpectedSession(displayId, mode, service, expectedBackendSessionId)) return null
        return callWithTimeout(frameInspector.sidecarCallTimeoutMs(), suspectService = service) {
            action(service)
        }.getOrNull()
    }

    private fun backendSessionId(
        displayId: Int,
        mode: RuntimeMode,
        service: IVirtualSpaceSidecar?
    ): String {
        val binderIdentity = service?.let {
            runtimeIdentities.getOrPut(it.asBinder()) { it.runtimeIdentity }
        } ?: error("A resident Binder is required for an execution binding")
        return "${mode.name.lowercase()}:$displayId:$binderIdentity"
    }

    private fun serviceOrNull(
        mode: RuntimeMode?,
    ): IVirtualSpaceSidecar? {
        val modes = mode?.let(::listOf) ?: listOf(RuntimeMode.STANDARD)
        for (candidateMode in modes) {
            val service = rawServiceForMode(candidateMode) ?: continue
            if (isProtocolCompatible(service)) return service
        }
        return null
    }

    private fun rawServiceOrNull(
        mode: RuntimeMode?,
    ): IVirtualSpaceSidecar? {
        val modes = mode?.let(::listOf) ?: listOf(RuntimeMode.STANDARD)
        for (candidateMode in modes) {
            rawServiceForMode(candidateMode)?.let { return it }
        }
        return null
    }

    private fun rawServiceForMode(
        mode: RuntimeMode,
    ): IVirtualSpaceSidecar? {
        if (mode != RuntimeMode.STANDARD) return null
        val uid = shellUid
        val key = uid
        directServices[key]
            ?.takeIf {
                it.asBinder().isBinderAlive
            }
            ?.let { return it }
        directServices.remove(key)
        return getRegisteredService(mode)?.also {
            directServices[uid] = it
        }
    }

    private fun directServiceKey(service: IVirtualSpaceSidecar): Int = service.uid

    private fun isProtocolCompatible(service: IVirtualSpaceSidecar): Boolean {
        val binder = service.asBinder()
        if (protocolCompatibility[binder] == true) return true
        val compatible = callWithTimeout(
            timeoutMs = SIDECAR_PROTOCOL_TIMEOUT_MS,
            markSidecarSuspectOnTimeout = false,
            suspectService = service
        ) {
            service.protocolVersion == SIDECAR_PROTOCOL_VERSION &&
                service.codeSourcePath == context.applicationInfo.sourceDir
        }.getOrDefault(false)
        if (compatible) protocolCompatibility[binder] = true
        return compatible
    }

    private fun replaceIncompatibleResidentSidecar(
        mode: RuntimeMode,
        width: Int,
        height: Int,
        densityDpi: Int,
    ) {
        val service = rawServiceOrNull(mode) ?: return
        if (isProtocolCompatible(service)) return
        if (!shellRunner.isAvailable(ShellIdentity.SHELL) &&
            relaunchThroughSameProtocolSidecar(service, width, height, densityDpi)) return
        val binder = service.asBinder()
        val stopped = terminateResidentSidecarProcesses(
            mode,
            force = false,
            currentProtocolOnly = false
        ) || terminateResidentSidecarProcesses(
            mode,
            force = true,
            currentProtocolOnly = false
        )
        clearStoppedSidecar(binder)
        if (!stopped) {
            val remainingPids = residentSidecarPids(mode, currentProtocolOnly = false)
            lastSidecarStartDiagnostic = when (remainingPids) {
                null -> "stale sidecar cleanup unavailable; launching isolated protocol process"
                else -> "stale sidecar isolated: remaining pids=${remainingPids.joinToString(",")}"
            }
        }
    }

    /**
     * An APK update leaves the resident sidecar running the previous code source. Its protocol is
     * unchanged, so without the AIR shell it can launch its own replacement under the same shell
     * identity; the new process retires its stale siblings when it starts. Returns true only once
     * a sidecar from the installed APK has registered its Binder.
     */
    private fun relaunchThroughSameProtocolSidecar(
        stale: IVirtualSpaceSidecar,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Boolean {
        val sameProtocol = callWithTimeout(
            timeoutMs = SIDECAR_PROTOCOL_TIMEOUT_MS,
            markSidecarSuspectOnTimeout = false,
            suspectService = stale
        ) { stale.protocolVersion == SIDECAR_PROTOCOL_VERSION }.getOrDefault(false)
        if (!sameProtocol) return false
        val token = java.util.UUID.randomUUID().toString()
        // The replacement may terminate this process before the reply, so the reply is not evidence.
        callWithTimeout(
            timeoutMs = ADB_START_TIMEOUT_MS + 2_000L,
            markSidecarSuspectOnTimeout = false,
            suspectService = stale
        ) { stale.execShellCommand(buildAppProcessCommand(width, height, densityDpi), ADB_START_TIMEOUT_MS, token) }
        repeat(ADB_BIND_RETRIES) {
            Thread.sleep(BIND_POLL_MS)
            val service = getRegisteredService(RuntimeMode.STANDARD)
            if (service != null && isProtocolCompatible(service)) {
                directServices.clear()
                lastSidecarStartDiagnostic = "stale sidecar replaced through its own shell"
                return true
            }
        }
        lastSidecarStartDiagnostic = "stale sidecar relaunch not registered; sidecar-log=[${readSidecarLog()}]"
        return false
    }

    private fun stopResidentSidecarProcess(
        mode: RuntimeMode,
        service: IVirtualSpaceSidecar?,
        allowGracefulDestroy: Boolean = service?.let(::isProtocolCompatible) == true,
        currentProtocolOnly: Boolean = true,
    ): Boolean {
        val binder = service?.asBinder()
        if (allowGracefulDestroy && service != null && binder != null) {
            callWithTimeout(
                timeoutMs = SIDECAR_PROTOCOL_TIMEOUT_MS,
                markSidecarSuspectOnTimeout = false,
                suspectService = service
            ) { service.destroy() }
            if (waitForResidentProcessExit(
                    mode,
                    GRACEFUL_EXIT_RETRIES,
                    currentProtocolOnly = currentProtocolOnly,
                )) {
                clearStoppedSidecar(binder)
                return true
            }
        }

        if (terminateResidentSidecarProcesses(
                mode,
                force = false,
                currentProtocolOnly = currentProtocolOnly,
            ) || terminateResidentSidecarProcesses(
                mode,
                force = true,
                currentProtocolOnly = currentProtocolOnly,
            )
        ) {
            clearStoppedSidecar(binder)
            return true
        }

        val remainingPids = residentSidecarPids(
            mode,
            currentProtocolOnly = currentProtocolOnly,
        )
        lastSidecarStartDiagnostic = when (remainingPids) {
            null -> "resident sidecar stop failed: process probe unavailable"
            else -> "resident sidecar stop failed: remaining pids=${remainingPids.joinToString(",")}"
        }
        return false
    }

    private fun terminateResidentSidecarProcesses(
        mode: RuntimeMode,
        force: Boolean,
        currentProtocolOnly: Boolean = true,
    ): Boolean {
        val pids = residentSidecarPids(
            mode = mode,
            currentProtocolOnly = currentProtocolOnly,
        ) ?: return false
        val command = ResidentSidecarProcessCommands.stopCommand(pids, force, context.packageName) ?: return true
        val commandAccepted = when (mode) {
            RuntimeMode.STANDARD -> {
                if (!shellRunner.isAvailable(ShellIdentity.SHELL)) return false
                !shellRunner.isFailure(shellRunner.shell(command, PROCESS_PROBE_TIMEOUT_MS, "shell,v2"))
            }
            else -> return false
        }
        if (!commandAccepted) return false
        return waitForResidentProcessExit(
            mode = mode,
            retries = PROCESS_EXIT_RETRIES,
            currentProtocolOnly = currentProtocolOnly,
        )
    }

    private fun waitForResidentProcessExit(
        mode: RuntimeMode,
        retries: Int,
        currentProtocolOnly: Boolean = true,
    ): Boolean {
        repeat(retries) {
            val pids = residentSidecarPids(
                mode = mode,
                currentProtocolOnly = currentProtocolOnly,
            ) ?: return false
            if (pids.isEmpty()) return true
            Thread.sleep(PROCESS_EXIT_POLL_MS)
        }
        return residentSidecarPids(
            mode = mode,
            currentProtocolOnly = currentProtocolOnly,
        )?.isEmpty() == true
    }

    private fun clearStoppedSidecar(binder: IBinder?) {
        if (binder != null) {
            SidecarBinderRegistry.clear(binder)
            protocolCompatibility.remove(binder)
        }
        directServices.clear()
        displayModes.clear()
        displaySizes.clear()
    }

    private fun isRegisteredServiceAlive(
        mode: RuntimeMode?,
    ): Boolean = serviceOrNull(mode)?.asBinder()?.isBinderAlive == true

    private fun uidForMode(mode: RuntimeMode): Int = when (mode) {
        RuntimeMode.STANDARD -> shellUid
        else -> -1
    }

    private fun startShellAppProcess(width: Int, height: Int, densityDpi: Int): CommandResult {
        if (!shellRunner.isAvailable(ShellIdentity.SHELL)) {
            return invalid(shellRunner.describeShellUnavailable())
        }
        val launch = shellRunner.shell(
            buildAppProcessCommand(width, height, densityDpi),
            ADB_START_TIMEOUT_MS,
            "shell,v2"
        )
        if (shellRunner.isFailure(launch)) {
            return invalid("device shell app_process start failed: ${launch.take(160)}")
        }
        repeat(ADB_BIND_RETRIES) {
            Thread.sleep(BIND_POLL_MS)
            val service = getRegisteredService(RuntimeMode.STANDARD)
            // The old same-protocol process can broadcast again while its replacement starts.
            // Do not allocate through that Binder before checking the current APK identity.
            if (service != null && isProtocolCompatible(service)) {
                return startService(
                    service,
                    width,
                    height,
                    densityDpi,
                    "shell_app_process_binder",
                    RuntimeMode.STANDARD
                )
            }
        }
        return invalid(
            "device shell app_process binder timeout; sidecar-log=[${readSidecarLog()}]"
        )
    }

    private fun getRegisteredService(
        mode: RuntimeMode,
    ): IVirtualSpaceSidecar? =
        runCatching {
            val binder = SidecarBinderRegistry.get(
                uidForMode(mode),
            ) ?: return@runCatching null
            IVirtualSpaceSidecar.Stub.asInterface(binder)
        }.getOrNull()

    /** Resident files live in the application's external files directory, removed with its data. */
    private fun sidecarStorageDirectory(): String = ResidentSidecarProcessCommands.requireStorageDirectory(
        requireNotNull(context.getExternalFilesDir(null)) { "Application storage is unavailable" }.absolutePath,
        context.packageName
    )

    private fun buildAppProcessCommand(
        width: Int,
        height: Int,
        densityDpi: Int,
    ): String {
        return ResidentSidecarProcessCommands.launchCommand(
            apkPath = context.applicationInfo.sourceDir,
            mainClass = "com.droidance.core.device.agent.device.VirtualSpaceSidecarMain",
            width = width,
            height = height,
            densityDpi = densityDpi,
            packageName = context.packageName,
            protocolVersion = SIDECAR_PROTOCOL_VERSION,
            storageDirectory = sidecarStorageDirectory(),
            stopWhenDeveloperOptionsDisabled = !rootConnection.useRoot,
            escape = adbShell::escapeForShell
        )
    }

    /** 诊断用：把 sidecar 进程存活、setsid/nohup 可用性、日志文件状态、日志内容一起读回来，定位 binder timeout 真因。 */
    private fun readSidecarLog(): String =
        runCatching {
            val log = ResidentSidecarProcessCommands.logPath(sidecarStorageDirectory())
            val probe = "echo SERVICE=$sidecarServiceName TAG=$sidecarLogTag; " +
                "echo PROC=[\$(pgrep -f VirtualSpaceSidecarMain | tr '\\n' ' ')]; " +
                "echo SETSID=[\$(command -v setsid || echo none)] NOHUP=[\$(command -v nohup || echo none)]; " +
                "echo LOGSTAT=[\$(ls -la $log 2>&1)]; " +
                "echo ---LOG---; cat $log 2>/dev/null | tail -25"
            val out = if (shellRunner.isAvailable(ShellIdentity.SHELL)) {
                shellRunner.shell(probe, 4_000L)
            } else {
                adbShell.exec(probe, 4_000L).output
            }
            out.trim().ifBlank { "empty/missing" }.take(1200)
        }.getOrElse { "read failed: ${it.message}" }

    private fun invalid(detail: String, channelUnavailable: Boolean = false): CommandResult =
        CommandResult(ok = false, detail = detail, channelUnavailable = channelUnavailable)

    private fun ByteArray.looksLikePng(): Boolean =
        size >= 8 &&
            this[0] == 0x89.toByte() &&
            this[1] == 0x50.toByte() &&
            this[2] == 0x4E.toByte() &&
            this[3] == 0x47.toByte() &&
            this[4] == 0x0D.toByte() &&
            this[5] == 0x0A.toByte() &&
            this[6] == 0x1A.toByte() &&
            this[7] == 0x0A.toByte()

    private fun ByteArray.looksLikeJpeg(): Boolean =
        size >= 4 &&
            this[0] == 0xFF.toByte() &&
            this[1] == 0xD8.toByte() &&
            this[size - 2] == 0xFF.toByte() &&
            this[size - 1] == 0xD9.toByte()

    companion object {
        private const val DEFAULT_LAUNCH_FLAGS = 0x10000000
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private const val ADB_START_TIMEOUT_MS = 2_000L
        private const val ADB_BIND_RETRIES = 50
        private const val RESIDENT_REBIND_RETRIES = 30
        private const val BIND_POLL_MS = 200L
        private const val EVIDENCE_TIMEOUT_MS = 6_000L
        private const val PROCESS_PROBE_TIMEOUT_MS = 2_000L
        private const val PROCESS_EXIT_RETRIES = 20
        private const val GRACEFUL_EXIT_RETRIES = 5
        private const val PROCESS_EXIT_POLL_MS = 100L
        private const val SIDECAR_PROTOCOL_TIMEOUT_MS = 1_000L
        private const val SIDECAR_PROTOCOL_VERSION = VirtualSpaceSidecarService.SIDECAR_PROTOCOL_VERSION
        private const val AM_START_TIMEOUT_MS = 12_000L
        // 跨进程 Binder 调用的有界超时：tap/key/text 等输入动作正常是毫秒级本机 IPC。
        private const val SIDECAR_TEXT_CALL_TIMEOUT_MS = 20_000L
        // 截图给 sidecar 自身内部截屏逻辑（含 6s 内部超时）留出往返余量。
        // 创建 VirtualDisplay/ImageReader 比普通调用重，放宽一些。
        private const val SIDECAR_START_TIMEOUT_MS = 8_000L
    }
}
