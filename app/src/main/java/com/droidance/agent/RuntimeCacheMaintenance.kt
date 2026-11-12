// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import android.content.Context
import com.droidance.agent.blob.FileBlobStore
import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.blob.SHA256_HASH_REGEX
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.device.debug.DiagnosticsCollector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Singleton
class RuntimeCacheMaintenance @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: AgentEventStore,
    private val blobs: BlobStore,
    private val diagnostics: DiagnosticsCollector
) {
    /** Called after Turn resources close, while the owner still holds the single execution lane. */
    suspend fun afterTask() = withContext(NonCancellable + Dispatchers.IO) {
        try {
            val result = cleanRuntimeCaches(events, blobs, diagnostics.modelDiagnosticsDirectory(),
                context.cacheDir)
            android.util.Log.i("RuntimeCache", "Cleanup: orphan_blobs=${result.orphanBlobs}; " +
                "deduplicated_bytes=${result.deduplicatedBytes}; expired_export_bytes=${result.expiredExportBytes}; " +
                "abandoned_write_bytes=${result.abandonedWriteBytes}")
        } catch (error: Exception) {
            // Cache maintenance must not turn a settled task into a failed task.
            android.util.Log.w("RuntimeCache", "Cleanup deferred", error)
        } finally {
            (blobs as? FileBlobStore)?.clearEncodedCache()
        }
    }
}

internal data class RuntimeCachePolicy(
    val exportRetentionMs: Long = 24L * 60 * 60 * 1000,
    val maxExportBytes: Long = 256L * 1024 * 1024,
    // Protect a freshly generated archive and any active export, even while over the soft cap.
    val minExportAgeMs: Long = 60L * 60 * 1000
)

internal data class RuntimeCacheCleanup(val orphanBlobs: Int, val deduplicatedBytes: Long,
                                       val expiredExportBytes: Long, val abandonedWriteBytes: Long)

internal suspend fun cleanRuntimeCaches(
    events: AgentEventStore,
    blobs: BlobStore,
    modelRoot: File,
    cacheDirectory: File,
    now: Long = System.currentTimeMillis(),
    policy: RuntimeCachePolicy = RuntimeCachePolicy()
): RuntimeCacheCleanup {
    require(policy.minExportAgeMs > 0 && policy.exportRetentionMs >= policy.minExportAgeMs && policy.maxExportBytes > 0)
    // References include every retained conversation, knowledge evidence and open Turn. Never
    // collect against a partial task snapshot or from a parallel background coroutine.
    val referenced = events.allReferencedBlobRefs()
    var deduplicated = 0L
    if (blobs is FileBlobStore && !Files.isSymbolicLink(modelRoot.toPath())) {
        for (scope in modelRoot.listFiles().orEmpty()) {
            if (!scope.isDirectory || Files.isSymbolicLink(scope.toPath())) continue
            val images = File(scope, "images")
            if (Files.isSymbolicLink(images.toPath())) continue
            for (file in images.listFiles().orEmpty()) {
                val hash = file.name.substringBeforeLast('.')
                if (file.extension !in setOf("png", "jpg", "webp", "bin") ||
                    !SHA256_HASH_REGEX.matches(hash) || "sha256:$hash" !in referenced) continue
                // Failed verification leaves the copy intact. Export reconstructs verified copies
                // from their content references only while writing the archive.
                try { deduplicated += synchronized(com.droidance.debug.ProviderExchangeRecorder.imageFilesLock) {
                    blobs.discardVerifiedDuplicate("sha256:$hash", file)
                } }
                catch (_: java.io.IOException) { }
            }
        }
    }
    val orphanBlobs = deleteUnreferencedBlobs(blobs, referenced)
    val abandoned = (blobs as? FileBlobStore)?.removeAbandonedWrites() ?: 0L
    val expiredBytes = cleanExportCache(cacheDirectory, now, policy)
    return RuntimeCacheCleanup(orphanBlobs, deduplicated, expiredBytes, abandoned)
}

internal fun cleanExportCache(cacheDirectory: File, now: Long, policy: RuntimeCachePolicy): Long {
    val directory = File(cacheDirectory, "share")
    if (Files.isSymbolicLink(cacheDirectory.toPath()) || Files.isSymbolicLink(directory.toPath())) return 0L
    var removed = 0L
    val stagingDirectories = listOf(cacheDirectory to "runtime-export-", directory to "Droidance-debug-images-")
        .flatMap { (parent, prefix) -> parent.listFiles().orEmpty().filter {
            it.name.startsWith(prefix) && it.isDirectory && !Files.isSymbolicLink(it.toPath()) &&
                now - it.lastModified() >= policy.minExportAgeMs
        } }
    for (staging in stagingDirectories) {
        // A crashed export can leave staging behind. walkFileTree does not follow symbolic links.
        try {
            Files.walkFileTree(staging.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    if (attrs.isRegularFile) removed += attrs.size()
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: java.io.IOException) { }
    }
    val files = directory.listFiles().orEmpty().filter {
        Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            (it.name.startsWith("Droidance-debug-") || it.name.startsWith("Droidance-diagnostics-"))
    }
    fun remove(file: File): Boolean {
        val size = file.length()
        return file.delete().also { if (it) removed += size }
    }
    files.filter { it.name.endsWith(".zip.partial") && now - it.lastModified() >= policy.minExportAgeMs }
        .forEach { remove(it) }
    val archives = files.filter { it.name.endsWith(".zip") }.sortedBy { it.lastModified() }
    var total = archives.sumOf { it.length() }
    for (file in archives) {
        val age = now - file.lastModified()
        if (age < policy.minExportAgeMs) continue
        if (age >= policy.exportRetentionMs || total > policy.maxExportBytes) {
            val size = file.length()
            if (remove(file)) total -= size
        }
    }
    return removed
}
