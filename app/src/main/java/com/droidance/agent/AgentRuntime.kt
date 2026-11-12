// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.debug.recordRuntimeFailure

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.tools.ToolTurnServices
import com.droidance.tools.ToolInteractionPort
import com.droidance.tools.ToolProgressPort
import com.droidance.core.agent.event.TurnActivationKind
import com.droidance.tools.ToolMemoryPort

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.R
import com.droidance.DroidanceApp
import com.droidance.automation.AppTakeoverStore
import com.droidance.debug.DebugLogManager
import com.droidance.core.device.debug.DiagnosticsCollector
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.DeviceExecutionStrategy
import com.droidance.agent.device.VirtualSpaceManager
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.agent.event.ConversationEntryKind
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.config.RuleConfigManager
import com.droidance.core.model.llm.LLMRouter
import com.droidance.core.model.llm.models.*
import com.droidance.core.knowledge.memory.DurableMemoryStore
import com.droidance.memory.ProgressiveMemoryContextProjector
import com.droidance.memory.SessionIndex
import com.droidance.core.knowledge.memory.TurnMemoryCandidateProjector
import com.droidance.daemon.TaskExecutionService
import com.droidance.runtime.RuntimeNotifications
import com.droidance.tools.ToolExecutor
import com.droidance.core.agent.tools.OperatorToolProfile
import com.droidance.tools.ToolRiskPolicy
import com.droidance.core.device.permission.PendingPermissionRequest
import com.droidance.core.device.permission.PermissionRequestOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val TAKEOVER_LIVE_OUTPUT_CHARS = 1_000
private const val DEVICE_SNAPSHOT_INIT_FAILED = "DEVICE_SNAPSHOT_INIT_FAILED"
internal const val INTERNAL_SKILL_THREAD_ID = "droidance-internal:skill-drafts"

/**
 * Agent 运行时
 * Droidance 的大脑，负责：
 * 1. 接收用户指令
 * 2. 将用户目标交给单一 Operator
 * 3. 执行工具调用并记录结果
 */
