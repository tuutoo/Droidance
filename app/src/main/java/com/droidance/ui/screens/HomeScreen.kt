// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import com.droidance.ui.components.droidanceFloatingSurfaceColor
import com.droidance.ui.components.DroidanceSnackbar
import com.droidance.ui.connection.DeviceConnectionNotice
import com.droidance.ui.connection.DeviceConnectionUiState

import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.screens.home.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.droidance.R
import com.droidance.config.TokenUsageTotals
import com.droidance.core.agent.UserInteractionKind
import com.droidance.core.agent.UserInteractionState
import com.droidance.core.agent.event.AgentEventProjectionFailureSnapshot
import com.droidance.ui.mascot.MascotStage
import com.droidance.ui.mascot.MascotMood
import com.droidance.ui.components.FlatMarkdown
import com.droidance.ui.components.appGradientBackground
import com.droidance.core.knowledge.skill.SkillInfo
import com.droidance.ui.theme.*
import com.droidance.ui.execution.RetainedVirtualExecutionOwnerPolicy
import com.droidance.ui.viewmodel.HomeViewModel
import com.droidance.ui.viewmodel.SkillsViewModel
import com.droidance.ui.viewmodel.UIMessage
import kotlinx.coroutines.flow.collect

/**
 * 对话界面（子页面）
 * 从对话列表进入，支持新建或打开已有对话
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    onOpenDrawer: () -> Unit = {},
    onNewConversation: () -> Unit = {},
    onConnectDevice: () -> Unit = {},
    connectionState: DeviceConnectionUiState = DeviceConnectionUiState(),
    onOpenSkill: (SkillInfo) -> Unit = {},
    onManageSkills: () -> Unit = {},
    initialTurnId: String? = null,
    initialSkillId: String? = null,
    initialSkillDraft: Boolean = false,
    viewModel: HomeViewModel = hiltViewModel(),
    skillsViewModel: SkillsViewModel = hiltViewModel()
) {
    val lifecycle by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(lifecycle) {
        if (lifecycle == androidx.lifecycle.Lifecycle.State.RESUMED) {
            viewModel.refreshComposerModels()
        }
    }
    var hasDraft by remember { mutableStateOf(false) }
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val tokenUsage by viewModel.tokenUsage.collectAsStateWithLifecycle()
    val historyStatus by viewModel.historyStatus.collectAsStateWithLifecycle()
    val conversationLoaded by viewModel.conversationLoaded.collectAsStateWithLifecycle()
    val turnPresentations by viewModel.turnPresentations.collectAsStateWithLifecycle()
    val threadProjectionFailure by viewModel.threadProjectionFailure.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val composerModels by viewModel.composerModels.collectAsStateWithLifecycle()
    val composerPreferences by viewModel.composerPreferences.collectAsStateWithLifecycle()
    val activeTurnIds by viewModel.activeTurnIds.collectAsStateWithLifecycle()
    val queuedTurnIds by viewModel.queuedTurnIds.collectAsStateWithLifecycle()
    val currentAction by viewModel.currentAction.collectAsStateWithLifecycle()
    val isPaused by viewModel.isPaused.collectAsStateWithLifecycle()
    val userInteraction by viewModel.userInteraction.collectAsStateWithLifecycle()
    val manualTakeoverReason = userInteraction?.takeIf {
        it.kind == UserInteractionKind.DEVICE_TAKEOVER && it.state == UserInteractionState.PENDING
    }?.reason.orEmpty()
    val pendingPermission by viewModel.pendingPermissionRequest.collectAsStateWithLifecycle()
    val retainedVirtualExecution by viewModel.retainedVirtualExecution.collectAsStateWithLifecycle()
    val retainedVirtualContent by viewModel.retainedVirtualContent.collectAsStateWithLifecycle()
    val appTakeoverStatusUiState by viewModel.appTakeoverStatusUiState.collectAsStateWithLifecycle()
    val skills by skillsViewModel.skills.collectAsStateWithLifecycle()
    val draftWork by skillsViewModel.draftWorkState.collectAsStateWithLifecycle()
    LaunchedEffect(lifecycle, draftWork) {
        if (lifecycle == androidx.lifecycle.Lifecycle.State.RESUMED) skillsViewModel.loadSkills()
    }
    val snackbarHostState = remember { SnackbarHostState() }
    // 监听错误事件，以 Snackbar 形式显示（不污染 chat）
    LaunchedEffect(Unit) {
        viewModel.errorEvent.collect { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
        }
    }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val motionEnabled = LocalAppMotionEnabled.current
    var latestJumpJob by remember { mutableStateOf<Job?>(null) }
    var jumpingToLatest by remember { mutableStateOf(false) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val composerHeight = with(LocalDensity.current) { composerHeightPx.toDp() }
    val scrollController = remember(listState) {
        ChatScrollController(initiallyFollowing = initialTurnId == null,
            atBottom = { !listState.canScrollForward }, onUserScroll = {
                latestJumpJob?.cancel()
                focusManager.clearFocus()
                keyboardController?.hide()
            })
    }
    val userScrolledUp = !scrollController.followingLatest
    val focusPending = rememberConversationTurnFocus(listState, messages, conversationLoaded, initialTurnId) { found ->
        if (found) scrollController.readHistory() else scrollController.followLatest()
    }

    val visibleMessages = remember(messages, queuedTurnIds) { messages.filterNot { it.turnId in queuedTurnIds } }
    val latestAssistantId = remember(messages) { messages.lastOrNull { !it.isUser }?.messageId }
    val presentationsByMessage = remember(turnPresentations) { turnPresentations.associateBy { it.messageId } }
    // A loading snapshot is history; later user messages may animate once, even after lazy disposal.
    val enteredUserMessageIds = remember(conversationLoaded) { messages.mapTo(mutableSetOf()) { it.messageId } }
    val latestMessage = visibleMessages.lastOrNull()
    val latestUpdate = Triple(latestMessage, presentationsByMessage[latestMessage?.messageId],
        appTakeoverStatusUiState.takeIf { it.visible })
    var lastReadUpdate by remember(conversationLoaded) { mutableStateOf(latestUpdate) }
    SideEffect { if (!userScrolledUp) lastReadUpdate = latestUpdate }
    val showJumpToLatest = conversationLoaded && !focusPending && userScrolledUp &&
        lastReadUpdate != latestUpdate && listState.canScrollForward
    val retainedViewerMessageId = remember(turnPresentations, retainedVirtualExecution) {
        if (RetainedVirtualExecutionOwnerPolicy.owns(retainedVirtualExecution?.threadId, viewModel.conversationId)) {
            turnPresentations.lastOrNull { it.isCurrentSegment && it.showExecutionLog() }?.messageId
        } else null
    }

    // Follow measured layout changes until the user chooses to read history.
    LaunchedEffect(
        visibleMessages.size,
        visibleMessages.lastOrNull()?.messageId,
        appTakeoverStatusUiState.visible,
        userScrolledUp,
        focusPending
    ) {
        if (visibleMessages.isEmpty() || userScrolledUp) return@LaunchedEffect
        if (focusPending) return@LaunchedEffect
        val lastItemIndex = if (appTakeoverStatusUiState.visible) visibleMessages.size else visibleMessages.lastIndex
        listState.followLatestItemToBottom(lastItemIndex) { scrollController.followingLatest }
    }

    val onExecutionLayoutChange: () -> Unit = {
        // Opening history is an explicit reading action; do not fight it with bottom pinning.
        scrollController.readHistory()
    }

    val isDark = LocalAppIsDark.current
    Box(modifier = Modifier.fillMaxSize().appGradientBackground(isDark).imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                // IME insets are shared by the composer and anchored Snackbar host.
        ) {
            // ====== 顶部栏（侧栏开关） ======
            // 左右两个控件用同一字形尺寸与透明度，品牌名比页面标题小一档。
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        ConversationActionGlyph(
                            imageVector = DroidanceIcons.Menu,
                            contentDescription = stringResource(R.string.home_open_drawer),
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f)
                        )
                    }
                },
                title = {
                    Text(
                        text = "Droidance",
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, lineHeight = 24.sp)
                    )
                },
                actions = {
                    Surface(
                        onClick = {
                            onNewConversation()
                        },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(ConversationActionControlSize),
                        shape = AppShapes.Control,
                        color = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            ConversationActionGlyph(
                                imageVector = NewConversationIcon,
                                contentDescription = stringResource(R.string.home_new_conversation),
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )

            threadProjectionFailure?.let { failure ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    ThreadProjectionFailureNotice(failure)
                }
            }

            if (messages.isNotEmpty() && historyStatus == com.droidance.ui.viewmodel.HistoryLoadStatus.UNAVAILABLE) {
                TextButton(onClick = onConnectDevice, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.history_cached_unavailable), style = MaterialTheme.typography.bodySmall)
                }
            }

            // ====== 消息列表（正常顺序：最新消息在底部） ======
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollController),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // A new screen waits for its first durable projection before showing the empty state.
                    if (messages.isEmpty() && threadProjectionFailure == null) {
                        item(key = "conversation_empty_state") {
                            if (conversationLoaded) {
                                WelcomeMessage(hasDraft)
                            } else if (historyStatus == com.droidance.ui.viewmodel.HistoryLoadStatus.UNAVAILABLE) {
                                TextButton(onClick = onConnectDevice) {
                                    Text(stringResource(R.string.home_conversation_reconnect))
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = stringResource(R.string.home_conversation_loading),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    items(visibleMessages, key = { it.messageId }) { message ->
                        val isLatestAssistant = message.messageId == latestAssistantId
                        val isStreaming = !message.isUser && message.isCurrentSegment && (message.turnId in activeTurnIds ||
                            (activeTurnIds.isEmpty() && isLatestAssistant && isProcessing))
                        val isQueued = message.turnId in queuedTurnIds
                        val turnPresentation = if (message.isUser) {
                            null
                        } else {
                            presentationsByMessage[message.messageId]?.takeIf {
                                message.showsPresentationFor(it.turnId, it.messageId)
                            }
                        }
                        val ownsRetainedVirtualExecution = message.messageId == retainedViewerMessageId
                        ChatBubble(
                            text = message.text,
                            isUser = message.isUser,
                            availableSkills = skills,
                            onOpenSkill = onOpenSkill,
                            timestamp = message.timestamp,
                            timestampMs = message.timestampMs,
                            animateEntry = conversationLoaded && message.messageId !in enteredUserMessageIds,
                            onEntryStarted = { enteredUserMessageIds += message.messageId },
                            isThinking = isStreaming && message.text.isEmpty(),
                            isStreaming = isStreaming,
                            isQueued = isQueued,
                            isPaused = isStreaming && !isQueued && isPaused,
                            liveIntervention = manualTakeoverReason.takeIf { isStreaming && !isQueued }.orEmpty(),
                            statusText = if (isQueued) stringResource(R.string.chat_status_queued) else if (isStreaming) currentAction else "",
                            startedAtMs = message.startedAtMs,
                            completedAtMs = message.completedAtMs,
                            imageUris = message.imageUris,
                            imageBytes = message.imageBytes,
                            turnPresentation = turnPresentation,
                            tokenUsage = if (!message.isUser && message.turnId != null && message.isCurrentSegment && !isQueued)
                                tokenUsage.turns[message.turnId] ?: TokenUsageTotals() else null,
                            loadTimelineAttachment = viewModel::loadTimelineAttachment,
                            canOpenVirtualSpace = ownsRetainedVirtualExecution && retainedVirtualContent,
                            onOpenVirtualSpace = { viewModel.openVirtualSpaceViewer(retainedVirtualExecution?.takeIf {
                                it.threadId == viewModel.conversationId
                            }) },
                            onStop = viewModel::stopTask,
                            onExecutionLayoutChange = onExecutionLayoutChange,
                            pendingPermission = pendingPermission.takeIf { isStreaming && !isQueued },
                            onRequestPermission = viewModel::requestRuntimePermission,
                            toolConfirmation = userInteraction?.takeIf {
                                it.kind == UserInteractionKind.HIGH_RISK_CONFIRMATION && !message.isUser &&
                                    message.isCurrentSegment && message.turnId == it.turnId && !isQueued
                            },
                            onToolConfirmation = viewModel::respondToToolConfirmation
                        )
                    }

                    if (appTakeoverStatusUiState.visible) {
                        item(key = "app_takeover_log_card") {
                            AppTakeoverLogCard(
                                title = appTakeoverStatusUiState.title,
                                detail = appTakeoverStatusUiState.detail,
                                active = appTakeoverStatusUiState.active,
                                turnPresentations = appTakeoverStatusUiState.turnPresentations,
                                loadTimelineAttachment = viewModel::loadTimelineAttachment,
                                onOpenVirtualSpace = { viewModel.openVirtualSpaceViewer(retainedVirtualExecution) },
                                onStop = viewModel::stopAppTakeoverStatus,
                                onExecutionLayoutChange = onExecutionLayoutChange
                            )
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showJumpToLatest && !jumpingToLatest,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    enter = fadeIn(tween(AppMotion.FeedbackMillis)),
                    exit = fadeOut(tween(AppMotion.PressMillis))
                ) {
                    Surface(
                        onClick = {
                            latestJumpJob?.cancel()
                            latestJumpJob = scope.launch {
                                jumpingToLatest = true
                                try {
                                    val index = if (appTakeoverStatusUiState.visible) visibleMessages.size else visibleMessages.lastIndex
                                    if (index >= 0 && motionEnabled) {
                                        listState.animateScrollToItem(index)
                                        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                                        val overflow = item?.let { it.offset + it.size - listState.layoutInfo.viewportEndOffset } ?: 0
                                        if (overflow > 0) listState.animateScrollBy(overflow.toFloat(), tween(AppMotion.ContentMillis, easing = AppMotion.Settle))
                                    }
                                    scrollController.followLatest()
                                } finally { jumpingToLatest = false }
                            }
                        },
                        shape = CircleShape,
                        color = droidanceFloatingSurfaceColor(),
                        shadowElevation = 2.dp
                    ) {
                        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(DroidanceIcons.ChevronDown, null, Modifier.size(16.dp))
                            Text(stringResource(R.string.chat_jump_to_latest), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            // ====== 底部输入区域 ======
            DeviceConnectionNotice(state = connectionState, onClick = onConnectDevice)
            Box(Modifier.fillMaxWidth().onSizeChanged { composerHeightPx = it.height }) {
                ChatInputBar(
                    modelOptions = composerModels,
                    preferences = composerPreferences,
                    onOpenModelPicker = viewModel::refreshComposerModels,
                    onModelSelected = viewModel::selectComposerModel,
                    onDraftChanged = { hasDraft = it },
                    onError = viewModel::reportUiFailure,
                    availableSkills = skills,
                    initialText = viewModel.composerInitialText,
                    initialSkillId = initialSkillId,
                    initialSkillDraft = initialSkillDraft,
                    onManageSkills = onManageSkills,
                    isProcessing = isProcessing,
                    isEnabled = threadProjectionFailure == null,
                    queuedMessages = messages.filter { it.isUser && it.turnId in queuedTurnIds },
                    onDeleteQueuedMessage = viewModel::deleteQueuedMessage,
                    onSteerQueuedMessage = viewModel::steerQueuedMessage,
                    onSendMessage = { text, mode, selected ->
                        scrollController.followLatest()
                        viewModel.sendMessage(text, submissionMode = mode, requestedSkills = selected)
                    },
                    onSendImages = { text, uris, mode, selected ->
                        scrollController.followLatest()
                        viewModel.sendMessageWithImages(text, uris, mode, selected)
                    }
                )
            }
        }

        // Snackbar（显示非致命错误，不进入 chat）
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = composerHeight + 8.dp),
            snackbar = { DroidanceSnackbar(it) }
        )
    }
}

@Composable
private fun ThreadProjectionFailureNotice(
    failure: AgentEventProjectionFailureSnapshot
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = DroidanceIcons.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.home_thread_quarantined_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = stringResource(
                        R.string.home_thread_quarantined_detail,
                        failure.code,
                        failure.eventId.orEmpty().ifBlank { "-" }
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** Position a retained conversation once; subsequent updates must not move a history reader. */
@Composable
internal fun rememberConversationTurnFocus(
    listState: LazyListState,
    messages: List<UIMessage>,
    loaded: Boolean,
    targetTurnId: String?,
    onPositioned: (Boolean) -> Unit
): Boolean {
    var pending by rememberSaveable(targetTurnId) { mutableStateOf(!targetTurnId.isNullOrBlank()) }
    val currentOnPositioned by rememberUpdatedState(onPositioned)
    LaunchedEffect(loaded, messages, targetTurnId) {
        if (!pending || !loaded) return@LaunchedEffect
        val index = messages.indexOfFirst { it.turnId == targetTurnId }
        if (index >= 0) listState.scrollToItem(index)
        currentOnPositioned(index >= 0)
        pending = false
    }
    return pending
}

