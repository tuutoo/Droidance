// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import com.droidance.BuildConfig
import com.droidance.agent.AppForegroundState
import com.droidance.core.agent.di.ApplicationScope
import com.droidance.settings.GeneralSettingsRepository
import com.droidance.update.model.AppReleaseInfo
import com.droidance.update.model.DownloadError
import com.droidance.update.model.DownloadProgressState
import com.droidance.update.model.InstallResult
import com.droidance.update.model.UpdateCheckState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

/** A known update without a usable cached manifest needs a refresh when the app opens. */
internal fun shouldAutoCheck(
    enabled: Boolean,
    elapsedSinceLastCheckMs: Long,
    knownUpdateNeedsRestoring: Boolean
): Boolean = enabled && (elapsedSinceLastCheckMs >= AUTO_CHECK_INTERVAL_MS || knownUpdateNeedsRestoring)

@Singleton
class AppUpdateManager @Inject constructor(
    private val repository: UpdateRepository,
    private val downloader: UpdateDownloader,
    private val installer: UpdateInstaller,
    private val generalSettings: GeneralSettingsRepository,
    private val foregroundState: AppForegroundState,
    private val notifier: UpdateNotifier,
    @ApplicationScope private val externalScope: CoroutineScope
) {
    private val _checkState = MutableStateFlow<UpdateCheckState>(if (BuildConfig.UPDATE_REPOSITORY.isBlank()) UpdateCheckState.Disabled else repository.restoredCheckState())
    val checkState: StateFlow<UpdateCheckState> = _checkState.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadProgressState>(DownloadProgressState.Idle)
    val downloadState: StateFlow<DownloadProgressState> = _downloadState.asStateFlow()

    /** Stays set through later checks so a failed check does not hide a known update. */
    private val _availableRelease = MutableStateFlow(repository.cachedRelease())
    val availableRelease: StateFlow<AppReleaseInfo?> = _availableRelease.asStateFlow()

    private val _dialogVisible = MutableStateFlow(false)
    val dialogVisible: StateFlow<Boolean> = _dialogVisible.asStateFlow()

    private val _installPermissionRequired = MutableStateFlow(false)
    val installPermissionRequired: StateFlow<Boolean> = _installPermissionRequired.asStateFlow()

    private var downloadJob: Job? = null
    private var pendingInstall: File? = null

    fun checkIfDue(appOpen: Boolean) {
        if (BuildConfig.UPDATE_REPOSITORY.isBlank()) return
        if (appOpen) _availableRelease.value?.let(::promptIfUnseen)
        val known = generalSettings.knownUpdateVersion()
        val restoring = appOpen && _availableRelease.value == null && known.isNotEmpty() &&
            VersionComparator.isNewer(known, BuildConfig.VERSION_NAME)
        val elapsed = System.currentTimeMillis() - generalSettings.lastUpdateCheckTime()
        if (System.currentTimeMillis() >= repository.nextCheckAllowedAt() &&
            shouldAutoCheck(generalSettings.autoCheckUpdate(), elapsed, restoring)) {
            checkForUpdates(force = false)
        }
    }

    fun checkForUpdates(force: Boolean = true) {
        if (_checkState.value is UpdateCheckState.Checking) return
        _checkState.value = UpdateCheckState.Checking
        externalScope.launch {
            val result = repository.checkLatestRelease(force = force)
            _checkState.value = result
            when (result) {
                is UpdateCheckState.UpdateAvailable -> announce(result.release, manual = force)
                is UpdateCheckState.UpToDate -> _availableRelease.value = null
                else -> Unit
            }
        }
    }

    private fun announce(release: AppReleaseInfo, manual: Boolean) {
        if (_availableRelease.value?.let { it.tagName != release.tagName } == true) cancelDownload()
        _availableRelease.value = release
        generalSettings.setKnownUpdateVersion(release.versionName)
        if (manual) {
            _dialogVisible.value = true
            return
        }
        if (foregroundState.isInForeground) {
            promptIfUnseen(release)
        } else if (generalSettings.lastPromptedUpdateVersion() != release.versionName && notifier.notify(release)) {
            generalSettings.setLastPromptedUpdateVersion(release.versionName)
        }
    }

    private fun promptIfUnseen(release: AppReleaseInfo) {
        if (generalSettings.lastPromptedUpdateVersion() == release.versionName) return
        generalSettings.setLastPromptedUpdateVersion(release.versionName)
        _dialogVisible.value = true
    }

    fun showDialog() {
        _dialogVisible.value = true
    }

    fun dismissDialog() {
        _dialogVisible.value = false
    }

    fun startDownload(release: AppReleaseInfo) {
        val previousJob = downloadJob
        previousJob?.cancel()
        downloadJob = externalScope.launch {
            previousJob?.join()
            (_downloadState.value as? DownloadProgressState.ReadyToInstall)?.apkFile?.delete()
            downloader.download(
                downloadUrl = release.apkDownloadUrl,
                fileName = release.apkFileName,
                expectedSha256 = release.sha256,
                expectedSizeBytes = release.apkSizeBytes
            ).collect { state ->
                _downloadState.value = state
                // Android blocks background activity starts; the dialog keeps the install action.
                if (state is DownloadProgressState.ReadyToInstall && foregroundState.isInForeground) {
                    installDownloaded(state.apkFile)
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = DownloadProgressState.Idle
    }

    fun installDownloaded(apkFile: File) {
        when (val result = installer.install(apkFile)) {
            InstallResult.PermissionRequired -> {
                pendingInstall = apkFile
                _installPermissionRequired.value = true
            }
            InstallResult.PromptLaunched,
            InstallResult.PrivilegedSuccess -> _dialogVisible.value = false
            is InstallResult.Failed -> {
                _downloadState.value = DownloadProgressState.Failed(DownloadError.INVALID_APK, result.message)
            }
        }
    }

    /** Continues an install once the user returns from granting the unknown-sources permission. */
    fun resumePendingInstall() {
        val file = pendingInstall ?: return
        if (_installPermissionRequired.value || !installer.canRequestPackageInstalls()) return
        pendingInstall = null
        installDownloaded(file)
    }

    fun dismissInstallPermission(keepPendingInstall: Boolean) {
        _installPermissionRequired.value = false
        if (!keepPendingInstall) pendingInstall = null
    }

    fun getManageUnknownAppSourcesIntent() = installer.createManageUnknownAppSourcesIntent()
}
