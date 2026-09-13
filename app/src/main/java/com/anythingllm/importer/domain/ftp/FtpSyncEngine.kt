package com.anythingllm.importer.domain.ftp

import com.anythingllm.importer.data.config.FtpConfig
import com.anythingllm.importer.data.library.LibraryEntry
import com.anythingllm.importer.data.library.LibraryEntryType
import com.anythingllm.importer.data.library.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.File
import java.io.IOException

/** FTP 同步条目状态(v1.3 FR-33) */
enum class FtpItemStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

data class FtpItemState(
    val entry: LibraryEntry,
    val status: FtpItemStatus = FtpItemStatus.PENDING,
    val message: String? = null,
)

data class FtpSyncState(
    val running: Boolean = false,
    val items: List<FtpItemState> = emptyList(),
) {
    val totalCount get() = items.size
    val doneCount
        get() = items.count { it.status == FtpItemStatus.SUCCESS || it.status == FtpItemStatus.FAILED || it.status == FtpItemStatus.SKIPPED }
    val successCount get() = items.count { it.status == FtpItemStatus.SUCCESS }
    val failedCount get() = items.count { it.status == FtpItemStatus.FAILED }
    val isTerminal get() = !running && items.isNotEmpty() && doneCount == totalCount
}

/**
 * FTP 同步引擎(v1.3 FR-33,设计《10-v1.3开发计划》§3.4):
 * - 手机端作为 FTP 客户端,把资料库内容按文件夹树上传到 PC(未安装 AnythingLLM 场景的同步通道);
 * - 被动模式 + UTF-8 控制编码 + 二进制传输;远端目录逐级创建;
 * - 增量:仅同步未同步/大小变化的条目(syncedAt/syncedSize 判定,仓储层 pendingSync);
 * - 单条目失败保留待同步状态,整批可重试;单条目内 2 次指数退避重试。
 */
class FtpSyncEngine(
    private val config: FtpConfig,
    private val libraryRepository: LibraryRepository,
) {

    private val _state = MutableStateFlow(FtpSyncState())
    val state: StateFlow<FtpSyncState> = _state.asStateFlow()

    private var job: Job? = null

    fun launch(scope: CoroutineScope) {
        if (_state.value.running) return
        val pending = libraryRepository.pendingSync()
        _state.value = FtpSyncState(running = true, items = pending.map { FtpItemState(it) })
        if (pending.isEmpty()) {
            _state.update { it.copy(running = false) }
            return
        }
        if (!config.isConfigured) {
            markAllFailed("未配置 FTP 服务器,请到设置页填写主机地址")
            return
        }
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { runSync(pending) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markAllFailed("同步异常:${e.message ?: "未知错误"}")
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun runSync(pending: List<LibraryEntry>) {
        val client = FTPClient()
        try {
            client.connectTimeout = 10_000
            client.setControlEncoding("UTF-8")
            client.connect(config.host, config.port)
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                markAllFailed("FTP 连接失败:${client.replyString?.trim() ?: "服务器无响应"}")
                return
            }
            if (!client.login(config.username, config.password)) {
                markAllFailed("FTP 登录失败:用户名或密码错误")
                return
            }
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            ensureDir(client, config.remoteRoot)

            pending.forEach { runItem(client, it) }
            client.logout()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markAllFailed("FTP 连接失败:${e.message ?: "未知错误"}")
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private suspend fun runItem(client: FTPClient, entry: LibraryEntry) {
        val name = FtpNaming.remoteFileName(entry)
        val chain = libraryRepository.pathOf(entry.folderId)
        val rel = FtpNaming.remoteRelativePath(chain, name)
        val remote = "${config.remoteRoot}/$rel"
        setRunning(entry, "上传 $rel")
        val content = if (entry.type == LibraryEntryType.LINK) FtpNaming.buildUrlContent(entry.url.orEmpty()) else null
        val local = entry.localPath?.let { File(it) }
        if (entry.type == LibraryEntryType.FILE && (local == null || !local.exists())) {
            update(entry, FtpItemStatus.FAILED, "本地文件缺失,请重新归档")
            return
        }
        var attempt = 0
        val retryCount = 2
        while (true) {
            try {
                ensureDir(client, remote.substringBeforeLast('/'))
                val ok = when (entry.type) {
                    LibraryEntryType.FILE -> local!!.inputStream().use { client.storeFile(remote, it) }
                    LibraryEntryType.LINK -> client.storeFile(remote, content!!.byteInputStream(Charsets.UTF_8))
                }
                if (!ok) throw IOException(client.replyString?.trim() ?: "FTP 服务器拒绝上传")
                val size = if (entry.type == LibraryEntryType.FILE) local!!.length() else content!!.length.toLong()
                libraryRepository.markSynced(entry.id, remote, size)
                update(entry, FtpItemStatus.SUCCESS, "已同步")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= retryCount) {
                    update(entry, FtpItemStatus.FAILED, e.message ?: "上传失败")
                    return
                }
                attempt++
                val backoff = 2000L * (1L shl (attempt - 1))
                update(entry, FtpItemStatus.RUNNING, "上传失败,${backoff / 1000}s 后重试($attempt/$retryCount)")
                delay(backoff)
            }
        }
    }

    /** 逐级创建远端目录(已存在时 makeDirectory 返回 false 属正常,忽略) */
    private fun ensureDir(client: FTPClient, dir: String) {
        if (dir.isBlank() || dir == "/") return
        val segments = dir.split('/').filter { it.isNotBlank() }
        var current = ""
        for (seg in segments) {
            current = if (current.isEmpty()) seg else "$current/$seg"
            client.makeDirectory(current)
        }
    }

    private fun setRunning(entry: LibraryEntry, msg: String) = update(entry, FtpItemStatus.RUNNING, msg)

    private fun update(entry: LibraryEntry, status: FtpItemStatus, message: String?) {
        _state.update { st ->
            val items = st.items.toMutableList()
            val idx = items.indexOfFirst { it.entry.id == entry.id }
            if (idx < 0) return@update st
            items[idx] = items[idx].copy(status = status, message = message)
            st.copy(items = items)
        }
    }

    private fun markAllFailed(message: String) {
        _state.update { st ->
            st.copy(
                items = st.items.map {
                    if (it.status == FtpItemStatus.PENDING || it.status == FtpItemStatus.RUNNING) {
                        it.copy(status = FtpItemStatus.FAILED, message = message)
                    } else it
                },
            )
        }
    }
}
