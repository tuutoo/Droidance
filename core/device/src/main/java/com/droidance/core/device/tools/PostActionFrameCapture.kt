// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Confirm the delivered frame after sampling; rendering may restart during PNG capture. */
class PostActionFrameCapture(
    private val sampler: VisualFrameSampler,
    private val elapsedMillis: () -> Long,
    private val waitMillis: suspend (Long) -> Unit = { delay(it) }
) {
    companion object {
        /** The minimum delay belongs to the last delivered action, not each later capture. */
        /** Bounds recaptures inside the extension even when sampling returns immediately. */
        const val MAX_EXTENSION_CAPTURES = 2L

        fun remainingWaitMs(minimumMs: Long, actionEndedAtMs: Long?, nowMs: Long): Long =
            if (actionEndedAtMs == null) minimumMs else
                (minimumMs - (nowMs - actionEndedAtMs).coerceAtLeast(0)).coerceAtLeast(0)
    }
    data class Policy(val initialWaitMs: Long, val timeoutMs: Long, val intervalMs: Long,
        val stableSamples: Int, val pixelDelta: Int, val changedFraction: Double,
        val localizedMotionMaxFraction: Double = 0.0, val localizedMotionSamples: Int = 3,
        val settleExtensionMs: Long = 0, val lateChangeProbeMs: Long = 0, val newViewChangedFraction: Double = 1.0,
        val quietViewChangedFraction: Double = 0.0, val minStableMs: Long = 0) {
        init {
            require(initialWaitMs in 0..1000 && timeoutMs in 1..5000 && intervalMs in 1..500)
            require(stableSamples in 2..6 && pixelDelta in 0..255 && changedFraction in 0.0..1.0)
            require(localizedMotionMaxFraction in 0.0..1.0 && localizedMotionSamples in 3..6)
            require(settleExtensionMs in 0..5000)
            require(lateChangeProbeMs in 0..2000 && newViewChangedFraction in 0.0..1.0)
            require(quietViewChangedFraction in 0.0..newViewChangedFraction)
            require(minStableMs in 0..2000)
        }
    }

    suspend fun <T> capture(policy: Policy, waitBeforeFrameMs: Long = 0,
        actionEndedAtElapsedMs: Long? = null,
        verifyFrame: suspend (T) -> ObservationPixelComparison?,
        changedFromDecision: suspend () -> Double? = { null },
        captureFrame: suspend () -> T): Pair<VisualFrameSampler.Result, T> {
        require(waitBeforeFrameMs in 0..5000)
        coroutineContext.ensureActive()
        val started = elapsedMillis()
        val actionEnded = actionEndedAtElapsedMs?.coerceAtMost(started) ?: started
        // An explicit wait is a minimum transition delay, not permission to skip readiness.
        // Sampling and optional recaptures share one action-relative waiting budget.
        // The mandatory evidence capture and fresh verification keep their backend bounds.
        val minimumWait = maxOf(waitBeforeFrameMs, policy.initialWaitMs)
        val deadline = actionEnded + minimumWait + policy.timeoutMs
        val initialDelay = remainingWaitMs(minimumWait, actionEnded, started)
        waitMillis(initialDelay)
        val sampleBudget = deadline - elapsedMillis()
        val readiness = if (sampleBudget > 0) sampler.sample(sampleBudget, policy.intervalMs, policy.stableSamples,
            policy.pixelDelta, policy.changedFraction, policy.localizedMotionMaxFraction, policy.localizedMotionSamples, policy.minStableMs)
        else VisualFrameSampler.Result(false, 0, 0, diagnostics = mapOf("deadline_exhausted" to 1L))
        coroutineContext.ensureActive()
        // Always retain one fresh evidence frame, even after readiness exhausts its budget.
        // Backend calls keep their existing execution bounds; exhaustion never grants another wait window.
        val frameStarted = elapsedMillis()
        var frame = captureFrame()
        val verificationStarted = elapsedMillis()
        var captures = 1L
        var unavailable = 0L
        var comparison: ObservationPixelComparison?
        var stable: Boolean
        var localized: Boolean
        var effective = readiness
        val extendedDeadline = deadline + policy.settleExtensionMs
        var extensionCaptures = 0L
        var extensionMs = 0L
        while (true) {
            coroutineContext.ensureActive()
            comparison = verifyFrame(frame)?.takeIf { it.available }
            coroutineContext.ensureActive()
            // The deadline bounds further waiting/recapture, not the truth of a fresh
            // verification after mandatory PNG encoding. Readiness itself must have
            // been established inside its budget; missing/expired samples cannot certify it.
            stable = comparison?.let { !ObservationPixelComparator.exceedsChangeFraction(it, policy.changedFraction) } == true
            localized = effective.localizedMotion && comparison?.let {
                it.changedFraction <= policy.localizedMotionMaxFraction
            } == true
            if (comparison == null) unavailable++
            // Missing verification cannot certify readiness. Repeating an unavailable
            // channel does not help; keep the original evidence and mark it unsettled.
            if (stable || localized || comparison == null) break
            val remaining = deadline - elapsedMillis()
            if (remaining > 0 && (effective.stable || effective.localizedMotion)) {
                waitMillis(minOf(policy.intervalMs, remaining))
                coroutineContext.ensureActive()
                if (elapsedMillis() >= deadline) {
                    if (policy.settleExtensionMs > 0) continue else break
                }
                frame = captureFrame()
                captures++
                continue
            }
            // A quiet screen that changed again after its budget, for example content
            // replacing a loading placeholder, gets a bounded extension: wait for rendering
            // to settle and deliver a fresh frame instead of a transient one. Continuous
            // motion that never settled is not extended.
            val extensionRemaining = extendedDeadline - elapsedMillis()
            if (extensionRemaining <= 0 || extensionCaptures >= MAX_EXTENSION_CAPTURES ||
                !(effective.stable || effective.localizedMotion)) break
            val extensionStarted = elapsedMillis()
            effective = sampler.sample(extensionRemaining, policy.intervalMs, policy.stableSamples,
                policy.pixelDelta, policy.changedFraction, policy.localizedMotionMaxFraction, policy.localizedMotionSamples, policy.minStableMs)
            extensionMs += (elapsedMillis() - extensionStarted).coerceAtLeast(0)
            coroutineContext.ensureActive()
            frame = captureFrame()
            captures++
            extensionCaptures++
        }
        // A new view that went quiet may still be a loading placeholder whose content arrives just
        // after, and a screen that has not answered the action yet may respond late, such as a
        // panel that opens after a network request. Probe either once after a short delay; a real
        // change gets one bounded settle and a fresh frame. In-place changes between the two
        // bounds, such as a digit in a display, are neither.
        var lateChanges = 0L
        val decisionChange = if (policy.lateChangeProbeMs > 0 && (stable || localized)) changedFromDecision() else null
        if (decisionChange != null && (decisionChange >= policy.newViewChangedFraction ||
                decisionChange < policy.quietViewChangedFraction)) {
            waitMillis(policy.lateChangeProbeMs)
            coroutineContext.ensureActive()
            val late = verifyFrame(frame)?.takeIf { it.available }
            if (late != null && ObservationPixelComparator.exceedsChangeFraction(late, policy.changedFraction)) {
                lateChanges = 1
                val lateStarted = elapsedMillis()
                effective = sampler.sample(maxOf(policy.settleExtensionMs, policy.timeoutMs), policy.intervalMs,
                    policy.stableSamples, policy.pixelDelta, policy.changedFraction,
                    policy.localizedMotionMaxFraction, policy.localizedMotionSamples, policy.minStableMs)
                extensionMs += (elapsedMillis() - lateStarted).coerceAtLeast(0)
                coroutineContext.ensureActive()
                frame = captureFrame()
                captures++
                comparison = verifyFrame(frame)?.takeIf { it.available }
                if (comparison == null) unavailable++
                stable = comparison?.let { !ObservationPixelComparator.exceedsChangeFraction(it, policy.changedFraction) } == true
                localized = effective.localizedMotion && comparison?.let {
                    it.changedFraction <= policy.localizedMotionMaxFraction
                } == true
            }
        }
        val verificationElapsed = (elapsedMillis() - verificationStarted).coerceAtLeast(0)
        return effective.copy(
            stable = effective.stable && stable,
            localizedMotion = localized,
            elapsedMs = (elapsedMillis() - started).coerceAtLeast(0),
            diagnostics = readiness.diagnostics + mapOf(
                "initial_wait_ms" to initialDelay,
                "action_budget_elapsed_ms" to (elapsedMillis() - actionEnded).coerceAtLeast(0),
                "action_budget_ms" to minimumWait + policy.timeoutMs,
                "final_frame_capture_ms" to (verificationStarted - frameStarted).coerceAtLeast(0),
                "final_frame_captures" to captures,
                "final_frame_confirmed" to if ((effective.stable && stable) || localized) 1L else 0L,
                "settle_extension_captures" to extensionCaptures,
                "late_change_recaptures" to lateChanges,
                "settle_extension_ms" to extensionMs,
                "final_frame_unavailable_checks" to unavailable,
                "final_frame_last_changed_ppm" to (comparison?.changedFraction?.times(1_000_000)?.toLong() ?: -1L),
                "final_frame_verification_ms" to verificationElapsed,
                "final_frame_deadline_exhausted" to if (elapsedMillis() >= deadline) 1L else 0L
            )
        ) to frame
    }
}
