package com.anythingllm.importer

import android.app.Application
import com.anythingllm.importer.data.api.AnythingLlmClientFactory
import com.anythingllm.importer.data.collect.CollectRepository
import com.anythingllm.importer.data.config.ConfigRepository
import com.anythingllm.importer.data.favorite.FavoriteMigration
import com.anythingllm.importer.data.favorite.FavoriteRepository
import com.anythingllm.importer.data.log.ImportLogRepository
import com.anythingllm.importer.domain.probe.ConnectionProbe
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 搴旂敤鍏ュ彛:鎸佹湁鍏ㄥ眬鍗曚緥(杞婚噺鏈嶅姟瀹氫綅鍣?銆? */
class AnythingLLMApp : Application() {

    lateinit var configRepository: ConfigRepository
        private set

    lateinit var apiFactory: AnythingLlmClientFactory
        private set

    lateinit var connectionProbe: ConnectionProbe
        private set

    /** 瀵煎叆 JSONL 鏃ュ織浠撳偍(FR-13):logs 鐩綍浣嶄簬搴旂敤绉佹湁 filesDir,鍏嶅瓨鍌ㄦ潈闄?*/
    lateinit var importLogRepository: ImportLogRepository
        private set

    /** v1.2 鏀堕泦绠变粨鍌?FR-18):collect 鐩綍浣嶄簬搴旂敤绉佹湁 filesDir */
    lateinit var collectRepository: CollectRepository
        private set

    /** v1.9 鏀惰棌澶逛粨鍌?鍞竴鍒嗙被浣撶郴):favorites 鐩綍浣嶄簬搴旂敤绉佹湁 filesDir */
    lateinit var favoriteRepository: FavoriteRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        configRepository = ConfigRepository(this)
        apiFactory = AnythingLlmClientFactory()
        connectionProbe = ConnectionProbe(apiFactory)
        importLogRepository = ImportLogRepository(
            logsDir = File(filesDir, "logs"),
            retentionDays = 30,
        )
        collectRepository = CollectRepository(
            collectDir = File(filesDir, "collect"),
        )
        favoriteRepository = FavoriteRepository(
            favoriteDir = File(filesDir, "favorites"),
        )
        // v1.9 鏃ф暟鎹縼绉?Q10 瀹氱):鐗堟湰闂ㄦ帶,涓€娆℃€ф墽琛?澶辫触涓嶉樆濉炲惎鍔?涓嬫鍚姩閲嶈瘯
        appScope.launch {
            runCatching {
                if (!configRepository.isFavoritesMigrated()) {
                    val result = FavoriteMigration.migrate(collectRepository, favoriteRepository)
                    configRepository.markFavoritesMigrated()
                    android.util.Log.i(
                        "MarkToMigrate",
                        "migrated=${result.migratedEntries} created=${result.createdFolders} fallback=${result.fallbackToAccumulate}",
                    )
                }
            }.onFailure { e ->
                android.util.Log.w("MarkToMigrate", "migration failed, will retry next launch: ${e.message}")
            }
        }
        // v1.9 WiFi 鑷姩鍚屾(搂4.9):寮€鍏冲紑鍚笖褰撳墠杩炴帴 WiFi 鏃?鍚姩鍚庢寜 FTP鈫扐nythingLLM 椤哄簭鍚庡彴鍚屾
        appScope.launch {
            runCatching {
                val cfg = configRepository.snapshot()
                if (cfg.wifiAutoSync && isOnWifi()) {
                    runAutoSync(cfg)
                }
            }.onFailure { e ->
                android.util.Log.w("MarkToAutoSync", "auto sync failed: ${e.message}")
            }
        }
    }

    /** 褰撳墠鏄惁杩炴帴 WiFi(闇€ ACCESS_NETWORK_STATE) */
    private fun isOnWifi(): Boolean {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** 椤哄簭鍚屾:FTP 閫氶亾 鈫?AnythingLLM 閫氶亾(B 榛樿);澶辫触涓嶄腑鏂彟涓€閫氶亾 */
    private suspend fun runAutoSync(cfg: com.anythingllm.importer.data.config.AppConfig) {
        val entries = collectRepository.all().filter { it.isGrayCard && !it.isTrashed }
        if (entries.isEmpty()) return
        // FTP 閫氶亾(鏀惰棌澶圭洰褰曟爲 + .url 閾炬帴)
        if (cfg.ftp.isConfigured) {
            runCatching {
                val engine = com.anythingllm.importer.domain.sync.CollectFtpSyncEngine(
                    cfg.ftp, collectRepository, favoriteRepository,
                )
                engine.launch(appScope, entries)
                while (engine.state.value.running) kotlinx.coroutines.delay(500)
            }
        }
        // AnythingLLM 閫氶亾(ensure 鍚屽悕鏂囦欢澶?宸ヤ綔鍖?+ 涓婁紶/閾炬帴宓屽叆)
        if (cfg.baseUrl.isNotBlank() && cfg.apiKey.isNotBlank()) {
            runCatching {
                val api = apiFactory.create(
                    baseUrl = cfg.baseUrl,
                    apiKey = cfg.apiKey,
                    connectTimeoutSec = cfg.probeTimeoutSec.toLong(),
                    readTimeoutSec = cfg.uploadTimeoutSec.toLong(),
                    writeTimeoutSec = cfg.uploadTimeoutSec.toLong(),
                )
                val engine = com.anythingllm.importer.domain.sync.SyncEngine(
                    api = api,
                    config = cfg,
                    bodyProvider = com.anythingllm.importer.data.import.UriFileBodyProvider(contentResolver),
                    collectRepository = collectRepository,
                    favoriteRepository = favoriteRepository,
                )
                engine.launch(appScope, entries)
                while (engine.state.value.running) kotlinx.coroutines.delay(500)
            }
        }
    }
}

