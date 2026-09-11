package com.anythingllm.importer.data.log

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

/**
 * 导入日志仓储单测(FR-13):
 * 行格式可解析/字段齐全/不落敏感字段、倒序读取、损坏行容错、删除、30 天清理。
 */
class ImportLogTest {

    private lateinit var dir: File
    private lateinit var repo: ImportLogRepository

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("import-log-test").toFile()
        repo = ImportLogRepository(File(dir, "logs"), retentionDays = 30)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun entry(
        action: String = "add",
        file: String = "需求文档.txt",
        result: String = "success",
        error: String? = null,
    ) = ImportLogEntry(
        time = "2026-09-10T22:30:00.000+08:00",
        action = action,
        file = file,
        folder = "custom-documents",
        workspace = "wsl",
        storageName = "u9700u6c42u6587u6863.txt",
        sizeBytes = 123,
        result = result,
        error = error,
        retries = 0,
    )

    @Test
    fun `追加写生成当日文件且行可解析`() {
        repo.append(entry())
        repo.append(entry(result = "failed", error = "服务器返回错误(500)"))

        val files = repo.listFiles()
        assertEquals(1, files.size)
        assertEquals(repo.dailyFileName(), files[0].name)
        assertEquals(2, files[0].lineCount)

        val entries = repo.readEntries(files[0].name)
        assertEquals(2, entries.size)
        // 倒序:最新在前
        assertEquals("failed", entries[0].result)
        assertEquals("服务器返回错误(500)", entries[0].error)
        assertEquals("需求文档.txt", entries[1].file)
        assertEquals("u9700u6c42u6587u6863.txt", entries[1].storageName)
        assertEquals("custom-documents", entries[1].folder)
        assertEquals("wsl", entries[1].workspace)
        assertEquals(123L, entries[1].sizeBytes)
    }

    @Test
    fun `行内容为单行 JSON 且不落敏感字段`() {
        repo.append(entry())
        val raw = repo.exportContent(repo.dailyFileName()).trim()
        // 单行 JSON 对象
        assertTrue(raw.startsWith("{") && raw.endsWith("}"))
        assertEquals(1, raw.lineSequence().count())
        // 无换行符内嵌、无 Key/baseUrl 字段
        assertFalse(raw.contains("\n"))
        assertFalse(raw.contains("apiKey"))
        assertFalse(raw.contains("baseUrl"))
        assertFalse(raw.contains("Bearer"))
        // 关键字段齐全
        assertTrue(raw.contains("\"time\""))
        assertTrue(raw.contains("\"source\":\"import\""))
        assertTrue(raw.contains("\"file\":\"需求文档.txt\""))
        assertTrue(raw.contains("\"result\":\"success\""))
    }

    @Test
    fun `损坏行跳过不中断`() {
        val f = File(dir, "logs").apply { mkdirs() }
        val file = File(f, repo.dailyFileName())
        file.writeText(
            """{"time":"t","level":"info","source":"import","action":"add","file":"a.txt","folder":"f","workspace":"w","storageName":"a.txt","sizeBytes":1,"result":"success"}""" + "\n" +
                "this-is-not-json\n" +
                """{"time":"t2","level":"info","source":"import","action":"add","file":"b.txt","folder":"f","workspace":"w","storageName":"b.txt","sizeBytes":2,"result":"failed","error":"x"}""" + "\n",
            Charsets.UTF_8,
        )
        val entries = repo.readEntries(file.name)
        assertEquals(2, entries.size)
        assertEquals("b.txt", entries[0].file)
        assertEquals("a.txt", entries[1].file)
    }

    @Test
    fun `多文件按日期倒序排列`() {
        val logs = File(dir, "logs").apply { mkdirs() }
        File(logs, "import-20260908.jsonl").writeText("x\n")
        File(logs, "import-20260910.jsonl").writeText("y\n")
        File(logs, "import-20260909.jsonl").writeText("z\n")
        File(logs, "other.txt").writeText("ignore\n")

        val files = repo.listFiles()
        assertEquals(3, files.size)
        assertEquals(listOf("20260910", "20260909", "20260908"), files.map { it.date })
        assertEquals(1, files[0].lineCount)
    }

    @Test
    fun `清理删除超过保留天数文件`() {
        val logs = File(dir, "logs").apply { mkdirs() }
        val fmt = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
        val today = java.time.LocalDate.now().format(fmt)
        val yesterday = java.time.LocalDate.now().minusDays(1).format(fmt)
        val dayBefore = java.time.LocalDate.now().minusDays(2).format(fmt)
        File(logs, "import-$dayBefore.jsonl").writeText("x\n")
        File(logs, "import-$yesterday.jsonl").writeText("y\n")
        File(logs, "import-$today.jsonl").writeText("z\n")

        // 保留 0 天:仅今天保留,昨天与更早删除
        val shortRepo = ImportLogRepository(logs, retentionDays = 0)
        val removed = shortRepo.cleanupOld()
        assertEquals(2, removed)
        assertEquals(listOf(today), shortRepo.listFiles().map { it.date })
    }

    @Test
    fun `删除单个与全部`() {
        val logs = File(dir, "logs").apply { mkdirs() }
        File(logs, "import-20260908.jsonl").writeText("x\n")
        File(logs, "import-20260909.jsonl").writeText("y\n")

        assertTrue(repo.delete("import-20260908.jsonl"))
        assertEquals(1, repo.listFiles().size)

        assertEquals(1, repo.deleteAll())
        assertEquals(0, repo.listFiles().size)
    }

    @Test
    fun `导出单文件与全部合并`() {
        val logs = File(dir, "logs").apply { mkdirs() }
        File(logs, "import-20260908.jsonl").writeText("line-old\n", Charsets.UTF_8)
        File(logs, "import-20260910.jsonl").writeText("line-new\n", Charsets.UTF_8)

        assertEquals("line-old\n", repo.exportContent("import-20260908.jsonl"))
        // 全部合并按日期升序(旧→新),保持时间线
        assertEquals("line-old\nline-new\n", repo.exportAllContent())
    }

    @Test
    fun `不存在文件读取返回空`() {
        assertNull(repo.listFiles().firstOrNull { false })
        assertEquals(0, repo.readEntries("import-20990101.jsonl").size)
        assertEquals("", repo.exportContent("import-20990101.jsonl"))
        assertFalse(repo.delete("import-20990101.jsonl"))
    }
}
