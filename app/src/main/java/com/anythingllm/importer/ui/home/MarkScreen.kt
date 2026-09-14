@file:OptIn(ExperimentalFoundationApi::class)

package com.anythingllm.importer.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anythingllm.importer.R
import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.EntryStatus
import com.anythingllm.importer.data.collect.EntryType
import com.anythingllm.importer.data.favorite.FavoriteFolder
import com.anythingllm.importer.data.favorite.FavoriteRepository
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Tab1 Mark · 流式收件箱(V1.9 最终设计 §4.1–4.5):
 * - 平铺列表卡片式(留白间隔、不叠加);未标记白卡在前按 collectedAt 倒序,灰卡在后按 markedAt 倒序;
 * - 白卡:左滑=删除(进回收站 TRASHED),右滑=收藏夹气泡(两段式;仅 1 个用户夹直入,A1 定稿);
 * - 灰卡:左滑/右滑均=撤销标记(恢复 PENDING,双向同义,Q6);单击=弹气泡改夹;
 * - 左上角"+"菜单:批量多选 / 即时导入 / 日志(Q7)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkScreen(
    state: CollectViewModel.UiState,
    onMarkToFolder: (entryId: String, folderId: String) -> Unit,
    onMarkEntriesToFolder: (ids: List<String>, folderId: String) -> Unit,
    onTrash: (entryId: String) -> Unit,
    onTrashEntries: (ids: List<String>) -> Unit,
    onRestore: (entryId: String) -> Unit,
    onOpenImport: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenTo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }
    val trashToast = context.getString(R.string.mark_trashed_toast)
    val unmarkToast = context.getString(R.string.mark_unmark_toast)

    // 气泡打开的条目 id(null=关闭);多选模式与选中集
    var bubbleEntryId by remember { mutableStateOf<String?>(null) }
    var multiSelect by remember { mutableStateOf(false) }
    var multiSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var plusMenuOpen by remember { mutableStateOf(false) }

    val userFolders = state.userFolders
    val defaultFolder = userFolders.firstOrNull { it.id == state.defaultFolderId }

    /** 归类入口:0 夹引导新建;1 夹直入(A1 定稿);多夹弹气泡 */
    fun classify(entryId: String) {
        when {
            userFolders.isEmpty() -> toast(context.getString(R.string.mark_no_folder_toast))
            userFolders.size == 1 -> {
                val folder = userFolders[0]
                onMarkToFolder(entryId, folder.id)
                toast(
                    if (folder.id == defaultFolder?.id || defaultFolder == null) {
                        context.getString(R.string.mark_default_toast)
                    } else {
                        context.getString(R.string.mark_foldered_toast, folder.name)
                    },
                )
            }
            else -> bubbleEntryId = entryId
        }
    }

    /** 批量归类:弹夹选择气泡 */
    fun classifyBatch() {
        if (multiSelected.isEmpty()) return
        when {
            userFolders.isEmpty() -> toast(context.getString(R.string.mark_no_folder_toast))
            else -> bubbleEntryId = "__batch__"
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = modifier,
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            // ===== 顶部栏:左上角"+" + 标题 Mark =====
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Box {
                    IconButtonCompat(
                        onClick = { plusMenuOpen = true },
                    )
                    DropdownMenu(
                        expanded = plusMenuOpen,
                        onDismissRequest = { plusMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.mark_plus_batch)) },
                            onClick = {
                                plusMenuOpen = false
                                multiSelect = true
                                multiSelected = emptySet()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.mark_plus_import)) },
                            onClick = { plusMenuOpen = false; onOpenImport() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.mark_plus_logs)) },
                            onClick = { plusMenuOpen = false; onOpenLogs() },
                        )
                    }
                }
                Text(
                    stringResource(R.string.home_tab_mark),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                if (multiSelect) {
                    TextButton(onClick = { multiSelect = false; multiSelected = emptySet() }) {
                        Text(stringResource(R.string.mark_batch_exit))
                    }
                }
            }

            // ===== 多选模式提示 / 手势提示条 =====
            if (multiSelect) {
                Text(
                    stringResource(R.string.mark_batch_mode_on, multiSelected.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            } else {
                Text(
                    stringResource(R.string.mark_swipe_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            val allEntries = state.whiteEntries + state.grayEntries
            if (allEntries.isEmpty()) {
                Spacer(Modifier.height(40.dp))
                Icon(
                    Icons.Outlined.Inbox,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(52.dp).align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.mark_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    // 白卡段:今天/更早
                    whiteSection(
                        state = state,
                        entries = state.whiteEntries,
                        multiSelect = multiSelect,
                        multiSelected = multiSelected,
                        onToggleSelect = { id ->
                            multiSelected = if (id in multiSelected) multiSelected - id else multiSelected + id
                        },
                        onClassify = ::classify,
                        onTrash = onTrash,
                        onRestore = onRestore,
                        onToast = ::toast,
                        trashToast = trashToast,
                        unmarkToast = unmarkToast,
                    )
                    // 灰卡段:今天/更早
                    graySection(
                        state = state,
                        entries = state.grayEntries,
                        multiSelect = multiSelect,
                        multiSelected = multiSelected,
                        onToggleSelect = { id ->
                            multiSelected = if (id in multiSelected) multiSelected - id else multiSelected + id
                        },
                        onClassify = ::classify,
                        onRestore = onRestore,
                        onToast = ::toast,
                        unmarkToast = unmarkToast,
                    )
                }
            }

            // ===== 多选底部操作栏 =====
            if (multiSelect && multiSelected.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = ::classifyBatch, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.mark_batch_mark))
                    }
                    TextButton(
                        onClick = {
                            onTrashEntries(multiSelected.toList())
                            toast(context.getString(R.string.mark_trashed_toast))
                            multiSelected = emptySet()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.mark_batch_trash))
                    }
                }
            }
        }
    }

    // ===== 收藏夹气泡(右滑/单击触发;批量模式 target="__batch__") =====
    bubbleEntryId?.let { target ->
        FolderBubbleSheet(
            title = if (target == "__batch__") {
                context.getString(R.string.mark_batch_bubble_hint)
            } else {
                context.getString(R.string.mark_bubble_title)
            },
            folders = userFolders,
            onDismiss = { bubbleEntryId = null }, // 点空白取消 → 保持未标记(Q6)
            onPick = { folder ->
                if (target == "__batch__") {
                    onMarkEntriesToFolder(multiSelected.toList(), folder.id)
                    multiSelected = emptySet()
                    toast(context.getString(R.string.mark_foldered_toast, folder.name))
                } else {
                    onMarkToFolder(target, folder.id)
                    toast(context.getString(R.string.mark_foldered_toast, folder.name))
                }
                bubbleEntryId = null
            },
            onNew = {
                bubbleEntryId = null
                onOpenTo()
            },
        )
    }
}

