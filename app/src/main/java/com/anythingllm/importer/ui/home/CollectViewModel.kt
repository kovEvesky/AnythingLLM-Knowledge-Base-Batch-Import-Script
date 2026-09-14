package com.anythingllm.importer.ui.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anythingllm.importer.AnythingLLMApp
import com.anythingllm.importer.SyncForegroundService
import com.anythingllm.importer.data.api.AnythingLlmClientFactory
import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.CollectRepository
import com.anythingllm.importer.data.collect.EntryStatus
import com.anythingllm.importer.data.collect.EntryType
import com.anythingllm.importer.data.collect.KnowledgeSnapshot
import com.anythingllm.importer.data.collect.SnapshotDiff
import com.anythingllm.importer.data.collect.SnapshotFolder
import com.anythingllm.importer.data.collect.SnapshotRepository
import com.anythingllm.importer.data.collect.SnapshotWorkspace
import com.anythingllm.importer.data.config.ConfigRepository
import com.anythingllm.importer.data.config.FtpConfig
import com.anythingllm.importer.data.library.LibraryEntry
import com.anythingllm.importer.data.library.LibraryEntryType
import com.anythingllm.importer.data.library.LibraryFolder
import com.anythingllm.importer.data.library.LibraryRepository
import com.anythingllm.importer.domain.ftp.FtpSyncEngine
import com.anythingllm.importer.domain.ftp.FtpSyncState
import com.anythingllm.importer.domain.sync.SyncItemState
import com.anythingllm.importer.domain.sync.SyncState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 双主页共享 ViewModel(v1.2 FR-18/20/22/23):
 * - 收集箱:待整理条目(按日分组) + 勾选 + 标记(文件夹+工作区,候选来自快照);
 * - 知识库:快照结构清单 + 已标记计划 + 连接刷新快照(比对)+ 一键同步(前台服务);
 * - 标记后条目从 pending 移入 marked(Tab1→Tab2)。
 */
