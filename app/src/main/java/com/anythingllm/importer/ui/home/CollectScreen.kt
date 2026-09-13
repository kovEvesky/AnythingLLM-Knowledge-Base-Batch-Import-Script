package com.anythingllm.importer.ui.home
import com.anythingllm.importer.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.EntryType
import com.anythingllm.importer.util.formatBytes

/**
 * Tab1 收集箱(v1.2 FR-18/22/23 + v1.3 归档):
 * 待整理条目(文件+链接)按收集日分组;勾选批量操作 → 标记(文件夹+工作区)、归入资料库 或 删除;
 * 标记后条目移出本页(进入 Tab2 已标记计划区);归入资料库后进入 Tab3(移动语义)。
 */
@Composable
fun CollectScreen(
    state: CollectViewModel.UiState,
    onToggleSelect: (String) -> Unit,
    onToggleSelectAll: () -> Unit,
    onMark: (folder: String, workspace: String?) -> Unit,
    onDeleteSelected: () -> Unit,
    onArchiveToLibrary: (String?) -> Unit,
    onRefresh: () -> Unit,
) {
    var showMarkDialog by remember { mutableStateOf(false) }
    var showArchiveDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.collect_pending_count, state.pendingGroups.sumOf { it.second.size }),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggleSelectAll) {
                Text(
                    if (state.selectedIds.size == allPendingIds(state).size) {
                        stringResource(R.string.collect_deselect_all)
                    } else {
                        stringResource(R.string.collect_select_all)
                    },
                )
            }
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.common_refresh)) }
        }

        if (state.pendingGroups.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Icon(
                Icons.Outlined.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.collect_empty),
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
                    Text(stringResource(R.string.collect_mark_selected, state.selectedIds.size))
                }
                Button(onClick = { showArchiveDialog = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.collect_archive_title))
                }
                OutlinedButton(onClick = onDeleteSelected, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.collect_delete_selected))
                }
            }
        }
    }

    if (showArchiveDialog) {
        ArchiveDialog(
            folders = state.libraryAllFolders,
            onDismiss = { showArchiveDialog = false },
            onConfirm = { folderId ->
                onArchiveToLibrary(folderId)
                showArchiveDialog = false
            },
        )
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

/** UI-12:条目类型徽标(文件=InsertDriveFile/链接=Link),36dp 圆角容器色底 */
@Composable
private fun TypeBadge(type: EntryType) {
    val (icon, container, tint) = when (type) {
        EntryType.FILE -> Triple(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        EntryType.LINK -> Triple(
            Icons.Filled.Link,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(MaterialTheme.shapes.small)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

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
            // UI-12:条目类型徽标(文件/链接),一眼区分来源
            TypeBadge(entry.type)
            Spacer(Modifier.width(8.dp))
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
        title = { Text(stringResource(R.string.collect_mark_title)) },
        text = {
            Column {
                Text("目标文件夹(必选)", style = MaterialTheme.typography.labelLarge)
                if (folders.isEmpty()) {
                    Text(
                        stringResource(R.string.collect_no_snapshot),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    // UI-02:候选项可滚动,避免 10+ 项时对话框超高按钮被推出屏幕
                    LazyColumn(Modifier.heightIn(max = 180.dp)) {
                        items(folders) { f ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = folder == f, onClick = { folder = f })
                                Text(f, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.collect_workspace_optional), style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    items(workspaces) { w ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = workspace == w.slug, onClick = { workspace = w.slug })
                            Text(w.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = folder != null,
                onClick = { folder?.let { onConfirm(it, workspace) } },
            ) { Text(stringResource(R.string.collect_mark_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_cancel)) } },
    )
}

/** v1.3 归档目标选择:收集箱勾选条目 → 资料库文件夹(或根目录) */
@Composable
private fun ArchiveDialog(
    folders: List<com.anythingllm.importer.data.library.LibraryFolder>,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var target by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collect_archive_title)) },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { target = null },
                ) {
                    RadioButton(selected = target == null, onClick = { target = null })
                    Text("资料库根目录", style = MaterialTheme.typography.bodyMedium)
                }
                if (folders.isEmpty()) {
                    Text(
                        stringResource(R.string.collect_archive_no_folder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // NEW-01:与 MarkDialog 同款可滚动单选列表,目录多时不溢出
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(folders) { folder ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { target = folder.id },
                            ) {
                                RadioButton(selected = target == folder.id, onClick = { target = folder.id })
                                // C-16 修复:显示父路径,避免嵌套/同名文件夹混淆
                                Text(
                                    archiveFolderDisplayName(folder, folders),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(target) }) { Text(stringResource(R.string.collect_archive_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_cancel)) } },
    )
}

/** 归档对话框文件夹显示名:前缀父路径(C-16) */
private fun archiveFolderDisplayName(
    folder: com.anythingllm.importer.data.library.LibraryFolder,
    all: List<com.anythingllm.importer.data.library.LibraryFolder>,
): String {
    val parents = mutableListOf<String>()
    var cur = folder.parentId
    while (cur != null) {
        val f = all.firstOrNull { it.id == cur } ?: break
        parents.add(0, f.name)
        cur = f.parentId
    }
    return (parents + folder.name).joinToString(" / ")
}
