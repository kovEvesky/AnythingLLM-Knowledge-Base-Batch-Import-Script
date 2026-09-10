# AnythingLLM 文档嵌入工具 — 项目实施规划

> 基于《EmbedIntoWorkspace-Design-v4.0.md》制定
>
> 文档版本：v1.0 | 编制日期：2026-09-09 | 状态：待执行

---

## 一、项目概述

### 1.1 目标

实现一个 Windows 桌面端工具：**将文件拖拽到 BAT 脚本上，自动完成上传 → 嵌入到指定 AnythingLLM 工作区 → 验证 → 可选测试检索**的全流程。

### 1.2 核心能力

1. 拖拽即用，支持单文件与多文件批量处理
2. 多文件两种模式：统一模式（同一工作区）/ 逐项模式（每文件自选工作区）
3. 通用方向键菜单选择工作区，支持分页与关键字过滤
4. 文件预校验：扩展名白名单、大小上限、空文件、占用检查、长路径
5. 上传前重复检测（按 title 比对）：跳过 / 替换 / 全部保留
6. 嵌入后批量轮询验证（60s 窗口 + 退避）
7. 可选测试检索（query 模式）
8. 结构化 JSONL 日志 + 过期清理
9. 外部 config.json 配置（支持注释、校验、默认值回退）
10. 语义化退出码：0 成功 / 1 错误 / 2 取消 / 3 部分失败

### 1.3 技术栈

| 组件 | 技术 | 编码要求 |
|------|------|---------|
| 入口脚本 | `.bat`（cmd 原生） | UTF-8 无 BOM + CRLF |
| 核心逻辑 | PowerShell 5.1+（兼容 PS 7） | UTF-8 **带 BOM** + CRLF |
| API 通信 | `System.Net.Http.HttpClient` 统一封装 | — |
| JSON | `ConvertFrom-Json` / `ConvertTo-Json` | — |
| 终端 | 16 色 Write-Host + 可选 24-bit ANSI | — |

### 1.4 交付物清单

```
AnythingLLM-Tools/
├── tools/
│   ├── EmbedIntoWorkspace.bat        # 入口包装器
│   ├── embed.ps1                     # 核心脚本
│   ├── config.json                   # 外部配置（可选）
│   ├── apikey.txt                    # API Key
│   └── logs/                         # 操作日志（自动创建）
├── scripts/
│   ├── fix-bat-encoding.ps1          # BAT 编码修复
│   ├── fix-ps1-encoding.ps1          # PS1 编码修复
│   └── README.md                     # 使用说明
└── docs/
    ├── EmbedIntoWorkspace-Design-v4.0.md   # 设计文档（已有）
    └── EmbedIntoWorkspace-ProjectPlan.md    # 本文档
```

---

## 二、阶段总览

整个实施分为 **7 个阶段**，按依赖关系顺序执行。每阶段完成后进行独立验证，确认通过后再进入下一阶段。

| 阶段 | 主题 | 核心交付物 | 完成后可验证的能力 | 预计对应设计文档章节 |
|------|------|-----------|-------------------|---------------------|
| 1 | 地基：骨架与编码基础设施 | 目录结构、config.json、两个编码修复脚本 | 编码修复脚本可运行，BAT/PS1 编码可控 | §2、§9 |
| 2 | 配置与控制台初始化 | Read-Config/Assert-Config/Read-ApiKey/Initialize-Console + PS1 骨架 | --help 正常、配置回退生效、控制台中文无乱码 | §4.1–4.4 |
| 3 | API 统一层与环境检测 | HttpClient 封装、Invoke-Api/Start-Api、探活、工作区获取、全量诊断 | 能连通 API、区分不可达/Key无效、列出工作区 | §4.5–4.6、§5.0–5.1 |
| 4 | 终端美化与交互菜单 | CJK 显示宽度、True Color 降级、spinner、计数进度、方向键菜单（分页+过滤+重定向降级） | 菜单不漂移、中文对齐、动画正常、重定向不崩 | §4.7、§4.9 |
| 5 | 文件处理与上传 | 预校验（含占用/长路径）、同批次去重、multipart 上传、指数退避重试 | 单文件上传成功，各种非法输入正确拒绝 | §4.8、§4.10、§5.2 |
| 6 | 嵌入/验证/删除/测试聊天 | update-embeddings、批量轮询验证、物理删除（两步回退）、query 模式聊天 | 上传→嵌入→验证全链路通，替换旧版无孤儿 | §4.11–4.13、§5.3–5.6 |
| 7 | 集成与交付 | 重复检测、JSONL 日志、主流程六阶段、五类汇总、退出码、BAT 包装器、README、54 项回归测试 | 拖拽 BAT 完成完整流程，全部测试通过 | §3、§4.14–4.15、§6–§8 |

---

## 三、各阶段详细规划

### 阶段 1：地基 — 骨架与编码基础设施

**为什么先做**：PS1 必须带 BOM、BAT 必须无 BOM 是全项目硬约束。编码修复脚本是后续每阶段写完代码后的自检工具，必须最先可用。

