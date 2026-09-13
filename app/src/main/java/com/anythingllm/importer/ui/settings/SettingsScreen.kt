package com.anythingllm.importer.ui.settings

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anythingllm.importer.AnythingLLMApp
import com.anythingllm.importer.data.config.DuplicateAction
import com.anythingllm.importer.data.config.ThemeMode
import com.anythingllm.importer.data.config.FilenamePolicy
import com.anythingllm.importer.domain.probe.ProbeResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            onBack()
        }
    }

    Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("设置") },
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
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.loaded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("正在加载配置…")
                    }
                    return@Column
                }

                // ===== 服务器地址 =====
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = viewModel::onBaseUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务器地址 (baseUrl)") },
                    placeholder = { Text("http://10.0.2.2:3001") },
                    singleLine = true,
                    supportingText = { Text("模拟器用 10.0.2.2 访问宿主机;真机填局域网 IP") },
                )

                // ===== API Key =====
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = viewModel::onApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (state.showApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = viewModel::toggleShowApiKey) {
                            Text(if (state.showApiKey) "隐藏" else "显示", style = MaterialTheme.typography.labelMedium)
                        }
                    },
                )

                // ===== 测试连接 =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = viewModel::testConnection,
                        enabled = !state.testing && state.baseUrl.isNotBlank(),
                    ) {
                        if (state.testing) {
                            CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.testing) "测试中…" else "测试连接")
                    }
                    state.testResult?.let { result ->
                        TestResultBadge(result)
                    }
                }

                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                // ===== FTP 同步(v1.3 FR-32:未安装 AnythingLLM 时的 PC 同步通道) =====
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("FTP 同步(未安装 AnythingLLM 时用)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "在 PC 上运行 tools/ftp-server 脚本后,手机把「资料库」同步到 PC 目录。手机与 PC 需在同一局域网。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = state.ftpHost,
                            onValueChange = viewModel::onFtpHostChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("FTP 主机(PC 局域网 IP)") },
                            placeholder = { Text("如 192.168.1.100") },
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.ftpPort,
                                onValueChange = viewModel::onFtpPortChange,
                                modifier = Modifier.weight(1f),
                                label = { Text("端口") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            OutlinedTextField(
                                value = state.ftpRemoteRoot,
                                onValueChange = viewModel::onFtpRemoteRootChange,
                                modifier = Modifier.weight(1f),
                                label = { Text("远端根目录") },
                                singleLine = true,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.ftpUser,
                                onValueChange = viewModel::onFtpUserChange,
                                modifier = Modifier.weight(1f),
                                label = { Text("用户名") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = state.ftpPassword,
                                onValueChange = viewModel::onFtpPasswordChange,
                                modifier = Modifier.weight(1f),
                                label = { Text("密码") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                            )
                        }
                    }
                }

                // ===== 高级参数 =====
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "高级参数",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = viewModel::toggleAdvanced) {
                                Icon(
                                    imageVector = if (state.advancedExpanded) {
                                        Icons.Filled.KeyboardArrowUp
                                    } else {
                                        Icons.Filled.KeyboardArrowDown
                                    },
                                    contentDescription = "展开/折叠",
                                )
                            }
                        }

                        if (state.advancedExpanded) {
                            Spacer(Modifier.height(8.dp))
                            Text("超时(秒)", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumberField("探活", state.probeTimeout, viewModel::onProbeTimeoutChange, Modifier.weight(1f))
                                NumberField("API", state.apiTimeout, viewModel::onApiTimeoutChange, Modifier.weight(1f))
                                NumberField("上传", state.uploadTimeout, viewModel::onUploadTimeoutChange, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumberField("嵌入", state.embedTimeout, viewModel::onEmbedTimeoutChange, Modifier.weight(1f))
                                NumberField("聊天", state.chatTimeout, viewModel::onChatTimeoutChange, Modifier.weight(1f))
                                Spacer(Modifier.weight(1f))
                            }
                            Text("嵌入验证轮询(秒)", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumberField("超时", state.verifyTimeout, viewModel::onVerifyTimeoutChange, Modifier.weight(1f))
                                NumberField("起始间隔", state.verifyBaseDelay, viewModel::onVerifyBaseDelayChange, Modifier.weight(1f))
                                NumberField("最大间隔", state.verifyMaxDelay, viewModel::onVerifyMaxDelayChange, Modifier.weight(1f))
                            }

                            Spacer(Modifier.height(8.dp))
                            Text("文件与重试", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumberField("大小上限MB", state.maxFileSizeMB, viewModel::onMaxSizeChange, Modifier.weight(1f))
                                NumberField("重试次数", state.uploadRetryCount, viewModel::onRetryCountChange, Modifier.weight(1f))
                                NumberField("重试退避s", state.uploadRetryBaseDelay, viewModel::onRetryBaseDelayChange, Modifier.weight(1f))
                                NumberField("并发上传", state.importConcurrency, viewModel::onConcurrencyChange, Modifier.weight(1f))
                            }
                            OutlinedTextField(
                                value = state.allowedExtensions,
                                onValueChange = viewModel::onExtensionsChange,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("允许的扩展名(逗号分隔)") },
                                singleLine = true,
                            )

                            Spacer(Modifier.height(8.dp))
                            Text("文件名与重复", style = MaterialTheme.typography.labelLarge)
                            EnumDropdown(
                                label = "文件名策略",
                                value = state.filenamePolicy,
                                options = FilenamePolicy.entries,
                                display = { it.displayName() },
                                onSelect = viewModel::onPolicyChange,
                            )
                            SwitchRow(
                                title = "重复检测",
                                subtitle = "按原始文件名比对服务端已存文档",
                                checked = state.detectDuplicates,
                                onChange = viewModel::onDetectDuplicatesChange,
                            )
                            if (state.detectDuplicates) {
                                EnumDropdown(
                                    label = "重复默认动作",
                                    value = state.duplicateAction,
                                    options = DuplicateAction.entries,
                                    display = { it.displayName() },
                                    onSelect = viewModel::onDuplicateActionChange,
                                )
                            }

                            Spacer(Modifier.height(8.dp))
                            Text("其他", style = MaterialTheme.typography.labelLarge)
                            SwitchRow(
                                title = "导入后测试检索",
                                subtitle = "可选:完成后向工作区发 query 验证可检索",
                                checked = state.askForChatTest,
                                onChange = viewModel::onChatTestChange,
                            )
                            NumberField("日志保留天数", state.logRetentionDays, viewModel::onLogRetentionChange, Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text("外观", style = MaterialTheme.typography.labelLarge)
                            EnumDropdown(
                                label = "主题模式",
                                value = state.themeMode,
                                options = ThemeMode.entries,
                                display = { it.displayName() },
                                onSelect = viewModel::onThemeModeChange,
                            )
                        }
                    }
                }

                // ===== 保存 =====
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.saving && state.loaded,
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.saving) "保存中…" else "保存并返回")
                }
            }
        }
}