/** "＋"圆形按钮(无自带图标名冲突,手绘圆底 + Add 图标) */
@Composable
private fun IconButtonCompat(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 白卡段:今天/更早 分组 */
private fun androidx.compose.foundation.lazy.LazyListScope.whiteSection(
    state: CollectViewModel.UiState,
    entries: List<CollectEntry>,
    multiSelect: Boolean,
    multiSelected: Set<String>,
    onToggleSelect: (String) -> Unit,
    onClassify: (String) -> Unit,
    onTrash: (String) -> Unit,
    onRestore: (String) -> Unit,
    onToast: (String) -> Unit,
    trashToast: String,
    unmarkToast: String,
) {
    if (entries.isEmpty()) return
    sectionHeader(stringResourceId = R.string.mark_white_section, showGroup = false)
    val today = entries.filter { isToday(it.collectedAt) }
    val earlier = entries.filter { !isToday(it.collectedAt) }
    if (today.isNotEmpty()) {
        sectionHeader(R.string.mark_today)
        today.forEach { e ->
            item(key = e.id) {
                SwipeableMarkRow(
                    entry = e,
                    gray = false,
                    folderName = null,
                    multiSelect = multiSelect,
                    selected = e.id in multiSelected,
                    onToggle = { onToggleSelect(e.id) },
                    onClick = { if (multiSelect) onToggleSelect(e.id) else onClassify(e.id) },
                    onSwipeLeft = { onTrash(e.id); onToast(trashToast) },
                    onSwipeRight = { if (!multiSelect) onClassify(e.id) },
                    onSwipeRestore = { onRestore(e.id); onToast(unmarkToast) },
                )
            }
        }
    }
    if (earlier.isNotEmpty()) {
        sectionHeader(R.string.mark_earlier)
        earlier.forEach { e ->
            item(key = e.id) {
                SwipeableMarkRow(
                    entry = e,
                    gray = false,
                    folderName = null,
                    multiSelect = multiSelect,
                    selected = e.id in multiSelected,
                    onToggle = { onToggleSelect(e.id) },
                    onClick = { if (multiSelect) onToggleSelect(e.id) else onClassify(e.id) },
                    onSwipeLeft = { onTrash(e.id); onToast(trashToast) },
                    onSwipeRight = { if (!multiSelect) onClassify(e.id) },
                    onSwipeRestore = { onRestore(e.id); onToast(unmarkToast) },
                )
            }
        }
    }
}

/** 灰卡段:今天/更早 分组(终态条目仅可改夹不可撤销,A4 定稿) */
private fun androidx.compose.foundation.lazy.LazyListScope.graySection(
    state: CollectViewModel.UiState,
    entries: List<CollectEntry>,
    multiSelect: Boolean,
    multiSelected: Set<String>,
    onToggleSelect: (String) -> Unit,
    onClassify: (String) -> Unit,
    onRestore: (String) -> Unit,
    onToast: (String) -> Unit,
    unmarkToast: String,
) {
    if (entries.isEmpty()) return
    sectionHeader(stringResourceId = R.string.mark_gray_section, showGroup = false)
    val today = entries.filter { isToday(it.markedAt ?: it.collectedAt) }
    val earlier = entries.filter { !isToday(it.markedAt ?: it.collectedAt) }
    val folderNameOf: (CollectEntry) -> String = { e ->
        e.markFolderId?.let { id -> state.favoriteFolders.firstOrNull { it.id == id }?.name }
            ?: FavoriteRepository.TRASH_NAME
    }
    if (today.isNotEmpty()) {
        sectionHeader(R.string.mark_today)
        today.forEach { e ->
            item(key = e.id) {
                SwipeableMarkRow(
                    entry = e,
                    gray = true,
                    folderName = folderNameOf(e),
                    multiSelect = multiSelect,
                    selected = e.id in multiSelected,
                    onToggle = { onToggleSelect(e.id) },
                    onClick = { if (multiSelect) onToggleSelect(e.id) else onClassify(e.id) },
                    onSwipeLeft = {},
                    onSwipeRight = {},
                    onSwipeRestore = {
                        if (!e.isTerminal) {
                            onRestore(e.id); onToast(unmarkToast)
                        }
                    },
                )
            }
        }
    }
    if (earlier.isNotEmpty()) {
        sectionHeader(R.string.mark_earlier)
        earlier.forEach { e ->
            item(key = e.id) {
                SwipeableMarkRow(
                    entry = e,
                    gray = true,
                    folderName = folderNameOf(e),
                    multiSelect = multiSelect,
                    selected = e.id in multiSelected,
                    onToggle = { onToggleSelect(e.id) },
                    onClick = { if (multiSelect) onToggleSelect(e.id) else onClassify(e.id) },
                    onSwipeLeft = {},
                    onSwipeRight = {},
                    onSwipeRestore = {
                        if (!e.isTerminal) {
                            onRestore(e.id); onToast(unmarkToast)
                        }
                    },
                )
            }
        }
    }
}

/** 分组小标题 */
private fun androidx.compose.foundation.lazy.LazyListScope.sectionHeader(
    stringResourceId: Int,
    showGroup: Boolean = true,
) {
    item(key = "header_$stringResourceId") {
        Text(
            stringResource(stringResourceId),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp),
        )
    }
}

