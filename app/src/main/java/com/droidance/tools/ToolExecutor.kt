// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.debug.recordRuntimeFailure

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.LocalActionEvidence
import com.droidance.core.agent.device.RuntimeMode

import android.content.Context
import android.os.SystemClock
import com.droidance.R
import com.droidance.agent.LocalActionVerifier
import com.droidance.core.agent.ToolResultStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.projectToolActionEvidence
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.device.debug.DiagnosticsCollector
import com.droidance.debug.ExecutionPathClassifier
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.agent.UserInteractionState
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.settings.ExecutionSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import com.droidance.agent.RuntimeCapabilityRegistry
import com.droidance.core.device.agent.device.DeviceBackend
import com.droidance.agent.device.DisplaySnapshotResolver
import com.droidance.core.agent.device.DeviceExecutionStrategy
import com.droidance.agent.device.DeviceInfoReader
import com.droidance.core.agent.device.ExecutionContext
import com.droidance.core.agent.device.ExecutionContextStore
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.agent.device.VirtualSpaceManager
import com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge

/**
 * 工具执行器
 * 管理和执行所有可用的工具
 */
@Singleton
class ToolExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    internal val adbShell: AdbShellExecutor,
    private val installedAppsManager: com.droidance.core.device.config.InstalledAppsManager,
    private val deepLinkKnowledgeStore: com.droidance.config.DeepLinkKnowledgeStore,
    private val localActionVerifier: LocalActionVerifier,
    private val latencyTracker: com.droidance.core.agent.debug.TaskLatencyTracker,
    private val observeScreenTool: com.droidance.tools.observe.ObserveScreenTool,
    private val toolTurnServices: ToolTurnServices,
    private val diagnostics: DiagnosticsCollector,
    private val debugLogAppender: com.droidance.core.agent.debug.DebugLogAppender,
    private val ruleConfigManager: com.droidance.config.RuleConfigManager,
    private val executionSettingsRepository: ExecutionSettingsRepository,
    private val platformDriver: com.droidance.agent.device.NativeInputDriverClient,
    private val residentNotifications: com.droidance.automation.ResidentNotificationAccess,
    private val capabilityRegistry: RuntimeCapabilityRegistry,
    private val executionContextStore: ExecutionContextStore,
    private val virtualSpaceManager: VirtualSpaceManager,
    private val virtualSpaceSidecarBridge: VirtualSpaceSidecarBridge,
    private val deviceShellCommandChannel: DeviceShellCommandChannel,
    private val deviceBackend: DeviceBackend,
    private val deviceInfoReader: DeviceInfoReader,
    private val capabilityDiscovery: AndroidCapabilityDiscovery,
    private val displaySnapshotResolver: DisplaySnapshotResolver,
    private val appTakeoverStore: com.droidance.automation.AppTakeoverStore,
    private val evidenceLedger: com.droidance.core.agent.TaskEvidenceLedger,
    private val permissionBroker: com.droidance.core.device.permission.PermissionBroker,
    private val programEvents: com.droidance.core.agent.event.AgentEventStore,
    private val programBlobs: com.droidance.core.agent.blob.BlobStore,
    private val knowledgeTools: KnowledgeToolHandler,
    private val workspaceTools: WorkspaceToolHandler,
    private val operationRecorder: com.droidance.agent.OperationRecorder
) : ScriptExecutionPort, com.droidance.core.agent.ports.ToolExecutorPort {
    override fun canOpenPublicLink(link: String): Boolean = runCatching {
        val uri = android.net.Uri.parse(link)
        uri.scheme != null && context.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addCategory(android.content.Intent.CATEGORY_BROWSABLE),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.exported == true
    }.getOrDefault(false)
    private val toolRegistry = ToolRegistry()
    private val observedTargetCache: ObservedTargetCache
        get() = taskState.observedTargetCache
    private val gesturePlanner = HumanGesturePlanner(context.resources)
    private val timingRecorder = ToolTimingRecorder()
    private val completionState: CompletionState
        get() = taskState.completionState
    private val harnessUtilityState: HarnessUtilityState
        get() = taskState.harnessUtilityState
    private val observationEnvelopeStore: ObservationEnvelopeStore
        get() = taskState.observations.observationEnvelopeStore
    private val actionDeliveryByToolCall: MutableMap<String, MutableList<Boolean>>
        get() = taskState.actionDeliveryByToolCall
    private val textInputIdempotencyGuard: TextInputIdempotencyGuard
        get() = taskState.textInputIdempotencyGuard

    private class TaskState(val observations: ToolObservationSession) {
        val currentFrameCropBudget = CurrentFrameCropBudget()
        val observedTargetCache: ObservedTargetCache get() = observations.observedTargetCache
        val completionState: CompletionState = CompletionState()
        val harnessUtilityState: HarnessUtilityState = HarnessUtilityState()
        val actionDeliveryByToolCall: MutableMap<String, MutableList<Boolean>> = mutableMapOf()
        val textInputIdempotencyGuard: TextInputIdempotencyGuard = TextInputIdempotencyGuard()
        @Volatile var lastExecutionDisplayId: Int? = null
        @Volatile var pendingExecutionFallback: PendingExecutionFallback? = null
        var screenSequence: ScreenActionSequence? = null
        var screenSequenceBefore: LocalActionEvidence.ObservationLite? = null
        var screenSequenceGeometry: com.droidance.agent.device.PhysicalDisplaySnapshot? = null
        @Volatile var lastDumpElements: MutableList<DumpElement> = mutableListOf()
    }

    private val observationSession: ToolObservationSession get() = taskState.observations

    override fun currentPendingObservation(): ObservationEnvelope? {
        if (toolTurnServices.currentOrNull() == null) return null
        return observationSession.currentPendingObservation()
    }

    override fun bindModelFrame(observation: ObservationEnvelope) = observationSession.bindModelFrame(observation)
    override fun invalidateModelFrameBinding() = observationSession.invalidateModelFrameBinding()
    override fun invalidatePendingObservation() = observationSession.invalidatePendingObservation()
    override fun consumePendingObservation(): ObservationEnvelope? {
        if (executionContextStore.current() == null) return null
        return observationSession.consumePendingObservation()
    }
    override suspend fun awaitPendingScreenshot(timeoutMs: Long): Boolean =
        observationSession.awaitPendingScreenshot(timeoutMs)

    private val taskState: TaskState
        get() = toolTurnServices.requireCurrent().sessionContext.getOrPutTyped {
            TaskState(ToolObservationSession(executionContextStore, deviceBackend, displaySnapshotResolver,
                observeScreenTool, ruleConfigManager, localActionVerifier, timingRecorder))
        }

    private fun observedTargets(): ObservedTargetCache = taskState.observedTargetCache
    private fun completionForTurn(): CompletionState = taskState.completionState
    private fun inputGuardForTurn(): TextInputIdempotencyGuard = taskState.textInputIdempotencyGuard
    private fun utilityForTurn(): HarnessUtilityState = taskState.harnessUtilityState

    private val autoCaptureAfterTools: Set<String>
        get() {
            val raw = runCatching {
                ruleConfigManager.readMarkdownSection("observation_recovery.md", "auto_capture_after_tools")
            }.getOrElse { error ->
                throw IllegalStateException("RULE_OBSERVATION_RECOVERY_MISSING: failed to read auto_capture_after_tools section from observation_recovery.md", error)
            }
            return runCatching {
                Json.decodeFromString<List<String>>(raw).toSet()
            }.getOrElse { error ->
                throw IllegalStateException("RULE_OBSERVATION_RECOVERY_PARSE_ERROR: invalid auto_capture_after_tools JSON format: $raw", error)
            }
        }

    private suspend fun <T> measureTiming(name: String, block: suspend () -> T): T {
        return timingRecorder.measure(name, block)
    }

    fun recentTimingSummary(limit: Int = 20): String = timingRecorder.summary(limit)

    private var lastExecutionDisplayId: Int?
        get() = taskState.lastExecutionDisplayId
        set(value) { taskState.lastExecutionDisplayId = value }
    private var pendingExecutionFallback: PendingExecutionFallback?
        get() = taskState.pendingExecutionFallback
        set(value) { taskState.pendingExecutionFallback = value }

    /**
     * Activates the exact attempt context that was frozen before the Provider request.
     *
     * This method deliberately does not resolve a route, start a sidecar, or consult current
     * settings. Those operations belong to ExecutionAttemptBindingPreparer before the durable
     * ModelRound commit. Re-resolving here would allow a shell-session or display drift between
     * the model decision and the physical action.
     */
    override fun beginFrozenExecutionContext(
        frozen: com.droidance.core.agent.attempt.ExecutionContext,
        strategy: DeviceExecutionStrategy
    ) {
        val effectiveMode = checkNotNull(strategy.selection.effectiveMode) {
            strategy.selection.unavailableReason ?: "Runtime mode is unavailable."
        }
        check(strategy.spaceSelection.unavailableReason == null) {
            strategy.spaceSelection.unavailableReason ?: "Execution space is unavailable."
        }
        check(frozen.runtimeMode.name == effectiveMode.name) {
            "Frozen execution mode differs from the current strategy"
        }
        check(frozen.executionSpace.name == strategy.spaceSelection.effectiveSpace.name) {
            "Frozen execution space differs from the current strategy"
        }
        val current = executionContextStore.current()
        if (current != null) {
            if (current.executionId == frozen.executionId) {
                registerRetainedVirtualExecution(current)
                return
            }
            // 同一 Turn 内先出现 TURN_LOCAL 轮时，该轮的冻结
            // attempt 不会落库，下一轮会重新准备新的绑定。旧 context 只是本 turn 的临时
            // 激活，替换为最新绑定，避免误判为跨任务上下文冲突。
            executionContextStore.finish(current.executionId)
        }
        val context = ExecutionContext(
            executionId = frozen.executionId,
            backendSessionId = frozen.backendSessionId,
            strategy = strategy,
            space = strategy.spaceSelection.effectiveSpace,
            displayId = frozen.displayId,
            mode = effectiveMode,
            readinessMessage = null
        )
        executionContextStore.start(context)
        applyExecutionContext(context)
        registerRetainedVirtualExecution(context)
    }

    private fun registerRetainedVirtualExecution(context: ExecutionContext): ExecutionContext {
        if (context.space == ExecutionSpace.VIRTUAL_SPACE) {
            val boundary = virtualSpaceManager.beginExecution(context.executionId,
                toolTurnServices.requireCurrent().sessionContext.threadId)
            check(boundary.commandSent && boundary.frameReady) { boundary.detail }
        }
        return context
    }

    suspend fun clearExecutionContext() = withContext(NonCancellable) {
        observationSession.release()
        executionContextStore.current()?.executionId?.let { executionId ->
            withContext(Dispatchers.IO) { virtualSpaceManager.endExecutionKeyboard(executionId) }
        }
        executionContextStore.current()?.executionId?.let(executionContextStore::finish)
        lastExecutionDisplayId = null
    }

    init {
        observeScreenTool.setPendingVisionFrame = { frame ->
            if (frame.targets.isNotEmpty()) {
                observedTargets().cacheTargets(frame.targets)
            }
            val execution = executionContextStore.current()
            if (execution != null && toolTurnServices.currentOrNull() != null) {
                val token = observationEnvelopeStore.beginCapture(
                    executionId = execution.executionId,
                    backendSessionId = execution.backendSessionId,
                    displayId = execution.displayId,
                    rotationDegrees = displaySnapshotResolver.capture(execution.displayId).rotationDegrees,
                    actionId = coroutineContext[ToolInvocation]?.callId ?: "observe_screen"
                )
                observationSession.publishVisionFrame(token, frame)
            }
        }
        registerBuiltinTools()
    }

    fun register(tool: Tool) = toolRegistry.register(tool)


    override suspend fun execute(toolCall: ToolCall): ToolExecutionResult = execute(
        toolCall = toolCall,
        autoCaptureAfterTool = true,
        binding = ToolBinding.DEVICE_BOUND
    )

    override suspend fun executeTurnLocal(toolCall: ToolCall): ToolExecutionResult = execute(
        toolCall = toolCall,
        autoCaptureAfterTool = false,
        binding = ToolBinding.TURN_LOCAL
    )

    override suspend fun awaitToolConfirmation(toolCall: ToolCall, result: ToolExecutionResult): ToolExecutionResult? {
        if (result.diagnosticCode != ToolRiskPolicy.CONFIRMATION_REQUIRED) return result
        val turn = toolTurnServices.requireCurrent()
        val scope = TurnEventScope(turn.sessionContext.threadId, turn.sessionContext.turnId)
        return when (val response = turn.interaction.awaitHighRiskConfirmation(scope, toolCall.id, toolCall.name)) {
            UserInteractionState.RESOLVED -> null
            UserInteractionState.CANCELED -> ToolProtocolResult.notExecuted("USER_CONTEXT_CHANGED", retryable = true)
            else -> ToolRiskPolicy.confirmationRejection(response)
        }
    }

    private suspend fun execute(
        toolCall: ToolCall,
        autoCaptureAfterTool: Boolean,
        binding: ToolBinding
    ): ToolExecutionResult = withContext(coroutineContext[com.droidance.core.agent.tools.ToolExecutionBudget]
        ?: com.droidance.core.agent.tools.ToolExecutionBudget(ruleConfigManager.harnessPolicy().runtimeThresholds.toolExecutionTimeoutMs,
            deviceAccessAllowed = binding == ToolBinding.DEVICE_BOUND, mutationsAllowed = true)) {
        executeInvocation(toolCall, autoCaptureAfterTool, binding)
    }

    private suspend fun executeInvocation(toolCall: ToolCall, autoCaptureAfterTool: Boolean, binding: ToolBinding): ToolExecutionResult {
        coroutineContext.ensureActive()
        if (binding == ToolBinding.TURN_LOCAL &&
            UnifiedDeviceToolProtocol.requirements(toolCall.name, toolCall.arguments).requiresBackend) {
            return ToolProtocolResult.notExecuted("TOOL_BINDING_MISMATCH", false)
        }
        val budget = coroutineContext[com.droidance.core.agent.tools.ToolExecutionBudget]
        if (binding == ToolBinding.DEVICE_BOUND && budget?.deviceAccessAllowed == false)
            return ToolProtocolResult.notExecuted("TOOL_BINDING_MISMATCH", false)
        if (budget?.mutationsAllowed == false &&
            UnifiedDeviceToolProtocol.requirements(toolCall.name, toolCall.arguments).scope != ExecutionScope.TURN_LOCAL &&
            ruleConfigManager.toolContracts().toolContracts[toolCall.name]?.effect == com.droidance.core.agent.event.ToolEffect.STATE_CHANGING)
            return ToolProtocolResult.notExecuted("EXECUTION_MUTATION_RESTRICTED", false)
        val turnBinding = toolTurnServices.requireCurrent()
        val invocationState = turnBinding.sessionContext.getOrPutTyped {
            TaskState(ToolObservationSession(executionContextStore, deviceBackend, displaySnapshotResolver,
                observeScreenTool, ruleConfigManager, localActionVerifier, timingRecorder))
        }

        val executionContext = when (binding) {
            ToolBinding.TURN_LOCAL -> null
            ToolBinding.DEVICE_BOUND -> executionContextStore.requireCurrent().also(::applyExecutionContext)
        }
        val displayId = executionContext?.displayId

        val eventToolCallId = toolCall.id.ifBlank {
            java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        }
        val started = System.currentTimeMillis()
        val startedElapsed = SystemClock.elapsedRealtime()
        val observationTiming = ObservationTiming()
        val batchProgress = ScreenActionBatchProgress()
        val tapFeedback = TapFeedbackCapture(eventToolCallId, observationSession.decisionObservation)
        var completedToolResult: ToolExecutionResult? = null
        var handlerStarted = false
        val invocationScreenMember = coroutineContext[ScreenActionSequenceMember]
        var preActionObservationMetrics: Map<String, Long> = emptyMap()
        var preActionObservationFinishedAtElapsedMs: Long? = null
        fun finishEvent(result: ToolExecutionResult): ToolExecutionResult {
            val feedbackResult = tapFeedback.attach(result, programBlobs)
            val measured = feedbackResult.copy(metrics = feedbackResult.metrics + observationTiming.snapshot() + preActionObservationMetrics + buildMap {
                put("tool_execution_ms", SystemClock.elapsedRealtime() - startedElapsed)
                if (UnifiedDeviceToolProtocol.isScreenAction(toolCall.name)) {
                    put(if (UnifiedDeviceToolProtocol.operation(toolCall.name, toolCall.arguments) == "batch")
                        "screen_action_batch_calls" else "screen_action_scalar_calls", 1L)
                }
                if (invocationScreenMember != null) {
                    put("screen_sequence_member", 1L)
                    put("screen_sequence_final", if (invocationScreenMember.last) 1L else 0L)
                }
            })
            val delivered = synchronized(invocationState.actionDeliveryByToolCall) {
                invocationState.actionDeliveryByToolCall.remove(eventToolCallId)?.all { it }
            }
            val completed = if (delivered == null) measured else measured.copy(
                actionEvidence = (measured.actionEvidence ?: projectToolActionEvidence(null, null, null, null, null)).copy(
                    delivery = if (measured.executionStatus in setOf(com.droidance.core.agent.event.ToolExecutionStatus.INTERRUPTED,
                        com.droidance.core.agent.event.ToolExecutionStatus.CANCELED)) com.droidance.core.agent.event.ActionDeliveryEvidence.UNKNOWN
                        else if (delivered) com.droidance.core.agent.event.ActionDeliveryEvidence.ACTION_DELIVERED
                        else com.droidance.core.agent.event.ActionDeliveryEvidence.NOT_DELIVERED
                )
            )
            val retained = if (toolCall.name == DeviceToolNames.SHELL && completed.data != null) {
                val ref = programBlobs.put(completed.outputText.toByteArray()).contentRef
                completed.copy(sourceRef = ref, dataRef = ref)
            } else completed
            operationRecorder.recordToolExecution(
                sourceCallId = eventToolCallId,
                toolName = toolCall.name,
                argumentsJson = toolCall.arguments,
                result = retained
            )
            return retained
        }

        if (toolCall.name in EffectCheckpointLedger.deviceTools && runCatching {
            Json.parseToJsonElement(toolCall.arguments).jsonObject.containsKey("effect_key")
        }.getOrDefault(false)) {
            EffectCheckpointLedger.admission(toolCall.name, toolCall.arguments,
                programEvents.eventsForTurn(turnBinding.sessionContext.turnId),
                com.droidance.core.agent.event.TurnEventScope(turnBinding.sessionContext.threadId, turnBinding.sessionContext.turnId),
                eventToolCallId)?.let { return finishEvent(it) }
        }

        val frameWaitMs = if (UnifiedDeviceToolProtocol.isScreenAction(toolCall.name)) {
            val value = runCatching { Json.parseToJsonElement(toolCall.arguments).jsonObject["wait_for_frame_ms"] }.getOrNull()
            if (value == null) 0L else {
                val parsed = (value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
                if (parsed == null || parsed !in 0..ruleConfigManager.harnessPolicy().screenActionBatch.maxWaitMs)
                    return finishEvent(ToolProtocolResult.invalidArgument("wait_for_frame_ms", "outside_configured_wait_budget"))
                parsed
            }
        } else 0L

        if (UnifiedDeviceToolProtocol.isScreenAction(toolCall.name)) {
            val boundExecution = checkNotNull(executionContext)
            val member = coroutineContext[ScreenActionSequenceMember]
            if (member != null) {
                val geometry = displaySnapshotResolver.capture(boundExecution.displayId)
                if (member.first) {
                    invocationState.screenSequence = member.sequence
                    invocationState.screenSequenceGeometry = geometry
                } else if (invocationState.screenSequence !== member.sequence ||
                    invocationState.screenSequenceGeometry != geometry) {
                    return finishEvent(ToolProtocolResult.notExecuted("BATCH_EXECUTION_SURFACE_CHANGED", retryable = true))
                }
            }
            if (!observationSession.hasCurrentModelObservationLease(boundExecution)) {
                return finishEvent(
                    ToolProtocolResult.notExecuted(
                        code = "STALE_OBSERVATION",
                        retryable = true
                    )
                )
            }
            // Lightweight pixel samples also protect later targets in the shared frame.
            // Normal field edits can continue; a shifted keyboard/layout stops the suffix.
            if (UnifiedDeviceToolProtocol.operation(toolCall.name, toolCall.arguments) !in setOf("batch", "press_key", "scroll")) {
                val targetId = extractTargetId(toolCall.arguments)
                val (targetX, targetY) = extractTargetCoordinates(toolCall.arguments)
                observationSession.setActionTarget(targetId, targetX, targetY)
                val preActionCheck = withContext(observationTiming) { observationSession.checkPreActionObservation(boundExecution) }
                preActionObservationMetrics = preActionCheck.metrics
                preActionObservationFinishedAtElapsedMs = preActionCheck.finishedAtElapsedMs
                preActionCheck.rejection?.let {
                    // A missing/changed pre-action sample cannot authorize this decision frame on a
                    // later retry. The next model round must bind a newly observed envelope.
                    invalidateModelFrameBinding()
                    return finishEvent(it)
                }
            }
        }

        val tool = toolRegistry.getTool(toolCall.name)
            ?: return finishEvent(
                ToolProtocolResult.notExecuted(
                    code = "TOOL_NOT_FOUND",
                    retryable = false,
                    details = buildJsonObject { put("tool", JsonPrimitive(toolCall.name)) }
                )
            )
        if (UnifiedDeviceToolProtocol.requirements(toolCall.name, toolCall.arguments).requiresBackend && !isShellBackedToolAvailable(toolCall.name)) {
            return finishEvent(
                ToolProtocolResult.notExecuted(
                    code = "CHANNEL_DOWN",
                    retryable = false,
                    details = buildJsonObject { put("tool", JsonPrimitive(toolCall.name)) }
                )
            )
        }
        return try {
            val riskDecision = ToolRiskPolicy.assess(
                toolName = toolCall.name,
                arguments = toolCall.arguments,
                policy = ruleConfigManager.harnessPolicy().toolSafety,
                scope = TurnEventScope(turnBinding.sessionContext.threadId, turnBinding.sessionContext.turnId),
                callId = eventToolCallId
            )
            if (!riskDecision.allowed) {
                val rejection = checkNotNull(riskDecision.rejection)
                if (riskDecision.requiresConfirmation) {
                    return rejection
                }
                val durationMs = System.currentTimeMillis() - started
                timingRecorder.record(
                    name = toolCall.name,
                    durationMs = durationMs,
                    success = false,
                    detail = riskDecision.reason
                )
                return finishEvent(rejection)
            }
            turnBinding.progress.updateCurrentToolDetail(toolCall.name, toolCall.arguments)
            val executionPath = ExecutionPathClassifier.classify(toolCall.name, toolCall.arguments)
            preActionObservationFinishedAtElapsedMs?.let { finishedAt ->
                preActionObservationMetrics = preActionObservationMetrics + (
                    "pre_action_observation_check_to_handler_gap_ms" to
                        (SystemClock.elapsedRealtime() - finishedAt).coerceAtLeast(0L)
                    )
            }
            val sequenceMember = coroutineContext[ScreenActionSequenceMember]
            val deferredObservation = UnifiedDeviceToolProtocol.isScreenAction(toolCall.name) &&
                !UnifiedDeviceToolProtocol.isObservationOnlyAction(toolCall.name, toolCall.arguments) &&
                (frameWaitMs > 0 || sequenceMember != null ||
                    UnifiedDeviceToolProtocol.operation(toolCall.name, toolCall.arguments) == "batch")
            val groupBefore = if (deferredObservation) {
                if (sequenceMember != null && !sequenceMember.first) invocationState.screenSequenceBefore
                else captureLocalObservation(preferLastFrame = true).also {
                    invocationState.screenSequenceBefore = it
                }
            } else null
            // The frame this gesture was decided on, to tell whether content under it moved.
            val gestureBeforeFrame = if (groupBefore != null && toolCall.name == DeviceToolNames.SCREEN_ACT &&
                com.droidance.core.agent.tools.GestureEffect.applies(toolCall.arguments)) deviceBackend.lastScreenFrame() else null
            val repeatedStillGesture = gestureBeforeFrame?.let { stalledGestureRepeat(toolCall.arguments, it) }
            var result = try {
                handlerStarted = true
                repeatedStillGesture ?: withContext(ToolInvocation(eventToolCallId) + observationTiming + batchProgress + tapFeedback) {
                    tool.execute(toolCall.arguments)
                }
            } finally {
                if (UnifiedDeviceToolProtocol.isScreenAction(toolCall.name) ||
                    toolCall.name == DeviceToolNames.SHELL && UnifiedDeviceToolProtocol.expectsScreenChange(toolCall.name, toolCall.arguments)) {
                    invocationState.observations.invalidateModelFrameBinding()
                }
            }
            completedToolResult = result
            if (toolCall.name == DeviceToolNames.SHELL && result.executionStatus in
                setOf(com.droidance.core.agent.event.ToolExecutionStatus.INTERRUPTED, com.droidance.core.agent.event.ToolExecutionStatus.CANCELED))
                invalidateModelFrameBinding()
            check(toolTurnServices.currentOrNull() === turnBinding) { "Tool result arrived after its turn was released" }
            val toolNotExecuted = ToolResultStatus.isNotExecuted(result)
            // Sample and publish the completed action group within this invocation.
            val screenMember = coroutineContext[ScreenActionSequenceMember]
            val sequenceContinues = screenMember != null && !screenMember.last &&
                allowsOrderedContinuation(result)
            if (!toolNotExecuted && !sequenceContinues && groupBefore != null) {
                // Settle the entire deferred group once, then publish that same frame below.
                // Pixel change is a device fact, never an assertion that the task succeeded.
                try {
                    // Wait once at the group boundary, then publish one fresh full frame.
                    val captureStarted = SystemClock.elapsedRealtime()
                    var captureSamples = 0L
                    val after = withContext(observationTiming) {
                        batchProgress.finalObservation?.takeIf {
                            (frameWaitMs == 0L || batchProgress.finalObservationWaitSatisfied) && reusablePostActionFrame?.let { frame ->
                                frame.displayId == deviceBackend.activeDisplayId &&
                                System.currentTimeMillis() - frame.capturedAtMs in 0..observationSession.postActionReusableFrameTtlMs()
                            } == true
                        } ?: observationSession.captureSettledObservation(frameWaitMs,
                            onCapture = { captureSamples++ }, actionEndedAtElapsedMs = batchProgress.lastActionEndedAtElapsedMs)
                    }
                    result = result.copy(metrics = result.metrics + mapOf(
                        "action_group_final_observations" to 1L,
                        "action_group_observation_ms" to (SystemClock.elapsedRealtime() - captureStarted),
                        "action_group_capture_samples" to captureSamples
                    ))
                    result = appendLocalValidation(result,
                        localActionVerifier.validate(toolCall.name, toolCall.arguments, groupBefore, after))
                    gestureEffect(toolCall.arguments, gestureBeforeFrame)?.let { result = appendToolResultFeedback(result, it) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed observation cannot erase known delivery or the completed batch prefix.
                    result = appendToolResultFeedback(result,
                        "Post-action observation unavailable. Preserve the execution receipt and inspect a fresh frame before continuing.")
                        .copy(evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                            diagnosticCode = "POST_ACTION_OBSERVATION_UNAVAILABLE")
                } finally {
                    invocationState.screenSequenceBefore = null
                }
            }
            if (!toolNotExecuted && !sequenceContinues && autoCaptureAfterTool && shouldAutoCaptureAfterTool(toolCall, result)) {
                val boundExecutionContext = checkNotNull(executionContext) {
                    "Post-action capture requires DEVICE_BOUND execution"
                }
                val boundDisplayId = checkNotNull(displayId)
                autoCaptureJob?.cancelAndJoin()
                val captureToken = observationEnvelopeStore.beginCapture(
                    executionId = boundExecutionContext.executionId,
                    backendSessionId = boundExecutionContext.backendSessionId,
                    displayId = boundExecutionContext.displayId,
                    rotationDegrees = displaySnapshotResolver.capture(
                        boundExecutionContext.displayId
                    ).rotationDegrees,
                    actionId = eventToolCallId
                )
                val reusableFrame = observationSession.consumeReusablePostActionFrame(boundDisplayId)
                observeScreenTool.invalidateCache()
                if (reusableFrame == null) {
                    deviceBackend.invalidateScreenCache()
                }
                kotlinx.coroutines.supervisorScope {
                    autoCaptureJob = launch(Dispatchers.IO + observationTiming) {
                        try {
                            if (toolTurnServices.currentOrNull() !== turnBinding) return@launch
                            // Reuse the captured result or publish a fresh frame; readiness is assessed from actual pixels.
                            val autoFrame = reusableFrame ?: invocationState.observations.captureSettledAutoFrame(frameWaitMs)
                            coroutineContext.ensureActive()
                            if (autoFrame != null) {
                                val visionProviderAvailable = capabilityRegistry.current().visionProviderAvailable
                                run {
                                    val mode = if (visionProviderAvailable) {
                                        com.droidance.tools.observe.ObserveScreenTool.MODE_PURE_VLM
                                    } else {
                                        com.droidance.tools.observe.ObserveScreenTool.MODE_NONE
                                    }
                                    // Always publish a clean full-screen post-action frame. A prior
                                    // explicit crop mapper is stale for this new observation.
                                    val postActionModelFrame = if (visionProviderAvailable) {
                                        invocationState.observations.prepareVisionFrame(autoFrame)
                                    } else {
                                        null
                                    }
                                    coroutineContext.ensureActive()
                                    if (toolTurnServices.currentOrNull() !== turnBinding) return@launch
                                    invocationState.observations.publishPreparedVisionFrame(
                                        captureToken,
                                        mode,
                                        readableText = null,
                                        preparedFrame = postActionModelFrame,
                                        screenText = null,
                                        includeImage = visionProviderAvailable,
                                        producedAtMs = autoFrame.producedAtMs,
                                        nativeFrame = autoFrame
                                    )
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) {
                            // A missing result frame cannot erase an already delivered action.
                            result = appendToolResultFeedback(result,
                                "Post-action observation unavailable. Inspect a fresh frame before continuing.")
                                .copy(evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                                    diagnosticCode = "POST_ACTION_OBSERVATION_UNAVAILABLE")
                        }
                    }
                    autoCaptureJob?.join()
                }
            }

            coroutineContext.ensureActive()
            val durationMs = System.currentTimeMillis() - started
            val verified = ToolResultStatus.isSuccess(result)
            val acceptedForContinuation = verified || (
                toolCall.name == DeviceToolNames.ANDROID_ACT &&
                    ToolResultStatus.isLaunchAccepted(result)
                )
            recordExecutionFallback(executionPath, toolCall.name, result, acceptedForContinuation)
            timingRecorder.record(
                name = toolCall.name,
                durationMs = durationMs,
                success = acceptedForContinuation,
                detail = result.outputText
            )
            finishEvent(result)
        } catch (e: CancellationException) {
            batchProgress.attachTo(e)
            val receipt = e.screenActionBatchCancellationReceiptOrNull()
            finishEvent(
                if (receipt != null) ToolProtocolResult.canceled(
                    code = "SCREEN_ACT_BATCH_CANCELLED", details = receipt.toJsonDetails()
                ).copy(metrics = receipt.progressMetrics())
                else completedToolResult?.copy(
                    executionStatus = com.droidance.core.agent.event.ToolExecutionStatus.CANCELED,
                    evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                    diagnosticCode = "POST_ACTION_OBSERVATION_CANCELLED"
                ) ?: if (toolCall.name == DeviceToolNames.SHELL && handlerStarted)
                    ToolProtocolResult.interrupted("SHELL_CANCELED_EFFECT_UNKNOWN")
                        .copy(executionStatus = com.droidance.core.agent.event.ToolExecutionStatus.CANCELED)
                else ToolProtocolResult.notExecuted(code = "TOOL_EXECUTION_CANCELLED", retryable = false)
            )
            if (toolCall.name == DeviceToolNames.SHELL && handlerStarted) invalidateModelFrameBinding()
            throw e
        } catch (e: Exception) {
            runCatching {
                debugLogAppender.recordRuntimeFailure(eventToolCallId,
                    "phase=tool_execution tool=${toolCall.name} handler_started=$handlerStarted", e)
            }
            if (toolCall.name == DeviceToolNames.SHELL && handlerStarted) {
                invalidateModelFrameBinding()
                return finishEvent(ToolProtocolResult.interrupted("SHELL_EFFECT_UNKNOWN"))
            }
            val durationMs = System.currentTimeMillis() - started
            timingRecorder.record(toolCall.name, durationMs, false, e.message.orEmpty())
            finishEvent(
                ToolProtocolResult.failed(
                    code = "TOOL_EXECUTION_FAILED",
                    retryable = false,
                    error = e
                )
            )
        }
    }

    private fun shouldAutoCaptureAfterTool(toolCall: ToolCall, result: ToolExecutionResult): Boolean {
        return toolCall.name in autoCaptureAfterTools || toolCall.name == DeviceToolNames.SHELL &&
            (UnifiedDeviceToolProtocol.expectsScreenChange(toolCall.name, toolCall.arguments) ||
                result.executionStatus in setOf(com.droidance.core.agent.event.ToolExecutionStatus.INTERRUPTED, com.droidance.core.agent.event.ToolExecutionStatus.CANCELED))
    }

    override suspend fun capabilityContext(packageName: String?, appName: String?, inventoryPackageName: String?,
                                           inventoryQuery: String?, maxChars: Int): String =
        capabilityDiscovery.context(packageName, appName, inventoryPackageName, inventoryQuery, maxChars)

    override fun screenVisualFingerprint(imagePng: ByteArray): Long? = com.droidance.core.device.agent.ScreenVisualFingerprint.fromBytes(imagePng)

    override fun screenChangeSignature(imagePng: ByteArray): com.droidance.core.agent.ScreenChangeSignature? =
        com.droidance.core.device.agent.ScreenVisualFingerprint.changeSignature(imagePng)

    override fun schedulesPostActionCapture(toolName: String): Boolean =
        toolName in autoCaptureAfterTools || toolName == DeviceToolNames.SHELL

    override fun getOperatorToolDefinitions(
        mode: com.droidance.core.agent.device.RuntimeMode?,
        executionSpace: com.droidance.core.agent.device.ExecutionSpace?,
        profile: OperatorToolProfile
    ): List<ToolDefinition> = getOperatorTools(mode, executionSpace, profile).map(Tool::definition)

    override fun getOperatorTools(
        mode: com.droidance.core.agent.device.RuntimeMode?,
        executionSpace: com.droidance.core.agent.device.ExecutionSpace?,
        profile: OperatorToolProfile
    ): List<Tool> {
        val names = ToolCatalog.operatorToolNamesFor(mode, executionSpace, profile)
        return names.map { name ->
            checkNotNull(toolRegistry.getTool(name)) { "Operator tool '$name' is not registered" }
        }
    }

    private fun isShellBackedToolAvailable(toolName: String): Boolean {
        if (toolName !in ToolCatalog.shellBackedIntentToolNames) return true
        // The resident shell Binder remains usable after the online ADB connection drops.
        // DeviceShellCommandChannel resolves the selected shell transport before dispatch.
        return deviceShellCommandChannel.isAvailable()
    }

    /** Settle task-owned programs before their observation/device binding is released. */
    suspend fun releasePrograms(owner: com.droidance.core.agent.TurnSessionContext) {
        (toolRegistry.getTool("program") as? com.droidance.program.ProgramTool)?.release(owner)
    }

    /** 重置会话级工具缓存。 */
    fun resetSession() {
        if (toolTurnServices.currentOrNull() == null) return
        observeScreenTool.invalidateCache()
        pendingExecutionFallback = null
        textInputIdempotencyGuard.reset()
        synchronized(actionDeliveryByToolCall) { actionDeliveryByToolCall.clear() }
    }

    /** The latest gesture of this execution whose content did not move, keyed by execution id. */
    private var stalledGesture: Pair<String, com.droidance.core.agent.tools.GestureEffect.StillGesture>? = null

    private fun gestureEffect(arguments: String,
                              before: com.droidance.core.device.tools.DeviceScreenFrame?): String? {
        stalledGesture = null
        val after = deviceBackend.lastScreenFrame()?.takeIf { before != null && it !== before && it.displayId == before.displayId }
            ?: return null
        val beforeSignature = screenChangeSignature(checkNotNull(before).pngBytes) ?: return null
        val afterSignature = screenChangeSignature(after.pngBytes) ?: return null
        val still = com.droidance.core.agent.tools.GestureEffect.still(arguments, beforeSignature, afterSignature) ?: return null
        executionContextStore.current()?.executionId?.let { stalledGesture = it to still }
        return com.droidance.core.agent.tools.GestureEffect.describe(still)
    }

    /**
     * Repeating a gesture whose content just did not move, over the same unchanged area and in the
     * same direction, cannot change the screen. It is not delivered; the receipt names the only
     * direction that can still bring other content into view.
     */
    private fun stalledGestureRepeat(arguments: String, current: com.droidance.core.device.tools.DeviceScreenFrame): ToolExecutionResult? {
        val (executionId, still) = stalledGesture ?: return null
        if (executionId != executionContextStore.current()?.executionId) return null
        val signature = screenChangeSignature(current.pngBytes) ?: return null
        if (!com.droidance.core.agent.tools.GestureEffect.repeats(still, arguments, signature)) return null
        return ToolProtocolResult.notExecuted("GESTURE_AT_END", retryable = true, details = buildJsonObject {
            put("finger_direction", JsonPrimitive(still.fingerDirection))
            put("content_moved", JsonPrimitive(false))
            put("reason", JsonPrimitive("The previous ${still.op} moving the finger ${still.fingerDirection} over this area did not move its content, and the area is unchanged since."))
            put("recovery_condition", JsonPrimitive("Move the finger ${still.oppositeDirection} to bring other content into view, or use another control."))
        })
    }

    fun appendToolResultFeedback(result: ToolExecutionResult, feedback: String): ToolExecutionResult {
        val normalized = feedback.trim()
        if (normalized.isBlank()) return result
        return result.copy(outputText = result.outputText.trimEnd() + "\n\n" + normalized)
    }

    private fun appendLocalValidation(
        result: ToolExecutionResult,
        validation: LocalActionEvidence.ValidationResult?
    ): ToolExecutionResult {
        if (validation == null) return result
        val captured = validation.progressState in setOf(
            LocalActionEvidence.ProgressState.RAW_CHANGED,
            LocalActionEvidence.ProgressState.STATE_CHANGED,
            LocalActionEvidence.ProgressState.GOAL_PROGRESS,
            LocalActionEvidence.ProgressState.ACTION_CONFIRMED,
            LocalActionEvidence.ProgressState.ROUTE_VERIFIED
        )
        return appendToolResultFeedback(result, validation.toToolLine()).copy(
            validation = validation.toEvidence(),
            diagnosticCode = result.diagnosticCode ?: validation.progressState.name.takeUnless { captured },
            evidenceStatus = when {
                validation.progressState == LocalActionEvidence.ProgressState.ROUTE_FAILED -> ToolEvidenceStatus.INVALID
                captured -> ToolEvidenceStatus.CAPTURED
                else -> ToolEvidenceStatus.UNKNOWN
            },
            actionEvidence = projectToolActionEvidence(
                delivered = when (result.actionEvidence?.delivery) {
                    com.droidance.core.agent.event.ActionDeliveryEvidence.ACTION_DELIVERED -> true
                    com.droidance.core.agent.event.ActionDeliveryEvidence.NOT_DELIVERED -> false
                    else -> null
                },
                progressState = validation.progressState.name,
                rawChanged = validation.rawChanged,
                semanticChanged = validation.semanticChanged,
                goalProgress = validation.goalProgress
            )
        )
    }

    private suspend fun recordExecutedActionResult(result: DeviceInputResult?) {
        val success = result?.success ?: return
        coroutineContext[TapFeedbackCapture]?.record(result, coroutineContext[TapRequest])
        coroutineContext[ToolInvocation]?.callId?.let { callId ->
            synchronized(actionDeliveryByToolCall) {
                actionDeliveryByToolCall.getOrPut(callId) { mutableListOf() }.add(success)
            }
        }
        if (!success) return
        observationSession.lastActionEndedAtElapsedMs = SystemClock.elapsedRealtime()
    }

    fun currentExecutionDisplayId(): Int? = lastExecutionDisplayId

    private fun applyExecutionContext(context: ExecutionContext) {
        lastExecutionDisplayId = context.displayId
    }

    private suspend fun captureLocalObservation(
        visibleText: String = "",
        preferLastFrame: Boolean = false,
        forceFresh: Boolean = false
    ): LocalActionEvidence.ObservationLite {
        return localActionVerifier.capture(
            visibleText = visibleText,
            preferLastFrame = preferLastFrame,
            forceFresh = forceFresh
        )
    }


    private fun registerBuiltinTools() {
        workspaceTools.registerAll(::register, { toolRegistry.getTool(it)?.definition }, evidenceLedger::hasCompletionEvidence)
        val programTool = com.droidance.program.ProgramTool(
            context = context,
            rules = { section -> ruleConfigManager.readMarkdownSection("program.md", section) },
            workspace = workspaceTools.taskWorkspace,
            workspacePolicy = { Json.decodeFromString<com.droidance.core.agent.workspace.WorkspacePolicy>(
                ruleConfigManager.readMarkdownSection("workspace.md", "policy")) },
            readArtifact = { arguments -> toolTurnServices.requireCurrent().history.read(arguments) },
            session = { toolTurnServices.requireCurrent().sessionContext },
            execution = { executionContextStore.current() },
            observation = { currentPendingObservation() },
            modelObservationSequence = { observationSession.modelObservationSequence },
            deviceCall = { name, arguments, childId ->
                val childCall = ToolCall(childId, name, arguments.toString())
                val raw = if (UnifiedDeviceToolProtocol.requirements(name, arguments.toString()).requiresBackend) {
                    execute(childCall)
                } else executeTurnLocal(childCall)
                if (UnifiedDeviceToolProtocol.isScreenAction(name) || name == DeviceToolNames.SHELL &&
                    (UnifiedDeviceToolProtocol.expectsScreenChange(name, arguments.toString()) ||
                        raw.executionStatus in setOf(com.droidance.core.agent.event.ToolExecutionStatus.INTERRUPTED, com.droidance.core.agent.event.ToolExecutionStatus.CANCELED)))
                    awaitPendingScreenshot(2000)
                val result = ToolObservationReference.forGroundedCall(raw,
                    name == DeviceToolNames.ANDROID_READ, toolTurnServices.requireCurrent().sessionContext.turnId, childId)
                if (name == DeviceToolNames.SHELL && result.isVerifiedSuccess) {
                    result.sourceRef?.let { ref ->
                        evidenceLedger.recordDerivedSource(ref, name, dataRef = result.dataRef,
                            outputPreview = result.outputText.take(200))
                    }
                }
                if (name == DeviceToolNames.ANDROID_READ && result.isVerifiedSuccess &&
                    !UnifiedDeviceToolProtocol.isScreenObservation(name, arguments.toString())) {
                    val ref = ToolObservationReference.fromResult(result)
                    if (ref != null && evidenceLedger.observationById(ref) == null) {
                        evidenceLedger.recordObservation(observationId = ref, toolName = name,
                            rawText = result.outputText, sourceKind = com.droidance.core.agent.EvidenceSourceKind.OBSERVED_STATE,
                            actionCallId = childId)
                    }
                }
                result
            },
            events = programEvents, blobs = programBlobs, skillEngine = knowledgeTools.skillEngine
        )
        register(programTool)
        val touchInput = TouchInputService(
            deviceBackend = deviceBackend,
            planScroll = gesturePlanner::safeSwipe,
            preflightScrollTarget = preflight@{ physicalStart ->
                // A scroll reads content and taps nothing, so changed pixels under its start are not
                // a hazard. Without an in-batch checkpoint, only require the page (foreground
                // activity or window) the batch started on; a different or unknown page falls back
                // to the pixel check.
                if (coroutineContext[ScreenBatchScrollCheckpoint] == null) {
                    val decided = taskState.screenSequenceBefore?.semanticState?.foregroundActivityOrWindow?.trim()
                    val current = localActionVerifier.currentForegroundComponentForContext()?.trim()
                    if (!decided.isNullOrBlank() && decided == current)
                        return@preflight ToolProtocolResult.success("Scroll stays on the page the batch started on.")
                            .copy(metrics = mapOf("pre_action_scroll_same_page" to 1L))
                }
                val execution = executionContextStore.requireCurrent()
                val target = currentModelCoordinateMapper.toModel(physicalStart.x, physicalStart.y)
                val check = observationSession.checkPreActionObservation(execution, targetX = target.x, targetY = target.y, usePendingTarget = false,
                    scrollCheckpoint = coroutineContext[ScreenBatchScrollCheckpoint])
                if (check.rejection != null) invalidateModelFrameBinding()
                (check.rejection ?: ToolProtocolResult.success("Gesture start remains grounded.")).copy(metrics = check.metrics)
            },
            scrollDuration = gesturePlanner::durationForSpeed,
            describeTarget = { x, y -> observedTargets().describeAt(x, y) },
            performVerifiedTap = { toolName, arguments, label, x, y, precision ->
                this@ToolExecutor.performVerifiedTap(toolName, arguments, label, x, y, precision = precision)
            },
            captureObservation = { forceFresh ->
                this@ToolExecutor.captureLocalObservation(preferLastFrame = !forceFresh, forceFresh = forceFresh)
            },
            captureSettledObservation = {
                observationSession.captureGestureObservation()
            },
            stagePostActionObservation = { observationSession.stageReusablePostActionFrame(it) },
            validate = { toolName, arguments, before, after, runtimeProgress ->
                val validation = localActionVerifier.validate(toolName, arguments, before, after)
                if (runtimeProgress) {
                    localActionVerifier.confirmRuntimeProgress(
                        validation,
                        "text value was read back from the intended input region"
                    )
                } else {
                    validation
                }
            },
            appendValidation = ::appendLocalValidation,
            recordExecutedActionResult = { result -> recordExecutedActionResult(result) },
            coordinateMapper = { currentModelCoordinateMapper },
            resolveTarget = { id -> observedTargets().resolveModelPoint(id) }
        )
        val textInput = TextInputService(
            deviceBackend = deviceBackend,
            captureObservation = { forceFresh ->
                this@ToolExecutor.captureLocalObservation(preferLastFrame = !forceFresh, forceFresh = forceFresh)
            },
            captureSettledObservation = {
                observationSession.captureSettledObservation()
            },
            stagePostActionObservation = { observationSession.stageReusablePostActionFrame(it) },
            verifyTextDelivery = ::verifyTextDelivery,
            inputFocusProbeTimeoutMs = ::inputFocusProbeTimeoutMs,
            resolveSemanticTarget = { query ->
                searchElements(query).firstOrNull()?.let { ModelCoordinateMapper.Point(it.cx, it.cy) }
            },
            wasTextInputVerified = { pageKey, targetKey, text ->
                inputGuardForTurn().wasVerified(pageKey, targetKey, text)
            },
            recordTextInputVerification = { pageKey, targetKey, text, deliveryVerified ->
                inputGuardForTurn().record(pageKey, targetKey, text, deliveryVerified)
            },
            recordInputDiagnostic = { text, outcome, detail ->
                diagnostics.logTypeText(
                    textPreview = text,
                    path = "guard/$outcome",
                    success = false,
                    durationMs = 0L,
                    reason = detail
                )
            },
            validate = { toolName, arguments, before, after, runtimeProgress ->
                val validation = localActionVerifier.validate(toolName, arguments, before, after)
                if (runtimeProgress) {
                    localActionVerifier.confirmRuntimeProgress(
                        validation,
                        "text value was read back from the intended input region"
                    )
                } else {
                    validation
                }
            },
            appendValidation = ::appendLocalValidation,
            recordExecutedActionResult = { result -> recordExecutedActionResult(result) },
            coordinateMapper = { currentModelCoordinateMapper },
            resolveTarget = { id -> observedTargets().resolveModelPoint(id) },
            deviceLocked = {
                if (executionContextStore.current()?.isVirtualSpace == true) {
                    false
                } else {
                    (context.getSystemService(android.content.Context.KEYGUARD_SERVICE)
                        as android.app.KeyguardManager).isKeyguardLocked
                }
            }
        )

        val appLaunchService = AppLaunchService(
            context = context,
            adbShell = adbShell,
            installedAppsManager = installedAppsManager,
            localActionVerifier = localActionVerifier,
            executionContextStore = executionContextStore,
            deviceBackend = deviceBackend,
            deepLinkKnowledgeStore = deepLinkKnowledgeStore,
            appendLocalValidation = ::appendLocalValidation,
            isDeepLinkEnabled = { executionSettingsRepository.isDeepLinkEnabled() }
        )
        NotificationToolRegistrar(
            ::register,
            deviceShellCommandChannel,
            installedAppsManager,
            appTakeoverStore,
            ruleConfigManager,
            executionContextStore,
            requestTime = {
                programEvents.eventsForTurn(toolTurnServices.requireCurrent().sessionContext.turnId).lastOrNull {
                    it.eventKind == com.droidance.core.agent.event.AgentEventKind.USER_MESSAGE ||
                        it.eventKind == com.droidance.core.agent.event.AgentEventKind.USER_STEER
                }?.occurredAt ?: error("TAKEOVER_REQUEST_TIME_MISSING")
            },
            requestTurnId = { toolTurnServices.requireCurrent().sessionContext.turnId },
            requestModelSelection = { checkNotNull(toolTurnServices.requireCurrent().sessionContext
                .getTyped<com.droidance.automation.AppTakeoverModelSelection>()) },
            notifications = residentNotifications
        ).registerAll()
        val keyInput = KeyInputService(deviceBackend)
        val contentQueryService = AndroidContentQueryService(
            context = context,
            permissionReason = { uri -> context.getString(R.string.runtime_permission_request_reason, uri) },
            permissionBroker = permissionBroker,
            permissionOwner = { toolTurnServices.requireCurrent().sessionContext.turnId to
                requireNotNull(kotlinx.coroutines.currentCoroutineContext()[ToolInvocation]).callId },
            permissionTimeoutMillis = {
                ruleConfigManager.harnessPolicy().runtimeThresholds.manualTakeoverWaitTimeoutMs
            },
            onPermissionWaiting = { request ->
                toolTurnServices.requireCurrent().interaction.beginRuntimePermissionRequest(request)
            },
            onPermissionResolved = { request, outcome ->
                toolTurnServices.requireCurrent().interaction.resolveRuntimePermissionRequest(request, outcome)
            },
            platformDispatch = { operation, arguments ->
                val reply = platformDriver.command("content", android.os.Bundle().apply {
                    putString("operation", operation); putString("arguments", arguments)
                })
                Json.decodeFromString<ToolExecutionResult>(reply.getString("result")!!)
            }
        )

        val elementInteraction = ElementInteractionService(
            refreshScreenData = ::refreshScreenData,
            searchElements = ::searchElements,
            performVerifiedTap = { toolName, arguments, label, x, y, _, precision ->
                this@ToolExecutor.performVerifiedTap(
                    toolName = toolName,
                    arguments = arguments,
                    label = label,
                    x = x,
                    y = y,
                    precision = precision
                )
            }
        )
        knowledgeTools.registerAll(::register, programTool)

        MetaToolRegistrar(::register).registerAll()
        register(ReportResultTool { section -> ruleConfigManager.readMarkdownSection("agent_core.md", section) })
        CompletionToolRegistrar(::register, ::completionForTurn,
            ruleConfigManager.readMarkdownSection("completion_recovery.md", "public_result"),
            ruleConfigManager.readMarkdownSection("completion_recovery.md", "status_parameter"),
            captureExperienceEnvironment = knowledgeTools::installedVersions,
            recordCompletion = { candidate, state -> toolTurnServices.requireCurrent().completion.record(candidate, state) }).registerAll()
        HarnessUtilityToolRegistrar(
            registerTool = ::register,
            stateProvider = ::utilityForTurn,
            requestHuman = { reason, instruction ->
                toolTurnServices.requireCurrent().interaction.requestManualAssistance(reason, instruction)
            },
            allowedHumanBoundaryTypes = ruleConfigManager
                .harnessPolicy().humanAssistance.allowedBoundaryTypes,
            recordCompletion = { candidate ->
                toolTurnServices.requireCurrent().completion.record(candidate, completionForTurn())
            }
        ).registerAll()
        UnifiedDeviceToolRegistrar(
            intentExtraContracts = Json.decodeFromString<List<com.droidance.core.agent.config.IntentExtraContract>>(
                ruleConfigManager.readMarkdownSection("device_tools.md", "intent_extra_contracts")).onEach { it.validate() },
            registerTool = ::register,
            deviceBackend = deviceBackend,
            readDevice = { ToolProtocolResult.success(deviceInfoReader.read()) },
            readApps = installedAppsManager::readApps,
            discoverCapabilities = capabilityDiscovery::read,
            readContent = contentQueryService::query,
            mutateContent = contentQueryService::mutate,
            readScreen = ::readBoundScreen,
            readCrop = ::readBoundCrop,
            openAndroid = appLaunchService::open,
            androidReadDescription = ruleConfigManager.readMarkdownSection("device_tools.md", "android_read_description") +
                "\n" + ruleConfigManager.readMarkdownSection("device_tools.md", "platform_methods"),
            androidActDescription = ruleConfigManager.readMarkdownSection("device_tools.md", "android_act_description") +
                "\n" + ruleConfigManager.readMarkdownSection("device_tools.md", "direct_route_selection") +
                "\n" + ruleConfigManager.readMarkdownSection("device_tools.md", "intent_argument_repair"),
            shellDescription = ruleConfigManager.readMarkdownSection("device_tools.md", "shell_description") +
                "\n" + ruleConfigManager.readMarkdownSection("device_tools.md", "shell_contract"),
            shellPolicy = Json.decodeFromString<com.droidance.core.agent.config.ShellExecutionPolicy>(
                ruleConfigManager.readMarkdownSection("device_tools.md", "shell_policy")),
            screenActDescription = ruleConfigManager.readMarkdownSection("device_tools.md", "screen_act_description"),
            textInputClearDescription = ruleConfigManager.readMarkdownSection("device_tools.md", "text_input_clear_parameter"),
            scrollDirectionDescription = ruleConfigManager.readMarkdownSection("observation_recovery.md", "scroll_direction"),
            batchDescription = ruleConfigManager.readMarkdownSection("completion_recovery.md", "batch_contract")
                .replace("{batch_policy}", kotlinx.serialization.json.Json.encodeToString(com.droidance.core.agent.config.ScreenActionBatchPolicy.serializer(), ruleConfigManager.harnessPolicy().screenActionBatch)),
            screenActions = object : ScreenActionService {
                override suspend fun click(arguments: String) = executeGroundedClick(arguments, touchInput,
                    observeAfter = coroutineContext[ScreenActionSequenceMember] == null && !hasFrameWait(arguments))
                override suspend fun findAndClick(arguments: String) =
                    elementInteraction.findAndClick(arguments)
                override suspend fun swipe(arguments: String) = touchInput.swipe(arguments,
                    observeAfter = coroutineContext[ScreenActionSequenceMember] == null && !hasFrameWait(arguments))
                override suspend fun drag(arguments: String) = executeRepeatedDrag(arguments, touchInput)
                override suspend fun path(arguments: String) = touchInput.path(arguments, drag = false,
                    observeAfter = coroutineContext[ScreenActionSequenceMember] == null && !hasFrameWait(arguments))
                override suspend fun scroll(arguments: String) = touchInput.scroll(arguments,
                    observeAfter = coroutineContext[ScreenActionSequenceMember] == null && !hasFrameWait(arguments))
                override suspend fun typeText(arguments: String) = textInput.typeText(arguments,
                    batchObservation = coroutineContext[ScreenActionSequenceMember]?.let {
                        captureLocalObservation(preferLastFrame = true)
                    })
                override suspend fun pressKey(arguments: String) = keyInput.pressKey(arguments)
                override suspend fun batch(arguments: String) = executeScreenActionBatch(arguments, touchInput, textInput, keyInput)
            }
        ).registerAll()
    }

    private fun hasFrameWait(arguments: String): Boolean =
        UnifiedDeviceToolProtocol.requestsFrameWait(DeviceToolNames.SCREEN_ACT, arguments)

    private data class GroundedClick(val arguments: String, val request: TapRequest, val x: Int?, val y: Int?)

    private suspend fun groundClick(arguments: String): GroundedClick? {
        val input = Json.parseToJsonElement(arguments).jsonObject
        val x = input["x"]?.jsonPrimitive?.intOrNull
        val y = input["y"]?.jsonPrimitive?.intOrNull
        val ref = input["coordinate_ref"]?.jsonPrimitive?.content
        val point = if (ref == null) x?.let { px -> y?.let { ModelCoordinateMapper.Point(px, it) } } else {
            if (input["target_id"] != null || x == null || y == null) return null
            val frame = observationSession.decisionObservation ?: return null
            val hash = frame.nativeFrameSha256 ?: frame.nativeImagePng?.let {
                java.security.MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) }
            } ?: return null
            val scope = toolTurnServices.requireCurrent().sessionContext
            val images = programEvents.eventsForTurn(scope.turnId).filter {
                it.threadId == scope.threadId && it.eventKind == com.droidance.core.agent.event.AgentEventKind.DETAIL_ATTACHMENT_ADDED
            }.map { Json.decodeFromString<com.droidance.core.agent.event.DetailAttachmentAddedEventPayload>(it.payloadJson) }
            RetainedCoordinateResolver.resolve(ref, x, y, frame.observationId, "sha256:$hash", images) ?: return null
        }
        val physical = point?.let { currentModelCoordinateMapper.toPhysical(it.x, it.y) }
        val mapped = if (ref == null) arguments else JsonObject(input.filterKeys { it != "coordinate_ref" } + mapOf(
            "x" to JsonPrimitive(checkNotNull(point).x), "y" to JsonPrimitive(point.y))).toString()
        return GroundedClick(mapped, TapRequest(x, y, ref, physical), point?.x, point?.y)
    }

    private suspend fun executeGroundedClick(arguments: String, touchInput: TouchInputService, observeAfter: Boolean): ToolExecutionResult {
        val click = groundClick(arguments) ?: return ToolProtocolResult.notExecuted("COORDINATE_REFERENCE_STALE", true)
        return withContext(click.request) { touchInput.click(click.arguments, observeAfter) }
    }

    private suspend fun readBoundScreen(): ToolExecutionResult {
        val state = measureTiming("observe_screen.bound_display") { observeScreenTool.execute() }
        val identifiedState = attachPendingObservationReference(state)
        pendingScreenStateText = identifiedState.outputText
        val latest = observeScreenTool.latestTargets()
        if (latest.isNotEmpty()) {
            observedTargets().cacheTargets(latest)
        } else {
            observedTargets().cacheFromScreenState(identifiedState.outputText)
        }
        return identifiedState
    }

    private suspend fun readBoundCrop(arguments: String): ToolExecutionResult {
        val input = Json.parseToJsonElement(arguments).jsonObject
        val scope = toolTurnServices.requireCurrent().sessionContext.let {
            com.droidance.core.agent.event.TurnEventScope(it.threadId, it.turnId)
        }
        val reference = input["source_ref"]?.jsonPrimitive?.content
        val events = programEvents.eventsForTurn(scope.turnId)
        val source = com.droidance.core.agent.RetainedReviewSources.resolveImage(events, scope, reference)
            ?: return ToolProtocolResult.notExecuted("CROP_SOURCE_UNAVAILABLE", true)
        val frame = observationSession.decisionObservation
        val currentSource = frame?.nativeFrameSha256?.let { hash ->
            val images = events.filter { it.eventKind == com.droidance.core.agent.event.AgentEventKind.DETAIL_ATTACHMENT_ADDED }
                .map { Json.decodeFromString<com.droidance.core.agent.event.DetailAttachmentAddedEventPayload>(it.payloadJson) }
            RetainedCoordinateResolver.resolve(source.observationId ?: source.contentRef, 500, 500,
                frame.observationId, "sha256:$hash", images) != null
        } == true
        val budget = taskState.currentFrameCropBudget
        if (currentSource) {
            val limit = Json.decodeFromString<com.droidance.core.agent.ObservationRecoveryPolicy>(
                ruleConfigManager.readMarkdownSection("observation_recovery.md", "policy")).maxCurrentFrameCrops
            if (!budget.acquire(checkNotNull(frame).observationId, limit)) {
                return ToolProtocolResult.notExecuted("CROP_CURRENT_FRAME_BUDGET_EXHAUSTED", true,
                    details = buildJsonObject {
                        put("source_observation_ref", frame.observationId); put("max_current_frame_crops", limit)
                        put("recovery_condition", "Read a fresh full screen before deriving another crop of the live viewport. Cropping retained pixels does not refresh loading or clipped content. Historical source inspection remains available.")
                    })
            }
        }
        val cropped = try {
            val bytes = programBlobs.read(source.contentRef, com.droidance.core.agent.HistoricalObservationImages.MAX_IMAGE_BYTES)
            observeScreenTool.executeCropZoom(arguments, bytes)
        } catch (error: Exception) {
            if (currentSource) budget.releaseFailed(checkNotNull(frame).observationId)
            throw error
        }
        val callId = coroutineContext[ToolInvocation]?.callId ?: error("Missing crop invocation")
        val id = checkNotNull(ToolObservationReference.forGroundedCall(
            ToolProtocolResult.success("Retained crop"), true, scope.turnId, callId).observationId)
        val retained = programBlobs.put(cropped.bytes)
        val attachment = com.droidance.core.agent.event.DetailAttachmentAddedEventPayload(
            com.droidance.core.agent.event.AGENT_READ_EVENT_CONTRACT_VERSION, id,
            com.droidance.core.agent.event.DetailAttachmentRole.RESULT, retained.contentRef,
            retained.contentHash, retained.sizeBytes, "image/png", cropped.width, cropped.height, id,
            source.observationId ?: source.contentRef, source.contentRef, cropped.sourceRegion)
        return ToolProtocolResult.success(buildJsonObject {
            put("source_ref", source.observationId ?: source.contentRef)
            put("source_content_ref", source.contentRef); put("crop_ref", id)
            put("source_region_px", buildJsonObject {
                put("x", cropped.sourceRegion.x); put("y", cropped.sourceRegion.y)
                put("width", cropped.sourceRegion.width); put("height", cropped.sourceRegion.height)
                put("source_width", cropped.sourceRegion.sourceWidth); put("source_height", cropped.sourceRegion.sourceHeight)
            })
            put("effect", "retained_image_transform")
            put("current_target_binding", false)
            put("click_coordinate_ref", id)
            put("click_coordinate_space", "local_normalized_0_1000; valid only while original remains current; runtime checks freshness")
        }.toString()).copy(historicalAttachment = attachment, observationId = id, sourceRef = id)
    }

    private fun attachPendingObservationReference(result: ToolExecutionResult): ToolExecutionResult {
        val execution = executionContextStore.current() ?: return result
        val observationId = observationEnvelopeStore.peek(
            executionId = execution.executionId,
            backendSessionId = execution.backendSessionId,
            displayId = execution.displayId
        )?.observationId ?: return result
        return ToolObservationReference.attachToSuccessfulResult(result, observationId)
    }

    /**
     * A visit's BACK was delivered. If the settled screen still matches the target the visit
     * captured, such as a live room that needs a second BACK, the return failed and the batch
     * stops with its fresh frame. The origin may legitimately move, for example under a sticky
     * header, so a difference from the origin alone is not a failed return.
     */
    private suspend fun confirmVisitReturn(
        execution: ExecutionContext,
        pressed: ToolExecutionResult,
        actionIndex: Int,
        batchMetrics: MutableMap<String, Long>,
        visitTarget: ObservationPixelReference?
    ): ToolExecutionResult {
        val target = visitTarget ?: return pressed
        val settled = observationSession.awaitRenderingSettled("action_capture_policy")
        batchMetrics["batch_visit_return_settle_ms"] = (batchMetrics["batch_visit_return_settle_ms"] ?: 0L) + settled.elapsedMs
        val check = observationSession.checkPreActionObservation(execution, usePendingTarget = false,
            scrollCheckpoint = ScreenBatchScrollCheckpoint(target, SystemClock.elapsedRealtime()))
        val targetPpm = check.metrics["pre_action_observation_changed_fraction_ppm"]
            ?.takeIf { check.metrics["pre_action_observation_available"] == 1L }
        batchMetrics["action_${actionIndex}_visit_return_target_changed_fraction_ppm"] = targetPpm ?: -1L
        val limitPpm = (ruleConfigManager.harnessPolicy().runtimeThresholds.visitReturnChangeFraction * 1_000_000).toLong()
        if (targetPpm == null || targetPpm > limitPpm) return pressed
        return ToolProtocolResult.failed(
            code = "VISIT_RETURN_NOT_CONFIRMED",
            retryable = true,
            details = buildJsonObject {
                put("back_delivered", JsonPrimitive(true))
                put("target_changed_fraction_ppm", JsonPrimitive(targetPpm))
                put("limit_ppm", JsonPrimitive(limitPpm))
                put("recovery_condition", JsonPrimitive("BACK was sent but the screen still shows the target this visit opened. Inspect the fresh frame and return to the list before visiting another target."))
            }
        ).copy(actionEvidence = pressed.actionEvidence)
    }

    /**
     * Waits for the input window a tap opened, within the configured focus budget. While a new
     * input page opens, its window changes and is not yet focused; those reports are retried after
     * rendering settles. Only a lost backend, session or display ends the wait early.
     */
    private suspend fun awaitTappedInputFocus(batchMetrics: MutableMap<String, Long>): com.droidance.core.device.agent.device.InputFocusReadiness {
        val budget = ruleConfigManager.harnessPolicy().runtimeThresholds.inputFocusProbeTimeoutMs
        val started = SystemClock.elapsedRealtime()
        var readiness = deviceBackend.awaitInputFocus(budget)
        var attempts = 1
        while (readiness.target == null && readiness.diagnosticCode !in FINAL_FOCUS_DIAGNOSTICS) {
            if (SystemClock.elapsedRealtime() - started >= budget) break
            observationSession.awaitRenderingSettled("input_settle_policy")
            val remaining = budget - (SystemClock.elapsedRealtime() - started)
            if (remaining <= 0) break
            readiness = deviceBackend.awaitInputFocus(remaining)
            attempts++
        }
        batchMetrics["batch_focus_probe_attempts"] = (batchMetrics["batch_focus_probe_attempts"] ?: 0L) + attempts
        return readiness.copy(elapsedMs = SystemClock.elapsedRealtime() - started)
    }

    private suspend fun executeRepeatedDrag(arguments: String, touchInput: TouchInputService,
                                          region: ModelCoordinateMapper.Bounds? = null): ToolExecutionResult {
        val action = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return ToolProtocolResult.invalidArguments()
        val repeat = if ("repeat" in action) (action["repeat"] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.intOrNull ?: return ToolProtocolResult.invalidArgument("repeat", "expected_integer")
            else 1
        val maxRepeat = ruleConfigManager.harnessPolicy().screenActionBatch.maxDragRepeat
        if (repeat !in 1..maxRepeat) return ToolProtocolResult.invalidArgument("repeat", "expected_1_to_$maxRepeat")
        val execution = executionContextStore.requireCurrent()
        val geometry = displaySnapshotResolver.capture(execution.displayId)
        val progress = coroutineContext[ScreenActionBatchProgress]
        val primitive = JsonObject(action.filterKeys { it != "repeat" && it != "op" }).toString()
        val comparison = JsonObject(mapOf("actions" to JsonArray(listOf(
            JsonObject(action + ("op" to JsonPrimitive("drag"))))))).toString()
        stalledGesture = null
        return DragRepeatExecutor(
            capture = {
                var readiness: VisualFrameSampler.Result? = null
                val observation = observationSession.captureBoundedObservation("drag_capture_policy",
                    onReadiness = { readiness = it }, actionEndedAtElapsedMs = progress?.lastActionEndedAtElapsedMs)
                val frame = deviceBackend.lastScreenFrame()
                val component = observation.semanticState?.foregroundActivityOrWindow
                val signature = frame?.let { screenChangeSignature(it.pngBytes) }
                if (executionContextStore.current() != execution ||
                    displaySnapshotResolver.capture(execution.displayId) != geometry ||
                    observation.backendSessionId != execution.backendSessionId || observation.displayId != execution.displayId ||
                    frame?.displayId != execution.displayId || frame.contentHash != observation.screenHash ||
                    component.isNullOrBlank() || signature == null) null
                else {
                    progress?.finalObservation = observation
                    progress?.finalObservationWaitSatisfied = true
                    DragRepeatExecutor.Frame(signature,
                        "${observation.backendSessionId}:${observation.displayId}:$component",
                        readiness?.stable == true)
                }
            },
            drag = {
                // The baseline capture can take time. Recheck a declared control immediately before
                // dispatch as well, without adopting that runtime frame as new model grounding.
                val check = region?.let { observationSession.checkPreActionObservation(
                    execution, usePendingTarget = false, targetRegion = it) }
                (check?.rejection ?: touchInput.path(primitive, drag = true, observeAfter = false)).let {
                    it.copy(metrics = it.metrics + check?.metrics.orEmpty())
                }.also {
                    progress?.lastActionEndedAtElapsedMs = SystemClock.elapsedRealtime()
                }
            },
            onProgress = { progress?.updateAttemptOutput(it) }
        ).execute(comparison, repeat)
    }

    private suspend fun executeScreenActionBatch(
        arguments: String,
        touchInput: TouchInputService,
        textInput: TextInputService,
        keyInput: KeyInputService
    ): ToolExecutionResult {
        val execution = executionContextStore.requireCurrent()
        val geometry = displaySnapshotResolver.capture(execution.displayId)
        val policy = ruleConfigManager.harnessPolicy().screenActionBatch
        val plan = ScreenActionBatchPlanner.plan(arguments, policy)
        val collecting = (plan as? ScreenActionBatchPlan.Ready)?.actions
            ?.any { it.operation == ScreenBatchOperation.SCREENSHOT } == true
        val captures = coroutineContext[ScreenBatchCaptures]
        if (collecting && (captures == null || captures.callId != coroutineContext[ToolInvocation]?.callId ||
                !capabilityRegistry.current().visionProviderAvailable)) {
            return ToolProtocolResult.notExecuted("BATCH_CAPTURE_UNAVAILABLE", true)
        }
        var grounding: LocalActionEvidence.ObservationLite? = taskState.screenSequenceBefore
        val scrollGrounding = ScreenBatchScrollGrounding(plan, grounding)
        var focusTarget: String? = null
        var focusReadiness: com.droidance.core.device.agent.device.InputFocusReadiness? = null
        var previousOperation: ScreenBatchOperation? = null
        var previousAction: MappedScreenAction? = null
        var visitTarget: ObservationPixelReference? = null
        var focusActionIndex: Int? = null
        val batchMetrics = mutableMapOf<String, Long>()
        var actionIndex = 0
        var deliveredCount = 0L
        val batchStarted = SystemClock.elapsedRealtime()
        val progress = coroutineContext[ScreenActionBatchProgress]
        progress?.lastActionEndedAtElapsedMs = batchStarted
        val minimumFrameWaitMs = Json.parseToJsonElement(arguments).jsonObject["wait_for_frame_ms"]?.jsonPrimitive?.longOrNull ?: 0L
        reusablePostActionFrame = null
        suspend fun refreshScrollGrounding(): ToolExecutionResult? {
            val started = SystemClock.elapsedRealtime()
            return try {
                // A page that keeps changing, such as a countdown or carousel, still grounds the next
                // scroll: its own pre-scroll check rejects a start area that actually moved.
                val observation = observationSession.captureBoundedObservation(
                    "collection_capture_policy", minimumFrameWaitMs,
                    actionEndedAtElapsedMs = progress?.lastActionEndedAtElapsedMs)
                val frame = reusablePostActionFrame
                when {
                    executionContextStore.current() != execution ||
                        displaySnapshotResolver.capture(execution.displayId) != geometry ->
                        ToolProtocolResult.notExecuted("BATCH_EXECUTION_SURFACE_CHANGED", true)
                    frame?.pixelReference == null -> ToolProtocolResult.failed("BATCH_CAPTURE_UNAVAILABLE", true)
                    else -> {
                        scrollGrounding.captured(observation, frame.pixelReference, SystemClock.elapsedRealtime())
                        if (scrollGrounding.checkpoint == null)
                            ToolProtocolResult.notExecuted("BATCH_EXECUTION_SURFACE_CHANGED", true)
                        else {
                            grounding = observation
                            batchMetrics["batch_scroll_refreshes"] = (batchMetrics["batch_scroll_refreshes"] ?: 0L) + 1L
                            null
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ToolProtocolResult.failed("BATCH_CAPTURE_FAILED", true, error = error)
            } finally {
                batchMetrics["batch_scroll_refresh_ms"] = (batchMetrics["batch_scroll_refresh_ms"] ?: 0L) +
                    (SystemClock.elapsedRealtime() - started)
            }
        }
        val result = ScreenActionBatchExecutor(
            policy = policy,
            executeAction = { operation, payload, mappedAction ->
                actionIndex++
                scrollGrounding.before(mappedAction.actionIndex)
                val actionStarted = SystemClock.elapsedRealtime()
                val capturePreflight = if (collecting && scrollGrounding.isCollection && operation == ScreenBatchOperation.SCROLL)
                    captures?.beforeCollectionScroll() else null
                val actionResult = try {
                    // Check the frozen execution surface without taking another full screenshot.
                    // Coordinates inside a batch remain the Agent's grounded plan; changed geometry
                    // or a replaced backend must never silently execute the remaining suffix.
                    if (collecting && captures?.hasCapacity() != true) {
                        ToolProtocolResult.notExecuted("BATCH_CAPTURE_CAPACITY", retryable = true)
                    } else if (capturePreflight != null) {
                        capturePreflight
                    } else if (executionContextStore.current() != execution ||
                        displaySnapshotResolver.capture(execution.displayId) != geometry) {
                        ToolProtocolResult.notExecuted("BATCH_EXECUTION_SURFACE_CHANGED", retryable = true)
                    } else {
                        // Refresh only the same preplanned scrolling viewport after delivered
                        // scrolling. New targets and mixed action groups still use the model lease.
                        val scrollRefreshFailure = if (operation == ScreenBatchOperation.SCROLL && scrollGrounding.needsScrollRefresh)
                            refreshScrollGrounding() else null
                        val usesFocus = operation == ScreenBatchOperation.TYPE_TEXT && mappedAction.focusFrom != null
                        // A key (BACK, ENTER) or gesture earlier in this batch may leave the page still
                        // re-rendering, such as a list redrawing its images after BACK. Let it settle
                        // before comparing the click target with the decision frame.
                        if (operation == ScreenBatchOperation.CLICK && previousOperation in SETTLE_BEFORE_CLICK_AFTER) {
                            val settled = observationSession.awaitRenderingSettled("action_capture_policy")
                            batchMetrics["batch_pre_click_settle_ms"] = (batchMetrics["batch_pre_click_settle_ms"] ?: 0L) + settled.elapsedMs
                        }
                        val click = if (operation == ScreenBatchOperation.CLICK) groundClick(payload) else null
                        val coordinateFailure = if (operation == ScreenBatchOperation.CLICK && click == null)
                            ToolProtocolResult.notExecuted("COORDINATE_REFERENCE_STALE", true) else null
                        suspend fun checkTarget() = if (coordinateFailure != null || usesFocus || operation in setOf(ScreenBatchOperation.PRESS_KEY, ScreenBatchOperation.SCROLL, ScreenBatchOperation.SCREENSHOT)) null else observationSession.checkPreActionObservation(
                            execution = execution,
                            targetId = mappedAction.targetId,
                            targetX = click?.x ?: mappedAction.targetX,
                            targetY = click?.y ?: mappedAction.targetY,
                            usePendingTarget = false,
                            surfaceGesture = operation in VIEWPORT_GESTURES,
                            targetRegion = mappedAction.gestureRegion
                        )
                        var observationCheck = checkTarget()
                        // A click delivered just before in this batch can leave its press feedback on or
                        // beside this target, such as neighbouring keypad keys. Let rendering settle and
                        // check once more; a page that really changed still rejects the click.
                        if (observationCheck?.rejection != null && mappedAction.followsClick(previousAction)) {
                            val settled = observationSession.awaitRenderingSettled("action_capture_policy")
                            batchMetrics["batch_pre_click_recheck_settle_ms"] =
                                (batchMetrics["batch_pre_click_recheck_settle_ms"] ?: 0L) + settled.elapsedMs
                            batchMetrics["batch_pre_click_rechecks"] = (batchMetrics["batch_pre_click_rechecks"] ?: 0L) + 1L
                            observationCheck = checkTarget()
                        }
                        // A wait earlier in this batch does not re-ground the planned coordinates.
                        val waitedBefore = (plan as? ScreenActionBatchPlan.Ready)?.actions
                            ?.getOrNull(mappedAction.actionIndex - 2)?.operation == ScreenBatchOperation.WAIT
                        if (waitedBefore && observationCheck?.rejection != null) {
                            observationCheck = observationCheck.copy(rejection = observationCheck.rejection?.let { rejection ->
                                rejection.withDetail("after_in_batch_wait", "The target area changed during the wait " +
                                    "in this batch; the tap was grounded on the frame before it. Act on the fresh frame " +
                                    "instead of waiting inside the batch.")
                            })
                        }
                        observationCheck?.metrics?.forEach { (key, value) ->
                            batchMetrics["action_${actionIndex}_$key"] = value
                        }
                        val before = grounding ?: captureLocalObservation(preferLastFrame = true).also { grounding = it }
                        scrollRefreshFailure ?: coordinateFailure ?: observationCheck?.rejection ?: when (operation) {
                            ScreenBatchOperation.CLICK -> withContext(checkNotNull(click).request) {
                                touchInput.click(click.arguments, observeAfter = false)
                            }
                            ScreenBatchOperation.SWIPE -> touchInput.swipe(payload, observeAfter = false)
                            ScreenBatchOperation.DRAG -> executeRepeatedDrag(payload, touchInput, mappedAction.gestureRegion)
                            ScreenBatchOperation.PATH -> touchInput.path(payload, drag = false, observeAfter = false)
                            ScreenBatchOperation.TYPE_TEXT -> if (usesFocus &&
                                (focusTarget == null || focusActionIndex != mappedAction.focusFrom)) {
                                ToolProtocolResult.notExecuted("BATCH_INPUT_FOCUS_UNAVAILABLE", true, details = buildJsonObject {
                                    focusReadiness?.let { readiness ->
                                        put("focus_diagnostic", JsonPrimitive(readiness.diagnosticCode))
                                        put("focus_wait_ms", JsonPrimitive(readiness.elapsedMs))
                                        readiness.queryDiagnosticCode?.let { put("focus_query_diagnostic", JsonPrimitive(it)) }
                                        readiness.focusedWindowName?.let { put("focused_window", JsonPrimitive(it)) }
                                    }
                                    put("text_injected", JsonPrimitive(false))
                                })
                            } else textInput.typeText(payload, batchObservation = before,
                                expectedFocusTarget = focusTarget.takeIf { usesFocus })
                            ScreenBatchOperation.PRESS_KEY -> keyInput.pressKey(payload).let { pressed ->
                                if (mappedAction.returnsFromVisit && pressed.executionStatus == ToolExecutionStatus.SUCCEEDED)
                                    confirmVisitReturn(execution, pressed, actionIndex, batchMetrics, visitTarget) else pressed
                            }
                            ScreenBatchOperation.SCROLL -> scrollGrounding.checkpoint?.let { checkpoint ->
                                withContext(checkpoint) { touchInput.scroll(payload, observeAfter = false) }
                            } ?: touchInput.scroll(payload, observeAfter = false)
                            ScreenBatchOperation.SCREENSHOT -> try {
                                var collectionReadiness: VisualFrameSampler.Result? = null
                                val observation = if (scrollGrounding.isCollection) observationSession.captureBoundedObservation(
                                    "collection_capture_policy", minimumFrameWaitMs, onReadiness = { collectionReadiness = it },
                                    actionEndedAtElapsedMs = progress?.lastActionEndedAtElapsedMs)
                                else observationSession.captureSettledObservation(minimumFrameWaitMs,
                                    actionEndedAtElapsedMs = progress?.lastActionEndedAtElapsedMs)
                                val frame = reusablePostActionFrame
                                val prepared = frame?.let { observationSession.prepareVisionFrame(it) }
                                if (executionContextStore.current() != execution ||
                                    displaySnapshotResolver.capture(execution.displayId) != geometry) {
                                    reusablePostActionFrame = null
                                    ToolProtocolResult.notExecuted("BATCH_EXECUTION_SURFACE_CHANGED", true)
                                } else if (frame == null || prepared == null) {
                                    ToolProtocolResult.failed("BATCH_CAPTURE_UNAVAILABLE", true)
                                } else {
                                    checkNotNull(captures).save(
                                        mappedAction.actionIndex, frame.capturedAtMs, execution.executionId,
                                        execution.backendSessionId, execution.displayId, geometry.rotationDegrees,
                                        frame.pngBytes, prepared.physicalWidth, prepared.physicalHeight,
                                        modelImageBytes = android.util.Base64.decode(prepared.base64Jpeg, android.util.Base64.DEFAULT)
                                    ).let { captured ->
                                        if (captured.isVerifiedSuccess) {
                                            if (mappedAction.capturesVisitTarget) visitTarget = frame.pixelReference
                                            scrollGrounding.captured(observation, frame.pixelReference, SystemClock.elapsedRealtime())
                                            coroutineContext[ScreenActionBatchProgress]?.finalObservation = observation
                                            progress?.finalObservationWaitSatisfied = true
                                        }
                                        val readiness = collectionReadiness
                                        if (captured.isVerifiedSuccess && scrollGrounding.isCollection && readiness?.collectionSettled() != true) {
                                            val state = if (readiness?.localizedMotion == true) "LOCALIZED_MOTION" else "UNSETTLED"
                                            // The frame is retained evidence; parts that kept moving are noted, not failed.
                                            captured.copy(
                                                outputText = "[COLLECTION_FRAME_UNSETTLED] rendering_state=$state; frame_saved=true; " +
                                                    "parts of the page kept changing while it was captured, for example a timer, " +
                                                    "carousel or video; the collection continued\n" + captured.outputText,
                                                metrics = captured.metrics + mapOf(
                                                    "collection_frame_saved" to 1L,
                                                    "collection_frame_localized_motion" to if (readiness?.localizedMotion == true) 1L else 0L,
                                                    "collection_frame_static" to 0L))
                                        } else captured
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                ToolProtocolResult.failed("BATCH_CAPTURE_FAILED", true, error = error)
                            }
                            ScreenBatchOperation.WAIT -> error("Batch executor owns $operation")
                        }
                    }
                } finally {
                    batchMetrics["action_${actionIndex}_duration_ms"] = SystemClock.elapsedRealtime() - actionStarted
                }
                actionResult.metrics.forEach { (key, value) -> batchMetrics["action_${actionIndex}_$key"] = value }
                previousOperation = operation
                previousAction = mappedAction
                if (operation != ScreenBatchOperation.SCREENSHOT &&
                    actionResult.executionStatus != ToolExecutionStatus.NOT_EXECUTED) {
                    progress?.lastActionEndedAtElapsedMs = SystemClock.elapsedRealtime()
                    if (operation != ScreenBatchOperation.DRAG) {
                        progress?.finalObservation = null
                        progress?.finalObservationWaitSatisfied = false
                    }
                }
                focusTarget = null
                focusActionIndex = null
                if (operation == ScreenBatchOperation.CLICK && actionResult.executionStatus == ToolExecutionStatus.SUCCEEDED) {
                    val next = (plan as? ScreenActionBatchPlan.Ready)?.actions?.getOrNull(mappedAction.actionIndex)
                    if (next?.focusFrom == mappedAction.actionIndex) {
                        val readiness = awaitTappedInputFocus(batchMetrics)
                        // A tap may open a new input page that fills its editor after focus arrives;
                        // typing before rendering settles can race that initialization.
                        if (readiness.target != null) {
                            val settled = observationSession.awaitRenderingSettled("input_settle_policy")
                            batchMetrics["batch_focus_settle_ms"] = (batchMetrics["batch_focus_settle_ms"] ?: 0L) + settled.elapsedMs
                        }
                        focusTarget = readiness.target
                        focusReadiness = readiness
                        focusActionIndex = mappedAction.actionIndex
                        batchMetrics["batch_focus_binding_wait_ms"] = (batchMetrics["batch_focus_binding_wait_ms"] ?: 0L) + readiness.elapsedMs
                    }
                }
                if (mappedAction.focusFrom != null && actionResult.executionStatus == ToolExecutionStatus.SUCCEEDED)
                    batchMetrics["batch_focus_continuations"] = (batchMetrics["batch_focus_continuations"] ?: 0L) + 1L
                if (operation == ScreenBatchOperation.SCROLL) scrollGrounding.scrollSettled(actionResult)
                if (actionResult.executionStatus == ToolExecutionStatus.SUCCEEDED) deliveredCount++
                actionResult
            }
        ).execute(arguments)
        return result.copy(metrics = result.metrics + batchMetrics + mapOf(
            "batch_primitive_attempts" to actionIndex.toLong(),
            "batch_primitive_succeeded_receipts" to deliveredCount,
            "batch_execution_ms" to (SystemClock.elapsedRealtime() - batchStarted)
        ))
    }

    override fun isCompletionRequested(): Boolean = completionState.result != null

    override fun completionSummary(): String = completionState.result?.summary.orEmpty()

    override fun completionStatus(): String = completionState.result?.status.orEmpty()

    fun completionResult(): CompletionResult? = completionState.result


    override fun clearCompletionRequest() {
        completionState.reset()
    }

    override fun resetTextInputTransactions() {
        textInputIdempotencyGuard.reset()
    }

    override fun currentResultCard(): ResultCard? = harnessUtilityState.resultCard

    fun resetTaskState() {
        completionState.reset()
        harnessUtilityState.reset()
        pendingExecutionFallback = null
        textInputIdempotencyGuard.reset()
    }

    override suspend fun stagePreparedObservation(frameBytes: ByteArray): ToolExecutionResult {
        require(frameBytes.isNotEmpty()) { "Prepared observation frame cannot be empty" }
        val execution = executionContextStore.requireCurrent()
        val preparedFrame = DeviceScreenFrame(
            pngBytes = frameBytes,
            source = "app_takeover_preflight",
            displayId = execution.displayId.takeUnless { it == 0 }
        )
        return observeScreenTool.execute(preparedFrame)
    }

    private fun recordExecutionFallback(path: String?, toolName: String, result: ToolExecutionResult, success: Boolean) {
        if (ToolResultStatus.isNotExecuted(result)) return
        if (path == null) return
        if (!success && path in DIRECT_EXECUTION_PATHS) {
            pendingExecutionFallback = PendingExecutionFallback(
                sourcePath = path,
                sourceTool = toolName,
                reason = result.outputText.lineSequence().firstOrNull().orEmpty().take(240)
            )
            return
        }
        if (!success) return
        val pending = pendingExecutionFallback ?: return
        if (path == pending.sourcePath) {
            pendingExecutionFallback = null
            return
        }
        if (path !in FALLBACK_EXECUTION_PATHS) return
        pendingExecutionFallback = null
    }

    private data class PendingExecutionFallback(
        val sourcePath: String,
        val sourceTool: String,
        val reason: String
    )

    // ====== 公共状态 ======

    /** Read-only compatibility views. Consumers must use consumePendingObservation() atomically. */
    override val pendingScreenshotBase64: String?
        get() = currentPendingObservation()?.imageBase64

    private val currentModelCoordinateMapper: ModelCoordinateMapper
        get() = observationSession.currentModelCoordinateMapper

    /** observe_screen 工具执行后存储的结构化屏幕状态，由 TaskCoordinator 优先消费。 */
    override var pendingScreenStateText: String?
        get() = taskState.observations.pendingScreenStateText
        set(value) { taskState.observations.pendingScreenStateText = value }

    private fun extractTargetId(argumentsJson: String): String? = runCatching {
        Json.parseToJsonElement(argumentsJson).jsonObject["target_id"]
            ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            ?.trim()?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun extractTargetCoordinates(argumentsJson: String): Pair<Int?, Int?> = runCatching {
        val root = Json.parseToJsonElement(argumentsJson).jsonObject
        val swipe = root["op"]?.jsonPrimitive?.content == "swipe"
        val x = (root[if (swipe) "startX" else "x"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        val y = (root[if (swipe) "startY" else "y"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        x to y
    }.getOrElse { null to null }

    /** 后台自动截图 Job（异步执行，不阻塞当前轮工具返回） */
    private var autoCaptureJob: Job?
        get() = taskState.observations.autoCaptureJob
        set(value) { taskState.observations.autoCaptureJob = value }

    private var reusablePostActionFrame: DeviceScreenFrame?
        get() = taskState.observations.reusablePostActionFrame
        set(value) { taskState.observations.reusablePostActionFrame = value }

    private companion object {
        val DIRECT_EXECUTION_PATHS = setOf("android_api", "deeplink", "native_intent", "native_launch")
        val FALLBACK_EXECUTION_PATHS = DIRECT_EXECUTION_PATHS + setOf("shell", "gui")
    }

    fun clearPerceptionState() {
        if (toolTurnServices.currentOrNull() == null) return
        observationSession.clear()
        synchronized(actionDeliveryByToolCall) { actionDeliveryByToolCall.clear() }
        resetTaskState()
        lastDumpElements.clear()
    }

    /** 快速屏幕哈希，用于动作前后对比 UI 是否发生变化。 */
    private suspend fun quickScreenHash(): Long? {
        return localActionVerifier.quickScreenHash()
    }

    /** 存储最近一次读屏解析出的可交互元素，供统一 find_and_click 与文本目标定位使用。 */
    private var lastDumpElements: MutableList<DumpElement>
        get() = taskState.lastDumpElements
        set(value) { taskState.lastDumpElements = value }

    private suspend fun refreshScreenData(): String? {
        return null
    }

    private suspend fun performVerifiedTap(
        toolName: String,
        arguments: String,
        label: String,
        x: Int,
        y: Int,
        precision: TapPrecision = TapPrecision.NORMAL
    ): ToolExecutionResult {
        val before = captureLocalObservation(preferLastFrame = true)
        val primary = ObservationTiming.measure("tap_delivery_ms") { executeTapByPrecision(x, y, precision) }
        recordExecutedActionResult(primary)
        val after = observationSession.captureSettledObservation()
        val validation = localActionVerifier.validate(toolName, arguments, before, after)
        if (!primary.success) {
            return appendLocalValidation(ToolProtocolResult.failed(primary.diagnosticCode ?: "TAP_FAILED", false,
                details = buildJsonObject { put("message", JsonPrimitive(primary.message)) }), validation)
        }
        return appendLocalValidation(ToolProtocolResult.accepted("Tap delivered: $label"), validation)
    }

    /** Rendering readiness uses small samples; only the final full frame becomes evidence. */

    private suspend fun verifyTextDelivery(
        text: String,
        observation: LocalActionEvidence.ObservationLite,
        focusPoint: ModelCoordinateMapper.Point?,
        clear: Boolean,
        targetFocusAttested: Boolean
    ): Boolean {
        return false
    }

    private fun inputFocusProbeTimeoutMs(): Long =
        ruleConfigManager.harnessPolicy().runtimeThresholds.inputFocusProbeTimeoutMs

    private suspend fun executeTapByPrecision(
        x: Int,
        y: Int,
        precision: TapPrecision
    ): DeviceInputResult = deviceBackend.tap(x, y, precision)


    /** 在 lastDumpElements 中按当前屏幕字面文本搜索元素 */
    private fun searchElements(query: String): List<DumpElement> {
        return lastDumpElements.mapNotNull { it.targetForText(query) }
    }

}

/**
 * Collection pages are read, not tapped: motion confined to the policy's localized fraction
 * (a carousel or video tile) leaves the scrolled content readable, so the batch continues.
 */
private fun com.droidance.core.device.tools.VisualFrameSampler.Result.collectionSettled(): Boolean =
    stable || localizedMotion

/** Adds one fact to a protocol receipt's details without changing its code or status. */
private fun ToolExecutionResult.withDetail(key: String, value: String): ToolExecutionResult {
    val root = runCatching { Json.parseToJsonElement(outputText).jsonObject }.getOrNull() ?: return this
    val details = (root["details"] as? kotlinx.serialization.json.JsonObject).orEmpty() + (key to JsonPrimitive(value))
    return copy(outputText = kotlinx.serialization.json.JsonObject(root + ("details" to kotlinx.serialization.json.JsonObject(details))).toString())
}

/** Focus reports after which waiting longer cannot produce the tapped input window. */
private val FINAL_FOCUS_DIAGNOSTICS = setOf("INPUT_BACKEND_UNAVAILABLE", "INPUT_BACKEND_SESSION_CHANGED",
    "INPUT_DISPLAY_UNAVAILABLE", "INPUT_FOCUS_CANCELED", "INPUT_FOCUS_INVALID_TOKEN")

/** Gestures that move the viewport rather than press a control. */
private val VIEWPORT_GESTURES = setOf(ScreenBatchOperation.SWIPE, ScreenBatchOperation.DRAG, ScreenBatchOperation.PATH)

/** Batch operations after which a following click waits for rendering to settle. */
private val SETTLE_BEFORE_CLICK_AFTER = setOf(ScreenBatchOperation.PRESS_KEY, ScreenBatchOperation.SCROLL,
    ScreenBatchOperation.SWIPE, ScreenBatchOperation.DRAG, ScreenBatchOperation.PATH)
