// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.navigation

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import com.droidance.ui.components.DroidanceSnackbar
import kotlinx.coroutines.launch
import com.droidance.ui.components.DroidanceDropdownMenu

import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.theme.AppIconSize
import com.droidance.ui.theme.AppShapes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.droidance.R
import com.droidance.ui.screens.shareConversation
import com.droidance.ui.screens.home.ConversationActionGlyph
import com.droidance.ui.viewmodel.ConversationListItem
import com.droidance.ui.viewmodel.ConversationListViewModel
import com.droidance.ui.viewmodel.UIMessage
import com.droidance.ui.viewmodel.UpdateViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun AppDrawerContent(
    navController: NavHostController,
    currentRoute: String,
    currentConversationId: String? = null,
    visible: Boolean,
    onClose: () -> Unit,
    vm: ConversationListViewModel = hiltViewModel(),
    updateVm: UpdateViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repositoryUrl = stringResource(R.string.settings_repository_url)
    val availableRelease by updateVm.availableRelease.collectAsStateWithLifecycle()
    // A closed drawer must not replay all retained conversations during chat restoration.
    val historySnapshot by if (visible) vm.historySnapshot.collectAsStateWithLifecycle()
        else remember(vm) { mutableStateOf(vm.historySnapshot.value) }
    val conversations = historySnapshot.value.orEmpty()
    val runningConversationId by vm.runningConversationId.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val visibleConversations = conversations.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deleteFailed = stringResource(R.string.drawer_delete_failed)
    var deleting by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ConversationListItem?>(null) }
    var expandedConversationMenuId by remember { mutableStateOf<String?>(null) }

    pendingDelete?.let { target ->
        val isRunning = runningConversationId == target.id
        com.droidance.ui.components.DroidanceDialog(
            confirmEnabled = !deleting, dismissEnabled = !deleting,
            onDismissRequest = { pendingDelete = null },
            title = if (isRunning) stringResource(R.string.drawer_delete_running_title) else stringResource(R.string.drawer_delete_title),
            text = if (isRunning) stringResource(R.string.drawer_delete_running_message, target.title)
            else stringResource(R.string.drawer_delete_message, target.title),
            confirmLabel = if (isRunning) stringResource(R.string.drawer_stop_and_delete) else stringResource(R.string.common_delete),
            destructive = true,
            onConfirm = {
                val deletedCurrent = target.id == currentConversationId || currentRoute == "home/chat/${target.id}"
                deleting = true
                vm.deleteConversation(target.id) { succeeded ->
                    deleting = false
                    pendingDelete = null
                    if (succeeded) {
                        if (deletedCurrent || isRunning) {
                            navController.navigate(SubRoutes.buildNewChatRoute()) {
                                popUpTo(navController.graph.id) { inclusive = true }
                                launchSingleTop = true
                            }
                            onClose()
                        }
                    } else scope.launch { snackbar.showSnackbar(deleteFailed, withDismissAction = true) }
                }
            },
            dismissLabel = stringResource(R.string.common_cancel),
            onDismiss = { pendingDelete = null }
        )
    }

    Box(Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                ConversationActionGlyph(
                    imageVector = DroidanceIcons.Menu,
                    contentDescription = stringResource(R.string.drawer_close_menu),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                ConversationActionGlyph(
                    imageVector = DroidanceIcons.Search,
                    contentDescription = stringResource(R.string.common_search),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
                )
            }
        }

        if (searching) DrawerSearchField(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DrawerNavChip(
                icon = DroidanceIcons.Sparkles,
                label = stringResource(R.string.drawer_skills),
                selected = currentRoute == SubRoutes.SKILLS,
                onClick = { navController.navigate(SubRoutes.SKILLS); onClose() },
                modifier = Modifier.weight(1f)
            )
            DrawerNavChip(
                icon = DroidanceIcons.Checklist,
                label = stringResource(R.string.drawer_tasks),
                selected = currentRoute == SubRoutes.TASKS,
                onClick = { navController.navigate(SubRoutes.TASKS); onClose() },
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )

        Text(
            stringResource(R.string.drawer_recent_conversations),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).padding(top = 6.dp)
        )

        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(visibleConversations, key = { it.id }) { conv ->
                DrawerConversationItem(
                    conv = conv,
                    isSelected = conv.id == currentConversationId || currentRoute == "home/chat/${conv.id}",
                    isRunning = runningConversationId == conv.id,
                    isMenuExpanded = expandedConversationMenuId == conv.id,
                    onClick = {
                        if (conv.id == currentConversationId) {
                            onClose()
                        } else {
                            navController.navigate(SubRoutes.buildChatRoute(conv.id)) {
                                popUpTo(navController.graph.id) { inclusive = true }
                                launchSingleTop = true
                            }
                            onClose()
                        }
                    },
                    onMenuToggle = {
                        expandedConversationMenuId =
                            if (expandedConversationMenuId == conv.id) null else conv.id
                    },
                    onDelete = { expandedConversationMenuId = null; pendingDelete = conv },
                    onShareHtml = {
                        expandedConversationMenuId = null
                        shareConversation(context, conv.messages.map {
                            UIMessage(
                                text = it.text,
                                isUser = it.isUser,
                                timestamp = formatConversationMessageTime(it.occurredAt),
                                timestampMs = it.occurredAt
                            )
                        }, "html")
                    },
                    onShareMarkdown = {
                        expandedConversationMenuId = null
                        shareConversation(context, conv.messages.map {
                            UIMessage(
                                text = it.text,
                                isUser = it.isUser,
                                timestamp = formatConversationMessageTime(it.occurredAt),
                                timestampMs = it.occurredAt
                            )
                        }, "md")
                    }
                )
            }
            if (visibleConversations.isNotEmpty() && historySnapshot.status == com.droidance.ui.viewmodel.HistoryLoadStatus.UNAVAILABLE) {
                item(key = "history_sync_notice") {
                    Text(stringResource(R.string.history_cached_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
            if (visibleConversations.isEmpty()) {
                item {
                    Text(
                        stringResource(when {
                            historySnapshot.status == com.droidance.ui.viewmodel.HistoryLoadStatus.UNAVAILABLE -> R.string.history_unavailable
                            historySnapshot.status != com.droidance.ui.viewmodel.HistoryLoadStatus.READY -> R.string.history_loading
                            query.isNotBlank() -> R.string.drawer_search_empty
                            else -> R.string.drawer_no_conversations
                        }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "v${com.droidance.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
            )
            if (availableRelease != null) {
                IconButton(onClick = { updateVm.showDialog(); onClose() }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        DroidanceIcons.Download,
                        contentDescription = stringResource(R.string.update_available_description),
                        modifier = Modifier.size(AppIconSize.Action),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(repositoryUrl)
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_github),
                    contentDescription = "GitHub",
                    // Solid glyph: one step smaller so it carries the same visual weight as the outlined settings icon.
                    modifier = Modifier.size(AppIconSize.Action),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { navController.navigate(SubRoutes.SETTINGS); onClose() }) {
                Icon(
                    DroidanceIcons.Settings,
                    contentDescription = stringResource(R.string.drawer_settings),
                    modifier = Modifier.size(AppIconSize.Leading),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
    SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) { DroidanceSnackbar(it) }
    }
}

/** Search shares the nav chips' shape, fill and type so it reads as part of the same row family. */
@Composable
private fun DrawerSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(
        modifier = modifier.heightIn(min = 48.dp),
        shape = AppShapes.Card,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                DroidanceIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(AppIconSize.Leading),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(
                    stringResource(R.string.common_search),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                )
            }
        }
    }
}

@Composable
private fun DrawerNavChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = AppShapes.Card,
        color = if (selected) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        },
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(AppIconSize.Leading),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 1f else 0.92f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DrawerConversationItem(
    conv: ConversationListItem,
    isSelected: Boolean,
    isRunning: Boolean,
    isMenuExpanded: Boolean,
    onClick: () -> Unit,
    onMenuToggle: () -> Unit,
    onDelete: () -> Unit,
    onShareHtml: () -> Unit,
    onShareMarkdown: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .background(if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // 侧栏只保留对话摘要标题，不再显示时间。
            Text(
                text = conv.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSelected) 1f else 0.90f)
            )
        }
        if (isRunning) {
            val runningDescription = stringResource(R.string.drawer_conversation_running)
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp).semantics { contentDescription = runningDescription },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 1.5.dp,
                trackColor = Color.Transparent
            )
        }
        Box {
            IconButton(onClick = onMenuToggle, modifier = Modifier.size(34.dp)) {
                Icon(
                    DroidanceIcons.MoreHorizontal, contentDescription = stringResource(R.string.drawer_conversation_actions),
                    modifier = Modifier.size(AppIconSize.Action),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            DroidanceDropdownMenu(
                expanded = isMenuExpanded,
                onDismissRequest = onMenuToggle
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_delete)) }, onClick = onDelete,
                    leadingIcon = { Icon(DroidanceIcons.Trash, contentDescription = null, Modifier.size(AppIconSize.Action)) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_share_html)) }, onClick = onShareHtml,
                    leadingIcon = { Icon(DroidanceIcons.Code, contentDescription = null, Modifier.size(AppIconSize.Action)) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_share_markdown)) }, onClick = onShareMarkdown,
                    leadingIcon = { Icon(DroidanceIcons.FileText, contentDescription = null, Modifier.size(AppIconSize.Action)) }
                )
            }
        }
    }
}

private fun formatConversationMessageTime(occurredAt: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(occurredAt))