/** 左右滑条目:白卡 左滑=删/右滑=归类;灰卡 双滑=撤销(终态不可撤销) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableMarkRow(
    entry: CollectEntry,
    gray: Boolean,
    folderName: String?,
    multiSelect: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeRestore: () -> Unit,
) {
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (gray) onSwipeRestore() else onSwipeRight()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    if (gray) onSwipeRestore() else onSwipeLeft()
                    false
                }
                else -> false
            }
        },
    )
    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            val dir = swipeState.dismissDirection
            val isLeft = dir == SwipeToDismissBoxValue.EndToStart
            val (bgColor, label) = if (gray) {
                // 灰卡:双向均为撤销提示(青绿底)
                Color(0xFF14B8A6) to stringResource(R.string.mark_bg_undo)
            } else if (isLeft) {
                // 左滑删除:灰红底
                Color(0xFFE5484D) to stringResource(R.string.mark_bg_delete)
            } else {
                // 右滑归类:青绿底
                Color(0xFF14B8A6) to stringResource(R.string.mark_bg_classify)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(bgColor),
                contentAlignment = if (isLeft) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    ) {
        MarkEntryCard(
            entry = entry,
            gray = gray,
            folderName = folderName,
            multiSelect = multiSelect,
            selected = selected,
            onToggle = onToggle,
            onClick = onClick,
        )
    }
}

/** 平铺卡片:类型图标 + 标题 + 副行(类型·来源·相对时间);灰卡灰底 + 右上角已归入标签;图片条目缩略图 */
@Composable
private fun MarkEntryCard(
    entry: CollectEntry,
    gray: Boolean,
    folderName: String?,
    multiSelect: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val cardBg = if (gray) Color(0xE6E7E9EE) else MaterialTheme.colorScheme.surface
    val borderColor = if (selected) Color(0xFF14B8A6) else Color.Transparent
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.background(cardBg).fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (multiSelect) {
                    Checkbox(checked = selected, onCheckedChange = { onToggle() })
                    Spacer(Modifier.width(4.dp))
                }
                MarkTypeBadge(entry.type)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.displayTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        markDetailLine(entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ImageThumb(entry)
            }
            // 灰卡右上角"已归入[收藏夹]"标签
            if (gray && folderName != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x33FFFFFF))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        stringResource(R.string.mark_gray_tag, folderName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 类型徽标:链接=青绿底 / 文件=蓝底(方案 §4.1) */
@Composable
private fun MarkTypeBadge(type: EntryType) {
    val (icon, container, tint) = when (type) {
        EntryType.LINK -> Triple(Icons.Filled.Link, Color(0xFF14B8A6), Color.White)
        EntryType.FILE -> Triple(Icons.AutoMirrored.Filled.InsertDriveFile, Color(0xFF3B82F6), Color.White)
    }
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
    }
}

