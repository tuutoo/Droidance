// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.config.RuleConfigManager
import com.droidance.core.knowledge.memory.LongTermMemoryStore
import com.droidance.memory.MarkdownFileManager
import com.droidance.core.knowledge.memory.ProfileStore
import javax.inject.Inject
import javax.inject.Singleton

/** Owns Agent workspace files and initialization. */
@Singleton
class AgentWorkspaceStore @Inject constructor(
    private val fileManager: MarkdownFileManager,
    private val ruleConfigManager: RuleConfigManager,
    private val profileStore: ProfileStore,
    private val longTermMemoryStore: LongTermMemoryStore
) {
    private data class FileCacheEntry(val mtime: Long, val content: String)

    @Volatile private var soulCache: Pair<String, FileCacheEntry>? = null

    fun readSoul(agentName: String = DEFAULT_AGENT): String =
        readSoul(agentName, defaultSoul())

    fun readCustomSoul(agentName: String = DEFAULT_AGENT): String {
        val defaultSoul = defaultSoul()
        val soul = readSoul(agentName, defaultSoul).trim()
        return soul.takeUnless { it == defaultSoul.trim() }.orEmpty()
    }

    /** Stores only a persona that differs from the built-in default, so default updates reach every user. */
    fun writeSoul(content: String, agentName: String = DEFAULT_AGENT) {
        val path = soulPath(agentName)
        if (content.trim() == defaultSoul()) {
            if (fileManager.exists(path)) check(fileManager.deleteFile(path)) { "Cannot reset the persona" }
            soulCache = null
            return
        }
        fileManager.writeFile(path, content)
        soulCache = agentName to FileCacheEntry(fileManager.lastModified(path), content)
    }

    fun initializeAgent(agentName: String) {
        // Earlier builds wrote the default persona as a file; an unchanged copy would pin that default.
        val path = soulPath(agentName)
        if (fileManager.readFile(path)?.trim() == defaultSoul()) check(fileManager.deleteFile(path)) { "Cannot reset the persona" }
        longTermMemoryStore.initializeIfMissing(agentName)
    }

    fun listAgents(): List<String> = fileManager.listFiles(AGENTS_DIR)

    fun initializeDefault() {
        initializeAgent(DEFAULT_AGENT)
        profileStore.initializeIfMissing("# User Profile\n\n")
    }

    private fun readSoul(agentName: String, fallback: String): String {
        val path = soulPath(agentName)
        val cached = soulCache?.takeIf { it.first == agentName }?.second
        val mtime = fileManager.lastModified(path)
        if (cached != null && cached.mtime == mtime) return cached.content
        val content = fileManager.readFile(path) ?: fallback
        soulCache = agentName to FileCacheEntry(mtime, content)
        return content
    }

    private fun soulPath(agentName: String): String = "$AGENTS_DIR/$agentName/SOUL.md"

    private fun defaultSoul(): String =
        ruleConfigManager.readMarkdownSection("agent_core.md", "default_soul").trim()

    private companion object {
        const val AGENTS_DIR = "agents"
        const val DEFAULT_AGENT = "default"
    }
}
