@file:OptIn(ExperimentalFoundationApi::class)

package com.anythingllm.importer.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anythingllm.importer.R
import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.favorite.FavoriteFolder
import com.anythingllm.importer.data.favorite.FolderPalette
import java.time.OffsetDateTime
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Tab2 To · 收藏夹管理(V1.9 最终设计 §4.6–4.8):
 * 列表页(搜索/新建/长按拖动排序/左滑删除迁移/右滑重命名)→ 收藏夹详情(移出/彻底删除)→ 回收站详情(恢复/清空)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToScreen(
    viewModel: ToViewModel = viewModel(factory = ToViewModel.factory()),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    when {
        state.openFolder != null -> FolderDetailView(
            state = state,
            onBack = viewModel::closeDetail,
            onMove = { entryId, folderId ->
                viewModel.moveEntry(entryId, folderId)
                toast(context.getString(R.string.to_entry_moved, state.userFolders.firstOrNull { it.id == folderId }?.name ?: ""))
            },
            onDeleteEntry = { id -> viewModel.deleteEntryPermanently(id); toast(context.getString(R.string.to_entry_deleted)) },
            toast = ::toast,
            modifier = modifier,
        )
        state.openTrash -> TrashDetailView(
            state = state,
            onBack = viewModel::closeTrash,
            onRestore = { id -> viewModel.restoreEntry(id); toast(context.getString(R.string.to_entry_restored)) },
            onDelete = { id -> viewModel.deleteEntryPermanently(id); toast(context.getString(R.string.to_entry_deleted)) },
            onClear = { viewModel.clearTrash(); toast(context.getString(R.string.to_trash_cleared)) },
            modifier = modifier,
        )
        else -> FolderListView(
            state = state,
            onCreate = { name, color ->
                if (viewModel.createFolder(name, color)) toast(context.getString(R.string.to_folder_created))
            },
            onRename = { id, name, color ->
                if (viewModel.renameFolder(id, name, color)) toast(context.getString(R.string.to_folder_renamed))
            },
            onDelete = { id, targetId ->
                viewModel.deleteFolder(id, targetId)
                toast(context.getString(R.string.to_folder_deleted))
            },
            onReorder = viewModel::reorder,
            onSearch = viewModel::setSearch,
            onOpenFolder = viewModel::openFolderDetail,
            onOpenTrash = viewModel::openTrash,
            snackbarHost = { SnackbarHost(snackbar) },
            modifier = modifier,
        )
    }
}

