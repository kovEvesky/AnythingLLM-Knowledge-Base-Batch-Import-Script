package com.anythingllm.importer.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {

    @Test
    fun `默认值与文档一致`() {
        val cfg = AppConfig.defaults()
        assertEquals("http://10.0.2.2:3001", cfg.baseUrl)
        assertEquals(100, cfg.maxFileSizeMB)
        assertEquals(FilenamePolicy.UNICODE, cfg.filenamePolicy)
        assertTrue(cfg.detectDuplicates)
        assertEquals(DuplicateAction.ASK, cfg.duplicateDefaultAction)
        assertEquals(2, cfg.uploadRetryCount)
        assertEquals(2, cfg.uploadRetryBaseDelaySec)
        assertEquals(300, cfg.verifyTimeoutSec)
        assertEquals(2, cfg.verifyBaseDelaySec)
        assertEquals(5, cfg.verifyMaxDelaySec)
        assertEquals(5, cfg.probeTimeoutSec)
        assertEquals(300, cfg.uploadTimeoutSec)
        assertEquals(120, cfg.embedTimeoutSec)
        assertEquals(60, cfg.apiTimeoutSec)
        assertEquals(300, cfg.chatTimeoutSec)
        assertEquals(false, cfg.askForChatTest)
        assertEquals(30, cfg.logRetentionDays)
        assertEquals(15, cfg.allowedExtensions.size)
        assertTrue(cfg.allowedExtensions.contains(".pdf"))
        assertTrue(cfg.allowedExtensions.contains(".odp"))
    }

    @Test
    fun `扩展名解析-自动补点去重排序保留`() {
        val parsed = AppConfig.parseExtensions("pdf, .DOCX;,txt .txt")
        assertEquals(listOf(".pdf", ".docx", ".txt"), parsed)
    }

    @Test
    fun `扩展名解析-空输入返回空列表`() {
        assertTrue(AppConfig.parseExtensions("").isEmpty())
        assertTrue(AppConfig.parseExtensions("   , ， ;\n").isEmpty())
    }

    @Test
    fun `正整数解析-合法值`() {
        assertEquals(100, AppConfig.parsePositiveInt("100", 50))
    }

    @Test
    fun `正整数解析-非法值回退默认`() {
        assertEquals(50, AppConfig.parsePositiveInt("abc", 50))
        assertEquals(50, AppConfig.parsePositiveInt("-5", 50))
        assertEquals(50, AppConfig.parsePositiveInt("0", 50, min = 1))
        assertEquals(50, AppConfig.parsePositiveInt("", 50))
    }

    @Test
    fun `正整数解析-越界收敛`() {
        assertEquals(3600, AppConfig.parsePositiveInt("99999", 300, max = 3600))
        assertEquals(300, AppConfig.parsePositiveInt("0", 300, min = 1, max = 3600))
    }
}
