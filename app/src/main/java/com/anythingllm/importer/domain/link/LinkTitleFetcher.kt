package com.anythingllm.importer.domain.link

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.text.MatchResult

/**
 * 网页标题抓取器(v1.5-需求一):
 * 分享链接入库时异步抓取 <title> 作为展示标题,失败返回 null(调用方降级为域名)。
 * - OkHttp 短超时(连接 4s / 读 5s),跟随重定向,浏览器 UA 规避部分站点 403;
 * - 响应体仅读前 MAX_BODY_BYTES,防止超大页面拖慢;
 * - 标题做 HTML 实体解码 + 空白压缩 + 长度截断。
 */
class LinkTitleFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    fun fetch(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.byteStream()?.use { ins ->
                val buffer = ByteArray(8192)
                val out = java.io.ByteArrayOutputStream(MAX_BODY_BYTES)
                while (out.size() < MAX_BODY_BYTES) {
                    val n = ins.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toString(Charsets.UTF_8)
            } ?: return null
            extractTitle(body)
        }
    }.getOrNull()

    /** 从 HTML 提取 <title>(含属性写法、大小写不敏感),无则 null */
    internal fun extractTitle(html: String): String? {
        val m = TITLE_REGEX.find(html) ?: return null
        val raw = m.groupValues[1]
        val decoded = decodeEntities(raw)
        val cleaned = decoded.replace(WHITESPACE, " ").trim()
        if (cleaned.isEmpty()) return null
        return cleaned.take(MAX_TITLE_LEN)
    }

    private fun decodeEntities(s: String): String {
        var out = s
        // 数字实体(十进制/十六进制)
        out = NUM_ENTITY_REGEX.replace(out) { m: MatchResult ->
            val code = m.groupValues[1].let {
                if (it.startsWith("x", true)) it.drop(1).toIntOrNull(16) else it.toIntOrNull()
            }
            if (code != null && code in 0..0x10FFFF) {
                runCatching { String(Character.toChars(code)) }.getOrDefault(m.value)
            } else m.value
        }
        out = NAMED_ENTITIES.entries.fold(out) { acc, (name, value) -> acc.replace("&$name;", value) }
        return out
    }

    companion object {
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_TITLE_LEN = 120
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        private val TITLE_REGEX = Regex(
            """<title[^>]*>(.*?)</title>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val WHITESPACE = Regex("""\s+""")
        private val NUM_ENTITY_REGEX = Regex("""&#(x?[0-9a-fA-F]+);""")
        private val NAMED_ENTITIES = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "nbsp" to " ", "copy" to "©", "reg" to "®", "trade" to "™",
            "mdash" to "—", "ndash" to "–", "hellip" to "…", "middot" to "·",
            "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
        )
    }
}
