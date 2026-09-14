package com.anythingllm.importer

import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.EntrySource
import com.anythingllm.importer.data.collect.EntryType
import com.anythingllm.importer.domain.ftp.CollectFtpNaming
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CollectFtpNaming 纯函数测试(v1.9 §4.9 通道 A):
 * - 文件条目:清洗标题 → 可读远端文件名;
 * - 链接条目:可读标题 .url(URL 取域名);非法字符清洗;
 * - buildUrlContent:RFC InternetShortcut 文本。
 */
class CollectFtpNamingTest {

    private fun entry(
        type: EntryType,
        title: String? = null,
        fileName: String? = null,
        url: String? = null,
    ) = CollectEntry(
        id = "id1",
        type = type,
        source = EntrySource.SHARE_FILE,
        fileName = fileName,
        title = title,
        url = url,
        collectedAt = "2026-09-14T10:00:00+08:00",
    )

    @Test
    fun `file entry uses cleaned title`() {
        val e = entry(EntryType.FILE, title = "产品 需求:V1.9*/方案")
        assertEquals("产品 需求_V1.9__方案", CollectFtpNaming.remoteFileName(e))
    }

    @Test
    fun `file entry falls back to fileName when title blank`() {
        val e = entry(EntryType.FILE, title = null, fileName = "report.txt")
        assertEquals("report.txt", CollectFtpNaming.remoteFileName(e))
    }

    @Test
    fun `link entry becomes readable title with url extension`() {
        val e = entry(EntryType.LINK, title = "收藏夹设计", url = "https://example.com/a")
        assertEquals("收藏夹设计.url", CollectFtpNaming.remoteFileName(e))
    }

    @Test
    fun `link with url title takes hostname`() {
        val e = entry(EntryType.LINK, title = "https://example.com/a", url = "https://example.com/a")
        assertEquals("example.com.url", CollectFtpNaming.remoteFileName(e))
    }

    @Test
    fun `link with no title takes url hostname`() {
        val e = entry(EntryType.LINK, title = null, url = "https://blog.example.com/p/1")
        assertEquals("blog.example.com.url", CollectFtpNaming.remoteFileName(e))
    }

    @Test
    fun `link title with illegal chars is cleaned`() {
        val e = entry(EntryType.LINK, title = "a/b:c*?", url = "https://example.com/x")
        assertEquals("a_b_c__.url", CollectFtpNaming.remoteFileName(e))
    }

    @Test
    fun `cleanFileName blank falls back to file`() {
        assertEquals("file", CollectFtpNaming.cleanFileName("   "))
        assertEquals("file", CollectFtpNaming.cleanFileName(""))
    }

    @Test
    fun `buildUrlContent produces RFC shortcut`() {
        val content = CollectFtpNaming.buildUrlContent("https://example.com/x")
        assertEquals("[InternetShortcut]\r\nURL=https://example.com/x\r\n", content)
    }
}
