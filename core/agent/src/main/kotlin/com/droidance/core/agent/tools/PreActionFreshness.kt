// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

/**
 * Decides whether the frame an action was planned on is still fresh. A changed target stops the
 * action; changes elsewhere do not. Samples that keep changing on their own, such as a playing
 * video, differ from any earlier frame whatever the Agent decided, so only samples that held still
 * right before the action can show that the page itself changed.
 */
object PreActionFreshness {
    data class Decision(
        val whole: ObservationPixelComparison,
        val local: ObservationPixelComparison?,
        val allowed: Boolean
    )

    /** A declared region must itself match; never widen it or fall back to the whole frame. */
    fun decideRegion(
        reference: ObservationPixelReference,
        width: Int,
        height: Int,
        pixelAt: (Int, Int) -> Int,
        bounds: IntArray,
        channelDelta: Int
    ): Decision {
        val whole = ObservationPixelComparator.compare(reference, width, height, pixelAt, channelDelta)
        val local = ObservationPixelComparator.compareTarget(reference, width, height, pixelAt, bounds, channelDelta)
        return Decision(whole, local, local != null && local.changedPixels == 0)
    }

    fun decide(
        reference: ObservationPixelReference,
        width: Int,
        height: Int,
        pixelAt: (Int, Int) -> Int,
        target: Pair<Int, Int>?,
        targetBounds: (Pair<Int, Int>, Int) -> IntArray,
        radiusMultipliers: List<Int>,
        changeFraction: Double,
        channelDelta: Int
    ): Decision {
        require(radiusMultipliers.isNotEmpty())
        val whole = ObservationPixelComparator.compare(reference, width, height, pixelAt, channelDelta)
        fun around(point: Pair<Int, Int>, multiplier: Int) = ObservationPixelComparator.compareTarget(
            reference, width, height, pixelAt, targetBounds(point, multiplier), channelDelta)
        // A flat target area, such as a plain search field, carries no comparable structure; widen
        // the neighborhood until it includes the control's edges or text.
        val near = target?.let { point -> radiusMultipliers.asSequence().mapNotNull { around(point, it) }.firstOrNull() }
        // Text changing inside a control (a rotating hint, a counter) changes the near area while the
        // control stays put. A layout shift also moves the wider neighborhood, so accept a changed
        // near area only when that neighborhood held still.
        val local = if (target != null && near != null &&
            !ObservationPixelComparator.allowsAction(whole, near, changeFraction))
            around(target, radiusMultipliers.last() * 2)?.takeIf {
                ObservationPixelComparator.allowsAction(whole, it, changeFraction / 4)
            } ?: near
        else near
        return Decision(whole, local, ObservationPixelComparator.allowsAction(whole, local, changeFraction))
    }

    /**
     * The decision-frame samples that are not in recurring motion across time-ordered frames of the
     * same surface. A playing video changes a sample again and again; a control laid over it does
     * not. A page change or a dialog changes a sample once and then holds, so that sample stays in
     * and the decision still sees the change. Frames are aligned with [reference] samples.
     */
    fun withoutRecurringMotion(
        reference: ObservationPixelReference,
        frames: List<IntArray>,
        channelDelta: Int,
        motionChanges: Int
    ): ObservationPixelReference {
        require(frames.size >= 2 && frames.all { it.size == reference.colors.size } && motionChanges >= 2)
        val kept = reference.colors.indices.filter { index ->
            frames.zipWithNext().count { (before, after) ->
                ObservationPixelComparator.channelDelta(before[index], after[index]) > channelDelta
            } < motionChanges
        }
        return ObservationPixelReference(
            samples = kept.map { reference.samples[it] },
            colors = IntArray(kept.size) { reference.colors[kept[it]] }
        )
    }

    /**
     * The decision-frame samples that did not change across consecutive live readings taken right
     * before the action. Readings are aligned with [reference] samples.
     */
    fun stillReference(
        reference: ObservationPixelReference,
        liveReadings: List<IntArray>,
        channelDelta: Int
    ): ObservationPixelReference {
        require(liveReadings.size >= 2 && liveReadings.all { it.size == reference.colors.size })
        val still = reference.colors.indices.filter { index ->
            liveReadings.zipWithNext().all { (before, after) ->
                ObservationPixelComparator.channelDelta(before[index], after[index]) <= channelDelta
            }
        }
        return ObservationPixelReference(
            samples = still.map { reference.samples[it] },
            colors = IntArray(still.size) { reference.colors[still[it]] }
        )
    }
}
