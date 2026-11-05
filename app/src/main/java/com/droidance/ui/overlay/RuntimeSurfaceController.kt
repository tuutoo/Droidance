// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Binder
import android.os.Parcel
import android.view.MotionEvent
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.window.InputTransferToken
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.view.Display
import com.droidance.core.agent.device.ExecutionContextStore
import com.droidance.core.device.agent.device.IRuntimeIndicatorCallback
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.device.agent.device.SidecarSessionBinding
import com.droidance.agent.device.VirtualSpaceManager
import com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge
import com.droidance.debug.DebugLogManager
import com.droidance.core.agent.di.ApplicationScope
import com.droidance.core.device.tools.InputActionLifecycleEvent
import com.droidance.core.device.tools.InputAction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 虚拟屏动作的小手线索：由 App 侧镜像预览渲染，虚拟屏本身不合成任何 Droidance 像素。 */
data class VirtualSpaceHandCue(
    val actionId: String,
    val displayId: Int,
    val action: InputAction,
    val startedAtMs: Long,
    val finishedAtMs: Long = 0L,
    val fromX: Int = 0,
    val fromY: Int = 0,
    val travelDurationMs: Long = 0L
)

/** Serializes shell-sidecar system-surface updates so a late status refresh cannot undo a hide. */
@Singleton
class RuntimeSurfaceController @Inject constructor(
    private val foregroundState: com.droidance.agent.AppForegroundState,
    @ApplicationContext private val appContext: Context,
    private val executionContextStore: ExecutionContextStore,
    private val virtualSpaceManager: VirtualSpaceManager,
    private val bridge: VirtualSpaceSidecarBridge,
    private val bitmapRendererProvider: dagger.Lazy<RuntimeSurfaceBitmapRenderer>,
    private val debugLogManager: DebugLogManager,
    private val inputActionLifecycle: com.droidance.core.device.tools.InputActionLifecycle,
    private val processClient: com.droidance.runtime.RuntimeProcessClient,
    @ApplicationScope private val appScope: CoroutineScope
) {
    // Shell runtime initialization must not eagerly construct Android text paints.
    // Defer renderer allocation until a surface actually needs a bitmap.
    private val bitmapRenderer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { bitmapRendererProvider.get() }
    private var screenWidthPx = appContext.resources.displayMetrics.widthPixels
    private var screenHeightPx = appContext.resources.displayMetrics.heightPixels
    private sealed interface Command {
        val binding: SidecarSessionBinding

        /** One bootstrap image creates the sidecar parent; the hosted View owns live drawing. */
        data class Frame(
            override val binding: SidecarSessionBinding,
            val visible: Boolean,
            val png: ByteArray,
            val baseTopPx: Int,
            val widthPx: Int,
            val heightPx: Int,
            val stopButtonLeftPx: Int,
            val spec: IslandSpec? = null
        ) : Command

        data class Cursor(
            override val binding: SidecarSessionBinding,
            val targetDisplayId: Int,
            val visible: Boolean,
            val actionType: Int,
            val startX: Int,
            val startY: Int,
            val endX: Int,
            val endY: Int,
            val durationMs: Int,
            val startedAtMs: Long,
            val finishedAtMs: Long,
            val cueId: String?
        ) : Command

        data class Suppress(
            override val binding: SidecarSessionBinding,
            val suppressed: Boolean,
            val completed: CompletableDeferred<Unit>
        ) : Command

        data class Edge(
            override val binding: SidecarSessionBinding,
            val spec: EdgeSpec
        ) : Command
    }

    private data class EdgeSpec(
        val visible: Boolean,
        val cornerRadii: DeviceCornerRadii,
        val motionEnabled: Boolean
    )

    private data class IslandSpec(
        val currentActionText: String,
        val action: RuntimeSurfaceBitmapRenderer.IslandAction,
        val paused: Boolean,
        val state: RuntimeIslandPresentation.State,
        val collapsed: Boolean
    )

    // This windowless hierarchy owns its own Looper, independent of Activity
    // layout and task-state updates. Create, dispatch and release on that owner.
    private val inputHandler by lazy {
        Handler.createAsync(HandlerThread("DroidanceIslandInput", Process.THREAD_PRIORITY_DISPLAY)
            .apply { start() }.looper)
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val cursors = Channel<Command.Cursor>(Channel.CONFLATED)
    @Volatile private var latestHandCue: VirtualSpaceHandCue? = null
    private val _virtualSpaceHandCue = MutableStateFlow<VirtualSpaceHandCue?>(null)

    /** 虚拟屏动作反馈流；查看页与接管镜像面板订阅它，在预览之上画小手。 */
    val virtualSpaceHandCue: StateFlow<VirtualSpaceHandCue?> = _virtualSpaceHandCue.asStateFlow()
    internal fun adoptRuntimeHandCue(cue: VirtualSpaceHandCue?) {
        check(!processClient.isOwner)
        _virtualSpaceHandCue.value = cue
    }
    @Volatile private var lastBinding: SidecarSessionBinding? = null
    @Volatile private var activeCursorActionId: String? = null
    @Volatile private var activeCursorDisplayId = 0
    @Volatile private var runtimeVisible = false
    @Volatile private var edgeSpec: EdgeSpec? = null
    @Volatile private var indicatorClickListener: (() -> Unit)? = null
    @Volatile private var controlClickListener: (() -> Unit)? = null
    @Volatile private var stopClickListener: (() -> Unit)? = null
    @Volatile private var viewerExpanded = false
    val isViewerExpanded: Boolean get() = viewerExpanded
    private var visibilityChanged: (() -> Unit)? = null
    fun setOnVisibilityChanged(listener: () -> Unit) { visibilityChanged = listener }
    @Volatile private var inputSurface: SurfaceControl? = null
    private var inputHost: SurfaceControlViewHost? = null
    private var inputIslandView: RuntimeIslandSurfaceView? = null
    @Volatile private var inputSuppressionDepth = 0 // Owned by inputHandler, including host registration.
    private var inputWidthPx = 0
    private var inputHeightPx = 0
    private var inputStopButtonLeftPx = 0
    private var inputPositionTransaction: SurfaceControl.Transaction? = null
    private var inputPositionScheduler: RuntimeIslandFrameScheduler? = null
    private var lastInputEventTimeMs = 0L
    @Volatile internal var lastIndicatorInputDispatchDelayMs = 0L
        private set
    private var inputDownRawX = 0f
    private var inputDownRawY = 0f
    private var inputDownAtMs = 0L
    private var dragStartOffsetX = 0
    private var dragStartOffsetY = 0
    @Volatile private var dragging = false
    private val tapSlopPx = ViewConfiguration.get(appContext).scaledTouchSlop.toFloat()
    @Volatile private var islandDragOffsetX = RuntimeIslandPosition.readX(appContext)
    @Volatile private var islandDragOffsetY = RuntimeIslandPosition.read(appContext)
    @Volatile private var lastIslandBaseTopPx = 0
    @Volatile private var lastIslandHeightPx = 0
    @Volatile private var currentSpec: IslandSpec? = null
    @Volatile private var collapsed = RuntimeIslandPosition.isCollapsed(appContext)
    private data class PendingPlacement(val edge: RuntimeIslandDocking.Edge?, val centerX: Float, val centerY: Float)
    private var pendingPlacement: PendingPlacement? = null
    private var renderJob: Job? = null
    private val callback = object : IRuntimeIndicatorCallback.Stub() {
        override fun onRuntimeIndicatorTapped() {
            inputHandler.post { indicatorClickListener?.invoke() }
        }

        override fun onRuntimeStopRequested() {
            inputHandler.post { stopClickListener?.invoke() }
        }
    }

    init {
        if (processClient.isOwner) {
            appScope.launch(Dispatchers.IO) {
                delay(500L)
                hideAll()
            }
            appScope.launch(Dispatchers.IO) {
                while (isActive) {
                    val command = select<Command> {
                        commands.onReceive { it }
                        cursors.onReceive { it }
                    }
                    execute(command)
                }
            }
        }
    }

    fun setOnIndicatorClick(listener: (() -> Unit)?): (() -> Unit)? {
        val previous = indicatorClickListener
        indicatorClickListener = listener
        return previous
    }

    fun setOnStopClick(listener: (() -> Unit)?): (() -> Unit)? {
        val previous = stopClickListener
        stopClickListener = listener
        return previous
    }

    fun setOnControlClick(listener: (() -> Unit)?) {
        controlClickListener = listener
    }

    fun setViewerExpanded(expanded: Boolean) {
        if (!processClient.isOwner) {
            viewerExpanded = expanded
            processClient.send("viewer", android.os.Bundle().apply { putBoolean("expanded", expanded) })
            return
        }
        if (viewerExpanded == expanded) return
        viewerExpanded = expanded
        if (expanded) hide(preserveCursor = true)
        visibilityChanged?.invoke()
    }

    fun showIndicator(
        currentActionText: String,
        action: RuntimeSurfaceBitmapRenderer.IslandAction,
        paused: Boolean,
        state: RuntimeIslandPresentation.State
    ) {
        if (viewerExpanded || foregroundState.isInForeground) return
        val binding = currentBinding() ?: return
        replaceBinding(binding)
        if (!runtimeVisible) {
            islandDragOffsetX = RuntimeIslandPosition.readX(appContext)
            islandDragOffsetY = RuntimeIslandPosition.read(appContext)
        }
        runtimeVisible = true
        val spec = IslandSpec(currentActionText, action, paused, state, collapsed)
        if (spec == currentSpec && inputSurface != null) return
        currentSpec = spec
        // Status changes stay on the windowless UI thread even if a sidecar command is busy.
        inputHandler.post {
            if (runtimeVisible && currentSpec == spec && lastBinding == binding) {
                presentIsland(spec)
            }
        }
        if (inputSurface == null) {
            startRender(binding, spec)
        }
    }

    fun setEdgeLightVisible(visible: Boolean) {
        val binding = (if (visible) currentBinding() else lastBinding ?: currentBinding()) ?: return
        replaceBinding(binding)
        val spec = EdgeSpec(
            visible && !viewerExpanded && !foregroundState.isInForeground,
            DeviceCornerRadius.resolve(appContext),
            RuntimeIslandAnimations.areEnabled(appContext)
        )
        if (edgeSpec == spec) return
        edgeSpec = spec
        commands.trySend(Command.Edge(binding, spec))
    }

    /** Bootstrap only. Runtime animation never traverses the Binder command queue. */
    private fun startRender(binding: SidecarSessionBinding, spec: IslandSpec) {
        renderJob?.cancel()
        renderJob = appScope.launch(Dispatchers.Default) {
            val first = bitmapRenderer.renderIsland(spec.currentActionText, spec.action, spec.paused,
                spec.state, phase = 0f, collapsed = spec.collapsed)
            if (!isActive || currentSpec != spec || !runtimeVisible || lastBinding != binding) return@launch
            commands.trySend(Command.Frame(binding, true, first.png, first.topOffsetPx,
                first.widthPx, first.heightPx, first.stopButtonLeftPx, spec))
        }
    }

    private fun presentIsland(spec: IslandSpec) {
        inputIslandView?.present(spec.currentActionText, spec.action, spec.paused, spec.state, spec.collapsed)
    }

    fun ensureVirtualSpaceCursor(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) return
        if (_virtualSpaceHandCue.value != null && _virtualSpaceHandCue.value?.displayId == displayId) return
        val size = virtualSpaceManager.activeDisplaySize() ?: return
        val now = com.droidance.core.device.agent.device.CursorMotion.nowMs() - 200L
        val parkedX = (size.first * 0.3f).toInt()
        val parkedY = (size.second * 0.75f).toInt()
        val cue = VirtualSpaceHandCue(
            "parked:$displayId", displayId,
            InputAction.Tap(parkedX, parkedY), now, now, parkedX, parkedY, 0L
        )
        inputActionLifecycle.recordDisplayPosition(displayId, parkedX, parkedY)
        _virtualSpaceHandCue.value = cue
    }

    fun showAction(event: InputActionLifecycleEvent.Started) {
        val binding = currentBinding() ?: return
        replaceBinding(binding)
        activeCursorActionId = event.actionId
        activeCursorDisplayId = event.displayId
        val cue = VirtualSpaceHandCue(
            actionId = event.actionId,
            displayId = event.displayId,
            action = event.action,
            startedAtMs = event.elapsedMs,
            fromX = event.fromX,
            fromY = event.fromY,
            travelDurationMs = event.travelDurationMs
        )
        latestHandCue = cue
        dispatchHandCue(binding, cue)
    }

    fun finishAction(event: InputActionLifecycleEvent.Finished) {
        val previous = latestHandCue ?: return
        if (previous.actionId != event.actionId) return
        activeCursorActionId = null
        val cue = previous.copy(finishedAtMs = event.elapsedMs)
        latestHandCue = cue
        val binding = lastBinding ?: return
        dispatchHandCue(binding, cue)
    }

    private fun dispatchHandCue(binding: SidecarSessionBinding, cue: VirtualSpaceHandCue) {
        if (cue.displayId != Display.DEFAULT_DISPLAY) {
            // Only the user preview contains the hand; model observations remain clean.
            _virtualSpaceHandCue.value = cue
            return
        }
        val action = cue.action
        val swipe = action as? InputAction.Swipe
        val tap = action as? InputAction.Tap
        cursors.trySend(Command.Cursor(
            binding = binding,
            targetDisplayId = cue.displayId,
            visible = true,
            actionType = if (swipe != null) ACTION_SWIPE else ACTION_TAP,
            startX = swipe?.startX ?: requireNotNull(tap).x,
            startY = swipe?.startY ?: requireNotNull(tap).y,
            endX = swipe?.endX ?: requireNotNull(tap).x,
            endY = swipe?.endY ?: requireNotNull(tap).y,
            durationMs = action.durationMs,
            startedAtMs = cue.startedAtMs,
            finishedAtMs = cue.finishedAtMs,
            cueId = cue.actionId
        ))
    }

    fun hide(preserveCursor: Boolean = true) {
        setEdgeLightVisible(false)
        if (!preserveCursor) {
            activeCursorActionId = null
            latestHandCue = null
            _virtualSpaceHandCue.value = null
        }
        val binding = lastBinding ?: currentBinding() ?: return
        if (lastBinding == null) lastBinding = binding
        runtimeVisible = false
        renderJob?.cancel()
        currentSpec = null
        commands.trySend(
            Command.Frame(
                binding = binding,
                visible = false,
                png = ByteArray(0),
                baseTopPx = 0,
                widthPx = 0,
                heightPx = 0,
                stopButtonLeftPx = 0
            )
        )
        if (!preserveCursor) {
            cursors.trySend(hiddenCursor(binding, binding.displayId))
            if (activeCursorDisplayId != binding.displayId) {
                cursors.trySend(hiddenCursor(binding, activeCursorDisplayId))
            }
        }
    }

    fun hideCursor() {
        activeCursorActionId = null
        latestHandCue = null
        _virtualSpaceHandCue.value = null
        val binding = lastBinding ?: currentBinding() ?: return
        if (lastBinding == null) lastBinding = binding
        cursors.trySend(hiddenCursor(binding, binding.displayId))
        if (activeCursorDisplayId != binding.displayId) {
            cursors.trySend(hiddenCursor(binding, activeCursorDisplayId))
        }
    }

    fun hideCursorOnDisplay(targetDisplayId: Int) {
        if (targetDisplayId == Display.DEFAULT_DISPLAY) {
            val binding = lastBinding ?: currentBinding() ?: return
            if (lastBinding == null) lastBinding = binding
            if (latestHandCue?.displayId == Display.DEFAULT_DISPLAY) {
                activeCursorActionId = null
                latestHandCue = null
            }
            cursors.trySend(hiddenCursor(binding, Display.DEFAULT_DISPLAY))
        } else {
            if (_virtualSpaceHandCue.value?.displayId == targetDisplayId) {
                _virtualSpaceHandCue.value = null
            }
        }
    }

    fun hideAll() {
        hide(preserveCursor = false)
    }

    private fun replaceBinding(binding: SidecarSessionBinding) {
        val previous = lastBinding
        if (previous != binding) {
            unregisterIndicatorInput()
            edgeSpec = null
        }
        if (previous != null && previous.runtimeMode != binding.runtimeMode) {
            renderJob?.cancel()
            currentSpec = null
            runtimeVisible = false
        }
        lastBinding = binding
    }

    private fun currentBinding(): SidecarSessionBinding? = virtualSpaceManager.activeBinding()
        ?: bridge.runtimeSurfaceBinding(Display.DEFAULT_DISPLAY, RuntimeMode.STANDARD)


    private fun hiddenCursor(
        binding: SidecarSessionBinding,
        targetDisplayId: Int
    ): Command.Cursor = Command.Cursor(
        binding = binding,
        targetDisplayId = targetDisplayId,
        visible = false,
        actionType = ACTION_NONE,
        startX = 0,
        startY = 0,
        endX = 0,
        endY = 0,
        durationMs = 0,
        startedAtMs = 0L,
        finishedAtMs = 0L,
        cueId = null
    )

    private fun execute(command: Command) {
        // Binding 切换时 Channel 中可能还排着旧执行空间的命令。全部丢弃，
        // 避免旧虚拟屏请求淹没主屏运行岛更新。
        if (!isExecutable(command)) {
            if (command is Command.Suppress) command.completed.completeExceptionally(
                IllegalStateException("Runtime surface binding changed before suppression"))
            return
        }
        try {
            val result = when (command) {
                is Command.Frame -> {
                    lastIslandBaseTopPx = command.baseTopPx
                    lastIslandHeightPx = command.heightPx
                    val currentTop = clampIslandTop(command.baseTopPx + islandDragOffsetY)
                    val update = bridge.updateRuntimeIslandBitmap(
                        binding = command.binding,
                        visible = command.visible,
                        islandPng = command.png,
                        topOffsetPx = currentTop,
                        callback = callback
                    )
                    if (!command.visible) unregisterIndicatorInput()
                    if (command.visible && inputSurface != null &&
                        (inputWidthPx != command.widthPx || inputHeightPx != command.heightPx)) {
                        if (!dragging) unregisterIndicatorInput()
                    }
                    if (update.ok && command.visible && command.binding == lastBinding && inputSurface == null && inputSuppressionDepth == 0) {
                        val surface = bridge.runtimeIslandSurface(command.binding)
                        if (surface == null || !registerIndicatorInput(surface, command.widthPx,
                                command.heightPx, command.stopButtonLeftPx)) {
                            debugLogManager.appendEvent("Runtime island input registration unavailable")
                        }
                    }
                    update
                }
                is Command.Cursor -> bridge.updateRuntimeCursorBitmapOnDisplay(
                    binding = command.binding,
                    targetDisplayId = command.targetDisplayId,
                    visible = command.visible,
                    actionType = command.actionType,
                    startX = command.startX,
                    startY = command.startY,
                    endX = command.endX,
                    endY = command.endY,
                    durationMs = command.durationMs,
                    startedAtMs = command.startedAtMs,
                    finishedAtMs = command.finishedAtMs,
                    motionEnabled = ValueAnimator.areAnimatorsEnabled(),
                    pointerPng = if (command.visible) bitmapRenderer.pointerBitmapPng() else ByteArray(0)
                )
                is Command.Suppress -> {
                    bridge.setRuntimeSurfaceSuppressed(
                        binding = command.binding,
                        suppressed = command.suppressed
                    ).also { result ->
                        check(result.ok) { "Runtime surface suppression failed: ${result.detail}" }
                        command.completed.complete(Unit)
                    }
                }
                is Command.Edge -> bridge.updateRuntimeEdgeLight(
                    binding = command.binding,
                    visible = command.spec.visible,
                    density = appContext.resources.displayMetrics.density,
                    cornerRadii = command.spec.cornerRadii.toPathRadii(),
                    motionEnabled = command.spec.motionEnabled
                )
            }
            if (!result.ok) {
                debugLogManager.appendEvent("运行浮层 sidecar 调用失败: ${result.detail.take(180)}")
            }
        } catch (error: Throwable) {
            if (command is Command.Suppress) command.completed.completeExceptionally(error)
            debugLogManager.appendEvent("运行浮层 sidecar 异常: ${error.message.orEmpty().take(180)}")
        }
    }

    private fun isExecutable(command: Command): Boolean {
        val current = lastBinding
        if (command.binding != current) return false
        return when (command) {
            is Command.Edge -> command.spec == edgeSpec &&
                (!command.spec.visible || (!viewerExpanded && !foregroundState.isInForeground))
            is Command.Frame -> if (!command.visible) !runtimeVisible else (runtimeVisible && !foregroundState.isInForeground &&
                command.spec == currentSpec)
            is Command.Cursor -> !command.visible || latestHandCue?.let {
                command.cueId == it.actionId && command.finishedAtMs == it.finishedAtMs
            } == true
            else -> true
        }
    }

    @SuppressLint("NewApi")
    private fun registerIndicatorInput(surface: SurfaceControl, widthPx: Int, heightPx: Int,
            stopButtonLeftPx: Int): Boolean = runOnInputSync {
        if (inputSuppressionDepth > 0) { surface.release(); return@runOnInputSync true }
        var host: SurfaceControlViewHost? = null
        try {
            val display = appContext.getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)
            // Android 16 requires a registered application PID to create a WMS Session.
            // The platform driver supplies the system session; input, drawing and frame timing stay here.
            val sessionField = Class.forName("android.view.WindowManagerGlobal").getDeclaredField("sWindowSession")
                .apply { isAccessible = true }
            if (sessionField.get(null) == null) {
                val session = com.droidance.core.device.agent.device.RuntimePlatformDriver.connect().windowSession
                val api = Class.forName("android.view.IWindowSession\$Stub")
                    .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, session)
                sessionField.set(null, api)
            }
            host = SurfaceControlViewHost(appContext, display, createInputTransferToken())
            val view = object : RuntimeIslandSurfaceView(appContext) {
                override fun onAttachedToWindow() {
                    super.onAttachedToWindow()
                    // Unbuffered input shares the live island host, independently of the Activity.
                    requestUnbufferedDispatch(android.view.InputDevice.SOURCE_CLASS_POINTER)
                }

                override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
                    // The public host API supplies these params before attaching the root.
                    // Set the input flags here so normal app processes need no hidden API.
                    if (params is WindowManager.LayoutParams) {
                        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        params.setTitle("Droidance Runtime Island Input")
                    }
                    super.setLayoutParams(params)
                }
            }.apply {
                setOnTouchListener { view, event ->
                    view.requestUnbufferedDispatch(event)
                    onIndicatorInputEvent(event)
                }
            }
            host.setView(view, widthPx, heightPx)
            val pkg = host.surfacePackage ?: error("Island input surface unavailable")
            val bounds = bitmapRenderer.screenBounds()
            screenWidthPx = bounds.width()
            screenHeightPx = bounds.height()
            inputWidthPx = widthPx
            inputHeightPx = heightPx
            pendingPlacement?.let { pending ->
                val placed = RuntimeIslandDocking.place(pending.edge, pending.centerX, pending.centerY,
                    widthPx, heightPx, screenWidthPx, screenHeightPx)
                islandDragOffsetX = placed.left - baseLeftPx()
                islandDragOffsetY = placed.top - lastIslandBaseTopPx
                pendingPlacement = null
                val offsetX = islandDragOffsetX
                val offsetY = islandDragOffsetY
                appScope.launch(Dispatchers.IO) { RuntimeIslandPosition.write(appContext, offsetX, offsetY) }
            }
            islandDragOffsetX = clampIslandOffsetX(islandDragOffsetX)
            islandDragOffsetY = clampIslandOffsetY(islandDragOffsetY)
            try {
                SurfaceControl.Transaction().use {
                    it.reparent(pkg.surfaceControl, surface).setLayer(pkg.surfaceControl, 1)
                        .setCrop(pkg.surfaceControl, Rect(0, 0, widthPx, heightPx))
                        .setVisibility(pkg.surfaceControl, true)
                        .setPosition(surface, (baseLeftPx() + islandDragOffsetX).toFloat(),
                            (lastIslandBaseTopPx + islandDragOffsetY).toFloat()).apply()
                }
            } finally { pkg.release() }
            inputHost = host
            inputIslandView = view
            currentSpec?.let(::presentIsland)
            inputSurface = surface
            inputPositionTransaction = SurfaceControl.Transaction()
            val choreographer = Choreographer.getInstance()
            val positionFrame = Choreographer.VsyncCallback { frame ->
                inputPositionScheduler?.onFrame(frame.preferredFrameTimeline.vsyncId)
            }
            inputPositionScheduler = RuntimeIslandFrameScheduler(
                requestFrame = { choreographer.postVsyncCallback(positionFrame) },
                cancelFrame = { choreographer.removeVsyncCallback(positionFrame) },
                submitPosition = ::applyIslandPosition
            )
            inputWidthPx = widthPx
            inputHeightPx = heightPx
            inputStopButtonLeftPx = stopButtonLeftPx
            true
        } catch (error: Throwable) {
            host?.release()
            surface.release()
            debugLogManager.appendEvent("Runtime island input registration failed: ${error.message.orEmpty().take(160)}")
            false
        }
    }

    @androidx.annotation.RequiresApi(35)
    private fun createInputTransferToken(): InputTransferToken {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeStrongBinder(Binder())
            parcel.setDataPosition(0)
            InputTransferToken.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    @SuppressLint("NewApi")
    private fun unregisterIndicatorInput(): Boolean = runOnInputSync {
        inputPositionScheduler?.cancel()
        inputPositionScheduler = null
        inputPositionTransaction?.close()
        inputPositionTransaction = null
        inputHost?.release()
        inputHost = null
        inputIslandView = null
        inputSurface?.release()
        inputSurface = null
        inputWidthPx = 0
        inputHeightPx = 0
        inputDownAtMs = 0L
        dragging = false
        true
    }

    @SuppressLint("NewApi")
    private fun onIndicatorInputEvent(event: MotionEvent): Boolean {
        if (inputSuppressionDepth > 0) return false
        lastInputEventTimeMs = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inputIslandView?.setInteractionActive(true)
                inputDownRawX = event.rawX
                inputDownRawY = event.rawY
                inputDownAtMs = event.eventTime
                dragStartOffsetX = islandDragOffsetX
                dragStartOffsetY = islandDragOffsetY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - inputDownRawX
                val dy = event.rawY - inputDownRawY
                if (abs(dx) > tapSlopPx || abs(dy) > tapSlopPx) dragging = true
                if (dragging) moveIslandTo(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastIndicatorInputDispatchDelayMs = (SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0L)
                val dx = event.rawX - inputDownRawX
                val dy = event.rawY - inputDownRawY
                if (dragging) {
                    // Batched MOVE delivery can end before the finger's final point.
                    // Commit UP's position before persisting the shared offset.
                    if (event.actionMasked == MotionEvent.ACTION_UP) moveIslandTo(event.rawX, event.rawY)
                    inputPositionScheduler?.flush()
                    val edge = RuntimeIslandDocking.nearbyEdge(
                        baseLeftPx() + islandDragOffsetX, lastIslandBaseTopPx + islandDragOffsetY,
                        inputWidthPx, inputHeightPx, screenWidthPx, screenHeightPx,
                        (24f * appContext.resources.displayMetrics.density).toInt())
                    if (event.actionMasked == MotionEvent.ACTION_UP && collapsed != (edge != null)) {
                        changeCollapsed(edge != null, edge)
                    } else {
                        val offsetX = islandDragOffsetX
                        val offsetY = islandDragOffsetY
                        appScope.launch(Dispatchers.IO) { RuntimeIslandPosition.write(appContext, offsetX, offsetY) }
                    }
                } else if (event.actionMasked == MotionEvent.ACTION_UP && inputDownAtMs != 0L &&
                    dx * dx + dy * dy <= tapSlopPx * tapSlopPx &&
                    event.eventTime - inputDownAtMs in 0L..1000L) {
                    val density = appContext.resources.displayMetrics.density
                    val controlLeft = inputStopButtonLeftPx -
                        (RuntimeIslandStyle.CHIP_SIZE_DP * density).toInt() -
                        (RuntimeIslandStyle.CHIP_GAP_DP * density).toInt()
                    when {
                        collapsed -> changeCollapsed(false, RuntimeIslandDocking.nearbyEdge(
                            baseLeftPx() + islandDragOffsetX, lastIslandBaseTopPx + islandDragOffsetY,
                            inputWidthPx, inputHeightPx, screenWidthPx, screenHeightPx,
                            (24f * density).toInt()))
                        event.x >= inputStopButtonLeftPx -> stopClickListener?.invoke()
                        event.x >= controlLeft && currentSpec?.let { it.action != RuntimeSurfaceBitmapRenderer.IslandAction.NONE } == true ->
                            controlClickListener?.invoke()
                        else -> indicatorClickListener?.invoke()
                    }
                }
                dragging = false
                inputDownAtMs = 0L
                inputIslandView?.setInteractionActive(false)
            }
        }
        return true
    }

    /** Resize only after UP: the touch coordinate space remains stable throughout a drag. */
    private fun changeCollapsed(value: Boolean, edge: RuntimeIslandDocking.Edge?) {
        val spec = currentSpec ?: return
        val binding = lastBinding ?: return
        pendingPlacement = PendingPlacement(edge,
            baseLeftPx() + islandDragOffsetX + inputWidthPx / 2f,
            lastIslandBaseTopPx + islandDragOffsetY + inputHeightPx / 2f)
        collapsed = value
        val updated = spec.copy(collapsed = value)
        currentSpec = updated
        appScope.launch(Dispatchers.IO) { RuntimeIslandPosition.writeCollapsed(appContext, value) }
        startRender(binding, updated)
    }

    @SuppressLint("NewApi")
    private fun moveIslandTo(rawX: Float, rawY: Float) {
        val dx = (rawX - inputDownRawX).toInt()
        val dy = (rawY - inputDownRawY).toInt()
        islandDragOffsetX = clampIslandOffsetX(dragStartOffsetX + dx)
        islandDragOffsetY = clampIslandOffsetY(dragStartOffsetY + dy)
        // Keep the latest input; one transaction per display frame never waits for a new buffer.
        inputPositionScheduler?.request()
    }

    @SuppressLint("NewApi")
    private fun applyIslandPosition(vsyncId: Long) {
        val surface = inputSurface ?: return
        val posX = clampIslandLeft(baseLeftPx() + islandDragOffsetX).toFloat()
        val posY = clampIslandTop(lastIslandBaseTopPx + islandDragOffsetY).toFloat()
        Trace.beginSection("DroidanceIsland.position")
        try {
            inputPositionTransaction?.let {
                if (vsyncId != RuntimeIslandFrameScheduler.NO_FRAME_TIMELINE) it.setFrameTimeline(vsyncId)
                it.setPosition(surface, posX, posY).apply()
            }
            // Submission age is not presentation latency; correlate it with FrameTimeline in Perfetto.
            Trace.setCounter("DroidanceIsland.inputToSubmitMs",
                (SystemClock.uptimeMillis() - lastInputEventTimeMs).coerceAtLeast(0L))
            Trace.setCounter("DroidanceIsland.positionVsyncId", vsyncId)
            inputIslandView?.invalidate()
        } finally {
            Trace.endSection()
        }
    }

    private fun runOnInputSync(action: () -> Boolean): Boolean {
        if (Looper.myLooper() == inputHandler.looper) return action()
        val task = FutureTask(action)
        if (!inputHandler.post(task)) return false
        return task.get(3000L, TimeUnit.MILLISECONDS)
    }

    private fun baseLeftPx(): Int =
        (screenWidthPx - inputWidthPx) / 2

    private fun clampIslandLeft(leftPx: Int): Int {
        val maxLeft = (screenWidthPx - inputWidthPx).coerceAtLeast(0)
        return leftPx.coerceIn(0, maxLeft)
    }

    private fun clampIslandOffsetX(offsetX: Int): Int =
        clampIslandLeft(baseLeftPx() + offsetX) - baseLeftPx()

    /** 岛整体必须留在屏幕内，否则拖出去就再也点不回来。 */
    private fun clampIslandTop(topPx: Int): Int {
        val maxTop = (screenHeightPx - lastIslandHeightPx).coerceAtLeast(0)
        return topPx.coerceIn(0, maxTop)
    }

    private fun clampIslandOffsetY(offsetY: Int): Int =
        clampIslandTop(lastIslandBaseTopPx + offsetY) - lastIslandBaseTopPx

    private companion object {
        const val ACTION_NONE = 0
        const val ACTION_TAP = 1
        const val ACTION_SWIPE = 2
    }
}
