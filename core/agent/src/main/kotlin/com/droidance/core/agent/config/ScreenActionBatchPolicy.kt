// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.config

import com.droidance.core.agent.config.*

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ScreenActionBatchPolicy(
    val enabled: Boolean,
    @SerialName("allowed_operations")
    val allowedOperations: List<String>,
    @SerialName("default_wait_ms")
    val defaultWaitMs: Long,
    @SerialName("min_wait_ms")
    val minWaitMs: Long,
    @SerialName("max_wait_ms")
    val maxWaitMs: Long,
    @SerialName("max_total_wait_ms")
    val maxTotalWaitMs: Long,
    @SerialName("max_capture_images")
    val maxCaptureImages: Int,
    @SerialName("max_capture_bytes")
    val maxCaptureBytes: Long,
    @SerialName("max_drag_repeat")
    val maxDragRepeat: Int,
    @SerialName("max_regional_drags")
    val maxRegionalDrags: Int,
    @SerialName("decision_boundary_operations")
    val decisionBoundaryOperations: List<String>
) {
    fun validate() {
        require(allowedOperations.distinct().size == allowedOperations.size)
        require(allowedOperations.isNotEmpty() && allowedOperations.all {
            it in com.droidance.core.agent.tools.ScreenActionBatchProtocol.operations
        })
        require(minWaitMs > 0)
        require(maxWaitMs >= minWaitMs)
        require(defaultWaitMs in minWaitMs..maxWaitMs)
        require(maxTotalWaitMs >= maxWaitMs)
        require(maxCaptureImages > 0)
        require(maxDragRepeat > 0)
        require(maxRegionalDrags > 0)
        require(decisionBoundaryOperations.distinct().size == decisionBoundaryOperations.size)
        require(decisionBoundaryOperations.all { it in allowedOperations })
        require(maxCaptureBytes in 1..maxCaptureImages.toLong() * 2 * com.droidance.core.agent.HistoricalObservationImages.MAX_IMAGE_BYTES)
    }
}
