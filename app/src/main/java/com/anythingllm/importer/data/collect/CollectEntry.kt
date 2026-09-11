package com.anythingllm.importer.data.collect

import kotlinx.serialization.Serializable

/** 收集条目类型(v1.2 FR-18):文件 / 链接 */
@Serializable
enum class EntryType { FILE, LINK }

/** 收集来源(v1.2 FR-17/FR-19) */
@Serializable
enum class EntrySource {
    /** SAF 系统文件选择器 */
    SAF,

    /** Android 分享(文件) */
    SHARE_FILE,

    /** Android 分享(文本链接) */
    SHARE_LINK,
}

/** 收集条目生命周期状态 */
@Serializable
enum class EntryStatus {
    /** 待整理(未标记) */
    PENDING,

    /** 已标记(计划已定:目标文件夹+工作区,未执行) */
    MARKED,

    /** 执行中 */
    EXECUTING,

    /** 已执行成功 */
    EXECUTED,

    /** 执行失败(可重试) */
    FAILED,
}

/**
 * 收集箱条目(v1.2 FR-18):
 * - 文件型:复制到私有目录,localPath 指向副本;
 * - 链接型:仅存 url,内容由服务器 upload-link 抓取;
 * - 标记 = markFolder + markWorkspace(计划),执行后回填 serverTitle/serverLocation。
 */
@Serializable
data class CollectEntry(
    val id: String,
    val type: EntryType,
    val source: EntrySource,
    val fileName: String? = null,
    val localPath: String? = null,
    val sizeBytes: Long = 0,
    val url: String? = null,
    val title: String? = null,
    val collectedAt: String,
    val status: EntryStatus = EntryStatus.PENDING,
    val markFolder: String? = null,
    val markWorkspace: String? = null,
    val serverTitle: String? = null,
    val serverLocation: String? = null,
    val error: String? = null,
) {
    /** 展示标题:服务器回填 title > 标题 > 文件名 > 链接 > id */
    val displayTitle: String
        get() = serverTitle ?: title ?: fileName ?: url ?: id

    /** 是否已标记待执行 */
    val isMarked: Boolean
        get() = status == EntryStatus.MARKED || status == EntryStatus.EXECUTING

    /** 是否终态(执行完成/失败) */
    val isTerminal: Boolean
        get() = status == EntryStatus.EXECUTED || status == EntryStatus.FAILED
}
