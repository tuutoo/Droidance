// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.device.agent.device.DeviceBackend
import com.droidance.core.device.agent.device.SystemStateQuery
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive

interface ScreenActionService {
    suspend fun click(arguments: String): ToolExecutionResult
    suspend fun findAndClick(arguments: String): ToolExecutionResult
    suspend fun swipe(arguments: String): ToolExecutionResult
    suspend fun drag(arguments: String): ToolExecutionResult
    suspend fun path(arguments: String): ToolExecutionResult
    suspend fun scroll(arguments: String): ToolExecutionResult
    suspend fun typeText(arguments: String): ToolExecutionResult
    suspend fun pressKey(arguments: String): ToolExecutionResult
    suspend fun batch(arguments: String): ToolExecutionResult
}

/** Model-facing device primitives bound directly to explicit services. */
class UnifiedDeviceToolRegistrar(
    private val registerTool: (Tool) -> Unit,
    private val deviceBackend: DeviceBackend,
    private val readDevice: suspend () -> ToolExecutionResult,
    private val readApps: suspend (String) -> ToolExecutionResult,
    private val discoverCapabilities: suspend (String) -> ToolExecutionResult,
    private val readContent: suspend (String) -> ToolExecutionResult,
    private val readScreen: suspend () -> ToolExecutionResult,
    private val readCrop: suspend (String) -> ToolExecutionResult,
    private val openAndroid: suspend (String) -> ToolExecutionResult,
    private val scrollDirectionDescription: String,
    private val batchDescription: String,
    private val screenActions: ScreenActionService,
    private val androidReadDescription: String,
    private val androidActDescription: String,
    private val intentExtraContracts: List<com.droidance.core.agent.config.IntentExtraContract>,
    private val shellDescription: String,
    private val shellPolicy: com.droidance.core.agent.config.ShellExecutionPolicy,
    private val textInputClearDescription: String,
    private val screenActDescription: String,
    private val mutateContent: suspend (String) -> ToolExecutionResult = {
        ToolProtocolResult.notExecuted("NOT_IMPLEMENTED", false)
    }
) {
    fun registerAll() {
        registerAndroidRead()
        registerAndroidAct()
        registerShell()
        registerScreenAct()
    }

    private fun registerAndroidRead() = registerTool(object : Tool {
        override val name = DeviceToolNames.ANDROID_READ
        override val tier = ToolTier.CORE
        override val category = ToolCategory.PERCEPTION
        override val definition = ToolDefinition(
            name = name,
            description = androidReadDescription,
            parameters = linkedMapOf(
                "op" to ToolParameter("string", "Read operation.", true, listOf("device", "apps", "network", "capabilities", "system", "content", "clipboard", "screen", "crop")),
                "service" to ToolParameter("string", "System service name for op=system; omit to list services. No service arguments.", false),
                "line_filter" to ToolParameter("string", "Optional literal substring for selecting dump lines; filtered output is partial evidence.", false),
                "context_lines" to ToolParameter("integer", "For a system line_filter: surrounding lines per match, 0-40, default 0. Preserve object headers when interpreting fields.", false),
                "package_name" to ToolParameter("string", "Exact package filter for op=apps or op=capabilities.", false),
                "query" to ToolParameter("string", "App name/package search for op=apps; route catalog search for op=capabilities.", false),
                "offset" to ToolParameter("integer", "Pagination offset for op=apps or op=capabilities, default 0.", false),
                "intent_action" to ToolParameter("string", "Optional exact platform Intent action to resolve without launching, for op=capabilities.", false),
                "uri" to ToolParameter("string", "content:// URI for op=content, or Intent data URI for op=capabilities.", false),
                "mime_type" to ToolParameter("string", "Intent MIME type for op=capabilities.", false),
                "categories" to ToolParameter("array", "Intent categories for op=capabilities.", false,
                    items = ToolParameter("string", "Category name.", false)),
                "projection" to ToolParameter("array", "Columns for op=content.", false,
                    items = ToolParameter("string", "Column name.", false)),
                "selection" to ToolParameter("string", "Provider selection with ? placeholders.", false),
                "selection_args" to ToolParameter("array", "Provider selection values.", false,
                    items = ToolParameter("string", "Selection argument.", false)),
                "sort_order" to ToolParameter("string", "Provider sort order.", false),
                "limit" to ToolParameter("integer", "Maximum rows: op=apps uses its configured page size; op=content allows 1-100.", false),
                "left" to ToolParameter("integer", "Normalized crop left edge, 0-1000.", false),
                "source_ref" to ToolParameter("string", "For crop: exact retained observation ID or image content_ref. Omit for the latest retained original. Bounds belong to that image; explicit crop references allow nested transforms. Cropping never refreshes the screen. To click within a crop, pass its crop_ref as the click's coordinate_ref and local normalized x/y; a stale original is rejected.", false),
                "top" to ToolParameter("integer", "Normalized crop top edge, 0-1000.", false),
                "right" to ToolParameter("integer", "Normalized crop right edge, 0-1000.", false),
                "bottom" to ToolParameter("integer", "Normalized crop bottom edge, 0-1000.", false),
                "zoom" to ToolParameter("number", "Crop zoom factor, 1-4.", false)
            )
        )

        override suspend fun execute(arguments: String): ToolExecutionResult {
            val input = runCatching { Json.parseToJsonElement(arguments).jsonObject }
                .getOrElse { return ToolProtocolResult.invalidArguments() }
            val operation = UnifiedDeviceToolProtocol.operation(name, arguments)
                ?: return ToolProtocolResult.invalidArgument("op", "missing_or_unsupported")
            val payload = JsonObject(input.filterKeys { it != "op" }).toString()
            return when (operation) {
                "device" -> readDevice()
                "apps" -> readApps(payload)
                "network" -> readSystem(JsonObject(emptyMap()), SystemStateQuery(null,
                    com.droidance.core.device.agent.device.SystemQueryKind.WIFI_STATUS))
                "capabilities" -> discoverCapabilities(payload)
                "system" -> readSystem(input)
                "content" -> readContent(payload)
                "clipboard" -> deviceBackend.readClipboard()
                "screen" -> readScreen()
                "crop" -> readCrop(payload)
                else -> ToolProtocolResult.invalidArgument("op", "unsupported_value", operation)
            }
        }
    })

    private suspend fun readSystem(input: JsonObject, fixedQuery: SystemStateQuery? = null): ToolExecutionResult {
        val service = input["service"]?.jsonPrimitive?.contentOrNull
        val filter = input["line_filter"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (filter.length > 200) return ToolProtocolResult.invalidArgument("line_filter", "too_long")
        val contextLines = input["context_lines"]?.let {
            (it as? JsonPrimitive)?.intOrNull
                ?: return ToolProtocolResult.invalidArgument("context_lines", "expected_integer")
        } ?: 0
        if (contextLines !in 0..40 || (contextLines > 0 && filter.isBlank()))
            return ToolProtocolResult.invalidArgument("context_lines", "requires_filter_and_range_0_40")
        val request = runCatching {
            (fixedQuery ?: SystemStateQuery(service)).copy(lineFilter = filter, contextLines = contextLines)
        }.getOrElse { return ToolProtocolResult.invalidArgument("service", "invalid_identifier") }
        val executionDisplayId = deviceBackend.activeDisplayId
        val outcome = deviceBackend.querySystem(request)
        val selection = outcome.output
        val selected = selection.stdout
        val backendTruncated = selection.truncated
        val platformDiagnostic = selection.diagnosticCode ?: SystemQueryDiagnostic.code("", outcome.stderr)
        val diagnostic = outcome.diagnosticCode ?: platformDiagnostic
            ?: if (!outcome.success) "SYSTEM_QUERY_FAILED"
            else if (selection.originalChars == 0L || (filter.isBlank() && selected.isBlank())) "SYSTEM_QUERY_EMPTY"
            else if (selection.selectedLines == 0 || selected.isBlank()) "SYSTEM_QUERY_NO_MATCH" else null
        val output = buildJsonObject {
            put("source_kind", JsonPrimitive(if (request.kind == com.droidance.core.device.agent.device.SystemQueryKind.WIFI_STATUS)
                "android_wifi_status" else "android_system_service_dump"))
            put("scope", JsonPrimitive("device"))
            put("execution_display_id", executionDisplayId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
            put("backend", JsonPrimitive(outcome.backend))
            put("command", JsonPrimitive(request.command))
            put("captured_at_ms", JsonPrimitive(System.currentTimeMillis()))
            // Preserve the bounded backend receipt for durable, paged source reads.
            put("stdout", JsonPrimitive(selected))
            put("stderr", JsonPrimitive(outcome.stderr.take(2_000)))
            put("stdout_chars", JsonPrimitive(selection.originalChars))
            put("stdout_line_count", JsonPrimitive(selection.originalLines))
            put("selected_chars", JsonPrimitive(selection.selectedChars))
            put("filtered", JsonPrimitive(filter.isNotBlank()))
            put("line_filter", JsonPrimitive(filter))
            put("context_lines", JsonPrimitive(contextLines))
            put("selected_line_count", JsonPrimitive(selection.selectedLines))
            put("selection_stage", JsonPrimitive("before_bounded_transport"))
            put("stdout_truncated", JsonPrimitive(backendTruncated))
            put("backend_truncated", JsonPrimitive(backendTruncated))
            put("stderr_truncated", JsonPrimitive(outcome.stderr.length > 2_000))
            put("truncated", JsonPrimitive(outcome.stderr.length > 2_000 || backendTruncated))
            if (backendTruncated) {
                put("recovery", JsonPrimitive("This original query is truncated. Re-query op=system with the same service and a narrower literal line_filter; selection drains the original stream before transport limits. Use context_lines (0-40) to retain nearby object headers. Reading this saved receipt cannot recover omitted dump text. No match or an omitted field is not proof of absence."))
            }
        }.toString()
        return if (diagnostic == null) ToolProtocolResult.success(output)
        else ToolExecutionResult(output, com.droidance.core.agent.event.ToolExecutionStatus.FAILED,
            com.droidance.core.agent.event.ToolEvidenceStatus.UNKNOWN,
            diagnosticCode = diagnostic)
    }

    private fun registerAndroidAct() = registerTool(object : Tool {
        override val name = DeviceToolNames.ANDROID_ACT
        override val tier = ToolTier.CORE
        override val category = ToolCategory.NAVIGATION
        override val definition = ToolDefinition(
            name = name,
            description = androidActDescription,
            parameters = linkedMapOf(
                "op" to ToolParameter("string", "Native action operation.", true, listOf("open", "content", "keyboard"))
            ).apply {
                putAll(OpenAppToolSchema.parameters)
                put("effect_key", EffectCheckpointLedger.keyParameter)
                put("action", ToolParameter("string", "ContentProvider mutation for op=content only. For op=open use intent_action; put the user-facing operation label in action_title.", false,
                    listOf("insert", "update", "delete", "batch")))
                put("values", ToolParameter("object", "Key-value map of content values for op=content.", false))
                put("authority", ToolParameter("string", "Content provider authority for op=content batch operations.", false))
                put("operations", ToolParameter("array", "Batch operations array for op=content batch.", false,
                    items = ToolParameter("object", "Batch operation item.", false)))
                put("selection", ToolParameter("string", "Provider selection with ? placeholders for op=content.", false))
                put("selection_args", ToolParameter("array", "Provider selection values for op=content.", false,
                    items = ToolParameter("string", "Selection argument.", false)))
                put("visible", ToolParameter("boolean", "For op=keyboard: false hides an execution keyboard. Showing or switching the system keyboard is unavailable during background execution; use screen_act type_text instead.", false,
                    requiredWhen = mapOf("op" to listOf("keyboard"))))
            }
        )

        override suspend fun execute(arguments: String): ToolExecutionResult {
            val input = runCatching { Json.parseToJsonElement(arguments).jsonObject }
                .getOrElse { return ToolProtocolResult.invalidArguments() }
            val operation = UnifiedDeviceToolProtocol.operation(name, arguments)
                ?: return ToolProtocolResult.invalidArgument("op", "missing_or_unsupported")
            val payloadInput = JsonObject(input.filterKeys { it != "op" && it != "effect_key" })
            val payload = payloadInput.toString()
            return when (operation) {
                "open" -> OpenAppToolSchema.routeArgumentMismatch(payloadInput)
                    ?: intentExtraContracts.firstNotNullOfOrNull { it.rejection(payloadInput) }
                    ?: openAndroid(payload)
                "content" -> mutateContent(payload)
                "keyboard" -> {
                    if (input.keys.any { it !in setOf("op", "visible") })
                        return ToolProtocolResult.invalidArgument("keyboard", "unsupported_parameter")
                    val visible = (input["visible"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                        ?: return ToolProtocolResult.invalidArgument("visible", "expected_boolean")
                    val result = deviceBackend.setKeyboardVisible(visible)
                    if (result.success) ToolProtocolResult.accepted(result.message)
                    else ToolProtocolResult.failed(result.diagnosticCode ?: "NATIVE_KEYBOARD_UNAVAILABLE", false,
                        details = buildJsonObject { put("message", JsonPrimitive(result.message)) })
                }
                else -> ToolProtocolResult.invalidArgument("op", "unsupported_value", operation)
            }
        }
    })

    private fun registerShell() = registerTool(object : Tool {
        override val name = DeviceToolNames.SHELL
        override val tier = ToolTier.CORE
        override val category = ToolCategory.SYSTEM
        override val definition = ToolDefinition(
            name = name,
            description = shellDescription,
            parameters = linkedMapOf(
                "command" to ToolParameter("string", "Android sh command or multiline script; pipelines, redirection and loops are supported. Maximum ${shellPolicy.maxCommandChars} characters.", true),
                "timeout" to ToolParameter("integer", "Requested seconds; effective timeout is bounded by the remaining invocation budget. Default ${shellPolicy.defaultTimeoutMs / 1000}.", false),
                "expect_screen_change" to ToolParameter("boolean", "Default true. Set false for nonvisual work; this is an observation hint, not a permission or proof of no effects.", false)
            )
        )

        override suspend fun execute(arguments: String): ToolExecutionResult {
            val input = runCatching { Json.parseToJsonElement(arguments).jsonObject }
                .getOrElse { return ToolProtocolResult.invalidArguments() }
            shellPolicy.validate()
            if (input.keys.any { it !in setOf("command", "timeout", "expect_screen_change") })
                return ToolProtocolResult.invalidArgument("command", "unsupported_parameter")
            val command = (input["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return ToolProtocolResult.missingArgument("command")
            if (command.isBlank() || command.length > shellPolicy.maxCommandChars || '\u0000' in command)
                return ToolProtocolResult.invalidArgument("command", "invalid_command_size_or_bytes")
            if (input["expect_screen_change"] != null &&
                (input["expect_screen_change"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == null)
                return ToolProtocolResult.invalidArgument("expect_screen_change", "expected_boolean")
            val requested = if (input["timeout"] == null) shellPolicy.defaultTimeoutMs else {
                val value = (input["timeout"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
                if (value == null || value !in 1..(shellPolicy.maxTimeoutMs / 1000))
                    return ToolProtocolResult.invalidArgument("timeout", "outside_policy")
                value * 1000
            }
            val budget = kotlin.coroutines.coroutineContext[ToolExecutionBudget]
            val timeoutMs = minOf(requested, shellPolicy.maxTimeoutMs,
                budget?.remainingMs()?.minus(2500) ?: shellPolicy.maxTimeoutMs)
            if (timeoutMs <= 0) return ToolProtocolResult.notExecuted("TOOL_EXECUTION_BUDGET_EXHAUSTED", false)
            val executionDisplayId = deviceBackend.activeDisplayId
            val started = System.nanoTime()
            val outcome = deviceBackend.shell(command, timeoutMs)
            val status = if (outcome.backend == "none") com.droidance.core.agent.event.ToolExecutionStatus.NOT_EXECUTED
                else outcome.executionStatus
            val data = buildJsonObject {
                put("command", JsonPrimitive(command)); put("backend", JsonPrimitive(outcome.backend)); put("scope", JsonPrimitive("device"))
                put("execution_display_id", executionDisplayId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                put("execution_uid", outcome.executionUid?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                put("exit_code", JsonPrimitive(outcome.exitCode)); put("execution_status", JsonPrimitive(status.name))
                put("stdout", JsonPrimitive(outcome.stdout)); put("stderr", JsonPrimitive(outcome.stderr))
                put("stdout_truncated", JsonPrimitive(outcome.stdoutTruncated)); put("stderr_truncated", JsonPrimitive(outcome.stderrTruncated))
                put("stdout_bytes", JsonPrimitive(outcome.stdoutBytes)); put("stderr_bytes", JsonPrimitive(outcome.stderrBytes))
                put("effective_timeout_ms", JsonPrimitive(timeoutMs)); put("duration_ms", JsonPrimitive((System.nanoTime() - started) / 1000000))
            }
            val invocation = kotlin.coroutines.coroutineContext[ToolInvocation]?.callId
            val source = invocation?.let { "shell_" + java.util.UUID.nameUUIDFromBytes(it.toByteArray()).toString() }
            return ToolExecutionResult(data.toString(), status,
                if (status in setOf(com.droidance.core.agent.event.ToolExecutionStatus.INTERRUPTED, com.droidance.core.agent.event.ToolExecutionStatus.CANCELED))
                    com.droidance.core.agent.event.ToolEvidenceStatus.UNKNOWN else com.droidance.core.agent.event.ToolEvidenceStatus.NOT_REQUIRED,
                diagnosticCode = outcome.diagnosticCode ?: if (status == com.droidance.core.agent.event.ToolExecutionStatus.NOT_EXECUTED)
                    "BACKEND_CHANNEL_UNAVAILABLE" else if (outcome.exitCode != 0) "SHELL_EXIT_NONZERO" else null,
                data = data, sourceRef = source,
                metrics = mapOf("shell_execution_ms" to ((System.nanoTime() - started) / 1000000)),
                actionEvidence = com.droidance.core.agent.event.projectToolActionEvidence(
                    if (status == com.droidance.core.agent.event.ToolExecutionStatus.NOT_EXECUTED) false
                    else if (status in setOf(com.droidance.core.agent.event.ToolExecutionStatus.INTERRUPTED, com.droidance.core.agent.event.ToolExecutionStatus.CANCELED)) null else true,
                    null, null, null, null))
        }
    })

    private fun registerScreenAct() = registerTool(object : Tool {
        override val name = DeviceToolNames.SCREEN_ACT
        override val tier = ToolTier.CORE
        override val category = ToolCategory.COMPOUND
        override val definition = ToolDefinition(
            name = name,
            description = screenActDescription + "\n" + batchDescription,
            parameters = linkedMapOf(
                "actions" to ToolParameter("array", "Ordered screen primitives grounded in the current frame; use the same shape for one action or several.",
                    items = ToolParameter("object", "One screen primitive.", properties = ScreenActionBatchProtocol.parameters(textInputClearDescription), additionalProperties = false), minItems = 1),
                "effect_key" to EffectCheckpointLedger.keyParameter,
                "wait_for_frame_ms" to ToolParameter("integer", "Optional 0..5000 ms pause after the action group before its final frame. Omit normally; use only when a transition needs extra loading time.", false)
            )
        )

        override suspend fun execute(arguments: String): ToolExecutionResult {
            val input = runCatching { Json.parseToJsonElement(arguments).jsonObject }
                .getOrElse { return ToolProtocolResult.invalidArguments() }
            if (UnifiedDeviceToolProtocol.operation(name, arguments) != "batch")
                return ToolProtocolResult.invalidArgument("actions", "explicit_array_required")
            com.droidance.core.agent.harness.ToolArgumentSchemaValidator.mismatchDetail(definition.parameters, arguments)?.let {
                return ToolProtocolResult.invalidArgument("actions", it)
            }
            return screenActions.batch(input.toString())
        }
    })

}
