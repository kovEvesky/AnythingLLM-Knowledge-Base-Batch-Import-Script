package com.anythingllm.importer.ui.import
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
                    title = { Text("导入目标") },
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
                    Text("正在加载文件夹与工作区…", style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                if (state.loadError != null) {
                    Text("加载失败: ${state.loadError}", color = MaterialTheme.colorScheme.error)
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
                        label = { Text("统一模式") },
                    )
                    FilterChip(
                        selected = state.mode == ImportMode.PER_ITEM,
                        onClick = { viewModel.setMode(ImportMode.PER_ITEM) },
                        label = { Text("逐项模式") },
                    )
                }
                Text(
                    if (state.mode == ImportMode.UNIFIED) {
                        "全部 ${passedFiles.size} 个文件 → 同一文件夹 + 同一工作区"
                    } else {
                        "每个文件单独选择文件夹与工作区"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.mode == ImportMode.UNIFIED) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DropdownField(
                            label = "文档文件夹",
                            options = state.folders,
                            selected = state.unifiedFolder,
                            onSelect = viewModel::setUnifiedFolder,
                            onCreateNew = viewModel::showCreateFolderDialog,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        DropdownField(
                            label = "目标工作区",
                            options = state.workspaces.map { it.slug },
                            optionLabel = { slug -> viewModel.workspaceNameOf(slug) },
                            selected = state.unifiedWorkspace,
                            onSelect = viewModel::setUnifiedWorkspace,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
                    enabled = !state.running && !state.starting && state.unifiedWorkspace.isNotBlank(),
                ) {
                    Text("开始导入 ${passedFiles.size} 个文件")
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
    val sel = state.perItem[pf.file.uriString] ?: (state.unifiedFolder to state.unifiedWorkspace)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(pf.file.displayName, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField(
                    label = "文件夹",
                    options = state.folders,
                    selected = sel.first,
                    onSelect = { viewModel.setPerItemFolder(pf.file.uriString, it) },
                    modifier = Modifier.weight(1f),
                )
            }
            DropdownField(
                label = "工作区",
                options = state.workspaces.map { it.slug },
                optionLabel = { slug -> viewModel.workspaceNameOf(slug) },
                selected = sel.second,
                onSelect = { viewModel.setPerItemWorkspace(pf.file.uriString, it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    optionLabel: (String) -> String = { it },
    onCreateNew: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(optionLabel(opt)) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    },
                )
            }
            if (onCreateNew != null) {
                DropdownMenuItem(
                    text = { Text("＋ 新建文件夹…", color = MaterialTheme.colorScheme.primary) },
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
        title = { Text("新建文档文件夹") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.createFolderName,
                    onValueChange = viewModel::onCreateFolderNameChange,
                    label = { Text("文件夹名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.createFolderError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::createFolder) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissCreateFolder) { Text("取消") }
        },
    )
}
