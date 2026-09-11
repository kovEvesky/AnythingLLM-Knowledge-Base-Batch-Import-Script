# AnythingLLM API 实测修订记录

> 编制日期:2026-09-10 | 来源:对本机实例(localhost:3001)逐端点实测
> 说明:对《01-开发文档.md》§5 的契约修正/补充;实现以本文件为准。

## 1. 与开发文档的差异(重要)

| # | 端点 | 开发文档描述 | 实测结果 | 影响 |
|---|---|---|---|---|
| 1 | `GET /api/v1/workspace/{slug}` | `{workspace:{documents:[...]}}`(对象) | `{"workspace":[{...documents:[...]}]}`(**数组**) | DTO 按 `workspace: List` 建模,取 `[0]` |
| 2 | `POST .../upload` 响应 | `{location}`(顶层) | `{"success":true,"error":null,"documents":[{...,"location":"{folder}/{storageName}-{uuid}.json"}]}` | `location` 在 `documents[]` 内,取 `documents[0].location` |
| 3 | `DELETE /api/v1/system/remove-documents` | `{names:[文件名]}` | names 必须**带文件夹前缀**,如 `custom-documents/xxx.json`;不带前缀 → 返回 success 但**静默不删**(假成功) | 替换/清理流程必须拼前缀;删除后应回查确认 |
| 4 | `DELETE /api/v1/document/{docname}` | 部分资料提到 | 本版本**不存在此路由**(返回 SPA HTML) | 物理删除一律走 remove-documents(带前缀) |

## 2. 实测确认的响应结构要点

### 2.1 工作区详情(验证轮询数据源)
```json
{"workspace":[{"id":33,"slug":"wsl","documents":[
  {"id":329,"docId":"uuid","filename":"a.json","docpath":"1/a-uuid.json",
   "metadata":{"title":"02WSL环境配置指南.md","url":"file:///app/collector/hotdir/..."}}]}]}
```
- `docpath` 格式:`{folder}/{storageName}-{uuid}.json`,与 upload 返回的 `location` 一致 → 验证轮询按此匹配(忽略大小写)。

### 2.2 文档树
```json
{"localFiles":{"name":"documents","type":"folder","items":[
  {"name":"custom-documents","type":"folder","items":[
    {"name":"01u6df1u6d77u4e16u754c.txt-uuid.json","type":"file",
     "url":"file:///app/collector/hotdir/...","title":"01深海世界.txt",...}]}]}}
```
- 文件夹节点:`type=="folder"`;重复检测按 `title`(原始文件名)比对。

### 2.3 上传(multipart,与 PC 版字节级一致)
- 请求:`--{boundary}\nContent-Disposition: form-data; name="metadata"\n\n{"title":"原始文件名"}\n--{boundary}\nContent-Disposition: form-data; name="file"; filename="{storageName}"\nContent-Type: application/octet-stream\n\n<字节流>\n--{boundary}--`
- **metadata 部分无 Content-Type 头**(OkHttp `createFormData(name,value)` 行为);**必须在 file 之前**。
- 实测上传 `契约测试.txt` → `title="契约测试.txt"`,`filename=u5951u7ea6u6d4bu8bd5.txt`,`location=custom-documents/u5951u7ea6u6d4bu8bd5.txt-{uuid}.json`,200。
- 中文转 uXXXX 的手算结果与服务端存储名一致,算法预期正确。

### 2.4 探活
- `GET /api/ping` → `{"online":true}`(200,无需 Key;实测带 Key 也无妨)
- `GET /api/v1/auth` → `{"authenticated":true}`(200=有效;401/403=无效)

### 2.5 文件夹管理
- `POST /api/v1/document/create-folder`,`{"name":"x"}` → `{"success":true,"message":null}`;重名 → 500 `{"success":false,"message":"Folder by that name already exists"}`
- `DELETE /api/v1/document/remove-folder`,`{"name":"x"}` → `{"success":true,"message":"Folder removed successfully"}`
- 注意:此版本 remove-folder **会删除文件夹全部内容**(源码 `purgeFolder`),非仅空目录;Android 版删除前需二次确认。

## 3. 联调备忘(阶段 3 用)
- 上传成功→ `location`;`update-embeddings` body `{"adds":[location],"deletes":[]}`。
- 验证:`GET /workspace/{slug}` 的 `documents[].docpath`(忽略大小写)匹配 location 即通过。
- 替换旧文档:先 `update-embeddings deletes=[旧docpath]` 解关联,再 `remove-documents names=[带前缀旧名]` 物理删除,删除后回查 `/documents` 确认。
- 本机 7 个工作区:`1,2,5,wsl,ee,ewewew,3333`;5 个文件夹:`custom-documents,1,3,555,d`。

