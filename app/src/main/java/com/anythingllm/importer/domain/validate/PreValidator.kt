package com.anythingllm.importer.domain.validate

import com.anythingllm.importer.data.config.FilenamePolicy
import com.anythingllm.importer.domain.filename.ConvertToStorageName
import java.util.UUID

/**
 * 用户所选文件(domain 层使用 uriString,避免依赖 android.net.Uri,保证可单测)。
 */
data class PickedFile(
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,          // -1 表示未知
    val mimeType: String? = null,
)

/** 通过预校验的文件(含转换后存储名) */
data class PassedFile(
    val file: PickedFile,
    val storageName: String,
)

/** 被拒绝的文件(含全部原因) */
data class RejectedFile(
    val file: PickedFile,
    val reasons: List<String>,
)

data class ValidationSummary(
    val passed: List<PassedFile>,
    val rejected: List<RejectedFile>,
) {
    val passedCount: Int get() = passed.size
    val rejectedCount: Int get() = rejected.size
    val passedBytes: Long get() = passed.sumOf { it.file.sizeBytes.coerceAtLeast(0) }
    val totalBytes: Long get() =
        passed.sumOf { it.file.sizeBytes.coerceAtLeast(0) } +
            rejected.sumOf { it.file.sizeBytes.coerceAtLeast(0) }
}

/**
 * 预校验器(开发文档 §3.2 FR-04 / 开发计划 2.2):
 * 扩展名白名单、大小上限、空文件过滤,输出通过/拒绝清单。
 * 大小未知(-1)时跳过大小与空文件检查(上传时兜底)。
 */
class PreValidator {

    fun validate(
        files: List<PickedFile>,
        allowedExtensions: List<String>,
        maxFileSizeMB: Int,
        policy: FilenamePolicy,
        guidProvider: () -> String = { UUID.randomUUID().toString() },
    ): ValidationSummary {
        val normalizedWhitelist = allowedExtensions.map { it.lowercase() }
        val maxBytes = maxFileSizeMB.toLong() * 1024 * 1024

        val passed = mutableListOf<PassedFile>()
        val rejected = mutableListOf<RejectedFile>()

        for (file in files) {
            val reasons = mutableListOf<String>()

            // 1. 扩展名白名单(大小写不敏感)
            val ext = file.displayName.extensionOrEmpty()
            if (ext !in normalizedWhitelist) {
                val allowed = if (normalizedWhitelist.isEmpty()) "(未配置)" else normalizedWhitelist.joinToString(" ")
                reasons += "扩展名不在允许列表(允许: $allowed)"
            }

            // 2/3. 大小与空文件
            when {
                file.sizeBytes == 0L -> reasons += "文件为空(0 字节)"
                file.sizeBytes > maxBytes -> reasons += "超过大小上限 ${maxFileSizeMB}MB"
            }

            if (reasons.isEmpty()) {
                passed += PassedFile(file, ConvertToStorageName.convert(file.displayName, policy, guidProvider))
            } else {
                rejected += RejectedFile(file, reasons)
            }
        }

        return ValidationSummary(passed, rejected)
    }
}

/** 提取扩展名(含点,小写);无扩展名或点开头的隐藏文件返回空串 */
fun String.extensionOrEmpty(): String {
    val idx = lastIndexOf('.')
    return if (idx > 0) substring(idx).lowercase() else ""
}
