# AnythingLLM 文档嵌入工具 — 设计方案 v4.0

> 拖拽文件到 BAT 脚本 → 自动上传并嵌入选定工作区 → 可选测试聊天
>
> **v4.0 定位**：v3.0 的**评审修正实现版**。基于《v3.0 评审意见》修复 3 处确定性错误（计数/文案矛盾）、9 处代码缺陷（B1–B9，会导致运行错误或产生脏数据）与 6 处一致性/健壮性问题，并落地若干可优化项。变更明细与逐条对照见「§10 版本历史」v4.0 段；v2.0→v3.0 的历史对照保留在「§7 优化清单」。
>
> ⚠️ **实现前必读 §1.5「API 事实基线」** —— 重复检测、删除语义、True Color 三处事实已在 v3.0 纠正，v4.0 继续沿用。

---

## 一、项目概览

### 1.1 功能目标

1. **拖拽即用**：将文件拖入 BAT 脚本，自动完成上传、嵌入、验证全流程
2. **多文件处理模式选择**：拖入多个文件时，提示选择：
   - **统一模式（unified）**：所有文件放入同一工作区（选一次工作区，批量执行）
   - **逐项模式（per-file）**：每个文件分别选择工作区（全部选完后一次性批量嵌入）
3. **工作区选择**：通用方向键菜单（↑↓ 移动 / Enter 确认 / ESC 退出），工作区较多时支持输入过滤
4. **文件预校验**：扩展名白名单、存在性、大小上限、占用/长路径检查，拖入目录或不支持类型直接提示
5. **重复检测（上传前）**：上传前比对工作区已有文档 title，同名文件提示跳过 / 替换 / 全部保留
6. **嵌入验证（批量轮询）**：嵌入后批量轮询工作区文档列表，直到目标文档全部出现（默认 60s 窗口 + 退避）
7. **可选测试**：嵌入完成后询问是否调用本地模型进行测试检索
8. **结构化日志**：每次操作记录到 `logs/YYYY-MM-DD_embed.jsonl`（JSONL，时间戳 / 文件 / 工作区 / 状态 / 耗时）
9. **外部配置**：`config.json` 可配置 baseUrl、超时、文件白名单、聊天开关等，带校验与默认值回退
10. **结果可见**（v3.0）：正常结束也会停留窗口展示汇总，并透传语义化退出码

### 1.2 技术选型

| 组件 | 技术 | 说明 |
|------|------|------|
| 入口脚本 | `.bat`（UTF-8 无 BOM, CRLF） | 拖拽关联、cmd 原生支持 |
| 核心逻辑 | PowerShell `.ps1`（UTF-8 **带 BOM**） | PS 5.1 对无 BOM 的 .ps1 按 ANSI 解析，中文会乱码 |
| API 通信 | **`System.Net.Http.HttpClient` 统一封装** | v3.0：全部接口走同一层，放弃 `Invoke-RestMethod`（见 1.2.1） |
| JSON 解析 | `ConvertFrom-Json` / `ConvertTo-Json` | 原生支持 |
| 终端美化 | 16 色 `Write-Host` 为主 + 可选 24-bit ANSI | v3.0 修正措辞：默认路径不依赖 ANSI；ANSI 仅在检测到支持时启用 |
| 状态图标 | Unicode 符号 (✔ ✘ ⚠ ⬆ ⚙) | 需要 UTF-8 输出链路（见 1.3 编码约定） |
| 进度反馈 | 真实 Task 轮询 + 旋转帧 / 计数进度 | v3.0：彻底移除"假进度条" |

#### 1.2.1 为什么放弃 `Invoke-RestMethod`（v3.0 决策）

v2.0 混用 `Invoke-RestMethod`（探活/嵌入/聊天）与 `HttpClient`（上传）。v3.0 统一为 `HttpClient`，原因：

| 问题 | `Invoke-RestMethod` 表现 |
|---|---|
| 错误无响应体 | 4xx/5xx 时只抛 "500 Internal Server Error"，拿不到 AnythingLLM 的具体原因（如"未配置嵌入器"） |
| 401 与不可达混淆 | 一律抛异常，无法区分「Key 无效」与「服务未启动」（v2.0 §4.5 的 `authenticated=false` 分支永远走不到） |
| `Content-Type` | 在 `-Headers` 中设置受限标头会抛异常，必须用 `-ContentType` |
| PS 5.1 TLS | 默认可能不协商 TLS 1.2，https 场景失败 |
| DELETE + Body | 带请求体的 DELETE 行为不可靠、易踩坑（`remove-documents` 需要） |
| 超时行为 | PS 5.1 的 `-TimeoutSec` 行为不一致 |

统一后收益：错误可读、状态码可控、超时一致、可加统一重试、可带进度上报。

### 1.3 编码约定（全链路 UTF-8）

| 文件 | 编码 | 原因 |
|------|------|------|
| `EmbedIntoWorkspace.bat` | UTF-8 无 BOM + CRLF | 首行 `chcp 65001` 后 cmd 按 UTF-8 解析后续中文行 |
| `embed.ps1` | UTF-8 **带 BOM** + CRLF | PS 5.1 只有带 BOM 才会按 UTF-8 解析脚本 |
| `apikey.txt` | 自动检测（BOM/UTF-16LE/UTF-16BE/UTF-8/ANSI） | 读取时按字节检测，不依赖固定编码 |
| `config.json` | UTF-8 无 BOM（允许注释，解析前剥离） | 手写配置常加注释，`ConvertFrom-Json` 在 PS 5.1 不支持 |
| `logs/*.jsonl` | UTF-8 **无 BOM** 追加 | PS 5.1 的 `Add-Content -Encoding UTF8` 会重复写 BOM，必须绕开 |
| 控制台 | `chcp 65001` + 输入/输出编码均为 UTF-8 | **两处必须一致**，否则中文花屏；v3.0 补上 `InputEncoding` |

### 1.4 兼容性要求

- **操作系统**：Windows 10 1903+（ANSI 虚拟终端）；低于此版本自动降级为纯文本 + 数字菜单，功能不受影响
- **PowerShell**：5.1+（Windows 自带；脚本同时兼容 PS 7）
- **AnythingLLM**：本地部署，默认地址 `http://localhost:3001`（可用 `config.json` 覆盖）
- **运行方式**：Docker 或桌面版均可——环境检测不再把 Docker 缺失当作阻断项
- **输出重定向**：`> file.txt` / 管道调用时自动降级，不抛异常（v3.0 新增保证）

### 1.5 AnythingLLM API 事实基线（v3.0 新增，实现前必读）

v2.0 的三处设计建立在错误前提上，此处给出核实后的事实：

| 事实 | 说明 | 对设计的影响 |
|---|---|---|
| **document location 格式** | `custom-documents/<原始文件名含扩展名>-<uuid>.json`<br>例：`custom-documents/报告.pdf-75d8cdc3-846c-4d2d-ac44-7e69e34cb0ef.json` | **不能**从 location 反推文件名去比对 `doc.title`。重复检测必须用 `title` |
| **`update-embeddings` 的 `deletes` 语义** | 仅为 **un-embed**：解除文档与工作区的关联，**文件仍留在 storage**，不会回收磁盘 json | 「替换」不能只靠 `deletes`，否则孤儿 json 越积越多 |
| **物理删除接口** | `DELETE /api/v1/system/remove-documents`，body `{"names":["custom-documents/xxx.json"]}`。<br>该操作会**同时**从 storage 与向量库清除，并自动解除各工作区的关联 | 「替换」= 先 `remove-documents`，失败时回退 `deletes` + `remove-documents` |
| **`adds` / `deletes` 字段名** | 正确，无需修改 | — |
| **一步上传端点** | `POST /api/v1/workspace/{slug}/upload`（multipart）存在，可省一次 `update-embeddings` | 列为 v3.0 可选快路径（§5.6），**引入前必须实测**，已有报告称旧路径 `/api/workspace/:slug/upload` 返回 401（注意 `/api/v1/` 前缀） |

> 落地前建议用本地 Swagger（`http://localhost:3001/api/docs/`）再核对一遍版本差异，上述行为可能有版本间变动。

---

## 二、文件结构

```
AnythingLLM-Tools/                        ← v3.0：去掉 v2.0 的 Linux 风格路径
├── tools/
│   ├── EmbedIntoWorkspace.bat            ← BAT 包装器（UTF-8 无 BOM, CRLF）
│   ├── embed.ps1                         ← 核心 PowerShell 脚本（UTF-8 带 BOM）
│   ├── config.json                       ← 外部配置（可选，缺省用内置默认值）
│   ├── apikey.txt                        ← API Key 文件
│   └── logs/                             ← 操作日志目录（自动创建）
│       └── YYYY-MM-DD_embed.jsonl
├── apikey.txt                            ← 项目根目录备选 Key
├── scripts/
│   ├── fix-bat-encoding.ps1              ← 仅处理 .bat：转 UTF-8 无 BOM + CRLF
│   ├── fix-ps1-encoding.ps1              ← v3.0 新增：仅处理 .ps1：转 UTF-8 带 BOM + CRLF
│   └── README.md                         ← v3.0 新增：使用说明
└── docs/
    ├── EmbedIntoWorkspace-Design.md           ← v1.2（存档）
    ├── EmbedIntoWorkspace-Design-v2.0.md      ← v2.0（存档）
    ├── EmbedIntoWorkspace-Design-v2.0-评审意见.md ← 评审记录
    └── EmbedIntoWorkspace-Design-v3.0.md      ← 本文档
```

> **v3.0 修正**：v2.0 让 `fix-bat-encoding.ps1` 同时承担「BAT → 无 BOM」与「PS1 → 带 BOM」两个**目标相反**的任务（§2 / §8 测试项 3 / §9 依赖表自相矛盾），极易把 PS1 转成无 BOM 而破坏中文。v3.0 拆成两个脚本，各自目标单一。
>
> 修复后处理可用 `Set-Content -Encoding` 或 `[IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($isPs1)))` 精确控制 BOM。

`config.json`（可选，缺省时全部走内置默认值）：

```json
{
  "baseUrl": "http://localhost:3001",
  "probeTimeoutSec": 5,
  "uploadTimeoutSec": 300,
  "embedTimeoutSec": 120,
  "chatTimeoutSec": 300,
  "apiTimeoutSec": 60,

  "verifyTimeoutSec": 60,
  "verifyBaseIntervalSec": 2,
  "verifyMaxIntervalSec": 5,

  "uploadRetryCount": 2,
  "uploadRetryBaseDelaySec": 2,

  "allowedExtensions": [".pdf", ".docx", ".doc", ".txt", ".md", ".csv", ".xlsx", ".pptx"],
  "maxFileSizeMB": 100,

  "defaultWorkspace": "",
  "askForChatTest": true,
  "chatMode": "query",
  "detectDuplicates": true,
  "duplicateDefaultAction": "ask",

  "logDir": "logs",
  "logRetentionDays": 30,

  "useTrueColor": true
}
```

> v3.0 新增 `embedTimeoutSec`（原来硬编码 60）、`probeTimeoutSec`、`apiTimeoutSec`、`verifyTimeoutSec`（替代 `verifyMaxTries`+`verifyIntervalSec` 的固定窗口）、`uploadRetryCount`、`chatMode`、`duplicateDefaultAction`、`logRetentionDays`、`useTrueColor`。

---

## 三、BAT 包装器设计

**文件**：`EmbedIntoWorkspace.bat`
**编码**：UTF-8 无 BOM + CRLF

```bat
@echo off
setlocal EnableExtensions

REM ---- 第一行即切换代码页，其后所有中文按 UTF-8 解析 ----
chcp 65001 >nul 2>&1

REM 检查是否拖入文件
if "%~1"=="" (
    echo 请将文件拖拽到此脚本上运行。
    echo.
    echo 支持的文件类型：PDF, DOCX, TXT, MD, CSV 等
    echo 支持一次拖入多个文件。
    echo.
    pause
    exit /b 2
)

REM 检查 apikey.txt 是否存在
if not exist "%~dp0apikey.txt" (
    if not exist "%~dp0..\apikey.txt" (
        echo [错误] 未找到 apikey.txt
        echo 请在脚本目录或项目根目录放置 apikey.txt
        echo.
        pause
        exit /b 1
    )
)

REM ---- 逐个重新加引号拼装，避免 %* 在特殊字符 / 超长命令行下出错 ----
set "PSARGS="
:argloop
if "%~1"=="" goto argdone
set "PSARGS=%PSARGS% "%~1""
shift
goto argloop
:argdone

powershell -ExecutionPolicy Bypass -NoProfile -File "%~dp0embed.ps1" %PSARGS%
set "RC=%errorlevel%"

echo.
echo ===== 执行结束，退出码 %RC% （0=成功 1=错误 2=已取消 3=部分失败）=====
pause
exit /b %RC%
```

**关键点（v3.0 变更）**：

1. **正常路径也 `pause`**（v2.0 缺失，P0-7）：v2.0 在 `powershell -File` 返回后直接 `exit /b`，窗口一闪而过，用户看不到汇总框、日志路径和验证警告。v3.0 统一在末尾暂停。
2. **参数重新拼装**（P2-5）：`%*` 对含 `%`（被 cmd 展开）、末尾反斜杠（`\"` 转义引号）的路径不安全。改用 `shift` 循环逐个 `"%~1"` 拼接。
   > 若命令行超过 8191 字符（大量文件拖放），cmd 会静默截断。兜底方案：检测到 `PSARGS` 超长时，改为把路径逐行写入 `%TEMP%\embed_files_<pid>.lst`，PS 侧读取清单文件。
3. **`setlocal EnableExtensions`**：避免污染调用方环境变量。
4. **`chcp 65001` 之前的行全部为纯 ASCII**：`@echo off` 与 `chcp` 行，无解析风险。
5. **退出码语义化**：`0`=成功 / `1`=错误 / `2`=用户取消 / `3`=部分失败，由 PS 透传。
6. `%~dp0` 获取 BAT 所在目录；`pause` 防止窗口自动关闭。

> 原则：**BAT 只做三件事** —— 切代码页、转调 PS、展示退出码并停留。所有中文提示与业务逻辑都在 `.ps1` 里，把 cmd 的多字节解析风险面降到最小。

---

## 四、PowerShell 核心脚本设计

**文件**：`embed.ps1`
**编码**：UTF-8 带 BOM（必须！PS 5.1 无 BOM 时按 ANSI 解析，中文注释与字符串全部乱码）

### 4.1 模块划分

