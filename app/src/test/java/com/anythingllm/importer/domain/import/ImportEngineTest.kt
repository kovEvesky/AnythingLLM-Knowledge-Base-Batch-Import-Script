package com.anythingllm.importer.domain.import

import com.anythingllm.importer.data.api.AnythingLLMApi
import com.anythingllm.importer.data.api.AnythingLlmClientFactory
import com.anythingllm.importer.data.config.AppConfig
import com.anythingllm.importer.data.config.DuplicateAction
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 导入引擎集成测试:引擎在 Dispatchers.Default(真实时间)上运行,
 * 退避/超时均按真实时长执行(参数调小以加速);MockWebServer 模拟 AnythingLLM。
 */
class ImportEngineTest {

    private lateinit var harness: ServerHarness
    private lateinit var engine: ImportEngine
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val textBody = "文件内容".toByteArray().toRequestBody("text/plain".toMediaType())

    @Before
    fun setUp() {
        harness = ServerHarness().apply { start() }
    }

    @After
    fun tearDown() {
        engineScope.cancel()
        harness.server.shutdown()
    }

    private fun newEngine(
        detectDuplicates: Boolean = false,
        verifyTimeoutSec: Int = 300,
        uploadRetryCount: Int = 2,
        importConcurrency: Int = 2,
        uploadRetryBaseDelaySec: Int = 1,
        verifyBaseDelaySec: Int = 1,
        verifyMaxDelaySec: Int = 2,
    ): ImportEngine = ImportEngine(
        api = harness.api(),
        bodyProvider = FileBodyProvider { _, _ -> textBody },
        config = AppConfig(
            detectDuplicates = detectDuplicates,
            verifyTimeoutSec = verifyTimeoutSec,
            uploadRetryCount = uploadRetryCount,
            importConcurrency = importConcurrency,
            uploadRetryBaseDelaySec = uploadRetryBaseDelaySec,
            verifyBaseDelaySec = verifyBaseDelaySec,
            verifyMaxDelaySec = verifyMaxDelaySec,
        ),
    )

    private fun target(name: String = "a.txt"): ImportTarget =
        ImportTarget(
            uriString = "content://test/$name",
            displayName = name,
            storageName = "uXXXX.txt",
            sizeBytes = 100,
            folder = "custom-documents",
            workspaceSlug = "w",
        )

