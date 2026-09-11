package com.anythingllm.importer.data.log

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 导入日志行(FR-13,阶段 4)。
 *
 * 结构与 PC 版桌面端 JSONL 日志同构:每行一个独立 JSON 对象,含时间/动作/文件/目标/结果/错误,
 * 追加写 `logs/import-YYYYMMDD.jsonl`(按本地日期分文件,与 PC 版 import-YYYYMMDD.jsonl 命名一致)。
 * 日志字段不包含 API Key / baseUrl 等敏感配置(安全要求:日志不记录 Key)。
 */
@Serializable
data class ImportLogEntry(
    val time: String,
    val level: String = "info",
    val source: String = "import",
    /** 动作:add(导入)/retry(重试后再次导入)/skip(重复跳过) */
    val action: String,
    /** 原始文件名(中文原名) */
    val file: String,
    val folder: String,
    val workspace: String,
    val storageName: String,
    val sizeBytes: Long = 0,
    /** 结果:success / failed / skipped */
    val result: String,
    val error: String? = null,
    /** 本次终态前上传重试次数 */
    val retries: Int = 0,
)

/**
 * 导入日志仓储(FR-13):
 * - 追加写当日文件;按本地日期跨天分文件;
 * - 倒序读取(新→旧),损坏行跳过不中断;
 * - 删除单个/全部;按文件名日期清理超过保留天数的旧文件。
 *
 * 纯 java.io.File 实现,不依赖 Android Context(JVM 单测可直接用临时目录)。
 */
class ImportLogRepository(
    private val logsDir: File,
    private val retentionDays: Int = 30,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val dateFormat = DateTimeFormatter.BASIC_ISO_DATE
    private val fileNamePattern = Regex("^import-(\\d{8})\\.jsonl$")

    /** 追加一行到当日日志文件(自动建目录) */
    fun append(entry: ImportLogEntry) {
        logsDir.mkdirs()
        val file = dailyFile()
        file.appendText(json.encodeToString(ImportLogEntry.serializer(), entry) + "\n", Charsets.UTF_8)
    }

    /** 当日文件名:import-YYYYMMDD.jsonl */
    fun dailyFileName(): String = "import-" + LocalDate.now().format(dateFormat) + ".jsonl"

    /** 全部日志文件信息,新→旧倒序(按文件名日期) */
    fun listFiles(): List<LogFileInfo> = logsDir.listFiles()
        .orEmpty()
        .filter { fileNamePattern.matches(it.name) }
        .map { f ->
            val m = fileNamePattern.find(f.name)!!
            LogFileInfo(
                name = f.name,
                date = m.groupValues[1],
                sizeBytes = f.length(),
                lineCount = f.useLines { l -> l.count { it.isNotBlank() } },
            )
        }
        .sortedByDescending { it.date }

    /** 读取指定文件全部行,解析为条目,新→旧倒序;损坏行跳过 */
    fun readEntries(fileName: String): List<ImportLogEntry> {
        val f = File(logsDir, fileName)
        if (!f.exists()) return emptyList()
        return f.useLines { lines ->
            lines.filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching { json.decodeFromString(ImportLogEntry.serializer(), line) }.getOrNull()
            }.toList()
        }.reversed()
    }

    /** 删除单个日志文件 */
    fun delete(fileName: String): Boolean = File(logsDir, fileName).delete()

    /** 删除全部日志文件 */
    fun deleteAll(): Int {
        val deleted = listFiles().map { it.name }.count { delete(it) }
        return deleted
    }

    /** 清理早于 retentionDays 天的文件;返回删除数量 */
    fun cleanupOld(): Int {
        val cutoff = LocalDate.now().minusDays(retentionDays.toLong())
        var removed = 0
        listFiles().forEach { info ->
            val d = runCatching { LocalDate.parse(info.date, dateFormat) }.getOrNull() ?: return@forEach
            if (d.isBefore(cutoff) && delete(info.name)) removed++
        }
        return removed
    }

    /** 导出单个文件原始内容 */
    fun exportContent(fileName: String): String {
        val f = File(logsDir, fileName)
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    /** 导出全部文件内容(按文件名拼接,保持时间顺序) */
    fun exportAllContent(): String = listFiles().reversed()
        .joinToString(separator = "", postfix = "") { exportContent(it.name) }

    private fun dailyFile(): File = File(logsDir, dailyFileName())

    data class LogFileInfo(
        val name: String,
        val date: String,
        val sizeBytes: Long,
        val lineCount: Int,
    )
}
