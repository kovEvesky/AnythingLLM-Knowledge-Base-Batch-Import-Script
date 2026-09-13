package com.anythingllm.importer.data.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Serializable
private data class LibraryStore(
    val folders: List<LibraryFolder> = emptyList(),
    val entries: List<LibraryEntry> = emptyList(),
)

/**
 * 资料库仓储(v1.3 FR-30/31,设计《10-v1.3开发计划》§3.2):
 * - 元数据统一存 `library/library.json`(整体序列化,风格对齐 CollectRepository);
 * - 文件副本存 `library/files/{entryId}_{title清洗}`;
 * - 移动 = 元数据变更(folderId),不实际搬文件;
 * - 删除文件夹 = 子文件夹/子条目上移到父级(不物理删文件,防误删);
 * - 纯 java.io.File 实现,不依赖 Android Context(JVM 单测可直接用临时目录)。
 */
class LibraryRepository(
    private val libraryDir: File,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val storeFile: File get() = File(libraryDir, "library.json")
    private val filesDir: File get() = File(libraryDir, "files")
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    init {
        libraryDir.mkdirs()
        filesDir.mkdirs()
    }

    // ===== 读 =====

    @Synchronized
    fun folders(): List<LibraryFolder> = read().folders

    @Synchronized
    fun entries(): List<LibraryEntry> = read().entries

    /** 某目录下的子文件夹 + 条目(文件夹在前,均按名称排序) */
    fun childrenOf(folderId: String?): Pair<List<LibraryFolder>, List<LibraryEntry>> {
        val store = read()
        val folders = store.folders
            .filter { it.parentId == folderId }
            .sortedBy { it.name.lowercase() }
        val entries = store.entries
            .filter { it.folderId == folderId }
            .sortedBy { it.title.lowercase() }
        return folders to entries
    }

    /** 根 → 该文件夹的名称链(不含根自身),用于 FTP 远端目录映射 */
    fun pathOf(folderId: String?): List<String> {
        if (folderId == null) return emptyList()
        val store = read()
        val byId = store.folders.associateBy { it.id }
        val chain = mutableListOf<String>()
        var cur = byId[folderId] ?: return emptyList()
        while (true) {
            chain.add(0, cur.name)
            if (cur.parentId == null) break
            cur = byId[cur.parentId] ?: break
        }
        return chain
    }

    // ===== 文件夹管理(FR-30 文件目录管理) =====

    /** 新建文件夹:同级重名自动追加序号(如 "文档 (2)") */
    @Synchronized
    fun createFolder(name: String, parentId: String?): LibraryFolder {
        val store = read()
        val base = name.trim().ifEmpty { "新建文件夹" }
        val siblings = store.folders.filter { it.parentId == parentId }.map { it.name.lowercase() }.toSet()
        var finalName = base
        var n = 1
        while (finalName.lowercase() in siblings) {
            n++
            finalName = "$base ($n)"
        }
        val folder = LibraryFolder(
            id = UUID.randomUUID().toString(),
            name = finalName,
            parentId = parentId,
            createdAt = now(),
        )
        write(store.copy(folders = store.folders + folder))
        return folder
    }

    @Synchronized
    fun renameFolder(id: String, newName: String): Boolean {
        val store = read()
        val idx = store.folders.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val name = newName.trim()
        if (name.isEmpty()) return false
        val list = store.folders.toMutableList()
        list[idx] = list[idx].copy(name = name)
        write(store.copy(folders = list))
        return true
    }

    /** 删除文件夹:子文件夹/子条目上移到父级,不物理删文件 */
    @Synchronized
    fun deleteFolder(id: String): Boolean {
        val store = read()
        val folder = store.folders.firstOrNull { it.id == id } ?: return false
        val parent = folder.parentId
        val folders = store.folders.filterNot { it.id == id }.map { f ->
            if (f.parentId == id) f.copy(parentId = parent) else f
        }
        val entries = store.entries.map { e ->
            if (e.folderId == id) e.copy(folderId = parent, syncedAt = null, syncedPath = null, syncedSize = -1) else e
        }
        write(LibraryStore(folders, entries))
        return true
    }

    // ===== 条目管理(FR-31) =====

    /** 收集箱 → 资料库归档:文件副本复制到 library/files/,建资料库条目(调用方随后删除收集箱条目=移动语义) */
    @Synchronized
    fun archiveFromCollect(
        id: String,
        type: LibraryEntryType,
        title: String,
        url: String?,
        srcFile: File?,
        sizeBytes: Long,
        destFolderId: String?,
    ): LibraryEntry {
        val store = read()
        filesDir.mkdirs()
        val destFile = if (srcFile != null && srcFile.exists()) {
            val target = File(filesDir, "${id}_${cleanFileName(title)}")
            runCatching { srcFile.copyTo(target, overwrite = true) }.getOrNull()
        } else null
        val entry = LibraryEntry(
            id = id,
            type = type,
            folderId = destFolderId,
            title = title,
            url = url,
            localPath = destFile?.absolutePath,
            sizeBytes = destFile?.length() ?: sizeBytes,
            addedAt = now(),
        )
        write(store.copy(entries = store.entries + entry))
        return entry
    }

    /** 条目移动到目标文件夹(根级传 null);移动后视为未同步(远端路径将变化) */
    @Synchronized
    fun moveEntry(id: String, folderId: String?): Boolean {
        val store = read()
        val idx = store.entries.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val list = store.entries.toMutableList()
        list[idx] = list[idx].copy(folderId = folderId, syncedAt = null, syncedPath = null, syncedSize = -1)
        write(store.copy(entries = list))
        return true
    }

    /** 删除条目:删元数据 + 物理删文件副本 */
    @Synchronized
    fun deleteEntry(id: String): Boolean {
        val store = read()
        val target = store.entries.firstOrNull { it.id == id } ?: return false
        write(store.copy(entries = store.entries.filterNot { it.id == id }))
        target.localPath?.let { runCatching { File(it).delete() } }
        return true
    }

    /** 待同步条目:未同步(syncedAt==null)或本地文件大小与上次同步不一致 */
    fun pendingSync(): List<LibraryEntry> = entries().filter { e ->
        e.syncedAt == null ||
            (e.type == LibraryEntryType.FILE && e.localPath?.let { File(it).length() } != e.syncedSize)
    }

    /** FTP 同步成功后回写远端路径与大小(增量依据) */
    @Synchronized
    fun markSynced(id: String, remotePath: String, size: Long) {
        val store = read()
        val idx = store.entries.indexOfFirst { it.id == id }
        if (idx < 0) return
        val list = store.entries.toMutableList()
        list[idx] = list[idx].copy(syncedAt = now(), syncedPath = remotePath, syncedSize = size)
        write(store.copy(entries = list))
    }

    /** 清空资料库(全部文件夹与条目,含文件副本);用于测试与极端清理 */
    @Synchronized
    fun clear() {
        write(LibraryStore())
        filesDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    // ===== 内部 =====

    private fun read(): LibraryStore {
        if (!storeFile.exists()) return LibraryStore()
        return runCatching {
            json.decodeFromString(LibraryStore.serializer(), storeFile.readText(Charsets.UTF_8))
        }.getOrDefault(LibraryStore())
    }

    private fun write(store: LibraryStore) {
        libraryDir.mkdirs()
        storeFile.writeText(json.encodeToString(LibraryStore.serializer(), store), Charsets.UTF_8)
    }

    private fun now(): String = OffsetDateTime.now().format(isoFormatter)

    companion object {
        /** 文件/远端名称清洗:剔除路径分隔与 Windows 非法字符,防路径穿越;空名回退 */
        fun cleanFileName(name: String): String = name
            .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
            .trim()
            .ifBlank { "file" }
    }
}
