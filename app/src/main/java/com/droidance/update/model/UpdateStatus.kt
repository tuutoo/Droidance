// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update.model

import java.io.File

/**
 * 更新检查状态（类型化状态）
 */
sealed interface UpdateCheckState {
    data object Disabled : UpdateCheckState
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpToDate(val checkedVersion: String) : UpdateCheckState
    data class UpdateAvailable(
        val release: AppReleaseInfo,
        val currentVersion: String,
        val checkedAt: Long = 0,
        val fromCache: Boolean = false
    ) : UpdateCheckState
    data class CheckFailed(
        val reason: UpdateCheckError,
        val rawMessage: String? = null,
        val retryAt: Long = 0,
        val lastSuccessfulCheckAt: Long = 0
    ) : UpdateCheckState
}

enum class UpdateCheckError {
    NETWORK_ERROR,
    RATE_LIMITED,
    ACCESS_DENIED,
    PARSE_ERROR,
    NO_RELEASE_FOUND,
    UNKNOWN
}

/**
 * 下载进度状态
 */
sealed interface DownloadProgressState {
    data object Idle : DownloadProgressState
    data class Downloading(val bytesRead: Long, val totalBytes: Long, val percent: Int) : DownloadProgressState
    data object Verifying : DownloadProgressState
    data class ReadyToInstall(val apkFile: File) : DownloadProgressState
    data class Failed(val reason: DownloadError, val detail: String? = null) : DownloadProgressState
}

enum class DownloadError {
    NETWORK_ERROR,
    STORAGE_ERROR,
    CHECKSUM_MISMATCH,
    MISSING_CHECKSUM,
    INVALID_APK,
    CANCELED
}

/**
 * 安装操作结果
 */
sealed interface InstallResult {
    data object PromptLaunched : InstallResult
    data object PrivilegedSuccess : InstallResult
    data object PermissionRequired : InstallResult
    data class Failed(val message: String) : InstallResult
}
