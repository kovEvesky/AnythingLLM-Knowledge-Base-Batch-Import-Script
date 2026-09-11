package com.anythingllm.importer.data.collect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

// ===== 快照结构(FR-20):{snapshotTime, folders[], workspaces[]} =====

@Serializable
data class SnapshotFolder(val name: String)

@Serializable
data class SnapshotWorkspace(val slug: String, val name: String)

@Serializable
data class KnowledgeSnapshot(
    val snapshotTime: String = "",
    val folders: List<SnapshotFolder> = emptyList(),
    val workspaces: List<SnapshotWorkspace> = emptyList(),
) {
    val hasData: Boolean get() = snapshotTime.isNotBlank()
    val folderNames: Set<String> get() = folders.map { it.name }.toSet()
    val workspaceSlugs: Set<String> get() = workspaces.map { it.slug }.toSet()
}

/** 快照比对结果(FR-21):实时 vs 基准(旧快照) */
data class SnapshotDiff(
    val addedFolders: List<String> = emptyList(),
    val removedFolders: List<String> = emptyList(),
    val addedWorkspaces: List<String> = emptyList(),
    val removedWorkspaces: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = addedFolders.isEmpty() && removedFolders.isEmpty() &&
            addedWorkspaces.isEmpty() && removedWorkspaces.isEmpty()
}

/**
 * 知识库结构快照仓储(FR-20):
 * - 本地 JSON 存 `{snapshotTime, folders, workspaces}`;
 * - 断网可读(离线标记用);每次连接成功刷新;
 * - diff 语义:文件夹按 name、工作区按 slug 为 key(§4.2.7)。
 * 纯 java.io,不依赖 Android(JVM 单测)。
 */
class SnapshotRepository(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(): KnowledgeSnapshot {
        if (!file.exists()) return KnowledgeSnapshot()
        return runCatching {
            json.decodeFromString(KnowledgeSnapshot.serializer(), file.readText(Charsets.UTF_8))
        }.getOrDefault(KnowledgeSnapshot())
    }

    fun write(snapshot: KnowledgeSnapshot) {
        file.parentFile?.mkdirs()
        file.writeText(
            json.encodeToString(KnowledgeSnapshot.serializer(), snapshot),
            Charsets.UTF_8,
        )
    }

    /** 实时结构 vs 旧快照:输出新增/删除;文件夹按 name、工作区按 slug */
    fun diff(realTime: KnowledgeSnapshot, base: KnowledgeSnapshot): SnapshotDiff {
        val rtFolders = realTime.folderNames
        val baseFolders = base.folderNames
        val rtWorkspaces = realTime.workspaceSlugs
        val baseWorkspaces = base.workspaceSlugs
        return SnapshotDiff(
            addedFolders = (rtFolders - baseFolders).sorted(),
            removedFolders = (baseFolders - rtFolders).sorted(),
            addedWorkspaces = (rtWorkspaces - baseWorkspaces).sorted(),
            removedWorkspaces = (baseWorkspaces - rtWorkspaces).sorted(),
        )
    }
}
