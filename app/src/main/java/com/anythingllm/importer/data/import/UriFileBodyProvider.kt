package com.anythingllm.importer.data.import

import android.content.ContentResolver
import android.net.Uri
import com.anythingllm.importer.data.api.ContentUriRequestBody
import com.anythingllm.importer.domain.import.FileBodyProvider
import com.anythingllm.importer.domain.import.ImportTarget
import okhttp3.RequestBody

/**
 * 生产环境文件体提供者:从 SAF content:// Uri 流式读取(不整体载入内存,支持进度回调)。
 */
class UriFileBodyProvider(
    private val contentResolver: ContentResolver,
) : FileBodyProvider {

    override fun bodyFor(target: ImportTarget, onProgress: (Long) -> Unit): RequestBody {
        return ContentUriRequestBody(
            contentResolver = contentResolver,
            uri = Uri.parse(target.uriString),
            sizeBytes = target.sizeBytes,
            onProgress = onProgress,
        )
    }
}
