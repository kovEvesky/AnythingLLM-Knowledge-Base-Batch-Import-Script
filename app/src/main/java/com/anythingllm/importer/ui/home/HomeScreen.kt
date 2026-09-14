package com.anythingllm.importer.ui.home
import com.anythingllm.importer.R

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anythingllm.importer.AnythingLLMApp
import kotlinx.coroutines.launch

/**
 * 三主页(v1.2 FR-23 + v1.3):
 * 底部导航 Tab1「收集箱」(待整理条目)/ Tab2「知识库」(结构清单 + 已标记计划 + 同步入口)/
 * Tab3「资料库」(v1.3:文件夹树 + 条目管理 + FTP 同步到 PC,未安装 AnythingLLM 场景)。
 * v1.0 服务器状态/导入/设置/日志入口收拢到对应 Tab(即时导入保留为收集箱内快捷入口)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenLogs: () -> Unit,
    viewModel: CollectViewModel = viewModel(factory = CollectViewModel.factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }

    // 每次进入主页重新读取本地仓储(分享接收/执行完成后返回可即时看到最新条目)。
    // 修复 BUG-全面验证-01:分享发生在 app 已在前台时,ShareReceiver 压栈处理→返回仅 onResume,
    // 仅 LaunchedEffect(Unit) 组合期一次刷新不够 → 改为每次 ON_RESUME 刷新。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tab) {
                            0 -> stringResource(R.string.home_tab_inbox)
                            1 -> stringResource(R.string.home_tab_knowledge)
                            else -> stringResource(R.string.library_title)
                        },
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Inbox, contentDescription = stringResource(R.string.home_tab_inbox)) },
                    label = { Text(stringResource(R.string.home_tab_inbox)) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = stringResource(R.string.home_tab_knowledge)) },
                    label = { Text(stringResource(R.string.home_tab_knowledge)) },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = stringResource(R.string.library_title)) },
                    label = { Text(stringResource(R.string.library_title)) },
                )
            }
        },
    ) { padding ->
        Crossfade(targetState = tab, label = "homeTab") { currentTab ->
        when (currentTab) {
            0 -> Column(
                Modifier
                    .fillMaxWidth()
                    .padding(padding),
            ) {
                // 即时导入快捷入口(v1.0 流程保留)
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    TextButton(onClick = onOpenImport) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.width(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.home_quick_import))
                    }
                }
                CollectScreen(
                    state = state,
                    onToggleSelect = viewModel::toggleSelected,
                    onToggleSelectAll = viewModel::toggleSelectAll,
                    onMark = viewModel::markSelected,
                    onDeleteSelected = viewModel::deleteSelected,
                    onArchiveToLibrary = viewModel::archiveSelectedToLibrary,
                    onRefresh = viewModel::refresh,
                    onMarkOneToDefault = viewModel::markEntryToDefault,
                    onMarkOneToLast = viewModel::markEntryToLast,
                    onMarkOne = viewModel::markOneEntry,
                    onMarkAllToDefault = viewModel::markAllToDefault,
                    onOpenSettings = onOpenSettings,
                )
            }
            1 -> KnowledgeScreen(
                state = state,
                onSync = viewModel::syncNow,
                onUnmark = viewModel::unmark,
                onDeleteEntry = viewModel::deleteEntry,
                onOpenSettings = onOpenSettings,
                onOpenLogs = onOpenLogs,
                modifier = Modifier.padding(padding),
            )
            2 -> LibraryScreen(
                state = state,
                onEnterFolder = viewModel::libraryEnterFolder,
                onGoUp = viewModel::libraryGoUp,
                onGoRoot = viewModel::libraryGoRoot,
                onCreateFolder = viewModel::libraryCreateFolder,
                onRenameFolder = viewModel::libraryRenameFolder,
                onDeleteFolder = viewModel::libraryDeleteFolder,
                onMoveEntry = viewModel::libraryMoveEntry,
                onDeleteEntry = viewModel::libraryDeleteEntry,
                onSyncFtp = viewModel::syncFtpNow,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(padding),
            )
        }
        }
    }

    // ===== UI-19 首启引导 =====
    val appContext = LocalContext.current.applicationContext as AnythingLLMApp
    val repository = remember { appContext.configRepository }
    val scope = rememberCoroutineScope()
    var showGuide by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { showGuide = !repository.snapshot().guideSeen }
    if (showGuide) {
        AlertDialog(
            onDismissRequest = { /* 必须点"开始使用" */ },
            title = { Text(stringResource(R.string.guide_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.guide_step1), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.guide_step2), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.guide_step3), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch { repository.markGuideSeen() }
                    showGuide = false
                }) { Text(stringResource(R.string.guide_confirm)) }
            },
        )
    }
}