class CollectViewModel(
    private val context: Context,
    private val collectRepository: CollectRepository,
    private val snapshotRepository: SnapshotRepository,
    private val configRepository: ConfigRepository,
    private val apiFactory: AnythingLlmClientFactory,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    data class UiState(
        val pendingGroups: List<Pair<String, List<CollectEntry>>> = emptyList(),
        val markedEntries: List<CollectEntry> = emptyList(),
        val executedEntries: List<CollectEntry> = emptyList(),
        val snapshot: KnowledgeSnapshot = KnowledgeSnapshot(),
        val snapshotDiff: SnapshotDiff? = null,
        val selectedIds: Set<String> = emptySet(),
        val refreshingSnapshot: Boolean = false,
        val snapshotError: String? = null,
        val sync: SyncState = SyncState(),
        val syncDone: Boolean = false,
        // v1.3 资料库(FR-30/31/33)
        val libraryCurrentFolderId: String? = null,
        val librarySubFolders: List<LibraryFolder> = emptyList(),
        val libraryEntries: List<LibraryEntry> = emptyList(),
        val libraryPathChain: List<String> = emptyList(),
        val libraryAllFolders: List<LibraryFolder> = emptyList(),
        val libraryAllEntries: List<LibraryEntry> = emptyList(),
        val ftp: FtpConfig = FtpConfig(),
        val ftpSync: FtpSyncState = FtpSyncState(),
        val ftpSyncDone: Boolean = false,
        // v1.7 收件箱范式:零决策默认值
        val defaultFolderName: String = "",
        val defaultWorkspace: String = "",
        val lastMarkFolder: String = "",
        val lastMarkWorkspace: String = "",
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
        // v1.3:实时跟随 FTP 配置(设置页保存后资料库横幅/同步立即生效)
        viewModelScope.launch {
            configRepository.config.collect { cfg ->
                _uiState.update {
                    it.copy(
                        ftp = cfg.ftp,
                        defaultFolderName = cfg.defaultFolderName,
                        defaultWorkspace = cfg.defaultWorkspace,
                        lastMarkFolder = cfg.lastMarkFolder,
                        lastMarkWorkspace = cfg.lastMarkWorkspace,
                    )
                }
            }
        }
    }

    /** 从本地仓储重读(条目 + 快照 + 资料库);分享接收后/返回主页时调用 */
    fun refresh() {
        val snapshot = snapshotRepository.read()
        val all = collectRepository.all()
        _uiState.update {
            it.copy(
                pendingGroups = collectRepository.groupByDay(collectRepository.pending()),
                markedEntries = collectRepository.marked(),
                executedEntries = all.filter { e -> e.isTerminal },
                snapshot = snapshot,
                selectedIds = it.selectedIds intersect all.map { e -> e.id }.toSet(),
            )
        }
        refreshLibrary()
    }

    // ===== 资料库(v1.3 FR-30/31):目录浏览与管理 =====

    /** 重读当前目录内容与全量文件夹(移动目标选择用) */
    fun refreshLibrary() {
        val current = _uiState.value.libraryCurrentFolderId
        val (folders, entries) = libraryRepository.childrenOf(current)
        _uiState.update {
            it.copy(
                librarySubFolders = folders,
                libraryEntries = entries,
                libraryPathChain = libraryRepository.pathOf(current),
                libraryAllFolders = libraryRepository.folders(),
                libraryAllEntries = libraryRepository.entries(),
            )
        }
    }

    fun libraryEnterFolder(id: String) {
        _uiState.update { it.copy(libraryCurrentFolderId = id) }
        refreshLibrary()
    }

    fun libraryGoUp() {
        val current = _uiState.value.libraryCurrentFolderId ?: return
        val folder = _uiState.value.libraryAllFolders.firstOrNull { it.id == current } ?: return
        _uiState.update { it.copy(libraryCurrentFolderId = folder.parentId) }
        refreshLibrary()
    }

    fun libraryGoRoot() {
        _uiState.update { it.copy(libraryCurrentFolderId = null) }
        refreshLibrary()
    }

    fun libraryCreateFolder(name: String) {
        libraryRepository.createFolder(name, _uiState.value.libraryCurrentFolderId)
        refreshLibrary()
    }

    fun libraryRenameFolder(id: String, newName: String) {
        libraryRepository.renameFolder(id, newName)
        refreshLibrary()
    }

    fun libraryDeleteFolder(id: String) {
        val folder = libraryRepository.folders().firstOrNull { it.id == id }
        libraryRepository.deleteFolder(id)
        // 当前浏览目录被删 → 回到其父级
        if (_uiState.value.libraryCurrentFolderId == id) {
            _uiState.update { it.copy(libraryCurrentFolderId = folder?.parentId) }
        }
        refreshLibrary()
    }

    fun libraryMoveEntry(id: String, folderId: String?) {
        libraryRepository.moveEntry(id, folderId)
        refreshLibrary()
    }

    fun libraryDeleteEntry(id: String) {
        libraryRepository.deleteEntry(id)
        refreshLibrary()
    }

    // ===== 资料库(v1.3):收集箱 → 归档 =====

    /** 勾选的收集箱条目归档到资料库指定文件夹(移动语义:文件副本移入,收集箱条目删除) */
    fun archiveSelectedToLibrary(folderId: String?) {
        val ids = _uiState.value.selectedIds
        if (ids.isEmpty()) return
        ids.forEach { id ->
            val entry = collectRepository.all().firstOrNull { it.id == id } ?: return@forEach
            when (entry.type) {
                EntryType.FILE -> libraryRepository.archiveFromCollect(
                    id = entry.id,
                    type = LibraryEntryType.FILE,
                    title = entry.fileName ?: entry.id,
                    url = null,
                    srcFile = entry.localPath?.let { File(it) },
                    sizeBytes = entry.sizeBytes,
                    destFolderId = folderId,
                )
                EntryType.LINK -> libraryRepository.archiveFromCollect(
                    id = entry.id,
                    type = LibraryEntryType.LINK,
                    title = entry.displayTitle,
                    url = entry.url,
                    srcFile = null,
                    sizeBytes = 0,
                    destFolderId = folderId,
                )
            }
            collectRepository.remove(entry.id)
        }
        refresh()
    }

    // ===== 资料库(v1.3 FR-33):FTP 同步到 PC =====

    /** 一键 FTP 同步:加载配置 → 引擎执行(未同步/变更条目)→ 回写同步状态 */
    fun syncFtpNow() {
        if (_uiState.value.ftpSync.running) return
        viewModelScope.launch {
            val cfg = configRepository.snapshot()
            _uiState.update { it.copy(ftp = cfg.ftp, ftpSyncDone = false) }
            val engine = FtpSyncEngine(cfg.ftp, libraryRepository)
            engine.launch(viewModelScope)
            _uiState.update { it.copy(ftpSync = engine.state.value) }
            while (engine.state.value.running) {
                _uiState.update { it.copy(ftpSync = engine.state.value) }
                delay(600)
            }
            _uiState.update { it.copy(ftpSync = engine.state.value, ftpSyncDone = true) }
            refreshLibrary()
        }
    }

    // ===== 勾选 =====

    fun toggleSelected(id: String) {
        _uiState.update {
            val sel = it.selectedIds.toMutableSet()
            if (!sel.add(id)) sel.remove(id)
            it.copy(selectedIds = sel)
        }
    }

    fun toggleSelectAll() {
        _uiState.update {
            val all = collectRepository.pending().map { e -> e.id }.toSet()
            it.copy(selectedIds = if (it.selectedIds == all) emptySet() else all)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    // ===== 标记(FR-22):勾选条目 → 目标 folder(+workspace) =====

    fun markSelected(folder: String, workspace: String?) {
        val ids = _uiState.value.selectedIds
        if (ids.isEmpty()) return
        ids.forEach { id ->
            collectRepository.update(id) { e ->
                e.copy(
                    status = EntryStatus.MARKED,
                    markFolder = folder,
                    markWorkspace = workspace,
                )
            }
        }
        refresh()
    }

    /** 取消标记(从已标记区退回待整理) */
    fun unmark(entryId: String) {
        collectRepository.update(entryId) { e ->
            e.copy(
                status = EntryStatus.PENDING,
                markFolder = null,
                markWorkspace = null,
            )
        }
        refresh()
    }

    // ===== v1.7 收件箱范式:单条零决策入箱 =====

    /** 单条标记到指定文件夹+工作区,并记住为"上次夹";返回是否成功 */
    fun markOneEntry(entryId: String, folder: String, workspace: String?): Boolean {
        if (folder.isBlank()) return false
        collectRepository.update(entryId) { e ->
            e.copy(status = EntryStatus.MARKED, markFolder = folder, markWorkspace = workspace)
        }
        viewModelScope.launch { configRepository.rememberLastMark(folder, workspace) }
        refresh()
        return true
    }

    /** 左滑/点按钮:单条入默认箱;默认未设置时返回 false(UI 引导去设置) */
    fun markEntryToDefault(entryId: String): Boolean {
        val s = _uiState.value
        val folder = s.defaultFolderName
        if (folder.isBlank()) return false
        return markOneEntry(entryId, folder, s.defaultWorkspace.ifBlank { null })
    }

    /** 右滑:单条入"上次夹";上次夹未用过则回退到默认箱 */
    fun markEntryToLast(entryId: String): Boolean {
        val s = _uiState.value
        val folder = s.lastMarkFolder.ifBlank { s.defaultFolderName }
        if (folder.isBlank()) return false
        val ws = s.lastMarkWorkspace.ifBlank { s.defaultWorkspace }
        return markOneEntry(entryId, folder, ws.ifBlank { null })
    }

    /** 顶部"全部入默认箱";返回实际入箱条数(默认未设置返回 0) */
    fun markAllToDefault(): Int {
        val s = _uiState.value
        val folder = s.defaultFolderName
        if (folder.isBlank()) return 0
        val ws = s.defaultWorkspace.ifBlank { null }
        val pending = collectRepository.pending()
        pending.forEach { e ->
            collectRepository.update(e.id) {
                it.copy(status = EntryStatus.MARKED, markFolder = folder, markWorkspace = ws)
            }
        }
        if (pending.isNotEmpty()) {
            viewModelScope.launch { configRepository.rememberLastMark(folder, ws) }
        }
        refresh()
        return pending.size
    }

    // ===== 删除 =====

    fun deleteSelected() {
        val ids = _uiState.value.selectedIds
        if (ids.isEmpty()) return
        collectRepository.removeAll(ids.toList())
        refresh()
    }

    fun deleteEntry(entryId: String) {
        collectRepository.remove(entryId)
        refresh()
    }

    // ===== 连接刷新快照(FR-20/21) =====

    fun refreshSnapshot() {
        viewModelScope.launch {
            val cfg = configRepository.snapshot()
            if (cfg.baseUrl.isBlank() || cfg.apiKey.isBlank()) {
                _uiState.update { it.copy(snapshotError = "尚未配置服务器,请先到设置页配置") }
                return@launch
            }
            _uiState.update { it.copy(refreshingSnapshot = true, snapshotError = null) }
            try {
                val api = apiFactory.create(cfg.baseUrl, cfg.apiKey, readTimeoutSec = cfg.apiTimeoutSec.toLong())
                val folders = api.documents().localFiles?.items.orEmpty()
                    .filter { it.type == "folder" }
                    .map { SnapshotFolder(it.name) }
                    .sortedBy { f -> f.name }
                val workspaces = api.workspaces().workspaces
                    .map { SnapshotWorkspace(it.slug, it.name) }
                    .sortedBy { w -> w.slug }
                val old = snapshotRepository.read()
                val now = KnowledgeSnapshot(
                    snapshotTime = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    folders = folders,
                    workspaces = workspaces,
                )
                snapshotRepository.write(now)
                val diff = snapshotRepository.diff(now, old)
                _uiState.update {
                    it.copy(
                        snapshot = now,
                        snapshotDiff = diff,
                        refreshingSnapshot = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        refreshingSnapshot = false,
                        snapshotError = "连接服务器失败:${e.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    // ===== 一键连接并同步嵌入(FR-24) =====

    /**
     * 连接 → 刷新快照(比对) → 启动前台同步服务(防杀+通知)批量执行已标记计划。
     * 服务执行完成回写 EXECUTED/FAILED,文件原件自动清理(FR-26);
     * 本方法随后轮询仓储直至执行完成(UI 进度展示)。
     */
    fun syncNow() {
        if (_uiState.value.sync.running) return
        viewModelScope.launch {
            val cfg = configRepository.snapshot()
            if (cfg.baseUrl.isBlank() || cfg.apiKey.isBlank()) {
                _uiState.update { it.copy(snapshotError = "尚未配置服务器,请先到设置页配置") }
                return@launch
            }
            _uiState.update { it.copy(refreshingSnapshot = true, snapshotError = null) }
            try {
                val api = apiFactory.create(cfg.baseUrl, cfg.apiKey, readTimeoutSec = cfg.apiTimeoutSec.toLong())
                // 1. 刷新快照 + 比对
                val folders = api.documents().localFiles?.items.orEmpty()
                    .filter { it.type == "folder" }
                    .map { SnapshotFolder(it.name) }
                    .sortedBy { f -> f.name }
                val workspaces = api.workspaces().workspaces
                    .map { SnapshotWorkspace(it.slug, it.name) }
                    .sortedBy { w -> w.slug }
                val old = snapshotRepository.read()
                val now = KnowledgeSnapshot(
                    snapshotTime = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    folders = folders,
                    workspaces = workspaces,
                )
                snapshotRepository.write(now)
                val diff = snapshotRepository.diff(now, old)

                // 2. 收集已标记待执行条目
                val marked = collectRepository.marked()
                _uiState.update {
                    it.copy(snapshot = now, snapshotDiff = diff, refreshingSnapshot = false)
                }

                // 2.5 目标失效预检(R15):目标工作区已被服务器删除 → 回写 FAILED + 失效提示,不执行;
                //     目标文件夹缺失由服务器执行时自动重建(不阻断)
                val validWorkspaces = now.workspaces.map { it.slug }.toSet()
                marked.filter { e ->
                    !e.markWorkspace.isNullOrBlank() && e.markWorkspace !in validWorkspaces
                }.forEach { e ->
                    collectRepository.update(e.id) {
                        it.copy(
                            status = EntryStatus.FAILED,
                            error = "目标工作区已删除:${e.markWorkspace},请取消标记后重新选择",
                        )
                    }
                }
                val executable = collectRepository.marked().filter { it.status == EntryStatus.MARKED }
                if (executable.isEmpty()) {
                    _uiState.update { it.copy(sync = SyncState(running = false), syncDone = true) }
                    return@launch
                }

                // 3. 启动前台同步服务(防杀+进度通知);UI 轮询仓储直至完成
                SyncForegroundService.start(context)
                _uiState.update { it.copy(sync = SyncState(running = true, items = executable.map { e ->
                    com.anythingllm.importer.domain.sync.SyncItemState(e)
                })) }
                pollUntilSyncDone()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        refreshingSnapshot = false,
                        snapshotError = "连接服务器失败:${e.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    /** 轮询仓储直至已标记条目全部离开队列(执行完成/失败/被取消) */
    private suspend fun pollUntilSyncDone() {
        while (true) {
            delay(2000)
            refresh()
            val remaining = collectRepository.marked().filter { it.status == EntryStatus.MARKED }
            if (remaining.isEmpty()) {
                _uiState.update { it.copy(sync = SyncState(running = false), syncDone = true) }
                return
            }
        }
    }

    companion object {
        @Composable
        fun factory(): ViewModelProvider.Factory {
            val app = LocalContext.current.applicationContext as AnythingLLMApp
            return viewModelFactory { initializer {
                CollectViewModel(
                    context = app,
                    collectRepository = app.collectRepository,
                    snapshotRepository = app.snapshotRepository,
                    configRepository = app.configRepository,
                    apiFactory = app.apiFactory,
                    libraryRepository = app.libraryRepository,
                )
            } }
        }
    }
}
