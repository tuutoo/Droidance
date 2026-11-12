// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.theme.AppShapes
import com.droidance.ui.theme.LocalAppIsDark
import android.content.Context
import com.droidance.ui.execution.PendingPermissionUiState
import com.droidance.core.agent.UserInteractionRequest
import com.droidance.core.agent.UserInteractionState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalConfiguration
import com.droidance.core.knowledge.skill.SkillInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.droidance.R
import com.droidance.config.TokenUsageTotals
import com.droidance.core.agent.event.DetailAttachmentReadModel
import com.droidance.core.agent.harness.renderResultCardMarkdown
import com.droidance.ui.components.FlatMarkdown
import com.droidance.ui.components.rememberAppHaptics
import com.droidance.ui.theme.AppMotion
import com.droidance.ui.execution.ExecutionProcessHistory
import com.droidance.ui.execution.ExecutionLogLayout
import com.droidance.ui.execution.EventStoreExecutionTimelinePresenter
import com.droidance.ui.execution.TurnPresentationUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ExecutionOpenControlSize = 26.dp
private val ExecutionControlTouchSize = 40.dp
private val ExecutionControlGlyphSize = 14.dp
internal enum class TakeoverActionState {
    PENDING,
    RESOLVED,
    CLOSED
}

internal data class TakeoverActionPresentation(
    val interactionId: String,
    val title: String?,
    val reason: String,
    val state: TakeoverActionState
)


private fun publicDialogueText(text: String): String =
    text.trim()


private enum class TaskCardStatus { RUNNING, PAUSED, DONE, FAILED, STOPPED, UNFINISHED }

internal fun mobileMarkdownContent(content: String): String {
    return content.trimEnd()
}


