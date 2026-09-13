package com.anythingllm.importer

import com.anythingllm.importer.data.library.LibraryEntryType
import com.anythingllm.importer.data.library.LibraryRepository
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
 * 资料库仓储单测(v1.3):
 * 文件夹 CRUD / 同级重名去重 / 删除级联上移 / 条目归档(文件复制+元数据)/ 移动 / 删除 / 待同步增量判定。
 */
class LibraryRepositoryTest {

    private lateinit var dir: File
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("lib-test").toFile()
        repo = LibraryRepository(File(dir, "library"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ===== 文件夹管理 =====

    @Test
    fun `createFolder - 根级与子级,同级重名自动追加序号`() {
        val root = repo.createFolder("文档", null)
        val dup = repo.createFolder("文档", null)
        assertEquals("文档", root.name)
        assertEquals("文档 (2)", dup.name)
        assertNull(root.parentId)
        assertEquals(2, repo.folders().size)

        val child = repo.createFolder("子目录", root.id)
        assertEquals(root.id, child.parentId)
        // 子级与根级同名不冲突
        val childDup = repo.createFolder("文档", root.id)
        assertEquals("文档", childDup.name)
    }

    @Test
    fun `renameFolder - 重命名生效,空名拒绝`() {
        val f = repo.createFolder("旧名", null)
        assertTrue(repo.renameFolder(f.id, "新名"))
        assertEquals("新名", repo.folders().first().name)
        assertFalse(repo.renameFolder(f.id, "   "))
        assertEquals("新名", repo.folders().first().name)
        assertFalse(repo.renameFolder("不存在", "x"))
    }

    @Test
    fun `deleteFolder - 子文件夹与条目上移到父级,不物理删文件`() {
        val root = repo.createFolder("根", null)
        val child = repo.createFolder("子", root.id)
        val grand = repo.createFolder("孙", child.id)
        val entry = repo.archiveFromCollect(
            id = "e1", type = LibraryEntryType.FILE, title = "a.txt",
            url = null, srcFile = tempFile("hello"), sizeBytes = 5, destFolderId = child.id,
        )
        assertTrue(entry.localPath?.let { File(it).exists() } == true)

        // 删除子文件夹:孙上移到根级,条目落到父级(root)
        assertTrue(repo.deleteFolder(child.id))
        assertNull(repo.folders().firstOrNull { it.id == child.id })
        assertEquals(root.id, repo.folders().first { it.id == grand.id }.parentId)
        val moved = repo.entries().first { it.id == "e1" }
        assertEquals(root.id, moved.folderId)
        // 文件副本仍在(不物理删)
        assertTrue(File(moved.localPath!!).exists())
    }

    // ===== 条目管理 =====

    @Test
    fun `archiveFromCollect - 文件复制到 library files 并建条目`() {
        val folder = repo.createFolder("文档", null)
        val src = tempFile("资料内容")
        val entry = repo.archiveFromCollect(
            id = "e1", type = LibraryEntryType.FILE, title = "资料.txt",
            url = null, srcFile = src, sizeBytes = 4, destFolderId = folder.id,
        )
        assertEquals("e1", entry.id)
        assertEquals(folder.id, entry.folderId)
        assertEquals("资料.txt", entry.title)
        assertNotNull(entry.localPath)
        assertTrue(File(entry.localPath!!).exists())
        assertEquals(src.length(), entry.sizeBytes)
        assertNull(entry.syncedAt)
        assertFalse(entry.isSynced)
    }

    @Test
    fun `archiveFromCollect - 链接条目不复制文件`() {
        val entry = repo.archiveFromCollect(
            id = "l1", type = LibraryEntryType.LINK, title = "示例",
            url = "https://example.com/a", srcFile = null, sizeBytes = 0, destFolderId = null,
        )
        assertNull(entry.localPath)
        assertEquals("https://example.com/a", entry.url)
    }

    @Test
    fun `archiveFromCollect - 文件名清洗,非法字符替换`() {
        val src = tempFile("x")
        val entry = repo.archiveFromCollect(
            id = "e1", type = LibraryEntryType.FILE, title = "a/b:c*d.txt",
            url = null, srcFile = src, sizeBytes = 1, destFolderId = null,
        )
        val localName = File(entry.localPath!!).name
        assertFalse(localName.contains('/'))
        assertFalse(localName.contains(':'))
        assertTrue(localName.startsWith("e1_"))
    }

    @Test
    fun `moveEntry - 改目标文件夹并重置同步状态`() {
        val f1 = repo.createFolder("一", null)
        val f2 = repo.createFolder("二", null)
        repo.archiveFromCollect("e1", LibraryEntryType.FILE, "a.txt", null, tempFile("x"), 1, f1.id)
        repo.markSynced("e1", "Library/一/a.txt", 1)
        assertTrue(repo.entries().first().isSynced)

        assertTrue(repo.moveEntry("e1", f2.id))
        val moved = repo.entries().first()
        assertEquals(f2.id, moved.folderId)
        assertNull(moved.syncedAt) // 移动后需重传
        assertTrue(repo.moveEntry("e1", null))
        assertNull(repo.entries().first().folderId)
        assertFalse(repo.moveEntry("不存在", f1.id))
    }

    @Test
    fun `deleteEntry - 删元数据并物理删副本`() {
        val src = tempFile("x")
        val entry = repo.archiveFromCollect("e1", LibraryEntryType.FILE, "a.txt", null, src, 1, null)
        val local = File(entry.localPath!!)
        assertTrue(local.exists())
        assertTrue(repo.deleteEntry("e1"))
        assertTrue(repo.entries().isEmpty())
        assertFalse(local.exists())
        assertFalse(repo.deleteEntry("e1"))
    }

    // ===== 待同步增量判定 =====

    @Test
    fun `pendingSync - 未同步与大小变化需重传,已同步未变跳过`() {
        val src = tempFile("hello")
        val e1 = repo.archiveFromCollect("e1", LibraryEntryType.FILE, "a.txt", null, src, 5, null)
        repo.archiveFromCollect("l1", LibraryEntryType.LINK, "链接", "https://example.com", null, 0, null)

        // 初始:文件+链接都未同步
        assertEquals(setOf("e1", "l1"), repo.pendingSync().map { it.id }.toSet())

        // 文件同步后:只剩链接
        repo.markSynced("e1", "Library/a.txt", 5)
        assertEquals(listOf("l1"), repo.pendingSync().map { it.id })

        // 链接同步后:无待同步
        repo.markSynced("l1", "Library/链接.url", 0)
        assertTrue(repo.pendingSync().isEmpty())

        // 本地文件变大:文件重新待同步(改的是库内副本)
        File(e1.localPath!!).writeText("hello world")
        assertEquals(listOf("e1"), repo.pendingSync().map { it.id })
    }

    // ===== 路径链 =====

    @Test
    fun `pathOf - 根到目标文件夹的名称链`() {
        val root = repo.createFolder("根", null)
        val child = repo.createFolder("子", root.id)
        val grand = repo.createFolder("孙", child.id)
        assertEquals(emptyList<String>(), repo.pathOf(null))
        assertEquals(listOf("根"), repo.pathOf(root.id))
        assertEquals(listOf("根", "子"), repo.pathOf(child.id))
        assertEquals(listOf("根", "子", "孙"), repo.pathOf(grand.id))
        assertEquals(emptyList<String>(), repo.pathOf("不存在"))
    }

    @Test
    fun `childrenOf - 目录内容分区排序`() {
        val root = repo.createFolder("根", null)
        val fB = repo.createFolder("B目录", root.id)
        val fA = repo.createFolder("A目录", root.id)
        repo.archiveFromCollect("e1", LibraryEntryType.FILE, "b.txt", null, tempFile("x"), 1, root.id)
        repo.archiveFromCollect("e2", LibraryEntryType.LINK, "a链接", "https://a.com", null, 0, root.id)
        repo.archiveFromCollect("e3", LibraryEntryType.FILE, "other.txt", null, tempFile("y"), 1, fA.id)

        val (folders, entries) = repo.childrenOf(root.id)
        assertEquals(listOf("A目录", "B目录"), folders.map { it.name }) // 子文件夹按名排序
        assertEquals(listOf("a链接", "b.txt"), entries.map { it.title }) // 条目按名排序
        val (_, faEntries) = repo.childrenOf(fA.id)
        assertEquals(listOf("other.txt"), faEntries.map { it.title })
        // 根级内容
        val (rootFolders, _) = repo.childrenOf(null)
        assertEquals(listOf("根"), rootFolders.map { it.name })
    }

    // ===== 损坏容错 =====

    @Test
    fun `损坏的 library json 回退为空数据`() {
        repo.archiveFromCollect("e1", LibraryEntryType.FILE, "a.txt", null, tempFile("x"), 1, null)
        File(dir, "library/library.json").writeText("{broken")
        assertTrue(repo.folders().isEmpty())
        assertTrue(repo.entries().isEmpty())
        // 写入恢复正常
        repo.createFolder("恢复", null)
        assertEquals(1, repo.folders().size)
    }

    private fun tempFile(content: String): File {
        val f = File(dir, "src_${System.nanoTime()}.tmp")
        f.writeText(content, Charsets.UTF_8)
        return f
    }
}
