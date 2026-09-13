package com.anythingllm.importer.ui.home
import com.anythingllm.importer.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import com.anythingllm.importer.data.library.LibraryEntry
import com.anythingllm.importer.data.library.LibraryEntryType
import com.anythingllm.importer.data.library.LibraryFolder
import com.anythingllm.importer.domain.ftp.FtpItemStatus
import com.anythingllm.importer.util.formatBytes

/**
 * Tab3 资料库(v1.3 FR-30/31/33):
 * - 手机端建立资料库文件夹树(文件目录管理:新建/重命名/删除/移动);
 * - 条目(文件/链接)归档后可查看/移动到其他文件夹/删除;
 * - 顶部「同步到 PC」:通过 FTP 把资料库镜像到 PC 目录(未安装 AnythingLLM 场景)。
 */
@Composable
fun LibraryScreen(
    state: CollectViewModel.UiState,
    onEnterFolder: (String) -> Unit,
    onGoUp: () -> Unit,
    onGoRoot: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onMoveEntry: (String, String?) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onSyncFtp: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<LibraryFolder?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<LibraryFolder?>(null) }
    var moveTarget by remember { mutableStateOf<LibraryEntry?>(null) }
    var deleteEntryTarget by remember { mutableStateOf<LibraryEntry?>(null) }
    var menuTarget by remember { mutableStateOf<LibraryEntry?>(null) }

    // C-17 修复:子文件夹页系统返回键回上级而非退出 App
    BackHandler(enabled = state.libraryPathChain.isNotEmpty()) { onGoUp() }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // ===== 顶部工具条 =====
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            if (state.libraryPathChain.isNotEmpty()) {
                IconButton(onClick = onGoUp) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上级")
                }
            }
            Text(
                state.libraryPathChain.joinToString(" / ").ifEmpty { "资料库" },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "FTP 设置")
            }
        }

        // ===== 同步入口 =====
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onSyncFtp,
                modifier = Modifier.weight(1f),
                enabled = !state.ftpSync.running,
            ) {
                Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (state.ftpSync.running) "同步中…" else "同步到 PC")
            }
            OutlinedButton(onClick = { showNewFolder = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.library_new_folder))
            }
        }
        if (!state.ftp.isConfigured) {
            Text(
                stringResource(R.string.library_ftp_not_configured),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ===== FTP 同步进度 =====
        if (state.ftpSync.items.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.library_ftp_progress, state.ftpSync.doneCount, state.ftpSync.totalCount) +
                            (if (state.ftpSync.isTerminal) " · 完成" else ""),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.library_ftp_summary, state.ftpSync.successCount, state.ftpSync.failedCount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.ftpSync.items.take(8).forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FtpStatusIcon(item.status)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                item.entry.title + (item.message?.let { " — $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                            )
                        }
                    }
                    if (state.ftpSync.totalCount > 8) {
                        Text(stringResource(R.string.library_ftp_rest, state.ftpSync.totalCount - 8), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (state.ftpSyncDone && state.ftpSync.items.isEmpty() && !state.ftpSync.running) {
            Text(
                stringResource(R.string.library_ftp_no_pending),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ===== 内容 =====
        if (state.librarySubFolders.isEmpty() && state.libraryEntries.isEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text(
                stringResource(R.string.library_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(Modifier.weight(1f)) {
            items(state.librarySubFolders, key = { "f_${it.id}" }) { folder ->
                FolderCard(
                    folder = folder,
                    // C-18 修复:子项 = 子文件夹数 + 该文件夹内条目数(用户归档后能看见文件/链接计入)
                    entryCount = state.libraryAllFolders.count { it.parentId == folder.id } +
                        state.libraryAllEntries.count { it.folderId == folder.id },
                    onOpen = { onEnterFolder(folder.id) },
                    onRename = { renameTarget = folder },
                    onDelete = { deleteFolderTarget = folder },
                )
            }
            items(state.libraryEntries, key = { "e_${it.id}" }) { entry ->
                EntryCard(
                    entry = entry,
                    onClick = { menuTarget = entry },
                    onMove = { moveTarget = entry },
                    onDelete = { deleteEntryTarget = entry },
                )
            }
        }
    }

    // ===== 对话框 =====
    if (showNewFolder) {
        NameDialog(
            title = "新建文件夹",
            initial = "",
            onDismiss = { showNewFolder = false },
            onConfirm = { name ->
                onCreateFolder(name)
                showNewFolder = false
            },
        )
    }
    renameTarget?.let { folder ->
        NameDialog(
            title = "重命名文件夹",
            initial = folder.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                onRenameFolder(folder.id, name)
                renameTarget = null
            },
        )
    }
    deleteFolderTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleteFolderTarget = null },
            title = { Text(stringResource(R.string.library_delete_folder_title)) },
            text = { Text(stringResource(R.string.library_delete_folder_msg, folder.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteFolder(folder.id)
                    deleteFolderTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFolderTarget = null }) { Text(stringResource(R.string.import_cancel)) } },
        )
    }
    moveTarget?.let { entry ->
        MoveDialog(
            allFolders = state.libraryAllFolders,
            onDismiss = { moveTarget = null },
            onConfirm = { folderId ->
                onMoveEntry(entry.id, folderId)
                moveTarget = null
            },
        )
    }
    deleteEntryTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntryTarget = null },
            title = { Text(stringResource(R.string.library_delete_entry_title)) },
            text = { Text(stringResource(R.string.library_delete_entry_msg, entry.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteEntry(entry.id)
                    deleteEntryTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteEntryTarget = null }) { Text(stringResource(R.string.import_cancel)) } },
        )
    }
}

