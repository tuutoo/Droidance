// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus

/** Mechanical continuation requirements, not judgments about the user's goal. */
enum class ToolRecovery {
    NONE, SKIPPED, CHECKPOINT, RECORD, OBSERVE, REPLAN, COMPLETION_REVIEW, PROTOCOL_REPAIR;

    companion object {
        fun classify(result: ToolExecutionResult, refreshDiagnostics: Set<String> = emptySet()): ToolRecovery {
            val code = result.diagnosticCode ?: return NONE
            // Delivery may succeed while the final observation fails; retain that distinction.
            if (code == "POST_ACTION_OBSERVATION_UNAVAILABLE") return OBSERVE
            if (result.executionStatus == ToolExecutionStatus.SUCCEEDED) return NONE
            if (CompletionReviewGate.isReview(code)) return COMPLETION_REVIEW
            return when (code) {
                "PRIOR_CALL_FAILED", "PRIOR_CALL_REJECTED", "QUERY_RESULT_REQUIRED" -> SKIPPED
                "TASK_EXECUTION_BUDGET_EXHAUSTED" -> CHECKPOINT
                "WORKSPACE_REVIEW_REQUIRED" -> RECORD
                "OBSERVATION_RECOVERY_REQUIRED", "EXACT_ACTION_REPEATED", "TEXT_INPUT_BACKEND_NOT_READY" -> REPLAN
                "PRE_ACTION_OBSERVATION_CHANGED", "PRE_ACTION_OBSERVATION_UNAVAILABLE",
                "STALE_OBSERVATION", "COORDINATE_REFERENCE_STALE", "CROP_CURRENT_FRAME_BUDGET_EXHAUSTED",
                "CURRENT_SCREEN_REQUIRED", "BATCH_EXECUTION_SURFACE_CHANGED", "BATCH_TEXT_READBACK_REQUIRED",
                "TEXT_INPUT_TARGET_FOCUS_UNVERIFIED", "TEXT_INPUT_TARGET_NOT_FOUND",
                "TEXT_INPUT_TARGET_TRANSITIONED", "TEXT_INPUT_EFFECT_UNKNOWN", "VISIT_RETURN_NOT_CONFIRMED" -> OBSERVE
                else -> when {
                    code in refreshDiagnostics -> OBSERVE
                    result.executionStatus == ToolExecutionStatus.NOT_EXECUTED -> PROTOCOL_REPAIR
                    else -> NONE
                }
            }
        }
    }
}
