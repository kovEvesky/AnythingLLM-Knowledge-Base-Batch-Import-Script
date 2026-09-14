package com.anythingllm.importer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.CollectRepository
import com.anythingllm.importer.data.collect.EntrySource
import com.anythingllm.importer.data.collect.EntryType
import com.anythingllm.importer.domain.link.LinkParser
import com.anythingllm.importer.domain.link.LinkTitleFetcher
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Android 分享接收器(v1.2 FR-17):
 * 接收 SEND / SEND_MULTIPLE 的文件与文本/链接,复制/暂存到收集箱后立即结束。
 * 透明主题,无 UI;接收结果通过"已暂存"提示由收集箱页展示。
 * 关键:文件接收即复制到私有目录(防 URI 权限过期)。
 */
class ShareReceiver : Activity() {

    private val parser = LinkParser()
    private val titleFetcher = LinkTitleFetcher()

    companion object {
        private const val TITLE_WAIT_MS = 6_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AnythingLLMApp
        val repo = app.collectRepository
        val action = intent?.action
        when (action) {
            Intent.ACTION_SEND -> handleSend(intent!!, repo)
            Intent.ACTION_SEND_MULTIPLE -> handleSendMultiple(intent!!, repo)
            else -> Unit
        }
        finish()
    }

    private fun handleSend(intent: Intent, repo: CollectRepository) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val links = text?.let { parser.extract(it) }.orEmpty()
        if (links.isNotEmpty()) {
            val jobs = links.map { addLink(repo, it) }
            awaitTitle(jobs)
            openCollector()
            return
        }
        val stream = extractUris(intent).firstOrNull()
        if (stream != null && copyAndAdd(repo, stream)) openCollector()
    }

    private fun handleSendMultiple(intent: Intent, repo: CollectRepository) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val links = text?.let { parser.extract(it) }.orEmpty()
        if (links.isNotEmpty()) {
            val jobs = links.map { addLink(repo, it) }
            awaitTitle(jobs)
            openCollector()
            return
        }
        var added = 0
        extractUris(intent).forEach { if (copyAndAdd(repo, it)) added++ }
        if (added > 0) openCollector()
    }

    /**
     * 等待标题抓取线程结束(单条最长 6s,并行)。
     * v1.5-需求一:必须等回填完成再打开收集箱,否则 UI 先读到域名标题且不再刷新。
     */
    private fun awaitTitle(jobs: List<Thread>) {
        jobs.forEach { runCatching { it.join(TITLE_WAIT_MS) } }
    }

    /**
     * 安全提取分享 URI(BUG-多选-01):
     * 分享器形态不一——EXTRA_STREAM 可能是单 Uri、ArrayList<Uri> 或 Parcelable[],
     * 且部分文件管理器只发 clipData 多 item。用 Bundle.get 原始对象做类型判断,
     * 避免 getParcelableArrayListExtra 在单 Uri 时抛 ClassCastException 导致接收崩溃无反应。
     * get(String) 在新 SDK 标记 deprecated,但多形态分发无类型化替代,有意保留(编译卫生标注)。
     */
    @Suppress("DEPRECATION")
    private fun extractUris(intent: Intent): List<Uri> {
        val result = LinkedHashSet<Uri>()
        intent.extras?.get(Intent.EXTRA_STREAM)?.let { raw ->
            when (raw) {
                is Uri -> result.add(raw)
                is List<*> -> raw.filterIsInstance<Uri>().forEach { result.add(it) }
                is Array<*> -> raw.filterIsInstance<Uri>().forEach { result.add(it) }
                else -> Unit
            }
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { result.add(it) }
            }
        }
        return result.toList()
    }

    /** 暂存成功后打开收集箱主界面,给用户可见反馈(分享方流程可返回键退回) */
    private fun openCollector() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    /** 暂存链接并异步抓取标题;返回抓取线程(调用方 awaitTitle 等待) */
    private fun addLink(repo: CollectRepository, url: String): Thread {
        val id = UUID.randomUUID().toString()
        repo.add(
            CollectEntry(
                id = id,
                type = EntryType.LINK,
                source = EntrySource.SHARE_LINK,
                url = url,
                title = parser.hostOf(url),
                collectedAt = nowIso(),
            ),
        )
        // v1.5-需求一:异步抓取网页 <title> 回填(失败保持域名占位)
        return Thread {
            val fetched = titleFetcher.fetch(url)
            if (fetched != null) {
                repo.update(id) { it.copy(title = fetched) }
            }
        }.apply { isDaemon = true }.also { it.start() }
    }

    /** 复制到私有目录并入库;成功返回 true(供多选计数) */
    private fun copyAndAdd(repo: CollectRepository, uri: Uri): Boolean {
        var ok = false
        runCatching {
            val name = queryDisplayName(uri) ?: "share_${System.currentTimeMillis()}"
            val size = querySize(uri)
            val id = UUID.randomUUID().toString()
            val day = LocalDate.now()
            val dir = repo.dayDir(day)
            dir.mkdirs()
            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val dest = File(dir, "${id}_$safeName")
            val input = contentResolver.openInputStream(uri) ?: return@runCatching
            input.use { ins -> dest.outputStream().use { outs -> ins.copyTo(outs) } }
            if (dest.length() == 0L) {
                dest.delete()
                return@runCatching
            }
            repo.add(
                CollectEntry(
                    id = id,
                    type = EntryType.FILE,
                    source = EntrySource.SHARE_FILE,
                    fileName = name,
                    localPath = dest.absolutePath,
                    sizeBytes = if (size > 0) size else dest.length(),
                    title = name,
                    collectedAt = nowIso(),
                ),
            )
            ok = true
        }
        // 接收失败静默:不阻塞分享方,收集箱不出现条目即可
        return ok
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else -1L
        }
    }.getOrNull() ?: -1L

    private fun nowIso(): String = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
