package com.anythingllm.importer

import com.anythingllm.importer.domain.link.LinkTitleFetcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** v1.5-需求一:网页标题抓取(解析 + MockWebServer 抓取链路) */
class LinkTitleFetcherTest {

    private lateinit var server: MockWebServer
    private val fetcher = LinkTitleFetcher()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url(path: String) = server.url(path).toString()

    // ===== 解析层(不依赖网络) =====

    @Test
    fun `提取标准 title`() {
        assertEquals("知乎 - 有问题,就会有答案", fetcher.extractTitle("<html><head><title>知乎 - 有问题,就会有答案</title></head></html>"))
    }

    @Test
    fun `title 带属性且大小写混写`() {
        assertEquals("My Page", fetcher.extractTitle("<TITLE data-x=\"1\">  My   Page  </TITLE>"))
    }

    @Test
    fun `title 含 HTML 实体解码`() {
        assertEquals("A & B < C > D \"Q\" 'A'", fetcher.extractTitle("<title>A &amp; B &lt; C &gt; D &quot;Q&quot; &apos;A&apos;</title>"))
    }

    @Test
    fun `title 含数字实体与中文注释实体`() {
        assertEquals("雪 — 经典 · 定制 ©", fetcher.extractTitle("<title>&#38634; &mdash; &#x7ECF;&#20856; &middot; &#23450;&#21046; &copy;</title>"))
    }

    @Test
    fun `无 title 返回 null`() {
        assertNull(fetcher.extractTitle("<html><body>no title here</body></html>"))
    }

    @Test
    fun `空白 title 返回 null`() {
        assertNull(fetcher.extractTitle("<html><head><title>   \n </title></head></html>"))
    }

    @Test
    fun `超长 title 截断`() {
        val long = "x".repeat(300)
        assertEquals(120, fetcher.extractTitle("<title>$long</title>")!!.length)
    }

    // ===== 抓取链路(MockWebServer) =====

    @Test
    fun `抓取成功返回解码后标题`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "<!DOCTYPE html><html><head><title>AnythingLLM 文档 &amp; 指南</title></head><body>hi</body></html>",
            ),
        )
        assertEquals("AnythingLLM 文档 & 指南", fetcher.fetch(url("/doc")))
    }

    @Test
    fun `非 200 返回 null`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        assertNull(fetcher.fetch(url("/missing")))
    }

    @Test
    fun `重定向跟随`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<title>Final Page</title>"))
        assertEquals("Final Page", fetcher.fetch(url("/redirect")))
    }

    @Test
    fun `连接失败返回 null 不抛异常`() {
        assertNull(fetcher.fetch("http://127.0.0.1:1/unreachable"))
    }

    @Test
    fun `超大页面只读前缀仍能取到 title`() {
        val bigBody = "<html><head><title>BigPage</title></head><body>" + "x".repeat(2 * 1024 * 1024) + "</body></html>"
        server.enqueue(MockResponse().setResponseCode(200).setBody(bigBody))
        assertEquals("BigPage", fetcher.fetch(url("/big")))
    }

    @Test
    fun `无 title 页面返回 null`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>plain</body></html>"))
        assertNull(fetcher.fetch(url("/plain")))
    }

    @Test
    fun `浏览器 UA 已携带`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<title>UA ok</title>"))
        fetcher.fetch(url("/ua"))
        val recorded = server.takeRequest()
        assertTrue(recorded.getHeader("User-Agent")!!.contains("Chrome"))
    }
}
