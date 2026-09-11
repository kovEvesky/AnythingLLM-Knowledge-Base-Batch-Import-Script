package com.anythingllm.importer.data.api

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.UUID

private val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()

/**
 * multipart 上传体构造器。
 * 关键约束(开发文档 §4.4-1):metadata 文本字段必须放在 file 字段之前
 * (服务端 multer 只解析 file 之前的文本字段);boundary 用随机 GUID。
 */
object UploadBodyFactory {

    /**
     * @param metadataJson 如 {"title":"原始文件名.txt"}
     * @param storageName  转换后的存储名(ASCII 安全),作为 file 的 filename
     * @param fileBody     文件流(建议 ContentUriRequestBody,避免整体载入内存)
     */
    fun build(
        metadataJson: String,
        storageName: String,
        fileBody: RequestBody,
        boundary: String = "Aio" + UUID.randomUUID().toString().replace("-", ""),
    ): RequestBody {
        val metadataPart = MultipartBody.Part.createFormData(
            "metadata",
            metadataJson,
        )
        val filePart = MultipartBody.Part.createFormData("file", storageName, fileBody)
        return MultipartBody.Builder(boundary)
            .setType(MultipartBody.FORM)
            .addPart(metadataPart)
            .addPart(filePart)
            .build()
    }
}

/**
 * 从 content:// Uri 流式读取的文件体:不整体载入内存,支持上传进度回调。
 */
class ContentUriRequestBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val sizeBytes: Long,
    private val onProgress: ((uploadedBytes: Long) -> Unit)? = null,
) : RequestBody() {

    override fun contentType() = OCTET_STREAM

    override fun contentLength() = sizeBytes

    override fun writeTo(sink: BufferedSink) {
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开文件流: $uri")
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var uploaded = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                uploaded += read
                onProgress?.invoke(uploaded)
            }
        }
        sink.flush()
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}
