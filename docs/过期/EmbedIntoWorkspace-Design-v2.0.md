# AnythingLLM 文档嵌入工具 — 设计方案 v2.0

> 拖拽文件到 BAT 脚本 → 自动上传并嵌入选定工作区 → 可选测试聊天
>
> v2.0 核心变化：修正 PS 5.1 兼容性硬伤、统一 UTF-8 编码链路、验证轮询、快速环境检测、通用选择菜单、结构化日志、外部配置。变更明细见「§10 版本历史」。

---

## 一、项目概览

### 1.1 功能目标

1. **拖拽即用**：将文件拖入 BAT 脚本，自动完成上传、嵌入、验证全流程
2. **多文件处理模式选择**：拖入多个文件时，提示选择：
   - **统一模式**：所有文件放入同一工作区（选一次工作区，批量执行）
   - **逐项模式**：每个文件分别选择工作区（全部选完后一次性批量嵌入）
3. **工作区选择**：通用方向键菜单（↑↓ 移动 / Enter 确认 / ESC 退出），工作区较多时支持输入过滤
4. **文件预校验**：扩展名白名单、存在性、大小上限检查，拖入目录/不支持类型直接提示
5. **重复检测**：嵌入前比对工作区已有文档，同名文件提示跳过或先删除旧版本
6. **嵌入验证（轮询）**：嵌入后自动轮询工作区 documents 列表，直到出现该文档（上限 5 次 × 3s）
7. **可选测试**：嵌入完成后询问是否调用本地模型进行测试聊天
8. **结构化日志**：每次操作记录到 `logs/YYYY-MM-DD_embed.log`（时间戳 / 文件 / 工作区 / 状态 / 耗时）
9. **外部配置**：`config.json` 可配置 baseUrl、超时、文件白名单、聊天开关等

### 1.2 技术选型

| 组件 | 技术 | 说明 |
|------|------|------|
| 入口脚本 | `.bat`（UTF-8 无 BOM, CRLF） | 拖拽关联、cmd 原生支持 |
| 核心逻辑 | PowerShell `.ps1`（UTF-8 **带 BOM**） | 关键：PS 5.1 对无 BOM 的 .ps1 按 ANSI 解析，中文会乱码 |
| API 通信 | `System.Net.Http.HttpClient` | **PS 5.1 没有 `Invoke-RestMethod -Form`**（PS 6+ 才有），multipart 必须手工构造 |
| JSON 解析 | `ConvertFrom-Json` | 原生支持 |
| 终端美化 | ANSI 24-bit True Color | Windows 10 1903+ 原生支持；不支持时自动降级为纯文本 |
| 状态图标 | Unicode 符号 (✔ ✘ ⚠ ⬆ ⚙) | 需要 UTF-8 输出链路（见 1.3 编码约定） |

### 1.3 编码约定（v2.0 统一为 UTF-8）

| 文件 | 编码 | 原因 |
|------|------|------|
| `EmbedIntoWorkspace.bat` | UTF-8 无 BOM + CRLF | 首行 `chcp 65001` 后 cmd 按 UTF-8 解析后续中文行 |
| `embed.ps1` | UTF-8 **带 BOM** + CRLF | PS 5.1 只有带 BOM 才会按 UTF-8 解析脚本 |
| `apikey.txt` | 自动检测（BOM/UTF-16/UTF-8） | 读取时按字节检测，不依赖固定编码 |
| `config.json` | UTF-8 无 BOM | PowerShell 读取 JSON 无编码歧义 |
| 控制台 | `chcp 65001` + `[Console]::OutputEncoding=UTF8` | 两处必须一致，否则中文花屏 |

> ⚠️ 与 v1.2 的差异：v1.2 使用 GBK(936) 方案，导致 Unicode 状态图标（✔✘⚠）无法在 GBK 中编码显示，且 PS 输出编码与控制台代码页冲突。v2.0 全链路 UTF-8，`fix-bat-encoding.ps1` 后处理目标同步改为「转 UTF-8 无 BOM」。

### 1.4 兼容性要求

- **操作系统**：Windows 10 1903+（支持 ANSI 虚拟终端）
- **PowerShell**：5.1+（Windows 自带；脚本同时兼容 PS 7）
- **AnythingLLM**：本地部署，默认地址 `http://localhost:3001`（可用 `config.json` 覆盖）
- **运行方式**：Docker 或桌面版均可——环境检测不再把 Docker 缺失当作阻断项

---

## 二、文件结构

```
/home/admin/003anythingllmtools/
├── tools/
│   ├── EmbedIntoWorkspace.bat        ← BAT 包装器（UTF-8 无 BOM, CRLF）
│   ├── embed.ps1                     ← 核心 PowerShell 脚本（UTF-8 带 BOM）
│   ├── config.json                   ← 外部配置（可选，缺省用内置默认值）
│   ├── apikey.txt                    ← API Key 文件
│   └── logs/                         ← 操作日志目录（自动创建）
│       └── YYYY-MM-DD_embed.log
├── apikey.txt                        ← 项目根目录备选 Key
├── scripts/
│   └── fix-bat-encoding.ps1          ← v2.0：转 UTF-8 无 BOM + CRLF 归一化
└── docs/
    ├── EmbedIntoWorkspace-Design.md        ← v1.2（存档）
    └── EmbedIntoWorkspace-Design-v2.0.md   ← 本文档
```

`config.json`（可选，缺省时全部走内置默认值）：

```json
{
  "baseUrl": "http://localhost:3001",
  "uploadTimeoutSec": 300,
  "chatTimeoutSec": 300,
  "verifyMaxTries": 5,
  "verifyIntervalSec": 3,
  "allowedExtensions": [".pdf", ".docx", ".doc", ".txt", ".md", ".csv", ".xlsx", ".pptx"],
  "maxFileSizeMB": 100,
  "defaultWorkspace": "",
  "askForChatTest": true,
  "detectDuplicates": true,
  "logDir": "logs"
}
```

---

## 三、BAT 包装器设计

**文件**：`EmbedIntoWorkspace.bat`
**编码**：UTF-8 无 BOM + CRLF（由 `fix-bat-encoding.ps1` 后处理，目标从 v1.2 的 GBK 改为 UTF-8）

```bat
@echo off
chcp 65001 >nul 2>&1

REM 检查是否拖入文件
if "%~1"=="" (
    echo 请将文件拖拽到此脚本上运行。
    echo.
    echo 支持的文件类型：PDF, DOCX, TXT, MD, CSV 等
    echo 支持一次拖入多个文件。
    pause
    exit /b 1
)

REM 检查 apikey.txt 是否存在
if not exist "%~dp0apikey.txt" (
    if not exist "%~dp0..\apikey.txt" (
        echo [错误] 未找到 apikey.txt
        echo 请在脚本目录或项目根目录放置 apikey.txt
        pause
        exit /b 1
    )
)

REM 调用 PowerShell 核心脚本，传递所有拖入的文件路径
powershell -ExecutionPolicy Bypass -NoProfile -File "%~dp0embed.ps1" %*
exit /b %errorlevel%
```