@Composable
private fun EventStoreTaskCardBubble(
    presentation: TurnPresentationUiState,
    completedAtMs: Long,
    isStreaming: Boolean,
    isPaused: Boolean,
    liveReply: String,
    liveIntervention: String,
    loadTimelineAttachment: suspend (DetailAttachmentReadModel) -> ByteArray?,
    canOpenVirtualSpace: Boolean,
    onOpenVirtualSpace: () -> Unit,
    onStop: () -> Unit,
    onExecutionLayoutChange: () -> Unit,
    pendingPermission: PendingPermissionUiState? = null,
    onRequestPermission: () -> Unit = {},
    toolConfirmation: UserInteractionRequest? = null,
    onToolConfirmation: (String, Boolean) -> Unit = { _, _ -> }
) {
    val activity = presentation.activity
    val paused = isPaused || presentation.isPaused(isPaused)
    val intervention = presentation.handoffReason(liveIntervention)
    val dialogue = presentation.liveDialogue(liveReply)
    val opening = publicDialogueText(presentation.opening)
    val finalSummary = publicDialogueText(presentation.finalSummary)
    val liveStatus = remember(presentation, isStreaming, isPaused) { presentation.liveStatus(isStreaming, isPaused) }
    val progressHistory = remember(presentation, isStreaming, isPaused) {
        presentation.progressHistory(isStreaming, isPaused)
    }
    val hasTimeline = remember(activity, progressHistory) {
        EventStoreExecutionTimelinePresenter.hasHistory(activity, progressHistory)
    }
    var expanded by remember(presentation.messageId) { mutableStateOf(false) }
    val status = when {
        paused -> TaskCardStatus.PAUSED
        presentation.isTerminal -> when (presentation.terminalStatus) {
            "done" -> TaskCardStatus.DONE
            "error", "failed" -> TaskCardStatus.FAILED
            "need_more_info" -> TaskCardStatus.UNFINISHED
            else -> TaskCardStatus.STOPPED
        }
        else -> TaskCardStatus.RUNNING
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (isStreaming && !presentation.hasPublicResponse(liveReply) && !presentation.showExecutionLog() &&
            liveStatus.isBlank()) {
            WaitingResponseBubble()
        }

        if (presentation.applyingGuidance) {
            Text(stringResource(if (paused) R.string.chat_guidance_waiting_resume else R.string.chat_applying_guidance),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("guidance-applying"))
        }
        if (!presentation.isCurrentSegment) {
            Text(stringResource(R.string.chat_guidance_history),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("guidance-history"))
        }

        if (opening.isNotBlank()) {
            SelectionContainer {
                FlatMarkdown(
                    content = mobileMarkdownContent(opening),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)
                )
            }
        }

        if (isStreaming && dialogue.isNotBlank()) {
            FlatMarkdown(
                content = mobileMarkdownContent(dialogue),
                streaming = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)
            )
        }

        if (presentation.showExecutionLog() || (pendingPermission != null && presentation.isCurrentSegment)) {
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("execution-task-card"),
                shape = AppShapes.Card,
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column {
                    AnimatedContent(
                        targetState = isStreaming && presentation.isCurrentSegment && !presentation.isTerminal,
                        transitionSpec = {
                            fadeIn(tween(AppMotion.ContentMillis))
                                .togetherWith(fadeOut(tween(AppMotion.PressMillis)))
                        }, label = "executionStatus"
                    ) { running ->
                        if (running) {
                            RunningProcessStatusRow(
                                expanded = expanded,
                                onToggle = {
                                    expanded = !expanded
                                    onExecutionLayoutChange()
                                },
                                status = status,
                                startedAtMs = presentation.startedAtMs,
                                executionClock = presentation.executionClock,
                                isStreaming = true,
                                isPaused = paused,
                                recordCount = activity.operationCount,
                                canExpand = hasTimeline,
                                canOpenVirtualSpace = canOpenVirtualSpace,
                                onOpenVirtualSpace = onOpenVirtualSpace,
                                onStop = { if (!presentation.isTerminal) onStop() }
                            )
                        } else {
                            CompletedProcessStatusRow(
                                expanded = expanded,
                                onToggle = {
                                    expanded = !expanded
                                    onExecutionLayoutChange()
                                },
                                status = status,
                                startedAtMs = presentation.startedAtMs,
                                completedAtMs = activity.completedAt ?: completedAtMs,
                                activeDurationMillis = activity.activeDurationMillis,
                                allowWallClockDuration = !presentation.isSegmented,
                                isStreaming = false,
                                isPaused = paused,
                                recordCount = activity.operationCount,
                                canExpand = hasTimeline,
                                canOpenVirtualSpace = canOpenVirtualSpace,
                                onOpenVirtualSpace = onOpenVirtualSpace
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = expanded && hasTimeline,
                        enter = expandVertically(tween(AppMotion.ContentMillis, easing = AppMotion.Settle), expandFrom = Alignment.Top),
                        exit = shrinkVertically(tween(AppMotion.ContentMillis, easing = AppMotion.Settle), shrinkTowards = Alignment.Top)
                    ) {
                        Column {
                            ExecutionLogDivider()
                            ExecutionProcessHistory(
                                activity = activity,
                                progressHistory = progressHistory,
                                toolDetails = presentation.toolDetails,
                                roundDetails = presentation.roundDetails,
                                loadAttachment = loadTimelineAttachment,
                                modifier = Modifier.padding(horizontal = ExecutionLogLayout.HorizontalPadding, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        ReportedResults(presentation.reportedResults)

        if (presentation.showCurrentProgress(isStreaming, isPaused) &&
            (presentation.showExecutionLog() || liveStatus.isNotBlank() || presentation.reportedResults.isNotEmpty())) {
            TaskCurrentProgress(liveStatus)
        }

        if (presentation.resultCard != null) {
            StructuredResultCard(presentation.resultCard)
        } else {
            val output = finalSummary
            if (output.isNotBlank()) {
                SelectionContainer {
                    FlatMarkdown(
                        content = mobileMarkdownContent(output),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)
                    )
                }
            }
        }

        if (toolConfirmation != null) {
            ToolConfirmationActionBlock(toolConfirmation, onToolConfirmation)
        } else if (paused && pendingPermission == null) {
            TakeoverActionBlock(
                action = TakeoverActionPresentation(
                    interactionId = presentation.turnId,
                    title = presentation.handoffTitle(),
                    reason = publicDialogueText(intervention),
                    state = TakeoverActionState.PENDING
                ),
                actionEnabled = canOpenVirtualSpace,
                onOpenVirtualSpace = onOpenVirtualSpace
            )
        }

        if (paused && pendingPermission != null) {
            PermissionActionBlock(
                pendingPermission = pendingPermission,
                onRequestPermission = onRequestPermission
            )
        }


    }
}

@Composable
private fun StructuredResultCard(card: com.droidance.core.agent.event.ResultCardReadModel) {
    val markdown = remember(card) {
        renderResultCardMarkdown(
            title = card.title,
            items = card.items.map { it.text to it.deeplink }
        )
    }
    SelectionContainer {
        FlatMarkdown(
            content = markdown,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)
        )
    }
}


@Composable
internal fun AppTakeoverLogCard(
    title: String,
    detail: String,
    active: Boolean,
    turnPresentations: List<TurnPresentationUiState>,
    loadTimelineAttachment: suspend (DetailAttachmentReadModel) -> ByteArray?,
    onOpenVirtualSpace: () -> Unit,
    onStop: () -> Unit,
    onExecutionLayoutChange: () -> Unit
) {
    val currentTurn = turnPresentations.lastOrNull()
    val executionKey = turnPresentations.lastOrNull()?.turnId.orEmpty()
    var expanded by remember(executionKey) { mutableStateOf(false) }
    val canExpand = turnPresentations.any { turn ->
        EventStoreExecutionTimelinePresenter.hasHistory(
            turn.activity, turn.progressHistory(active && turn.turnId == currentTurn?.turnId)
        )
    }
    val recordCount = turnPresentations.sumOf { it.activity.operationCount }
    val recordSummary = stringResource(R.string.chat_process_record_count, recordCount)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShapes.Card,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column {
                ExecutionLogHeader(
                    expanded = expanded,
                    canExpand = canExpand,
                    onToggle = {
                        expanded = !expanded
                        onExecutionLayoutChange()
                    },
                    summary = {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = listOfNotNull(title, recordSummary).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (detail.isNotBlank()) {
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    controls = {
                        ExecutionLogControls(
                            canOpenVirtualSpace = true,
                            onOpenVirtualSpace = onOpenVirtualSpace,
                            onStop = onStop
                        )
                    }
                )

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(tween(AppMotion.ContentMillis, easing = AppMotion.Settle), expandFrom = Alignment.Top),
                    exit = shrinkVertically(tween(AppMotion.ContentMillis, easing = AppMotion.Settle), shrinkTowards = Alignment.Top)
                ) {
                    Column {
                        ExecutionLogDivider()
                        turnPresentations.forEach { turn ->
                            ExecutionProcessHistory(
                                activity = turn.activity,
                                progressHistory = turn.progressHistory(active && turn.turnId == currentTurn?.turnId),
                                toolDetails = turn.toolDetails,
                                roundDetails = turn.roundDetails,
                                loadAttachment = loadTimelineAttachment,
                                modifier = Modifier.padding(horizontal = ExecutionLogLayout.HorizontalPadding, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
        turnPresentations.forEach { turn ->
            ReportedResults(turn.reportedResults)
        }
        if (currentTurn?.showCurrentProgress(active) == true) {
            TaskCurrentProgress(currentTurn.liveStatus(active))
        }
    }
}

@Composable
private fun TaskCurrentProgress(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("execution-task-progress")
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        if (text.isNotBlank()) {
            ShimmeringRunningLogText(text, Modifier.fillMaxWidth())
        } else {
            ResponseWaveDots()
        }
    }
}

@Composable
private fun ShimmeringRunningLogText(text: String, modifier: Modifier = Modifier) {
    if (!com.droidance.ui.theme.LocalAppMotionEnabled.current) {
        FlatMarkdown(content = mobileMarkdownContent(text), modifier = modifier)
        return
    }
    val baseAlpha = if (LocalAppIsDark.current) 0.66f else 0.64f
    val transition = rememberInfiniteTransition(label = "runningLogShimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    )

    // A slightly wider opacity range keeps the neutral sweep visible on both backgrounds.
    FlatMarkdown(
        content = mobileMarkdownContent(text),
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val bandWidth = (size.width * 0.45f).coerceIn(100f, 320f)
                val startX = -bandWidth + progress * (size.width + bandWidth * 2f)
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Black.copy(alpha = baseAlpha), Color.Black,
                            Color.Black.copy(alpha = baseAlpha)),
                        start = Offset(startX, 0f),
                        end = Offset(startX + bandWidth, 0f)
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    )
}

@Composable
private fun TakeoverActionBlock(
    action: TakeoverActionPresentation,
    actionEnabled: Boolean,
    onOpenVirtualSpace: () -> Unit
) {
    if (action.reason.isNotBlank()) {
        SelectionContainer {
            FlatMarkdown(
                content = mobileMarkdownContent(action.reason),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)
            )
        }
    }
    TakeoverStyleActionCard(
        title = when (action.state) {
            TakeoverActionState.PENDING -> action.title ?: stringResource(R.string.chat_takeover_title)
            TakeoverActionState.RESOLVED -> stringResource(R.string.chat_takeover_resolved)
            TakeoverActionState.CLOSED -> stringResource(R.string.chat_takeover_closed)
        },
        hint = if (action.state == TakeoverActionState.PENDING) {
            stringResource(R.string.chat_takeover_resume_hint)
        } else "",
        buttonText = if (action.state == TakeoverActionState.PENDING) {
            stringResource(R.string.chat_takeover_handle)
        } else null,
        buttonEnabled = actionEnabled,
        onButtonClick = onOpenVirtualSpace
    )
}

@Composable
private fun TakeoverStyleActionCard(
    title: String,
    reason: String = "",
    hint: String = "",
    buttonText: String? = null,
    buttonEnabled: Boolean = true,
    onButtonClick: () -> Unit = {},
    actions: @Composable ColumnScope.() -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Control,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
            )
            if (reason.isNotBlank()) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                )
            }
            if (hint.isNotBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.54f)
                )
            }
            if (buttonText != null) {
                Button(
                    onClick = onButtonClick,
                    enabled = buttonEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .defaultMinSize(minHeight = 48.dp),
                    shape = AppShapes.Control,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp,
                        vertical = 9.dp
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
                    ),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                    )
                ) {
                    Text(
                        text = buttonText,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Start
                    )
                }
            }
            actions()
        }
    }
}

