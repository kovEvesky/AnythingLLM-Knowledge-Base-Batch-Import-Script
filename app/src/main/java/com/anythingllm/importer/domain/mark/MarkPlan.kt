package com.anythingllm.importer.domain.mark

import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.KnowledgeSnapshot

/** 标记计划校验结果(FR-21/FR-22) */
sealed interface MarkValidation {
    /** 目标有效(或条目未标记,无需校验) */
    data object Ok : MarkValidation

    /**
     * 目标文件夹已不在快照中——软失效:
     * `document/upload/{folder}` 会自动重建文件夹(V-02),执行仍可成功,仅提示。
     */
    data class FolderMissing(val folder: String) : MarkValidation

    /**
     * 目标工作区已删除——硬失效:嵌入无落点,必须重新指定。
     */
    data class WorkspaceMissing(val slug: String) : MarkValidation
}

/**
 * 标记计划(FR-22):条目 → 目标 folder(+workspace)的持久化语义与校验。
 * 纯 Kotlin,可 JVM 单测。
 */
object MarkPlan {

    /** 校验条目标记目标在快照中的有效性;未标记条目视为 Ok */
    fun validate(entry: CollectEntry, snapshot: KnowledgeSnapshot): MarkValidation {
        val folder = entry.markFolder
        val workspace = entry.markWorkspace
        if (folder.isNullOrBlank() && workspace.isNullOrBlank()) return MarkValidation.Ok
        if (!folder.isNullOrBlank() && folder !in snapshot.folderNames) {
            return MarkValidation.FolderMissing(folder)
        }
        // 同步模式:工作区可为空(纯上传)
        if (!workspace.isNullOrBlank() && workspace !in snapshot.workspaceSlugs) {
            return MarkValidation.WorkspaceMissing(workspace)
        }
        return MarkValidation.Ok
    }

    /** 已标记(计划存在) */
    fun isMarked(entry: CollectEntry): Boolean = entry.isMarked

    /** 是否同步模式计划(只有文件夹、无工作区) */
    fun isSyncOnly(entry: CollectEntry): Boolean =
        !entry.markFolder.isNullOrBlank() && entry.markWorkspace.isNullOrBlank()
}