**关键点**（v2.0 变更）：
- 首行改为 `chcp 65001`，与 PS 内 `[Console]::OutputEncoding=UTF8` 一致（v1.2 的 `chcp 936` + UTF-8 输出会花屏）
- BAT 自身保存为 UTF-8 无 BOM；`chcp 65001` 之前的两行均为纯 ASCII，无解析风险
- 末尾 `exit /b %errorlevel%` 回传 PS 退出码（v1.2 缺失），便于自动化调用方判断结果
- `%~dp0` 获取 BAT 所在目录；`%*` 传递所有拖入文件；`pause` 防止窗口自动关闭

---

## 四、PowerShell 核心脚本设计

**文件**：`embed.ps1`
**编码**：UTF-8 带 BOM（必须！PS 5.1 无 BOM 时按 GBK/ANSI 解析，中文注释与字符串全部乱码）

### 4.1 模块划分

```powershell
# ============================================================
# embed.ps1 — AnythingLLM 文档嵌入工具 v2.0
# 编码：UTF-8 (BOM) | 终端：chcp 65001 + ANSI True Color
# 调用：powershell -ExecutionPolicy Bypass -File embed.ps1 <file...>
# ============================================================

param([string[]]$Files)   # ← v2.0 修正：脚本级接收拖拽文件列表

#region ========== 配置与初始化 ==========
function Initialize-Console { ... }     # ANSI 启用/降级 + 输出编码
function Read-Config { ... }            # config.json 合并内置默认值
function Read-ApiKey { ... }            # BOM/UTF-16/UTF-8 自动检测
#endregion

#region ========== 终端美化 ==========
function Write-Banner { ... }
function Write-Section { ... }
function Write-Status { ... }
function Write-RainbowProgress { ... }  # 仅用于可量化的批处理计数
function Write-Spinner { ... }          # v2.0 新增：不确定时长操作（上传/嵌入）的旋转动画
function Write-BoxMessage { ... }
#endregion

#region ========== API 调用 ==========
function Test-QuickEnvironment { ... }  # v2.0：仅 API 探活 + Key，阻断项
function Invoke-FullDiagnose { ... }    # v2.0：OS/PS/WSL/Docker/容器，仅警告
function Get-Workspaces { ... }
function Get-Workspace { ... }
function Upload-Document { ... }        # HttpClient multipart（PS 5.1 兼容）
function Set-WorkspaceEmbedding { ... }
function Confirm-Embedding { ... }      # v2.0：轮询验证
function Send-ChatMessage { ... }
#endregion

#region ========== 文件处理 ==========
function Test-InputFile { ... }         # v2.0：存在性/白名单/大小
function Find-DuplicateDocuments { ... }# v2.0：工作区同名文档检测
#endregion

#region ========== 交互菜单 ==========
function Select-MenuFromList { ... }    # v2.0：通用方向键菜单（合并原两个菜单）
function Confirm-Action { ... }
#endregion

#region ========== 日志 ==========
function Write-OperationLog { ... }     # v2.0：结构化一行日志
#endregion

#region ========== 主流程 ==========
function Main { param([string[]]$Files) ... }
#endregion

# 启动（显式传入文件列表，函数内不可依赖 $args！）
Main -Files $Files
```

> ⚠️ **v1.2 的致命坑**：原设计在 `Main` 函数体内直接使用 `$args`。函数内的 `$args` 是**该函数的参数**（无参调用 = 空数组），拖拽的文件列表在**脚本作用域**的 `$args` 里，二者不是一回事。v2.0 改为脚本级 `param([string[]]$Files)` + `Main -Files $Files`。

### 4.2 初始化流程（ANSI 修正版）

```powershell
function Initialize-Console {
    # 1. 窗口标题
    $host.UI.RawUI.WindowTitle = "AnythingLLM 嵌入工具 v2.0"

    # 2. 启用 ANSI 虚拟终端（Windows 10 1903+）
    $script:UseAnsi = $true
    if (-not $Host.UI.SupportsVirtualTerminal) {
        try {
            $sig = @'
[DllImport("kernel32.dll", SetLastError = true)]
public static extern IntPtr GetStdHandle(int nStdHandle);
[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool GetConsoleMode(IntPtr hConsoleHandle, out uint lpMode);
[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool SetConsoleMode(IntPtr hConsoleHandle, uint dwMode);
'@
            $k32 = Add-Type -MemberDefinition $sig -Name 'Kernel32V2' -Namespace 'Win32' -PassThru
            $handle = $k32::GetStdHandle(-11)        # STD_OUTPUT_HANDLE
            $mode = 0
            if ($k32::GetConsoleMode($handle, [ref]$mode)) {
                [void]$k32::SetConsoleMode($handle, $mode -bor 0x0004)  # ENABLE_VIRTUAL_TERMINAL_PROCESSING
            }
        } catch {
            $script:UseAnsi = $false    # 不支持则降级为纯文本
        }
    }

    # 3. 输出编码：必须与控制台代码页(chcp 65001)一致
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8

    # 4. 窗口大小（尽力而为）
    try {
        $host.UI.RawUI.WindowSize = New-Object System.Management.Automation.Host.Size(90, 45)
    } catch { }

    # 5. 清屏
    Clear-Host
}
```

**v1.2 原代码的三处错误（已修正）**：
1. `$modebor 0x0004` 是语法错误，应为 `$mode -bor 0x0004`（原写法 `$modebor` 被当作变量名）
2. `GetConsoleMode($handle, [ref]0)` 不能对字面量取引用，必须先声明变量 `$mode = 0` 再传 `[ref]$mode`
3. `GetConsoleMode` 的返回值是 bool（调用是否成功），不是 mode 值——原设计把返回值当 mode 用，`SetConsoleMode` 收到的 mode 是错的

### 4.3 配置读取

```powershell
function Read-Config {
    $defaults = @{
        BaseUrl          = "http://localhost:3001"
        UploadTimeoutSec = 300
        ChatTimeoutSec   = 300
        VerifyMaxTries   = 5
        VerifyIntervalSec= 3
        AllowedExtensions= @(".pdf",".docx",".doc",".txt",".md",".csv",".xlsx",".pptx")
        MaxFileSizeMB    = 100
        DefaultWorkspace = ""
        AskForChatTest   = $true
        DetectDuplicates = $true
        LogDir           = "logs"
    }
    $configPath = Join-Path $PSScriptRoot "config.json"
    if (Test-Path $configPath) {
        $fromFile = Get-Content $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($key in $fromFile.PSObject.Properties.Name) {
            if ($defaults.ContainsKey($key)) { $defaults[$key] = $fromFile.$key }
        }
    }
    return $defaults
}
```

### 4.4 API Key 读取（编码检测，保留 v1.2 方案）

