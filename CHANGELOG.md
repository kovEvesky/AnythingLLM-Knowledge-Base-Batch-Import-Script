# Changelog — AnythingLLM 批量导入工具(Android 版)

> 本文件记录项目从初始化到当前 1.0 版本的全部迭代过程。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),版本号遵循语义化版本(SemVer)。
> 编制日期:2026-09-11 | 阶段与验收明细见 `DOC/05-开发方案与阶段推进记录.md`,缺陷明细见 `DOC/06-Bug与问题记录.md`。

---

## 版本概览

| 版本 | 日期 | 阶段 | 摘要 |
|---|---|---|---|
| 1.9.0 | 2026-09-14 | v1.9 交互重构(进行中) | 重塑为 Mark To(mt) 个人收藏流:四 Tab(Mark/To/Sync/Settings)、收藏夹唯一分类体系、流式收件箱左右滑、双通道同步(阶段推进中) |
| 1.7.0 | 2026-09-14 | v1.7 收件箱范式 | 信息收集类 App(Cubox/flomo/抖音收藏)交互范式落地:默认入库位置一键入箱、条目左右滑手势分拣、单击底部抽屉连续整理、接收静默+短震动;三 Tab 切换 Crossfade;单测全绿 |
| 1.6.0 | 2026-09-14 | v1.6 Mac 服务器版 | 新增 macOS 端 FTP 服务器启动器 start-ftp-server.command(自动装依赖+二维码+权限操作提示:防火墙放行/本地网络/隔离解除),复用跨平台 ftp_server.py;README 增加 macOS 章节 |
| 1.5.1 | 2026-09-14 | v1.5.1 补丁 | PC FTP 脚本 IP 排序优化:192.168 网段优先(虚拟网卡 172.25.x 后置),二维码默认输出手机可连的物理局域网 IP(实测 192.168.8.71 优先) |
| 1.5.0 | 2026-09-14 | v1.5 分享标题化+扫码连接 | 分享链接异步抓取网页标题回填(真机验证百度"百度一下，你就知道"),PC ftp_server 终端输出 FTP 配置二维码,App FTP 设置扫码连接自动回填;单测 163 全绿,PC 端二维码实测+真机 UI 回归通过 |
| 1.4.0 | 2026-09-13 | v1.4 UI 优化 | 26 项 UI 诊断 + NEW-01~06 补充按 §6.1 全部落地:设计系统/深色窗口/文案资源化/信息架构/首启引导;单测 144 全绿,模拟器 uiautomator 回归通过 |
| 1.3.0 | 2026-09-11 | v1.3 开发 | 未安装 AnythingLLM 用户全程可用:资料库目录树 + FTP 同步到 PC;单测 144 全绿,模拟器端到端全链路通过(收集→归档→目录管理→FTP 同步→增量/变更重传) |
| 1.2.0-rc | 2026-09-11 | v1.2 开发 | 真机全面回归:链接/单选/多选分享、标记、同步、SAF 导入全链路通过;extractUris 修复生效(多选单 Uri 不再崩溃);服务器回归数据已清理 |
| 1.0.0 | 2026-09-10 | 阶段 5 | 正式交付:深色模式、Release 签名、回归全过,发布 1.0.0 |
| 0.4.0 | 2026-09-10 | 阶段 4 | 结果汇总与 JSONL 日志,单测 79 全绿 |
| 0.3.0 | 2026-09-10 | 阶段 3 | 批量导入主流程(统一/逐项),强化测试 A–G 全过 |
| 0.2.0 | 2026-09-10 | 阶段 2 | SAF 选文件与预校验 |
| 0.1.0 | 2026-09-10 | 阶段 0–1 | 环境搭建、配置与 API 层 |
| 0.0.1 | 2026-09-10 | 初始化 | 项目骨架与构建链路 |

---

## [1.9.0] — v1.9 交互重构:Mark To(进行中,2026-09-14)

> 设计依据:`DOC/v1.9最终设计方案/MarkTo-V1.9-最终设计方案.md`(定稿,Q1–Q11 全部答复)。
> 目标:把应用从「AnythingLLM 批量导入工具」重塑为「Mark To(mt)」——"分享即流式、滑一下即归类"的个人收藏流,
> 配「收藏夹 → FTP / AnythingLLM 双通道同步」。未决项 A1–A8 已全部按推荐定稿,期间无需再确认。
> 本区块随阶段推进追加;当前进度:**阶段 1(数据层)+ 阶段 2(Mark 页)+ 阶段 3(To 页/Settings/目标改造/品牌更名)+ 阶段 4(Sync 页与双通道同步)完成**。

### Added(阶段 1 · 数据层)
- **收藏夹模型**(`data/favorite/FavoriteFolder.kt`):`FavoriteFolder(id, name, color ARGB, builtin, sortOrder, createdAt, isTrash, serverWorkspaceSlug)`;
  `FolderPalette` 12 色调色板(青蓝/蓝/紫/粉/红/橙/黄/绿/青/棕/灰/黑)。
- **收藏夹仓储**(`data/favorite/FavoriteRepository.kt`):`favorites.json` 整体序列化(可 JVM 单测);
  种子四夹 **工作(蓝)/ 学习(橙)/ 积累(绿)/ 回收站(灰,builtin 不可删/改名/恒排最后)**;
  重名自动加序号;名称清洗防路径穿越(全非法字符视为空);拖动排序 `reorder()`;服务器工作区 slug 回填。
- **CollectEntry 扩展**(`data/collect/CollectEntry.kt`):`markFolder → markFolderId`(收藏夹 id,旧字段保留追溯)、
  `markedAt`(灰卡沉底排序)、`sourceApp`(来源 App);`EntryStatus + TRASHED`(左滑删除进回收站,Q4);
  `isGrayCard` 灰卡判定(A3 定稿:EXECUTED/FAILED 保留灰卡可见)。
- **旧数据迁移**(`data/favorite/FavoriteMigration.kt` + `AnythingLLMApp` 启动版本门控,Q10 定稿):旧 `markFolder`
  条目按文件夹名自动建同名收藏夹归入;同名复用;空名/回收站同名归入"积累";服务器端由用户手动清理。
- **配置扩展**(`AppConfig/ConfigRepository`):`defaultFolderId`(默认收藏夹)、`wifiAutoSync`(WiFi 自动同步开关)、
  `favorites_migrated_v19` 迁移标记。

### Changed
- `AnythingLLMApp` 注册 `favoriteRepository`(私有 filesDir/favorites),启动协程执行一次性迁移(失败下次启动重试,不阻塞)。

### Notes
- 修复三处实现细节(FavoriteRepository 单测暴露):全非法字符名清洗残留下划线未回退;空名重命名未拒绝;
  回收站 sortOrder 用 `hashCode()%100` 可能为负导致 Int 溢出 → 固定 `Int.MAX_VALUE-1`。

### Added(阶段 2 · Mark 流式收件箱)
- **四 Tab 骨架**(`HomeScreen.kt`):Mark(M 文字图标)/ To(T)/ Sync(循环箭头)/ Settings(齿轮);
  Tab0 接入 MarkScreen,Tab1–3 暂为占位(后续阶段填充)。
- **Mark 页**(`ui/home/MarkScreen.kt`,最终设计 §4.1–4.5):平铺列表卡片式(留白间隔、不叠加);
  白卡在前按 `collectedAt` 倒序,灰卡在后按 `markedAt` 倒序,顶部"今天/更早"轻量分组;
  卡片=类型图标(链接青绿底/文件蓝底)+ 标题 + 副行"类型·来源App·相对时间"+ 图片缩略图(Q9 采样解码 96px);
  灰卡灰色半透明 + 右上角"已归入[收藏夹]"标签。
