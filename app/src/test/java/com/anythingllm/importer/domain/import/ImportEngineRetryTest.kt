package com.anythingllm.importer.domain.import

import com.anythingllm.importer.data.api.AnythingLLMApi
import com.anythingllm.importer.data.api.AnythingLlmClientFactory
import com.anythingllm.importer.data.config.AppConfig
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 阶段 4 FR-12 失败重试测试:引擎真实时间 + MockWebServer。
 * 覆盖:失败→重试→成功、重试仍失败、仅失败项可重试、计数流转、运行中调用忽略。
 */
class ImportEngineRetryTest {

    private lateinit var harness: RetryHarness
    private lateinit var engine: ImportEngine
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val textBody = "内容".toByteArray().toRequestBody("text/plain".toMediaType())

    @Before
    fun setUp() {
        harness = RetryHarness().apply { start() }
    }

    @After
    fun tearDown() {
        engineScope.cancel()
        harness.server.shutdown()
    }

    private fun newEngine() = ImportEngine(
        api = harness.api(),
        bodyProvider = FileBodyProvider { _, _ -> textBody },
        config = AppConfig(
            uploadRetryCount = 2,
            uploadRetryBaseDelaySec = 1,
            verifyBaseDelaySec = 1,
            verifyMaxDelaySec = 2,
            verifyTimeoutSec = 300,
        ),
    )

    private fun target(name: String = "a.txt"): ImportTarget =
        ImportTarget(
            uriString = "content://test/$name",
            displayName = name,
            storageName = "uXXXX.txt",
            sizeBytes = 10,
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

    @Test
    fun `失败后重试成功-计数正确流转`() = runTest {
        harness.failUploads = Int.MAX_VALUE
        engine = newEngine()
        engine.launch(engineScope, listOf(target()), onDuplicate = { error("不应查重") })

        awaitTerminal()
        assertEquals(RunPhase.FINISHED, engine.state.value.phase)
        assertEquals(1, engine.state.value.failedCount)
        assertEquals(0, engine.state.value.successCount)

        // 服务器恢复后重试
        harness.failUploads = 0
        engine.retryItems(engineScope, listOf(0), onDuplicate = { error("不应查重") })
        assertEquals(RunPhase.RUNNING, engine.state.value.phase)
        awaitTerminal()

        val s = engine.state.value
        assertEquals(RunPhase.FINISHED, s.phase)
        assertEquals(1, s.successCount)
        assertEquals(0, s.failedCount)
        assertEquals(ItemStatus.SUCCESS, s.items[0].status)
        assertNotNull(s.items[0].location)
        // 首次 3 次上传(1+2 重试) + 重试 1 次
        assertEquals(4, harness.uploadCount.get())
    }

    @Test
    fun `重试仍失败-保持失败并带原因`() = runTest {
        harness.failUploads = Int.MAX_VALUE
        engine = newEngine()
        engine.launch(engineScope, listOf(target()), onDuplicate = { error("不应查重") })

        awaitTerminal()
        assertEquals(1, engine.state.value.failedCount)

        engine.retryItems(engineScope, listOf(0), onDuplicate = { error("不应查重") })
        awaitTerminal()

        val s = engine.state.value
        assertEquals(RunPhase.FINISHED, s.phase)
        assertEquals(1, s.failedCount)
        assertEquals(ItemStatus.FAILED, s.items[0].status)
        assertNotNull(s.items[0].error)
        // 重试又消耗 3 次上传尝试
        assertEquals(6, harness.uploadCount.get())
    }

    @Test
    fun `仅失败项可重试-成功项不重复上传`() = runTest {
        harness.failUploads = Int.MAX_VALUE
        engine = newEngine()
        // b.txt 成功(failUploads 在第一个文件失败耗尽后仍为 MAX……见下)
        // 改为:先让 b 成功,再让 a 失败——用 per-uri 控制不便,这里用顺序:先上传 b(成功)需 failUploads=0,
        // 但 a 要失败。方案:初次用 failUploads 大值让两个都失败,再分别重试 b 一个。
        engine.launch(
            engineScope,
            listOf(target("a.txt"), target("b.txt")),
            onDuplicate = { error("不应查重") },
        )
        awaitTerminal()
        assertEquals(2, engine.state.value.failedCount)

        harness.failUploads = 0
        val before = harness.uploadCount.get()
        engine.retryItems(engineScope, listOf(0), onDuplicate = { error("不应查重") })
        awaitTerminal()

        val s = engine.state.value
        assertEquals(1, s.successCount)
        assertEquals(1, s.failedCount)
        assertEquals(ItemStatus.SUCCESS, s.items[0].status) // a 重试成功
        assertEquals(ItemStatus.FAILED, s.items[1].status)  // b 未动
        assertEquals(before + 1, harness.uploadCount.get())  // 只多 1 次上传
    }

    @Test
    fun `重试忽略非失败项与越界索引`() = runTest {
        harness.failUploads = Int.MAX_VALUE
        engine = newEngine()
        engine.launch(engineScope, listOf(target()), onDuplicate = { error("不应查重") })
        awaitTerminal()
        assertEquals(1, engine.state.value.failedCount)

        val before = harness.uploadCount.get()
        // 越界索引 + 重复索引:不产生额外上传,状态不变
        engine.retryItems(engineScope, listOf(5, 5), onDuplicate = { error("不应查重") })
        Thread.sleep(300)
        assertEquals(before, harness.uploadCount.get())
        assertEquals(RunPhase.FINISHED, engine.state.value.phase)
        assertEquals(1, engine.state.value.failedCount)
    }

    @Test
    fun `运行中调用重试被忽略`() = runTest {
        harness.uploadSleepMs = 40
        harness.failUploads = Int.MAX_VALUE
        engine = newEngine()
        engine.launch(engineScope, listOf(target()), onDuplicate = { error("不应查重") })

        // 运行中立刻重试:被忽略(不会重置为 RUNNING 造成混乱)
        engine.retryItems(engineScope, listOf(0), onDuplicate = { error("不应查重") })
        awaitTerminal()
        assertEquals(1, engine.state.value.failedCount)
    }
}

/** 简化 MockWebServer:上传计数/失败注入/上传睡眠/详情轮询 */
private class RetryHarness {
    val server = MockWebServer()
    val uploadCount = AtomicInteger(0)
    val uploadedLocations = java.util.concurrent.CopyOnWriteArrayList<String>()

