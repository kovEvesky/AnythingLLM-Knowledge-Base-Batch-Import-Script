package com.anythingllm.importer.ui.validate
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.ui.res.stringResource
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
                    title = { Text(stringResource(R.string.validation_title)) },
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
                    stringResource(
                        R.string.validation_summary,
                        summary.passedCount,
                        formatBytes(summary.passedBytes),
                        summary.rejectedCount,
                    ),
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
                        Text(stringResource(R.string.validation_reselect))
                    }
                    Button(
                        onClick = onNext,
                        modifier = Modifier.weight(1f),
                        enabled = summary.passed.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.validation_next))
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
                // UI-11:通过/拒绝符号图标化
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "通过",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(16.dp).height(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(passed.file.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(formatBytes(passed.file.sizeBytes), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(R.string.validation_storage_name, passed.storageName),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = "拒绝",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.width(16.dp).height(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(rejected.file.displayName, style = MaterialTheme.typography.bodyLarge)
            }
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
