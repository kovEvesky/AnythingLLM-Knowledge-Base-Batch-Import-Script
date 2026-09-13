package com.anythingllm.importer.data.library

import kotlinx.serialization.Serializable

/**
 * 资料库条目类型(v1.3):文件 / 链接。
 * - FILE:localPath 指向应用私有目录中的文件副本;
 * - LINK:仅存 url,同步到 PC 时生成 .url 文本。
 */
@Serializable
enum class LibraryEntryType { FILE, LINK }

/**
 * 资料库文件夹(v1.3 FR-30):
 * 目录树节点,parentId=null 表示根级;名称在展示层做同级去重,存储不强制唯一。
 */
@Serializable
data class LibraryFolder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: String,
)

/**
 * 资料库条目(v1.3 FR-31):
 * - folderId=null 表示位于根级;移动 = 修改 folderId(不实际搬文件);
 * - syncedAt/syncedPath/syncedSize 记录最近一次 FTP 同步结果,用于增量判定。
 */
@Serializable
data class LibraryEntry(
    val id: String,
    val type: LibraryEntryType,
    val folderId: String? = null,
    val title: String,
    val url: String? = null,
    val localPath: String? = null,
    val sizeBytes: Long = 0,
    val addedAt: String,
    val syncedAt: String? = null,
    val syncedPath: String? = null,
    val syncedSize: Long = -1,
) {
    /** 是否已同步到 PC */
    val isSynced: Boolean get() = syncedAt != null
}