internal suspend fun LazyListState.followLatestItemToBottom(itemIndex: Int, shouldFollow: () -> Boolean = { true }) {
    if (!shouldFollow()) return
    scrollToItem(itemIndex)
    snapshotFlow {
        val layout = layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == itemIndex }
        layout.totalItemsCount to item?.let {
            (it.offset + it.size - (layout.viewportEndOffset - layout.afterContentPadding))
                .coerceAtLeast(0)
        }
    }.collect { (itemCount, bottomOverflow) ->
        // Insets and item measurements can publish during layout. Scroll on the
        // next frame so following a keyboard resize cannot re-enter measurement.
        withFrameNanos { }
        if (!shouldFollow()) return@collect
        if (itemCount > itemIndex) {
            if (bottomOverflow == null) {
                scrollToItem(itemIndex)
            } else if (bottomOverflow > 0) {
                scrollBy(bottomOverflow.toFloat())
            }
        }
    }
}

/**
 * 欢迎消息
 */
@Composable
private fun WelcomeMessage(hasDraft: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        MascotStage(
            modifier = Modifier.size(104.dp),
            mood = if (hasDraft) MascotMood.LISTENING else MascotMood.DANCING
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.home_welcome_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Start
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.home_welcome_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            textAlign = TextAlign.Start
        )
    }
}

