// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import android.content.Context
import com.droidance.update.model.ReleaseMetadataDto
import com.droidance.update.model.UpdateCheckError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class UpdateCheckRecord(
    val repository: String = "",
    val metadata: ReleaseMetadataDto? = null,
    val etag: String? = null,
    val checkedAt: Long = 0,
    val retryAt: Long = 0,
    val failure: UpdateCheckError? = null,
    val failureCount: Int = 0
)

internal interface UpdateCheckStorage {
    fun load(): UpdateCheckRecord
    fun save(record: UpdateCheckRecord)
}

/** Update transport state is local to this installation, outside settings backups. */
@Singleton
internal class UpdateCheckStore @Inject constructor(@ApplicationContext context: Context) : UpdateCheckStorage {
    private val prefs = context.getSharedPreferences("droidance_update_check", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): UpdateCheckRecord = runCatching {
        prefs.getString("record", null)?.let { json.decodeFromString<UpdateCheckRecord>(it) }
    }.getOrNull() ?: UpdateCheckRecord()

    override fun save(record: UpdateCheckRecord) {
        prefs.edit().putString("record", json.encodeToString(record)).apply()
    }
}