    var failUploads = 0
    var uploadSleepMs = 0L

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path ?: return MockResponse().setResponseCode(404)
                return when {
                    p.startsWith("/api/v1/document/upload/") -> handleUpload()
                    p.endsWith("/update-embeddings") -> jsonOk()
                    p == "/api/v1/documents" -> jsonOk("""{"localFiles":{"name":"documents","type":"folder","items":[]}}""")
                    p == "/api/v1/workspace/w" -> handleDetail()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    fun api(): AnythingLLMApi = AnythingLlmClientFactory().create(server.url("/").toString(), "test-key")

    private fun handleUpload(): MockResponse {
        val count = uploadCount.incrementAndGet()
        if (uploadSleepMs > 0) Thread.sleep(uploadSleepMs)
        if (failUploads > 0) {
            failUploads--
            return MockResponse().setResponseCode(500)
        }
        val loc = "custom-documents/f$count-uuid.json"
        uploadedLocations += loc
        return jsonOk("""{"success":true,"error":null,"documents":[{"id":"d$count","location":"$loc"}]}""")
    }

    private fun handleDetail(): MockResponse {
        if (uploadedLocations.isEmpty()) {
            return jsonOk("""{"workspace":[{"id":1,"slug":"w","documents":[]}]}""")
        }
        val docs = uploadedLocations.map { """{"docpath":"$it","metadata":"{\"title\":\"t\"}"}""" }
            .joinToString(",")
        return jsonOk("""{"workspace":[{"id":1,"slug":"w","documents":[$docs]}]}""")
    }

    private fun jsonOk(body: String = """{"success":true}""") =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}
