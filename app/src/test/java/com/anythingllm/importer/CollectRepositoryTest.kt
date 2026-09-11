package com.anythingllm.importer

import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.CollectRepository
import com.anythingllm.importer.data.collect.EntrySource
import com.anythingllm.importer.data.collect.EntryStatus
import com.anythingllm.importer.data.collect.EntryType
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CollectRepositoryTest {

    private lateinit var dir: File
    private lateinit var repo: CollectRepository

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("collect-test").toFile()
        repo = CollectRepository(File(dir, "collect"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun entry(id: String, type: EntryType = EntryType.FILE, status: EntryStatus = EntryStatus.PENDING): CollectEntry =
        CollectEntry(
            id = id,
            type = type,
            source = if (type == EntryType.LINK) EntrySource.SHARE_LINK else EntrySource.SAF,
            fileName = "$id.txt",
            localPath = if (type == EntryType.FILE) {
                File(File(dir, "collect/files/20260911"), "f_$id").absolutePath
            } else {
                null
            },
            sizeBytes = 10,
            url = if (type == EntryType.LINK) "https://$id.com" else null,
            title = "$id",
            collectedAt = "2026-09-11T10:00:00+08:00",
        )

    /** 模拟接收方落盘:创建文件副本(父目录一并创建) */
    private fun writeFile(entry: CollectEntry) {
        val f = File(entry.localPath!!)
        f.parentFile?.mkdirs()
        f.writeText("content-${entry.id}")
    }

    @Test
    fun `add and read back`() {
        repo.add(entry("a"))
        val all = repo.all()
        assertEquals(1, all.size)
        assertEquals("a", all[0].id)
        assertEquals(EntryStatus.PENDING, all[0].status)
    }

    @Test
    fun `pending and marked filters`() {
        repo.add(entry("p1"))
        repo.add(entry("p2").copy(status = EntryStatus.MARKED, markFolder = "f", markWorkspace = "w"))
        assertEquals(1, repo.pending().size)
        assertEquals(1, repo.marked().size)
    }

    @Test
    fun `marked includes failed for retry - r15`() {
        // R15 修复:执行失败(含目标失效)的条目保留在已标记区,供取消/重选/重试
        repo.add(entry("a").copy(status = EntryStatus.FAILED, markFolder = "f", markWorkspace = "gone", error = "目标工作区已删除:gone"))
        repo.add(entry("b").copy(status = EntryStatus.EXECUTED, markFolder = "f", markWorkspace = "w"))
        assertEquals(1, repo.marked().size)
        assertEquals("a", repo.marked()[0].id)
        // 执行成功条目不进已标记区(可被清理)
        assertEquals(0, repo.pending().size)
    }

    @Test
    fun `update marks entry`() {
        repo.add(entry("a"))
        val updated = repo.update("a") { it.copy(status = EntryStatus.MARKED, markFolder = "docs", markWorkspace = "wsl") }
        assertNotNull(updated)
        assertEquals("docs", updated!!.markFolder)
        assertEquals(EntryStatus.MARKED, repo.all()[0].status)
    }

    @Test
    fun `update missing id returns null`() {
        assertNull(repo.update("nope") { it })
    }

    @Test
    fun `remove deletes entry and local file`() {
        val e = entry("a")
        writeFile(e)
        repo.add(e)
        assertTrue(repo.remove("a"))
        assertTrue(repo.all().isEmpty())
        assertFalse(File(e.localPath!!).exists())
    }

    @Test
    fun `removeAll deletes multiple`() {
        repo.add(entry("a"))
        repo.add(entry("b"))
        repo.add(entry("c"))
        repo.removeAll(listOf("a", "c"))
        assertEquals(listOf("b"), repo.all().map { it.id })
    }

    @Test
    fun `group by day`() {
        val today = entry("t")
        val other = entry("o").copy(collectedAt = "2026-09-10T08:00:00+08:00")
        repo.add(today)
        repo.add(other)
        val groups = repo.groupByDay(repo.all())
        assertEquals(2, groups.size)
        assertEquals("2026-09-11", groups[0].first)
        assertEquals("2026-09-10", groups[1].first)
    }

    @Test
    fun `corrupted entries file falls back to empty`() {
        val f = File(dir, "collect/entries.json")
        f.parentFile?.mkdirs()
        f.writeText("{broken json")
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun `cleanupExecutedFiles deletes only executed file copies`() {
        val exec = entry("e").copy(status = EntryStatus.EXECUTED)
        val pend = entry("p")
        writeFile(exec)
        writeFile(pend)
        repo.add(exec)
        repo.add(pend)
        repo.cleanupExecutedFiles()
        assertFalse(File(exec.localPath!!).exists())
        assertTrue(File(pend.localPath!!).exists())
        // 条目记录保留
        assertEquals(2, repo.all().size)
    }

    @Test
    fun `clear wipes entries and files`() {
        val e = entry("a")
        writeFile(e)
        repo.add(e)
        repo.clear()
        assertTrue(repo.all().isEmpty())
        assertFalse(File(e.localPath!!).exists())
    }
}
