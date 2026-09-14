package com.anythingllm.importer.domain.sync

import com.anythingllm.importer.data.api.AnythingLLMApi
import com.anythingllm.importer.data.api.UploadBodyFactory
import com.anythingllm.importer.data.api.dto.CreateFolderRequest
import com.anythingllm.importer.data.api.dto.CreateWorkspaceRequest
import com.anythingllm.importer.data.api.dto.UploadLinkRequest
import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.CollectRepository
import com.anythingllm.importer.data.collect.EntryStatus
import com.anythingllm.importer.data.collect.EntryType
import com.anythingllm.importer.data.config.AppConfig
import com.anythingllm.importer.data.config.DuplicateAction
import com.anythingllm.importer.data.favorite.FavoriteRepository
import com.anythingllm.importer.domain.error.toImportErrorMessage
import com.anythingllm.importer.domain.filename.ConvertToStorageName
import com.anythingllm.importer.domain.import.DuplicateAnswer
import com.anythingllm.importer.domain.import.DuplicateQuestion
import com.anythingllm.importer.domain.import.FileBodyProvider
import com.anythingllm.importer.domain.import.ImportEngine
import com.anythingllm.importer.domain.import.ImportTarget
import com.anythingllm.importer.domain.import.ItemStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 同步条目运行状态(FR-24/25) */
enum class SyncItemStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

data class SyncItemState(
    val entry: CollectEntry,
    val status: SyncItemStatus = SyncItemStatus.PENDING,
    val message: String? = null,
    val serverTitle: String? = null,
    val serverLocation: String? = null,
) {
    val isTerminal: Boolean
        get() = status == SyncItemStatus.SUCCESS ||
            status == SyncItemStatus.FAILED ||
            status == SyncItemStatus.SKIPPED
}

data class SyncState(
    val running: Boolean = false,
    val items: List<SyncItemState> = emptyList(),
) {
    val successCount get() = items.count { it.status == SyncItemStatus.SUCCESS }
    val failedCount get() = items.count { it.status == SyncItemStatus.FAILED }
    val skippedCount get() = items.count { it.status == SyncItemStatus.SKIPPED }
    val doneCount get() = items.count { it.isTerminal }
    val totalCount get() = items.size
    val isTerminal get() = !running && items.isNotEmpty() && doneCount == totalCount
}

/**
 * 一键同步执行引擎(v1.2 FR-24/25/26 + v1.9 收藏夹驱动改造):
 * - 输入:已归类灰卡条目(MARKED;FAILED 传入可重试;EXECUTED 自动跳过=断点续传);
 * - v1.9 ensure 收藏夹:对每个 markFolderId 收藏夹自动 ensure 同名服务器文件夹 + 同名工作区(§4.9 通道 B),
 *   工作区 slug 回填 favoriteRepository(serverWorkspaceSlug);
 * - 文件+工作区 → 复用 ImportEngine 管线(查重→上传→嵌入→轮询验证→替换重试);
 * - 文件+无工作区(同步模式) → 纯上传 `document/upload/{folder}`(重试+重复跳过);
 * - 链接 → `upload-link` 服务器抓取(并发 2),响应回填 title/location;
 * - 完成后回写条目状态(EXECUTED/FAILED)+ 清理执行成功的文件原件(FR-26)。
 */
