// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidance.settings.ProviderConfigManager
import com.droidance.settings.ProviderPreset
import com.droidance.settings.endpointsFor
import com.droidance.settings.providerAddressKey
import com.droidance.settings.providerGroups
import com.droidance.core.model.llm.ModelDiscoveryResult
import com.droidance.core.model.llm.ProviderEndpoint
import com.droidance.core.model.llm.ProviderModelDiscovery
import com.droidance.core.model.llm.ProviderModelList
import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.session.ModelTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URI
import java.util.UUID
import javax.inject.Inject

/**
 * Provider 编辑页 ViewModel
 */
@HiltViewModel
class ProviderEditViewModel @Inject constructor(
    private val configManager: ProviderConfigManager,
    httpClient: OkHttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProviderEditUiState())
    val uiState: StateFlow<ProviderEditUiState> = _uiState
    val presets get() = configManager.presets
    val providerPresets get() = presets.filter { it.groupId == null }
    val addressPresets get() = _uiState.value.presetId?.let { presets.endpointsFor(it) }.orEmpty()
    private val discovery = ProviderModelDiscovery(httpClient)
    private val _modelCatalog = MutableStateFlow(ProviderModelCatalogState())
    val modelCatalog: StateFlow<ProviderModelCatalogState> = _modelCatalog
    private val _customModels = MutableStateFlow<List<String>>(emptyList())
    val customModels: StateFlow<List<String>> = _customModels
    private var discoveryJob: Job? = null
    private var sourceGeneration = 0L
    private var keyOrigin: String? = null

    private var loaded = false
    private var initialState = ProviderEditUiState()
    fun hasUnsavedChanges(): Boolean = _uiState.value.copy(saveFailed = false) !=
        initialState.copy(saveFailed = false) || drafts.any { (key, draft) ->
        draft.state.copy(saveFailed = false) != baselineStates[key]?.copy(saveFailed = false)
    }

    private var editingId: String? = null
    private var editingConfig: ProviderConfig? = null
    private var originalGroupConfigs: List<ProviderConfig> = emptyList()
    private val drafts = mutableMapOf<String, AddressDraft>()
    private val baselineStates = mutableMapOf<String, ProviderEditUiState>()

    private data class AddressDraft(
        val config: ProviderConfig?,
        val state: ProviderEditUiState,
        val customModels: List<String>
    )

    private fun draftKey(state: ProviderEditUiState = _uiState.value): String = state.addressPresetId ?: "custom"

    private fun saveDraft() {
        drafts[draftKey()] = AddressDraft(editingConfig, _uiState.value, _customModels.value)
    }

    /** 加载已有 Provider 配置（编辑模式） */
    fun loadProvider(id: String?) {
        if (loaded && editingId == id) return
        drafts.clear()
        baselineStates.clear()
        if (id != null) {
            editingId = id
            val config = configManager.getConfig(id) ?: return
            val group = configManager.getAllConfigs().providerGroups(presets)
                .firstOrNull { candidate -> candidate.configs.any { it.id == id } }
            originalGroupConfigs = group?.configs.orEmpty()
            editingConfig = config
            val groupId = group?.preset?.id
            val endpoint = groupId?.let { presets.endpointsFor(it) }
                ?.firstOrNull { providerAddressKey(it.baseUrl) == providerAddressKey(config.baseUrl) }
            _uiState.value = config.toEditState().copy(
                name = group?.displayName ?: config.name,
                presetId = groupId,
                addressPresetId = endpoint?.id
            )
        } else {
            editingId = null
            editingConfig = null
            originalGroupConfigs = emptyList()
            _uiState.value = ProviderEditUiState()
        }
        initialState = _uiState.value
        baselineStates[draftKey()] = _uiState.value
        loaded = true
        keyOrigin = urlOrigin(_uiState.value.baseUrl).takeIf { _uiState.value.apiKey.isNotBlank() }
        _customModels.value = emptyList()
        updateModelSource()
    }

    // ====== 字段更新 ======

    fun selectPreset(id: String?) {
        if (editingId != null) return
        val preset = id?.let { selected -> presets.firstOrNull { it.id == selected } ?: return }
        // A key belongs to its endpoint; selecting another template always clears it.
        drafts.clear()
        baselineStates.clear()
        editingConfig = null
        originalGroupConfigs = emptyList()
        _uiState.value = if (preset == null) ProviderEditUiState() else ProviderEditUiState(
            presetId = preset.id,
            addressPresetId = preset.id,
            name = preset.name,
            baseUrl = preset.baseUrl,
            apiType = preset.apiType,
            transportKind = preset.transportKind
        )
        keyOrigin = null
        _customModels.value = emptyList()
        updateModelSource()
    }

    /** Switch the editing form without sending one endpoint's key to another endpoint. */
    fun selectAddress(id: String?) {
        val groupId = _uiState.value.presetId ?: return
        val endpoint = id?.let { chosen -> presets.endpointsFor(groupId).firstOrNull { it.id == chosen } ?: return }
        if (id == _uiState.value.addressPresetId) return
        saveDraft()
        val nextKey = id ?: "custom"
        val cached = drafts[nextKey]
        if (cached != null) {
            editingConfig = cached.config
            _uiState.value = cached.state
            _customModels.value = cached.customModels
        } else {
            val matches = originalGroupConfigs.filter { config ->
                val match = presets.endpointsFor(groupId).firstOrNull {
                    providerAddressKey(it.baseUrl) == providerAddressKey(config.baseUrl)
                }
                if (endpoint == null) match == null else match?.id == endpoint.id
            }
            val existing = matches.firstOrNull { it.id == editingId }
                ?: matches.firstOrNull { it.apiKey.isNotBlank() || it.models.isNotEmpty() }
                ?: matches.firstOrNull()
            editingConfig = existing
            val currentName = _uiState.value.name
            _uiState.value = (existing?.toEditState() ?: ProviderEditUiState(
                name = currentName,
                baseUrl = endpoint?.baseUrl.orEmpty(),
                apiType = endpoint?.apiType ?: ApiType.OPENAI_COMPATIBLE,
                transportKind = endpoint?.transportKind ?: ModelTransport.GENERIC_CHAT_COMPLETIONS
            )).copy(
                name = currentName,
                presetId = groupId,
                addressPresetId = id,
                isEnabled = initialState.isEnabled
            )
            _customModels.value = emptyList()
            baselineStates[nextKey] = _uiState.value
        }
        keyOrigin = urlOrigin(_uiState.value.baseUrl).takeIf { _uiState.value.apiKey.isNotBlank() }
        updateModelSource()
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun updateBaseUrl(url: String) {
        val newOrigin = urlOrigin(url)
        _uiState.update { state ->
            val clearKey = state.apiKey.isNotBlank() && keyOrigin != null &&
                newOrigin != null && newOrigin != keyOrigin
            state.copy(baseUrl = url, apiKey = if (clearKey) "" else state.apiKey)
        }
        if (_uiState.value.apiKey.isBlank()) keyOrigin = null
        _customModels.value = emptyList()
        updateModelSource()
    }

    fun selectProtocol(transport: ModelTransport) {
        _uiState.update { it.withProtocol(transport) }
    }

    fun updateApiKey(key: String) {
        _uiState.update { it.copy(apiKey = key) }
        keyOrigin = urlOrigin(_uiState.value.baseUrl).takeIf { key.isNotBlank() }
        updateModelSource()
    }

    fun updateReasoningPreference(effort: ReasoningEffort?) {
        _uiState.update { it.copy(reasoningPreference = effort) }
    }

    fun addCustomModel(model: String): List<String> {
        val added = parseModelInput(model).distinct()
        _customModels.update { (added + it).distinct() }
        return added
    }

    fun setSelectedModels(models: List<String>) {
        _uiState.update { state ->
            val selected = reconcileSelectedModels(state.models, models)
            state.copy(
                models = selected,
                defaultModel = state.defaultModel.takeIf { it in selected }
                    ?: selected.firstOrNull().orEmpty()
            )
        }
    }

    fun refreshModels() = fetchModels(delayMillis = 0, force = true)

    fun loadModelsOnOpen() {
        if (!_modelCatalog.value.loading) fetchModels(delayMillis = 0)
    }

    private fun updateModelSource() {
        discoveryJob?.cancel()
        sourceGeneration++
        val state = _uiState.value
        val preset = presetForAddress(state.baseUrl)
        _modelCatalog.value = ProviderModelCatalogState(candidates = preset?.suggestedModels.orEmpty())
        if (state.baseUrl.isBlank() || state.apiKey.isBlank()) return
        fetchModels(delayMillis = 700)
    }

    private fun fetchModels(delayMillis: Long, force: Boolean = false) {
        discoveryJob?.cancel()
        val generation = ++sourceGeneration
        val state = _uiState.value
        val preset = presetForAddress(state.baseUrl)
        if (state.baseUrl.isBlank()) {
            _modelCatalog.update { it.copy(loading = false, error = ModelCatalogError.MISSING_CONNECTION) }
            return
        }
        if (preset?.modelListSupported == false && !force) {
            _modelCatalog.update { it.copy(loading = false, error = ModelCatalogError.UNSUPPORTED) }
            return
        }
        _modelCatalog.update { it.copy(loading = true, error = null) }
        discoveryJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            val result = discovery.fetch(state.baseUrl, state.apiKey)
            if (generation != sourceGeneration) return@launch
            val models = withContext(Dispatchers.Default) {
                (result as? ModelDiscoveryResult.Success)?.body?.let(ProviderModelList::parse)
            }
            if (generation != sourceGeneration) return@launch
            _modelCatalog.update { current ->
                when {
                    models == null -> current.copy(loading = false, error = if (preset?.modelListSupported == false)
                        ModelCatalogError.UNSUPPORTED else ModelCatalogError.FETCH_FAILED)
                    models.isEmpty() -> current.copy(loading = false, error = ModelCatalogError.EMPTY)
                    else -> current.copy(candidates = models, loading = false, error = null)
                }
            }
        }
    }

    private fun presetForAddress(baseUrl: String): ProviderPreset? {
        val root = providerAddressKey(baseUrl)
        return presets.firstOrNull { providerAddressKey(it.baseUrl) == root }
    }

    fun removeModel(model: String) {
        _uiState.update { state ->
            val models = state.models.filterNot { it == model }
            state.copy(
                models = models,
                defaultModel = state.defaultModel.takeIf { it in models }
                    ?: models.firstOrNull().orEmpty()
            )
        }
    }

    fun selectDefaultModel(model: String) {
        _uiState.update { state ->
            if (model in state.models) state.copy(defaultModel = model) else state
        }
    }

    // ====== 保存 ======

    fun saveProvider(): Boolean {
        val state = _uiState.value
        if (state.validationError != null) return false
        val models = mergeModels(emptyList(), state.models)
        if (state.name.isBlank() || state.baseUrl.isBlank() || state.apiKey.isBlank() || models.isEmpty()) return false
        saveDraft()
        val selectedKey = draftKey()
        val selectedId = editingConfig?.id ?: UUID.randomUUID().toString()
        val updates = drafts.mapNotNull { (key, draft) ->
            val selected = key == selectedKey
            if (!selected && draft.state.copy(saveFailed = false) == baselineStates[key]?.copy(saveFailed = false)) return@mapNotNull null
            if (!selected && draft.config == null && draft.state.apiKey.isBlank() && draft.state.models.isEmpty()) return@mapNotNull null
            val draftModels = mergeModels(emptyList(), draft.state.models)
            val group = presets.firstOrNull { it.id == draft.state.presetId }
            val originalName = draft.config?.name?.takeIf { name ->
                group != null && draft.state.name == group.name &&
                    presets.endpointsFor(group.id).any { it.name == name }
            }
            draft.state.toConfig(
                id = if (selected) selectedId else draft.config?.id ?: UUID.randomUUID().toString(),
                models = draftModels,
                contextWindowTokens = draft.config?.contextWindowTokens ?: 128_000
            ).copy(
                name = originalName ?: draft.state.name.trim(),
                isEnabled = if (selected) state.isEnabled else draft.config?.isEnabled ?: false
            )
        }
        val saved = runCatching {
            configManager.saveProviderGroup(
                updates = updates,
                selectedId = selectedId,
                previousIds = originalGroupConfigs.mapTo(mutableSetOf()) { it.id },
                switchAddress = editingId != null && selectedId != editingId
            )
        }.isSuccess
        _uiState.update { it.copy(saveFailed = !saved) }
        if (saved) {
            editingId = selectedId
            editingConfig = configManager.getConfig(selectedId)
            initialState = _uiState.value
            drafts.clear()
            baselineStates.clear()
            baselineStates[draftKey()] = _uiState.value
        }
        return saved
    }

    private fun parseModelInput(input: String): List<String> = input
        .split(',', '，', ';', '；', '、', '\n', '\r', '\t')
        .map { it.trim() }
        .filter { it.isNotBlank() }

    private fun mergeModels(existing: List<String>, added: List<String>): List<String> {
        return (existing + added)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
}

