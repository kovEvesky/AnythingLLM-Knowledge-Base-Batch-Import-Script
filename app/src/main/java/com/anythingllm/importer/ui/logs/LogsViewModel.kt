package com.anythingllm.importer.ui.logs

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anythingllm.importer.AnythingLLMApp
import com.anythingllm.importer.data.log.ImportLogEntry
import com.anythingllm.importer.data.log.ImportLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 日志页 ViewModel(阶段 4 FR-13):文件列表/详情读取/删除/清理/导出(SAF)。
 */
class LogsViewModel(
    private val app: AnythingLLMApp,
) : ViewModel() {

    data class UiState(
        val files: List<ImportLogRepository.LogFileInfo> = emptyList(),
        val selectedName: String? = null,
        val entries: List<ImportLogEntry> = emptyList(),
        /** 待 SAF 导出内容(建议文件名 + 内容);UI 创建文件并写回后调用 consumeExport */
        val pendingExport: ExportRequest? = null,
        val message: String? = null,
    ) {
        data class ExportRequest(val suggestedName: String, val content: String)
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val repo: ImportLogRepository get() = app.importLogRepository

    /** 刷新:先执行 30 天清理,再列文件(新→旧) */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.cleanupOld()
            val files = repo.listFiles()
            _uiState.update { it.copy(files = files, message = null) }
        }
    }

    /** 选择文件查看详情(条目倒序) */
    fun select(fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = repo.readEntries(fileName)
            _uiState.update { it.copy(selectedName = fileName, entries = entries) }
        }
    }

    fun clearSelection() = _uiState.update { it.copy(selectedName = null, entries = emptyList()) }

    /** 准备导出单个文件(触发 UI 的 SAF CreateDocument) */
    fun requestExport(fileName: String) {
        _uiState.update {
            it.copy(pendingExport = UiState.ExportRequest(fileName, repo.exportContent(fileName)))
        }
    }

    /** 准备导出全部文件(合并内容) */
    fun requestExportAll() {
        _uiState.update {
            it.copy(
                pendingExport = UiState.ExportRequest(
                    suggestedName = "import-all-${System.currentTimeMillis()}.jsonl",
                    content = repo.exportAllContent(),
                ),
            )
        }
    }

    /** SAF 返回 URI 后写入内容并清除待导出状态 */
    fun consumeExport(uri: Uri?) {
        val req = _uiState.value.pendingExport ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (uri != null) {
                runCatching {
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(req.content.toByteArray(Charsets.UTF_8))
                    }
                }.onSuccess {
                    _uiState.update { it.copy(pendingExport = null, message = "已导出 ${req.suggestedName}") }
                }.onFailure { e ->
                    _uiState.update { it.copy(pendingExport = null, message = "导出失败: ${e.message ?: "未知"}") }
                }
            } else {
                _uiState.update { it.copy(pendingExport = null) }
            }
        }
    }

    /** 删除单个日志文件 */
    fun delete(fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.delete(fileName)
            refresh()
        }
    }

    /** 删除全部日志 */
    fun deleteAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val n = repo.deleteAll()
            _uiState.update { it.copy(files = emptyList(), selectedName = null, entries = emptyList(), message = "已删除 $n 个日志文件") }
        }
    }

    /** 手动清理 30 天前日志 */
    fun cleanupOld() {
        viewModelScope.launch(Dispatchers.IO) {
            val n = repo.cleanupOld()
            _uiState.update { it.copy(message = "已清理 $n 个过期日志文件") }
            refresh()
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    companion object {
        @Composable
        fun factory(): ViewModelProvider.Factory {
            val app = LocalContext.current.applicationContext as AnythingLLMApp
            return viewModelFactory { initializer { LogsViewModel(app) } }
        }
    }
}
