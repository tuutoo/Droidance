// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

enum class UserInteractionKind {
    DEVICE_TAKEOVER,
    HIGH_RISK_CONFIRMATION,
    CHOICE,
    PERMISSION
}

enum class UserInteractionState {
    PENDING,
    RESOLVED,
    DENIED,
    EXPIRED,
    CANCELED
}

enum class UserInteractionResponseSource {
    LOCAL_DEVICE,
    RUNTIME
}

data class UserInteractionRequest(
    val requestId: String,
    val conversationId: String?,
    val kind: UserInteractionKind,
    val reason: String,
    val requestedAtMs: Long,
    val state: UserInteractionState = UserInteractionState.PENDING,
    val responseSource: UserInteractionResponseSource? = null,
    val resolvedAtMs: Long? = null,
    val turnId: String? = null,
    val toolName: String? = null
)
