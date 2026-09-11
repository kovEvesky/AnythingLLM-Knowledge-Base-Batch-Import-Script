package com.anythingllm.importer.data.api

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * AnythingLLM API 客户端工厂。
 * - 每次调用基于最新配置(地址/Key/超时)创建独立实例,便于设置改动后即时生效;
 * - 认证:Bearer 拦截器;
 * - 超时映射(开发文档 §5.8):connect 5s / 默认 api 60s,上传/嵌入/聊天按需指定更长超时。
 */
class AnythingLlmClientFactory {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        // 请求体显式携带全部字段(含默认值),与服务端契约/PC 版行为一致
        encodeDefaults = true
    }

    private val contentType = "application/json".toMediaType()

    fun create(
        baseUrl: String,
        apiKey: String,
        connectTimeoutSec: Long = 5,
        readTimeoutSec: Long = 60,
        writeTimeoutSec: Long = 60,
    ): AnythingLLMApi {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val client = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .build()
                chain.proceed(request)
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(AnythingLLMApi::class.java)
    }
}
