// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.debug

import com.droidance.core.agent.event.AgentEventEnvelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Diagnostics safe to hand to developers. Only allow-listed structural fields leave the device:
 * event kinds, tool names, statuses, diagnostic codes, durations, sizes and model identifiers.
 * Free text (messages, tool arguments and output, screen content, memory) is never copied, and a
 * kept value must look like an identifier, so no field can carry user text by accident.
 */
object RedactedDiagnostics {
    private val SAFE_VALUES = setOf(
        "contractVersion", "status", "executionStatus", "evidenceStatus", "diagnosticCode",
        "terminalDiagnosticCode", "totalDurationMillis", "activeDurationMillis", "exclusiveDurationMillis",
        "cancellationOrigin", "name", "source", "binding", "effect", "state", "repairPurpose", "executionMode",
        "followUp", "publicMessageKind", "providerId", "modelId", "transportKind", "platformContractAccepted",
        "stateChanged", "role", "mimeType", "sizeBytes", "width", "height", "providerOrder", "previousEpoch",
        "currentEpoch", "delivery", "transition", "outcome", "kind"
    )
    private val SAFE_OBJECTS = setOf("result", "modelSessionIdentity", "actionEvidence", "validation")
    private val IDENTIFIER = Regex("[A-Za-z0-9_.:/+@-]{1,96}")
    private val json = Json { encodeDefaults = true }

    /** One line per event; threads, turns and calls become per-report aliases. */
    fun events(envelopes: List<AgentEventEnvelope>): List<JsonObject> {
        val aliases = mutableMapOf<String, String>()
        fun alias(prefix: String, id: String?) = id?.let { aliases.getOrPut("$prefix:$it") { "$prefix${aliases.count { (key, _) -> key.startsWith("$prefix:") } + 1}" } }
        val turnStarts = mutableMapOf<String, Long>()
        return envelopes.sortedBy { it.sequence }.map { event ->
            val start = event.turnId?.let { turnStarts.getOrPut(it) { event.occurredAt } } ?: event.occurredAt
            buildJsonObject {
                put("sequence", event.sequence)
                put("kind", event.eventKind.name)
                put("conversation", alias("C", event.threadId))
                alias("T", event.turnId)?.let { put("turn", it) }
                alias("K", event.callId)?.let { put("call", it) }
                put("offsetMs", event.occurredAt - start)
                val payload = runCatching { Json.parseToJsonElement(event.payloadJson) }.getOrNull()
                redact(payload)?.let { put("fields", it) }
            }
        }
    }

    /** Keeps allow-listed fields whose values are numbers, booleans or identifiers. */
    fun redact(element: JsonElement?): JsonObject? {
        if (element !is JsonObject) return null
        val kept = element.mapNotNull { (key, value) ->
            when {
                key in SAFE_OBJECTS -> redact(value)?.takeIf { it.isNotEmpty() }?.let { key to it }
                key == "metrics" && value is JsonObject -> JsonObject(value.filter { (name, number) ->
                    IDENTIFIER.matches(name) && number is JsonPrimitive && !number.isString
                }).takeIf { it.isNotEmpty() }?.let { key to it }
                key in SAFE_VALUES -> safeValue(value)?.let { key to it }
                else -> null
            }
        }.toMap()
        return JsonObject(kept)
    }

    private fun safeValue(value: JsonElement): JsonElement? = when {
        value is JsonNull -> null
        value is JsonPrimitive && !value.isString -> value
        value is JsonPrimitive && IDENTIFIER.matches(value.content) -> value
        value is JsonArray -> JsonArray(value.mapNotNull(::safeValue)).takeIf { it.isNotEmpty() }
        else -> null
    }

    private val ADDRESS = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?\b|\b(?:[0-9a-fA-F]{1,4}:){3,}[0-9a-fA-F:]+\b""")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
    private val URL = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")
    private val LONG_NUMBER = Regex("""\b\d{7,}\b""")
    private val USER_PATH = Regex("""/(?:storage/emulated|sdcard|data/user|data/data)/\S*""")
    private val LOG_TIME = Regex("""^\d{10,13}\s""")
    private val NON_ASCII_TEXT = Regex("""[^\x00-\x7F]+""")

    /** Code-authored log lines with addresses, accounts, paths and non-ASCII text removed. */
    fun maskLog(text: String): String = text.lineSequence().joinToString("\n") { line ->
        val time = LOG_TIME.find(line)?.value.orEmpty()
        time + line.removePrefix(time).replace(URL, "<url>").replace(EMAIL, "<email>").replace(ADDRESS, "<address>")
            .replace(USER_PATH, "<path>").replace(LONG_NUMBER, "<number>").replace(NON_ASCII_TEXT, "<text>")
            .take(400)
    }

    fun line(value: JsonObject): String = json.encodeToString(JsonObject.serializer(), value)
}