/**
 * Provider 编辑页 UI 状态
 */
data class ProviderEditUiState(
    val presetId: String? = null,
    val addressPresetId: String? = null,
    val name: String = "",
    val apiType: ApiType = ApiType.OPENAI_COMPATIBLE,
    val transportKind: ModelTransport? = ModelTransport.GENERIC_CHAT_COMPLETIONS,
    val baseUrl: String = "",
    val apiKey: String = "",
    val models: List<String> = emptyList(),
    val defaultModel: String = "",
    val isEnabled: Boolean = true,
    val reasoningPreference: ReasoningEffort? = null,
    val saveFailed: Boolean = false
) {
    private val hasSupportedUrl: Boolean
        get() = runCatching {
            val uri = java.net.URI(baseUrl.trim())
            uri.scheme?.lowercase(java.util.Locale.ROOT) in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)

    val requestUrl: String?
        get() = transportKind?.takeIf { hasSupportedUrl }?.let { ProviderEndpoint.resolve(baseUrl, it) }

    val validationError: ProviderFormError?
        get() = when {
            name.isBlank() -> ProviderFormError.NAME_REQUIRED
            baseUrl.isBlank() -> ProviderFormError.URL_REQUIRED
            !hasSupportedUrl -> ProviderFormError.INVALID_URL
            transportKind == null -> ProviderFormError.PROTOCOL_REQUIRED
            apiKey.isBlank() -> ProviderFormError.KEY_REQUIRED
            models.all { it.isBlank() } -> ProviderFormError.MODEL_REQUIRED
            else -> null
        }

    /** Selecting the current protocol keeps a stored CUSTOM entry as it is. */
    fun withProtocol(transport: ModelTransport): ProviderEditUiState =
        if (transport == transportKind) this else copy(
            transportKind = transport,
            apiType = when (transport) {
                ModelTransport.OPENAI_RESPONSES -> ApiType.OPENAI_RESPONSES
                ModelTransport.GENERIC_CHAT_COMPLETIONS -> ApiType.OPENAI_COMPATIBLE
            }
        )

    /** Saving carries the protocol the form shows, which settles any pending confirmation. */
    internal fun toConfig(id: String, models: List<String>, contextWindowTokens: Int): ProviderConfig = ProviderConfig(
        id = id,
        name = name.trim(),
        apiType = apiType,
        transportKind = transportKind,
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        models = models,
        defaultModel = defaultModel.takeIf { it in models } ?: models.firstOrNull().orEmpty(),
        isEnabled = isEnabled,
        contextWindowTokens = contextWindowTokens,
        reasoningPreference = reasoningPreference,
        providerGroupId = presetId
    )
}

