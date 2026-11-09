// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*


import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import androidx.compose.ui.graphics.toArgb
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.droidance.R
import com.droidance.MainActivity
import com.droidance.agent.AgentRuntime
import com.droidance.core.agent.AgentState
import com.droidance.core.agent.di.ApplicationScope
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.agent.event.AgentThreadProjectionResult
import com.droidance.agent.OperationRecorder
import com.droidance.core.agent.UserDemonstrationActionType
import com.droidance.core.agent.UserDemonstrationGestureClassifier
import com.droidance.debug.DebugLogManager
import com.droidance.ui.overlay.AuroraEdgeView
import com.droidance.ui.overlay.RuntimeIslandPosition
import com.droidance.ui.overlay.RuntimeIslandStyle
import com.droidance.ui.overlay.RuntimeIslandTextPainter
import com.droidance.ui.overlay.RuntimeIslandPresentation
import com.droidance.ui.overlay.RuntimeSurfaceController
import com.droidance.ui.overlay.TouchIndicatorView
import com.droidance.ui.overlay.VirtualDisplayTouchMapper
import com.droidance.ui.overlay.VirtualSpaceHandCue
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 虚拟空间的普通 Activity 查看页。画面和输入始终绑定当前 VirtualDisplay；本页本身不创建悬浮窗。
 */
@AndroidEntryPoint
class VirtualSpaceViewerActivity : AppCompatActivity() {
    @Inject lateinit var virtualSpaceManager: VirtualSpaceManager
    @Inject lateinit var agentRuntime: AgentRuntime
    @Inject lateinit var eventStore: AgentEventStore
    @Inject lateinit var runtimeSurfaceController: RuntimeSurfaceController
    @Inject lateinit var debugLogManager: DebugLogManager
    @Inject lateinit var operationRecorder: OperationRecorder
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private lateinit var rootContainer: FrameLayout
    private lateinit var previewContainer: FrameLayout
    @Volatile private var lastIsLightStatusBars: Boolean? = null
    @Volatile private var lastIsLightNavBars: Boolean? = null
    private var currentTopInsetPx = 0
    private var currentBottomInsetPx = 0
    private var currentLeftInsetPx = 0
    private var currentRightInsetPx = 0

