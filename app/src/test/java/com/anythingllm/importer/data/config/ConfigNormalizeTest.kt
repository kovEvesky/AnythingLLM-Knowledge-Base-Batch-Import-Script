package com.anythingllm.importer.data.config

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigNormalizeTest {

    @Test
    fun `normalizeApiKey_全角连字符转半角`() {
        // 中文输入法标点自动全角化场景:U+FF0D(－) → U+002D(-)
        val raw = "2JP5NKF\uFF0DSFF4KRE\uFF0DK896D1J\uFF0D2C06W84"
        assertEquals("2JP5NKF-SFF4KRE-K896D1J-2C06W84", normalizeApiKey(raw))
    }

    @Test
    fun `normalizeApiKey_首尾空白与中间空格剥离`() {
        assertEquals("ABC-123", normalizeApiKey("  ABC - 123  "))
        assertEquals("ABC-123", normalizeApiKey("\tABC-123\n"))
    }

    @Test
    fun `normalizeApiKey_非法字符剥离`() {
        // 引号/分号/中文等不应进入 Authorization 头
        assertEquals("ABCDEF", normalizeApiKey("\"ABCD'EF\"测试"))
    }

    @Test
    fun `normalizeApiKey_纯ASCII原样保留`() {
        assertEquals("2JP5NKF-SFF4KRE-K896D1J-2C06W84", normalizeApiKey("2JP5NKF-SFF4KRE-K896D1J-2C06W84"))
    }

    @Test
    fun `normalizeApiKey_全角字母数字转半角`() {
        // NFKC 归一化:全角字母/数字 → 半角
        assertEquals("ABC123", normalizeApiKey("\uFF21\uFF22\uFF23\uFF11\uFF12\uFF13"))
    }
}
