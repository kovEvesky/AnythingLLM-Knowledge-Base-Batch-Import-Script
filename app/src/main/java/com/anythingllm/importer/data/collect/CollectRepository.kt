package com.anythingllm.importer.data.collect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Serializable
private data class EntriesStore(val entries: List<CollectEntry> = emptyList())

/**
 * 收集箱仓储(v1.2 FR-18):
 * - 元数据统一存 `collect/entries.json`(整体序列化,量级小,简单可靠);
 * - 文件副本存 `collect/files/{yyyyMMdd}/{id}_{name}`(由接收方复制,本类只管理元数据与清理);
 * - 纯 java.io.File 实现,不依赖 Android Context(JVM 单测可直接用临时目录)。
 */
class CollectRepository(
    private val collectDir: File,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val entriesFile: File get() = File(collectDir, "entries.json")
    private val basicDate = DateTimeFormatter.BASIC_ISO_DATE
    private val isoDate = DateTimeFormatter.ISO_LOCAL_DATE

    init {
        collectDir.mkdirs()
    }

    // ===== 读 =====

    @Synchronized
    fun all(): List<CollectEntry> {
        if (!entriesFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(EntriesStore.serializer(), entriesFile.readText(Charsets.UTF_8)).entries
        }.getOrDefault(emptyList())
    }

    /** 待整理条目(未标记) */
    fun pending(): List<CollectEntry> = all().filter { it.status == EntryStatus.PENDING }

    /** 已标记待执行条目(含执行中与执行失败可重试;R15 修复:FAILED 保留在此区供重选/重试) */
    fun marked(): List<CollectEntry> = all().filter { it.status == EntryStatus.MARKED || it.status == EntryStatus.EXECUTING || it.status == EntryStatus.FAILED }

    /** 按收集日分组(新→旧),组内保持加入顺序 */
    fun groupByDay(entries: List<CollectEntry>): List<Pair<String, List<CollectEntry>>> =
        entries.groupBy { dayLabel(it.collectedAt) }
            .toSortedMap(compareByDescending { it })
            .map { it.key to it.value }

    /** 某天的文件副本目录(接收方复制用) */
    fun dayDir(date: LocalDate): File = File(File(collectDir, "files"), date.format(basicDate))

    // ===== 写 =====

    @Synchronized
    fun add(entry: CollectEntry) {
        write(all() + entry)
    }

    @Synchronized
    fun update(id: String, transform: (CollectEntry) -> CollectEntry): CollectEntry? {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return null
        list[idx] = transform(list[idx])
        write(list)
        return list[idx]
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val list = all()
        val target = list.firstOrNull { it.id == id } ?: return false
        write(list.filterNot { it.id == id })
        deleteLocalFile(target)
        return true
    }

    @Synchronized
    fun removeAll(ids: List<String>) {
        if (ids.isEmpty()) return
        val list = all()
        val targets = list.filter { it.id in ids }
        write(list.filterNot { it.id in ids })
        targets.forEach(::deleteLocalFile)
    }

    @Synchronized
    fun clear() {
        write(emptyList())
        File(collectDir, "files").listFiles()?.forEach { f -> runCatching { f.deleteRecursively() } }
    }

    /** 执行成功条目的文件原件清理(FR-26):删除文件,保留条目记录 */
    @Synchronized
    fun cleanupExecutedFiles() {
        all().filter { it.status == EntryStatus.EXECUTED }.forEach { deleteLocalFile(it) }
    }

    // ===== 内部 =====

    private fun deleteLocalFile(entry: CollectEntry) {
        entry.localPath?.let { runCatching { File(it).delete() } }
    }

    private fun write(list: List<CollectEntry>) {
        collectDir.mkdirs()
        entriesFile.writeText(
            json.encodeToString(EntriesStore.serializer(), EntriesStore(list)),
            Charsets.UTF_8,
        )
    }

    private fun dayLabel(iso: String): String = runCatching {
        OffsetDateTime.parse(iso).toLocalDate().format(isoDate)
    }.getOrDefault(iso.take(10))
}
