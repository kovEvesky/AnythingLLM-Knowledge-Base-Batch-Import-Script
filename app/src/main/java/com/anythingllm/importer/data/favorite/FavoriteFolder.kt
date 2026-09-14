package com.anythingllm.importer.data.favorite

import kotlinx.serialization.Serializable

/**
 * 收藏夹 12 色调色板(V1.9 最终设计 §4.7):
 * 青蓝/蓝/紫/粉/红/橙/黄/绿/青/棕/灰/黑,选中色块带对勾。
 */
object FolderPalette {
    val COLORS: List<Long> = listOf(
        0xFF14B8A6, // 青蓝(品牌色)
        0xFF3B82F6, // 蓝
        0xFF8B5CF6, // 紫
        0xFFEC4899, // 粉
        0xFFEF4444, // 红
        0xFFF59E0B, // 橙
        0xFFEAB308, // 黄
        0xFF22C55E, // 绿
        0xFF06B6D4, // 青
        0xFF8B5E3C, // 棕
        0xFF6B7280, // 灰
        0xFF1F2937, // 黑
    )

    /** 按索引取色(循环安全);负索引也归一化 */
    fun colorAt(index: Int): Long = COLORS[((index % COLORS.size) + COLORS.size) % COLORS.size]
}

/**
 * 收藏夹(V1.9 唯一分类体系,Q3 定稿):
 * - 每个收藏夹 = 本地缓存文件夹 `MarkTo/{name}/` + 服务器同名文件夹 + 同名工作区;
 * - 预置:工作 / 学习 / 积累(可删可改名,删除走迁移选择)+ 回收站(builtin 不可删不可改名);
 * - 长按拖动排序 → sortOrder 持久化(影响气泡顺序与同步遍历顺序)。
 */
@Serializable
data class FavoriteFolder(
    val id: String,
    val name: String,
    /** ARGB 色值(12 色调色板) */
    val color: Long,
    /** true=内置(回收站强制 true;工作/学习/积累 预置但 builtin=false,可删可改名) */
    val builtin: Boolean = false,
    /** 拖动排序结果;回收站恒为最大值,固定排最后 */
    val sortOrder: Int = 0,
    val createdAt: String,
    /** v1.9 回收站专用标记:true 时不参与同步、不可删/改名、Mark 气泡不出现 */
    val isTrash: Boolean = false,
    /** v1.9 AnythingLLM 同名工作区 slug(ensure 后回填;收藏夹改名不联动服务器) */
    val serverWorkspaceSlug: String? = null,
) {
    /** 是否用户可归入的收藏夹(排除回收站) */
    val isUserFolder: Boolean get() = !isTrash
}
