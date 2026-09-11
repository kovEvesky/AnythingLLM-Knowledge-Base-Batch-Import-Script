# AnythingLLM 批量导入工具(Android 版)— Bug 与问题记录

> 编制日期:2026-09-11 | 状态:全部已修复/已明确边界(除标注"建议"项)
> 说明:按开发阶段组织的问题台账,含功能缺陷、竞态、测试基建、编码事故、服务器端契约差异五类;每条含现象/根因/修复/验证/教训,供排障与后续开发复用。
> 对应源记录:《03-API实测修订.md》§1–§6;相关修复脚本见 `DOC/_stagetest/`。

---

## 1. 缺陷台账总览

| 编号 | 阶段 | 类型 | 问题摘要 | 状态 |
|---|---|---|---|---|
| BUG-001 | 3 | API 契约 | workspace 详情 metadata 为 JSON 字符串,DTO 解析失败 | ✅ 已修复 |
| BUG-002 | 3 | 竞态 | 引擎状态整体覆盖冲掉 duplicateQuestion,重复对话框不弹 | ✅ 已修复 |
| BUG-003 | 3 | 测试基建 | runTest 虚拟时间驱动真实网络,7 用例挂 | ✅ 已修复 |
| BUG-004 | 3 | 测试基建 | domain 层用 android.util.Log,JVM not mocked 挂 10 例 | ✅ 已修复 |
| BUG-005 | 4 | 功能缺陷 | 重试后再失败"终态指纹相同"漏写日志 | ✅ 已修复 |
| BUG-006 | 5 | 编码事故 | PowerShell Set-Content 以 GBK 误读 → TargetScreen 全文件 mojibake | ✅ 已修复 |
| BUG-007 | 5 | 编码事故 | ValidationScreen 误删 `Card(` 行 | ✅ 已修复 |
| BUG-008 | 5 | 编码事故 | ValidationScreen DuplicateDialog title 行丢失 | ✅ 已修复 |
| BUG-009 | 5 | 编码事故 | TargetScreen 误删 `@Composable` 注解 | ✅ 已修复 |
| BUG-010 | 5 | 编码事故 | ImportScreen 误删 title 参数 + 多余闭合花括号 | ✅ 已修复 |
| BUG-011 | 5 | 编码事故 | SettingsScreen 误删 `val (text, color)` 行 | ✅ 已修复 |
| BUG-012 | 5 | 编码事故 | TargetScreen 遗留 Theme 闭合括号(配平 OPEN=67/CLOSE=68) | ✅ 已修复 |
| — | 3/4 | 已知边界 | 替换阶段无重试;全拒绝时按钮未显式禁用 | ⏸ 建议项(见 07) |

---

## 2. 阶段 3:批量导入主流程

### BUG-001 workspace 详情 metadata 是 JSON 字符串(API 契约)

- **现象**:请求 `GET /api/v1/workspace/{slug}` 后,DTO 反序列化失败或 metadata 字段取不到 title。
- **根因**:服务端返回 `{"workspace":[{..., "metadata":"{\"title\":\"xxx\"}"}]}`,metadata 是 **JSON 字符串**而非对象;开发文档初版按对象建模。
- **修复**:
  1. DTO 字段改为 `metadata: String?`;
  2. `ImportEngine` 增加 `metadataTitle()` 解析(JSON 字符串 → 取 title);
  3. ApiContractTest / ImportEngineTest 的 fixture 同步为字符串形式。
- **验证**:`assembleDebug` + 单测通过;模拟器工作区详情正常解析。
- **教训**:AnythingLLM 版本间 DTO 结构漂移大,必须以**真实实例实测**为准,开发文档只能作初稿。

### BUG-002 重复检测对话框竞态(第二个永不弹出)

- **现象**:多个重复文件时,第一个重复对话框正常,第二个及以后不再弹出,流程"卡住"。
- **根因**:`ImportSessionViewModel` 收集引擎状态时用引擎状态**整体覆盖** `runState`,把 `askDuplicate` 刚写入的 `duplicateQuestion` 字段冲掉(引擎状态本身恒为 null)。
- **修复**:收集器保留 `it.runState?.duplicateQuestion`,不整体覆盖。
- **验证**:logcat 实证——修复前 `askDuplicate(index=2)` 已调用但 UI 无对话框;修复后正常弹出。
- **教训**:状态合并用"字段级保留 + 整体兜底",禁止用子状态整体覆盖父状态中另写的字段。

