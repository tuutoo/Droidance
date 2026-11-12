// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.widget.Toast
import com.droidance.R
import com.droidance.agent.AgentRuntime
import com.droidance.core.agent.AgentState
import com.droidance.core.agent.UserInteractionKind
import com.droidance.core.agent.UserInteractionState
import com.droidance.core.agent.device.ExecutionContextStore
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.agent.device.VirtualSpaceManager
import com.droidance.agent.device.VirtualSpaceViewerLauncher
import com.droidance.core.agent.di.ApplicationScope
import com.droidance.core.device.tools.InputActionLifecycle
import com.droidance.core.device.tools.InputActionLifecycleEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import javax.inject.Inject
import javax.inject.Singleton

/** Shows virtual-task progress and opens the virtual-space viewer. */
@Singleton
class AgentStatusOverlay @Inject constructor(
    private val foregroundState: com.droidance.agent.AppForegroundState,
    @ApplicationContext private val context: Context,
    private val agentRuntime: AgentRuntime,
    private val virtualSpaceViewerLauncher: VirtualSpaceViewerLauncher,
    private val virtualSpaceManager: VirtualSpaceManager,
    private val executionContextStore: ExecutionContextStore,
    private val inputActionLifecycle: InputActionLifecycle,
    private val runtimeSurfaceController: RuntimeSurfaceController,
    private val shell: com.droidance.core.device.tools.AdbShellExecutor,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayMonitorJob: Job? = null
    init {
        if (com.droidance.core.device.agent.device.RuntimeProcessRole.isOwner) {
            appScope.launch {
                foregroundState.foreground.collect {
                    mainHandler.post { updateRuntimeOverlayVisibility() }
                }
            }
            appScope.launch {
                agentRuntime.isMobileUseTaskActive.collect {
                    mainHandler.post { updateRuntimeOverlayVisibility() }
                }
            }
            appScope.launch {
                agentRuntime.currentTaskExecutionSpace.collect {
                    mainHandler.post { updateRuntimeOverlayVisibility() }
                }
            }
            appScope.launch {
                delay(1000L)
                if (!isRuntimeActive()) {
                    runtimeSurfaceController.hideAll()
                }
            }
            runtimeSurfaceController.setOnIndicatorClick { handleRuntimeIslandAction() }
            runtimeSurfaceController.setOnControlClick { handleRuntimeIslandControl() }
            runtimeSurfaceController.setOnStopClick { handleRuntimeIslandStop() }
            runtimeSurfaceController.setOnVisibilityChanged { mainHandler.post { updateRuntimeOverlayVisibility() } }
            appScope.launch {
                agentRuntime.state.collect { state ->
                    when (state) {
                        AgentState.THINKING,
                        AgentState.EXECUTING_TOOL -> {
                            startOverlayMonitor()
                            updateRuntimeOverlayVisibility()
                        }
                        AgentState.WAITING_PERMISSION -> {
                            startOverlayMonitor()
                            updateRuntimeOverlayVisibility()
                        }
                        AgentState.ERROR -> {
                            stopOverlayMonitor()
                            runtimeSurfaceController.hideAll()
                        }
                        AgentState.IDLE -> {
                            stopOverlayMonitor()
                            runtimeSurfaceController.hideAll()
                        }
                    }
                }
            }
            // 运行岛随 mobile_use 执行空间与主屏生命周期更新。
            appScope.launch {
                agentRuntime.currentTaskTitle.collect {
                    mainHandler.post {
                        if (shouldShowExternalTaskOverlay()) showRuntimeSurfaceIndicator()
                    }
                }
            }
            appScope.launch {
                agentRuntime.isPaused.collect { paused ->
                    mainHandler.post {
                        // 接管 = 暂停 agent，让用户手动操作；再点「继续」交还给 agent
                        if (shouldShowExternalTaskOverlay()) showRuntimeSurfaceIndicator()
                    }
                }
            }
            appScope.launch {
                agentRuntime.manualTakeoverAvailable.collect {
                    mainHandler.post {
                        if (shouldShowExternalTaskOverlay()) showRuntimeSurfaceIndicator()
                    }
                }
            }
            appScope.launch {
                inputActionLifecycle.events.collect { event ->
                    if (!isRuntimeActive()) return@collect
                    mainHandler.post {
                        if (!agentRuntime.isMobileUseTaskActive.value) {
                            runtimeSurfaceController.hideAll()
                            return@post
                        }
                        // 小手只跟随真实输入生命周期，并挂到事件所属 display；它只做视觉反馈，不注入输入。
                        when (event) {
                            is InputActionLifecycleEvent.Started ->
                                runtimeSurfaceController.showAction(event)
                            is InputActionLifecycleEvent.Finished ->
                                runtimeSurfaceController.finishAction(event)
                        }
                    }
                }
            }
        }
    }



    private fun startOverlayMonitor() {
        if (overlayMonitorJob != null) return
        overlayMonitorJob = appScope.launch {
            while (true) {
                updateRuntimeOverlayVisibility()
                delay(500L)
            }
        }
    }

    private fun stopOverlayMonitor() {
        overlayMonitorJob?.cancel()
        overlayMonitorJob = null
    }

    fun refreshLanguage() {
        mainHandler.post { updateRuntimeOverlayVisibility() }
    }

    private fun updateRuntimeOverlayVisibility() {
        runtimeSurfaceController.setEdgeLightVisible(false)
        runtimeSurfaceController.hideCursorOnDisplay(Display.DEFAULT_DISPLAY)

        if (!shouldShowExternalTaskOverlay()) {
            runtimeSurfaceController.hide(preserveCursor = isRuntimeActive())
            return
        }
        // The indicator opens the viewer; gesture feedback stays on the virtual display.
        showRuntimeSurfaceIndicator()
    }

    private fun showRuntimeSurfaceIndicator() {
        runtimeSurfaceController.showIndicator(
            currentActionText = runtimeTaskTitleText(),
            action = runtimeIslandActionKind(),
            paused = agentRuntime.isPaused.value,
            state = runtimeIslandState()
        )
    }

    private fun runtimeIslandState(): RuntimeIslandPresentation.State = when {
        agentRuntime.state.value == AgentState.WAITING_PERMISSION ->
            RuntimeIslandPresentation.State.WAITING_PERMISSION
        agentRuntime.isPaused.value || agentRuntime.manualTakeoverAvailable.value ->
            RuntimeIslandPresentation.State.WAITING_FOR_USER
        else -> RuntimeIslandPresentation.State.EXECUTING
    }

    private fun runtimeTaskTitleText(): String {
        return RuntimeIslandPresentation.label(context, runtimeIslandState(), agentRuntime.currentTaskTitle.value)
    }

    private fun isRuntimeActive(): Boolean {
        val state = agentRuntime.state.value
        return state == AgentState.THINKING ||
            state == AgentState.EXECUTING_TOOL ||
            state == AgentState.WAITING_PERMISSION
    }

    /**
     * The external indicator is hidden while the viewer provides its own controls.
     */
    private fun shouldShowExternalTaskOverlay(): Boolean =
        RuntimeOverlayVisibility.shouldShow(
            active = isRuntimeActive(),
            mobileUse = agentRuntime.isMobileUseTaskActive.value || virtualSpaceManager.isActive(),
            hostForeground = foregroundState.isInForeground,
            viewerExpanded = runtimeSurfaceController.isViewerExpanded,
            permissionWaiting = agentRuntime.state.value == AgentState.WAITING_PERMISSION
        )

    /** Open the current virtual-space viewer. */
    private fun runtimeIslandActionKind() = RuntimeSurfaceBitmapRenderer.IslandAction.OPEN

    /** An empty execution display has nothing to show; the task's progress lives in Droidance. */
    private fun handleRuntimeIslandAction() {
        appScope.launch(Dispatchers.IO) {
            val request = agentRuntime.userInteractionRequest.value
            if (request?.kind == UserInteractionKind.HIGH_RISK_CONFIRMATION &&
                request.state == UserInteractionState.PENDING
            ) {
                openApplication()
            } else if (virtualSpaceManager.inspectDisplayContent() == com.droidance.core.device.agent.device.VirtualDisplayContentState.CONTENT_PRESENT) {
                openVirtualSpaceViewer()
            } else {
                openApplication()
            }
        }
    }

    private fun openApplication() {
        val component = shell.escapeForShell("${context.packageName}/com.droidance.MainActivity")
        if (!shell.exec("am start --user 0 -n $component").success) mainHandler.post { showVirtualSpaceViewerOpenFailed() }
    }

    private fun handleRuntimeIslandControl() {
        mainHandler.post {
            if (!isRuntimeActive()) return@post
            if (agentRuntime.isPaused.value) agentRuntime.resumeManualTakeover()
            else agentRuntime.beginManualTakeoverFromOverlay()
        }
    }

    private fun handleRuntimeIslandStop() {
        val executionId = executionContextStore.current()?.executionId
        val turnId = agentRuntime.taskTiming.value?.turnId
        runtimeSurfaceController.hideAll()
        appScope.launch(Dispatchers.IO) {
            // Disk-backed residency cleanup and Provider cancellation must not occupy
            // the touch thread. A stale click cannot stop a subsequent execution.
            if (executionContextStore.current()?.executionId == executionId &&
                agentRuntime.taskTiming.value?.turnId == turnId) {
                agentRuntime.stopCurrentMobileUse(origin = "runtime_island_stop")
            }
        }
    }

    /** 通过冻结的 sidecar 在主屏打开普通 Activity 查看页。 */
    private fun openVirtualSpaceViewer() {
        appScope.launch(Dispatchers.IO) {
            if (!virtualSpaceViewerLauncher.open()) {
                mainHandler.post { showVirtualSpaceViewerOpenFailed() }
            }
        }
    }

    private fun showVirtualSpaceViewerOpenFailed() {
        Toast.makeText(context, ContextCompat.getString(context, R.string.virtual_space_viewer_open_failed), Toast.LENGTH_SHORT).show()
    }

}
