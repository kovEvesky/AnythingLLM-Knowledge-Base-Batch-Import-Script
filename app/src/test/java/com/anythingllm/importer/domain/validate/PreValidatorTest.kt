package com.anythingllm.importer.domain.validate

import com.anythingllm.importer.data.config.AppConfig
import com.anythingllm.importer.data.config.FilenamePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreValidatorTest {

    private val validator = PreValidator()
    private val whitelist = AppConfig.DEFAULT_ALLOWED_EXTENSIONS

    private fun file(name: String, size: Long) = PickedFile(
        uriString = "content://test/$name",
        displayName = name,
        sizeBytes = size,
        mimeType = null,
    )

    // ===== 通过场景 =====

    @Test
    fun `白名单内文件通过并计算存储名`() {
        val summary = validator.validate(
            listOf(file("需求文档.txt", 1024)),
            whitelist, 100, FilenamePolicy.UNICODE,
        )
        assertEquals(1, summary.passedCount)
        assertEquals(0, summary.rejectedCount)
        assertEquals("u9700u6c42u6587u6863.txt", summary.passed[0].storageName)
    }

    @Test
    fun `扩展名大小写不敏感`() {
        val summary = validator.validate(
            listOf(file("README.PDF", 10)),
            whitelist, 100, FilenamePolicy.UNICODE,
        )
        assertEquals(1, summary.passedCount)
    }

    @Test
    fun `大小上限边界-恰好等于上限通过`() {
        val summary = validator.validate(
            listOf(file("a.pdf", 100L * 1024 * 1024)),
            whitelist, 100, FilenamePolicy.UNICODE,
        )
        assertEquals(1, summary.passedCount)
    }

    @Test
    fun `大小未知-1通过`() {
        val summary = validator.validate(
            listOf(file("a.pdf", -1)),
            whitelist, 100, FilenamePolicy.UNICODE,
        )
        assertEquals(1, summary.passedCount)
    }

    // ===== 拒绝场景 =====

    @Test
    fun `白名单外扩展名拒绝`() {
        val summary = validator.validate(
            listOf(file("evil.exe", 1024)),
            whitelist, 100, FilenamePolicy.UNICODE,
        )
        assertEquals(1, summary.rejectedCount)
        assertTrue(summary.rejected[0].reasons[0].contains("扩展名不在允许列表"))
    }

    @Test
    fun `无扩展名拒绝`() {
        val summary = validator.validate(
            listOf(file("README", 1024)),
            whitelist, 100, FilenamePolicy.UNICODE,
        )
        assertEquals(1, summary.rejectedCount)
    }

    @Test
    fun `空文件拒绝`() {
        val summary = validator.validate(
            listOf(file("empty.txt", 0)),
            whitelist, 100, FilenamePolicy.UNICODE,
        )
        assertEquals(1, summary.rejectedCount)
        assertTrue(summary.rejected[0].reasons[0].contains("文件为空"))
    }

    @Test
    fun `超过大小上限拒绝`() {
        val summary = validator.validate(
            listOf(file("big.docx", 100L * 1024 * 1024 + 1)),
            whitelist, 100, FilenamePolicy.UNICODE,
        )
        assertEquals(1, summary.rejectedCount)
        assertTrue(summary.rejected[0].reasons[0].contains("超过大小上限 100MB"))
    }

    @Test
    fun `多原因叠加`() {
        val summary = validator.validate(
            listOf(file("bad.exe", 0)),
            whitelist, 100, FilenamePolicy.UNICODE,
        )
        assertEquals(1, summary.rejectedCount)
        assertEquals(2, summary.rejected[0].reasons.size)
    }

    @Test
    fun `混合场景汇总计数与字节`() {
        val summary = validator.validate(
            listOf(
                file("ok1.pdf", 100),
                file("ok2.txt", 200),
                file("bad.exe", 300),
                file("empty.txt", 0),
            ),
            whitelist, 100, FilenamePolicy.UNICODE,
        )
        assertEquals(2, summary.passedCount)
        assertEquals(2, summary.rejectedCount)
        assertEquals(300, summary.passedBytes)
        assertEquals(600, summary.totalBytes)
    }

    @Test
    fun `空输入返回空汇总`() {
        val summary = validator.validate(emptyList(), whitelist, 100, FilenamePolicy.UNICODE)
        assertEquals(0, summary.passedCount)
        assertEquals(0, summary.rejectedCount)
    }

    // ===== extensionOrEmpty =====

    @Test
    fun `扩展名提取`() {
        assertEquals(".txt", "a.txt".extensionOrEmpty())
        assertEquals(".pdf", "A.PDF".extensionOrEmpty()) // 统一小写
        assertEquals("", "README".extensionOrEmpty())
        assertEquals("", ".gitignore".extensionOrEmpty())
        assertEquals(".docx", "多级.名称.docx".extensionOrEmpty())
    }
}