```powershell
function Read-ApiKey {
    param([string]$ScriptDir)

    $keyPaths = @(
        Join-Path $ScriptDir "apikey.txt"
        Join-Path (Split-Path $ScriptDir -Parent) "apikey.txt"
    )
    $keyPath = $keyPaths | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $keyPath) {
        Write-Status -Type "error" -Message "未找到 apikey.txt"
        exit 1
    }

    $bytes = [System.IO.File]::ReadAllBytes($keyPath)

    # UTF-8 BOM (EF BB BF)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $apiKey = [System.Text.Encoding]::UTF8.GetString($bytes, 3, $bytes.Length - 3)
    }
    # UTF-16 LE BOM (FF FE)
    elseif ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE) {
        $apiKey = [System.Text.Encoding]::Unicode.GetString($bytes)
    }
    # 默认按 UTF-8
    else {
        $apiKey = [System.Text.Encoding]::UTF8.GetString($bytes)
    }

    $apiKey = $apiKey.Trim()
    if ([string]::IsNullOrEmpty($apiKey)) {
        Write-Status -Type "error" -Message "apikey.txt 内容为空"
        exit 1
    }
    return $apiKey
}
```

### 4.5 系统环境检测（v2.0 重构：快速阻断 + 深度诊断）

**原则**：AnythingLLM 可以 Docker 运行，也可以桌面版/npm 原生运行。因此**阻断项只有两个**：API 可达、API Key 存在。WSL / Docker / 容器检测全部为信息或警告，绝不阻断主流程。

**快速检查（每次启动必跑，≤3 秒）**：