```powershell
# ============================================================
# embed.ps1 — AnythingLLM 文档嵌入工具 v3.0
# 编码：UTF-8 (BOM) | 终端：chcp 65001 + 可选 ANSI True Color
# 调用：powershell -ExecutionPolicy Bypass -File embed.ps1 <file...> [--diagnose] [--clean-logs]
# 退出码：0 成功 / 1 错误 / 2 用户取消 / 3 部分失败
# ============================================================

param(
    [string[]]$Files,          # 拖拽的文件列表（位置参数）
    [switch]$Diagnose,         # v3.0：深度环境诊断（不阻断）
    [switch]$CleanLogs,        # v3.0：清理过期日志
    [switch]$NoPause,          # v3.0：自动化调用时不等待按键
    [switch]$Help
)

#region ========== 配置与初始化 ==========
function Initialize-Console { ... }        # 编码 + ANSI 探测/降级 + 重定向降级
function Read-Config { ... }               # config.json 合并内置默认值 + 校验
function Assert-Config { ... }             # v3.0：配置合法性校验与回退
function Read-ApiKey { ... }               # BOM/UTF-16LE/UTF-16BE/UTF-8/ANSI 自动检测
#endregion

#region ========== 终端美化 ==========
function Get-DisplayWidth { ... }          # v3.0：东亚宽字符显示宽度
function Format-Fixed { ... }              # v3.0：按显示宽度补齐/截断
function Write-Banner { ... }
function Write-Section { ... }
function Write-Status { ... }
function Write-BoxMessage { ... }
function Wait-TaskWithSpinner { ... }   # v3.0：轮询 .NET Task + 旋转帧（不在后台跑 scriptblock）
function Write-CountProgress { ... }    # v3.0：真实计数进度（替代彩虹假进度）
function Write-SummaryBox { ... }          # v3.0：四类计数（v2.0 未实现）
#endregion

#region ========== API 统一层（v3.0 新增） ==========
function Get-HttpClient { ... }            # 单例 HttpClient
function Invoke-Api { ... }                # GET/POST/DELETE 同步封装，返回结构化结果
function Start-Api { ... }                 # 异步发起（返回 Pending：Request + Task）
function Complete-ApiResponse { ... }      # 把 HttpResponseMessage 解析为结构化结果
function Test-QuickEnvironment { ... }     # 仅 API 探活（区分 401 与不可达）
function Invoke-FullDiagnose { ... }       # OS/PS/WSL/Docker/容器，仅警告
function Get-Workspaces { ... }        # 返回裸工作区数组（已剥掉 {workspaces:...} 包装）
function Get-Workspace { ... }         # 返回裸工作区对象（已剥掉 {workspace:...} 包装），供 $ws.documents 直接访问
function New-UploadRequest { ... }         # 构造 multipart 请求（同步/异步共用）
function Start-Upload { ... }              # 异步发起上传，返回 Pending
function Complete-Upload { ... }           # 解析上传响应 + 释放资源
function Upload-Document { ... }           # 同步便捷包装
function Invoke-UploadWithRetry { ... }    # 指数退避重试
function Set-WorkspaceEmbedding { ... }
function Remove-Documents { ... }          # v3.0：物理删除（remove-documents）
function Confirm-Embedding { ... }         # v3.0：批量轮询 + 退避
function Send-ChatMessage { ... }
#endregion

#region ========== 文件处理 ==========
function Test-InputFile { ... }            # 存在性/白名单/大小/占用/长路径
function Remove-DuplicatePaths { ... }     # v3.0：同批次内去重
function Find-DuplicateDocuments { ... }   # v3.0：按 title 比对，上传前执行
#endregion

#region ========== 交互菜单 ==========
function Select-MenuFromList { ... }       # 通用方向键菜单（固定 menuTop + 宽度统一）
function Read-FilteredChoice { ... }       # v3.0：关键字过滤 / 重定向时的数字选择
function Confirm-Action { ... }
function Select-DuplicateAction { ... }    # v3.0：跳过/替换/保留（支持应用到全部）
#endregion

#region ========== 日志 ==========
function Write-OperationLog { ... }        # v3.0：JSONL 无 BOM 追加
function Clear-OldLogs { ... }             # v3.0：按 logRetentionDays 清理
#endregion

#region ========== 主流程 ==========
function Main { param(...) ... }           # 返回退出码，由入口统一 exit
#endregion

# 启动（显式传入文件列表，函数内不可依赖 $args！）
$code = Main -Files $Files -Diagnose:$Diagnose -CleanLogs:$CleanLogs
if (-not $NoPause) { ... }
exit $code
```

> ⚠️ **v1.2 的致命坑（v3.0 仍须注意）**：`Main` 函数体内不可直接使用 `$args`——函数内的 `$args` 是**该函数的参数**（无参调用 = 空数组），拖拽的文件列表在**脚本作用域**的 `$args` 里。v3.0 用脚本级 `param([string[]]$Files)` + `Main -Files $Files`。

### 4.2 初始化流程（v3.0 修正版）

```powershell
function Initialize-Console {
    # 1. 窗口标题
    try { $host.UI.RawUI.WindowTitle = "AnythingLLM 嵌入工具 v3.0" } catch { }

    # 2. 能力探测：v3.0 不再依赖 $Host.UI.SupportsVirtualTerminal（PS 5.1 无此属性）
    $script:IsOutputRedirected = [Console]::IsOutputRedirected
    $script:IsInputRedirected  = [Console]::IsInputRedirected
    $script:UseAnsi = $false

    if ($script:Config.UseTrueColor -and -not $script:IsOutputRedirected) {
        try {
            if (-not ('Win32.Kernel32V3' -as [type])) {
                $sig = @'
using System.Runtime.InteropServices;
[DllImport("kernel32.dll", SetLastError = true)]
public static extern IntPtr GetStdHandle(int nStdHandle);
[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool GetConsoleMode(IntPtr hConsoleHandle, out uint lpMode);
[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool SetConsoleMode(IntPtr hConsoleHandle, uint dwMode);
'@
                Add-Type -MemberDefinition $sig -Name 'Kernel32V3' -Namespace 'Win32' `
                         -PassThru -ErrorAction Stop | Out-Null
            }
            $k32     = [Win32.Kernel32V3]
            $handle  = $k32::GetStdHandle(-11)          # STD_OUTPUT_HANDLE
            $mode    = [uint32]0
            if ($k32::GetConsoleMode($handle, [ref]$mode)) {
                $script:UseAnsi = [bool]$k32::SetConsoleMode($handle, $mode -bor 0x0004)
            }
        } catch { $script:UseAnsi = $false }
    }

    # 3. 输入/输出编码：必须与控制台代码页(chcp 65001)一致
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [Console]::OutputEncoding = $utf8NoBom
    if (-not $script:IsInputRedirected) { [Console]::InputEncoding = $utf8NoBom }

    # 4. PS 5.1 TLS：baseUrl 为 https 时必需
    try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }

    # 5. 窗口大小（尽力而为）
    if (-not $script:IsOutputRedirected) {
        try { $host.UI.RawUI.WindowSize = New-Object System.Management.Automation.Host.Size(90, 45) } catch { }
    }

    # 6. 清屏
    if (-not $script:IsOutputRedirected) { Clear-Host }
}
```

**v3.0 相对 v2.0 的修正**：

| v2.0 问题 | v3.0 处理 |
|---|---|
| `$Host.UI.SupportsVirtualTerminal` 在 PS 5.1 **不存在**（返回 `$null`，`-not $null` 恒真，注释"1903+ 检测"是错的） | 删除该判断，改为**直接尝试 P/Invoke 并以 `SetConsoleMode` 返回值判定** |
| 输出重定向时 `GetStdHandle(-11)` 无效，ANSI 序列会被写进文件 | 加 `[Console]::IsOutputRedirected` 判断，重定向时强制纯文本 |
| 输入重定向时后续 `ReadKey` 抛异常 | 探测 `IsInputRedirected`，菜单自动降级为数字选择（§4.9） |
| `Add-Type` 每次启动编译 1–3s，同会话二次调用同名类型报错 | 加 `('Win32.Kernel32V3' -as [type])` 存在性判断 |
| 只设 `OutputEncoding`，中文输入会乱码 | 补 `[Console]::InputEncoding` |
| https 场景可能 TLS 协商失败 | 固定 TLS 1.2 |

> ANSI 转义符：PS 5.1 **没有** `` `e ``（PS 6+ 才有），必须用 `$esc = [char]27`。

### 4.3 配置读取与校验

```powershell
function Read-Config {
    $defaults = [ordered]@{
        BaseUrl                 = "http://localhost:3001"
        ProbeTimeoutSec         = 5
        UploadTimeoutSec        = 300
        EmbedTimeoutSec         = 120
        ChatTimeoutSec          = 300
        ApiTimeoutSec           = 60
        VerifyTimeoutSec        = 60
        VerifyBaseIntervalSec   = 2
        VerifyMaxIntervalSec    = 5
        UploadRetryCount        = 2
        UploadRetryBaseDelaySec = 2
        AllowedExtensions       = @(".pdf",".docx",".doc",".txt",".md",".csv",".xlsx",".pptx")
        MaxFileSizeMB           = 100
        DefaultWorkspace        = ""
        AskForChatTest          = $true
        ChatMode                = "query"
        DetectDuplicates        = $true
        DuplicateDefaultAction  = "ask"
        LogDir                  = "logs"
        LogRetentionDays        = 30
        UseTrueColor            = $true
    }

    $configPath = Join-Path $PSScriptRoot "config.json"
    if (Test-Path -LiteralPath $configPath) {
        $raw = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8
        # v3.0：剥离 // 与 /* */ 注释（PS 5.1 的 ConvertFrom-Json 不支持注释）
        $raw = [regex]::Replace($raw, '/\*.*?\*/', '', 'Singleline')
        $raw = [regex]::Replace($raw, '(^|\s)//.*$', '$1', 'Multiline')
        try {
            $fromFile = $raw | ConvertFrom-Json
        } catch {
            Write-Warning "config.json 解析失败，使用全部默认配置：$($_.Exception.Message)"
            return $defaults
        }
        foreach ($p in $fromFile.PSObject.Properties) {
            # v3.0：显式做 key 规范化，不依赖 hashtable 的大小写不敏感
            $k = $defaults.Keys | Where-Object { $_ -ieq $p.Name } | Select-Object -First 1
            if ($k) { $defaults[$k] = $p.Value }
        }
    }
    return $defaults
}

function Assert-Config {
    param([hashtable]$Config)
    $warn = @()

    if ($Config.BaseUrl -notmatch '^https?://') { $warn += "baseUrl 非法，回退默认"; $Config.BaseUrl = "http://localhost:3001" }
    $Config.BaseUrl = $Config.BaseUrl.TrimEnd('/')

    foreach ($t in @('ProbeTimeoutSec','UploadTimeoutSec','EmbedTimeoutSec','ChatTimeoutSec','ApiTimeoutSec','VerifyTimeoutSec')) {
        $v = [int]$Config[$t]
        if ($v -le 0) { $warn += "$t 非正数，回退 60"; $Config[$t] = 60 }
    }
    # v4.0：轮询间隔按各自默认值回退，不能统一回退 60（否则验证轮询间隔变成 60s）
    if ([int]$Config.VerifyBaseIntervalSec -le 0) { $warn += "verifyBaseIntervalSec 非正数，回退 2"; $Config.VerifyBaseIntervalSec = 2 }
    if ([int]$Config.VerifyMaxIntervalSec  -le 0) { $warn += "verifyMaxIntervalSec 非正数，回退 5";  $Config.VerifyMaxIntervalSec  = 5 }
    if ([int]$Config.MaxFileSizeMB -le 0) { $warn += "maxFileSizeMB 非正数，回退 100"; $Config.MaxFileSizeMB = 100 }

    # 白名单补齐前导点并转小写
    $Config.AllowedExtensions = @($Config.AllowedExtensions | ForEach-Object {
        $e = "$_".ToLowerInvariant()
        if (-not $e.StartsWith('.')) { $e = ".$e" }
        $e
    })

    if ($Config.ChatMode -notin @('chat','query')) { $Config.ChatMode = 'query' }
    if ($Config.DuplicateDefaultAction -notin @('ask','skip','replace','keep')) { $Config.DuplicateDefaultAction = 'ask' }

    # LogDir 允许绝对路径
    if (-not [IO.Path]::IsPathRooted($Config.LogDir)) {
        $Config.LogDir = Join-Path $PSScriptRoot $Config.LogDir
    }
    return $warn
}
```

### 4.4 API Key 读取（v3.0 修正版）

```powershell
function Read-ApiKey {
    param([string]$ScriptDir)

    $keyPaths = @(
        (Join-Path $ScriptDir "apikey.txt"),
        (Join-Path (Split-Path $ScriptDir -Parent) "apikey.txt")
    )
    $keyPath = $keyPaths | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $keyPath) { throw "未找到 apikey.txt（已查找：$($keyPaths -join ' / ')）" }

    $bytes = [System.IO.File]::ReadAllBytes($keyPath)

    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        # UTF-8 BOM：跳过 3 字节
        $apiKey = [Text.Encoding]::UTF8.GetString($bytes, 3, $bytes.Length - 3)
    }
    elseif ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE) {
        # UTF-16 LE BOM：v3.0 必须跳过 2 字节，否则 Key 前会带 U+FEFF（Trim 去不掉）
        $apiKey = [Text.Encoding]::Unicode.GetString($bytes, 2, $bytes.Length - 2)
    }
    elseif ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF) {
        # UTF-16 BE（v3.0 新增）
        $apiKey = [Text.Encoding]::BigEndianUnicode.GetString($bytes, 2, $bytes.Length - 2)
    }
    else {
        # 无 BOM：先按 UTF-8 严格解码，失败则回退 ANSI/GBK（记事本默认编码）
        $strict = New-Object System.Text.UTF8Encoding($false, $true)
        try { $apiKey = $strict.GetString($bytes) }
        catch { $apiKey = [Text.Encoding]::Default.GetString($bytes) }
    }

    # Trim：去掉 BOM 残留、空白与所有控制字符
    $apiKey = ($apiKey -replace '^[\s\uFEFF\u200B]+', '') -replace '[\s\uFEFF\u200B]+$', ''
    if ([string]::IsNullOrEmpty($apiKey)) { throw "apikey.txt 内容为空：$keyPath" }
    return $apiKey
}
```

**v3.0 修正**：

1. **UTF-16 LE 分支去掉 BOM 字节**（P1-4）：v2.0 的 `GetString($bytes)` 把 `FF FE` 也解码了，得到前导 `U+FEFF`。而 .NET 中 `U+FEFF` 的 `IsWhiteSpace` 为 `false`，`Trim()` 去不掉 → **该场景下认证必然失败**。
2. 新增 UTF-16 BE 与 ANSI/GBK 兜底。
3. 函数内 `exit 1` 改为 `throw`（v2.0 写法让函数不可复用、不可测试），由 `Main` 统一捕获并转退出码。
4. `Trim` 改为正则，显式处理 `U+FEFF` / `U+200B`。

