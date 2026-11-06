// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.agent.config.*
import com.droidance.core.device.config.*

import com.droidance.config.*

import android.content.Context
import com.droidance.memory.MarkdownFileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

internal fun markdownSection(document: String, heading: String): String {
    val target = "## ${heading.trim()}"
    val lines = document.lines()
    val start = lines.indexOfFirst { it.trim() == target }
    require(start >= 0) { "Missing Markdown section '$heading'" }
    val relativeEnd = lines.drop(start + 1).indexOfFirst { line ->
        line.trimStart().startsWith("## ")
    }
    val end = if (relativeEnd >= 0) start + 1 + relativeEnd else lines.size
    return lines.subList(start + 1, end).joinToString("\n").trim()
}

/**
 * 内置规则与用户覆盖的唯一读取入口。
 *
 * Markdown 承载静态模型指令；YAML 分别承载本地 Harness 配置和条件化提示，并在启动时完整校验。
 * 用户覆盖位于 rules/overrides/，覆盖文件必须是完整文档，不与内置值做 union 或逐项合并。
 */
@Singleton
class RuleConfigManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileManager: MarkdownFileManager,
    private val languageManager: LanguageManager
) : com.droidance.core.agent.ports.RuleSource {
    companion object {
        private const val ASSET_DIR = "rules"
        private const val OVERRIDE_DIR = "rules/overrides"
        private const val VERSION_FILE = "rules/RULES_VERSION"
        private const val BUILTIN_VERSION = "2026-09-14-2"
        private const val HARNESS_POLICY_FILE = "harness_policy.yaml"
        private const val HARNESS_PROMPTS_FILE = "harness_prompts.yaml"
        private const val TOOL_CONTRACTS_FILE = "tool_contracts.yaml"
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9._-]+")
    }

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
    }
    private val yaml = Load(
        LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .build()
    )

    @Volatile
    private var harnessPolicyCache: HarnessPolicy? = null

    @Volatile
    private var harnessPromptPolicyCache: HarnessPromptPolicy? = null

    @Volatile
    private var toolContractsCache: ToolContractsDocument? = null

    private val documentCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Set when user overrides fail validation against the installed rules; they stay on disk unused. */
    @Volatile
    private var overridesSuspended = false

    fun initialize() {
        // 内置配置错误必须在启动阶段直接暴露；用户 override 与新版本不兼容时停用 override，回到内置规则。
        withOverrideFallback { validate() }
        fileManager.writeFile(VERSION_FILE, BUILTIN_VERSION)
    }

    /**
     * Rules may be read before [initialize] (while the dependency graph is built). Any read that
     * fails while user overrides exist suspends all overrides and retries with built-in rules only;
     * a failure of the built-in rules themselves still surfaces.
     */
    private fun <T> withOverrideFallback(read: () -> T): T = try {
        read()
    } catch (error: Exception) {
        if (!suspendOverrides(error)) throw error
        read()
    }

    @Synchronized
    private fun suspendOverrides(error: Exception): Boolean {
        if (overridesSuspended || fileManager.listFiles(OVERRIDE_DIR).isEmpty()) return false
        android.util.Log.w("RuleConfig", "Rule overrides are incompatible with the installed rules; using built-in rules", error)
        overridesSuspended = true
        harnessPolicyCache = null
        harnessPromptPolicyCache = null
        toolContractsCache = null
        documentCache.clear()
        return true
    }

    private fun validate() {
        harnessPolicy()
        harnessPrompts()
        publicFallbackDocument()
        toolContracts()
        json.decodeFromString<com.droidance.core.agent.ObservationRecoveryPolicy>(
            readMarkdownSection("observation_recovery.md", "policy")
        ).validate()
        json.decodeFromString<com.droidance.core.agent.context.ContextCompactionExecutionPolicy>(
            readMarkdownSection("context_compaction.md", "execution_policy")
        ).validate()
        json.decodeFromString<com.droidance.core.agent.StoppedTaskReplyPolicy>(
            readMarkdownSection("stopped_task_reply.md", "policy")
        ).validate()
        json.decodeFromString<com.droidance.core.agent.workspace.TaskExecutionPolicy>(
            readMarkdownSection("workspace.md", "execution_policy")
        ).validate()
        json.decodeFromString<com.droidance.core.agent.workspace.WorkspaceContextPolicy>(
            readMarkdownSection("workspace.md", "context_policy")
        ).validate()
        json.decodeFromString<com.droidance.core.agent.ReasoningControlPolicy>(
            readMarkdownSection("agent_core.md", "reasoning_control_policy")
        ).validate()
        require(readMarkdownSection("model_context.md", "recent_tool_feedback_limit").trim().toInt() > 0)
    }

    private fun readOverride(fileName: String): String? =
        if (overridesSuspended) null else fileManager.readFile("$OVERRIDE_DIR/$fileName")

    fun harnessPolicy(): HarnessPolicy {
        harnessPolicyCache?.let { return it }
        return withOverrideFallback { synchronized(this) {
            harnessPolicyCache ?: loadYaml(
                fileName = HARNESS_POLICY_FILE,
                deserializer = HarnessPolicy.serializer()
            ).also {
                it.validate()
                harnessPolicyCache = it
            }
        } }
    }

    fun toolContracts(): ToolContractsDocument {
        toolContractsCache?.let { return it }
        return withOverrideFallback { synchronized(this) {
            toolContractsCache ?: loadYaml(
                fileName = TOOL_CONTRACTS_FILE,
                deserializer = ToolContractsDocument.serializer()
            ).also {
                it.validate()
                toolContractsCache = it
            }
        } }
    }

    fun harnessPrompts(): HarnessPromptPolicy {
        harnessPromptPolicyCache?.let { return it }
        return withOverrideFallback { synchronized(this) {
            harnessPromptPolicyCache ?: loadYaml(
                fileName = HARNESS_PROMPTS_FILE,
                deserializer = HarnessPromptPolicy.serializer()
            ).also {
                it.validate()
                harnessPromptPolicyCache = it
            }
        } }
    }

    fun message(key: String): String =
        harnessPrompts().messages[key]
            ?: error("Missing harness message: $key")

    private fun publicFallbackDocument(): LocalizedPublicFallbacks =
        json.decodeFromString<LocalizedPublicFallbacks>(
            readMarkdownSection("public_fallbacks.md", "translations")
        ).also { it.validate() }

    fun publicFallback(key: String): String =
        publicFallbackDocument().text(languageManager.resolved().tag, key)

    fun terminationFallback(reasonMarker: String): String {
        val reasonKey = reasonMarker.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
        return publicFallbackDocument().termination(languageManager.resolved().tag, reasonKey)
    }

    /** 保存完整 override 后调用；不会回退或合并旧格式。 */
    fun invalidate(filePath: String) {
        val fileName = filePath.substringAfterLast('/')
        when (fileName) {
            HARNESS_POLICY_FILE -> harnessPolicyCache = null
            HARNESS_PROMPTS_FILE -> harnessPromptPolicyCache = null
            TOOL_CONTRACTS_FILE -> toolContractsCache = null
        }
        documentCache.remove(fileName)
    }

    /** 读取 Markdown 文档。用户 override 存在时完整替换内置文档。 */
    fun readDocument(fileName: String): String {
        require(fileName.matches(SAFE_FILE_NAME)) { "Invalid rule file name: $fileName" }
        return documentCache[fileName] ?: synchronized(documentCache) {
            documentCache[fileName] ?: (
                readOverride(fileName)
                    ?: readAsset("$ASSET_DIR/$fileName")
                    ?: error("Missing rule document: $fileName")
                ).also { documentCache[fileName] = it }
        }
    }

    /** 读取指定二级 Markdown 标题下的正文。 */
    override fun readMarkdownSection(fileName: String, heading: String): String {
        return runCatching { withOverrideFallback { markdownSection(readDocument(fileName), heading) } }
            .getOrElse { error ->
                throw IllegalArgumentException(
                    "Missing Markdown section '$heading' in $fileName",
                    error
                )
            }
    }

    private fun <T> loadYaml(
        fileName: String,
        deserializer: DeserializationStrategy<T>
    ): T {
        val content = readOverride(fileName)
            ?: readAsset("$ASSET_DIR/$fileName")
            ?: error("Missing YAML rule file: $fileName")
        return try {
            val parsed = yaml.loadFromString(content)
                ?: error("YAML document is empty")
            json.decodeFromJsonElement(deserializer, parsed.toJsonElement())
        } catch (error: Exception) {
            throw IllegalStateException("Invalid $fileName: ${error.message}", error)
        }
    }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(entries.associate { (key, value) ->
            require(key is String && key.isNotBlank()) { "YAML mapping keys must be non-blank strings" }
            key to value.toJsonElement()
        })
        is List<*> -> JsonArray(map { it.toJsonElement() })
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        else -> error("Unsupported YAML value type: ${this::class.java.name}")
    }

    private fun readAsset(path: String): String? = runCatching {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
}
