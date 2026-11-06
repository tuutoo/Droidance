// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What a backup holds, written last so it can list every entry with its digest. */
@Serializable
data class BackupManifest(
    val backupFormat: Int,
    val appVersion: String,
    val dataFormat: Int,
    val createdAt: Long,
    val contents: BackupContents,
    val files: List<BackupFile>
)

/** Counts shown to the user after a backup or a restore. */
@Serializable
data class BackupContents(
    val conversations: Int,
    val attachments: Int,
    val skills: Int,
    val memoryFiles: Int,
    val settings: Int
)

@Serializable
data class BackupFile(val path: String, val size: Long, val sha256: String)

/**
 * The plaintext layout inside the encrypted stream: `store/` holds the resident store,
 * `settings.json` the application's own settings, and `manifest.json` closes the archive.
 */
object BackupArchive {
    const val FORMAT = 1
    const val STORE = "store/"
    const val SETTINGS = "settings.json"
    private const val MANIFEST = "manifest.json"
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    class Writer(output: OutputStream) {
        private val zip = ZipOutputStream(output)
        private val files = mutableListOf<BackupFile>()

        fun add(path: String, file: File) = file.inputStream().use { add(path, it) }

        fun add(path: String, bytes: ByteArray) = add(path, bytes.inputStream())

        private fun add(path: String, input: InputStream) {
            require(isSafe(path) && path != MANIFEST) { "Invalid backup path" }
            zip.putNextEntry(ZipEntry(path))
            val digest = MessageDigest.getInstance("SHA-256")
            val size = DigestInputStream(input, digest).copyTo(zip)
            zip.closeEntry()
            files += BackupFile(path, size, hex(digest.digest()))
        }

        fun finish(appVersion: String, dataFormat: Int, createdAt: Long, contents: BackupContents): BackupManifest {
            val manifest = BackupManifest(FORMAT, appVersion, dataFormat, createdAt, contents, files.toList())
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.close()
            return manifest
        }
    }

    /** Extracts into [target] and verifies every entry against the manifest. */
    fun extract(input: InputStream, target: File): BackupManifest {
        check(target.mkdirs() || target.isDirectory) { "Cannot create the restore directory" }
        val found = mutableMapOf<String, BackupFile>()
        var manifest: BackupManifest? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == MANIFEST) {
                    if (manifest != null) throw BackupCipher.InvalidBackupException("BACKUP_CORRUPTED")
                    manifest = try {
                        json.decodeFromString<BackupManifest>(zip.readBytes().toString(Charsets.UTF_8))
                    } catch (_: Exception) { throw BackupCipher.InvalidBackupException("BACKUP_CORRUPTED") }
                    continue
                }
                if (entry.isDirectory || !isSafe(entry.name) || entry.name in found) {
                    throw BackupCipher.InvalidBackupException("BACKUP_CORRUPTED")
                }
                val destination = File(target, entry.name)
                destination.parentFile!!.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                val size = destination.outputStream().use { DigestInputStream(zip, digest).copyTo(it) }
                found[entry.name] = BackupFile(entry.name, size, hex(digest.digest()))
            }
        }
        val result = manifest ?: throw BackupCipher.InvalidBackupException("BACKUP_CORRUPTED")
        if (result.backupFormat != FORMAT) throw BackupCipher.InvalidBackupException("BACKUP_UNSUPPORTED")
        if (result.files.associateBy { it.path } != found) throw BackupCipher.InvalidBackupException("BACKUP_CORRUPTED")
        return result
    }

    private fun isSafe(path: String): Boolean =
        (path == SETTINGS || path.startsWith(STORE)) && !path.startsWith("/") && !path.contains('\\') &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
