package com.anythingllm.importer.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anythingllm.importer.AnythingLLMApp
import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.EntryStatus
import com.anythingllm.importer.data.import.UriFileBodyProvider
import com.anythingllm.importer.domain.sync.CollectFtpSyncEngine
import com.anythingllm.importer.domain.sync.SyncEngine
import com.anythingllm.importer.domain.sync.SyncItemState
import com.anythingllm.importer.domain.sync.SyncItemStatus
import com.anythingllm.importer.domain.sync.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** v1.9 同步通道(§4.9 双子 Tab;B 默认 FTP 先于 AnythingLLM 顺序同步) */
enum class SyncChannel { FTP, ANY }

/** 收藏夹级同步状态(§4.9 收藏夹同步状态列表) */
enum class SyncFolderStatus { DONE, EMBEDDING, PENDING, FAILED, LOCKED }

data class SyncFolderState(
    val id: String,
    val name: String,
    val color: Long,
    val status: SyncFolderStatus,
    val detail: String = "",
)

data class SyncUiState(
    val channel: SyncChannel = SyncChannel.FTP,
    val running: Boolean = false,
    val items: List<SyncItemState> = emptyList(),
    val folders: List<SyncFolderState> = emptyList(),
    val error: String? = null,
    /** 本轮同步完成提示(consumed 后清空) */
    val finished: String? = null,
) {
    val total get() = items.size
    val done get() = items.count { it.isTerminal }
    val success get() = items.count { it.status == SyncItemStatus.SUCCESS }
    val failed get() = items.count { it.status == SyncItemStatus.FAILED }
}

/**
 * v1.9 Sync 页 ViewModel(§4.9):
 * - 双子 Tab 通道各自执行:通道 A FTP(CollectFtpSyncEngine)/ 通道 B AnythingLLM(SyncEngine);
 * - 同步源:已归类灰卡条目(排除回收站);EXECUTED 增量跳过由引擎各自判定;
 * - 收藏夹级状态列表 = 按条目状态汇总(全部成功=已同步 / 有运行中=正在嵌入 / 有失败=失败可重试 / 无条目=待同步 / 回收站=不参与)。
 */
class SyncViewModel(
    private val app: AnythingLLMApp,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private var ftpEngine: CollectFtpSyncEngine? = null
    private var llmEngine: SyncEngine? = null

    fun selectChannel(channel: SyncChannel) = _uiState.update { it.copy(channel = channel) }

    fun refreshFolders() {
        // 全部收藏夹(含回收站,🔒 不参与同步,§4.9 列表要求展示)
        val folders = app.favoriteRepository.folders()
        val gray = syncEntries()
        _uiState.update { st ->
            st.copy(
                folders = folders.map { f ->
                    val folderEntries = gray.filter { it.markFolderId == f.id }
                    val status = when {
                        f.isTrash -> SyncFolderStatus.LOCKED
                        folderEntries.isEmpty() -> SyncFolderStatus.PENDING
                        folderEntries.all { it.status == EntryStatus.EXECUTED } -> SyncFolderStatus.DONE
                        folderEntries.any { it.status == EntryStatus.FAILED } -> SyncFolderStatus.FAILED
                        else -> SyncFolderStatus.EMBEDDING
                    }
                    SyncFolderState(
                        id = f.id,
                        name = f.name,
                        color = f.color,
                        status = status,
                        detail = when (status) {
                            SyncFolderStatus.DONE -> "已同步"
                            SyncFolderStatus.EMBEDDING -> "待同步 ${folderEntries.size} 项"
                            SyncFolderStatus.FAILED -> "失败可重试"
                            SyncFolderStatus.LOCKED -> "🔒 不参与同步"
                            SyncFolderStatus.PENDING -> if (folderEntries.isEmpty()) "待同步" else "待同步 ${folderEntries.size} 项"
                        },
                    )
                },
            )
        }
    }

    /** 一键同步:执行当前通道 */
    fun runSync() {
        val s = _uiState.value
        if (s.running) return
        val entries = syncEntries()
        _uiState.update {
            it.copy(
                running = true,
                items = entries.map { SyncItemState(it) },
                error = null,
                finished = null,
            )
        }
        viewModelScope.launch {
            when (s.channel) {
                SyncChannel.FTP -> {
                    val cfg = app.configRepository.snapshot()
                    val engine = CollectFtpSyncEngine(cfg.ftp, app.collectRepository, app.favoriteRepository)
                    ftpEngine = engine
                    engine.launch(viewModelScope, entries)
                    engine.state.collect { st -> onEngineState(st) }
                }
                SyncChannel.ANY -> {
                    val cfg = app.configRepository.snapshot()
                    val api = app.apiFactory.create(
                        baseUrl = cfg.baseUrl,
                        apiKey = cfg.apiKey,
                        connectTimeoutSec = cfg.probeTimeoutSec.toLong(),
                        readTimeoutSec = cfg.uploadTimeoutSec.toLong(),
                        writeTimeoutSec = cfg.uploadTimeoutSec.toLong(),
                    )
                    val engine = SyncEngine(
                        api = api,
                        config = cfg,
                        bodyProvider = UriFileBodyProvider(app.contentResolver),
                        collectRepository = app.collectRepository,
                        favoriteRepository = app.favoriteRepository,
                    )
                    llmEngine = engine
                    engine.launch(viewModelScope, entries)
                    engine.state.collect { st -> onEngineState(st) }
                }
            }
        }
    }

    private suspend fun onEngineState(st: SyncState) {
        val s = _uiState.value
        _uiState.update {
            it.copy(
                running = st.running,
                items = st.items,
                error = if (st.failedCount > 0) "有 ${st.failedCount} 项同步失败,可重试" else null,
            )
        }
        if (st.isTerminal) {
            refreshFolders()
            val failed = st.failedCount
            _uiState.update {
                it.copy(
                    running = false,
                    finished = if (failed > 0) "同步完成,有 $failed 项失败,可重试" else "同步完成",
                )
            }
        }
    }

    fun consumeFinished() = _uiState.update { it.copy(finished = null) }

    /** 同步源:已归类灰卡(排除回收站与 PENDING),EXECUTED 增量由引擎判定 */
    private fun syncEntries(): List<CollectEntry> =
        app.collectRepository.all().filter { it.isGrayCard && !it.isTrashed }

    companion object {
        @Composable
        fun factory(): ViewModelProvider.Factory {
            val app = LocalContext.current.applicationContext as AnythingLLMApp
            return viewModelFactory { initializer { SyncViewModel(app) } }
        }
    }
}
