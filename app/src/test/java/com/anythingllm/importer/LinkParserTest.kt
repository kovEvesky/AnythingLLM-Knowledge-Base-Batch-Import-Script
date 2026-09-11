package com.anythingllm.importer

import com.anythingllm.importer.domain.link.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserTest {

    private val parser = LinkParser()

    @Test
    fun `extracts single http url`() {
        assertEquals(listOf("https://example.com"), parser.extract("看这个 https://example.com"))
    }

    @Test
    fun `extracts http and https with paths`() {
        val text = "a https://example.com/x?y=1 b http://sub.cn/page"
        assertEquals(
            listOf("https://example.com/x?y=1", "http://sub.cn/page"),
            parser.extract(text),
        )
    }

    @Test
    fun `strips trailing english punctuation`() {
        assertEquals(
            listOf("https://example.com/a"),
            parser.extract("url: https://example.com/a.")
        )
    }

    @Test
    fun `strips trailing chinese punctuation`() {
        assertEquals(
            listOf("https://example.com/a"),
            parser.extract("链接是https://example.com/a，请查看")
        )
        assertEquals(
            listOf("https://example.com"),
            parser.extract("https://example.com。")
        )
    }

    @Test
    fun `deduplicates identical urls`() {
        assertEquals(
            listOf("https://example.com"),
            parser.extract("https://example.com 和 https://example.com"),
        )
    }

    @Test
    fun `no url returns empty`() {
        assertTrue(parser.extract("普通文本没有链接").isEmpty())
        assertTrue(parser.extract("").isEmpty())
    }

    @Test
    fun `ignores scheme-less www without protocol`() {
        assertTrue(parser.extract("访问 www.example.com 网站").isEmpty())
    }

    @Test
    fun `extracts multiple urls and keeps order`() {
        val urls = parser.extract("1:https://a.com 2:http://b.cn/p 3:https://c.org")
        assertEquals(3, urls.size)
        assertEquals("https://a.com", urls[0])
        assertEquals("http://b.cn/p", urls[1])
        assertEquals("https://c.org", urls[2])
    }

    @Test
    fun `hostOf strips scheme path and port`() {
        assertEquals("example.com", parser.hostOf("https://example.com/a/b"))
        assertEquals("sub.cn", parser.hostOf("http://sub.cn:8080/page"))
        assertEquals("example.com", parser.hostOf("https://example.com"))
    }
}