### 4.5 环境检测

**原则**：AnythingLLM 可以 Docker 运行，也可以桌面版/npm 原生运行。**阻断项只有两个**：API 可达、API Key 有效。WSL / Docker / 容器检测全部为信息或警告，绝不阻断主流程。

**快速检查（每次启动必跑，≤5 秒）**：

```powershell
function Test-QuickEnvironment {
    param([string]$ApiKey)

    $r = Invoke-Api -Method GET -Path "/api/v1/auth" -ApiKey $ApiKey `
                    -TimeoutSec $script:Config.ProbeTimeoutSec
    if ($r.TransportError) {
        return @{ Success = $false; Kind = "unreachable"
                  Message = "API 不可达: $($script:Config.BaseUrl)（$($r.Error)）" }
    }
    if ($r.StatusCode -eq 401 -or $r.StatusCode -eq 403) {
        return @{ Success = $false; Kind = "badkey"
                  Message = "API Key 无效（HTTP $($r.StatusCode)），请检查 apikey.txt" }
    }
    if ($r.StatusCode -ge 400) {
        return @{ Success = $false; Kind = "http"
                  Message = "探活失败 HTTP $($r.StatusCode): $($r.Error)" }
    }
    if (-not $r.Data.authenticated) {
        return @{ Success = $false; Kind = "badkey"
                  Message = "API Key 无效（authenticated=false）" }
    }
    return @{ Success = $true; Message = "API 连接成功: $($script:Config.BaseUrl)" }
}
```

> **v3.0 修正（P1-2）**：v2.0 用 `Invoke-RestMethod` + `try/catch`，Key 无效时 AnythingLLM 返回 **401/403** 并抛异常 → 一律进 `catch` → 报成「API 不可达」。而 §5.1 设计的 `authenticated=false` 分支**永远走不到**。v3.0 通过统一 API 层拿到真实状态码，区分 `unreachable` / `badkey` / 其他 HTTP 错误，各自给出可操作提示。

**深度诊断（`--diagnose` 参数，或快速检查失败时提示执行，全部不阻断）**：

```powershell
function Invoke-FullDiagnose {
    # 1. 操作系统：Get-CimInstance Win32_OperatingSystem → 信息
    # 2. PowerShell：$PSVersionTable.PSVersion → 信息
    # 3. WSL：wsl --version → 警告
    #    ⚠️ 未安装 WSL 时该命令可能弹窗/挂起：用 Start-Process -Wait -NoNewWindow
    #       并设置整体超时 5s，超时即判定"未安装"
    # 4. Docker：docker --version → 警告（桌面版无 Docker 也可运行）
    #    ⚠️ 不要通过 wsl 间接调用（会冷启动 WSL 实例，耗时数秒）
    # 5. 容器：docker ps -f name=<pattern> → 信息（精确过滤，避免误匹配）
    # 6. API 地址 / 认证 / 工作区数量 / 是否配置嵌入器 → 信息
    # 全部项只写入 Info/Warnings，不参与 Success 判定
}
```

> 调用外部命令一律用**参数数组 + `Start-Process`/`&`**，禁止 `Invoke-Expression` 拼字符串。

### 4.6 API 统一层（v3.0 新增，替代散落的 `Invoke-RestMethod`）

```powershell
function Get-HttpClient {
    if (-not $script:Http) {
        $script:Http = New-Object System.Net.Http.HttpClient
        $script:Http.Timeout = [TimeSpan]::FromSeconds($script:Config.ApiTimeoutSec)
    }
    return $script:Http
}

function Invoke-Api {
    param(
        [ValidateSet('GET','POST','DELETE')] [string]$Method,
        [string]$Path,
        [string]$ApiKey = $script:ApiKey,
        [object]$Body = $null,          # hashtable → JSON；DELETE 也支持
        [int]$TimeoutSec = 0            # 0 = 用全局默认
    )

    $client = Get-HttpClient
    if ($TimeoutSec -gt 0) { $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec) }

    $uri  = "$($script:Config.BaseUrl.TrimEnd('/'))$Path"
    $req  = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::$Method, $uri)
    if ($ApiKey) { $req.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $ApiKey) }

    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 8 -Compress
        $req.Content = New-Object System.Net.Http.StringContent($json, (New-Object System.Text.UTF8Encoding($false)), "application/json")
    }

    try {
        $resp = $client.SendAsync($req).GetAwaiter().GetResult()   # 不用 .Result，避免 AggregateException
        return (Complete-ApiResponse -Response $resp)
    } catch {
        $ex = $_.Exception
        while ($ex -is [System.AggregateException] -and $ex.InnerException) { $ex = $ex.InnerException }
        return @{ Success = $false; StatusCode = 0; Data = $null; Raw = $null
                  Error = $ex.Message; TransportError = $true }
    } finally {
        $req.Dispose()
    }
}

# ---- 异步版本：供 Wait-TaskWithSpinner 使用（见 §4.7.3） ----

function Complete-ApiResponse {
    param([System.Net.Http.HttpResponseMessage]$Response)
    $text = $Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $data = $null
    if (-not [string]::IsNullOrWhiteSpace($text)) {
        try { $data = $text | ConvertFrom-Json } catch { $data = $null }
    }
    $result = @{
        Success        = $Response.IsSuccessStatusCode
        StatusCode     = [int]$Response.StatusCode
        Data           = $data
        Raw            = $text
        Error          = if ($Response.IsSuccessStatusCode) { $null } else { "HTTP $([int]$Response.StatusCode): $text" }
        TransportError = $false
    }
    # v4.0：读完响应体即释放，避免批量场景 HttpResponseMessage 累积
    try { $Response.Dispose() } catch { }
    return $result
}

# 只发起不阻塞，返回 Pending 对象；主线程可轮询 .Task 并画动画
function Start-Api {
    param(
        [ValidateSet('GET','POST','DELETE')] [string]$Method,
        [string]$Path,
        [string]$ApiKey = $script:ApiKey,
        [object]$Body = $null,
        [int]$TimeoutSec = 0
    )
    $client = Get-HttpClient
    if ($TimeoutSec -gt 0) { $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec) }

    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::$Method,
               "$($script:Config.BaseUrl.TrimEnd('/'))$Path")
    if ($ApiKey) { $req.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $ApiKey) }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 8 -Compress
        $req.Content = New-Object System.Net.Http.StringContent($json, (New-Object System.Text.UTF8Encoding($false)), "application/json")
    }
    return @{ Request = $req; Task = $client.SendAsync($req) }
}
```

异步调用范式（**不要在 `Task::Run` 里跑 PowerShell 脚本块**，非 PS 线程没有 Runspace）：

```powershell
$p = Start-Api -Method POST -Path "/api/v1/workspace/$slug/update-embeddings" -Body @{ adds = $locs }
$w = Wait-TaskWithSpinner -Task $p.Task -Activity "嵌入到「$wsName」"
# v4.0：HttpClient 超时会让 Task 进入 Canceled，先判状态再取结果
$result = if ($w.TimedOut)      { @{ Success = $false; Error = "超时" } }
          elseif ($p.Task.IsFaulted -or $p.Task.IsCanceled) { @{ Success = $false; Error = "请求已中止（超时/连接中断）" } }
          else { Complete-ApiResponse -Response $p.Task.GetAwaiter().GetResult() }
$p.Request.Dispose()
```

要点：

- **统一错误结构**：`TransportError` 区分"连不上"与"服务端拒绝"，供 §4.5 精确判定。
- **`GetAwaiter().GetResult()`**（P1-5）：v2.0 的 `.Result` 抛 `AggregateException`，`Message` 只有 "One or more errors occurred."，日志和提示完全不可读。
- **异步路径取结果前先判 `Task` 状态**（v4.0）：`HttpClient.Timeout` 到期会让 `SendAsync` 的 Task 进入 `Canceled`（`IsCompleted=true`），spinner 会误判为"正常结束"；先查 `IsFaulted`/`IsCanceled` 再取结果，避免裸抛 `AggregateException`。
- **`ConvertTo-Json -Depth 8`**：避免嵌套结构被截断为字符串。
- **`SendAsync` + `HttpRequestMessage`**：支持 DELETE 带 body（`remove-documents` 需要）。
- 单例 HttpClient 复用，避免端口耗尽。
- **每请求超时与共享 `HttpClient.Timeout`**（v4.0 提示）：`HttpClient.Timeout` 是实例级共享状态，当前串行流程可用；若未来并行，应改用 `CancellationTokenSource` 做每请求超时，避免互相覆盖。

### 4.7 终端美化（v3.0 修正版）

#### 4.7.1 显示宽度（v3.0 新增，解决中文错位）

> v2.0 的全部对齐都用 `PadRight`，按**字符数**补齐；而东亚字符在终端占 **2 列**，中文标题/工作区名的右边界必然错位。菜单还存在顶边框 33 字符、底边框 46 字符、内容行 48 字符三处不等的问题。

```powershell
function Get-DisplayWidth {
    param([string]$s)
    if ([string]::IsNullOrEmpty($s)) { return 0 }
    $w = 0
    foreach ($ch in $s.ToCharArray()) {
        $c = [int]$ch
        $wide = ($c -ge 0x1100 -and $c -le 0x115F) -or
                ($c -ge 0x2E80 -and $c -le 0xA4CF) -or
                ($c -ge 0xAC00 -and $c -le 0xD7A3) -or
                ($c -ge 0xF900 -and $c -le 0xFAFF) -or
                ($c -ge 0xFE30 -and $c -le 0xFE6F) -or
                ($c -ge 0xFF00 -and $c -le 0xFF60) -or
                ($c -ge 0xFFE0 -and $c -le 0xFFE6)
        $w += if ($wide) { 2 } else { 1 }
    }
    return $w
}

