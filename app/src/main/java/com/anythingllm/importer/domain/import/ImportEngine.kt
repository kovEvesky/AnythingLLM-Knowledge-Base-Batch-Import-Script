package com.anythingllm.importer.domain.import

import com.anythingllm.importer.data.api.AnythingLLMApi
import com.anythingllm.importer.data.api.UploadBodyFactory
import com.anythingllm.importer.data.api.dto.FileNodeDto
import com.anythingllm.importer.data.api.dto.RemoveDocumentsRequest
import com.anythingllm.importer.data.api.dto.UpdateEmbeddingsRequest
import com.anythingllm.importer.data.config.AppConfig
import com.anythingllm.importer.data.config.DuplicateAction
import com.anythingllm.importer.domain.error.toImportErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 批量导入引擎(开发计划 3.2–3.6):
 * - 协程并发控制(Semaphore,config.importConcurrency,默认 2);
 * - 上传失败自动重试(指数退避 2s 起,config.uploadRetryCount 次);
 * - update-embeddings 触发(失败重试 1 次);
 * - docpath 轮询验证(2s→5s 退避,verifyTimeoutSec 超时);
 * - 重复检测(按 title 比对 /documents 树) + 四类动作(保留/跳过/替换/中止 + 应用到全部);
 * - 替换:update-embeddings deletes 解关联 → remove-documents(带文件夹前缀)物理删除 → 回查确认。
 *
 * 纯 Kotlin + 协程:延迟与调度由调用方作用域决定(生产 viewModelScope 真实时间)。
 */
