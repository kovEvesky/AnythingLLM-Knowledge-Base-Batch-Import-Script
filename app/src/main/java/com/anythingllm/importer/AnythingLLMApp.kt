package com.anythingllm.importer

import android.app.Application
import com.anythingllm.importer.data.api.AnythingLlmClientFactory
import com.anythingllm.importer.data.collect.CollectRepository
import com.anythingllm.importer.data.collect.SnapshotRepository
import com.anythingllm.importer.data.config.ConfigRepository
import com.anythingllm.importer.data.log.ImportLogRepository
import com.anythingllm.importer.domain.probe.ConnectionProbe
import java.io.File

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
    }
}
