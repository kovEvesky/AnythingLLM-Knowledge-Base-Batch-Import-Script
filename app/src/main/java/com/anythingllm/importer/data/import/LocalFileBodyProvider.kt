package com.anythingllm.importer.data.import

import com.anythingllm.importer.domain.import.FileBodyProvider
import com.anythingllm.importer.domain.import.ImportTarget
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException

private val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()

/**
 * 收集箱执行用文件体提供者(v1.2 执行层):
 * 从收集箱文件副本(绝对路径)流式读取,不整体载入内存,支持进度回调。
 * ImportTarget.uriString 传入本地文件绝对路径。
 */
class LocalFileBodyProvider : FileBodyProvider {

    override fun bodyFor(target: ImportTarget, onProgress: (Long) -> Unit): RequestBody {
        val file = File(target.uriString)
        return object : RequestBody() {
            override fun contentType() = OCTET_STREAM

            override fun contentLength(): Long = file.length()

            override fun writeTo(sink: BufferedSink) {
                if (!file.exists()) throw IOException("收集箱文件不存在(可能已被清理): ${target.uriString}")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var uploaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        uploaded += read
                        onProgress(uploaded)
                    }
                }
                sink.flush()
            }
        }
    }
}
