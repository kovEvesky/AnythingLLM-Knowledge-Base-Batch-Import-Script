package com.anythingllm.importer

import android.app.Application
import com.anythingllm.importer.data.api.AnythingLlmClientFactory
import com.anythingllm.importer.data.collect.CollectRepository
import com.anythingllm.importer.data.collect.SnapshotRepository
import com.anythingllm.importer.data.config.ConfigRepository
import com.anythingllm.importer.data.favorite.FavoriteMigration
import com.anythingllm.importer.data.favorite.FavoriteRepository
import com.anythingllm.importer.data.library.LibraryRepository
import com.anythingllm.importer.data.log.ImportLogRepository
import com.anythingllm.importer.domain.probe.ConnectionProbe
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口:持有全局单例(轻量服务定位器)。
 */
class AnythingLLMApp : Application() {

    lateinit var configRepository: ConfigRepository
        private set

    lateinit var apiFactory: AnythingLlmClientFactory
        private set

    lateinit var connectionProbe: ConnectionProbe
        private set

    /** 导入 JSONL 日志仓储(FR-13):logs 目录位于应用私有 filesDir,免存储权限 */
    lateinit var importLogRepository: ImportLogRepository
        private set

    /** v1.2 收集箱仓储(FR-18):collect 目录位于应用私有 filesDir */
    lateinit var collectRepository: CollectRepository
        private set

    /** v1.2 知识库结构快照(FR-20):snapshot.json 位于应用私有 filesDir */
    lateinit var snapshotRepository: SnapshotRepository
        private set

    /** v1.3 资料库仓储(FR-30/31):library 目录位于应用私有 filesDir(文件夹树 + 条目 + 文件副本) */
    lateinit var libraryRepository: LibraryRepository
        private set

    /** v1.9 收藏夹仓储(唯一分类体系):favorites 目录位于应用私有 filesDir */
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
        snapshotRepository = SnapshotRepository(
            file = File(filesDir, "snapshot.json"),
        )
        libraryRepository = LibraryRepository(
            libraryDir = File(filesDir, "library"),
        )
        favoriteRepository = FavoriteRepository(
            favoriteDir = File(filesDir, "favorites"),
        )
        // v1.9 旧数据迁移(Q10 定稿):版本门控,一次性执行;失败不阻塞启动,下次启动重试
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
    }
}