# v4.0 已知局限：上表未覆盖 CJK 扩展 B（U+20000–2FFFD）、多数 emoji（U+1F300+）
# 与组合/零宽字符（U+0300 等）；极端文件名仍可能错位，如需精确宽度可引入完整 UAX#11 表。
function Format-Fixed {
    param([string]$Text, [int]$Width, [string]$Align = 'Left')
    $t = ($Text -replace "`t", ' ').Trim()
    while ((Get-DisplayWidth $t) -gt $Width -and $t.Length -gt 0) {
        $t = $t.Substring(0, $t.Length - 1)
    }
    $pad = $Width - (Get-DisplayWidth $t)
    if ($pad -le 0) { return $t }
    switch ($Align) {
        'Right' { return (' ' * $pad) + $t }
        'Center' {
            $l = [math]::Floor($pad / 2); $r = $pad - $l
            return (' ' * $l) + $t + (' ' * $r)
        }
        default { return $t + (' ' * $pad) }
    }
}
```

所有边框绘制统一为：

```powershell
$W = 46                                    # 内部内容宽度（唯一常量）
$top = '┌' + ('─' * $W) + '┐'
$row = '│' + (Format-Fixed $content $W) + '│'
$bot = '└' + ('─' * $W) + '┘'
```

#### 4.7.2 配色（v3.0 修正 True Color 措辞）

v2.0 声称 ANSI 24-bit True Color，但实现全用 `Write-Host -ForegroundColor`（16 色），§4.6 算出的 `$colorCode` **定义后从未使用**。v3.0 二选一：

```powershell
function Write-Colored {
    param([string]$Text, [string]$Rgb = "0,255,255", [string]$FallbackColor = "Cyan")
    if ($script:UseAnsi) {
        $esc = [char]27                     # PS 5.1 无 `e 转义符
        $rgbNorm = ($Rgb -replace '\s', '') # v4.0：去空格，避免 "0, 255, 255" 破坏 ANSI 序列
        Write-Host "$esc[38;2;$($rgbNorm)m$Text$esc[0m" -NoNewline
    } else {
        Write-Host $Text -ForegroundColor $FallbackColor -NoNewline
    }
}
```

`Write-Section` / `Write-Banner` / 菜单一律改用 `Write-Colored`，`$FallbackColor` 保证降级。§1.2 技术选型表同步改为「16 色为主 + 可选 24-bit ANSI」。

#### 4.7.3 进度与动画（v3.0 重写）

> **v2.0 的 P0-6**：`Write-Spinner` 自身是 `while($true)` 阻塞循环。PowerShell 单线程，调用它就没法同时上传——动画只能在上传前后空转，**上传期间屏幕是静止的**，设计目标根本达不成。

**关键约束**：不要试图用 `[Task]::Run({ <scriptblock> })` 把 PowerShell 代码丢到后台线程——非 PS 线程没有 Runspace，脚本块会执行失败或行为异常。正确做法是**让 .NET 自己发起异步 I/O（`SendAsync` 返回 `Task`），主线程只轮询这个 Task 并画帧**——全程不需要后台运行 PowerShell 代码。

```powershell
# 轮询任意 .NET Task，期间绘制旋转帧 + 实时耗时
function Wait-TaskWithSpinner {
    param(
        [Parameter(Mandatory)][System.Threading.Tasks.Task]$Task,
        [string]$Activity,
        [int]$TimeoutSec = 0
    )

    $frames = @('|','/','-','\'); $i = 0
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $show = -not $script:IsOutputRedirected

    try {
        while (-not $Task.IsCompleted) {
            if ($TimeoutSec -gt 0 -and $sw.Elapsed.TotalSeconds -gt $TimeoutSec) {
                if ($show) { Write-Host "" }
                return @{ TimedOut = $true; ElapsedSec = [int]$sw.Elapsed.TotalSeconds }
            }
            if ($show) {
                Write-Host ("`r  {0} {1}  ({2}s)   " -f $frames[$i % 4], $Activity, [int]$sw.Elapsed.TotalSeconds) -NoNewline
            }
            Start-Sleep -Milliseconds 120
            $i++
        }
        return @{ TimedOut = $false; ElapsedSec = [int]$sw.Elapsed.TotalSeconds }
    } finally {
        if ($show) { Write-Host ("`r" + (' ' * 78) + "`r") -NoNewline }   # 清除动画行
    }
}
```

```powershell
function Write-CountProgress {
    param([int]$Current, [int]$Total, [string]$Label)
    if ($script:IsOutputRedirected) { return }
    $pct = if ($Total -gt 0) { [int](($Current / $Total) * 100) } else { 0 }
    $barLen = 30
    $filled = [math]::Round($barLen * $pct / 100)
    $bar = ('█' * $filled) + ('░' * ($barLen - $filled))
    Write-Host ("`r  {0} [{1}] {2}/{3} ({4}%)   " -f $Label, $bar, $Current, $Total, $pct) -NoNewline
    if ($Current -ge $Total) { Write-Host "" }
}
```

原则（沿用 v2.0，但实现到位）：

- **上传/嵌入**（时长不确定）→ `Wait-TaskWithSpinner` + 实时已耗时
- **批处理计数**（3/10 个文件）→ `Write-CountProgress`，百分比 = 真实完成数/总数
- **不做假字节进度**。若需要真实字节级进度，用自定义 `HttpContent` 在 `SerializeToStreamAsync` 中回调上报（列为可选增强，见 §10）

### 4.8 文件预校验与重复检测（v3.0 重构：前置 + 按 title 比对）

#### 4.8.1 文件预校验

```powershell
function Test-InputFile {
    param([string]$FilePath)

    if ([string]::IsNullOrWhiteSpace($FilePath)) { return @{ Ok = $false; Reason = "空路径" } }
    if (-not (Test-Path -LiteralPath $FilePath))  { return @{ Ok = $false; Reason = "文件不存在" } }
    if (Test-Path -LiteralPath $FilePath -PathType Container) { return @{ Ok = $false; Reason = "是目录，不是文件" } }

    # v3.0：长路径（ReadAllBytes 会失败）
    if ($FilePath.Length -gt 240) { return @{ Ok = $false; Reason = "路径过长（>240 字符）：$FilePath" } }

    $ext = [IO.Path]::GetExtension($FilePath).ToLowerInvariant()
    if ($ext -notin $script:Config.AllowedExtensions) {
        return @{ Ok = $false; Reason = "不支持的类型: $ext（允许: $($script:Config.AllowedExtensions -join ', ')）" }
    }

    try {
        $fi = Get-Item -LiteralPath $FilePath -ErrorAction Stop
    } catch { return @{ Ok = $false; Reason = "无法访问: $($_.Exception.Message)" } }

    if ($fi.Length -eq 0) { return @{ Ok = $false; Reason = "文件为空" } }

    $sizeMB = $fi.Length / 1MB
    if ($sizeMB -gt $script:Config.MaxFileSizeMB) {
        return @{ Ok = $false; Reason = "文件过大: $([math]::Round($sizeMB,1)) MB（上限 $($script:Config.MaxFileSizeMB) MB）" }
    }

    # v4.0：占用检查（大 PDF 常被 Office/阅读器锁住）
    # 必须用 FileShare.None —— 阅读器通常以 FileShare.Read 打开，用 'Read' 会误判为未占用
    try {
        $fs = [IO.File]::Open($fi.FullName, 'Open', 'Read', 'None')
        $fs.Close(); $fs.Dispose()
    } catch { return @{ Ok = $false; Reason = "文件被占用或无读取权限" } }

    return @{ Ok = $true; Item = $fi }
}

function Remove-DuplicatePaths {
    param([string[]]$Paths)
    $seen = @{}; $out = New-Object System.Collections.ArrayList
    foreach ($p in $Paths) {
        try { $k = (Get-Item -LiteralPath $p).FullName.ToLowerInvariant() } catch { $k = $p.ToLowerInvariant() }
        if ($seen.ContainsKey($k)) { continue }
        $seen[$k] = $true
        [void]$out.Add($p)
    }
    return @($out)
}
```

#### 4.8.2 重复检测（v3.0 关键修正）

**修正点**：

1. **按 `title` 比对，不从 location 反推**（P0-1）。location 形如 `custom-documents/报告.pdf-<uuid>.json`，反推得到 `报告.pdf-<uuid>`，与 title `报告.pdf` **永不相等** → v2.0 的 `Find-DuplicateDocuments` 形同虚设。
2. **在上传前执行**（P0-3）。v2.0 放在上传之后，用户选「跳过」时新文件已上传，孤儿 json 当场产生。

```powershell
# 上传前：用本地文件名比对工作区已有文档的 title
function Find-DuplicateDocuments {
    param(
        [object[]]$Assignments,          # 含 File(文件名) / FilePath / Slug / Workspace
        [hashtable]$WorkspaceDocs        # slug -> documents[]（每工作区只取一次）
    )

    $result = New-Object System.Collections.ArrayList
    foreach ($a in $Assignments) {
        $docs = @($WorkspaceDocs[$a.Slug])
        $key  = $a.File.ToLowerInvariant()
        $old  = @($docs | Where-Object { $_.title -and $_.title.ToLowerInvariant() -eq $key })
        if ($old.Count -gt 0) {
            [void]$result.Add([pscustomobject]@{
                Assignment = $a
                OldDocs    = $old          # 同名可能有多份，全部列出
            })
        }
    }
    return @($result)
}

# 替换策略：v3.0 起必须物理删除，不能只用 update-embeddings 的 deletes
# v4.0：真正实现"两步回退"——先 un-embed 解除关联，再重试物理删除
function Remove-Documents {
    param(
        [string]$ApiKey,
        [string[]]$Locations,
        [string[]]$Slugs = @()      # 需要先解除关联的工作区（可选）
    )

    # remove-documents 会从 storage + 向量库清除，并自动解除各工作区关联
    $r = Invoke-Api -Method DELETE -Path "/api/v1/system/remove-documents" `
                    -ApiKey $ApiKey -Body @{ names = @($Locations) } `
                    -TimeoutSec $script:Config.ApiTimeoutSec
    if ($r.Success) { return @{ Success = $true } }

    # 第一步回退：先 un-embed 解除各工作区关联（deletes 只解除关联，不删文件）
    foreach ($slug in $Slugs) {
        $d = Set-WorkspaceEmbedding -ApiKey $ApiKey -Slug $slug -Deletes $Locations
        if (-not $d.Success) {
            return @{ Success = $false; Error = "删除失败且解除关联失败：$($r.Error) / $($d.Error)" }
        }
    }

    # 第二步回退：解除关联后重试物理删除，避免残留孤儿 json
    $r2 = Invoke-Api -Method DELETE -Path "/api/v1/system/remove-documents" `
                     -ApiKey $ApiKey -Body @{ names = @($Locations) } `
                     -TimeoutSec $script:Config.ApiTimeoutSec
    if ($r2.Success) { return @{ Success = $true } }
    return @{ Success = $false; Error = "物理删除失败（已解除关联）：$($r2.Error)" }
}
```

```powershell
function Select-DuplicateAction {
    param([string]$FileName, [int]$OldCount)

    if ($script:Config.DuplicateDefaultAction -ne 'ask') {
        return $script:Config.DuplicateDefaultAction
    }
    Write-Host ""
    Write-Host "  ⚠ 「$FileName」在工作区中已有 $OldCount 份同名文档" -ForegroundColor Yellow
    Write-Host "    [S] 跳过  [R] 替换（删除旧版后嵌入）  [A] 全部保留（会产生重复向量）" -ForegroundColor Gray
    $key = [Console]::ReadKey($true).Key
    switch ($key) {
        'S' { return 'skip' }
        'R' { return 'replace' }
        'A' { return 'keep' }
        default { return 'keep' }
    }
}
```

> 「替换」= `Remove-Documents -Locations @(old.location)`（自动解除关联）→ 成功后继续上传新文件。
> 若 `remove-documents` 失败，回退为 `Set-WorkspaceEmbedding -Deletes @(old.location)` 再重试删除；两步都失败则记为 warning 并询问是否继续。

### 4.9 通用选择菜单（v3.0 重写）

**v2.0 的三个问题（P0-8 / P0-9 / P2-2）**：

1. `menuTop` 每帧用当前 `CursorTop` 反推 → 每按一次方向键菜单整体下移 2 行，最终溢出屏幕
2. 顶边框 33 字符 / 底边框 46 字符 / 内容行 48 字符，三处宽度不等；`PadRight` 按字符数补齐，中文标签必然错位
3. 输入重定向时 `ReadKey` 抛异常

```powershell
function Select-MenuFromList {
    param(
        [object[]]$Items,
        [string]$Title,
        [scriptblock]$DisplayLabel,
        [int]$FilterThreshold = 20
    )

    if (-not $Items -or $Items.Count -eq 0) { return $null }

    # v4.0：输入或输出任一重定向 → 降级为数字选择。
    # 输出重定向时 [Console]::CursorTop/SetCursorPosition 会抛 "The handle is invalid"
    if ($script:IsInputRedirected -or $script:IsOutputRedirected) {
        return Read-FilteredChoice -Items $Items -Title $Title -DisplayLabel $DisplayLabel
    }

    # 条目过多 → 先走关键字过滤
    if ($Items.Count -gt $FilterThreshold) {
        $r = Read-FilteredChoice -Items $Items -Title $Title -DisplayLabel $DisplayLabel
        if ($r) { return $r }
        # 用户选择直接浏览时继续下方方向键菜单
    }

    $W       = 46                                   # 内容宽度（唯一常量）
    $selected = 0
    $total    = $Items.Count
    $pageSize = 20                                  # v4.0：每页最多显示条目数
    $viewTop  = 0                                   # 可视窗口起始下标
    $menuHeight = 5 + [math]::Min($total, $pageSize) # 边框2 + 空行 + 条目 + 提示

    Write-Host ""
    # v3.0：menuTop 只在首帧计算一次并缓存（P0-8）
    $top = [Console]::CursorTop
    $buffer = $host.UI.RawUI.BufferHeight
    if ($top + $menuHeight -ge $buffer -or $top -lt 1) { Clear-Host; $top = 1 }
    $script:MenuTop = $top

    function Draw-Menu {
        param([int]$Highlight, [int]$From)
        $visible = [math]::Min($total - $From, $pageSize)
        [Console]::SetCursorPosition(0, $script:MenuTop)
        Write-Host ('┌' + (Format-Fixed " $Title" $W) + '┐') -ForegroundColor DarkGray
        Write-Host ('│' + (' ' * $W) + '│') -ForegroundColor DarkGray

        for ($i = 0; $i -lt $visible; $i++) {
            $idx   = $From + $i
            $label = & $DisplayLabel $Items[$idx]
            $mark  = if ($idx -eq $Highlight) { ' ▶ ' } else { '   ' }
            $inner = Format-Fixed ($mark + $label) $W       # 按显示宽度截断/补齐
            [Console]::SetCursorPosition(0, $script:MenuTop + 2 + $i)
            if ($idx -eq $Highlight) { Write-Host ('│' + $inner + '│') -ForegroundColor Cyan }
            else                     { Write-Host ('│' + $inner + '│') -ForegroundColor Gray  }
        }

        [Console]::SetCursorPosition(0, $script:MenuTop + 2 + $visible)
        Write-Host ('└' + ('─' * $W) + '┘') -ForegroundColor DarkGray
        $hint = if ($total -gt $pageSize) { "  [↑↓] 移动  [Enter] 确认  [ESC] 退出  [F] 过滤  ($($From+1)-$($From+$visible)/$total)" }
                else                      { "  [↑↓] 移动  [Enter] 确认  [ESC] 退出  [F] 过滤" }
        Write-Host $hint -ForegroundColor DarkGray
    }

    Draw-Menu -Highlight $selected -From $viewTop

    while ($true) {
        if ([Console]::KeyAvailable) {
            $key = [Console]::ReadKey($true)
            switch ($key.Key) {
                'UpArrow'   { $selected = ($selected - 1 + $total) % $total
                              if ($selected -lt $viewTop) { $viewTop = $selected }
                              elseif ($selected -ge $viewTop + $pageSize) { $viewTop = $selected - $pageSize + 1 }
                              Draw-Menu -Highlight $selected -From $viewTop }
                'DownArrow' { $selected = ($selected + 1) % $total
                              if ($selected -lt $viewTop) { $viewTop = $selected }
                              elseif ($selected -ge $viewTop + $pageSize) { $viewTop = $selected - $pageSize + 1 }
                              Draw-Menu -Highlight $selected -From $viewTop }
                'Home'      { $selected = 0; $viewTop = 0
                              Draw-Menu -Highlight $selected -From $viewTop }
                'End'       { $selected = $total - 1; $viewTop = [math]::Max(0, $total - $pageSize)
                              Draw-Menu -Highlight $selected -From $viewTop }
                'F'         { $r = Read-FilteredChoice -Items $Items -Title $Title -DisplayLabel $DisplayLabel
                              if ($r) { return $r }
                              Draw-Menu -Highlight $selected -From $viewTop }
                'Enter'     { Write-Host ""; return $Items[$selected] }
                'Escape'    { Write-Host ""; return $null }
            }
        }
        Start-Sleep -Milliseconds 15
    }
}

function Read-FilteredChoice {
    param([object[]]$Items, [string]$Title, [scriptblock]$DisplayLabel)
    Write-Host ""
    Write-Host "  $Title（共 $($Items.Count) 项）— 输入关键字过滤，直接回车查看全部，Q 取消" -ForegroundColor Gray
    $kw = Read-Host "  关键字"
    if ($kw -eq 'Q' -or $kw -eq 'q') { return $null }

    $matched = if ([string]::IsNullOrWhiteSpace($kw)) { $Items }
               else { @($Items | Where-Object { (& $DisplayLabel $_) -like "*$kw*" }) }
    if ($matched.Count -eq 0) { Write-Host "  无匹配项" -ForegroundColor Yellow; return $null }

    for ($i = 0; $i -lt $matched.Count; $i++) {
        Write-Host ("  [{0,2}] {1}" -f ($i + 1), (& $DisplayLabel $matched[$i]))
    }
    $sel = Read-Host "  选择序号（回车=第 1 项）"
    if ([string]::IsNullOrWhiteSpace($sel)) { return $matched[0] }
    $idx = 0
    if ([int]::TryParse($sel, [ref]$idx) -and $idx -ge 1 -and $idx -le $matched.Count) { return $matched[$idx - 1] }
    return $null
}
```

### 4.10 上传文件（v3.0 修正版）

```powershell
# ---- 请求构造：抽出来给同步/异步两条路径共用 ----
function New-UploadRequest {
    param([string]$ApiKey, [string]$FilePath, [string]$Slug = "")

    $fs = [IO.File]::Open($FilePath, 'Open', 'Read', 'Read')   # 流式，避免 100MB 全量入内存
    $name = [IO.Path]::GetFileName($FilePath)

    # v3.0：显式构造 Content-Disposition，同时给 ASCII 兜底名与 RFC5987 的 filename*
    $streamContent = New-Object System.Net.Http.StreamContent($fs)
    $streamContent.Headers.ContentType =
        New-Object System.Net.Http.Headers.MediaTypeHeaderValue("application/octet-stream")
    $cd = New-Object System.Net.Http.Headers.ContentDispositionHeaderValue("form-data")
    $cd.Name = "file"
    $cd.FileName = [Text.Encoding]::ASCII.GetString([Text.Encoding]::ASCII.GetBytes($name))  # 兜底
    $cd.FileNameStar = [Uri]::EscapeDataString($name)                                        # UTF-8
    $streamContent.Headers.ContentDisposition = $cd

    $form = New-Object System.Net.Http.MultipartFormDataContent
    $form.Add($streamContent)

    $url = if ($Slug) { "/api/v1/workspace/$Slug/upload" } else { "/api/v1/document/upload" }
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post,
               "$($script:Config.BaseUrl.TrimEnd('/'))$url")
    $req.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $ApiKey)
    $req.Content = $form

    return @{ Request = $req; Form = $form; Stream = $fs }
}

