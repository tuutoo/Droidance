// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import kotlinx.serialization.encodeToString

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.tools.DeviceToolNames
import com.droidance.core.agent.workspace.TaskExecutionProgress
import com.droidance.core.agent.workspace.WorkspaceBrowsingProgress

import com.droidance.core.agent.harness.RoundFollowUp
import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.agent.harness.PendingDetailAttachment
import com.droidance.core.agent.harness.materializeDetailAttachments
import com.droidance.core.agent.attempt.AttemptDiagnosticCode
import com.droidance.core.agent.harness.ToolArgumentSchemaDiagnosticCode
import com.droidance.core.agent.harness.ModelToolCallArgumentsNormalizer
import com.droidance.core.agent.context.DurableContextProjector
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolLifecycleEventScope
import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import com.droidance.core.agent.event.DetailAttachmentRole
import com.droidance.core.agent.event.ResultImageRole
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.agent.tools.ToolCatalog
import com.droidance.core.agent.tools.OperatorToolProfile
import com.droidance.core.agent.tools.ToolObservationReference
import com.droidance.core.agent.tools.screenActionBatchCancellationReceiptOrNull
import com.droidance.core.agent.tools.progressMetrics
import com.droidance.core.agent.tools.toJsonDetails
import com.droidance.core.agent.tools.ToolProtocolResult
import com.droidance.core.agent.tools.UnifiedDeviceToolProtocol
import com.droidance.core.agent.tools.ExecutionScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

