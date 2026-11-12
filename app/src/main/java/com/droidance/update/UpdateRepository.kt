// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import com.droidance.BuildConfig
import com.droidance.settings.GeneralSettingsRepository
import com.droidance.update.model.AppReleaseInfo
import com.droidance.update.model.ReleaseMetadataDto
import com.droidance.update.model.UpdateCheckError
import com.droidance.update.model.UpdateCheckState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateRepository internal constructor(
    okHttpClient: OkHttpClient,
    private val recordCheckTime: (Long) -> Unit,
    private val currentVersion: String,
    private val storage: UpdateCheckStorage,
    private val retryDelayMillis: Long = 1_000L,
    private val repository: String = BuildConfig.UPDATE_REPOSITORY,
    private val packageName: String = BuildConfig.APPLICATION_ID,
    private val now: () -> Long = System::currentTimeMillis
) {
    @Inject internal constructor(
        okHttpClient: OkHttpClient,
        generalSettingsRepository: GeneralSettingsRepository,
        store: UpdateCheckStore
    ) : this(okHttpClient, generalSettingsRepository::setLastUpdateCheckTime,
        BuildConfig.VERSION_NAME, store)

    private val client = okHttpClient.forUpdates().newBuilder()
        .callTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    @Volatile private var record = storage.load().takeIf { it.repository == repository }
        ?: UpdateCheckRecord(repository = repository)

    fun cachedRelease(): AppReleaseInfo? = cachedMetadata()?.let(::releaseInfo)
        ?.takeIf { VersionComparator.isNewer(it.tagName, currentVersion) }

    fun nextCheckAllowedAt(): Long = record.retryAt

    fun restoredCheckState(): UpdateCheckState = cachedRelease()?.let {
        UpdateCheckState.UpdateAvailable(it, currentVersion, record.checkedAt, fromCache = true)
    } ?: UpdateCheckState.Idle

    suspend fun checkLatestRelease(force: Boolean = false): UpdateCheckState = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (repository.isBlank()) return@withLock UpdateCheckState.Disabled
            if (record.retryAt > now()) return@withLock failureState()
            val cached = cachedMetadata()
            if (!force && cached != null && now() - record.checkedAt in 0 until AUTO_CHECK_INTERVAL_MS) {
                return@withLock checkedState(cached, fromCache = true)
            }
            val request = Request.Builder()
                .url("https://github.com/$repository/releases/latest/download/release.json")
                .header("User-Agent", "Droidance-AppUpdate/${BuildConfig.VERSION_NAME}")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .apply { if (cached != null) record.etag?.let { header("If-None-Match", it) } }
                .build()
            // Only direct GitHub HTTPS may supply metadata and the expected APK digest.
            // No API request or untrusted metadata mirror is needed.
            for (attempt in 0..2) {
                try {
                    return@withLock client.withUpdateResponse(request) { response ->
                        val metadata = if (response.code == 304) {
                            cached ?: throw SerializationException("No validated metadata for HTTP 304")
                        } else {
                            if (!response.isSuccessful) throw UpdateHttpException.from(response, now())
                            json.decodeFromString<ReleaseMetadataDto>(response.body?.string()
                                ?: throw SerializationException("Empty release metadata")).also(::validate)
                        }
                        val checkedAt = now()
                        save(UpdateCheckRecord(repository, metadata,
                            response.header("ETag") ?: record.etag.takeIf { response.code == 304 }, checkedAt))
                        recordCheckTime(checkedAt)
                        checkedState(metadata)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SerializationException) {
                    return@withLock failed(UpdateCheckError.PARSE_ERROR, e.message)
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (e is UpdateHttpException) {
                        val reason = when {
                            e.rateLimited -> UpdateCheckError.RATE_LIMITED
                            e.status == 403 -> UpdateCheckError.ACCESS_DENIED
                            e.status == 404 -> UpdateCheckError.NO_RELEASE_FOUND
                            else -> UpdateCheckError.NETWORK_ERROR
                        }
                        if (e.rateLimited || !e.retryable || e.retryAt != null || attempt == 2) {
                            return@withLock failed(reason, e.message, e.retryAt)
                        }
                    }
                    if (attempt == 2) return@withLock failed(UpdateCheckError.NETWORK_ERROR, e.message)
                    delay(retryDelayMillis * (attempt + 1))
                }
            }
            error("Update retry loop exhausted without a result")
        }
    }

    private fun cachedMetadata(): ReleaseMetadataDto? = record.metadata?.takeIf {
        runCatching { validate(it) }.isSuccess
    }

    private fun validate(metadata: ReleaseMetadataDto) {
        val url = "https://github.com/$repository/releases/download/${metadata.tag}/${metadata.apk}"
        if (!VersionComparator.isValid(currentVersion) || !VersionComparator.isValid(metadata.versionName) ||
            metadata.versionName != metadata.versionName.trim().removePrefix("v").removePrefix("V") ||
            metadata.tag != "v${metadata.versionName}" || metadata.packageName != packageName ||
            !isReleaseAsset(url, metadata.apk, repository) || metadata.sizeBytes <= 0 ||
            !metadata.sha256.matches(Regex("[a-fA-F0-9]{64}")) ||
            listOf("en", "zh").any { metadata.releaseNotes[it].isNullOrBlank() }) {
            throw SerializationException("Invalid release identity, APK checksum, size or localized notes")
        }
    }

    private fun releaseInfo(metadata: ReleaseMetadataDto) = AppReleaseInfo(
        tagName = metadata.tag,
        versionName = metadata.versionName,
        releaseTitle = metadata.tag,
        releaseNotes = metadata.releaseNotes,
        publishedAt = "", // The release manifest does not contain a publication timestamp.
        htmlUrl = "https://github.com/$repository/releases",
        apkDownloadUrl = "https://github.com/$repository/releases/download/${metadata.tag}/${metadata.apk}",
        apkFileName = metadata.apk,
        apkSizeBytes = metadata.sizeBytes,
        sha256 = metadata.sha256
    )

    private fun checkedState(metadata: ReleaseMetadataDto, fromCache: Boolean = false): UpdateCheckState =
        if (VersionComparator.isNewer(metadata.tag, currentVersion)) {
            UpdateCheckState.UpdateAvailable(releaseInfo(metadata), currentVersion, record.checkedAt, fromCache)
        } else UpdateCheckState.UpToDate(currentVersion)

    private fun failed(reason: UpdateCheckError, message: String?, serverRetryAt: Long? = null): UpdateCheckState {
        val failures = (record.failureCount.coerceIn(0, 6) + 1).coerceAtMost(7)
        val backoff = (60_000L * (1L shl (failures - 1))).coerceAtMost(3_600_000L)
        save(record.copy(retryAt = maxOf(now() + backoff, serverRetryAt ?: 0),
            failure = reason, failureCount = failures))
        return failureState(message)
    }

    private fun failureState(message: String? = null) = UpdateCheckState.CheckFailed(
        record.failure ?: UpdateCheckError.NETWORK_ERROR, message, record.retryAt, record.checkedAt
    )

    private fun save(value: UpdateCheckRecord) {
        storage.save(value)
        record = value
    }
}
