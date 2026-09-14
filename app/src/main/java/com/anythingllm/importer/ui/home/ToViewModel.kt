package com.anythingllm.importer.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anythingllm.importer.AnythingLLMApp
import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.CollectRepository
import com.anythingllm.importer.data.collect.EntryStatus
import com.anythingllm.importer.data.favorite.FavoriteFolder
import com.anythingllm.importer.data.favorite.FavoriteRepository
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tab2 To · 收藏夹管理(V1.9 最终设计 §4.6–4.8):
 * - 收藏夹卡片:色点 + 名称 + 条目数;回收站带锁恒最后;
 * - 长按拖动排序 / 左滑删除(迁移选择)/ 右滑重命名;搜索过滤;
 * - 点击进入收藏夹详情:条目列表,点条目可"移出到其他夹 / 彻底删除";
 * - 回收站详情:查看 / 单个恢复 / 彻底删除 / 清空(二次确认,删文件副本+条目,A5 定稿)。
 */
class ToViewModel(
    private val collectRepository: CollectRepository,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {

    data class UiState(
        val folders: List<FavoriteFolder> = emptyList(),
        val userFolders: List<FavoriteFolder> = emptyList(),
        /** 收藏夹 → 条目数(含白卡与灰卡,不含回收站条目) */
        val countByFolder: Map<String, Int> = emptyMap(),
        val trashCount: Int = 0,
        val searchQuery: String = "",
        /** 详情:当前打开的收藏夹(null=列表页) */
        val openFolder: FavoriteFolder? = null,
        val folderEntries: List<CollectEntry> = emptyList(),
        /** 回收站详情开关 */
        val openTrash: Boolean = false,
        val trashEntries: List<CollectEntry> = emptyList(),
        val deleted: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        val folders = favoriteRepository.folders()
        val entries = collectRepository.all()
        val countByFolder = folders.filter { it.isUserFolder }.associate { f ->
            f.id to entries.count { it.markFolderId == f.id }
        }
        val trashCount = entries.count { it.isTrashed }
        val openFolderId = _uiState.value.openFolder?.id
        _uiState.update {
            it.copy(
                folders = folders,
                userFolders = favoriteRepository.userFolders(),
                countByFolder = countByFolder,
                trashCount = trashCount,
                openFolder = openFolderId?.let { id -> folders.firstOrNull { f -> f.id == id } },
                folderEntries = openFolderId?.let { id ->
                    entries.filter { e -> e.markFolderId == id }.sortedByDescending { e -> e.markedAt ?: "" }
                } ?: emptyList(),
                trashEntries = entries.filter { e -> e.isTrashed }.sortedByDescending { e -> e.markedAt ?: "" },
            )
        }
    }

    fun setSearch(q: String) = _uiState.update { it.copy(searchQuery = q) }

    /** 新建收藏夹(重名自动加序号,由仓储处理);返回是否成功 */
    fun createFolder(name: String, color: Long): Boolean {
        if (name.isBlank()) return false
        favoriteRepository.createFolder(name, color)
        refresh()
        return true
    }

    /** 重命名 + 改色 */
    fun renameFolder(id: String, newName: String, color: Long? = null): Boolean {
        val ok = favoriteRepository.renameFolder(id, newName, color)
        if (ok) refresh()
        return ok
    }

    /**
     * 删除收藏夹(A5/Q11 定稿:删除前选择条目去向):
     * - migrateTargetId != null → 该夹条目迁移到目标夹(保持已标记);
     * - migrateTargetId == null → 该夹全部条目移入回收站(TRASHED);
     * 随后删除收藏夹本身;回收站不可删。
     */
    fun deleteFolder(id: String, migrateTargetId: String?) {
        val entries = collectRepository.all().filter { it.markFolderId == id }
        entries.forEach { e ->
            collectRepository.update(e.id) { entry ->
                when {
                    migrateTargetId != null -> entry.copy(
                        markFolderId = migrateTargetId,
                        markedAt = now(),
                        error = null,
                    )
                    entry.isTerminal -> entry.copy(status = EntryStatus.TRASHED, markedAt = now())
                    else -> entry.copy(
                        status = EntryStatus.TRASHED,
                        markedAt = now(),
                        error = null,
                    )
                }
            }
        }
        favoriteRepository.deleteFolder(id)
        refresh()
        _uiState.update { it.copy(deleted = true) }
    }

    /** 长按拖动排序结果持久化(回收站恒最后,仓储处理) */
    fun reorder(orderedIds: List<String>) {
        favoriteRepository.reorder(orderedIds)
        refresh()
    }

    // ===== 详情页 =====

    fun openFolderDetail(id: String) {
        val folder = favoriteRepository.folderById(id) ?: return
        if (folder.isTrash) return
        _uiState.update { it.copy(openFolder = folder) }
        refresh()
    }

    fun closeDetail() = _uiState.update { it.copy(openFolder = null) }

    fun openTrash() {
        _uiState.update { it.copy(openTrash = true) }
        refresh()
    }

    fun closeTrash() = _uiState.update { it.copy(openTrash = false) }

    /** 详情页:条目移出到其他收藏夹 */
    fun moveEntry(entryId: String, targetFolderId: String) {
        collectRepository.update(entryId) { e ->
            e.copy(markFolderId = targetFolderId, markedAt = now(), error = null)
        }
        refresh()
    }

    /** 详情页:彻底删除(删文件副本 + 条目,不可恢复) */
    fun deleteEntryPermanently(entryId: String) {
        collectRepository.remove(entryId)
        refresh()
    }

    /** 回收站:单个恢复(回到未标记白卡,与 Mark 灰卡撤销同语义) */
    fun restoreEntry(entryId: String) {
        collectRepository.update(entryId) { e ->
            e.copy(status = EntryStatus.PENDING, markFolderId = null, markedAt = null, error = null)
        }
        refresh()
    }

    /** 回收站:清空(彻底删除文件副本 + 条目,二次确认在 UI 层) */
    fun clearTrash() {
        val ids = collectRepository.trashed().map { it.id }
        if (ids.isEmpty()) return
        collectRepository.removeAll(ids)
        refresh()
    }

    private fun now(): String = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory { initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AnythingLLMApp
            ToViewModel(
                collectRepository = app.collectRepository,
                favoriteRepository = app.favoriteRepository,
            )
        } }
    }
}