```powershell
function Test-QuickEnvironment {
    param([string]$ApiKey)

    # 阻断项 1：API 探活
    try {
        $r = Invoke-RestMethod -Uri "$($script:Config.BaseUrl)/api/v1/auth" `
            -Headers @{ Authorization = "Bearer $ApiKey" } -Method Get -TimeoutSec 5 -ErrorAction Stop
        if (-not $r.authenticated) {
            return @{ Success = $false; Errors = @("API Key 无效（authenticated=false）") }
        }
    } catch {
        return @{ Success = $false; Errors = @("API 不可达: $($script:Config.BaseUrl)（$($_.Exception.Message)）") }
    }

    # 阻断项 2：Key 文件存在性（由 Read-ApiKey 保证，此处仅为返回信息）
    return @{ Success = $true; Errors = @() }
}
```

**深度诊断（`--diagnose` 参数或快速检查失败时自动执行，全部不阻断）**：

```powershell
function Invoke-FullDiagnose {
    # 1. 操作系统：Get-CimInstance Win32_OperatingSystem → 信息
    # 2. PowerShell：$PSVersionTable.PSVersion → 信息
    # 3. WSL：wsl --version → 警告（缺省仅提示；Docker Desktop 可用 Hyper-V 后端）
    # 4. Docker：wsl docker --version / docker --version → 警告（缺省仅提示，桌面版无 Docker 也可运行）
    # 5. 容器：docker ps 过滤 anything/llm → 信息（用 -f name= 精确过滤，避免误匹配）
    # 6. API 地址与认证 → 信息
    # 全部项只写入 Info/Warnings，不参与 Success 判定
}
```

> **v1.2 的问题**：Docker 未检测到被设为 Error 并阻断流程，会误伤「桌面版/原生部署」用户；且 `Invoke-Expression "$dockerCmd ps --format ..."` 拼接字符串调用是坏味道，v2.0 改用参数数组调用。

### 4.6 终端美化（修正版）

配色方案、状态图标沿用 v1.2（24-bit True Color + Unicode），修复两处实现缺陷：

**Write-Section（修复字符串语法错误）**：

```powershell
function Write-Section {
    param(
        [string]$Title,
        [ValidateSet("cyan","green","magenta","yellow","red")]
        [string]$Color = "cyan"
    )

    $colorCode = switch ($Color) {
        "cyan"    { "38;2;0;255;255" }
        "green"   { "38;2;0;200;0" }
        "magenta" { "38;2;255;0;255" }
        "yellow"  { "38;2;255;255;0" }
        "red"     { "38;2;255;50;50" }
    }

    $padTotal = 54
    $padLeft  = [math]::Floor(($padTotal - $Title.Length) / 2)
    $padRight = $padTotal - $Title.Length - $padLeft

    $line = "─" * $padLeft + " " + $Title + " " + ("─" * $padRight)
    Write-Host ""
    Write-Host "─── $line ───" -ForegroundColor Gray
}
```

> v1.2 原写法 `"$("─" * $padLeft) $Title $($ "─" * $padRight)"` 中 `$($ "─"` 多了一个空格，是语法错误。

**Write-Banner（修正宽度对齐）**：

```powershell
function Write-Banner {
    $inner = 56                      # 内容区宽度
    $border = "═" * $inner

    Write-Host ""
    Write-Host "╔$border╗" -ForegroundColor Cyan
    Write-Host ("║" + " " * $inner + "║") -ForegroundColor Cyan
    $title = "⚙  AnythingLLM 文档嵌入工具 v2.0"
    Write-Host ("║" + $title.PadRight($inner) + "║") -ForegroundColor Cyan
    $sub = "拖拽文件到此脚本即可自动嵌入工作区"
    Write-Host ("║" + $sub.PadRight($inner) + "║") -ForegroundColor Gray
    Write-Host ("║" + " " * $inner + "║") -ForegroundColor Cyan
    Write-Host "╚$border╝" -ForegroundColor Cyan
    Write-Host ""
}
```

> v1.2 边框宽 58 而内容行宽 60，右边界不对齐；v2.0 统一内容区 56 字符。

**旋转动画（v2.0 新增，替代假进度）**：

```powershell
function Write-Spinner {
    param(
        [string]$Activity,
        [int]$DurationSec = 0     # 0 = 无限，直到调用方手动结束
    )

    $frames = @("|", "/", "-", "\")
    $i = 0
    $deadline = if ($DurationSec -gt 0) { [DateTime]::Now.AddSeconds($DurationSec) } else { $null }

    while ($true) {
        if ($deadline -and [DateTime]::Now -ge $deadline) { break }
        Write-Host "`r  $($frames[$i % 4]) $Activity   " -NoNewline
        [Console]::Out.Flush()
        Start-Sleep -Milliseconds 120
        $i++
    }
}
```

> **v1.2 的问题**：彩虹进度条是纯模拟动画，与真实上传/嵌入无关（`Invoke-RestMethod` 无法上报字节进度），显示"上传中 40%"而实际什么都没发生，会误导用户。v2.0 原则：
> - **上传/嵌入**（时长不确定）→ 旋转动画 + 当前阶段文字
> - **批处理计数**（如 3/10 个文件）→ 彩虹进度条，百分比 = 真实完成数/总数
> - 若要真实字节级进度，需基于 `HttpClient` 流式分片 + 自定义 `HttpContent` 上报（列为可选增强，见 §10）

### 4.7 通用选择菜单（合并原两个菜单）

`Select-Workspace` 与 `Select-ProcessingMode` 结构完全相同，v2.0 合并为一个通用函数：

```powershell
function Select-MenuFromList {
    param(
        [object[]]$Items,            # 任意对象数组
        [string]$Title,              # 菜单标题
        [scriptblock]$DisplayLabel   # 返回显示文本的表达式，如 { $_.name }
    )

    if ($Items.Count -eq 0) { return $null }
    $selectedIndex = 0
    $totalItems = $Items.Count

    # 菜单高度计算：边框2 + 空行2 + 条目 + 提示2
    $menuHeight = 6 + $totalItems

    function Draw-Menu {
        param([int]$Highlight)

        # 越界保护：菜单起始行不允许为负
        $menuTop = [Console]::CursorTop - $menuHeight
        if ($menuTop -lt 1) {
            Clear-Host
            $script:menuTop = 1
        } else {
            $script:menuTop = $menuTop
        }

        [Console]::SetCursorPosition(0, $script:menuTop)
        Write-Host "┌─ $Title ─────────────────────────────┐" -ForegroundColor DarkGray

        for ($i = 0; $i -lt $totalItems; $i++) {
            [Console]::SetCursorPosition(0, $script:menuTop + 2 + $i)
            $label = & $DisplayLabel $Items[$i]
            if ($i -eq $Highlight) {
                Write-Host "│  ▶ " -NoNewline -ForegroundColor Cyan
                Write-Host $label.PadRight(44) -NoNewline -ForegroundColor Cyan
                Write-Host "│" -ForegroundColor DarkGray
            } else {
                Write-Host "│    " -NoNewline -ForegroundColor DarkGray
                Write-Host $label.PadRight(44) -NoNewline -ForegroundColor White
                Write-Host "│" -ForegroundColor DarkGray
            }
        }

        [Console]::SetCursorPosition(0, $script:menuTop + $totalItems + 4)
        Write-Host "└──────────────────────────────────────────────┘" -ForegroundColor DarkGray
        Write-Host ""
        Write-Host "  [↑↓] 移动  [Enter] 确认  [ESC] 退出" -ForegroundColor DarkGray
    }

    Write-Host ""
    Draw-Menu -Highlight $selectedIndex

    while ($true) {
        if ([Console]::KeyAvailable) {
            $key = [Console]::ReadKey($true)
            switch ($key.Key) {
                "UpArrow" {
                    $selectedIndex = ($selectedIndex - 1 + $totalItems) % $totalItems
                    Draw-Menu -Highlight $selectedIndex
                }
                "DownArrow" {
                    $selectedIndex = ($selectedIndex + 1) % $totalItems
                    Draw-Menu -Highlight $selectedIndex
                }
                "Enter"   { return $Items[$selectedIndex] }
                "Escape"  { return $null }
            }
        }
        Start-Sleep -Milliseconds 10
    }
}
```

**v1.2 的健壮性问题（已处理）**：
- `$menuTop = CursorTop - totalItems - 4` 在菜单靠近屏幕顶部时变为负坐标，`SetCursorPosition` 会抛异常 → v2.0 加 `menuTop < 1` 时 `Clear-Host` 重绘
- 工作区数量超过终端高度会溢出 → v2.0 建议：当 `Items.Count -gt 20` 时，在菜单旁提供「输入关键字过滤」子模式（简化实现：ESC 后进入过滤输入框，实时过滤 `DisplayLabel` 结果）；也可直接分页

### 4.8 文件预校验与重复检测（v2.0 新增）

```powershell
function Test-InputFile {
    param([string]$FilePath)

    if (-not (Test-Path -LiteralPath $FilePath)) {
        return @{ Ok = $false; Reason = "文件不存在" }
    }
    if (Test-Path -LiteralPath $FilePath -PathType Container) {
        return @{ Ok = $false; Reason = "是目录，不是文件" }
    }
    $ext = [System.IO.Path]::GetExtension($FilePath).ToLower()
    if ($ext -notin $script:Config.AllowedExtensions) {
        return @{ Ok = $false; Reason = "不支持的类型: $ext（允许: $($script:Config.AllowedExtensions -join ', ')）" }
    }
    $sizeMB = (Get-Item -LiteralPath $FilePath).Length / 1MB
    if ($sizeMB -gt $script:Config.MaxFileSizeMB) {
        return @{ Ok = $false; Reason = "文件过大: $([math]::Round($sizeMB,1)) MB（上限 $($script:Config.MaxFileSizeMB) MB）" }
    }
    return @{ Ok = $true }
}
```

```powershell
# 嵌入前：比对工作区现有 documents，按 title（文件名）检测同名
function Find-DuplicateDocuments {
    param([string]$ApiKey, [string]$Slug, [string[]]$Locations)

    $ws = Get-Workspace -ApiKey $ApiKey -Slug $Slug
    if (-not $ws -or -not $ws.documents) { return @{} }

    $existing = @{}
    foreach ($doc in $ws.documents) {
        if ($doc.title) { $existing[$doc.title.ToLower()] = $doc.location }
    }

    $dups = @{}
    foreach ($loc in $Locations) {
        $fileName = [System.IO.Path]::GetFileName($loc) -replace '\.json$',''
        if ($existing.ContainsKey($fileName.ToLower())) {
            $dups[$loc] = $existing[$fileName.ToLower()]
        }
    }
    return $dups   # @{ newLocation = oldLocation }
}
```

**重复处理策略**（`config.json` 的 `detectDuplicates=true` 时生效）：
1. 检测到同名旧文档 → 询问用户：`[S] 跳过  [R] 替换（先删旧再嵌入） [A] 全部保留（可能产生重复向量）`
2. 替换时，`update-embeddings` 的 `deletes` 传入旧 location、`adds` 传入新 location，一次调用完成
3. 该机制避免「同名文件反复拖入 → 工作区出现 N 份重复向量、custom-documents 目录堆积孤儿 json」

### 4.9 API 调用（PS 5.1 兼容）

#### 4.9.1 上传文件（HttpClient multipart）

```powershell
function Upload-Document {
    param(
        [string]$ApiKey,
        [string]$FilePath,
        [string]$AddToWorkspaces = ""   # 可选：逗号分隔 slug，上传后立即嵌入
    )

    $client = New-Object System.Net.Http.HttpClient
    try {
        $client.Timeout = [TimeSpan]::FromSeconds($script:Config.UploadTimeoutSec)
        $client.DefaultRequestHeaders.Authorization =
            New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $ApiKey)

        $form = New-Object System.Net.Http.MultipartFormDataContent

        # 读取文件为字节数组（注意 (,$bytes) 逗号防止 PowerShell 展开数组）
        $bytes = [System.IO.File]::ReadAllBytes($FilePath)
        $fileContent = New-Object System.Net.Http.ByteArrayContent(,$bytes)
        $fileContent.Headers.ContentType =
            New-Object System.Net.Http.Headers.MediaTypeHeaderValue("application/octet-stream")

        # 中文文件名：MultipartFormDataContent.Add 会按 UTF-8 生成 filename*，
        # AnythingLLM(multer) 可正确识别；不要用 GBK 字节直传
        $form.Add($fileContent, "file", [System.IO.Path]::GetFileName($FilePath))

        if ($AddToWorkspaces) {
            $form.Add((New-Object System.Net.Http.StringContent($AddToWorkspaces)), "addToWorkspaces")
        }

        $response = $client.PostAsync("$($script:Config.BaseUrl)/api/v1/document/upload", $form).Result
        $body = $response.Content.ReadAsStringAsync().Result
        $json = $body | ConvertFrom-Json

        if (-not $response.IsSuccessStatusCode -or -not $json.success) {
            return @{ Success = $false; Error = "HTTP $([int]$response.StatusCode): $body" }
        }

        $doc = $json.documents | Select-Object -First 1
        return @{
            Success  = $true
            Location = $doc.location      # ← 嵌入必须用 location，不能用 id
            Title    = $doc.title
        }
    } catch {
        return @{ Success = $false; Error = $_.Exception.Message }
    } finally {
        $client.Dispose()
    }
}
```

> ⚠️ **v1.2 的技术选型错误**：表格声称 `Invoke-RestMethod` "原生支持 JSON、multipart"——在 Windows PowerShell 5.1 中 **不存在 `-Form` 参数**（PowerShell 6.0 才引入）。v2.0 统一改用 `HttpClient` + `MultipartFormDataContent`，同时获得超时控制与更好的错误可读性。

#### 4.9.2 嵌入到工作区（按 slug 分组，一次调用）

```powershell
function Set-WorkspaceEmbedding {
    param([string]$ApiKey, [string]$Slug, [string[]]$Adds, [string[]]$Deletes = @())

    $bodyObj = @{
        adds    = @($Adds)
        deletes = @($Deletes)
    }
    $body = $bodyObj | ConvertTo-Json -Depth 5 -Compress

    try {
        $response = Invoke-RestMethod -Uri "$($script:Config.BaseUrl)/api/v1/workspace/$Slug/update-embeddings" `
            -Method Post `
            -Headers @{ Authorization = "Bearer $ApiKey"; "Content-Type" = "application/json" } `
            -Body $body -TimeoutSec 60 -ErrorAction Stop

        # 响应体可能直接携带更新后的 workspace（含 documents），可用于即时验证
        return @{ Success = $true; Workspace = $response.workspace; Message = $response.message }
    } catch {
        return @{ Success = $false; Error = $_.Exception.Message }
    }
}
```

#### 4.9.3 验证嵌入（v2.0：轮询兜底）

```powershell
function Confirm-Embedding {
    param(
        [string]$ApiKey,
        [string]$Slug,
        [string]$Location,
        [int]$MaxTries    = 5,     # 可被 config.json 覆盖
        [int]$IntervalSec = 3
    )

    for ($i = 1; $i -le $MaxTries; $i++) {
        try {
            $ws = Get-Workspace -ApiKey $ApiKey -Slug $Slug
            if ($ws.documents | Where-Object { $_.location -eq $Location }) {
                return $true
            }
        } catch { }
        if ($i -lt $MaxTries) { Start-Sleep -Seconds $IntervalSec }
    }
    return $false
}
```

> **为什么必须轮询**：`update-embeddings` 返回 200 只代表"已受理"，嵌入是异步的；且 AnythingLLM 存在已知缺陷——接口返回 200/显示 success，但工作区↔文档关联未写入（见 upstream issue #5901、#1814）。v1.2 只查一次，查不到就标"待确认"，会大量误报。v2.0 轮询 5×3s，最终仍未出现时给出可执行建议（检查 AnythingLLM 日志 / 嵌入器是否配置）。

#### 4.9.4 测试聊天

```powershell
function Send-ChatMessage {
    param([string]$ApiKey, [string]$Slug, [string]$Message)

    $body = @{ message = $Message; mode = "chat"; stream = $false } | ConvertTo-Json -Compress

    try {
        $r = Invoke-RestMethod -Uri "$($script:Config.BaseUrl)/api/v1/workspace/$Slug/chat" `
            -Method Post `
            -Headers @{ Authorization = "Bearer $ApiKey"; "Content-Type" = "application/json" } `
            -Body $body -TimeoutSec $script:Config.ChatTimeoutSec -ErrorAction Stop
        return @{ Success = $true; Text = $r.textResponse; Sources = @($r.sources) }
    } catch {
        return @{ Success = $false; Error = $_.Exception.Message }
    }
}
```

> 聊天（LLM 推理）耗时可能远超上传，超时用 `config.json` 的 `chatTimeoutSec`（默认 300s），并只在嵌入验证通过后发送，避免"模型还没嵌入完就问"的假失败。

### 4.10 结构化日志（v2.0）

```powershell
function Write-OperationLog {
    param(
        [string]$File,
        [string]$Workspace,
        [string]$Status,          # success / error / warning / skipped
        [int]$DurationSec = 0,
        [string]$Detail = ""
    )

    $logDir = Join-Path $PSScriptRoot $script:Config.LogDir
    if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

    $logFile = Join-Path $logDir ("{0:yyyy-MM-dd}_embed.log" -f (Get-Date))
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $line = "$ts | $Status | $Workspace | $File | ${DurationSec}s | $Detail"
    Add-Content -Path $logFile -Value $line -Encoding UTF8
}
```

- **v1.2 的问题**：只写成功路径、且 Warning 状态也写 `"success"`、无时间戳/耗时/失败明细。
- **v2.0 约定**：每次操作一行（含失败与跳过）；状态可机器解析（`|` 分隔）；按日切文件，可配合定期清理（保留最近 30 天，提供 `--clean-logs` 参数或系统计划任务）。

### 4.11 主流程（完整伪代码）

```powershell
param([string[]]$Files)

function Main {
    param([string[]]$Files)

    # ========== 初始化 ==========
    Initialize-Console
    $script:Config = Read-Config
    Write-Banner

    # ========== 参数校验 ==========
    if ($Files.Count -eq 0) {
        Write-Status -Type "error" -Message "未拖入任何文件"
        [Console]::ReadKey($true) | Out-Null
        exit 1
    }

    # ========== 文件预校验（白名单/大小/目录） ==========
    Write-Section -Title "文件校验" -Color "cyan"
    $validFiles = New-Object System.Collections.ArrayList
    foreach ($f in $Files) {
        $check = Test-InputFile -FilePath $f
        if ($check.Ok) {
            [void]$validFiles.Add($f)
            Write-Status -Type "success" -Message "OK  $(Split-Path $f -Leaf)"
        } else {
            Write-Status -Type "error" -Message "跳过 $(Split-Path $f -Leaf)" -Detail $check.Reason
        }
    }
    if ($validFiles.Count -eq 0) {
        Write-Status -Type "error" -Message "没有可处理的文件，退出"
        exit 1
    }
    $Files = @($validFiles)

    # ========== API Key + 快速环境检测（仅阻断项） ==========
    $apiKey = Read-ApiKey -ScriptDir $PSScriptRoot
    $envCheck = Test-QuickEnvironment -ApiKey $apiKey
    if (-not $envCheck.Success) {
        foreach ($e in $envCheck.Errors) { Write-Status -Type "error" -Message $e }
        Write-Status -Type "warning" -Message "可运行 --diagnose 查看完整环境诊断"
        exit 1
    }
    Write-Status -Type "success" -Message "API 连接成功: $($script:Config.BaseUrl)"

    # ========== 获取工作区 ==========
    $workspaces = Get-Workspaces -ApiKey $apiKey
    if ($workspaces.Count -eq 0) {
        Write-Status -Type "error" -Message "没有可用的工作区，请先在 AnythingLLM 中创建工作区"
        exit 1
    }
    Write-Status -Type "info" -Message "已加载 $($workspaces.Count) 个工作区"

    # ========== 模式选择（多文件时） ==========
    $processingMode = "统一模式"
    if ($Files.Count -gt 1) {
        $mode = Select-MenuFromList -Items @(@{Label="统一模式";Desc="所有文件放入同一工作区"}, @{Label="逐项模式";Desc="每个文件分别选择工作区"}) `
            -Title "检测到 $($Files.Count) 个文件，选择处理模式" `
            -DisplayLabel { "$($_.Label)  —  $($_.Desc)" }
        if (-not $mode) { Write-Status -Type "warning" -Message "用户取消"; exit 0 }
        $processingMode = $mode.Label
    }

    # ========== 阶段一：工作区分配（仅收集，不执行） ==========
    $assignments = New-Object System.Collections.ArrayList   # v2.0：ArrayList 避免 += 的 O(n²)

    if ($processingMode -eq "统一模式") {
        $selected = Select-MenuFromList -Items $workspaces `
            -Title "选择目标工作区" -DisplayLabel { $_.name }
        if (-not $selected) { Write-Status -Type "warning" -Message "用户取消"; exit 0 }
        foreach ($f in $Files) {
            [void]$assignments.Add(@{ File=Split-Path $f -Leaf; FilePath=$f; Workspace=$selected.name; Slug=$selected.slug })
        }
    } else {
        foreach ($f in $Files) {
            Write-Host ""
            Write-Host "  分配文件: $(Split-Path $f -Leaf)" -ForegroundColor Magenta
            $selected = Select-MenuFromList -Items $workspaces -Title "为该文件选择工作区" -DisplayLabel { $_.name }
            if (-not $selected) {
                Write-Status -Type "warning" -Message "用户取消，已放弃本文件分配"
                continue
            }
            [void]$assignments.Add(@{ File=Split-Path $f -Leaf; FilePath=$f; Workspace=$selected.name; Slug=$selected.slug })
        }
    }

    if ($assignments.Count -eq 0) {
        Write-Status -Type "warning" -Message "未分配任何文件，退出"; exit 0
    }

    # 逐项模式：分配预览 + 确认
    if ($processingMode -eq "逐项模式") {
        Write-Section -Title "文件分配预览" -Color "yellow"
        foreach ($a in $assignments) {
            Write-Host "  $($a.File) → $($a.Workspace)" -ForegroundColor Gray
        }
        if (-not (Confirm-Action -Prompt "确认开始批量嵌入？")) { exit 0 }
    }

    # ========== 阶段二：批量上传（HttpClient multipart，逐个文件） ==========
    Write-Section -Title "上传阶段" -Color "cyan"
    $uploaded = New-Object System.Collections.ArrayList
    $failedUploads = New-Object System.Collections.ArrayList

    for ($i = 0; $i -lt $assignments.Count; $i++) {
        $a = $assignments[$i]
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        Write-Host ""
        Write-Host "  [$($i+1)/$($assignments.Count)] ⬆ 上传 $($a.File) ..." -ForegroundColor Gray

        $result = Upload-Document -ApiKey $apiKey -FilePath $a.FilePath
        $sw.Stop()

        if ($result.Success) {
            $a.Location = $result.Location
            [void]$uploaded.Add($a)
            Write-Status -Type "success" -Message "上传成功" -Detail $result.Location
        } else {
            [void]$failedUploads.Add($a)
            Write-Status -Type "error" -Message "上传失败: $($result.Error)"
            Write-OperationLog -File $a.File -Workspace $a.Workspace -Status "error" -DurationSec $sw.Elapsed.Seconds -Detail $result.Error
        }
    }

    if ($uploaded.Count -eq 0) {
        Write-Status -Type "error" -Message "所有文件上传失败，退出"; exit 1
    }

    # ========== 阶段三：按 slug 分组嵌入（每工作区一次 update-embeddings） ==========
    Write-Section -Title "嵌入阶段" -Color "green"
    $grouped = $uploaded | Group-Object -Property Slug

    foreach ($group in $grouped) {
        $slug = $group.Name
        $wsName = ($group.Group | Select-Object -First 1).Workspace
        $locations = @($group.Group | ForEach-Object { $_.Location })

        # 可选：重复检测（config.detectDuplicates）
        $deletes = @()
        if ($script:Config.DetectDuplicates) {
            $dups = Find-DuplicateDocuments -ApiKey $apiKey -Slug $slug -Locations $locations
            foreach ($newLoc in $dups.Keys) {
                $oldLoc = $dups[$newLoc]
                Write-Status -Type "warning" -Message "检测到同名旧文档: $oldLoc"
                # 询问：替换（deletes 旧）/ 保留
                $action = Confirm-Action -Prompt "替换旧版本？(N=保留两者)"
                if ($action) { $deletes += $oldLoc }
            }
        }

        Write-Host ""
        Write-Host "  工作区: $wsName | 文件数: $($locations.Count) | 嵌入中..."
        $embed = Set-WorkspaceEmbedding -ApiKey $apiKey -Slug $slug -Adds $locations -Deletes $deletes

        if (-not $embed.Success) {
            Write-Status -Type "error" -Message "嵌入失败: $($embed.Error)"
            foreach ($item in $group.Group) {
                Write-OperationLog -File $item.File -Workspace $item.Workspace -Status "error" -Detail "嵌入失败"
            }
            continue
        }

        # ========== 阶段四：轮询验证 ==========
        Write-Section -Title "验证阶段" -Color "magenta"
        foreach ($item in $group.Group) {
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            $verified = Confirm-Embedding -ApiKey $apiKey -Slug $slug -Location $item.Location `
                -MaxTries $script:Config.VerifyMaxTries -IntervalSec $script:Config.VerifyIntervalSec
            $sw.Stop()

            if ($verified) {
                Write-Status -Type "success" -Message "$($item.File) 验证通过"
                Write-OperationLog -File $item.File -Workspace $item.Workspace -Status "success" -DurationSec $sw.Elapsed.Seconds
            } else {
                Write-Status -Type "warning" -Message "$($item.File) 轮询超时未确认" -Detail "请检查 AnythingLLM 日志与嵌入器配置"
                Write-OperationLog -File $item.File -Workspace $item.Workspace -Status "warning" -DurationSec $sw.Elapsed.Seconds -Detail "轮询 $($script:Config.VerifyMaxTries) 次未确认"
            }
        }
    }

    # ========== 阶段五：测试聊天（仅对第一个成功上传的工作区） ==========
    if ($script:Config.AskForChatTest) {
        Write-Section -Title "测试" -Color "yellow"
        if (Confirm-Action -Prompt "是否测试聊天？") {
            $first = $uploaded[0]
            Write-Host "  发送测试消息到: $($first.Workspace)" -ForegroundColor Gray
            $chat = Send-ChatMessage -ApiKey $apiKey -Slug $first.Slug -Message "请简要介绍最近嵌入的文档内容"
            if ($chat.Success) {
                Write-Host ""
                Write-Host "  ╭─ 模型回复 ─────────────────────────────────────╮" -ForegroundColor Cyan
                Write-Host "  │ $($chat.Text)" -ForegroundColor White
                foreach ($src in $chat.Sources) {
                    Write-Host "  │   • $($src.title)" -ForegroundColor DarkCyan
                }
                Write-Host "  ╰─────────────────────────────────────────────────╯" -ForegroundColor Cyan
            } else {
                Write-Status -Type "error" -Message "聊天失败: $($chat.Error)"
            }
        }
    }

    # ========== 汇总 ==========
    $successCount = 0
    foreach ($r in $uploaded) { if ($r.Location) { $successCount++ } }
    Write-SummaryBox -Total $Files.Count -Success $successCount -Results $uploaded
    Write-Host ""
    Write-Host "  日志路径: $(Join-Path $PSScriptRoot $script:Config.LogDir)" -ForegroundColor DarkGray
    Write-Host ""
}

