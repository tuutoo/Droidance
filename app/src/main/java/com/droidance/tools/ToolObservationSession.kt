// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import android.os.SystemClock
import com.droidance.core.agent.LocalActionEvidence
import com.droidance.agent.LocalActionVerifier
import com.droidance.core.device.agent.device.DeviceBackend
import com.droidance.agent.device.DisplaySnapshotResolver
import com.droidance.core.agent.device.ExecutionContext
import com.droidance.core.agent.device.ExecutionContextStore
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.config.RuleConfigManager
import com.droidance.tools.observe.ObserveScreenTool
import com.droidance.core.device.tools.observe.ObservationFrameConfig
import com.droidance.core.device.tools.observe.VisionFrameScaler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong

/** Owns observation leases, coordinate bindings and reusable frames for one bound Turn. */
internal class ToolObservationSession(
    private val executionContextStore: ExecutionContextStore,
    private val deviceBackend: DeviceBackend,
    private val displaySnapshotResolver: DisplaySnapshotResolver,
    private val observeScreenTool: ObserveScreenTool,
    private val ruleConfigManager: RuleConfigManager,
    private val localActionVerifier: LocalActionVerifier,
    private val timingRecorder: ToolTimingRecorder
) {
    val observationEnvelopeStore = ObservationEnvelopeStore()
    val observedTargetCache = ObservedTargetCache()
    @Volatile var currentModelCoordinateMapper = ModelCoordinateMapper.identity()
    @Volatile private var currentModelObservationLease: ObservationLease? = null

    /** Earlier frames of the execution surface, kept only as pixel samples for the stable-interface check. */
    private data class SurfaceFrame(
        val executionId: String,
        val displayId: Int,
        val foregroundComponent: String?,
        val capturedAtElapsedMs: Long,
        val reference: ObservationPixelReference
    )
    private val surfaceFrames = ArrayDeque<SurfaceFrame>()

    @Synchronized
    private fun rememberSurfaceFrame(reference: ObservationPixelReference?, foregroundComponent: String?, capturedAtElapsedMs: Long) {
        val execution = executionContextStore.current() ?: return
        reference ?: return
        val limit = ruleConfigManager.harnessPolicy().runtimeThresholds.preActionStableHistoryFrames
        surfaceFrames.removeAll { it.executionId != execution.executionId || it.displayId != execution.displayId }
        surfaceFrames.addLast(SurfaceFrame(execution.executionId, execution.displayId, foregroundComponent, capturedAtElapsedMs, reference))
        while (surfaceFrames.size > limit) surfaceFrames.removeFirst()
    }

    @Synchronized
    private fun surfaceHistory(execution: ExecutionContext, foregroundComponent: String, reference: ObservationPixelReference,
        notBeforeElapsedMs: Long): List<SurfaceFrame> =
        surfaceFrames.filter {
            it.executionId == execution.executionId && it.displayId == execution.displayId &&
                it.foregroundComponent == foregroundComponent && it.capturedAtElapsedMs >= notBeforeElapsedMs &&
                it.reference.samples == reference.samples
        }
    @Volatile var decisionObservation: ObservationEnvelope? = null
        private set
    @Volatile var pendingScreenStateText: String? = null
    @Volatile var autoCaptureJob: Job? = null
    @Volatile var reusablePostActionFrame: DeviceScreenFrame? = null
    @Volatile private var pendingActionTarget: ActionTarget? = null
    @Volatile var lastActionEndedAtElapsedMs: Long? = null

    val modelObservationSequence: Long? get() = currentModelObservationLease?.sequence

    fun setActionTarget(targetId: String?, targetX: Int?, targetY: Int?) {
        pendingActionTarget = ActionTarget(targetId, targetX, targetY)
    }

    suspend fun release() {
        withTimeoutOrNull(2_000L) { autoCaptureJob?.cancelAndJoin() }
        autoCaptureJob = null
        observationEnvelopeStore.invalidate()
        reusablePostActionFrame = null
        lastActionEndedAtElapsedMs = null
        invalidateModelFrameBinding()
    }

    fun clear() {
        autoCaptureJob?.cancel()
        observationEnvelopeStore.invalidate()
        reusablePostActionFrame = null
        lastActionEndedAtElapsedMs = null
        invalidateModelFrameBinding()
        pendingScreenStateText = null
        pendingActionTarget = null
        observedTargetCache.clear()
        observeScreenTool.invalidateCache()
        deviceBackend.invalidateScreenCache()
        localActionVerifier.reset()
    }

    private data class ActionTarget(
        val targetId: String? = null,
        val targetX: Int? = null,
        val targetY: Int? = null
    )

    data class PreActionObservationCheck(
        val rejection: ToolExecutionResult?,
        val metrics: Map<String, Long>,
        val finishedAtElapsedMs: Long
    )

    private data class ObservationLease(
        val executionId: String,
        val backendSessionId: String,
        val displayId: Int,
        val rotationDegrees: Int,
        val sequence: Long,
        val decisionCapturedAtElapsedMs: Long,
        val physicalFrameSize: Pair<Int, Int>?,
        val reference: ObservationPixelReference?,
        val foregroundComponent: String?
    )

    fun currentPendingObservation(): ObservationEnvelope? {
        val execution = executionContextStore.current() ?: return null
        return observationEnvelopeStore.peek(
            execution.executionId,
            execution.backendSessionId,
            execution.displayId
        )
    }

    private fun bindCurrentModelFrame(
        scale: Float,
        frameSize: Pair<Int, Int>?,
        physicalFrameSize: Pair<Int, Int>?,
        origin: Pair<Int, Int> = 0 to 0
    ) {
        currentModelCoordinateMapper = ModelCoordinateMapper(
            scale = scale,
            modelSize = frameSize,
            physicalSize = physicalFrameSize,
            physicalOrigin = ModelCoordinateMapper.Point(origin.first, origin.second)
        )
    }

    fun bindModelFrame(observation: ObservationEnvelope) {
        val execution = executionContextStore.requireCurrent()
        require(
            observation.executionId == execution.executionId &&
                observation.backendSessionId == execution.backendSessionId &&
                observation.displayId == execution.displayId
        ) { "Observation binding does not match the current execution context" }
        decisionObservation = observation
        bindCurrentModelFrame(
            scale = observation.frameScale,
            frameSize = observation.frameSize,
            physicalFrameSize = observation.physicalFrameSize,
            origin = observation.frameOrigin
        )
        currentModelObservationLease = ObservationLease(
            executionId = observation.executionId,
            backendSessionId = observation.backendSessionId,
            displayId = observation.displayId,
            rotationDegrees = observation.rotationDegrees,
            sequence = observation.sequence,
            decisionCapturedAtElapsedMs = observation.capturedAtElapsedMs,
            physicalFrameSize = observation.physicalFrameSize,
            reference = captureObservationPixelReference(observation),
            foregroundComponent = observation.foregroundComponent
        )
        rememberSurfaceFrame(observation.pixelReference, observation.foregroundComponent, observation.capturedAtElapsedMs)
        currentModelObservationLease?.let(::startSurfaceSampling)
    }

    fun hasCurrentModelObservationLease(execution: ExecutionContext): Boolean {
        val currentRotation = runCatching {
            displaySnapshotResolver.capture(execution.displayId).rotationDegrees
        }.getOrNull() ?: return false
        return currentModelObservationLease?.let { lease ->
            lease.executionId == execution.executionId &&
                lease.backendSessionId == execution.backendSessionId &&
                lease.displayId == execution.displayId &&
                lease.rotationDegrees == currentRotation
        } == true
    }

    /** Checks fresh pixels from the frozen backend; pixels establish freshness, not semantic identity. */
    suspend fun checkPreActionObservation(
        execution: ExecutionContext,
        targetId: String? = null,
        targetX: Int? = null,
        targetY: Int? = null,
        usePendingTarget: Boolean = true,
        scrollCheckpoint: ScreenBatchScrollCheckpoint? = null,
        surfaceGesture: Boolean = false,
        targetRegion: ModelCoordinateMapper.Bounds? = null
    ): PreActionObservationCheck {
        // The Agent has decided; further samples would describe the action, not the decision.
        stopSurfaceSampling()
        val startedAt = SystemClock.elapsedRealtime()
        val policy = ruleConfigManager.harnessPolicy().runtimeThresholds
        val unavailable = fun(reason: String): PreActionObservationCheck {
            val finishedAtElapsedMs = SystemClock.elapsedRealtime()
            val elapsedMs = (finishedAtElapsedMs - startedAt).coerceAtLeast(0L)
            return PreActionObservationCheck(
                rejection = ToolProtocolResult.notExecuted(
                    code = "PRE_ACTION_OBSERVATION_UNAVAILABLE",
                    retryable = true,
                    details = buildJsonObject {
                        put("reason", JsonPrimitive(reason))
                        put("check_elapsed_ms", JsonPrimitive(elapsedMs))
                    }
                ),
                metrics = preActionObservationMetrics(
                    elapsedMs = elapsedMs,
                    comparison = null,
                    available = false,
                    lease = currentModelObservationLease,
                    sampleSide = policy.preActionObservationSampleSide
                ),
                finishedAtElapsedMs = finishedAtElapsedMs
            )
        }

        val lease = currentModelObservationLease ?: return unavailable("lease_missing")
        val reference = scrollCheckpoint?.reference ?: lease.reference ?: return unavailable("decision_frame_sampling_failed")
        val currentSample = try {
            deviceBackend.samplePixels(reference.samples.map { it.physicalX to it.physicalY })
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { null } ?: return unavailable("execution_sample_capture_failed")
        if (currentSample.displayId != execution.displayId ||
            currentSample.backendSessionId != execution.backendSessionId ||
            currentSample.capturedElapsedMs < startedAt || currentSample.rotationDegrees != lease.rotationDegrees)
            return unavailable("execution_sample_identity_or_freshness_mismatch")
        if (!hasCurrentModelObservationLease(execution)) return unavailable("lease_changed_during_capture")
        val expectedSize = lease.physicalFrameSize ?: return unavailable("physical_frame_size_missing")
        if (currentSample.width != expectedSize.first || currentSample.height != expectedSize.second)
            return unavailable("execution_sample_size_mismatch")
        if (currentSample.colors.size != reference.samples.size)
            return unavailable("execution_sample_count_mismatch")
        val colors = reference.samples.mapIndexed { index, point ->
            (point.physicalX to point.physicalY) to currentSample.colors[index]
        }.toMap()
        val comparison = ObservationPixelComparator.compare(reference, currentSample.width, currentSample.height,
            pixelAt = { x, y -> colors.getValue(x to y) }, channelDelta = policy.preActionObservationPixelDelta)

        if (!comparison.available) return unavailable("pixel_sampling_failed")

        val finishedAtElapsedMs = SystemClock.elapsedRealtime()
        val elapsedMs = (finishedAtElapsedMs - startedAt).coerceAtLeast(0L)
        val metrics = preActionObservationMetrics(
            elapsedMs = elapsedMs,
            comparison = comparison,
            available = true,
            lease = lease,
            capturedAtMs = currentSample.capturedAtMs,
            sampleSide = policy.preActionObservationSampleSide
        ) + if (scrollCheckpoint != null) mapOf(
            "pre_action_batch_scroll_checkpoint" to 1L,
            "pre_action_batch_scroll_captured_elapsed_ms" to scrollCheckpoint.capturedAtElapsedMs
        ) else emptyMap()
        if (targetRegion != null) {
            val component = lease.foregroundComponent ?: return unavailable("decision_foreground_missing")
            val current = localActionVerifier.currentForegroundComponentForContext(execution)
            if (current != component) return unavailable("regional_drag_foreground_changed")
            if (!hasCurrentModelObservationLease(execution)) return unavailable("lease_changed_during_region_check")
            val bounds = currentModelCoordinateMapper.toPhysicalBounds(
                targetRegion.left, targetRegion.top, targetRegion.right, targetRegion.bottom)
            val decision = PreActionFreshness.decideRegion(reference, currentSample.width, currentSample.height,
                { x, y -> colors.getValue(x to y) }, intArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                policy.preActionObservationPixelDelta)
            val local = decision.local
            // This exception to the decision boundary always enforces the declared region. Moving
            // content, unchanged pixels elsewhere, and an observe-only global policy cannot waive it.
            return PreActionObservationCheck(
                if (decision.allowed) null else ToolProtocolResult.notExecuted(
                    "PRE_ACTION_OBSERVATION_CHANGED", true, details = buildJsonObject {
                        put("target_check", if (local == null) "region_unavailable" else "region_changed")
                        put("action_delivered", false)
                    }),
                metrics + mapOf(
                    "pre_action_region_sampled_pixels" to (local?.sampledPixels?.toLong() ?: 0L),
                    "pre_action_region_changed_fraction_ppm" to
                        (local?.changedFraction?.times(1_000_000)?.roundToLong() ?: -1L)
                ), SystemClock.elapsedRealtime())
        }
        if (policy.preActionObservationMode == "enforce") {
            val pending = pendingActionTarget.takeIf { usePendingTarget }
            val effectiveTargetId = targetId ?: pending?.targetId
            val x = targetX ?: pending?.targetX
            val y = targetY ?: pending?.targetY
            val target = effectiveTargetId?.let { observedTargetCache.resolveModelPoint(it) }
                ?: if (x != null && y != null) x to y else null
            // Both a single action and every group member use the same target check. Samples come
            // from the original uncompressed frame, not a second observation or a decoded JPEG.
            fun targetBounds(point: Pair<Int, Int>, multiplier: Int): IntArray {
                val radius = policy.preActionTargetRadius * multiplier
                val topLeft = currentModelCoordinateMapper.toPhysical(
                    (point.first - radius).coerceIn(0, 1000), (point.second - radius).coerceIn(0, 1000))
                val bottomRight = currentModelCoordinateMapper.toPhysical(
                    (point.first + radius).coerceIn(0, 1000), (point.second + radius).coerceIn(0, 1000))
                return intArrayOf(minOf(topLeft.x, bottomRight.x), minOf(topLeft.y, bottomRight.y),
                    maxOf(topLeft.x, bottomRight.x), maxOf(topLeft.y, bottomRight.y))
            }
            fun decide(decisionReference: ObservationPixelReference, pixelAt: (Int, Int) -> Int) =
                PreActionFreshness.decide(decisionReference, currentSample.width, currentSample.height, pixelAt, target,
                    ::targetBounds, TARGET_RADIUS_MULTIPLIERS, policy.preActionObservationChangeFraction,
                    policy.preActionObservationPixelDelta)
            val decision = decide(reference) { px, py -> colors.getValue(px to py) }
            val localComparison = decision.local
            var targetMetrics = metrics + mapOf(
                "pre_action_target_sampled_pixels" to (localComparison?.sampledPixels?.toLong() ?: 0L),
                "pre_action_target_changed_fraction_ppm" to
                    (localComparison?.changedFraction?.times(1_000_000)?.roundToLong() ?: -1L)
            )
            if (decision.allowed) {
                return PreActionObservationCheck(null, targetMetrics, finishedAtElapsedMs)
            }
            // Video changes its samples whatever the Agent decided. Read the same samples again and
            // repeat the decision on those that held still; a page that really changed still differs
            // there, and a screen that is almost entirely moving keeps the rejection.
            val motion = motionTolerantDecision(execution, reference, currentSample, policy, ::decide)
            targetMetrics = targetMetrics + motion.metrics
            if (motion.allowed) {
                return PreActionObservationCheck(null, targetMetrics, SystemClock.elapsedRealtime())
            }
            // A control laid over moving content keeps its pixels across seconds while the content does
            // not. Judge the tap once more on the samples that matched every earlier frame of the same
            // surface; a page that really changed differs on exactly those samples.
            if (target != null && scrollCheckpoint == null) {
                val stable = stableInterfaceDecision(execution, lease, reference, policy, ::decide) { px, py -> colors.getValue(px to py) }
                targetMetrics = targetMetrics + stable.metrics
                if (stable.allowed) return PreActionObservationCheck(null, targetMetrics, SystemClock.elapsedRealtime())
            }
            // A swipe without a control target acts on the viewport, not on pixels: full-screen video
            // can change every sample between decision and action. It stays grounded while the same
            // activity or window is in front; another surface is a real page change.
            if (surfaceGesture && target == null && scrollCheckpoint == null) {
                val decided = lease.foregroundComponent
                val current = decided?.let { localActionVerifier.currentForegroundComponentForContext(execution) }
                val sameSurface = decided != null && current == decided
                targetMetrics = targetMetrics + mapOf("pre_action_same_surface_gesture" to if (sameSurface) 1L else 0L)
                if (sameSurface) return PreActionObservationCheck(null, targetMetrics, SystemClock.elapsedRealtime())
            }
            // Reject immediately and attach the fresh recovery frame through the normal
            // recovery path. Waiting for an entire video to become still cannot repair a target.
            return PreActionObservationCheck(
                rejection = ToolProtocolResult.notExecuted(
                    code = "PRE_ACTION_OBSERVATION_CHANGED",
                    retryable = true,
                    details = buildJsonObject {
                        put("changed_fraction_ppm", JsonPrimitive(metrics.getValue("pre_action_observation_changed_fraction_ppm")))
                        put("target_check", JsonPrimitive(if (localComparison == null) "unavailable" else "changed"))
                        target?.let {
                            put("target_x", JsonPrimitive(it.first))
                            put("target_y", JsonPrimitive(it.second))
                        }
                        put("target_sampled_pixels", JsonPrimitive(localComparison?.sampledPixels ?: 0))
                        put("action_delivered", JsonPrimitive(false))
                    }
                ),
                metrics = targetMetrics,
                finishedAtElapsedMs = finishedAtElapsedMs
            )
        }
        return PreActionObservationCheck(
            rejection = null,
            metrics = metrics,
            finishedAtElapsedMs = finishedAtElapsedMs
        )
    }

    private class MotionDecision(val allowed: Boolean, val metrics: Map<String, Long>)

    /**
     * Repeats [decide] without the decision samples in recurring motion across the time-ordered
     * frames of the same surface, including those sampled while the Agent decided.
     */
    private fun stableInterfaceDecision(
        execution: ExecutionContext,
        lease: ObservationLease,
        reference: ObservationPixelReference,
        policy: com.droidance.core.agent.config.RuntimeThresholds,
        decide: (ObservationPixelReference, (Int, Int) -> Int) -> PreActionFreshness.Decision,
        livePixelAt: (Int, Int) -> Int
    ): MotionDecision {
        val foreground = lease.foregroundComponent ?: return MotionDecision(false, emptyMap())
        val now = SystemClock.elapsedRealtime()
        val frames = (surfaceHistory(execution, foreground, reference, now - policy.preActionStableHistoryWindowMs)
            .map { it.capturedAtElapsedMs to it.reference.colors } + (lease.decisionCapturedAtElapsedMs to reference.colors))
            .sortedBy { it.first }
        val span = frames.last().first - frames.first().first
        if (frames.size < 3 || span < policy.preActionStableHistoryMinSpanMs) {
            return MotionDecision(false, mapOf("pre_action_stable_history_frames" to frames.size.toLong(),
                "pre_action_stable_history_span_ms" to span))
        }
        val kept = PreActionFreshness.withoutRecurringMotion(reference, frames.map { it.second },
            policy.preActionObservationPixelDelta, policy.preActionStableMotionChanges)
        val decision = decide(kept, livePixelAt)
        val allowed = decision.allowed && decision.local != null
        return MotionDecision(allowed, mapOf(
            "pre_action_stable_history_frames" to frames.size.toLong(),
            "pre_action_stable_history_span_ms" to span,
            "pre_action_stable_fraction_ppm" to (kept.samples.size * 1_000_000L / reference.samples.size.coerceAtLeast(1)),
            "pre_action_stable_target_changed_fraction_ppm" to
                (decision.local?.changedFraction?.times(1_000_000)?.roundToLong() ?: -1L),
            "pre_action_stable_tolerated" to if (allowed) 1L else 0L
        ))
    }

    private val samplingScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    @Volatile private var surfaceSampling: Job? = null

    /**
     * While the Agent decides on [lease], sample its pixel grid about once per interval into the
     * surface history. Sampling stops at the next action check, another binding, another execution
     * or another foreground surface, and after the history window.
     */
    private fun startSurfaceSampling(lease: ObservationLease) {
        surfaceSampling?.cancel()
        val policy = ruleConfigManager.harnessPolicy().runtimeThresholds
        val reference = lease.reference ?: return
        val foreground = lease.foregroundComponent ?: return
        if (policy.preActionSurfaceSamplingIntervalMs <= 0) return
        surfaceSampling = samplingScope.launch {
            val points = reference.samples.map { it.physicalX to it.physicalY }
            val started = SystemClock.elapsedRealtime()
            while (currentModelObservationLease === lease &&
                SystemClock.elapsedRealtime() - started < policy.preActionStableHistoryWindowMs) {
                kotlinx.coroutines.delay(policy.preActionSurfaceSamplingIntervalMs)
                val execution = executionContextStore.current() ?: break
                if (execution.executionId != lease.executionId || execution.displayId != lease.displayId ||
                    currentModelObservationLease !== lease) break
                val sample = try { deviceBackend.samplePixels(points) } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { null } ?: continue
                if (sample.displayId != lease.displayId || sample.backendSessionId != lease.backendSessionId ||
                    sample.rotationDegrees != lease.rotationDegrees || sample.colors.size != points.size) continue
                val current = try { localActionVerifier.currentForegroundComponentForContext(execution) }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                // Another surface ends this history; its frames must never vouch for the decided one.
                if (current != foreground) break
                rememberSurfaceFrame(ObservationPixelReference(reference.samples, sample.colors), current, sample.capturedElapsedMs)
            }
        }
    }

    fun stopSurfaceSampling() {
        surfaceSampling?.cancel()
        surfaceSampling = null
    }

    private suspend fun motionTolerantDecision(
        execution: ExecutionContext,
        reference: ObservationPixelReference,
        first: com.droidance.core.device.agent.device.FreshPixelSample,
        policy: com.droidance.core.agent.config.RuntimeThresholds,
        decide: (ObservationPixelReference, (Int, Int) -> Int) -> PreActionFreshness.Decision
    ): MotionDecision {
        if (policy.preActionMotionReadings <= 0) return MotionDecision(false, emptyMap())
        val started = SystemClock.elapsedRealtime()
        val points = reference.samples.map { it.physicalX to it.physicalY }
        val readings = mutableListOf(first.colors)
        repeat(policy.preActionMotionReadings) {
            kotlinx.coroutines.delay(policy.preActionMotionIntervalMs)
            val next = try { deviceBackend.samplePixels(points) } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { null }
            if (next == null || next.displayId != execution.displayId || next.backendSessionId != execution.backendSessionId ||
                next.width != first.width || next.height != first.height || next.colors.size != first.colors.size ||
                next.rotationDegrees != first.rotationDegrees) {
                return MotionDecision(false, mapOf("pre_action_motion_readings_unavailable" to 1L))
            }
            readings += next.colors
        }
        val still = PreActionFreshness.stillReference(reference, readings, policy.preActionObservationPixelDelta)
        val stillFraction = still.samples.size.toDouble() / reference.samples.size.coerceAtLeast(1)
        val latest = readings.last()
        val latestColors = reference.samples.mapIndexed { index, point -> (point.physicalX to point.physicalY) to latest[index] }.toMap()
        val enoughStill = stillFraction >= policy.preActionMotionMinStillFraction
        val decision = if (enoughStill) decide(still) { px, py -> latestColors.getValue(px to py) } else null
        return MotionDecision(decision?.allowed == true, mapOf(
            "pre_action_motion_check_ms" to (SystemClock.elapsedRealtime() - started),
            "pre_action_motion_still_fraction_ppm" to (stillFraction * 1_000_000).roundToLong(),
            "pre_action_motion_still_changed_fraction_ppm" to
                (decision?.whole?.changedFraction?.times(1_000_000)?.roundToLong() ?: -1L),
            "pre_action_motion_target_changed_fraction_ppm" to
                (decision?.local?.changedFraction?.times(1_000_000)?.roundToLong() ?: -1L),
            "pre_action_motion_tolerated" to if (decision?.allowed == true) 1L else 0L,
            // Samples changed between the re-readings: part of the screen moves on its own.
            "pre_action_motion_moving" to if (still.samples.size < reference.samples.size) 1L else 0L
        ))
    }

    private fun preActionObservationMetrics(
        elapsedMs: Long,
        comparison: ObservationPixelComparison?,
        available: Boolean,
        lease: ObservationLease? = null,
        capturedAtMs: Long? = null,
        sampleSide: Int? = null
    ): Map<String, Long> = buildMap {
        put("pre_action_observation_check_ms", elapsedMs)
        put("pre_action_observation_available", if (available) 1L else 0L)
        put("pre_action_observation_sampled_pixels", comparison?.sampledPixels?.toLong() ?: 0L)
        put("pre_action_observation_changed_pixels", comparison?.changedPixels?.toLong() ?: 0L)
        put(
            "pre_action_observation_changed_fraction_ppm",
            comparison?.changedFraction?.times(1_000_000.0)?.roundToLong() ?: 0L
        )
        put("pre_action_observation_max_channel_delta", comparison?.maxChannelDelta?.toLong() ?: 0L)
        put("pre_action_observation_total_channel_delta", comparison?.totalChannelDelta ?: 0L)
        put("pre_action_observation_changed", if (comparison != null && comparison.changedPixels > 0) 1L else 0L)
        sampleSide?.let { put("pre_action_observation_sample_side", it.toLong()) }
        lease?.let {
            put("pre_action_observation_decision_captured_elapsed_ms", it.decisionCapturedAtElapsedMs)
            put("pre_action_observation_display_id", it.displayId.toLong())
            put("pre_action_observation_rotation_degrees", it.rotationDegrees.toLong())
            put("pre_action_observation_sequence", it.sequence)
        }
        capturedAtMs?.let {
            put("pre_action_observation_execution_captured_wall_ms", it)
        }
    }

    private fun captureObservationPixelReference(observation: ObservationEnvelope): ObservationPixelReference? =
        observation.pixelReference

    fun invalidateModelFrameBinding() {
        stopSurfaceSampling()
        decisionObservation = null
        currentModelCoordinateMapper = ModelCoordinateMapper.identity()
        currentModelObservationLease = null
    }

    fun invalidatePendingObservation() {
        autoCaptureJob?.cancel()
        reusablePostActionFrame = null
        observationEnvelopeStore.invalidate()
        // A failed/page-changing action must not let the short SidecarScreenCapture cache or the
        // ObserveScreenTool semantic cache reintroduce the pre-action frame on the forced refresh.
        observeScreenTool.invalidateCache()
        deviceBackend.invalidateScreenCache()
    }

    fun consumePendingObservation(): ObservationEnvelope? {
        val execution = executionContextStore.current() ?: return null
        return observationEnvelopeStore.consume(
            execution.executionId,
            execution.backendSessionId,
            execution.displayId
        )
    }

    suspend fun awaitPendingScreenshot(timeoutMs: Long): Boolean {
        val job = autoCaptureJob
        if (job != null && !job.isCompleted) {
            withTimeoutOrNull(timeoutMs) { job.join() } ?: return false
        }
        return currentPendingObservation() != null
    }

    private fun frameConfig(): VisionFrameScaler.Config =
        ObservationFrameConfig
            .fromPolicy(ruleConfigManager.harnessPolicy().visionFrame)
            .scalerConfig()

    fun prepareVisionFrame(frame: DeviceScreenFrame): VisionFrameScaler.ScaledFrame? {
        val config = frameConfig()
        return frame.modelFrame(config)
    }

    fun publishPreparedVisionFrame(
        token: ObservationEnvelopeStore.CaptureToken,
        mode: String,
        readableText: String? = null,
        preparedFrame: VisionFrameScaler.ScaledFrame? = null,
        screenText: String? = null,
        includeImage: Boolean = true,
        producedAtMs: Long? = null,
        nativeFrame: DeviceScreenFrame
    ): Boolean {
        val frame = if (includeImage) {
            preparedFrame ?: prepareVisionFrame(nativeFrame)
        } else {
            null
        }
        val published = observationEnvelopeStore.publish(
            token = token,
            capturedAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
            imageBase64 = if (includeImage) frame?.base64Jpeg ?: nativeFrame.base64() else null,
            mode = mode,
            frameScale = frame?.scale ?: 1f,
            frameSize = frame?.modelSize,
            physicalFrameSize = frame?.physicalSize,
            frameOrigin = 0 to 0,
            readableText = readableText,
            screenText = screenText,
            producedAtMs = producedAtMs,
            pixelReference = nativeFrame.pixelReference,
            nativeImagePng = nativeFrame.pngBytes.takeIf { includeImage },
            nativeFrameSha256 = if (includeImage) nativeFrame.sha256 else null,
            nativeVisualFingerprint = if (includeImage) nativeFrame.visualFingerprint else null,
            foregroundComponent = nativeFrame.foregroundComponent
        )
        if (frame != null) {
            val kb = frame.byteCount / 1024
            timingRecorder.record(
                name = "vision_frame",
                durationMs = 0,
                success = true,
                detail = "physical=${frame.physicalWidth}x${frame.physicalHeight} model=${frame.width}x${frame.height} scale=${frame.scale} bytes=${kb}KB"
            )
        }
        return published
    }

    fun publishVisionFrame(
        token: ObservationEnvelopeStore.CaptureToken,
        frame: com.droidance.tools.observe.ObserveScreenTool.PublishedVisionFrame
    ): Boolean {
        return observationEnvelopeStore.publish(
            token = token,
            capturedAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
            imageBase64 = frame.imageBase64,
            mode = frame.mode,
            frameScale = frame.frameScale,
            frameSize = frame.frameSize,
            physicalFrameSize = frame.physicalFrameSize,
            frameOrigin = frame.physicalOrigin,
            readableText = frame.readableText,
            screenText = frame.screenText,
            producedAtMs = frame.producedAtMs,
            pixelReference = frame.pixelReference,
            nativeImagePng = frame.nativeImagePng,
            nativeFrameSha256 = frame.nativeFrameSha256,
            nativeVisualFingerprint = frame.nativeVisualFingerprint,
            foregroundComponent = frame.foregroundComponent
        )
    }

    suspend fun captureSettledObservation(
        waitBeforeFrameMs: Long = 0,
        onCapture: () -> Unit = {},
        actionEndedAtElapsedMs: Long? = lastActionEndedAtElapsedMs
    ): LocalActionEvidence.ObservationLite = captureBoundedObservation(
        "action_capture_policy", waitBeforeFrameMs, onCapture, actionEndedAtElapsedMs = actionEndedAtElapsedMs)

    suspend fun captureGestureObservation(): LocalActionEvidence.ObservationLite =
        captureBoundedObservation("gesture_capture_policy")

    /**
     * Waits with light samples until rendering is quiet, without capturing evidence. Used before
     * typing into an editor that a tap just opened, so page initialization cannot race the input.
     */
    suspend fun awaitRenderingSettled(section: String): VisualFrameSampler.Result {
        val policy = capturePolicy(section)
        return com.droidance.core.device.tools.VisualFrameSampler(
            capture = { deviceBackend.sampleScreen() }, elapsedMillis = { SystemClock.elapsedRealtime() })
            .sample(policy.timeoutMs, policy.intervalMs, policy.stableSamples, policy.pixelDelta,
                policy.changedFraction, policy.localizedMotionMaxFraction, policy.localizedMotionSamples, policy.minStableMs)
    }

    private fun capturePolicy(section: String): com.droidance.core.device.tools.PostActionFrameCapture.Policy {
        val config = Json.parseToJsonElement(ruleConfigManager.readMarkdownSection(
            "observation_recovery.md", section)).jsonObject
        return com.droidance.core.device.tools.PostActionFrameCapture.Policy(
            initialWaitMs = checkNotNull(config["initialWaitMs"]?.jsonPrimitive?.longOrNull),
            timeoutMs = checkNotNull(config["timeoutMs"]?.jsonPrimitive?.longOrNull),
            intervalMs = checkNotNull(config["intervalMs"]?.jsonPrimitive?.longOrNull),
            stableSamples = checkNotNull(config["stableSamples"]?.jsonPrimitive?.intOrNull),
            pixelDelta = checkNotNull(config["pixelDelta"]?.jsonPrimitive?.intOrNull),
            changedFraction = checkNotNull(config["changedFraction"]?.jsonPrimitive?.doubleOrNull),
            localizedMotionMaxFraction = checkNotNull(config["localizedMotionMaxFraction"]?.jsonPrimitive?.doubleOrNull),
            localizedMotionSamples = checkNotNull(config["localizedMotionSamples"]?.jsonPrimitive?.intOrNull),
            settleExtensionMs = checkNotNull(config["settleExtensionMs"]?.jsonPrimitive?.longOrNull),
            lateChangeProbeMs = checkNotNull(config["lateChangeProbeMs"]?.jsonPrimitive?.longOrNull),
            newViewChangedFraction = checkNotNull(config["newViewChangedFraction"]?.jsonPrimitive?.doubleOrNull),
            quietViewChangedFraction = checkNotNull(config["quietViewChangedFraction"]?.jsonPrimitive?.doubleOrNull),
            minStableMs = checkNotNull(config["minStableMs"]?.jsonPrimitive?.longOrNull)
        )
    }

    suspend fun captureBoundedObservation(section: String, waitBeforeFrameMs: Long = 0,
        onCapture: () -> Unit = {}, onReadiness: (VisualFrameSampler.Result) -> Unit = {},
        actionEndedAtElapsedMs: Long? = lastActionEndedAtElapsedMs): LocalActionEvidence.ObservationLite {
        val policy = capturePolicy(section)
        val sampler = com.droidance.core.device.tools.VisualFrameSampler(
            capture = { deviceBackend.sampleScreen() }, elapsedMillis = { SystemClock.elapsedRealtime() })
        val (readiness, latest) = com.droidance.core.device.tools.PostActionFrameCapture(sampler, { SystemClock.elapsedRealtime() })
            .capture(policy, waitBeforeFrameMs, actionEndedAtElapsedMs, changedFromDecision = {
                // How far the live screen moved from the frame the Agent decided on; a large move is a new view.
                executionContextStore.current()?.let { execution ->
                    checkPreActionObservation(execution, usePendingTarget = false).metrics.takeIf {
                        it["pre_action_observation_available"] == 1L
                    }?.get("pre_action_observation_changed_fraction_ppm")?.let { it / 1_000_000.0 }
                }
            }, verifyFrame = { snapshot ->
                val native = snapshot.nativeFrame?.takeIf {
                    it.displayId == snapshot.displayId && it.pngBytes.contentEquals(snapshot.imagePng)
                }
                val reference = native?.pixelReference
                if (reference == null) null else {
                    val started = SystemClock.elapsedRealtime()
                    val sample = deviceBackend.samplePixels(reference.samples.map { it.physicalX to it.physicalY })
                    if (sample == null || sample.displayId != snapshot.displayId ||
                        sample.backendSessionId != snapshot.backendSessionId || sample.capturedElapsedMs < started ||
                        sample.width != snapshot.width || sample.height != snapshot.height ||
                        sample.colors.size != reference.samples.size) null
                    else {
                        val colors = reference.samples.mapIndexed { index, point ->
                            (point.physicalX to point.physicalY) to sample.colors[index]
                        }.toMap()
                        ObservationPixelComparator.compare(reference, sample.width, sample.height,
                            pixelAt = { x, y -> colors.getValue(x to y) }, channelDelta = policy.pixelDelta)
                    }
                }
            }) {
            localActionVerifier.captureDisplaySnapshot(preferLastFrame = false, forceFresh = true).also { onCapture() }
        }
        onReadiness(readiness)
        coroutineContext[ObservationTiming]?.apply {
            recordRendering(section, readiness)
            add("observation_settle_samples", readiness.captures.toLong())
            add("observation_settle_wait_ms", readiness.elapsedMs)
            add("observation_settle_stable", if (readiness.stable) 1 else 0)
            add("observation_settle_localized_motion", if (readiness.localizedMotion) 1 else 0)
        }
        val fingerprint = ObservationTiming.measure("observation_fingerprint_ms") {
            latest.nativeFrame?.visualFingerprint
        }
        latest.nativeFrame?.let { rememberSurfaceFrame(it.pixelReference, it.foregroundComponent, SystemClock.elapsedRealtime()) }
        return stageReusablePostActionFrame(localActionVerifier.describeSnapshot(latest, visualFingerprint = fingerprint))
    }

    suspend fun captureSettledAutoFrame(waitBeforeFrameMs: Long = 0): DeviceScreenFrame? {
        captureSettledObservation(waitBeforeFrameMs)
        return consumeReusablePostActionFrame(deviceBackend.activeDisplayId)
    }

    fun stageReusablePostActionFrame(
        observation: LocalActionEvidence.ObservationLite
    ): LocalActionEvidence.ObservationLite {
        val observationHash = observation.screenHash ?: return observation
        val activeDisplayId = deviceBackend.activeDisplayId
        val frame = deviceBackend.lastScreenFrame()
            ?.takeIf { it.displayId == activeDisplayId }
            ?.takeIf { it.contentHash == observationHash }
            ?: return observation
        reusablePostActionFrame = frame
        return observation
    }

    fun consumeReusablePostActionFrame(displayId: Int?): DeviceScreenFrame? {
        val frame = reusablePostActionFrame ?: return null
        reusablePostActionFrame = null
        val maxAgeMs = postActionReusableFrameTtlMs()
        val ageMs = System.currentTimeMillis() - frame.capturedAtMs
        return frame.takeIf { it.displayId == displayId && ageMs in 0..maxAgeMs }
    }

    fun postActionReusableFrameTtlMs(): Long =
        ruleConfigManager.harnessPolicy().runtimeThresholds.postActionReusableFrameTtlMs
}

/** Neighborhood sizes, in units of pre_action_target_radius, tried for a structured target area. */
private val TARGET_RADIUS_MULTIPLIERS = listOf(1, 2, 4)
