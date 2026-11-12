// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import android.content.Context
import com.droidance.update.model.DownloadError
import com.droidance.update.model.DownloadProgressState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateDownloader internal constructor(
    client: OkHttpClient,
    private val updateDir: File,
    private val verifyApk: (File) -> Boolean,
    private val retryDelayMillis: Long = 1_000L,
    private val repository: String = com.droidance.BuildConfig.UPDATE_REPOSITORY
) {
    @Inject constructor(
        okHttpClient: OkHttpClient,
        @ApplicationContext context: Context,
        apkVerifier: ApkUpdateVerifier
    ) : this(okHttpClient, File(context.cacheDir, "updates"), apkVerifier::isValidUpdate)

    private val client = client.forUpdates()

    fun download(
        downloadUrl: String,
        fileName: String,
        expectedSha256: String?,
        expectedSizeBytes: Long
    ): Flow<DownloadProgressState> = flow {
        val sha256 = expectedSha256?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
        if (sha256 == null) {
            emit(DownloadProgressState.Failed(DownloadError.MISSING_CHECKSUM))
            return@flow
        }
        if (!isReleaseAsset(downloadUrl, fileName, repository) || expectedSizeBytes <= 0) {
            emit(DownloadProgressState.Failed(DownloadError.INVALID_APK, "Invalid release asset"))
            return@flow
        }

        var tempFile: File? = null
        var targetFile: File? = null
        var ready = false
        try {
            if (!updateDir.isDirectory && !updateDir.mkdirs()) {
                throw UpdateStorageException(IOException("Cannot create update directory"))
            }
            val partialFile = try {
                File.createTempFile("update-", ".download", updateDir)
            } catch (e: IOException) {
                throw UpdateStorageException(e)
            }
            tempFile = partialFile
            // Three direct attempts precede one bounded fallback. No geolocation is needed.
            // ghproxy.net links to the MIT-licensed hunshcn/gh-proxy project. Treat it as
            // untrusted transport: the digest always comes from the direct GitHub release manifest.
            val urls = List(3) { downloadUrl } + "https://ghproxy.net/$downloadUrl"
            for ((attempt, url) in urls.withIndex()) {
                currentCoroutineContext().ensureActive()
                emit(DownloadProgressState.Downloading(0, expectedSizeBytes, 0))
                try {
                    val request = Request.Builder().url(url)
                        .header("User-Agent", "Droidance-AppUpdate").build()
                    client.withUpdateResponse(request) { response ->
                        if (!response.isSuccessful) throw UpdateHttpException(response.code)
                        val body = response.body ?: throw IOException("Empty body")
                        if (body.contentLength() > 0 && body.contentLength() != expectedSizeBytes) {
                            throw UpdateIntegrityException()
                        }
                        val output = try {
                            partialFile.outputStream()
                        } catch (e: IOException) {
                            throw UpdateStorageException(e)
                        }
                        body.byteStream().use { input ->
                            output.use { sink ->
                                val buffer = ByteArray(8192)
                                var bytesRead = 0L
                                var lastPercent = -1
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    bytesRead += count
                                    if (bytesRead > expectedSizeBytes) throw UpdateIntegrityException()
                                    try {
                                        sink.write(buffer, 0, count)
                                    } catch (e: IOException) {
                                        throw UpdateStorageException(e)
                                    }
                                    val percent = (bytesRead * 100 / expectedSizeBytes).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        emit(DownloadProgressState.Downloading(bytesRead, expectedSizeBytes, percent))
                                    }
                                }
                                if (bytesRead != expectedSizeBytes) throw IOException("Incomplete response body")
                            }
                        }
                    }
                    break
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (e is UpdateStorageException || e is UpdateIntegrityException ||
                        (e is UpdateHttpException && !e.retryable) || attempt == urls.lastIndex
                    ) throw e
                    delay(retryDelayMillis * (attempt + 1))
                }
            }

            emit(DownloadProgressState.Verifying)
            val digest = MessageDigest.getInstance("SHA-256")
            partialFile.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(sha256, ignoreCase = true)) throw UpdateIntegrityException()
            if (!verifyApk(partialFile)) {
                emit(DownloadProgressState.Failed(DownloadError.INVALID_APK))
                return@flow
            }
            currentCoroutineContext().ensureActive()
            val verifiedFile = File(updateDir, partialFile.name.removeSuffix(".download") + ".apk")
            targetFile = verifiedFile
            if (!partialFile.renameTo(verifiedFile)) throw UpdateStorageException(IOException("Cannot finalize APK"))
            emit(DownloadProgressState.ReadyToInstall(verifiedFile))
            ready = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            val reason = when (e) {
                is UpdateStorageException -> DownloadError.STORAGE_ERROR
                is UpdateIntegrityException -> DownloadError.CHECKSUM_MISMATCH
                else -> DownloadError.NETWORK_ERROR
            }
            emit(DownloadProgressState.Failed(reason, e.message))
        } finally {
            tempFile?.delete()
            if (!ready) targetFile?.delete()
        }
    }.flowOn(Dispatchers.IO)
}

internal fun isReleaseAsset(url: String, fileName: String, repository: String): Boolean {
    if (!repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) return false
    val parsed = url.toHttpUrlOrNull() ?: return false
    val segments = parsed.pathSegments
    return parsed.scheme == "https" && parsed.host == "github.com" && parsed.port == 443 &&
        parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null &&
        segments.size == 6 && segments.take(4) == (repository.split("/") + listOf("releases", "download")) &&
        segments[4].isNotBlank() && segments[5] == fileName &&
        fileName.endsWith(".apk", ignoreCase = true) && fileName.none { it == '/' || it == '\\' }
}

private class UpdateStorageException(cause: IOException) : IOException(cause)
private class UpdateIntegrityException : IOException("Release asset integrity check failed")