# 启动：必须显式传参（函数内 $args 是函数参数，不是脚本参数）
Main -Files $Files
exit 0
```

---

## 五、API 工作流（v2.0 更新）

### 5.1 探活（唯一阻断项）

```
GET {baseUrl}/api/v1/auth
Header: Authorization: Bearer {api_key}

期望响应: {"authenticated": true}
```

### 5.2 上传文件

```
POST {baseUrl}/api/v1/document/upload
Header: Authorization: Bearer {api_key}
Body: multipart/form-data
  - file = @filepath            ← 每次仅一个 file 字段（官方接口不支持多文件批量）
  - addToWorkspaces（可选）     ← 逗号分隔 slug，上传后立即嵌入

响应: {
  "success": true,
  "documents": [{ "location": "custom-documents/filename-<uuid>.json", "title": "...", ... }]
}
```

**关键坑点（保留 v1.2 结论）**：嵌入必须用 `location`，不是 `id`（docId）。传 docId 会静默失败。

**v2.0 补充**：
- 官方接口**一次只接受一个 `file` 字段**，不存在官方"批量上传"端点（第三方教程提到的 `/api/v1/document/upload/batch` 未经官方 openapi 证实，不可依赖）。批量 = 循环调用。
- `addToWorkspaces` 可在统一模式下省掉独立的 update-embeddings 阶段；但分组嵌入（每工作区一次 update-embeddings）在逐项模式/需要重复检测时更可控，作为主流程。

### 5.3 嵌入到工作区

```
POST {baseUrl}/api/v1/workspace/{slug}/update-embeddings
Header: Authorization: Bearer {api_key}
Content-Type: application/json
Body: {"adds": ["custom-documents/filename-uuid.json"], "deletes": []}