### BUG-003 引擎测试用 runTest 虚拟时间驱动真实网络(7 例挂)

- **现象**:新增引擎集成测试时 7 个用例全部失败/挂起。
- **根因**:用 `runTest` 的虚拟时间(TestDispatcher)驱动真实 HTTP(MockWebServer),虚拟时钟与真实网络不同步。
- **修复**:引擎测试改用 `Dispatchers.Default` 真实时间 + `awaitTerminal()` 轮询等待终态 + MockWebServer harness。
- **教训**:真实网络 IO 的测试必须用真实时间;`runTest` 只适用于无 IO 的纯逻辑。

### BUG-004 domain 层放置 android.util.Log(JVM not mocked,10 例挂)

- **现象**:domain 层代码加了 `android.util.Log`,JVM 单测批量失败("not mocked")。
- **根因**:domain 层应纯 Kotlin 可测,引入 Android 平台类破坏 JVM 测试。
- **修复**:domain 层移除 Log 依赖(日志改由 ViewModel/Repository 层负责)。
- **教训**:分层纪律——domain 不依赖 Android 框架类,是 JVM 可测的前提。

### 服务器端契约备忘(非 App 缺陷,联调期确认)

| # | 现象 | 结论/对策 |
|---|---|---|
| S-01 | `DELETE /api/v1/system/remove-documents` 不带文件夹前缀 → 返回 success 但**静默不删** | names 必须带前缀 `custom-documents/xxx.json`;删除后回查 `/documents` 确认 |
| S-02 | `DELETE /api/v1/document/{docname}` 路由**不存在**(返回 SPA HTML) | 物理删除一律走 remove-documents(带前缀) |
| S-03 | report.pdf(47B 假 PDF)被服务器 pdf-parse 拒绝(500) | 属服务器行为;App 正确显示"失败·服务器返回错误(500)" |
| S-04 | `remove-folder` 实测会**删除文件夹全部内容**(源码 purgeFolder) | Android 版删除前必须二次确认 |
| S-05 | 存储名含空格时,服务器落盘把空格规整为 `-` | App 用返回 docpath 验证,不受影响 |

---

## 3. 阶段 4:结果与日志

### BUG-005 重试后再失败漏写日志(终态指纹相同)

- **现象**:某文件首次失败已写日志;用户重试后再次失败,日志页**没有新的 retry 记录**。
- **根因**:日志按"终态指纹"判重写入,首次失败与重试失败终态相同 → 指纹一致被判定为"已写过",跳过。
- **修复**:重试前**清除该 index 的指纹缓存**,保证重试终态必写一条 `action=retry` 记录。
- **验证**:验收门②——导入后日志含 success 行;重试事件补写 retry 行;日志页倒序可见;导出 1304B 内容逐行可读。
- **教训**:"去重写入"逻辑要区分"同一任务同状态"与"新一次执行"两个语义。

---

## 4. 阶段 5:打磨与交付

### BUG-006 ⚠️ TargetScreen.kt 编码事故(PowerShell GBK 误读,全文件 mojibake)

- **现象**:删除 import 行后,`TargetScreen.kt` 全文件中文字符变乱码(GBK mojibake)+ 空行丢失 + 字符串尾部引号被吞 → 编译失败。
- **根因**:PowerShell `Get-Content|Set-Content` 默认以 **GBK 编码读 UTF-8 文件**,导致中文双向错乱。
- **修复(多步恢复)**:
  1. GBK→UTF-8 编码逆向还原;
  2. 从旧 `.class` 常量池提取原始字符串(`DOC/_stagetest/extract_strings.py`)逐串修复;
  3. 多个 fix 脚本补齐引号/闭合括号(fix_targetscreen.py / fix2–fix6.py)。
- **验证**:`assembleDebug` 编译通过;UI 文案与 class 提取串逐一比对正确(逐项/统一模式/文档文件夹等全部正常显示)。
- **教训(红线,必须遵守)**:Kotlin 源码编辑**一律禁止 PowerShell `Set-Content`**(默认 GBK);统一用 Read/Edit 工具或 `ReadAllText/WriteAllText(UTF8 无 BOM)`;删行用 Python 脚本精确匹配。

### BUG-007~012 阶段 5 编辑事故链(均已修复)

