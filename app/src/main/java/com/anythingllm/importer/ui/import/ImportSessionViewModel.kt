package com.anythingllm.importer.ui.import

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anythingllm.importer.AnythingLLMApp
import com.anythingllm.importer.data.api.dto.CreateFolderRequest
import com.anythingllm.importer.data.api.dto.WorkspaceDto
import com.anythingllm.importer.data.config.AppConfig
import com.anythingllm.importer.data.import.UriFileBodyProvider
import com.anythingllm.importer.data.log.ImportLogEntry
import com.anythingllm.importer.domain.error.toUserMessage
import com.anythingllm.importer.domain.import.DuplicateAnswer
import com.anythingllm.importer.domain.import.DuplicateQuestion
import com.anythingllm.importer.domain.import.ImportEngine
import com.anythingllm.importer.domain.import.ImportMode
import com.anythingllm.importer.domain.import.ImportRunState
import com.anythingllm.importer.domain.import.ImportTarget
import com.anythingllm.importer.domain.import.ItemStatus
import com.anythingllm.importer.domain.validate.PassedFile
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 导入会话 ViewModel(阶段 3):
 * 目标选择(统一/逐项)→ 启动导入引擎 → 进度/重复提问/暂停取消。
 * 与选择/校验页共享 SELECT 路由的 backStackEntry 作用域。
 */
