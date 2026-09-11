package com.anythingllm.importer.ui.select

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
import com.anythingllm.importer.data.config.AppConfig
import com.anythingllm.importer.data.config.ConfigRepository
import com.anythingllm.importer.data.config.FilenamePolicy
import com.anythingllm.importer.data.files.FileMetadataReader
import com.anythingllm.importer.domain.validate.PickedFile
import com.anythingllm.importer.domain.validate.PreValidator
import com.anythingllm.importer.domain.validate.ValidationSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 文件选择与预校验 ViewModel(FR-03/FR-04)。
 * 选择页与校验结果页共享同一实例(经 SELECT 路由的 backStackEntry 作用域)。
 */
class SelectViewModel(
    private val repository: ConfigRepository,
) : ViewModel() {

    data class UiState(
        val files: List<PickedFile> = emptyList(),
        val validation: ValidationSummary? = null,
        val maxFileSizeMB: Int = 100,
        val allowedExtensions: List<String> = AppConfig.DEFAULT_ALLOWED_EXTENSIONS,
        val policy: FilenamePolicy = FilenamePolicy.UNICODE,
        val configLoaded: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val preValidator = PreValidator()

    fun init() {
        if (_uiState.value.configLoaded) return
        viewModelScope.launch {
            val cfg = repository.config.first()
            _uiState.update {
                it.copy(
                    maxFileSizeMB = cfg.maxFileSizeMB,
                    allowedExtensions = cfg.allowedExtensions,
                    policy = cfg.filenamePolicy,
                    configLoaded = true,
                )
            }
        }
    }

    /** SAF 返回的 Uri 集合 → 读取元数据 + 持久权限 */
    fun onFilesPicked(uris: List<Uri>, context: Context) {
        val picked = uris.map { uri ->
            FileMetadataReader.takePersistablePermission(context, uri)
            FileMetadataReader.read(context, uri)
        }
        _uiState.update { it.copy(files = picked, validation = null) }
    }

    fun clearFiles() = _uiState.update { it.copy(files = emptyList(), validation = null) }

    /** 运行预校验,结果供校验结果页展示 */
    fun runValidation() {
        val s = _uiState.value
        if (s.files.isEmpty()) return
        val summary = preValidator.validate(
            files = s.files,
            allowedExtensions = s.allowedExtensions,
            maxFileSizeMB = s.maxFileSizeMB,
            policy = s.policy,
        )
        _uiState.update { it.copy(validation = summary) }
    }

    companion object {
        @Composable
        fun factory(): ViewModelProvider.Factory {
            val app = LocalContext.current.applicationContext as AnythingLLMApp
            return viewModelFactory { initializer {
                SelectViewModel(app.configRepository)
            } }
        }
    }
}
