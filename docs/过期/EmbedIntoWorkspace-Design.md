# AnythingLLM 文档嵌入工具 — 设计方案

> 拖拽文件到 BAT 脚本 → 自动上传并嵌入选定工作区 → 可选测试聊天

---

## 一、项目概览

### 功能目标

1. **拖拽即用**：将文件拖入 BAT 脚本，自动完成上传、嵌入、验证全流程
2. **多文件处理模式选择**：拖入多个文件时，提示选择：
   - **统一模式**：所有文件放入同一工作区（选一次工作区，批量执行）
   - **逐项模式**：每个文件分别选择工作区（全部选完后一次性批量嵌入）
3. **工作区选择**：弹出 PowerShell 方向键菜单，用户选择目标工作区（ESC 退出）
4. **嵌入验证**：嵌入后自动验证文档是否进入工作区 documents 列表
5. **可选测试**：嵌入完成后询问是否调用本地模型进行测试聊天
6. **操作日志**：每次操作记录到 `logs/YYYY-MM-DD_embed.log`

### 技术选型

| 组件 | 技术 | 原因 |
|------|------|------|
| 入口脚本 | `.bat` (GBK) | 拖拽关联、cmd 原生支持 |
| 核心逻辑 | PowerShell `.ps1` (UTF-8) | HTTP 调用、ANSI 美化、方向键交互 |
| API 通信 | `Invoke-RestMethod` / `Invoke-WebRequest` | 原生支持 JSON、multipart |
| 终端美化 | ANSI 24-bit True Color | Windows 10 1903+ 原生支持 |
| 状态图标 | Unicode 符号 (✔ ✘ ⚠ ⬆ ⚙) | 现代终端原生渲染 |

### 兼容性要求

- **操作系统**：Windows 10 1903+（支持 ANSI 虚拟终端）
- **PowerShell**：5.1+（Windows 自带）
- **AnythingLLM**：本地部署，地址 `http://localhost:3001`

---

## 二、文件结构

```
/home/admin/003anythingllmtools/
├── tools/
│   ├── EmbedIntoWorkspace.bat      ← BAT 包装器（GBK 编码）
│   ├── embed.ps1                   ← 核心 PowerShell 脚本（UTF-8 编码）
│   ├── apikey.txt                  ← API Key 文件
│   └── logs/                       ← 操作日志目录
│       └── YYYY-MM-DD_embed.log
├── apikey.txt                      ← 项目根目录备选 Key
└── docs/
    └── EmbedIntoWorkspace-Design.md ← 本文档
```

---

## 三、BAT 包装器设计

### 文件：`EmbedIntoWorkspace.bat`

**编码**：GBK (936) + CRLF + 无 BOM（由 `fix-bat-encoding.ps1` 后处理）

**职责**（仅 <15 行）：

```bat
@echo off
chcp 936 >nul 2>&1

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

pause
```

**关键点**：
- `%~dp0` 获取 BAT 所在目录（确保相对路径正确）
- `%*` 传递所有拖入的文件（支持多文件拖拽）
- `pause` 防止窗口自动关闭
- `chcp 936` 确保中文输出正常

---

## 四、PowerShell 核心脚本设计

### 文件：`embed.ps1`

**编码**：UTF-8 无 BOM（可包含中文注释）

### 4.1 模块划分

```powershell
# ============================================================
# embed.ps1 — AnythingLLM 文档嵌入工具
# 编码：UTF-8 | 终端：ANSI True Color
# ============================================================

#region ========== 配置与初始化 ==========
function Initialize-Environment { ... }
function Read-ApiKey { ... }
function Test-SystemEnvironment { ... }  # 环境检测
#endregion

#region ========== 终端美化 ==========
function Write-Banner { ... }
function Write-Section { ... }
function Write-Status { ... }
function Write-RainbowProgress { ... }
function Write-BoxMessage { ... }
#endregion

#region ========== API 调用 ==========
function Test-Connection { ... }
function Get-Workspaces { ... }
function Upload-Document { ... }
function Set-WorkspaceEmbedding { ... }
function Confirm-Embedding { ... }
function Send-ChatMessage { ... }
#endregion

#region ========== 交互菜单 ==========
function Select-ProcessingMode { ... }  # 统一/逐项模式选择
function Select-Workspace { ... }
function Confirm-Action { ... }
#endregion

#region ========== 日志 ==========
function Write-OperationLog { ... }
#endregion

#region ========== 主流程 ==========
function Main { ... }
#endregion

# 启动
Main
```

### 4.2 初始化流程

```powershell
function Initialize-Environment {
    # 1. 设置窗口标题
    $host.UI.RawUI.WindowTitle = "AnythingLLM 嵌入工具 v1.0"

    # 2. 启用 ANSI 虚拟终端（Windows 10 1903+）
    $kernel32 = Add-Type -MemberDefinition @"
        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern bool SetConsoleMode(IntPtr hConsoleHandle, int mode);
        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern IntPtr GetStdHandle(int nStdHandle);
"@ -Name "Kernel32" -Namespace "Win32" -PassThru

    $handle = $kernel32::GetStdHandle(-11)  # STD_OUTPUT_HANDLE
    $mode = $kernel32::GetConsoleMode($handle, [ref]0)
    $kernel32::SetConsoleMode($handle, $modebor 0x0004)  # ENABLE_VIRTUAL_TERMINAL_PROCESSING

    # 3. 设置 UTF-8 输出编码
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8

    # 4. 设置窗口大小
    try {
        $host.UI.RawUI.WindowSize = New-Object System.Management.Automation.Host.Size(80, 45)
    } catch { }

    # 5. 清屏
    Clear-Host
}
```

### 4.3 API Key 读取（编码检测）

