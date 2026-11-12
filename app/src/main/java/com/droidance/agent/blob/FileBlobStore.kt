// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.blob

import com.droidance.core.agent.blob.*

import com.droidance.agent.blob.*

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

class FileBlobStore(rootDirectory: File, private val encodedCacheMaxBytes: Long = 24L * 1024 * 1024) : BlobStore {
    private val encodedCache = LinkedHashMap<String, String>(16, 0.75f, true)
    private var encodedCacheBytes = 0L

    init { require(encodedCacheMaxBytes >= 0) }

    fun clearEncodedCache() = synchronized(encodedCache) {
        encodedCache.clear()
        encodedCacheBytes = 0L
    }

    /** No writer may be active; these names never represent committed content references. */
    internal fun removeAbandonedWrites(): Long = Files.list(blobDirectory).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) &&
            it.fileName.toString().let { name -> name.startsWith(".pending-") && name.endsWith(".blob") }
        }.mapToLong { path -> val size = Files.size(path); if (Files.deleteIfExists(path)) size else 0L }.sum()
    }

    /** Discard only an exact diagnostic copy; export can reconstruct it from the retained blob. */
    internal fun discardVerifiedDuplicate(contentRef: String, duplicate: File): Long {
        val hash = hashFromContentRef(contentRef)
        val source = targetFor(hash)
        val destination = duplicate.toPath()
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) return 0L
        if (Files.isSameFile(source, destination)) return 0L
        val size = Files.size(source)
        val metadata = BlobMetadata(contentRef, hash, size)
        verify(source, metadata)
        verify(destination, metadata)
        return if (Files.deleteIfExists(destination)) size else 0L
    }

    override fun readBase64(contentRef: String, maxBytes: Long): String {
        // Keep read limits, missing-file detection and SHA-256 verification on every read.
        // Only the derived encoding is reused; no filesystem freshness assumptions.
        val bytes = read(contentRef, maxBytes)
        return synchronized(encodedCache) {
            encodedCache[contentRef] ?: java.util.Base64.getEncoder().encodeToString(bytes).also { encoded ->
                val weight = encoded.length.toLong() * 2
                if (weight <= encodedCacheMaxBytes) {
                    while (encodedCacheBytes + weight > encodedCacheMaxBytes && encodedCache.isNotEmpty()) {
                        val oldest = encodedCache.entries.iterator()
                        encodedCacheBytes -= oldest.next().value.length.toLong() * 2
                        oldest.remove()
                    }
                    encodedCache[contentRef] = encoded
                    encodedCacheBytes += weight
                }
            }
        }
    }

    private val blobDirectory: Path = prepareBlobDirectory(rootDirectory)

    override fun put(bytes: ByteArray): BlobMetadata {
        val snapshot = bytes.copyOf()
        val contentHash = sha256(snapshot)
        val contentRef = "$SHA256_REF_PREFIX$contentHash"
        val metadata = BlobMetadata(
            contentRef = contentRef,
            contentHash = contentHash,
            sizeBytes = snapshot.size.toLong()
        )
        val target = targetFor(contentHash)

        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verify(target, metadata)
            return metadata
        }

        val pending = Files.createTempFile(blobDirectory, ".pending-", ".blob")
        try {
            FileOutputStream(pending.toFile()).use { output ->
                output.write(snapshot)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: FileAlreadyExistsException) {
                // A concurrent writer committed the same content-addressed blob.
            } catch (error: AtomicMoveNotSupportedException) {
                throw BlobStoreException(
                    "Blob storage filesystem does not support atomic moves",
                    error
                )
            }
            verify(target, metadata)
            return metadata
        } finally {
            Files.deleteIfExists(pending)
        }
    }

    override fun read(contentRef: String, maxBytes: Long): ByteArray {
        require(maxBytes in 0L..Int.MAX_VALUE.toLong()) {
            "Blob maxBytes must be between 0 and Int.MAX_VALUE"
        }
        val contentHash = hashFromContentRef(contentRef)
        val target = targetFor(contentHash)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw BlobNotFoundException(contentRef)
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw BlobIntegrityException(contentRef, "stored path is not a regular file")
        }
        val sizeBytes = Files.size(target)
        if (sizeBytes > maxBytes) {
            throw BlobReadLimitExceededException(contentRef, sizeBytes, maxBytes)
        }
        val bytes = readExactBytes(target, sizeBytes.toInt(), contentRef)
        val actualHash = sha256(bytes)
        if (actualHash != contentHash) {
            throw BlobIntegrityException(
                contentRef,
                "expected hash $contentHash but found $actualHash"
            )
        }
        return bytes
    }

    override fun delete(contentRef: String): Boolean {
        val contentHash = hashFromContentRef(contentRef)
        val target = targetFor(contentHash)
        synchronized(encodedCache) {
            encodedCache.remove(contentRef)?.let { encodedCacheBytes -= it.length.toLong() * 2 }
        }
        return Files.deleteIfExists(target)
    }

    override fun copyTo(contentRef: String, output: java.io.OutputStream): Long {
        val hash = hashFromContentRef(contentRef)
        val target = targetFor(hash)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw BlobNotFoundException(contentRef)
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw BlobIntegrityException(contentRef, "stored path is not a regular file")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newInputStream(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
                total += count
            }
        }
        if (digest.digest().toLowerHex() != hash) throw BlobIntegrityException(contentRef, "content hash mismatch")
        return total
    }

    override fun listAllRefs(): Set<String> {
        if (!Files.exists(blobDirectory)) return emptySet()
        return Files.list(blobDirectory).use { stream ->
            stream
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) &&
                    it.fileName.toString().let { name ->
                        name.endsWith(".blob") && SHA256_HASH_REGEX.matches(name.removeSuffix(".blob"))
                    } }
                .map { "$SHA256_REF_PREFIX${it.fileName.toString().removeSuffix(".blob")}" }
                .iterator().asSequence().toSet()
        }
    }

    private fun readExactBytes(target: Path, sizeBytes: Int, contentRef: String): ByteArray {
        val bytes = ByteArray(sizeBytes)
        Files.newInputStream(
            target,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
        ).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) {
                    throw BlobIntegrityException(
                        contentRef,
                        "stored file was truncated while being read"
                    )
                }
                offset += count
            }
            if (input.read() >= 0) {
                throw BlobIntegrityException(
                    contentRef,
                    "stored file size changed while being read"
                )
            }
        }
        return bytes
    }

    private fun verify(target: Path, metadata: BlobMetadata) {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw BlobIntegrityException(metadata.contentRef, "stored path is not a regular file")
        }
        val actualSize = Files.size(target)
        if (actualSize != metadata.sizeBytes) {
            throw BlobIntegrityException(
                metadata.contentRef,
                "expected ${metadata.sizeBytes} bytes but found $actualSize"
            )
        }
        val actualHash = Files.newInputStream(
            target,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
        ).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().toLowerHex()
        }
        if (actualHash != metadata.contentHash) {
            throw BlobIntegrityException(
                metadata.contentRef,
                "expected hash ${metadata.contentHash} but found $actualHash"
            )
        }
    }

    private fun targetFor(contentHash: String): Path {
        require(SHA256_HASH_REGEX.matches(contentHash)) { "Invalid blob content hash" }
        val target = blobDirectory.resolve("$contentHash.blob").normalize()
        check(target.parent == blobDirectory) { "Blob path escaped its storage directory" }
        return target
    }

    private companion object {
        fun prepareBlobDirectory(rootDirectory: File): Path {
            val requestedRoot = rootDirectory.toPath().toAbsolutePath().normalize()
            Files.createDirectories(requestedRoot)
            val realRoot = requestedRoot.toRealPath()
            val requestedBlobDirectory = realRoot.resolve("sha256")
            Files.createDirectories(requestedBlobDirectory)
            val realBlobDirectory = requestedBlobDirectory.toRealPath()
            if (!realBlobDirectory.startsWith(realRoot) ||
                !Files.isDirectory(realBlobDirectory, LinkOption.NOFOLLOW_LINKS)
            ) {
                throw BlobStoreException("Blob storage directory escapes its configured root")
            }
            return realBlobDirectory
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .toLowerHex()

        fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