// ====== 对话分享功能 ======

/**
 * 将对话导出为文件并调用系统分享
 * @param format "md" 或 "html"
 */
internal fun shareConversation(context: android.content.Context, messages: List<UIMessage>, format: String) {
    if (messages.isEmpty()) return

    val dateStr = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date())
    val fileName = "Droidance_$dateStr.$format"

    val content = if (format == "html") {
        buildConversationHtml(context, messages)
    } else {
        buildConversationMarkdown(context, messages)
    }

    try {
        // 写入缓存目录（FileProvider 可以安全分享）
        val shareDir = File(context.cacheDir, "share")
        shareDir.mkdirs()
        val file = File(shareDir, fileName)
        file.writeText(content)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val mimeType = if (format == "html") "text/html" else "text/markdown"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_conversation_subject, dateStr))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_conversation_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (error: Exception) {
        android.util.Log.w("DroidanceShare", "Conversation export failed: ${error.javaClass.simpleName}")
    }
}

private fun buildConversationMarkdown(context: Context, messages: List<UIMessage>): String = buildString {
    appendLine("# ${context.getString(R.string.conversation_export_title)}")
    appendLine()
    appendLine(context.getString(R.string.conversation_export_time, SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())))
    appendLine()
    appendLine("---")
    appendLine()

    for (msg in messages) {
        val role = if (msg.isUser) "🧑 **${context.getString(R.string.conversation_export_user)}**" else "🤖 **Droidance**"
        appendLine("$role _(${msg.timestamp})_")
        appendLine()
        appendLine(msg.text)
        appendLine()
        appendLine("---")
        appendLine()
    }
}