```powershell
function Read-ApiKey {
    param([string]$ScriptDir)

    # 搜索顺序：脚本目录 → 项目根目录
    $keyPaths = @(
        Join-Path $ScriptDir "apikey.txt"
        Join-Path (Split-Path $ScriptDir -Parent) "apikey.txt"
    )

    $keyPath = $keyPaths | Where-Object { Test-Path $_ } | Select-Object -First 1

    if (-not $keyPath) {
        Write-Status -Type "error" -Message "未找到 apikey.txt"
        exit 1
    }

    # 自动检测编码
    $bytes = [System.IO.File]::ReadAllBytes($keyPath)

    # 检查 UTF-8 BOM (EF BB BF)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $apiKey = [System.Text.Encoding]::UTF8.GetString($bytes, 3, $bytes.Length - 3)
    }
    # 检查 UTF-16 LE BOM (FF FE)
    elseif ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE) {
        $apiKey = [System.Text.Encoding]::Unicode.GetString($bytes)
    }
    # 默认 UTF-8
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

### 4.4 系统环境检测

脚本启动时自动检测运行环境，确保满足前置条件。检测项按层级排列：操作系统 → PowerShell → WSL → Docker → AnythingLLM → API Key。

```powershell
function Test-SystemEnvironment {
    <#
    .SYNOPSIS
        检测系统环境是否满足运行条件
    .OUTPUTS
        @{ Success=[bool]; Info=[hashtable]; Errors=[string[]]; Warnings=[string[]] }
    #>

    $result = @{
        Success   = $true
        Info      = @{}
        Errors    = @()
        Warnings  = @()
    }

    # ─────────────────────────────────────────────
    # 1. 操作系统检测
    # ─────────────────────────────────────────────
    $os = Get-CimInstance -ClassName Win32_OperatingSystem
    $osVersion = [System.Environment]::OSVersion.Version
    $osName = $os.Caption

    $result.Info.OS = "$osName ($($osVersion.Major).$($osVersion.Minor).$($osVersion.Build))"

    # Windows 10 1903+ 或 Windows 11（Build 22000+）才支持 ANSI 虚拟终端
    if ($osVersion.Major -lt 10 -or ($osVersion.Major -eq 10 -and $osVersion.Build -lt 18362)) {
        $result.Errors += "操作系统版本过低: $osName（需要 Windows 10 1903+ 或 Windows 11）"
        $result.Success = $false
    }

    # ─────────────────────────────────────────────
    # 2. PowerShell 版本检测
    # ─────────────────────────────────────────────
    $psVersion = $PSVersionTable.PSVersion
    $result.Info.PowerShell = "v$psVersion"

    if ($psVersion.Major -lt 5) {
        $result.Errors += "PowerShell 版本过低: v$psVersion（需要 5.1+）"
        $result.Success = $false
    }

    # ─────────────────────────────────────────────
    # 3. WSL 检测
    # ─────────────────────────────────────────────
    $wslAvailable = $false
    $wslVersion = ""

    try {
        $wslOutput = wsl --version 2>&1 | Out-String
        if ($wslOutput -match "WSL\s+版本[：:]\s*(\S+)") {
            $wslVersion = $Matches[1]
        } elseif ($wslOutput -match "(\d+\.\d+\.\d+)") {
            $wslVersion = $Matches[1]
        }
        $wslAvailable = $true
        $result.Info.WSL = "v$wslVersion（可用）"
    } catch {
        $result.Warnings += "WSL 不可用或未安装（Docker Desktop 可能使用 Hyper-V 后端）"
        $result.Info.WSL = "未检测到"
    }

    # ─────────────────────────────────────────────
    # 4. Docker 检测
    # ─────────────────────────────────────────────
    $dockerAvailable = $false
    $dockerVersion = ""

    # 方法1：通过 WSL 检测 Docker
    if ($wslAvailable) {
        try {
            $dockerOutput = wsl docker --version 2>&1 | Out-String
            if ($dockerOutput -match "Docker version\s+(\S+)") {
                $dockerVersion = $Matches[1]
                $dockerAvailable = $true
                $result.Info.Docker = "v$dockerVersion（WSL 内）"
            }
        } catch { }
    }

    # 方法2：通过 Docker Desktop (Windows) 检测
    if (-not $dockerAvailable) {
        try {
            $dockerPath = Get-Command docker -ErrorAction SilentlyContinue
            if ($dockerPath) {
                $dockerOutput = & docker --version 2>&1 | Out-String
                if ($dockerOutput -match "Docker version\s+(\S+)") {
                    $dockerVersion = $Matches[1]
                    $dockerAvailable = $true
                    $result.Info.Docker = "v$dockerVersion（Docker Desktop）"
                }
            }
        } catch { }
    }

    if (-not $dockerAvailable) {
        $result.Errors += "Docker 未检测到（请确保 Docker Desktop 运行或 WSL 内已安装 Docker）"
        $result.Success = $false
    }

    # ─────────────────────────────────────────────
    # 5. AnythingLLM 容器检测
    # ─────────────────────────────────────────────
    $containerRunning = $false
    $containerName = ""

    if ($dockerAvailable) {
        try {
            $dockerCmd = if ($wslAvailable) { "wsl docker" } else { "docker" }
            $psOutput = Invoke-Expression "$dockerCmd ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'" 2>&1 | Out-String

            # 查找 AnythingLLM 相关容器
            $lines = $psOutput -split "`n" | Where-Object { $_ -match "anything" -or $_ -match "llm" }
            if ($lines) {
                $firstLine = ($lines | Select-Object -First 1).Trim()
                $parts = $firstLine -split "`t"
                $containerName = $parts[0]
                $containerImage = $parts[1]
                $containerStatus = $parts[2]
                $containerRunning = $true

                $result.Info.AnythingLLM = "容器: $containerName | 镜像: $containerImage | 状态: $containerStatus"
            }
        } catch { }
    }

    if (-not $containerRunning) {
        $result.Warnings += "未检测到 AnythingLLM 运行中的容器"
        $result.Info.AnythingLLM = "未运行"
    }

    # ─────────────────────────────────────────────
    # 6. AnythingLLM API 探活
    # ─────────────────────────────────────────────
    $baseUrl = "http://localhost:3001"
    $apiReachable = $false

    try {
        $authResponse = Invoke-RestMethod -Uri "$baseUrl/api/v1/auth" `
            -Method Get -TimeoutSec 5 -ErrorAction Stop
        $apiReachable = $true
        $result.Info.API = "$baseUrl — 可达（authenticated: $($authResponse.authenticated)）"
    } catch {
        $result.Warnings += "AnythingLLM API 不可达: $baseUrl（$($_.Exception.Message)）"
        $result.Info.API = "$baseUrl — 不可达"
    }

    # ─────────────────────────────────────────────
    # 7. API Key 文件检测
    # ─────────────────────────────────────────────
    $keyFound = $false
    $keyPaths = @(
        (Join-Path $PSScriptRoot "apikey.txt")
        (Join-Path (Split-Path $PSScriptRoot -Parent) "apikey.txt")
    )

    foreach ($kp in $keyPaths) {
        if (Test-Path $kp) {
            $keyFound = $true
            $result.Info.APIKey = "找到: $kp"
            break
        }
    }

    if (-not $keyFound) {
        $result.Errors += "未找到 apikey.txt（请在脚本目录或项目根目录放置）"
        $result.Success = $false
    }

    return $result
}
```

### 4.5 环境检测结果显示

```powershell
function Show-EnvironmentReport {
    param([hashtable]$EnvResult)

    Write-Section -Title "系统环境检测" -Color "cyan"

    # 信息项
    $items = @(
        @{ Key = "操作系统";   Value = $EnvResult.Info.OS }
        @{ Key = "PowerShell"; Value = $EnvResult.Info.PowerShell }
        @{ Key = "WSL";        Value = $EnvResult.Info.WSL }
        @{ Key = "Docker";     Value = $EnvResult.Info.Docker }
        @{ Key = "AnythingLLM"; Value = $EnvResult.Info.AnythingLLM }
        @{ Key = "API 地址";   Value = $EnvResult.Info.API }
        @{ Key = "API Key";    Value = $EnvResult.Info.APIKey }
    )

    foreach ($item in $items) {
        if ($item.Value) {
            $hasWarning = $EnvResult.Warnings | Where-Object { $_ -match $item.Key }
            $hasError = $EnvResult.Errors | Where-Object { $_ -match $item.Key }

            if ($hasError) {
                Write-Status -Type "error" -Message "$($item.Key): $($item.Value)"
            } elseif ($hasWarning) {
                Write-Status -Type "warning" -Message "$($item.Key): $($item.Value)"
            } else {
                Write-Status -Type "success" -Message "$($item.Key): $($item.Value)"
            }
        }
    }

    # 警告
    foreach ($w in $EnvResult.Warnings) {
        Write-Status -Type "warning" -Message $w
    }

    # 错误
    foreach ($e in $EnvResult.Errors) {
        Write-Status -Type "error" -Message $e
    }

    # 最终状态
    Write-Host ""
    if ($EnvResult.Success) {
        Write-Host "  " -NoNewline
        Write-Host "✔" -NoNewline -ForegroundColor Green
        Write-Host " 环境检测通过，可以继续" -ForegroundColor Green
    } else {
        Write-Host "  " -NoNewline
        Write-Host "✘" -NoNewline -ForegroundColor Red
        Write-Host " 环境检测未通过，请修复以上问题后重试" -ForegroundColor Red
    }
}
```

**环境检测输出效果**：

```
─── 系统环境检测 ────────────────────────────────────────
  ✔ 操作系统: Microsoft Windows 11 专业版 (10.0.22631)
  ✔ PowerShell: v5.1.22621.1
  ✔ WSL: v2.0.9（可用）
  ✔ Docker: v24.0.7（WSL 内）
  ✔ AnythingLLM: 容器: anythingllm | 镜像: mintplex/anythingllm | 状态: Up 3 days
  ✔ API 地址: http://localhost:3001 — 可达（authenticated: true）
  ✔ API Key: 找到: C:\tools\apikey.txt

  ✔ 环境检测通过，可以继续
```

---

## 五、终端美化系统

### 5.1 配色方案

| 元素 | ANSI 颜色代码 | 视觉效果 |
|------|--------------|----------|
| 标题/边框 | `\e[38;2;0;255;255m` | Cyan 亮青 |
| 文件名 | `\e[38;2;255;0;255m` | Magenta 品红 |
| 工作区名 | `\e[38;2;0;255;255m` | Cyan 亮青 |
| 成功 ✔ | `\e[38;2;0;200;0m` | Green 绿色 |
| 警告 ⚠ | `\e[38;2;255;255;0m` | Yellow 黄色 |
| 错误 ✘ | `\e[38;2;255;50;50m` | Red 红色 |
| 普通信息 | `\e[38;2;220;220;220m` | White 白色 |
| 分隔线 | `\e[38;2;100;100;100m` | Gray 灰色 |
| 重置 | `\e[0m` | 恢复默认 |

### 5.2 状态图标（Unicode）

| 图标 | Unicode | 含义 | 颜色 |
|------|---------|------|------|
| ✔ | U+2714 | 成功 | Green |
| ✘ | U+2718 | 失败 | Red |
| ⚠ | U+26A0 | 警告 | Yellow |
| ⬆ | U+2B06 | 上传中 | Cyan |
| ⚙ | U+2699 | 嵌入中 | Green |
| ★ | U+2605 | 当前选中 | Cyan |

### 5.3 标题横幅

```
╔══════════════════════════════════════════════════════════╗
║                                                          ║
║       ⚙  AnythingLLM 文档嵌入工具 v1.0                 ║
║       拖拽文件到此脚本即可自动嵌入工作区                ║
║                                                          ║
╚══════════════════════════════════════════════════════════╝
```

实现：

```powershell
function Write-Banner {
    $width = 58
    $border = "═" * ($width - 2)

    Write-Host ""
    Write-Host "╔$border╗" -ForegroundColor Cyan
    Write-Host ("║" + " " * $width + "║") -ForegroundColor Cyan
    Write-Host ("║  ⚙  " + "AnythingLLM 文档嵌入工具 v1.0".PadRight($width - 9) + "║") -ForegroundColor Cyan
    Write-Host ("║      " + "拖拽文件到此脚本即可自动嵌入工作区".PadRight($width - 9) + "║") -ForegroundColor Gray
    Write-Host ("║" + " " * $width + "║") -ForegroundColor Cyan
    Write-Host "╚$border╝" -ForegroundColor Cyan
    Write-Host ""
}
```

### 5.4 分隔线与区块标题

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

    $padTotal = 56
    $padLeft  = [math]::Floor(($padTotal - $Title.Length) / 2)
    $padRight = $padTotal - $Title.Length - $padLeft

    Write-Host ""
    Write-Host "─── " -NoNewline -ForegroundColor Gray
    Write-Host "$("─" * $padLeft) $Title $($ "─" * $padRight)" -NoNewline -ForegroundColor DarkGray
    Write-Host " ───" -ForegroundColor Gray
}
```

### 5.5 彩虹进度条

这是美化系统的核心组件，用于上传和嵌入阶段的进度展示。

```powershell
function Write-RainbowProgress {
    param(
        [string]$Activity,
        [int]$Percent,
        [int]$BarWidth = 30
    )

    # 彩虹色谱（7 色均匀分布）
    $rainbow = @(
        @(255, 0, 0)      # 红
        @(255, 127, 0)    # 橙
        @(255, 255, 0)    # 黄
        @(0, 200, 0)      # 绿
        @(0, 150, 255)    # 蓝
        @(75, 0, 130)     # 靛
        @(148, 0, 211)    # 紫
    )

    # 构建进度条
    $filled = [math]::Round($BarWidth * [math]::Min($Percent, 100) / 100)
    $bar = ""

    for ($i = 0; $i -lt $BarWidth; $i++) {
        if ($i -lt $filled) {
            # 根据位置计算彩虹颜色（循环映射）
            $ratio = $i / $BarWidth
            $colorIdx = [int]($ratio * 7) % 7
            $nextIdx = ($colorIdx + 1) % 7
            $subRatio = ($ratio * 7) - [int]($ratio * 7)

            # 颜色插值（平滑过渡）
            $r = [int]($rainbow[$colorIdx][0] * (1 - $subRatio) + $rainbow[$nextIdx][0] * $subRatio)
            $g = [int]($rainbow[$colorIdx][1] * (1 - $subRatio) + $rainbow[$nextIdx][1] * $subRatio)
            $b = [int]($rainbow[$colorIdx][2] * (1 - $subRatio) + $rainbow[$nextIdx][2] * $subRatio)

            $bar += "`e[38;2;${r};${g};${b}m█"
        } else {
            # 未填充部分：深灰色
            $bar += "`e[38;2;60;60;60m░"
        }
    }

    # 重置颜色
    $bar += "`e[0m"

    # 输出（\r 回车覆盖上一行）
    $percentColor = "`e[38;2;0;200;200m"
    $reset = "`e[0m"

    Write-Host "`r$Activity $bar ${percentColor}${Percent}%${reset}  " -NoNewline

    # 强制刷新输出
    [Console]::Out.Flush()
}
```

**使用示例**：

```powershell
# 模拟上传进度
for ($i = 0; $i -le 100; $i += 5) {
    Write-RainbowProgress -Activity "⬆ 上传中 document.pdf" -Percent $i
    Start-Sleep -Milliseconds 100
}
Write-Host ""  # 换行
```

**视觉效果**：

```
⬆ 上传中 document.pdf ████████████░░░░░░░░░░░░░░░░░░  40%  
```

### 5.6 状态消息

```powershell
function Write-Status {
    param(
        [ValidateSet("success","error","warning","info")]
        [string]$Type,
        [string]$Message,
        [string]$Detail = ""
    )

    $icon = switch ($Type) {
        "success" { "`e[38;2;0;200;0m✔`e[0m" }
        "error"   { "`e[38;2;255;50;50m✘`e[0m" }
        "warning" { "`e[38;2;255;255;0m⚠`e[0m" }
        "info"    { "`e[38;2;0;255;255mℹ`e[0m" }
    }

    $msgColor = switch ($Type) {
        "success" { "`e[38;2;0;200;0m" }
        "error"   { "`e[38;2;255;50;50m" }
        "warning" { "`e[38;2;255;255;0m" }
        "info"    { "`e[38;2;220;220;220m" }
    }

    Write-Host "  $icon ${msgColor}${Message}`e[0m" -NoNewline

    if ($Detail) {
        Write-Host " — $Detail" -ForegroundColor DarkGray
    } else {
        Write-Host ""
    }
}
```

**输出示例**：

```
  ✔ 上传成功 — custom-documents/file.pdf-abc123.json
  ✘ 上传失败 — 连接超时，请检查 AnythingLLM 是否运行
  ⚠ 嵌入可能未完成 — 请稍后手动验证
  ℹ 正在连接 http://localhost:3001 ...
```

### 5.7 方向键工作区选择菜单

```powershell
function Select-Workspace {
    param([array]$Workspaces)

    $selectedIndex = 0
    $totalItems = $Workspaces.Count

    # 绘制菜单
    function Draw-Menu {
        param([int]$Highlight)

        # 移动光标到菜单起始位置（假设菜单高度固定）
        $menuTop = [Console]::CursorTop - $totalItems - 4

        # 绘制边框
        [Console]::SetCursorPosition(0, $menuTop)
        Write-Host "┌─ "`e[38;2;0;255;255m请选择目标工作区`e[0m" ──────────────────────────────┐" -ForegroundColor DarkGray

        # 绘制空行
        Write-Host "│" -NoNewline -ForegroundColor DarkGray
        Write-Host (" " * 52) -NoNewline
        Write-Host "│" -ForegroundColor DarkGray

        # 绘制工作区列表
        for ($i = 0; $i -lt $totalItems; $i++) {
            [Console]::SetCursorPosition(0, $menuTop + 2 + $i)
            Write-Host "│" -NoNewline -ForegroundColor DarkGray

            if ($i -eq $Highlight) {
                # 选中项：Cyan 高亮 + ▶ 前缀
                $line = "  ▶ $($i + 1). $($Workspaces[$i].name)".PadRight(50)
                Write-Host $line -NoNewline -ForegroundColor Cyan
            } else {
                # 非选中项：White
                $line = "    $($i + 1). $($Workspaces[$i].name)".PadRight(50)
                Write-Host $line -NoNewline -ForegroundColor White
            }

            Write-Host "│" -ForegroundColor DarkGray
        }

        # 绘制空行
        Write-Host "│" -NoNewline -ForegroundColor DarkGray
        Write-Host (" " * 52) -NoNewline
        Write-Host "│" -ForegroundColor DarkGray

        # 绘制快捷键提示
        [Console]::SetCursorPosition(0, $menuTop + $totalItems + 4)
        Write-Host "└──────────────────────────────────────────────────────────┘" -ForegroundColor DarkGray
        Write-Host ""
        Write-Host "  " -NoNewline
        Write-Host "[↑↓]" -NoNewline -ForegroundColor Yellow
        Write-Host " 移动  " -NoNewline -ForegroundColor Gray
        Write-Host "[Enter]" -NoNewline -ForegroundColor Yellow
        Write-Host " 确认  " -NoNewline -ForegroundColor Gray
        Write-Host "[ESC]" -NoNewline -ForegroundColor Yellow
        Write-Host " 退出" -ForegroundColor Gray
    }

    # 初始绘制
    Write-Host ""
    $startRow = [Console]::CursorTop
    Draw-Menu -Highlight $selectedIndex

    # 按键循环
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
                "Enter" {
                    return $Workspaces[$selectedIndex]
                }
                "Escape" {
                    return $null
                }
            }
        }
        Start-Sleep -Milliseconds 10
    }
}
```

**菜单视觉效果**：

```
┌─ 请选择目标工作区 ──────────────────────────────┐
│                                                    │
│  ▶ 1. wsl子系统相关                                │
│    2. 人文社科                                     │
│    3. 网络维护                                     │
│    4. crx开发                                      │
│                                                    │
└──────────────────────────────────────────────────┘

  [↑↓] 移动  [Enter] 确认  [ESC] 退出