// ===== 列表页 =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderListView(
    state: ToViewModel.UiState,
    onCreate: (String, Long) -> Unit,
    onRename: (String, String, Long) -> Unit,
    onDelete: (String, String?) -> Unit,
    onReorder: (List<String>) -> Unit,
    onSearch: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenTrash: () -> Unit,
    snackbarHost: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showNew by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FavoriteFolder?>(null) }
    var deleteTarget by remember { mutableStateOf<FavoriteFolder?>(null) }
    var deleteChoice by remember { mutableStateOf<String?>(null) }

    // 拖动排序状态(v1.91 重写:实时预览交换,结束/取消均提交,修复"从上往下拖不生效")
    val listState = rememberLazyListState()
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var previewOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    val density = LocalDensity.current
    val itemSpanPx = with(density) { (56.dp).toPx() }

    val query = state.searchQuery.trim()
    val visible = state.userFolders.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
    val order = visible.map { it.id }
    val trash = state.folders.firstOrNull { it.isTrash }
    // 拖拽闭包需要读取最新顺序(pointerInput 闭包捕获首次组合,普通 val 会过期)
    var orderRef by remember { mutableStateOf(order) }
    LaunchedEffect(order) { orderRef = order }
    // 渲染顺序 = 预览顺序(拖拽中实时变化);未拖拽时等于原始顺序
    val displayOrder = if (draggingId != null && previewOrder.isNotEmpty()) previewOrder else orderRef
    val displayFolders = displayOrder.mapNotNull { id -> visible.firstOrNull { it.id == id } }

    // 提交预览排序(与原始顺序不同才落库)
    fun commitPreview() {
        if (previewOrder.isNotEmpty() && previewOrder != orderRef) {
            onReorder(previewOrder.toList())
        }
        previewOrder = emptyList()
    }

    Scaffold(snackbarHost = snackbarHost, modifier = modifier) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            // 顶部:搜索 + 新建
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearch,
                    placeholder = { Text(stringResource(R.string.to_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { showNew = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                }
            }
            Text(
                stringResource(R.string.to_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )

            if (visible.isEmpty() && trash == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.to_detail_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    itemsIndexed(displayFolders, key = { _, f -> f.id }) { index, folder ->
                        val isDragging = draggingId == folder.id
                        FolderRow(
                            folder = folder,
                            count = state.countByFolder[folder.id] ?: 0,
                            isDragging = isDragging,
                            dragOffset = if (isDragging) dragOffset else 0f,
                            onClick = { onOpenFolder(folder.id) },
                            onRename = { renameTarget = folder },
                            onDelete = {
                                deleteTarget = folder
                                deleteChoice = null
                            },
                            dragModifier = Modifier.pointerInput(folder.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingId = folder.id
                                        dragOffset = 0f
                                        previewOrder = orderRef.toMutableList()
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        // 实时预览:以预览顺序计算目标位并交换
                                        val from = previewOrder.indexOf(folder.id)
                                        if (from >= 0) {
                                            val shift = (dragOffset / itemSpanPx).roundToInt()
                                            val target = (from + shift).coerceIn(0, previewOrder.lastIndex)
                                            if (target != from) {
                                                val newOrder = previewOrder.toMutableList()
                                                val id = newOrder.removeAt(from)
                                                newOrder.add(target, id)
                                                previewOrder = newOrder
                                                dragOffset = 0f
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        commitPreview()
                                        draggingId = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        // 手势取消同样提交,避免顺序丢失(v1.91 修复从上往下拖)
                                        commitPreview()
                                        draggingId = null
                                        dragOffset = 0f
                                    },
                                )
                            },
                        )
                    }
                    // 回收站恒最后
                    trash?.let { t ->
                        item(key = "__trash__") {
                            FolderRow(
                                folder = t,
                                count = state.trashCount,
                                isDragging = false,
                                dragOffset = 0f,
                                onClick = onOpenTrash,
                                onRename = {},
                                onDelete = {},
                                dragModifier = Modifier,
                                locked = true,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNew) {
        FolderEditDialog(
            title = stringResource(R.string.to_new_title),
            initialName = "",
            onConfirm = { name, color -> onCreate(name, color) },
            onDismiss = { showNew = false },
        )
    }
    renameTarget?.let { target ->
        FolderEditDialog(
            title = stringResource(R.string.to_rename_title),
            initialName = target.name,
            initialColor = target.color,
            onConfirm = { name, color -> onRename(target.id, name, color) },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        DeleteFolderDialog(
            target = target,
            count = state.countByFolder[target.id] ?: 0,
            migrateTargets = state.userFolders.filter { it.id != target.id },
            choice = deleteChoice,
            onChoice = { deleteChoice = it },
            onDismiss = { deleteTarget = null },
            onConfirm = {
                val chosen = deleteChoice
                onDelete(
                    target.id,
                    if (chosen == "__trash__") null else chosen,
                )
                deleteTarget = null
            },
        )
    }
}

/** 收藏夹行(拖动跟随 / 左滑删除 / 右滑重命名;回收站锁定) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderRow(
    folder: FavoriteFolder,
    count: Int,
    isDragging: Boolean,
    dragOffset: Float,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    dragModifier: Modifier,
    locked: Boolean = false,
) {
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                SwipeToDismissBoxValue.EndToStart -> { if (!locked) onDelete(); false }
                SwipeToDismissBoxValue.StartToEnd -> { if (!locked) onRename(); false }
                else -> false
            }
        },
    )
    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = !locked,
        enableDismissFromEndToStart = !locked,
        backgroundContent = {
            val left = swipeState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().background(if (left) Color(0xFFE5484D) else Color(0xFF14B8A6)),
                contentAlignment = if (left) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Text(
                    if (left) stringResource(R.string.to_delete_confirm) else stringResource(R.string.to_rename_title),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 5.dp)
                .then(dragModifier)
                .zIndex(if (isDragging) 1f else 0f)
                .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                .shadow(if (isDragging) 8.dp else 0.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(if (locked) Color(0xFFF1F5F9) else MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (locked) Color(0xFF94A3B8) else Color(folder.color)),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    folder.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (locked) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    stringResource(R.string.to_count_suffix, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 新建/重命名对话框:名称 + 12 色板 */
@Composable
private fun FolderEditDialog(
    title: String,
    initialName: String,
    initialColor: Long? = null,
    onConfirm: (String, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor ?: FolderPalette.COLORS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.to_new_name_hint)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.to_pick_color),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(FolderPalette.COLORS) { _, c ->
                        val selected = c == color
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(17.dp))
                                .background(Color(c))
                                .border(
                                    if (selected) 3.dp else 0.dp,
                                    MaterialTheme.colorScheme.onSurface,
                                    RoundedCornerShape(17.dp),
                                )
                                .clickable { color = c },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onConfirm(name.trim(), color)
                    onDismiss()
                }
            }) { Text(stringResource(R.string.to_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.to_cancel)) }
        },
    )
}