/** UI-11: FTP 状态符号图标化(带语义 contentDescription) */
@Composable
private fun FtpStatusIcon(status: FtpItemStatus) {
    val (icon, tint, label) = when (status) {
        FtpItemStatus.PENDING -> Triple(
            Icons.Outlined.Schedule, MaterialTheme.colorScheme.onSurfaceVariant, "待同步",
        )
        FtpItemStatus.RUNNING -> Triple(
            Icons.Outlined.Sync, MaterialTheme.colorScheme.primary, "同步中",
        )
        FtpItemStatus.SUCCESS -> Triple(
            Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary, "成功",
        )
        FtpItemStatus.FAILED -> Triple(
            Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error, "失败",
        )
        FtpItemStatus.SKIPPED -> Triple(
            Icons.AutoMirrored.Filled.ArrowForward, MaterialTheme.colorScheme.onSurfaceVariant, "跳过",
        )
    }
    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.width(16.dp).height(16.dp))
}

@Composable
private fun FolderCard(
    folder: LibraryFolder,
    entryCount: Int,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
                Text(folder.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "文件夹 · $entryCount 个子项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "文件夹操作")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.library_menu_open)) }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text(stringResource(R.string.library_menu_rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: LibraryEntry,
    onClick: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.type == LibraryEntryType.LINK) {
                Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            } else {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).clickable(onClick = onClick)) {
                Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                Text(
                    entryMeta(entry) + " · " + if (entry.isSynced) "已同步" else "未同步",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "条目操作")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.library_move_title)) }, onClick = { menu = false; onMove() })
                DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

private fun entryMeta(entry: LibraryEntry): String = when (entry.type) {
    LibraryEntryType.FILE -> formatBytes(entry.sizeBytes)
    LibraryEntryType.LINK -> entry.url.orEmpty()
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.library_name_label)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_cancel)) } },
    )
}

@Composable
private fun MoveDialog(
    allFolders: List<LibraryFolder>,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var target by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_move_title)) },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { target = null },
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = target == null,
                        onClick = { target = null },
                    )
                    Text("资料库根目录", style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                // NEW-01:目录多时可滚动,避免对话框超高
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(allFolders) { folder ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { target = folder.id },
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = target == folder.id,
                                onClick = { target = folder.id },
                            )
                            // C-16 修复:显示父路径,避免嵌套/同名文件夹混淆
                            Text(
                                folderDisplayName(folder, allFolders),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(target) }) { Text(stringResource(R.string.library_move_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_cancel)) } },
    )
}

/** 文件夹显示名:前缀父路径(C-16) */
private fun folderDisplayName(folder: LibraryFolder, all: List<LibraryFolder>): String {
    val parents = mutableListOf<String>()
    var cur = folder.parentId
    while (cur != null) {
        val f = all.firstOrNull { it.id == cur } ?: break
        parents.add(0, f.name)
        cur = f.parentId
    }
    return (parents + folder.name).joinToString(" / ")
}
