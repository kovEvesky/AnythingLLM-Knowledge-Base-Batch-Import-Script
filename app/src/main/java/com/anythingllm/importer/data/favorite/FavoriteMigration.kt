package com.anythingllm.importer.data.favorite

import com.anythingllm.importer.data.collect.CollectRepository
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** 迁移结果统计(Q10 定稿:全部保留不删除,旧条目按文件夹名迁移) */
data class FavoriteMigrationResult(
    /** 已迁移到收藏夹的旧标记条目数 */
    val migratedEntries: Int = 0,
    /** 按旧文件夹名新建的收藏夹 */
    val createdFolders: List<String> = emptyList(),
    /** 冲突/异常归入"积累"的条目数 */
    val fallbackToAccumulate: Int = 0,
)

/**
 * 旧数据迁移(V1.9 阶段 1,Q10 定稿:全部保留不删除):
 * 旧已标记条目(带 markFolder 服务器文件夹名,尚无 markFolderId)→ 按文件夹名自动建同名收藏夹并归入;
 * - 同名收藏夹已存在 → 直接归入;
 * - 名称清洗后为空、或与"回收站"同名(回收站不可作为标记目标)→ 归入"积累";
 * - 服务器端旧文件夹/工作区由用户手动清理,应用不删除。
 */
object FavoriteMigration {

    fun migrate(collect: CollectRepository, favorites: FavoriteRepository): FavoriteMigrationResult {
        val oldEntries = collect.all().filter { it.markFolderId == null && !it.markFolder.isNullOrBlank() }
        if (oldEntries.isEmpty()) return FavoriteMigrationResult()

        val accumulate = favorites.folderByName(FavoriteRepository.SEED_ACCUMULATE)
        val created = mutableListOf<String>()
        val resolvedByMark = mutableMapOf<String, String?>() // markFolder -> folderId(null=归积累)
        var fallback = 0
        var migrated = 0

        oldEntries.forEach { entry ->
            val mark = entry.markFolder!!
            val folderId = resolvedByMark.getOrPut(mark) {
                val cleaned = FavoriteRepository.cleanFolderName(mark)
                when {
                    cleaned.isEmpty() -> null
                    cleaned == FavoriteRepository.TRASH_NAME -> null
                    else -> favorites.folderByName(cleaned)?.id ?: run {
                        val f = favorites.createFolder(cleaned, FolderPalette.colorAt(cleaned.hashCode()))
                        created.add(f.name)
                        f.id
                    }
                }
            }
            // 冲突(空名/回收站同名)→ 兜底归入"积累";积累不存在则跳过(不擅自重建用户删除的夹)
            val targetId = folderId ?: accumulate?.id
            if (targetId == null) {
                fallback++
                return@forEach
            }
            val updated = collect.update(entry.id) { e ->
                e.copy(markFolderId = targetId, markedAt = e.markedAt ?: now())
            }
            if (updated != null) {
                if (folderId == null) fallback++ else migrated++
            } else {
                fallback++
            }
        }
        return FavoriteMigrationResult(
            migratedEntries = migrated,
            createdFolders = created,
            fallbackToAccumulate = fallback,
        )
    }

    private fun now(): String = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