    private fun awaitTerminal(timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (engine.state.value.phase == RunPhase.RUNNING || engine.state.value.phase == RunPhase.PAUSED) {
            assertTrue("等待引擎结束超时", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
    }

    // ===== 1. 成功流:上传→嵌入→轮询验证 =====

    @Test
    fun `成功流-上传嵌入轮询验证全通过`() = runTest {
        engine = newEngine()
        engine.launch(engineScope, listOf(target()), onDuplicate = { error("不应触发查重") })

        awaitTerminal()
        val s = engine.state.value
        assertEquals(RunPhase.FINISHED, s.phase)
        assertEquals(1, s.successCount)
        assertEquals(0, s.failedCount)
        assertEquals("custom-documents/f1-uuid.json", s.items[0].location)
        assertTrue("轮询至少 2 次", harness.detailCalls.get() >= 2)

        // 上传 multipart:metadata 在 file 之前
        val uploadBody = harness.uploadBodies.single()
        assertTrue(uploadBody.indexOf("name=\"metadata\"") < uploadBody.indexOf("name=\"file\""))
        assertTrue(uploadBody.contains("filename=\"uXXXX.txt\""))
        assertTrue(uploadBody.contains("\"title\":\"a.txt\""))
        // update-embeddings adds=[location]
        val embed = harness.jsonRequests.first { it.first.endsWith("/update-embeddings") }.second
        assertTrue(embed.contains("\"adds\":[\"custom-documents/f1-uuid.json\"]"))
    }

    // ===== 2. 上传失败自动重试 =====

    @Test
    fun `上传失败自动重试后成功`() = runTest {
        harness.failUploads = 2
        engine = newEngine()
        engine.launch(engineScope, listOf(target()), onDuplicate = { error("不应触发查重") })

        awaitTerminal()
        val s = engine.state.value
        assertEquals(RunPhase.FINISHED, s.phase)
        assertEquals(1, s.successCount)
        assertEquals(2, s.items[0].retryCount)
        assertEquals(3, harness.uploadCount.get())
    }

    @Test
    fun `重试用尽标记失败`() = runTest {
        harness.failUploads = Int.MAX_VALUE
        engine = newEngine()
        engine.launch(engineScope, listOf(target()), onDuplicate = { error("不应触发查重") })

        awaitTerminal()
        val s = engine.state.value
        assertEquals(RunPhase.FINISHED, s.phase)
        assertEquals(1, s.failedCount)
        assertEquals(2, s.items[0].retryCount)
        assertTrue(s.items[0].error!!.contains("500"))
    }

    // ===== 3. 验证超时 =====

    @Test
    fun `嵌入验证超时标记失败`() = runTest {
        harness.neverMatchDetail = true
        engine = newEngine(verifyTimeoutSec = 2)
        engine.launch(engineScope, listOf(target()), onDuplicate = { error("不应触发查重") })

        awaitTerminal()
        val s = engine.state.value
        assertEquals(RunPhase.FINISHED, s.phase)
        assertEquals(1, s.failedCount)
        assertTrue("error=${s.items[0].error}", s.items[0].error!!.contains("嵌入验证超时"))
    }

    // ===== 4. 重复检测四类动作 =====

    @Test
    fun `重复-跳过不产生上传`() = runTest {
        harness.duplicateTitles = listOf("a.txt")
        engine = newEngine(detectDuplicates = true)
        engine.launch(engineScope, listOf(target()), onDuplicate = { q ->
            assertEquals("a.txt", q.title)
            assertEquals(1, q.existingCount)
            DuplicateAnswer(DuplicateAction.SKIP, false)
        })

        awaitTerminal()
        val s = engine.state.value
        assertEquals(RunPhase.FINISHED, s.phase)
        assertEquals(1, s.skippedCount)
        assertEquals(0, harness.uploadCount.get())
    }

    @Test
    fun `重复-保留继续上传`() = runTest {
        harness.duplicateTitles = listOf("a.txt")
        engine = newEngine(detectDuplicates = true)
        engine.launch(engineScope, listOf(target()), onDuplicate = {
            DuplicateAnswer(DuplicateAction.KEEP, false)
        })

        awaitTerminal()
        assertEquals(1, engine.state.value.successCount)
        assertEquals(1, harness.uploadCount.get())
    }

    @Test
    fun `重复-替换先解关联再物理删除后上传`() = runTest {
        harness.duplicateTitles = listOf("a.txt")
        harness.oldDocpath = "custom-documents/old-uuid.json"
        engine = newEngine(detectDuplicates = true)
        engine.launch(engineScope, listOf(target()), onDuplicate = {
            DuplicateAnswer(DuplicateAction.REPLACE, false)
        })

        awaitTerminal()
        val s = engine.state.value
        assertEquals(RunPhase.FINISHED, s.phase)
        assertEquals(1, s.successCount)
        assertEquals(1, harness.uploadCount.get())

        // 解关联:update-embeddings deletes=[旧docpath]
        val deletes = harness.jsonRequests
            .filter { it.first.endsWith("/update-embeddings") }
            .first().second
        assertTrue(deletes.contains("\"deletes\":[\"custom-documents/old-uuid.json\"]"))

        // 物理删除:remove-documents names 带文件夹前缀
        val removeBody = harness.jsonRequests
            .filter { it.first == "/api/v1/system/remove-documents" }
            .first().second
        assertTrue(removeBody.contains("custom-documents/old0.json"))
    }

    @Test
    fun `重复-中止取消全部`() = runTest {
        harness.duplicateTitles = listOf("a.txt", "b.txt")
        engine = newEngine(detectDuplicates = true)
        engine.launch(engineScope, listOf(target("a.txt"), target("b.txt")), onDuplicate = {
            DuplicateAnswer(DuplicateAction.ABORT, false)
        })

        awaitTerminal()
        val s = engine.state.value
        assertEquals(RunPhase.CANCELLED, s.phase)
        assertEquals(2, s.cancelledCount)
        assertEquals(0, harness.uploadCount.get())
    }

    @Test
    fun `重复-应用到全部只问一次`() = runTest {
        harness.duplicateTitles = listOf("a.txt", "b.txt")
        engine = newEngine(detectDuplicates = true)
        var questions = 0
        engine.launch(engineScope, listOf(target("a.txt"), target("b.txt")), onDuplicate = {
            questions++
            DuplicateAnswer(DuplicateAction.REPLACE, true)
        })

        awaitTerminal()
        assertEquals(1, questions)
        assertEquals(2, engine.state.value.successCount)
        assertEquals(2, harness.uploadCount.get())
    }

    // ===== 5. 并发控制 =====

    @Test
    fun `并发控制-最多同时2个上传`() = runTest {
        harness.uploadSleepMs = 30
        engine = newEngine(importConcurrency = 2)
        engine.launch(
            engineScope,
            listOf(target("1.txt"), target("2.txt"), target("3.txt"), target("4.txt")),
            onDuplicate = { error("不应触发查重") },
        )

        awaitTerminal()
        assertEquals(4, engine.state.value.successCount)
        assertEquals(2, harness.maxActive.get())
    }
}

/** MockWebServer 封装:可编程化模拟 AnythingLLM 行为 */
private class ServerHarness {
    val server = MockWebServer()
    val uploadCount = AtomicInteger(0)
    val activeUploads = AtomicInteger(0)
    val maxActive = AtomicInteger(0)
    val detailCalls = AtomicInteger(0)
    val uploadedLocations = CopyOnWriteArrayList<String>()
    val uploadBodies = CopyOnWriteArrayList<String>()
    val jsonRequests = CopyOnWriteArrayList<Pair<String, String>>() // path to body

    var failUploads = 0
    var duplicateTitles: List<String> = emptyList()
    var oldDocpath: String? = null
    var neverMatchDetail = false
    var uploadSleepMs = 0L

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path ?: return MockResponse().setResponseCode(404)
                return when {
                    p.startsWith("/api/v1/document/upload/") -> handleUpload(request)
                    p.endsWith("/update-embeddings") -> {
                        jsonRequests += p to request.body.readUtf8()
                        jsonOk()
                    }
                    p == "/api/v1/system/remove-documents" -> {
                        jsonRequests += p to request.body.readUtf8()
                        jsonOk()
                    }
                    p == "/api/v1/documents" -> handleDocuments()
                    p == "/api/v1/workspace/w" -> handleDetail()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    fun api(): AnythingLLMApi = AnythingLlmClientFactory().create(server.url("/").toString(), "test-key")

    private fun handleUpload(request: RecordedRequest): MockResponse {
        val count = uploadCount.incrementAndGet()
        activeUploads.incrementAndGet()
        maxActive.updateAndGet { maxOf(it, activeUploads.get()) }
        try {
            if (failUploads > 0) {
                failUploads--
                return MockResponse().setResponseCode(500)
            }
            if (uploadSleepMs > 0) Thread.sleep(uploadSleepMs)
            val loc = "custom-documents/f$count-uuid.json"
            uploadedLocations += loc
            uploadBodies += request.body.readUtf8()
            return jsonOk("""{"success":true,"error":null,"documents":[{"id":"d$count","location":"$loc"}]}""")
        } finally {
            activeUploads.decrementAndGet()
        }
    }

    private fun handleDocuments(): MockResponse {
        // 替换流程:remove-documents 后回查返回空树 → 回查确认通过
        val replaced = jsonRequests.any { it.first == "/api/v1/system/remove-documents" }
        if (duplicateTitles.isEmpty() || replaced) {
            return jsonOk("""{"localFiles":{"name":"documents","type":"folder","items":[]}}""")
        }
        val items = duplicateTitles.mapIndexed { i, t ->
            """{"name":"old$i.json","type":"file","title":"$t"}"""
        }.joinToString(",")
        return jsonOk(
            """{"localFiles":{"name":"documents","type":"folder","items":[{"name":"custom-documents","type":"folder","items":[$items]}]}}""",
        )
    }

    private fun handleDetail(): MockResponse {
        val calls = detailCalls.incrementAndGet()
        if (neverMatchDetail) {
            return jsonOk("""{"workspace":[{"id":1,"slug":"w","documents":[]}]}""")
        }
        // 替换场景:上传前的详情调用返回旧文档(供 deletes 解关联)
        if (oldDocpath != null && uploadedLocations.isEmpty()) {
            return jsonOk(
                """{"workspace":[{"id":1,"slug":"w","documents":[{"docpath":"$oldDocpath","metadata":"{\"title\":\"a.txt\"}"}]}]}""",
            )
        }
        if (uploadedLocations.isNotEmpty()) {
            // 首个详情调用返回空 → 验证至少轮询 2 次
            if (calls <= 1) {
                return jsonOk("""{"workspace":[{"id":1,"slug":"w","documents":[]}]}""")
            }
            val docs = uploadedLocations.map { """{"docpath":"$it","metadata":"{\"title\":\"t\"}"}""" }
                .joinToString(",")
            return jsonOk("""{"workspace":[{"id":1,"slug":"w","documents":[$docs]}]}""")
        }
        return jsonOk("""{"workspace":[{"id":1,"slug":"w","documents":[]}]}""")
    }

    private fun jsonOk(body: String = """{"success":true}""") =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
