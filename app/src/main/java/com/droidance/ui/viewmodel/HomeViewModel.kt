// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import androidx.core.content.ContextCompat


import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidance.R
import com.droidance.config.TokenUsageRepository
import com.droidance.core.device.permission.PermissionBroker
import com.droidance.ui.execution.PendingPermissionUiState
import com.droidance.agent.AgentRuntime
import com.droidance.core.agent.UserInteractionRequest
import com.droidance.core.agent.AgentState
import com.droidance.core.agent.PublicAssistantText
import com.droidance.core.agent.TurnRequest
import com.droidance.core.agent.MessageSource
import com.droidance.agent.ConversationSubmissionMode
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.model.llm.ModelStreamPlan
import com.droidance.core.model.llm.providerHttpException
import com.droidance.settings.ProviderConfigManager
import com.droidance.settings.ComposerSettings
import com.droidance.settings.ComposerSettingsRepository
import com.droidance.agent.device.VirtualSpaceViewerLauncher
import com.droidance.agent.device.VirtualSpaceManager
import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.event.DetailAttachmentReadModel
import com.droidance.core.agent.event.AgentEventProjectionFailureSnapshot
import com.droidance.core.agent.event.AgentThreadProjectionResult
import com.droidance.core.agent.event.AgentThreadReadModel
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.automation.AppTakeoverStore
import com.droidance.ui.execution.TurnPresentationUiState
import com.droidance.ui.execution.conversationExecutionSegments
import com.droidance.ui.execution.segmentPresentations
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * 对话界面 ViewModel
 * 管理消息列表、与 AgentRuntime 交互、对话持久化
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val agentRuntime: AgentRuntime,
    private val appTakeoverStore: AppTakeoverStore,
    private val eventStore: AgentEventStore,
    private val blobStore: BlobStore,
    private val deferredMemoryQueue: com.droidance.core.knowledge.memory.DeferredMemoryQueue,
    private val virtualSpaceViewerLauncher: VirtualSpaceViewerLauncher,
    private val virtualSpaceManager: VirtualSpaceManager,
    private val conversationTasks: com.droidance.agent.ConversationTaskController,
    private val permissionBroker: PermissionBroker,
    private val providerConfigManager: ProviderConfigManager,
    private val composerSettingsRepository: ComposerSettingsRepository,
    private val reasoningCapabilities: com.droidance.settings.ReasoningCapabilityRepository,
    tokenUsageRepository: TokenUsageRepository,
    runtimeClient: com.droidance.runtime.RuntimeProcessClient,
    @ApplicationContext private val context: Context,
    private val history: ConversationHistoryRepository,
    private val displayCache: ConversationDisplayCache,
    private val navigationPresentation: com.droidance.ui.navigation.StartupNavigationStore,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val displayGeneration = displayCache.generation()
    val runtimeConnection = runtimeClient.connectionState

    /** 当前对话 ID —— 持久化到 SavedStateHandle，跨 ViewModel 重建（屏幕旋转/低内存）保持稳定 */
    val conversationId: String = run {
        val existing = savedStateHandle.get<String>("conversationId")
            ?: savedStateHandle.get<String>("threadId")?.takeIf { it.isNotBlank() }
        if (existing != null) existing
        else {
            val newId = UUID.randomUUID().toString()
            savedStateHandle["conversationId"] = newId  // 写回，重建后能恢复
            newId
        }
    }
    private val initialPrompt: String? = savedStateHandle.get<String>("prompt")
    private val autoSendInitialPrompt: Boolean = savedStateHandle.get<Boolean>("autoSend") ?: false
    val composerInitialText: String get() = if (autoSendInitialPrompt) "" else initialPrompt.orEmpty()
    private var initialPromptConsumed = false

    private val _composerModels = MutableStateFlow(composerModelOptions(providerConfigManager.getEnabledConfigs(), providerConfigManager.presets))
    val composerModels: StateFlow<List<ComposerModelOption>> = _composerModels
    private val _composerPreferences = MutableStateFlow(restoredComposerPreferences(
        composerSettingsRepository.read(), _composerModels.value
    ))
    val composerPreferences: StateFlow<ComposerPreferences> = _composerPreferences

    fun refreshComposerModels() {
        _composerModels.value = composerModelOptions(providerConfigManager.getEnabledConfigs(), providerConfigManager.presets)
        if (!_isProcessing.value) {
            _composerPreferences.value = restoredComposerPreferences(composerSettingsRepository.read(), _composerModels.value)
        }
        providerConfigManager.getEnabledConfigs().forEach { config ->
            viewModelScope.launch { reasoningCapabilities.refresh(config) }
        }
    }

    fun selectComposerModel(model: ComposerModelOption?) {
        if (model != null && model !in _composerModels.value) return
        updateComposerPreferences(_composerPreferences.value.copy(model = model))
    }

    private fun updateComposerPreferences(preferences: ComposerPreferences) {
        _composerPreferences.value = preferences
        composerSettingsRepository.save(ComposerSettings(
            providerId = preferences.model?.providerId,
            modelId = preferences.model?.modelId,
            executionSpace = ExecutionSpace.VIRTUAL_SPACE
        ))
    }

    private data class ComposerSubmissionOptions(val modelPlan: ModelStreamPlan?, val space: ExecutionSpace)

    private fun captureComposerOptions(): ComposerSubmissionOptions? {
        if (providerConfigManager.getEnabledConfigs().isEmpty()) {
            _errorEvent.tryEmit(ContextCompat.getContextForLanguage(context).getString(R.string.model_not_configured))
            return null
        }
        return try {
            ComposerSubmissionOptions(
                composerModelPlan(_composerPreferences.value, providerConfigManager.getEnabledConfigs()),
                ExecutionSpace.VIRTUAL_SPACE
            )
        } catch (_: IllegalArgumentException) {
            _errorEvent.tryEmit(ContextCompat.getContextForLanguage(context).getString(R.string.composer_model_unavailable))
            null
        }
    }

    @Volatile private var latestSubmittedTurnId: String? = null
    // Only the first visit to a newly generated route is known to be empty.
    // A restored route must still read its durable history.
    private val initiallyEmpty = savedStateHandle.get<String>("conversationId") == null &&
        savedStateHandle.get<String>("threadId")?.isNotBlank() == true &&
        savedStateHandle.get<Boolean>("screenInitialized") != true
    private val _conversationLoaded = MutableStateFlow(initiallyEmpty)
    val conversationLoaded: StateFlow<Boolean> = _conversationLoaded
    private val _historyStatus = MutableStateFlow(HistoryLoadStatus.LOADING)
    val historyStatus: StateFlow<HistoryLoadStatus> = _historyStatus

    /** 消息列表 */
    private val _messages = MutableStateFlow<List<UIMessage>>(emptyList())
    val messages: StateFlow<List<UIMessage>> = _messages

    val tokenUsage: StateFlow<ConversationTokenUsage> = combine(messages, tokenUsageRepository.turnRecords) { messages, records ->
        conversationTokenUsage(conversationId, messages, records)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationTokenUsage())

    private val _turnPresentations = MutableStateFlow<List<TurnPresentationUiState>>(emptyList())
    val turnPresentations: StateFlow<List<TurnPresentationUiState>> = _turnPresentations

    private val _threadProjectionFailure =
        MutableStateFlow<AgentEventProjectionFailureSnapshot?>(null)
    val threadProjectionFailure: StateFlow<AgentEventProjectionFailureSnapshot?> =
        _threadProjectionFailure

    /** 是否正在处理中 */
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing
    private val _activeTurnIds = MutableStateFlow<Set<String>>(emptySet())
    val activeTurnIds: StateFlow<Set<String>> = _activeTurnIds
    private val _queuedTurnIds = MutableStateFlow<Set<String>>(emptySet())
    val queuedTurnIds: StateFlow<Set<String>> = _queuedTurnIds

    /** 是否有前台任务正在运行（显示发光边框） */
    private val _isTaskRunning = MutableStateFlow(false)
    val isTaskRunning: StateFlow<Boolean> = _isTaskRunning

    /** 当前 LLM 动作文字（只展示本会话的动作，防止跨会话污染） */
    private val _currentAction = MutableStateFlow("")
    val currentAction: StateFlow<String> = _currentAction

    /** 是否处于暂停状态（只反映本会话） */
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused
    private val _userInteraction = MutableStateFlow<UserInteractionRequest?>(null)
    val userInteraction: StateFlow<UserInteractionRequest?> = _userInteraction
    private val _pendingPermissionRequest = MutableStateFlow<PendingPermissionUiState?>(null)
    val pendingPermissionRequest: StateFlow<PendingPermissionUiState?> = _pendingPermissionRequest

    val retainedVirtualExecution = virtualSpaceManager.retainedExecution
    val retainedVirtualContent = virtualSpaceManager.retainedDisplayHasContent
    private val _appTakeoverStatusUiState = MutableStateFlow(AppTakeoverLogUiState())
    val appTakeoverStatusUiState: StateFlow<AppTakeoverLogUiState> = _appTakeoverStatusUiState
    private val _appTakeoverTurnPresentations =
        MutableStateFlow<List<TurnPresentationUiState>>(emptyList())

    /** 切换暂停/恢复（只操作本会话） */
    fun togglePause() {
        if (agentRuntime.currentConversationId.value == conversationId) {
            agentRuntime.togglePause()
        }
    }

    fun requestRuntimePermission() {
        val request = _pendingPermissionRequest.value ?: return
        if (request.settingsRequired) {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } else {
            permissionBroker.requestSystemPermissionDialog(request.requestId)
        }
    }

    fun respondToToolConfirmation(requestId: String, allow: Boolean) {
        if (_userInteraction.value?.requestId == requestId) {
            agentRuntime.respondToHighRiskConfirmation(requestId, allow)
        }
    }

    /** 停止当前任务。 */
    fun stopTask() {
        conversationTasks.stop(conversationId, origin = "chat_stop")
    }

    fun openVirtualSpaceViewer(owner: com.droidance.agent.device.RetainedVirtualExecution?) {
        viewModelScope.launch(Dispatchers.IO) {
            if (owner == null || !virtualSpaceViewerLauncher.open(owner)) {
                _errorEvent.emit(ContextCompat.getContextForLanguage(context).getString(R.string.virtual_space_viewer_open_failed))
            }
        }
    }

    fun stopAppTakeoverStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            agentRuntime.stopAppTakeoverResidency(origin = "chat_takeover_log_stop")
        }
    }

    /** 错误通知事件（不写入聊天，用 Snackbar 显示） */
    private val _errorEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvent: SharedFlow<String> = _errorEvent.asSharedFlow()

    init {
        savedStateHandle["screenInitialized"] = true
        if (initiallyEmpty) navigationPresentation.recordConversationEmpty(conversationId, true)
        viewModelScope.launch {
            deferredMemoryQueue.failures.collect { failures ->
                if (failures.any { it.threadId == conversationId && it.turnId == latestSubmittedTurnId }) {
                    _errorEvent.emit(ContextCompat.getContextForLanguage(context).getString(R.string.memory_background_save_failed))
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            combine(
                history.thread(conversationId).transform { snapshot ->
                    _historyStatus.value = snapshot.status
                    val projection = snapshot.value ?: return@transform
                    val thread = (projection as? AgentThreadProjectionResult.Projected)?.thread
                    if (!_conversationLoaded.value && thread != null && !thread.metadata.tombstoned) {
                        // Publish readable history before fetching any attachment bytes.
                        emit(projection to projectConversationMessages(thread.conversation.entries,
                            conversationExecutionSegments(thread.conversation.entries, thread.completeRecord.events)) { null })
                    }
                    val projected = thread?.takeUnless { it.metadata.tombstoned }?.let {
                        projectConversationMessages(it.conversation.entries,
                            conversationExecutionSegments(it.conversation.entries, it.completeRecord.events)) { attachment ->
                            if (attachment.sizeBytes !in 1..MAX_TIMELINE_IMAGE_BYTES) null
                            else displayCache.image(conversationId, attachment.contentRef)
                                ?: if (snapshot.status != HistoryLoadStatus.READY) null else {
                                    runCatching { blobStore.read(attachment.contentRef, MAX_TIMELINE_IMAGE_BYTES) }.getOrNull()?.also { bytes ->
                                        runCatching { displayCache.saveIfCurrent(displayGeneration) {
                                            displayCache.saveImage(conversationId, attachment.contentRef, bytes)
                                        } }
                                    }
                                }
                        }
                    }.orEmpty()
                    emit(projection to projected)
                },
                conversationTasks.activeTasks.map { it[conversationId].orEmpty() }.distinctUntilChanged(),
                agentRuntime.taskTiming
            ) { projection, active, timing -> Triple(projection, active, timing) }
                .collect { (projectionAndMessages, active, timing) ->
                val (projection, projected) = projectionAndMessages
                val thread = when (projection) {
                    is AgentThreadProjectionResult.Projected -> {
                        _threadProjectionFailure.value = null
                        projection.thread
                    }
                    is AgentThreadProjectionResult.Quarantined -> {
                        _threadProjectionFailure.value = projection.failure
                        _turnPresentations.value = emptyList()
                        _conversationLoaded.value = true
                        return@collect
                    }
                }
                _turnPresentations.value = thread?.let { turnPresentations(it, timing) }.orEmpty()
                if (thread?.metadata?.tombstoned == true) {
                    _messages.value = emptyList()
                } else {
                    val terminalTurns = thread?.turns.orEmpty()
                        .filter { it.state == com.droidance.core.agent.event.TurnReadModelState.TERMINAL }
                        .mapTo(mutableSetOf()) { it.turnId }
                    _messages.update { current ->
                        reconcileConversationMessages(current, projected, terminalTurns, active)
                    }
                }
                if (_historyStatus.value == HistoryLoadStatus.READY) {
                    navigationPresentation.recordConversationEmpty(conversationId, _messages.value.isEmpty())
                }
                _conversationLoaded.value = true
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            agentRuntime.appTakeoverConversationId.collectLatest { threadId ->
                if (threadId == null) {
                    _appTakeoverTurnPresentations.value = emptyList()
                    return@collectLatest
                }
                combine(eventStore.observeThreadProjectionResult(threadId), agentRuntime.taskTiming) { projection, timing ->
                    projection to timing
                }.collect { (projection, timing) ->
                    _appTakeoverTurnPresentations.value = when (projection) {
                        is AgentThreadProjectionResult.Projected ->
                            projection.thread?.let { turnPresentations(it, timing) }.orEmpty()
                        is AgentThreadProjectionResult.Quarantined -> emptyList()
                    }
                }
            }
        }
        // 监听 Agent 状态变化，只响应本会话（防止跨会话状态污染）
        viewModelScope.launch {
            combine(agentRuntime.state, agentRuntime.currentConversationId, conversationTasks.activeTasks) { state, currentId, tasks ->
                Triple(state, currentId, tasks[conversationId].orEmpty())
            }.collect { (state, currentId, submitted) ->
                val mine = currentId == conversationId
                _activeTurnIds.value = submitted.mapTo(mutableSetOf()) { it.turnId }
                _queuedTurnIds.value = submitted.filterNot { it.started }.mapTo(mutableSetOf()) { it.turnId }
                _isProcessing.value = submitted.isNotEmpty() || (mine && state != AgentState.IDLE)
                _isTaskRunning.value = mine && (
                    state == AgentState.EXECUTING_TOOL ||
                        state == AgentState.WAITING_PERMISSION
                    )
            }
        }
        viewModelScope.launch {
            combine(agentRuntime.currentAction, agentRuntime.currentConversationId, agentRuntime.state,
                conversationTasks.activeTasks) { action, currentId, state, tasks ->
                when {
                    currentId != conversationId && tasks.containsKey(conversationId) ->
                        ContextCompat.getContextForLanguage(context).getString(R.string.chat_status_queued)
                    currentId != conversationId -> ""
                    state == AgentState.THINKING -> ContextCompat.getContextForLanguage(context).getString(R.string.runtime_status_thinking)
                    else -> action
                }
            }.collect { _currentAction.value = it }
        }
        viewModelScope.launch {
            combine(
                appTakeoverStore.changeVersion,
                agentRuntime.takeoverResidentStatus,
                agentRuntime.appTakeoverTurnActive,
                combine(
                    _appTakeoverTurnPresentations,
                    agentRuntime.currentAction
                ) { turns, currentAction -> turns to currentAction }
            ) { _, residentStatus, turnActive, turnState ->
                val (turns, currentAction) = turnState
                val enabledCount = appTakeoverStore.list().count { it.enabled }
                val observation = turns.lastOrNull()?.activity?.publicProgress.orEmpty()
                AppTakeoverLogUiState(
                    visible = residentStatus != null && enabledCount > 0,
                    title = ContextCompat.getContextForLanguage(context).getString(R.string.app_takeover_status_title),
                    detail = if (turnActive) {
                        observation.ifBlank { currentAction }.ifBlank {
                            ContextCompat.getContextForLanguage(context).getString(R.string.app_takeover_status_checking)
                        }
                    } else {
                        ContextCompat.getContextForLanguage(context).getString(R.string.app_takeover_status_waiting)
                    },
                    active = turnActive,
                    turnPresentations = turns
                )
            }.collect { _appTakeoverStatusUiState.value = it }
        }
        viewModelScope.launch {
            combine(agentRuntime.isPaused, agentRuntime.currentConversationId) { paused, currentId ->
                currentId == conversationId && paused
            }.collect { _isPaused.value = it }
        }
        viewModelScope.launch {
            agentRuntime.userInteractionRequest.collect { request ->
                _userInteraction.value = request?.takeIf { it.conversationId == conversationId }
            }
        }
        viewModelScope.launch {
            combine(
                permissionBroker.pendingRequest,
                agentRuntime.isPaused,
                agentRuntime.currentConversationId
            ) { request, paused, currentId ->
                if (paused && currentId == conversationId && request != null) {
                    PendingPermissionUiState(
                        requestId = request.requestId,
                        permissions = request.permissions,
                        reason = request.reason,
                        settingsRequired = request.settingsRequired
                    )
                } else {
                    null
                }
            }.collect { _pendingPermissionRequest.value = it }
        }
        maybeHandleInitialPrompt()
    }

    private companion object {
        const val MAX_TIMELINE_IMAGE_BYTES = 8L * 1024L * 1024L
    }

    suspend fun loadTimelineAttachment(attachment: DetailAttachmentReadModel): ByteArray? =
        withContext(Dispatchers.IO) {
            attachment.takeIf {
                it.mimeType.startsWith("image/") &&
                    it.sizeBytes in 1..MAX_TIMELINE_IMAGE_BYTES
            }?.let {
                runCatching {
                    blobStore.read(it.contentRef, MAX_TIMELINE_IMAGE_BYTES)
                }.getOrNull()
            }
        }

    /** 由 AppNavigation 的 LaunchedEffect 调用，用于 startDestination 场景的自动发送（nav args 无法传递时） */
    fun triggerAutoSend(prompt: String) {
        android.util.Log.d("Droidance", "triggerAutoSend called: consumed=$initialPromptConsumed")
        if (initialPromptConsumed) return
        initialPromptConsumed = true
        sendMessage(prompt)
    }

    private fun maybeHandleInitialPrompt() {
        if (initialPromptConsumed) return
        if (!autoSendInitialPrompt) return
        val prompt = initialPrompt?.trim().orEmpty()
        if (prompt.isBlank()) return
        initialPromptConsumed = true
        sendMessage(prompt)
    }

    /** 发送用户消息。每个用户请求只进入一次 AgentRuntime。 */
    fun sendMessageWithImage(text: String, imageUri: Uri) {
        sendMessageWithImages(text, listOf(imageUri))
    }

    fun sendMessageWithImages(text: String, imageUris: List<Uri>,
        submissionMode: ConversationSubmissionMode = ConversationSubmissionMode.QUEUE,
        requestedSkills: List<com.droidance.core.agent.RequestedSkill> = emptyList()): Boolean {
        if (rejectQuarantinedThreadWrite()) return false
        val options = captureComposerOptions() ?: return false
        val request = TurnRequest.create(source = MessageSource.DIRECT, rawText = text.trim(),
            images = emptyList(), modelPlan = options.modelPlan, executionSpace = options.space,
            requestedSkills = requestedSkills)
        submitMessage(request, imageUris.map { it.toString() }, submissionMode) {
            val base64List = withContext(Dispatchers.IO) { imageUris.mapNotNull { uri ->
                try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                } catch (_: Exception) {
                    null
                }
            } }
            request.copy(images = base64List)
        }
        return true
    }

    fun sendMessage(
        text: String,
        imageBase64: String? = null,
        imageBase64List: List<String> = emptyList(),
        imageUris: List<String> = emptyList(),
        submissionMode: ConversationSubmissionMode = ConversationSubmissionMode.QUEUE,
        requestedSkills: List<com.droidance.core.agent.RequestedSkill> = emptyList()
    ): Boolean {
        if (rejectQuarantinedThreadWrite()) return false
        val options = captureComposerOptions() ?: return false
        val request = TurnRequest.create(
            source = MessageSource.DIRECT,
            rawText = text,
            images = imageBase64List.ifEmpty { imageBase64?.let(::listOf).orEmpty() },
            modelPlan = options.modelPlan,
            executionSpace = options.space,
            requestedSkills = requestedSkills
        )
        submitMessage(request, imageUris, submissionMode)
        return true
    }

    private fun submitMessage(request: TurnRequest, imageUris: List<String>, submissionMode: ConversationSubmissionMode,
        prepareRequest: suspend () -> TurnRequest = { request }) {
        if (rejectQuarantinedThreadWrite()) return
        val turnId = "turn:${request.requestId}"
        val userMessage = UIMessage(
            turnId = turnId,
            messageId = "message:${request.requestId}",
            text = request.rawText,
            isUser = true,
            timestamp = getCurrentTime(),
            timestampMs = System.currentTimeMillis(),
            imageUris = imageUris,
            isTransient = true
        )
        _isProcessing.value = true
        navigationPresentation.recordConversationEmpty(conversationId, false)
        _messages.update { it + userMessage }

        conversationTasks.dispatch(conversationId, request, submissionMode, prepareRequest) { prepared ->
            submitConversationRequest(prepared, turnId)
        }
    }

    fun deleteQueuedMessage(turnId: String) {
        if (conversationTasks.deleteQueued(conversationId, turnId)) {
            _messages.update { messages -> messages.filterNot { it.turnId == turnId && it.isTransient } }
        }
    }

    fun steerQueuedMessage(turnId: String) {
        viewModelScope.launch {
            conversationTasks.steerQueued(conversationId, turnId) { accepted ->
                if (accepted) {
                    // The ledger projects this input into the running Turn under the same
                    // message identity. Remove only its former, transient queue presentation.
                    _messages.update { messages -> messages.filterNot { it.turnId == turnId && it.isTransient } }
                } else {
                    _errorEvent.tryEmit(ContextCompat.getContextForLanguage(context).getString(R.string.composer_steer_unavailable))
                }
            }
        }
    }

    fun reportUiFailure(message: String) { _errorEvent.tryEmit(message) }

    private fun submitConversationRequest(request: TurnRequest, turnId: String) {
        latestSubmittedTurnId = turnId
        var currentAssistantMessage = UIMessage(
            turnId = turnId, text = "", isUser = false, messageId = "assistant:$turnId",
            timestamp = getCurrentTime(), timestampMs = System.currentTimeMillis(),
            startedAtMs = System.currentTimeMillis(), isTransient = true
        )
        _messages.update { it + currentAssistantMessage }
        _isProcessing.value = true
        conversationTasks.submit(
            conversationId = conversationId,
            request = request,
            onStream = {},
            onCompleted = { outcome ->
                val latest = _messages.value.lastOrNull { !it.isUser && it.turnId == turnId && it.isCurrentSegment }
                    ?: currentAssistantMessage
                currentAssistantMessage = latest.copy(
                    text = mergeFinalAssistantText(latest.text, outcome.response),
                    completedAtMs = System.currentTimeMillis(), isTransient = false
                )
                replaceAssistantMessage(currentAssistantMessage)
            },
            onFailure = { error ->
                currentAssistantMessage = _messages.value.lastOrNull { !it.isUser && it.turnId == turnId && it.isCurrentSegment }
                    ?: currentAssistantMessage
                val preserved = preservePublicAssistantText(currentAssistantMessage.text)
                val text = if (error is com.droidance.core.agent.TaskExecutionFailure) {
                    mergeFinalAssistantText(preserved, error.publicReply)
                } else if (error is kotlinx.coroutines.CancellationException) {
                    buildStoppedAssistantText(
                        userRequestedStop = true,
                        existingText = currentAssistantMessage.text,
                        runtimeFinalText = agentRuntime.lastResponse.value
                    )
                } else mergeFinalAssistantText(preserved, buildVisibleFailureText(friendlyError(error)))
                if (text.isBlank()) {
                    _messages.update { it.filterNot { msg -> msg.messageId == currentAssistantMessage.messageId } }
                } else {
                    currentAssistantMessage = currentAssistantMessage.copy(
                        text = text, completedAtMs = System.currentTimeMillis(), isTransient = false
                    )
                    replaceAssistantMessage(currentAssistantMessage)
                }
            },
            onFinished = {}
        )
    }

    private fun rejectQuarantinedThreadWrite(): Boolean {
        if (_threadProjectionFailure.value == null) return false
        _errorEvent.tryEmit(ContextCompat.getContextForLanguage(context).getString(R.string.home_thread_quarantined_snackbar))
        return true
    }

    private fun buildVisibleFailureText(errorText: String): String {
        return errorText.trim()
    }

    /**
     * Completes the bubble without throwing away the first model-authored reply. This matters
     * for both pure chat and device tasks: the final projection may be a report, while the
     * streamed text before it is the natural opening that the user already saw.
     */
    private fun mergeFinalAssistantText(existingText: String, response: String): String {
        val existingPublic = PublicAssistantText.sanitize(existingText)
        val finalProjection = PublicAssistantText.sanitize(response)
        if (finalProjection.isBlank()) return existingPublic
        if (existingPublic.isBlank()) return finalProjection
        if (responseAlreadyVisible(existingPublic, finalProjection)) return existingPublic
        return "$existingPublic\n\n$finalProjection"
    }

    private fun responseAlreadyVisible(existingText: String, response: String): Boolean {
        val normalizedExisting = normalizeAssistantText(existingText)
        val normalizedResponse = normalizeAssistantText(response)
        if (normalizedExisting.isBlank() || normalizedResponse.isBlank()) return false
        return normalizedExisting == normalizedResponse ||
            normalizedExisting.endsWith(normalizedResponse)
    }

    private fun normalizeAssistantText(text: String): String {
        return text.replace(Regex("""\s+"""), " ").trim()
    }

    private fun buildStoppedAssistantText(
        userRequestedStop: Boolean,
        existingText: String = "",
        runtimeFinalText: String = ""
    ): String {
        val runtimeProjection = PublicAssistantText.sanitize(runtimeFinalText)
        val stoppedText = if (userRequestedStop) {
            ContextCompat.getContextForLanguage(context).getString(R.string.execution_stopped_by_user)
        } else {
            ContextCompat.getContextForLanguage(context).getString(R.string.execution_cancelled)
        }
        val preserved = preservePublicAssistantText(existingText)
        if (userRequestedStop) {
            // When stopped by user, preserve what the assistant already generated without appending fixed notices.
            return preserved.ifBlank { runtimeProjection }
        }
        val terminalNotice = runtimeProjection.ifBlank { stoppedText }
        return when {
            preserved.isBlank() -> terminalNotice
            terminalNotice.isBlank() || responseAlreadyVisible(preserved, terminalNotice) -> preserved
            responseAlreadyVisible(terminalNotice, preserved) -> terminalNotice
            else -> "$preserved\n\n$terminalNotice"
        }
    }

    /** 保留接管说明等模型公开文本。 */
    private fun preservePublicAssistantText(text: String): String {
        return PublicAssistantText.sanitize(text)
    }

    private fun friendlyError(e: Exception): String {
        val localized = ContextCompat.getContextForLanguage(context)
        val message = localized.getString(com.droidance.agent.runtimeFailureMessageResource(e))
        return e.providerHttpException()?.publicReason?.let { reason ->
            localized.getString(R.string.chat_provider_service_reason, message, reason)
        } ?: message
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    private fun turnPresentations(
        thread: AgentThreadReadModel,
        timing: com.droidance.core.agent.LiveTaskTiming?
    ): List<TurnPresentationUiState> =
        thread.segmentPresentations(timing)

    private fun replaceAssistantMessage(updated: UIMessage) {
        _messages.update { messages -> messages.replaceAssistantInPlace(updated) }
    }

}

/**
 * UI 消息数据类
 */
data class UIMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: String,
    val turnId: String? = null,
    val timestampMs: Long = 0L,
    val startedAtMs: Long = 0L,
    val completedAtMs: Long = 0L,
    val imageUris: List<String> = emptyList(),
    val imageBytes: List<ByteArray> = emptyList(),
    val executionSpace: String = "",
    val isTransient: Boolean = false,
    val messageId: String = UUID.randomUUID().toString(),
    val anchorMessageId: String? = null,
    val roundIds: Set<String> = emptySet(),
    val isCurrentSegment: Boolean = true,
    val isGuidance: Boolean = false
) {
    fun showsPresentationFor(candidateTurnId: String, candidateMessageId: String): Boolean =
        !isUser && turnId != null && turnId == candidateTurnId && messageId == candidateMessageId
}

data class AppTakeoverLogUiState(
    val visible: Boolean = false,
    val title: String = "",
    val detail: String = "",
    val active: Boolean = false,
    val turnPresentations: List<TurnPresentationUiState> = emptyList()
)
