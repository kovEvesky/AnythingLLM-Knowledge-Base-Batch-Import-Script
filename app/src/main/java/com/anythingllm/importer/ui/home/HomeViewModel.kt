package com.anythingllm.importer.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anythingllm.importer.AnythingLLMApp
import com.anythingllm.importer.data.api.AnythingLlmClientFactory
import com.anythingllm.importer.data.config.ConfigRepository
import com.anythingllm.importer.domain.probe.ConnectionProbe
import com.anythingllm.importer.domain.probe.ProbeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 主页 ViewModel(FR-16 服务器状态卡片 / FR-05 工作区数量)。
 * 进入主页自动探测;从设置页返回后重新组合会再次刷新。
 */
class HomeViewModel(
    private val repository: ConfigRepository,
    private val probe: ConnectionProbe,
    private val apiFactory: AnythingLlmClientFactory,
) : ViewModel() {

    sealed interface HomeStatus {
        data object Loading : HomeStatus
        data object NotConfigured : HomeStatus
        data class Ok(val workspaceCount: Int, val folders: List<String>) : HomeStatus
        data class Error(val message: String) : HomeStatus
    }

    data class UiState(
        val baseUrl: String = "",
        val status: HomeStatus = HomeStatus.Loading,
        val workspaceCount: Int = 0,
        val folders: List<String> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val cfg = repository.snapshot()
            if (cfg.baseUrl.isBlank() || cfg.apiKey.isBlank()) {
                _uiState.update { it.copy(baseUrl = cfg.baseUrl, status = HomeStatus.NotConfigured) }
                return@launch
            }
            _uiState.update { it.copy(baseUrl = cfg.baseUrl, status = HomeStatus.Loading) }
            when (val result = probe.probe(cfg.baseUrl, cfg.apiKey, cfg.probeTimeoutSec.toLong())) {
                is ProbeResult.Ok -> {
                    val folders = loadFolders(cfg.baseUrl, cfg.apiKey, cfg.apiTimeoutSec.toLong())
                    _uiState.update {
                        it.copy(
                            status = HomeStatus.Ok(result.workspaceCount, folders),
                            workspaceCount = result.workspaceCount,
                            folders = folders,
                        )
                    }
                }
                ProbeResult.Unreachable ->
                    _uiState.update { it.copy(status = HomeStatus.Error("无法连接服务器,请检查地址与网络")) }
                ProbeResult.InvalidKey ->
                    _uiState.update { it.copy(status = HomeStatus.Error("API Key 无效,请到设置页修改")) }
                is ProbeResult.ServerError ->
                    _uiState.update { it.copy(status = HomeStatus.Error(result.detail)) }
            }
        }
    }

    private suspend fun loadFolders(baseUrl: String, apiKey: String, timeoutSec: Long): List<String> {
        return try {
            val api = apiFactory.create(baseUrl, apiKey, readTimeoutSec = timeoutSec)
            api.documents().localFiles?.items.orEmpty()
                .filter { it.type == "folder" }
                .map { it.name }
                .sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        @Composable
        fun factory(): ViewModelProvider.Factory {
            val app = LocalContext.current.applicationContext as AnythingLLMApp
            return viewModelFactory { initializer {
                HomeViewModel(app.configRepository, app.connectionProbe, app.apiFactory)
            } }
        }
    }
}
