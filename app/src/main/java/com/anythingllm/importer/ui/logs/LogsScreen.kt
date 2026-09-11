package com.anythingllm.importer.ui.logs

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
                        TextButton(onClick = {
                            if (state.selectedName != null) viewModel.clearSelection() else onBack()
                        }) { Text("返回") }
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
                        ) { Text("导出全部") }
                        OutlinedButton(
                            onClick = viewModel::cleanupOld,
                            modifier = Modifier.weight(1f),
                        ) { Text("清理 30 天前") }
                        OutlinedButton(
                            onClick = { confirmDeleteAll = true },
                            modifier = Modifier.weight(1f),
                            enabled = state.files.isNotEmpty(),
                        ) { Text("清空日志") }
                    }
                    if (state.files.isEmpty()) {
                        Text(
                            "暂无日志文件(导入完成后自动生成 import-YYYYMMDD.jsonl)",
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
            title = { Text("清空全部日志") },
            text = { Text("将删除所有 import-*.jsonl 日志文件,不可恢复。确定继续?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAll()
                    confirmDeleteAll = false
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("取消") }
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
                        "${info.date} · ${info.lineCount} 行 · ${formatBytes(info.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpen) { Text("查看") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onExport) { Text("导出") }
                TextButton(onClick = onDelete) { Text("删除") }
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
                "动作 ${entry.action} · 目标 ${entry.folder}/${entry.workspace} · 存储名 ${entry.storageName}" +
                    (if (entry.retries > 0) " · 重试 ${entry.retries} 次" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.error?.let {
                Text("错误: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(entry.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