@Composable
internal fun ToolConfirmationActionBlock(
    request: UserInteractionRequest,
    onResponse: (String, Boolean) -> Unit
) {
    val title = when (request.state) {
        UserInteractionState.PENDING -> stringResource(R.string.tool_confirmation_title, request.toolName.orEmpty())
        UserInteractionState.DENIED -> stringResource(R.string.tool_confirmation_denied)
        UserInteractionState.EXPIRED -> stringResource(R.string.tool_confirmation_expired)
        else -> return
    }
    TakeoverStyleActionCard(
        title = title,
        reason = if (request.state == UserInteractionState.PENDING) stringResource(R.string.tool_confirmation_reason) else ""
    ) {
        if (request.state == UserInteractionState.PENDING) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { onResponse(request.requestId, false) }) {
                    Text(stringResource(R.string.tool_confirmation_deny))
                }
                Button(onClick = { onResponse(request.requestId, true) }) {
                    Text(stringResource(R.string.tool_confirmation_allow_once))
                }
            }
        }
    }
}

@Composable
private fun PermissionActionBlock(
    pendingPermission: PendingPermissionUiState,
    onRequestPermission: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionNames = remember(pendingPermission.permissions) {
        pendingPermission.permissions.map { permissionDisplayName(context, it) }.distinct()
    }
    val reasonText = buildString {
        if (permissionNames.isNotEmpty()) {
            append(context.getString(R.string.chat_permission_names_format, permissionNames.joinToString("、")))
        }
        if (pendingPermission.reason.isNotBlank()) {
            if (isNotEmpty()) append("\n")
            append(pendingPermission.reason)
        }
    }
    TakeoverStyleActionCard(
        title = stringResource(R.string.chat_permission_title),
        reason = reasonText,
        hint = stringResource(
            if (pendingPermission.settingsRequired) R.string.chat_permission_settings_hint
            else R.string.chat_permission_grant_hint
        ),
        buttonText = stringResource(
            if (pendingPermission.settingsRequired) R.string.chat_permission_grant_settings_action
            else R.string.chat_permission_grant_action
        ),
        buttonEnabled = true,
        onButtonClick = onRequestPermission
    )
}

