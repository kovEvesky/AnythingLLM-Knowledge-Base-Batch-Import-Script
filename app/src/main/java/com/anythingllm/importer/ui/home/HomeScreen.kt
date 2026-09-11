package com.anythingllm.importer.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 双主页(v1.2 FR-23):
 * 底部导航 Tab1「收集箱」(待整理条目) / Tab2「知识库」(结构清单 + 已标记计划 + 同步入口)。
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
            TopAppBar(title = { Text(if (tab == 0) "收集箱" else "知识库") })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Inbox, contentDescription = null) },
                    label = { Text("收集箱") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
                    label = { Text("知识库") },
                )
            }
        },
    ) { padding ->
        when (tab) {
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
                        Text("立即导入(v1.0 即时流程)")
                    }
                }
                CollectScreen(
                    state = state,
                    onToggleSelect = viewModel::toggleSelected,
                    onToggleSelectAll = viewModel::toggleSelectAll,
                    onMark = viewModel::markSelected,
                    onDeleteSelected = viewModel::deleteSelected,
                    onRefresh = viewModel::refresh,
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
        }
    }
}