private fun markDetailLine(entry: CollectEntry): String {
    val kind = when (entry.type) {
        EntryType.FILE -> "文件"
        EntryType.LINK -> "链接"
    }
    val source = entry.sourceApp?.ifBlank { null } ?: "未知来源"
    val time = relativeLabel(entry.collectedAt)
    return "$kind · $source · $time"
}

/** 相对时间标签(UI 文案直接中文,简单稳定) */
private fun relativeLabel(iso: String): String {
    return try {
        val t = OffsetDateTime.parse(iso)
        val now = OffsetDateTime.now()
        val sec = java.time.Duration.between(t, now).seconds
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
}

private fun isToday(iso: String): Boolean = try {
    val t = OffsetDateTime.parse(iso).toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
    t == LocalDate.now()
} catch (e: Exception) {
    iso.take(10) == LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
}

/** 图片条目缩略图(Q9 定稿:显示缩略图,控制解码尺寸) */
@Composable
private fun ImageThumb(entry: CollectEntry) {
    if (entry.type != EntryType.FILE) return
    val path = entry.localPath ?: return
    if (!isImageFile(entry.fileName)) return
    val bmp = remember(path) {
        runCatching { decodeSampledBitmap(path, 96, 96) }.getOrNull()
    } ?: return
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp)),
    )
}

private fun isImageFile(name: String?): Boolean {
    val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
    return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
}

private fun decodeSampledBitmap(path: String, reqW: Int, reqH: Int): android.graphics.Bitmap {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    var sample = 1
    while (opts.outWidth / sample > reqW * 2 || opts.outHeight / sample > reqH * 2) sample *= 2
    return BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        },
    ) ?: throw IllegalStateException("decode failed")
}

/** 收藏夹气泡(半透明毛玻璃近似):横向胶囊(色点+名称)+ 虚线"新建…" */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderBubbleSheet(
    title: String,
    folders: List<FavoriteFolder>,
    onDismiss: () -> Unit,
    onPick: (FavoriteFolder) -> Unit,
    onNew: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (folders.isEmpty()) {
                Text(
                    stringResource(R.string.mark_no_folder_toast),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            } else {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    items(folders, key = { it.id }) { folder ->
                        BubbleChip(folder = folder, onClick = { onPick(folder) })
                    }
                    item(key = "__new__") {
                        NewFolderChip(onClick = onNew)
                    }
                }
            }
        }
    }
}

@Composable
private fun BubbleChip(folder: FavoriteFolder, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFF1F5F9))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(folder.color)),
        )
        Spacer(Modifier.width(6.dp))
        Text(folder.name, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NewFolderChip(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = Color(0xFF94A3B8),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.mark_bubble_new),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF64748B),
        )
    }
}