internal fun permissionDisplayName(context: Context, permission: String): String = when (permission) {
    android.Manifest.permission.READ_SMS -> context.getString(R.string.settings_permission_read_sms)
    android.Manifest.permission.SEND_SMS -> context.getString(R.string.settings_permission_send_sms)
    android.Manifest.permission.RECEIVE_SMS -> context.getString(R.string.settings_permission_receive_sms)
    android.Manifest.permission.READ_CONTACTS -> context.getString(R.string.settings_permission_read_contacts)
    android.Manifest.permission.WRITE_CONTACTS -> context.getString(R.string.settings_permission_write_contacts)
    android.Manifest.permission.READ_CALL_LOG -> context.getString(R.string.settings_permission_read_call_log)
    android.Manifest.permission.WRITE_CALL_LOG -> context.getString(R.string.settings_permission_write_call_log)
    android.Manifest.permission.READ_CALENDAR -> context.getString(R.string.settings_permission_read_calendar)
    android.Manifest.permission.WRITE_CALENDAR -> context.getString(R.string.settings_permission_write_calendar)
    android.Manifest.permission.READ_MEDIA_IMAGES -> context.getString(R.string.settings_permission_media_images)
    android.Manifest.permission.READ_MEDIA_VIDEO -> context.getString(R.string.settings_permission_media_video)
    android.Manifest.permission.READ_MEDIA_AUDIO -> context.getString(R.string.settings_permission_media_audio)
    android.Manifest.permission.READ_EXTERNAL_STORAGE -> context.getString(R.string.settings_permission_read_storage_legacy)
    else -> runCatching {
        val pm = context.packageManager
        val info = pm.getPermissionInfo(permission, 0)
        info.loadLabel(pm).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: permission.substringAfterLast('.')
}

internal fun effectiveTaskStatusName(
    statusName: String,
    isPaused: Boolean,
    isStreaming: Boolean
): String =
    when {
        isPaused -> TaskCardStatus.PAUSED.name
        statusName == TaskCardStatus.RUNNING.name && !isStreaming -> TaskCardStatus.UNFINISHED.name
        else -> statusName
    }

@Composable
private fun WaitingResponseBubble(onCancel: (() -> Unit)? = null) {
    Box(modifier = Modifier.padding(start = 6.dp)) {
        SimpleMessageBubble(isUser = false) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResponseWaveDots()
                if (onCancel != null) {
                    Spacer(Modifier.weight(1f))
                    ExecutionLogControls(canOpenVirtualSpace = false, onOpenVirtualSpace = {}, onStop = onCancel)
                }
            }
        }
    }
}

private data class QuietDotMetrics(val yOffsetDp: Float, val alpha: Float, val scale: Float)