@Composable
private fun TestResultBadge(result: ProbeResult) {
    val (text, color) = when (result) {
        is ProbeResult.Ok -> "连接正常 · ${result.workspaceCount} 个工作区" to
            MaterialTheme.colorScheme.primary
        ProbeResult.Unreachable -> "无法连接服务器,请检查地址与网络" to
            MaterialTheme.colorScheme.error
        ProbeResult.InvalidKey -> "API Key 无效" to MaterialTheme.colorScheme.error
        is ProbeResult.ServerError -> result.detail to MaterialTheme.colorScheme.error
    }
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.all { it.isDigit() } || v.isEmpty()) onChange(v) },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    label: String,
    value: T,
    options: List<T>,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = display(value),
            onValueChange = {},
            readOnly = true,
            modifier = modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun FilenamePolicy.displayName(): String = when (this) {
    FilenamePolicy.UNICODE -> "unicode (中文转 uXXXX)"
    FilenamePolicy.STRIP -> "strip (丢弃非 ASCII)"
    FilenamePolicy.KEEP -> "keep (原样保留)"
}

private fun DuplicateAction.displayName(): String = when (this) {
    DuplicateAction.ASK -> "询问"
    DuplicateAction.KEEP -> "全部保留"
    DuplicateAction.SKIP -> "跳过"
    DuplicateAction.REPLACE -> "替换"
    DuplicateAction.ABORT -> "中止"
}

private fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}
