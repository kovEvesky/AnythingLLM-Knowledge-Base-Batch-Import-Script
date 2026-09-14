package com.anythingllm.importer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anythingllm.importer.R
import com.anythingllm.importer.domain.sync.SyncItemStatus
import com.anythingllm.importer.domain.sync.SyncItemState

/** 状态符号(§4.9:✓ 已同步 / ◌ 正在嵌入 / ⏸ 待同步 / ✕ 失败 / 🔒 不参与) */
private fun folderMark(status: SyncFolderStatus): String = when (status) {
    SyncFolderStatus.DONE -> "✓"
    SyncFolderStatus.EMBEDDING -> "◌"
    SyncFolderStatus.PENDING -> "⏸"
    SyncFolderStatus.FAILED -> "✕"
    SyncFolderStatus.LOCKED -> "🔒"
}

@Composable
fun SyncScreen(
    modifier: Modifier = Modifier,
    viewModel: SyncViewModel = viewModel(factory = SyncViewModel.factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.refreshFolders() }

    LaunchedEffect(state.finished) {
        state.finished?.let {
            viewModel.consumeFinished()
            snackbar.showSnackbar(it)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        // 双子选项卡
        TabRow(selectedTabIndex = if (state.channel == SyncChannel.FTP) 0 else 1) {
            Tab(
                selected = state.channel == SyncChannel.FTP,
                onClick = { viewModel.selectChannel(SyncChannel.FTP) },
                text = { Text(stringResource(R.string.sync_tab_ftp)) },
            )
            Tab(
                selected = state.channel == SyncChannel.ANY,
                onClick = { viewModel.selectChannel(SyncChannel.ANY) },
                text = { Text(stringResource(R.string.sync_tab_llm)) },
            )
        }

        // 进度卡片
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        state.running -> stringResource(R.string.sync_running, state.done, state.total)
                        state.total == 0 -> stringResource(R.string.sync_empty)
                        else -> stringResource(R.string.sync_ready, state.total)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
                val err = state.error
                if (err != null) {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // 收藏夹同步状态列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.folders, key = { it.id }) { f ->
                SyncFolderRow(f)
            }
            item {
                state.items.filter { it.status == SyncItemStatus.RUNNING || it.status == SyncItemStatus.FAILED }
                    .take(5)
                    .forEach { item ->
                        EntryRow(item)
                    }
            }
        }

        Button(
            onClick = { viewModel.runSync() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.running,
        ) {
            Text(
                if (state.running) stringResource(R.string.sync_running, state.done, state.total)
                else stringResource(R.string.sync_one_click),
            )
        }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SyncFolderRow(f: SyncFolderState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(Color(f.color), CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(f.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                "${folderMark(f.status)} ${f.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = when (f.status) {
                    SyncFolderStatus.FAILED -> MaterialTheme.colorScheme.error
                    SyncFolderStatus.EMBEDDING -> MaterialTheme.colorScheme.primary
                    SyncFolderStatus.LOCKED -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun EntryRow(item: SyncItemState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.entry.displayTitle,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when (item.status) {
                SyncItemStatus.RUNNING -> "◌"
                SyncItemStatus.FAILED -> "✕"
                else -> "·"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (item.status == SyncItemStatus.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
