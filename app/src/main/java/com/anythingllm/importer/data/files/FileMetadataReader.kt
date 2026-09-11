package com.anythingllm.importer.data.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.anythingllm.importer.domain.validate.PickedFile

/**
 * 从 content:// Uri 读取文件元数据(名称/大小/类型)并申请持久读权限(FR-03)。
 */
object FileMetadataReader {

    fun read(context: Context, uri: Uri): PickedFile {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long = -1

        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }

        val displayName = name
            ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
            ?: "未命名文件"

        val mime = runCatching { resolver.getType(uri) }.getOrNull()

        return PickedFile(
            uriString = uri.toString(),
            displayName = displayName,
            sizeBytes = size,
            mimeType = mime,
        )
    }

    /** 申请跨重启的读权限(SAF 多选返回的 Uri 权限默认仅当次有效) */
    fun takePersistablePermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
}
