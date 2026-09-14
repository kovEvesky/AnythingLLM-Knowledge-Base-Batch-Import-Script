package com.anythingllm.importer.domain.ftp

import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.EntryType

/**
 * 收藏夹版 FTP 命名与路径规划(v1.9,§4.9 通道 A;纯函数可单测):
 * - 远端目录树 = {remoteRoot}/{收藏夹名}/;
 * - 文件条目远端文件名 = 清洗后的标题(UTF-8 可读,PC 目录友好);
 * - 链接条目 = {可读标题}.url(Windows InternetShortcut);标题为完整 URL 时取域名。
 */
object CollectFtpNaming {

    /** 清洗文件名字符(与 LibraryRepository.cleanFileName 同口径,独立实现避免跨层依赖) */
    fun cleanFileName(name: String): String = name
        .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
        .trim()
        .ifBlank { "file" }

    /** 链接条目远端落盘文件名:{可读标题}.url */
    fun linkRemoteName(title: String?, url: String?): String {
        val base = title?.takeIf { it.isNotBlank() } ?: url ?: "link"
        val readable = if (base.startsWith("http://") || base.startsWith("https://")) {
            com.anythingllm.importer.domain.link.LinkParser().hostOf(url ?: base)
        } else {
            base
        }
        return "${cleanFileName(readable)}.url"
    }

    /** 条目远端文件名:链接走 .url 规则,文件走清洗标题 */
    fun remoteFileName(entry: CollectEntry): String = when (entry.type) {
        EntryType.LINK -> linkRemoteName(entry.title, entry.url)
        EntryType.FILE -> cleanFileName(entry.title ?: entry.fileName ?: entry.id)
    }

    /** .url 文本内容(RFC 兼容 Windows InternetShortcut) */
    fun buildUrlContent(url: String): String = "[InternetShortcut]\r\nURL=$url\r\n"
}