@Singleton
class AgentRuntime @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val llmRouter: LLMRouter,
    private val knowledge: RuntimeKnowledgeHandler,
    private val agentWorkspaceStore: AgentWorkspaceStore,
    private val toolExecutor: ToolExecutor,
    private val toolTurnServices: ToolTurnServices,
    private val sessionReadService: com.droidance.tools.SessionReadService,
    private val evidenceLedger: TaskEvidenceLedger,
    private val evidenceFactExtractor: EvidenceFactExtractor,
    private val localActionVerifier: LocalActionVerifier,
    private val ruleConfigManager: RuleConfigManager,
    private val languageManager: com.droidance.config.LanguageManager,
    private val runtimePromptPolicy: RuntimePromptPolicy,
    private val capabilityRegistry: RuntimeCapabilityRegistry,
    private val virtualSpaceManager: VirtualSpaceManager,
    val debugLogManager: DebugLogManager,
    // ── Operator Agent（UI 自动化执行） ──────────────────────────
    private val taskCoordinator: TaskCoordinator,
    private val latencyTracker: com.droidance.core.agent.debug.TaskLatencyTracker,
    private val diagnostics: DiagnosticsCollector,
    private val agentTurnLedger: AgentTurnLedger,
    private val taskLifecycle: TaskLifecycle,
    private val conversationHistoryReader: ConversationHistoryReader,
    private val eventStore: AgentEventStore,
    private val cacheMaintenance: RuntimeCacheMaintenance,
    private val appTakeoverStore: AppTakeoverStore,
    private val appTakeoverPromptBuilder: com.droidance.automation.AppTakeoverPromptBuilder,
    private val processRecoveryCoordinator: AgentProcessRecoveryCoordinator,
    private val foregroundState: AppForegroundState,
    private val adbLifecycleGate: com.droidance.core.device.air.AdbLifecycleGate,
    private val foregroundHost: ForegroundExecutionHost,
    private val processClient: com.droidance.runtime.RuntimeProcessClient
) : ToolInteractionPort, ConversationTaskRuntime {
    override val remoteQueue: Boolean get() = !processClient.isOwner
    override val remoteTasks get() = processClient.queuedTasks.takeUnless { processClient.isOwner }
    override fun removeRemoteQueued(conversationId: String, turnId: String): Boolean =
        processClient.command("removeQueued", android.os.Bundle().apply {
            putString("conversationId", conversationId); putString("turnId", turnId)
        }).getBoolean("result")
    override suspend fun steerRemoteQueued(conversationId: String, turnId: String): Boolean =
        withContext(Dispatchers.IO) { processClient.command("steerQueued", android.os.Bundle().apply {
            putString("conversationId", conversationId); putString("turnId", turnId)
        }).getBoolean("result") }
    private val finishScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _agentLoopState = MutableStateFlow(AgentLoopState.COMPLETED)
    val agentLoopState: StateFlow<AgentLoopState> = if (processClient.isOwner) _agentLoopState else processClient.agentLoopState

    /** Model switch callback for live UI status. */
    private var _onModelSwitchCallback: (suspend (String) -> Unit)? = null

    init {
        finishScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            llmRouter.modelSwitches.collect { event ->
                debugLogManager.appendEvent("Model switched: ${event.fromModel} → ${event.toModel}, reason=${event.reason}")
                _onModelSwitchCallback?.invoke(foregroundHost.localizedString(
                    R.string.runtime_model_switched, event.fromModel, event.toModel))
            }
        }
    }

    private fun runtimeTaskIdForSource(source: MessageSource): String = when (source) {
        MessageSource.SCHEDULED -> "scheduled"
        MessageSource.APP_TAKEOVER -> "app_takeover"
        MessageSource.DIRECT -> "ui"
    }

    override suspend fun steerConversationInput(conversationId: String, turnId: String, request: TurnRequest): Boolean {
        if (!processClient.isOwner) return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            processClient.command("steer", android.os.Bundle().apply { putString("conversationId", conversationId); putString("turnId", turnId); putString("request", kotlinx.serialization.json.Json.encodeToString(TurnRequest.serializer(), request)) }).getBoolean("result")
        }
        val active = activeDurableTurn ?: return false
        if (active.scope.threadId != conversationId || active.scope.turnId != turnId) return false
        return recordUserSteer("message:${request.requestId}", request.rawText, request.images, active.scope,
            request.requestedSkills)
    }

    private val userInputMutex = Mutex()
    private var inputClosed = false

    suspend fun recordUserSteer(
        messageId: String, text: String, images: List<String> = emptyList(),
        expectedScope: com.droidance.core.agent.event.TurnEventScope? = null,
        requestedSkills: List<com.droidance.core.agent.RequestedSkill> = emptyList()
    ): Boolean = userInputMutex.withLock {
        val activeTurn = activeDurableTurn ?: return@withLock false
        if (inputClosed || (expectedScope != null && activeTurn.scope != expectedScope)) return@withLock false
        try {
            // A steer carries exact user-selected identities; source files remain tool-read task data.
            val selections = if (requestedSkills.isEmpty()) "" else knowledge.selectedSkillContext(
                toolTurnServices.requireCurrent().sessionContext, requestedSkills, 0, messageId)
            agentTurnLedger.recordUserSteer(activeTurn, messageId,
                listOf(text, selections).filter(String::isNotBlank).joinToString("\n\n"), images)
            pendingUserSteer.set(true)
            cancelPendingUserInteraction(UserInteractionKind.HIGH_RISK_CONFIRMATION)
            // Finish an in-flight call, then discard its unexecuted plan at the next boundary.
            // Cancelling the Provider here also cancelled Phase A and surfaced a false task failure.
            debugLogManager.appendEvent("User steer persisted for the active Agent boundary")
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            debugLogManager.appendEvent("User steer unavailable: ${error::class.java.simpleName}")
            false
        }
    }

    private val pendingUserSteer = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 当前激活的 Agent 名称 */
    private var activeAgent: String = "default"

    /** 对话历史 */
    private val conversationHistory = mutableListOf<ChatMessage>()

    /**
     * 会话上下文代数。显式新对话会递增它；被取消的旧 Turn 即使进入 finally，也不能把取消回执
     * 追加到新 Turn 的全局历史，避免新任务继承旧任务的最后一句话。
     */
    @Volatile private var conversationEpoch: Long = 0L

    /** 当前可恢复 Turn；接管恢复消息必须写入同一 EventStore Turn。 */
    private val activeTurnClock = MutableStateFlow<AgentTurnLedger.ActiveTurn?>(null)
    @Volatile private var activeDurableTurn: AgentTurnLedger.ActiveTurn? = null
        set(value) {
            if (field?.scope != value?.scope) field?.scope?.let(ToolRiskPolicy::revoke)
            field = value
            activeTurnClock.value = value
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val taskTiming: StateFlow<LiveTaskTiming?> = if (!processClient.isOwner) processClient.taskTiming else activeTurnClock.flatMapLatest { active ->
        active?.executionClock?.state?.map { LiveTaskTiming(active.scope.turnId, it) } ?: flowOf(null)
    }.stateIn(finishScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    suspend fun applyMemoryUpdates(
        updates: List<com.droidance.core.knowledge.memory.DurableMemoryUpdate>
    ): Result<com.droidance.core.knowledge.memory.DurableMemoryApplyResult> {
        val active = activeDurableTurn
            ?: return Result.failure(IllegalStateException("No active Turn"))
        val callId = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[com.droidance.core.agent.tools.ToolInvocation]).callId
        return knowledge.applyUpdates(updates, active, callId) { activeDurableTurn === active }
    }

    /**
     * AgentRuntime 持有宿主会话状态；工具状态由当前任务上下文持有。
     * 同一进程内一次只允许一个任务进入主循环，避免 UI 会话、定时任务互相污染上下文。
     */
    private val idleWorkScheduler = IdleWorkScheduler(finishScope)
    val skillDraftWorkState = if (!processClient.isOwner) processClient.skillDraftWorkState else kotlinx.coroutines.flow.combine(idleWorkScheduler.state, knowledge.skillReflectionQueue.sources) { state, sources ->
        state.copy(pending = sources.count { it.failure == null },
            failures = maxOf(state.failures, sources.count { it.failure != null }))
    }.stateIn(finishScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, IdleWorkScheduler.State())

    fun cancelSkillDraftWork() {
        if (!processClient.isOwner) { processClient.send("cancelSkillDraft", android.os.Bundle()); return }
        idleWorkScheduler.clearPending()
        finishScope.launch {
            runCatching { knowledge.skillReflectionQueue.clear() }.onFailure {
                debugLogManager.appendEvent("Skill draft queue clear failed: ${it.javaClass.simpleName}")
            }
        }
    }

    fun resumeSkillDraftWork() {
        if (!processClient.isOwner) { processClient.send("resumeSkillDraft", android.os.Bundle()); return }
        finishScope.launch {
            if (!appContext.getSharedPreferences("skill-authoring", android.content.Context.MODE_PRIVATE).getBoolean("auto_draft", false)) return@launch
            runCatching { knowledge.skillReflectionQueue.pending().forEach(::scheduleSkillDraft) }.onFailure {
                debugLogManager.appendEvent("Skill draft queue read failed: ${it.javaClass.simpleName}")
            }
        }
    }

    private fun scheduleSkillDraft(source: com.droidance.core.knowledge.skill.SkillReflectionQueue.Source) {
        idleWorkScheduler.enqueue(source.turnId) {
            try {
                createBackgroundSkillDraft(TurnEventScope(source.threadId, source.turnId))
                knowledge.skillReflectionQueue.complete(source.turnId)
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) {
                knowledge.skillReflectionQueue.fail(source.turnId, error.javaClass.simpleName)
                throw error
            }
        }
    }

    /** Agent 运行状态 */
    private val _state = MutableStateFlow(AgentState.IDLE)
    override val state: StateFlow<AgentState> = if (processClient.isOwner) _state else processClient.state

    /** 当前活动会话 ID；任务入口在执行锁内绑定。 */
    private val _currentConversationId = MutableStateFlow<String?>(null)
    override val currentConversationId: StateFlow<String?> = if (processClient.isOwner) _currentConversationId else processClient.currentConversationId
    private val _currentTaskExecutionSpace = MutableStateFlow<ExecutionSpace?>(null)
    val currentTaskExecutionSpace: StateFlow<ExecutionSpace?> = if (processClient.isOwner) _currentTaskExecutionSpace else processClient.currentTaskExecutionSpace

    /** 最近一次响应 */
    private val _lastResponse = MutableStateFlow<String>("")
    val lastResponse: StateFlow<String> = if (processClient.isOwner) _lastResponse else processClient.lastResponse

    /** 当前正在执行的动作描述（工具名/推理状态），供任务详情显示 */
    private val _currentAction = MutableStateFlow("")
    val currentAction: StateFlow<String> = if (processClient.isOwner) _currentAction else processClient.currentAction
    /** 同一 Agent 生成一次的任务短标题，供运行岛显示 */
    private val _currentTaskTitle = MutableStateFlow("")
    val currentTaskTitle: StateFlow<String> = if (processClient.isOwner) _currentTaskTitle else processClient.currentTaskTitle
    private val _isMobileUseTaskActive = MutableStateFlow(false)
    val isMobileUseTaskActive: StateFlow<Boolean> = if (processClient.isOwner) _isMobileUseTaskActive else processClient.isMobileUseTaskActive

    /** 持久 App 接管的岛内空闲态；每次唤醒仍创建一个普通 Agent Turn。 */
    private val _takeoverResidentStatus = MutableStateFlow<String?>(null)
    val takeoverResidentStatus: StateFlow<String?> = if (processClient.isOwner) _takeoverResidentStatus else processClient.takeoverResidentStatus
    private val _appTakeoverTurnActive = MutableStateFlow(false)
    val appTakeoverTurnActive: StateFlow<Boolean> = if (processClient.isOwner) _appTakeoverTurnActive else processClient.appTakeoverTurnActive
    private val _appTakeoverConversationId = MutableStateFlow<String?>(null)
    val appTakeoverConversationId: StateFlow<String?> = if (processClient.isOwner) _appTakeoverConversationId else processClient.appTakeoverConversationId
    private val _appTakeoverLiveOutput = MutableStateFlow("")
    val appTakeoverLiveOutput: StateFlow<String> = if (processClient.isOwner) _appTakeoverLiveOutput else processClient.appTakeoverLiveOutput

    init {
        if (appTakeoverStore.hasEnabledWatches()) {
            _takeoverResidentStatus.value = appContext.getString(R.string.app_takeover_waiting)
            _isMobileUseTaskActive.value = true
        }
    }

    /** 当前流式输出的最新片段（最多 80 字符），供悬浮窗实时显示 */
    private val _streamingOutput = MutableStateFlow("")
    val streamingOutput: StateFlow<String> = if (processClient.isOwner) _streamingOutput else processClient.streamingOutput

    /** 当前工具调用参数摘要，供外部执行悬浮窗展示“正在做什么”和关键入参。 */
    private val _currentActionDetail = MutableStateFlow("")
    val currentActionDetail: StateFlow<String> = if (processClient.isOwner) _currentActionDetail else processClient.currentActionDetail

    /** 是否处于暂停状态（用户可接管） */
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = if (processClient.isOwner) _isPaused else processClient.isPaused
    private val _manualTakeoverState = MutableStateFlow(ManualTakeoverState.RUNNING)
    val manualTakeoverState: StateFlow<ManualTakeoverState> = if (processClient.isOwner) _manualTakeoverState else processClient.manualTakeoverState

    /** 当前是否需要暴露人工接管入口。仅由明确的用户介入请求驱动，不写业务场景规则。 */
    private val _manualTakeoverAvailable = MutableStateFlow(false)
    val manualTakeoverAvailable: StateFlow<Boolean> = if (processClient.isOwner) _manualTakeoverAvailable else processClient.manualTakeoverAvailable
    private val _userInteractionRequest = MutableStateFlow<UserInteractionRequest?>(null)
    val userInteractionRequest: StateFlow<UserInteractionRequest?> = if (processClient.isOwner) _userInteractionRequest else processClient.userInteractionRequest
    @Volatile private var manualResumeRequiresObservation = false
    @Volatile private var manualTakeoverResumedAtElapsedMs = 0L
    /** 用户在接管期间或恢复时补充的上下文。 */
    /** waitIfPaused 等待超时后置位，供任务收尾时选择"等待接管超时"而非通用"已取消"文案；每次任务结束后消费并复位。 */
    @Volatile private var manualTakeoverWaitTimedOut = false

    @Synchronized
    private fun pauseForManualControl(reason: String) {
        if (!_isPaused.value && _userInteractionRequest.value?.kind == UserInteractionKind.HIGH_RISK_CONFIRMATION) {
            _userInteractionRequest.value = null
        }
        activeDurableTurn?.executionClock?.pause()
        if (_agentLoopState.value != AgentLoopState.COMPLETED) {
            _agentLoopState.value = AgentLoopState.WAITING_HUMAN
        }
        if (!_isPaused.value) {
            _manualTakeoverState.value = ManualTakeoverState.PAUSE_REQUESTED
        }
        _isPaused.value = true
        llmRouter.cancelActiveRequests()
        _currentAction.value = appContext.getString(R.string.manual_takeover_waiting_action)
        _streamingOutput.value = appContext.getString(R.string.manual_takeover_waiting_action)
        _currentActionDetail.value = reason.trim()
    }

    /** Agent 确实需要用户介入时才发布接管请求；用户主动暂停不走此入口。 */
    private fun requestManualTakeover(reason: String): Boolean {
        val firstRequest = !_manualTakeoverAvailable.value
        pauseForManualControl(reason)
        _manualTakeoverAvailable.value = true
        if (firstRequest) {
            val request = UserInteractionRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = currentConversationId.value,
                kind = UserInteractionKind.DEVICE_TAKEOVER,
                reason = reason.trim(),
                requestedAtMs = System.currentTimeMillis()
            )
            _userInteractionRequest.value = request
            TaskExecutionService.notifyManualTakeoverRequired(appContext)
        }
        return firstRequest
    }

    override suspend fun awaitHighRiskConfirmation(
        scope: TurnEventScope,
        callId: String,
        toolName: String
    ): UserInteractionState {
        val active = activeDurableTurn?.takeIf { it.scope == scope } ?: return UserInteractionState.CANCELED
        val request = UserInteractionRequest(
            requestId = callId,
            conversationId = scope.threadId,
            kind = UserInteractionKind.HIGH_RISK_CONFIRMATION,
            reason = "",
            requestedAtMs = System.currentTimeMillis(),
            turnId = scope.turnId,
            toolName = toolName
        )
        val waitingText = runtimeStatusText(R.string.tool_confirmation_waiting)
        synchronized(this) {
            if (activeDurableTurn !== active || _isPaused.value || pendingUserSteer.get() || cancelRequested) {
                return UserInteractionState.CANCELED
            }
            active.executionClock.pause()
            _agentLoopState.value = AgentLoopState.WAITING_HUMAN
            _userInteractionRequest.value = request
            _currentAction.value = waitingText
            _streamingOutput.value = waitingText
            _currentActionDetail.value = ""
            _isPaused.value = true
        }
        try {
            agentTurnLedger.pauseForUser(active, callId)
            RuntimeNotifications.post(
                androidx.core.content.ContextCompat.getContextForLanguage(appContext),
                TaskExecutionService.MANUAL_TAKEOVER_NOTIFICATION_ID,
                R.string.tool_confirmation_waiting,
                runtimeStatusText(R.string.tool_confirmation_notification_text)
            )
            val remainingMs = ToolRiskPolicy.remainingConfirmationMs(scope, callId) ?: 0L
            val response = withTimeoutOrNull(remainingMs) {
                _userInteractionRequest.first { it?.requestId != callId || it.state != UserInteractionState.PENDING }
            }?.takeIf { it.requestId == callId }?.state
                ?: finishHighRiskConfirmation(scope, callId, allow = null)
                ?: UserInteractionState.CANCELED
            agentTurnLedger.resumeFromUser(active, callId)
            return if (pendingUserSteer.get()) UserInteractionState.CANCELED else response
        } finally {
            synchronized(this) {
                if (activeDurableTurn === active && _userInteractionRequest.value?.requestId == callId) {
                    cancelPendingUserInteraction(UserInteractionKind.HIGH_RISK_CONFIRMATION)
                    if (!cancelRequested && _isPaused.value) {
                        _agentLoopState.value = AgentLoopState.PHASE_B
                        _currentAction.value = ""
                        _streamingOutput.value = ""
                        active.executionClock.resume()
                        _isPaused.value = false
                    }
                }
            }
            TaskExecutionService.cancelManualTakeover(appContext)
        }
    }

    fun respondToHighRiskConfirmation(requestId: String, allow: Boolean) {
        if (!processClient.isOwner) {
            processClient.send("respondToHighRiskConfirmation", android.os.Bundle().apply {
                putString("requestId", requestId)
                putBoolean("allow", allow)
            })
            return
        }
        val scope = activeDurableTurn?.scope ?: return
        finishHighRiskConfirmation(scope, requestId, allow)
    }

    @Synchronized
    private fun finishHighRiskConfirmation(scope: TurnEventScope, requestId: String, allow: Boolean?): UserInteractionState? {
        if (activeDurableTurn?.scope != scope || cancelRequested) return null
        val request = _userInteractionRequest.value?.takeIf {
            it.kind == UserInteractionKind.HIGH_RISK_CONFIRMATION && it.requestId == requestId &&
                it.turnId == scope.turnId && it.conversationId == scope.threadId
        } ?: return null
        if (request.state != UserInteractionState.PENDING) return request.state
        val state = ToolRiskPolicy.respondToConfirmation(scope, requestId, allow) ?: UserInteractionState.CANCELED
        _userInteractionRequest.value = request.copy(
            state = state,
            responseSource = if (state == UserInteractionState.EXPIRED || allow == null) {
                UserInteractionResponseSource.RUNTIME
            } else UserInteractionResponseSource.LOCAL_DEVICE,
            resolvedAtMs = System.currentTimeMillis()
        )
        return state
    }

    /** Publish the model explanation and request local device assistance. */
    override fun requestManualAssistance(reason: String, instruction: String?) {
        val publicInstruction = instruction?.trim().takeUnless { it.isNullOrBlank() }
            ?: reason.trim()
        requestManualTakeover(publicInstruction)
        _currentActionDetail.value = publicInstruction
    }

    /** Called from a native tool before the normal Android permission dialog is launched. */
    override suspend fun beginRuntimePermissionRequest(request: PendingPermissionRequest) {
        check(activeDurableTurn?.scope?.turnId == request.turnId) { "STALE_PERMISSION_OWNER" }
        activeDurableTurn?.let { active ->
            agentTurnLedger.pauseForUser(active, request.requestId)
        }
        _state.value = AgentState.WAITING_PERMISSION
        pauseForManualControl(request.reason)
        _manualTakeoverState.value = ManualTakeoverState.PAUSED
        _manualTakeoverAvailable.value = false
        _currentAction.value = appContext.getString(R.string.runtime_permission_waiting_action)
        _streamingOutput.value = _currentAction.value
        val interaction = UserInteractionRequest(
            requestId = request.requestId,
            conversationId = currentConversationId.value,
            kind = UserInteractionKind.PERMISSION,
            reason = request.reason,
            requestedAtMs = request.requestedAtMs
        )
        _userInteractionRequest.value = interaction
        if (!foregroundState.isInForeground) {
            TaskExecutionService.notifyRuntimePermissionRequired(appContext, interaction.reason)
        }
    }

    /** Resume the same tool call after grant/deny; permission results never require a new screenshot. */
    override suspend fun resolveRuntimePermissionRequest(
        request: PendingPermissionRequest,
        outcome: PermissionRequestOutcome
    ) {
        if (activeDurableTurn?.scope?.turnId != request.turnId) return
        if (outcome != PermissionRequestOutcome.CANCELED) {
            activeDurableTurn?.let { active ->
                agentTurnLedger.resumeFromUser(active, request.requestId)
            }
        }
        val pending = _userInteractionRequest.value
            ?.takeIf { it.requestId == request.requestId && it.state == UserInteractionState.PENDING }
        if (pending != null) {
            val resolved = pending.copy(
                state = if (outcome == PermissionRequestOutcome.CANCELED) {
                    UserInteractionState.CANCELED
                } else {
                    UserInteractionState.RESOLVED
                },
                responseSource = UserInteractionResponseSource.LOCAL_DEVICE,
                resolvedAtMs = System.currentTimeMillis()
            )
            _userInteractionRequest.value = resolved
        }
        synchronized(this) {
            _isPaused.value = false
            activeDurableTurn?.executionClock?.resume()
        }
        TaskExecutionService.cancelManualTakeover(appContext)
        _manualTakeoverState.value = ManualTakeoverState.RUNNING
        if (_agentLoopState.value == AgentLoopState.WAITING_HUMAN) {
            _agentLoopState.value = AgentLoopState.PHASE_B
        }
        if (_state.value == AgentState.WAITING_PERMISSION) {
            _state.value = AgentState.EXECUTING_TOOL
        }
        manualResumeRequiresObservation = false
        _currentAction.value = appContext.getString(
            if (outcome == PermissionRequestOutcome.GRANTED) {
                R.string.runtime_permission_granted_action
            } else {
                R.string.runtime_permission_denied_action
            }
        )
        _streamingOutput.value = _currentAction.value
        _currentActionDetail.value = outcome.name
    }

    fun updateCurrentToolDetail(toolName: String, arguments: String) {
        _currentActionDetail.value = listOf(toolName, compactToolArguments(arguments))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    private fun runtimeStatusText(resId: Int, vararg formatArgs: Any): String =
        foregroundHost.localizedString(resId, *formatArgs)

    private fun compactToolArguments(arguments: String): String {
        val normalized = runCatching { Json.parseToJsonElement(arguments).toString() }
            .getOrElse { arguments }
            .replace(Regex("\\s+"), " ")
            .trim()
        return normalized.take(220)
    }

    private fun clearManualTakeoverAvailable() {
        if (_manualTakeoverAvailable.value) {
            TaskExecutionService.cancelManualTakeover(appContext)
        }
        _manualTakeoverAvailable.value = false
    }

    @Synchronized
    private fun cancelPendingUserInteraction(kind: UserInteractionKind? = null) {
        val pending = _userInteractionRequest.value
            ?.takeIf { it.state == UserInteractionState.PENDING && (kind == null || it.kind == kind) }
            ?: return
        val canceled = pending.copy(
            state = UserInteractionState.CANCELED,
            responseSource = UserInteractionResponseSource.RUNTIME,
            resolvedAtMs = System.currentTimeMillis()
        )
        _userInteractionRequest.value = canceled
    }

    private val turnJobs = TurnJobs()
    private val runningTurnJob: Job? get() = turnJobs.runningJob
    private val cancelRequested: Boolean get() = runningTurnJob?.isCancelled == true
    @Volatile private var lastCancellationOrigin: String? = null

    /** 注册当前任务 Job（由应用任务入口调用） */
    fun registerActiveJob(job: Job) {
        registerActiveJob("ui", job)
    }

    /** 注册指定任务 Job，用于本地对话和定时任务按来源定向取消 */
    override fun registerActiveJob(taskId: String, job: Job) = turnJobs.register(taskId, job)

    override fun unregisterActiveJob(taskId: String, expectedJob: Job) =
        turnJobs.release(taskId, expectedJob)

    /** 取消当前正在运行的任务（包括 AI 决策循环） */
    override fun cancelCurrentTask(taskId: String?, origin: String) {
        if (!processClient.isOwner) { processClient.send("cancel", android.os.Bundle().apply { putString("origin", origin); putString("taskId", taskId) }); return }
        finishScope.launch {
            debugLogManager.appendEvent(
                "Task cancellation requested: origin=${origin.take(120)} task=${taskId ?: "all"}"
            )
        }
        val jobsToCancel = turnJobs.cancellationTargets(taskId)
        val cancelsRunningTurn = jobsToCancel.any { it === runningTurnJob }
        if (cancelsRunningTurn) lastCancellationOrigin = origin.take(120)
        jobsToCancel.forEach { it.cancel() }
        if (cancelsRunningTurn) llmRouter.cancelActiveRequests()

        val shouldResetUiState = cancelsRunningTurn || (taskId == null && runningTurnJob == null)
        if (shouldResetUiState) {
            // 立即重置所有状态，确保悬浮窗立刻关闭
            _isPaused.value = false
            _manualTakeoverState.value = ManualTakeoverState.RUNNING
            cancelPendingUserInteraction()
            clearManualTakeoverAvailable()
            manualResumeRequiresObservation = false
            manualTakeoverResumedAtElapsedMs = 0L
            _currentAction.value = ""
            _streamingOutput.value = ""
            _currentActionDetail.value = ""
            _currentTaskExecutionSpace.value = null
            _currentTaskTitle.value = ""
            _state.value = AgentState.IDLE
        }
    }

    fun stopCurrentMobileUse(origin: String) {
        if (!processClient.isOwner) { processClient.send("stop", android.os.Bundle().apply { putString("origin", origin) }); return }
        val residencyStopped = stopAppTakeoverResidency(origin)
        val currentTaskStillActive = !residencyStopped || state.value != AgentState.IDLE
        if (currentTaskStillActive) {
            cancelCurrentTask(origin = origin)
        }
        _isMobileUseTaskActive.value = false
        _currentTaskExecutionSpace.value = null
        _currentTaskTitle.value = ""
    }

    fun stopAppTakeoverResidency(origin: String): Boolean {
        if (!processClient.isOwner) return processClient.command("stopResidency", android.os.Bundle().apply { putString("origin", origin) }).getBoolean("result")
        val takeoverTurnActive = _appTakeoverTurnActive.value
        val hasResidency = _takeoverResidentStatus.value != null ||
            appTakeoverStore.hasEnabledWatches() ||
            takeoverTurnActive
        if (!hasResidency) return false
        debugLogManager.appendEvent("App takeover residency stopped: origin=${origin.take(120)}")
        appTakeoverStore.clear()
        _takeoverResidentStatus.value = null
        if (takeoverTurnActive) {
            cancelCurrentTask(taskId = "app_takeover", origin = origin)
        }
        if (takeoverTurnActive || state.value == AgentState.IDLE) {
            _isMobileUseTaskActive.value = false
            _currentTaskExecutionSpace.value = null
            _currentTaskTitle.value = ""
            _currentAction.value = ""
            _streamingOutput.value = ""
            _currentActionDetail.value = ""
            _state.value = AgentState.IDLE
        }
        return true
    }

    /** Scheduler completion must not cancel an unrelated foreground task or erase other watches. */
    fun refreshAppTakeoverResidency() {
        if (!processClient.isOwner) { processClient.send("refreshResidency", android.os.Bundle()); return }
        if (!appTakeoverStore.hasEnabledWatches()) {
            _takeoverResidentStatus.value = null
            if (state.value == AgentState.IDLE) {
                _isMobileUseTaskActive.value = false
                _currentAction.value = ""
            }
        }
    }


    private fun materializeOperatorEvidence(taskGoal: String) {
        val facts = evidenceLedger.observationsSnapshot().flatMap { observation ->
            evidenceFactExtractor.extractInline(taskGoal, observation)
        }
        evidenceLedger.addFacts(facts)
    }

    /** 切换暂停/恢复状态 */
    fun togglePause() {
        if (!processClient.isOwner) { processClient.send("pause", android.os.Bundle()); return }
        if (_isPaused.value) {
            resumeManualTakeover()
        } else {
            pauseForManualControl(appContext.getString(R.string.manual_takeover_user_paused_action))
        }
    }

    /** Cancel only the currently bound Turn; a stale caller cannot stop a newer task. */
    @Synchronized
    fun cancelTurn(turnId: String): Boolean {
        if (!processClient.isOwner) return processClient.command("cancelTurn", android.os.Bundle().apply { putString("turnId", turnId) }).getBoolean("result")
        val active = activeDurableTurn ?: return false
        if (active.scope.turnId != turnId) return false
        val job = runningTurnJob ?: return false
        if (activeDurableTurn !== active) return false
        job.cancel()
        llmRouter.cancelActiveRequests()
        return true
    }

    @Synchronized
    fun resumeManualTakeover(): Boolean {
        if (!processClient.isOwner) return processClient.command("resume", android.os.Bundle().apply {  }).getBoolean("result")
        if (!_isPaused.value) return false
        val interaction = _userInteractionRequest.value
        if (interaction?.kind == UserInteractionKind.HIGH_RISK_CONFIRMATION &&
            _manualTakeoverState.value == ManualTakeoverState.RUNNING
        ) return false
        val pendingRequest = interaction
            ?.takeIf { it.state == UserInteractionState.PENDING }
        if (pendingRequest?.kind == UserInteractionKind.PERMISSION) return false
        _manualTakeoverState.value = ManualTakeoverState.RESUMING
        manualTakeoverResumedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        manualResumeRequiresObservation = true
        clearManualTakeoverAvailable()
        _currentAction.value = appContext.getString(R.string.manual_takeover_resumed_action)
        _streamingOutput.value = appContext.getString(R.string.manual_takeover_resumed_action)
        if (pendingRequest != null) {
            val resolved = pendingRequest.copy(
                state = UserInteractionState.RESOLVED,
                responseSource = UserInteractionResponseSource.LOCAL_DEVICE,
                resolvedAtMs = System.currentTimeMillis()
            )
            _userInteractionRequest.value = resolved
        }
        _manualTakeoverState.value = ManualTakeoverState.RUNNING
        if (_agentLoopState.value == AgentLoopState.WAITING_HUMAN) {
            _agentLoopState.value = AgentLoopState.PHASE_B
        }
        _isPaused.value = false
        activeDurableTurn?.executionClock?.resume()
        return true
    }

    fun beginManualTakeoverFromOverlay(): Boolean {
        if (!processClient.isOwner) return processClient.command("takeControl", android.os.Bundle().apply {  }).getBoolean("result")
        // 同 display 接管（§5.4）：只暂停 Agent，把当前虚拟 display 的画面与输入权临时交给用户。
        // 绝不再把 App 迁移到主屏 0——那会让人与 Agent 操作在不同 display，恢复后 Agent 又回到旧虚拟
        // display。接管面板通过 VirtualSpaceManager 直接读写同一 display。
        val accepted = ManualTakeoverTransitionPolicy.shouldAcceptOverlayTakeover(
            isPaused = _isPaused.value,
            lastResumeElapsedMs = manualTakeoverResumedAtElapsedMs,
            nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
            debounceMs = manualTakeoverTransitionDebounceMs()
        )
        if (!accepted) return false
        pauseForManualControl(appContext.getString(R.string.manual_takeover_user_paused_action))
        return true
    }

    /**
     * 如果处于暂停状态则挂起等待，直到恢复。
     * Human takeover ends only through explicit user resume/cancel. No timeout, model call,
     * polling observation or automatic tool retry is allowed while awaiting that response.
     */
    private suspend fun waitIfPaused() {
        // Input persistence is a short execution barrier, independent of human takeover.
        userInputMutex.withLock { }
        ensureTaskActive()
        if (_isPaused.value) {
            if (_manualTakeoverState.value == ManualTakeoverState.PAUSE_REQUESTED) {
                _manualTakeoverState.value = ManualTakeoverState.PAUSED
            }
            _currentAction.value = appContext.getString(R.string.manual_takeover_waiting_action)
            val active = activeDurableTurn
            val interactionId = _userInteractionRequest.value?.requestId ?: "manual-control"
            if (active != null) agentTurnLedger.pauseForUser(active, interactionId)
            _isPaused.first { !it }
            if (active != null) agentTurnLedger.resumeFromUser(active, interactionId)
            manualResumeRequiresObservation = true
        }
        if (manualResumeRequiresObservation) {
            withContext(Dispatchers.IO) {
                check(virtualSpaceManager.endViewerInput()) { "VIRTUAL_VIEWER_IME_RELEASE_FAILED" }
            }
        }
        ensureTaskActive()
    }

    private fun manualTakeoverWaitTimeoutMs(): Long =
        ruleConfigManager.harnessPolicy().runtimeThresholds.manualTakeoverWaitTimeoutMs

    private fun manualTakeoverTransitionDebounceMs(): Long =
        ruleConfigManager.harnessPolicy().runtimeThresholds.manualTakeoverTransitionDebounceMs

    private fun operatorRuntimeGuard(key: String, values: Map<String, String> = emptyMap()): String {
        val template = ruleConfigManager.message(key)
        return values.entries.fold(template) { text, (name, value) ->
            text.replace("{$name}", value)
        }
    }

    private fun takeManualResumeInstruction(): String? {
        if (pendingUserSteer.getAndSet(false)) {
            return ruleConfigManager.readMarkdownSection("utility_prompts.md", "user_steer")
        }
        if (!manualResumeRequiresObservation) return null
        manualResumeRequiresObservation = false
        return operatorRuntimeGuard(key = "manual_takeover_resumed").ifBlank { null }
    }

    private suspend fun ensureTaskActive() {
        coroutineContext.ensureActive()
    }

    /**
     * 切换当前激活的 Agent
     * 切换后自动清空对话历史（新 Agent 有不同的人格和记忆）
     */
    fun switchAgent(agentName: String) {
        if (!processClient.isOwner) { processClient.send("switchAgent", android.os.Bundle().apply { putString("name", agentName) }); return }
        if (agentName == activeAgent) return
        activeAgent = agentName
        conversationHistory.clear()
    }

    /** 获取当前激活的 Agent 名称 */
    fun getActiveAgent(): String = if (processClient.isOwner) activeAgent else processClient.activeAgent

    suspend fun clearLogData() = withContext(Dispatchers.IO) { processClient.command("clearLogs"); Unit }

    /** Storage maintenance shares the execution lane with every foreground and idle Turn. */
    suspend fun <T> withStorageMaintenance(block: suspend () -> T): T = idleWorkScheduler.runForeground {
        check(processClient.isOwner) { "Storage maintenance belongs to the resident owner" }
        processRecoveryCoordinator.awaitReady()
        block()
    }

    /** The task host restores facts under the same lock that protects execution. */
    override suspend fun processConversationInput(
        conversationId: String,
        request: TurnRequest,
        onStreamContent: suspend (AssistantStreamChunk) -> Unit,
        onTurnStarted: () -> Unit
    ): RuntimeTurnOutcome = if (!processClient.isOwner) {
        processClient.conversation(conversationId, request, onStreamContent, onTurnStarted)
    } else idleWorkScheduler.runForeground {
        processRecoveryCoordinator.awaitReady()
        if (_currentConversationId.value != conversationId) conversationEpoch += 1L
        _currentConversationId.value = conversationId
        val restoredHistory = conversationHistoryReader.read(conversationId)
        conversationHistory.clear()
        conversationHistory.addAll(restoredHistory)
        processInputInternal(request, onStreamContent, onTurnStarted = { onTurnStarted() })
    }

    @kotlinx.serialization.Serializable
    data class RuntimeTurnOutcome(
        val response: String,
        val status: String,
        val completion: com.droidance.tools.CompletionResult?
    )

    @kotlinx.serialization.Serializable
    data class AutomationTurnResult(
        val response: String,
        val status: String
    )

    /**
     * A scheduled occurrence is an ordinary Turn in the existing single Agent runtime. The stable
     * automation thread keeps EventStore lineage, while chat history stays
     * isolated from whichever interactive task happened to run before it.
     */
    suspend fun processAutomationInput(
        userInput: String,
        conversationId: String,
        prepareDevice: () -> AutoCloseable,
        onTurnStarted: (com.droidance.core.agent.event.TurnEventScope) -> Unit,
        onStreamContent: suspend (AssistantStreamChunk) -> Unit = {}
    ): AutomationTurnResult = if (!processClient.isOwner) {
        processClient.automation(userInput, conversationId, onTurnStarted, onStreamContent)
    } else idleWorkScheduler.runForeground {
        val historySnapshot = conversationHistory.toList()
        val conversationIdSnapshot = _currentConversationId.value
        conversationHistory.clear()
        _currentConversationId.value = conversationId
        try {
            val outcome = processInputInternal(
                request = TurnRequest.create(
                    source = MessageSource.SCHEDULED,
                    rawText = userInput,
                    images = emptyList()
                ),
                onStreamContent = onStreamContent,
                prepareDevice = prepareDevice,
                onTurnStarted = onTurnStarted,
            )
            AutomationTurnResult(
                response = outcome.response,
                status = outcome.status
            )
        } finally {
            conversationHistory.clear()
            conversationHistory.addAll(historySnapshot)
            _currentConversationId.value = conversationIdSnapshot
        }
    }

    @kotlinx.serialization.Serializable
    data class AppTakeoverTurnResult(
        val response: String,
        val status: String,
        val checkpoint: String,
        val nextCheckSeconds: Long?,
        val goalComplete: Boolean,
        val notifyUser: Boolean?
    )

    /** 持久会话只负责触发；每次运行复用普通 AgentRuntime，并与其他设备任务串行。 */
    suspend fun processAppTakeoverInput(
        userInput: String,
        conversationId: String,
        strategy: DeviceExecutionStrategy,
        initialScreenFrame: ByteArray,
        roundContext: () -> String,
        takeoverRunId: String,
        modelPlan: com.droidance.core.model.llm.ModelStreamPlan?,
        onStreamContent: suspend (AssistantStreamChunk) -> Unit = {}
    ): AppTakeoverTurnResult = if (!processClient.isOwner) {
        processClient.takeover(userInput, conversationId, initialScreenFrame, roundContext(), takeoverRunId, modelPlan, onStreamContent)
    } else idleWorkScheduler.runForeground {
        val historySnapshot = conversationHistory.toList()
        val conversationIdSnapshot = _currentConversationId.value
        conversationHistory.clear()
        _currentConversationId.value = conversationId
        _appTakeoverConversationId.value = conversationId
        _appTakeoverTurnActive.value = true
        _appTakeoverLiveOutput.value = ""
        try {
            val outcome = processInputInternal(
                request = TurnRequest.create(
                    source = MessageSource.APP_TAKEOVER,
                    modelPlan = modelPlan,
                    rawText = userInput,
                    images = emptyList()
                ),
                onStreamContent = { chunk ->
                    _appTakeoverLiveOutput.value =
                        (_appTakeoverLiveOutput.value + chunk.text).takeLast(TAKEOVER_LIVE_OUTPUT_CHARS)
                    onStreamContent(chunk)
                },
                executionStrategyOverride = strategy,
                initialScreenFrame = initialScreenFrame,
                roundContext = roundContext,
                onTurnStarted = { appTakeoverStore.bindTakeoverTurn(it.turnId, takeoverRunId) }
            )
            AppTakeoverTurnResult(
                response = outcome.response,
                status = outcome.status,
                checkpoint = outcome.completion?.checkpoint.orEmpty(),
                nextCheckSeconds = outcome.completion?.nextCheckSeconds,
                goalComplete = outcome.completion?.goalComplete == true,
                notifyUser = outcome.completion?.notifyUser
            )
        } finally {
            _appTakeoverTurnActive.value = false
            conversationHistory.clear()
            conversationHistory.addAll(historySnapshot)
            _currentConversationId.value = conversationIdSnapshot
        }
    }

    private suspend fun processInputInternal(
        request: TurnRequest,
        onStreamContent: suspend (AssistantStreamChunk) -> Unit = {},
        executionStrategyOverride: DeviceExecutionStrategy? = null,
        initialScreenFrame: ByteArray? = null,
        roundContext: () -> String = { "" },
        prepareDevice: (() -> AutoCloseable)? = null,
        onTurnStarted: ((com.droidance.core.agent.event.TurnEventScope) -> Unit)? = null
    ): RuntimeTurnOutcome {
        // Takeover runs already have a scheduler owner. Bound the registering ordinary Turn
        // as soon as its tool persists a goal, without affecting any unrelated Turn.
        val originTurnId = java.util.concurrent.atomic.AtomicReference<String?>(null)
        var result: RuntimeTurnOutcome? = null
        suspend fun execute() {
            result = adbLifecycleGate.withTask { processInputWithinTakeoverDeadline(
                request, onStreamContent, executionStrategyOverride, initialScreenFrame,
                roundContext = {
                    (listOf(roundContext(), appTakeoverStore.deadlineForTurn(originTurnId.get())?.let { deadline ->
                        val now = System.currentTimeMillis()
                        "<task_clock>" + kotlinx.serialization.json.buildJsonObject {
                            put("host_now_ms", kotlinx.serialization.json.JsonPrimitive(now))
                            put("deadline_at_ms", kotlinx.serialization.json.JsonPrimitive(deadline))
                            put("remaining_ms", kotlinx.serialization.json.JsonPrimitive((deadline - now).coerceAtLeast(0L)))
                        } + "</task_clock>"
                    }.orEmpty()) + appTakeoverStore.forOriginTurn(originTurnId.get()).map(appTakeoverPromptBuilder::roundContext))
                        .filter(String::isNotBlank).joinToString("\n")
                },
                prepareDevice = prepareDevice,
                onTurnStarted = { scope -> originTurnId.set(scope.turnId); onTurnStarted?.invoke(scope) }
            ) }
        }
        return try {
        val completedWithinDeadline = if (request.source == MessageSource.APP_TAKEOVER) {
            execute()
            true
        } else com.droidance.automation.AppTakeoverDeadlineRunner.run(
            appTakeoverStore.changeVersion,
            deadlineAtMs = { appTakeoverStore.deadlineForTurn(originTurnId.get()) },
            maxSleepMs = ruleConfigManager.harnessPolicy().runtimeThresholds.appTakeoverSchedulerMaxSleepMs,
            onDeadlineReached = { originTurnId.get()?.let(::cancelTurn) },
            operation = ::execute
        )
        if (!completedWithinDeadline) throw kotlinx.coroutines.CancellationException("TASK_DEADLINE_REACHED")
        requireNotNull(result)
        } finally { originTurnId.get()?.let(appTakeoverStore::releaseTurnDeadline) }
    }

    private suspend fun processInputWithinTakeoverDeadline(
        request: TurnRequest,
        onStreamContent: suspend (AssistantStreamChunk) -> Unit = {},
        executionStrategyOverride: DeviceExecutionStrategy? = null,
        initialScreenFrame: ByteArray? = null,
        roundContext: () -> String = { "" },
        prepareDevice: (() -> AutoCloseable)? = null,
        onTurnStarted: ((com.droidance.core.agent.event.TurnEventScope) -> Unit)? = null
    ): RuntimeTurnOutcome {
        processRecoveryCoordinator.awaitReady()
        val currentJob = coroutineContext[Job]
        val runtimeTaskId = runtimeTaskIdForSource(request.source)
        if (currentJob != null) turnJobs.begin(runtimeTaskId, currentJob)
        _isPaused.value = false
        _manualTakeoverState.value = ManualTakeoverState.RUNNING
        clearManualTakeoverAvailable()
        var isChatMode = false
        var deviceTraceStarted = false
        var taskFinalized = false
        var finishedTaskStatus: ExecutionTerminalStatus? = null
        var finalResponse = ""
        val turnEpoch = conversationEpoch
        var durableTurn: AgentTurnLedger.ActiveTurn? = null
        var toolServicesBinding: ToolTurnServices.Binding? = null
        var devicePreparation: AutoCloseable? = null
        var deviceResourcesPrepared = false
        var deviceSnapshotInitializationFailure: Exception? = null
        _lastResponse.value = ""
        var activationKind = if (request.source == MessageSource.APP_TAKEOVER) TurnActivationKind.ANDROID
            else TurnActivationKind.LOCAL
        val operatorToolProfile = if (request.source == MessageSource.APP_TAKEOVER) {
            OperatorToolProfile.BACKGROUND_AUTOMATION
        } else {
            OperatorToolProfile.DEFAULT
        }
        fun startDeviceTrace() {
            if (deviceTraceStarted) return
            latencyTracker.startTask()
            diagnostics.startTaskDiagnostics(request.requestId)
            deviceTraceStarted = true
        }

        var initialExecutionStrategy: DeviceExecutionStrategy? = null
        val forceAndroidActivation = activationKind == TurnActivationKind.ANDROID
        val preliminaryMobileUse = forceAndroidActivation || appTakeoverStore.hasEnabledWatches()
        lastCancellationOrigin = null
        try {
            _agentLoopState.value = AgentLoopState.PHASE_B
            _currentTaskTitle.value = ""
            if (preliminaryMobileUse) {
                val preliminaryStrategy = executionStrategyOverride ?: capabilityRegistry.currentStrategy(
                    space = request.executionSpace ?: capabilityRegistry.requestedSpace()
                )
                val preliminarySpace = preliminaryStrategy.spaceSelection.effectiveSpace
                _isMobileUseTaskActive.value = true
                _currentTaskExecutionSpace.value = preliminarySpace
            }
            return taskLifecycle.run(
                conversationId = currentConversationId.value,
                request = request,
                initialPrefix = {
                    // Freeze configured capabilities, but prepare the device only when selected.
                    val strategy = executionStrategyOverride ?: capabilityRegistry.currentStrategy(
                        space = request.executionSpace ?: capabilityRegistry.requestedSpace()
                    )
                    initialExecutionStrategy = strategy
                    taskCoordinator.stablePrefix(
                        mode = strategy.selection.effectiveMode,
                        executionSpace = strategy.spaceSelection.effectiveSpace,
                        toolProfile = operatorToolProfile,
                        extraSystemContext = operatorStableSystemContext()
                    )
                },
                unfinishedNotice = { session, failure ->
                    val timedOut = manualTakeoverWaitTimedOut
                    val deadlineReached = appTakeoverStore.deadlineForTurn(session.durableTurn.scope.turnId)
                        ?.let { System.currentTimeMillis() >= it } == true
                    val cancelled = currentJob?.isCancelled == true || cancelRequested ||
                        failure is kotlinx.coroutines.CancellationException
                    val preparationFailure = failure as? com.droidance.daemon.DevicePreparationException
                    val modelNotConfigured = failure is com.droidance.core.model.llm.ModelNotConfiguredException
                    val explainedFailure = (failure as? TaskExecutionFailure)?.publicReply
                    val notice = when {
                        deadlineReached -> appTakeoverStore.forTurn(session.durableTurn.scope.turnId)
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString("\n\n", transform = appTakeoverPromptBuilder::expired)
                            ?: ruleConfigManager.publicFallback("task_deadline_reached")
                        timedOut || cancelled -> PublicAssistantText.sanitize(buildCancelledTaskSummary(timedOut))
                        failure is com.droidance.core.knowledge.memory.DeferredMemoryEnqueueException -> appContext.getString(R.string.memory_background_save_failed)
                        explainedFailure != null -> explainedFailure
                        modelNotConfigured -> appContext.getString(R.string.model_not_configured)
                        preparationFailure != null -> appContext.getString(preparationFailure.reason.messageResource)
                        deviceSnapshotInitializationFailure != null -> appContext.getString(
                            R.string.execution_device_snapshot_failed,
                            deviceSnapshotInitializationFailure?.message.orEmpty().take(240))
                        else -> appContext.getString(R.string.execution_completion_not_confirmed)
                    }
                    if (cancelled || timedOut || modelNotConfigured) _lastResponse.value = notice
                    else if (explainedFailure != null) _lastResponse.value = explainedFailure
                    TaskLifecycle.FailureNotice(
                        status = if (cancelled) "cancelled" else if (timedOut || modelNotConfigured || preparationFailure?.reason?.requiresUser == true) "need_more_info" else "error",
                        text = notice.ifBlank { appContext.getString(R.string.execution_completion_not_confirmed) },
                        diagnosticCode = when {
                            deadlineReached -> "TASK_DEADLINE_REACHED"
                            timedOut -> "MANUAL_TAKEOVER_TIMEOUT"
                            cancelled -> "TASK_CANCELLED"
                            failure is com.droidance.core.knowledge.memory.DeferredMemoryEnqueueException -> "MEMORY_ENQUEUE_FAILED"
                            modelNotConfigured -> "MODEL_NOT_CONFIGURED"
                            preparationFailure != null -> preparationFailure.reason.name
                            deviceSnapshotInitializationFailure != null -> DEVICE_SNAPSHOT_INIT_FAILED
                            else -> "TURN_NOT_COMPLETED"
                        },
                        cancellationOrigin = if (deadlineReached) "task_deadline" else if (cancelled) lastCancellationOrigin ?: "coroutine_cancelled" else null
                    )
                },
                releaseResources = {
                    try {
                        if (request.source != MessageSource.SCHEDULED) {
                            foregroundHost.stopExecution()
                        }
                    } finally {
                        try {
                            try {
                                toolServicesBinding?.let { toolExecutor.releasePrograms(it.sessionContext) }
                            } finally {
                                if (activationKind == TurnActivationKind.ANDROID && toolServicesBinding != null) {
                                    toolExecutor.clearExecutionContext()
                                }
                            }
                        } finally {
                            try {
                                try { devicePreparation?.close() } finally {
                                    devicePreparation = null
                                    toolServicesBinding?.let(toolTurnServices::release)
                                }
                            } finally {
                                durableTurn?.scope?.let(ToolRiskPolicy::revoke)
                                if (activeDurableTurn === durableTurn) activeDurableTurn = null
                                if (activationKind == TurnActivationKind.DIRECT || activationKind == TurnActivationKind.LOCAL) {
                                    _isMobileUseTaskActive.value = false
                                    _currentTaskExecutionSpace.value = null
                                }
                                cancelPendingUserInteraction()
                            }
                        }
                    }
                }
            ) { taskSession ->
                durableTurn = taskSession.durableTurn
                activeDurableTurn = durableTurn
                pendingUserSteer.set(false)
                inputClosed = false
                if (request.source != MessageSource.SCHEDULED) {
                    foregroundHost.startExecution(request.rawText.take(40))
                }
                val selectedModel = llmRouter.planStreamChat(emptyList(), initialScreenFrame != null)
                taskSession.context.getOrPutTyped {
                    com.droidance.automation.AppTakeoverModelSelection(selectedModel.providerId, selectedModel.initialModel)
                }
                toolServicesBinding = toolTurnServices.bind(
                    sessionContext = taskSession.context,
                    progress = ToolProgressPort(::updateCurrentToolDetail),
                    memory = ToolMemoryPort(::applyMemoryUpdates),
                    completion = com.droidance.tools.ToolCompletionPort { candidate, state ->
                        userInputMutex.withLock {
                            when {
                                pendingUserSteer.get() -> com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("USER_CONTEXT_CHANGED", true)
                                inputClosed || !state.record(candidate) -> com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
                                else -> {
                                    inputClosed = true
                                    com.droidance.core.agent.tools.ToolProtocolResult.success("Completion recorded. The execution loop will stop.")
                                }
                            }
                        }
                    },
                    interaction = this,
                    history = com.droidance.tools.ToolHistoryPort { arguments ->
                        val scope = requireNotNull(durableTurn).scope
                        val input = kotlinx.serialization.json.Json.parseToJsonElement(arguments).jsonObject
                        val historicalThread = input["thread_id"]?.jsonPrimitive?.contentOrNull
                        val historicalTurn = input["turn_id"]?.jsonPrimitive?.contentOrNull
                        if ((historicalThread == null) != (historicalTurn == null)) {
                            com.droidance.core.agent.tools.ToolProtocolResult.invalidArgument("thread_id/turn_id", "supply_both")
                        } else {
                            val targetThread = historicalThread ?: scope.threadId
                            val targetTurn = historicalTurn ?: scope.turnId
                            val original = eventStore.eventsForTurn(targetTurn)
                            if (original.isEmpty() || original.any { it.threadId != targetThread } ||
                                eventStore.threadReadModel(targetThread) == null) {
                                com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("HISTORY_SOURCE_UNAVAILABLE", false)
                            } else sessionReadService.read(targetThread, targetTurn, arguments,
                                llmRouter.planStreamChat(messages = emptyList(), hasImage = true).imageIncluded)
                        }
                    }
                )

                onTurnStarted?.invoke(taskSession.durableTurn.scope)
                if (forceAndroidActivation) startDeviceTrace()
                val frozenExecutionStrategy = requireNotNull(initialExecutionStrategy)
                val frozenOperatorSystemContext = operatorStableSystemContext()
                taskSession.activateTools(
                    prefix = taskSession.durableTurn.stablePrefix,
                    kind = activationKind,
                    preparedAtStart = true
                )
                val memoryContextPayload = runCatching {
                    knowledge.progressiveMemoryContextProjector.project(
                        query = request.rawText,
                        policy = ruleConfigManager.harnessPolicy().memoryContext,
                        foregroundPackage = null
                    )
                }.onFailure { error ->
                    debugLogManager.appendEvent(
                        "progressive memory projection failed: ${error.message.orEmpty().take(160)}"
                    )
                }.getOrNull()
                memoryContextPayload?.let { payload ->
                    runCatching {
                        eventStore.recordMemoryContextLoaded(taskSession.durableTurn.scope, payload)
                    }.onFailure { error ->
                        debugLogManager.appendEvent(
                            "memory context event rejected; no memory injected: ${error.message.orEmpty().take(160)}"
                        )
                    }.getOrNull()
                }
                val frozenOperatorTurnContext = try {
                    val selectedContext = knowledge.selectedSkillContext(taskSession.context,
                        request.requestedSkills, ruleConfigManager.harnessPolicy().memoryContext.skillCatalogMaxChars,
                        request.requestId)
                    operatorTurnContext(
                        executionStrategy = frozenExecutionStrategy,
                        availableCapabilities = taskSession.durableTurn.stablePrefix.tools.map { it.name }.toSet(),
                        query = request.rawText,
                        selectedSkillContext = selectedContext
                    )
                } catch (error: Exception) {
                    deviceSnapshotInitializationFailure = error
                    val detail = error.message.orEmpty().take(240)
                    debugLogManager.appendEvent(
                        "$DEVICE_SNAPSHOT_INIT_FAILED: ${error::class.java.simpleName}: $detail"
                    )
                    throw error
                }
                _agentLoopState.value = AgentLoopState.PHASE_B
                clearManualTakeoverAvailable()
                _userInteractionRequest.value = null
                _manualTakeoverState.value = ManualTakeoverState.RUNNING
                manualResumeRequiresObservation = false
                manualTakeoverResumedAtElapsedMs = 0L
                _state.value = AgentState.THINKING
                _currentAction.value = ""
                _streamingOutput.value = ""
                _currentActionDetail.value = ""
                _isMobileUseTaskActive.value = appTakeoverStore.hasEnabledWatches()
                toolExecutor.resetSession()

                _onModelSwitchCallback = { text ->
                    val roundId = eventStore.eventsForTurn(taskSession.durableTurn.scope.turnId)
                        .lastOrNull { it.eventKind == com.droidance.core.agent.event.AgentEventKind.MODEL_ROUND_STARTED }?.roundId
                    onStreamContent(AssistantStreamChunk(text, roundId))
                }

                conversationHistory.add(
                    ChatMessage(role = Role.USER, content = request.rawText, imageBase64List = request.images)
                )
                ensureTaskActive()

                // 所有普通交互只进入一次 Operator。是否需要设备由同一次模型响应的工具调用决定。
                val uiResult = runUiTask(
                    instruction = request.rawText,
                    onStreamContent = onStreamContent,
                    durableTurn = requireNotNull(durableTurn),
                    executionStrategyOverride = frozenExecutionStrategy,
                    initialScreenRequired = request.source == MessageSource.APP_TAKEOVER,
                    initialScreenFrame = initialScreenFrame,
                    roundContext = roundContext,
                    toolProfile = operatorToolProfile,
                    operatorSystemContext = frozenOperatorSystemContext,
                    turnContext = frozenOperatorTurnContext.text,
                    expectedBackend = frozenOperatorTurnContext.backend,
                    prepareExecution = {
                        if (!deviceResourcesPrepared) {
                            activationKind = TurnActivationKind.ANDROID
                            _agentLoopState.value = AgentLoopState.ACTIVATING_ANDROID
                            _currentTaskExecutionSpace.value = frozenExecutionStrategy.spaceSelection.effectiveSpace
                            startDeviceTrace()
                            devicePreparation = prepareDevice?.invoke()
                            if (devicePreparation == null && frozenExecutionStrategy.spaceSelection.effectiveSpace == ExecutionSpace.VIRTUAL_SPACE) {
                                virtualSpaceManager.ensureDisplayInteractive()
                                val powerManager = appContext.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                                val keyguardManager = appContext.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                                val isLocked = keyguardManager?.isKeyguardLocked == true || powerManager?.isInteractive == false
                                if (isLocked) {
                                    virtualSpaceManager.onScreenLocked()
                                } else {
                                    virtualSpaceManager.onScreenUnlocked()
                                }
                            }
                            if (request.source != MessageSource.SCHEDULED) {
                                foregroundHost.startExecution(request.rawText.take(40))
                            }
                            deviceResourcesPrepared = true
                        }
                        executionStrategyOverride ?: capabilityRegistry.awaitTaskStrategy(
                            space = frozenExecutionStrategy.spaceSelection.requestedSpace
                        )
                    }
                )
                val effectiveTerminal = uiResult.terminal
                val effectiveFailureReason = uiResult.failureReason
                finalResponse = if (uiResult.terminal == TurnTerminal.DIRECT_REPLY) {
                    isChatMode = true
                    uiResult.operatorOutput
                } else {
                    val publicText = resolveInternalTerminalMarker(uiResult.operatorOutput)
                        ?: evidenceLedger.stripInternalEvidence(uiResult.operatorOutput).trim()
                    PublicAssistantText.sanitize(publicText)
                        .ifBlank { runtimeStatusText(R.string.execution_empty_report) }
                }
                val terminalProjection = RuntimeTerminalProjection.from(effectiveTerminal)
                if (turnEpoch != conversationEpoch) {
                    throw kotlinx.coroutines.CancellationException("Conversation switched")
                }
                conversationHistory.add(ChatMessage(role = Role.ASSISTANT, content = finalResponse))
                _lastResponse.value = finalResponse
                val status = terminalProjection.executionTerminalStatus
                taskSession.complete(
                    status = terminalProjection.reportStatus,
                    reply = finalResponse,
                    diagnosticCode = effectiveFailureReason.name.lowercase()
                        .takeIf { effectiveTerminal != TurnTerminal.DONE && effectiveTerminal != TurnTerminal.DIRECT_REPLY }
                )
                finalizeTaskArtifacts(
                    status = status,
                    durableTurn = durableTurn
                )
                finishedTaskStatus = status
                taskFinalized = true
                // Save the first run's continuation before releasing the single execution lane.
                toolExecutor.completionResult()?.takeIf { it.status == "done" }?.let { completion ->
                    appTakeoverStore.completeOriginTurn(taskSession.durableTurn.scope.turnId,
                        completion.checkpoint, finalResponse, completion.nextCheckSeconds, completion.goalComplete)
                }
                RuntimeTurnOutcome(
                    response = finalResponse,
                    status = terminalProjection.reportStatus,
                    completion = toolExecutor.completionResult()
                )
            }
        } finally {
            try {
                _agentLoopState.value = AgentLoopState.COMPLETED
                val timedOutWaitingForTakeover = manualTakeoverWaitTimedOut
                val unfinishedTaskStatus = when {
                    timedOutWaitingForTakeover -> ExecutionTerminalStatus.NEED_MORE_INFO
                    finishedTaskStatus == ExecutionTerminalStatus.FAILED -> ExecutionTerminalStatus.FAILED
                    finishedTaskStatus == ExecutionTerminalStatus.ERROR -> ExecutionTerminalStatus.ERROR
                    currentJob?.isCancelled == true || cancelRequested -> ExecutionTerminalStatus.CANCELED
                    else -> ExecutionTerminalStatus.ERROR
                }
                val cancelSummary = if (
                    !isChatMode &&
                    !taskFinalized &&
                    (unfinishedTaskStatus == ExecutionTerminalStatus.CANCELED || timedOutWaitingForTakeover)
                ) {
                    withContext(NonCancellable) {
                        buildCancelledTaskSummary(
                            timedOutWaitingForTakeover = timedOutWaitingForTakeover,
                            activeTurn = durableTurn ?: activeDurableTurn
                        )
                    }
                } else {
                    null
                }
                if (deviceTraceStarted) {
                    val summaryStr = latencyTracker.generateSummary()
                    debugLogManager.appendEvent(
                        "Task Latency Summary:\n$summaryStr\nRecent Tool Timings:\n${toolExecutor.recentTimingSummary()}"
                    )
                }
                if (!isChatMode && !taskFinalized) {
                    val status = unfinishedTaskStatus
                    // 任务被取消（如用户点了停止）会以 CancellationException 中断 try 块，永远走不到上面
                    // 正常完成路径里的对话历史与通知不会执行；这里补一条轻量总结，不再调用 LLM
                    // （用户已经主动要求停止，不该再等一轮生成），只用已有的运行时状态拼装。
                    if ((status == ExecutionTerminalStatus.CANCELED || timedOutWaitingForTakeover) && cancelSummary != null) {
                        if (turnEpoch == conversationEpoch) {
                            conversationHistory.add(ChatMessage(role = Role.ASSISTANT, content = cancelSummary))
                        }
                        _lastResponse.value = cancelSummary
                    }
                    finalizeTaskArtifacts(
                        status = status,
                        durableTurn = durableTurn
                    )
                    finishedTaskStatus = status
                }
                manualTakeoverWaitTimedOut = false
                if (deviceTraceStarted) {
                    diagnostics.finishTaskDiagnostics(request.requestId)
                }
                val effectiveTakeoverStatus = appContext.getString(R.string.app_takeover_waiting)
                    .takeIf { appTakeoverStore.hasEnabledWatches() }
                _takeoverResidentStatus.value = effectiveTakeoverStatus
                _isMobileUseTaskActive.value = effectiveTakeoverStatus != null
                _currentTaskExecutionSpace.value = null
                val wasMobileUseTask = !isChatMode && (
                    activationKind == TurnActivationKind.ANDROID ||
                        deviceTraceStarted ||
                        preliminaryMobileUse
                )
                if (wasMobileUseTask) {
                    _isPaused.value = true
                    _manualTakeoverState.value = ManualTakeoverState.PAUSED
                    _manualTakeoverAvailable.value = true
                    _currentAction.value = appContext.getString(R.string.manual_takeover_waiting_action)
                    _streamingOutput.value = ""
                    _currentActionDetail.value = ""
                    foregroundHost.cancelManualTakeover()
                } else {
                    _isPaused.value = false
                    _manualTakeoverState.value = ManualTakeoverState.RUNNING
                    clearManualTakeoverAvailable()
                    if (effectiveTakeoverStatus != null) {
                        _currentAction.value = effectiveTakeoverStatus
                        _streamingOutput.value = ""
                        _currentActionDetail.value = ""
                    } else {
                        _currentAction.value = ""
                        _streamingOutput.value = ""
                        _currentActionDetail.value = ""
                    }
                }
                manualResumeRequiresObservation = false
                manualTakeoverResumedAtElapsedMs = 0L
                _onModelSwitchCallback = null
                if (request.source != MessageSource.SCHEDULED) {
                    foregroundHost.stopExecution()
                }
                _currentTaskTitle.value = ""
                _state.value = AgentState.IDLE
                if (wasMobileUseTask && request.source != MessageSource.APP_TAKEOVER && request.source != MessageSource.SCHEDULED) {
                    val response = _lastResponse.value
                    val body = PublicAssistantText.sanitize(
                        evidenceLedger.stripInternalEvidence(response)
                    )
                    if (body.isNotBlank() && !foregroundState.isInForeground) {
                        TaskExecutionService.notifyTaskDone(appContext, body)
                    }
                }
            } finally {
                try {
                    cacheMaintenance.afterTask()
                } finally {
                    if (currentJob != null) unregisterActiveJob(runtimeTaskId, currentJob)
                }
            }
        }
    }

    // A missing harness rule can leave a guardText placeholder such as
    // [max_device_task_wall_stop]. Internal markers must never reach the user.
    private val internalTerminalMarker = Regex("""^\[[a-z0-9_]+]$""")

    /**
     * Resolves an internal marker through the local Markdown policy. A null result means the
     * operator already supplied public text and no fallback is needed.
     */
    private fun resolveInternalTerminalMarker(rawDraft: String): String? {
        val stripped = evidenceLedger.stripInternalEvidence(rawDraft).trim()
        if (!internalTerminalMarker.matches(stripped)) return null
        val reason = stripped.removeSurrounding("[", "]")
        return ruleConfigManager.terminationFallback(reasonMarker = reason)
    }

    /** Cancellation is acknowledged immediately without another model request or memo dump. */
    private suspend fun buildCancelledTaskSummary(
        timedOutWaitingForTakeover: Boolean,
        activeTurn: AgentTurnLedger.ActiveTurn? = activeDurableTurn
    ): String {
        val statusText = runtimeStatusText(
            when {
                timedOutWaitingForTakeover -> R.string.execution_takeover_timeout
                lastCancellationOrigin?.contains("stop") == true -> R.string.execution_stopped_by_user
                else -> R.string.execution_cancelled
            }
        )
        val turnId = activeTurn?.scope?.turnId
        val threadId = activeTurn?.scope?.threadId
        val threadModel = threadId?.let { eventStore.threadReadModel(it) }
        val intervention = threadModel?.conversation?.publicReplies(turnId.orEmpty())
            ?.lastOrNull { it.kind == ConversationEntryKind.ASSISTANT_USER_INTERVENTION }
            ?.text?.let(PublicAssistantText::sanitize)?.trim().orEmpty()
        val progress = threadModel?.activityByTurn?.get(turnId)?.publicProgress
            ?.let(PublicAssistantText::sanitize)?.trim().orEmpty()
        val detail = intervention.ifBlank { progress }.take(800)
        return when {
            detail.isBlank() -> statusText
            detail == statusText || detail.contains(statusText) -> detail
            else -> detail
        }
    }

    private suspend fun recentConversationContext(activeTurn: AgentTurnLedger.ActiveTurn): String {
        val prior = conversationHistoryReader.read(activeTurn.scope.threadId,
            excludingTurnId = activeTurn.scope.turnId)
        if (prior.isEmpty()) return ""
        return buildString {
            appendLine("Recent conversation; use it only when the current request refers to earlier context:")
            prior.forEach { message ->
                appendLine(if (message.role == Role.USER) "User:" else "Assistant:")
                appendLine(message.content)
                appendLine()
            }
        }.trim()
    }

    private suspend fun runUiTask(
        instruction: String,
        onStreamContent: suspend (AssistantStreamChunk) -> Unit,
        durableTurn: AgentTurnLedger.ActiveTurn,
        executionStrategyOverride: DeviceExecutionStrategy? = null,
        initialScreenRequired: Boolean = false,
        initialScreenFrame: ByteArray? = null,
        roundContext: () -> String = { "" },
        toolProfile: OperatorToolProfile = OperatorToolProfile.DEFAULT,
        operatorSystemContext: String,
        turnContext: String,
        expectedBackend: com.droidance.core.agent.attempt.ExecutionBackendObservation?,
        prepareExecution: suspend () -> DeviceExecutionStrategy,
    ): TurnResult {
        // The action label stays empty until an actual action or handoff is available.
        _currentAction.value = ""
        debugLogManager.appendEvent("▶️ run_ui_task: ${instruction.take(80)}")
        // Completion and result-card state belong to one user task. Clear them before
        // the first Operator round so a previous task can never close this task.
        toolExecutor.resetTaskState()
        if (toolProfile != OperatorToolProfile.LOCAL_KNOWLEDGE) toolExecutor.clearPerceptionState()
        val intentSourceText = instruction
        val executionStrategy = if (toolProfile == OperatorToolProfile.LOCAL_KNOWLEDGE) null else {
            executionStrategyOverride ?: capabilityRegistry.currentStrategy()
        }
        val stepInstruction = recentConversationContext(durableTurn)

        return try {
            val result = taskCoordinator.coordinateTask(
taskGoal = intentSourceText,
additionalContext = stepInstruction,
durableTurn = durableTurn,
turnContext = turnContext,
expectedBackend = expectedBackend,
executionStrategy = executionStrategy,
initialScreenRequired = initialScreenRequired,
initialScreenFrame = initialScreenFrame,
toolProfile = toolProfile,
mode = executionStrategy?.selection?.effectiveMode,
host = object : com.droidance.core.agent.TurnHost {
    override val onPrepareDevice: suspend () -> com.droidance.core.agent.device.DeviceExecutionStrategy? = { prepareExecution() }
    override val onExecutionStarted: suspend (firstToolName: String) -> Unit = { firstToolName ->
                    _streamingOutput.value = runtimeStatusText(R.string.runtime_status_operating)
                    _isMobileUseTaskActive.value = true
                    ensureLocalUiAutomationResidency()
                }
    override val onStreamContent: suspend (AssistantStreamChunk) -> Unit = onStreamContent
    override val onActionUpdate: (String) -> Unit = { action ->
                    _currentAction.value = action
                    _streamingOutput.value = action
                }
    override val onTaskTitle: (String) -> Unit = { title -> _currentTaskTitle.value = title }
    override val onTaskDeadline: suspend (TaskDeadlineProtocol.Bound) -> Unit = deadline@ { bound ->
                    if (appTakeoverStore.isTakeoverTurn(durableTurn.scope.turnId)) return@deadline
                    val requestAt = eventStore.eventsForTurn(durableTurn.scope.turnId)
                        .lastOrNull { it.eventKind == com.droidance.core.agent.event.AgentEventKind.USER_MESSAGE ||
                            it.eventKind == com.droidance.core.agent.event.AgentEventKind.USER_STEER }?.occurredAt
                        ?: error("TASK_REQUEST_TIME_UNAVAILABLE")
                    appTakeoverStore.bindTurnDeadline(durableTurn.scope.turnId, bound.at(requestAt))
                }
    override val checkTaskDeadline: () -> Unit = {
                    val deadline = appTakeoverStore.deadlineForTurn(durableTurn.scope.turnId)
                    if (deadline != null && System.currentTimeMillis() >= deadline)
                        throw kotlinx.coroutines.CancellationException("TASK_DEADLINE_REACHED")
                }
    override val onManualTakeoverNeeded: (reason: String) -> Unit = { reason ->
        requestManualTakeover(reason)
    }
    override val stateFlow: MutableStateFlow<AgentState>? = _state
    override val waitIfPaused: suspend () -> Unit = ::waitIfPaused
    override val isPaused: () -> Boolean = { _isPaused.value }
    override val runInterruptibleTool: suspend (suspend () -> ToolExecutionResult) -> ToolExecutionResult = { operation -> PauseAwareToolExecution.run(_isPaused, { _state.value != AgentState.WAITING_PERMISSION }, operation) }
    override val takeManualResumeInstruction: () -> String? = ::takeManualResumeInstruction
    override val refreshMemoryContext: suspend (String, String?) -> com.droidance.core.agent.event.MemoryContextLoadedEventPayload? = { query, foregroundPackage -> knowledge.progressiveMemoryContextProjector.project(
                    query, ruleConfigManager.harnessPolicy().memoryContext, foregroundPackage) }
    override val trySealInput: suspend () -> Boolean = {
                    userInputMutex.withLock {
                        if (!inputClosed && pendingUserSteer.get()) false
                        else { inputClosed = true; true }
                    }
                }
    override val roundContext: () -> String = roundContext
}
)
            materializeOperatorEvidence(intentSourceText)
            if (result.terminal == TurnTerminal.DIRECT_REPLY) {
                return result
            }

            val summary = result.summary.trim()

            // 完成判断完全交给显式终态，不再用 completionMode / postconditionVerifier /
            // validateUiResult / completedWithoutAnyTool 等"完成条件规则"否决。正常结果只接受
            // 模型公开输出；模型通道失败时只返回本地异常说明，不把证据文本伪造成总结。
            val outcome = RuntimeUiResultPolicy.outcome(
                terminal = result.terminal,
                failureReason = result.failureReason
            )

            if (outcome == RuntimeUiOutcome.DONE) {
                val finalSummary = summary
                debugLogManager.appendEvent("✅ run_ui_task completed: ${finalSummary.take(80)}")
                val finalOutput = evidenceLedger.wrapInternalEvidence(finalSummary)
                return result.copy(
                    summary = finalSummary,
                    operatorOutput = finalOutput,
                    terminal = TurnTerminal.DONE,
                )
            }

            if (outcome == RuntimeUiOutcome.NEED_MORE_INFO) {
                debugLogManager.appendEvent("❓ run_ui_task needs more info: ${summary.take(80)}")
                val finalOutput = evidenceLedger.wrapInternalEvidence(summary)
                return result.copy(
                    summary = summary,
                    operatorOutput = finalOutput,
                    terminal = TurnTerminal.NEED_MORE_INFO,
                )
            }

            // 非成功终态只使用模型已给出的公开失败说明，或 Markdown 本地异常文案。
            val systemError = outcome == RuntimeUiOutcome.SYSTEM_ERROR
            val rescueTerminal = if (systemError) {
                TurnTerminal.SYSTEM_ERROR
            } else {
                TurnTerminal.FAILED
            }
            val rescueStatus = when (rescueTerminal) {
                TurnTerminal.DONE -> "done"
                TurnTerminal.SYSTEM_ERROR -> "error"
                else -> "failed"
            }
            val terminalSummary = RuntimeUiResultPolicy.failureSummary(result) {
                ruleConfigManager.terminationFallback(
                    reasonMarker = result.failureReason.name.lowercase()
                )
            }
            val finalFailure = terminalSummary.ifBlank {
                ruleConfigManager.terminationFallback(reasonMarker = "default")
            }
            debugLogManager.appendEvent("⚠️ run_ui_task $rescueStatus: ${finalFailure.take(80)}")
            val finalOutput = evidenceLedger.wrapInternalEvidence(finalFailure)
            return result.copy(
                summary = finalFailure,
                operatorOutput = finalOutput,
                terminal = rescueTerminal,
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val reference = java.util.UUID.randomUUID().toString().take(8)
            debugLogManager.recordRuntimeFailure(reference, "phase=agent_runtime", e)
            debugLogManager.appendEvent(
                "Agent runtime failed: ${e::class.java.simpleName}: ${e.message.orEmpty().take(240)}"
            )
            val failureText = evidenceLedger.wrapInternalEvidence(
                ruleConfigManager.publicFallback("termination_internal_error_detail")
                    .replace("{error_type}", e.javaClass.simpleName).replace("{reference}", reference)
            )
            TurnResult(
                hasToolCalls = false,
                summary = failureText,
                operatorOutput = failureText,
                terminal = TurnTerminal.SYSTEM_ERROR,
                failureReason = TurnFailureReason.RUNTIME_EXCEPTION,
                stopDiagnostic = "RUNTIME_EXCEPTION:$reference:${e.javaClass.simpleName}"
            )
        } finally {
            // Do not retain a notifier across tasks.
        }
    }

    private fun operatorStableSystemContext(): String = listOf(
        runtimePromptPolicy.agentSoulBlock(agentWorkspaceStore.readCustomSoul(activeAgent)),
        runtimePromptPolicy.memoryBehaviorBlock()
    ).filter(String::isNotBlank).joinToString("\n\n")

    /** Same Agent loop, separate Turn and model lease, only while the execution lane is idle. */
    private suspend fun createBackgroundSkillDraft(source: com.droidance.core.agent.event.TurnEventScope) {
        processRecoveryCoordinator.awaitReady()
        val preferences = appContext.getSharedPreferences("skill-authoring", android.content.Context.MODE_PRIVATE)
        if (!preferences.getBoolean("auto_draft", false)) return
        if (eventStore.threadReadModel(source.threadId) == null || eventStore.eventsForTurn(source.turnId).isEmpty()) return
        val request = TurnRequest.create(
            source = MessageSource.DIRECT,
            rawText = ruleConfigManager.readMarkdownSection("skills.md", "auto_draft") +
                "\nsource_thread_id=${source.threadId}\nsource_turn_id=${source.turnId}"
        )
        val profile = OperatorToolProfile.BACKGROUND_KNOWLEDGE
        var binding: ToolTurnServices.Binding? = null
        try {
            taskLifecycle.run(
                conversationId = INTERNAL_SKILL_THREAD_ID,
                request = request,
                initialPrefix = { taskCoordinator.stablePrefix(null, null, profile, operatorStableSystemContext()) },
                unfinishedNotice = { _, error -> TaskLifecycle.FailureNotice(
                    if (error is kotlinx.coroutines.CancellationException) "cancelled" else "error",
                    "Background draft did not finish.", "BACKGROUND_DRAFT_INTERRUPTED"
                ) },
                releaseResources = {
                    binding?.let {
                        try { toolExecutor.releasePrograms(it.sessionContext) }
                        finally { toolTurnServices.release(it) }
                    }
                }
            ) { session ->
                binding = toolTurnServices.bind(
                    sessionContext = session.context,
                    progress = ToolProgressPort { _, _ -> },
                    memory = ToolMemoryPort { Result.failure(IllegalStateException("BACKGROUND_MEMORY_UNAVAILABLE")) },
                    interaction = this,
                    completion = com.droidance.tools.ToolCompletionPort { candidate, state ->
                        if (state.record(candidate)) com.droidance.core.agent.tools.ToolProtocolResult.success("Completion recorded.")
                        else com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
                    },
                    history = com.droidance.tools.ToolHistoryPort { arguments ->
                        val input = kotlinx.serialization.json.Json.parseToJsonElement(arguments).jsonObject
                        if (input["thread_id"]?.jsonPrimitive?.contentOrNull != source.threadId ||
                            input["turn_id"]?.jsonPrimitive?.contentOrNull != source.turnId ||
                            eventStore.threadReadModel(source.threadId) == null) {
                            com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("HISTORY_SOURCE_UNAVAILABLE", false)
                        } else sessionReadService.read(source.threadId, source.turnId, arguments,
                            llmRouter.planStreamChat(emptyList(), hasImage = true).imageIncluded)
                    }
                )
                toolExecutor.resetSession()
                session.activateTools(session.durableTurn.stablePrefix, TurnActivationKind.LOCAL, preparedAtStart = true)
                val result = taskCoordinator.coordinateTask(
                    taskGoal = request.rawText,
                    additionalContext = "",
                    durableTurn = session.durableTurn,
                    turnContext = skillAuthoringContext() + "\n\n" + knowledge.skillEngine.authoringCatalogContext(
                        ruleConfigManager.harnessPolicy().memoryContext.skillCatalogMaxChars) +
                        "\n\nStatic program environment documentation; this does not establish live device readiness:\n" +
                        ruleConfigManager.readMarkdownSection("program.md", "environment") +
                        "\nConfigured program limits:\n" + ruleConfigManager.readMarkdownSection("program.md", "policy"),
                    expectedBackend = null,
                    executionStrategy = null,
                    toolProfile = profile,
                    mode = null
                )
                session.complete(RuntimeTerminalProjection.from(result.terminal).reportStatus,
                    PublicAssistantText.sanitize(result.summary))
                check(result.terminal == TurnTerminal.DONE || result.terminal == TurnTerminal.DIRECT_REPLY) {
                    "BACKGROUND_DRAFT_${result.terminal}"
                }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            debugLogManager.appendEvent("Background skill draft failed: ${error.javaClass.simpleName}")
            throw error
        } finally {
            cacheMaintenance.afterTask()
        }
    }

    private fun skillAuthoringContext(): String = buildString {
        append(ruleConfigManager.readMarkdownSection("skills.md", "context"))
    }

    private data class OperatorTurnContext(
        val text: String,
        val backend: com.droidance.core.agent.attempt.ExecutionBackendObservation?
    )

    private suspend fun operatorTurnContext(
        executionStrategy: DeviceExecutionStrategy,
        availableCapabilities: Set<String>,
        query: String,
        selectedSkillContext: String
    ): OperatorTurnContext {
        val memoryPolicy = ruleConfigManager.harnessPolicy().memoryContext
        val text = listOf(
            skillAuthoringContext(),
            selectedSkillContext,
            knowledge.skillEngine.catalogContext(availableCapabilities, memoryPolicy.skillCatalogMaxChars, query),
            ruleConfigManager.readMarkdownSection("utility_prompts.md", "execution_environment") +
                "\nconfigured_mode=${executionStrategy.selection.effectiveMode}; configured_space=${executionStrategy.spaceSelection.effectiveSpace}",
            "current_time=" + java.time.ZonedDateTime.now().toString()
        ).filter(String::isNotBlank).joinToString("\n\n")
        return OperatorTurnContext(text, null)
    }

    private fun ensureLocalUiAutomationResidency() {
        TaskExecutionService.startStandby(appContext)
    }

    private fun finalizeTaskArtifacts(
        status: ExecutionTerminalStatus,
        durableTurn: AgentTurnLedger.ActiveTurn?
    ) {
        finishScope.launch {
            val started = System.currentTimeMillis()
            try {
                val appliedExperienceRefs = knowledge.finalizeTurn(durableTurn)
                if (status == ExecutionTerminalStatus.DONE && appliedExperienceRefs.isNotEmpty() &&
                    appContext.getSharedPreferences("skill-authoring", android.content.Context.MODE_PRIVATE).getBoolean("auto_draft", false)) {
                    durableTurn?.scope?.let { source ->
                        knowledge.skillReflectionQueue.enqueue(source.threadId, source.turnId)
                        scheduleSkillDraft(com.droidance.core.knowledge.skill.SkillReflectionQueue.Source(source.threadId, source.turnId))
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) {
                debugLogManager.appendEvent("Task learning finalization failed: ${error.javaClass.simpleName}")
            }
            val ms = System.currentTimeMillis() - started
            debugLogManager.appendEvent("⏱ finalizeTaskArtifacts(background): ${ms}ms")
        }
    }

    /** 清空对话历史 */
    override fun clearConversation() {
        if (!processClient.isOwner) { processClient.send("clearConversation", android.os.Bundle()); return }
        conversationEpoch += 1L
        conversationHistory.clear()
        _currentConversationId.value = null
        evidenceLedger.clear() // 清空当前任务证据账本
    }

    // Restore only after every runtime field is initialized; IO work can start immediately.
    init { if (processClient.isOwner) resumeSkillDraftWork() }
}

enum class ManualTakeoverState {
    RUNNING,
    PAUSE_REQUESTED,
    PAUSED,
    RESUMING
}
