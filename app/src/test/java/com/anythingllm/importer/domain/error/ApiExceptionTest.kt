package com.anythingllm.importer.domain.error

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

class ApiExceptionTest {

    private fun httpError(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType())))

    @Test
    fun `401映射为InvalidKey`() {
        assertTrue(httpError(401).toApiException() is ApiException.InvalidKey)
    }

    @Test
    fun `403映射为InvalidKey`() {
        assertTrue(httpError(403).toApiException() is ApiException.InvalidKey)
    }

    @Test
    fun `500映射为ServerError`() {
        val e = httpError(500).toApiException()
        assertTrue(e is ApiException.ServerError)
        assertEquals(500, (e as ApiException.ServerError).code)
    }

    @Test
    fun `连接失败映射为Unreachable`() {
        assertTrue(IOException("connection refused").toApiException() is ApiException.Unreachable)
    }

    @Test
    fun `超时映射为Timeout`() {
        assertTrue(SocketTimeoutException("timeout").toApiException() is ApiException.Timeout)
    }

    @Test
    fun `已知ApiException原样返回`() {
        val original = ApiException.InvalidKey
        assertEquals(original, original.toApiException())
    }

    @Test
    fun `用户提示为中文且不含Key`() {
        assertEquals("API Key 无效", ApiException.InvalidKey.toUserMessage())
        assertEquals("无法连接服务器", ApiException.Unreachable(IOException()).toUserMessage())
        assertEquals("请求超时", ApiException.Timeout(SocketTimeoutException()).toUserMessage())
    }

    @Test
    fun `HttpException先于IOException判定`() {
        // HttpException 是 IOException 子类,必须先判为 InvalidKey 而非 Unreachable
        assertTrue(httpError(401).toApiException() is ApiException.InvalidKey)
    }
}
