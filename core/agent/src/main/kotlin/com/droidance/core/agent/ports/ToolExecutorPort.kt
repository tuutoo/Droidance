// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.ports

import com.droidance.core.agent.attempt.ExecutionContext
import com.droidance.core.agent.device.DeviceExecutionStrategy
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.agent.tools.ObservationEnvelope
import com.droidance.core.agent.tools.OperatorToolProfile
import com.droidance.core.agent.tools.ResultCard
import com.droidance.core.agent.tools.Tool

/** Execution, observation and completion receipts used by the production task coordinator. */
interface ToolExecutorPort {
    fun canOpenPublicLink(link: String): Boolean
    suspend fun capabilityContext(packageName: String?, appName: String?, inventoryPackageName: String?,
                                  inventoryQuery: String?, maxChars: Int): String
    suspend fun execute(toolCall: ToolCall): ToolExecutionResult
    suspend fun executeTurnLocal(toolCall: ToolCall): ToolExecutionResult
    /** Return the settled result, or null to dispatch this exact call once after approval. */
    suspend fun awaitToolConfirmation(toolCall: ToolCall, result: ToolExecutionResult): ToolExecutionResult? = result
    fun getOperatorToolDefinitions(
        mode: RuntimeMode?,
        executionSpace: ExecutionSpace?,
        profile: OperatorToolProfile = OperatorToolProfile.DEFAULT
    ): List<ToolDefinition>
    fun getOperatorTools(
        mode: RuntimeMode?,
        executionSpace: ExecutionSpace?,
        profile: OperatorToolProfile = OperatorToolProfile.DEFAULT
    ): List<Tool>
    fun beginFrozenExecutionContext(frozen: ExecutionContext, strategy: DeviceExecutionStrategy)
    fun screenVisualFingerprint(imagePng: ByteArray): Long?
    fun screenChangeSignature(imagePng: ByteArray): com.droidance.core.agent.ScreenChangeSignature?
    fun schedulesPostActionCapture(toolName: String): Boolean
    suspend fun stagePreparedObservation(frameBytes: ByteArray): ToolExecutionResult
    suspend fun awaitPendingScreenshot(timeoutMs: Long = 2000): Boolean
    fun currentPendingObservation(): ObservationEnvelope?
    fun consumePendingObservation(): ObservationEnvelope?
    fun bindModelFrame(observation: ObservationEnvelope)
    fun invalidateModelFrameBinding()
    fun invalidatePendingObservation()
    var pendingScreenStateText: String?
    val pendingScreenshotBase64: String?
    fun isCompletionRequested(): Boolean
    fun completionSummary(): String
    fun completionStatus(): String
    fun clearCompletionRequest()
    fun resetTextInputTransactions()
    fun currentResultCard(): ResultCard?
}