private fun calculateQuietDotMetrics(t: Float): QuietDotMetrics {
    val normalized = t.coerceIn(0f, 1f)
    return when {
        normalized < 0.08f -> {
            val u = normalized / 0.08f
            QuietDotMetrics(
                yOffsetDp = 1.2f - u * 1.0f,
                alpha = 0.25f + u * 0.15f,
                scale = 0.88f + u * 0.08f
            )
        }
        normalized < 0.22f -> {
            val u = (normalized - 0.08f) / 0.14f
            val ease = u * u * (3f - 2f * u)
            QuietDotMetrics(
                yOffsetDp = 0.2f - ease * 2.8f,
                alpha = 0.40f + ease * 0.60f,
                scale = 0.96f + ease * 0.18f
            )
        }
        normalized < 0.36f -> {
            val u = (normalized - 0.22f) / 0.14f
            val ease = u * u * (3f - 2f * u)
            QuietDotMetrics(
                yOffsetDp = -2.6f + ease * 2.6f,
                alpha = 1.0f - ease * 0.15f,
                scale = 1.14f - ease * 0.14f
            )
        }
        normalized < 0.70f -> {
            QuietDotMetrics(
                yOffsetDp = 0f,
                alpha = 0.85f,
                scale = 1.0f
            )
        }
        normalized < 0.90f -> {
            val u = (normalized - 0.70f) / 0.20f
            QuietDotMetrics(
                yOffsetDp = 0f,
                alpha = 0.85f - u * 0.60f,
                scale = 1.0f - u * 0.12f
            )
        }
        else -> {
            QuietDotMetrics(
                yOffsetDp = 1.2f,
                alpha = 0.25f,
                scale = 0.88f
            )
        }
    }
}

@Composable
private fun ResponseWaveDots(modifier: Modifier = Modifier) {
    val motion = com.droidance.ui.theme.LocalAppMotionEnabled.current
    val masterProgress = if (motion) rememberInfiniteTransition(label = "responseQuietDots").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "quietDotMasterProgress"
    ) else remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }
    Row(
        modifier = modifier
            .testTag("response-wave")
            .padding(start = 0.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val delayFraction = (index * 150f) / 1900f
            Box(
                Modifier
                    .testTag("response-wave-dot")
                    .size(5.5.dp)
                    .graphicsLayer {
                        val t = (masterProgress.value - delayFraction + 1f) % 1f
                        val metrics = if (motion) calculateQuietDotMetrics(t) else QuietDotMetrics(0f, 0.6f, 1f)
                        translationY = metrics.yOffsetDp.dp.toPx()
                        alpha = metrics.alpha
                        scaleX = metrics.scale
                        scaleY = metrics.scale
                    }
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun RunningProcessStatusRow(
    expanded: Boolean,
    onToggle: () -> Unit,
    status: TaskCardStatus,
    startedAtMs: Long,
    executionClock: com.droidance.core.agent.TaskExecutionClock.Snapshot?,
    isStreaming: Boolean,
    isPaused: Boolean,
    recordCount: Int,
    canExpand: Boolean,
    canOpenVirtualSpace: Boolean,
    onOpenVirtualSpace: () -> Unit,
    onStop: () -> Unit
) {
    val effectiveStatus = TaskCardStatus.valueOf(
        effectiveTaskStatusName(status.name, isPaused, isStreaming)
    )
    val tickerKey = isProcessTickerActive(
        effectiveStatus = effectiveStatus.name,
        startedAtMs = startedAtMs,
        isStreaming = isStreaming
    ) && executionClock?.runningSinceMs != null
    val now by produceState(
        initialValue = com.droidance.core.agent.TaskExecutionClock.monotonicTimeMs(),
        key1 = tickerKey,
        key2 = executionClock
    ) {
        while (tickerKey) {
            value = com.droidance.core.agent.TaskExecutionClock.monotonicTimeMs()
            kotlinx.coroutines.delay(1000)
        }
        value = com.droidance.core.agent.TaskExecutionClock.monotonicTimeMs()
    }
    val durationMs = executionClock?.elapsedMs(now) ?: 0L
    val processText = stringResource(R.string.chat_elapsed, formatDuration(durationMs))
    val recordSummary = stringResource(R.string.chat_process_record_count, recordCount)
    ExecutionLogHeader(
        expanded = expanded,
        canExpand = canExpand,
        onToggle = onToggle,
        modifier = Modifier.testTag("execution-process-status-row"),
        summary = {
            ExecutionProcessSummary(processText, recordSummary)
        },
        controls = {
            ExecutionLogControls(
                canOpenVirtualSpace = canOpenVirtualSpace,
                onOpenVirtualSpace = onOpenVirtualSpace,
                onStop = onStop
            )
        }
    )
}

@Composable
private fun ExecutionLogHeader(
    expanded: Boolean,
    canExpand: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    summary: @Composable () -> Unit,
    controls: @Composable () -> Unit
) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f,
        tween(AppMotion.ContentMillis, easing = AppMotion.Settle), label = "executionDisclosure")
    val toggleLabel = stringResource(if (expanded) R.string.chat_collapse_process else R.string.chat_view_process)
    Row(
        modifier = modifier.fillMaxWidth()
            .padding(start = ExecutionLogLayout.HorizontalPadding, end = 4.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                .testTag("execution-records-toggle")
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = canExpand,
                    onClickLabel = toggleLabel,
                    onClick = onToggle
                )
                .semantics { contentDescription = toggleLabel },
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExecutionDisclosure(rotation = rotation, canExpand = canExpand)
            Spacer(Modifier.width(ExecutionLogLayout.TextGap))
            Box(Modifier.weight(1f)) { summary() }
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { controls() }
    }
}

@Composable
private fun ExecutionDisclosure(rotation: Float, canExpand: Boolean) {
    Icon(
        DroidanceIcons.ChevronRight,
        contentDescription = null,
        modifier = Modifier.width(ExecutionLogLayout.MarkerWidth)
            .wrapContentSize(unbounded = true).size(16.dp).rotate(rotation),
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (canExpand) 0.72f else 0.38f)
    )
}

