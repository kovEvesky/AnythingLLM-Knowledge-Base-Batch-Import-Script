package com.anythingllm.importer.domain.import

import com.anythingllm.importer.data.config.DuplicateAction
import okhttp3.RequestBody

/** 导入模式(FR-07):统一模式全部→同一文件夹+工作区;逐项模式每文件单独选择 */
enum class ImportMode { UNIFIED, PER_ITEM }

/** 单文件导入目标(由校验通过的文件 + 目标选择组装) */
data class ImportTarget(
    val uriString: String,
    val displayName: String,
    val storageName: String,
    val sizeBytes: Long,
    val folder: String,
    val workspaceSlug: String,
)

/** 单文件运行状态 */
enum class ItemStatus {
    PENDING, UPLOADING, WAITING_EMBED, VERIFYING, SUCCESS, FAILED, SKIPPED, CANCELLED,
}

/** 整体运行阶段 */
enum class RunPhase { RUNNING, PAUSED, CANCELLED, FINISHED }

data class ImportItemState(
    val target: ImportTarget,
    val status: ItemStatus = ItemStatus.PENDING,
    val uploadedBytes: Long = 0,
    val location: String? = null,
    val error: String? = null,
    val retryCount: Int = 0,
    val verifyAttempt: Int = 0,
) {
    val isTerminal: Boolean
        get() = status == ItemStatus.SUCCESS ||
            status == ItemStatus.FAILED ||
            status == ItemStatus.SKIPPED ||
            status == ItemStatus.CANCELLED
}

/** 重复命中提问(FR-10) */
data class DuplicateQuestion(
    val itemIndex: Int,
    val title: String,
    val existingCount: Int,
)

data class DuplicateAnswer(
    val action: DuplicateAction,
    val applyToAll: Boolean,
)

data class ImportRunState(
    val phase: RunPhase = RunPhase.RUNNING,
    val items: List<ImportItemState> = emptyList(),
    val duplicateQuestion: DuplicateQuestion? = null,
) {
    val successCount get() = items.count { it.status == ItemStatus.SUCCESS }
    val failedCount get() = items.count { it.status == ItemStatus.FAILED }
    val skippedCount get() = items.count { it.status == ItemStatus.SKIPPED }
    val cancelledCount get() = items.count { it.status == ItemStatus.CANCELLED }
    val doneCount get() = successCount + failedCount + skippedCount + cancelledCount
    val isTerminalPhase get() = phase == RunPhase.FINISHED || phase == RunPhase.CANCELLED
}

/**
 * 文件体提供者:引擎不依赖 Android,由调用方注入。
 * 生产实现从 content:// Uri 流式读取;单测注入内存 RequestBody。
 */
fun interface FileBodyProvider {
    fun bodyFor(target: ImportTarget, onProgress: (Long) -> Unit): RequestBody
}

/** 内部信号:重复检测时用户选择"中止" */
internal class ImportAbortException : Exception()

/** 导入业务语义错误(嵌入验证超时/替换确认失败等),文案需原样展示,不做网络错误映射 */
class ImportException(message: String) : Exception(message)