#### 交付物

| 文件 | 说明 |
|------|------|
| 目录结构 | 创建 `tools/`、`tools/logs/`、`scripts/`、`docs/` |
| `tools/config.json` | 带 `//` 注释的完整配置示例，所有字段 + 默认值 |
| `tools/apikey.txt` | 占位说明文件（提示用户填入 API Key） |
| `scripts/fix-bat-encoding.ps1` | 将 `.bat` 转为 UTF-8 无 BOM + CRLF |
| `scripts/fix-ps1-encoding.ps1` | 将 `.ps1` 转为 UTF-8 带 BOM + CRLF |

#### 实现要点

- 编码修复脚本使用 `[IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($isPs1)))` 精确控制 BOM
- 两个脚本目标单一，互不影响（v3.0 已纠正 v2.0 单脚本目标冲突的问题）
- config.json 包含设计文档 §2 列出的全部字段：baseUrl、各类超时、验证参数、上传重试、白名单、maxFileSizeMB、defaultWorkspace、askForChatTest、chatMode、detectDuplicates、duplicateDefaultAction、logDir、logRetentionDays、useTrueColor

#### 验证标准

1. 对一个测试 `.bat` 运行 `fix-bat-encoding.ps1`，十六进制确认无 `EF BB BF`、行尾为 `0D 0A`
2. 对一个测试 `.ps1` 运行 `fix-ps1-encoding.ps1`，十六进制确认首三字节为 `EF BB BF`、行尾为 `0D 0A`
3. 两个脚本对同一文件分别运行后结果正确，互不干扰
4. config.json 可被 `ConvertFrom-Json`（剥离注释后）正常解析

---

### 阶段 2：配置与控制台初始化

**依赖**：阶段 1

#### 交付物

| 函数/模块 | 说明 |
|-----------|------|
| `embed.ps1` 完整骨架 | `param()`、各 `#region` 占位、入口 `try/catch/finally`、`exit $code` |
| `Read-Config` | 注释剥离 + 默认值合并 + key 大小写规范化 |
| `Assert-Config` | 配置合法性校验 + 回退 + 警告收集 |
| `Read-ApiKey` | 多编码自动检测读取 |
| `Initialize-Console` | 编码设置 + ANSI 探测 + 重定向降级 + TLS 1.2 |
| `--help` | 输出用法说明并退出 0（v4.0 补齐） |

#### 实现要点

**Read-Config**：
- 用正则剥离 `//` 行注释和 `/* */` 块注释（PS 5.1 的 ConvertFrom-Json 不支持注释）
- 内置 `[ordered]` 默认值表，遍历文件属性按大小写不敏感匹配后覆盖
- 解析失败时 warning 并返回全部默认值，不阻断

**Assert-Config**（v4.0 修正 B5）：
- baseUrl 必须匹配 `^https?://`，否则回退默认并 TrimEnd('/')
- 所有超时字段必须 > 0，否则回退对应默认值
- **VerifyBaseIntervalSec 回退 2、VerifyMaxIntervalSec 回退 5**，不得统一回退 60
- 白名单补齐前导 `.` 并转小写
- ChatMode 限定 `chat`/`query`，DuplicateDefaultAction 限定 `ask`/`skip`/`replace`/`keep`
- LogDir 支持绝对路径，相对路径基于 `$PSScriptRoot`

**Read-ApiKey**：
- 按字节检测：UTF-8 BOM（跳过 3 字节）→ UTF-16 LE BOM（跳过 2 字节，v3.0 关键修复）→ UTF-16 BE（跳过 2 字节）→ 无 BOM 严格 UTF-8 解码 → 失败回退 ANSI/GBK
- 正则 Trim 去除 `U+FEFF`、`U+200B`、空白和控制字符
- 函数内用 `throw` 而非 `exit`，由 Main 统一捕获

