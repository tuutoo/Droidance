// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.blob.SHA256_HASH_REGEX
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Enumerates retained diagnostics without following symlinks or exporting provider settings. */
internal object DebugExportSources {
    /** The captured paths remain live until the archive has consumed every supplement. */
    suspend fun withSources(root: File, cacheDir: File, snapshot: List<AgentEventEnvelope>, blobs: BlobStore,
                            write: suspend (List<DebugArchiveWriter.Supplement>) -> Unit) {
        var staged: File? = null
        fun stagingFile(name: String): File {
            val directory = staged ?: Files.createTempDirectory(
                File(cacheDir, "share").apply { mkdirs() }.toPath(), "Droidance-debug-images-"
            ).toFile().also { staged = it }
            return File.createTempFile("image-", ".${name.substringAfterLast('.')}", directory)
        }
        val detached = root.parentFile?.canonicalFile == cacheDir.canonicalFile && root.name.startsWith("runtime-export-")
        try {
            // Remote exports already own a detached file snapshot. For an owner-local export,
            // pin image copies before releasing the same lock used by end-of-task reclamation.
            val sources = synchronized(ProviderExchangeRecorder.imageFilesLock) {
                collect(root, snapshot).map { source ->
                    if (!detached && source.path.startsWith("diagnostics/model/") && source.path.contains("/images/")) {
                        val copy = stagingFile(source.source.name)
                        source.source.copyTo(copy, overwrite = true)
                        DebugArchiveWriter.Supplement(source.path, copy)
                    } else source
                }.toMutableList()
            }
            val included = sources.mapTo(mutableSetOf()) { it.path }
            for (request in sources.toList().filter {
                it.path.startsWith("diagnostics/model/") && it.path.endsWith("/request.json")
            }) {
                val images = runCatching { Json.parseToJsonElement(request.source.readText()).jsonObject["images"] as? JsonArray }
                    .getOrNull().orEmpty()
                val scopePath = request.path.substringBeforeLast('/').substringBeforeLast('/')
                for (value in images) {
                    currentCoroutineContext().ensureActive()
                    val image = value as? JsonObject ?: continue
                    val ref = (image["content_ref"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val hash = ref.removePrefix("sha256:")
                    if (ref != "sha256:$hash" || !SHA256_HASH_REGEX.matches(hash)) continue
                    val relative = (image["path"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val name = relative.removePrefix("../images/")
                    if (relative != "../images/$name" || name.substringBeforeLast('.') != hash ||
                        name.substringAfterLast('.') !in setOf("png", "jpg", "webp", "bin")) continue
                    val path = "$scopePath/images/$name"
                    if (!included.add(path)) continue
                    val file = stagingFile(name)
                    file.outputStream().use { blobs.copyTo(ref, it) }
                    sources += DebugArchiveWriter.Supplement(path, file)
                }
            }
            write(sources)
        } finally {
            staged?.deleteRecursively()
            if (detached) {
                root.deleteRecursively()
            }
        }
    }

    fun collect(root: File, snapshot: List<AgentEventEnvelope>): List<DebugArchiveWriter.Supplement> = buildList {
        val retainedScopes = snapshot.mapNotNull { it.turnId }.toSet().map(ProviderExchangeRecorder::scopeDirectory).toSet()
        addAll(files(File(root, "diagnostics"), "diagnostics").filter { file ->
            val scope = file.path.removePrefix("diagnostics/model/").substringBefore('/')
            !file.path.startsWith("diagnostics/model/") || scope == "unscoped" ||
                scope == "recording-errors.jsonl" || scope in retainedScopes
        })
        addAll(files(File(root, "debug"), "logs"))
        listOf("global", "rules", "skills").forEach { directory ->
            addAll(files(File(root, directory), "current/$directory", markdownOnly = true))
        }
    }

    private fun files(root: File, prefix: String, markdownOnly: Boolean = false): List<DebugArchiveWriter.Supplement> {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return emptyList()
        val canonicalRoot = root.canonicalFile
        return root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter { file ->
            file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                file.canonicalFile.toPath().startsWith(canonicalRoot.toPath()) &&
                (!markdownOnly || file.extension == "md")
        }.map { file ->
            DebugArchiveWriter.Supplement("$prefix/${file.relativeTo(root).invariantSeparatorsPath}", file)
        }.sortedBy { it.path }.toList()
    }
}
