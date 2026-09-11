package com.anythingllm.importer.domain.probe

import com.anythingllm.importer.data.api.AnythingLlmClientFactory
import com.anythingllm.importer.domain.error.ApiException
import com.anythingllm.importer.domain.error.toApiException

/**
 * 连接探测结果三态(开发文档 §3.2 FR-01 / §5.7)。
 */
sealed interface ProbeResult {
    /** 连接正常,附带工作区数量 */
    data class Ok(val workspaceCount: Int) : ProbeResult

    /** 服务器不可达:ping 超时/连接失败 */
    data object Unreachable : ProbeResult

    /** Key 无效:ping 通但 auth 失败 */
    data object InvalidKey : ProbeResult

    /** 服务器可达但返回异常(非预期状态码等) */
    data class ServerError(val detail: String) : ProbeResult
}

/**
 * 三级探测:ping → auth → workspaces。
 * 区分「不可达 / Key 无效 / 正常」三种状态。
 */
class ConnectionProbe(
    private val factory: AnythingLlmClientFactory,
) {
    suspend fun probe(baseUrl: String, apiKey: String, probeTimeoutSec: Long = 5): ProbeResult {
        val api = factory.create(baseUrl, apiKey, readTimeoutSec = probeTimeoutSec)
        return try {
            // 1. 探活(无需 Key)
            val ping = api.ping()
            if (!ping.online) return ProbeResult.Unreachable
            // 2. Key 校验
            val auth = api.auth()
            if (!auth.authenticated) return ProbeResult.InvalidKey
            // 3. 拉工作区列表(兼做连通性验证)
            val workspaces = api.workspaces().workspaces
            ProbeResult.Ok(workspaces.size)
        } catch (e: Exception) {
            when (val mapped = e.toApiException()) {
                is ApiException.InvalidKey -> ProbeResult.InvalidKey
                is ApiException.ServerError -> ProbeResult.ServerError(mapped.message ?: "服务器返回错误")
                else -> ProbeResult.Unreachable
            }
        }
    }
}
