// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 远程发布版本信息
 */
data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: Map<String, String>,
    val publishedAt: String,
    val htmlUrl: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val sha256: String? = null
)

@Serializable
internal data class ReleaseMetadataDto(
    val tag: String,
    @SerialName("version_name") val versionName: String,
    @SerialName("package") val packageName: String,
    val apk: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
    @SerialName("release_notes") val releaseNotes: Map<String, String>
)
