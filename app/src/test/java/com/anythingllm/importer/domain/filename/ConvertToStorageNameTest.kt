package com.anythingllm.importer.domain.filename

import com.anythingllm.importer.data.config.FilenamePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertToStorageNameTest {

    private val fixedGuid = { "12345678-90ab-cdef-1234-567890abcdef" }

    // ===== unicode 策略 =====

    @Test
    fun `unicode-中文转uXXXX小写十六进制`() {
        assertEquals("u9700u6c42u6587u6863.txt", ConvertToStorageName.convert("需求文档.txt", FilenamePolicy.UNICODE))
    }

    @Test
    fun `unicode-服务端实测对照1-深海世界`() {
        // 服务端已存文档实测存储名
        assertEquals("01u6df1u6d77u4e16u754c.txt", ConvertToStorageName.convert("01深海世界.txt", FilenamePolicy.UNICODE))
    }

    @Test
    fun `unicode-服务端实测对照2-契约测试`() {
        // 实测上传 契约测试.txt → u5951u7ea6u6d4bu8bd5.txt
        assertEquals("u5951u7ea6u6d4bu8bd5.txt", ConvertToStorageName.convert("契约测试.txt", FilenamePolicy.UNICODE))
    }

    @Test
    fun `unicode-纯ASCII保持不变`() {
        assertEquals("report_v2.pdf", ConvertToStorageName.convert("report_v2.pdf", FilenamePolicy.UNICODE))
        assertEquals("EmbeddingTechniques.md", ConvertToStorageName.convert("EmbeddingTechniques.md", FilenamePolicy.UNICODE))
    }

    @Test
    fun `unicode-中英文混合`() {
        assertEquals("u62a5u544a_v2.txt", ConvertToStorageName.convert("报告_v2.txt", FilenamePolicy.UNICODE))
    }

    @Test
    fun `unicode-无扩展名`() {
        assertEquals("u6d4bu8bd5", ConvertToStorageName.convert("测试", FilenamePolicy.UNICODE))
    }

    @Test
    fun `unicode-特殊字符引号与反斜杠替换为连字符`() {
        assertEquals("a-b-c.txt", ConvertToStorageName.convert("a\"b\\c.txt", FilenamePolicy.UNICODE))
    }

    @Test
    fun `unicode-空主名回退file前缀`() {
        // strip 掉全部中文后为空 → file-{guid8}
        assertEquals("file-12345678.txt", ConvertToStorageName.convert("测试.txt", FilenamePolicy.STRIP, fixedGuid))
    }

    @Test
    fun `unicode-完全空名回退`() {
        assertEquals("file-12345678", ConvertToStorageName.convert("", FilenamePolicy.UNICODE, fixedGuid))
        assertEquals("file-12345678", ConvertToStorageName.convert("", FilenamePolicy.STRIP, fixedGuid))
    }

    @Test
    fun `unicode-纯标点与空格保留`() {
        // !(0x21) 与空格(0x20) 均在可打印范围
        assertEquals("! .txt", ConvertToStorageName.convert("! .txt", FilenamePolicy.UNICODE))
    }

    @Test
    fun `unicode-辅助平面字符按UTF16码元处理`() {
        // 😀 U+1F600 为代理对(2 个 UTF-16 码元),逐字符迭代与 PC 版 PowerShell ToCharArray() 一致:
        // 高代理 \uD83D → ud83d,低代理 \uDE00 → ude00
        assertEquals("ud83dude00.txt", ConvertToStorageName.convert("😀.txt", FilenamePolicy.UNICODE))
    }

    @Test
    fun `unicode-点开头隐藏文件无扩展名路径`() {
        // ".gitignore" dotIndex=0 → 主名整体处理,点保留
        assertEquals(".gitignore", ConvertToStorageName.convert(".gitignore", FilenamePolicy.UNICODE))
    }

    // ===== strip 策略 =====

    @Test
    fun `strip-中文被丢弃`() {
        assertEquals("_v2.txt", ConvertToStorageName.convert("报告_v2.txt", FilenamePolicy.STRIP))
    }

    @Test
    fun `strip-纯中文回退`() {
        assertEquals("file-12345678.txt", ConvertToStorageName.convert("需求文档.txt", FilenamePolicy.STRIP, fixedGuid))
    }

    // ===== keep 策略 =====

    @Test
    fun `keep-原样返回`() {
        assertEquals("需求文档.txt", ConvertToStorageName.convert("需求文档.txt", FilenamePolicy.KEEP))
        assertEquals("a\"b\\c.txt", ConvertToStorageName.convert("a\"b\\c.txt", FilenamePolicy.KEEP))
    }
}