/** An entry that cannot run yet becomes enabled once the form completes it. */
internal fun ProviderConfig.toEditState(): ProviderEditUiState {
    val editableModels = models.ifEmpty { listOf(defaultModel).filter { it.isNotBlank() } }
    return ProviderEditUiState(
        name = name,
        apiType = apiType,
        transportKind = resolvedTransportOrNull(),
        baseUrl = baseUrl,
        apiKey = apiKey,
        models = editableModels,
        defaultModel = defaultModel.takeIf { it in editableModels } ?: editableModels.firstOrNull().orEmpty(),
        isEnabled = isEnabled || apiKey.isBlank() || !isTransportConfigured(),
        reasoningPreference = reasoningPreference
    )
}

enum class ProviderFormError {
    NAME_REQUIRED, URL_REQUIRED, INVALID_URL, PROTOCOL_REQUIRED, KEY_REQUIRED, MODEL_REQUIRED
}

data class ProviderModelCatalogState(
    val candidates: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: ModelCatalogError? = null
)

enum class ModelCatalogError { MISSING_CONNECTION, UNSUPPORTED, FETCH_FAILED, EMPTY }

private fun urlOrigin(value: String): String? = runCatching {
    val uri = URI(value.trim())
    val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT) ?: return null
    val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return null
    if (scheme !in setOf("http", "https") || uri.userInfo != null) return null
    val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
    "$scheme://$host:$port"
}.getOrNull()
