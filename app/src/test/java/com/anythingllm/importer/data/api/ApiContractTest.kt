package com.anythingllm.importer.data.api

import com.anythingllm.importer.data.api.dto.ChatRequest
import com.anythingllm.importer.data.api.dto.CreateFolderRequest
import com.anythingllm.importer.data.api.dto.CreateWorkspaceRequest
import com.anythingllm.importer.data.api.dto.RemoveDocumentsRequest
import com.anythingllm.importer.data.api.dto.RemoveFolderRequest
import com.anythingllm.importer.data.api.dto.UpdateEmbeddingsRequest
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * API 契约集成测试(MockWebServer):
 * 端点路径/方法/鉴权头/请求体结构与 PC 版对齐(开发文档 §4.2)。
 * 响应 JSON 结构与 2026-09-10 实测本机实例一致。
 */
class ApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AnythingLLMApi
    private val factory = AnythingLlmClientFactory()
    private val API_KEY = "TEST-KEY-123"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = factory.create(server.url("/").toString(), API_KEY, readTimeoutSec = 5)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ===== 1. ping =====
    @Test
    fun `ping-路径方法与鉴权头`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"online":true}"""))
        val resp = api.ping()
        assertTrue(resp.online)
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/api/ping", r.path)
        assertEquals("Bearer $API_KEY", r.getHeader("Authorization"))
    }

    // ===== 2. auth =====
    @Test
    fun `auth-解析authenticated`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"authenticated":true}"""))
        assertTrue(api.auth().authenticated)
        assertEquals("/api/v1/auth", server.takeRequest().path)
    }

    // ===== 3. workspaces =====
    @Test
    fun `workspaces-解析列表`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"workspaces":[{"id":30,"name":"wsl","slug":"wsl"},{"id":31,"name":"1","slug":"1"}]}""",
            ),
        )
        val list = api.workspaces().workspaces
        assertEquals(2, list.size)
        assertEquals("wsl", list[0].slug)
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/api/v1/workspaces", r.path)
    }

    // ===== 4. create workspace =====
    @Test
    fun `createWorkspace-请求体与响应`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"id":40,"name":"新工作区","slug":"new-ws"}"""),
        )
        val ws = api.createWorkspace(CreateWorkspaceRequest("新工作区"))
        assertEquals("new-ws", ws.slug)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/v1/workspace/new", r.path)
        assertEquals("""{"name":"新工作区"}""", r.body.readUtf8())
    }

    // ===== 5. workspace detail(数组结构,metadata 为 JSON 字符串) =====
    @Test
    fun `workspaceDetail-解析数组结构与docpath`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"workspace":[{"id":33,"name":"wsl","slug":"wsl",
                  "documents":[{"id":1,"docpath":"1/02WSLu73afu5883u914du7f6eu6307u5357.md-e41fa1ad.json",
                   "metadata":"{\"id\":\"e41fa1ad\",\"title\":\"02WSL环境配置指南.md\"}"}]}]}
                """.trimIndent(),
            ),
        )
        val detail = api.workspaceDetail("wsl")
        assertEquals(1, detail.workspace.size)
        val doc = detail.workspace[0].documents[0]
        assertEquals("1/02WSLu73afu5883u914du7f6eu6307u5357.md-e41fa1ad.json", doc.docpath)
        assertTrue(doc.metadata?.contains("02WSL环境配置指南.md") == true)
        assertEquals("/api/v1/workspace/wsl", server.takeRequest().path)
    }

    // ===== 6. documents 文档树 =====
    @Test
    fun `documents-解析文件夹与文件节点`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"localFiles":{"name":"documents","type":"folder","items":[
                  {"name":"custom-documents","type":"folder","items":[
                    {"name":"01u6df1u6d77u4e16u754c.txt-uuid.json","type":"file","title":"01深海世界.txt"}
                  ]},
                  {"name":"555","type":"folder","items":[]}
                ]}}
                """.trimIndent(),
            ),
        )
        val root = api.documents().localFiles
        assertNotNull(root)
        val folders = root!!.items.orEmpty().filter { it.type == "folder" }
        assertEquals(listOf("custom-documents", "555"), folders.map { it.name })
        assertEquals("01深海世界.txt", folders[0].items?.get(0)?.title)
    }

    // ===== 7/8. create / remove folder =====
    @Test
    fun `createFolder-请求体与响应`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"message":null}"""))
        val resp = api.createFolder(CreateFolderRequest("new-folder"))
        assertTrue(resp.success)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/v1/document/create-folder", r.path)
        assertEquals("""{"name":"new-folder"}""", r.body.readUtf8())
    }

    @Test
    fun `removeFolder-请求体与响应`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"message":"ok"}"""))
        val resp = api.removeFolder(RemoveFolderRequest("new-folder"))
        assertTrue(resp.success)
        val r = server.takeRequest()
        assertEquals("DELETE", r.method)
        assertEquals("/api/v1/document/remove-folder", r.path)
        assertEquals("""{"name":"new-folder"}""", r.body.readUtf8())
    }

    // ===== 9. upload to workspace: multipart 顺序与 filename =====
    @Test
    fun `uploadToWorkspace-multipart结构与顺序`() = runTest {
        server.enqueue(uploadResponse())
        val fileBody = "契约测试文件内容".toRequestBody("application/octet-stream".toMediaType())
        val body = UploadBodyFactory.build(
            metadataJson = """{"title":"契约测试.txt"}""",
            storageName = "u5951u7ea6u6d4bu8bd5.txt",
            fileBody = fileBody,
            boundary = "AioBoundaryTest123",
        )
        val resp = api.uploadToWorkspace("wsl", body)
        assertEquals("wsl/custom.pdf-uuid.json", resp.documents[0].location)
        assertEquals("契约测试.txt", resp.documents[0].title)

        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/v1/workspace/wsl/upload", r.path)
        val contentType = r.getHeader("Content-Type")!!
        assertTrue(contentType.startsWith("multipart/form-data"))
        assertTrue(contentType.contains("boundary=AioBoundaryTest123"))

        val bodyText = r.body.readUtf8()
        // 关键约束:metadata 必须出现在 file 之前
        val metadataIdx = bodyText.indexOf("""name="metadata"""")
        val fileIdx = bodyText.indexOf("""name="file"; filename="u5951u7ea6u6d4bu8bd5.txt"""")
        assertTrue("metadata part 缺失", metadataIdx >= 0)
        assertTrue("file part 缺失", fileIdx >= 0)
        assertTrue("metadata 必须在 file 之前", metadataIdx < fileIdx)
        // file part 内容与类型
        assertTrue(bodyText.contains("""Content-Type: application/octet-stream"""))
        assertTrue(bodyText.contains("契约测试文件内容"))
        // metadata 部分无 Content-Type 头(与 PC 版一致)
        val metadataSegment = bodyText.substring(metadataIdx, fileIdx)
        assertTrue(!metadataSegment.contains("Content-Type"))
    }

    // ===== 10. upload to folder: URL 编码 =====
    @Test
    fun `uploadToFolder-folder路径URL编码`() = runTest {
        server.enqueue(uploadResponse())
        val fileBody = "x".toRequestBody("application/octet-stream".toMediaType())
        val body = UploadBodyFactory.build("""{"title":"a.txt"}""", "a.txt", fileBody)
        api.uploadToFolder("my folder", body)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        // @Path 默认整段 URL 编码:空格 → %20
        assertEquals("/api/v1/document/upload/my%20folder", r.path)
    }

    // ===== 11. update-embeddings =====
    @Test
    fun `updateEmbeddings-请求体adds与deletes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"message":"ok"}"""))
        val resp = api.updateEmbeddings(
            "wsl",
            UpdateEmbeddingsRequest(adds = listOf("1/a.json"), deletes = listOf("1/b.json")),
        )
        assertTrue(resp.success)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/v1/workspace/wsl/update-embeddings", r.path)
        assertEquals("""{"adds":["1/a.json"],"deletes":["1/b.json"]}""", r.body.readUtf8())
    }

    // ===== 12. remove-documents(带文件夹前缀) =====
    @Test
    fun `removeDocuments-请求体names带前缀`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"message":"ok"}"""))
        val resp = api.removeDocuments(
            RemoveDocumentsRequest(names = listOf("custom-documents/a.json")),
        )
        assertTrue(resp.success)
        val r = server.takeRequest()
        assertEquals("DELETE", r.method)
        assertEquals("/api/v1/system/remove-documents", r.path)
        assertEquals("""{"names":["custom-documents/a.json"]}""", r.body.readUtf8())
    }

    // ===== 13. chat query =====
    @Test
    fun `chat-query模式请求体`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"textResponse":"答案","sources":[{"text":"src","title":"t","docpath":"1/a.json"}]}""",
            ),
        )
        val resp = api.chat("wsl", ChatRequest(message = "测试问题", mode = "query", stream = false))
        assertEquals("答案", resp.textResponse)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/v1/workspace/wsl/chat", r.path)
        assertEquals("""{"message":"测试问题","mode":"query","stream":false}""", r.body.readUtf8())
    }

    // ===== 错误场景:401 与 404 =====
    @Test
    fun `非2xx抛HttpException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Invalid API Key"}"""))
        val e = runCatching { api.auth() }.exceptionOrNull()
        assertTrue(e is retrofit2.HttpException)
        assertEquals(401, (e as retrofit2.HttpException).code())
    }

    // ===== 未知字段容忍 =====
    @Test
    fun `响应含未知字段不崩溃`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"workspaces":[{"id":1,"name":"a","slug":"a","unexpectedField":{"x":1},"extra":"y"}]}""",
            ),
        )
        val list = api.workspaces().workspaces
        assertEquals(1, list.size)
    }

    private fun uploadResponse() = MockResponse().setResponseCode(200).setBody(
        """{"success":true,"error":null,"documents":[{"id":"uuid","title":"契约测试.txt",
           "location":"wsl/custom.pdf-uuid.json","wordCount":6,"isDirectUpload":false}]}""",
    )
}
