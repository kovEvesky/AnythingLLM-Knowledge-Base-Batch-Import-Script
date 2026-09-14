package com.anythingllm.importer.data.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * v1.5 FTP 扫码连接:PC 端 ftp_server.py 输出的二维码内容(JSON)。
 * 约定格式:{"v":1,"t":"anythingllm-ftp","host":"192.168.1.100","port":2121,
 *           "user":"sync","password":"...","root":"Library"}
 * 校验:类型标记 + host 非空 + 端口合法,避免误扫任意二维码。
 */
@Serializable
data class FtpQrConfig(
    val v: Int = 1,
    val t: String = "anythingllm-ftp",
    val host: String = "",
    val port: Int = 2121,
    val user: String = "sync",
    val password: String = "",
    val root: String = "Library",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 解析并校验;非法内容返回 null(不抛异常) */
        fun parse(text: String): FtpQrConfig? = runCatching {
            val cfg = json.decodeFromString(FtpQrConfig.serializer(), text)
            if (cfg.t == "anythingllm-ftp" &&
                cfg.host.isNotBlank() &&
                cfg.port in 1..65535 &&
                cfg.user.isNotBlank()
            ) cfg else null
        }.getOrNull()
    }
}
