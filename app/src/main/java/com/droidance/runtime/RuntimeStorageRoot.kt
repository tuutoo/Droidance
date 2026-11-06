// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.runtime

import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/**
 * The resident owner's store lives in the application's external files directory, which the
 * system removes with the package and with its data. Earlier builds kept it in the shared shell
 * directory, which survives both; this object moves that store once and removes the leftovers.
 */
internal object RuntimeStorageRoot {
    const val INITIALIZED = "initialized"
    private val LEGACY_PARENT = File("/data/local/tmp")
    private const val LEGACY_PREFIX = "droidance-runtime-"
    private const val STAGING = "runtime.staging"
    private val RUNTIME_RECEIVER = com.droidance.agent.device.RuntimeDriverBinderReceiver::class.java.name

    fun root(storageDirectory: String): File = File(storageDirectory, "runtime")

    fun isInitialized(root: File): Boolean = File(root, INITIALIZED).isFile

    /**
     * Leaves an initialized store at [root] when one exists here or in the legacy location.
     * A store without its marker never finished its initial transfer and holds nothing authoritative.
     */
    fun prepare(storageDirectory: String, clientUid: Int, installationId: String, legacyParent: File = LEGACY_PARENT) {
        val root = root(storageDirectory)
        if (isInitialized(root)) return
        val staging = File(storageDirectory, STAGING)
        check(!staging.exists() || staging.deleteRecursively()) { "Cannot clear the runtime staging store" }
        check(!root.exists() || root.deleteRecursively()) { "Cannot clear an incomplete runtime store" }
        val legacy = File(legacyParent, "$LEGACY_PREFIX$clientUid-$installationId")
        if (!isInitialized(legacy)) return
        StoreTree.copy(legacy, staging)
        check(StoreTree.digest(legacy) == StoreTree.digest(staging)) { "Runtime store copy does not match its source" }
        check(staging.renameTo(root)) { "Cannot install the moved runtime store" }
    }

    /**
     * Removes legacy stores of this application and of Droidance installations that no longer exist.
     * A store whose uid still belongs to an installed Droidance build stays for that build to move.
     */
    fun removeLegacyStores(packageManager: PackageManager, clientUid: Int) =
        removeLegacyStores(clientUid, LEGACY_PARENT) { uid -> hostsRuntime(packageManager, uid) }

    fun removeLegacyStores(clientUid: Int, legacyParent: File, hostsRuntime: (Int) -> Boolean) {
        val stores = legacyParent.listFiles { file -> file.isDirectory && file.name.startsWith(LEGACY_PREFIX) }
            .orEmpty()
        for (store in stores) {
            val uid = store.name.removePrefix(LEGACY_PREFIX).substringBefore('-').toIntOrNull() ?: continue
            if (uid != clientUid && hostsRuntime(uid)) continue
            if (!store.deleteRecursively()) android.util.Log.w("DroidanceRuntime", "Legacy runtime store not removed")
        }
    }

    private fun hostsRuntime(packageManager: PackageManager, uid: Int): Boolean =
        packageManager.getPackagesForUid(uid).orEmpty().any { name ->
            runCatching {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(name, PackageManager.GET_RECEIVERS).receivers.orEmpty()
                    .any { it.name == RUNTIME_RECEIVER }
            }.getOrDefault(true)
        }
}

/** Whole-tree copy and comparison for moving and backing up a store. */
internal object StoreTree {
    fun copy(source: File, target: File, excluded: Set<String> = emptySet()) {
        check(target.mkdirs() || target.isDirectory) { "Cannot create ${target.name}" }
        source.walkTopDown().onEnter { it == source || relative(source, it) !in excluded }.forEach { file ->
            if (file == source) return@forEach
            val path = relative(source, file)
            if (path in excluded) return@forEach
            val destination = File(target, path)
            if (file.isDirectory) check(destination.mkdirs() || destination.isDirectory) { "Cannot create $path" }
            else file.copyTo(destination, overwrite = true)
        }
    }

    /** Relative path to SHA-256 of every regular file. */
    fun digest(root: File, excluded: Set<String> = emptySet()): Map<String, String> =
        root.walkTopDown().onEnter { it == root || relative(root, it) !in excluded }
            .filter { it.isFile && relative(root, it) !in excluded }
            .associate { file ->
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        hash.update(buffer, 0, count)
                    }
                }
                relative(root, file) to hash.digest().joinToString("") { "%02x".format(it) }
            }

    /** Deletes everything in [root] except [excluded] paths. */
    fun clear(root: File, excluded: Set<String>) {
        root.listFiles().orEmpty().forEach { child -> clearEntry(root, child, excluded) }
    }

    private fun clearEntry(root: File, file: File, excluded: Set<String>) {
        val path = relative(root, file)
        if (path in excluded) return
        if (file.isDirectory && excluded.any { it.startsWith("$path/") }) {
            file.listFiles().orEmpty().forEach { clearEntry(root, it, excluded) }
            return
        }
        check(file.deleteRecursively()) { "Cannot remove $path" }
    }

    private fun relative(root: File, file: File): String = file.relativeTo(root).invariantSeparatorsPath
}