class ImportSessionViewModel(
    private val app: AnythingLLMApp,
    private val passedFiles: List<PassedFile>,
) : ViewModel() {

    data class UiState(
        val loaded: Boolean = false,
        val loadError: String? = null,
        val folders: List<String> = emptyList(),
        val workspaces: List<WorkspaceDto> = emptyList(),
        val mode: ImportMode = ImportMode.UNIFIED,
        val unifiedFolder: String = "custom-documents",
        val unifiedWorkspace: String = "",
        val workspaceFilter: String = "",
        // uriString -> (folder, workspaceSlug);逐项模式
        val perItem: Map<String, Pair<String, String>> = emptyMap(),
        // 导入运行
        val runState: ImportRunState? = null,
        val starting: Boolean = false,
        val error: String? = null,
        // 新建文件夹对话框
        val showCreateFolder: Boolean = false,
        val createFolderName: String = "",
        val createFolderError: String? = null,
        // 退出确认
        val showExitConfirm: Boolean = false,
    ) {
        val running: Boolean
            get() = runState?.isTerminalPhase == false
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val duplicateAnswers = Channel<DuplicateAnswer>(Channel.UNLIMITED)
    private var engine: ImportEngine? = null

    /** 日志去重:index -> 已写终态指纹(status|error|location|retries) */
    private val logWritten = mutableMapOf<Int, String>()

    /** 日志动作判定:记录每项上一次终态,失败→成功记为 retry */
    private val lastTerminalStatus = mutableMapOf<Int, ItemStatus>()

    fun init() {
        if (_uiState.value.loaded) return
        viewModelScope.launch {
            val cfg = repository.snapshot()
            val api = buildApi(cfg)
            runCatching {
                val workspaces = api.workspaces().workspaces
                val folders = api.documents().localFiles?.items.orEmpty()
                    .filter { it.type == "folder" }
                    .map { it.name }
                    .ifEmpty { listOf("custom-documents") }
                val defaultWs = workspaces.firstOrNull {
                    it.slug == cfg.defaultWorkspace || it.name == cfg.defaultWorkspace
                } ?: workspaces.firstOrNull()
                _uiState.update {
                    it.copy(
                        loaded = true,
                        folders = folders,
                        workspaces = workspaces,
                        unifiedFolder = if ("custom-documents" in folders) "custom-documents" else folders.first(),
                        unifiedWorkspace = defaultWs?.slug ?: "",
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(loadError = e.toUserMessage()) }
            }
        }
    }

    // ===== 目标选择 =====

    fun setMode(mode: ImportMode) = _uiState.update { it.copy(mode = mode) }
    fun setUnifiedFolder(folder: String) = _uiState.update { it.copy(unifiedFolder = folder) }
    fun setUnifiedWorkspace(slug: String) = _uiState.update { it.copy(unifiedWorkspace = slug) }
    fun setWorkspaceFilter(v: String) = _uiState.update { it.copy(workspaceFilter = v) }

    fun setPerItemFolder(uriString: String, folder: String) = _uiState.update {
        val cur = it.perItem[uriString] ?: (it.unifiedFolder to it.unifiedWorkspace)
        it.copy(perItem = it.perItem + (uriString to (folder to cur.second)))
    }

    fun setPerItemWorkspace(uriString: String, slug: String) = _uiState.update {
        val cur = it.perItem[uriString] ?: (it.unifiedFolder to it.unifiedWorkspace)
        it.copy(perItem = it.perItem + (uriString to (cur.first to slug)))
    }

    fun workspaceNameOf(slug: String): String {
        val s = _uiState.value
        return s.workspaces.firstOrNull { it.slug == slug }?.name ?: slug
    }

    // ===== 新建文件夹 =====

    fun showCreateFolderDialog() = _uiState.update {
        it.copy(showCreateFolder = true, createFolderName = "", createFolderError = null)
    }
    fun onCreateFolderNameChange(v: String) = _uiState.update { it.copy(createFolderName = v) }
    fun dismissCreateFolder() = _uiState.update { it.copy(showCreateFolder = false) }

    fun createFolder() {
        val name = _uiState.value.createFolderName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(createFolderError = "文件夹名不能为空") }
            return
        }
        viewModelScope.launch {
            val cfg = repository.snapshot()
            val api = buildApi(cfg)
            runCatching {
                val resp = api.createFolder(CreateFolderRequest(name))
                if (!resp.success) throw IOException(resp.message ?: "创建文件夹失败")
                _uiState.update {
                    it.copy(
                        showCreateFolder = false,
                        createFolderError = null,
                        folders = it.folders + name,
                        unifiedFolder = name,
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(createFolderError = e.toUserMessage()) }
            }
        }
    }

    // ===== 启动 / 控制 =====

    fun startImport() {
        val s = _uiState.value
        if (s.running || s.starting) return
        viewModelScope.launch {
            _uiState.update { it.copy(starting = true, error = null) }
            val cfg = repository.snapshot()
            val targets = buildTargets(s, cfg)
            if (targets == null) {
                _uiState.update { it.copy(starting = false) }
                return@launch
            }
            val eng = ImportEngine(buildApi(cfg), UriFileBodyProvider(app.contentResolver), cfg)
            engine = eng
            eng.launch(viewModelScope, targets, ::askDuplicate)
            viewModelScope.launch {
                // 收集引擎状态时保留 VM 侧的 duplicateQuestion(引擎状态本身恒为 null,
                // 直接整体覆盖会把 askDuplicate 刚写入的问询状态冲掉——已实测复现)
                eng.state.collect { run ->
                    writeImportLogIfNeeded(run)
                    _uiState.update {
                        it.copy(runState = run.copy(duplicateQuestion = it.runState?.duplicateQuestion))
                    }
                }
            }
            _uiState.update { it.copy(starting = false) }
        }
    }

    private fun buildTargets(s: UiState, cfg: AppConfig): List<ImportTarget>? {
        if (s.unifiedWorkspace.isBlank()) {
            _uiState.update { it.copy(error = "请选择目标工作区") }
            return null
        }
        val targets = passedFiles.map { pf ->
            val (folder, ws) = if (s.mode == ImportMode.UNIFIED) {
                s.unifiedFolder to s.unifiedWorkspace
            } else {
                s.perItem[pf.file.uriString] ?: (s.unifiedFolder to s.unifiedWorkspace)
            }
            ImportTarget(
                uriString = pf.file.uriString,
                displayName = pf.file.displayName,
                storageName = pf.storageName,
                sizeBytes = pf.file.sizeBytes,
                folder = folder,
                workspaceSlug = ws,
            )
        }
        if (targets.any { it.folder.isBlank() || it.workspaceSlug.isBlank() }) {
            _uiState.update { it.copy(error = "逐项模式:请为每个文件选择文件夹与工作区") }
            return null
        }
        return targets
    }

    fun pauseToggle() {
        val s = _uiState.value
        engine?.setPaused(s.runState?.phase != com.anythingllm.importer.domain.import.RunPhase.PAUSED)
    }

    fun cancel() {
        engine?.cancel()
        _uiState.update { it.copy(showExitConfirm = false) }
    }

    // ===== 失败重试(阶段 4 FR-12) =====

    /** 重试全部失败项 */
    fun retryFailed() {
        val run = _uiState.value.runState ?: return
        val indexes = run.items.mapIndexedNotNull { i, it ->
            if (it.status == ItemStatus.FAILED) i else null
        }
        if (indexes.isNotEmpty()) {
            indexes.forEach(logWritten::remove)
            engine?.retryItems(viewModelScope, indexes, ::askDuplicate)
        }
    }

    /** 重试单个失败项 */
    fun retryItem(index: Int) {
        logWritten.remove(index)
        engine?.retryItems(viewModelScope, listOf(index), ::askDuplicate)
    }

    // ===== JSONL 日志(阶段 4 FR-13) =====

    /**
     * 引擎状态收集时对每项"终态变化"追加一行日志(指纹去重,保证一次终态只写一行;
     * 重试后再次终态会因指纹变化写入新行)。动作:失败→成功记 retry;跳过记 skip;其余 add。
     */
    private fun writeImportLogIfNeeded(run: ImportRunState) {
        run.items.forEachIndexed { i, item ->
            if (!item.isTerminal) return@forEachIndexed
            val fp = "${item.status}|${item.error}|${item.location}|${item.retryCount}"
            if (logWritten[i] == fp) {
                lastTerminalStatus[i] = item.status
                return@forEachIndexed
            }
            val prev = lastTerminalStatus[i]
            // 上次终态为失败 → 本次为"重试事件"(无论成败);跳过记 skip;其余 add
            val action = when {
                prev == ItemStatus.FAILED -> "retry"
                item.status == ItemStatus.SKIPPED -> "skip"
                else -> "add"
            }
            app.importLogRepository.append(
                ImportLogEntry(
                    time = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    action = action,
                    file = item.target.displayName,
                    folder = item.target.folder,
                    workspace = item.target.workspaceSlug,
                    storageName = item.target.storageName,
                    sizeBytes = item.target.sizeBytes,
                    result = when (item.status) {
                        ItemStatus.SUCCESS -> "success"
                        ItemStatus.FAILED -> "failed"
                        ItemStatus.SKIPPED -> "skipped"
                        else -> "cancelled"
                    },
                    error = item.error,
                    retries = item.retryCount,
                ),
            )
            logWritten[i] = fp
            lastTerminalStatus[i] = item.status
        }
    }

    fun answerDuplicate(action: com.anythingllm.importer.data.config.DuplicateAction, applyToAll: Boolean) {
        duplicateAnswers.trySend(DuplicateAnswer(action, applyToAll))
        _uiState.update { it.copy(runState = it.runState?.copy(duplicateQuestion = null)) }
    }

    fun showExitConfirm() = _uiState.update { it.copy(showExitConfirm = true) }
    fun dismissExitConfirm() = _uiState.update { it.copy(showExitConfirm = false) }

    private suspend fun askDuplicate(q: DuplicateQuestion): DuplicateAnswer {
        _uiState.update { it.copy(runState = it.runState?.copy(duplicateQuestion = q)) }
        return duplicateAnswers.receive()
    }

    private val repository get() = app.configRepository

    private fun buildApi(cfg: AppConfig) = app.apiFactory.create(
        baseUrl = cfg.baseUrl,
        apiKey = cfg.apiKey,
        connectTimeoutSec = cfg.probeTimeoutSec.toLong(),
        readTimeoutSec = cfg.uploadTimeoutSec.toLong(),
        writeTimeoutSec = cfg.uploadTimeoutSec.toLong(),
    )

    companion object {
        @Composable
        fun factory(passedFiles: List<PassedFile>): ViewModelProvider.Factory {
            val app = LocalContext.current.applicationContext as AnythingLLMApp
            return viewModelFactory { initializer {
                ImportSessionViewModel(app, passedFiles)
            } }
        }
    }
}