private fun buildConversationHtml(context: Context, messages: List<UIMessage>): String = buildString {
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    appendLine("<!DOCTYPE html>")
    appendLine("<html lang=\"${context.resources.configuration.locales[0].toLanguageTag()}\"><head><meta charset=\"UTF-8\">")
    appendLine("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
    appendLine("<title>${context.getString(R.string.conversation_export_title)}</title>")
    appendLine("<style>")
    appendLine("body{font-family:-apple-system,system-ui,sans-serif;max-width:720px;margin:0 auto;padding:16px;background:#f5f5f5;}")
    appendLine(".msg{margin:12px 0;padding:12px 16px;border-radius:12px;line-height:1.6;white-space:pre-wrap;word-break:break-word;}")
    appendLine(".user{background:#e3f2fd;margin-left:48px;border-bottom-right-radius:4px;}")
    appendLine(".bot{background:#fff;margin-right:48px;border-bottom-left-radius:4px;box-shadow:0 1px 2px rgba(0,0,0,.08);}")
    appendLine(".role{font-weight:600;font-size:13px;color:#666;margin-bottom:4px;}")
    appendLine(".time{font-size:11px;color:#999;margin-top:4px;}")
    appendLine("h1{text-align:center;font-size:18px;color:#333;}")
    appendLine(".meta{text-align:center;font-size:12px;color:#999;margin-bottom:20px;}")
    appendLine("</style></head><body>")
    appendLine("<h1>${context.getString(R.string.conversation_export_title)}</h1>")
    appendLine("<p class=\"meta\">${context.getString(R.string.conversation_export_time, dateStr)}</p>")

    for (msg in messages) {
        val cls = if (msg.isUser) "user" else "bot"
        val role = if (msg.isUser) "🧑 ${context.getString(R.string.conversation_export_user)}" else "🤖 Droidance"
        val escaped = msg.text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        appendLine("<div class=\"msg $cls\">")
        appendLine("  <div class=\"role\">$role</div>")
        appendLine("  <div>$escaped</div>")
        appendLine("  <div class=\"time\">${msg.timestamp}</div>")
        appendLine("</div>")
    }

    appendLine("</body></html>")
}
