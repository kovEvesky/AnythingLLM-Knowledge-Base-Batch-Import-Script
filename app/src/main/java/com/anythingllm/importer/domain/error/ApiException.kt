package com.anythingllm.importer.domain.error

import com.anythingllm.importer.domain.import.ImportException
import java.io.IOException
import java.net.SocketTimeoutException
import retrofit2.HttpException

/**
 * 统一 API 错误模型(开发文档 §5.7 错误分类)。
 * UI 层通过 [toUserMessage] 直接取中文提示。
 */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 服务器不可达:连接失败 / ping 超时 */
    class Unreachable(cause: Throwable) : ApiException("无法连接服务器", cause)

    /** API Key 无效:auth 非 200(401/403) */
    object InvalidKey : ApiException("API Key 无效")

    /** 服务器返回非预期状态码 */
    class ServerError(val code: Int, detail: String? = null) :
        ApiException("服务器返回错误($code)${detail?.let { " - $it" } ?: ""}")

    /** 请求超时 */
    class Timeout(cause: Throwable) : ApiException("请求超时", cause)

    /** 其他 */
    class Unexpected(cause: Throwable) : ApiException("发生未知错误: ${cause.message}", cause)
}

/** 将任意 Throwable 映射为 [ApiException](HttpException 也是 IOException,必须先判) */
fun Throwable.toApiException(): ApiException = when (this) {
    is ApiException -> this
    is HttpException -> when (code()) {
        401, 403 -> ApiException.InvalidKey
        else -> ApiException.ServerError(code())
    }
    is SocketTimeoutException -> ApiException.Timeout(this)
    is IOException -> ApiException.Unreachable(this)
    else -> ApiException.Unexpected(this)
}

/** 用户可读的中文错误提示(日志不记录 Key,此映射天然不含敏感信息) */
fun Throwable.toUserMessage(): String = when (this) {
    is ApiException -> message ?: "未知错误"
    else -> toApiException().message ?: "未知错误"
}

/**
 * 导入引擎专用错误映射:业务语义错误(ImportException)保留原始文案;
 * 其余(网络/超时/服务器错误)走统一映射。
 */
fun Throwable.toImportErrorMessage(): String = when (this) {
    is ApiException -> message ?: "未知错误"
    is ImportException -> message ?: "未知错误"
    else -> toApiException().message ?: "未知错误"
}