/** 删除迁移对话框:单选目标夹 或 全部移入回收站 */
@Composable
private fun DeleteFolderDialog(
    target: FavoriteFolder,
    count: Int,
    migrateTargets: List<FavoriteFolder>,
    choice: String?,
    onChoice: (String?) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.to_delete_title)) },
        text = {
            Column {
                Text(stringResource(R.string.to_delete_ask, target.name, count))
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.to_migrate_choose),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                migrateTargets.forEach { f ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onChoice(f.id) },
                    ) {
                        RadioButton(selected = choice == f.id, onClick = { onChoice(f.id) })
                        Box(
                            Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(f.color)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(f.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onChoice("__trash__") },
                ) {
                    RadioButton(selected = choice == "__trash__", onClick = { onChoice("__trash__") })
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.to_delete_to_trash), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = choice != null) {
                Text(stringResource(R.string.to_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.to_cancel)) }
        },
    )
}

// ===== 收藏夹详情 =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderDetailView(
    state: ToViewModel.UiState,
    onBack: () -> Unit,
    onMove: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    toast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val folder = state.openFolder ?: return
    var actionEntry by remember { mutableStateOf<CollectEntry?>(null) }
    var moveTarget by remember { mutableStateOf<CollectEntry?>(null) }
    val context = LocalContext.current

    Scaffold(modifier = modifier) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                Box(
                    Modifier.size(16.dp).clip(RoundedCornerShape(8.dp)).background(Color(folder.color)),
                )
                Spacer(Modifier.width(8.dp))
                Text(folder.name, style = MaterialTheme.typography.titleLarge)
            }
            if (state.folderEntries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.to_detail_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(state.folderEntries, key = { _, e -> e.id }) { _, entry ->
                        EntryRow(entry = entry, onClick = { actionEntry = entry })
                    }
                }
            }
        }
    }

    actionEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { actionEntry = null },
            title = { Text(entry.displayTitle, maxLines = 2) },
            text = { Text(if (entry.type.name == "LINK") "链接" else "文件", style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { moveTarget = entry; actionEntry = null }) {
                    Text(stringResource(R.string.to_detail_entry_move))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    onDeleteEntry(entry.id)
                    actionEntry = null
                }) { Text(stringResource(R.string.to_detail_entry_delete), color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    moveTarget?.let { entry ->
        FolderPickDialog(
            title = stringResource(R.string.to_migrate_choose),
            folders = state.userFolders.filter { it.id != state.openFolder?.id },
            onDismiss = { moveTarget = null },
            onPick = { f ->
                onMove(entry.id, f.id)
                moveTarget = null
            },
        )
    }
}

// ===== 回收站详情 =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashDetailView(
    state: ToViewModel.UiState,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var actionEntry by remember { mutableStateOf<CollectEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(modifier = modifier) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.to_trash_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }, enabled = state.trashEntries.isNotEmpty()) {
                    Text(stringResource(R.string.to_trash_clear), color = MaterialTheme.colorScheme.error)
                }
            }
            if (state.trashEntries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.to_trash_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(state.trashEntries, key = { _, e -> e.id }) { _, entry ->
                        EntryRow(entry = entry, onClick = { actionEntry = entry })
                    }
                }
            }
        }
    }

    actionEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { actionEntry = null },
            title = { Text(entry.displayTitle, maxLines = 2) },
            text = { Text(stringResource(R.string.to_trash_title), style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { onRestore(entry.id); actionEntry = null }) {
                    Text(stringResource(R.string.to_trash_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { onDelete(entry.id); actionEntry = null }) {
                    Text(stringResource(R.string.to_detail_entry_delete), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.to_trash_clear)) },
            text = { Text(stringResource(R.string.to_trash_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { onClear(); confirmClear = false }) {
                    Text(stringResource(R.string.to_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.to_cancel)) }
            },
        )
    }
}

/** 收藏夹选择对话框(移出/批量) */
@Composable
private fun FolderPickDialog(
    title: String,
    folders: List<FavoriteFolder>,
    onDismiss: () -> Unit,
    onPick: (FavoriteFolder) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (folders.isEmpty()) {
                    Text(stringResource(R.string.to_detail_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    folders.forEach { f ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(f) }.padding(vertical = 8.dp),
                        ) {
                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(f.color)))
                            Spacer(Modifier.width(8.dp))
                            Text(f.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.to_cancel)) }
        },
    )
}

/** 条目行(详情页/回收站共用):类型图标 + 标题 + 时间 */
@Composable
private fun EntryRow(entry: CollectEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (entry.type.name == "LINK") Color(0xFF14B8A6) else Color(0xFF3B82F6)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.displayTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    relativeTime(entry.markedAt ?: entry.collectedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun relativeTime(iso: String): String = try {
    val sec = java.time.Duration.between(OffsetDateTime.parse(iso), OffsetDateTime.now()).seconds
    when {
        sec < 60 -> "刚刚"
        sec < 3600 -> "${sec / 60} 分钟前"
        sec < 86400 -> "${sec / 3600} 小时前"
        sec < 172800 -> "昨天"
        else -> "${sec / 86400} 天前"
    }
} catch (e: Exception) {
    iso.take(10)
}
