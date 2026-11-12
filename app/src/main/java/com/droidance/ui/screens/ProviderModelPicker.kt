// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidance.R
import com.droidance.ui.components.droidanceFieldColors
import com.droidance.ui.components.droidanceFloatingSurfaceColor
import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.theme.AppShapes
import com.droidance.ui.viewmodel.ModelCatalogError
import com.droidance.ui.viewmodel.ProviderModelCatalogState
import com.droidance.ui.viewmodel.providerModelOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderModelPicker(
    selectedModels: List<String>,
    customModels: List<String>,
    catalog: ProviderModelCatalogState,
    onAddCustom: (String) -> List<String>,
    onRefresh: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(selectedModels) }
    var customExpanded by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    val candidates = remember(customModels, selectedModels, catalog.candidates, draft, search) {
        providerModelOptions(customModels, selectedModels, catalog.candidates, draft, search)
    }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = droidanceFloatingSurfaceColor(),
        dragHandle = null
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding().navigationBarsPadding()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.provider_models_select), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    search = ""
                    onRefresh()
                }, enabled = !catalog.loading) {
                    if (catalog.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(DroidanceIcons.Refresh, contentDescription = stringResource(R.string.provider_models_refresh))
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { customExpanded = !customExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.Control,
                    border = BorderStroke(1.dp, settingsHairline())
                ) {
                    Icon(DroidanceIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.provider_models_custom), modifier = Modifier.weight(1f).padding(start = 8.dp))
                    Icon(if (customExpanded) DroidanceIcons.ChevronUp else DroidanceIcons.ChevronDown,
                        contentDescription = null, modifier = Modifier.size(18.dp))
                }
                if (customExpanded) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customInput,
                            onValueChange = { customInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.provider_custom_model_hint)) },
                            singleLine = true,
                            shape = AppShapes.Control,
                            colors = droidanceFieldColors()
                        )
                        TextButton(onClick = {
                            val added = onAddCustom(customInput)
                            draft = (draft + added).distinct()
                            customInput = ""
                            search = ""
                            customExpanded = false
                        }, enabled = customInput.isNotBlank()) {
                            Text(stringResource(R.string.provider_models_add))
                        }
                    }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.provider_models_search)) },
                    leadingIcon = { Icon(DroidanceIcons.Search, contentDescription = null) },
                    singleLine = true,
                    shape = AppShapes.Control,
                    colors = droidanceFieldColors()
                )
                catalog.error?.let { error ->
                    Text(stringResource(error.messageRes()), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.provider_models_empty),
                    modifier = Modifier.weight(1f, fill = false).padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(candidates, key = { it }) { model ->
                        val checked = model in draft
                        Surface(
                            shape = AppShapes.Control,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, settingsHairline()),
                            modifier = Modifier.fillMaxWidth().clickable {
                                draft = if (checked) draft.filterNot { it == model } else draft + model
                            }
                        ) {
                            Row(Modifier.heightIn(min = 48.dp).padding(start = 12.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(model, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Checkbox(checked = checked, onCheckedChange = null)
                            }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = settingsHairline())
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss, shape = AppShapes.Control,
                    border = BorderStroke(1.dp, settingsHairline())) { Text(stringResource(R.string.common_cancel)) }
                Button(onClick = { onConfirm(draft) }, shape = AppShapes.Control) {
                    Text(stringResource(R.string.provider_models_done, draft.size))
                }
            }
        }
    }
}

private fun ModelCatalogError.messageRes(): Int = when (this) {
    ModelCatalogError.MISSING_CONNECTION -> R.string.provider_models_connection_required
    ModelCatalogError.UNSUPPORTED -> R.string.provider_models_unsupported
    ModelCatalogError.FETCH_FAILED -> R.string.provider_models_fetch_failed
    ModelCatalogError.EMPTY -> R.string.provider_models_none_returned
}