- **手势状态机**(Q6/A1 定稿):白卡左滑=删除(TRASHED 进回收站+Snackbar);白卡右滑=两段式
  (仅 1 个用户夹直入并提示"已存入默认收藏夹",多夹弹收藏夹气泡,0 夹引导去 To 页);
  灰卡左滑/右滑均=撤销(双向同义,恢复 PENDING;终态 EXECUTED/FAILED 不可撤销,A4);
  单击白卡/灰卡=弹气泡(快速归类/改夹);气泡点空白取消→保持未标记。
- **收藏夹气泡**(`FolderBubbleSheet`):底部 ModalBottomSheet,横向胶囊(色点+名称,默认夹置顶,
  A1 定稿:默认夹仅作排序置顶非直入条件)+ 虚线"新建…"。
- **左上角"+"菜单**(Q7):批量多选(勾选→标记到收藏夹/移回收站)/ 即时导入 / 日志。
- **来源 App**(Q2 定稿):`ShareReceiver.sourceAppOf()` 读 `ClipDescription.label`,
  过滤 MIME(含'/')与超长标签,写入 `CollectEntry.sourceApp`,UI 兜底"未知来源"。
- **配置扩展**:`defaultFolderId` 跟随 DataStore 流入 UiState,参与气泡排序。

### Changed
- 旧 CollectScreen 手势语义(v1.7 左滑=入默认箱/右滑=上次夹)整体替换为 V1.9 语义,
  旧 CollectScreen 文件保留(阶段 3 移除知识库/资料库时清理)。

### Verified(阶段 2)
- `compileDebugKotlin` 通过(仅既有 deprecation 警告);`testDebugUnitTest` 全绿 180 项无回归。

### Added(阶段 3 · To 收藏夹页 / Settings 重组 / 目标改造 / 品牌更名)
- **To 页**(`ui/home/ToScreen.kt` + `ToViewModel.kt`,最终设计 §4.6–4.10):收藏夹列表(搜索 / 新建/重命名对话框含 12 色板 /
  左滑删除右滑重命名 / 长按拖动排序 `detectDragGesturesAfterLongPress`);
  删除迁移对话框(单选迁移目标夹或全部移入回收站,未选不可确认,Q5);
  收藏夹详情(条目移出到其他夹 / 彻底删除);回收站详情(单个恢复 / 彻底删除 / 清空二次确认,A5:文件副本+条目一并删除)。
- **Settings 重组**(`ui/settings/SettingsScreen.kt` 增加 `embedded` 参数,Tab 内不显示返回键):
  「默认入库」卡片 →「默认操作」卡片——默认收藏夹(下拉,色点+名称)/ 静默接收 / 接收震动 / **WiFi 下自动同步**开关;
  `SettingsViewModel` 注入 `favoriteRepository`,保存回写 `defaultFolderId` / `wifiAutoSync`。
- **HomeScreen 接入**:Tab1=ToScreen(Tab 自带 Scaffold),Tab3=embedded SettingsScreen(隐藏外层 TopAppBar 避免双标题)。
- **旧页面移除**:删除无引用的 `CollectScreen.kt / KnowledgeScreen.kt / LibraryScreen.kt / HomeViewModel.kt`。
- **即时导入向导目标改造(A2 定稿)**:`ImportSessionViewModel`/`TargetScreen` 目标由「服务器文件夹+工作区」统一改为「收藏夹」——
  加载本地收藏夹(离线可用),统一/逐项模式都只选收藏夹;执行时映射:服务器文件夹名=收藏夹名,
  工作区=已回填 `serverWorkspaceSlug` 兜底 defaultWorkspace(阶段 4 Sync ensure 完善);新建收藏夹走本地创建。
- **品牌更名(B 默认)**:`app_name = "Mark To"`,`versionName = 1.9.0`,`versionCode = 7`;首启引导文案更新为收藏夹体系
  (Mark 流式收件箱 → To 收藏夹 → Sync 双通道同步)。

### Notes(阶段 3)
- A6 取舍:收藏夹改名**不联动服务器**,本地缓存目录沿用旧名(阶段 4 同步前需用户知晓此语义)。
- 导入向导当前工作区映射为"回填 slug 兜底 defaultWorkspace",阶段 4 接入 Sync ensure 后自动建夹+同名工作区。
- `build.gradle.kts` 经 PowerShell `Set-Content` 曾写入 UTF-8 BOM,已用无 BOM 写入方式修复(编码治理)。

### Verified(阶段 3)
- `compileDebugKotlin` 通过;`testDebugUnitTest` 全绿 180 项无回归(阶段 3 未新增测试类)。

### Added(阶段 4 · Sync 页与双通道同步引擎)
- **SyncScreen**(`ui/home/SyncScreen.kt`):双子 Tab「FTP→PC / AnythingLLM」+ 连接状态卡 + 「立即同步」/「WiFi 自动同步」开关;
  收藏夹驱动状态列表:每夹一行(名称/数量/同步状态徽标),回收站行🔒不可选。
- **SyncViewModel**(`ui/home/SyncViewModel.kt`):收藏夹 → 待同步条目映射、FTP/AnythingLLM 双通道执行编排、进度/结果状态。
- **CollectFtpSyncEngine**(`domain/ftp/`):FTP 上传通道重写为收藏夹驱动——按收藏夹名在 FTP 建目录,条目副本+JSONL 元数据上传;
  与 CollectSyncEngine(AnythingLLM 通道)共用收藏夹映射,回收站条目不入同步队列。
- **CollectFtpNaming**:FTP 侧命名规则(收藏夹目录 / 条目存储名),与服务器侧 ensure 文件夹同名,双通道路径一致。
- **SyncEngine 收藏夹驱动改造**:AnythingLLM 通道按收藏夹 ensure 服务器文件夹+同名工作区(实现 A2/A4:同步时自动创建,不再依赖预建文件夹);
  EXECUTED 条目回写 serverLocation;MARKED/TRASHED 不上传。
- **WiFi 自动同步**(`wifiAutoSync` 配置 + ConnectivityManager 监听):连接 WiFi 且已配置通道时自动触发同步;仅同步 MARKED 条目。
- **死代码清理**:删除 `snapshot`、`library`(资料库 Tab)、旧 `FtpSyncEngine`、`MarkPlan`、v1.3 资料库条目与目录树等遗留组件/资源(引用零残留,见 DOC 6.0 记录)。
- **测试**:新增 FTP 命名/映射/同步过滤等用例,`testDebugUnitTest` 全绿 **154 项**(阶段 3 后按需移除已删组件测试)。

### Changed(阶段 4)
- `settings_ftp_hint` 旧文案「资料库」→「收藏夹」(模拟器冒烟发现,已修复)。

### Notes(阶段 4)
- FTP 与 AnythingLLM 通道同步顺序:先 FTP 后 AnythingLLM(各收藏夹内条目 FIFO)。
- A2 落地验证:即时导入向导目标页 =「导入目标」页,目标收藏夹下拉预填默认夹(未设置时按气泡顺序取第一个「工作」),
  说明文案「收藏夹是唯一目标:同步时自动在服务器创建同名文件夹与工作区」;导入失败不保留本地条目(与 v1.3 语义一致,可重试)。
- A7(SAF 树授权)仍未实现,Android 10+ 分区存储下依赖每次 SAF 选文件授权,后续版本跟进。

### Verified(阶段 4)
- `compileDebugKotlin` 通过;`testDebugUnitTest` 全绿 154 项;模拟器冒烟 8 场景全过:
  ①四 Tab 切换 ②Sync 双子 Tab ③收藏夹状态列表(回收站🔒) ④To 页四夹+回收站 ⑤Settings embedded(无返回键)+默认操作卡片
  ⑥默认收藏夹下拉弹出(工作/学习/积累) ⑦即时导入向导全链路(选文件→预校验→导入目标页收藏夹预填→开始导入→失败重试 UI) ⑧Mark 页正常。
  截图归档 `DOC/6.0V1.9实施记录/`(v19_stage4_*.png)。

---

## [1.7.0] — v1.7 收件箱范式(2026-09-14)

