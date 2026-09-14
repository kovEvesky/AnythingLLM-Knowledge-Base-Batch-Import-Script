@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.EntryType
import com.anythingllm.importer.util.formatBytes
import kotlinx.coroutines.launch

/**
 * Tab1 收集箱(v1.7 收件箱范式):
 * - 顶部主按钮"全部入默认箱";
 * - 条目:左滑=入默认箱,右滑=再用上次夹,单击=底部抽屉选文件夹;
 * - 勾选批量操作(标记/归档/删除)保留为进阶能力。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectScreen(
    state: CollectViewModel.UiState,
    onToggleSelect: (String) -> Unit,
    onToggleSelectAll: () -> Unit,
    onMark: (folder: String, workspace: String?) -> Unit,
    onDeleteSelected: () -> Unit,
    onArchiveToLibrary: (String?) -> Unit,
    onRefresh: () -> Unit,
    // v1.7 单条零决策入箱
    onMarkOneToDefault: (String) -> Boolean,
    onMarkOneToLast: (String) -> Boolean,
    onMarkOne: (String, String, String?) -> Boolean,
    onMarkAllToDefault: () -> Int,
    onOpenSettings: () -> Unit,
) {
    var showMarkDialog by remember { mutableStateOf(false) }
    var showArchiveDialog by remember { mutableStateOf(false) }
    // v1.7 单条整理抽屉:记录当前正在整理的条目 id(null=关闭)
    var sheetEntryId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val pendingCount = state.pendingGroups.sumOf { it.second.size }
    val defaultReady = state.defaultFolderName.isNotBlank()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).padding(16.dp)) {
            // ===== v1.7 顶部主操作区 =====
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.collect_pending_count, pendingCount),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onToggleSelectAll) {
                    Text(
                        if (state.selectedIds.size == allPendingIds(state).size && allPendingIds(state).isNotEmpty()) {
                            stringResource(R.string.collect_deselect_all)
                        } else {
                            stringResource(R.string.collect_select_all)
                        },
                    )
                }
            }

            // 全部入默认箱 / 引导设置
            if (defaultReady) {
                Button(
                    onClick = {
                        val n = onMarkAllToDefault()
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.collect_archived_toast, n),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.collect_quick_default_all, pendingCount))
                }
            } else {
                TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.collect_default_setup))
                }
            }
            Text(
                stringResource(R.string.collect_swipe_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            if (pendingCount == 0) {
                Spacer(Modifier.height(24.dp))
                Icon(
                    Icons.Outlined.Inbox,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.collect_empty_v17),
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
                        Box(Modifier.animateItemPlacement()) {
                            SwipeableEntryRow(
                                entry = entry,
                                selected = entry.id in state.selectedIds,
                                onToggle = { onToggleSelect(entry.id) },
                                onClick = { sheetEntryId = entry.id },
                                onSwipeDefault = {
                                    if (!onMarkOneToDefault(entry.id)) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.collect_default_setup),
                                            )
                                        }
                                    }
                                },
                                onSwipeLast = {
                                    if (!onMarkOneToLast(entry.id)) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.collect_default_setup),
                                            )
                                        }
                                    }
                                },
                            )
                        }
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

    // v1.7 单条整理底部抽屉
    sheetEntryId?.let { id ->
        val entry = state.pendingGroups.flatMap { it.second }.firstOrNull { it.id == id }
        if (entry != null) {
            MarkEntrySheet(
                state = state,
                initialFolder = state.lastMarkFolder.ifBlank { state.defaultFolderName },
                onDismiss = { sheetEntryId = null },
                onConfirm = { folder, ws ->
                    onMarkOne(id, folder, ws)
                    // 连续分拣:整理完自动跳到下一条待整理
                    val all = state.pendingGroups.flatMap { it.second }
                    val idx = all.indexOfFirst { it.id == id }
                    val next = all.getOrNull(idx + 1)
                    sheetEntryId = next?.id
                },
            )
        } else {
            sheetEntryId = null
        }
    }
}

/** v1.7 左右滑条目:左滑(EndToStart)入默认箱,右滑(StartToEnd)再用上次夹 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableEntryRow(
    entry: CollectEntry,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onSwipeDefault: () -> Unit,
    onSwipeLast: () -> Unit,
) {
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                SwipeToDismissBoxValue.StartToEnd -> { onSwipeLast(); false }
                SwipeToDismissBoxValue.EndToStart -> { onSwipeDefault(); false }
                else -> false
            }
        },
    )
    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            val color: Color
            val label: String
            val bg = when (swipeState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    color = MaterialTheme.colorScheme.secondaryContainer
                    label = "上次夹"
                    Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.CenterStart) {
                        Text(label, Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    color = MaterialTheme.colorScheme.primaryContainer
                    label = "入默认箱"
                    Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.CenterEnd) {
                        Text(label, Modifier.padding(end = 20.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                SwipeToDismissBoxValue.Settled -> Color.Transparent
            }
            bg
        },
    ) {
        EntryRow(
            entry = entry,
            selected = selected,
            onToggle = onToggle,
            onClick = onClick,
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
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onClick() }) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
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

/** v1.7 单条整理底部抽屉:横向文件夹胶囊 + 工作区 + 入箱并看下一条 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkEntrySheet(
    state: CollectViewModel.UiState,
    initialFolder: String,
    onDismiss: () -> Unit,
    onConfirm: (folder: String, workspace: String?) -> Unit,
) {
    val folders = state.snapshot.folders.map { it.name }
    val workspaces = state.snapshot.workspaces
    var folder by remember { mutableStateOf(initialFolder.ifBlank { folders.firstOrNull() ?: "" }) }
    var workspace by remember { mutableStateOf(state.defaultWorkspace) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth()) {
            Text(stringResource(R.string.collect_sheet_title), style = MaterialTheme.typography.titleMedium)
            if (folders.isEmpty()) {
                Text(
                    stringResource(R.string.collect_sheet_no_folder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                Text(stringResource(R.string.collect_sheet_recent), style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    folders.take(6).forEach { f ->
                        val selected = f == folder
                        Box(
                            Modifier
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .clickable { folder = f }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                f,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.collect_workspace_optional), style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(
                            if (workspace.isBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable { workspace = "" }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("不嵌入", color = if (workspace.isBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                workspaces.take(5).forEach { w ->
                    val selected = w.slug == workspace
                    Box(
                        Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .clickable { workspace = w.slug }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            w.name,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onConfirm(folder, workspace.ifBlank { null }) },
                enabled = folder.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.collect_sheet_go_next)) }
            Spacer(Modifier.height(16.dp))
        }
    }
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
    onConfirm: (folderId: String?) -> Unit,
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
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(folders) { folder ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { target = folder.id },
                            ) {
                                RadioButton(selected = target == folder.id, onClick = { target = folder.id })
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
