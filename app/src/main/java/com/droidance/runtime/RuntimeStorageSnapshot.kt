// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One initial transfer of the existing private storage into its resident owner. */
internal object RuntimeStorageSnapshot {
    private val directories = listOf("files", "no_backup", "shared_prefs", "databases")
    private const val PROVIDER_BOOTSTRAP = "runtime-provider-bootstrap.json"
    private const val MAX_PROVIDER_BYTES = 4 * 1024 * 1024
    private val excludedFiles = setOf(
        "files/air_key", "files/air_key.pub", "files/debug_intent_token",
        "shared_prefs/droidance_providers.xml", "shared_prefs/droidance_providers.xml.bak"
    )

    @Synchronized
    fun installationId(context: Context): String {
        val file = File(context.noBackupFilesDir, "runtime-installation-id")
        if (!file.isFile) {
            file.parentFile?.mkdirs()
            check(file.createNewFile())
            file.writeText(java.util.UUID.randomUUID().toString())
        }
        return java.util.UUID.fromString(file.readText().trim()).toString()
    }

    fun create(context: Context): ParcelFileDescriptor {
        val temporary = File.createTempFile("runtime-storage-", ".zip", context.cacheDir)
        try {
            val root = File(context.applicationInfo.dataDir)
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                val provider = Json.encodeToString(RuntimeProviderSnapshot.capture(context)).toByteArray(Charsets.UTF_8)
                require(provider.size <= MAX_PROVIDER_BYTES) { "Provider configuration exceeds the transfer limit" }
                zip.putNextEntry(ZipEntry(PROVIDER_BOOTSTRAP))
                zip.write(provider)
                zip.closeEntry()
                for (name in directories) {
                    val directory = File(root, name)
                    if (!directory.isDirectory) continue
                    directory.walkTopDown().filter { it.isFile }.forEach { file ->
                        val path = file.relativeTo(root).invariantSeparatorsPath
                        if (path in excludedFiles) return@forEach
                        zip.putNextEntry(ZipEntry(path))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            return ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally { temporary.delete() }
    }

    fun managedFiles(context: Context): ParcelFileDescriptor = synchronized(com.droidance.debug.ProviderExchangeRecorder.imageFilesLock) {
        val temporary = File.createTempFile("runtime-files-", ".zip", context.cacheDir)
        try {
            val root = File(context.noBackupFilesDir, "droidance-data")
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                root.walkTopDown().filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally { temporary.delete() }
    }

    fun restore(root: File, source: ParcelFileDescriptor, restoreFiles: Boolean): RuntimeProviderSnapshot {
        check(root.mkdirs() || root.isDirectory)
        android.system.Os.chmod(root.path, 448) // 0700; only the resident shell owner.
        var total = 0L
        return ZipInputStream(ParcelFileDescriptor.AutoCloseInputStream(source).buffered()).use { zip ->
            val bootstrap = requireNotNull(zip.nextEntry) { "Missing runtime provider bootstrap" }
            require(!bootstrap.isDirectory && bootstrap.name == PROVIDER_BOOTSTRAP) { "Invalid runtime provider bootstrap" }
            val data = ByteArrayOutputStream()
            val providerBuffer = ByteArray(8192)
            while (true) {
                val count = zip.read(providerBuffer)
                if (count < 0) break
                require(data.size() + count <= MAX_PROVIDER_BYTES) { "Provider configuration exceeds the transfer limit" }
                data.write(providerBuffer, 0, count)
            }
            val provider = try { Json.decodeFromString<RuntimeProviderSnapshot>(data.toString(Charsets.UTF_8.name())) }
            catch (_: Exception) { error("Invalid runtime provider bootstrap") }
            zip.closeEntry()
            if (!restoreFiles) return@use provider
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name.substringBefore('/') in directories)
                val destination = File(root, entry.name).canonicalFile
                require(destination.path.startsWith(root.canonicalPath + File.separator))
                if (entry.name in excludedFiles) { zip.closeEntry(); continue }
                destination.parentFile!!.mkdirs()
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 512L * 1024 * 1024) { "Runtime storage exceeds the transfer limit" }
                        output.write(buffer, 0, count)
                    }
                }
                android.system.Os.chmod(destination.path, 384) // 0600
                zip.closeEntry()
            }
            provider
        }
    }

    /** Remove credential copies from this installation's old owner without deleting task data. */
    fun removeCredentialCopies(context: Context, root: File) {
        val prefs = context.getSharedPreferences("droidance_providers", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        listOf("provider_configs", "provider_configs_backup", "provider_configs_unreadable_backup").forEach(editor::remove)
        check(editor.commit()) { "Cannot remove the previous runtime provider configuration" }
        val providerFile = File(root, "shared_prefs/droidance_providers.xml")
        if (prefs.all.isEmpty() && providerFile.exists()) check(providerFile.delete())
        listOf("shared_prefs/droidance_providers.xml.bak", "files/air_key", "files/air_key.pub", "files/debug_intent_token")
            .forEach { path -> File(root, path).let { if (it.exists()) check(it.delete()) } }
    }
}