> 设计依据:`DOC/4,0界面美化/12-交互优化建议-收件箱范式.md`。
> 核心思路从"管理工具"转向"收件箱范式":分享进来零决策,左/右滑即整理,不再"先勾选再弹窗选文件夹"。

### Added
- **默认入库(零决策)**:设置页新增"默认入库"卡片——默认文件夹名、默认工作区、静默接收开关、接收震动开关。设置一次后,收集箱顶部出现"全部入默认箱(N)"主按钮,一键把所有待整理条目标记到默认位置。
- **单条手势分拣**(`CollectScreen`):条目**左滑=入默认箱**、**右滑=再用上次夹**、**单击=弹出底部抽屉选文件夹**。滑动露出对应色底操作区;未设默认时 Snackbar 引导去设置。
- **底部连续分拣抽屉**(`MarkEntrySheet`):单击条目从底部弹出 ModalBottomSheet,横向文件夹胶囊(上次/常用置顶)+ 工作区胶囊,"入箱并看下一条"自动跳到下一条待整理,替代原来的居中 AlertDialog。
- **上次夹记忆**(`ConfigRepository.rememberLastMark`):每次标记后独立写入最近使用的文件夹+工作区,右滑直接复用,不覆盖设置表单。
- **静默接收**(`ShareReceiver`):按配置接收分享后默认**不拉起主界面**,改为 Toast"已收到 N 项,待整理"+短震动;关闭静默开关仍可像旧版直接打开收集箱。新增 `VIBRATE` 权限。

### Changed
- 收集箱空态文案改为引导手势分拣;条目重排加 `animateItemPlacement()` 回弹。
- 主页三个 Tab 内容切换由 `when(tab)` 硬切改为 `Crossfade`。
- 批量勾选的"标记/归档/删除"作为进阶能力保留,路径不变。

### Notes
- 三 Tab(收集箱/知识库/资料库)收敛为双 Tab 涉及导航与服务器/ FTP 同步接线重构,列为后续版本规划,本版保持结构不变,仅在交互层做减法。
- `SwipeToDismissBox` 的 `confirmValueChange` 返回 `false` 不触发 dismiss,条目靠状态变更后从 pending 列表自然消失。

### Verified
- `compileDebugKotlin` 通过;`testDebugUnitTest` 全绿(基线 163 项无回归)。
- 版本号 versionCode=6 / versionName=1.7.0。

---

## [1.6.0] — v1.6 Mac 服务器版(2026-09-14)

新增 macOS 端 FTP 服务器,复用跨平台 `ftp_server.py`(Windows/macOS 同一份核心脚本,含二维码输出与 IP 排序)。

### Added
- **`tools/ftp-server/start-ftp-server.command`**(macOS 双击启动器):
  - 自动检测 python3(缺失提示 brew / 官网安装);
  - 自动安装 `pyftpdlib qrcode`(`pip3 install --user`,失败自动切清华镜像,再失败给出手动命令);
  - 启动前打印 **macOS 权限操作提示**:『python3 想要接受传入连接』→ 点允许(防火墙放行 2121);误点拒绝的修复路径(系统设置 → 网络 → 防火墙 → 选项 → python3 允许传入连接);Sequoia+ 本地网络权限提示;
  - `exec python3 ftp_server.py "$@"` 透传参数(端口/账号/密码/--qr-host)。
- **README §7 macOS 使用**:首次三步(解除隔离右键打开或 chmod +x → 防火墙允许 → 本地网络允许)、常见问题(手机连不上排查:防火墙选项/同网段/--qr-host 多网卡)、依赖安装失败手动命令。

### Changed
- `tools/ftp-server/README.md`:标题 v1.5 → v1.6,新增 §7 macOS 章节。

### Verified
- 脚本 LF 行尾 + `bash -n` 语法检查通过(WSL);核心 `ftp_server.py` 跨平台逻辑(二维码/依赖/IP 排序)已在 Windows 实测,Mac 端无平台特有代码分支。
- 未在真实 macOS 上执行(当前无 Mac 环境),启动器仅做语法/逻辑审查,首次在 Mac 运行如遇问题按脚本内提示处理。

---

## [1.5.1] — v1.5.1 补丁:PC FTP 默认 IP 排序(2026-09-14)

用户实测反馈:多网卡环境(本机 172.25.128.1 虚拟网卡 + 192.168.8.71 物理局域网)启动时默认展示/二维码用的是 172.25.128.1,手机(192.168.8.170 同网段)无法直连。

### Changed
- `tools/ftp-server/ftp_server.py` `lan_ipv4_addresses()`:排序改为**按手机可连性优先级**(192.168.x.x > 10.x.x.x > 172.16.0.0/12 > 其他,同级内字符串序),替代原纯字符串排序;启动横幅标注"按手机可连性排序,192.168 优先",二维码提示同步更新。
- 实测本机输出:先 `ftp://192.168.8.71:2121` 后 `ftp://172.25.128.1:2121`,二维码默认取 192.168.8.71;`--qr-host` 仍可显式覆盖。

---

## [1.5.0] — v1.5 分享标题化 + FTP 扫码连接(2026-09-14)

里程碑:真机(192.168.8.170 无线调试)回归定位需求一关键时序问题并修复;PC 端二维码输出隔离 venv 实测通过;App 扫码链路真机验证入口/权限/扫码页/返回正常(真实扫码解码因需摄像头物理对准 PC 屏幕,留待用户实测)。提交线:d5ca1ce(需求一)→ 0d8085a(需求二-PC)→ 8c110bd(需求二-App)→ f999a96(需求一时序修正)。单测 163 全绿(144 基线 + LinkTitleFetcherTest 12 + FtpQrConfigTest 7)。

### Added
- **需求一 链接标题抓取**:新增 `domain/link/LinkTitleFetcher.kt`(OkHttp 短超时 connect 4s/read 5s、浏览器 UA、读响应前缀 256KB、`<title>` 正则 + HTML 实体解码 + 空白压缩 + 120 字符截断);`ShareReceiver.addLink()` 先以域名占位入库 → 守护线程异步抓取 → 成功后回填标题,失败保持域名不阻塞分享。
- **需求一 收集箱时序修正**:分享后 `awaitTitle()`(join ≤ 6s,并行)等抓取完成再打开收集箱,修复"UI 先读域名标题且不刷新"问题;真机验证百度链接标题显示"百度一下,你就知道",知乎因反爬降级域名(预期行为)。
- **需求二-PC 二维码**:`tools/ftp-server/ftp_server.py` 新增 `print_ftp_qr()`(qrcode 终端 ASCII 二维码 + 可选 PNG 存 root 目录;pillow 缺失时仅终端二维码),启动输出 JSON payload `{"v":1,"t":"anythingllm-ftp","host","port","user","password","root"}` 二维码,`--qr-host` 指定二维码 IP;`start-ftp-server.bat` 检测 pyftpdlib+qrcode 一起自动 pip 安装(失败给清华镜像提示)。
- **需求二-App 扫码连接**:CameraX 1.4.1 + ZXing core 3.5.3;`ScanFtpQrActivity`(相机权限门控 → Preview+ImageAnalysis → MultiFormatReader 解码 YUV,800ms 节流 → RESULT_OK+EXTRA_RESULT 返回);`data/config/FtpQrConfig.kt`(kotlinx.serialization 校验 `t=="anythingllm-ftp"`/host 非空/port 1..65535/user 非空);设置页 FTP 卡"扫码连接"按钮,解析成功回填 主机/端口/远端根目录/用户名/密码 + Toast"已扫码填入,请点保存生效",失败 Toast"未识别到 FTP 配置二维码"。
- 测试:LinkTitleFetcherTest 12 条(解析 + MockWebServer 抓取/重定向/404/超大页/UA)、FtpQrConfigTest 7 条(合法/非法 payload/端口边界/类型校验)。

