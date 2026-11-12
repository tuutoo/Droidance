// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import com.droidance.ui.components.droidanceFieldColors
import com.droidance.ui.icons.DroidanceIcons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import com.droidance.ui.viewmodel.ProviderFormError
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.droidance.R
import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.session.ModelTransport
import com.droidance.settings.ProviderAddressLabel
import com.droidance.settings.ProviderPreset
import com.droidance.ui.theme.*
import com.droidance.ui.viewmodel.ProviderEditViewModel

/**
 * Provider 编辑/新建子页面
 * Provider connection and model editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(
    providerId: String?,  // null = 新建
    onNavigateBack: () -> Unit,
    viewModel: ProviderEditViewModel = hiltViewModel()
) {
    // 加载已有配置
    LaunchedEffect(providerId) {
        viewModel.loadProvider(providerId)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val modelCatalog by viewModel.modelCatalog.collectAsStateWithLifecycle()
    val customModels by viewModel.customModels.collectAsStateWithLifecycle()
    val isNew = providerId == null
    var showModelPicker by remember(providerId) { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val close: () -> Unit = { if (viewModel.hasUnsavedChanges()) confirmDiscard = true else onNavigateBack() }
    BackHandler(onBack = close)
    if (confirmDiscard) {
        com.droidance.ui.components.GlassAlertDialog(
            title = stringResource(R.string.form_unsaved_title),
            text = stringResource(R.string.form_unsaved_body),
            confirmLabel = stringResource(R.string.form_discard),
            dismissLabel = stringResource(R.string.form_keep_editing),
            destructive = true,
            onConfirm = { confirmDiscard = false; onNavigateBack() },
            onDismiss = { confirmDiscard = false },
            onDismissRequest = { confirmDiscard = false }
        )
    }
    if (showModelPicker) {
        ProviderModelPicker(
            selectedModels = uiState.models,
            customModels = customModels,
            catalog = modelCatalog,
            onAddCustom = viewModel::addCustomModel,
            onRefresh = viewModel::refreshModels,
            onConfirm = { models -> viewModel.setSelectedModels(models); showModelPicker = false },
            onDismiss = { showModelPicker = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isNew) stringResource(R.string.provider_edit_new) else stringResource(R.string.provider_edit_existing, uiState.name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(DroidanceIcons.Close, contentDescription = stringResource(R.string.common_close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            // 底部按钮栏：顶部一条 hairline，取消 + 保存
            Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                // Every field is required; the next missing one is named only after editing starts.
                val formMessage = when {
                    uiState.saveFailed -> R.string.provider_save_failed
                    viewModel.hasUnsavedChanges() -> uiState.validationError?.messageRes()
                    else -> null
                }
                AnimatedContent(
                    targetState = formMessage,
                    transitionSpec = {
                        fadeIn(tween(AppMotion.FeedbackMillis)) togetherWith fadeOut(tween(AppMotion.PressMillis)) using
                            SizeTransform(clip = false) { _, _ -> tween(AppMotion.ContentMillis, easing = AppMotion.Settle) }
                    },
                    label = "providerFormMessage"
                ) { message ->
                    if (message != null) {
                        Text(
                            stringResource(message),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.saveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = settingsHairline())
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Spacer(modifier = Modifier.weight(1f))

                    OutlinedButton(
                        onClick = close,
                        shape = AppShapes.Control,
                        border = BorderStroke(1.dp, settingsHairline())
                    ) {
                        Text(
                            stringResource(R.string.common_cancel),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }

                    Button(
                        onClick = {
                            if (viewModel.saveProvider()) {
                                onNavigateBack()
                            }
                        },
                        shape = AppShapes.Control,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        ),
                        enabled = uiState.validationError == null
                    ) {
                        Text(stringResource(R.string.common_save), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isNew) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = viewModel.presets.firstOrNull { it.id == uiState.presetId }?.name
                            ?: stringResource(R.string.provider_preset_custom),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.provider_preset)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = AppShapes.Control,
                        colors = droidanceFieldColors()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.provider_preset_custom)) },
                            onClick = { viewModel.selectPreset(null); expanded = false }
                        )
                        viewModel.providerPresets.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.name) },
                                onClick = { viewModel.selectPreset(preset.id); expanded = false }
                            )
                        }
                    }
                }
            }
            // ====== Provider 名称 ======
            SectionField(label = stringResource(R.string.provider_name)) {
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = { viewModel.updateName(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.provider_name_placeholder)) },
                    singleLine = true,
                    shape = AppShapes.Control,
                    colors = droidanceFieldColors()
                )
            }

            // ====== API 地址 ======
            SectionField(label = stringResource(R.string.provider_api_address)) {
                val addresses = viewModel.addressPresets
                if (addresses.isNotEmpty()) {
                    ProviderAddressMenu(
                        addresses = addresses,
                        groupName = viewModel.providerPresets.firstOrNull { it.id == uiState.presetId }?.name.orEmpty(),
                        selectedId = uiState.addressPresetId,
                        currentUrl = uiState.baseUrl,
                        onSelect = viewModel::selectAddress
                    )
                    if (uiState.addressPresetId == null) Spacer(Modifier.height(8.dp))
                }
                if (addresses.isEmpty() || uiState.addressPresetId == null) {
                    OutlinedTextField(
                        value = uiState.baseUrl,
                        isError = uiState.baseUrl.isNotBlank() && uiState.validationError == ProviderFormError.INVALID_URL,
                        supportingText = if (uiState.validationError == ProviderFormError.INVALID_URL) {
                            { Text(stringResource(R.string.provider_invalid_url)) }
                        } else null,
                        onValueChange = { viewModel.updateBaseUrl(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://api.example.com/v1") },
                        singleLine = true,
                        shape = AppShapes.Control,
                        colors = droidanceFieldColors()
                    )
                }
            }

            // ====== API 协议 ======
            SectionField(label = stringResource(R.string.provider_protocol)) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = uiState.transportKind?.let { stringResource(it.protocolLabelRes()) }.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        isError = uiState.transportKind == null,
                        placeholder = { Text(stringResource(R.string.provider_protocol_required)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = AppShapes.Control,
                        colors = droidanceFieldColors()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        listOf(ModelTransport.GENERIC_CHAT_COMPLETIONS, ModelTransport.OPENAI_RESPONSES).forEach { transport ->
                            DropdownMenuItem(
                                text = { Text(stringResource(transport.protocolLabelRes())) },
                                onClick = { viewModel.selectProtocol(transport); expanded = false }
                            )
                        }
                    }
                }
                uiState.requestUrl?.let { url ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ====== API Key ======
            SectionField(label = stringResource(R.string.provider_api_key)) {
                var passwordVisible by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = uiState.apiKey,
                    onValueChange = { viewModel.updateApiKey(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) DroidanceIcons.EyeOff
                                else DroidanceIcons.Eye,
                                contentDescription = if (passwordVisible) stringResource(R.string.common_hide) else stringResource(R.string.common_show),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    shape = AppShapes.Control,
                    colors = droidanceFieldColors()
                )
            }

            // ====== 模型 ======
            SectionField(label = stringResource(R.string.provider_model)) {
                if (uiState.models.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        uiState.models.forEach { model ->
                            ModelListItem(
                                model = model,
                                isDefault = model == uiState.defaultModel,
                                onSetDefault = { viewModel.selectDefaultModel(model) },
                                onRemove = { viewModel.removeModel(model) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
                OutlinedButton(
                    onClick = {
                        viewModel.loadModelsOnOpen()
                        showModelPicker = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.Control,
                    border = BorderStroke(1.dp, settingsHairline())
                ) {
                    Icon(DroidanceIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.provider_models_manage))
                }
            }

            SectionField(label = stringResource(R.string.provider_reasoning)) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(),
                        shape = AppShapes.Control) {
                        Text(stringResource(uiState.reasoningPreference.reasoningLabelRes()))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        (listOf<ReasoningEffort?>(null) + ReasoningEffort.entries).forEach { effort ->
                            DropdownMenuItem(text = { Text(stringResource(effort.reasoningLabelRes())) }, onClick = {
                                viewModel.updateReasoningPreference(effort)
                                expanded = false
                            })
                        }
                    }
                }
                Text(stringResource(R.string.provider_reasoning_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ====== 子组件 ======

@androidx.annotation.StringRes
private fun ModelTransport.protocolLabelRes(): Int = when (this) {
    ModelTransport.GENERIC_CHAT_COMPLETIONS -> R.string.provider_protocol_chat_completions
    ModelTransport.OPENAI_RESPONSES -> R.string.provider_protocol_responses
}

@androidx.annotation.StringRes
private fun ReasoningEffort?.reasoningLabelRes(): Int = when (this) {
    null -> R.string.provider_reasoning_agent
    ReasoningEffort.DEFAULT -> R.string.provider_reasoning_default
    ReasoningEffort.NONE -> R.string.provider_reasoning_none
    ReasoningEffort.MINIMAL -> R.string.provider_reasoning_minimal
    ReasoningEffort.LOW -> R.string.provider_reasoning_low
    ReasoningEffort.MEDIUM -> R.string.provider_reasoning_medium
    ReasoningEffort.HIGH -> R.string.provider_reasoning_high
    ReasoningEffort.XHIGH -> R.string.provider_reasoning_xhigh
    ReasoningEffort.MAX -> R.string.provider_reasoning_max
}

@Composable
private fun ModelListItem(
    model: String,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Control,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, settingsHairline())
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                DroidanceIcons.Cpu,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = model,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isDefault) {
                Icon(
                    DroidanceIcons.Check,
                    contentDescription = stringResource(R.string.provider_default_model),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            } else {
                TextButton(onClick = onSetDefault) {
                    Text(
                        stringResource(R.string.provider_set_default),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    DroidanceIcons.Close,
                    contentDescription = stringResource(R.string.provider_remove_model),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderAddressMenu(
    addresses: List<ProviderPreset>,
    groupName: String,
    selectedId: String?,
    currentUrl: String,
    onSelect: (String?) -> Unit
) {
    var expanded by remember(addresses) { mutableStateOf(false) }
    val selected = addresses.firstOrNull { it.id == selectedId }
    val fieldColor = if (LocalAppIsDark.current) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        Surface(
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            shape = AppShapes.Control,
            color = fieldColor,
            border = if (LocalAppIsDark.current) BorderStroke(1.dp, settingsHairline()) else null
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(selected?.let { addressLabel(it, groupName) } ?: stringResource(R.string.provider_address_custom),
                        style = MaterialTheme.typography.bodyMedium)
                    if (currentUrl.isNotBlank()) {
                        Text(currentUrl, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                ExposedDropdownMenuDefaults.TrailingIcon(expanded)
            }
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp)) {
            addresses.forEach { endpoint ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(addressLabel(endpoint, groupName), style = MaterialTheme.typography.bodyMedium)
                            Text(endpoint.baseUrl, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    trailingIcon = if (endpoint.id == selectedId) {
                        { Icon(DroidanceIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                    onClick = { onSelect(endpoint.id); expanded = false }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.provider_address_custom)) },
                trailingIcon = if (selectedId == null) {
                    { Icon(DroidanceIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null,
                onClick = { onSelect(null); expanded = false }
            )
        }
    }
}

@Composable
private fun addressLabel(endpoint: ProviderPreset, groupName: String): String = when (endpoint.addressLabel) {
    ProviderAddressLabel.API -> stringResource(R.string.provider_address_api, groupName)
    ProviderAddressLabel.CODING_PLAN -> stringResource(R.string.provider_address_coding_plan)
    ProviderAddressLabel.AGENT_PLAN -> stringResource(R.string.provider_address_agent_plan)
    ProviderAddressLabel.TOKEN_PLAN -> stringResource(R.string.provider_address_token_plan)
    ProviderAddressLabel.KIMI_CODE -> stringResource(R.string.provider_address_kimi_code)
}

/**
 * 表单字段容器
 */
@Composable
private fun SectionField(
    label: String,
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
            if (hint != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "($hint)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        content()
    }
}


private fun ProviderFormError.messageRes(): Int = when (this) {
    ProviderFormError.NAME_REQUIRED -> R.string.provider_name_required
    ProviderFormError.URL_REQUIRED -> R.string.provider_url_required
    ProviderFormError.INVALID_URL -> R.string.provider_invalid_url
    ProviderFormError.PROTOCOL_REQUIRED -> R.string.provider_protocol_required
    ProviderFormError.KEY_REQUIRED -> R.string.provider_key_required
    ProviderFormError.MODEL_REQUIRED -> R.string.provider_model_required
}