## 4. 阶段3 模拟器验收记录(2026-09-10)
- 修复1:workspaceDetail 的 metadata 为 JSON 字符串,DTO 改 String?;ImportEngine 增加 metadataTitle() 解析取 title;ApiContractTest/ImportEngineTest fixture 同步为字符串形式。
- 修复2(竞态):ImportSessionViewModel 收集引擎状态时整体覆盖 runState,把 askDuplicate 刚写入的 duplicateQuestion 冲掉 → 第二个重复对话框永不弹出。修复:收集器保留 it.runState?.duplicateQuestion(引擎状态本身恒为 null)。logcat 实证:修复前 askDuplicate index=2 已调用但 UI 无对话框;修复后正常弹出。
- 验收门①(统一模式 上传→嵌入→轮询验证):3 txt(验收文件三/需求文档/验收文件四)应用全部替换 → 共 3·成功 3·失败 0·已完成;中文存储名 u9a8c/u9700/u9a8c4 均正确。
- 验收门②(重复四类动作+应用到全部):替换✓(4 旧单据删净、新 title 正常)、跳过✓、保留✓(单据数 1→2)、中止✓(取消 1)、应用到全部✓(applyToAll=true 只弹一次对话框)。
- 验收门③(断网重试):断网测试.txt 上传期断网 5.7s(模拟器 airplane-mode)→ 自动退避重试 → 恢复后重试成功 → 嵌入验证通过。UI 可见"上传重试 2/3"。
- 失败展示:report.pdf(47B 假 PDF)被服务器 pdf-parse 拒(500)→ 界面正确标"失败·服务器返回错误(500)",属服务器行为非 App 缺陷。
- 已知边界:替换阶段(update-embeddings deletes/remove-documents)无重试(FR-08 只要求上传重试);该阶段遇瞬时断网 → 单文件失败并显示"无法连接服务器",重试入口在阶段4(结果汇总/失败重试)。

### §4.1 强化全面测试补记(2026-09-10,用户要求"再次全面测试+加大难度")
- 用例A(单文件 emoji):🎉特殊文件.txt(67B)→ 成功;存储名 ud83cudf89u7279u6b8au6587u4ef6.txt(UTF-16 代理对两段 unicode 转义)✓。
- 用例B(单文件带重复):断网测试.txt 重复 → 替换 → 成功 ✓。
- 用例C(统一模式 5 文件混战):3.8MB 大文件(1500 chunks,bge-m3 CPU 嵌入约 1 分钟,docker 日志 Batch 1500/1500 后入 LanceDB)+ emoji + 新文件 + 2 重复 → 应用全部替换 → 共 5·成功 5·已完成;大文件验证在 verifyTimeoutSec=300 内完成。
- 用例D(逐项模式 3 文件,首次真机):my'file→文件夹3/工作区wsl、🎉特殊→custom-documents/工作区2、验收文件五→文件夹1/工作区1 → 共 3·成功 3。服务器路由核验:3/my'file-test.txt-*.json 在 ws=wsl;custom-documents/ud83c...json 在 ws=2;1/u9a8c...五.json 在 ws=1。替换跨文件夹生效(旧 custom-documents 同名字据被删)。
- 用例E(暂停/继续):大文件替换后重新嵌入期点"暂停"→ 状态"进行中"→"已暂停"、按钮变"继续"、暂停 5s 项状态无推进 → 点"继续" → 最终 共 1·成功 1·已完成 ✓。
- 用例F(100MB 上限边界):big.docx 恰 104857601B(100MB+1)→ 预校验"✗ 超过大小上限 100MB",通过 0·拒绝 1;全拒绝时点"下一步"无推进(实际不可继续导入)✓。注:按钮未显式禁用,建议后续显式 enabled=false。
- 用例G(空文件+非法扩展名):empty.txt(0B)→"✗ 文件为空(0 字节)";evil.exe(20B)→"✗ 扩展名不在允许列表(允许: .pdf .docx .doc .txt .md .csv .xlsx .pptx .org .adoc .rst .json .html .odt .odp)";通过 0·拒绝 2 ✓。
- 存储名实测补充:my'file - test.txt 经 App 转换保留(0x20 空格/撇号均保留),服务器落盘规整为 my'file-test.txt(空格→-),与 PC 版行为一致,App 用返回 docpath 验证不受影响。