**Initialize-Console**：
- P/Invoke 调用 `GetStdHandle`/`GetConsoleMode`/`SetConsoleMode` 探测并启用虚拟终端（v4.0 B9：C# 片段必须补 `using System.Runtime.InteropServices;`）
- `Add-Type` 前用 `('Win32.Kernel32V3' -as [type])` 判断类型是否已存在，避免重复编译报错
- 检测 `[Console]::IsOutputRedirected` / `IsInputRedirected`，重定向时强制纯文本、不设 InputEncoding
- 输出/输入编码均设为 UTF-8 无 BOM，与 `chcp 65001` 一致
- 固定 TLS 1.2（https 场景必需）
- ANSI 转义符用 `$esc = [char]27`（PS 5.1 无 `` `e ``）

#### 验证标准

1. `embed.ps1 --help` 输出用法说明，退出码 0
2. config.json 设 `verifyBaseIntervalSec=-1`，启动后 warning 提示回退为 2（不是 60）
3. config.json 语法错误时，warning 并使用全部默认值，不崩溃
4. apikey.txt 分别存为 UTF-8 BOM / UTF-16 LE / UTF-16 BE / ANSI，均能正确读取且 Key 前无 `U+FEFF`
5. 控制台中文输出无乱码，`✔ ✘ ⚠` 图标正常渲染
6. `embed.ps1 --help > out.txt` 不抛异常，输出文件内无 ANSI 序列

---

### 阶段 3：API 统一层与环境检测

**依赖**：阶段 2

#### 交付物

| 函数 | 说明 |
|------|------|
| `Get-HttpClient` | 单例 HttpClient，超时可配 |
| `Invoke-Api` | 同步 GET/POST/DELETE 封装，返回结构化结果 |
| `Start-Api` | 异步发起请求，返回 Pending（Request + Task） |
| `Complete-ApiResponse` | 解析 HttpResponseMessage 为结构化结果，读完即释放 |
| `Test-QuickEnvironment` | API 探活，区分不可达/Key无效/HTTP错误 |
| `Get-Workspaces` | 获取工作区列表，剥掉外层 `{workspaces:...}` 包装 |
| `Get-Workspace` | 获取单个工作区，剥掉外层 `{workspace:...}` 包装 |
| `Invoke-FullDiagnose` | 深度环境诊断（OS/PS/WSL/Docker/容器/嵌入器），全部不阻断 |

#### 实现要点

**统一错误结构**：
```
@{ Success; StatusCode; Data; Raw; Error; TransportError }
```
- `TransportError=$true` 表示连不上（DNS/连接拒绝/超时）
- `StatusCode=401/403` 表示 Key 无效
- 两者明确区分，是 v3.0 核心修正之一

**Invoke-Api**：
- 用 `HttpRequestMessage` 构造请求，支持 DELETE 带 body（`remove-documents` 需要）
- Body 为 hashtable 时用 `ConvertTo-Json -Depth 8 -Compress` 序列化
- 用 `GetAwaiter().GetResult()` 同步等待（替代 `.Result`，避免 AggregateException 不可读）
- catch 中展开 InnerException，返回 `TransportError=$true`
- `finally` 中 Dispose Request

**异步路径**：
- `Start-Api` 只发起不阻塞，返回 `@{ Request; Task }`
- 取结果前**必须先判 `$Task.IsFaulted` / `$Task.IsCanceled`**（v4.0 B3）：HttpClient 超时会让 Task 进入 Canceled，直接取结果会抛 AggregateException
- `Complete-ApiResponse` 读完响应体后立即 Dispose HttpResponseMessage（v4.0：批量场景防累积）

**Test-QuickEnvironment**：
- `GET /api/v1/auth`，超时用 `probeTimeoutSec`（默认 5s）
- TransportError → `unreachable`
- 401/403 → `badkey`
- `authenticated=false` → `badkey`
- 其他 ≥400 → `http`

**Get-Workspaces / Get-Workspace**：
- 明确返回契约：已剥掉外层包装，调用方可直接 `$ws.name` / `$ws.documents`
- 这是 v4.0 一致性修正项

**Invoke-FullDiagnose**：
- 仅在 `--diagnose` 或快速检查失败时调用
- WSL 检测用 `Start-Process -Wait -NoNewWindow` + 整体 5s 超时，防未安装时挂起/弹窗
- Docker 检测不通过 wsl 间接调用（会冷启动 WSL 实例）
- 全部结果写入 Info/Warnings，不参与 Success 判定，不阻断主流程

#### 验证标准

1. AnythingLLM 未启动时运行，报"API 不可达"并自动触发诊断，退出码 1
2. apikey.txt 填错误 Key 时运行，报"API Key 无效（HTTP 401）"，**不得**报成不可达
3. 正常时 `Get-Workspaces` 返回工作区数组，每个对象含 `id`/`name`/`slug`
4. `Get-Workspace -Slug xxx` 返回的对象可直接访问 `.documents`
5. `--diagnose` 5s 内完成，输出 OS/PS/WSL/Docker/API 信息，不阻断
6. API 返回 500 时，错误信息包含响应体详情，而非仅 "500 Internal Server Error"

---

### 阶段 4：终端美化与交互菜单

**依赖**：阶段 2（控制台初始化）

#### 交付物

| 函数 | 说明 |
|------|------|
| `Get-DisplayWidth` | 计算字符串的终端显示宽度（CJK 宽字符计 2） |
| `Format-Fixed` | 按显示宽度截断/补齐，支持 Left/Right/Center 对齐 |
| `Write-Colored` | True Color ANSI 输出 + 16 色降级 |
| `Write-Banner` | 启动横幅 |
| `Write-Section` | 分段标题 |
| `Write-Status` | 状态行（success/error/warning/info） |
| `Write-BoxMessage` | 边框消息框 |
| `Write-SummaryBox` | 汇总框（五类计数） |
| `Wait-TaskWithSpinner` | 轮询 .NET Task + 旋转帧 + 实时耗时 |
| `Write-CountProgress` | 真实计数进度条 |
| `Select-MenuFromList` | 通用方向键菜单（分页+过滤+重定向降级） |
| `Read-FilteredChoice` | 关键字过滤 + 数字选择 |
| `Confirm-Action` | 通用确认提示 |
| `Select-DuplicateAction` | 重复文件处理选择（[S]/[R]/[A] + 应用到全部） |

#### 实现要点

**Get-DisplayWidth**：
- 遍历字符，按 Unicode 区间判断是否为宽字符（CJK 统一表意、韩文、全角形式等）
- 已知局限（v4.0 标注）：未覆盖 CJK 扩展 B（U+20000+）、多数 emoji（U+1F300+）、组合/零宽字符；极端文件名仍可能错位

**Format-Fixed**：
- 先替换 Tab 为空格、Trim
- 循环截断直到显示宽度 ≤ 目标宽度
- 按对齐方式补齐空格
- 所有边框绘制统一使用 `$W = 46` 作为内部内容宽度唯一常量

**Write-Colored**（v3.0 落地 True Color）：
- `$script:UseAnsi` 为 true 时输出 `$esc[38;2;R;G;Bm...$esc[0m`
- `$Rgb` 参数先去空格（v4.0：防 `"0, 255, 255"` 破坏 ANSI 序列）
- 降级时用 `Write-Host -ForegroundColor $FallbackColor`

**Wait-TaskWithSpinner**（v3.0 核心重写）：
- **关键约束**：不在 `Task::Run` 里跑 PowerShell scriptblock（非 PS 线程无 Runspace）
- 正确范式：.NET 自己发起异步 I/O（`SendAsync` 返回 Task），主线程只轮询 `$Task.IsCompleted` 并画旋转帧
- 旋转帧 `| / - \`，每 120ms 刷新，带实时已耗时
- 支持 `TimeoutSec`，超时返回 `@{ TimedOut=$true; ElapsedSec }`
- `finally` 中清除动画行
- 输出重定向时静默（不画帧）

**Write-CountProgress**：
- 百分比 = 真实完成数/总数，不做假进度
- 30 格进度条（`█`/`░`），完成时换行

**Select-MenuFromList**（v3.0 重写 + v4.0 增强）：
- 输入或输出任一重定向 → 降级为 `Read-FilteredChoice` 数字选择（v4.0 B7：防 `SetCursorPosition` 抛 "The handle is invalid"）
- `menuTop` 只在首帧计算一次并缓存到 `$script:MenuTop`（v3.0 P0-8：防每按一次方向键下移 2 行）
- 首帧计算时检查缓冲区高度，越界则清屏重绘
- >20 项时支持分页滚动（`$pageSize=20`、`$viewTop` 可视窗口），`$menuHeight` 与实际绘制行数一致（v4.0：防溢出缓冲区）
- 支持 UpArrow/DownArrow/Home/End/Enter/Escape/F(过滤)
- 高亮行用 Cyan，非高亮用 Gray，所有行通过 `Format-Fixed` 按显示宽度对齐
- 条目 > `FilterThreshold`（默认 20）时自动先进入过滤模式

**Read-FilteredChoice**：
- 输入关键字过滤（`-like "*$kw*"`），直接回车查看全部，Q 取消
- 列出匹配项后用数字选择，回车默认第 1 项

#### 验证标准

1. 菜单中连续按 20 次方向键，菜单位置固定不漂移
2. 中文工作区名（如"产品研发知识库"）渲染时右边界与英文条目对齐
3. 模拟 >20 个工作区，分页显示正常，方向键可翻页，不溢出缓冲区
4. `embed.ps1 file.pdf > out.txt`（输出重定向但键盘可输入）时，菜单降级为数字选择，不抛 IOException
5. spinner 动画在异步请求期间持续显示并带实时秒数，结束后动画行被清除
6. 计数进度条百分比 = 真实完成数/总数
7. `Write-Colored` 在不支持 ANSI 的环境下降级为 16 色，不输出乱码

---

### 阶段 5：文件处理与上传

**依赖**：阶段 3（API 层）、阶段 4（进度显示）

#### 交付物

| 函数 | 说明 |
|------|------|
| `Test-InputFile` | 文件预校验（存在性/白名单/大小/空文件/占用/长路径） |
| `Remove-DuplicatePaths` | 同批次内路径去重 |
| `New-UploadRequest` | 构造 multipart 上传请求（同步/异步共用） |
| `Start-Upload` | 异步发起上传 |
| `Complete-Upload` | 解析上传响应 + 释放资源 |
| `Upload-Document` | 同步便捷包装 |
| `Invoke-UploadWithRetry` | 指数退避重试上传 |

#### 实现要点

**Test-InputFile**：
- 检查顺序：空路径 → 不存在 → 是目录 → 路径过长（>240 字符，防 ReadAllBytes 失败）→ 扩展名白名单 → 无法访问 → 空文件 → 大小超限 → **占用检查**
- 占用检查（v4.0 B8）：必须用 `FileShare.None` 打开文件。阅读器通常以 `FileShare.Read` 打开，用 `'Read'` 会误判为未占用
- 返回 `@{ Ok; Reason; Item }`，拒绝时给出可操作的原因说明

**Remove-DuplicatePaths**：
- 按 `(Get-Item).FullName.ToLowerInvariant()` 去重
- 获取 FullName 失败时回退用原路径小写
- 用 `ArrayList` 收集，避免数组 `+=` 的 O(n²)

**New-UploadRequest**：
- 用 `[IO.File]::Open($FilePath, 'Open', 'Read', 'Read')` 流式打开，避免 100MB 全量入内存
- `StreamContent` + 显式 `Content-Type: application/octet-stream`
- 中文文件名（v3.0 修正）：同时设置
  - `ContentDisposition.FileName` = ASCII 兜底名（`[Text.Encoding]::ASCII.GetString([Text.Encoding]::ASCII.GetBytes($name))`）
  - `ContentDisposition.FileNameStar` = RFC 5987 UTF-8 编码（`[Uri]::EscapeDataString($name)`）
- `MultipartFormDataContent` 只加一个 file 字段（官方接口一次只接受一个文件）
- URL：有 slug 时用 `/api/v1/workspace/$Slug/upload`（一步上传，待实测），否则用 `/api/v1/document/upload`
- 返回 `@{ Request; Form; Stream }`，供调用方释放

**Complete-Upload**：
- 用 `GetAwaiter().GetResult()` 取响应
- 判 `resp.IsSuccessStatusCode` 且 `json.success` 且 `json.documents` 非空
- 从第一个 document 取 `location`（嵌入必须用 location，不能用 id）和 `title`（重复检测依赖）
- `finally` 中释放 Stream / Form / Request / **Response**（v4.0：释放响应体）

**Invoke-UploadWithRetry**：
- 设置 HttpClient 超时为 `uploadTimeoutSec`（默认 300s）
- 循环：`Start-Upload` → `Wait-TaskWithSpinner`（带超时）→ 取结果
- **超时分支必须释放 Stream/Form/Request**（v4.0 B2：防重试期间文件句柄泄漏）
- 失败且 `attempt <= uploadRetryCount` 时，指数退避 `delay = baseDelay * 2^(attempt-1)`，打印 warning 后重试
- 成功或超过重试次数时返回

#### 验证标准

1. 拖入 `.exe` / 目录 / 空文件 / >100MB 文件 / 路径 >240 字符，均被预校验拒绝并给出明确原因，其余文件继续处理
2. 文件被 Acrobat/Word 以读锁打开时，预校验拒绝并提示"文件被占用或无读取权限"
3. 中文文件名（含空格、`&`、`%`）上传成功，AnythingLLM 中显示的文件名正确
4. 上传成功后返回的 `location` 形如 `custom-documents/xxx-<uuid>.json`，`title` 为原始文件名
5. 模拟上传超时（断网），重试 2 次后失败，期间无文件句柄泄漏（句柄数不随重试增长）
6. 同一批次拖入两个完全相同的文件，自动去重只处理一次

---

### 阶段 6：嵌入 / 验证 / 删除 / 测试聊天

**依赖**：阶段 3、阶段 4、阶段 5

#### 交付物

| 函数 | 说明 |
|------|------|
| `Set-WorkspaceEmbedding` | 调用 update-embeddings（adds/deletes） |
| `Confirm-Embedding` | 批量轮询验证嵌入结果 |
| `Remove-Documents` | 物理删除文档（含两步回退） |
| `Send-ChatMessage` | 测试检索（query 模式） |

#### 实现要点

**Set-WorkspaceEmbedding**（v4.0 B1）：
- `$Adds` 和 `$Deletes` 必须给默认值 `@()`
- 否则省略 `-Adds` 时 `@($null)` 会序列化成 `"adds":[null]`，导致 API 报错
- Body：`@{ adds = @($Adds); deletes = @($Deletes) }`
- 超时用 `embedTimeoutSec`（默认 120s，v3.0 新增可配置，原硬编码 60）

**Confirm-Embedding**（v3.0 重构 + v4.0 B4）：
- 参数默认值直接取自 `$script:Config`（v4.0 B4：防调用方漏传导致 verifyTimeoutSec 等配置不生效）
- 用 `HashSet[string]`（OrdinalIgnoreCase）维护待确认 location 集合
- **一次请求批量检查**所有待确认 location（v2.0 是逐个串行，10 文件 = 150s）
- `GET /api/v1/workspace/$Slug`，从 `$r.Data.workspace.documents` 中匹配 location
- 轮询窗口：默认 60s（`verifyTimeoutSec`），间隔从 `verifyBaseIntervalSec`（默认 2s）起 ×1.5 退避，上限 `verifyMaxIntervalSec`（默认 5s）
- 轮询请求失败时记录 `LastError` 并打印可见 warning（不空 catch）
- 返回 `@{ Verified; Pending; AllOk; ElapsedSec; LastError }`
- 耗时用 `TotalSeconds`（v3.0 P0-5：原 `.Seconds` 会把 3m20s 记成 20s）

**Remove-Documents**（v3.0 新增 + v4.0 B6 真正实现两步回退）：
- 第一步：`DELETE /api/v1/system/remove-documents`，body `@{ names = @($Locations) }`
  - 该接口同时从 storage 与向量库清除，并自动解除各工作区关联
- 第一步失败时的回退（v4.0 B6）：
  1. 对每个受影响工作区调用 `Set-WorkspaceEmbedding -Deletes $Locations`（un-embed，只解除关联）
  2. 解除关联后**重试** `remove-documents` 物理删除
  3. 两步都失败则返回 error，说明"已解除关联但物理删除失败"
- 这是「替换旧版本」策略的核心：不能只用 `deletes`（会产生孤儿 json）

**Send-ChatMessage**：
- `POST /api/v1/workspace/$Slug/chat`
- Body：`@{ message; mode = "query"; stream = $false }`
- v3.0 修正：默认 mode 从 `chat` 改为 **`query`**——测试目的是验证刚嵌入的文档能否被检索到，`chat` 模式不保证触发检索
- 返回 `@{ Success; Text = textResponse; Sources = sources }`
- 超时用 `chatTimeoutSec`（默认 300s）

#### 验证标准

1. 上传文件后调用嵌入，60s 内 `Confirm-Embedding` 返回 `AllOk=$true`，Verified 包含所有 location
2. 模拟 `update-embeddings` 返回 200 但文档未关联（上游已知缺陷），轮询超时后返回 `AllOk=$false`，给出可执行建议（检查嵌入器配置/稍后确认/日志路径），不误报成功
3. config 设 `verifyTimeoutSec=30`，验证窗口按 30s 生效，不回落 60
4. 对工作区中已有的同名文档执行「替换」：先 `Remove-Documents`，成功后上传新文档，最终工作区只剩一份新文档，storage 中旧 json 消失，无孤儿
5. 模拟 `remove-documents` 首次失败，自动 un-embed 后重试删除，最终无孤儿 json
6. 测试检索返回 `textResponse` 和 `sources`，sources 中包含刚嵌入的文档 title
7. 省略 `-Adds` 调用 `Set-WorkspaceEmbedding -Deletes`，请求体中 `adds` 为空数组 `[]`，不是 `[null]`

---

### 阶段 7：集成与交付

**依赖**：全部前序阶段

#### 交付物

| 模块 | 说明 |
|------|------|
| `Find-DuplicateDocuments` | 上传前按 title 比对重复文档 |
| `Write-OperationLog` | JSONL 无 BOM 追加日志 |
| `Clear-OldLogs` | 按保留天数清理过期日志 |
| `Main` 主流程 | 六阶段串联 + 异常处理 + 退出码 |
| `EmbedIntoWorkspace.bat` | 入口包装器 |
| `scripts/README.md` | 使用说明文档 |
| 54 项回归测试 | 按设计文档 §8 逐项验证 |

#### 实现要点

**Find-DuplicateDocuments**（v3.0 关键修正）：
- **在上传前执行**（v2.0 放在上传后，选"跳过"时新文件已上传，孤儿 json 当场产生）
- **按 `title` 比对**，不从 location 反推（location 含 uuid，反推永不相等）
- 输入：assignments 数组（含 File/FilePath/Slug/Workspace）+ 每工作区的 documents 缓存
- 对每个 assignment，用 `$a.File.ToLowerInvariant()` 匹配该工作区 documents 中 `$_.title.ToLowerInvariant()`
- 返回重复项列表，每项含 Assignment 和 OldDocs（可能有多份同名）

**Write-OperationLog**（v3.0 重构）：
- JSONL 格式（替代 v2.0 的 `|` 分隔文本，防 Detail 含 `|` 或换行破坏结构）
- 每行一个完整 JSON：`ts / status / workspace / file / location / sec / detail`
- 用 `[IO.File]::AppendAllText($logFile, $entry + "`n", (New-Object System.Text.UTF8Encoding($false)))`
  - **不能用 `Add-Content -Encoding UTF8`**（PS 5.1 会逐行写 BOM，文件中间出现 `EF BB BF`）
- 日志目录自动创建，写日志失败只记 warning 不阻断主流程
- 文件名：`logs/YYYY-MM-DD_embed.jsonl`

**Clear-OldLogs**：
- 按 `logRetentionDays`（默认 30）清理 `*_embed.jsonl` 中 LastWriteTime 早于截止日期的文件
- `--clean-logs` 开关触发，可独立运行（无文件时只清理日志后退出 0）

**Main 主流程**（六阶段串联）：

```
初始化（Config → Console → Assert → Banner）
  → 独立开关（--help / --clean-logs / --diagnose）
  → 参数校验（无文件 → 退出 2）
  → 文件预校验（Test-InputFile，全部失败 → 退出 1）
  → API Key + 快速环境检测（失败 → 退出 1）
  → 获取工作区（0 个 → 退出 1）
  → 模式选择（多文件时 unified / per-file）
  → 阶段一：工作区分配（仅收集，不执行）
  → 阶段二：重复检测（上传前，skip/replace/keep + 应用到全部）
  → 阶段三：批量上传（Invoke-UploadWithRetry + 计数进度）
  → 阶段四：按 slug 分组嵌入（Start-Api + spinner + Task 状态判断）
  → 阶段五：批量验证（Confirm-Embedding）
  → 阶段六：测试检索（仅对验证通过的文件，query 模式）
  → 汇总（五类计数）+ 日志路径
  → 退出码（0 全成功 / 3 部分失败 / 1 全失败）
```

- 汇总五类计数（v4.0 自洽修正）：
  - `Total` = 进入处理流程的原始分配数（`$assignedCount`，排除逐项模式中被放弃的文件）
  - `Uploaded` = 上传成功数
  - `Verified` = 验证通过数
  - `Skipped` = Total - Uploaded - Failed（重复检测中选跳过的）
  - `Failed` = 上传失败数 + (上传成功但验证失败数)
- 嵌入/聊天异步路径取结果前先判 `Task.IsFaulted/IsCanceled`（v4.0 B3，已在阶段 3 实现，主流程调用时必须遵守）
- 入口统一 `try/catch/finally`：catch 打印未处理异常 + ScriptStackTrace，设 code=1；finally 复位 ANSI、等待按键（`-NoPause` 或输入重定向时跳过）
- `$processingMode` 用英文 key（`unified` / `per-file`），不用中文字符串做状态机

**EmbedIntoWorkspace.bat**：
- 首行 `@echo off` + `setlocal EnableExtensions`，第二行 `chcp 65001 >nul 2>&1`（chcp 之前的行全部纯 ASCII）
- 无参数时提示用法并 `exit /b 2`
- 检查 `apikey.txt`（脚本目录或项目根目录），缺失时报错 `exit /b 1`
- **参数逐个重新加引号**：用 `shift` 循环 `"%~1"` 拼接，替代 `%*`（防 `%` 被 cmd 展开、尾反斜杠转义引号）
- 调用 `powershell -ExecutionPolicy Bypass -NoProfile -File "%~dp0embed.ps1" %PSARGS%`
- 捕获 `%errorlevel%`，打印退出码说明（0=成功 1=错误 2=已取消 3=部分失败）
- **正常路径也 `pause`**（v3.0 P0-7：原实现窗口一闪而过，用户看不到汇总）
- `exit /b %RC%` 透传退出码
- 编码：UTF-8 无 BOM + CRLF

**README.md**：
- 工具简介、功能列表
- 环境要求（Windows 10 1903+ / PowerShell 5.1+ / AnythingLLM 本地部署）
- 快速开始（获取 API Key → 放入 apikey.txt → 拖文件到 BAT）
- config.json 各字段说明
- 多文件模式说明
- 重复检测策略说明
- 退出码说明
- 常见问题（API 不可达 / Key 无效 / 嵌入未确认 / 中文乱码 / 文件被占用）
- 日志说明

**54 项回归测试**：
- 按设计文档 §8 逐项执行：环境与编码 7 项 + 环境检测 5 项 + 核心功能 19 项 + 菜单与交互 6 项 + 日志 4 项 + 配置 3 项 + v4.0 追加 10 项 = 54 项
- 每项记录预期结果与实际结果

#### 验证标准

1. 拖拽单个 PDF 到 BAT：不弹模式选择，直接选工作区后完成上传→嵌入→验证，汇总显示 Total=1/Uploaded=1/Verified=1，退出码 0，窗口停留
2. 拖拽多个文件 + 统一模式：选一次工作区，批量上传 + 分组嵌入，全部成功退出码 0
3. 拖拽多个文件 + 逐项模式：每文件选工作区 → 分配预览 → 确认 → 批量执行
4. 同名文件再次拖入：提示 [S]跳过/[R]替换/[A]保留；选"跳过"时**不上传**，汇总 Skipped 计数正确
5. 选"替换"：旧文档从 storage 消失，工作区只剩新文档，无孤儿 json
6. 多个同名文件时支持"应用到全部"，不重复弹窗
7. 任意环节按 ESC：优雅退出，退出码 2
8. 部分文件上传失败：成功的继续嵌入验证，汇总 Failed 计数正确，退出码 3
9. 所有文件上传失败：退出码 1
10. `--clean-logs` 按保留天数清理过期日志，无文件参数时只清理后退出 0
11. `--diagnose` 输出完整环境诊断，不阻断
12. `--no-pause` 时结束不等待按键
13. `--help` 输出用法并退出 0
14. 日志文件为合法 JSONL，无重复 BOM，可被 `jq` 正常解析
15. 正常结束窗口停留，用户可看到汇总框与日志路径
16. BAT 透传 PS 的退出码
17. 54 项测试全部通过

---

## 四、跨阶段硬约束

以下约束在**所有阶段**中必须遵守，每阶段完成后自检：

| # | 约束 | 原因 | 自检方式 |
|---|------|------|---------|
| 1 | PS1 始终 UTF-8 **带 BOM** + CRLF | PS 5.1 无 BOM 时按 ANSI 解析，中文乱码 | 十六进制查首三字节 `EF BB BF` |
| 2 | BAT 始终 UTF-8 **无 BOM** + CRLF | 首行 `chcp 65001` 后 cmd 按 UTF-8 解析 | 十六进制查无 `EF BB BF` |
| 3 | 函数先写骨架（签名 + `throw "not implemented"`）再填充 | 确保脚本始终可解析，便于分阶段验证 | 每阶段结束 `powershell -Command "& { . .\embed.ps1 }"` 无语法错误 |
| 4 | 不依赖 `$args`，所有参数通过 `param()` 显式传入 | 函数内 `$args` 是函数自身参数，脚本级 `$args` 在函数内不可见 | 代码审查 |
| 5 | 异步只用 `SendAsync` 返回的 .NET Task，禁止 `Task::Run` 跑 scriptblock | 非 PS 线程无 Runspace，脚本块执行失败 | 代码审查 |
| 6 | 所有 HttpClient 请求的 Request/Response/Stream/Form 在 `finally` 中 Dispose | 防文件句柄泄漏、端口耗尽、HttpResponseMessage 累积 | 代码审查 + 重试场景句柄数检查 |
| 7 | 取异步 Task 结果前先判 `IsFaulted`/`IsCanceled` | HttpClient 超时后 Task 进入 Canceled，直接取结果抛 AggregateException | 代码审查 |
| 8 | 耗时统计用 `TotalSeconds`，不用 `.Seconds` | `.Seconds` 只取秒部分（0-59），3m20s 会记成 20s | 代码审查 |
| 9 | 日志用 `AppendAllText` + `UTF8Encoding($false)`，不用 `Add-Content -Encoding UTF8` | PS 5.1 的 Add-Content 逐行写 BOM | 十六进制查日志文件中间无 `EF BB BF` |
| 10 | 重复检测按 `title` 比对，在上传前执行 | location 含 uuid 无法反推；上传后选跳过会产生孤儿 | 代码审查 + 功能测试 |

---

## 五、里程碑与验收

| 里程碑 | 完成标志 | 验收人 |
|--------|---------|--------|
| M1（阶段 1+2） | 项目骨架就绪，embed.ps1 可启动，--help 正常，控制台中文无乱码，配置回退生效 | 使用者确认 |
| M2（阶段 3+4） | API 连通正常（区分不可达/Key无效），工作区列表可获取，菜单交互正常（不漂移/中文对齐/重定向降级） | 使用者确认 |
| M3（阶段 5+6） | 单文件上传→嵌入→验证全链路通，替换旧版无孤儿，测试检索返回结果 | 使用者确认 |
| M4（阶段 7） | 拖拽 BAT 完成完整流程，多文件两种模式正常，重复检测/退出码/日志全部正确，54 项测试通过 | 使用者确认 |

每个里程碑完成后，交付物通过 `present_files` 提交，使用者确认后进入下一阶段。

---

## 六、风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| AnythingLLM API 版本差异（如一步上传端点 401） | 快路径不可用 | 默认走标准两步路径（upload + update-embeddings），快路径列为可选且引入前必须实测 |
| `update-embeddings` 返回 200 但未写入关联（上游已知缺陷） | 误报成功 | 强制批量轮询验证，超时给 warning + 可执行建议，不算失败 |
| 大文件嵌入耗时超过 60s | 验证超时误报 | verifyTimeoutSec 可配置（默认 60s），用户可按需调大；超时后提示"文档可能仍在处理中" |
| PS 5.1 与 PS 7 行为差异 | 部分功能在 PS 7 下异常 | 以 PS 5.1 为基准开发，关键路径在 PS 7 下回归验证（测试项 #2） |
| 命令行超长（大量文件拖放，>8191 字符） | cmd 静默截断参数 | BAT 检测 PSARGS 超长时改用清单文件传递（`%TEMP%\embed_files_<pid>.lst`），PS 侧读取清单 |
| 用户系统未配置嵌入器（Embedding Provider） | 文档上传但无法嵌入 | 验证超时提示中明确列出"检查嵌入器配置"作为首要可能原因 |
| CJK 扩展 B / emoji 文件名导致对齐错位 | 菜单边框轻微错位 | 已知局限，标注在 Get-DisplayWidth 注释中；常见文件名（BMP 内 CJK）不受影响 |

---

## 七、版本记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-09-09 | 初始版本，基于设计文档 v4.0 制定 7 阶段实施规划 |

---

*本文档与《EmbedIntoWorkspace-Design-v4.0.md》配合使用。设计文档规定"做什么"和"为什么"，本文档规定"按什么顺序做"和"每阶段怎么验收"。*
