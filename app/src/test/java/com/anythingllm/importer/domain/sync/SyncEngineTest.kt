package com.anythingllm.importer.domain.sync

import com.anythingllm.importer.data.api.AnythingLlmClientFactory
import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.CollectRepository
import com.anythingllm.importer.data.collect.EntrySource
import com.anythingllm.importer.data.collect.EntryStatus
import com.anythingllm.importer.data.collect.EntryType
import com.anythingllm.importer.data.config.AppConfig
import com.anythingllm.importer.data.import.LocalFileBodyProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SyncEngine 契约测试(MockWebServer):
 * - 链接条目 → upload-link(服务器抓取),成功回填 EXECUTED + title/location;
 * - 同步模式文件(无工作区) → 纯上传 document/upload/{folder};
 * - 断点续传:EXECUTED 条目不重复执行。
 */
class SyncEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var api: com.anythingllm.importer.data.api.AnythingLLMApi
    private lateinit var dir: File
    private lateinit var repo: CollectRepository
    private val factory = AnythingLlmClientFactory()
    private val config = AppConfig(detectDuplicates = false, uploadRetryCount = 1)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = factory.create(server.url("/").toString(), "KEY", readTimeoutSec = 5)
        dir = Files.createTempDirectory("sync-test").toFile()
        repo = CollectRepository(File(dir, "collect"))
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun linkEntry(id: String, status: EntryStatus = EntryStatus.MARKED, workspace: String? = "wsl") =
        CollectEntry(
            id = id,
            type = EntryType.LINK,
            source = EntrySource.SHARE_LINK,
            url = "https://example.com/$id",
            title = "example.com",
            collectedAt = "2026-09-11T10:00:00+08:00",
            status = status,
            markFolder = "docs",
            markWorkspace = workspace,
        )

    private fun fileEntry(id: String, status: EntryStatus = EntryStatus.MARKED, workspace: String? = null) =
        CollectEntry(
            id = id,
            type = EntryType.FILE,
            source = EntrySource.SHARE_FILE,
            fileName = "$id.txt",
            localPath = File(dir, "f_$id").absolutePath,
            sizeBytes = 4,
            collectedAt = "2026-09-11T10:00:00+08:00",
            status = status,
            markFolder = "docs",
            markWorkspace = workspace,
        )

    private fun engine() = SyncEngine(api, config, LocalFileBodyProvider(), repo)

    @Test
    fun `link entry calls upload-link and writes back success`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"documents":[{"title":"example.com_1_.html","location":"custom-documents/url-example.com_1_-abc.json"}]}""",
            ),
        )
        val entry = linkEntry("l1")
        repo.add(entry)

        val engine = engine()
        engine.launch(this, listOf(entry))
        engine.state.first { it.isTerminal }

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/document/upload-link", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"link\":[\"https://example.com/l1\"]"))
        assertTrue(body.contains("\"addToWorkspaces\":\"wsl\""))

        val saved = repo.all().single()
        assertEquals(EntryStatus.EXECUTED, saved.status)
        assertEquals("example.com_1_.html", saved.serverTitle)
        assertEquals("custom-documents/url-example.com_1_-abc.json", saved.serverLocation)
    }

    @Test
    fun `link without workspace omits addToWorkspaces (sync mode)`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"documents":[{"title":"t.html","location":"custom-documents/url-t-1.json"}]}""",
            ),
        )
        val entry = linkEntry("l2", workspace = null)
        repo.add(entry)

        val engine = engine()
        engine.launch(this, listOf(entry))
        engine.state.first { it.isTerminal }

        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("addToWorkspaces"))
        assertEquals(EntryStatus.EXECUTED, repo.all().single().status)
    }

    @Test
    fun `link failure marks failed`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":false,"error":"No URL content found at https://bad.example.com"}""",
            ),
        )
        val entry = linkEntry("l3")
        repo.add(entry)

        val engine = engine()
        engine.launch(this, listOf(entry))
        engine.state.first { it.isTerminal }

        assertEquals(EntryStatus.FAILED, repo.all().single().status)
        assertTrue(repo.all().single().error.orEmpty().contains("No URL content"))
    }

    @Test
    fun `sync mode file uploads to folder`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"documents":[{"location":"docs/f_1-u.txt"}]}""",
            ),
        )
        val entry = fileEntry("f1") // 无 workspace → 同步模式
        File(entry.localPath!!).writeText("data")
        repo.add(entry)

        val engine = engine()
        engine.launch(this, listOf(entry))
        engine.state.first { it.isTerminal }

        val req = server.takeRequest()
        assertEquals("/api/v1/document/upload/docs", req.path)
        assertEquals(EntryStatus.EXECUTED, repo.all().single().status)
    }

    @Test
    fun `executed entries are skipped (resume)`() = runTest {
        val entry = linkEntry("done", status = EntryStatus.EXECUTED)
        repo.add(entry)

        val engine = engine()
        engine.launch(this, listOf(entry))
        // 断点续传:EXECUTED 不入执行队列,无任何请求
        assertEquals(0, server.requestCount)
        assertEquals(EntryStatus.EXECUTED, repo.all().single().status)
    }
}
