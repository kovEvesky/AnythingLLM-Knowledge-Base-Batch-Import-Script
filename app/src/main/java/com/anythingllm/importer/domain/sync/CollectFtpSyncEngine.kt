package com.anythingllm.importer.domain.sync

import com.anythingllm.importer.data.collect.CollectEntry
import com.anythingllm.importer.data.collect.CollectRepository
import com.anythingllm.importer.data.collect.EntryStatus
import com.anythingllm.importer.data.collect.EntryType
import com.anythingllm.importer.data.config.FtpConfig
import com.anythingllm.importer.data.favorite.FavoriteRepository
import com.anythingllm.importer.domain.ftp.CollectFtpNaming
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

/**
 * 收藏夹版 FTP 同步引擎(v1.9,§4.9 通道 A):
 * - 输入:已归类灰卡条目(排除回收站);增量判定:EXECUTED 且 serverLocation 以 "ftp://" 开头视为 FTP 已同步;
 * - 远端目录树 = {remoteRoot}/{收藏夹名}/,逐级创建;文件副本 + 链接 .url(RFC InternetShortcut);
 * - 幂等覆盖:同名远端文件直接覆盖(每次 FTP 同步都是最新内容);
 * - 单条目失败保留待同步状态,整批可重试;单条目内 2 次指数退避重试;
 * - 成功回写 EXECUTED + serverLocation="ftp://{host}:{port}/{remote}"(A4 定稿)。
 */
class CollectFtpSyncEngine(
    private val config: FtpConfig,
    private val collectRepository: CollectRepository,
    private val favoriteRepository: FavoriteRepository,
) {

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private var job: Job? = null

    fun launch(scope: CoroutineScope, entries: List<CollectEntry>) {
        if (_state.value.running) return
        // 增量:跳过 FTP 已同步条目(跨通道互不干扰:AnythingLLM 回写的位置不以 ftp:// 开头)
        val pending = entries.filter { !(it.status == EntryStatus.EXECUTED && it.serverLocation?.startsWith("ftp://") == true) }
        _state.value = SyncState(running = true, items = pending.map { SyncItemState(it) })
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

    private suspend fun runSync(pending: List<CollectEntry>) {
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

    private suspend fun runItem(client: FTPClient, entry: CollectEntry) {
        val folder = favoriteRepository.folderById(entry.markFolderId.orEmpty())
        val dirName = folder?.name ?: entry.markFolder ?: "未分类"
        val name = CollectFtpNaming.remoteFileName(entry)
        val remote = "${config.remoteRoot.trimEnd('/')}/$dirName/$name"
        setRunning(entry, "上传 ${remote.substringAfterLast('/')}")
        val content = if (entry.type == EntryType.LINK) CollectFtpNaming.buildUrlContent(entry.url.orEmpty()) else null
        val local = entry.localPath?.let { File(it) }
        if (entry.type == EntryType.FILE && (local == null || !local.exists())) {
            update(entry, SyncItemStatus.FAILED, "本地文件缺失,请重新收集")
            return
        }
        var attempt = 0
        val retryCount = 2
        while (true) {
            try {
                ensureDir(client, remote.substringBeforeLast('/'))
                val ok = when (entry.type) {
                    EntryType.FILE -> local!!.inputStream().use { client.storeFile(remote, it) }
                    EntryType.LINK -> client.storeFile(remote, content!!.byteInputStream(Charsets.UTF_8))
                }
                if (!ok) throw IOException(client.replyString?.trim() ?: "FTP 服务器拒绝上传")
                val size = if (entry.type == EntryType.FILE) local!!.length() else content!!.length.toLong()
                val location = "ftp://${config.host}:${config.port}/$remote"
                collectRepository.update(entry.id) {
                    it.copy(
                        status = EntryStatus.EXECUTED,
                        serverLocation = location,
                        error = null,
                        markedAt = it.markedAt,
                    )
                }
                update(entry, SyncItemStatus.SUCCESS, "已同步到 PC")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= retryCount) {
                    collectRepository.update(entry.id) {
                        it.copy(status = EntryStatus.FAILED, error = e.message ?: "上传失败")
                    }
                    update(entry, SyncItemStatus.FAILED, e.message ?: "上传失败")
                    return
                }
                attempt++
                val backoff = 2000L * (1L shl (attempt - 1))
                update(entry, SyncItemStatus.RUNNING, "上传失败,${backoff / 1000}s 后重试($attempt/$retryCount)")
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

    private fun setRunning(entry: CollectEntry, msg: String) = update(entry, SyncItemStatus.RUNNING, msg)

    private fun update(entry: CollectEntry, status: SyncItemStatus, message: String?) {
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
                    if (it.status == SyncItemStatus.PENDING || it.status == SyncItemStatus.RUNNING) {
                        it.copy(status = SyncItemStatus.FAILED, message = message)
                    } else it
                },
            )
        }
    }
}