响应: 200 { "workspace": { "slug": "...", "documents": [...] }, "message": null }
```

**v2.0 注意**：200 只代表受理，嵌入异步完成；且官方存在已知 bug（返回 200 但工作区关联未写入，见 upstream #5901 / #1814）。必须轮询验证，不能以 HTTP 200 判定成功。

### 5.4 验证嵌入（轮询）

```
GET {baseUrl}/api/v1/workspace/{slug}
Header: Authorization: Bearer {api_key}

判定: response.documents[] 中存在 location == 已上传文档 location
轮询: 最多 5 次，间隔 3 秒（config.json 可调）
```

### 5.5 测试聊天

```
POST {baseUrl}/api/v1/workspace/{slug}/chat
Header: Authorization: Bearer {api_key}
Content-Type: application/json
Body: {"message": "...", "mode": "chat", "stream": false}

响应: { "textResponse": "...", "sources": [...] }
```

---

## 六、潜在 Bug 分析与规避（v2.0 更新表）

### 6.1 高风险（v2.0 已修复）

| 问题 | 症状 | v2.0 规避 |
|------|------|-----------|
| PS 5.1 无 `Invoke-RestMethod -Form` | 上传直接报"找不到参数 -Form" | 改用 `HttpClient` + `MultipartFormDataContent` |
| PS1 无 BOM + PS 5.1 解析 | 中文注释/字符串乱码 | PS1 存 UTF-8 带 BOM |
| `Main` 内用 `$args` | 拖拽文件列表丢失（函数参数为空） | 脚本级 `param([string[]]$Files)` + `Main -Files $Files` |
| ANSI 初始化语法错误 | 脚本解析失败 / 颜色失效 | 修正 P/Invoke 调用 + `SupportsVirtualTerminal` 降级 |
| BAT 内嵌 PowerShell 转义 | 命令被 cmd 展开破坏 | 保留 v1.2 方案：BAT 仅转发参数，逻辑全在 .ps1 |

### 6.2 中风险（v2.0 调整）

| 问题 | 场景 | v2.0 规避 |
|------|------|-----------|
| 编码不一致 | `chcp 936` + `OutputEncoding=UTF8` → 中文花屏 | 全链路 UTF-8：`chcp 65001` + UTF-8 BOM 脚本 + `OutputEncoding=UTF8` |
| apikey.txt 编码 | BOM/UTF-16 乱码 | 保留 v1.2 字节级检测（BOM/UTF-16/UTF-8） |
| 中文文件名 multipart | 上传后文件名乱码 | `MultipartFormDataContent.Add(name)` 按 UTF-8 生成 `filename*` |
| 路径特殊字符 | 空格/中文/`&` | 全流程用 `-LiteralPath` / 引号包裹 |

### 6.3 逻辑与健壮性（v2.0 新增）

| 问题 | 症状 | v2.0 规避 |
|------|------|-----------|
| 嵌入 200 假成功 | 返回 200 但文档不在工作区 | 轮询验证（5×3s）+ 失败给出可执行建议 |
| 同名文件重复嵌入 | 工作区重复向量 + 孤儿 json | 嵌入前 `Find-DuplicateDocuments` 比对 title，跳过或先 deletes 旧 location |
| 菜单越界 | 菜单靠近顶部时 `SetCursorPosition` 抛异常 | `menuTop < 1` 时 `Clear-Host` 重绘；工作区过多时过滤/分页 |
| 数组 `+=` 追加 | 大量文件时 O(n²) 卡顿 | `ArrayList` / `List[object]` |
| Docker 误阻断 | 桌面版用户被挡 | 快速检查只保留 API + Key 阻断，Docker/WSL 仅诊断警告 |
| 无文件校验 | 拖入目录/.exe 直接上传 | `Test-InputFile` 白名单 + 大小上限 |

### 6.4 低风险（保留 v1.2）

| 问题 | 场景 | 规避 |
|------|------|------|
| 连接超时 | AnythingLLM 未启动 | 探活失败友好提示 |
| 上传超时 | 大文件 | `uploadTimeoutSec` 默认 300s |
| ANSI 不支持 | Win10 1903 以下 | 检测失败自动降级纯文本 |
| 测试聊天超时 | LLM 推理慢 | `chatTimeoutSec` 默认 300s |

---

## 七、v2.0 优化清单（相对 v1.2 落实对照）

| 级别 | v1.2 原项 | v2.0 状态 |
|------|-----------|-----------|
| P0 | BAT + PS1 分离 | ✅ 保留 |
| P0 | 嵌入后验证 | ✅ 升级为轮询验证（5×3s） |
| P0 | API Key 编码检测 | ✅ 保留 |
| **P0 新增** | PS 5.1 multipart（-Form 不存在） | ✅ HttpClient 实现 |
| **P0 新增** | PS1 UTF-8 带 BOM | ✅ 编码约定 |
| **P0 新增** | `Main` 函数 `$args` 丢失 | ✅ param 传参 |
| **P0 新增** | ANSI 初始化代码错误 | ✅ 修正 P/Invoke |
| **P0 新增** | Docker 缺失误阻断 | ✅ 快速检查仅 API+Key |
| P1 | 彩虹进度条 | ⚠️ 改为「计数真实进度 + 旋转动画」，去掉假字节进度 |
| P1 | 操作日志 | ✅ 结构化一行日志（时间/状态/耗时/明细） |
| P1 | 彩色状态消息 | ✅ 保留 |
| P1 | 方向键菜单 | ✅ 合并为通用菜单 + 越界保护 |
| **P1 新增** | 文件预校验 | ✅ Test-InputFile |
| **P1 新增** | 重复/孤儿文档 | ✅ Find-DuplicateDocuments |
| **P1 新增** | 冗余探活 | ✅ 合并为单次 Test-QuickEnvironment |
| **P1 新增** | 数组 += O(n²) | ✅ ArrayList |
| P2 | 窗口标题动态更新 | ✅ 保留 |
| P2 | 结果汇总框 | ✅ 保留 |
| P2 | 多轮测试聊天 | ⏳ 后续版本（保存 sessionId） |
| P2 | 配置外置 | ✅ config.json |

---

## 八、测试验证清单（v2.0）

### 环境与编码

| # | 测试项 | 预期结果 |
|---|--------|----------|
| 1 | Windows PowerShell 5.1 启动 | 中文无乱码，ANSI 颜色正常 |
| 2 | PowerShell 7 启动 | 同样正常（兼容） |
| 3 | PS1 误存为无 BOM | 文档要求带 BOM；此场景应在构建期被 `fix-bat-encoding.ps1` 拦截 |
| 4 | BAT 编码校验 | `chcp 65001` 后中文输出正常 |
| 5 | 控制台代码页与 PS 输出编码一致 | 无花屏、✔✘⚠ 图标正常渲染 |

### 环境检测（v2.0 新口径）

| # | 测试项 | 预期结果 |
|---|--------|----------|
| 6 | Docker 未运行但 API 可达（桌面版） | ✅ 正常通过，Docker 仅诊断警告 |
| 7 | WSL 未安装 | ⚠ 警告，不阻断 |
| 8 | API 不可达 | ✘ 阻断并提示 `--diagnose` |
| 9 | API Key 无效 | ✘ 阻断（authenticated=false） |
| 10 | `--diagnose` | 输出完整环境诊断，全部不阻断 |

### 核心功能（含 v2.0 修正项）

| # | 测试项 | 预期结果 |
|---|--------|----------|
| 11 | 拖入单个 PDF | 直接进入上传（不弹模式选择） |
| 12 | 多文件 + 统一模式 | 选一次工作区，批量上传 + 分组嵌入 |
| 13 | 多文件 + 逐项模式 | 每文件选工作区 + 分配预览 + 确认 |
| 14 | ESC 取消各环节 | 优雅退出，退出码 0 |
| 15 | **拖入 .exe / 目录 / 超大文件** | 预校验拒绝，其余文件继续 |
| 16 | **同名文件再次拖入** | 提示跳过/替换/保留 |
| 17 | **上传成功但嵌入延迟** | 轮询最多 5×3s，最终确认或明确警告 |
| 18 | **update-embeddings 返回 200 但未关联（官方 bug 场景）** | 轮询超时后警告 + 提示检查日志，不误报成功 |
| 19 | 拖入文件名含中文/空格/`&` | 上传成功且 AnythingLLM 中文件名正确 |
| 20 | 退出码 | PS 正常结束 exit 0，异常 exit 1；BAT `%errorlevel%` 透传 |

### 日志与交互

| # | 测试项 | 预期结果 |
|---|--------|----------|
| 21 | 成功/失败/跳过均写日志 | 一行一条，含时间戳/状态/耗时 |
| 22 | 日志按日切文件 | `logs/YYYY-MM-DD_embed.log` |
| 23 | 旋转动画 | 上传/嵌入期间显示，结束后恢复光标行 |
| 24 | 菜单顶部越界（缩小窗口后选择） | 自动清屏重绘，不抛异常 |
| 25 | 工作区 > 20 个 | 过滤/分页可用 |

---

## 九、依赖与前置条件

| 依赖 | 版本要求 | 用途 | 检测方式 |
|------|---------|------|----------|
| Windows | 10 1903+ 或 Windows 11 | ANSI 虚拟终端支持 | `SupportsVirtualTerminal` / P/Invoke |
| PowerShell | 5.1+（7 兼容） | HTTP、菜单交互 | `$PSVersionTable.PSVersion` |
| WSL / Docker | 可选（仅诊断信息） | Docker 部署时的容器检测 | `wsl --version` / `docker --version` |
| AnythingLLM | 最新 | API 服务 | **API 探活（唯一硬性检测）** |
| apikey.txt | - | 认证凭证 | 文件存在性检测 |
| fix-bat-encoding.ps1 | v2.0 | BAT 编码后处理（转 UTF-8 无 BOM + CRLF） | - |

---

## 十、版本历史

### v2.0（2026-09-09）—— 兼容性重构

**修复（P0，实现前必须落实）**：
1. PS 5.1 无 `-Form`：multipart 改用 `HttpClient` + `MultipartFormDataContent`
2. PS1 必须 UTF-8 带 BOM（PS 5.1 无 BOM 按 ANSI/GBK 解析导致中文乱码）
3. `Main` 函数 `$args` 丢失问题：改为脚本级 `param([string[]]$Files)` + 显式传参
4. ANSI 初始化 P/Invoke 三处错误（`$modebor` 语法、`[ref]0` 字面量、bool/mode 混淆）
5. 环境检测不再把 Docker 缺失当阻断（支持桌面版/原生部署）

**改进（P1/P2）**：
6. 全链路 UTF-8（chcp 65001 + UTF-8 BOM），解决 GBK 方案下 Unicode 图标无法显示、输出编码冲突
7. 嵌入验证升级为轮询（5×3s），应对异步嵌入与官方"200 假成功"缺陷
8. 通用选择菜单（合并两个菜单）+ 越界保护 + 多工作区过滤
9. 文件预校验（白名单/大小/目录）、同名重复检测（跳过/替换/保留）
10. 结构化日志（时间戳/状态/耗时/明细，含失败路径）
11. 假进度条改为「真实计数进度 + 旋转动画」
12. `config.json` 外部配置（baseUrl/超时/白名单/聊天开关）
13. 数组追加改 ArrayList；退出码透传 BAT；修复 Write-Section 语法与 Banner 对齐
14. 冗余探活合并为单次快速检查；`--diagnose` 深度诊断模式

### v1.2（2026-09-09）—— 初版

- 新增系统环境检测模块（7 项检测）
- 原设计存档于 `EmbedIntoWorkspace-Design.md`

---

*文档版本：v2.0 | 更新日期：2026-09-09 | 兼容性重构版（基于 v1.2 设计评审）*