internal suspend fun TaskTurnSession.runToolRound(modelRound: ModelRoundOutput): TurnResult? {
    val repairPurpose = RepairPurpose.NONE

    val toolCallsMap = modelRound.toolCalls.toMutableMap()
    var fullContent = modelRound.content
    val titleMetadata = ConversationTitleProtocol.extract(fullContent)
    fullContent = titleMetadata.body
    val fullReasoning = modelRound.reasoning
    val pendingScreenshot = modelRound.pendingScreenshot
    val sourceObservationId = modelRound.sourceObservationId
    val pendingFrameSize = modelRound.pendingFrameSize
    val pendingPhysicalFrameSize = modelRound.pendingPhysicalFrameSize
    val canAttachScreenshot = modelRound.canAttachScreenshot
    val textObservationForRound = modelRound.textObservation
    val detailId = modelRound.detailId
    val reusePostActionObservationThisRound = modelRound.reusePostActionObservation
    val durableRound = modelRound.durableRound
    val hasCurrentScreenContext = canAttachScreenshot ||
        reusePostActionObservationThisRound ||
        !textObservationForRound.isNullOrBlank()
    val presentedToolNames = modelRound.presentedToolNames
    val mutationsRestricted = modelRound.mutationsRestricted
    val currentEvidenceStartedNs = System.nanoTime()
    val decisionFrameBytes = pendingScreenshot
        ?.takeIf { canAttachScreenshot }
        ?.let(::decodeModelFrame)

    val deadlineMetadata = TaskDeadlineProtocol.extract(toolCallsMap.values.map {
        ToolCall(id = it.id, name = it.name, arguments = it.arguments)
    })
    deadlineMetadata.bound?.let { onTaskDeadline(it) }
    checkTaskDeadline()
    val taskNaming = TaskTitleProtocol.extract(deadlineMetadata.calls)
    val conversationNaming = ConversationTitleProtocol.extractToolCalls(taskNaming.toolCalls)
    val conversationTitle = if (toolCallsMap.isEmpty()) titleMetadata.title else conversationNaming.title
    val rawParsedToolCalls = conversationNaming.toolCalls
    if (hasCurrentScreenContext) {
        evidenceRecorder.recordModelObservation(
            rawText = fullContent.ifBlank { fullReasoning },
            screenshotRef = detailId
        )
    }

    fun modelProtocolFailure(
        summary: String,
        output: String = fullContent,
        failureReason: TurnFailureReason = TurnFailureReason.MODEL_PROTOCOL_ERROR
    ): TurnResult {
        return TurnResult(
            hasToolCalls = totalToolCalls > 0,
            toolNames = executedToolNames.toList(),
            summary = summary,
            evidenceText = evidenceLedger.buildOperatorEvidenceText(),
        operatorOutput = output,
        rounds = rounds,
        failureReason = failureReason,
        takeoverManaged = takeoverManaged,
        terminal = TurnTerminal.SYSTEM_ERROR
        )
    }

    val normalizedToolCalls = rawParsedToolCalls.map { call ->
        val registered = operatorTooling.registeredTools.firstOrNull {
            it.contract.name == call.name
        }
        // Device actions are never structurally repaired; turn-local tools (reads, records,
        // completion) only carry text whose repaired shape the strict parser still checks.
        val turnLocal = registered?.contract?.binding == ToolBinding.TURN_LOCAL
        ModelToolCallArgumentsNormalizer.normalize(
            toolCall = call,
            allowStructuralRepair = turnLocal,
            parameters = registered?.contract?.parameters
        ).also { normalized ->
            if (normalized.repaired) {
                debugLogManager.appendEvent(
                    "🧩 canonicalized arguments locally for tool=${call.name} call=${call.id}"
                )
            }
        }.toolCall
    }
    val reasoningMetadata = ReasoningEffortProtocol.extract(normalizedToolCalls)
    val protocolSafeToolCalls = reasoningMetadata.calls
    val reasoningDecision = reasoningControl.propose(reasoningMetadata)
    val roundVisibleMessage = PublicAssistantText.sanitize(fullContent).trim()
    val groundedAction = GroundedActionProtocol.extractScreenEvidence(
        toolCalls = protocolSafeToolCalls,
        pendingRepairs = modelRecordRepairs.pending.mapValues { it.value.receipt },
        sourceExists = evidenceLedger::hasCompletionEvidence
    )
    val rejectedRecordCalls = groundedAction.retentionOutcomes.filterValues { it.records == null }.keys
    if (rejectedRecordCalls.isNotEmpty()) {
        // screen_evidence feeds the visible page log and research note; it is not an
        // execution attestation. A provider omission must never discard a valid action.
        debugLogManager.appendEvent(
            "Optional model record rejected for call(s)=" +
                rejectedRecordCalls.joinToString(",") +
                "; valid device actions remain executable"
        )
    }
    val completionSubmission = CompletionSubmissionProtocol.resolve(groundedAction.toolCalls, workspaceContextReader, durableTurn.scope,
        Json.decodeFromString(ruleConfigManager.readMarkdownSection("workspace.md", "policy")),
        operatorTooling.contentTools.associate { it.name to it.parameters }, toolExecutor::canOpenPublicLink)
    val actionControl = ActionControlProtocol.extract(completionSubmission.calls, completionSubmission.errors,
        completionSubmission.errorDetails)
    val actionTitles = ActionTitleProtocol.extract(actionControl.toolCalls, PublicAssistantText::sanitize)
    val parsedToolCalls = actionTitles.toolCalls
    if (actionTitles.titlesByProviderOrder.size < parsedToolCalls.size) {
        debugLogManager.appendEvent(
            "Action title unavailable for ${parsedToolCalls.size - actionTitles.titlesByProviderOrder.size} call(s); generic activity labels retained"
        )
    }
    val toolCalls = parsedToolCalls
    suspend fun commitDurableRound(
        publicBlocks: List<String>,
        calls: List<ToolCall>
    ): OperatorRoundCommitter.Commit {
        val handle = durableRound
        val acceptedTaskTitle = taskNaming.title?.takeIf { taskTitle == null && modelRound.taskTitleRequested }
        val commit = roundSession.commit(
            handle, operatorTooling.registeredTools.associate { it.contract.name to it.presentation },
            presentedToolNames = presentedToolNames, publicBlocks = publicBlocks,
            calls = calls, finishMetadata = actionControl.finishMetadata + modelRound.inputMetadata +
                reasoningDecision.metadata() +
                mapOf("reasoning_control_mode" to if (reasoningControl.fixedEffort == null) "agent" else "fixed") +
                modelRound.titleRequest.finishMetadata(conversationTitle) +
                (acceptedTaskTitle?.let { mapOf("task_title" to it) } ?: emptyMap()) +
                (deadlineMetadata.bound?.let { mapOf("task_deadline" to JsonObject(mapOf(
                    (if (it.durationSeconds != null) "duration_seconds" else "deadline_at") to
                        (it.durationSeconds?.let(::JsonPrimitive) ?: JsonPrimitive(it.deadlineAt))
                )).toString()) } ?: emptyMap()) +
                (fullReasoning.takeIf { it.isNotBlank() }?.let { mapOf("model_thought" to it, "reasoning" to it) } ?: emptyMap()) + mapOf(
                // Replay the Provider's wire arguments, not the resolved file body.
                com.droidance.core.agent.context.DurableContextProjector.MODEL_ARGUMENTS_METADATA to
                    JsonObject(protocolSafeToolCalls.mapIndexedNotNull { index, call ->
                        if (call.name in setOf("done", "present_result_card"))
                            index.toString() to JsonPrimitive(call.arguments) else null
                    }.toMap()).toString(),
                "completion_candidate_refs" to completionSubmission.references,
                "completion_submission_errors" to kotlinx.serialization.json.Json.encodeToString(completionSubmission.errors),
                "completion_submission_error_details" to JsonObject(completionSubmission.errorDetails).toString(),
                "model_observations" to kotlinx.serialization.json.buildJsonArray {
                    groundedAction.evidence.forEach { evidence -> add(buildJsonObject {
                        put("provider_call_id", JsonPrimitive(evidence.toolCallId))
                        put("records", evidence.records)
                    }) }
                }.toString(),
                "operation_scopes" to calls.joinToString(",") { "${it.name}:${UnifiedDeviceToolProtocol.requirements(it.name, it.arguments).scope}" },
                "model_record_receipts" to JsonObject(groundedAction.retentionOutcomes.mapValues { (providerCallId, outcome) ->
                    outcome.receipt("model-round:${handle.scope.roundId}", providerCallId)
                }).toString()
            ) + ("action_title_status_by_order" to
                actionTitles.statusByProviderOrder.entries.joinToString(",") { "${it.key}:${it.value.name}" }),
            actionTitlesByProviderOrder = actionTitles.titlesByProviderOrder,
            mutationsRestricted = mutationsRestricted
        )
        reasoningControl.restore(reasoningDecision.metadata())
        checkTaskDeadline()
        acceptedTaskTitle?.let { title ->
            taskTitle = title
            onTaskTitle(title)
        }
        try {
            conversationTitles.commit(modelRound.titleRequest, conversationTitle, handle.scope.roundId)
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (failure: Exception) {
            debugLogManager.appendEvent("Conversation title update unavailable: ${failure.javaClass.simpleName}")
        }
        return commit
    }

    if (toolCalls.isEmpty() && fullContent.isBlank()) {
        settlePendingDurableRound("EMPTY_MODEL_RESPONSE")
        if (!emptyResponseNudged && ModelRoundRetryPolicy.shouldRetry(false, false, llmTransientRetries, llmTransientRetryLimit)) {
            emptyResponseNudged = true
            messages.add(ChatMessage(Role.SYSTEM, guardText("empty_final_response")))
            if (retryModelRequest("EMPTY_MODEL_RESPONSE")) return null
        }
        return modelProtocolFailure(guardText("empty_final_response"))
    }

    // A complete, nonempty decision renews the budget for the next decision. Failed
    // or empty requests retain the same budget and cannot monopolize the execution lane.
    modelRecoveryBudget.reset()
    llmTransientRetries = 0
    val interventionReply = interventionPublicReply(toolCalls, fullContent)
    val durablePublicBlocks = listOfNotNull(
        interventionReply ?: roundVisibleMessage.takeIf {
            it.isNotBlank() && toolCalls.none { call -> call.name in ToolCatalog.finalizationToolNames }
        }
    )
    val deviceBoundToolNames = operatorTooling.registeredTools
        .asSequence()
        .filter { it.contract.binding == ToolBinding.DEVICE_BOUND }
        .mapTo(linkedSetOf()) { it.contract.name }
    val deviceToolSelected = toolCalls.any { call ->
        call.name in deviceBoundToolNames &&
            operatorTooling.registeredTools.first { it.contract.name == call.name }
                .contract.forArguments(call.arguments).binding == ToolBinding.DEVICE_BOUND
    }
    // Device-round public text is rendered by the durable process timeline.
    if (deviceToolSelected) {
        ensureDurableExecutionBinding(repairPurpose)
    }
    val durableCommit = commitDurableRound(
        publicBlocks = durablePublicBlocks,
        calls = toolCalls
    )
    val durablePreparedExecution = durableCommit.prepared

    fun hasGroundedExecution(): Boolean {
        return executionToolCalls > 0 || evidenceLedger.hasEvidence()
    }

    fun rejectUngroundedCompletionSignal(content: String) {
        messages.add(ChatMessage(
            role = Role.ASSISTANT,
            content = content,
            reasoningContent = fullReasoning.takeIf { it.isNotBlank() }
        ))
        messages.add(ChatMessage(
            role = Role.SYSTEM,
            content = completionDeviceExecutionRequired().outputText
        ))
    }

    if (toolCalls.isNotEmpty()) {
        val roundPlan = durablePreparedExecution.roundPlan
        val deviceExecutionCallExists = roundPlan.selectedCalls.any {
            it.contract.binding == ToolBinding.DEVICE_BOUND
        } || roundPlan.rejectedCalls.any {
            it.call.contract.binding == ToolBinding.DEVICE_BOUND
        }
        if (deviceExecutionCallExists) {
            deviceExecutionRequested = true
        }
        if (roundPlan.selectedCalls.isNotEmpty()) {
            latencyTracker.toolRounds.incrementAndGet()
        }
        val selectedProviderOrders = roundPlan.selectedCalls
            .mapTo(mutableSetOf()) { it.providerOrder }
        val selectedByProviderOrder = roundPlan.selectedCalls
            .associateBy { it.providerOrder }
        val rejectedByProviderOrder = roundPlan.rejectedCalls
            .associateBy { it.call.providerOrder }
        val interventionProviderOrder = roundPlan.selectedCalls
            .firstOrNull { it.contract.effect == ToolEffect.USER_INTERVENTION }
            ?.providerOrder
        roundPlan.firstDeviceExecutionStartCall()?.let { executionStartCall ->
            ensureExecutionStarted(toolCalls[executionStartCall.providerOrder].name)
            stateFlow?.value = AgentState.EXECUTING_TOOL
        }

        val plannedAssistantIndex = messages.size
        messages.add(ChatMessage(
            role = Role.ASSISTANT,
            content = fullContent.ifBlank { interventionReply.orEmpty() },
            toolCalls = toolCalls.mapIndexed { index, call ->
                if (call.name in setOf("done", "present_result_card")) protocolSafeToolCalls[index] else call
            },
            reasoningContent = fullReasoning.takeIf { it.isNotBlank() }
        ))

        var completedToolCallCount = 0
        val batchCaptureBudget = com.droidance.core.agent.tools.ScreenBatchCaptureBudget(
            ruleConfigManager.harnessPolicy().screenActionBatch)
        val batchCaptures = mutableMapOf<String, com.droidance.core.agent.tools.ScreenBatchCaptures>()
        val orderedSequence = com.droidance.core.agent.tools.OrderedToolSequence()
        val settledSelectedCalls = mutableSetOf<String>()
        val screenSequenceMembers = com.droidance.core.agent.tools.screenActionSequenceMembers(
            selectedByProviderOrder.values.toList(), currentObservationLease)
        var interventionAccepted = false
        fun executionTimeoutFor(toolCall: ToolCall): Long =
            if (toolCall.name in setOf(DeviceToolNames.ANDROID_READ, DeviceToolNames.ANDROID_ACT) &&
                UnifiedDeviceToolProtocol.operation(toolCall.name, toolCall.arguments) == "content"
            ) {
                permissionToolExecutionTimeoutMs
            } else {
                toolExecutionTimeoutMs
            }

        suspend fun executeSelectedTool(
            toolCall: ToolCall,
            binding: ToolBinding
        ): ToolExecutionResult {
            suspend fun dispatch(): ToolExecutionResult = when (binding) {
                ToolBinding.TURN_LOCAL -> toolExecutor.executeTurnLocal(toolCall)
                ToolBinding.DEVICE_BOUND -> toolExecutor.execute(toolCall)
            }
            suspend fun executeBoundTool(): ToolExecutionResult {
                val member = screenSequenceMembers[toolCall.id]
                val captures = com.droidance.core.agent.tools.ScreenBatchCaptures(toolCall.id, batchCaptureBudget, blobStore)
                batchCaptures[toolCall.id] = captures
                return withContext(captures) {
                    if (member == null) dispatch() else withContext(member) { dispatch() }
                }
            }
            val timeoutMs = executionTimeoutFor(toolCall)
            suspend fun executeTimed(): ToolExecutionResult = if (timeoutMs > 0L) {
                withTimeout(timeoutMs) {
                    withContext(com.droidance.core.agent.tools.ToolExecutionBudget(timeoutMs,
                        deviceAccessAllowed = binding == ToolBinding.DEVICE_BOUND, mutationsAllowed = !mutationsRestricted)) {
                        executeBoundTool()
                    }
                }
            } else executeBoundTool()
            val isIntervention = selectedByProviderOrder.values.any {
                it.providerCall.name == toolCall.name && it.contract.effect == ToolEffect.USER_INTERVENTION
            }
            suspend fun executeOnce() = if (isIntervention) executeTimed() else runInterruptibleTool { executeTimed() }

            val result = executeOnce()
            // Waiting for the user is outside both the invocation timeout and the manual-pause watcher.
            toolExecutor.awaitToolConfirmation(toolCall, result)?.let { return it }
            checkTaskDeadline()
            return executeOnce()
        }
        val durableScope = durableRound.scope
        var durableAttemptRunning = false
        var durableAttemptSnapshot: com.droidance.core.agent.event.DurableExecutionAttemptSnapshot? = null

        suspend fun ensureDurableAttemptRunning(
            planned: com.droidance.core.agent.harness.PlannedToolCall?
        ) {
            val scope = durableScope ?: return
            val call = planned ?: return
            if (call.contract.binding != ToolBinding.DEVICE_BOUND || durableAttemptRunning) {
                return
            }
            val snapshot = eventStore.latestExecutionAttemptForTurn(durableTurn.scope)
                ?: error("Durable device call has no ExecutionAttempt")
            durableAttemptSnapshot = eventStore.ensureExecutionAttemptRunning(
                scope = com.droidance.core.agent.event.ExecutionAttemptEventScope(
                    threadId = scope.threadId,
                    turnId = scope.turnId,
                    attemptId = snapshot.scope.attemptId,
                    executionId = snapshot.scope.executionId
                ),
                transitionId = "round:${scope.roundId}:running",
                expectedLifecycleSequence = snapshot.lifecycleSequence
            )
            durableAttemptRunning = true
        }

        data class DurableToolAttachments(
            val saved: List<DetailAttachmentAddedEventPayload>,
            val expectedCount: Long
        )

        suspend fun durableToolAttachments(
            planned: com.droidance.core.agent.harness.PlannedToolCall,
            toolCall: ToolCall,
            decisionFrameBytes: ByteArray?,
            sourceObservationId: String?,
            resolvedObservationId: String?,
            output: ToolExecutionResult
        ): DurableToolAttachments {
            val sourceDecisionBytes = modelRound.nativeFrameBytes ?: decisionFrameBytes
            val captured = batchCaptures[planned.callId]?.screenshots.orEmpty().map { it.attachment } + output.actionFeedback
            if (output.executionStatus == ToolExecutionStatus.CANCELED && captured.isNotEmpty())
                return DurableToolAttachments(captured, captured.size.toLong())
            if (output.isVerifiedSuccess) output.historicalAttachment?.let { historical ->
                return DurableToolAttachments(listOf(historical.copy(
                    attachmentId = "${planned.callId}:history",
                    observationId = historical.observationId ?: historical.attachmentId
                )), 1L)
            }
            val imageRole = operatorTooling.registeredTools
                .firstOrNull { it.contract.name == planned.contract.name }
                ?.presentation
                ?.resultImageRole
                ?: ResultImageRole.NONE
            val toolScope = UnifiedDeviceToolProtocol.requirements(
                planned.contract.name,
                planned.providerCall.argumentsJson
            ).scope
            if (localKnowledge || imageRole == ResultImageRole.NONE || toolScope != ExecutionScope.DISPLAY)
                return DurableToolAttachments(captured, captured.size.toLong())

            var outputFrameObservationId: String? = null
            var outputModelImageBytes: ByteArray? = null
            suspend fun latestFrameBytes(): ByteArray? {
                if (!toolExecutor.awaitPendingScreenshot()) return null
                val observation = toolExecutor.currentPendingObservation()
                if (UnifiedDeviceToolProtocol.isScreenAction(toolCall.name) &&
                    observation?.actionId != planned.callId) return null
                outputFrameObservationId = observation?.observationId
                outputModelImageBytes = observation?.imageBase64?.let(::decodeModelFrame)
                return observation?.nativeImagePng ?: outputModelImageBytes
            }

            val outputFrameBytes = when {
                imageRole == ResultImageRole.OUTPUT ||
                    imageRole == ResultImageRole.BEFORE_AND_AFTER -> latestFrameBytes()
                imageRole == ResultImageRole.INPUT && isScreenObservation(toolCall) ->
                    latestFrameBytes()
                else -> null
            }
            val pending = buildList {
                if (imageRole == ResultImageRole.INPUT) {
                    val input = when {
                        isScreenObservation(toolCall) -> outputFrameBytes ?: sourceDecisionBytes
                        else -> sourceDecisionBytes
                    }
                    input?.takeIf(ByteArray::isNotEmpty)?.let { bytes ->
                        add(
                            PendingDetailAttachment(
                                attachmentId = "${planned.callId}:input",
                                role = DetailAttachmentRole.INPUT_OBSERVATION,
                                bytes = bytes,
                                mimeType = screenshotMimeType(bytes),
                                observationId = if (isScreenObservation(toolCall) && outputFrameBytes != null) {
                                    outputFrameObservationId ?: resolvedObservationId ?: "${planned.callId}:input"
                                } else sourceObservationId ?: "${planned.callId}:input",
                                modelImageBytes = if (isScreenObservation(toolCall) && outputFrameBytes != null)
                                    outputModelImageBytes else decisionFrameBytes
                            )
                        )
                    }
                }
                if (imageRole == ResultImageRole.BEFORE_AND_AFTER) {
                    sourceDecisionBytes?.takeIf(ByteArray::isNotEmpty)?.let { bytes ->
                        add(
                            PendingDetailAttachment(
                                attachmentId = "${planned.callId}:before",
                                role = DetailAttachmentRole.INPUT_OBSERVATION,
                                bytes = bytes,
                                mimeType = screenshotMimeType(bytes),
                                observationId = sourceObservationId ?: "${planned.callId}:before",
                                modelImageBytes = decisionFrameBytes
                            )
                        )
                    }
                }
                if (imageRole == ResultImageRole.OUTPUT ||
                    imageRole == ResultImageRole.BEFORE_AND_AFTER
                ) {
                    outputFrameBytes?.takeIf(ByteArray::isNotEmpty)?.let { bytes ->
                        add(
                            PendingDetailAttachment(
                                attachmentId = "${planned.callId}:after",
                                role = DetailAttachmentRole.RESULT,
                                bytes = bytes,
                                mimeType = screenshotMimeType(bytes),
                                observationId = outputFrameObservationId ?: "${planned.callId}:after",
                                modelImageBytes = outputModelImageBytes
                            )
                        )
                    }
                }
            }
            // Presentation policy is a plan, not proof that source pixels exist.
            // Count actual persistence inputs; an initial launch can have no before frame.
            val expectedCount = (captured.size + pending.size).toLong()
            if (pending.isEmpty()) return DurableToolAttachments(captured, expectedCount)
            val saved = try {
                captured + materializeDetailAttachments(blobStore, pending)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                debugLogManager.appendEvent(
                    "tool screenshot persistence failed: turn=${durableTurn.scope.turnId} call=${planned.callId} " +
                        "${error::class.simpleName}: ${error.message?.take(240)}"
                )
                captured
            }
            return DurableToolAttachments(saved, expectedCount)
        }

        suspend fun settleDurableTool(
            planned: com.droidance.core.agent.harness.PlannedToolCall?,
            toolCall: ToolCall,
            output: ToolExecutionResult,
            executionStatusOverride: ToolExecutionStatus? = null,
            diagnosticCodeOverride: String? = null
        ) {
            val scope = durableScope ?: return
            val call = planned ?: return
            val executionStatus = executionStatusOverride ?: output.executionStatus
            val succeeded = executionStatus == ToolExecutionStatus.SUCCEEDED
            val resolvedObservationId = output.observationId
            ensureDurableAttemptRunning(call)
            // A preceding device call may have initialized this round's attempt.
            // TURN_LOCAL receipts must keep the null binding of their declaration.
            val attempt = durableAttemptSnapshot.takeIf { call.contract.binding == ToolBinding.DEVICE_BOUND }
            val attachmentStartedNs = System.nanoTime()
            val attachmentFacts = durableToolAttachments(
                planned = call,
                toolCall = toolCall,
                decisionFrameBytes = decisionFrameBytes,
                sourceObservationId = sourceObservationId,
                resolvedObservationId = resolvedObservationId,
                output = output
            )
            val attachments = attachmentFacts.saved
            val imageRole = operatorTooling.registeredTools
                .firstOrNull { it.contract.name == call.contract.name }?.presentation?.resultImageRole
            val capturedOutput = batchCaptures[call.callId]?.attach(output) ?: output
            val enrichedOutput = GroundedActionProtocol.attachRetentionReceipt(capturedOutput,
                groundedAction.retentionOutcomes[toolCall.id], "model-round:${scope.roundId}", toolCall.id)
            val plannedImages = when (imageRole) {
                ResultImageRole.BEFORE_AND_AFTER -> 2L
                ResultImageRole.INPUT, ResultImageRole.OUTPUT -> 1L
                else -> 0L
            }
            val expectedImages = attachmentFacts.expectedCount
            eventStore.finishToolWithAttachments(
                scope = ToolLifecycleEventScope(
                    threadId = scope.threadId,
                    turnId = scope.turnId,
                    roundId = scope.roundId,
                    callId = call.callId,
                    attemptId = attempt?.scope?.attemptId,
                    executionId = attempt?.scope?.executionId
                ),
                result = enrichedOutput.copy(
                    executionStatus = executionStatus,
                    diagnosticCode = diagnosticCodeOverride ?: output.diagnosticCode,
                    metrics = enrichedOutput.metrics + mapOf(
                        "tool_attachment_ms" to (System.nanoTime() - attachmentStartedNs) / 1_000_000,
                        "screenshot_attachments_planned" to plannedImages,
                        "screenshot_attachments_expected" to expectedImages,
                        "screenshot_attachments_saved" to attachments.size.toLong(),
                        "screenshot_attachments_missing" to (expectedImages - attachments.size).coerceAtLeast(0)
                    )
                ),
                roundDetailRef = scope.roundId.let { "model-round:$it" },
                attachments = attachments
            )
            settledSelectedCalls += call.callId
            batchCaptures[call.callId]?.screenshots.orEmpty().forEach { frame ->
                evidenceLedger.registerImageEvidence(
                    observationId = checkNotNull(frame.attachment.observationId),
                    contentHash = frame.attachment.contentHash,
                    scope = "${frame.executionId}:${frame.backendSessionId}:${frame.displayId}:${frame.rotationDegrees}",
                    rawText = "Captured batch page at action ${frame.actionIndex}; inspect original image for its contents.",
                    screenshotRef = frame.attachment.contentRef,
                    isCurrentState = false
                )
            }
            enrichedOutput.historicalAttachment?.takeIf { enrichedOutput.isVerifiedSuccess }?.let { image ->
                evidenceLedger.registerImageEvidence(
                    checkNotNull(image.observationId), image.contentHash,
                    "retained:${durableTurn.scope.turnId}", enrichedOutput.outputText,
                    image.contentRef, isCurrentState = false
                )
            }
            val toolPresentation = operatorTooling.registeredTools
                .firstOrNull { it.contract.name == call.contract.name }
                ?.presentation
            val expectsPostActionImage = toolPresentation?.resultImageRole in setOf(
                ResultImageRole.OUTPUT,
                ResultImageRole.BEFORE_AND_AFTER
            ) && UnifiedDeviceToolProtocol.requirements(
                call.contract.name,
                call.providerCall.argumentsJson
            ).mayChangeDisplay

            if (succeeded && expectsPostActionImage && (
                    (call.contract.binding == ToolBinding.DEVICE_BOUND && call.contract.effect == ToolEffect.STATE_CHANGING) ||
                        (call.contract.name in recoveryPolicy.refreshAfterTools && currentObservationLease != null)
                )
            ) {
                val attempt = durableAttemptSnapshot
                if (call.contract.binding == ToolBinding.DEVICE_BOUND) {
                    checkNotNull(attempt) { "A durable state change requires an ExecutionAttempt" }
                }
                pendingPostActionResult = attachments.singleOrNull {
                    it.attachmentId == "${call.callId}:after" && it.role == DetailAttachmentRole.RESULT
                }
                pendingPostActionAttachmentScope = ToolLifecycleEventScope(
                    threadId = scope.threadId,
                    turnId = scope.turnId,
                    roundId = scope.roundId,
                    callId = call.callId,
                    attemptId = attempt?.scope?.attemptId,
                    executionId = attempt?.scope?.executionId
                )
            }
        }

        suspend fun processToolCalls(): ToolRoundOutcome {
            /**
             * 完成类调用的证据守卫：终态观察引用、完成证据、目标复核三项判定彼此耦合，
             * 但只有两个结果被守卫链下游使用，集中在这里让主链保持可读。
             */
            fun evaluateCompletionEvidenceGuards(
                tc: ToolCall,
                control: ActionControlProtocol.Control?,
                fullCompletionRequested: Boolean
            ): CompletionEvidenceGuards {
                if (tc.name in setOf("done", "present_result_card") && control?.invalidArgument != null) {
                    return CompletionEvidenceGuards(
                        evidenceBlockResult = ActionControlProtocol.argumentFailure(control,
                            CompletionSubmissionProtocol.parameters(operatorTooling.contentTools.first { it.name == tc.name }.parameters),
                            Json.parseToJsonElement(completionSubmission.references).jsonObject[tc.id] as? JsonObject)
                    )
                }
                if (executionStarted && tc.name in ToolCatalog.finalizationToolNames &&
                    control != null && ((control.requirementChecksDeclared && !CompletionCoverage.suppliedReferencesValid(
                        control.requirementChecks, evidenceLedger::hasCompletionEvidence
                    )) || (control.claims.orEmpty().isNotEmpty() &&
                        !ActionControlProtocol.completionReferencesValid(control, evidenceLedger::hasCompletionEvidence)))
                ) {
                    val availableEvidenceRefs = evidenceLedger.completionEvidenceReferences()
                        .split(',').map(String::trim).filter(String::isNotBlank)
                    return CompletionEvidenceGuards(evidenceBlockResult = ToolProtocolResult.notExecuted(
                        code = "COMPLETION_RESULTS_REQUIRED", retryable = true,
                        details = ActionControlProtocol.completionClaimIssues(
                            control, availableEvidenceRefs, evidenceLedger::unresolvedCompletionReferences
                        ),
                        evidenceRefs = availableEvidenceRefs
                    ))
                }
                val latestTerminalObservationRef = terminalObservationRefVisibleToModel
                val terminalObservationRequired = completionGroundingGate
                    .requiresTerminalObservation(
                        fullCompletionRequested = fullCompletionRequested,
                        takeoverManaged = takeoverManaged
                    )
                // The runtime knows which frame reached this Agent. Echoing its UUID
                // is optional; a supplied reference still has to identify that frame.
                val terminalObservationReferenceValid = !terminalObservationRequired ||
                    (latestTerminalObservationRef != null &&
                        (control?.terminalObservationRef.isNullOrBlank() ||
                            control?.terminalObservationRef == latestTerminalObservationRef))
                val completionAfterFailedActionBlockResult =
                    if (executionStarted &&
                        fullCompletionRequested &&
                        !takeoverManaged &&
                        completionGroundingGate.requiresFreshObservationBeforeCompletion()
                    ) {
                        completionFreshObservationRequired()
                    } else {
                        null
                    }
                val completionEvidenceBlockResult =
                    if (executionStarted && fullCompletionRequested &&
                        !ActionControlProtocol.completionReferencesValid(control, evidenceLedger::hasCompletionEvidence)
                    ) {
                        val availableEvidenceRefs = evidenceLedger.completionEvidenceReferences()
                            .split(',').map(String::trim).filter(String::isNotBlank)
                        ToolProtocolResult.notExecuted(
                            code = "COMPLETION_RESULTS_REQUIRED",
                            retryable = true,
                            details = ActionControlProtocol.completionClaimIssues(
                                control, availableEvidenceRefs, evidenceLedger::unresolvedCompletionReferences
                            ),
                            evidenceRefs = availableEvidenceRefs
                        )
                    } else if (executionStarted &&
                        fullCompletionRequested &&
                        !takeoverManaged &&
                        (completionAfterFailedActionBlockResult != null ||
                            !terminalObservationReferenceValid ||
                            (fullCompletionRequested &&
                                evidenceLedger.routeVerificationIssues().isNotEmpty()))
                    ) {
                        completionAfterFailedActionBlockResult
                            ?: completionEvidenceRejection(control, latestTerminalObservationRef)
                    } else {
                        null
                    }
                return CompletionEvidenceGuards(
                    evidenceBlockResult = completionEvidenceBlockResult
                )
            }

            suspend fun evaluateToolCallGuards(
                tc: ToolCall,
                selectedCall: com.droidance.core.agent.harness.PlannedToolCall?,
                planRejection: com.droidance.core.agent.harness.RejectedToolCall?,
                planRejectionCode: String?,
                control: ActionControlProtocol.Control?
            ): ToolCallGuardEvaluation {
                val repeatObservationKey = currentObservationLease?.let { envelope ->
                    "${envelope.executionId}:${envelope.sequence}"
                }
                val exactRepeatEligible =
                    selectedCall?.contract?.binding == ToolBinding.DEVICE_BOUND &&
                        selectedCall.contract.effect == ToolEffect.STATE_CHANGING &&
                        repeatObservationKey != null
                val exactActionRepeatBlockResult =
                    if (planRejectionCode == null && observationRecovery.blocked(tc.name, tc.arguments)) {
                        ToolProtocolResult.notExecuted(
                            code = "OBSERVATION_RECOVERY_REQUIRED",
                            retryable = false,
                            details = buildJsonObject {
                                put("tool", JsonPrimitive(tc.name))
                                put("repetition_facts", observationRecovery.recoveryFacts(tc.name, tc.arguments))
                                put("recovery_condition", JsonPrimitive(ruleConfigManager.readMarkdownSection(
                                    "observation_recovery.md", "feedback"
                                )))
                            }
                        )
                    } else if (planRejectionCode == null && exactRepeatEligible &&
                        exactActionRepeatGuard.wasExecuted(
                            observationId = checkNotNull(repeatObservationKey),
                            toolName = tc.name,
                            arguments = tc.arguments
                        )
                    ) {
                        ToolProtocolResult.notExecuted(
                            code = "EXACT_ACTION_REPEATED",
                            retryable = false,
                            details = buildJsonObject {
                                put("tool", JsonPrimitive(tc.name))
                                put("observation", JsonPrimitive(checkNotNull(repeatObservationKey)))
                            }
                        )
                    } else {
                        null
                    }
                val blindActionBlockResult =
                    if (planRejectionCode == null && tc.name in ToolCatalog.screenDependentActionToolNames &&
                        !hasCurrentScreenContext
                    ) {
                        ToolProtocolResult.notExecuted(
                            code = "CURRENT_SCREEN_REQUIRED",
                            retryable = true
                        )
                    } else {
                        null
                    }
                val staleObservationBlockResult =
                    if (planRejectionCode == null && tc.name in ToolCatalog.screenDependentActionToolNames &&
                        currentObservationLease == null
                    ) {
                        ToolProtocolResult.notExecuted(
                            code = "STALE_OBSERVATION",
                            retryable = true
                        )
                    } else {
                        null
                    }
                val successfulDoneRequested = tc.name == "done" &&
                    jsonStringArg(tc.arguments, "status").equals("done", ignoreCase = true)
                val fullCompletionRequested =
                    successfulDoneRequested || tc.name == "present_result_card"
                val completionEvidenceGuards = evaluateCompletionEvidenceGuards(
                    tc = tc,
                    control = control,
                    fullCompletionRequested = fullCompletionRequested
                )
                val prematureTaskDone =
                    successfulDoneRequested && deviceExecutionRequested &&
                        !takeoverManaged && !hasGroundedExecution()
                val requirementPlan = com.droidance.core.agent.event.TaskRequirementPlan.current(
                    eventStore.eventsForTurn(durableTurn.scope.turnId), durableTurn.scope.threadId, durableTurn.scope.turnId)
                val pendingRefreshPlan = if (requirementPlan == null)
                    com.droidance.core.agent.event.TaskRequirementPlan.pendingRefresh(
                        eventStore.eventsForTurn(durableTurn.scope.turnId), durableTurn.scope.threadId, durableTurn.scope.turnId)
                    else null
                val restrictionPlan = requirementPlan ?: pendingRefreshPlan
                // Re-read committed events: a preceding workspace write in this same ordered
                // model response must be visible before the next device call or completion.
                val executionProgress = TaskExecutionProgress.project(
                    eventStore.eventsForTurn(durableTurn.scope.turnId), durableTurn.scope, recoveryPolicy.refreshAfterDiagnostics)
                val executionContract = selectedCall?.contract
                val executionPlanBlock = if (planRejectionCode == null && !takeoverManaged)
                    executionProgress.check(tc.name, tc.arguments,
                        deviceMutation = executionContract?.binding == ToolBinding.DEVICE_BOUND &&
                            executionContract.effect == ToolEffect.STATE_CHANGING,
                        policy = taskExecutionPolicy,
                        instruction = ruleConfigManager.readMarkdownSection("workspace.md", "execution_plan"),
                        sourceExists = evidenceLedger::hasCompletionEvidence)
                    else null
                val finalizingTask = tc.name in ToolCatalog.finalizationToolNames
                val requirementBlockResult = if (planRejectionCode == null && !takeoverManaged)
                    TaskRequirementGuard.check(requirementPlan, tc.arguments,
                        memo = tc.name == "task_memo",
                        requiresCoverage = finalizingTask && control?.invalidArgument == null &&
                            control?.requirementChecks?.isNotEmpty() == true,
                        completionRequirements = control?.takeIf { it.invalidArgument == null && it.requirementChecksDeclared }?.requirementChecks?.map { it.requirement }.takeIf { tc.name in ToolCatalog.finalizationToolNames },
                        instruction = ruleConfigManager.readMarkdownSection("completion_recovery.md", "requirement_plan"),
                        pendingRefreshPlan = pendingRefreshPlan)
                    else null
                val completionEvidenceBlockResult = executionPlanBlock.takeIf { finalizingTask }
                    ?: requirementBlockResult.takeIf { finalizingTask }
                    ?: (if (finalizingTask && control?.invalidArgument == null && restrictionPlan?.constraints != null) {
                        val reference = Json.parseToJsonElement(completionSubmission.references).jsonObject[tc.id] as? JsonObject
                        val files = (reference?.get("deliverables_to_verify") as? JsonArray).orEmpty().map { it.jsonObject }
                        runCatching { restrictionPlan.constraints.validateCompletion(
                            Json.parseToJsonElement(checkNotNull(control?.originalArguments)).jsonObject, files, restrictionPlan)
                        }.exceptionOrNull()?.let { failure -> ToolProtocolResult.notExecuted(
                            "COMPLETION_OUTPUT_CONSTRAINT_VIOLATION", true, details = buildJsonObject {
                                put("reason", JsonPrimitive(failure.message.orEmpty()))
                                put("recovery_condition", JsonPrimitive(ruleConfigManager.readMarkdownSection("completion_recovery.md", "requirement_plan")))
                            }) }
                    } else null)
                    ?: completionEvidenceGuards.evidenceBlockResult
                    ?: if (!takeoverManaged && !prematureTaskDone &&
                        planRejectionCode == null && tc.name in ToolCatalog.finalizationToolNames
                    ) {
                        val reviewEvents = eventStore.eventsForTurn(durableTurn.scope.turnId)
                        val fileReference = Json.parseToJsonElement(completionSubmission.references)
                            .jsonObject[tc.id] as? JsonObject
                        completionReviewGate.request(
                            taskGoal, ruleConfigManager.readMarkdownSection("completion_recovery.md", "review_candidate"),
                            evidenceLedger.completionImageSources(
                                ActionControlProtocol.completionEvidenceReferences(control)
                            ),
                            CompletionReviewCandidate.from(tc.name, checkNotNull(control?.originalArguments), fileReference),
                            reviewEvents
                                .filter { it.eventKind == com.droidance.core.agent.event.AgentEventKind.USER_STEER }
                                .map { Json.decodeFromString<com.droidance.core.agent.event.UserTextEventPayload>(it.payloadJson).text },
                            confirmation = completionSubmission.reviews[tc.id]
                        )
                    } else null
                // Optional note retention never changes execution permission. Its
                // rejected payload remains available for repair from original sources.
                val hardBlockResult = executionPlanBlock ?: requirementBlockResult ?: blindActionBlockResult
                    ?: staleObservationBlockResult
                    ?: completionEvidenceBlockResult
                    ?: exactActionRepeatBlockResult
                val guardedRejectionResult = when {
                    prematureTaskDone -> completionDeviceExecutionRequired()
                    hardBlockResult != null -> hardBlockResult
                    else -> null
                }
                val rejectedCompletionResult = when {
                    prematureTaskDone -> guardedRejectionResult
                    completionEvidenceBlockResult != null -> completionEvidenceBlockResult
                    else -> null
                }
                val locallyRejectedResult = when {
                    // Return the same enriched receipt that was committed durably.
                    // Rebuilding it here drops phase-specific repair facts and makes
                    // the model's view disagree with the recorded rejection.
                    planRejectionCode != null -> durablePreparedExecution.toolRejections.single {
                        it.call.callId == checkNotNull(planRejection).call.callId
                    }.result
                    prematureTaskDone -> {
                        toolExecutor.clearCompletionRequest()
                        guardedRejectionResult
                    }
                    hardBlockResult != null -> hardBlockResult
                    else -> null
                }
                return ToolCallGuardEvaluation(
                    repeatObservationKey = repeatObservationKey,
                    exactRepeatEligible = exactRepeatEligible,
                    exactActionRepeatBlockResult = exactActionRepeatBlockResult,
                    blindActionBlockResult = blindActionBlockResult,
                    staleObservationBlockResult = staleObservationBlockResult,
                    successfulDoneRequested = successfulDoneRequested,
                    completionEvidenceBlockResult = completionEvidenceBlockResult,
                    hardBlockResult = hardBlockResult,
                    prematureTaskDone = prematureTaskDone,
                    guardedRejectionResult = guardedRejectionResult,
                    rejectedCompletionResult = rejectedCompletionResult,
                    locallyRejectedResult = locallyRejectedResult
                )
            }
            suspend fun executeToolCall(
                toolIdx: Int,
                tc: ToolCall,
                selectedCall: com.droidance.core.agent.harness.PlannedToolCall?,
                locallyRejectedResult: ToolExecutionResult?,
                exactRepeatEligible: Boolean,
                repeatObservationKey: String?
            ): ToolExecutionResult {
                checkTaskDeadline()
                var durableToolSettled = false
                val result = locallyRejectedResult ?: run {
                    check(toolIdx in selectedProviderOrders) {
                        "Round planner neither selected nor rejected Provider tool call $toolIdx"
                    }
                    try {
                        val executingCall = checkNotNull(selectedCall)
                        ensureDurableAttemptRunning(executingCall)
                        val isScreenMutation = executingCall.contract.name in setOf(
                            DeviceToolNames.SCREEN_ACT,
                        ) || (executingCall.contract.name in setOf(DeviceToolNames.ANDROID_ACT, DeviceToolNames.SHELL) &&
                            UnifiedDeviceToolProtocol.expectsScreenChange(executingCall.contract.name, tc.arguments))
                        completionGroundingGate.recordExecutionStart(
                            callId = executingCall.callId,
                            binding = executingCall.contract.binding,
                            effect = executingCall.contract.effect,
                            mutatesScreen = isScreenMutation
                        )
                        if (exactRepeatEligible) {
                            exactActionRepeatGuard.record(
                                observationId = checkNotNull(repeatObservationKey),
                                toolName = tc.name,
                                arguments = tc.arguments
                            )
                        }
                        executeSelectedTool(
                            toolCall = tc.copy(id = executingCall.callId),
                            binding = executingCall.contract.binding
                        )
                    } catch (e: ManualToolPauseException) {
                        ToolProtocolResult.failed(
                            code = "TOOL_INTERRUPTED_FOR_HUMAN",
                            retryable = false,
                            details = buildJsonObject {
                                e.cause?.screenActionBatchCancellationReceiptOrNull()?.let {
                                    put("batch_receipt", it.toJsonDetails())
                                }
                            }
                        ).copy(executionStatus = ToolExecutionStatus.INTERRUPTED,
                            metrics = e.cause?.screenActionBatchCancellationReceiptOrNull()?.progressMetrics().orEmpty())
                    } catch (e: TimeoutCancellationException) {
                        ToolProtocolResult.failed(
                            code = "TOOL_EXECUTION_TIMEOUT",
                            retryable = false,
                            details = buildJsonObject {
                                put("tool", JsonPrimitive(tc.name))
                                put("timeout_ms", JsonPrimitive(executionTimeoutFor(tc)))
                                e.screenActionBatchCancellationReceiptOrNull()?.let {
                                    put("batch_receipt", it.toJsonDetails())
                                }
                            }
                        ).copy(metrics = e.screenActionBatchCancellationReceiptOrNull()?.progressMetrics().orEmpty(),
                            executionStatus = if (tc.name == DeviceToolNames.SHELL) ToolExecutionStatus.INTERRUPTED else ToolExecutionStatus.FAILED,
                            evidenceStatus = ToolEvidenceStatus.UNKNOWN)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) {
                            withContext(NonCancellable) {
                                runCatching {
                                    withTimeout(3_000L) {
                                        if (durableScope != null && selectedCall != null) {
                                            settleDurableTool(
                                                planned = selectedCall,
                                                toolCall = tc,
                                                output = e.screenActionBatchCancellationReceiptOrNull()?.let {
                                                    ToolProtocolResult.canceled("TURN_CANCELED", it.toJsonDetails()).copy(metrics = it.progressMetrics())
                                                } ?: ToolExecutionResult(guardText("turn_canceled"), ToolExecutionStatus.CANCELED, ToolEvidenceStatus.NOT_REQUIRED),
                                                executionStatusOverride = ToolExecutionStatus.CANCELED,
                                                diagnosticCodeOverride = "TURN_CANCELED"
                                            )
                                            durableToolSettled = true
                                        }
                                    }
                                }.onFailure { settlementError ->
                                    debugLogManager.appendEvent(
                                        "canceled tool settlement failed: " +
                                            "${settlementError::class.simpleName}: ${settlementError.message?.take(240)}"
                                    )
                                }
                            }
                            throw e
                        }
                        ToolProtocolResult.failed(
                            code = "TOOL_EXECUTION_FAILED",
                            retryable = false,
                            details = buildJsonObject {
                                put("tool", JsonPrimitive(tc.name))
                                put("error_type", JsonPrimitive(e::class.simpleName.orEmpty()))
                                put("message", JsonPrimitive(e.message.orEmpty().take(500)))
                            }
                        )
                    }
                }
                var identifiedResult = ToolObservationReference.forGroundedCall(
                    result = batchCaptures[selectedCall?.callId]?.attach(result) ?: result,
                    groundsCompletion = locallyRejectedResult == null &&
                        completionGroundingByTool[tc.name] == true && !isScreenObservation(tc),
                    turnId = durableTurn.scope.turnId,
                    callId = selectedCall?.callId ?: tc.id
                )
                if (!isScreenObservation(tc) && (selectedCall?.contract?.effect == ToolEffect.READ_ONLY ||
                        identifiedResult.executionStatus == ToolExecutionStatus.NOT_EXECUTED)) {
                    repetitionFacts.record(tc.name, tc.arguments, identifiedResult)?.let { facts ->
                        repetitionDirective?.let(messages::remove)
                        repetitionDirective = ChatMessage(Role.SYSTEM,
                            ruleConfigManager.readMarkdownSection("utility_prompts.md", "tool_repetition") + "\n" + facts)
                            .also(messages::add)
                        identifiedResult = identifiedResult.copy(metrics = identifiedResult.metrics +
                            ("same_result_count" to facts.getValue("same_arguments_and_result_count").jsonPrimitive.content.toLong()))
                    }
                }
                // Note acknowledgements must not make identical native reads appear new.
                identifiedResult = GroundedActionProtocol.attachRetentionReceipt(identifiedResult,
                    groundedAction.retentionOutcomes[tc.id], "model-round:${checkNotNull(durableScope).roundId}", tc.id)
                if (durableScope != null && selectedCall != null && !durableToolSettled) {
                    settleDurableTool(
                        planned = selectedCall,
                        toolCall = tc,
                        output = identifiedResult,
                        // A local guard has already withheld the provider call. Do not
                        // let a human-readable guard marker be projected as a successful
                        // execution merely because it lacks a provider receipt.
                        executionStatusOverride =
                            ToolExecutionStatus.NOT_EXECUTED.takeIf { locallyRejectedResult != null }
                    )
                }
                return identifiedResult
            }
            var interventionEntered = false
            for ((toolIdx, tc) in toolCalls.withIndex()) {
            checkTaskDeadline()
            coroutineContext.ensureActive()
            if (!interventionEntered) {
                waitForActiveExecution()
                checkTaskDeadline()
                coroutineContext.ensureActive()
                val manualResumeInstruction = takeManualResumeInstruction()
                if (manualResumeInstruction != null) {
                    // The committed plan already owns ToolStarted facts. Close every
                    // withheld call before the next model round reconstructs its history.
                    for (remainingIndex in toolIdx until toolCalls.size) {
                        val pending = selectedByProviderOrder[remainingIndex] ?: continue
                        settleDurableTool(pending, toolCalls[remainingIndex],
                            ToolProtocolResult.notExecuted("USER_CONTEXT_CHANGED", true))
                    }
                    InterruptedToolTurn.discardPendingCalls(
                        messages = messages,
                        assistantIndex = plannedAssistantIndex,
                        completedCallCount = completedToolCallCount
                    )
        resetAfterManualResume(
                        manualResumeInstruction,
                        "manual takeover resumed before tool execution; discarded planned action and reset loop guards"
                    )
                    return ToolRoundOutcome.RestartRound
                }
            }
            totalToolCalls += 1
            val control = actionControl.controls[tc.id]
            val planRejection = rejectedByProviderOrder[toolIdx]
            val selectedCall = selectedByProviderOrder[toolIdx]
            // Planned rejections are already settled with their own exact receipt.
            // A failed prerequisite only blocks later calls selected for execution.
            val sequenceRejection = if (selectedCall != null) orderedSequence.rejection(
                independentOfWorkingState = tc.name != "workspace" && tc.name !in ToolCatalog.finalizationToolNames
            ) else null
            val screenMember = screenSequenceMembers[selectedCall?.callId]
            if (sequenceRejection == null && screenMember != null && !screenMember.first) {
                currentObservationLease = screenMember.sequence.observation
                toolExecutor.bindModelFrame(screenMember.sequence.observation)
            }
            val planRejectionCode = planRejection?.diagnosticCode
            val selfManagementTool = tc.name in ToolCatalog.selfManagementToolNames
            // Public titles describe the current attempt, including observation, on both islands.
            if (planRejectionCode == null && !selfManagementTool) {
                onActionUpdate(actionTitles.titlesByProviderOrder[toolIdx] ?: buildActionLabel(tc))
            }
            val guard = evaluateToolCallGuards(
                tc = tc,
                selectedCall = selectedCall,
                planRejection = planRejection,
                planRejectionCode = planRejectionCode,
                control = control
            )
            val repeatObservationKey = guard.repeatObservationKey
            val exactRepeatEligible = guard.exactRepeatEligible
            val exactActionRepeatBlockResult = guard.exactActionRepeatBlockResult
            val blindActionBlockResult = guard.blindActionBlockResult
            val staleObservationBlockResult = guard.staleObservationBlockResult
            val successfulDoneRequested = guard.successfulDoneRequested
            val completionEvidenceBlockResult = guard.completionEvidenceBlockResult
            val hardBlockResult = guard.hardBlockResult
            val prematureTaskDone = guard.prematureTaskDone
            val guardedRejectionResult = guard.guardedRejectionResult
            val rejectedCompletionResult = guard.rejectedCompletionResult
            val locallyRejectedResult = sequenceRejection ?: guard.locallyRejectedResult
            val beforeActionObservationId =
                evidenceLedger.latestGroundedObservation()?.id
            val hadScreenObservation = currentObservationLease != null
            val result = executeToolCall(
                toolIdx = toolIdx,
                tc = tc,
                selectedCall = selectedCall,
                locallyRejectedResult = locallyRejectedResult,
                exactRepeatEligible = exactRepeatEligible,
                repeatObservationKey = repeatObservationKey
            )
            // Every started invocation settles before any recovery, handoff or early exit.
            if (locallyRejectedResult == null) {
                completionGroundingGate.recordExecutedResult(
                    selectedCall?.callId ?: tc.id, selectedCall?.contract?.binding,
                    selectedCall?.contract?.effect, result,
                    mutatesScreen = UnifiedDeviceToolProtocol.expectsScreenChange(tc.name, tc.arguments) ||
                        result.executionStatus in setOf(ToolExecutionStatus.INTERRUPTED, ToolExecutionStatus.CANCELED)
                )
            }
            if (selectedCall != null) orderedSequence.record(tc.id, result,
                workingStateOnly = tc.name == "workspace" && selectedCall.contract.binding == ToolBinding.TURN_LOCAL &&
                    com.droidance.core.agent.tools.WorkspaceTool.isWorkingStateMutation(tc.arguments))
            val toolDiagnosticCode = ToolResultStatus.diagnosticCode(result)
            lastExecutionDiagnostic = toolDiagnosticCode.takeUnless { ToolResultStatus.isSuccess(result) }
            if (toolDiagnosticCode in setOf("TOOL_BINDING_MISMATCH", "TOOL_EXECUTION_FAILED")) {
                return ToolRoundOutcome.Terminal(groundedGuardStop(toolDiagnosticCode))
            }
            val recovery = ToolRecovery.classify(result, recoveryPolicy.refreshAfterDiagnostics)
            if (toolDiagnosticCode == "VIRTUAL_SPACE_EMPTY" &&
                result.executionStatus == ToolExecutionStatus.FAILED) {
                retainEmptyDisplayObservation(result)
            }
            val recoveryHandoff = ruleConfigManager.harnessPolicy().humanAssistance.recoveryHandoffs[toolDiagnosticCode]
            if (recoveryHandoff != null) {
                currentObservationLease = null
                terminalObservationRefVisibleToModel = null
                toolExecutor.invalidateModelFrameBinding()
                toolExecutor.invalidatePendingObservation()
                onManualTakeoverNeeded(fallbackText(recoveryHandoff))
                if (!isPaused()) return ToolRoundOutcome.Terminal(groundedGuardStop())
                waitForActiveExecution()
                freshObservationRequired = true
                forceFreshObservationCapture = true
                return ToolRoundOutcome.RestartRound
            }
            val coordinateProtocolFailure = toolDiagnosticCode == "COORDINATE_OUT_OF_RANGE"
            if (planRejectionCode == null && hardBlockResult == null && !prematureTaskDone) {
                executedToolNames.add(tc.name)
                if (tc.name == "manage_app_takeover" &&
                    result.isVerifiedSuccess &&
                    toolProfile == OperatorToolProfile.BACKGROUND_AUTOMATION
                ) {
                    takeoverManaged = true
                }
            }
            coroutineContext.ensureActive()

            lastToolResultForReflector = buildString {
                appendLine("tool=${tc.name}")
                appendLine("arguments=${tc.arguments.take(500)}")
                append(result.outputText.take(2500))
            }
            val observationRef = result.observationId
            val toolMessageContent = if (observationRef != null && ToolResultStatus.isSuccess(result) && !result.outputText.startsWith("[observation id=")) {
                val sourceKind = if (result.historicalAttachment != null) "retained_source" else "observed_state"
                "[observation id=\"$observationRef\" evidence_ref=\"$observationRef\" source=\"$sourceKind\"]\n${result.outputText}"
            } else {
                result.outputText
            }
            messages.add(ChatMessage(
                role = Role.TOOL,
                content = toolMessageContent,
                toolCallId = tc.id
            ))
            if (locallyRejectedResult == null && exactRepeatEligible && repeatObservationKey != null) {
                exactActionRepeatGuard.settle(repeatObservationKey, tc.name, tc.arguments, result.executionStatus)
            }
            observationRecovery.settled(tc.name, tc.arguments, result)
            val toolNotExecuted = ToolResultStatus.isNotExecuted(result)
            val toolSucceeded = ToolResultStatus.isSuccess(result)
            if (CompletionReviewGate.isReview(result.diagnosticCode)) {
                recordRecoveryPending = false
                observationRecoveryDirective?.let(messages::remove)
                observationRecoveryDirective = null
                observationReplanContext.reset()
                completionReviewContext.begin(tc, result.outputText)
                if (completionRejectionCycleGuard.reviewRequired()) {
                    return ToolRoundOutcome.Terminal(groundedGuardStop())
                }
            }
            if (locallyRejectedResult == null && !toolNotExecuted &&
                selectedCall?.contract?.binding == ToolBinding.DEVICE_BOUND) {
                observationRecovery.executed(tc.name, tc.arguments, result)
            }
            if (CompletionReviewGate.isReview(result.diagnosticCode) || result.diagnosticCode == "COMPLETION_RESULTS_REQUIRED" ||
                (tc.name in ToolCatalog.finalizationToolNames && result.diagnosticCode == "ARGUMENT_SCHEMA_MISMATCH")) {
                completionReviewDirective?.let(messages::remove)
                completionReviewDirective = ChatMessage(role = Role.SYSTEM, content =
                    ruleConfigManager.readMarkdownSection("completion_recovery.md",
                        when { CompletionReviewGate.isReview(result.diagnosticCode) -> "review_candidate"
                            result.diagnosticCode == "ARGUMENT_SCHEMA_MISMATCH" -> "schema_repair"
                            else -> "binding_repair" }) + "\n" +
                        buildJsonObject { put("original_request", JsonPrimitive(taskGoal)) }.toString())
                messages.add(checkNotNull(completionReviewDirective))
            } else if (toolSucceeded && selectedCall?.contract?.binding == ToolBinding.DEVICE_BOUND &&
                selectedCall.contract.effect == ToolEffect.STATE_CHANGING &&
                !UnifiedDeviceToolProtocol.isObservationOnlyAction(tc.name, tc.arguments)) {
                completionReviewDirective?.let(messages::remove)
                completionReviewDirective = null
                completionReviewContext.reset()
            }
            val recoveryRejected = recovery in setOf(
                ToolRecovery.OBSERVE, ToolRecovery.REPLAN, ToolRecovery.RECORD, ToolRecovery.PROTOCOL_REPAIR)
            val observationRejected = recovery in setOf(ToolRecovery.OBSERVE, ToolRecovery.REPLAN)
            // A changed frame needs only the fresh observation and its receipt in the same
            // history; a method review is reserved for exhausted methods and repairs.
            if (recoveryRejected && recovery != ToolRecovery.OBSERVE) {
                recordRecoveryPending = recordRecoveryPending || recovery == ToolRecovery.RECORD
                // Completion execution arguments have already been compiled (including
                // runtime file links). Repair the exact native proposal, not that body.
                val recoveryCall = if (recovery == ToolRecovery.PROTOCOL_REPAIR)
                    protocolSafeToolCalls.firstOrNull { it.id == tc.id } ?: tc else tc
                observationReplanContext.begin(recoveryCall, buildJsonObject {
                    put("tool", JsonPrimitive(tc.name))
                    if (recovery != ToolRecovery.PROTOCOL_REPAIR) put("arguments", JsonPrimitive(tc.arguments))
                    put("receipt", JsonPrimitive(result.outputText))
                    put("repetition_facts", observationRecovery.recoveryFacts(tc.name, tc.arguments))
                    put("failed_methods", observationRecovery.failedMethodHistory())
                }.toString(), protocolRepair = recovery == ToolRecovery.PROTOCOL_REPAIR)
                observationRecoveryDirective?.let(messages::remove)
                observationRecoveryDirective = ChatMessage(role = Role.SYSTEM, content =
                    (if (recovery == ToolRecovery.PROTOCOL_REPAIR)
                        ruleConfigManager.readMarkdownSection("utility_prompts.md", "protocol_repair")
                        else if (recordRecoveryPending) ruleConfigManager.readMarkdownSection("workspace.md", "record_recovery")
                        else observationReplanInstruction()) + "\n" +
                        buildJsonObject {
                            put("original_request", JsonPrimitive(taskGoal))
                            put("repetition_facts", observationRecovery.recoveryFacts(tc.name, tc.arguments))
                            put("diagnostic_code", JsonPrimitive(toolDiagnosticCode))
                        }.toString())
                messages.add(checkNotNull(observationRecoveryDirective))
            } else if (observationReplanContext.resolvedBy(result)) {
                observationRecoveryDirective?.let(messages::remove)
                observationRecoveryDirective = null
                observationReplanContext.reset()
            } else if (!recordRecoveryPending && toolSucceeded && selectedCall?.contract?.binding == ToolBinding.DEVICE_BOUND &&
                selectedCall.contract.effect == ToolEffect.STATE_CHANGING &&
                !UnifiedDeviceToolProtocol.isObservationOnlyAction(tc.name, tc.arguments)) {
                // Retain settled history, but stop presenting a resolved incident as a current instruction.
                observationRecoveryDirective?.let(messages::remove)
                observationRecoveryDirective = null
                observationReplanContext.reset()
            }
            if (observationRejected || observationFreshnessPolicy.mustInvalidateAfter(
                    tc.name, toolSucceeded, currentObservationLease != null, recoveryPolicy.refreshAfterTools
                )) {
                currentObservationLease = null
                terminalObservationRefVisibleToModel = null
                toolExecutor.invalidateModelFrameBinding()
                toolExecutor.invalidatePendingObservation()
                freshObservationRequired = true
                forceFreshObservationCapture = true
            }
            if (
                locallyRejectedResult == null &&
                !toolNotExecuted &&
                selectedCall?.contract?.binding == ToolBinding.DEVICE_BOUND &&
                selectedCall?.contract?.effect == ToolEffect.STATE_CHANGING
            ) {
                val expectsScreenChange = if (tc.name == "program") {
                    result.metrics["program_observation_changed"] == 1L ||
                        result.executionStatus != ToolExecutionStatus.SUCCEEDED
                } else (tc.name == DeviceToolNames.SHELL && result.executionStatus in
                    setOf(ToolExecutionStatus.INTERRUPTED, ToolExecutionStatus.CANCELED)) ||
                    observationFreshnessPolicy.requiresFrameAfterMutation(tc.name, tc.arguments, hadScreenObservation)
                if (expectsScreenChange) {
                    emptyDisplayObservation = null
                    currentObservationLease = null
                    terminalObservationRefVisibleToModel = null
                    toolExecutor.invalidateModelFrameBinding()
                    // A state-changing call may have moved to a different page before
                    // its settled frame arrived. Do not let the next model round reuse
                    // the pre-action screenshot. Tools without an automatic settled-frame
                    // capture (notably android_act) must invalidate the old envelope and
                    // force an explicit observation, including when their command receipt
                    // reports a launch/frame failure.
                    freshObservationRequired = true
                    if ((!toolExecutor.schedulesPostActionCapture(tc.name) &&
                        result.metrics["program_observation_available"] != 1L) ||
                        (tc.name == DeviceToolNames.SHELL && result.executionStatus in
                            setOf(ToolExecutionStatus.INTERRUPTED, ToolExecutionStatus.CANCELED))) {
                        forceFreshObservationCapture = true
                        toolExecutor.invalidatePendingObservation()
                    }
                }
            }
            completedToolCallCount += 1
            if (toolIdx == interventionProviderOrder) {
                interventionEntered = true
                interventionAccepted = result.isVerifiedSuccess
            }
            if (!interventionEntered && (isPaused() || toolDiagnosticCode == "TOOL_INTERRUPTED_FOR_HUMAN")) {
                for (remainingIndex in (toolIdx + 1) until toolCalls.size) {
                    val pending = selectedByProviderOrder[remainingIndex] ?: continue
                    settleDurableTool(pending, toolCalls[remainingIndex],
                        ToolProtocolResult.notExecuted("USER_CONTEXT_CHANGED", true))
                }
                waitForActiveExecution()
                takeManualResumeInstruction()?.let { instruction ->
                    resetAfterManualResume(instruction, "manual pause interrupted tool; discarded pending plan")
                }
                return ToolRoundOutcome.RestartRound
            }
            val localRejectionKey = when {
                planRejectionCode != null -> "plan:${planRejectionCode.lowercase()}"
                prematureTaskDone -> "completion_before_execution"
                blindActionBlockResult != null -> "blind_screen_action"
                staleObservationBlockResult != null -> "stale_observation"
                completionEvidenceBlockResult != null -> "completion_without_evidence"
                exactActionRepeatBlockResult != null -> "exact_action_repeated"
                else -> null
            }
            if (locallyRejectedResult != null && localRejectionKey != null) {
                settleLocallyRejectedToolCall(
                    toolCall = tc,
                    rejectionKey = localRejectionKey,
                    beforeObservationId = beforeActionObservationId,
                    countsAsNoProgress = true
                )
                if (staleObservationBlockResult != null) {
                    freshObservationRequired = true
                    forceFreshObservationCapture = true
                    toolExecutor.invalidatePendingObservation()
                }
            }
            if (recordRecoveryPending && toolSucceeded && tc.name == "workspace" &&
                !WorkspaceBrowsingProgress.project(eventStore.eventsForTurn(durableTurn.scope.turnId),
                    durableTurn.scope, workspaceContextPolicy).reviewDue(workspaceContextPolicy)) {
                recordRecoveryPending = false
                observationReplanContext.reset()
                observationRecoveryDirective?.let(messages::remove)
                observationRecoveryDirective = null
            }
            val recoveryProgress = if (recovery in setOf(ToolRecovery.PROTOCOL_REPAIR, ToolRecovery.OBSERVE))
                TaskExecutionProgress.project(eventStore.eventsForTurn(durableTurn.scope.turnId),
                    durableTurn.scope, recoveryPolicy.refreshAfterDiagnostics) else null
            if (recovery == ToolRecovery.PROTOCOL_REPAIR &&
                checkNotNull(recoveryProgress).repeatedProtocolRejections >= taskExecutionPolicy.maxRejectedCalls) {
                return ToolRoundOutcome.Terminal(groundedGuardStop(result.diagnosticCode))
            }
            if (recovery == ToolRecovery.OBSERVE &&
                checkNotNull(recoveryProgress).consecutiveObservationRecoveries >= taskExecutionPolicy.maxObservationRecoveries) {
                return ToolRoundOutcome.Terminal(groundedGuardStop("OBSERVATION_RECOVERY_BUDGET_EXHAUSTED"))
            }
            if (planRejectionCode != null) {
                // 参数协议错误不会执行设备动作；下一轮不能继续相信旧截图，
                // 否则模型会在同一帧上重复生成越界/过期坐标。
                if (planRejectionCode != com.droidance.core.agent.harness.RoundDiagnosticCode.QUERY_RESULT_REQUIRED &&
                    tc.name in ToolCatalog.screenDependentActionToolNames && hadScreenObservation
                ) {
                    currentObservationLease = null
                    toolExecutor.invalidateModelFrameBinding()
                    toolExecutor.invalidatePendingObservation()
                    freshObservationRequired = true
                    forceFreshObservationCapture = true
                }
                continue
            }
            if (coordinateProtocolFailure) {
                debugLogManager.appendEvent("coordinate bounds rejected; fresh observation required")
                currentObservationLease = null
                toolExecutor.invalidateModelFrameBinding()
                toolExecutor.invalidatePendingObservation()
                freshObservationRequired = true
                forceFreshObservationCapture = true
            }
            if (
                locallyRejectedResult == null &&
                toolDiagnosticCode == "TEXT_INPUT_DUPLICATE_VERIFIED"
            ) {
                // A duplicate means the requested value is already verified on this
                // semantic input destination.  It is a failed provider call only in the
                // narrow sense that no new characters were injected; letting the same
                // Agent see the unchanged frame without a recovery boundary causes it to
                // resend the value indefinitely. Freeze this transaction and force one
                // fresh observation; the normal counted Agent loop chooses what follows.
                debugLogManager.appendEvent(
                    "🛑 duplicate text input detected; freezing destination and requesting " +
                        "one fresh strategy replan"
                )
                currentObservationLease = null
                terminalObservationRefVisibleToModel = null
                toolExecutor.invalidateModelFrameBinding()
                toolExecutor.invalidatePendingObservation()
                freshObservationRequired = true
                forceFreshObservationCapture = true
                return ToolRoundOutcome.RestartRound
            }
            if (
                locallyRejectedResult == null &&
                toolDiagnosticCode == "TEXT_INPUT_TARGET_TRANSITIONED"
            ) {
                debugLogManager.appendEvent(
                    "↩️ text input focus landed on a fresh page; requesting one grounded target replan"
                )
                return ToolRoundOutcome.RestartRound
            }
            if (completionReviewGate.onToolSettled(selectedCall?.contract?.binding,
                selectedCall?.contract?.effect, result.executionStatus,
                result.stateChanged)) completionReviewContext.reset()
            if (rejectedCompletionResult != null && !CompletionReviewGate.isReview(toolDiagnosticCode)) {
                val repeatedCompletionRejection = completionRejectionCycleGuard.isRepeated(
                    toolName = tc.name,
                    completionStatus = jsonStringArg(tc.arguments, "status"),
                    claimedResults = control?.rawClaims,
                    claimedEvidenceRefs = ActionControlProtocol.completionEvidenceReferences(control),
                    claimedTerminalObservationRef = control?.terminalObservationRef,
                    availableEvidenceRefs = evidenceLedger.completionEvidenceReferences(),
                    visibleTerminalObservationRef = terminalObservationRefVisibleToModel,
                    rejectionResult = rejectedCompletionResult.outputText
                )
                if (repeatedCompletionRejection) {
                    debugLogManager.appendEvent(
                        "unchanged completion rejection closed fail-closed"
                    )
                    return ToolRoundOutcome.Terminal(completionProtocolUnconfirmed())
                }
                debugLogManager.appendEvent(
                    "ungrounded completion rejected; result returned to the normal model loop"
                )
            } else if (guardedRejectionResult != null) {
                debugLogManager.appendEvent(
                    "🛑 recoverable action rejection: key=${localRejectionKey ?: tc.name}"
                )
            }
            if (selfManagementTool) {
                val resultCard = toolExecutor.currentResultCard()
                if (
                    tc.name == "present_result_card" &&
                    hardBlockResult == null &&
                    ToolResultStatus.isSuccess(result) &&
                    resultCard != null
                ) {
                    // 结果卡已经是结构化的用户可见终态。继续请求模型只会让它重复提交
                    // 同一张卡，既浪费一轮又可能把已完成任务拖入无进展循环。
                    finalOutput = resultCard.toUserFacingText()
                    finalTerminal = TurnTerminal.DONE
                    break
                }
                if (tc.name == "done" && !prematureTaskDone && hardBlockResult == null) {
                    break
                }
                continue
            }
            if (hardBlockResult != null || toolNotExecuted) {
                continue
            }

            if (!toolNotExecuted && result.executionStatus == ToolExecutionStatus.SUCCEEDED) {
                llmTransientRetries = 0
                completionRepairDirective?.let(messages::remove)
                completionRepairDirective = null
            }
            executionToolCalls += 1
            if (tc.name in ToolCatalog.progressSensitiveToolNames) {
                groundedTextObservation = null
            }
            if (isScreenObservation(tc)) {
                latestScreenStateForReflector = result.outputText
                groundedTextObservation = result.outputText.takeIf { result.isVerifiedSuccess }
            }

            val recordedToolEvidence = evidenceRecorder.recordToolEvidence(
                taskGoal = taskGoal,
                toolCall = tc,
                result = result,
                blocked = toolNotExecuted,
                groundsCompletion = (UnifiedDeviceToolProtocol.operation(tc.name, tc.arguments) != "crop") &&
                    (completionGroundingByTool[tc.name] == true ||
                    UnifiedDeviceToolProtocol.isGroundedNativeContractReceipt(tc.name, result) ||
                    (tc.name == "program" && result.isVerifiedSuccess) ||
                    (tc.name == DeviceToolNames.ANDROID_ACT &&
                        UnifiedDeviceToolProtocol.operation(tc.name, tc.arguments) == "content" &&
                        result.isVerifiedSuccess)),
                beforeObservationId = beforeActionObservationId
            )
            val validation = recordedToolEvidence.validation
            if (!toolNotExecuted && isScreenObservation(tc)) {
                if (recordedToolEvidence.observation.sourceKind == EvidenceSourceKind.OBSERVED_STATE) {
                    completionGroundingGate.recordFreshGroundedObservation()
                }
            }
            val verifiedGoalProgress = validation?.goalProgress == true
            if (!isScreenObservation(tc)) {
                val outcome = when {
                    toolNotExecuted -> "not_executed"
                    verifiedGoalProgress -> "progress"
                    toolSucceeded -> validation?.progressState?.lowercase() ?: "done"
                    else -> "failed"
                }
                actionNarrative.addLast(
                    guardText(
                        "working_memory_action_line",
                        mapOf(
                            "round" to rounds.toString(),
                            "tool" to tc.name,
                            "args" to promptValuePreview(tc.arguments, 80),
                            "outcome" to outcome
                        )
                    )
                )
                while (actionNarrative.size > ACTION_NARRATIVE_LIMIT) actionNarrative.removeFirst()
            }
            if (!localKnowledge && observationFreshnessPolicy.shouldObserveAfter(
                toolName = tc.name,
                    arguments = tc.arguments,
                    validation = validation,
                    visionPerception = canAttachScreenshot,
                    toolSucceeded = toolSucceeded,
                    launchAccepted = ToolResultStatus.isLaunchAccepted(result),
                    postActionCaptureScheduled = toolExecutor.schedulesPostActionCapture(tc.name)
                )
            ) {
                val refreshedObservation = runCatching {
                    ensureExecutionStarted(DeviceToolNames.ANDROID_READ)
                    executeAuxiliaryObservation(
                        ToolCall(
                            "auto_observe_${tc.id}",
                            DeviceToolNames.ANDROID_READ,
                            """{"op":"screen"}"""
                        )
                    )
                }.getOrNull()
                if (refreshedObservation != null) {
                    if (refreshedObservation.diagnosticCode == "VIRTUAL_SPACE_EMPTY" &&
                        refreshedObservation.executionStatus == ToolExecutionStatus.FAILED) {
                        retainEmptyDisplayObservation(refreshedObservation)
                    }
                    val refreshedObservationUsable = refreshedObservation.isVerifiedSuccess
                    reusablePostActionObservation = refreshedObservation.outputText.takeIf { refreshedObservationUsable }
                    groundedTextObservation = refreshedObservation.outputText.takeIf { refreshedObservationUsable }
                    avoidRedundantObservationPromptForNextRound = refreshedObservationUsable
                    if (refreshedObservationUsable) {
                        emptyDisplayObservation = null
                        attachScreenStateObservation(refreshedObservation.outputText)
                        // The explicit recovery observation is already the fresh frame
                        // required by the state-changing launch. Consume its envelope now
                        // so the following model round cannot discard it and capture a
                        // second, potentially transitional frame.
                        toolExecutor.consumePendingObservation()?.let { observation ->
                            currentObservationLease = observation
                            toolExecutor.bindModelFrame(observation)
                        }
                        freshObservationRequired = false
                        forceFreshObservationCapture = false
                    }
                }
            }
            if (looksLikeRootUnavailable(result)) {
                channelUnavailableRepeats += 1
                val hint = guardText("channel_unavailable_hint")
                appendGuardFeedbackToRecentToolResult(hint)

                if (channelUnavailableRepeats >= channelUnavailableStopRepeats) {
                    return ToolRoundOutcome.Terminal(TurnResult(
                        hasToolCalls = totalToolCalls > 0,
                        toolNames = executedToolNames.toList(),
                        summary = guardText("channel_unavailable_stop"),
                        evidenceText = evidenceLedger.buildOperatorEvidenceText(),
                        operatorOutput = fullContent,
                        rounds = rounds,
                        failureReason = TurnFailureReason.CHANNEL_DOWN,
                        terminal = TurnTerminal.SYSTEM_ERROR
                    ))
                }
            } else if (result.outputText.isNotBlank()) {
                channelUnavailableRepeats = 0
            }

            }
            return ToolRoundOutcome.ContinueRound
        }

        val toolRoundOutcome = processToolCalls()
        // The committed round starts every selected call before dispatch. A normal
        // stop, replan or accepted completion may leave a suffix unvisited; record
        // that it was withheld before starting another round or completing the turn.
        // Exceptions and cancellation still belong to lifecycle recovery, where an
        // in-flight call cannot be assumed not to have executed.
        for (pending in roundPlan.selectedCalls) {
            if (pending.callId in settledSelectedCalls) continue
            val rejected = orderedSequence.rejection() ?: ToolProtocolResult.notExecuted(
                "ROUND_EXECUTION_STOPPED",
                retryable = toolRoundOutcome == ToolRoundOutcome.RestartRound,
                details = buildJsonObject {
                    put("reason", JsonPrimitive(when (toolRoundOutcome) {
                        is ToolRoundOutcome.Terminal -> toolRoundOutcome.result.stopDiagnostic
                            ?: toolRoundOutcome.result.failureReason.name
                        ToolRoundOutcome.RestartRound -> "REPLAN_REQUIRED"
                        ToolRoundOutcome.ContinueRound -> "ROUND_COMPLETED"
                    }))
                }
            )
            val pendingCall = toolCalls[pending.providerOrder]
            settleDurableTool(pending, pendingCall, rejected)
            messages.add(ChatMessage(role = Role.TOOL,
                content = rejected.outputText, toolCallId = pendingCall.id))
        }
        when (toolRoundOutcome) {
            ToolRoundOutcome.RestartRound -> return null
            is ToolRoundOutcome.Terminal -> return toolRoundOutcome.result
            ToolRoundOutcome.ContinueRound -> Unit
        }

        if (finalTerminal == TurnTerminal.DONE) {
            // A successful result card is already the model-authored user-visible terminal.
            return finishTurnResult()
        }

        // `done(results)` is itself the model-authored structured final reply. Once its
        // tool execution succeeds, completion must win over any planner follow-up from
        // the same round; no second terminal-summary request is allowed.
        if (toolExecutor.isCompletionRequested()) {
            val completionSummary = PublicAssistantText.sanitize(
                toolExecutor.completionSummary()
            ).trim()
            // Only this accepted structured body passed coverage and publication checks.
            val conclusion = completionSummary
            val requestedCompletionStatus = toolExecutor.completionStatus().trim().lowercase()
            if (!executionStarted) {
                finalOutput = conclusion
                finalTerminal = completionTerminal(requestedCompletionStatus, executionStarted = false)
                return finishTurnResult()
            }
            if (requestedCompletionStatus == "done" && !takeoverManaged && !hasGroundedExecution()) {
                toolExecutor.clearCompletionRequest()
                rejectUngroundedCompletionSignal(fullContent.ifBlank { "done" })
                return null
            }
            finalTerminal = completionTerminal(requestedCompletionStatus, executionStarted = true)
            finalOutput = conclusion
            return finishTurnResult()
        }

        if (roundPlan.followUp == RoundFollowUp.WAIT_FOR_USER && (interventionAccepted || isPaused())) {
            val earlyResume = if (!isPaused()) takeManualResumeInstruction() else null
            if (earlyResume != null) {
                resetAfterManualResume(earlyResume, "explicit resume arrived during handoff settlement")
                return null
            }
            if (!isPaused()) {
                // Only an accepted handoff can create a pause. Rejected receipts return
                // to the same Agent; actions excluded from this round stay excluded.
                onManualTakeoverNeeded(interventionReply ?: guardText("user_intervention_not_paused"))
            }
            if (!isPaused()) return modelProtocolFailure(guardText("user_intervention_not_paused"))
            waitForActiveExecution()
            val manualResumeInstruction = takeManualResumeInstruction()
                ?: return modelProtocolFailure(guardText("user_intervention_not_paused"))
        resetAfterManualResume(
                manualResumeInstruction,
                "user intervention resumed; discarded pre-pause actions and forced fresh observation"
            )
            return null
        }

        val terminalBackendDiagnostics = setOf(
            AttemptDiagnosticCode.BACKEND_UNAVAILABLE,
            AttemptDiagnosticCode.AIR_CHANNEL_RECOVERY_REQUIRED,
            AttemptDiagnosticCode.AIR_PAIRING_REQUIRED,
            AttemptDiagnosticCode.INPUT_PERMISSION_DENIED,
            AttemptDiagnosticCode.VIRTUAL_DISPLAY_UNSUPPORTED
        )
        val backendUnavailableRejections = roundPlan.rejectedCalls.filter {
            it.diagnosticCode in terminalBackendDiagnostics
        }
        if (
            roundPlan.selectedCalls.isEmpty() &&
            backendUnavailableRejections.isNotEmpty() &&
            backendUnavailableRejections.size == roundPlan.rejectedCalls.size
        ) {
            val diagnosticCode = backendUnavailableRejections.first().diagnosticCode
            if (diagnosticCode == AttemptDiagnosticCode.INPUT_PERMISSION_DENIED) {
                return groundedGuardStop(diagnosticCode)
            }
            val terminationClaim = when (diagnosticCode) {
                AttemptDiagnosticCode.AIR_CHANNEL_RECOVERY_REQUIRED ->
                    fallbackText("termination_air_channel_recovery_required")
                AttemptDiagnosticCode.AIR_PAIRING_REQUIRED ->
                    fallbackText("termination_air_pairing_required")
                AttemptDiagnosticCode.VIRTUAL_DISPLAY_UNSUPPORTED ->
                    fallbackText("termination_virtual_display_unsupported")
                else -> fallbackText("termination_channel_down")
            }
            // The execution channel, not the model protocol, ended this turn.
            return modelProtocolFailure(terminationClaim, failureReason = TurnFailureReason.CHANNEL_DOWN)
        }

        val protocolRejections = roundPlan.rejectedCalls.filter {
            it.diagnosticCode == com.droidance.core.agent.harness.RoundDiagnosticCode.UNKNOWN_TOOL ||
                it.diagnosticCode ==
                ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH
        }
        if (
            roundPlan.selectedCalls.isEmpty() &&
            protocolRejections.size == roundPlan.rejectedCalls.size &&
            protocolRejections.isNotEmpty()
        ) {
            debugLogManager.appendEvent(
                "rejected tool protocol results returned to the normal model loop"
            )
            return null
        }

    } else {
        val publicFinal = PublicAssistantText.sanitize(fullContent)

        // A tool-free reply remains conversational after a retry or user steer.
        if (!executionStarted && totalToolCalls == 0 && fullContent.isNotBlank()
        ) {
            streamFallbackContent(fullContent)
            return TurnResult(
                hasToolCalls = false,
                summary = fullContent.trim(),
                operatorOutput = fullContent.trim(),
                rounds = rounds,
                terminal = TurnTerminal.DIRECT_REPLY,
                takeoverManaged = takeoverManaged,
                contentStreamed = contentStreamed
            )
        }

        // Once tools have been attempted, all modes require structured completion.
        messages.add(ChatMessage(
            role = Role.ASSISTANT,
            content = publicFinal,
            reasoningContent = fullReasoning.takeIf { it.isNotBlank() }
        ))
        debugLogManager.appendEvent(
            "plain terminal omitted the required structured completion"
        )
        if (completionRejectionCycleGuard.rejectMissingSignal()) {
            return completionProtocolUnconfirmed()
        }
        if (completionRepairDirective == null) {
            completionRepairDirective = ChatMessage(
                role = Role.SYSTEM,
                content = ruleConfigManager.readMarkdownSection("completion_recovery.md", "missing_signal")
                    .also { require(it.isNotBlank()) }
            )
            messages.add(checkNotNull(completionRepairDirective))
        }
        // A completion-format repair is not output truncation. Keep its output budget.
        return null
    }

    return null
}
