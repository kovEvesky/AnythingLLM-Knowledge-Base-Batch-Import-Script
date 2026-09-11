package com.anythingllm.importer.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.EntryType
import com.anythingllm.importer.util.formatBytes

/**
 * Tab1 收集箱(v1.2 FR-18/22/23):
 * 待整理条目(文件+链接)按收集日分组;勾选批量操作 → 标记(文件夹+工作区)或删除;
 * 标记后条目移出本页(进入 Tab2 已标记计划区)。
 */
@Composable
fun CollectScreen(
    state: CollectViewModel.UiState,
    onToggleSelect: (String) -> Unit,
    onToggleSelectAll: () -> Unit,
    onMark: (folder: String, workspace: String?) -> Unit,
    onDeleteSelected: () -> Unit,
    onRefresh: () -> Unit,
) {
    var showMarkDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "待整理 ${state.pendingGroups.sumOf { it.second.size }} 条",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggleSelectAll) { Text(if (state.selectedIds.size == allPendingIds(state).size) "取消全选" else "全选") }
            TextButton(onClick = onRefresh) { Text("刷新") }
        }

        if (state.pendingGroups.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "收集箱为空:通过系统分享(文件/链接)或上方导入入口收集,回家后批量整理。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(Modifier.weight(1f)) {
            state.pendingGroups.forEach { (day, entries) ->
                item(key = "day_$day") {
                    Text(
                        day,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(entries, key = { it.id }) { entry ->
                    EntryRow(
                        entry = entry,
                        selected = entry.id in state.selectedIds,
                        onToggle = { onToggleSelect(entry.id) },
                    )
                }
            }
        }

        if (state.selectedIds.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showMarkDialog = true }, modifier = Modifier.weight(1f)) {
                    Text("标记所选(${state.selectedIds.size})")
                }
                OutlinedButton(onClick = onDeleteSelected, modifier = Modifier.weight(1f)) {
                    Text("删除所选")
                }
            }
        }
    }

    if (showMarkDialog) {
        MarkDialog(
            state = state,
            onDismiss = { showMarkDialog = false },
            onConfirm = { folder, workspace ->
                onMark(folder, workspace)
                showMarkDialog = false
            },
        )
    }
}

private fun allPendingIds(state: CollectViewModel.UiState): Set<String> =
    state.pendingGroups.flatMap { it.second }.map { it.id }.toSet()

@Composable
private fun EntryRow(
    entry: CollectEntry,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(
                    entry.displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                )
                Text(
                    detailLine(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun detailLine(entry: CollectEntry): String {
    val kind = when (entry.type) {
        EntryType.FILE -> "文件"
        EntryType.LINK -> "链接"
    }
    val meta = when (entry.type) {
        EntryType.FILE -> formatBytes(entry.sizeBytes)
        EntryType.LINK -> entry.url.orEmpty()
    }
    val source = when (entry.source) {
        com.anythingllm.importer.data.collect.EntrySource.SAF -> "SAF"
        com.anythingllm.importer.data.collect.EntrySource.SHARE_FILE -> "分享"
        com.anythingllm.importer.data.collect.EntrySource.SHARE_LINK -> "分享链接"
    }
    return "$kind · $source · $meta"
}

@Composable
private fun MarkDialog(
    state: CollectViewModel.UiState,
    onDismiss: () -> Unit,
    onConfirm: (folder: String, workspace: String?) -> Unit,
) {
    var folder by remember { mutableStateOf<String?>(null) }
    var workspace by remember { mutableStateOf<String?>(null) }
    val folders = state.snapshot.folders.map { it.name }
    val workspaces = state.snapshot.workspaces

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标记目标") },
        text = {
            Column {
                Text("目标文件夹(必选)", style = MaterialTheme.typography.labelLarge)
                if (folders.isEmpty()) {
                    Text(
                        "暂无快照:请先在知识库页连接服务器刷新结构",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    folders.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = folder == f, onClick = { folder = f })
                            Text(f, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("嵌入工作区(可选,不选=同步模式)", style = MaterialTheme.typography.labelLarge)
                workspaces.forEach { w ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = workspace == w.slug, onClick = { workspace = w.slug })
                        Text(w.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = folder != null,
                onClick = { folder?.let { onConfirm(it, workspace) } },
            ) { Text("标记") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
