// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.runtime

import androidx.sqlite.db.SupportSQLiteDatabase
import com.droidance.backup.BackupArchive
import com.droidance.backup.BackupCipher
import com.droidance.backup.BackupContents
import com.droidance.backup.BackupManifest
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Backup and restore of the resident store. A backup carries the user's data and leaves
 * device-local state behind; a restore is staged first and swapped in when the owner next
 * starts, before any store opens, keeping the current store until the swap succeeds.
 */
internal object RuntimeBackup {
    private const val USER_FILES = "no_backup/droidance-data"
    private const val BLOBS = "files/agent-blobs"
    /** Logs and diagnostics describe this device's runs, not the user's data. */
    private val DEVICE_USER_FILES = setOf("$USER_FILES/debug", "$USER_FILES/diagnostics")
    private val PREFERENCES = setOf("droidance_app_takeover.xml", "droidance_token_usage.xml")
    private const val STAGED = "runtime-restore"
    private const val PREVIOUS = "runtime-previous"

    class Database(val name: String, val connection: SupportSQLiteDatabase)

    fun export(
        root: File,
        databases: List<Database>,
        conversations: (SupportSQLiteDatabase) -> Int,
        skills: Int,
        settings: ByteArray,
        settingsCount: Int,
        appVersion: String,
        target: OutputStream,
        password: CharArray
    ): BackupManifest {
        val work = File(root, "cache/backup-${java.util.UUID.randomUUID()}")
        check(work.mkdirs()) { "Cannot prepare the backup" }
        try {
            databases.forEach { database -> snapshot(database, File(root, "databases"), work) }
            val conversationCount = conversations(databases.first().connection)
            BackupCipher.encrypt(target, password).use { sealed ->
                val writer = BackupArchive.Writer(sealed)
                work.listFiles().orEmpty().sortedBy { it.name }.forEach { writer.add("${BackupArchive.STORE}databases/${it.name}", it) }
                val blobs = files(root, BLOBS)
                (blobs + files(root, USER_FILES).filter { path -> DEVICE_USER_FILES.none { path.startsWith("$it/") } } +
                    PREFERENCES.map { "shared_prefs/$it" }.filter { File(root, it).isFile } + RuntimeDataFormat.MANIFEST)
                    .forEach { path -> writer.add(BackupArchive.STORE + path, File(root, path)) }
                writer.add(BackupArchive.SETTINGS, settings)
                val memory = files(root, USER_FILES).count { path ->
                    path.endsWith(".md") && listOf("memory/", "global/", "agents/").any { path.startsWith("$USER_FILES/$it") }
                }
                return writer.finish(appVersion, RuntimeDataFormat.CURRENT, System.currentTimeMillis(),
                    BackupContents(conversationCount, blobs.size, skills, memory, settingsCount))
            }
        } finally { work.deleteRecursively() }
    }

    /** Copies a consistent database image: writers are held off while the files are read. */
    private fun snapshot(database: Database, directory: File, target: File) {
        val connection = database.connection
        connection.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        connection.beginTransaction()
        try {
            listOf("", "-wal").map { File(directory, database.name + it) }
                .filter { it.isFile && it.length() > 0 }
                .forEach { it.copyTo(File(target, it.name)) }
        } finally { connection.endTransaction() }
    }

    private fun files(root: File, path: String): List<String> {
        val directory = File(root, path)
        if (!directory.isDirectory) return emptyList()
        return directory.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
    }

    /** Decrypts and verifies a backup next to the store; returns its manifest and settings. */
    fun stage(storageDirectory: File, source: InputStream, password: CharArray): Pair<BackupManifest, ByteArray> {
        val partial = File(storageDirectory, "$STAGED.partial")
        val staged = File(storageDirectory, STAGED)
        check(!partial.exists() || partial.deleteRecursively()) { "Cannot clear the restore directory" }
        try {
            val manifest = BackupArchive.extract(BackupCipher.decrypt(source, password), partial)
            if (manifest.dataFormat > RuntimeDataFormat.CURRENT) throw IllegalStateException("BACKUP_NEWER")
            if (!File(partial, "store").isDirectory) throw BackupCipher.InvalidBackupException("BACKUP_CORRUPTED")
            val settings = File(partial, BackupArchive.SETTINGS).readBytes()
            check(!staged.exists() || staged.deleteRecursively()) { "Cannot clear the restore directory" }
            check(partial.renameTo(staged)) { "Cannot stage the restore" }
            return manifest to settings
        } finally { partial.deleteRecursively() }
    }

    /** Puts the previous store back when a swap was interrupted before it completed. */
    fun recover(storageDirectory: String) {
        val root = RuntimeStorageRoot.root(storageDirectory)
        val previous = File(storageDirectory, PREVIOUS)
        if (!previous.isDirectory) return
        if (!RuntimeStorageRoot.isInitialized(root)) {
            check(!root.exists() || root.deleteRecursively()) { "Cannot clear an incomplete restore" }
            check(previous.renameTo(root)) { "Cannot recover the previous store" }
        } else check(previous.deleteRecursively()) { "Cannot remove the previous store" }
    }

    /**
     * Swaps a staged backup in; on any failure the previous store is put back and the failure is
     * returned. Returns null when nothing was staged or the swap succeeded.
     */
    fun applyStaged(storageDirectory: String, format: RuntimeDataFormat = RuntimeDataFormat()): Throwable? {
        val staged = File(storageDirectory, STAGED)
        val store = File(staged, "store")
        if (!store.isDirectory) { staged.deleteRecursively(); return null }
        val root = RuntimeStorageRoot.root(storageDirectory)
        val previous = File(storageDirectory, PREVIOUS)
        check(root.renameTo(previous)) { "Cannot set the current store aside" }
        try {
            check(store.renameTo(root)) { "Cannot install the restored store" }
            carryDeviceState(previous, root)
            format.prepare(root)
            File(root, RuntimeStorageRoot.INITIALIZED).writeText("1")
        } catch (error: Throwable) {
            root.deleteRecursively()
            check(previous.renameTo(root)) { "Cannot recover the previous store" }
            staged.deleteRecursively()
            return error
        }
        previous.deleteRecursively()
        staged.deleteRecursively()
        return null
    }

    /** State that belongs to this device: connection and scheduler state, logs, other preferences. */
    private fun carryDeviceState(previous: File, root: File) {
        val restored = setOf(BLOBS, USER_FILES, RuntimeDataFormat.MANIFEST, RuntimeStorageRoot.INITIALIZED, "databases", "cache") +
            PREFERENCES.map { "shared_prefs/$it" }
        previous.walkTopDown()
            .onEnter { it == previous || it.relativeTo(previous).invariantSeparatorsPath.let { path ->
                path !in restored || DEVICE_USER_FILES.any { device -> device == path || device.startsWith("$path/") } } }
            .filter { it.isFile }
            .forEach { file ->
                val path = file.relativeTo(previous).invariantSeparatorsPath
                val device = DEVICE_USER_FILES.any { path.startsWith("$it/") }
                if (device || restored.none { path == it || path.startsWith("$it/") }) {
                    File(root, path).also { it.parentFile!!.mkdirs(); file.copyTo(it, overwrite = true) }
                }
            }
    }
}
