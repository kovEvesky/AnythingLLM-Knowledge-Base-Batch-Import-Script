package com.anythingllm.importer.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anythingllm.importer.AnythingLLMApp
import com.anythingllm.importer.data.api.normalizeBaseUrl
import com.anythingllm.importer.data.config.AppConfig
import com.anythingllm.importer.data.config.ThemeMode
import com.anythingllm.importer.data.config.ConfigRepository
import com.anythingllm.importer.data.config.DuplicateAction
import com.anythingllm.importer.data.config.FilenamePolicy
import com.anythingllm.importer.domain.probe.ConnectionProbe
import com.anythingllm.importer.domain.probe.ProbeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel(FR-01/FR-02):
 * 加载 → 编辑 → 测试连接 → 保存,全链路。
 */
class SettingsViewModel(
    private val repository: ConfigRepository,
    private val probe: ConnectionProbe,
) : ViewModel() {

    data class UiState(
        // 基础
        val baseUrl: String = "",
        val apiKey: String = "",
        val showApiKey: Boolean = false,
        val advancedExpanded: Boolean = false,
        // 高级参数(字符串态,保存时解析)
        val allowedExtensions: String = "",
        val maxFileSizeMB: String = "100",
        val filenamePolicy: FilenamePolicy = FilenamePolicy.UNICODE,
        val detectDuplicates: Boolean = true,
        val duplicateAction: DuplicateAction = DuplicateAction.ASK,
        val defaultWorkspace: String = "",
        val uploadRetryCount: String = "2",
        val uploadRetryBaseDelay: String = "2",
        val importConcurrency: String = "2",
        val verifyTimeout: String = "300",
        val verifyBaseDelay: String = "2",
        val verifyMaxDelay: String = "5",
        val probeTimeout: String = "5",
        val uploadTimeout: String = "300",
        val embedTimeout: String = "120",
        val apiTimeout: String = "60",
        val chatTimeout: String = "300",
        val askForChatTest: Boolean = false,
        val logRetentionDays: String = "30",
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        // v1.3 FTP 同步(FR-32)
        val ftpHost: String = "",
        val ftpPort: String = "2121",
        val ftpUser: String = "sync",
        val ftpPassword: String = "sync123",
        val ftpRemoteRoot: String = "Library",
        // v1.7 收件箱范式
        val defaultFolderName: String = "",
        val silentReceive: Boolean = true,
        val hapticOnReceive: Boolean = true,
        val lastMarkFolder: String = "",
        val lastMarkWorkspace: String = "",
        // 交互
        val loaded: Boolean = false,
        val testing: Boolean = false,
        val testResult: ProbeResult? = null,
        val saving: Boolean = false,
        val saved: Boolean = false,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val cfg = repository.config.first()
            _uiState.value = UiState(
                baseUrl = cfg.baseUrl,
                apiKey = cfg.apiKey,
                allowedExtensions = cfg.allowedExtensions.joinToString(","),
                maxFileSizeMB = cfg.maxFileSizeMB.toString(),
                filenamePolicy = cfg.filenamePolicy,
                detectDuplicates = cfg.detectDuplicates,
                duplicateAction = cfg.duplicateDefaultAction,
                defaultWorkspace = cfg.defaultWorkspace,
                uploadRetryCount = cfg.uploadRetryCount.toString(),
                uploadRetryBaseDelay = cfg.uploadRetryBaseDelaySec.toString(),
                importConcurrency = cfg.importConcurrency.toString(),
                verifyTimeout = cfg.verifyTimeoutSec.toString(),
                verifyBaseDelay = cfg.verifyBaseDelaySec.toString(),
                verifyMaxDelay = cfg.verifyMaxDelaySec.toString(),
                probeTimeout = cfg.probeTimeoutSec.toString(),
                uploadTimeout = cfg.uploadTimeoutSec.toString(),
                embedTimeout = cfg.embedTimeoutSec.toString(),
                apiTimeout = cfg.apiTimeoutSec.toString(),
                chatTimeout = cfg.chatTimeoutSec.toString(),
                askForChatTest = cfg.askForChatTest,
                logRetentionDays = cfg.logRetentionDays.toString(),
                themeMode = cfg.themeMode,
                ftpHost = cfg.ftp.host,
                ftpPort = cfg.ftp.port.toString(),
                ftpUser = cfg.ftp.username,
                ftpPassword = cfg.ftp.password,
                ftpRemoteRoot = cfg.ftp.remoteRoot,
                defaultFolderName = cfg.defaultFolderName,
                silentReceive = cfg.silentReceive,
                hapticOnReceive = cfg.hapticOnReceive,
                lastMarkFolder = cfg.lastMarkFolder,
                lastMarkWorkspace = cfg.lastMarkWorkspace,
                loaded = true,
            )
        }
    }

    // ===== 输入更新 =====
    fun onBaseUrlChange(v: String) = _uiState.update { it.copy(baseUrl = v, error = null) }
    fun onApiKeyChange(v: String) = _uiState.update { it.copy(apiKey = v, error = null) }
    fun toggleShowApiKey() = _uiState.update { it.copy(showApiKey = !it.showApiKey) }
    fun toggleAdvanced() = _uiState.update { it.copy(advancedExpanded = !it.advancedExpanded) }
    fun onExtensionsChange(v: String) = _uiState.update { it.copy(allowedExtensions = v) }
    fun onMaxSizeChange(v: String) = _uiState.update { it.copy(maxFileSizeMB = v) }
    fun onPolicyChange(v: FilenamePolicy) = _uiState.update { it.copy(filenamePolicy = v) }
    fun onDetectDuplicatesChange(v: Boolean) = _uiState.update { it.copy(detectDuplicates = v) }
    fun onDuplicateActionChange(v: DuplicateAction) = _uiState.update { it.copy(duplicateAction = v) }
    fun onDefaultWorkspaceChange(v: String) = _uiState.update { it.copy(defaultWorkspace = v) }
    fun onRetryCountChange(v: String) = _uiState.update { it.copy(uploadRetryCount = v) }
    fun onRetryBaseDelayChange(v: String) = _uiState.update { it.copy(uploadRetryBaseDelay = v) }
    fun onConcurrencyChange(v: String) = _uiState.update { it.copy(importConcurrency = v) }
    fun onVerifyTimeoutChange(v: String) = _uiState.update { it.copy(verifyTimeout = v) }
    fun onVerifyBaseDelayChange(v: String) = _uiState.update { it.copy(verifyBaseDelay = v) }
    fun onVerifyMaxDelayChange(v: String) = _uiState.update { it.copy(verifyMaxDelay = v) }
    fun onProbeTimeoutChange(v: String) = _uiState.update { it.copy(probeTimeout = v) }
    fun onUploadTimeoutChange(v: String) = _uiState.update { it.copy(uploadTimeout = v) }
    fun onEmbedTimeoutChange(v: String) = _uiState.update { it.copy(embedTimeout = v) }
    fun onApiTimeoutChange(v: String) = _uiState.update { it.copy(apiTimeout = v) }
    fun onChatTimeoutChange(v: String) = _uiState.update { it.copy(chatTimeout = v) }
    fun onChatTestChange(v: Boolean) = _uiState.update { it.copy(askForChatTest = v) }
    fun onLogRetentionChange(v: String) = _uiState.update { it.copy(logRetentionDays = v) }
    fun onThemeModeChange(v: ThemeMode) = _uiState.update { it.copy(themeMode = v) }
    fun onFtpHostChange(v: String) = _uiState.update { it.copy(ftpHost = v, error = null) }
    fun onFtpPortChange(v: String) = _uiState.update { it.copy(ftpPort = v) }
    fun onFtpUserChange(v: String) = _uiState.update { it.copy(ftpUser = v) }
    fun onFtpPasswordChange(v: String) = _uiState.update { it.copy(ftpPassword = v) }
    fun onFtpRemoteRootChange(v: String) = _uiState.update { it.copy(ftpRemoteRoot = v) }
    fun onDefaultFolderNameChange(v: String) = _uiState.update { it.copy(defaultFolderName = v) }
    fun onSilentReceiveChange(v: Boolean) = _uiState.update { it.copy(silentReceive = v) }
    fun onHapticChange(v: Boolean) = _uiState.update { it.copy(hapticOnReceive = v) }

    fun consumeSaved() = _uiState.update { it.copy(saved = false) }

    /** 测试连接:探测结果不保存,仅在页面展示 */
    fun testConnection() {
        val s = _uiState.value
        if (s.testing) return
        viewModelScope.launch {
            _uiState.update { it.copy(testing = true, testResult = null) }
            runCatching {
                probe.probe(
                    baseUrl = s.baseUrl.trim(),
                    apiKey = s.apiKey.trim(),
                    probeTimeoutSec = AppConfig.parsePositiveInt(s.probeTimeout, 5, max = 60).toLong(),
                )
            }.onSuccess { result ->
                _uiState.update { it.copy(testing = false, testResult = result) }
            }.onFailure { e ->
                // 兜底:任何未预期异常都不得闪退(BUG-连接测试-01),降级为不可达提示
                _uiState.update {
                    it.copy(
                        testing = false,
                        testResult = ProbeResult.Unreachable,
                        error = "测试连接异常: ${e.message}",
                    )
                }
            }
        }
    }

    /** 保存:全部字段解析校验后落库 */
    fun save() {
        val s = _uiState.value
        viewModelScope.launch {
            val url = s.baseUrl.trim()
            if (url.isBlank()) {
                _uiState.update { it.copy(error = "服务器地址不能为空") }
                return@launch
            }
            _uiState.update { it.copy(saving = true, error = null) }
            runCatching {
                repository.save(s.toConfig())
            }.onSuccess {
                _uiState.update { it.copy(saving = false, saved = true) }
            }.onFailure { e ->
                _uiState.update { it.copy(saving = false, error = "保存失败: ${e.message}") }
            }
        }
    }

    private fun UiState.toConfig(): AppConfig = AppConfig(
        baseUrl = normalizeBaseUrl(baseUrl),
        apiKey = apiKey.trim(),
        allowedExtensions = AppConfig.parseExtensions(allowedExtensions),
        maxFileSizeMB = AppConfig.parsePositiveInt(maxFileSizeMB, 100, max = 2048),
        filenamePolicy = filenamePolicy,
        detectDuplicates = detectDuplicates,
        duplicateDefaultAction = duplicateAction,
        defaultWorkspace = defaultWorkspace.trim(),
        uploadRetryCount = AppConfig.parsePositiveInt(uploadRetryCount, 2, max = 10),
        uploadRetryBaseDelaySec = AppConfig.parsePositiveInt(uploadRetryBaseDelay, 2, max = 60),
        importConcurrency = AppConfig.parsePositiveInt(importConcurrency, 2, max = 10),
        verifyTimeoutSec = AppConfig.parsePositiveInt(verifyTimeout, 300, max = 3600),
        verifyBaseDelaySec = AppConfig.parsePositiveInt(verifyBaseDelay, 2, max = 60),
        verifyMaxDelaySec = AppConfig.parsePositiveInt(verifyMaxDelay, 5, max = 120),
        probeTimeoutSec = AppConfig.parsePositiveInt(probeTimeout, 5, max = 60),
        uploadTimeoutSec = AppConfig.parsePositiveInt(uploadTimeout, 300, max = 3600),
        embedTimeoutSec = AppConfig.parsePositiveInt(embedTimeout, 120, max = 3600),
        apiTimeoutSec = AppConfig.parsePositiveInt(apiTimeout, 60, max = 600),
        chatTimeoutSec = AppConfig.parsePositiveInt(chatTimeout, 300, max = 3600),
        askForChatTest = askForChatTest,
        logRetentionDays = AppConfig.parsePositiveInt(logRetentionDays, 30, max = 3650),
        themeMode = themeMode,
        ftp = com.anythingllm.importer.data.config.FtpConfig(
            host = ftpHost.trim(),
            port = AppConfig.parsePositiveInt(ftpPort, 2121, max = 65535),
            username = ftpUser.trim().ifEmpty { "sync" },
            password = ftpPassword,
            remoteRoot = ftpRemoteRoot.trim().ifEmpty { "Library" },
        ),
        defaultFolderName = defaultFolderName.trim(),
        silentReceive = silentReceive,
        hapticOnReceive = hapticOnReceive,
        lastMarkFolder = lastMarkFolder,
        lastMarkWorkspace = lastMarkWorkspace,
    )

    companion object {
        @Composable
        fun factory(): ViewModelProvider.Factory {
            val app = LocalContext.current.applicationContext as AnythingLLMApp
            return viewModelFactory { initializer {
                SettingsViewModel(app.configRepository, app.connectionProbe)
            } }
        }
    }
}
