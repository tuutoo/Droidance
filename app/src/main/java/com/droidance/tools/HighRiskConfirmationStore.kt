// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.UserInteractionState
import com.droidance.core.agent.event.TurnEventScope
import java.util.concurrent.TimeUnit

/** A response belongs to the exact turn, call and payload. */
internal class HighRiskConfirmationStore(
    private val nowMs: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
    private val windowMs: Long = 60_000L
) {
    private data class Request(
        val callId: String,
        val actionKey: String,
        val expiresAtMs: Long,
        val approved: Boolean = false
    )

    private val pending = mutableMapOf<TurnEventScope, Request>()
    private val declined = mutableMapOf<TurnEventScope, MutableMap<String, UserInteractionState>>()

    @Synchronized
    fun consumeOrRequest(scope: TurnEventScope, callId: String, actionKey: String): UserInteractionState {
        declined[scope]?.get(actionKey)?.let { return it }
        val request = pending[scope]
        if (request?.callId == callId && request.actionKey == actionKey) {
            if (!request.approved) return UserInteractionState.PENDING
            pending.remove(scope)
            return UserInteractionState.RESOLVED
        }
        pending[scope] = Request(callId, actionKey, nowMs() + windowMs)
        return UserInteractionState.PENDING
    }

    @Synchronized
    fun remainingMs(scope: TurnEventScope, callId: String): Long? =
        pending[scope]?.takeIf { it.callId == callId && !it.approved }
            ?.let { (it.expiresAtMs - nowMs()).coerceAtLeast(0L) }

    @Synchronized
    fun respond(scope: TurnEventScope, callId: String, allow: Boolean?): UserInteractionState? {
        val request = pending[scope]?.takeIf { it.callId == callId && !it.approved } ?: return null
        val state = when {
            allow == null || nowMs() >= request.expiresAtMs -> UserInteractionState.EXPIRED
            allow -> UserInteractionState.RESOLVED
            else -> UserInteractionState.DENIED
        }
        if (state == UserInteractionState.RESOLVED) {
            pending[scope] = request.copy(approved = true)
        } else {
            pending.remove(scope)
            declined.getOrPut(scope, ::mutableMapOf)[request.actionKey] = state
        }
        return state
    }

    @Synchronized
    fun revoke(scope: TurnEventScope) {
        pending.remove(scope)
        declined.remove(scope)
    }
}
