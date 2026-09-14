package com.anythingllm.importer.ui.import
import com.anythingllm.importer.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anythingllm.importer.domain.import.ImportMode
import com.anythingllm.importer.domain.validate.PassedFile
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetScreen(
    passedFiles: List<PassedFile>,
    viewModel: ImportSessionViewModel,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.init() }
    Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.target_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.loaded) {
                    Spacer(Modifier.height(48.dp))
                    CircularProgressIndicator(Modifier.padding(horizontal = 48.dp))
                    Text("正在加载收藏夹…", style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                if (state.loadError != null) {
                    Text(stringResource(R.string.target_load_error, state.loadError.toString()), color = MaterialTheme.colorScheme.error)
                    return@Column
                }
                if (passedFiles.isEmpty()) {
                    Text("没有待导入的文件,请返回重新选择", style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.mode == ImportMode.UNIFIED,
                        onClick = { viewModel.setMode(ImportMode.UNIFIED) },
                        label = { Text(stringResource(R.string.target_mode_unified)) },
                    )
                    FilterChip(
                        selected = state.mode == ImportMode.PER_ITEM,
                        onClick = { viewModel.setMode(ImportMode.PER_ITEM) },
                        label = { Text(stringResource(R.string.target_mode_itemized)) },
                    )
                }
                Text(
                    stringResource(R.string.target_folder_v19_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.mode == ImportMode.UNIFIED) {
                    // v1.9 统一目标:收藏夹(同步时自动 ensure 服务器文件夹+工作区)
                    FolderDropdownField(
                        label = stringResource(R.string.target_folder_v19),
                        folders = state.favoriteFolders,
                        selectedId = state.unifiedFolderId,
                        onSelect = viewModel::setUnifiedFolderId,
                        onCreateNew = viewModel::showCreateFolderDialog,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(passedFiles, key = { it.file.uriString }) { pf ->
                            PerItemCard(
                                pf = pf,
                                state = state,
                                viewModel = viewModel,
                            )
                        }
                    }
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = {
                        viewModel.startImport()
                        onStart()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.running && !state.starting && state.unifiedFolderId.isNotBlank(),
                ) {
                    Text(stringResource(R.string.target_start_import, passedFiles.size))
                }
            }
        }
    if (state.showCreateFolder) {
        CreateFolderDialog(viewModel)
    }
}
@Composable
private fun PerItemCard(
    pf: PassedFile,
    state: ImportSessionViewModel.UiState,
    viewModel: ImportSessionViewModel,
) {
    val selId = state.perItem[pf.file.uriString] ?: state.unifiedFolderId
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(pf.file.displayName, style = MaterialTheme.typography.bodyLarge)
            FolderDropdownField(
                label = stringResource(R.string.target_folder_v19),
                folders = state.favoriteFolders,
                selectedId = selId,
                onSelect = { viewModel.setPerItemFolderId(pf.file.uriString, it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 收藏夹下拉(色点 + 名称;支持新建) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderDropdownField(
    label: String,
    folders: List<com.anythingllm.importer.data.favorite.FavoriteFolder>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCreateNew: (() -> Unit)? = null,
) {
    val selected = folders.firstOrNull { it.id == selectedId }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: stringResource(R.string.target_folder_none),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            folders.forEach { f ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(androidx.compose.ui.graphics.Color(f.color)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(f.name)
                        }
                    },
                    onClick = {
                        onSelect(f.id)
                        expanded = false
                    },
                )
            }
            if (onCreateNew != null) {
                DropdownMenuItem(
                    text = { Text("＋ 新建收藏夹…", color = MaterialTheme.colorScheme.primary) },
                    onClick = {
                        expanded = false
                        onCreateNew()
                    },
                )
            }
        }
    }
}
@Composable
private fun CreateFolderDialog(viewModel: ImportSessionViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = viewModel::dismissCreateFolder,
        title = { Text(stringResource(R.string.target_new_folder_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.createFolderName,
                    onValueChange = viewModel::onCreateFolderNameChange,
                    label = { Text(stringResource(R.string.target_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.createFolderError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::createFolder) { Text(stringResource(R.string.target_create)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissCreateFolder) { Text(stringResource(R.string.import_cancel)) }
        },
    )
}
