// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal fun OkHttpClient.forUpdates(): OkHttpClient = newBuilder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.MINUTES)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

/** Cancels connection setup and blocked body reads when the owning coroutine stops. */
internal suspend fun <T> OkHttpClient.withUpdateResponse(
    request: Request,
    block: suspend (Response) -> T
): T = coroutineScope {
    val call = newCall(request)
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        call.execute().use { block(it) }
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        cancellation.cancel()
    }
}

internal class UpdateHttpException(
    val status: Int,
    val retryAt: Long? = null,
    val rateLimited: Boolean = status == 429
) : IOException("HTTP $status") {
    val retryable: Boolean get() = status == 408 || status == 429 || status in 500..599

    companion object {
        fun from(response: Response, now: Long): UpdateHttpException {
            val retryAfter = response.header("Retry-After")?.let { value ->
                value.toLongOrNull()?.takeIf { it in 0..(Long.MAX_VALUE - now) / 1000 }
                    ?.let { now + it * 1000 }
                    ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() }.getOrNull()
            }
            val exhausted = response.header("X-RateLimit-Remaining") == "0"
            val reset = if (exhausted) response.header("X-RateLimit-Reset")?.toLongOrNull()
                ?.takeIf { it in 0..Long.MAX_VALUE / 1000 }?.times(1000) else null
            return UpdateHttpException(response.code, listOfNotNull(retryAfter, reset).maxOrNull(),
                response.code == 429 || (response.code == 403 && (exhausted || retryAfter != null)))
        }
    }
}
