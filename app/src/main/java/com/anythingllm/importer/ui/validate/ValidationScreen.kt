package com.anythingllm.importer.ui.validate

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anythingllm.importer.domain.validate.PassedFile
import com.anythingllm.importer.domain.validate.RejectedFile
import com.anythingllm.importer.ui.select.SelectViewModel
import com.anythingllm.importer.util.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ValidationScreen(
    viewModel: SelectViewModel,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val summary = state.validation

    Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("预校验结果") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                if (summary == null) {
                    Text("暂无校验结果", style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }

                // 汇总
                Text(
                    "通过 ${summary.passedCount} 个 · ${formatBytes(summary.passedBytes)}   拒绝 ${summary.rejectedCount} 个",
                    style = MaterialTheme.typography.titleMedium,
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (summary.passed.isNotEmpty()) {
                        item {
                            Text("通过", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        items(summary.passed, key = { "p_${it.file.uriString}" }) { passed ->
                            PassedCard(passed)
                        }
                    }
                    if (summary.rejected.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(4.dp))
                            Text("拒绝", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        }
                        items(summary.rejected, key = { "r_${it.file.uriString}" }) { rejected ->
                            RejectedCard(rejected)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                        Text("重新选择")
                    }
                    Button(
                        onClick = onNext,
                        modifier = Modifier.weight(1f),
                        enabled = summary.passed.isNotEmpty(),
                    ) {
                        Text("下一步:选择目标")
                    }
                }
            }
        }
}

@Composable
private fun PassedCard(passed: PassedFile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓ ", color = MaterialTheme.colorScheme.primary)
                Text(passed.file.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(formatBytes(passed.file.sizeBytes), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "存储名: ${passed.storageName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RejectedCard(rejected: RejectedFile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("✗ ${rejected.file.displayName}", style = MaterialTheme.typography.bodyLarge)
            rejected.reasons.forEach { reason ->
                Text(
                    "· $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
