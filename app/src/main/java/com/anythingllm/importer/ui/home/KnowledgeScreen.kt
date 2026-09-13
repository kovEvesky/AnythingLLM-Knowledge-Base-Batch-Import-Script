package com.anythingllm.importer.ui.home
import com.anythingllm.importer.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.EntryType
import com.anythingllm.importer.domain.mark.MarkPlan
import com.anythingllm.importer.domain.mark.MarkValidation
import com.anythingllm.importer.domain.sync.SyncItemStatus

/** UI-11:状态符号图标化(带语义 contentDescription,替代纯文本 ○…✓✗→) */
@Composable
private fun StatusIcon(status: SyncItemStatus) {
    val (icon, tint, label) = when (status) {
        SyncItemStatus.PENDING -> Triple(
            Icons.Outlined.Schedule, MaterialTheme.colorScheme.onSurfaceVariant, "待同步",
        )
        SyncItemStatus.RUNNING -> Triple(
            Icons.Outlined.Sync, MaterialTheme.colorScheme.primary, "同步中",
        )
        SyncItemStatus.SUCCESS -> Triple(
            Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary, "成功",
        )
        SyncItemStatus.FAILED -> Triple(
            Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error, "失败",
        )
        SyncItemStatus.SKIPPED -> Triple(
            Icons.AutoMirrored.Filled.ArrowForward, MaterialTheme.colorScheme.onSurfaceVariant, "跳过",
        )
    }
    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.width(16.dp).height(16.dp))
}

/**
 * Tab2 知识库(v1.2 FR-20/21/23/24):
 * 分区 A:服务器"我的文档"文件夹 + 工作区清单(仅缓存清单,来自快照);
 * 分区 B:手机端已标记待执行计划(可取消/删除,失效目标提示重新指定);
 * 顶部醒目按钮:连接服务器并同步嵌入(阶段 3 接通批量执行)。
 */
@Composable
fun KnowledgeScreen(
    state: CollectViewModel.UiState,
    onSync: () -> Unit,
    onUnmark: (String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // ===== 醒目同步入口(FR-24) =====
        Button(
            onClick = onSync,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = !state.refreshingSnapshot && !state.sync.running,
        ) {
            if (state.refreshingSnapshot || state.sync.running) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
            } else {
                Text("连接服务器并同步嵌入", style = MaterialTheme.typography.titleMedium)
            }
        }
        state.snapshotError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        // v1.3 无服务器引导:未安装/未连接 AnythingLLM 时提示走资料库 + FTP 通道
        if (!state.snapshot.hasData) {
            Text(
                stringResource(R.string.knowledge_offline_hint),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ===== 同步执行进度(FR-24) =====
        if (state.sync.items.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.knowledge_sync_progress, state.sync.doneCount, state.sync.totalCount) +
                            (if (state.sync.isTerminal) " · 完成" else ""),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(
                            R.string.knowledge_sync_summary,
                            state.sync.successCount,
                            state.sync.skippedCount,
                            state.sync.failedCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.sync.items.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusIcon(item.status)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                item.entry.displayTitle + (item.message?.let { " — $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
        if (state.syncDone) {
            Text(
                stringResource(R.string.knowledge_sync_cleaned),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // ===== 比对结果(FR-21) =====
        state.snapshotDiff?.let { diff ->
            if (!diff.isEmpty) {
                Card(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("结构变化", style = MaterialTheme.typography.titleSmall)
                        diff.addedFolders.forEach { Text("+ 文件夹: $it", style = MaterialTheme.typography.bodySmall) }
                        diff.removedFolders.forEach { Text("- 文件夹: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        diff.addedWorkspaces.forEach { Text("+ 工作区: $it", style = MaterialTheme.typography.bodySmall) }
                        diff.removedWorkspaces.forEach { Text("- 工作区: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        // ===== 分区 A:结构清单(FR-20) =====
        Spacer(Modifier.height(16.dp))
        Text("知识库结构(快照)", style = MaterialTheme.typography.titleMedium)
        Text(
            if (state.snapshot.hasData) {
                stringResource(R.string.knowledge_snapshot_time, state.snapshot.snapshotTime.take(16).replace('T', ' '))
            } else {
                stringResource(R.string.knowledge_snapshot_empty)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.knowledge_folders, state.snapshot.folders.size), style = MaterialTheme.typography.labelLarge)
                state.snapshot.folders.forEach { f ->
                    Text("• ${f.name}", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.knowledge_workspaces, state.snapshot.workspaces.size), style = MaterialTheme.typography.labelLarge)
                state.snapshot.workspaces.forEach { w ->
                    Text("• ${w.name}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // ===== 分区 B:已标记计划(FR-22/23) =====
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.knowledge_marked, state.markedEntries.size), style = MaterialTheme.typography.titleMedium)
        if (state.markedEntries.isEmpty()) {
            Text(
                stringResource(R.string.knowledge_marked_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.markedEntries.forEach { entry ->
                MarkedEntryCard(
                    entry = entry,
                    invalid = MarkPlan.validate(entry, state.snapshot),
                    onUnmark = { onUnmark(entry.id) },
                    onDelete = { onDeleteEntry(entry.id) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.knowledge_open_settings)) }
            OutlinedButton(onClick = onOpenLogs, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.knowledge_open_logs)) }
        }
    }
}

@Composable
private fun MarkedEntryCard(
    entry: CollectEntry,
    invalid: MarkValidation,
    onUnmark: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (entry.type == EntryType.LINK) "链接" else "文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "→ ${entry.markFolder ?: "(未选文件夹)"}" +
                    (entry.markWorkspace?.let { " / 嵌入:$it" } ?: " / 同步模式(不嵌入)"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            when (invalid) {
                MarkValidation.Ok -> Unit
                is MarkValidation.FolderMissing ->
                    Text("目标文件夹已失效(执行时将自动重建)", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                is MarkValidation.WorkspaceMissing ->
                    Text("目标工作区已删除,请取消后重新标记", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (entry.error != null) {
                Text(
                    entry.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row {
                TextButton(onClick = onUnmark) { Text(stringResource(R.string.knowledge_unmark)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.common_delete)) }
            }
        }
    }
}