@Composable
private fun ExecutionLogDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = ExecutionLogLayout.HorizontalPadding),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    )
}

@Composable
private fun ExecutionLogControls(
    canOpenVirtualSpace: Boolean,
    onOpenVirtualSpace: () -> Unit,
    onStop: () -> Unit
) {
    if (canOpenVirtualSpace) {
        VirtualSpaceOpenControl(onOpenVirtualSpace)
    }
    IconButton(onClick = onStop, modifier = Modifier.size(ExecutionControlTouchSize)) {
        Surface(
            modifier = Modifier.size(ExecutionOpenControlSize),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f),
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)
            )
        ) {
            Box(contentAlignment = Alignment.Center) {
                ConversationActionGlyph(
                    iconRes = R.drawable.ic_conversation_stop,
                    contentDescription = stringResource(R.string.app_takeover_status_stop),
                    modifier = Modifier.size(ExecutionControlGlyphSize)
                )
            }
        }
    }
}

@Composable
private fun VirtualSpaceOpenControl(onOpenVirtualSpace: () -> Unit) {
    IconButton(onClick = onOpenVirtualSpace, modifier = Modifier.size(ExecutionControlTouchSize)) {
        Surface(
            modifier = Modifier.size(ExecutionOpenControlSize),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f),
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)
            )
        ) {
            Box(contentAlignment = Alignment.Center) {
                ConversationActionGlyph(
                    iconRes = R.drawable.ic_conversation_open,
                    contentDescription = stringResource(R.string.virtual_space_viewer_open),
                    modifier = Modifier.size(ExecutionControlGlyphSize)
                )
            }
        }
    }
}

@Composable
private fun CompletedProcessStatusRow(
    expanded: Boolean,
    onToggle: () -> Unit,
    status: TaskCardStatus,
    startedAtMs: Long,
    completedAtMs: Long,
    activeDurationMillis: Long? = null,
    allowWallClockDuration: Boolean = true,
    isStreaming: Boolean,
    isPaused: Boolean,
    recordCount: Int,
    canExpand: Boolean,
    canOpenVirtualSpace: Boolean,
    onOpenVirtualSpace: () -> Unit
) {
    val durationMs = computeProcessDurationMs(
        startedAtMs = startedAtMs,
        completedAtMs = completedAtMs,
        activeDurationMillis = activeDurationMillis
    )
    val processText = if (activeDurationMillis == null && !allowWallClockDuration) {
        stringResource(R.string.execution_process_duration_unrecorded)
    } else stringResource(R.string.chat_elapsed, formatDuration(durationMs))
    val recordSummary = stringResource(R.string.chat_process_record_count, recordCount)
    ExecutionLogHeader(
        expanded = expanded,
        canExpand = canExpand,
        onToggle = onToggle,
        modifier = Modifier.testTag("execution-process-status-row"),
        summary = {
            ExecutionProcessSummary(processText, recordSummary)
        },
        controls = {
            if (canOpenVirtualSpace) {
                VirtualSpaceOpenControl(onOpenVirtualSpace)
            }
        }
    )
}

@Composable
private fun ExecutionProcessSummary(elapsed: String, interactions: String) {
    Text(
        text = "$elapsed · $interactions",
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
    )
}

internal fun computeProcessDurationMs(
    startedAtMs: Long,
    completedAtMs: Long,
    activeDurationMillis: Long? = null
): Long = when {
    activeDurationMillis != null -> activeDurationMillis.coerceAtLeast(0L)
    startedAtMs > 0L && completedAtMs > 0L -> (completedAtMs - startedAtMs).coerceAtLeast(0L)
    else -> 0L
}

internal fun isProcessTickerActive(
    effectiveStatus: String,
    startedAtMs: Long,
    isStreaming: Boolean
): Boolean = effectiveStatus == "RUNNING" &&
    startedAtMs > 0L && isStreaming

