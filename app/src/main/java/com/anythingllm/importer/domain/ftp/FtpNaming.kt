package com.anythingllm.importer.domain.ftp

import com.anythingllm.importer.data.library.LibraryEntry
import com.anythingllm.importer.data.library.LibraryRepository

/**
 * FTP 同步命名与路径规划(v1.3,纯函数可单测):
 * - 远端文件名:文件=清洗后的标题(UTF-8 可读,PC 目录友好);链接={可读标题}.url;
 * - 链接标题若为完整 URL,取域名(https://example.com/a → example.com)更可读;
 * - 目录链 = 资料库文件夹名链;远端路径 = {remoteRoot}/{目录链}/{文件名}。
 */
object FtpNaming {

    fun cleanFileName(name: String): String = LibraryRepository.cleanFileName(name)

    /** 链接条目远端落盘文件名:{可读标题}.url(Windows InternetShortcut) */
    fun linkRemoteName(entry: LibraryEntry): String {
        val base = entry.title.ifBlank { entry.url ?: "link" }
        val readable = if (base.startsWith("http://") || base.startsWith("https://")) {
            com.anythingllm.importer.domain.link.LinkParser().hostOf(entry.url ?: base)
        } else {
            base
        }
        return "${cleanFileName(readable)}.url"
    }

    /** 条目远端文件名:链接走 .url 规则,文件走清洗标题 */
    fun remoteFileName(entry: LibraryEntry): String = when (entry.type) {
        com.anythingllm.importer.data.library.LibraryEntryType.LINK -> linkRemoteName(entry)
        com.anythingllm.importer.data.library.LibraryEntryType.FILE -> cleanFileName(entry.title)
    }

    /** 远端相对路径(不含 remoteRoot):目录链 + 文件名 */
    fun remoteRelativePath(folderChain: List<String>, fileName: String): String =
        (folderChain + fileName).joinToString("/")

    /** .url 文本内容(RFC 兼容 Windows InternetShortcut) */
    fun buildUrlContent(url: String): String = "[InternetShortcut]\r\nURL=$url\r\n"
}