# ---- 异步发起：只发送不阻塞，主线程可轮询 .Task 画动画 ----
function Start-Upload {
    param([string]$ApiKey, [string]$FilePath, [string]$Slug = "")
    $p = New-UploadRequest -ApiKey $ApiKey -FilePath $FilePath -Slug $Slug
    $p.Task = (Get-HttpClient).SendAsync($p.Request)      # 注意：Timeout 需在此前设置
    return $p
}

function Complete-Upload {
    param([hashtable]$Pending)
    try {
        $resp = $Pending.Task.GetAwaiter().GetResult()
        $body = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $json = if ($body) { $body | ConvertFrom-Json } else { $null }

        if (-not $resp.IsSuccessStatusCode -or -not $json -or -not $json.success) {
            return @{ Success = $false; Error = "HTTP $([int]$resp.StatusCode): $body" }
        }
        $doc = @($json.documents) | Select-Object -First 1
        if (-not $doc) { return @{ Success = $false; Error = "上传响应中无 documents: $body" } }

        return @{
            Success  = $true
            Location = $doc.location       # ← 嵌入必须用 location，不能用 id
            Title    = $doc.title          # ← v3.0：重复检测按 title 比对，必须带回
        }
    } catch {
        $ex = $_.Exception
        while ($ex -is [System.AggregateException] -and $ex.InnerException) { $ex = $ex.InnerException }
        return @{ Success = $false; Error = $ex.Message }
    } finally {
        if ($Pending.Stream)  { $Pending.Stream.Close(); $Pending.Stream.Dispose() }
        if ($Pending.Form)    { $Pending.Form.Dispose() }
        if ($Pending.Request) { $Pending.Request.Dispose() }
        if ($resp)            { $resp.Dispose() }     # v4.0：释放响应体
    }
}

# ---- 同步便捷包装（无需动画时使用）----
function Upload-Document {
    param([string]$ApiKey, [string]$FilePath, [string]$Slug = "")
    (Get-HttpClient).Timeout = [TimeSpan]::FromSeconds($script:Config.UploadTimeoutSec)
    return Complete-Upload -Pending (Start-Upload -ApiKey $ApiKey -FilePath $FilePath -Slug $Slug)
}
```

**v3.0 修正**：

| v2.0 问题 | v3.0 处理 |
|---|---|
| `$client.PostAsync(...).Result` 抛 `AggregateException`，错误信息不可读 | `GetAwaiter().GetResult()` + InnerException 展开 |
| 100MB `ReadAllBytes` 全量入内存，峰值约 2× | 改用 `StreamContent` + `FileStream` |
| 每个文件 `new` 一个 HttpClient（TIME_WAIT/端口耗尽） | 复用单例 `Get-HttpClient` |
| 中文文件名只依赖 `MultipartFormDataContent.Add` 的默认编码 | 显式同时设置 `FileName`（ASCII 兜底）与 `FileNameStar`（RFC 5987） |
| `$form` 未释放 | `finally` 中 Dispose |
| `documents` 为空时 `$doc.location` 裸访问 | 显式判空并给出可读错误 |
| `$AddToWorkspaces` 是死参数 | 删除 |

**上传重试**（网络抖动场景）：

```powershell
function Invoke-UploadWithRetry {
    param([string]$ApiKey, [string]$FilePath, [string]$Slug = "")

    (Get-HttpClient).Timeout = [TimeSpan]::FromSeconds($script:Config.UploadTimeoutSec)
    $attempt = 0
    while ($true) {
        $attempt++
        $name = [IO.Path]::GetFileName($FilePath)

        $p = Start-Upload -ApiKey $ApiKey -FilePath $FilePath -Slug $Slug          # 只发起
        $w = Wait-TaskWithSpinner -Task $p.Task -Activity "上传 $name" `
                                  -TimeoutSec $script:Config.UploadTimeoutSec      # 期间画动画
        # v4.0：超时分支也必须释放 Stream/Form/Request，避免重试期间文件句柄泄漏
        $r = if ($w.TimedOut) {
                 if ($p.Stream)  { $p.Stream.Close(); $p.Stream.Dispose() }
                 if ($p.Form)    { $p.Form.Dispose() }
                 if ($p.Request) { $p.Request.Dispose() }
                 @{ Success = $false; Error = "上传超时（$($w.ElapsedSec)s）" }
             }
             else { Complete-Upload -Pending $p }                                  # 收尾 + 释放资源

        if ($r.Success -or $attempt -gt $script:Config.UploadRetryCount) { return $r }

        $delay = [int]($script:Config.UploadRetryBaseDelaySec * [math]::Pow(2, $attempt - 1))
        Write-Host "  ⚠ 第 $attempt 次上传失败，${delay}s 后重试：$($r.Error)" -ForegroundColor Yellow
        Start-Sleep -Seconds $delay
    }
}
```

### 4.11 嵌入到工作区

```powershell
function Set-WorkspaceEmbedding {
    # v4.0：$Adds 必须给默认空数组；否则省略 -Adds 时 @($null) 会序列化成 "adds":[null]
    param([string]$ApiKey, [string]$Slug, [string[]]$Adds = @(), [string[]]$Deletes = @())

    $r = Invoke-Api -Method POST -Path "/api/v1/workspace/$Slug/update-embeddings" `
                    -ApiKey $ApiKey -TimeoutSec $script:Config.EmbedTimeoutSec `
                    -Body @{ adds = @($Adds); deletes = @($Deletes) }

    if (-not $r.Success) { return @{ Success = $false; Error = $r.Error } }
    return @{ Success = $true; Workspace = $r.Data.workspace; Message = $r.Data.message }
}
```

> v3.0 修正：v2.0 用 `Invoke-RestMethod` 并在 `-Headers` 里设 `Content-Type` —— **PS 5.1 会抛"必须使用相应属性或方法修改该标头"异常，嵌入调用会直接失败**（P0-4）。v3.0 走统一 API 层，超时也可配置（v2.0 硬编码 60s）。
>
> 主流程（§4.15 阶段四）走的是**异步路径**：`Start-Api` → `Wait-TaskWithSpinner` → `Complete-ApiResponse`，以便在嵌入期间显示动画。上面这个同步包装用于不需要动画的场景（如重复检测的回退删除）。

### 4.12 验证嵌入（v3.0：批量 + 退避 + 长窗口）

```powershell
function Confirm-Embedding {
    param(
        [string]$ApiKey,
        [string]$Slug,
        [string[]]$Locations,
        # v4.0：默认值直接取自 config，避免调用方漏传导致 verifyTimeoutSec 等配置失效
        [int]$TimeoutSec = $script:Config.VerifyTimeoutSec,
        [int]$BaseIntervalSec = $script:Config.VerifyBaseIntervalSec,
        [int]$MaxIntervalSec = $script:Config.VerifyMaxIntervalSec
    )

    $pending = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($l in $Locations) { [void]$pending.Add($l) }

    $verified = New-Object System.Collections.ArrayList
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $interval = $BaseIntervalSec
    $lastError = $null

    while ($pending.Count -gt 0) {
        # v3.0：一次请求批量检查所有待确认 location（v2.0 是逐个串行轮询）
        $r = Invoke-Api -Method GET -Path "/api/v1/workspace/$Slug" -ApiKey $ApiKey `
                        -TimeoutSec $script:Config.ApiTimeoutSec
        if ($r.Success -and $r.Data.workspace) {
            $docs = @($r.Data.workspace.documents)
            $found = @($docs | Where-Object { $pending.Contains($_.location) } | ForEach-Object { $_.location })
            foreach ($f in $found) { [void]$pending.Remove($f); [void]$verified.Add($f) }
        } else {
            # v3.0：不再空 catch，记录原因并给出可见反馈
            $lastError = $r.Error
            Write-Host "  ⚠ 轮询请求失败：$($r.Error)" -ForegroundColor DarkYellow
        }

        if ($pending.Count -eq 0) { break }
        if ($sw.Elapsed.TotalSeconds -ge $TimeoutSec) { break }

        Start-Sleep -Seconds $interval
        $interval = [math]::Min($interval * 1.5, $MaxIntervalSec)      # 退避
    }

    return @{
        Verified  = @($verified)
        Pending   = @($pending)
        AllOk     = ($pending.Count -eq 0)
        ElapsedSec = [int]$sw.Elapsed.TotalSeconds                     # v3.0：TotalSeconds
        LastError = $lastError
    }
}
```

**v3.0 修正**：

| v2.0 问题 | v3.0 处理 |
|---|---|
| 固定 5 次 × 3s = 15s 窗口，大 PDF 嵌入常需数十秒 → 大量误报"未确认" | 默认 60s 超时窗口，可配置 |
| 逐个文件串行轮询，10 文件 = 150s | 一次请求批量检查 |
| `catch { }` 空吞异常，静默重试无反馈 | 记录 `LastError` 并打印可见警告 |
| 固定间隔 | 2s 起、×1.5 退避、上限 5s |

**验证失败时的提示必须是可执行的**：

```
⚠ 文档未在 60s 内出现在工作区。可能原因：
  1) 工作区未配置嵌入器（Embedding Provider）→ AnythingLLM 设置页检查
  2) 文档仍在处理中 → 稍后在 UI 中确认，本次不算失败
  3) 上游已知问题：update-embeddings 返回 200 但未写入关联（见 §1.5）
  日志：logs\2026-09-09_embed.jsonl
```

### 4.13 测试聊天

```powershell
function Send-ChatMessage {
    param([string]$ApiKey, [string]$Slug, [string]$Message, [string]$Mode = "query")

    $r = Invoke-Api -Method POST -Path "/api/v1/workspace/$Slug/chat" -ApiKey $ApiKey `
                    -TimeoutSec $script:Config.ChatTimeoutSec `
                    -Body @{ message = $Message; mode = $Mode; stream = $false }

    if (-not $r.Success) { return @{ Success = $false; Error = $r.Error } }
    return @{ Success = $true; Text = $r.Data.textResponse; Sources = @($r.Data.sources) }
}
```

> v3.0 修正：①去掉 `-Headers` 里的 `Content-Type`；②默认 `mode` 由 `chat` 改为 **`query`** —— 测试目的是「验证刚嵌入的文档能否被检索到」，`chat` 模式不保证触发检索，`query` 才是对口的；③只在**验证通过**的文件所在工作区发送（v2.0 取 `$uploaded[0]`，可能正是验证失败的那个）。
>
> 与嵌入同理，主流程走 `Start-Api` + `Wait-TaskWithSpinner` 异步路径；此同步包装保留给非交互场景。

### 4.14 结构化日志（v3.0：JSONL + 无 BOM 追加）

```powershell
function Write-OperationLog {
    param(
        [string]$File,
        [string]$Workspace,
        [string]$Status,          # success / error / warning / skipped
        [double]$DurationSec = 0,
        [string]$Detail = "",
        [string]$Location = ""
    )

    try {
        $logDir = $script:Config.LogDir
        if (-not (Test-Path -LiteralPath $logDir)) {
            New-Item -ItemType Directory -Path $logDir -Force | Out-Null
        }
        $logFile = Join-Path $logDir ("{0:yyyy-MM-dd}_embed.jsonl" -f (Get-Date))

        $entry = [ordered]@{
            ts        = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
            status    = $Status
            workspace = $Workspace
            file      = $File
            location  = $Location
            sec       = [math]::Round($DurationSec, 2)
            detail    = $Detail
        } | ConvertTo-Json -Depth 4 -Compress

        # v3.0：不能用 Add-Content -Encoding UTF8（PS 5.1 会每行写一次 BOM）
        [IO.File]::AppendAllText($logFile, $entry + "`n", (New-Object System.Text.UTF8Encoding($false)))
    } catch {
        Write-Host "  ⚠ 写日志失败（不影响主流程）：$($_.Exception.Message)" -ForegroundColor DarkYellow
    }
}

function Clear-OldLogs {
    param([int]$RetentionDays = 30)
    $logDir = $script:Config.LogDir
    if (-not (Test-Path -LiteralPath $logDir)) { return 0 }
    $cut = (Get-Date).AddDays(-$RetentionDays)
    $old = @(Get-ChildItem -LiteralPath $logDir -Filter "*_embed.jsonl" -File |
             Where-Object { $_.LastWriteTime -lt $cut })
    foreach ($f in $old) { Remove-Item -LiteralPath $f.FullName -Force -ErrorAction SilentlyContinue }
    return $old.Count
}
```

**v3.0 修正**：

- **JSONL 替代 `|` 分隔文本**（P1-8）：v2.0 的 `Detail` 含 `|` 或换行时会破坏结构化。JSONL 每行一个完整 JSON，可直接被 `jq` / PowerShell 消费。
- **无 BOM 追加**（P1-7）：PS 5.1 的 `Add-Content -Encoding UTF8` 是 with-BOM，逐行追加会在文件中间反复插入 `EF BB BF`。改用 `AppendAllText` + `UTF8Encoding($false)`。
- **耗时用 `TotalSeconds` 并保留 2 位小数**（P0-5）：v2.0 传的是 `TimeSpan.Seconds`（秒部分，0–59）。
- **写日志失败不阻断主流程**。
- **补齐 `--clean-logs`**：v2.0 承诺「保留最近 30 天」但从未实现。

### 4.15 主流程（v3.0 完整伪代码）

```powershell
param([string[]]$Files, [switch]$Diagnose, [switch]$CleanLogs, [switch]$NoPause, [switch]$Help)