### §5 阶段4 结果与日志验收记录(2026-09-10)
- 实现:ImportEngine.retryItems(仅 FAILED 项可重试,重置 PENDING 重跑完整管线,重置 docsCache/applyAllAction,共用并发闸门);ImportLogRepository(纯 File 实现,JVM 可测:追加写当日 import-YYYYMMDD.jsonl、跨天分文件、倒序读、损坏行容错、单删/全删/30 天清理);LogsScreen(文件列表/详情倒序/SAF 导出/清理/清空);ImportSessionViewModel 引擎状态收集时按"终态指纹"写日志,上次失败→本次终态记 action=retry。
- 单测:新增 ImportEngineRetryTest(5 例:失败→重试成功计数流转、重试仍失败、仅失败项可重试且不重复上传、越界索引忽略、运行中调用忽略)+ ImportLogTest(8 例:行格式/脱敏/倒序/损坏容错/多文件排序/清理/删单全删/导出合并)。总计 66→79,全部通过。
- 模拟器端到端(验收门①混合结果):验收文件四(新)+ 需求文档(重复→替换)+ report.pdf(47B 假 PDF)统一模式 → 共 3·成功 2·失败 1·已完成;report.pdf 原因"服务器返回错误(500)"(服务器 pdf-parse 拒绝,属服务器行为);单条"重试此文件"与底部"重试失败项(1)"均出现;重试后状态正确流转(失败 0→1 终态一致,汇总数字一致)。
- 验收门②日志:导入后自动生成 files/logs/import-20260910.jsonl;行结构 {time,level,source,action,file,folder,workspace,storageName,sizeBytes,result,error,retries},与 PC 版可对照,不含 Key/baseUrl;重试事件补写 action=retry(修复前终态指纹相同会漏写——修复:retry 前清除该 index 指纹,重试终态必写);日志页详情倒序展示 5 行;SAF 导出到 Download 成功(1304B 内容逐行可读);"清理 30 天前"自动删除 60 天前旧文件;"清空日志"确认对话框后列表清空。

### §6 阶段5 打磨与交付验收记录(2026-09-10)
- **FR-15 深色模式(手动切换)**:新增 `ThemeMode{SYSTEM,LIGHT,DARK}`(AppConfig 默认 SYSTEM);ConfigRepository 新增 theme_mode 键持久化;`MainActivity` 成为**唯一** `AnythingLLMTheme(darkTheme=…)` 包裹点(SYSTEM→isSystemInDarkTheme()/LIGHT→false/DARK→true);**7 个 Screen(Home/Select/Validation/Target/Import/Settings/Logs)全部移除内部冗余 wrapper**,否则嵌套 wrapper 会用默认"跟随系统"覆盖全局强制值;设置页"高级参数→外观→主题模式"三选下拉。
- 模拟器验证:默认"跟随系统"→切"深色"保存→Home 立即深色背景(截图确认)→切回"跟随系统"→回浅色背景;配置持久化(重启 App 仍生效)。双向切换 ✓。
- **FR-16 服务器状态卡片**:Home 已有(服务器状态/连接正常·绿点/7 个工作区 · 5 个文档文件夹),回归确认 ✓。
- **Release 签名构建**:`keytool` 生成 `keystore/anythingllm-release.jks`(RSA 2048/SHA256withRSA,10000 天,CN=AnythingLLM Importer);`keystore.properties` 存口令(不入库);build.gradle.kts 读取 keystore.properties 自动配置 signingConfig(缺失时降级无签名);`assembleRelease` 成功 → `app-release.apk`(7838565B);`apksigner verify` 通过(SHA-256 digest 0f03df89…)。
- **回归 R1–R11(模拟器端到端)**:R1 连接与配置✓(Home 连接正常);R2 单文件导入✓(stage5_test.txt 63B→成功 1,服务器 storage 确认真实落盘,App 日志 success 行落盘);R3 预校验✓(通过 1·拒绝 0,存储名正确);R4 目标选择✓(统一/逐项 chip、文档文件夹、目标工作区、开始导入文案完整);R5 统一模式✓;R8 日志✓(import-20260910.jsonl success 行);R10 深色✓(见上);R6/R7/R9 在 §4/§5 已覆盖(逐项模式/暂停继续/日志导出)。R11 真机回归:用户未提供真机,**维持模拟器交付为底线**(开发文档 §5 已声明)。
- **单测**:79 tests 全绿(66 原 + 重试 5 + 日志 8),`gradlew :app:assembleDebug :app:testDebugUnitTest` 通过。
- **过程事故与修复(记录)**:
  1. PowerShell `Get-Content|Set-Content` 删 import 行时以 GBK 误读 UTF-8 → TargetScreen.kt 全文件中文 mojibake + 空行丢失 + 字符串尾部引号被吞。**恢复方法**:GBK→UTF-8 编码逆向 + 从旧 class 常量池提取原始字符串(Python 脚本 `DOC/_stagetest/extract_strings.py`)逐串修复;修复后 `assembleDebug` 编译通过、UI 文案与 class 提取串一致(逐项/统一模式/文档文件夹等全部正确显示)。
  2. **红线重申**:Kotlin 源码编辑一律禁止 PowerShell `Set-Content`(默认 GBK 读);统一用 Read/Edit 工具或 `ReadAllText/WriteAllText(UTF8 无 BOM)`;删行用 Python 脚本精确匹配。
- **交付物**:`app/build/outputs/apk/release/app-release.apk`(签名 Release)、`app/build/outputs/apk/debug/app-debug.apk`、`keystore/`(签名文件+口令,交付时单独说明)、本文档。