### Changed
- `AndroidManifest.xml`:新增 CAMERA 权限 + `uses-feature camera required=false` + 注册 ScanFtpQrActivity(portrait)。
- `app/build.gradle.kts`:versionCode=5/versionName=1.5.0。
- `strings.xml`:新增 settings_ftp_scan/scan_ok/scan_invalid/scan_perm 等文案资源。

### Fixed
- LinkTitleFetcher:RegexOption 组合用 `setOf(IGNORE_CASE, DOT_MATCHES_ALL)`(无 `or` 运算符)、replace lambda 显式 `MatchResult` 类型。
- ScanFtpQrActivity:PermissionGate 补 onBack 参数;`Color` 修正为 Compose `androidx.compose.ui.graphics.Color`(避免误引 android.graphics.Color)。
- start-ftp-server.bat:if 块内 echo 去括号(未配对 `(` 导致 `". was unexpected at this time."`)。

### Verified
- 单测 163 全绿(编译 :app:testDebugUnitTest --rerun-tasks BUILD SUCCESSFUL)。
- PC 端:隔离 venv 实测启动器自动安装 pyftpdlib+qrcode → 终端 ASCII 二维码完整输出 → FTP 服务 0.0.0.0:2121 启动。
- 真机(192.168.8.170:39913):分享百度链接入库标题回填"百度一下,你就知道"(uiautomator 证据);FTP 设置"扫码连接"入口 → 相机权限授予 → ScanFtpQrActivity 打开("对准 PC 终端中的二维码")→ 返回正常。
- 真机扫码解码→表单回填链路:单测覆盖解析校验;真实扫码需摄像头对准 PC 终端二维码,留待用户实测。

### 裁剪/候选
- 知乎等强反爬站点标题抓取失败降级域名(已按设计);后续可加 OpenGraph/多源降级。

---

## [1.4.0] — v1.4 UI 优化(2026-09-13)

里程碑:按《11-UI方案实况对比与补充建议.md》§6.1 执行顺序完成全部 26 项诊断修复 + NEW-01~06 补充(UI-24 Snackbar 统一 / UI-27 计数缓存评估后裁剪,纳入 v1.5 候选)。提交线:fe3f133(P0)→ cf9dc52(P1)→ 268d61b(P2)→ 3777b5a(P3)。单测 144 全绿;模拟器回归以 uiautomator 证据为准(截屏管道被模拟器残留应用污染,已移除无效果图)。

### Added
- **UI-19 首启引导**:AppConfig.guideSeen + ConfigRepository.GUIDE_SEEN/markGuideSeen()(独立写入),HomeScreen 三步引导 AlertDialog(收集/整理/导入),DataStore 持久化只弹一次。
- **UI-20 服务器连接状态卡**(知识库):已连接(快照时间)/未连接("可走资料库 FTP 通道离线同步")/错误三态图标卡片,替换原纯文字错误块与 v1.3 offline hint。
- **UI-21 同步按钮阶段化**(知识库 + 资料库):空闲=图标+文案,连接/同步中=进度圈+"同步中 x/y"。
- **UI-22 比对结果结构化**:DiffRow(新增=Add 图标/移除=Remove 图标)。
- **UI-23 页面过渡动画**:NavHost enter/exit/pop 过渡(淡入 + 1/20 屏宽滑入,≤220ms)。
- **UI-18 空态图标化**:收集箱/知识库标记区/资料库空态统一 48dp Outlined.Inbox。
- **NEW-02 资料库信息架构**:同步按钮阶段化 + SyncBadge 徽标(已同步 primaryContainer 底/未同步 surfaceVariant 底)替换纯文字、空态图标。
- **NEW-05 结构化清单**:知识库文件夹/工作区清单图标化(Folder/Workspaces),明细首行加图标与计数。
- UI-26 大字体适配:font_scale 1.3 模拟器实测布局不崩、文案不截断(ADB 验证)。

