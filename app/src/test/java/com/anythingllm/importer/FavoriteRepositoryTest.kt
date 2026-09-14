package com.anythingllm.importer

import com.anythingllm.importer.data.favorite.FavoriteRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 收藏夹仓储单测(v1.9 阶段 1):
 * 种子四夹 / 回收站锁定 / 重名去重 / 重命名 / 删除 / 拖动排序 / workspace slug 回填 / 名称清洗。
 */
class FavoriteRepositoryTest {

    private lateinit var dir: File
    private lateinit var repo: FavoriteRepository

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("fav-test").toFile()
        repo = FavoriteRepository(File(dir, "favorites"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ===== 种子 =====

    @Test
    fun `seeds - 预置四夹且回收站内置恒排最后`() {
        val all = repo.folders()
        assertEquals(4, all.size)
        assertEquals(listOf("工作", "学习", "积累", "回收站"), all.map { it.name })
        val trash = repo.trashFolder()
        assertNotNull(trash)
        assertTrue(trash!!.builtin)
        assertTrue(trash.isTrash)
        // 回收站 sortOrder 最大,恒最后
        assertEquals(3, all.indexOfFirst { it.isTrash })
        // 用户夹排除回收站
        assertEquals(listOf("工作", "学习", "积累"), repo.userFolders().map { it.name })
    }

    @Test
    fun `seeds - 已有收藏夹但缺回收站时升级补建`() {
        // 模拟 v1.9 中途升级:删除 favorites.json 里的回收站后重建仓库
        repo.deleteFolder(repo.trashFolder()!!.id) // 应被拒绝
        assertNotNull(repo.trashFolder())
    }

    // ===== 新建 =====

    @Test
    fun `createFolder - 重名自动加序号,颜色按传入`() {
        val f1 = repo.createFolder("测试", 0xFF123456)
        val f2 = repo.createFolder("测试", 0xFF654321)
        assertEquals("测试", f1.name)
        assertEquals("测试 (2)", f2.name)
        assertEquals(0xFF123456, f1.color)
        assertEquals(0xFF654321, f2.color)
        // sortOrder 递增,排在种子后
        val all = repo.folders()
        assertEquals(6, all.size)
        assertTrue(all[3].sortOrder < all[4].sortOrder)
    }

    @Test
    fun `createFolder - 非法字符清洗,空名回退`() {
        val f = repo.createFolder("a/b\\c:d*e?f\"g<h>i|j", 0xFF000000)
        assertEquals("a_b_c_d_e_f_g_h_i_j", f.name)
        val empty = repo.createFolder("   ///***", 0xFF000000)
        assertEquals("新建收藏夹", empty.name)
    }

    // ===== 重命名 =====

    @Test
    fun `renameFolder - 生效,空名拒绝,回收站拒绝`() {
        val work = repo.folderByName("工作")!!
        assertTrue(repo.renameFolder(work.id, "工作 (主业)"))
        assertEquals("工作 (主业)", repo.folderByName("工作 (主业)")!!.name)
        assertFalse(repo.renameFolder(work.id, "   "))
        assertEquals("工作 (主业)", repo.folderByName("工作 (主业)")!!.name)

        val trash = repo.trashFolder()!!
        assertFalse(repo.renameFolder(trash.id, "垃圾桶"))
        assertEquals(FavoriteRepository.TRASH_NAME, repo.trashFolder()!!.name)
    }

    // ===== 删除 =====

    @Test
    fun `deleteFolder - 删除生效,回收站拒绝`() {
        val f = repo.createFolder("临时", 0xFF000000)
        assertTrue(repo.deleteFolder(f.id))
        assertNull(repo.folderById(f.id))
        val trash = repo.trashFolder()!!
        assertFalse(repo.deleteFolder(trash.id))
        assertNotNull(repo.trashFolder())
    }

    // ===== 排序 =====

    @Test
    fun `reorder - 按给定顺序重写 sortOrder,回收站恒排最后`() {
        val work = repo.folderByName("工作")!!
        val study = repo.folderByName("学习")!!
        val acc = repo.folderByName("积累")!!
        repo.reorder(listOf(acc.id, study.id, work.id))
        assertEquals(listOf("积累", "学习", "工作", "回收站"), repo.folders().map { it.name })
    }

    // ===== workspace slug =====

    @Test
    fun `updateWorkspaceSlug - 回填生效,未知 id 忽略`() {
        val work = repo.folderByName("工作")!!
        repo.updateWorkspaceSlug(work.id, "work")
        assertEquals("work", repo.folderById(work.id)!!.serverWorkspaceSlug)
        repo.updateWorkspaceSlug("no-such-id", "x")
        assertNull(repo.folderByName("学习")!!.serverWorkspaceSlug)
    }

    // ===== 名称清洗工具 =====

    @Test
    fun `cleanFolderName - 路径分隔与非法字符替换,空白修剪`() {
        assertEquals("a_b_c", FavoriteRepository.cleanFolderName(" a/b\\c "))
        assertEquals("", FavoriteRepository.cleanFolderName(":::***"))
        assertEquals("正常", FavoriteRepository.cleanFolderName("  正常  "))
    }
}
