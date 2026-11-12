// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.UserInteractionState
import com.droidance.core.agent.config.ToolSafetyPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * Generic action confirmation and lexical command-risk hints.
 * Shell patterns are heuristics, not an execution sandbox or a proof of isolation.
 * The program worker has a separate OS identity and exposes only mediated capabilities.
 */
internal object ToolRiskPolicy {
    const val CONFIRMATION_REQUIRED = "HIGH_RISK_CONFIRMATION_REQUIRED"

    data class Decision(
        val allowed: Boolean,
        val reason: String = "",
        val requiresConfirmation: Boolean = false,
        val rejection: com.droidance.core.agent.event.ToolExecutionResult? = null
    )

    private val confirmations = HighRiskConfirmationStore()

    fun remainingConfirmationMs(scope: TurnEventScope, callId: String): Long? =
        confirmations.remainingMs(scope, callId)

    fun respondToConfirmation(scope: TurnEventScope, callId: String, allow: Boolean?): UserInteractionState? =
        confirmations.respond(scope, callId, allow)

    fun revoke(scope: TurnEventScope) = confirmations.revoke(scope)

    fun confirmationRejection(state: UserInteractionState) = ToolProtocolResult.notExecuted(
        code = when (state) {
            UserInteractionState.DENIED -> "HIGH_RISK_CONFIRMATION_DENIED"
            UserInteractionState.EXPIRED -> "HIGH_RISK_CONFIRMATION_EXPIRED"
            else -> error("Unexpected confirmation state: $state")
        },
        retryable = false
    )

    /**
     * Confirmation identity must be stable across processes and collision-resistant. Kotlin's
     * Int.hashCode is neither: two different destructive payloads can share a value, and the
     * value can change with runtime/platform details. Canonicalize JSON object keys first, then
     * bind the tool name and full payload to SHA-256.
     */
    private fun actionKey(toolName: String, arguments: String): String {
        val canonicalArguments = runCatching {
            canonicalize(Json.parseToJsonElement(arguments)).toString()
        }.getOrDefault(arguments)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$toolName\u0000$canonicalArguments".toByteArray(Charsets.UTF_8))
        return "$toolName:${digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }}"
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associate { (key, value) -> key to canonicalize(value) }
        )
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }

    private fun blockedOrConfirmed(
        scope: TurnEventScope, callId: String, toolName: String, arguments: String, reason: String
    ): Decision = when (val state = confirmations.consumeOrRequest(scope, callId, actionKey(toolName, arguments))) {
        UserInteractionState.RESOLVED -> Decision(allowed = true)
        UserInteractionState.PENDING -> blocked(reason, requiresConfirmation = true)
        else -> confirmationRejection(state).let { Decision(false, it.outputText, rejection = it) }
    }

    private fun blocked(reason: String, requiresConfirmation: Boolean = false): Decision {
        val rejection = ToolProtocolResult.notExecuted(
            code = if (requiresConfirmation) CONFIRMATION_REQUIRED else "TOOL_POLICY_BLOCKED",
            retryable = requiresConfirmation,
            details = buildJsonObject {
                put("reason", JsonPrimitive(reason))
                put("confirmation_required", JsonPrimitive(requiresConfirmation))
            }
        )
        return Decision(false, rejection.outputText, requiresConfirmation, rejection)
    }

    fun assess(
        toolName: String, arguments: String, policy: ToolSafetyPolicy, scope: TurnEventScope, callId: String
    ): Decision = when (toolName) {
        DeviceToolNames.SHELL -> assessShell(arguments, policy, scope, callId)
        in policy.confirmationTools -> blockedOrConfirmed(
            scope = scope,
            callId = callId,
            toolName = toolName,
            arguments = arguments,
            reason = "$toolName can affect external communication, personal data, or device state."
        )
        else -> Decision(allowed = true)
    }

    private fun assessShell(arguments: String, policy: ToolSafetyPolicy, scope: TurnEventScope, callId: String): Decision {
        val command = runCatching {
            Json.parseToJsonElement(arguments).jsonObject["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.getOrDefault("")
        if (command.isBlank()) return Decision(allowed = true)

        val normalized = command.trim().replace(Regex("""\s+"""), " ")
        val lower = normalized.lowercase()

        // UI-tree reads and simulated input are forbidden: perception goes through screenshots, and
        // input through screen_act on the bound display with its coordinate mapping and checks.
        if (policy.shellForbiddenPatterns.any { Regex(it).containsMatchIn(lower) }) {
            return blocked("UI-tree reads and simulated input are blocked by policy. Observe with screenshots and act through screen_act on the bound display.")
        }

        if (policy.shellDestructivePatterns.any { Regex(it).containsMatchIn(lower) }) {
            return blockedOrConfirmed(scope, callId, DeviceToolNames.SHELL, arguments,
                "shell blocked a privileged command that may modify the system, delete data, or terminate processes.")
        }

        return Decision(allowed = true)
    }

}