### Changed
- **P1 完整设计系统**(UI-07~10):Theme.kt 全量重写 — Light/Dark ColorScheme(品牌蓝 #3A5BA0 系 + 2E7D62 绿 tertiary)、AppTypography(标题加粗/中文行高)、AppShapes(8/12/16)、Spacing 体系。
- **UI-01 深色窗口层**:新增 values-night/themes.xml(深色 Theme.AnythingLLM),修复暗色下窗口层(状态栏/导航栏/背景)白闪。
- **UI-04 返回键统一**:Settings/Select/Logs/Target/Validation/Import 六页 TextButton("返回")→ IconButton+ArrowBack;Import 保留运行中退出确认。
- **UI-11 状态图标化**:知识库 StatusIcon(Schedule/Sync/CheckCircle/ErrorOutline)、资料库 FtpStatusIcon、ValidationScreen ✓/✗→CheckCircle/ErrorOutline,均带语义 contentDescription。
- **UI-12 类型徽标**:收集箱条目 TypeBadge(文件=InsertDriveFile/链接=Link,36dp 圆角容器色底)。
- **UI-13 文案资源化**:strings.xml 1→~190 条(补回 app_name),WSL Python 脚本批量替换 90 处字面量,带参插值手工转 stringResource;10 文件补 `import com.anythingllm.importer.R`。
- **UI-15 信息架构分区**:知识库页重组为"连接状态卡→同步入口→结构清单卡→已标记待执行"卡片分区。
- **UI-16 detailLine 截断**:收集箱 detailLine maxLines=2 + Ellipsis。
- **UI-25 无障碍**:HomeScreen 底部导航图标补 contentDescription,标题资源化(收集箱/知识库/资料库)。
- NEW-01 三个对话框(Mark/Archive/Move)列表 forEach→LazyColumn + heightIn 可滚动;NEW-03 编译零 warning(4 处 deprecation 清理)。

### Fixed
- **UI-02/03** 长文件夹/工作区列表对话框内容溢出不可见→可滚动 + 条目截断。
- **UI-05** 重复文件对话框按钮挤在一行 → RadioButton 单选(保留/跳过/中止)+ 整行可点 + 复选时禁用中止。
- NEW-04 编译期修复实证:`RoundedCornerShape(Shape)` 非法→clip(shapes.small);`state.loadError` 委托属性 smart cast 不可→toString();InsertDriveFile deprecated→AutoMirrored。
- **PC 启动器自动装依赖**:`tools/ftp-server/start-ftp-server.bat` 检测 pyftpdlib 缺失时自动 `pip install`,失败给镜像提示(清华源),不再要求手动装;`ftp_server.py`/README 版本号同步 v1.4,依赖提示指向启动器。实测两条路径(已有依赖直接启动 / venv 隔离缺依赖自动安装后启动)均通过。

---

## [1.3.0] — v1.3 资料库 + FTP 同步(2026-09-11)

里程碑:未安装/未配置 AnythingLLM 知识库的用户全程可用。设计文档《DOC/3.0需求回归/10-v1.3开发计划.md》;v1.2 服务器链路全部保留,新增独立第三通道(资料库 + FTP)。

### Added
- **手机端资料库(第三 Tab)**:本地目录树(`LibraryFolder` parentId 链表 + `LibraryEntry`,整体序列化 `library/library.json`,文件副本 `library/files/{id}_{名}`)。目录管理:新建文件夹(同级重名自动 "(2)" 序号)、重命名、删除(子项上移父级,不物理删文件)、进入/返回上级、条目移动到文件夹/删除、收集箱勾选「归入资料库」归档(移动语义:副本复制入库 + 收集箱条目删除,链接不建文件)。
- **PC 端 FTP 服务脚本** `tools/ftp-server/`:pyftpdlib 单文件 `ftp_server.py`(免管理员、跨平台,默认 root=脚本同级 `any-sync`、port 2121、user sync、password sync123,`--password-env` 支持环境变量取密码,启动打印局域网 IP)+ `start-ftp-server.bat`(纯 ASCII 防乱码启动器,自动检查 python/pyftpdlib)+ `README.md`(安装/防火墙/手机端配置说明)。
- **FTP 同步引擎**(commons-net FTPClient,被动模式,控制编码 UTF-8,二进制):目录树镜像到 PC,链接落盘 `.url`(`[InternetShortcut]\r\nURL=...`),文件名保留中文仅清洗非法字符(PC 目录可读可双击);增量判定 `syncedAt==null 或 syncedSize≠当前大小`;幂等覆盖;单条目 2 次指数退避重试;失败保留未同步可重试。
- **FTP 设置卡片**(设置页):主机/端口(2121)/远端根目录(Library)/用户名(sync)/密码,明文 DataStore 5 键;资料库页 FTP 未配置横幅引导;知识库 Tab 无服务器引导文案。
- 新增测试:LibraryRepositoryTest 12 + FtpNamingTest 8 + FtpSyncEngineIntegrationTest 2(真实 pyftpdlib 子进程,需本机 python)。

### Changed
- 底部导航 2 Tab → 3 Tab(收集箱/知识库/资料库);`CollectViewModel.refresh()` 同时刷新资料库状态。
- FTP 文件命名设计定稿:由"ASCII 化文件名"改为 **UTF-8 可读名**(仅清洗 `\ / : * ? " < > |`),两端控制编码均 UTF-8。

### Fixed
- PC 脚本日志钩子 `on_file_received/on_file_sent` 引用 `received_bytes/sent_bytes`(pyftpdlib DTPHandler 属性,控制通道 handler 无此属性)抛 AttributeError → 改为 `os.path.getsize(file)` 取落盘真实大小。
- 设置页保存 FTP 配置后资料库横幅仍显示"未配置":CollectViewModel init 增加 `configRepository.config.collect { ftp = cfg.ftp }` 实时跟随,保存即生效。
- **C-16** 归档/移动对话框文件夹平铺无层级:子文件夹显示为父路径式("Study / Docs"),避免嵌套/同名文件夹误选(全量用户流程测试中实际发生误选)。
- **C-17** 资料库子文件夹页系统返回键未拦截(直接退出 App):增加 `BackHandler`,子目录按返回回上级目录。
- **C-18** 文件夹卡片"子项"计数仅含子文件夹不含条目(归档 3 条仍显示 1 个子项):计数改为子文件夹 + 文件夹内条目数。
- **C-19** Release 版本号未随 v1.3 更新(versionName=0.1.0):提升为 versionCode=3 / versionName=1.3.0。

### Verified
- 单测 **144 通过 / 0 失败**(v1.2 基线 122 + 新增 22)。
- 模拟器端到端(anythingllm_api36):分享→收集箱;新建文件夹持久化;归档移动语义(收集箱清空、文件副本入库);FTP 配置持久化;同步 2/2 成功(PC 端 `.url` + 文件字节一致);二次同步无待同步条目;文件变更(46→48B)重传 1/1;知识库引导文案;服务端日志完整无异常。
- **全量用户流程重测(2026-09-11,Release v1.3.0)**:链接分享 2 条(系统 Chooser 真实链路)→ 收集箱;文件条目(ShareReceiver 格式种子,Android16 模拟器 adb 直发 URI grant 收紧所致,见设计文档 11.3)→ 收集箱 3 条;建文件夹 Work/Study/Notes 层级;归档 3 条入 Study;FTP 配置(10.0.2.2:2121)持久化 + 横幅消失;同步 3/3 → PC 端 `Library/Study/`(`.url` 格式正确、PDF MD5 一致);二次同步"没有待同步条目";PDF 608→610B 增量重传 1/1(MD5 一致);移动条目(同步态重置)→ 同步后 PC 端新路径出现;删除文件夹(子项上移、文件不删);C-16/17/18 修复后回归通过(路径显示、返回键、计数)。PC 端 FTP 服务日志 4 次会话全部干净(连接/登录/MKD 忽略已存在/STOR/断开)。
- 测试中环境缺陷记录:adb root push 的种子文件属主 u0_a0 导致 App 写回 EACCES(测试环境问题,chown 修复;归档链路本身无 bug,详见设计文档 11.3-F3)。本轮新增:push 种子文件 SELinux 上下文 category 不符(c219 vs App c220)致 App 读不到收集箱 → `chcon` 修正(测试链路问题,App 自身写入不受影响)。

---

## [Unreleased] — v1.2 需求回归(2026-09-11 规划)

里程碑:v1.2 开发计划《DOC/2.0需求回归/08-v1.2开发计划.md》建立;三需求整理完成;关键前提已检索验证;4 条工程纪律固化。

### Added(规划,详见 08 计划文档)
- **需求一 离线暂存**:户外/室内场景区分;收集箱本地暂存;知识库结构快照(文件夹+工作区)离线可用;回家后快照比对、失效目标提示重选。
- **需求二 分享入口 + 链接**:`ACTION_SEND`/`SEND_MULTIPLE` 接收文件与文本/链接(接收即复制防 URI 权限过期);按日分组管理;链接内容**由服务器抓取**。
- **需求三 同步模式 + 双主页**:底部导航 Tab1 收集箱(待整理,勾选标记)/ Tab2 知识库(结构清单 + 已标记计划 + 醒目"连接并同步嵌入"按钮);无可用工作区时降级为纯上传同步(=PC 目录同步)。
- **已验证**:AnythingLLM 提供 `POST /api/v1/document/upload-link`(body `{link: 单条/数组, addToWorkspaces}`,服务端 scrape 网页,返回 title/location);同步模式复用 `POST /api/v1/document/upload/{folder}` 纯上传。
- 新增 FR-17~28;阶段 0–4 门制;回归 R12–R20;预计 **7.5–8.5 人日**;并入 07-N3(替换阶段重试)/N5(前台 Service 防杀)。

### 阶段 0 预研验证(2026-09-11 完成,验收门达成)
- 冒烟:PowerShell 直传 WSL 命令转译截断 → 改为**脚本文件 + WSL 执行**后一次通过(ping 200 / auth 200);E-1 纪律实证。
- **upload-link 实测 4 组**:单 URL 成功(title=`{域名}_.html`、location=`custom-documents/url-{title}-{uuid}.json`、wordCount 含正文);无效 URL → `success:false`+明确错误;URL 数组一次处理多个;addToWorkspaces 生效(文档自动入工作区,异步嵌入,docpath 匹配与 v1.0 一致);清理链路(解关联+remove-documents 带前缀)验证无残留。
- 设计定稿:快照 diff(文件夹按 name/工作区按 slug 求差,失效标"目标失效",与重复检测职责分离;`upload/{folder}` 自动建文件夹 → 文件夹删除非硬失效);分享 intent(filter 覆盖 text/image/pdf/octet-stream,主方案接收即复制,大文件 fallback 持 URI)。
- 脚本入库 `DOC/_stagetest/v12_smoke.sh` / `v12_uploadlink_t1t2.sh` / `v12_uploadlink_t3.sh` / `v12_uploadlink_t4.sh`。

### 工程纪律(2026-09-11 用户补充,全局强制)
- E-1 编码安全:全局避免 PowerShell 转译/编码事故(史:BUG-006 GBK mojibake);源码编辑统一 Read/Edit/Write 或 WSL 内命令。
- E-2 冒烟测试前置:关键节点/阶段尝试前先跑最小冒烟(编译/单测/连通性)再实际进行。
- E-3 CHANGELOG 记录:重要发现与技术实现及时写入本文件。
- E-4 经验知识库:踩坑与经验及时记录到 DOC 文档(06/03/07 等),随取随用。

### 阶段 1 收集层(2026-09-11 编码完成,编译+单测冒烟通过)
- **数据模型** `data/collect/CollectEntry.kt`:EntryType{FILE,LINK} / EntrySource{SAF,SHARE_FILE,SHARE_LINK} / EntryStatus{PENDING,MARKED,EXECUTING,EXECUTED,FAILED};displayTitle 服务器回填优先;isMarked/isTerminal 便捷判断。
- **收集箱仓储** `data/collect/CollectRepository.kt`:纯 java.io 实现(JVM 可单测,不依赖 Context);元数据 `collect/entries.json` 整体序列化(kotlinx),文件副本 `collect/files/{yyyyMMdd}/{id}_{name}`;提供 pending/marked/groupByDay/dayDir/removeAll/cleanupExecutedFiles(FR-26:执行成功清原件、保留记录)。
- **链接解析** `domain/link/LinkParser.kt`:仅识别 http/https(不碰无协议 www),剥离中英文尾部标点,去重保序;hostOf 供展示;纯 Kotlin 单测。
- **分享接收** `ShareReceiver.kt`(透明 Activity,无 UI):ACTION_SEND/SEND_MULTIPLE 接收文件(接收即复制到私有目录,防 URI 权限过期)与文本/链接(提取 URL 暂存,内容交由服务器 upload-link 抓取);Manifest 增加 intent-filter(text/image/pdf/octet-stream/application);themes.xml 新增 Theme.AnythingLLM.Transparent;AnythingLLMApp 挂载 collectRepository(filesDir/collect)。
- **单测**:LinkParserTest 9 + CollectRepositoryTest 10,全量 98 全绿。
- **本轮修复链(踩坑)**:① getParcelableExtra 需显式泛型 `<Uri>`(编译错)② URL 正则误排半角 `?`/`:` → query 截断(仅排除全角中文标点)③ 测试落盘父目录未建 → FileNotFoundException(先 mkdirs)④ 测试 helper 变量名笔误(Unresolved reference)。已记入 06 文档方向。

### BUG-多选-01 多选分享无反应修复(2026-09-11,用户真机验证通过)
- **现象**:从文件管理器文件夹多选文件分享 → app 不启动/无反应。
- **根因**:`getParcelableArrayListExtra<Uri>(EXTRA_STREAM)` 在 EXTRA_STREAM 为**单 Uri**(部分文件管理器多选只发单 Uri + clipData)时抛 **ClassCastException** → ShareReceiver.onCreate 崩溃 → 分享无任何反馈。
- **修复**(ShareReceiver.kt):新增 `extractUris()`——用 `extras.get(EXTRA_STREAM)` 取原始对象做 `is Uri / is List<*> / is Array<*>` 类型分发 + clipData 逐 item 收集(LinkedHashSet 去重),handleSend/handleSendMultiple 统一走该函数;`copyAndAdd` 改返回 Boolean;接收成功 `openCollector()`(NEW_TASK|CLEAR_TOP|SINGLE_TOP)自动打开收集箱即时反馈(HomeScreen 已有 ON_RESUME 刷新)。
- **验证**:编译通过;模拟器复现崩溃→修复后崩溃消除;adb 直发无 grant 属测试链路限制,真实授权走 chooser/文件管理器——**用户真机多选分享验证成功**。
- **经验**:①Bundle 的 getParcelableArrayListExtra 对"单值"抛异常而非返回 null,必须先判型再分发 ②分享器形态各异,兼容必须覆盖 Uri/List/Array/clipData 四种 ③adb 直发 content:// 无 grant 必 SecurityException,测试多选分享须走真实文件管理器链路。

### 阶段 2 标记层(2026-09-11 编码完成,编译+单测冒烟通过)
- **结构快照** `data/collect/SnapshotRepository.kt`(FR-20):`{snapshotTime, folders[], workspaces[]}` 存 snapshot.json;diff 语义=文件夹按 name、工作区按 slug 为 key(§4.2.7);损坏容错;纯 Kotlin 单测。
- **标记校验** `domain/mark/MarkPlan.kt`(FR-21/22):文件夹缺失=软失效(执行时自动重建,V-02),工作区删除=硬失效(需重新指定);同步模式=仅文件夹无工作区。
- **API 契约补充**:`POST /api/v1/document/upload-link`(FR-19)→ `AnythingLLMApi.uploadLink` + `UploadLinkRequest(link: List<String>, addToWorkspaces)`。
- **双主页 UI**(FR-23):HomeScreen 重构为底部导航 Tab1「收集箱」(按日分组/勾选/标记对话框:文件夹必选+工作区可选=同步模式/删除)/ Tab2「知识库」(醒目同步按钮 + 快照结构清单 + 已标记计划可取消删除 + 比对结果展示);新增 CollectViewModel(收集箱+快照+标记+连接刷新);material-icons-extended 依赖(Compose BOM)。
- **单测**:SnapshotRepositoryTest 7 + MarkPlanTest 6,全量 116 全绿。

### 阶段 3 执行层(2026-09-11 编码完成,编译+单测冒烟通过)
- **SyncEngine** `domain/sync/SyncEngine.kt`(FR-24/25/26):编排三路执行——文件+工作区→复用 ImportEngine 完整管线(查重/上传/嵌入/轮询验证/替换,onDuplicate 用配置默认动作不弹窗);文件+无工作区(同步模式)→纯上传 `document/upload/{folder}`(带重试+简化重复检测);链接→`upload-link` 服务器抓取(并发 2),响应回填 title/location;断点续传(EXECUTED 跳过);完成后回写 EXECUTED/FAILED + cleanupExecutedFiles 清理原件。
- **LocalFileBodyProvider** `data/import/LocalFileBodyProvider.kt`:收集箱文件副本(绝对路径)流式读取,支持进度回调。
- **CollectViewModel.syncNow**:连接→刷新快照(比对)→启动前台服务→轮询仓储直至完成。
- **单测**:SyncEngineTest 5(MockWebServer:upload-link 契约/addToWorkspaces 省略/失败分类/同步模式纯上传/断点续传),全量 116 全绿。

### 阶段 4 打磨与回归(2026-09-11 编码完成,编译+单测冒烟通过)
- **前台同步服务** `SyncForegroundService.kt`(FR-24 防杀,07-N5):前台 dataSync 服务 + 进度通知(成功/失败/跳过计数 + 取消 action);Manifest 增 FOREGROUND_SERVICE/FOREGROUND_SERVICE_DATA_SYNC/POST_NOTIFICATIONS 权限;MainActivity Android 13+ 通知权限运行时请求;collect 恢复后刷新。
- **单测**:全量 116 全绿(阶段 1-4 新增 37 条,基线 79 保持)。

### 阶段 4 回归验收(2026-09-11 模拟器端到端,全过)
- **R12 文件分享 → 收集箱(通过)**:系统分享 chooser 选中本应用后,`files/collect/files/{yyyyMMdd}/{id}_{name}` 生成文件副本(59B 与源一致),entries.json 新增 `FILE/SHARE_FILE/PENDING` 条目;验证了"接收即复制防 URI 权限过期"设计。
- **R13 链接分享 → 收集箱(通过)**:`EXTRA_TEXT` 含 2 个 URL → 2 条 `LINK/SHARE_LINK/PENDING` 条目,query 参数完整保留(回归前修复的正则);重启应用条目不丢(本地持久化)。
- **R14 断网离线(通过)**:`svc wifi/data disable` 后重启,知识库快照仍从本地缓存读取(18:51 快照);断网点同步 → 明确提示"连接服务器失败:Failed to connect to /10.0.2.2:3001",快照不受影响。
- **R15 连接服务器刷新快照+比对(通过)**:一键同步按钮连接本机 AnythingLLM,拉取 5 文件夹/7 工作区落盘快照,首刷 diff 正确列出全部新增项。
- **R16 勾选→标记→双主页流转(通过)**:收集箱勾选(标记所选(1))→ 标记对话框选文件夹 custom-documents + 工作区 wsl → 条目 `MARKED` 从 Tab1 消失;知识库 Tab 已标记区可见(可取消/删除)。
- **R17/R18 一键同步端到端(通过)**:点"连接服务器并同步嵌入"→ 刷新快照(18:51)→ 启动前台服务 → `upload-link` 批量执行 → **服务器侧实测** wsl 工作区出现 `url-example.com_docs_a-{uuid}.json`(chunkSource=`link://https://example.com/docs/a?x=1`,嵌入 workspaceId=33/wsl)→ 条目回写 `EXECUTED`+serverTitle/serverLocation → 原件清理提示"同步完成,已执行条目的文件原件已自动清理"。
- **回归发现并修复的真实缺陷**:知识库 Tab 内容未应用 Scaffold `contentPadding`(Tab0 的 Column 有,Tab1 的 KnowledgeScreen 漏了),导致顶部"连接服务器并同步嵌入"按钮被 TopAppBar 遮挡不可见;修复=HomeScreen 给 KnowledgeScreen 传 `Modifier.padding(padding)` + KnowledgeScreen 增加 `modifier` 参数;修复后按钮正常显示且可点击。
- **回归工程记录**:PowerShell 直传 adb 多参数/中文多次被拆坏(E-1 实证),固化范式=WSL bash 脚本 + 整条命令单参数传给 `adb shell "..."`;uiautomator dump 仅含可见节点(滚动区需先滚动再验证);Compose RadioButton 需点击单选钮本体(文本区点击不选中);模拟器出现其他应用覆盖/遗留 chooser 时先 `am force-stop` + 关闭 ResolverActivity 再继续。
- **交付物**:Release APK 已按修复后代码重签重建(`app-release.apk`,12,066,539 B);单测 116 全绿保持。

### 二次全面验证(2026-09-11 交付后,含版本备份)
- **版本备份**:项目非 git → `D:\WSL\object\AnythingLLM-Android-backup-20260911-v1.2.tar.gz`(12.8MB,源码/配置/DOC/keystore,排除 build/.gradle/.kotlin)+ `-apk-20260911-v1.2.zip`(27.9MB,release+debug APK);Release SHA256=73F831F63366F7041BE3D3CD59BA6C9E5641EBBE58C96F4B7B541F5FFA57A628 为基准;全量单测 `--rerun-tasks` 强制重跑 116 全绿(确认 `UP-TO-DATE` 不代表最新,clean 后仍可能 UP-TO-DATE,须 `--rerun-tasks` 真重编)。
- **R15 失效端到端(修复后通过)**:分享→标记到 3333→服务器删 3333→同步:diff 检出 `- 工作区: 3333`,条目保留显示"目标工作区已删除,请取消后重新标记"→取消→重标 wsl→执行成功,服务器落盘 `url-openai.com_research-6b6aa1aa-*.json`。
- **R16 同步模式纯上传(通过)**:链接只选文件夹→"同步模式(不嵌入)"→`url-openai.com_research-883ed278-*.json` 落盘且不属于任何工作区。
- **R20 v1.0 即时导入(通过)**:选 v12_test.txt→校验通过→目标 custom-documents+wsl→成功 1/1→服务器落盘 `v12_test.txt-b6b5c72c-*.json`(嵌入向量化成功;workspace 关联未出现系 AnythingLLM 上传即嵌竞态,非 app 缺陷)。
- **二次验证修复 3 缺陷**:BUG-全面验证-01 前台分享不刷新(LaunchedEffect(Unit) 只跑一次→改 DisposableEffect 监听 ON_RESUME 刷新);BUG-全面验证-02 FAILED 条目 UI 不可见(marked() 增 FAILED 可重试语义+卡片显示 error);BUG-全面验证-03 失效目标假成功被静默吞(syncNow 启动同步前预检最新快照,失效→FAILED+提示重选,不执行)。
- **回归数据清理**:`POST /api/v1/workspace/wsl/update-embeddings`(deletes)+ `DELETE /api/v1/system/remove-documents`(`{names}`)→ success:true;复检 url-*/v12_test 测试文档 leftover=0(踩坑:首轮误用 `POST /api/v1/document/remove` 与 `POST /api/v1/system/update-embeddings` 返回登录页 HTML,正确端点与 01 文档 API 表一致)。
- **全量单测 122 全绿/14 suites**(新增 `marked includes failed for retry - r15`);修复后 Release 重签重建(12,066,539 B)。

---

## [1.0.0] — 2026-09-10(阶段 5:打磨与交付)

里程碑:可分发 Release APK 产出;回归 R1–R10 模拟器全过;维持"模拟器交付为底线"(R11 真机未执行)。

### Added
- **FR-15 深色模式**:新增 `ThemeMode{SYSTEM, LIGHT, DARK}`(默认跟随系统),`ConfigRepository` 新增 `theme_mode` 持久化;`MainActivity` 作为唯一全局主题包裹点;设置页"高级参数 → 外观 → 主题模式"三选下拉。
- **FR-16 服务器状态卡片回归确认**:Home 页展示连接状态/绿点/7 工作区/5 文件夹。
- **Release 签名构建**:`keystore/anythingllm-release.jks`(RSA 2048 / SHA256withRSA / 10000 天)+ `keystore.properties`(口令不入库);`build.gradle.kts` 读取自动配置 signingConfig(缺失时降级无签名);`assembleRelease` 成功产出 `app-release.apk`(7,838,565 B),`apksigner verify` 通过。
- 交付物上传飞书云空间「AnythingLLM-Android-交付」(5 文件:release/debug APK、03 文档、keystore 两件)。

### Fixed(阶段 5 编辑事故链,均已修复)
- BUG-006:PowerShell `Set-Content` 以 GBK 误读 UTF-8 → `TargetScreen.kt` 全文件中文字符 mojibake + 空行丢失 + 尾部引号被吞。恢复:GBK→UTF-8 逆向 + 从旧 `.class` 常量池提取原始字符串(`DOC/_stagetest/extract_strings.py`)逐串修复 + 多次 fix 脚本补齐。
- BUG-007 / BUG-008:`ValidationScreen.kt` 误删 `Card(` 行、DuplicateDialog title 行。
- BUG-009:`TargetScreen.kt` 误删 `@Composable` 注解。
- BUG-010:`ImportScreen.kt` 误删 title 参数 + 多余闭合花括号。
- BUG-011:`SettingsScreen.kt` 误删 `val (text, color)` 解构行。
- BUG-012:`TargetScreen.kt` 遗留 Theme 闭合括号配平(OPEN=67/CLOSE=68)。
- 沉淀红线:【Kotlin 源码编辑一律禁止 PowerShell `Set-Content`;统一 Read/Edit 工具或 `ReadAllText/WriteAllText(UTF8 无 BOM)`;删行用 Python 脚本精确匹配】

### Changed
- 7 个 Screen(Home/Select/Validation/Target/Import/Settings/Logs)移除内部冗余主题 wrapper,统一由 MainActivity 管理。

### Verified
- 深色↔跟随系统双向切换 + 持久化(模拟器截图确认);
- Release APK 签名验证通过;单测 79 全绿;
- 回归 R1–R10:连接/单文件导入(服务器落盘核验)/预校验/目标选择/统一模式/日志/深色等全过。

---

## [0.4.0] — 2026-09-10(阶段 4:结果与日志)

里程碑:混合结果可重试、日志可审计;单测 66 → **79 全绿**。

### Added
- **FR-12 结果汇总**:成功/失败/跳过计数;失败原因列表;单条"重试此文件"与"重试失败项"。
- **ImportEngine.retryItems**:仅 FAILED 项可重试,重置 PENDING 重跑完整管线,重置 docsCache/applyAllAction,共用并发闸门;越界索引与运行中调用安全忽略。
- **FR-13 JSONL 日志**:`ImportLogRepository`(纯 File 实现,JVM 可测)——追加写当日 `import-YYYYMMDD.jsonl`、跨天分文件、倒序读、损坏行容错、单删/全删/30 天清理。
- **日志页 LogsScreen**:文件列表/详情倒序/SAF 导出/清理/清空。

### Fixed
- BUG-005:重试后再失败时"终态指纹相同"导致 retry 日志漏写 → 修复为重试前清除该 index 指纹,重试终态必写。

### Verified
- 混合结果端到端(2 成功 1 失败)+ 重试流转正确;日志行结构与 PC 版可对照、不含 Key/baseUrl;SAF 导出可读;30 天清理与清空生效。

---

## [0.3.0] — 2026-09-10(阶段 3:批量导入主流程)

里程碑:全链路批量导入成功;验收门①–③通过;强化测试 A–G 全过。

### Added
- **FR-07 目标选择页**:统一/逐项模式切换;文档文件夹(列表/新建/删除确认);工作区列表 + 搜索 + 默认记忆;逐项模式逐文件指示。
- **FR-08 上传队列**:协程并发(2);multipart 构造(metadata 字段在 file 之前,字节级对齐 PC 版);单文件进度;指数退避重试(2 次,2s 起);取消支持。
- **FR-11 嵌入与验证**:`update-embeddings` 触发;按 docpath 轮询工作区详情验证(2s→5s 退避、300s 超时);全部匹配=通过。
- **FR-10 重复检测**:按原始 title 比对;命中弹对话框(保留/跳过/替换/中止 + 应用到全部);替换=update-embeddings 解关联为主 + remove-documents 物理删除为辅,删除后回查确认。
- **FR-09 中文文件名**:`ConvertToStorageName` Kotlin 移植(unicode/strip/keep),存储名 `uXXXX` ASCII 化,原始中文名写入 metadata.title。
- **进度页**:总进度、单文件状态卡片、阶段标签、暂停/继续/取消。

### Fixed
- BUG-001(API 契约):workspace 详情 metadata 实为 JSON 字符串 → DTO 改 `String?`,`ImportEngine` 增加 `metadataTitle()` 解析;API 契约同步:workspace 详情为数组取 `[0]`、upload 的 location 在 `documents[0]` 内、remove-documents names 必须带文件夹前缀、`DELETE /api/v1/document/{docname}` 不存在。
- BUG-002(竞态):ViewModel 收集引擎状态整体覆盖 `runState` 冲掉 `duplicateQuestion` → 第二个重复对话框永不弹出;修复为收集器保留 `it.runState?.duplicateQuestion`。
- BUG-003(测试基建):引擎测试改 `Dispatchers.Default` 真实时间 + `awaitTerminal()` 轮询 + MockWebServer(禁 runTest 虚拟时间驱动真实网络)。
- BUG-004(测试基建):domain 层移除 `android.util.Log`(JVM not mocked)。

### Verified(强化测试 A–G,2026-09-10)
- 用例 A:单文件 emoji 🎉 → 存储名 `ud83cudf89...`(UTF-16 代理对)✓
- 用例 B:单文件带重复 → 替换成功 ✓
- 用例 C:统一模式 5 文件混战(3.8MB/1500 chunks 嵌入约 1 分钟 + emoji + 新文件 + 2 重复)5/5 成功 ✓
- 用例 D:逐项模式 3 文件(真机首测),路由 100% 正确 ✓
- 用例 E:暂停/继续状态流转 ✓
- 用例 F:100MB+1 字节边界拒绝 ✓(注:全拒绝时按钮未显式禁用,建议项)
- 用例 G:空文件 + 非法扩展名拒绝 ✓
- 断网 5.7s → 自动退避重试 → 恢复成功,"上传重试 2/3"可见 ✓

---

## [0.2.0] — 2026-09-10(阶段 2:选文件与预校验)

里程碑:预校验正确、中文名转换与 PC 版逐项一致。

### Added
- **FR-03 SAF 多选**:`ACTION_OPEN_DOCUMENT` 多选;读取名称/大小/类型;持久 URI 权限。
- **FR-04 预校验器**:扩展名白名单、`maxFileSizeMB`(100MB)、空文件过滤;输出通过/拒绝清单。
- **FR-09 文件名策略**:`ConvertToStorageName`(unicode/strip/keep)——0x20–0x7E 保留、其余 `u`+4 位小写 hex、emoji 按 UTF-16 代理对、空名回退 `file-{guid8}`、移除 `"` `\`。
- **校验结果页**:通过/拒绝分区、总数/总大小。

### Verified
- 白名单外/超 100MB/空文件正确拒绝并注明原因;中文名转换与 PC 版对照一致。

---

## [0.1.0] — 2026-09-10(阶段 0–1:环境搭建、配置与 API 层)

里程碑:模拟器连通宿主机 AnythingLLM 并列出工作区。

### Added
- **环境基线**:JDK 17(Temurin)、Android Studio 2026.1.4.7、SDK platform 36、Gradle 8.14、AVD `anythingllm_api36`(WHPX 加速)、镜像源(清华/腾讯/阿里云)。
- **FR-01 设置页**:服务器地址、API Key(掩码)、测试连接按钮、高级参数折叠区(超时/白名单/大小/策略/重复动作/聊天测试开关)。
- **FR-02 配置仓库**:DataStore(非敏感)+ Keystore 自实现加密(API Key,security-crypto 已废弃);默认值回退。
- **FR-01 API 客户端**:Retrofit 实现 13 端点;OkHttp Bearer 拦截器;超时映射(probe 5 / upload 300 / embed 120 / api 60 / chat 300);探活三级 ping→auth;错误分类三态(不可达/Key 无效/正常)。
- **FR-05/06 工作区与文件夹**:列表 + 搜索 + 默认记忆;文件夹一级列表 + 创建。
- 单元测试:配置解析/回退、错误分类、MockWebServer 端点契约。

### Verified
- 模拟器内 `http://10.0.2.2:3001` + API Key → "连接正常";7 工作区 + custom-documents 列出;停服/错 Key 中文提示正确。

---

## [0.0.1] — 2026-09-10(项目初始化)

### Added
- Gradle 项目骨架(Kotlin DSL、settings.gradle.kts、gradle.properties、gradlew wrapper 8.14)。
- `.gitignore`(含 `apikey.txt`、keystore);`apikey.txt` 写入本机 API Key。
- 需求规格《01-开发文档.md》v1.0(16 项 FR + API 设计 + 技术方案)。
- 开发计划《02-开发计划.md》v1.0(阶段 0–5 门制 + 回归清单 R1–R11 + 风险对策)。
- 审阅拍板:FR-13 日志升 P0、并发上传 2、chat 测试默认关闭、明文 HTTP 放行、模拟器交付底线。

---

## [文档与工程资产补充记录]

| 日期 | 变更 |
|---|---|
| 2026-09-10 | 《03-API实测修订.md》完成(§1 契约差异 / §2 实测结构 / §3 联调备忘 / §4–§6 各阶段验收记录) |
| 2026-09-10 | 服务器核查脚本入库 `DOC/_stagetest/`(probe_ws / parse_routes / verify_routing / check_ws) |
| 2026-09-11 | 项目总结文档 04–07 生成(总结总览 / 阶段推进 / Bug 台账 / 下一步建议) |
| 2026-09-11 | 本 CHANGELOG.md 建立 |
| 2026-09-11 | v1.2 需求回归:计划《08-v1.2开发计划.md》建立于 `DOC/2.0需求回归/`;三需求整理 + upload-link 端点验证 + 4 条工程纪律固化(E-1~E-4) |

---

## 后续版本规划

- **1.2(已规划,见《DOC/2.0需求回归/08-v1.2开发计划.md》)**:离线暂存(户外/室内 + 结构快照比对)、Android 分享入口 + 链接(服务器 upload-link 抓取)、同步模式 + 双主页;并入 07-N3(替换阶段重试)/N5(前台 Service 防杀);阶段 0–4 门制,预计 7.5–8.5 人日。
- 1.1.x 候选(部分已并入 1.2):R11 真机回归、全拒绝时按钮显式禁用、HTTPS 收窄(networkSecurityConfig)等,详见《07-下一步开发建议.md》。
