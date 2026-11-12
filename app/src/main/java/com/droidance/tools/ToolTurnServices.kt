// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.knowledge.memory.DurableMemoryApplyResult
import com.droidance.core.knowledge.memory.DurableMemoryUpdate
import com.droidance.core.agent.UserInteractionState
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.device.permission.PendingPermissionRequest
import com.droidance.core.device.permission.PermissionRequestOutcome
import javax.inject.Inject
import javax.inject.Singleton

fun interface ToolProgressPort {
    fun updateCurrentToolDetail(toolName: String, arguments: String)
}

fun interface ToolMemoryPort {
    suspend fun applyMemoryUpdates(updates: List<DurableMemoryUpdate>): Result<DurableMemoryApplyResult>
}

fun interface ToolHistoryPort {
    suspend fun read(arguments: String): com.droidance.core.agent.event.ToolExecutionResult
}

fun interface ToolCompletionPort {
    suspend fun record(candidate: CompletionResult, state: CompletionState): com.droidance.core.agent.event.ToolExecutionResult
}

interface ToolInteractionPort {
    suspend fun awaitHighRiskConfirmation(scope: TurnEventScope, callId: String, toolName: String): UserInteractionState
    fun requestManualAssistance(reason: String, instruction: String?)
    suspend fun beginRuntimePermissionRequest(request: PendingPermissionRequest)
    suspend fun resolveRuntimePermissionRequest(request: PendingPermissionRequest, outcome: PermissionRequestOutcome)
}

/** A task-scoped connection to its owner; TaskLifecycle owns context disposal after host release. */
@Singleton
class ToolTurnServices @Inject constructor() {
    class Binding internal constructor(
        val sessionContext: com.droidance.core.agent.TurnSessionContext,
        val progress: ToolProgressPort,
        val memory: ToolMemoryPort,
        val interaction: ToolInteractionPort,
        val history: ToolHistoryPort,
        val completion: ToolCompletionPort
    )

    @Volatile private var active: Binding? = null

    @Synchronized
    fun bind(sessionContext: com.droidance.core.agent.TurnSessionContext, progress: ToolProgressPort, memory: ToolMemoryPort, interaction: ToolInteractionPort, history: ToolHistoryPort, completion: ToolCompletionPort): Binding {
        check(active == null) { "Tool services already belong to an active turn" }
        return Binding(sessionContext, progress, memory, interaction, history, completion).also { active = it }
    }

    fun currentOrNull(): Binding? = active

    fun requireCurrent(): Binding = checkNotNull(active) { "No active turn owns tool services" }

    @Synchronized
    fun release(binding: Binding) {
        if (active === binding) {
            active = null
        }
    }
}
