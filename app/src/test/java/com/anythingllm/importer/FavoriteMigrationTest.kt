package com.anythingllm.importer

import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.CollectRepository
import com.anythingllm.importer.data.collect.EntrySource
import com.anythingllm.importer.data.collect.EntryStatus
import com.anythingllm.importer.data.collect.EntryType
import com.anythingllm.importer.data.favorite.FavoriteMigration
import com.anythingllm.importer.data.favorite.FavoriteRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 旧数据迁移单测(v1.9 阶段 1,Q10 定稿):
 * 旧 markFolder 条目按文件夹名建同名收藏夹归入 / 同名复用 / 清洗为空与回收站同名归积累 / 已迁移条目跳过。
 */
class FavoriteMigrationTest {

    private lateinit var dir: File
    private lateinit var collect: CollectRepository
    private lateinit var favorites: FavoriteRepository

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("mig-test").toFile()
        collect = CollectRepository(File(dir, "collect"))
        favorites = FavoriteRepository(File(dir, "favorites"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun entry(id: String, markFolder: String?, markFolderId: String? = null): CollectEntry =
        CollectEntry(
            id = id,
            type = EntryType.LINK,
            source = EntrySource.SHARE_LINK,
            url = "https://example.com/$id",
            collectedAt = "2026-09-14T10:00:00+08:00",
            markFolder = markFolder,
            markFolderId = markFolderId,
        )

    @Test
    fun `migrate - 旧文件夹名自动建同名收藏夹并归入`() {
        collect.add(entry("e1", "产品资料"))
        collect.add(entry("e2", "产品资料"))
        val r = FavoriteMigration.migrate(collect, favorites)
        assertEquals(2, r.migratedEntries)
        assertEquals(listOf("产品资料"), r.createdFolders)
        assertEquals(0, r.fallbackToAccumulate)

        val folder = favorites.folderByName("产品资料")
        assertNotNull(folder)
        val e1 = collect.all().first { it.id == "e1" }
        assertEquals(folder!!.id, e1.markFolderId)
        assertNotNull(e1.markedAt)
        // 旧字段保留可追溯
        assertEquals("产品资料", e1.markFolder)
    }

    @Test
    fun `migrate - 与预置夹同名时直接归入不新建`() {
        collect.add(entry("e1", "工作"))
        val r = FavoriteMigration.migrate(collect, favorites)
        assertEquals(1, r.migratedEntries)
        assertTrue(r.createdFolders.isEmpty())
        assertEquals(favorites.folderByName("工作")!!.id, collect.all().first { it.id == "e1" }.markFolderId)
        // 预置夹仍是 4 个,没有多建
        assertEquals(4, favorites.folders().size)
    }

    @Test
    fun `migrate - 名称清洗为空或回收站同名归入积累`() {
        collect.add(entry("e1", "///***"))
        collect.add(entry("e2", FavoriteRepository.TRASH_NAME))
        val r = FavoriteMigration.migrate(collect, favorites)
        assertEquals(0, r.createdFolders.size)
        assertEquals(2, r.fallbackToAccumulate)
        val acc = favorites.folderByName("积累")!!
        assertEquals(acc.id, collect.all().first { it.id == "e1" }.markFolderId)
        assertEquals(acc.id, collect.all().first { it.id == "e2" }.markFolderId)
    }

    @Test
    fun `migrate - 幂等已迁移条目跳过且不重复建夹`() {
        collect.add(entry("e1", "旧夹"))
        FavoriteMigration.migrate(collect, favorites)
        val r2 = FavoriteMigration.migrate(collect, favorites)
        assertEquals(0, r2.migratedEntries)
        assertEquals(5, favorites.folders().size)
    }

    @Test
    fun `migrate - 无旧条目时返回空结果`() {
        collect.add(entry("e1", null))
        val r = FavoriteMigration.migrate(collect, favorites)
        assertEquals(0, r.migratedEntries)
        assertNull(collect.all().first { it.id == "e1" }.markFolderId)
        assertEquals(4, favorites.folders().size)
    }

    @Test
    fun `migrate - 旧条目保持原有状态不被覆盖`() {
        collect.add(
            entry("e1", "工作").copy(
                status = EntryStatus.EXECUTED,
                serverLocation = "/documents/工作/a.pdf",
            ),
        )
        FavoriteMigration.migrate(collect, favorites)
        val e = collect.all().first { it.id == "e1" }
        assertEquals(EntryStatus.EXECUTED, e.status)
        assertEquals("/documents/工作/a.pdf", e.serverLocation)
    }
}