    private lateinit var navigationBackground: View
    private lateinit var previewView: SurfaceView
    private lateinit var viewerMirror: VirtualSpaceViewerMirror
    private var viewerVisible = false
    private var previewSurfaceReady = false
    private var displayedBinding: SidecarSessionBinding? = null
    private lateinit var auroraView: AuroraEdgeView
    private lateinit var handView: TouchIndicatorView
    private lateinit var errorView: TextView
    private lateinit var emptyView: TextView
    private var hostNavigationBottomInsetPx = 0
    private var nativeImeBottomInsetPx = 0
    private lateinit var islandView: ViewerControlIslandView
    private var islandDragStartRawX = 0f
    private var islandDragStartRawY = 0f
    private var islandDragStartOffsetX = 0
    private var islandDragStartOffsetPx = 0
    private var islandDragOffsetX = 0
    private var islandDragOffsetPx = 0
    private var lastManualState: Boolean? = null
    private var frameFailures = 0
    private var displayContentState = VirtualDisplayContentState.UNAVAILABLE
    private val touchSamples = Channel<TouchSample>(Channel.UNLIMITED)
    private val pendingTouchSamples = AtomicInteger(0)
    private val touchInjectionFailures = AtomicInteger(0)
    private val pendingLearningCaptures = AtomicInteger(0)
    private var closingTeachingSession = false
    private var stopRequested = false
    private var returningToResult = false
    private var backDispatchInFlight = false
    private var textInputDialog: AlertDialog? = null
    private var textInputOpening = false
    private val performanceTracker = VirtualSpaceViewerPerformanceTracker()
    private val density: Float get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mirroring a display into a SurfaceView on that same display creates a compositor cycle.
        @Suppress("DEPRECATION")
        if (windowManager.defaultDisplay.displayId != Display.DEFAULT_DISPLAY) {
            Toast.makeText(this, R.string.virtual_space_viewer_open_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // This host activity is launched on the main display while the target app
        // remains on the virtual display. It must own main-display focus; otherwise
        // covering MainActivity leaves the process with no focused window and the
        // system reports an input-dispatch ANR when the viewer is closed.
        // On Android 14+, manual text is entered here so the virtual display never needs top focus.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        if (!hasViewableVirtualSpace()) {
            Toast.makeText(this, R.string.virtual_space_viewer_open_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        islandDragOffsetX = RuntimeIslandPosition.readX(this)
        islandDragOffsetPx = RuntimeIslandPosition.read(this)
        viewerMirror = VirtualSpaceViewerMirror(virtualSpaceManager) { binding, attached ->
            displayedBinding = binding.takeIf { attached && viewerVisible && it == virtualSpaceManager.activeBinding() }
            if (binding != null && viewerVisible) {
                // A healthy mirror does not clear a frame failure that is still being reported.
                if (!attached) showChannelError()
                else if (frameFailures < FRAME_FAILURE_LIMIT) errorView.visibility = View.GONE
            }
        }
        setContentView(buildContentView())
        refreshControlState()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = sendVirtualBack("system")
        })
        lifecycleScope.launch(Dispatchers.IO) {
            for (sample in touchSamples) {
                val dispatchStartedAtMs = SystemClock.uptimeMillis()
                try {
                    sample.beforeFrame?.complete(virtualSpaceManager.captureLatestPreviewJpeg())
                    if (!virtualSpaceManager.injectTouch(
                            action = sample.action,
                            x = sample.point.x,
                            y = sample.point.y,
                            downTimeMs = sample.downTimeMs,
                            eventTimeMs = sample.eventTimeMs
                        )
                    ) {
                        touchInjectionFailures.incrementAndGet()
                        showChannelError()
                    }
                } finally {
                    sample.beforeFrame?.complete(null)
                    pendingTouchSamples.decrementAndGet()
                    performanceTracker.recordTouchDispatched(
                        queueLagMs = dispatchStartedAtMs - sample.queuedAtMs,
                        injectionMs = SystemClock.uptimeMillis() - dispatchStartedAtMs
                    )
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    while (isActive) {
                        refreshFrame()
                        delay(CONTENT_INSPECTION_INTERVAL_MS)
                    }
                }
                launch {
                    while (isActive) {
                        refreshNativeImeInsets()
                        delay(NATIVE_IME_INSPECTION_INTERVAL_MS)
                    }
                }
                launch {
                    agentRuntime.isPaused.collect { manual ->
                        if (manual != lastManualState) refreshControlState(manual)
                    }
                }
                launch {
                    combine(agentRuntime.currentTaskTitle, agentRuntime.state,
                        agentRuntime.manualTakeoverAvailable) { _, _, _ -> Unit }
                        .collect {
                            refreshControlState()
                            markIslandActive()
                        }
                }
                launch {
                    runtimeSurfaceController.virtualSpaceHandCue.collect(::replayHandCue)
                }
                launch { returnAfterTaskCompletion() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewerVisible = true
        if (::previewView.isInitialized) {
            refreshControlState()
            runtimeSurfaceController.setViewerExpanded(true)
            val displayId = virtualSpaceManager.activeDisplayId()
            if (displayId != null) {
                runtimeSurfaceController.ensureVirtualSpaceCursor(displayId)
            }
        }
    }

    // A cross-display focus handoff can pause this Activity while it stays visible.
    // Keep the mirror and its IME owner until the viewer actually leaves the screen.
    override fun onStop() {
        viewerVisible = false
        textInputDialog?.dismiss()
        virtualSpaceManager.authorizeViewerIme(null)
        displayedBinding = null
        if (::viewerMirror.isInitialized) viewerMirror.update(null)
        nativeImeBottomInsetPx = 0
        updateInputBottomInset()
        runtimeSurfaceController.setViewerExpanded(false)
        super.onStop()
    }

    override fun onDestroy() {
        touchSamples.close()
        if (::viewerMirror.isInitialized) viewerMirror.close()
        super.onDestroy()
    }

    private fun hasViewableVirtualSpace(): Boolean = virtualSpaceManager.activeBinding() != null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun returnAfterTaskCompletion() {
        agentRuntime.currentConversationId.flatMapLatest { conversationId ->
            if (conversationId == null) emptyFlow()
            else eventStore.observeThreadProjectionResult(conversationId)
                .map { projection ->
                    (projection as? AgentThreadProjectionResult.Projected)?.thread
                        ?.takeUnless { it.metadata.tombstoned }?.turns.orEmpty()
                }
                .completedViewerTasks()
        }.collect { turn ->
            if (!viewerVisible || isTeaching() || stopRequested || returningToResult || isFinishing) return@collect
            returningToResult = true
            startActivity(MainActivity.taskResultIntent(this, turn.threadId))
            finish()
        }
    }

    private fun buildContentView(): View {
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        previewContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        window.attributes = window.attributes.apply { preferredRefreshRate = 60f }
        previewView = SurfaceView(this@VirtualSpaceViewerActivity).apply {
            setOnTouchListener(buildTouchListener())
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    previewSurfaceReady = true
                    updateViewerMirror()
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    updateViewerMirror()
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    previewSurfaceReady = false
                    displayedBinding = null
                    viewerMirror.update(null)
                }
            })
        }
        previewContainer.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Own the physical navigation background above the complete, unscaled mirror.
        // OEM app decoration layers can otherwise alternate black/white inside mirrorDisplay.
        navigationBackground = View(this).apply { setBackgroundColor(Color.BLACK) }
        previewContainer.addView(navigationBackground,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))

