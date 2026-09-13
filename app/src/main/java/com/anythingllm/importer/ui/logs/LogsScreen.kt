package com.anythingllm.importer.ui.logs
import com.anythingllm.importer.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anythingllm.importer.data.log.ImportLogEntry
import com.anythingllm.importer.data.log.ImportLogRepository
import com.anythingllm.importer.util.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit,
    viewModel: LogsViewModel = viewModel(factory = LogsViewModel.factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDeleteAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/jsonl"),
    ) { uri -> viewModel.consumeExport(uri) }

    // SAF 导出:待导出请求出现时拉起系统"创建文件";用户取消时回调 null 由 consumeExport 清除
    LaunchedEffect(state.pendingExport) {
        state.pendingExport?.let { req ->
            exportLauncher.launch(req.suggestedName)
        }
    }

    Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (state.selectedName == null) "导入日志" else state.selectedName!!) },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (state.selectedName != null) viewModel.clearSelection() else onBack()
                        }) {
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
                state.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }

                if (state.selectedName == null) {
                    // 文件列表视图
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::requestExportAll,
                            modifier = Modifier.weight(1f),
                            enabled = state.files.isNotEmpty(),
                        ) { Text(stringResource(R.string.logs_export_all)) }
                        OutlinedButton(
                            onClick = viewModel::cleanupOld,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.logs_clean_30)) }
                        OutlinedButton(
                            onClick = { confirmDeleteAll = true },
                            modifier = Modifier.weight(1f),
                            enabled = state.files.isNotEmpty(),
                        ) { Text(stringResource(R.string.logs_clear)) }
                    }
                    if (state.files.isEmpty()) {
                        Text(
                            stringResource(R.string.logs_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.files, key = { it.name }) { f ->
                                LogFileCard(
                                    info = f,
                                    onOpen = { viewModel.select(f.name) },
                                    onExport = { viewModel.requestExport(f.name) },
                                    onDelete = { viewModel.delete(f.name) },
                                )
                            }
                        }
                    }
                } else {
                    // 详情视图:条目倒序列表
                    if (state.entries.isEmpty()) {
                        Text("此文件无可解析日志行", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.entries, key = { "${it.time}-${it.file}-${it.result}" }) { e ->
                                EntryCard(e)
                            }
                        }
                    }
                }
            }
        }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.logs_clear_title)) },
            text = { Text(stringResource(R.string.logs_clear_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAll()
                    confirmDeleteAll = false
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.import_cancel)) }
            },
        )
    }
}

@Composable
private fun LogFileCard(
    info: ImportLogRepository.LogFileInfo,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(info.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(
                            R.string.logs_line_summary,
                            info.date,
                            info.lineCount,
                            formatBytes(info.sizeBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpen) { Text(stringResource(R.string.logs_view)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onExport) { Text(stringResource(R.string.logs_export)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.common_delete)) }
            }
        }
    }
}

@Composable
private fun EntryCard(entry: ImportLogEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.file,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    entry.result,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (entry.result) {
                        "success" -> MaterialTheme.colorScheme.primary
                        "failed" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                stringResource(
                    R.string.logs_line_action,
                    entry.action,
                    entry.folder,
                    entry.workspace,
                    entry.storageName,
                ) + (if (entry.retries > 0) stringResource(R.string.logs_line_retries, entry.retries) else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.error?.let {
                Text(stringResource(R.string.logs_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(entry.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
