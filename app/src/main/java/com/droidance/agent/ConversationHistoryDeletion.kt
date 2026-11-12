// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.config.TokenUsageRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Singleton
class ConversationHistoryDeletion @Inject constructor(
    private val events: AgentEventStore,
    private val blobs: BlobStore,
    private val tasks: ConversationTaskController,
    private val runtime: AgentRuntime,
    private val tokenUsageRepository: TokenUsageRepository,
    private val processClient: com.droidance.runtime.RuntimeProcessClient
) {
    suspend fun delete(threadIds: Set<String>? = null) {
        if (!processClient.isOwner) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                processClient.command("deleteHistory", android.os.Bundle().apply {
                    threadIds?.let { putStringArrayList("threads", ArrayList(it)) }
                })
            }
            return
        }
        tasks.withHistoryMutation(threadIds) {
            // All production blob writers belong to this execution lane, including idle skill work.
            // UI attachment preparation retains encoded input; blobs are written when a Turn starts.
            runtime.withStorageMaintenance {
                val selected = threadIds ?: events.eventsByKind(AgentEventKind.THREAD_CREATED)
                    .map { it.threadId }.filterNot { it == INTERNAL_SKILL_THREAD_ID }.toSet()
                purgeConversationData(events, blobs, selected)
                tokenUsageRepository.deleteConversationUsage(threadIds)
                if (runtime.currentConversationId.value in selected) runtime.clearConversation()
            }
        }
    }
}

/** Caller owns the runtime execution lane, so no put-to-event-commit gap can race the sweep. */
internal suspend fun purgeConversationData(events: AgentEventStore, blobs: BlobStore, threadIds: Set<String>) {
    events.purgeThreads(threadIds)
    deleteUnreferencedBlobs(blobs, events.allReferencedBlobRefs())
}

/** Caller must hold the execution lane until all blob writers have committed their events. */
internal suspend fun deleteUnreferencedBlobs(blobs: BlobStore, referenced: Set<String>): Int {
    var deleted = 0
    for (ref in blobs.listAllRefs() - referenced) {
        currentCoroutineContext().ensureActive()
        if (!blobs.delete(ref) && ref in blobs.listAllRefs()) {
            throw java.io.IOException("An unreferenced attachment could not be deleted")
        }
        deleted++
    }
    return deleted
}