        // 小手画在镜像之上、不可点击（触摸照常穿透给下面的预览做人工接管）。
        handView = TouchIndicatorView(this@VirtualSpaceViewerActivity)
        handView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                replayHandCue(runtimeSurfaceController.virtualSpaceHandCue.value)
            }
        }
        previewContainer.addView(
            handView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 执行中的边缘呼吸流光：虚拟空间只在这块投屏里发光，主屏不亮。
        // 与接管镜像面板用的是同一个 View，不处理触摸。
        auroraView = AuroraEdgeView(this@VirtualSpaceViewerActivity)
        previewContainer.addView(
            auroraView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        rootContainer.addView(
            previewContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        emptyView = TextView(this@VirtualSpaceViewerActivity).apply {
            setTextColor(Color.parseColor(RuntimeIslandStyle.LABEL_SECONDARY))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(20), dp(28), dp(20))
            setText(R.string.virtual_space_viewer_empty)
            visibility = View.GONE
        }
        rootContainer.addView(
            emptyView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        errorView = TextView(this@VirtualSpaceViewerActivity).apply {
            setTextColor(com.droidance.ui.theme.StatusOnErrorContainerDark.toArgb())
            textSize = com.droidance.ui.theme.droidanceTypography(isDark = true).bodySmall.fontSize.value
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            text = getString(R.string.takeover_channel_error)
            background = GradientDrawable().apply {
                setColor(com.droidance.ui.theme.StatusErrorContainerDark.copy(alpha = 0.9f).toArgb())
                cornerRadius = dp(14).toFloat()
            }
            visibility = View.GONE
        }
        rootContainer.addView(
            errorView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                topMargin = dp(18)
                marginStart = dp(20)
                marginEnd = dp(20)
            }
        )

        // The viewer reserves room for manual Back without resizing on state changes.
        // Its placement stays shared with the collapsed runtime island.
        islandView = ViewerControlIslandView(
            this, ::closeViewer, onBack = { sendVirtualBack("island") }, onToggle = ::toggleTakeover,
            onTextInput = ::showTextInput,
            onStop = ::stopTaskFromIsland,
            onDragStart = ::beginIslandDrag, onDragMove = ::dragIslandTo, onDragEnd = ::endIslandDrag
        )
        rootContainer.addView(
            islandView,
            FrameLayout.LayoutParams(
                islandWidthPx(),
                islandHeightPx()
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = islandBottomMarginPx(resources.displayMetrics.heightPixels)
            }
        )
        islandView.translationY = islandDragOffsetPx.toFloat()
        islandView.translationX = islandDragOffsetX.toFloat()
        rootContainer.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                applyIslandOffset(islandDragOffsetPx)
            }
        }
        // 存下来的偏移可能来自别的屏幕尺寸或方向，布局出来后先钳回屏内再用。
        islandView.post { applyIslandOffset(islandDragOffsetPx) }

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            auroraView.updateWindowInsets(insets.toWindowInsets())
            val binding = virtualSpaceManager.activeBinding()
            val geometry = VirtualDisplayHostInsets.read(windowManager)
            if (binding != null && geometry != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    virtualSpaceManager.synchronizeDisplayInsets(binding, geometry)
                }
            }
            val statusInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navInsets = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars()
            )
            hostNavigationBottomInsetPx = navInsets.bottom
            (navigationBackground.layoutParams as FrameLayout.LayoutParams).let { params ->
                if (params.height != navInsets.bottom) {
                    params.height = navInsets.bottom
                    navigationBackground.layoutParams = params
                }
            }
            applySystemBarInsets(
                topInset = statusInsets.top,
                bottomInset = inputBottomInset(),
                leftInset = maxOf(statusInsets.left, navInsets.left),
                rightInset = maxOf(statusInsets.right, navInsets.right)
            )
            insets
        }

        return rootContainer
    }

    private fun inputBottomInset(): Int = maxOf(hostNavigationBottomInsetPx, nativeImeBottomInsetPx)

    @SuppressLint("NewApi") // The execution display IME policy already requires Android 12.
    private fun updateNativeImeViewport() {
        // The editor stays on the virtual display. The main display mirrors the virtual space
        // at 1:1 scale without viewport compression, including the source display's IME.
        val attributes = window.attributes
        if (attributes.height != WindowManager.LayoutParams.MATCH_PARENT) {
            attributes.height = WindowManager.LayoutParams.MATCH_PARENT
            attributes.gravity = Gravity.FILL
            window.attributes = attributes
        }
    }

    private fun updateInputBottomInset() {
        if (!::islandView.isInitialized) return
        updateNativeImeViewport()
        applySystemBarInsets(currentTopInsetPx, inputBottomInset(), currentLeftInsetPx, currentRightInsetPx)
    }

    private suspend fun refreshNativeImeInsets() {
        val binding = displayedBinding?.takeIf { viewerVisible && manualInputEnabled() } ?: return
        val bottom = withContext(Dispatchers.IO) { virtualSpaceManager.viewerImeBottomInset(binding) } ?: return
        if (!viewerVisible || !manualInputEnabled() || displayedBinding != binding ||
            virtualSpaceManager.activeBinding() != binding) return
        if (nativeImeBottomInsetPx != bottom) {
            nativeImeBottomInsetPx = bottom
            updateInputBottomInset()
        }
    }

    private fun applySystemBarInsets(
        topInset: Int,
        bottomInset: Int,
        leftInset: Int,
        rightInset: Int
    ) {
        if (currentTopInsetPx == topInset &&
            currentBottomInsetPx == bottomInset &&
            currentLeftInsetPx == leftInset &&
            currentRightInsetPx == rightInset
        ) return
        currentTopInsetPx = topInset
        currentBottomInsetPx = bottomInset
        currentLeftInsetPx = leftInset
        currentRightInsetPx = rightInset

        var layoutChanged = false
        // 投屏画面保持全屏贴合（与主屏 Edge-to-Edge 视觉一致，绝不对画面强行缩进产生黑边），
        // 仅对操作悬浮层（错误条、操作岛）做系统栏安全区避让。
        (errorView.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val targetTop = topInset + dp(18)
            val targetStart = leftInset + dp(20)
            val targetEnd = rightInset + dp(20)
            if (lp.topMargin != targetTop || lp.marginStart != targetStart || lp.marginEnd != targetEnd) {
                lp.topMargin = targetTop
                lp.marginStart = targetStart
                lp.marginEnd = targetEnd
                errorView.layoutParams = lp
                layoutChanged = true
            }
        }
        (islandView.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val targetBottom = islandBottomMarginPx(rootContainer.height.takeIf { it > 0 }
                ?: resources.displayMetrics.heightPixels)
            val targetWidth = islandWidthPx()
            if (lp.bottomMargin != targetBottom || lp.width != targetWidth) {
                lp.bottomMargin = targetBottom
                lp.width = targetWidth
                islandView.layoutParams = lp
                layoutChanged = true
            }
        }
        if (layoutChanged) {
            islandView.post { applyIslandOffset(islandDragOffsetPx) }
        }
    }

    /** 岛宽定死，只有窄屏才收：左右各留 [RuntimeIslandStyle.SIDE_MIN_MARGIN_DP]。 */
    private fun islandWidthPx(): Int {
        val maxWidth = resources.displayMetrics.widthPixels - currentLeftInsetPx - currentRightInsetPx -
            dp(RuntimeIslandStyle.SIDE_MIN_MARGIN_DP.toInt()) * 2
        return (RuntimeIslandStyle.VIEWER_WIDTH_DP * density).toInt().coerceAtMost(maxWidth)
    }

    private fun islandHeightPx(): Int = maxOf(
        dp(RuntimeIslandStyle.VIEWER_HEIGHT_DP.toInt()), RuntimeIslandTextPainter(this).heightPx()
    )

    private fun islandBottomMarginPx(parentHeight: Int): Int =
        maxOf(currentBottomInsetPx, parentHeight - islandHeightPx() -
            RuntimeIslandPosition.defaultTopPx(parentHeight, islandHeightPx()))

    private fun refreshControlState(manual: Boolean = agentRuntime.isPaused.value) {
        val teaching = isTeaching()
        val inputEnabled = manual || teaching
        // 接管状态一变就算活跃：岛淡下去之后也要立刻亮回来把这件事说清楚。
        if (manual != lastManualState) {
            lastManualState = manual
            markIslandActive()
        }
        previewView.isClickable = inputEnabled
        virtualSpaceManager.authorizeViewerIme(virtualSpaceManager.activeBinding().takeIf { viewerVisible && inputEnabled })
        if (!inputEnabled) {
            textInputDialog?.dismiss()
            nativeImeBottomInsetPx = 0
            updateInputBottomInset()
        }
        displayedBinding = null
        updateViewerMirror()
        val waitingPermission = agentRuntime.state.value == AgentState.WAITING_PERMISSION
        val waitingTakeover = agentRuntime.manualTakeoverAvailable.value
        val active = agentRuntime.isMobileUseTaskActive.value
        val presentationState = when {
            teaching -> RuntimeIslandPresentation.State.DEMONSTRATING
            waitingPermission -> RuntimeIslandPresentation.State.WAITING_PERMISSION
            manual -> RuntimeIslandPresentation.State.USER_CONTROL
            !active -> RuntimeIslandPresentation.State.READY
            waitingTakeover -> RuntimeIslandPresentation.State.WAITING_FOR_USER
            else -> RuntimeIslandPresentation.State.EXECUTING
        }
        islandView.render(
            label = RuntimeIslandPresentation.label(this, presentationState, agentRuntime.currentTaskTitle.value),
            manual = manual, teaching = teaching, state = presentationState
        )
        islandView.post { applyIslandOffset(islandDragOffsetPx) }
        // 暂停条 = 交给我操作，播放三角 = 把控制交回 agent，与主屏运行岛的 drawActionGlyph 同义。
    }

    private fun stopTaskFromIsland() {
        if (isTeaching()) {
            closeViewer()
            return
        }
        if (stopRequested) return
        stopRequested = true
        val turnId = agentRuntime.taskTiming.value?.turnId
        // Cancellation and disk-backed residency cleanup must survive this Activity and leave its UI responsive.
        appScope.launch(Dispatchers.IO) {
            if (agentRuntime.taskTiming.value?.turnId == turnId) {
                agentRuntime.stopCurrentMobileUse(origin = "virtual_space_viewer_stop")
            }
            withContext(Dispatchers.Main) {
                if (!isFinishing) finish()
            }
        }
    }

    private fun toggleTakeover() {
        if (agentRuntime.isPaused.value) {
            agentRuntime.resumeManualTakeover()
        } else {
            agentRuntime.beginManualTakeoverFromOverlay()
        }
        refreshControlState()
    }

    /** Redraw controls immediately after a state change or direct interaction. */
    private fun markIslandActive() {
        if (::islandView.isInitialized) islandView.markActive()
    }

    private fun beginIslandDrag(rawX: Float, rawY: Float) {
        markIslandActive()
        islandDragStartRawX = rawX
        islandDragStartRawY = rawY
        islandDragStartOffsetX = islandDragOffsetX
        islandDragStartOffsetPx = islandDragOffsetPx
    }

    private fun dragIslandTo(rawX: Float, rawY: Float) {
        islandDragOffsetX = islandDragStartOffsetX + (rawX - islandDragStartRawX).toInt()
        applyIslandOffset(islandDragStartOffsetPx + (rawY - islandDragStartRawY).toInt())
    }

    /** translationY 往下为正，岛贴在底部，往上拖是负偏移；整体必须留在屏幕内。 */
    private fun applyIslandOffset(offsetPx: Int) {
        val parent = islandView.parent as? View ?: rootContainer
        val parentHeight = parent.height.takeIf { it > 0 } ?: previewView.height.takeIf { it > 0 } ?: return
        // Use layout margins, not stale view.top, while an inset/IME change is being laid out.
        val lp = islandView.layoutParams as FrameLayout.LayoutParams
        val height = islandView.height.takeIf { it > 0 } ?: islandHeightPx()
        val bottomMargin = islandBottomMarginPx(parentHeight)
        if (lp.bottomMargin != bottomMargin) {
            lp.bottomMargin = bottomMargin
            islandView.layoutParams = lp
        }
        val controlsBottom = parentHeight - lp.bottomMargin
        val controlsTop = controlsBottom - height
        val maxUp = currentTopInsetPx - controlsTop
        val maxDown = parentHeight - currentBottomInsetPx - controlsBottom
        islandDragOffsetPx = offsetPx.coerceIn(maxUp, maxDown.coerceAtLeast(maxUp))
        islandView.translationY = islandDragOffsetPx.toFloat()
        val width = islandView.width.takeIf { it > 0 } ?: islandWidthPx()
        val baseLeft = (parent.width - width) / 2
        val minX = currentLeftInsetPx - baseLeft
        val maxX = parent.width - currentRightInsetPx - width - baseLeft
        islandDragOffsetX = islandDragOffsetX.coerceIn(minX, maxX.coerceAtLeast(minX))
        islandView.translationX = islandDragOffsetX.toFloat()
    }

    private fun endIslandDrag() {
        RuntimeIslandPosition.write(this, islandDragOffsetX, islandDragOffsetPx)
    }

    private fun sendVirtualBack(source: String) {
        val binding = displayedBinding
        debugLogManager.appendEvent(
            "virtual_space: back requested source=$source hostFocus=${hasWindowFocus()} " +
                "display=${binding?.displayId} manual=${manualInputEnabled()} pending=$backDispatchInFlight"
        )
        if (!viewerVisible || !manualInputEnabled() || stopRequested || closingTeachingSession || backDispatchInFlight) return
        // Back belongs to the app being handled. Only the explicit collapse control closes this host.
        if (binding == null || binding != virtualSpaceManager.activeBinding()) {
            showChannelError()
            return
        }
        backDispatchInFlight = true
        val recordingKey = isTeaching()
        if (recordingKey) pendingLearningCaptures.incrementAndGet()
        lifecycleScope.launch {
            try {
                awaitInputSettlement()
                if (!canSendVirtualBack(binding)) return@launch
                val beforeFrame = if (recordingKey) withContext(Dispatchers.IO) {
                    virtualSpaceManager.captureLatestPreviewJpeg()
                } else null
                if (!canSendVirtualBack(binding)) return@launch
                // One display-bound Back lets the target IME consume it before app navigation.
                // Hiding the host IME as well can turn a single gesture into two back actions.
                val sent = withContext(Dispatchers.IO) {
                    virtualSpaceManager.injectKey(binding, KeyEvent.KEYCODE_BACK)
                }
                debugLogManager.appendEvent("virtual_space: back dispatched source=$source display=${binding.displayId} sent=$sent")
                if (!sent) showChannelError()
                if (recordingKey && binding == virtualSpaceManager.activeBinding()) {
                    withContext(Dispatchers.IO) {
                        recordKeyAfterSettling(
                            keyCode = KeyEvent.KEYCODE_BACK,
                            actionType = UserDemonstrationActionType.BACK,
                            beforeFrame = beforeFrame,
                            success = sent
                        )
                    }
                }
            } finally {
                backDispatchInFlight = false
                if (recordingKey) pendingLearningCaptures.decrementAndGet()
            }
        }
    }

    private fun showTextInput() {
        val binding = displayedBinding ?: return
        if (!canSendVirtualBack(binding) || textInputDialog != null || textInputOpening) return
        textInputOpening = true
        lifecycleScope.launch {
            try {
                awaitInputSettlement()
                if (!canSendVirtualBack(binding)) return@launch
                val target = withContext(Dispatchers.IO) { virtualSpaceManager.viewerInputTarget(binding) }
                if (target == null || !canSendVirtualBack(binding)) {
                    showChannelError()
                    return@launch
                }
                val editor = EditText(this@VirtualSpaceViewerActivity).apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                }
                val dialog = AlertDialog.Builder(this@VirtualSpaceViewerActivity)
                    .setTitle(R.string.takeover_text_input)
                    .setView(editor)
                    .setNegativeButton(R.string.common_cancel, null)
                    .setPositiveButton(R.string.takeover_insert_text, null)
                    .setNeutralButton(R.string.takeover_enter_key, null)
                    .create()
                textInputDialog = dialog
                dialog.setOnDismissListener { if (textInputDialog === dialog) textInputDialog = null }
                dialog.setOnShowListener {
                    editor.requestFocus()
                    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                    editor.post {
                        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                            .showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                    fun submit(sendEnter: Boolean) {
                        val text = editor.text.toString()
                        if ((!sendEnter && text.isEmpty()) || !canSendVirtualBack(binding)) return
                        // A user submission is one delivery attempt, including an unknown timeout.
                        dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false
                        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = false
                        editor.isEnabled = false
                        lifecycleScope.launch {
                            val recording = isTeaching()
                            if (recording) pendingLearningCaptures.incrementAndGet()
                            try {
                                val before = if (recording) withContext(Dispatchers.IO) {
                                    virtualSpaceManager.captureLatestPreviewJpeg()
                                } else null
                                val sent = withContext(Dispatchers.IO) {
                                    val typed = text.isEmpty() || virtualSpaceManager.injectViewerText(binding, target, text)
                                    typed && (!sendEnter || virtualSpaceManager.injectViewerKey(binding, target, KeyEvent.KEYCODE_ENTER))
                                }
                                if (sent) dialog.dismiss() else showChannelError()
                                if (recording && binding == virtualSpaceManager.activeBinding()) withContext(Dispatchers.IO) {
                                    recordKeyAfterSettling(KeyEvent.KEYCODE_PASTE,
                                        UserDemonstrationActionType.TEXT_INPUT, before, sent)
                                }
                            } finally {
                                if (recording) pendingLearningCaptures.decrementAndGet()
                            }
                        }
                    }
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { submit(false) }
                    dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener { submit(true) }
                }
                dialog.show()
            } finally {
                textInputOpening = false
            }
        }
    }

    private fun canSendVirtualBack(binding: SidecarSessionBinding): Boolean {
        val ready = viewerVisible && manualInputEnabled() && !stopRequested && !closingTeachingSession &&
            displayedBinding == binding && binding == virtualSpaceManager.activeBinding() && pendingTouchSamples.get() == 0
        if (!ready) {
            debugLogManager.appendEvent("virtual_space: back dropped after input settlement display=${binding.displayId}")
        }
        return ready
    }

    private fun applyFrameAppearance(colors: ViewerSystemBarColors) {
        navigationBackground.setBackgroundColor(colors.navigation)
        val statusLight = ViewerSystemBarColors.isLight(colors.status, lastIsLightStatusBars)
        val navigationLight = ViewerSystemBarColors.isLight(colors.navigation, lastIsLightNavBars)
        if (lastIsLightStatusBars == statusLight && lastIsLightNavBars == navigationLight) return
        lastIsLightStatusBars = statusLight
        lastIsLightNavBars = navigationLight
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = statusLight
        controller.isAppearanceLightNavigationBars = navigationLight
    }

    private fun updateViewerMirror() {
        if (!::viewerMirror.isInitialized || !::previewView.isInitialized) return
        val binding = virtualSpaceManager.activeBinding()
        if (!viewerVisible || !previewSurfaceReady || binding == null ||
            displayContentState != VirtualDisplayContentState.CONTENT_PRESENT
        ) {
            displayedBinding = null
            viewerMirror.update(null)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            showChannelError()
            return
        }
        val parent = previewView.surfaceControl
        if (parent == null || !parent.isValid) return
        viewerMirror.update(VirtualSpaceViewerMirror.Target(binding, parent, previewView.width, previewView.height,
            nativeImeEnabled = manualInputEnabled()))
    }

    /** Only health and a 64x64 luminance sample are polled; pixels are displayed by the compositor. */
    private suspend fun refreshFrame() {
        val binding = virtualSpaceManager.activeBinding()
        if (binding == null) {
            closeViewer()
            return
        }
        val content = withContext(Dispatchers.IO) { virtualSpaceManager.inspectDisplayContent(binding) }
        if (!viewerVisible || virtualSpaceManager.activeBinding() != binding) return
        displayContentState = content
        updateViewerMirror()
        when (content) {
            VirtualDisplayContentState.EMPTY -> {
                frameFailures = 0
                errorView.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
            }
            VirtualDisplayContentState.UNAVAILABLE -> {
                emptyView.visibility = View.GONE
                handleFrameFailure(binding)
            }
            VirtualDisplayContentState.CONTENT_PRESENT -> {
                emptyView.visibility = View.GONE
                val topInset = currentTopInsetPx
                val navigationInset = hostNavigationBottomInsetPx
                val colors = withContext(Dispatchers.IO) {
                    virtualSpaceManager.sampleViewerBarColors(binding, topInset, navigationInset)
                }
                if (!viewerVisible || virtualSpaceManager.activeBinding() != binding) return
                if (colors == null) handleFrameFailure(binding) else {
                    frameFailures = 0
                    applyFrameAppearance(colors)
                }
            }
        }
        performanceTracker.report(SystemClock.uptimeMillis())?.let { report ->
            lifecycleScope.launch(Dispatchers.IO) { debugLogManager.appendEvent(report.toLogLine()) }
        }
    }

    private suspend fun handleFrameFailure(binding: SidecarSessionBinding) {
        frameFailures += 1
        if (frameFailures < FRAME_FAILURE_LIMIT) return
        val bindingAlive = withContext(Dispatchers.IO) {
            virtualSpaceManager.hasLiveSessionForMode(binding.runtimeMode)
        }
        if (!bindingAlive) {
            virtualSpaceManager.discardStaleSession(
                "viewer frame failures reached the limit and sidecar binding is dead"
            )
            closeViewer()
            return
        }
        showChannelError()
    }

    /** 把虚拟屏坐标的动作换算到镜像视图坐标，让小手在预览上跟着 agent 走。 */
    private fun replayHandCue(cue: VirtualSpaceHandCue?) {
        if (!::handView.isInitialized) return
        if (cue == null) {
            handView.showCue(null)
            return
        }
        if (cue.displayId != virtualSpaceManager.activeDisplayId()) {
            handView.showCue(null)
            return
        }
        val size = virtualSpaceManager.activeDisplaySize() ?: return
        val mapped = VirtualDisplayTouchMapper.mapActionToView(
            action = cue.action,
            viewWidth = handView.width,
            viewHeight = handView.height,
            displayWidth = size.first,
            displayHeight = size.second
        ) ?: return
        val mappedFrom = VirtualDisplayTouchMapper.mapDisplayToView(
            displayX = cue.fromX,
            displayY = cue.fromY,
            viewWidth = handView.width,
            viewHeight = handView.height,
            displayWidth = size.first,
            displayHeight = size.second
        )
        val fromX = mappedFrom?.x?.toInt() ?: cue.fromX
        val fromY = mappedFrom?.y?.toInt() ?: cue.fromY
        handView.showCue(cue.copy(action = mapped, fromX = fromX, fromY = fromY))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildTouchListener(): View.OnTouchListener {
        var lastPoint: VirtualDisplayTouchMapper.FramePoint? = null
        var startPoint: VirtualDisplayTouchMapper.FramePoint? = null
        var gestureStartedAtMs = 0L
        var beforeFrame: CompletableDeferred<ByteArray?>? = null
        var failuresAtStart = 0
        val recordedSamples = mutableListOf<com.droidance.core.agent.GestureSample>()
        var multiPointerObserved = false
        var samplesTruncated = false
        return View.OnTouchListener { view, event ->
            if (!manualInputEnabled()) return@OnTouchListener false
            if (displayedBinding == null || displayedBinding != virtualSpaceManager.activeBinding()) return@OnTouchListener false
            fun map(x: Float, y: Float): VirtualDisplayTouchMapper.FramePoint? {
                val size = virtualSpaceManager.activeDisplaySize() ?: return null
                return VirtualDisplayTouchMapper.mapViewToDisplay(
                    viewX = x,
                    viewY = y,
                    viewWidth = view.width,
                    viewHeight = view.height,
                    previewWidth = size.first,
                    previewHeight = size.second,
                    displayWidth = size.first,
                    displayHeight = size.second
                )
            }
            if (event.pointerCount > 1) multiPointerObserved = true
            fun enqueue(action: Int, point: VirtualDisplayTouchMapper.FramePoint, timeMs: Long) {
                if (isTeaching()) {
                    if (recordedSamples.size < 4096) recordedSamples += com.droidance.core.agent.GestureSample(point.x, point.y, timeMs - event.downTime)
                    else samplesTruncated = true
                }
                val queuedAtMs = SystemClock.uptimeMillis()
                val pendingCount = pendingTouchSamples.incrementAndGet()
                val sent = touchSamples.trySend(
                    TouchSample(action, point, event.downTime, timeMs, queuedAtMs,
                        beforeFrame.takeIf { action == MotionEvent.ACTION_DOWN })
                ).isSuccess
                if (sent) {
                    performanceTracker.recordTouchQueued(pendingCount)
                } else {
                    pendingTouchSamples.decrementAndGet()
                    if (action == MotionEvent.ACTION_DOWN) beforeFrame?.complete(null)
                }
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val point = map(event.x, event.y) ?: return@OnTouchListener false
                    recordedSamples.clear()
                    multiPointerObserved = false
                    samplesTruncated = false
                    startPoint = point
                    lastPoint = point
                    gestureStartedAtMs = event.eventTime
                    beforeFrame = if (isTeaching()) CompletableDeferred() else null
                    failuresAtStart = touchInjectionFailures.get()
                    enqueue(MotionEvent.ACTION_DOWN, point, event.eventTime)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    for (index in 0 until event.historySize) {
                        map(event.getHistoricalX(index), event.getHistoricalY(index))?.let { point ->
                            lastPoint = point
                            enqueue(MotionEvent.ACTION_MOVE, point, event.getHistoricalEventTime(index))
                        }
                    }
                    map(event.x, event.y)?.let { point ->
                        lastPoint = point
                        enqueue(MotionEvent.ACTION_MOVE, point, event.eventTime)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val point = map(event.x, event.y) ?: lastPoint ?: return@OnTouchListener false
                    val startedAt = gestureStartedAtMs
                    val startedPoint = startPoint ?: point
                    val capturedBefore = beforeFrame
                    val failureBaseline = failuresAtStart
                    enqueue(MotionEvent.ACTION_UP, point, event.eventTime)
                    lastPoint = null
                    startPoint = null
                    beforeFrame = null
                    if (isTeaching()) {
                        val size = virtualSpaceManager.activeDisplaySize()
                        if (size != null) {
                            val gesture = UserDemonstrationGestureClassifier.classify(
                                startX = startedPoint.x,
                                startY = startedPoint.y,
                                endX = point.x,
                                endY = point.y,
                                durationMillis = (event.eventTime - startedAt).coerceAtLeast(0L),
                                displayWidth = size.first,
                                displayHeight = size.second,
                                touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop
                            ).copy(samples = recordedSamples.toList(), multiPointerObserved = multiPointerObserved, samplesTruncated = samplesTruncated)
                            pendingLearningCaptures.incrementAndGet()
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    awaitInputSettlement()
                                    val afterFrame = virtualSpaceManager.captureLatestPreviewJpeg()
                                    operationRecorder.recordUserGesture(
                                        gesture = gesture,
                                        beforeFrame = capturedBefore?.await(),
                                        afterFrame = afterFrame,
                                        foregroundComponent = virtualSpaceManager.currentForegroundComponent(),
                                        success = touchInjectionFailures.get() == failureBaseline && !gesture.multiPointerObserved && !gesture.samplesTruncated
                                    )
                                } finally {
                                    pendingLearningCaptures.decrementAndGet()
                                }
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    lastPoint?.let { enqueue(MotionEvent.ACTION_CANCEL, it, event.eventTime) }
                    lastPoint = null
                    true
                }
                else -> false
            }
        }
    }

    private fun showChannelError() {
        runOnUiThread { if (::errorView.isInitialized) errorView.visibility = View.VISIBLE }
    }

    private fun closeViewer() {
        if (isTeaching() && !closingTeachingSession) {
            closingTeachingSession = true
            lifecycleScope.launch {
                val deadline = SystemClock.uptimeMillis() + LEARNING_CAPTURE_TIMEOUT_MS
                while (
                    pendingLearningCaptures.get() > 0 &&
                    SystemClock.uptimeMillis() < deadline
                ) {
                    delay(MIN_FRAME_YIELD_MS)
                }
                operationRecorder.stopRecording()
                withContext(Dispatchers.IO) { operationRecorder.awaitPendingWrites() }
                if (!isFinishing) finish()
            }
            return
        }
        if (!isFinishing) finish()
    }

    private fun isTeaching(): Boolean =
        operationRecorder.state.value == OperationRecorder.RecordingState.RECORDING &&
            operationRecorder.sessionInfo.value.captureMode ==
            OperationRecorder.USER_DEMONSTRATION_CAPTURE_MODE

    private fun manualInputEnabled(): Boolean = isTeaching() || agentRuntime.isPaused.value

    private suspend fun awaitInputSettlement() {
        val deadline = SystemClock.uptimeMillis() + INPUT_SETTLE_TIMEOUT_MS
        while (pendingTouchSamples.get() > 0 && SystemClock.uptimeMillis() < deadline) {
            delay(MIN_FRAME_YIELD_MS)
        }
        delay(POST_ACTION_FRAME_DELAY_MS)
    }

    private suspend fun recordKeyAfterSettling(
        keyCode: Int,
        actionType: UserDemonstrationActionType,
        beforeFrame: ByteArray?,
        success: Boolean
    ) {
        if (!isTeaching()) return
        delay(POST_ACTION_FRAME_DELAY_MS)
        operationRecorder.recordUserKey(
            keyCode = keyCode,
            actionType = actionType,
            beforeFrame = beforeFrame,
            afterFrame = virtualSpaceManager.captureLatestPreviewJpeg(),
            foregroundComponent = virtualSpaceManager.currentForegroundComponent(),
            success = success
        )
    }

    private fun roundedBackground(fill: String, radiusDp: Int, stroke: String? = null) =
        GradientDrawable().apply {
            setColor(Color.parseColor(fill))
            cornerRadius = dp(radiusDp).toFloat()
            stroke?.let { setStroke(dp(1).coerceAtLeast(1), Color.parseColor(it)) }
        }

    private fun dp(value: Int): Int = (value * density).toInt()

    private data class TouchSample(
        val action: Int,
        val point: VirtualDisplayTouchMapper.FramePoint,
        val downTimeMs: Long,
        val eventTimeMs: Long,
        val queuedAtMs: Long,
        val beforeFrame: CompletableDeferred<ByteArray?>? = null
    )

    private companion object {
        const val MIN_FRAME_YIELD_MS = 8L
        const val POST_ACTION_FRAME_DELAY_MS = 350L
        const val INPUT_SETTLE_TIMEOUT_MS = 1_000L
        const val LEARNING_CAPTURE_TIMEOUT_MS = 2_000L
        const val FRAME_FAILURE_LIMIT = 5
        const val CONTENT_INSPECTION_INTERVAL_MS = 1_000L
        const val NATIVE_IME_INSPECTION_INTERVAL_MS = 100L

        /** 岛内按钮是正圆底座：尺寸取一半做圆角，和主屏运行岛的图标按钮同形。 */
    }
}