```

### 5.8 多文件处理模式选择菜单

当拖入多个文件时，弹出模式选择菜单：

```powershell
function Select-ProcessingMode {
    param([int]$FileCount)

    $options = @(
        @{ Label = "统一模式"; Desc = "所有 $FileCount 个文件放入同一工作区（选一次即可）" }
        @{ Label = "逐项模式"; Desc = "每个文件分别选择工作区（灵活分配）" }
    )

    $selectedIndex = 0
    $totalItems = $options.Count

    function Draw-ModeMenu {
        param([int]$Highlight)

        $menuTop = [Console]::CursorTop - $totalItems - 6

        [Console]::SetCursorPosition(0, $menuTop)
        Write-Host "┌─ "`e[38;2;255;255;0m检测到 $FileCount 个文件`e[0m" ─────────────────────────────┐" -ForegroundColor DarkGray
        Write-Host "│" -NoNewline -ForegroundColor DarkGray
        Write-Host (" " * 56) -NoNewline
        Write-Host "│" -ForegroundColor DarkGray

        for ($i = 0; $i -lt $totalItems; $i++) {
            [Console]::SetCursorPosition(0, $menuTop + 2 + $i)
            Write-Host "│" -NoNewline -ForegroundColor DarkGray

            if ($i -eq $Highlight) {
                $line = "  ▶ $($options[$i].Label)  —  $($options[$i].Desc)".PadRight(54)
                Write-Host $line -NoNewline -ForegroundColor Cyan
            } else {
                $line = "    $($options[$i].Label)  —  $($options[$i].Desc)".PadRight(54)
                Write-Host $line -NoNewline -ForegroundColor White
            }

            Write-Host "│" -ForegroundColor DarkGray
        }

        Write-Host "│" -NoNewline -ForegroundColor DarkGray
        Write-Host (" " * 56) -NoNewline
        Write-Host "│" -ForegroundColor DarkGray

        [Console]::SetCursorPosition(0, $menuTop + $totalItems + 4)
        Write-Host "└────────────────────────────────────────────────────────────────┘" -ForegroundColor DarkGray
        Write-Host ""
        Write-Host "  " -NoNewline
        Write-Host "[↑↓]" -NoNewline -ForegroundColor Yellow
        Write-Host " 移动  " -NoNewline -ForegroundColor Gray
        Write-Host "[Enter]" -NoNewline -ForegroundColor Yellow
        Write-Host " 确认  " -NoNewline -ForegroundColor Gray
        Write-Host "[ESC]" -NoNewline -ForegroundColor Yellow
        Write-Host " 退出" -ForegroundColor Gray
    }

    Write-Host ""
    $startRow = [Console]::CursorTop
    Draw-ModeMenu -Highlight $selectedIndex

    while ($true) {
        if ([Console]::KeyAvailable) {
            $key = [Console]::ReadKey($true)

            switch ($key.Key) {
                "UpArrow" {
                    $selectedIndex = ($selectedIndex - 1 + $totalItems) % $totalItems
                    Draw-ModeMenu -Highlight $selectedIndex
                }
                "DownArrow" {
                    $selectedIndex = ($selectedIndex + 1) % $totalItems
                    Draw-ModeMenu -Highlight $selectedIndex
                }
                "Enter" {
                    return $options[$selectedIndex].Label  # "统一模式" 或 "逐项模式"
                }
                "Escape" {
                    return $null
                }
            }
        }
        Start-Sleep -Milliseconds 10
    }
}
```

**菜单视觉效果**：

```
┌─ 检测到 3 个文件 ─────────────────────────────┐
│                                                  │
│  ▶ 统一模式  —  所有 3 个文件放入同一工作区      │
│    逐项模式  —  每个文件分别选择工作区            │
│                                                  │
└──────────────────────────────────────────────────┘

  [↑↓] 移动  [Enter] 确认  [ESC] 退出
```

### 5.9 逐项模式文件分配预览

逐项模式下，所有工作区选择完毕后，显示分配预览：

```powershell
function Write-AssignmentPreview {
    param([array]$Assignments)  # @(@{Index=0; File="a.pdf"; Workspace="人文社科"}, ...)

    Write-Host ""
    Write-Host "┌─ "`e[38;2;0;255;255m文件分配预览`e[0m" ─────────────────────────────────────┐" -ForegroundColor DarkGray

    foreach ($a in $Assignments) {
        Write-Host "│  " -NoNewline -ForegroundColor DarkGray
        Write-Host "$($a.File)" -NoNewline -ForegroundColor Magenta
        Write-Host " → " -NoNewline -ForegroundColor Gray
        Write-Host "$($a.Workspace)" -ForegroundColor Cyan
    }

    Write-Host "│" -NoNewline -ForegroundColor DarkGray
    Write-Host (" " * 56) -NoNewline
    Write-Host "│" -ForegroundColor DarkGray
    Write-Host "└────────────────────────────────────────────────────────────────┘" -ForegroundColor DarkGray
    Write-Host ""
    Write-Host "  确认执行？" -NoNewline -ForegroundColor Yellow
    Write-Host " [Enter]" -NoNewline -ForegroundColor Yellow
    Write-Host " 开始  " -NoNewline -ForegroundColor Gray
    Write-Host "[ESC]" -NoNewline -ForegroundColor Yellow
    Write-Host " 取消" -ForegroundColor Gray
}
```

**预览效果**：

```
┌─ 文件分配预览 ─────────────────────────────────────┐
│  document1.pdf → 人文社科                           │
│  document2.md  → crx开发                            │
│  document3.docx → 网络维护                          │
│                                                     │
└─────────────────────────────────────────────────────┘

  确认执行？ [Enter] 开始  [ESC] 取消
```

### 5.10 结果汇总框

```powershell
function Write-SummaryBox {
    param(
        [int]$Total,
        [int]$Success,
        [array]$Results  # @(@{File="..."; Workspace="..."; Status="success/error"; Detail="..."})
    )

    $failed = $Total - $Success

    Write-Host ""
    Write-Host "╔══════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
    Write-Host ("║  " + "处理完成: $Success/$Total 文件成功").PadRight(57) + "║" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Yellow" })
    Write-Host "╠══════════════════════════════════════════════════════════╣" -ForegroundColor Cyan

    foreach ($r in $Results) {
        $icon = if ($r.Status -eq "success") { "✔" } else { "✘" }
        $iconColor = if ($r.Status -eq "success") { "Green" } else { "Red" }

        $line = "  $icon $($r.File) → $($r.Workspace)"
        if ($r.Detail) { $line += " ($($r.Detail))" }

        Write-Host "║" -NoNewline -ForegroundColor Cyan
        Write-Host $line.PadRight(57) -NoNewline -ForegroundColor $iconColor
        Write-Host "║" -ForegroundColor Cyan
    }

    Write-Host "╚══════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
}
```

---

## 六、API 工作流

### 6.1 探活

```
GET http://localhost:3001/api/v1/auth
Header: Authorization: Bearer {api_key}

期望响应: {"authenticated": true}
```

### 6.2 上传文件

```
POST http://localhost:3001/api/v1/document/upload
Header: Authorization: Bearer {api_key}
Body: multipart/form-data { file: @filepath }

响应: {
    "success": true,
    "documents": [
        {
            "location": "custom-documents/filename-<uuid>.json",  ← 嵌入必须用这个
            "id": "...",  ← 不要用这个
            "title": "...",
            ...
        }
    ]
}
```

**关键坑点**：`adds` 必须传 `location`（如 `custom-documents/xxx.json`），不是 `id`（docId）。传 docId 会静默失败。

### 6.3 嵌入到工作区

```
POST http://localhost:3001/api/v1/workspace/{slug}/update-embeddings
Header: Authorization: Bearer {api_key}
Content-Type: application/json
Body: {"adds": ["custom-documents/filename-uuid.json"], "deletes": []}

期望响应: 200 OK
```

### 6.4 验证嵌入

```
GET http://localhost:3001/api/v1/workspace/{slug}
Header: Authorization: Bearer {api_key}

检查: response.documents 数组是否包含已上传的文档 location
```

### 6.5 测试聊天

```
POST http://localhost:3001/api/v1/workspace/{slug}/chat
Header: Authorization: Bearer {api_key}
Content-Type: application/json
Body: {"message": "请简要介绍这份文档的内容", "mode": "chat", "stream": false}

响应: {
    "textResponse": "...",
    "sources": [...]  ← 引用的文档来源
}
```

---

## 七、潜在 Bug 分析与规避

### 7.1 高风险：BAT 内嵌 PowerShell 转义

| 问题 | 症状 | 规避方案 |
|------|------|----------|
| `%%` 未转义 | `%` 被 cmd 展开为空 | BAT 中变量用 `%%` |
| `"` 未转义 | 命令截断 | 用 `^"` 或 here-string |
| `|` 未转义 | 被 cmd 解析为管道 | 用 `^|` 或拆分到单独 .ps1 |
| `$()` 子命令 | 被 cmd 当变量展开 | 用 `^(` 转义 |

**最终规避**：BAT 仅做参数转发，所有复杂逻辑在独立 `.ps1` 文件中，彻底消除转义问题。

### 7.2 中风险：编码问题

| 问题 | 场景 | 规避方案 |
|------|------|----------|
| apikey.txt 有 BOM | `type` 输出带乱码头 | PowerShell 按字节读取 + BOM 检测 |
| apikey.txt 是 UTF-16 | `type` 输出完全乱码 | 自动检测 UTF-16 BOM 并转码 |
| BAT 编码不对 | 中文显示为 `?` 或截断 | 用 `fix-bat-encoding.ps1` 后处理转 GBK |

### 7.3 中风险：路径中的特殊字符

| 问题 | 场景 | 规避方案 |
|------|------|----------|
| 路径含空格 | `C:\My Documents\file.pdf` | PowerShell 中用引号包裹 |
| 路径含中文 | `C:\用户\文档\file.pdf` | UTF-8 编码 + `[Console]::OutputEncoding` |
| 路径含 `&` | `C:\Tom & Jerry\file.pdf` | PowerShell 中用引号包裹 |

### 7.4 低风险：网络与超时

| 问题 | 场景 | 规避方案 |
|------|------|----------|
| 连接超时 | AnythingLLM 未启动 | 探活失败时友好提示 |
| 上传超时 | 大文件上传慢 | 设置 `-TimeoutSec 300` |
| 嵌入是异步 | upload 返回 ≠ 嵌入完成 | 嵌入后轮询验证 |

### 7.5 低风险：终端兼容性

| 问题 | 场景 | 规避方案 |
|------|------|----------|
| ANSI 不支持 | Win10 1903 以下 | 检测 SetConsoleMode 返回值，不支持时降级 |
| Unicode 符号不显示 | 旧版 cmd | 启动时设置 `[Console]::OutputEncoding` |

---

## 八、优化清单

### P0 必做

| 优化 | 说明 |
|------|------|
| BAT + PS1 分离 | 消除转义地狱，从根本上解决 80% 的潜在 bug |
| 嵌入后验证 | GET workspace 确认 documents 包含该文档，不只是"接口 200" |
| API Key 编码检测 | 自动处理 BOM/UTF-16/UTF-8 |

### P1 推荐

| 优化 | 说明 |
|------|------|
| 彩虹进度条 | 上传/嵌入过程实时进度展示 |
| 操作日志 | 记录到 `logs/YYYY-MM-DD_embed.log` |
| 彩色状态消息 | ✔/✘/⚠ + 颜色区分成功/失败/警告 |
| 方向键菜单 | PowerShell 原生交互，用户体验好 |

### P2 锦上添花

| 优化 | 说明 |
|------|------|
| 窗口标题动态更新 | `正在上传 (1/3): file.pdf` |
| 结果汇总框 | 全部处理完后显示汇总表格 |
| 多轮测试聊天 | 嵌入后支持多轮对话验证 |
| 配置外置 | `config.json` 存默认工作区、超时等 |

---

## 九、主流程伪代码

```powershell
function Main {
    # ========== 初始化 ==========
    Initialize-Environment
    Write-Banner

    # ========== 环境检测 ==========
    $envResult = Test-SystemEnvironment
    Show-EnvironmentReport -EnvResult $envResult

    if (-not $envResult.Success) {
        Write-Host ""
        Write-Host "  按任意键退出..." -ForegroundColor Gray
        [Console]::ReadKey($true) | Out-Null
        exit 1
    }

    # 连接性二次确认（环境检测已验证 API 可达）
    Write-Host ""
    Write-Host "  " -NoNewline
    Write-Host "ℹ" -NoNewline -ForegroundColor Cyan
    Write-Host " 正在验证 API 连接..." -ForegroundColor Gray

    # 读取 API Key
    $apiKey = Read-ApiKey -ScriptDir $PSScriptRoot

    # 探活（环境检测中已测试过，这里是最终确认）
    $connected = Test-Connection -ApiKey $apiKey
    if (-not $connected) {
        Write-Status -Type "error" -Message "API 连接失败，请检查 AnythingLLM 是否正常运行"
        exit 1
    }

    Write-Status -Type "success" -Message "API 连接成功"

    # 获取工作区列表
    $workspaces = Get-Workspaces -ApiKey $apiKey
    if ($workspaces.Count -eq 0) {
        Write-Status -Type "error" -Message "没有可用的工作区，请先在 AnythingLLM 中创建工作区"
        exit 1
    }

    Write-Status -Type "info" -Message "已加载 $($workspaces.Count) 个工作区"

    # ========== 文件列表 ==========
    $files = $args
    $totalFiles = $files.Count
    $results = @()

    # 显示文件列表
    Write-Section -Title "待处理文件" -Color "cyan"
    for ($i = 0; $i -lt $totalFiles; $i++) {
        $fileName = Split-Path $files[$i] -Leaf
        Write-Host "  $($i+1). " -NoNewline -ForegroundColor DarkCyan
        Write-Host $fileName -ForegroundColor Magenta
    }

    # ========== 模式选择（多文件时） ==========
    $processingMode = "统一模式"  # 默认

    if ($totalFiles -gt 1) {
        Write-Section -Title "处理模式" -Color "yellow"
        $mode = Select-ProcessingMode -FileCount $totalFiles

        if (-not $mode) {
            Write-Status -Type "warning" -Message "用户取消"
            exit 0
        }

        $processingMode = $mode
        Write-Status -Type "info" -Message "已选择: $processingMode"
    }

    # ========== 阶段一：工作区分配（仅收集，不执行） ==========
    $assignments = @()  # @(@{Index=0; File="a.pdf"; FilePath="C:\a.pdf"; Workspace="xxx"; Slug="xxx"}, ...)

    if ($processingMode -eq "统一模式") {
        # 统一模式：选一次工作区，应用到所有文件
        Write-Section -Title "选择目标工作区" -Color "magenta"
        $selected = Select-Workspace -Workspaces $workspaces

        if (-not $selected) {
            Write-Status -Type "warning" -Message "用户取消"
            exit 0
        }

        Write-Status -Type "info" -Message "已选择: $($selected.name)（将应用于全部 $totalFiles 个文件）"

        # 为每个文件创建分配记录
        for ($i = 0; $i -lt $totalFiles; $i++) {
            $assignments += @{
                Index      = $i
                File       = Split-Path $files[$i] -Leaf
                FilePath   = $files[$i]
                Workspace  = $selected.name
                Slug       = $selected.slug
            }
        }

    } else {
        # 逐项模式：每个文件分别选工作区
        Write-Section -Title "逐项分配工作区" -Color "magenta"

        for ($i = 0; $i -lt $totalFiles; $i++) {
            $fileName = Split-Path $files[$i] -Leaf

            Write-Host ""
            Write-Host "  分配文件 " -NoNewline -ForegroundColor Gray
            Write-Host "[$($i+1)/$totalFiles] " -NoNewline -ForegroundColor DarkCyan
            Write-Host $fileName -ForegroundColor Magenta

            $selected = Select-Workspace -Workspaces $workspaces

            if (-not $selected) {
                Write-Status -Type "warning" -Message "用户取消，已放弃全部分配"
                $assignments = @()
                break
            }

            Write-Status -Type "info" -Message "已选择: $($selected.name)"

            $assignments += @{
                Index      = $i
                File       = $fileName
                FilePath   = $files[$i]
                Workspace  = $selected.name
                Slug       = $selected.slug
            }
        }
    }

    # 检查是否有分配
    if ($assignments.Count -eq 0) {
        Write-Status -Type "warning" -Message "未分配任何文件，退出"
        exit 0
    }

    # 逐项模式：显示分配预览并确认
    if ($processingMode -eq "逐项模式" -and $totalFiles -gt 1) {
        Write-Section -Title "确认分配" -Color "yellow"
        Write-AssignmentPreview -Assignments $assignments

        $confirm = Confirm-Action -Prompt "确认开始批量嵌入？"
        if (-not $confirm) {
            Write-Status -Type "warning" -Message "用户取消"
            exit 0
        }
    }

    # ========== 阶段二：批量上传 ==========
    Write-Section -Title "上传阶段" -Color "cyan"
    $uploadedFiles = @()  # 上传成功的结果

    for ($i = 0; $i -lt $assignments.Count; $i++) {
        $a = $assignments[$i]
        Write-Host ""
        Write-Host "  上传 " -NoNewline -ForegroundColor Gray
        Write-Host "[$($i+1)/$($assignments.Count)] " -NoNewline -ForegroundColor DarkCyan
        Write-Host $a.File -ForegroundColor Magenta

        $uploadResult = Upload-Document -ApiKey $apiKey -FilePath $a.FilePath

        if ($uploadResult.Success) {
            Write-Status -Type "success" -Message "上传成功" -Detail $uploadResult.Location
            $a.Location = $uploadResult.Location
            $uploadedFiles += $a
        } else {
            Write-Status -Type "error" -Message "上传失败" -Detail $uploadResult.Error
            $results += @{File=$a.File; Workspace=$a.Workspace; Status="error"; Detail="上传失败"}
        }
    }

    if ($uploadedFiles.Count -eq 0) {
        Write-Status -Type "error" -Message "所有文件上传失败"
        exit 1
    }

    # ========== 阶段三：批量嵌入 ==========
    Write-Section -Title "嵌入阶段" -Color "green"

    # 按工作区 slug 分组（统一模式下只有一个分组）
    $grouped = $uploadedFiles | Group-Object -Property Slug

    foreach ($group in $grouped) {
        $slug = $group.Name
        $wsName = ($group.Group | Select-Object -First 1).Workspace
        $locations = $group.Group | ForEach-Object { $_.Location }

        Write-Host ""
        Write-Host "  工作区: " -NoNewline -ForegroundColor Gray
        Write-Host $wsName -ForegroundColor Cyan
        Write-Host "  文件数: " -NoNewline -ForegroundColor Gray
        Write-Host $locations.Count -ForegroundColor White

        $embedResult = Set-WorkspaceEmbedding -ApiKey $apiKey -Slug $slug -Locations $locations

        if ($embedResult.Success) {
            Write-Status -Type "success" -Message "嵌入触发成功"
        } else {
            Write-Status -Type "error" -Message "嵌入失败" -Detail $embedResult.Error
            foreach ($item in $group.Group) {
                $results += @{File=$item.File; Workspace=$item.Workspace; Status="error"; Detail="嵌入失败"}
            }
            continue
        }

        # ========== 阶段四：验证 ==========
        Write-Section -Title "验证阶段" -Color "magenta"

        foreach ($item in $group.Group) {
            $verified = Confirm-Embedding -ApiKey $apiKey -Slug $slug -Location $item.Location

            if ($verified) {
                Write-Status -Type "success" -Message "$($item.File) 验证通过"
                $results += @{File=$item.File; Workspace=$item.Workspace; Status="success"; Detail="嵌入成功"}
            } else {
                Write-Status -Type "warning" -Message "$($item.File) 可能正在异步处理"
                $results += @{File=$item.File; Workspace=$item.Workspace; Status="warning"; Detail="待确认"}
            }

            # 写日志
            Write-OperationLog -File $item.File -Workspace $item.Workspace -Status "success"
        }
    }

    # ========== 阶段五：测试聊天 ==========
    Write-Section -Title "测试" -Color "yellow"
    $testConfirm = Confirm-Action -Prompt "是否测试聊天？（将测试第一个工作区）"

    if ($testConfirm) {
        $testAssignment = $uploadedFiles[0]
        Write-Host ""
        Write-Host "  发送测试消息到: " -NoNewline -ForegroundColor Gray
        Write-Host $testAssignment.Workspace -ForegroundColor Cyan

        $chatResult = Send-ChatMessage -ApiKey $apiKey -Slug $testAssignment.Slug -Message "请简要介绍最近嵌入的文档内容"

        if ($chatResult) {
            Write-Host ""
            Write-Host "  ╭─ 模型回复 ─────────────────────────────────────╮" -ForegroundColor Cyan
            Write-Host "  │" -NoNewline -ForegroundColor Cyan
            Write-Host "  $($chatResult.Text)" -ForegroundColor White
            Write-Host "  │" -NoNewline -ForegroundColor Cyan

            if ($chatResult.Sources.Count -gt 0) {
                Write-Host "  │" -ForegroundColor Cyan
                Write-Host "  │  引用来源:" -ForegroundColor Gray
                foreach ($src in $chatResult.Sources) {
                    Write-Host "  │    • $($src.title)" -ForegroundColor DarkCyan
                }
            }

            Write-Host "  ╰─────────────────────────────────────────────────╯" -ForegroundColor Cyan
        }
    }

    # ========== 汇总 ==========
    $successCount = ($results | Where-Object { $_.Status -eq "success" }).Count
    Write-SummaryBox -Total $totalFiles -Success $successCount -Results $results

    Write-Host ""
    Write-Host "  日志路径: $logPath" -ForegroundColor DarkGray
    Write-Host ""
}
```

---

## 十、BAT 后处理

使用已有的 `bat-encoding-fixer` skill 将 BAT 文件转换为正确的编码：

```powershell
powershell -ExecutionPolicy Bypass -File "C:\path\to\fix-bat-encoding.ps1" "C:\path\to\EmbedIntoWorkspace.bat"
```

自动完成：
1. 检测当前编码
2. 转换为 GBK (936)
3. 换行符归一化为 CRLF
4. 去除 BOM
5. 字节级验证

---

## 十一、测试验证清单

### 环境检测

| # | 测试项 | 预期结果 |
|---|--------|----------|
| 1 | 正常环境启动 | 显示 7 项检测全部 ✔，通过 |
| 2 | WSL 未安装 | ⚠ WSL 警告，但 Docker Desktop 可用时仍可通过 |
| 3 | Docker 未运行 | ✘ Docker 错误，阻止继续 |
| 4 | AnythingLLM 容器未运行 | ⚠ 容器警告，API 探活失败时阻止继续 |
| 5 | API 地址不可达 | ⚠ API 警告，提示检查端口转发 |
| 6 | Windows 10 1809（不支持 ANSI） | ✘ 系统版本错误 |
| 7 | PowerShell 4.0 | ✘ PS 版本错误 |

### 核心功能

| # | 测试项 | 预期结果 |
|---|--------|----------|
| 8 | 拖入单个 PDF 文件 | 直接进入上传流程（不弹模式选择） |
| 9 | 拖入多个文件，选择统一模式 | 弹一次工作区菜单，所有文件上传后批量嵌入同一工作区 |
| 10 | 拖入多个文件，选择逐项模式 | 每个文件各弹一次工作区菜单，显示分配预览，确认后批量嵌入 |
| 11 | 逐项模式，ESC 取消某个文件 | 已选的分配保留，未选的跳过 |
| 12 | 逐项模式，ESC 在预览确认时 | 取消全部操作 |
| 13 | 统一模式，ESC 取消工作区选择 | 退出脚本 |
| 14 | 拖入空参数（双击运行） | 提示"请拖拽文件" |

### API Key 与认证

| # | 测试项 | 预期结果 |
|---|--------|----------|
| 15 | apikey.txt 不存在 | 环境检测报错，退出 |
| 16 | apikey.txt 为空 | 环境检测报错，退出 |
| 17 | apikey.txt 有 UTF-8 BOM | 正确读取 Key |
| 18 | apikey.txt 是 UTF-16 | 正确读取 Key |
| 19 | API Key 错误 | 401 认证失败提示 |
| 20 | 工作区列表为空 | 提示无可用工作区 |

### 交互与美化

| # | 测试项 | 预期结果 |
|---|--------|----------|
| 21 | 选择测试聊天 | 显示模型回复 + sources |
| 22 | 选择不测试 | 跳过 |
| 23 | 文件名含空格 | 正确处理 |
| 24 | 文件名含中文 | 正确处理 |
| 25 | 彩虹进度条显示 | 渐变颜色正常，百分比实时更新 |
| 26 | 日志文件生成 | 格式正确，内容完整 |
| 27 | 窗口美化显示 | 边框、图标、颜色正确渲染 |
| 28 | 统一模式批量嵌入 | 按 slug 分组，一次 API 调用嵌入多个文档 |
| 29 | 逐项模式分配预览 | 正确显示 文件→工作区 映射表 |

---

## 十二、依赖与前置条件

| 依赖 | 版本要求 | 用途 | 检测方式 |
|------|---------|------|----------|
| Windows | 10 1903+ 或 Windows 11 | ANSI 虚拟终端支持 | `Get-CimInstance Win32_OperatingSystem` |
| PowerShell | 5.1+ | HTTP 调用、菜单交互 | `$PSVersionTable.PSVersion` |
| WSL | 2.x（推荐） | Docker 运行环境 | `wsl --version` |
| Docker | 20.10+ | 容器运行时 | `docker --version` |
| AnythingLLM | 最新 | API 服务 | 容器运行状态 + API 探活 |
| apikey.txt | - | 认证凭证 | 文件存在性检测 |

### 用户环境参考

```
操作系统:  Windows 11 专业版
子系统:    WSL 2 (Ubuntu)
容器化:    Docker Desktop (WSL 2 后端)
知识库:    AnythingLLM (Docker 部署)
API 地址:  http://localhost:3001
```

---

*文档版本：v1.2 | 更新日期：2026-09-09 | 新增系统环境检测模块*
