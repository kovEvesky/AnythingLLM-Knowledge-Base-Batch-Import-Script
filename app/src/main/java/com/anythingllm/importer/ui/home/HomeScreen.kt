package com.anythingllm.importer.ui.home
import com.anythingllm.importer.R

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anythingllm.importer.AnythingLLMApp
import com.anythingllm.importer.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/**
 * 四主页(V1.9 交互重构,最终设计 §3):
 * 底部导航 Tab1「Mark」(流式收件箱:分享即流式 + 左右滑归类/删除)/
 * Tab2「To」(收藏夹管理)/ Tab3「Sync」(FTP → PC / AnythingLLM 双通道同步)/
 * Tab4「Settings」(同步设置 + 默认操作 + 高级参数)。
 * v1.9 阶段 2:Tab0 已切换 MarkScreen;Tab1/2/3 暂为占位,后续阶段填充。
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
            // Settings 自带页内 TopAppBar,避免双标题
            if (tab != 3) {
                TopAppBar(
                    title = {
                        Text(
                            when (tab) {
                                0 -> stringResource(R.string.home_tab_mark)
                                1 -> stringResource(R.string.home_tab_to)
                                2 -> stringResource(R.string.home_tab_sync)
                                else -> stringResource(R.string.home_tab_settings)
                            },
                        )
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text("M", style = MaterialTheme.typography.titleMedium) },
                    label = { Text(stringResource(R.string.home_tab_mark)) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Text("T", style = MaterialTheme.typography.titleMedium) },
                    label = { Text(stringResource(R.string.home_tab_to)) },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                    label = { Text(stringResource(R.string.home_tab_sync)) },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.home_tab_settings)) },
                )
            }
        },
    ) { padding ->
        Crossfade(targetState = tab, label = "homeTab") { currentTab ->
            when (currentTab) {
                0 -> MarkScreen(
                    state = state,
                    onMarkToFolder = viewModel::markToFolder,
                    onMarkEntriesToFolder = viewModel::markEntriesToFolder,
                    onTrash = viewModel::trashEntry,
                    onTrashEntries = viewModel::trashEntries,
                    onRestore = viewModel::restoreEntry,
                    onOpenImport = onOpenImport,
                    onOpenLogs = onOpenLogs,
                    onOpenTo = { tab = 1 },
                    modifier = Modifier.padding(padding),
                )
                1 -> ToScreen(modifier = Modifier.padding(padding))
                2 -> SyncScreen(modifier = Modifier.padding(padding))
                else -> SettingsScreen(onBack = { tab = 0 }, embedded = true, modifier = Modifier.padding(padding))
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

/** 阶段 2 占位页(阶段 3/4 填充 To/Sync/Settings) */
@Composable
private fun PlaceholderTab(name: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$name · 开发中", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