class ImportEngine(
    private val api: AnythingLLMApi,
    private val bodyProvider: FileBodyProvider,
    private val config: AppConfig,
) {

    private val _state = MutableStateFlow(ImportRunState())
    val state: StateFlow<ImportRunState> = _state.asStateFlow()

    private var job: Job? = null
    private val duplicateMutex = Mutex()
    private var applyAllAction: DuplicateAction? = null
    private var docsCache: List<ExistingDoc>? = null

    /** 并发闸门:初次导入与失败重试共用(阶段 4 FR-12) */
    private val semaphore = Semaphore(config.importConcurrency)

    /** 启动批量导入;onDuplicate 由调用方实现(展示对话框并等待用户选择) */
    fun launch(
        scope: CoroutineScope,
        targets: List<ImportTarget>,
        onDuplicate: suspend (DuplicateQuestion) -> DuplicateAnswer,
    ) {
        _state.value = ImportRunState(RunPhase.RUNNING, targets.map { ImportItemState(it) })
        applyAllAction = null
        docsCache = null
        job = scope.launch {
            try {
                coroutineScope {
                    targets.forEachIndexed { index, target ->
                        launch {
                            waitWhilePaused()
                            semaphore.withPermit {
                                processItem(index, target, onDuplicate)
                            }
                        }
                    }
                }
                _state.update { it.copy(phase = RunPhase.FINISHED) }
            } catch (e: ImportAbortException) {
                markAllCancelled()
            } catch (e: CancellationException) {
                markAllCancelled()
                throw e
            }
        }
    }

    /**
     * 重试指定失败项(阶段 4 FR-12):只接受 FAILED 项,重置为 PENDING 后重跑
     * 完整管线(查重→上传→嵌入→验证)。重试期间服务器状态可能变化,
     * 因此重置 docsCache 与 applyAllAction(重复命中会重新询问)。
     * 仅终态(phase=FINISHED/CANCELLED)时可用;运行中调用直接忽略。
     */
    fun retryItems(
        scope: CoroutineScope,
        indexes: List<Int>,
        onDuplicate: suspend (DuplicateQuestion) -> DuplicateAnswer,
    ) {
        val s = _state.value
        if (!s.isTerminalPhase) return
        val toRetry = indexes.filter { s.items.getOrNull(it)?.status == ItemStatus.FAILED }
        if (toRetry.isEmpty()) return

        _state.update { st ->
            val items = st.items.toMutableList()
            toRetry.forEach { i ->
                items[i] = items[i].copy(
                    status = ItemStatus.PENDING,
                    error = null,
                    location = null,
                    uploadedBytes = 0,
                    verifyAttempt = 0,
                    retryCount = 0,
                )
            }
            st.copy(phase = RunPhase.RUNNING, items = items)
        }
        applyAllAction = null
        docsCache = null
        job = scope.launch {
            try {
                coroutineScope {
                    toRetry.forEach { index ->
                        launch {
                            waitWhilePaused()
                            semaphore.withPermit {
                                processItem(index, _state.value.items[index].target, onDuplicate)
                            }
                        }
                    }
                }
                _state.update { it.copy(phase = RunPhase.FINISHED) }
            } catch (e: ImportAbortException) {
                markAllCancelled()
            } catch (e: CancellationException) {
                markAllCancelled()
                throw e
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** 暂停/恢复:暂停时不再启动新文件,进行中的项继续完成 */
    fun setPaused(paused: Boolean) {
        val p = _state.value.phase
        if (p == RunPhase.RUNNING && paused) _state.update { it.copy(phase = RunPhase.PAUSED) }
        if (p == RunPhase.PAUSED && !paused) _state.update { it.copy(phase = RunPhase.RUNNING) }
    }

    // ===== 单文件处理管线 =====

    private suspend fun processItem(
        index: Int,
        target: ImportTarget,
        onDuplicate: suspend (DuplicateQuestion) -> DuplicateAnswer,
    ) {
        try {
            // 1. 重复检测(FR-10)
            if (config.detectDuplicates) {
                duplicateMutex.withLock {
                    val existing = findExisting(target.displayName)
                    if (existing.isNotEmpty()) {
                        val action = applyAllAction ?: run {
                            val answer = onDuplicate(
                                DuplicateQuestion(index, target.displayName, existing.size),
                            )
                            if (answer.applyToAll) applyAllAction = answer.action
                            answer.action
                        }
                        when (action) {
                            DuplicateAction.ABORT -> throw ImportAbortException()
                            DuplicateAction.SKIP -> {
                                updateItem(index) { it.copy(status = ItemStatus.SKIPPED) }
                                return
                            }
                            DuplicateAction.REPLACE -> replaceExisting(target, existing)
                            DuplicateAction.KEEP, DuplicateAction.ASK -> Unit // 继续上传
                        }
                    }
                }
            }

            // 2. 上传(带重试)
            val location = uploadWithRetry(index, target)

            // 3. 触发嵌入
            triggerEmbed(index, target.workspaceSlug, location)

            // 4. 轮询验证
            verifyByPolling(index, target.workspaceSlug, location)

            updateItem(index) {
                it.copy(status = ItemStatus.SUCCESS, location = location, error = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportAbortException) {
            throw e
        } catch (e: Exception) {
            updateItem(index) { it.copy(status = ItemStatus.FAILED, error = e.toImportErrorMessage()) }
        }
    }

    /** 上传:失败自动重试,指数退避(base*2^(n-1):2s,4s) */
    private suspend fun uploadWithRetry(index: Int, target: ImportTarget): String {
        var attempt = 0
        while (true) {
            try {
                updateItem(index) {
                    it.copy(status = ItemStatus.UPLOADING, uploadedBytes = 0, retryCount = attempt, error = null)
                }
                val metadataJson = buildJsonObject { put("title", target.displayName) }.toString()
                val body = UploadBodyFactory.build(
                    metadataJson = metadataJson,
                    storageName = target.storageName,
                    fileBody = bodyProvider.bodyFor(target) { bytes ->
                        updateItem(index) { it.copy(uploadedBytes = bytes) }
                    },
                )
                val resp = api.uploadToFolder(target.folder, body)
                if (!resp.success) throw ImportException(resp.error ?: "上传失败")
                val location = resp.documents.firstOrNull()?.location
                    ?: throw ImportException("上传响应缺少 location")
                updateItem(index) { it.copy(location = location) }
                return location
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= config.uploadRetryCount) throw e
                attempt++
                val backoffSec = config.uploadRetryBaseDelaySec * (1 shl (attempt - 1))
                updateItem(index) {
                    it.copy(
                        retryCount = attempt,
                        status = ItemStatus.UPLOADING,
                        error = "上传失败,${backoffSec}s 后重试($attempt/${config.uploadRetryCount})",
                    )
                }
                delay(config.uploadRetryBaseDelaySec * 1000L * (1L shl (attempt - 1)))
            }
        }
    }

    /** 触发嵌入:失败重试 1 次 */
    private suspend fun triggerEmbed(index: Int, slug: String, location: String) {
        updateItem(index) { it.copy(status = ItemStatus.WAITING_EMBED) }
        var attempt = 0
        while (true) {
            try {
                val resp = api.updateEmbeddings(slug = slug, body = UpdateEmbeddingsRequest(adds = listOf(location)))
                if (!resp.success) throw ImportException(resp.error ?: "触发嵌入失败")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= 1) throw e
                attempt++
                delay(config.uploadRetryBaseDelaySec * 1000L)
            }
        }
    }

    /**
     * 轮询工作区详情,docpath 匹配 location(忽略大小写);2s→5s 退避。
     * 超时用手动截止时间:只在退避延迟内等待,不在网络请求中途触发,
     * 避免超时取消在途请求时 OkHttp 抛出 IOException(Canceled) 而非 TimeoutCancellationException。
     */
    private suspend fun verifyByPolling(index: Int, slug: String, location: String) {
        val deadline = System.currentTimeMillis() + config.verifyTimeoutSec * 1000L
        var attempt = 0
        while (true) {
            updateItem(index) { it.copy(status = ItemStatus.VERIFYING, verifyAttempt = attempt + 1) }
            val detail = api.workspaceDetail(slug)
            val found = detail.workspace.firstOrNull()?.documents.orEmpty()
                .any { it.docpath?.equals(location, ignoreCase = true) == true }
            if (found) return
            attempt++
            val backoffMs = minOf(
                config.verifyBaseDelaySec * 1000L * (1L shl attempt),
                config.verifyMaxDelaySec * 1000L,
            )
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw ImportException("嵌入验证超时(${config.verifyTimeoutSec}s)")
            delay(minOf(backoffMs, remaining))
        }
    }

    /** 替换旧文档(03-API实测修订 §3):先解关联,再物理删除,后回查确认 */
    private suspend fun replaceExisting(target: ImportTarget, existing: List<ExistingDoc>) {
        // 1. update-embeddings deletes 解关联(按目标工作区详情的 metadata.title 匹配)
        val detail = api.workspaceDetail(target.workspaceSlug)
        val docpaths = detail.workspace.firstOrNull()?.documents.orEmpty()
            .filter { metadataTitle(it.metadata)?.equals(target.displayName, ignoreCase = true) == true }
            .mapNotNull { it.docpath }
        if (docpaths.isNotEmpty()) {
            val resp = api.updateEmbeddings(slug = target.workspaceSlug, body = UpdateEmbeddingsRequest(deletes = docpaths))
            if (!resp.success) throw ImportException("解关联失败: ${resp.error ?: resp.message ?: "未知"}")
        }

        // 2. remove-documents 物理删除(names 必须带文件夹前缀)
        val names = existing.map { if (it.folder.isEmpty()) it.nodeName else "${it.folder}/${it.nodeName}" }
        if (names.isNotEmpty()) {
            val resp = api.removeDocuments(RemoveDocumentsRequest(names))
            if (!resp.success) throw ImportException("删除旧文档失败: ${resp.message ?: "未知"}")
        }

        // 3. 回查确认
        docsCache = null
        if (findExisting(target.displayName).isNotEmpty()) {
            throw ImportException("替换:旧文档删除未确认")
        }
    }

    // ===== 文档树 / 重复检测 =====

    private data class ExistingDoc(
        val folder: String,
        val nodeName: String,
        val title: String,
    )

    private suspend fun findExisting(title: String): List<ExistingDoc> {
        val tree = docsCache ?: fetchDocsTree().also { docsCache = it }
        return tree.filter { it.title.equals(title, ignoreCase = true) }
    }

    private suspend fun fetchDocsTree(): List<ExistingDoc> {
        val resp = api.documents()
        val rootItems = resp.localFiles?.items.orEmpty()
        return rootItems.flatMap { collectDocs(it) }
    }

    private fun collectDocs(node: FileNodeDto, folder: String = ""): List<ExistingDoc> {
        if (node.type == "folder") {
            return (node.items ?: emptyList()).flatMap { collectDocs(it, node.name) }
        }
        val title = node.title ?: node.name
        return listOf(ExistingDoc(folder, node.name, title))
    }

    // ===== 内部工具 =====

    /** 解析工作区文档 metadata(JSON 字符串)的 title 字段 */
    private fun metadataTitle(metadata: String?): String? {
        if (metadata.isNullOrBlank()) return null
        return runCatching {
            Json.parseToJsonElement(metadata).jsonObject["title"]?.let {
                if (it is JsonPrimitive) it.content else null
            }
        }.getOrNull()
    }

    private fun updateItem(index: Int, transform: (ImportItemState) -> ImportItemState) {
        _state.update { s ->
            val items = s.items.toMutableList()
            if (index in items.indices) items[index] = transform(items[index])
            s.copy(items = items)
        }
    }

    private fun markAllCancelled() {
        _state.update { s ->
            s.copy(
                phase = RunPhase.CANCELLED,
                items = s.items.map { if (it.isTerminal) it else it.copy(status = ItemStatus.CANCELLED) },
            )
        }
    }

    private suspend fun waitWhilePaused() {
        while (_state.value.phase == RunPhase.PAUSED) delay(100)
    }
}
