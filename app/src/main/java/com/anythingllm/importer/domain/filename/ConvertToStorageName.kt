package com.anythingllm.importer.domain.filename

import com.anythingllm.importer.data.config.FilenamePolicy
import java.util.UUID

/**
 * 文件名转换算法(开发文档 §5.4),与 PC 版逐字符一致。
 *
 * 输入: name, policy(unicode 默认 / strip / keep)
 * 1. 若 policy=keep → 原样返回
 * 2. 拆分 ext(扩展名) 与 base(主名)
 * 3. 对 base 逐字符:
 *    - 0x20 ≤ code ≤ 0x7E(可打印 ASCII)→ 保留
 *    - 其他(中文等)→ policy=unicode 时追加 "u{code:x4}"(小写十六进制)
 *                       policy=strip 时丢弃
 * 4. 若结果为空 → "file-" + GUID 前 8 位
 * 5. 移除字符 `"` 与 `\`(替换为 -)
 * 6. 返回 结果 + ext
 *
 * 已用服务端实测存储名对照验证:
 *   深海世界 → u6df1u6d77u4e16u754c   契约测试 → u5951u7ea6u6d4bu8bd5
 */
object ConvertToStorageName {

    fun convert(
        name: String,
        policy: FilenamePolicy,
        guidProvider: () -> String = { UUID.randomUUID().toString() },
    ): String {
        if (policy == FilenamePolicy.KEEP) return name

        // 2. 拆分扩展名:最后一个 '.' 之后为扩展名(含点),之前为主名
        val dotIndex = name.lastIndexOf('.')
        val base = if (dotIndex > 0) name.substring(0, dotIndex) else name
        val ext = if (dotIndex > 0) name.substring(dotIndex) else ""

        // 3. 逐字符转换
        val sb = StringBuilder(base.length)
        for (ch in base) {
            val code = ch.code
            if (code in 0x20..0x7E) {
                sb.append(ch)
            } else {
                when (policy) {
                    FilenamePolicy.UNICODE ->
                        sb.append('u').append(code.toString(16).padStart(4, '0'))
                    FilenamePolicy.STRIP -> Unit // 丢弃
                    FilenamePolicy.KEEP -> Unit // 不可达
                }
            }
        }

        // 4. 空结果回退
        var result = sb.toString()
        if (result.isEmpty()) {
            result = "file-" + guidProvider().replace("-", "").take(8)
        }

        // 5. 移除引号与反斜杠
        result = result.replace('"', '-').replace('\\', '-')

        // 6. 拼回扩展名
        return result + ext
    }
}
