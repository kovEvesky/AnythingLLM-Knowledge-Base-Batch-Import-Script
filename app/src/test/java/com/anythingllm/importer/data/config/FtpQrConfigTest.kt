package com.anythingllm.importer.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.5 FTP 扫码连接:二维码 JSON 解析与校验 */
class FtpQrConfigTest {

    @Test
    fun `解析合法配置`() {
        val cfg = FtpQrConfig.parse(
            """{"v":1,"t":"anythingllm-ftp","host":"192.168.1.100","port":2121,"user":"sync","password":"s3cret","root":"Library"}""",
        )!!
        assertEquals("192.168.1.100", cfg.host)
        assertEquals(2121, cfg.port)
        assertEquals("sync", cfg.user)
        assertEquals("s3cret", cfg.password)
        assertEquals("Library", cfg.root)
    }

    @Test
    fun `缺字段用默认值`() {
        val cfg = FtpQrConfig.parse("""{"t":"anythingllm-ftp","host":"10.0.0.5"}""")!!
        assertEquals(2121, cfg.port)
        assertEquals("sync", cfg.user)
        assertEquals("Library", cfg.root)
    }

    @Test
    fun `非 FTP 类型二维码返回 null`() {
        assertNull(FtpQrConfig.parse("""{"t":"wechat","host":"x"}"""))
    }

    @Test
    fun `host 为空返回 null`() {
        assertNull(FtpQrConfig.parse("""{"t":"anythingllm-ftp","host":""}"""))
    }

    @Test
    fun `端口越界返回 null`() {
        assertNull(FtpQrConfig.parse("""{"t":"anythingllm-ftp","host":"1.2.3.4","port":70000}"""))
    }

    @Test
    fun `非 JSON 返回 null`() {
        assertNull(FtpQrConfig.parse("not a json"))
        assertNull(FtpQrConfig.parse(""))
        assertNull(FtpQrConfig.parse("https://example.com"))
    }

    @Test
    fun `多余字段忽略`() {
        val cfg = FtpQrConfig.parse("""{"t":"anythingllm-ftp","host":"1.2.3.4","extra":"ignored","port":2122}""")!!
        assertEquals(2122, cfg.port)
    }
}
