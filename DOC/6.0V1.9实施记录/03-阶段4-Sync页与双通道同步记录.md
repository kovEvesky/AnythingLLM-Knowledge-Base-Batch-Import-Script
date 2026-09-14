# 阶段 4 — Sync 页与双通道同步引擎(实施记录)

日期:2026-09-14 | 提交:`feat(v1.9) 阶段4 Sync页与双通道同步`(待提交)
前置:阶段 3 提交 `6b13991`

## 1. 目标(来自 v1.9 最终设计方案 §Sync)
- Sync 双子 Tab:FTP→PC / AnythingLLM,收藏夹驱动。
- 同步只上传「待同步(MARKED/FAILED 可重试)」条目;EXECUTED 不回传;TRASHED/回收站条目不入队。
- 服务器侧按收藏夹 ensure 同名文件夹+工作区(实现 A2「同步时自动创建」与 A4「EXECUTED 回写 serverLocation」)。
- WiFi 自动同步开关(ConfigRepository.wifiAutoSync,默认关)。

## 2. 落地内容
| 组件 | 说明 |
|---|---|
| `ui/home/SyncScreen.kt` | 双子 Tab + 连接状态卡 + 收藏夹状态列表(回收站🔒 灰显不可选)+「立即同步」+ WiFi 自动同步开关 |
| `ui/home/SyncViewModel.kt` | 收藏夹→待同步条目映射、双通道执行编排、进度/结果状态 |
| `domain/ftp/CollectFtpSyncEngine.kt` | FTP 通道按收藏夹名建目录上传(副本+JSONL),与服务器侧文件夹同名 |
| `domain/ftp/CollectFtpNaming.kt` | FTP 侧命名规则,双通道路径一致 |
| `domain/sync/SyncEngine` 改造 | AnythingLLM 通道 ensure 收藏夹同名文件夹+工作区;EXECUTED 回写 serverLocation |
| `wifiAutoSync` | ConnectivityManager 监听,连接 WiFi 且已配置通道时自动触发(MARKED 仅) |
| 死代码清理 | 删除 snapshot、library(资料库 Tab)、旧 FtpSyncEngine、MarkPlan、v1.3 资料库条目/目录树等,引用零残留 |

## 3. 测试
- `testDebugUnitTest` 全绿 **154 项**(阶段 3 后按需移除已删组件测试,新增 FTP 命名/映射/过滤用例)。
- 单测口径:阶段 2/3 历史用例随组件删除同步清理,无死引用。

## 4. 模拟器冒烟(2026-09-14,AVD anythingllm_api36,截图 v19_stage4_*.png)
1. 四 Tab 切换正常(Mark/To/Sync/Settings)。
2. Sync 双子 Tab(FTP→PC / AnythingLLM)切换正常。
3. 收藏夹状态列表:工作/学习/积累 + 回收站🔒;数量显示正确。
4. To 页:搜索/新建/重命名/排序提示文案;四夹+回收站(回收站 1 条)。
5. Settings:embedded 模式(无返回键)+ 服务器/API Key/测试连接 + 默认操作卡片(默认收藏夹/静默接收/接收震动/WiFi 自动同步)+ FTP 扫码。
6. 默认收藏夹下拉:点击展开 工作/学习/积累 选项(默认「未设置(按气泡顺序)」)。
7. **即时导入向导全链路(A2 核心验证)**:+菜单 → 即时导入 → 选择文件(mt_test.md,48.1KB)→ 预校验(通过 1 个)→ 导入目标页:
   - Tab 统一/逐项;说明「收藏夹是唯一目标:同步时自动在服务器创建同名文件夹与工作区」;
   - 目标收藏夹下拉 **预填「工作」**(默认未设置时按气泡顺序取第一个,与 A1/A2 一致);
   - 「开始导入 1 个文件」→ 导入进度页(API Key 无效,失败 1)→ 重试此文件/重试失败项(1)/完成 按钮齐全。
8. Mark 页:白卡/灰卡分组正常;导入失败不落本地条目(与 v1.3 语义一致,可重试)。

## 5. 发现并修复
- `settings_ftp_hint` 旧文案「资料库」→「收藏夹」(FTP 卡副标题残留 v1.3 概念,已改并重装验证)。
- 其余旧资源(`library_*`、`collect_archive_*`、`knowledge_*`、`home_quick_import` 等)经 grep 无代码引用,属死资源,
  由 build shrinkResources 处理;本阶段未逐一删除以控风险(阶段 5 可做最终清理)。

## 6. 已知取舍
- 导入向导失败不保留本地条目(保持 v1.3 直接上传语义);用户可「重试失败项」或重新走 Mark 收集箱路径。
- 同步顺序:先 FTP 后 AnythingLLM;各收藏夹内条目 FIFO。
- A7(SAF 树授权)未实现,Android 10+ 分区存储下依赖每次 SAF 授权,后续版本跟进。

## 7. 下一步(阶段 5)
- 阶段 5 回归:测试全绿已达成;端到端(服务器/FTP 实连)、交付 v1.9(Release APK)、真机手势复核(adb swipe 切 Tab 时序竞态需真机确认)。
