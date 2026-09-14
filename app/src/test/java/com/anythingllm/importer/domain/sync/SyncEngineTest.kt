package com.anythingllm.importer.domain.sync

import com.anythingllm.importer.data.api.AnythingLlmClientFactory
import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.CollectRepository
import com.anythingllm.importer.data.collect.EntrySource
import com.anythingllm.importer.data.collect.EntryStatus
import com.anythingllm.importer.data.collect.EntryType
import com.anythingllm.importer.data.config.AppConfig
import com.anythingllm.importer.data.favorite.FavoriteRepository
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
 * - v1.9 收藏夹驱动:addToWorkspaces = 收藏夹 serverWorkspaceSlug(ensure 回填);
 * - 同步模式文件(无工作区) → 纯上传 document/upload/{folder};
 * - 断点续传:EXECUTED 条目不重复执行。
 */
class SyncEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var api: com.anythingllm.importer.data.api.AnythingLLMApi
    private lateinit var dir: File
    private lateinit var repo: CollectRepository
    private lateinit var favRepo: FavoriteRepository
    private val factory = AnythingLlmClientFactory()
    private val config = AppConfig(detectDuplicates = false, uploadRetryCount = 1)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = factory.create(server.url("/").toString(), "KEY", readTimeoutSec = 5)
        dir = Files.createTempDirectory("sync-test").toFile()
        repo = CollectRepository(File(dir, "collect"))
        favRepo = FavoriteRepository(File(dir, "favorites"))
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    /** 建收藏夹并可选回填工作区 slug(模拟 ensure 后) */
    private fun folder(slug: String? = null): String {
        val f = favRepo.createFolder("docs", 0xFF14B8A6)
        if (slug != null) favRepo.updateWorkspaceSlug(f.id, slug)
        return f.id
    }

    private fun linkEntry(id: String, status: EntryStatus = EntryStatus.MARKED, folderId: String? = null) =
        CollectEntry(
            id = id,
            type = EntryType.LINK,
            source = EntrySource.SHARE_LINK,
            url = "https://example.com/$id",
            title = "example.com",
            collectedAt = "2026-09-11T10:00:00+08:00",
            status = status,
            markFolder = "docs",
            markFolderId = folderId,
        )

    private fun fileEntry(id: String, status: EntryStatus = EntryStatus.MARKED, folderId: String? = null) =
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
            markFolderId = folderId,
        )

    private fun engine() = SyncEngine(api, config, LocalFileBodyProvider(), repo, favRepo)

    @Test
    fun `link entry calls upload-link and writes back success`() = runTest {
        // ensure 已存在:docs 文件夹 + slug=wsl 同名工作区 → 只复用不新建
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"localFiles":{"name":"","type":"folder","items":[{"name":"docs","type":"folder"}]}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"workspaces":[{"id":1,"name":"docs","slug":"wsl"}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"documents":[{"title":"example.com_1_.html","location":"custom-documents/url-example.com_1_-abc.json"}]}""",
            ),
        )
        val fid = folder(slug = "wsl")
        val entry = linkEntry("l1", folderId = fid)
        repo.add(entry)

        val engine = engine()
        engine.launch(this, listOf(entry))
        engine.state.first { it.isTerminal }

        var last: okhttp3.mockwebserver.RecordedRequest? = null
        repeat(3) { last = server.takeRequest() }
        val req = last!!
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
    fun `link without workspace slug omits addToWorkspaces (sync mode)`() = runTest {
        // ensure:docs 文件夹已存在;无同名工作区 → createWorkspace 后回填(此处用新 slug 校验省略逻辑需匹配)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"localFiles":{"name":"","type":"folder","items":[{"name":"docs","type":"folder"}]}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"workspaces":[]}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":1,"name":"docs","slug":"docs-2"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"documents":[{"title":"t.html","location":"custom-documents/url-t-1.json"}]}""",
            ),
        )
        val fid = folder(slug = null)
        val entry = linkEntry("l2", folderId = fid)
        repo.add(entry)

        val engine = engine()
        engine.launch(this, listOf(entry))
        engine.state.first { it.isTerminal }

        var last: okhttp3.mockwebserver.RecordedRequest? = null
        repeat(4) { last = server.takeRequest() }
        val body = last!!.body.readUtf8()
        // createWorkspace 回填后链接应加入 docs-2;此用例验证 ensure 回填生效
        assertTrue(body.contains("\"addToWorkspaces\":\"docs-2\""))
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
        val entry = fileEntry("f1") // 无收藏夹 slug → 同步模式
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

    @Test
    fun `ensure folders creates folder and workspace then backfills slug`() = runTest {
        // documents 树:无 docs 文件夹 → 触发 createFolder
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"localFiles":{"name":"","type":"folder","items":[]}}""",
            ),
        )
        // workspaces:无同名工作区 → 触发 createWorkspace
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"workspaces":[]}""",
            ),
        )
        // createFolder 成功
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true}""",
            ),
        )
        // createWorkspace 返回新 slug
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":1,"name":"docs","slug":"docs-1"}""",
            ),
        )
        // upload-link 成功
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"documents":[{"title":"t.html","location":"custom-documents/url-t-1.json"}]}""",
            ),
        )
        val fid = folder(slug = null)
        val entry = linkEntry("l4", folderId = fid)
        repo.add(entry)

        val engine = engine()
        engine.launch(this, listOf(entry))
        engine.state.first { it.isTerminal }

        val saved = repo.all().single()
        assertEquals(EntryStatus.EXECUTED, saved.status)
        // ensure 回填 slug,链接加入该工作区
        assertEquals("docs-1", favRepo.folderById(fid)?.serverWorkspaceSlug)
        var last: okhttp3.mockwebserver.RecordedRequest? = null
        repeat(5) { last = server.takeRequest() }
        val body = last!!.body.readUtf8()
        assertTrue(body.contains("\"addToWorkspaces\":\"docs-1\""))
    }
}
