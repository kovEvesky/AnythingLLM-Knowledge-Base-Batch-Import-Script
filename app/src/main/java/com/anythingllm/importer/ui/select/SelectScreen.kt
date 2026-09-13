package com.anythingllm.importer.ui.select

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anythingllm.importer.domain.validate.PickedFile
import com.anythingllm.importer.util.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectScreen(
    onBack: () -> Unit,
    onValidate: () -> Unit,
    viewModel: SelectViewModel = viewModel(factory = SelectViewModel.factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.init() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.onFilesPicked(uris, context)
    }

    Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("选择文件") },
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
                if (state.files.isEmpty()) {
                    Spacer(Modifier.height(32.dp))
                    Text(
                        "从本机存储选择一个或多个文档\n\n" +
                            "支持: ${state.allowedExtensions.joinToString(" ")}\n" +
                            "单文件上限: ${state.maxFileSizeMB}MB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已选 ${state.files.size} 个文件 · 共 ${formatBytes(state.files.sumOf { it.sizeBytes.coerceAtLeast(0) })}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = viewModel::clearFiles) {
                            Icon(Icons.Filled.Delete, contentDescription = "清空")
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.files, key = { it.uriString }) { file ->
                            FileCard(file)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            launcher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.files.isEmpty()) "选择文件" else "重新选择")
                    }
                    if (state.files.isNotEmpty()) {
                        Button(
                            onClick = {
                                viewModel.runValidation()
                                onValidate()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("开始校验")
                        }
                    }
                }
            }
        }
}

@Composable
private fun FileCard(file: PickedFile) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(file.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${formatBytes(file.sizeBytes)} · ${file.mimeType ?: "未知类型"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
