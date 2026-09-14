package com.anythingllm.importer.data.favorite

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Serializable
private data class FavoriteStore(val folders: List<FavoriteFolder> = emptyList())

/**
 * 收藏夹仓储(V1.9 阶段 1,Q1/Q3 定稿):
 * - 元数据统一存 `favorites.json`(整体序列化,风格对齐 LibraryRepository,可 JVM 单测);
 * - 种子:工作 / 学习 / 积累(可删可改名)+ 回收站(builtin 不可删不可改名,恒排最后);
 * - 名称同级去重自动加序号(如 "文档 (2)"),避免本地目录/服务器文件夹名歧义;
 * - 纯 java.io.File 实现,不依赖 Android Context。
 */
class FavoriteRepository(
    private val favoriteDir: File,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val storeFile: File get() = File(favoriteDir, "favorites.json")
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    init {
        favoriteDir.mkdirs()
        ensureSeeds()
    }

    // ===== 读 =====

    /** 全部收藏夹,按 sortOrder 升序(回收站恒最后) */
    @Synchronized
    fun folders(): List<FavoriteFolder> = read().folders.sortedBy { it.sortOrder }

    /** 用户可归入的收藏夹(排除回收站) */
    fun userFolders(): List<FavoriteFolder> = folders().filter { it.isUserFolder }

    /** 回收站收藏夹 */
    fun trashFolder(): FavoriteFolder? = read().folders.firstOrNull { it.isTrash }

    fun folderById(id: String): FavoriteFolder? = read().folders.firstOrNull { it.id == id }

    /** 精确名称查找(不区分大小写,取首个) */
    fun folderByName(name: String): FavoriteFolder? {
        val n = name.trim()
        return read().folders.firstOrNull { it.name.equals(n, ignoreCase = true) }
    }

    // ===== 写 =====

    /** 新建收藏夹:重名自动加序号;颜色越界取色板循环 */
    @Synchronized
    fun createFolder(name: String, color: Long): FavoriteFolder {
        val store = read()
        val base = cleanFolderName(name).ifEmpty { "新建收藏夹" }
        val used = store.folders.map { it.name.lowercase() }.toSet()
        var finalName = base
        var n = 1
        while (finalName.lowercase() in used) {
            n++
            finalName = "$base ($n)"
        }
        val maxOrder = (store.folders.filterNot { it.isTrash }.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val folder = FavoriteFolder(
            id = UUID.randomUUID().toString(),
            name = finalName,
            color = color,
            createdAt = now(),
            sortOrder = maxOrder,
        )
        write(store.copy(folders = store.folders + folder))
        return folder
    }

    /** 重命名 + 改色;空名拒绝,回收站不可改名 */
    @Synchronized
    fun renameFolder(id: String, newName: String, color: Long? = null): Boolean {
        val store = read()
        val idx = store.folders.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val folder = store.folders[idx]
        if (folder.isTrash) return false
        val name = cleanFolderName(newName)
        if (name.isEmpty()) return false
        val list = store.folders.toMutableList()
        list[idx] = list[idx].copy(name = name, color = color ?: folder.color)
        write(store.copy(folders = list))
        return true
    }

    /** 删除收藏夹;回收站不可删(条目去向由调用方迁移后调用) */
    @Synchronized
    fun deleteFolder(id: String): Boolean {
        val store = read()
        val folder = store.folders.firstOrNull { it.id == id } ?: return false
        if (folder.isTrash) return false
        write(store.copy(folders = store.folders.filterNot { it.id == id }))
        return true
    }

    /** 拖动排序:按给定 id 顺序重写 sortOrder(回收站恒排最后,不参与) */
    @Synchronized
    fun reorder(orderedIds: List<String>) {
        val store = read()
        val trash = store.folders.filter { it.isTrash }
        val movable = store.folders.filterNot { it.isTrash }
        val orderById = orderedIds.withIndex().associate { (i, id) -> id to i }
        val list = movable.map { f ->
            val o = orderById[f.id] ?: (movable.size + f.sortOrder)
            f.copy(sortOrder = o)
        } + trash.map { it.copy(sortOrder = Int.MAX_VALUE - 1) }
        write(store.copy(folders = list))
    }

    /** 回填服务器工作区 slug(AnythingLLM ensure 后调用;改名不联动服务器) */
    @Synchronized
    fun updateWorkspaceSlug(id: String, slug: String?) {
        val store = read()
        val idx = store.folders.indexOfFirst { it.id == id }
        if (idx < 0) return
        val list = store.folders.toMutableList()
        list[idx] = list[idx].copy(serverWorkspaceSlug = slug)
        write(store.copy(folders = list))
    }

    // ===== 种子 =====

    /** 首启确保预置四个收藏夹;不覆盖用户已删除的自定义结果 */
    private fun ensureSeeds() {
        val store = read()
        if (store.folders.isEmpty()) {
            val now = now()
            write(
                store.copy(
                    folders = listOf(
                        FavoriteFolder(id = UUID.randomUUID().toString(), name = SEED_WORK, color = 0xFF3B82F6, sortOrder = 0, createdAt = now),
                        FavoriteFolder(id = UUID.randomUUID().toString(), name = SEED_STUDY, color = 0xFFF59E0B, sortOrder = 1, createdAt = now),
                        FavoriteFolder(id = UUID.randomUUID().toString(), name = SEED_ACCUMULATE, color = 0xFF22C55E, sortOrder = 2, createdAt = now),
                        FavoriteFolder(id = UUID.randomUUID().toString(), name = TRASH_NAME, color = 0xFF6B7280, builtin = true, sortOrder = Int.MAX_VALUE - 3, createdAt = now, isTrash = true),
                    ),
                ),
            )
        } else if (trashFolder() == null) {
            // 升级场景:已有收藏夹但缺回收站 → 补建(避免 Mark 左滑无落点)
            val trash = FavoriteFolder(
                id = UUID.randomUUID().toString(),
                name = TRASH_NAME,
                color = 0xFF6B7280,
                builtin = true,
                sortOrder = Int.MAX_VALUE - 3,
                createdAt = now(),
                isTrash = true,
            )
            write(store.copy(folders = store.folders + trash))
        }
    }

    // ===== 内部 =====

    private fun read(): FavoriteStore {
        if (!storeFile.exists()) return FavoriteStore()
        return runCatching {
            json.decodeFromString(FavoriteStore.serializer(), storeFile.readText(Charsets.UTF_8))
        }.getOrDefault(FavoriteStore())
    }

    private fun write(store: FavoriteStore) {
        favoriteDir.mkdirs()
        storeFile.writeText(json.encodeToString(FavoriteStore.serializer(), store), Charsets.UTF_8)
    }

    private fun now(): String = OffsetDateTime.now().format(isoFormatter)

    companion object {
        const val SEED_WORK = "工作"
        const val SEED_STUDY = "学习"
        const val SEED_ACCUMULATE = "积累"
        const val TRASH_NAME = "回收站"

        /**
         * 收藏夹名称清洗:剔除路径分隔与 Windows 非法字符(本地目录 `MarkTo/{name}/`
         * 与服务器文件夹名共用),防路径穿越;清洗后只剩占位下划线(全非法字符)视为空。
         */
        fun cleanFolderName(name: String): String {
            val cleaned = name
                .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
                .trim()
            return if (cleaned.all { it == '_' }) "" else cleaned
        }
    }
}
