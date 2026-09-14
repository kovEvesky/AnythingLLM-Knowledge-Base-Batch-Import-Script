package com.anythingllm.importer.data.config

/**
 * 文件名策略(开发文档 §6.1 / §5.4)。
 */
enum class FilenamePolicy {
    /** 非 ASCII 转 uXXXX 十六进制(默认) */
    UNICODE,

    /** 非 ASCII 直接丢弃 */
    STRIP,

    /** 原样保留 */
    KEEP,
}

/**
 * 重复文档命中动作(开发文档 §6.1 / §5.6)。
 */
enum class DuplicateAction {
    /** 询问用户 */
    ASK,

    /** 全部保留(继续上传) */
    KEEP,

    /** 跳过 */
    SKIP,

    /** 替换(删除旧文档后上传) */
    REPLACE,

    /** 中止 */
    ABORT,
}

/**
 * 外观模式(FR-15 深色模式,阶段 5):跟随系统 / 强制浅色 / 强制深色。
 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK,
}

/**
 * FTP 同步配置(v1.3 FR-32,PC 端配合 tools/ftp-server/ftp_server.py):
 * - host:PC 局域网 IP(未安装 AnythingLLM 场景的同步目标);
 * - port:与 PC 脚本默认 2121 一致;
 * - remoteRoot:PC 同步目录下建立的远端根子目录(镜像资料库文件夹树)。
 */
data class FtpConfig(
    val host: String = "",
    val port: Int = 2121,
    val username: String = "sync",
    val password: String = "sync123",
    val remoteRoot: String = "Library",
) {
    val isConfigured: Boolean get() = host.isNotBlank()
}

/**
 * 应用配置(对应 PC 版 config.json,开发文档 §6.1)。
 * 全部字段可在设置页修改;默认值与文档一致。
 */
data class AppConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val allowedExtensions: List<String> = DEFAULT_ALLOWED_EXTENSIONS,
    val maxFileSizeMB: Int = 100,
    val filenamePolicy: FilenamePolicy = FilenamePolicy.UNICODE,
    val detectDuplicates: Boolean = true,
    val duplicateDefaultAction: DuplicateAction = DuplicateAction.ASK,
    val defaultWorkspace: String = "",
    val uploadRetryCount: Int = 2,
    val uploadRetryBaseDelaySec: Int = 2,
    val importConcurrency: Int = 2,
    val verifyTimeoutSec: Int = 300,
    val verifyBaseDelaySec: Int = 2,
    val verifyMaxDelaySec: Int = 5,
    val probeTimeoutSec: Int = 5,
    val uploadTimeoutSec: Int = 300,
    val embedTimeoutSec: Int = 120,
    val apiTimeoutSec: Int = 60,
    val chatTimeoutSec: Int = 300,
    val askForChatTest: Boolean = false,
    val logRetentionDays: Int = 30,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** v1.4 UI-19:首启引导是否已看过(UI 状态,复用 DataStore 通道,不入设置表单) */
    val guideSeen: Boolean = false,
    /** v1.3 FTP 同步配置(资料库 → PC,未安装 AnythingLLM 场景) */
    val ftp: FtpConfig = FtpConfig(),
    // ===== v1.7 收件箱范式:零决策默认值 =====
    /** v1.7 默认入库文件夹名(留空=未设置,需在整理时手选) */
    val defaultFolderName: String = "",
    /** v1.7 上次使用的标记文件夹(右滑"再用一次"用) */
    val lastMarkFolder: String = "",
    /** v1.7 上次使用的标记工作区 slug */
    val lastMarkWorkspace: String = "",
    /** v1.7 分享接收后不自动拉起主界面(静默入库,Toast+震动提示) */
    val silentReceive: Boolean = true,
    /** v1.7 接收成功时短震动反馈 */
    val hapticOnReceive: Boolean = true,
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://10.0.2.2:3001"

        val DEFAULT_ALLOWED_EXTENSIONS = listOf(
            ".pdf", ".docx", ".doc", ".txt", ".md", ".csv",
            ".xlsx", ".pptx", ".org", ".adoc", ".rst",
            ".json", ".html", ".odt", ".odp",
        )

        fun defaults() = AppConfig()

        // ===== 纯函数工具(供设置页字符串输入解析,可单测) =====

        /** "a,b,c" → [".a",".b"];保留点号写法,统一小写,自动补点 */
        fun parseExtensions(raw: String): List<String> = raw
            .split(',', '，', ';', '；', ' ', '\n')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith(".")) it else ".$it" }
            .distinct()

        /** 解析正整数;解析失败或小于最小值(负数/0)回退默认值,超上限收敛 */
        fun parsePositiveInt(raw: String, default: Int, min: Int = 1, max: Int = Int.MAX_VALUE): Int {
            val v = raw.trim().toIntOrNull() ?: return default
            if (v < min) return default
            return v.coerceAtMost(max)
        }
    }
}