function Main {
    param([string[]]$Files, [switch]$Diagnose, [switch]$CleanLogs, [switch]$Help)

    # ========== 初始化 ==========
    $script:Config = Read-Config
    Initialize-Console                       # 依赖 Config.UseTrueColor，需在 Read-Config 之后
    foreach ($w in (Assert-Config -Config $script:Config)) { Write-Host "  ⚠ $w" -ForegroundColor Yellow }
    Write-Banner

    # v4.0：补齐 --help（原声明但未实现）
    if ($Help) {
        Write-Host "用法: embed.ps1 <file...> [--diagnose] [--clean-logs] [--no-pause] [--help]"
        Write-Host "  拖入一个或多个文件以批量上传并嵌入选定工作区。"
        Write-Host "  退出码: 0 成功 / 1 错误 / 2 用户取消 / 3 部分失败"
        return 0
    }

    # ========== 独立开关 ==========
    if ($CleanLogs) {
        $n = Clear-OldLogs -RetentionDays $script:Config.LogRetentionDays
        Write-Status -Type "success" -Message "已清理 $n 个过期日志文件"
        if (-not $Files) { return 0 }
    }
    if ($Diagnose) { Invoke-FullDiagnose }

    # ========== 参数校验 ==========
    if (-not $Files -or $Files.Count -eq 0) {
        Write-Status -Type "error" -Message "未拖入任何文件"
        return 2
    }
    $Files = Remove-DuplicatePaths -Paths $Files      # v3.0：同批次去重

    # ========== 文件预校验 ==========
    Write-Section -Title "文件校验" -Color "cyan"
    $validFiles = New-Object System.Collections.ArrayList
    foreach ($f in $Files) {
        $check = Test-InputFile -FilePath $f
        if ($check.Ok) { [void]$validFiles.Add($f); Write-Status -Type "success" -Message "OK  $(Split-Path $f -Leaf)" }
        else           { Write-Status -Type "error"   -Message "跳过 $(Split-Path $f -Leaf)" -Detail $check.Reason }
    }
    if ($validFiles.Count -eq 0) { Write-Status -Type "error" -Message "没有可处理的文件，退出"; return 1 }
    $Files = @($validFiles)

    # ========== API Key + 快速环境检测 ==========
    try { $script:ApiKey = Read-ApiKey -ScriptDir $PSScriptRoot }
    catch { Write-Status -Type "error" -Message $_.Exception.Message; return 1 }

    $envCheck = Test-QuickEnvironment -ApiKey $script:ApiKey
    if (-not $envCheck.Success) {
        Write-Status -Type "error" -Message $envCheck.Message
        if ($envCheck.Kind -eq 'unreachable') { Invoke-FullDiagnose }
        Write-Status -Type "warning" -Message "可运行 --diagnose 查看完整环境诊断"
        return 1
    }
    Write-Status -Type "success" -Message $envCheck.Message

    # ========== 获取工作区 ==========
    $workspaces = @(Get-Workspaces -ApiKey $script:ApiKey)
    if ($workspaces.Count -eq 0) { Write-Status -Type "error" -Message "没有可用的工作区，请先在 AnythingLLM 中创建"; return 1 }
    Write-Status -Type "info" -Message "已加载 $($workspaces.Count) 个工作区"

    # ========== 模式选择（多文件时） ==========
    $processingMode = 'unified'                        # v3.0：英文 key，不用中文做状态机
    if ($Files.Count -gt 1) {
        $mode = Select-MenuFromList `
            -Items @([pscustomobject]@{ Label='统一模式'; Desc='所有文件放入同一工作区'; Key='unified' },
                     [pscustomobject]@{ Label='逐项模式'; Desc='每个文件分别选择工作区'; Key='per-file' }) `
            -Title "检测到 $($Files.Count) 个文件，选择处理模式" `
            -DisplayLabel { "$($_.Label)  —  $($_.Desc)" }
        if (-not $mode) { Write-Status -Type "warning" -Message "用户取消"; return 2 }
        $processingMode = $mode.Key
    }

    # ========== 阶段一：工作区分配（仅收集，不执行） ==========
    $assignments = New-Object System.Collections.ArrayList
    if ($processingMode -eq 'unified') {
        $selected = Select-MenuFromList -Items $workspaces -Title "选择目标工作区" -DisplayLabel { $_.name }
        if (-not $selected) { Write-Status -Type "warning" -Message "用户取消"; return 2 }
        foreach ($f in $Files) {
            [void]$assignments.Add([pscustomobject]@{
                File = Split-Path $f -Leaf; FilePath = $f
                Workspace = $selected.name; Slug = $selected.slug })
        }
    } else {
        foreach ($f in $Files) {
            $selected = Select-MenuFromList -Items $workspaces -Title "为「$(Split-Path $f -Leaf)」选择工作区" -DisplayLabel { $_.name }
            if (-not $selected) { Write-Status -Type "warning" -Message "已放弃该文件"; continue }
            [void]$assignments.Add([pscustomobject]@{
                File = Split-Path $f -Leaf; FilePath = $f
                Workspace = $selected.name; Slug = $selected.slug })
        }
        if ($assignments.Count -eq 0) { Write-Status -Type "warning" -Message "未分配任何文件"; return 2 }
        Write-Section -Title "文件分配预览" -Color "yellow"
        foreach ($a in $assignments) { Write-Host "  $($a.File) → $($a.Workspace)" -ForegroundColor Gray }
        if (-not (Confirm-Action -Prompt "确认开始批量嵌入？")) { return 2 }
    }

    # v4.0：记录进入处理流程的原始分配数（Total 统计口径，排除逐项模式中被放弃的文件）
    $assignedCount = $assignments.Count

    # ========== 阶段二（v3.0 前移）：重复检测，上传前执行 ==========
    if ($script:Config.DetectDuplicates) {
        Write-Section -Title "重复检测" -Color "yellow"
        $wsDocs = @{}
        foreach ($slug in ($assignments | ForEach-Object { $_.Slug } | Select-Object -Unique)) {
            $ws = Get-Workspace -ApiKey $script:ApiKey -Slug $slug
            $wsDocs[$slug] = @($ws.documents)
        }
        $dups = Find-DuplicateDocuments -Assignments @($assignments) -WorkspaceDocs $wsDocs
        $applyAll = $null

        foreach ($d in $dups) {
            $action = if ($applyAll) { $applyAll } else { Select-DuplicateAction -FileName $d.Assignment.File -OldCount $d.OldDocs.Count }
            switch ($action) {
                'skip' {
                    $assignments.Remove($d.Assignment)
                    Write-OperationLog -File $d.Assignment.File -Workspace $d.Assignment.Workspace -Status "skipped" -Detail "同名已存在，用户跳过"
                }
                'replace' {
                    $locs = @($d.OldDocs | ForEach-Object { $_.location })
                    # v4.0：把受影响工作区传给 Remove-Documents，由其内部完成"解除关联 + 重试删除"两步回退
                    $rm = Remove-Documents -ApiKey $script:ApiKey -Locations $locs -Slugs @($d.Assignment.Slug)
                    if ($rm.Success) { Write-Status -Type "success" -Message "已删除旧版本 $($locs.Count) 份" }
                    else {
                        Write-Status -Type "warning" -Message "旧版本删除失败：$($rm.Error)"
                        Write-OperationLog -File $d.Assignment.File -Workspace $d.Assignment.Workspace -Status "warning" -Detail "旧版本删除失败: $($rm.Error)"
                    }
                }
                default { }   # keep：保留两者
            }
            if (-not $applyAll -and $dups.Count -gt 1) {
                if (Confirm-Action -Prompt "将本次选择应用于其余 $($dups.Count - 1) 个重复文件？") { $applyAll = $action }
            }
        }
        if ($assignments.Count -eq 0) { Write-Status -Type "warning" -Message "所有文件均已存在且选择跳过"; return 0 }
    }

    # ========== 阶段三：批量上传 ==========
    Write-Section -Title "上传阶段" -Color "cyan"
    $uploaded = New-Object System.Collections.ArrayList
    $failed   = New-Object System.Collections.ArrayList

    for ($i = 0; $i -lt $assignments.Count; $i++) {
        $a  = $assignments[$i]
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        Write-CountProgress -Current $i -Total $assignments.Count -Label "上传"

        $result = Invoke-UploadWithRetry -ApiKey $script:ApiKey -FilePath $a.FilePath
        $sw.Stop()

        if ($result.Success) {
            $a | Add-Member -NotePropertyName Location -NotePropertyValue $result.Location -Force
            $a | Add-Member -NotePropertyName Title    -NotePropertyValue $result.Title    -Force
            [void]$uploaded.Add($a)
        } else {
            [void]$failed.Add($a)
            Write-Host ""
            Write-Status -Type "error" -Message "上传失败: $($result.Error)"
            Write-OperationLog -File $a.File -Workspace $a.Workspace -Status "error" `
                               -DurationSec $sw.Elapsed.TotalSeconds -Detail $result.Error
        }
    }
    Write-CountProgress -Current $assignments.Count -Total $assignments.Count -Label "上传"
    if ($uploaded.Count -eq 0) { Write-Status -Type "error" -Message "所有文件上传失败，退出"; return 1 }

    # ========== 阶段四：按 slug 分组嵌入 ==========
    Write-Section -Title "嵌入阶段" -Color "green"
    $verifiedAll = New-Object System.Collections.ArrayList
    $grouped = @($uploaded | Group-Object -Property Slug)

    foreach ($group in $grouped) {
        $slug      = $group.Name
        $wsName    = ($group.Group | Select-Object -First 1).Workspace
        $locations = @($group.Group | ForEach-Object { $_.Location })

        Write-Host ""
        # 异步发起 → 主线程轮询 Task 画动画 → 收尾解析（见 §4.6 / §4.7.3）
        $p = Start-Api -Method POST -Path "/api/v1/workspace/$slug/update-embeddings" `
                       -TimeoutSec $script:Config.EmbedTimeoutSec `
                       -Body @{ adds = $locations; deletes = @() }
        $w = Wait-TaskWithSpinner -Task $p.Task -Activity "嵌入到「$wsName」（$($locations.Count) 个文档）"
        # v4.0：HttpClient 超时会让 Task 进入 Canceled，先判状态再取结果，避免 .Result 抛 AggregateException
        $resp = if ($w.TimedOut)      { $null }
                elseif ($p.Task.IsFaulted -or $p.Task.IsCanceled) { $null }
                else { Complete-ApiResponse -Response $p.Task.GetAwaiter().GetResult() }
        $p.Request.Dispose()

        $embed = if ($null -eq $resp)       { @{ Success = $false; Error = "嵌入请求超时或已中止" } }
                 elseif (-not $resp.Success) { @{ Success = $false; Error = $resp.Error } }
                 else { @{ Success = $true; Workspace = $resp.Data.workspace; Message = $resp.Data.message } }

        if (-not $embed.Success) {
            Write-Status -Type "error" -Message "嵌入失败: $($embed.Error)"
            foreach ($item in $group.Group) {
                Write-OperationLog -File $item.File -Workspace $item.Workspace -Status "error" -Detail "嵌入失败: $($embed.Error)"
            }
            continue
        }

        # ========== 阶段五：批量验证 ==========
        Write-Section -Title "验证阶段" -Color "magenta"
        $v = Confirm-Embedding -ApiKey $script:ApiKey -Slug $slug -Locations $locations   # 内部自带轮询与输出

        $verifiedSet = @{}
        foreach ($loc in $v.Verified) { $verifiedSet[$loc] = $true }

        foreach ($item in $group.Group) {
            if ($verifiedSet.ContainsKey($item.Location)) {
                Write-Status -Type "success" -Message "$($item.File) 验证通过"
                [void]$verifiedAll.Add($item)
                # v4.0：$v.ElapsedSec 是整批验证耗时，作为参考时长记录（非单文件耗时）
                Write-OperationLog -File $item.File -Workspace $item.Workspace -Status "success" `
                                   -DurationSec $v.ElapsedSec -Location $item.Location
            } else {
                Write-Status -Type "warning" -Message "$($item.File) 未在 $($v.ElapsedSec)s 内确认" `
                             -Detail "检查工作区嵌入器配置 / AnythingLLM 日志；文档可能仍在处理中"
                Write-OperationLog -File $item.File -Workspace $item.Workspace -Status "warning" `
                                   -DurationSec $v.ElapsedSec -Location $item.Location -Detail "轮询超时未确认"
            }
        }
    }

    # ========== 阶段六：测试检索（仅对验证通过的文件） ==========
    if ($script:Config.AskForChatTest -and $verifiedAll.Count -gt 0) {
        Write-Section -Title "测试" -Color "yellow"
        if (Confirm-Action -Prompt "是否测试检索？") {
            $first = $verifiedAll[0]
            $p = Start-Api -Method POST -Path "/api/v1/workspace/$($first.Slug)/chat" `
                           -TimeoutSec $script:Config.ChatTimeoutSec `
                           -Body @{ message = "请简要介绍最近嵌入的文档内容"; mode = $script:Config.ChatMode; stream = $false }
            $w = Wait-TaskWithSpinner -Task $p.Task -Activity "等待模型响应"
            # v4.0：与嵌入同理，先判 Task 状态再取结果
            $resp = if ($w.TimedOut)      { $null }
                    elseif ($p.Task.IsFaulted -or $p.Task.IsCanceled) { $null }
                    else { Complete-ApiResponse -Response $p.Task.GetAwaiter().GetResult() }
            $chat = if ($null -eq $resp)       { @{ Success = $false; Error = "聊天超时或已中止" } }
                    elseif (-not $resp.Success) { @{ Success = $false; Error = $resp.Error } }
                    else { @{ Success = $true; Text = $resp.Data.textResponse; Sources = @($resp.Data.sources) } }
            $p.Request.Dispose()
            if ($chat.Success) {
                Write-BoxMessage -Title "模型回复" -Lines @($chat.Text) -Footer ($chat.Sources | ForEach-Object { "• $($_.title)" })
            } else {
                Write-Status -Type "error" -Message "检索失败: $($chat.Error)"
            }
        }
    }

    # ========== 汇总（v4.0：五类计数，Total 与子项自洽） ==========
    $stat = [ordered]@{
        Total    = $assignedCount
        Uploaded = $uploaded.Count
        Verified = $verifiedAll.Count
        Skipped  = $assignedCount - $uploaded.Count - $failed.Count
        Failed   = $failed.Count + ($uploaded.Count - $verifiedAll.Count)
    }
    Write-SummaryBox -Stat $stat
    Write-Host ""
    Write-Host "  日志路径: $($script:Config.LogDir)" -ForegroundColor DarkGray

    # ========== 退出码 ==========
    if ($stat.Failed -eq 0) { return 0 }
    if ($stat.Verified -gt 0) { return 3 }      # 部分失败
    return 1
}

# ================= 入口 =================
try {
    $code = Main -Files $Files -Diagnose:$Diagnose -CleanLogs:$CleanLogs -Help:$Help
} catch {
    Write-Host ""
    Write-Host "  ✘ 未处理异常：" -ForegroundColor Red
    Write-Host "    $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "    $($_.ScriptStackTrace)" -ForegroundColor DarkGray
    $code = 1
} finally {
    # 恢复控制台状态，避免异常后终端残留异常编码/颜色
    try { if ($script:UseAnsi) { Write-Host "$([char]27)[0m" -NoNewline } } catch { }
    if (-not $NoPause -and -not [Console]::IsInputRedirected) {
        Write-Host ""
        Write-Host "  按任意键关闭..." -ForegroundColor DarkGray
        [Console]::ReadKey($true) | Out-Null
    }
}
exit $code
```

**v3.0 主流程修正清单**：

| v2.0 问题 | v3.0 处理 |
|---|---|
| 汇总 `$successCount` 遍历 `$uploaded` 判断 `Location`——恒等于 `uploaded.Count`，统计的是**上传成功**而非**验证成功**；`-Total $Files.Count` 又含校验失败文件 | 四类计数：`Total / Uploaded / Verified / Failed` |
| `$processingMode` 用中文字符串比较（`-eq "统一模式"`） | 英文 key `unified` / `per-file` |
| 正常结束无按键等待，窗口一闪而过 | `finally` 中统一等待按键（`-NoPause` 可关闭） |
| 无 `try/finally`，异常后终端状态不恢复 | 入口统一 `try/catch/finally` + ANSI 复位 |
| 取消与成功都返回 0 | `2` = 用户取消，`3` = 部分失败 |
| 重复检测在上传后 | 前移到阶段二 |
| `Wait-TaskWithSpinner` 与上传/嵌入未结合 | 阶段三~六全部通过 spinner 执行 |
| `$sw.Elapsed.Seconds` | `$sw.Elapsed.TotalSeconds` |
| `--diagnose` / `--clean-logs` 只出现在文档里 | `param` 中声明并实现 |

---

## 五、API 工作流（v3.0 更新）

### 5.0 工作区列表（v4.0 补充）

```
GET {baseUrl}/api/v1/workspaces
Header: Authorization: Bearer {api_key}

响应: { "workspaces": [ { "id": "...", "name": "...", "slug": "..." } ] }
```

> §4.15 的 `Get-Workspaces` 依赖此端点；返回数组按 `name`/`slug` 供菜单展示。

### 5.1 探活（唯一阻断项）

```
GET {baseUrl}/api/v1/auth
Header: Authorization: Bearer {api_key}

期望: 200 {"authenticated": true}
401/403 → API Key 无效（v3.0：与"不可达"明确区分）
连接失败/超时 → API 不可达
```

### 5.2 上传文件

```
POST {baseUrl}/api/v1/document/upload
Header: Authorization: Bearer {api_key}
Body: multipart/form-data
  - file = @filepath            ← 每次仅一个 file 字段

响应: {
  "success": true,
  "documents": [{
    "id": "...",
    "location": "custom-documents/报告.pdf-<uuid>.json",
    "title": "报告.pdf"
  }]
}
```

- 嵌入必须用 `location`，不是 `id`（docId）。传 docId 会静默失败。
- **v3.0**：必须同时带回 `title`，重复检测依赖它（不能从 location 反推）。
- 官方接口一次只接受一个 `file` 字段，**不存在官方批量上传端点**（第三方教程提到的 `/api/v1/document/upload/batch` 未经 openapi 证实，不可依赖）。批量 = 循环调用 + 重试。
- 中文文件名：显式设置 `Content-Disposition` 的 `FileName`（ASCII 兜底）与 `FileNameStar`（RFC 5987）。

### 5.3 嵌入到工作区

```
POST {baseUrl}/api/v1/workspace/{slug}/update-embeddings
Header: Authorization: Bearer {api_key}
Content-Type: application/json
Body: {"adds": ["custom-documents/报告.pdf-uuid.json"], "deletes": []}

响应: 200 { "workspace": { "slug": "...", "documents": [...] }, "message": null }
```

- **200 只代表受理**，嵌入异步完成；且上游存在已知缺陷（返回 200 但关联未写入）。必须轮询验证。
- **`deletes` 语义 = un-embed**，仅解除工作区关联，**不删除文件**。

### 5.4 物理删除文档（v3.0 新增）

```
DELETE {baseUrl}/api/v1/system/remove-documents
Header: Authorization: Bearer {api_key}
Content-Type: application/json
Body: {"names": ["custom-documents/报告.pdf-uuid.json"]}

作用：从 storage 与向量库彻底清除，并自动解除各工作区关联
用途：「替换旧版本」策略的第一步
```

> 该接口需要带请求体的 DELETE —— 这是 v3.0 放弃 `Invoke-RestMethod` 改用 `HttpClient` 的直接原因之一。

### 5.5 验证嵌入（批量轮询）

```
GET {baseUrl}/api/v1/workspace/{slug}
Header: Authorization: Bearer {api_key}

判定: workspace.documents[].location ∈ 本次上传的 locations 集合
轮询: 默认 60s 超时窗口，间隔 2s 起 ×1.5 退避，上限 5s
      v3.0：一次请求检查全部待确认 location（v2.0 为逐个串行）
```

### 5.6 测试检索

```
POST {baseUrl}/api/v1/workspace/{slug}/chat
Header: Authorization: Bearer {api_key}
Content-Type: application/json
Body: {"message": "...", "mode": "query", "stream": false}

响应: { "textResponse": "...", "sources": [...] }
```

> v3.0：`mode` 默认改为 `query`（`chat` 模式不保证触发检索，测试嵌入效果时应对口使用 `query`）。

### 5.7 可选快路径（待实测）

```
POST {baseUrl}/api/v1/workspace/{slug}/upload   (multipart, field: file)
一步完成上传 + 嵌入，省掉 update-embeddings
```

仅在**统一模式**下有收益。引入前必须实测：有报告称旧路径 `/api/workspace/:slug/upload`（缺 `/v1`）用 API Key 会返回 401。建议默认关闭，用 `config.json` 开关控制。

---

## 六、风险与规避（v3.0 全量表）

### 6.1 高风险

| 问题 | 症状 | 规避 |
|------|------|------|
| PS 5.1 无 `Invoke-RestMethod -Form` | 上传报"找不到参数 -Form" | 统一 `HttpClient` |
| PS1 无 BOM + PS 5.1 解析 | 中文乱码 | PS1 存 UTF-8 带 BOM；两个独立的编码修复脚本 |
| `Main` 内用 `$args` | 拖拽文件列表丢失 | 脚本级 `param([string[]]$Files)` + `Main -Files $Files` |
| `-Headers` 设 `Content-Type` | PS 5.1 抛异常，嵌入/聊天调用失败 | 统一 API 层，用 `StringContent` 指定 mediaType |
| 从 location 反推文件名 | 重复检测永远命中不了 | 按 `title` 比对 |
| `deletes` 当作删除 | 孤儿 json 越积越多 | 物理删除走 `remove-documents` |
| BAT 内嵌 PowerShell 转义 | 命令被 cmd 展开破坏 | BAT 仅转发参数，逻辑全在 .ps1 |
| 旋转动画阻塞主线程 | 上传期间界面静止 | `Wait-TaskWithSpinner` 轮询 Task（异步 I/O 交给 .NET） |
| 在 `Task::Run` 里跑 scriptblock | 后台线程无 Runspace，脚本块执行失败 | 只在主线程执行 PS 代码，异步部分用 `SendAsync` 的 Task |

### 6.2 中风险

| 问题 | 症状 | 规避 |
|------|------|------|
| 编码不一致 | 中文花屏 | 全链路 UTF-8 + InputEncoding |
| apikey.txt 为 UTF-16 | **认证必然失败** | 跳过 BOM 字节 + UTF-16BE + ANSI 兜底 |
| 中文文件名 multipart | 文件名乱码 | 显式 `FileName` + `FileNameStar` |
| 路径特殊字符 | 空格/中文/`&`/`%`/尾斜杠 | `-LiteralPath` + BAT 逐个重新加引号 |
| 401 被判为不可达 | 提示误导，用户反复排查服务 | 统一 API 层区分 `TransportError` / 状态码 |
| `AggregateException` | 错误信息只有 "One or more errors occurred." | `GetAwaiter().GetResult()` |
| 日志多 BOM | 日志文件中间出现 EF BB BF | `AppendAllText` + `UTF8Encoding($false)` |
| 中文对齐错位 | 边框右边界参差 | `Get-DisplayWidth` + `Format-Fixed` |
| 菜单漂移 | 每按一次方向键下移 | `MenuTop` 首帧固定 |
| 输出重定向 | `ReadKey` 抛异常 / ANSI 写入文件 | `IsOutputRedirected` / `IsInputRedirected` 降级 |
| 大文件内存 | 100MB 峰值 2× | `StreamContent` + FileStream |
| HttpClient 短生命周期 | TIME_WAIT / 端口耗尽 | 单例复用 |

### 6.3 逻辑与健壮性

| 问题 | 症状 | 规避 |
|------|------|------|
| 嵌入 200 假成功 | 返回 200 但文档不在工作区 | 批量轮询 60s + 可执行建议 |
| 重复检测时机错误 | 选"跳过"反而产生孤儿 | 上传前检测 |
| 轮询窗口过短 | 大文件大量误报 | 60s 窗口 + 退避 |
| 逐个串行轮询 | 10 文件 = 150s | 一次请求批量检查 |
| 菜单越界 | `SetCursorPosition` 抛异常 | 首帧计算 + 缓冲区高度判断 |
| 数组 `+=` | 大量文件 O(n²) | `ArrayList` / `List[object]` |
| Docker 误阻断 | 桌面版用户被挡 | 快速检查只保留 API + Key |
| 无文件校验 | 拖入目录/.exe 直接上传 | `Test-InputFile`（含占用/长路径/空文件） |
| 同批次重复路径 | 同一文件嵌入两遍 | `Remove-DuplicatePaths` |
| 耗时统计截断 | 3m20s 记成 20s | `TotalSeconds` |
| 配置非法值 | 静默生效 | `Assert-Config` 校验 + 回退 |

### 6.4 低风险

| 问题 | 场景 | 规避 |
|------|------|------|
| 连接超时 | AnythingLLM 未启动 | 探活失败 + 自动诊断 |
| 上传超时 | 大文件 | `uploadTimeoutSec` 默认 300s + 重试 2 次 |
| ANSI 不支持 | Win10 1903 以下 | 自动降级 16 色 |
| 检索超时 | LLM 推理慢 | `chatTimeoutSec` 默认 300s |
| 日志目录不可写 | 权限受限 | 记 warning，不阻断 |
| 命令行超长 | 大量文件拖放 | 兜底改用清单文件传递 |

---

## 七、优化清单（v2.0 → v3.0 落实对照）

| 级别 | 项 | v2.0 状态 | v3.0 处理 |
|------|---|-----------|-----------|
| **P0** | 重复检测按 location 反推 | ❌ 永远失效 | ✅ 改用 `title` 比对 |
| **P0** | `deletes` 当删除用 | ❌ 产生孤儿 | ✅ `remove-documents` 物理删除 |
| **P0** | 重复检测在上传后 | ❌ 跳过即产生孤儿 | ✅ 前移到上传前 |
| **P0** | `-Headers` 设 Content-Type | ❌ 抛异常 | ✅ 统一 API 层 |
| **P0** | `$sw.Elapsed.Seconds` | ❌ 耗时截断 | ✅ `TotalSeconds` |
| **P0** | Spinner 阻塞主线程 | ❌ 动画无法实现 | ✅ `Wait-TaskWithSpinner` 轮询 Task |
| **P0** | 正常路径无 pause | ❌ 窗口一闪而过 | ✅ finally 统一等待 |
| **P0** | 菜单 menuTop 逐帧漂移 | ❌ 菜单下移 | ✅ 首帧固定 |
| **P0** | 菜单/边框宽度不等 + 中文错位 | ❌ 必然错位 | ✅ 显示宽度函数 + 统一常量 |
| P1 | 401 被判为不可达 | ❌ | ✅ 状态码区分 |
| P1 | UTF-16 BOM 未跳过 | ❌ 必然失败 | ✅ 偏移 2 字节 + BE/ANSI 兜底 |
| P1 | `Add-Content` 多 BOM | ❌ | ✅ `AppendAllText` 无 BOM |
| P1 | `\|` 分隔日志被破坏 | ❌ | ✅ JSONL |
| P1 | AggregateException 不可读 | ❌ | ✅ `GetAwaiter()` |
| P1 | HttpClient 每文件 new | ❌ | ✅ 单例 + 流式 |
| P1 | 轮询 15s + 逐个串行 | ❌ 大量误报 | ✅ 60s + 批量 + 退避 |
| P1 | 中文字符串做状态机 | ⚠️ 脆弱 | ✅ 英文 key |
| P1 | 汇总统计语义错误 | ❌ | ✅ 四类计数 |
| P1 | 未定义函数 / 死参数 | ⚠️ | ✅ 补齐 / 删除 |
| P1 | 重复策略设计与实现矛盾 | ❌ | ✅ `Select-DuplicateAction` + 应用到全部 |
| P1 | 编码修复脚本目标冲突 | ❌ 会破坏 PS1 | ✅ 拆成两个脚本 |
| P1 | `$Host.UI.SupportsVirtualTerminal` | ⚠️ PS 5.1 无此属性 | ✅ 直接 P/Invoke 判定 |
| P1 | `$colorCode` 未使用 / True Color 未落地 | ❌ | ✅ `Write-Colored` + 措辞修正 |
| P1 | 重定向时抛异常 | ❌ | ✅ 全链路降级 |
| P2 | BAT `%*` 特殊字符 | ⚠️ | ✅ 逐个重新加引号 |
| P2 | config 无校验 / 不支持注释 | ⚠️ | ✅ `Assert-Config` + 注释剥离 |
| P2 | 测试聊天 mode / 对象选择 | ⚠️ | ✅ `query` + 取验证通过的 |
| P2 | 退出码语义 | ⚠️ | ✅ 0/1/2/3 |
| P2 | `--clean-logs` 未实现 | ❌ | ✅ 实现 |
| P2 | 重试机制 | ❌ | ✅ 上传重试 2 次 |
| P2 | 真实字节进度 | ⏳ | 列为可选增强（自定义 `HttpContent`） |
| P2 | 文件预校验（空/占用/长路径）+ 同批次去重 | ⚠️ | ✅ `Test-InputFile` + `Remove-DuplicatePaths` |
| P2 | 主流程 `try/catch/finally` | ❌ | ✅ 入口统一异常处理 + 控制台状态恢复 |

---

## 八、测试验证清单（v3.0 共 44 项 + v4.0 追加 10 项）

### 环境与编码

| # | 测试项 | 预期结果 |
|---|--------|----------|
| 1 | Windows PowerShell 5.1 启动 | 中文无乱码，颜色正常 |
| 2 | PowerShell 7 启动 | 同样正常（兼容） |
| 3 | BAT 编码校验 | `chcp 65001` 后中文输出正常 |
| 4 | 控制台代码页与 PS 输入/输出编码一致 | 无花屏，✔✘⚠ 图标正常渲染 |
| 5 | **编码修复脚本** | `.bat` → 无 BOM，`.ps1` → 带 BOM，两者互不影响 |
| 6 | 输出重定向 `> out.txt` | 不抛异常，自动降级纯文本，文件内无 ANSI 序列 |
| 7 | 输入重定向（管道调用） | 菜单自动降级为数字选择 |

### 环境检测

| # | 测试项 | 预期结果 |
|---|--------|----------|
| 8 | Docker 未运行但 API 可达（桌面版） | ✅ 正常通过，Docker 仅诊断警告 |
| 9 | WSL 未安装 | ⚠ 警告，不阻断；`wsl --version` 不挂起 |
| 10 | API 不可达 | ✘ 阻断，提示"API 不可达"并自动诊断 |
| 11 | **API Key 无效（返回 401）** | ✘ 阻断，提示"**API Key 无效**"，**不得**报成不可达 |
| 12 | `--diagnose` | 输出完整环境诊断，全部不阻断，5s 内完成 |

### 核心功能

| # | 测试项 | 预期结果 |
|---|--------|----------|
| 13 | 拖入单个 PDF | 直接进入上传（不弹模式选择） |
| 14 | 多文件 + 统一模式 | 选一次工作区，批量上传 + 分组嵌入 |
| 15 | 多文件 + 逐项模式 | 每文件选工作区 + 分配预览 + 确认 |
| 16 | ESC 取消各环节 | 优雅退出，退出码 **2** |
| 17 | 拖入 .exe / 目录 / 超大文件 / 空文件 | 预校验拒绝，其余文件继续 |
| 18 | 文件被 Office 占用 | 预校验拒绝并说明原因 |
| 19 | 路径 > 240 字符 | 明确报错，非裸异常 |
| 20 | **同名文件再次拖入** | 提示跳过/替换/保留；选"跳过"时**不上传** |
| 21 | **替换旧版本** | 旧文档从 storage 消失，工作区只剩一份新文档，无孤儿 json |
| 22 | 多个同名文件 | 支持"应用到全部"，不重复弹窗 |
| 23 | 上传成功但嵌入延迟 | 60s 内批量确认 |
| 24 | **update-embeddings 返回 200 但未关联** | 轮询超时后 warning + 可执行建议，不误报成功 |
| 25 | 工作区未配置嵌入器 | 提示检查嵌入器配置 |
| 26 | 拖入文件名含中文/空格/`&`/`%` | 上传成功且 AnythingLLM 中文件名正确 |
| 27 | 同一批次拖入两个相同文件 | 自动去重，只处理一次 |
| 28 | 上传中途断网 | 重试 2 次后失败，正常记日志并汇总 |
| 29 | API 返回 500 | 显示响应体详情，而非仅 "500" |
| 30 | 退出码 | 成功 0 / 错误 1 / 取消 2 / 部分失败 3；BAT 透传 |
| 31 | **正常执行结束** | **窗口停留，用户可看到汇总与日志路径** |

### 菜单与交互

| # | 测试项 | 预期结果 |
|---|--------|----------|
| 32 | **连续按 20 次方向键** | 菜单不漂移，位置固定 |
| 33 | **中文工作区名 + 菜单渲染** | 右边界对齐 |
| 34 | 工作区 > 20 个 | 自动进入过滤模式，关键字可用 |
| 35 | 菜单顶部越界（缩小窗口后选择） | 自动清屏重绘，不抛异常 |
| 36 | 上传/嵌入期间的动画 | 动画持续显示并带实时耗时，结束后清除动画行 |
| 37 | 计数进度 | 百分比 = 真实完成数/总数 |

### 日志

| # | 测试项 | 预期结果 |
|---|--------|----------|
| 38 | 成功/失败/跳过均写日志 | JSONL，一行一条，含时间戳/状态/耗时 |
| 39 | **日志文件编码** | 无重复 BOM，可被 jq / PowerShell 正常解析 |
| 40 | `--clean-logs` | 按 `logRetentionDays` 清理过期文件 |
| 41 | logs 目录不可写 | 记 warning，主流程不中断 |

### 配置

| # | 测试项 | 预期结果 |
|---|--------|----------|
| 42 | `config.json` 含 `//` 注释 | 正常解析 |
| 43 | `config.json` 语法错误 | 报错并使用全部默认值 |
| 44 | 非法值（负数超时 / baseUrl 无协议） | 校验回退 + 明确提示 |

### v4.0 追加测试项（回归第二轮评审修复）

| # | 测试项 | 预期结果 |
|---|--------|----------|
| 45 | 省略 `-Adds` 调用 `Set-WorkspaceEmbedding -Deletes` | body 中 `adds` 为空数组，不是 `[null]` |
| 46 | 上传连续超时并重试 3 次 | 无文件句柄泄漏（句柄数不随重试增长） |
| 47 | 嵌入请求超过 `embedTimeoutSec` | 显示"嵌入请求超时或已中止"，不抛未处理异常 |
| 48 | `config.json` 设 `verifyTimeoutSec=30` | 验证窗口按 30s 生效，不回落 60 |
| 49 | `verifyBaseIntervalSec=-1` | 回退为 2（不是 60） |
| 50 | 替换旧版本且 `remove-documents` 首次失败 | 自动 un-embed 后重试删除，无孤儿 json |
| 51 | `embed.ps1 file.pdf > out.txt`（输出重定向、键盘可输入） | 菜单降级为数字选择，不抛 IOException |
| 52 | 文件被 Acrobat 以读锁打开 | 预校验拒绝并提示"文件被占用" |
| 53 | `--help` | 输出用法说明并退出 0 |
| 54 | 工作区 > 20 个并选择"浏览全部" | 分页显示，方向键翻页，不溢出缓冲区 |

---

## 九、依赖与前置条件

| 依赖 | 版本要求 | 用途 | 检测方式 |
|------|---------|------|----------|
| Windows | 10 1903+ 或 Windows 11 | ANSI 虚拟终端支持 | P/Invoke 探测 + 自动降级 |
| PowerShell | 5.1+（7 兼容） | HTTP、菜单交互 | `$PSVersionTable.PSVersion` |
| WSL / Docker | 可选（仅诊断信息） | Docker 部署时的容器检测 | `wsl --version` / `docker --version`（带超时） |
| AnythingLLM | 最新 | API 服务 | **API 探活（唯一硬性检测）** |
| apikey.txt | - | 认证凭证 | 文件存在性 + 内容非空 |
| fix-bat-encoding.ps1 | v3.0 | 仅处理 `.bat` → UTF-8 无 BOM + CRLF | - |
| fix-ps1-encoding.ps1 | v3.0 | 仅处理 `.ps1` → UTF-8 带 BOM + CRLF | - |

---

## 十、版本历史

### v4.0（2026-09-09）—— 第二轮评审修正实现版

基于《EmbedIntoWorkspace-Design-v3.0 评审意见》，修复 3 处确定性错误、9 处代码缺陷（B1–B9）与 6 处一致性/健壮性问题。

**确定性错误**：

1. §8 标题"共 40 项"更正为"共 44 项"（与正文 1–44 及 §10 第 33 条一致）
2. P0/P1/P2 数量统一为 9 / 15 / 9（修正 v3.0 头部"14 处 P1"与 §7/§10 实际条数不一致）
3. 「应用到全部」提示文案修正 off-by-one（"其余 $dups.Count 个同名文件" → "其余 $dups.Count - 1 个重复文件"）

**代码缺陷（会导致运行错误或产生脏数据）**：

4. B1 `Set-WorkspaceEmbedding` 的 `$Adds` 补默认 `@()`，避免省略 `-Adds` 时序列化出 `"adds":[null]`
5. B2 `Invoke-UploadWithRetry` 超时分支补资源释放，避免重试期间文件句柄泄漏
6. B3 嵌入/聊天异步路径在取结果前先判 `Task.IsFaulted/IsCanceled`，避免 HttpClient 超时后裸取 `.Result` 抛 `AggregateException`
7. B4 `Confirm-Embedding` 的轮询参数默认值改为直接读 `$script:Config`，修复 `verifyTimeoutSec` 等配置不生效
8. B5 `Assert-Config` 中 `VerifyBaseIntervalSec/VerifyMaxIntervalSec` 按各自默认值（2/5）回退，不再统一回退 60
9. B6 `Remove-Documents` 真正实现"un-embed → 重试物理删除"两步回退，替换失败不再残留孤儿 json
10. B7 `Select-MenuFromList` 增加 `IsOutputRedirected` 判断，输出重定向时降级为数字选择，避免 `SetCursorPosition` 抛异常
11. B8 文件占用检查改用 `FileShare.None`，可靠检测阅读器/Office 的读锁
12. B9 `Add-Type` 的 C# 片段补 `using System.Runtime.InteropServices;`，避免 `[DllImport]` 编译失败导致 ANSI 静默失效

**一致性与健壮性**：

13. 汇总统计改为五类计数，`Total` 取进入处理流程的原始分配数，新增 `Skipped`，各计数自洽
14. `Get-Workspace/Get-Workspaces` 明确返回契约（已剥掉外层 `{workspace/workspaces:...}` 包装）
15. 验证成功日志的 `DurationSec` 标注为"整批验证耗时"，避免误读为单文件耗时
16. 补齐 `--help`（原声明未实现），并传入 `Main`
17. `Complete-ApiResponse/Complete-Upload` 释放 `HttpResponseMessage`，批量场景不再累积
18. 补充 §5.0「工作区列表」端点与响应形状；标注 `HttpClient.Timeout` 为共享状态、并行时建议 `CancellationTokenSource`

**可优化项落地**：

19. 菜单支持 >20 项分页滚动，`$menuHeight` 与实际绘制行数一致，不再溢出缓冲区
20. `Get-DisplayWidth` 标注 CJK 扩展 B / emoji / 组合字符的已知局限
21. `Write-Colored` 对 `$Rgb` 去空格，避免破坏 ANSI 序列
22. §1.2.1 将"不支持带请求体 DELETE"的表述修正为"行为不可靠/易踩坑"
23. 测试清单新增 v4.0 回归项（§8 第 45–54 项）

### v3.0（2026-09-09）—— 评审修正实现版

基于《EmbedIntoWorkspace-Design-v2.0-评审意见》，修复 9 处 P0、15 处 P1、9 处 P2，并补齐多项"设计提到但未实现"的能力。

**P0（会导致功能失效或脏数据）**：

1. 重复检测改为按 `title` 比对，不再从 location 反推（原实现永远命中不了）
2. 「替换」改为 `DELETE /api/v1/system/remove-documents` 物理删除，不再误用 `update-embeddings` 的 `deletes`（后者只解除关联，会持续产生孤儿 json）
3. 重复检测前移到上传之前，选「跳过」不再产生孤儿
4. 删除 `-Headers` 中的 `Content-Type`，全部改为统一 API 层（PS 5.1 会抛异常，嵌入/聊天调用会失败）
5. 耗时统计改用 `TotalSeconds`（原 `.Seconds` 会把 3m20s 记成 20s）
6. `Write-Spinner` 重写为 `Wait-TaskWithSpinner`：由 .NET 发起异步 I/O（`SendAsync` 返回 Task），主线程只轮询该 Task 并画帧；**禁止**用 `Task::Run` 在后台跑 PowerShell 脚本块（非 PS 线程无 Runspace）
7. 正常结束路径增加按键等待（原实现窗口一闪而过，用户看不到汇总）
8. 菜单 `menuTop` 首帧固定（原实现每按一次方向键下移 2 行）
9. 新增 `Get-DisplayWidth` / `Format-Fixed`，统一宽度常量（原实现三处宽度不等，中文必然错位）

**P1（明确 Bug）**：

10. API 层统一为 `HttpClient`，区分 `TransportError` 与状态码，401 不再被误报为"API 不可达"
11. `apikey.txt` UTF-16 分支跳过 BOM 字节，补 UTF-16BE 与 ANSI 兜底（原实现该场景必然认证失败）
12. 日志改用 JSONL（替代 `|` 分隔文本，避免 `Detail` 含 `|` 或换行时破坏结构）
13. 日志追加改用 `AppendAllText` 无 BOM（原 `Add-Content -Encoding UTF8` 会逐行写多个 BOM）
14. `GetAwaiter().GetResult()` 替代 `.Result`，错误信息可读
15. HttpClient 单例 + `StreamContent` 流式上传，降低内存与端口占用
16. 验证改为批量轮询 + 60s 窗口 + 退避 + 可见错误反馈
17. 状态机改用英文 key（不再用中文字符串做状态机）
18. 汇总改为四类计数（语义修正）
19. 补齐 `Write-SummaryBox` 等未定义函数，删除死参数
20. 重复处理策略统一为 `[S]/[R]/[A]` + 「应用到全部」
21. 编码修复脚本拆分为 `.bat` 与 `.ps1` 两个，消除目标冲突
22. 移除对 `$Host.UI.SupportsVirtualTerminal` 的错误依赖
23. 新增重定向降级（`IsOutputRedirected` / `IsInputRedirected`）
24. `Write-Colored` 落地 True Color，降级路径明确

**P2（健壮性与体验）**：

25. BAT 参数逐个重新加引号 + `setlocal` + 退出码说明
26. `config.json` 支持注释、校验与默认值回退；`LogDir` 支持绝对路径
27. 测试检索改用 `query` 模式，且只对验证通过的文件发送
28. 退出码语义化：0 成功 / 1 错误 / 2 取消 / 3 部分失败
29. 实现 `--clean-logs`、`--diagnose`、`--no-pause`
30. 上传失败指数退避重试 2 次
31. 文件预校验补充：空文件、被占用、长路径；同批次路径去重
32. 主流程统一 `try/catch/finally`，异常后恢复控制台状态
33. 测试清单从 25 项扩充到 44 项

### v2.0（2026-09-09）—— 兼容性重构

- 修正 PS 5.1 无 `-Form`、PS1 需 BOM、`Main` 内 `$args` 丢失、ANSI 初始化三处语法错误、Docker 误阻断
- 全链路 UTF-8、轮询验证、通用菜单、文件预校验、重复检测、结构化日志、`config.json`

### v1.2（2026-09-09）—— 初版

- 新增系统环境检测模块；原设计存档于 `EmbedIntoWorkspace-Design.md`

---

*文档版本：v4.0 | 更新日期：2026-09-09 | 第二轮评审修正实现版（基于 v3.0 设计评审）*