| 编号 | 文件 | 误删内容 | 修复 |
|---|---|---|---|
| BUG-007 | ValidationScreen.kt | `Card(` 行 | 补回 + 编译验证 |
| BUG-008 | ValidationScreen.kt | DuplicateDialog 的 title 行 | 补回 + 编译验证 |
| BUG-009 | TargetScreen.kt | `@Composable` 注解 | 补回 |
| BUG-010 | ImportScreen.kt | title 参数 + 多余闭合花括号 | 参数补回/括号删除 |
| BUG-011 | SettingsScreen.kt | `val (text, color)` 解构行 | 补回 |
| BUG-012 | TargetScreen.kt | 冗余 Theme 闭合括号(OPEN=67/CLOSE=68) | 删除 1 个闭合括号配平 |

- **共性根因**:阶段 5 深色模式改造需要批量删除/迁移代码,编辑脚本(正则/删除行)精度不足,连续误删。
- **教训**:
  1. 批量编辑用**精确匹配**(整行全文匹配 + 上下文校验),不用模糊正则;
  2. 每步编辑后立即 `assembleDebug` 快速反馈,避免错误累积;
  3. 括号/引号类修改优先用 IDE/结构化工具,纯文本删除易配平错误。

---

## 5. 缺陷类型统计与经验总结

| 类型 | 数量 | 共性根因 | 预防手段 |
|---|---|---|---|
| API 契约 | 1 | 服务端结构未实测 | 联调期以真实实例校准,DTO 适配层集中 |
| 竞态 | 1 | 状态合并覆盖 | 字段级合并;logcat 实证定位 |
| 功能缺陷 | 1 | 指纹判重语义 | 区分"同任务同态"与"新执行" |
| 测试基建 | 2 | 虚拟时间/平台类泄漏 | 真实时间测网络;domain 禁 Android 类 |
| 编码事故 | 7 | 编码误读 + 批量编辑误删 | 禁 Set-Content;精确匹配;每步编译 |
| 服务器行为 | 5 | 服务端固有 | 契约备忘 + 回查确认 |

**排障通用姿势**(本项目中验证有效):
1. 引擎类问题看 logcat 时间线(竞态实证);
2. API 问题回查 docker 日志 + 服务器 storage(落盘核验);
3. 测试问题先判断"虚拟时间 or 平台类";
4. 编码问题先判断源文件编码(UTF-8 无 BOM),禁止在未知编码上做 PowerShell 文本流操作。

## 6. 参考

- 契约与验收原文:《03-API实测修订.md》
- 修复脚本:`DOC/_stagetest/`(extract_strings.py / fix*.py / verify_routing.py 等)
- 后续遗留与改进:《07-下一步开发建议.md》

## 7. v1.2 二次全面验证缺陷(2026-09-11)

