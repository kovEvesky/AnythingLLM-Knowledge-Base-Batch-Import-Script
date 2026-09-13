package com.anythingllm.importer.data.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.Normalizer

/**
 * 规范化 API Key:
 * - 首尾空白去除;
 * - NFKC 归一化(全角连字符/字母/数字等 → 半角,规避中文输入法标点自动全角化);
 * - 仅保留 ASCII 字母/数字/`-`(AnythingLLM API Key 合法字符集),其余字符剥离;
 * 防止非法字符进入 Authorization 头导致 OkHttp `IllegalArgumentException` 崩溃(BUG-全面验证-02)。
 */
internal fun normalizeApiKey(raw: String): String {
    val nfkc = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
    return nfkc.filter {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-'
    }
}

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "config")

/**
 * 配置仓库(开发文档 §6.2):
 * - 非敏感配置 → DataStore(Preferences);
 * - API Key → Keystore 加密后同样存于 DataStore(避免引入已废弃的 security-crypto 依赖);
 * - 解密失败时回退为空 Key(如系统迁移/密钥失效),不崩溃。
 */
class ConfigRepository(private val context: Context) {

    private val crypto = KeyStoreCrypto()

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key_encrypted")
        val ALLOWED_EXTENSIONS = stringPreferencesKey("allowed_extensions")
        val MAX_FILE_SIZE_MB = intPreferencesKey("max_file_size_mb")
        val FILENAME_POLICY = stringPreferencesKey("filename_policy")
        val DETECT_DUPLICATES = booleanPreferencesKey("detect_duplicates")
        val DUPLICATE_ACTION = stringPreferencesKey("duplicate_default_action")
        val DEFAULT_WORKSPACE = stringPreferencesKey("default_workspace")
        val UPLOAD_RETRY_COUNT = intPreferencesKey("upload_retry_count")
        val UPLOAD_RETRY_BASE_DELAY = intPreferencesKey("upload_retry_base_delay")
        val IMPORT_CONCURRENCY = intPreferencesKey("import_concurrency")
        val VERIFY_TIMEOUT = intPreferencesKey("verify_timeout_sec")
        val VERIFY_BASE_DELAY = intPreferencesKey("verify_base_delay_sec")
        val VERIFY_MAX_DELAY = intPreferencesKey("verify_max_delay_sec")
        val PROBE_TIMEOUT = intPreferencesKey("probe_timeout_sec")
        val UPLOAD_TIMEOUT = intPreferencesKey("upload_timeout_sec")
        val EMBED_TIMEOUT = intPreferencesKey("embed_timeout_sec")
        val API_TIMEOUT = intPreferencesKey("api_timeout_sec")
        val CHAT_TIMEOUT = intPreferencesKey("chat_timeout_sec")
        val ASK_FOR_CHAT_TEST = booleanPreferencesKey("ask_for_chat_test")
        val LOG_RETENTION_DAYS = intPreferencesKey("log_retention_days")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        // v1.3 FTP 同步(FR-32)
        val FTP_HOST = stringPreferencesKey("ftp_host")
        val FTP_PORT = intPreferencesKey("ftp_port")
        val FTP_USER = stringPreferencesKey("ftp_user")
        val FTP_PASSWORD = stringPreferencesKey("ftp_password")
        val FTP_REMOTE_ROOT = stringPreferencesKey("ftp_remote_root")
        // v1.4 UI-19 首启引导
        val GUIDE_SEEN = booleanPreferencesKey("guide_seen")
    }

    /** 配置流:每次 DataStore 变更/Key 解密后发射新值 */
    val config: Flow<AppConfig> = context.configDataStore.data.map { prefs ->
        AppConfig(
            baseUrl = prefs[Keys.BASE_URL] ?: AppConfig.DEFAULT_BASE_URL,
            apiKey = decryptKey(prefs[Keys.API_KEY]),
            allowedExtensions = AppConfig.parseExtensions(prefs[Keys.ALLOWED_EXTENSIONS] ?: "")
                .ifEmpty { AppConfig.DEFAULT_ALLOWED_EXTENSIONS },
            maxFileSizeMB = prefs[Keys.MAX_FILE_SIZE_MB] ?: 100,
            filenamePolicy = runCatching {
                FilenamePolicy.valueOf(prefs[Keys.FILENAME_POLICY] ?: "")
            }.getOrDefault(FilenamePolicy.UNICODE),
            detectDuplicates = prefs[Keys.DETECT_DUPLICATES] ?: true,
            duplicateDefaultAction = runCatching {
                DuplicateAction.valueOf(prefs[Keys.DUPLICATE_ACTION] ?: "")
            }.getOrDefault(DuplicateAction.ASK),
            defaultWorkspace = prefs[Keys.DEFAULT_WORKSPACE] ?: "",
            uploadRetryCount = prefs[Keys.UPLOAD_RETRY_COUNT] ?: 2,
            uploadRetryBaseDelaySec = prefs[Keys.UPLOAD_RETRY_BASE_DELAY] ?: 2,
            importConcurrency = prefs[Keys.IMPORT_CONCURRENCY] ?: 2,
            verifyTimeoutSec = prefs[Keys.VERIFY_TIMEOUT] ?: 300,
            verifyBaseDelaySec = prefs[Keys.VERIFY_BASE_DELAY] ?: 2,
            verifyMaxDelaySec = prefs[Keys.VERIFY_MAX_DELAY] ?: 5,
            probeTimeoutSec = prefs[Keys.PROBE_TIMEOUT] ?: 5,
            uploadTimeoutSec = prefs[Keys.UPLOAD_TIMEOUT] ?: 300,
            embedTimeoutSec = prefs[Keys.EMBED_TIMEOUT] ?: 120,
            apiTimeoutSec = prefs[Keys.API_TIMEOUT] ?: 60,
            chatTimeoutSec = prefs[Keys.CHAT_TIMEOUT] ?: 300,
            askForChatTest = prefs[Keys.ASK_FOR_CHAT_TEST] ?: false,
            logRetentionDays = prefs[Keys.LOG_RETENTION_DAYS] ?: 30,
            themeMode = runCatching {
                ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: "")
            }.getOrDefault(ThemeMode.SYSTEM),
            guideSeen = prefs[Keys.GUIDE_SEEN] ?: false,
            ftp = FtpConfig(
                host = prefs[Keys.FTP_HOST] ?: "",
                port = prefs[Keys.FTP_PORT] ?: 2121,
                username = prefs[Keys.FTP_USER] ?: "sync",
                password = prefs[Keys.FTP_PASSWORD] ?: "sync123",
                remoteRoot = prefs[Keys.FTP_REMOTE_ROOT] ?: "Library",
            ),
        )
    }

    /** 一次性读取当前配置(测试连接/导入前取快照用) */
    suspend fun snapshot(): AppConfig = config.first()

    /** UI-19:标记首启引导已看过(独立写入,不触发全量保存) */
    suspend fun markGuideSeen() {
        context.configDataStore.edit { prefs -> prefs[Keys.GUIDE_SEEN] = true }
    }

    /** 保存全部配置;apiKey 为空时不覆盖已存 Key(保存前经 normalizeApiKey 规范化) */
    suspend fun save(config: AppConfig) {
        context.configDataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = config.baseUrl
            val normalizedKey = normalizeApiKey(config.apiKey)
            if (normalizedKey.isNotBlank()) {
                prefs[Keys.API_KEY] = crypto.encrypt(normalizedKey)
            }
            prefs[Keys.ALLOWED_EXTENSIONS] = config.allowedExtensions.joinToString(",")
            prefs[Keys.MAX_FILE_SIZE_MB] = config.maxFileSizeMB
            prefs[Keys.FILENAME_POLICY] = config.filenamePolicy.name
            prefs[Keys.DETECT_DUPLICATES] = config.detectDuplicates
            prefs[Keys.DUPLICATE_ACTION] = config.duplicateDefaultAction.name
            prefs[Keys.DEFAULT_WORKSPACE] = config.defaultWorkspace
            prefs[Keys.UPLOAD_RETRY_COUNT] = config.uploadRetryCount
            prefs[Keys.UPLOAD_RETRY_BASE_DELAY] = config.uploadRetryBaseDelaySec
            prefs[Keys.IMPORT_CONCURRENCY] = config.importConcurrency
            prefs[Keys.VERIFY_TIMEOUT] = config.verifyTimeoutSec
            prefs[Keys.VERIFY_BASE_DELAY] = config.verifyBaseDelaySec
            prefs[Keys.VERIFY_MAX_DELAY] = config.verifyMaxDelaySec
            prefs[Keys.PROBE_TIMEOUT] = config.probeTimeoutSec
            prefs[Keys.UPLOAD_TIMEOUT] = config.uploadTimeoutSec
            prefs[Keys.EMBED_TIMEOUT] = config.embedTimeoutSec
            prefs[Keys.API_TIMEOUT] = config.apiTimeoutSec
            prefs[Keys.CHAT_TIMEOUT] = config.chatTimeoutSec
            prefs[Keys.ASK_FOR_CHAT_TEST] = config.askForChatTest
            prefs[Keys.LOG_RETENTION_DAYS] = config.logRetentionDays
            prefs[Keys.THEME_MODE] = config.themeMode.name
            prefs[Keys.GUIDE_SEEN] = config.guideSeen
            prefs[Keys.FTP_HOST] = config.ftp.host
            prefs[Keys.FTP_PORT] = config.ftp.port
            prefs[Keys.FTP_USER] = config.ftp.username
            prefs[Keys.FTP_PASSWORD] = config.ftp.password
            prefs[Keys.FTP_REMOTE_ROOT] = config.ftp.remoteRoot
        }
    }

    private fun decryptKey(encrypted: String?): String {
        if (encrypted.isNullOrBlank()) return ""
        return runCatching { crypto.decrypt(encrypted) }.getOrDefault("")
    }
}