@Composable
private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val hour = min / 60
    return when {
        hour > 0 -> stringResource(R.string.chat_duration_hours, hour, min % 60, sec)
        min > 0 -> stringResource(R.string.chat_duration_minutes, min, sec)
        else -> stringResource(R.string.chat_duration_seconds, sec)
    }
}

internal fun formatMessageTimestamp(
    timestampMs: Long,
    fallbackLabel: String,
    nowMs: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault()
): String {
    if (timestampMs <= 0L) return fallbackLabel

    // 今天的消息只显示时间，不显示日期；往日的才显示日期+时间。
    val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    if (dayFmt.format(Date(timestampMs)) == dayFmt.format(Date(nowMs))) {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
    }
    return java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, locale
    ).format(Date(timestampMs))
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ChatBubble(
    text: String,
    isUser: Boolean,
    timestamp: String,
    availableSkills: List<SkillInfo> = emptyList(),
    onOpenSkill: (SkillInfo) -> Unit = {},
    timestampMs: Long = 0L,
    animateEntry: Boolean = false,
    onEntryStarted: () -> Unit = {},
    isThinking: Boolean = false,
    isStreaming: Boolean = false,
    isQueued: Boolean = false,
    isPaused: Boolean = false,
    statusText: String = "",
    startedAtMs: Long = 0L,
    completedAtMs: Long = 0L,
    imageUris: List<String> = emptyList(),
    imageBytes: List<ByteArray> = emptyList(),
    turnPresentation: TurnPresentationUiState? = null,
    tokenUsage: TokenUsageTotals? = null,
    liveIntervention: String = "",
    loadTimelineAttachment: suspend (DetailAttachmentReadModel) -> ByteArray? = { null },
    canOpenVirtualSpace: Boolean = false,
    onOpenVirtualSpace: () -> Unit = {},
    onStop: () -> Unit = {},
    onExecutionLayoutChange: () -> Unit = {},
    pendingPermission: PendingPermissionUiState? = null,
    onRequestPermission: () -> Unit = {},
    toolConfirmation: UserInteractionRequest? = null,
    onToolConfirmation: (String, Boolean) -> Unit = { _, _ -> }
) {
    val clipboardManager = LocalClipboardManager.current
    val haptics = rememberAppHaptics()
    var justCopied by remember { mutableStateOf(false) }
    var copyRequest by remember { mutableIntStateOf(0) }
    val messageTimeText = remember(timestampMs, timestamp) {
        formatMessageTimestamp(timestampMs = timestampMs, fallbackLabel = timestamp)
    }

    LaunchedEffect(justCopied, copyRequest) {
        if (justCopied) {
            kotlinx.coroutines.delay(AppMotion.CopyFeedbackMillis)
            justCopied = false
        }
    }

    val durableTurnPresentation = turnPresentation?.takeIf { !isUser }
    val durablePresentation = durableTurnPresentation?.takeIf {
        it.hasDurablePresentation
    }
    if (durableTurnPresentation?.isCurrentSegment == false && durablePresentation == null && text.isBlank()) return

    val copyText = remember(text, isUser, isPaused, liveIntervention, durablePresentation) {
        if (durablePresentation != null) {
            durablePresentation.publicCopyText(isPaused, liveIntervention)
        } else {
            publicDialogueText(text)
        }
    }

    if (durablePresentation != null) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            EventStoreTaskCardBubble(
                presentation = durablePresentation,
                completedAtMs = completedAtMs,
                isStreaming = isStreaming,
                isPaused = isPaused,
                liveReply = text,
                liveIntervention = liveIntervention,
                loadTimelineAttachment = loadTimelineAttachment,
                canOpenVirtualSpace = canOpenVirtualSpace,
                onOpenVirtualSpace = onOpenVirtualSpace,
                onStop = onStop,
                onExecutionLayoutChange = onExecutionLayoutChange,
                pendingPermission = pendingPermission,
                onRequestPermission = onRequestPermission,
                toolConfirmation = toolConfirmation,
                onToolConfirmation = onToolConfirmation
            )
            if (copyText.isNotBlank() || tokenUsage != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 0.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (copyText.isNotBlank()) {
                        MessageCopyButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(copyText))
                                haptics.confirm()
                                justCopied = true
                                copyRequest++
                            }
                        ) {
                            AnimatedContent(
                                targetState = justCopied,
                                transitionSpec = {
                                    (scaleIn(initialScale = 0.88f, animationSpec = AppMotion.controlSpring()) + fadeIn(tween(AppMotion.FeedbackMillis)))
                                        .togetherWith(scaleOut(targetScale = 0.88f, animationSpec = tween(AppMotion.FeedbackMillis)) + fadeOut(tween(AppMotion.PressMillis)))
                                },
                                label = "taskCardCopyTransition"
                            ) { copied ->
                                ConversationActionGlyph(
                                    if (copied) R.drawable.ic_conversation_check else R.drawable.ic_conversation_copy,
                                    contentDescription = stringResource(if (copied) R.string.chat_copied else R.string.chat_copy),
                                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                                )
                            }
                        }
                    }
                    if (!isStreaming && messageTimeText.isNotBlank()) {
                        Text(
                            text = messageTimeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (tokenUsage != null) {
                        TokenUsageLabel(tokenUsage)
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Column(
            modifier = if (isUser) {
                Modifier.widthIn(max = 320.dp)
            } else {
                Modifier.fillMaxWidth()
            }
        ) {
            when {
                isThinking && text.isEmpty() -> {
                    WaitingResponseBubble(onCancel = onStop.takeIf { isQueued })
                }

                isUser -> {
                    SimpleMessageBubble(isUser = true, animateEntry = animateEntry, onEntryStarted = onEntryStarted) {
                        val imageModels = imageUris.map(android.net.Uri::parse) + imageBytes
                        if (imageModels.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(imageModels) { imageModel ->
                                    AsyncImage(
                                        model = imageModel,
                                        contentDescription = stringResource(R.string.chat_uploaded_image),
                                        modifier = Modifier
                                            .size(132.dp)
                                            .clip(AppShapes.Card),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                }
                            }
                            if (text.isNotBlank()) {
                                Spacer(Modifier.heightIn(min = 8.dp))
                            }
                        }
                        if (text.isNotBlank()) {
                            val language = LocalConfiguration.current.locales[0].language
                            val draftFormat = stringResource(R.string.skill_selected_draft, "%s")
                            val (skillMentions, messageText) = skillMessageContent(text, availableSkills, language) {
                                draftFormat.replace("%s", it)
                            }
                            if (skillMentions.isNotEmpty()) {
                                androidx.compose.foundation.layout.FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(bottom = if (messageText.isBlank()) 0.dp else 8.dp)
                                ) {
                                    skillMentions.forEach { mention ->
                                        Surface(onClick = { onOpenSkill(mention.skill) }, shape = AppShapes.Control,
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                                            Text(skillMentionLabel(mention.skill, language) { draftFormat.replace("%s", it) },
                                                style = MaterialTheme.typography.labelMedium,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                                        }
                                    }
                                }
                            }
                            if (messageText.isNotBlank()) SelectionContainer {
                                Text(text = messageText, style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Justify, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }

                else -> {
                    SimpleMessageBubble(isUser = false) {
                        SelectionContainer {
                            FlatMarkdown(content = mobileMarkdownContent(publicDialogueText(text)))
                        }
                    }
                }
            }
        }
        if (toolConfirmation != null) {
            ToolConfirmationActionBlock(toolConfirmation, onToolConfirmation)
        }
        Row(
            modifier = (if (tokenUsage != null) Modifier.fillMaxWidth() else Modifier).padding(start = 4.dp, top = 0.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (!isThinking && copyText.isNotBlank()) {
                MessageCopyButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(copyText))
                        haptics.confirm()
                        justCopied = true
                        copyRequest++
                    }
                ) {
                    AnimatedContent(
                        targetState = justCopied,
                        transitionSpec = {
                            (scaleIn(initialScale = 0.88f, animationSpec = AppMotion.controlSpring()) + fadeIn(tween(AppMotion.FeedbackMillis)))
                                .togetherWith(scaleOut(targetScale = 0.88f, animationSpec = tween(AppMotion.FeedbackMillis)) + fadeOut(tween(AppMotion.PressMillis)))
                        },
                        label = "dialogueCopyTransition"
                    ) { copied ->
                        ConversationActionGlyph(
                            if (copied) R.drawable.ic_conversation_check else R.drawable.ic_conversation_copy,
                            contentDescription = stringResource(if (copied) R.string.chat_copied else R.string.chat_copy_all),
                            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                        )
                    }
                }
            }
            // 只在回复完成后(非 streaming)显示时间，执行过程中不显示。
            if (!isUser && !isThinking && !isStreaming && messageTimeText.isNotBlank()) {
                Text(
                    text = messageTimeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isUser && tokenUsage != null) {
                TokenUsageLabel(tokenUsage)
            }
        }
    }
}

@Composable
private fun SimpleMessageBubble(
    isUser: Boolean,
    animateEntry: Boolean = false,
    onEntryStarted: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    if (isUser) {
        val enterProgress = remember { Animatable(if (animateEntry) 0f else 1f) }
        LaunchedEffect(Unit) {
            if (animateEntry) {
                onEntryStarted()
                enterProgress.animateTo(1f, tween(AppMotion.ContentMillis, easing = AppMotion.Settle))
            }
        }
        Surface(
            shape = AppShapes.Large,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.graphicsLayer {
                val scale = 0.98f + 0.02f * enterProgress.value
                scaleX = scale
                scaleY = scale
                translationY = 4.dp.toPx() * (1f - enterProgress.value)
                alpha = enterProgress.value.coerceIn(0f, 1f)
            }
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), content = content)
        }
    } else {
        Column(modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 6.dp), content = content)
    }
}

@Composable
private fun MessageCopyButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp), content = content)
    }
}