| # | 缺陷 | 根因 | 修复 | 坑点/经验 |
|---|---|---|---|---|
| BUG-全面验证-01 | 前台分享后收集箱不刷新 | ShareReceiver 压栈处理→返回仅 onResume;LaunchedEffect(Unit) 只在组合期执行一次 | HomeScreen 用 DisposableEffect 监听 ON_RESUME 时 refresh() | Compose 中"回到页面刷新"不要依赖 LaunchedEffect(Unit),生命周期回调才可靠 |
| BUG-全面验证-02 | 执行失败/目标失效条目从 UI 消失 | marked() 只含 MARKED/EXECUTING;FAILED 条目不在任何列表展示 | marked() 增 FAILED(可重试语义);卡片显示 entry.error | 状态机条目必须有可见兜底,FAILED 也需可操作入口 |
| BUG-全面验证-03 | 失效目标被服务器"假成功"静默吞掉 | 服务器对无效 addToWorkspaces 静默忽略并成功上传;app 执行前无目标预检 | syncNow 比对最新快照预检,失效→FAILED+提示重选不执行 | 依赖外部系统契约时,关键前置条件必须在本地先校验;勿假设服务器会报错 |
| 清理端点踩坑 | remove/update-embeddings 返回登录页 HTML | 端点写错:`POST /api/v1/document/remove`、`POST /api/v1/system/update-embeddings` 均不存在 | 正确端点:`POST /api/v1/workspace/{slug}/update-embeddings` + `DELETE /api/v1/system/remove-documents`({names}) | API 表(01 文档)为准;HTML 响应=端点/鉴权错误,先核对文档再试 |
| 嵌入竞态 | v1.0 上传后 updateEmbeddings 关联有时不生效 | 上传处理异步,立即 updateEmbeddings 的 adds 可能被忽略;collector 自动嵌入只向量化不关联 workspace | 服务器行为,app 按契约调用(UI 成功+落盘);已记录为已知限制 | 服务器竞态:上传即嵌的流程,验证轮询可能瞬时通过,需以服务器日志为准归因 |
| BUG-多选-01 | **文件夹多选分享→app 无反应/不启动** | `getParcelableArrayListExtra<Uri>(EXTRA_STREAM)` 在 EXTRA_STREAM 为**单 Uri**(部分文件管理器多选只发单 Uri+clipData)时抛 ClassCastException → ShareReceiver.onCreate 崩溃 → 无任何反馈 | `extractUris()`:用 `extras.get(EXTRA_STREAM)` 取原始对象做 `is Uri / is List<*> / is Array<*>` 类型分发 + clipData 逐 item 收集(LinkedHashSet 去重);handleSend/handleSendMultiple 统一走该函数;接收成功 `openCollector()` 自动打开收集箱(NEW_TASK\|CLEAR_TOP\|SINGLE_TOP) | ①Bundle 的 getParcelableArrayListExtra 对"单值"不返回 null 而是抛异常,必须先 `extras.get()` 判型再分发 ②分享器形态各异(SEND_MULTIPLE 也可能发单 Uri),兼容必须覆盖 Uri/List/Array/clipData 四种 ③adb 直发 content:// URI 无 grant 必 SecurityException,真实授权只能走 chooser/文件管理器链路(用户真机验证通过) |

> 经验延续:API 问题一律回查 docker 日志(容器内 AnythingLLM stdout 有 CollectorApi/OllamaEmbedder 全链路日志)。

## 8. v1.2 真机(Pixel 3 / Android 15)全面测试(2026-09-11)

> 环境:项目迁至 `004AnythingLLM-Android`(git 已配 GitHub 远程 origin/Android),USB 真机 adb 直连;服务器地址改为局域网 `http://192.168.8.71:3001`(默认 10.0.2.2 仅模拟器可用);Debug 包 run-as 验证私有数据。

| 测试项 | 结果 | 实测证据 |
|---|---|---|
| 手机→PC 网络 | ✅ | ping 192.168.8.71 0% 丢包;HTTP /api/ping 200 |
| 服务器鉴权 | ✅ | `{"authenticated":true}`(apikey.txt) |
| 配置持久化 | ✅ | DataStore `config.preferences_pb` 含 `http://192.168.8.71:3001` |
| 链接分享×2 | ✅ | 文本分享 → 收集箱 LINK 条目×2(PENDING)→ 标记(custom-documents+wsl)→ 同步 → 服务器落盘 `url-*.json`(docSource="URL link uploaded by the user.") |
| 单选文件分享 | ✅ | 系统 chooser 选"AnythingLLM 批量导入" → FILE 条目 `v12_phone_a.txt`(43B,PENDING,已复制私有目录) |
| 多选分享(单 Uri 形态) | ✅ 无崩溃 | am SEND_MULTIPLE 单 Uri → extractUris 不抛 ClassCastException(修复生效);未入库系 Resolver 不授权单 Uri SEND_MULTIPLE(测试链路限制,非缺陷) |
| v1.0 即时导入 | ✅ | SAF 选文件 → 校验"通过 1·拒绝 0" → 目标 custom-documents+ws1 → "共 1·成功 1" → 服务器落盘 `v12_phone_a.txt-*.json` |
| 权限模型 | ✅ | 唯一运行时权限 POST_NOTIFICATIONS granted=true;存储权限按设计不需要(分享/SAF 走系统授权) |

**新踩坑(清理链路)**:`DELETE /api/v1/system/remove-documents` 对**仍关联工作区**的文档返回 `success:true` 但**不物理删除**(官方"假成功",01 文档 §5.7 已记载)。正确清理顺序:① `POST /api/v1/workspace/{slug}/update-embeddings` 的 **deletes 必须传 docpath**(`custom-documents/xxx.json`,非裸文件名——传文件名不生效)解关联;② remove-documents;③ 仍残留时 `docker exec anythingllm rm` 物理删除(解关联后安全)。复检容器 `custom-documents/` 下 LEFT=0。
