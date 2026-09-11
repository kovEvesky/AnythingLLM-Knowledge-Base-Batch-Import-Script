package com.anythingllm.importer.domain.link

/**
 * 分享文本中的 URL 识别(v1.2 FR-19)。
 * 纯 Kotlin,可 JVM 单测。规则:
 * - 仅识别带 http/https 协议的 URL(不处理 www. 无协议写法,避免误匹配);
 * - 剥离中英文尾部标点与闭合括号;
 * - 去重、保持输入顺序。
 */
class LinkParser {

    private val urlRegex = Regex("""https?://[^\s"'<>，。；、（）【】《》！？]+""")

    fun extract(text: String): List<String> = urlRegex
        .findAll(text)
        .map { trimTrailingPunctuation(it.value) }
        .filter { isValidUrl(it) }
        .distinct()
        .toList()

    /** 展示用域名(去协议、去路径):https://example.com/a → example.com */
    fun hostOf(url: String): String = runCatching {
        val noScheme = url.removePrefix("https://").removePrefix("http://")
        noScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
    }.getOrDefault(url)

    private fun trimTrailingPunctuation(url: String): String {
        var result = url
        while (result.isNotEmpty() && result.last() in TRAILING_PUNCTUATION) {
            result = result.dropLast(1)
        }
        return result
    }

    private fun isValidUrl(url: String): Boolean {
        val host = runCatching {
            val noScheme = url.removePrefix("https://").removePrefix("http://")
            noScheme.substringBefore('/').substringBefore(':')
        }.getOrDefault("")
        return host.isNotBlank() && host.contains('.')
    }

    companion object {
        private val TRAILING_PUNCTUATION = ".,;:!?、，。；：！？)】]}》>）".toSet()
    }
}
