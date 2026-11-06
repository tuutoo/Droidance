// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.runtime

import java.io.File

/**
 * The persisted format of the resident store, recorded in its manifest and advanced only by
 * ordered migrations before any store is opened. A change to a database schema, an event or
 * document contract, or the store layout bumps [CURRENT] and adds the migration that rewrites
 * existing data; readers keep accepting only the current format.
 *
 * Each migration runs against a backup of the store. Content-addressed blobs and the cache are
 * excluded from the backup, so a migration must leave them unchanged. An interrupted or failed
 * migration restores the backup; data is never discarded to make a store open.
 */
internal class RuntimeDataFormat(
    private val current: Int = CURRENT,
    private val migrations: Map<Int, (File) -> Unit> = MIGRATIONS
) {
    class NewerFormatException(found: Int, supported: Int) :
        IllegalStateException("Runtime data format $found is newer than supported format $supported")

    fun prepare(root: File) {
        val workspace = File(root.parentFile, "runtime-migration")
        val backup = File(workspace, "backup")
        if (backup.isDirectory) restore(backup, root)
        check(!workspace.exists() || workspace.deleteRecursively()) { "Cannot clear the migration workspace" }
        val manifest = File(root, MANIFEST)
        // Stores created before the manifest existed use format 1.
        var version = if (manifest.isFile) manifest.readText().trim().toInt() else 1
        if (version > current) throw NewerFormatException(version, current)
        while (version < current) {
            val migration = requireNotNull(migrations[version]) { "Missing runtime data migration from $version" }
            val partial = File(workspace, "backup.partial")
            StoreTree.copy(root, partial, UNVERSIONED)
            check(partial.renameTo(backup)) { "Cannot seal the migration backup" }
            try {
                migration(root)
                write(manifest, version + 1)
            } catch (error: Throwable) {
                restore(backup, root)
                throw error
            }
            check(workspace.deleteRecursively()) { "Cannot remove the migration backup" }
            version++
        }
        if (!manifest.isFile) write(manifest, current)
    }

    private fun restore(backup: File, root: File) {
        StoreTree.clear(root, UNVERSIONED)
        StoreTree.copy(backup, root)
        check(backup.parentFile!!.deleteRecursively()) { "Cannot remove the migration backup" }
    }

    private fun write(manifest: File, version: Int) {
        val temporary = File(manifest.parentFile, "$MANIFEST.tmp")
        temporary.writeText(version.toString())
        check(temporary.renameTo(manifest)) { "Cannot update the runtime data format" }
    }

    companion object {
        const val CURRENT = 1
        const val MANIFEST = "data-format"
        private val MIGRATIONS: Map<Int, (File) -> Unit> = emptyMap()
        /** Paths a migration never rewrites and a backup therefore omits. */
        val UNVERSIONED = setOf("files/agent-blobs", "cache", "code_cache")
    }
}
