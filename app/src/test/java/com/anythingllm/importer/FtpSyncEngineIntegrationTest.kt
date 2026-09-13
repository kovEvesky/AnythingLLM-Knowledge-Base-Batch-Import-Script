package com.anythingllm.importer

import com.anythingllm.importer.data.config.FtpConfig
import com.anythingllm.importer.data.library.LibraryEntryType
import com.anythingllm.importer.data.library.LibraryRepository
import com.anythingllm.importer.domain.ftp.FtpItemStatus
import com.anythingllm.importer.domain.ftp.FtpSyncEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files

/**
 * FtpSyncEngine × pyftpdlib 真实集成测试(v1.3 F1/F2/F3):
 * - 启动本机 `python -m pyftpdlib`(与交付脚本同栈);
 * - 归档文件+链接 → 引擎上传 → 校验远端目录结构与内容;
 * - 二次同步验证增量(无待同步条目)。
 * 环境无 Python 时自动跳过(Assume),不阻塞无 Python 的 CI。
 */
class FtpSyncEngineIntegrationTest {

    private lateinit var workDir: File
    private lateinit var ftpRoot: File
    private lateinit var repo: LibraryRepository
    private var serverProcess: Process? = null
    private var port: Int = 0

    @Before
    fun setUp() {
        assumeTrue(
            "Python 不可用,跳过 FTP 集成测试",
            runCatching {
                val p = ProcessBuilder("python", "--version").redirectErrorStream(true).start()
                p.waitFor() == 0
            }.getOrDefault(false),
        )
        workDir = Files.createTempDirectory("ftp-it").toFile()
        ftpRoot = File(workDir, "pc-root").apply { mkdirs() }
        repo = LibraryRepository(File(workDir, "library"))
        port = freePort()
        serverProcess = ProcessBuilder(
            "python", "-m", "pyftpdlib",
            "-i", "127.0.0.1",
            "-p", port.toString(),
            "-u", "sync", "-P", "sync123",
            "-w",
            "-d", ftpRoot.absolutePath,
        ).redirectErrorStream(true).start()
        waitForPort(port, timeoutMs = 15_000)
    }

    @After
    fun tearDown() {
        serverProcess?.destroy()
        serverProcess?.waitFor()
        workDir.deleteRecursively()
    }

    @Test
    fun `引擎真实上传文件与链接并镜像目录树,二次同步为增量`() = runBlocking {
        // 准备资料库:根级文件 + 子文件夹内文件 + 链接
        val folder = repo.createFolder("docs", null)
        val sub = repo.createFolder("sub", folder.id)
        val f1 = File(workDir, "a.txt").apply { writeText("hello v1.3 ftp") }
        val f2 = File(workDir, "b.md").apply { writeText("readme content") }
        repo.archiveFromCollect("e1", LibraryEntryType.FILE, "a.txt", null, f1, f1.length(), null)
        repo.archiveFromCollect("e2", LibraryEntryType.FILE, "b.md", null, f2, f2.length(), sub.id)
        repo.archiveFromCollect("l1", LibraryEntryType.LINK, "示例", "https://example.com/x", null, 0, folder.id)

        val engine = FtpSyncEngine(
            FtpConfig(host = "127.0.0.1", port = port, username = "sync", password = "sync123", remoteRoot = "Library"),
            repo,
        )
        engine.launch(this)
        while (engine.state.value.running) delay(200)
        val final = engine.state.value
        assertFalse("同步应在有限时间结束", final.running)
        assertEquals("全部条目成功", 3, final.successCount)
        assertEquals("无失败", 0, final.failedCount)
        assertTrue(final.items.all { it.status == FtpItemStatus.SUCCESS })

        // 远端结构校验
        assertTrue(File(ftpRoot, "Library/a.txt").readText() == "hello v1.3 ftp")
        assertTrue(File(ftpRoot, "Library/docs/sub/b.md").readText() == "readme content")
        assertTrue(File(ftpRoot, "Library/docs/示例.url").exists())
        assertTrue(File(ftpRoot, "Library/docs/示例.url").readText(Charsets.UTF_8).contains("URL=https://example.com/x"))

        // 增量:二次同步无待同步条目
        val second = FtpSyncEngine(
            FtpConfig(host = "127.0.0.1", port = port, username = "sync", password = "sync123", remoteRoot = "Library"),
            repo,
        )
        second.launch(this)
        while (second.state.value.running) delay(200)
        assertTrue(second.state.value.items.isEmpty())

        // 本地文件变更 → 重新待同步 → 重传覆盖
        File(ftpRoot, "Library/a.txt").writeText("stale")
        repo.entries().first { it.id == "e1" }.let { e ->
            File(e.localPath!!).writeText("hello v1.3 ftp CHANGED")
        }
        val third = FtpSyncEngine(
            FtpConfig(host = "127.0.0.1", port = port, username = "sync", password = "sync123", remoteRoot = "Library"),
            repo,
        )
        third.launch(this)
        while (third.state.value.running) delay(200)
        assertEquals(1, third.state.value.successCount)
        assertEquals("hello v1.3 ftp CHANGED", File(ftpRoot, "Library/a.txt").readText())
    }

    @Test
    fun `FTP 不可达时条目标记失败并给出明确信息`() = runBlocking {
        repo.archiveFromCollect("e1", LibraryEntryType.FILE, "a.txt", null, File(workDir, "a.txt").apply { writeText("x") }, 1, null)
        // 使用一个未监听端口
        val deadPort = freePort()
        val engine = FtpSyncEngine(
            FtpConfig(host = "127.0.0.1", port = deadPort, username = "sync", password = "sync123", remoteRoot = "Library"),
            repo,
        )
        engine.launch(this)
        while (engine.state.value.running) delay(200)
        val final = engine.state.value
        assertFalse(final.running)
        assertEquals(1, final.failedCount)
        assertTrue(final.items.first().message?.contains("FTP 连接失败") == true)
        // 未同步,可重试
        assertEquals(1, repo.pendingSync().size)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun waitForPort(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 500)
                    return
                }
            }
            Thread.sleep(200)
        }
        throw IllegalStateException("FTP 服务 $port 未在 $timeoutMs ms 内就绪")
    }
}
