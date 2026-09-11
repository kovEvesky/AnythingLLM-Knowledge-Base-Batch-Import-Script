package com.anythingllm.importer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.anythingllm.importer.data.import.LocalFileBodyProvider
import com.anythingllm.importer.domain.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * v1.2 FR-24 前台同步服务:
 * - 防杀:同步执行期间以前台服务运行(SyncEngine 跑在服务协程);
 * - 进度通知:显示成功/失败/跳过计数,支持取消;
 * - 完成后自动 stopSelf,回写仓储由 SyncEngine 负责。
 */
class SyncForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: SyncEngine? = null
    private var started = false

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                engine?.cancel()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildProgressNotification("正在连接服务器…", 0, 0, indeterminate = true))
                if (!started) {
                    started = true
                    scope.launch { runSync() }
                }
                return START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runSync() {
        val app = application as AnythingLLMApp
        val cfg = app.configRepository.snapshot()
        if (cfg.baseUrl.isBlank() || cfg.apiKey.isBlank()) {
            updateNotification("未配置服务器,请先在设置页配置", 0, 0, indeterminate = true)
            stopSelfCompat()
            return
        }
        try {
            val api = app.apiFactory.create(cfg.baseUrl, cfg.apiKey, readTimeoutSec = cfg.apiTimeoutSec.toLong())
            val marked = app.collectRepository.marked()
            if (marked.isEmpty()) {
                updateNotification("没有待执行的已标记条目", 0, 0, indeterminate = true)
                stopSelfCompat()
                return
            }
            val engine = SyncEngine(
                api = api,
                config = cfg,
                bodyProvider = LocalFileBodyProvider(),
                collectRepository = app.collectRepository,
            )
            this.engine = engine
            engine.launch(scope, marked)

            // 进度 → 通知(终态后停止收集)
            engine.state.takeWhile { !it.isTerminal }.collect { s ->
                if (s.items.isNotEmpty()) {
                    updateNotification(
                        "同步中 ${s.doneCount}/${s.totalCount}",
                        s.doneCount,
                        s.totalCount,
                        indeterminate = false,
                    )
                }
            }
            val final = engine.state.value
            updateNotification(
                "同步完成:成功 ${final.successCount} · 跳过 ${final.skippedCount} · 失败 ${final.failedCount}",
                final.doneCount,
                final.totalCount,
                indeterminate = false,
            )
        } catch (e: Exception) {
            updateNotification("同步失败:${e.message ?: "未知错误"}", 0, 0, indeterminate = true)
        } finally {
            engine = null
            stopSelfCompat()
        }
    }

    // ===== 通知 =====

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "同步进度",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "一键同步嵌入的进度与结果" }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildProgressNotification(
        text: String,
        done: Int,
        total: Int,
        indeterminate: Boolean,
    ): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, SyncForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("AnythingLLM 同步")
            .setContentText(text)
            .setOngoing(!indeterminate)
            .setOnlyAlertOnce(true)
            .addAction(0, "取消", cancelIntent)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (!indeterminate && total > 0) {
            builder.setProgress(total, done, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun updateNotification(text: String, done: Int, total: Int, indeterminate: Boolean) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildProgressNotification(text, done, total, indeterminate),
        )
    }

    private fun stopSelfCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "sync_progress"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_CANCEL = "com.anythingllm.importer.action.CANCEL_SYNC"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SyncForegroundService::class.java),
            )
        }
    }
}
