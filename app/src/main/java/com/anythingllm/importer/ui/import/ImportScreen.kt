package com.anythingllm.importer.ui.import

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anythingllm.importer.data.config.DuplicateAction
import com.anythingllm.importer.domain.import.ImportItemState
import com.anythingllm.importer.domain.import.ItemStatus
import com.anythingllm.importer.domain.import.RunPhase
import com.anythingllm.importer.util.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    viewModel: ImportSessionViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val run = state.runState
    val running = run?.isTerminalPhase == false

    BackHandler {
        if (running) viewModel.showExitConfirm() else onDone()
    }

    Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("导入进度") },
                    navigationIcon = {
                        TextButton(onClick = { if (running) viewModel.showExitConfirm() else onDone() }) {
                            Text("返回")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (run == null) {
                    Text("导入尚未开始", style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }

                // 汇总
                val total = run.items.size
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "共 $total · 成功 ${run.successCount} · 失败 ${run.failedCount} · 跳过 ${run.skippedCount}" +
                            if (run.cancelledCount > 0) " · 取消 ${run.cancelledCount}" else "",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        when (run.phase) {
                            RunPhase.RUNNING -> "进行中"
                            RunPhase.PAUSED -> "已暂停"
                            RunPhase.CANCELLED -> "已取消"
                            RunPhase.FINISHED -> "已完成"
                        },
                        color = if (run.phase == RunPhase.CANCELLED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else run.doneCount.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )

                // 状态卡片
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(run.items, key = { it.target.uriString }) { item ->
                        ItemCard(item, onRetry = { viewModel.retryItem(run.items.indexOf(item)) })
                    }
                }

                // 控制
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!run.isTerminalPhase) {
                        OutlinedButton(
                            onClick = viewModel::pauseToggle,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (run.phase == RunPhase.PAUSED) "继续" else "暂停")
                        }
                        OutlinedButton(
                            onClick = viewModel::showExitConfirm,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("取消")
                        }
                    } else if (run.failedCount > 0) {
                        OutlinedButton(
                            onClick = viewModel::retryFailed,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("重试失败项(${run.failedCount})")
                        }
                        Button(onClick = onDone, modifier = Modifier.weight(1f)) {
                            Text("完成")
                        }
                    } else {
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                            Text("完成")
                        }
                    }
                }
            }
        }

    run?.duplicateQuestion?.let { q ->
        DuplicateDialog(
            title = q.title,
            count = q.existingCount,
            onAnswer = viewModel::answerDuplicate,
        )
    }

    if (state.showExitConfirm) {
        ExitConfirmDialog(
            running = running,
            onCancel = viewModel::dismissExitConfirm,
            onConfirm = {
                viewModel.cancel()
                if (!running) onDone()
            },
        )
    }
}

@Composable
private fun ItemCard(
    item: ImportItemState,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.target.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    phaseText(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = phaseColor(item.status),
                )
            }
            Text(
                "存储名: ${item.target.storageName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (item.status) {
                ItemStatus.UPLOADING -> {
                    if (item.target.sizeBytes > 0) {
                        LinearProgressIndicator(
                            progress = { (item.uploadedBytes.toFloat() / item.target.sizeBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${formatBytes(item.uploadedBytes)} / ${formatBytes(item.target.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    item.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                ItemStatus.FAILED -> {
                    item.error?.let {
                        Text("原因: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onRetry) { Text("重试此文件") }
                }
                else -> Unit
            }
        }
    }
}

private fun phaseText(item: ImportItemState): String = when (item.status) {
    ItemStatus.PENDING -> "等待中"
    ItemStatus.UPLOADING ->
        if (item.retryCount > 0) "上传重试 ${item.retryCount}/${item.retryCount + 1}" else "上传中"
    ItemStatus.WAITING_EMBED -> "等待嵌入"
    ItemStatus.VERIFYING -> "验证嵌入(第${item.verifyAttempt}次)"
    ItemStatus.SUCCESS -> "成功"
    ItemStatus.FAILED -> "失败"
    ItemStatus.SKIPPED -> "已跳过(重复)"
    ItemStatus.CANCELLED -> "已取消"
}

@Composable
private fun phaseColor(status: ItemStatus) = when (status) {
    ItemStatus.SUCCESS -> MaterialTheme.colorScheme.primary
    ItemStatus.FAILED, ItemStatus.CANCELLED -> MaterialTheme.colorScheme.error
    ItemStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun DuplicateDialog(
    title: String,
    count: Int,
    onAnswer: (DuplicateAction, Boolean) -> Unit,
) {
    var applyAll by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { /* 必须选择动作 */ },
        title = { Text("检测到重复文档") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("《$title》在服务器已存在 $count 个同名单据。请选择处理方式:")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                    Text("应用到全部重复文件")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(DuplicateAction.REPLACE, applyAll) }) { Text("替换") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onAnswer(DuplicateAction.KEEP, applyAll) }) { Text("保留") }
                TextButton(onClick = { onAnswer(DuplicateAction.SKIP, applyAll) }) { Text("跳过") }
                TextButton(onClick = { onAnswer(DuplicateAction.ABORT, applyAll) }) { Text("中止") }
            }
        },
    )
}

@Composable
private fun ExitConfirmDialog(
    running: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (running) "导入进行中" else "导入已完成") },
        text = {
            Text(
                if (running) "确定取消导入并返回吗?已上传的文档可能已嵌入,可稍后查重处理。" else "确定返回主页吗?",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确定") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}