class SyncEngine(
    private val api: AnythingLLMApi,
    private val config: AppConfig,
    private val bodyProvider: FileBodyProvider,
    private val collectRepository: CollectRepository,
    private val favoriteRepository: FavoriteRepository,
) {

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private var job: Job? = null
    private var engineScope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob())
    private val linkSemaphore = Semaphore(2)

    fun launch(scope: CoroutineScope, entries: List<CollectEntry>) {
        // 断点续传:仅处理未成功条目
        val targets = entries.filter { it.status != EntryStatus.EXECUTED }
        engineScope = scope
        _state.value = SyncState(running = true, items = targets.map { SyncItemState(it) })
        job = scope.launch {
            try {
                // v1.9 ensure 收藏夹 → 服务器文件夹/工作区(§4.9 通道 B)
                ensureFolders(targets)
                coroutineScope {
                    launch { runEngineBatch(targets) }
                    launch { runSyncAndLinkBatch(targets) }
                }
                writeBack(targets)
                collectRepository.cleanupExecutedFiles()
                _state.update { it.copy(running = false) }
            } catch (e: CancellationException) {
                _state.update { it.copy(running = false) }
                throw e
            } catch (e: Exception) {
                // 引擎级异常:未完成条目标失败
                _state.update { st ->
                    st.copy(
                        running = false,
                        items = st.items.map {
                            if (it.isTerminal) it
                            else it.copy(status = SyncItemStatus.FAILED, message = e.toImportErrorMessage())
                        },
                    )
                }
            }
        }
    }

    /**
     * v1.9 ensure:对涉及的收藏夹自动建同名服务器文件夹 + 同名工作区(§4.9 通道 B;
     * 已存在则复用;收藏夹改名/删除不联动服务器端改名/删除,防误删)。
     * 工作区 slug 回填 favoriteRepository,供 target/链接解析。
     */
    private suspend fun ensureFolders(entries: List<CollectEntry>) {
        val folderIds = entries.mapNotNull { it.markFolderId }.distinct()
        if (folderIds.isEmpty()) return
        val existingFolders = runCatching {
            api.documents().localFiles?.items.orEmpty()
                .filter { it.type == "folder" }
                .map { it.name }
                .toSet()
        }.getOrDefault(emptySet())
        val workspaces = runCatching { api.workspaces().workspaces }.getOrDefault(emptyList())
        val wsByName = workspaces.associateBy { it.name }
        val wsBySlug = workspaces.associateBy { it.slug }
        folderIds.forEach { fid ->
            val folder = favoriteRepository.folderById(fid) ?: return@forEach
            // 文件夹:同名不存在才建
            if (folder.name !in existingFolders) {
                runCatching { api.createFolder(CreateFolderRequest(folder.name)) }
            }
            // 工作区:回填 slug > 同名工作区 > 新建同名工作区
            val slug = folder.serverWorkspaceSlug
                ?.takeIf { it in wsBySlug }
                ?: wsByName[folder.name]?.slug
                ?: runCatching { api.createWorkspace(CreateWorkspaceRequest(folder.name)).slug }.getOrNull()
            if (slug != null && slug != folder.serverWorkspaceSlug) {
                favoriteRepository.updateWorkspaceSlug(fid, slug)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    // ===== 批 1:文件 + 工作区 → ImportEngine 复用 =====

    private suspend fun runEngineBatch(all: List<CollectEntry>) {
        val engineEntries = all.filter {
            it.type == EntryType.FILE && !it.markWorkspace.isNullOrBlank()
        }
        if (engineEntries.isEmpty()) return

        val targets = engineEntries.map { e ->
            val folder = folderOf(e)
            ImportTarget(
                uriString = e.localPath.orEmpty(),
                displayName = e.fileName ?: e.id,
                storageName = ConvertToStorageName.convert(e.fileName ?: e.id, config.filenamePolicy),
                sizeBytes = e.sizeBytes,
                folder = folder?.name ?: e.markFolder.orEmpty(),
                workspaceSlug = folder?.serverWorkspaceSlug.orEmpty(),
            )
        }
        val engine = ImportEngine(api, bodyProvider, config)
        engine.launch(
            scope = engineScope,
            targets = targets,
            onDuplicate = { _: DuplicateQuestion ->
                val action = config.duplicateDefaultAction
                DuplicateAnswer(
                    action = if (action == DuplicateAction.ASK) DuplicateAction.KEEP else action,
                    applyToAll = true,
                )
            },
        )
        // 映射引擎条目状态 → 同步条目状态(轮询直到终态)
        while (!engine.state.value.isTerminalPhase) {
            engine.state.value.items.forEachIndexed { idx, item ->
                updateFromEngine(engineEntries[idx], item.status, item.error, item.location)
            }
            delay(300)
        }
        engine.state.value.items.forEachIndexed { idx, item ->
            updateFromEngine(engineEntries[idx], item.status, item.error, item.location)
        }
    }

    private fun updateFromEngine(
        entry: CollectEntry,
        status: ItemStatus,
        error: String?,
        location: String? = null,
    ) {
        _state.update { st ->
            val items = st.items.toMutableList()
            val idx = items.indexOfFirst { it.entry.id == entry.id }
            if (idx < 0) return@update st
            val mapped = when (status) {
                ItemStatus.PENDING -> SyncItemStatus.PENDING
                ItemStatus.UPLOADING, ItemStatus.WAITING_EMBED, ItemStatus.VERIFYING -> SyncItemStatus.RUNNING
                ItemStatus.SUCCESS -> SyncItemStatus.SUCCESS
                ItemStatus.FAILED -> SyncItemStatus.FAILED
                ItemStatus.SKIPPED -> SyncItemStatus.SKIPPED
                ItemStatus.CANCELLED -> SyncItemStatus.FAILED
            }
            // SUCCESS 时 message 携带 location(供回写 serverLocation)
            val msg = if (status == ItemStatus.SUCCESS) location ?: error else error
            items[idx] = items[idx].copy(status = mapped, message = msg, serverLocation = if (status == ItemStatus.SUCCESS) location else null)
            st.copy(items = items)
        }
    }

    // ===== 批 2:同步模式文件(纯上传) + 链接(upload-link) =====

    private suspend fun runSyncAndLinkBatch(all: List<CollectEntry>) {
        all.forEach { entry ->
            when {
                entry.type == EntryType.LINK -> runLink(entry)
                entry.type == EntryType.FILE && entry.markWorkspace.isNullOrBlank() -> runSyncFile(entry)
                else -> Unit // 由批 1 处理
            }
        }
    }

    /** 同步模式:纯上传到文档目录,不做嵌入/验证;重复检测命中则跳过 */
    private suspend fun runSyncFile(entry: CollectEntry) {
        setRunning(entry, "纯上传(同步模式)")
        try {
            if (config.detectDuplicates && findExisting(entry.displayTitle).isNotEmpty()) {
                update(entry, SyncItemStatus.SKIPPED, "服务器已存在同名文档,跳过")
                return
            }
            val folder = folderOf(entry)?.name ?: entry.markFolder.orEmpty()
            val storageName = ConvertToStorageName.convert(entry.fileName ?: entry.id, config.filenamePolicy)
            val body = UploadBodyFactory.build(
                metadataJson = """{"title":"${entry.fileName ?: entry.id}"}""",
                storageName = storageName,
                fileBody = bodyProvider.bodyFor(target(entry, storageName)) { },
            )
            var attempt = 0
            while (true) {
                try {
                    val resp = api.uploadToFolder(folder, body)
                    if (!resp.success) throw IllegalStateException(resp.error ?: "上传失败")
                    update(entry, SyncItemStatus.SUCCESS, "已同步到服务器: $folder")
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (attempt >= config.uploadRetryCount) throw e
                    attempt++
                    val backoff = config.uploadRetryBaseDelaySec * (1L shl (attempt - 1))
                    update(entry, SyncItemStatus.RUNNING, "上传失败,${backoff}s 后重试($attempt/${config.uploadRetryCount})")
                    delay(backoff * 1000L)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update(entry, SyncItemStatus.FAILED, e.toImportErrorMessage())
        }
    }

    /** 链接:upload-link 服务器抓取,并发 2;响应回填 title/location */
    private suspend fun runLink(entry: CollectEntry) {
        linkSemaphore.withPermit {
            setRunning(entry, "服务器抓取链接")
            try {
                val request = UploadLinkRequest(
                    link = listOf(entry.url.orEmpty()),
                    addToWorkspaces = folderOf(entry)?.serverWorkspaceSlug?.takeIf { it.isNotBlank() },
                )
                val resp = api.uploadLink(request)
                if (!resp.success) throw IllegalStateException(resp.error ?: "链接抓取失败")
                val doc = resp.documents.firstOrNull()
                    ?: throw IllegalStateException("链接抓取响应缺少 documents")
                update(entry, SyncItemStatus.SUCCESS, "抓取完成", doc.title, doc.location)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(entry, SyncItemStatus.FAILED, e.toImportErrorMessage())
            }
        }
    }

    // ===== 回写仓储(FR-26) =====

    private suspend fun writeBack(all: List<CollectEntry>) {
        val final = _state.value.items
        final.forEach { item ->
            val entry = item.entry
            when (item.status) {
                SyncItemStatus.SUCCESS -> collectRepository.update(entry.id) {
                    it.copy(
                        status = EntryStatus.EXECUTED,
                        serverTitle = item.serverTitle ?: it.title,
                        serverLocation = item.serverLocation,
                        error = null,
                    )
                }
                SyncItemStatus.FAILED -> collectRepository.update(entry.id) {
                    it.copy(status = EntryStatus.FAILED, error = item.message)
                }
                SyncItemStatus.SKIPPED -> collectRepository.update(entry.id) {
                    it.copy(status = EntryStatus.EXECUTED, error = item.message ?: "跳过")
                }
                else -> Unit
            }
        }
    }

    // ===== 内部工具 =====

    /** v1.9 条目 → 收藏夹(按 markFolderId,兜底按旧 markFolder 名匹配迁移夹) */
    private fun folderOf(entry: CollectEntry) =
        entry.markFolderId?.let { favoriteRepository.folderById(it) }
            ?: entry.markFolder?.let { name ->
                favoriteRepository.userFolders().firstOrNull { it.name == name }
            }

    private fun target(entry: CollectEntry, storageName: String): ImportTarget {
        val folder = folderOf(entry)
        return ImportTarget(
            uriString = entry.localPath.orEmpty(),
            displayName = entry.fileName ?: entry.id,
            storageName = storageName,
            sizeBytes = entry.sizeBytes,
            folder = folder?.name ?: entry.markFolder.orEmpty(),
            workspaceSlug = folder?.serverWorkspaceSlug.orEmpty(),
        )
    }

    private fun setRunning(entry: CollectEntry, msg: String) =
        update(entry, SyncItemStatus.RUNNING, msg)

    private fun update(
        entry: CollectEntry,
        status: SyncItemStatus,
        message: String?,
        serverTitle: String? = null,
        serverLocation: String? = null,
    ) {
        _state.update { st ->
            val items = st.items.toMutableList()
            val idx = items.indexOfFirst { it.entry.id == entry.id }
            if (idx < 0) return@update st
            items[idx] = items[idx].copy(
                status = status,
                message = message,
                serverTitle = serverTitle,
                serverLocation = serverLocation,
            )
            st.copy(items = items)
        }
    }

    /** 简化重复检测:documents 树按 title 比对(与 ImportEngine 同口径) */
    private suspend fun findExisting(title: String): List<String> {
        return try {
            val resp = api.documents()
            val root = resp.localFiles?.items.orEmpty()
            val titles = mutableListOf<String>()
            fun collect(node: com.anythingllm.importer.data.api.dto.FileNodeDto) {
                if (node.type == "folder") {
                    node.items.orEmpty().forEach(::collect)
                } else {
                    (node.title ?: node.name).let { if (it.isNotBlank()) titles.add(it) }
                }
            }
            root.forEach(::collect)
            titles.filter { it.equals(title, ignoreCase = true) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
