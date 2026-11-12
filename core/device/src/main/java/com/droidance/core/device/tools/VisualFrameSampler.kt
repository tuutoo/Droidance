// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Rendering readiness only; a stable sample never establishes action or task success. */
class VisualFrameSampler(
    private val capture: suspend () -> Frame?,
    private val elapsedMillis: () -> Long,
    private val waitMillis: suspend (Long) -> Unit = { delay(it) }
) {
    data class Frame(val pixels: ByteArray, val id: Long)

    data class Result(val stable: Boolean, val captures: Int, val elapsedMs: Long, val localizedMotion: Boolean = false,
        val diagnostics: Map<String, Long> = emptyMap())

    suspend fun sample(timeoutMs: Long, pollMs: Long, requiredSamples: Int,
        pixelDelta: Int, changedFraction: Double, localizedMotionMaxFraction: Double = 0.0,
        localizedMotionSamples: Int = 3, minStableMs: Long = 0): Result {
        require(requiredSamples >= 2 && pixelDelta in 0..255 && changedFraction in 0.0..1.0)
        require(minStableMs >= 0)
        require(localizedMotionMaxFraction in 0.0..1.0 && localizedMotionSamples >= 3)
        val started = elapsedMillis()
        var previous: Frame? = null
        var count = 0
        var stableCount = 0
        // Readiness is a span of observed stillness, not a count: back-to-back captures during a
        // transition's quiet start can match each other without the page having settled.
        var runStartedAt = 0L
        var lastSimilarAt = 0L
        var longestStableSpan = 0L
        val motionWindow = ArrayDeque<BooleanArray>()
        // Capture time of the frame each windowed transition started from.
        val motionStarts = ArrayDeque<Long>()
        var previousCapturedAt = 0L
        val motionTransitions = maxOf(localizedMotionSamples, requiredSamples) - 1
        var distinctFrames = 0L
        var cachedReads = 0L
        var unavailableReads = 0L
        var lastChangedPpm = 0L
        var maxChangedPpm = 0L
        var lastMotionUnionPpm = 0L
        var lastMotionUnionFraction = 0.0
        fun finish(stable: Boolean, elapsed: Long, localized: Boolean = false): Result =
            Result(stable, count, elapsed, localized, linkedMapOf(
                "distinct_frames" to distinctFrames, "cached_reads" to cachedReads,
                "unavailable_reads" to unavailableReads, "stable_samples" to stableCount.toLong(),
                "last_changed_ppm" to lastChangedPpm, "max_changed_ppm" to maxChangedPpm,
                "motion_union_ppm" to lastMotionUnionPpm,
                "stable_span_ms" to longestStableSpan,
                "deadline_exhausted" to if (elapsed >= timeoutMs) 1L else 0L))
        while (true) {
            coroutineContext.ensureActive()
            val captureBudget = timeoutMs - (elapsedMillis() - started)
            val current = if (captureBudget > 0) kotlinx.coroutines.withTimeoutOrNull(captureBudget) { capture() } else null
            val capturedAt = elapsedMillis()
            count++
            when {
                current == null || current.pixels.isEmpty() -> unavailableReads++
                previous?.id == current.id -> cachedReads++
                else -> distinctFrames++
            }
            coroutineContext.ensureActive()
            stableCount = when {
                current == null || current.pixels.isEmpty() -> 0
                previous != null && current.id == previous.id -> stableCount
                previous != null && similar(previous.pixels, current.pixels, pixelDelta, changedFraction) -> {
                    lastSimilarAt = capturedAt
                    longestStableSpan = maxOf(longestStableSpan, capturedAt - runStartedAt)
                    stableCount + 1
                }
                else -> {
                    runStartedAt = capturedAt
                    1
                }
            }
            if (current != null && previous != null && current.id != previous.id &&
                current.pixels.isNotEmpty() && current.pixels.size == previous.pixels.size) {
                if (motionWindow.firstOrNull()?.size != current.pixels.size) { motionWindow.clear(); motionStarts.clear() }
                val changedPixels = BooleanArray(current.pixels.size)
                var changed = 0L
                for (i in current.pixels.indices) {
                    if (abs((previous.pixels[i].toInt() and 255) - (current.pixels[i].toInt() and 255)) > pixelDelta) {
                        changedPixels[i] = true
                        changed++
                    }
                }
                lastChangedPpm = changed * 1_000_000 / current.pixels.size
                maxChangedPpm = maxOf(maxChangedPpm, lastChangedPpm)
                motionWindow.addLast(changedPixels)
                motionStarts.addLast(previousCapturedAt)
                // Keep enough transitions to cover the minimum span; a longer window only widens the union.
                while (motionWindow.size > motionTransitions && capturedAt - motionStarts.elementAt(1) >= minStableMs) {
                    motionWindow.removeFirst(); motionStarts.removeFirst()
                }
                val unionSize = current.pixels.indices.count { i -> motionWindow.any { it[i] } }
                lastMotionUnionFraction = unionSize.toDouble() / current.pixels.size
                lastMotionUnionPpm = unionSize.toLong() * 1_000_000 / current.pixels.size
            } else if (current == null || previous == null || current.pixels.size != previous.pixels.size) {
                motionWindow.clear()
                motionStarts.clear()
                lastMotionUnionPpm = 0
                lastMotionUnionFraction = 0.0
            }
            // A cached read repeats an older frame; transitions start from when that frame arrived.
            if (current != null && current.id != previous?.id) previousCapturedAt = capturedAt
            previous = current
            val elapsed = (elapsedMillis() - started).coerceAtLeast(0)
            if (elapsed <= timeoutMs && stableCount >= requiredSamples && lastSimilarAt - runStartedAt >= minStableMs)
                return finish(true, elapsed)
            // Never discard the changed region: the caller still delivers one fresh full frame.
            // Use a bounded window of distinct transitions: an earlier scroll must not
            // permanently contaminate a later localized animation. Moving regions still
            // accumulate within this window and cannot qualify merely by changing slowly.
            // Localized motion must also persist for the minimum span: a page transition can move
            // only part of the frame for a few back-to-back captures.
            if (elapsed <= timeoutMs && localizedMotionMaxFraction > 0 && motionWindow.size >= motionTransitions &&
                lastMotionUnionFraction > 0 && lastMotionUnionFraction <= localizedMotionMaxFraction &&
                capturedAt - motionStarts.first() >= minStableMs)
                return finish(false, elapsed, localized = true)
            val remaining = timeoutMs - elapsed
            if (remaining <= 0 || current == null) return finish(false, elapsed)
            waitMillis(pollMs.coerceAtLeast(1).coerceAtMost(remaining))
        }
    }

    companion object {
        fun similar(before: ByteArray, after: ByteArray, pixelDelta: Int, changedFraction: Double): Boolean {
            if (before.isEmpty() || before.size != after.size) return false
            var changed = 0
            val allowed = before.size * changedFraction
            for (i in before.indices) {
                if (abs((before[i].toInt() and 255) - (after[i].toInt() and 255)) > pixelDelta) {
                    changed++
                    if (changed > allowed) return false
                }
            }
            return true
        }
    }
}
