# V1.9 阶段 3 实施记录:To 收藏夹页 / Settings 重组 / 目标改造 / 品牌更名

> 日期:2026-09-14 | 状态:已完成并提交
> 关联:主方案 `MarkTo-V1.9-最终设计方案.md` §4.6–4.10 / Q1–Q11 / A1–A8 决策

## 1. 本阶段范围

| 子项 | 决策依据 | 说明 |
|---|---|---|
| To 收藏夹页 | 主方案 §4.6–4.10 | 收藏夹列表/详情/回收站,拖动排序,左滑删除右滑重命名 |
| Settings 重组 | 主方案 §4.11 | embedded 化;「默认操作」卡片替代 v1.7「默认入库」 |
| 即时导入目标改造 | A2 定稿 | 目标从「服务器文件夹+工作区」统一改为「收藏夹」 |
| 旧页面清理 | 交互重构方案 Q | 删除 KnowledgeScreen/LibraryScreen/CollectScreen/HomeViewModel |
| 品牌更名 | B 默认 | app_name=Mark To,versionName=1.9.0,versionCode=7 |

## 2. 关键实现决策(试错与取舍,留档备查)

### 2.1 To 页交互
- 左滑=删除进回收站、右滑=重命名(与 Mark 页手势语义一致:左右各有明确动作)。
- 拖动排序用 `detectDragGesturesAfterLongPress`(长按后拖动),列表项 `animateItemPlacement()` 回弹。
- 删除迁移对话框:单选迁移目标夹或「全部移入回收站」,**未选时确认按钮置灰**(防误删)。
- 回收站清空 = 文件副本+条目一并删除,AlertDialog 二次确认,不可恢复(A5);链接条目仅移除元数据。

### 2.2 Settings
- `SettingsScreen` 增加 `embedded` 参数:嵌入 Tab 时不显示返回键;HomeScreen Tab3 隐藏外层 TopAppBar 避免双标题。
- 「默认入库」→「默认操作」:默认收藏夹下拉(12 色板色点+名称)、静默接收、接收震动、WiFi 自动同步。
- `SettingsViewModel` 注入 `favoriteRepository`;保存回写 `defaultFolderId`/`wifiAutoSync`。
- 默认收藏夹语义(A1):气泡排序置顶 + 即时导入预填 + 批量标记目标,非右滑直入条件。

### 2.3 即时导入向导(A2 改造)
- `ImportSessionViewModel.UiState`:`folders/workspaces` → `favoriteFolders`;`unifiedFolder/unifiedWorkspace` → `unifiedFolderId`;
  `perItem` 简化为 `uriString -> folderId`。
- `init()` 改为**只读本地收藏夹**,不再请求服务器(离线可用)。
- 目标映射:服务器文件夹名 = 收藏夹名;工作区 = `folder.serverWorkspaceSlug` 兜底 `cfg.defaultWorkspace`。
  - ⚠ **遗留**:阶段 3 尚无 ensure 能力,若收藏夹未回填 slug 且未设 defaultWorkspace,导入会因工作区为空报错;
    阶段 4 Sync ensure(自动建夹+同名工作区)落地后此映射闭环。已记入 CHANGELOG Notes。
- 新建收藏夹对话框:本地创建(FavoriteRepository.createFolder),复用 To 页语义。

### 2.4 A6 取舍(重要,需用户知晓)
- 收藏夹改名**不联动服务器**;本地缓存目录沿用旧名。
- 即:改名后同步/导入的服务器文件夹名仍是**旧名**(阶段 4 ensure 以服务器为准做对齐时再处理)。

### 2.5 品牌
- `app_name=Mark To`;`versionName=1.9.0`;`versionCode=7`;首启引导三步文案改为 Mark/To/Sync 体系。

## 3. 编码治理(本阶段踩坑)
- `build.gradle.kts` 经 PowerShell `Set-Content -Encoding UTF8` 写入后带 **UTF-8 BOM**(EF BB BF),
  已用 `WriteAllText(UTF8Encoding($false))` 修复并复验头三字节为 `imp`。
- strings.xml 全程用 Edit 工具修改(无 BOM 引入)。

## 4. 验证
- `compileDebugKotlin` BUILD SUCCESSFUL。
- `testDebugUnitTest` 180 项全绿(阶段 3 未新增测试类,既有覆盖兜底)。
- 模拟器冒烟未覆盖本阶段 UI(To 页手势/拖动、Settings 下拉、导入向导收藏夹目标),
  阶段 5 回归需补:To 页增删改排 + 回收站清空二次确认 + 导入向导离线选夹 + embedded Settings 双标题检查。

## 5. 提交
- 提交信息见 git log(阶段 3 提交,UTF-8 无 BOM 方式写入)。
