// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import com.droidance.core.model.llm.ModelDiagnosticScope
import com.droidance.core.model.llm.TransportExchangeTrace
import com.droidance.core.model.llm.providerHttpException
import java.io.File
import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Retained transport evidence, never a parallel task-state or recovery store. */
internal class ProviderExchangeRecorder(private val root: File) {
    fun open(scope: ModelDiagnosticScope?, provider: String, model: String, request: JsonObject): TransportExchangeTrace {
        val requestId = UUID.randomUUID().toString()
        val scopeName = scope?.turnId?.let(::scopeDirectory) ?: "unscoped"
        val directory = File(root, "$scopeName/$requestId")
        try {
            pruneIfDue(root, keep = scopeName)
            if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create request diagnostics")
            val images = mutableListOf<JsonObject>()
            fun retain(value: JsonElement): JsonElement = when (value) {
                is JsonObject -> JsonObject(value.mapValues { retain(it.value) })
                is JsonArray -> JsonArray(value.map { retain(it) })
                is JsonPrimitive -> {
                    val raw = value.contentOrNull.orEmpty()
                    if (value.isString && raw.startsWith("data:image/") && raw.contains(";base64,")) {
                        val mime = raw.substringAfter("data:").substringBefore(';')
                        val bytes = Base64.getDecoder().decode(raw.substringAfter(";base64,"))
                        val hash = digest(bytes)
                        val extension = when (mime) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "bin" }
                        val path = "../images/$hash.$extension"
                        val file = File(directory, path)
                        file.parentFile!!.mkdirs()
                        if (!file.exists()) file.writeBytes(bytes)
                        images += buildJsonObject {
                            put("content_ref", "sha256:$hash"); put("path", path)
                            put("mime_type", mime); put("size_bytes", bytes.size)
                        }
                        JsonPrimitive("diagnostic-image:sha256:$hash")
                    } else value
                }
                else -> value
            }
            val retainedRequest = retain(request)
            File(directory, "request.json").writeText(buildJsonObject {
                put("schema_version", 1); put("request_id", requestId)
                put("thread_id", scope?.threadId); put("turn_id", scope?.turnId); put("round_id", scope?.roundId)
                put("provider", provider); put("model", model); put("started_at_ms", System.currentTimeMillis())
                put("body", retainedRequest); put("images", JsonArray(images.distinct()))
                put("tools_sha256", digest(request["tools"].toString().toByteArray()))
                val instructions = request["instructions"] ?: (request["messages"] as? JsonArray)?.firstOrNull()
                put("instructions_sha256", digest(instructions.toString().toByteArray()))
            }.toString())
            return object : TransportExchangeTrace {
                private val started = System.nanoTime()
                private var captureFailure: String? = null
                private var responseLines: BufferedWriter? = null
                private var finished = false
                private val timings = java.util.concurrent.ConcurrentHashMap<String, Long>()
                override fun timing(name: String, elapsedMs: Long) { timings[name] = elapsedMs }
                private fun record(action: () -> Unit) {
                    try { action() } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        captureFailure = error.javaClass.simpleName
                        failure(requestId, error)
                    }
                }
                override fun response(status: Int, requestId: String?, retryAfter: String?) = record {
                    File(directory, "response.json").writeText(buildJsonObject {
                        put("received_at_ms", System.currentTimeMillis()); put("http_status", status)
                        put("provider_request_id", requestId); put("retry_after", retryAfter)
                    }.toString())
                }
                override fun line(value: String) = record {
                    if (finished) return@record
                    val writer = responseLines ?: File(directory, "response.jsonl").bufferedWriter().also {
                        responseLines = it
                    }
                    writer.write(buildJsonObject {
                        put("received_at_ms", System.currentTimeMillis()); put("line", value)
                    }.toString())
                    writer.newLine()
                    // A complete SSE event remains exportable while the request is open.
                    // Reuse the writer instead of opening/closing a file for every stream line.
                    if (value.isEmpty()) writer.flush()
                }
                override fun finish(error: Throwable?) = record {
                    if (finished) return@record
                    finished = true
                    record { responseLines?.close() }
                    responseLines = null
                    File(directory, "completion.json").writeText(buildJsonObject {
                        put("finished_at_ms", System.currentTimeMillis())
                        put("duration_ms", (System.nanoTime() - started) / 1_000_000)
                        put("status", if (error == null) "completed" else if (error is CancellationException) "cancelled" else "failed")
                        put("capture_failure", captureFailure)
                        put("transport_timings_ms", JsonObject(timings.toSortedMap().mapValues { JsonPrimitive(it.value) }))
                        put("exception_type", error?.javaClass?.name)
                        error?.providerHttpException()?.let {
                            put("http_status", it.statusCode); put("provider_code", it.providerCode); put("error_category", it.category.name)
                        }
                        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
                        val causes = mutableListOf<JsonObject>()
                        var cause = error
                        while (cause != null && seen.add(cause)) {
                            val current = cause
                            causes += buildJsonObject {
                                put("type", current.javaClass.name)
                                put("stack", JsonArray(current.stackTrace.map { JsonPrimitive(it.toString()) }))
                            }
                            cause = current.cause
                        }
                        put("causes", JsonArray(causes))
                    }.toString())
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failure(requestId, error)
            return TransportExchangeTrace.None
        }
    }

    private fun failure(requestId: String, error: Exception) {
        // Diagnostics failure must not turn an otherwise successful model call into a failure.
        try {
            root.mkdirs()
            File(root, "recording-errors.jsonl").appendText(buildJsonObject {
                put("request_id", requestId); put("error", error.javaClass.name); put("occurred_at_ms", System.currentTimeMillis())
            }.toString() + "\n")
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
        }
    }

    companion object {
        /** Owner-side diagnostic snapshots and duplicate reclamation must not race file opens. */
        internal val imageFilesLock = Any()
        /** Retained exchanges contain prompts and screenshots; bound their age and total size. */
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
        private const val PRUNE_INTERVAL_MS = 10L * 60 * 1000
        @Volatile private var lastPruneAtMs = 0L

        /** Drops expired scope directories, then the oldest ones while over the size cap. */
        internal fun pruneIfDue(root: File, keep: String, now: Long = System.currentTimeMillis()) {
            if (now - lastPruneAtMs < PRUNE_INTERVAL_MS) return
            lastPruneAtMs = now
            try {
                val scopes = root.listFiles { file -> file.isDirectory && !Files.isSymbolicLink(file.toPath()) }
                    ?.sortedBy { it.lastModified() } ?: return
                val survivors = mutableListOf<File>()
                for (scope in scopes) {
                    if (scope.name != keep && now - scope.lastModified() > RETENTION_MS) scope.deleteRecursively()
                    else survivors += scope
                }
                var total = survivors.sumOf { directorySize(it) }
                for (scope in survivors) {
                    if (total <= MAX_TOTAL_BYTES) break
                    if (scope.name == keep) continue
                    total -= directorySize(scope)
                    scope.deleteRecursively()
                }
            } catch (_: Exception) {
                // Retention is best effort and must never fail a model call.
            }
        }

        private fun directorySize(directory: File): Long =
            directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        fun scopeDirectory(turnId: String): String = digest(turnId.toByteArray())
        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
