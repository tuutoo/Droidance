// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.droidance.BuildConfig
import com.droidance.runtime.RuntimeProcessClient
import com.droidance.ui.viewmodel.ConversationDisplayCache
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Backs up and restores all user data. The resident owner holds the store, so it writes and
 * reads the user's chosen file directly; this process contributes and restores its settings.
 */
@Singleton
class DataBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: RuntimeProcessClient,
    private val displayCache: ConversationDisplayCache
) {
    /** A failure the user can act on, identified by a stable code. */
    class BackupException(val code: String) : Exception(code)

    fun export(target: Uri, password: String): BackupContents = translate {
        val settings = AppSettingsBackup.capture(context)
        val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(target, "wt")) { "BACKUP_FAILED" }
        val result = descriptor.use {
            runtime.transfer("backupExport", Bundle().apply {
                putParcelable("target", it)
                putString("password", password)
                putByteArray("settings", Json.encodeToString(JsonObject.serializer(), settings).toByteArray(Charsets.UTF_8))
                putInt("settingsCount", AppSettingsBackup.count(settings))
                putString("appVersion", BuildConfig.VERSION_NAME)
            })
        }
        Json.decodeFromString(BackupContents.serializer(), requireNotNull(result.getString("contents")))
    }

    /** Replaces all user data; the caller restarts the application afterwards. */
    fun restore(source: Uri, password: String): BackupContents = translate {
        val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(source, "r")) { "BACKUP_FAILED" }
        val result = descriptor.use {
            runtime.transfer("backupRestore", Bundle().apply {
                putParcelable("source", it)
                putString("password", password)
            })
        }
        val settings = Json.parseToJsonElement(requireNotNull(result.getByteArray("settings")).toString(Charsets.UTF_8))
        AppSettingsBackup.apply(context, settings as JsonObject)
        displayCache.invalidate(null)
        Json.decodeFromString(BackupContents.serializer(), requireNotNull(result.getString("contents")))
    }

    /** Starts a fresh process so every component reads the restored data. */
    fun restartApplication() {
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }

    private inline fun <T> translate(action: () -> T): T = try { action() } catch (error: Exception) {
        val code = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }
            .firstOrNull { it.startsWith("BACKUP_") }
        throw BackupException(code ?: if (error is com.droidance.core.device.agent.device.RuntimeUnavailableException) "BACKUP_RUNTIME_UNAVAILABLE" else "BACKUP_FAILED")
    }
}
