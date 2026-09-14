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
import com.anythingllm.importer.data.collect.CollectRepository
import com.anythingllm.importer.data.collect.EntryStatus
import com.anythingllm.importer.data.config.ConfigRepository
import com.anythingllm.importer.data.favorite.FavoriteFolder
import com.anythingllm.importer.data.favorite.FavoriteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Mark 流式收件箱 ViewModel(v1.9 重构):
 * - 流式列表:白卡(未标记)按 collectedAt 倒序 / 灰卡(已整理)按 markedAt 倒序;
 * - 动作:归入收藏夹 / 移回收站 / 撤销(双向同义);终态只允许改夹(A4);
 * - 收藏夹体系:气泡排序=默认夹置顶(A1),其余按 sortOrder。
 */
class CollectViewModel(
    private val collectRepository: CollectRepository,
    private val configRepository: ConfigRepository,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {

    data class UiState(
        // ===== v1.9 Mark To 收藏夹体系 =====
        /** 流式列表:白卡(未标记)按 collectedAt 倒序 */
        val whiteEntries: List<CollectEntry> = emptyList(),
        /** 流式列表:灰卡(已整理)按 markedAt 倒序 */
        val grayEntries: List<CollectEntry> = emptyList(),
        /** 全部收藏夹(按 sortOrder,回收站恒最后) */
        val favoriteFolders: List<FavoriteFolder> = emptyList(),
        /** 用户可归入的收藏夹(气泡排序:默认夹置顶,其余按 sortOrder) */
        val userFolders: List<FavoriteFolder> = emptyList(),
        /** v1.9 默认收藏夹 id */
        val defaultFolderId: String = "",
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
        // 默认收藏夹跟随配置(设置页保存后立即生效)
        viewModelScope.launch {
            configRepository.config.collect { cfg ->
                _uiState.update { it.copy(defaultFolderId = cfg.defaultFolderId) }
                refresh()
            }
        }
    }

    /** 从本地仓储重读(条目 + 收藏夹);分享接收后/返回主页时调用 */
    fun refresh() {
        val folders = favoriteRepository.folders()
        _uiState.update {
            it.copy(
                whiteEntries = collectRepository.whiteCards().sortedByDescending { e -> e.collectedAt },
                grayEntries = collectRepository.grayCards().sortedByDescending { e -> e.markedAt ?: "" },
                favoriteFolders = folders,
                userFolders = userFoldersSorted(folders, it.defaultFolderId),
            )
        }
    }

    /** 气泡排序:默认收藏夹置顶,其余按 sortOrder(A1 定稿) */
    private fun userFoldersSorted(folders: List<FavoriteFolder>, defaultId: String): List<FavoriteFolder> {
        val users = folders.filter { it.isUserFolder }
        return users.sortedWith(
            compareBy<FavoriteFolder> { it.id != defaultId }.thenBy { it.sortOrder },
        )
    }

    // ===== v1.9 Mark To:流式收件箱动作 =====

    private fun markTime(): String = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** 单条归入收藏夹(白卡标记 / 灰卡改夹);返回收藏夹 id(供 UI 提示) */
    fun markToFolder(entryId: String, folderId: String) {
        collectRepository.update(entryId) { e ->
            e.copy(
                status = EntryStatus.MARKED,
                markFolderId = folderId,
                markedAt = markTime(),
                error = null,
            )
        }
        refresh()
    }

    /** 批量归入收藏夹(多选模式) */
    fun markEntriesToFolder(ids: List<String>, folderId: String) {
        if (ids.isEmpty()) return
        ids.forEach { id ->
            collectRepository.update(id) { e ->
                e.copy(
                    status = EntryStatus.MARKED,
                    markFolderId = folderId,
                    markedAt = markTime(),
                    error = null,
                )
            }
        }
        refresh()
    }

    /** 左滑删除:白卡 → 回收站(TRASHED,Q4:不参与同步,可撤销) */
    fun trashEntry(entryId: String) {
        collectRepository.update(entryId) { e ->
            e.copy(
                status = EntryStatus.TRASHED,
                markedAt = markTime(),
                error = null,
            )
        }
        refresh()
    }

    /** 批量移入回收站(多选模式) */
    fun trashEntries(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.forEach { id ->
            collectRepository.update(id) { e ->
                e.copy(
                    status = EntryStatus.TRASHED,
                    markedAt = markTime(),
                    error = null,
                )
            }
        }
        refresh()
    }

    /** 灰卡撤销(已归入/已回收):恢复 PENDING 白卡回未标记区;终态(EXECUTED/FAILED)仅允许改夹 */
    fun restoreEntry(entryId: String) {
        collectRepository.update(entryId) { e ->
            if (e.isTerminal) {
                e // 已同步到服务器的内容不可撤销(避免脏状态,A4 定稿)
            } else {
                e.copy(
                    status = EntryStatus.PENDING,
                    markFolderId = null,
                    markedAt = null,
                    error = null,
                )
            }
        }
        refresh()
    }

    companion object {
        @Composable
        fun factory(): ViewModelProvider.Factory {
            val app = LocalContext.current.applicationContext as AnythingLLMApp
            return viewModelFactory { initializer {
                CollectViewModel(
                    collectRepository = app.collectRepository,
                    configRepository = app.configRepository,
                    favoriteRepository = app.favoriteRepository,
                )
            } }
        }
    }
}
