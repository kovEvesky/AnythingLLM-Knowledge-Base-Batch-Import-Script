package com.anythingllm.importer

import com.anythingllm.importer.data.library.LibraryEntry
import com.anythingllm.importer.data.library.LibraryEntryType
import com.anythingllm.importer.domain.ftp.FtpNaming
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FTP 命名与路径规划单测(v1.3):
 * 文件名清洗 / 链接 .url 命名(URL→域名)/ 目录链拼接 / .url 内容。
 */
class FtpNamingTest {

    private fun link(title: String?, url: String?) = LibraryEntry(
        id = "l1",
        type = LibraryEntryType.LINK,
        title = title ?: "",
        url = url,
        addedAt = "2026-09-11T00:00:00+08:00",
    )

    private fun file(title: String) = LibraryEntry(
        id = "f1",
        type = LibraryEntryType.FILE,
        title = title,
        localPath = "C:\\x\\y.txt",
        addedAt = "2026-09-11T00:00:00+08:00",
    )

    // ===== 文件名清洗 =====

    @Test
    fun `cleanFileName - 剔除非法字符与路径分隔,空名回退`() {
        assertEquals("a_b_c.txt", FtpNaming.cleanFileName("a/b\\c.txt"))
        assertEquals("x_y", FtpNaming.cleanFileName("x:y"))
        assertEquals("q_w_e", FtpNaming.cleanFileName("q?w*e"))
        assertEquals("_", FtpNaming.cleanFileName("\""))
        assertEquals("file", FtpNaming.cleanFileName("   "))
        assertEquals("file", FtpNaming.cleanFileName(""))
    }

    // ===== 链接命名 =====

    @Test
    fun `linkRemoteName - 普通标题直接使用`() {
        assertEquals("AI 研究笔记.url", FtpNaming.linkRemoteName(link("AI 研究笔记", "https://example.com/a")))
    }

    @Test
    fun `linkRemoteName - 标题为完整 URL 时取域名`() {
        assertEquals("example.com.url", FtpNaming.linkRemoteName(link("https://example.com/a/b?x=1", "https://example.com/a/b?x=1")))
    }

    @Test
    fun `linkRemoteName - 标题为空回退域名,再回退 link`() {
        assertEquals("sub.example.org.url", FtpNaming.linkRemoteName(link("", "https://sub.example.org/path")))
        assertEquals("link.url", FtpNaming.linkRemoteName(link("", null)))
    }

    @Test
    fun `linkRemoteName - URL 标题含协议与非法字符清洗`() {
        assertEquals("m.example.com.url", FtpNaming.linkRemoteName(link("http://m.example.com/x", "http://m.example.com/x")))
        assertEquals("a_b.url", FtpNaming.linkRemoteName(link("a/b", "https://example.com")))
    }

    // ===== 远端路径 =====

    @Test
    fun `remoteFileName - 文件保留标题,链接加 url 后缀`() {
        assertEquals("报告.pdf", FtpNaming.remoteFileName(file("报告.pdf")))
        assertEquals("报告_终版.pdf", FtpNaming.remoteFileName(file("报告/终版.pdf")))
        assertEquals("example.com.url", FtpNaming.remoteFileName(link("https://example.com/x", "https://example.com/x")))
    }

    @Test
    fun `remoteRelativePath - 目录链与文件名拼接`() {
        assertEquals("文档/子目录/报告.pdf", FtpNaming.remoteRelativePath(listOf("文档", "子目录"), "报告.pdf"))
        assertEquals("根文件.txt", FtpNaming.remoteRelativePath(emptyList(), "根文件.txt"))
    }

    // ===== .url 内容 =====

    @Test
    fun `buildUrlContent - Windows InternetShortcut 格式`() {
        assertEquals(
            "[InternetShortcut]\r\nURL=https://example.com/a\r\n",
            FtpNaming.buildUrlContent("https://example.com/a"),
        )
    }
}
