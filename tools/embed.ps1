# ============================================================
# embed.ps1 — AnythingLLM 文档嵌入工具 V1.0
# 编码：UTF-8 (BOM) | 终端：chcp 65001 + 可选 ANSI True Color
# 调用：powershell -ExecutionPolicy Bypass -File embed.ps1 <file...> [-WorkspaceSlug <slug>] [-Folder <name>] [--diagnose] [--clean-logs] [-KeepDays N] [-MaxTotalMB N] [-Answer N] [--no-pause] [--help]
# 退出码：0 成功 / 1 错误 / 2 用户取消 / 3 部分失败
# v0.9：两段式传入逻辑 —— 先选"我的文档"文件夹，再选工作区，最后统一嵌入；
#       多文件支持"统一存入 / 逐个存入"两种模式（文件夹+工作区双维度）。
# ============================================================

param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$Files,          # 拖拽的文件列表（收集全部位置参数，支持多文件）
    [string]$WorkspaceSlug,    # 指定工作区 slug，跳过菜单选择
    [string]$Folder,           # 指定文档文件夹（我的文档），跳过菜单选择（默认 custom-documents）
    [string]$Mode = '',        # 多文件处理模式：unified（统一存入）/ per-file（逐个存入），自动化用
    [int]$KeepDays = -1,       # --clean-logs 保留天数（-1=使用配置默认值）
    [double]$MaxTotalMB = -1,  # --clean-logs 最大总大小 MB（-1=不限制）
    [int]$Answer = -1,         # 自动选择交互菜单项（1-4，自动化测试用）
    [switch]$Diagnose,         # 深度环境诊断（不阻断）
    [switch]$CleanLogs,        # 清理过期日志
    [switch]$NoPause,          # 自动化调用时不等待按键
    [switch]$Help
)

# 手动解析 --help 参数（PowerShell 不识别 --help 作为 switch）
if ($args -contains '--help' -or $args -contains '-h' -or $args -contains '-?') {
    Write-Host "用法: embed.ps1 <file...> [-WorkspaceSlug <slug>] [-Folder <name>] [--diagnose] [--clean-logs] [-KeepDays N] [-MaxTotalMB N] [-Answer N] [--no-pause] [--help]"
    Write-Host ""
    Write-Host "参数:"
    Write-Host "  <file...>              拖入一个或多个文件"
    Write-Host "  -WorkspaceSlug <slug>  指定工作区 slug，跳过菜单选择（适合自动化）"
    Write-Host "  -Folder <name>         指定文档文件夹（我的文档），跳过菜单选择；默认 custom-documents"
    Write-Host "  -Mode <unified|per-file>  多文件处理模式（自动化用；交互模式仍用菜单选择）"
    Write-Host "  --diagnose             深度环境诊断"
    Write-Host "  --clean-logs           清理过期日志"
    Write-Host "  -KeepDays <N>          日志保留天数（配合 --clean-logs，默认30）"
    Write-Host "  -MaxTotalMB <N>        日志最大总大小 MB（配合 --clean-logs，0=不限制）"
    Write-Host "  -Answer <N>            自动选择交互菜单项（1-4，自动化测试用）"
    Write-Host "  --no-pause             自动化调用时不等待按键"
    Write-Host "  --help                 显示帮助"
    Write-Host ""
    Write-Host "退出码: 0 成功 / 1 错误 / 2 用户取消 / 3 部分失败"
    exit 0
}

# 手动解析 --diagnose 和 --clean-logs（PowerShell 不识别 -- 前缀 switch）
# 注意：--diagnose 等会被 PowerShell 作为位置参数放入 $Files
if ($args -contains '--diagnose' -or ($Files -and $Files -contains '--diagnose')) { $Diagnose = $true }
if ($args -contains '--clean-logs' -or ($Files -and $Files -contains '--clean-logs')) { $CleanLogs = $true }
if ($args -contains '--no-pause' -or ($Files -and $Files -contains '--no-pause')) { $NoPause = $true }

# 从 Files 中移除被误识别的 -- 开头参数
if ($Files) { $Files = @($Files | Where-Object { $_ -notlike '--*' }) }

# PS 5.1 需要显式加载 System.Net.Http 程序集
try { Add-Type -Assembly System.Net.Http -ErrorAction Stop } catch { }

#region ========== 配置与初始化 ==========

function Initialize-Console {
    # 1. 窗口标题
    try { $host.UI.RawUI.WindowTitle = "AnythingLLM 嵌入工具 V1.0" } catch { }

    # 2. 能力探测：重定向检测 + ANSI 探测
    $script:IsOutputRedirected = [Console]::IsOutputRedirected
    $script:IsInputRedirected  = [Console]::IsInputRedirected
    $script:UseAnsi = $false

    if ($script:Config.UseTrueColor -and -not $script:IsOutputRedirected) {
        try {
            if (-not ('Win32.Kernel32V3' -as [type])) {
                $sig = @'
using System;
using System.Runtime.InteropServices;
public static class Kernel32V3 {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr GetStdHandle(int nStdHandle);
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool GetConsoleMode(IntPtr hConsoleHandle, out uint lpMode);
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool SetConsoleMode(IntPtr hConsoleHandle, uint dwMode);
}
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

function Read-Config {
    $script:Config = [ordered]@{
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
        FilenamePolicy          = "unicode"
    }

    $configPath = Join-Path $PSScriptRoot "config.json"
    if (Test-Path -LiteralPath $configPath) {
        $raw = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8
        # 剥离 // 与 /* */ 注释（PS 5.1 的 ConvertFrom-Json 不支持注释）
        $raw = [regex]::Replace($raw, '/\*.*?\*/', '', 'Singleline')
        $raw = [regex]::Replace($raw, '(^|\s)//.*$', '$1', 'Multiline')
        try {
            $fromFile = $raw | ConvertFrom-Json
        } catch {
            Write-Warning "config.json 解析失败，使用全部默认配置：$($_.Exception.Message)"
            return
        }
        foreach ($p in $fromFile.PSObject.Properties) {
            # 显式做 key 规范化，不依赖 hashtable 的大小写不敏感
            $k = $script:Config.Keys | Where-Object { $_ -ieq $p.Name } | Select-Object -First 1
            if ($k) { $script:Config[$k] = $p.Value }
        }
    }
}

function Assert-Config {
    $script:AssertWarnings = @()

    if ($script:Config.BaseUrl -notmatch '^https?://') {
        $script:AssertWarnings += "baseUrl 非法，回退默认"
        $script:Config.BaseUrl = "http://localhost:3001"
    }
    $script:Config.BaseUrl = $script:Config.BaseUrl.TrimEnd('/')

    foreach ($t in @('ProbeTimeoutSec','UploadTimeoutSec','EmbedTimeoutSec','ChatTimeoutSec','ApiTimeoutSec','VerifyTimeoutSec')) {
        $v = [int]$script:Config[$t]
        if ($v -le 0) { $script:AssertWarnings += "$t 非正数，回退 60"; $script:Config[$t] = 60 }
    }
    if ([int]$script:Config.VerifyBaseIntervalSec -le 0) {
        $script:AssertWarnings += "verifyBaseIntervalSec 非正数，回退 2"
        $script:Config.VerifyBaseIntervalSec = 2
    }
    if ([int]$script:Config.VerifyMaxIntervalSec -le 0) {
        $script:AssertWarnings += "verifyMaxIntervalSec 非正数，回退 5"
        $script:Config.VerifyMaxIntervalSec = 5
    }
    if ([double]$script:Config.MaxFileSizeMB -le 0) {
        $script:AssertWarnings += "maxFileSizeMB 非正数，回退 100"
        $script:Config.MaxFileSizeMB = 100
    }

    # 白名单补齐前导点并转小写
    $script:Config.AllowedExtensions = @($script:Config.AllowedExtensions | ForEach-Object {
        $e = "$_".ToLowerInvariant()
        if (-not $e.StartsWith('.')) { $e = ".$e" }
        $e
    })

    if ($script:Config.ChatMode -notin @('chat','query')) { $script:Config.ChatMode = 'query' }
    if ($script:Config.DuplicateDefaultAction -notin @('ask','skip','replace','keep')) {
        $script:Config.DuplicateDefaultAction = 'ask'
    }

    # LogDir 允许绝对路径
    if (-not [IO.Path]::IsPathRooted($script:Config.LogDir)) {
        $script:Config.LogDir = Join-Path $PSScriptRoot $script:Config.LogDir
    }
}

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
        # UTF-16 LE BOM：跳过 2 字节
        $apiKey = [Text.Encoding]::Unicode.GetString($bytes, 2, $bytes.Length - 2)
    }
    elseif ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF) {
        # UTF-16 BE BOM：跳过 2 字节
        $apiKey = [Text.Encoding]::BigEndianUnicode.GetString($bytes, 2, $bytes.Length - 2)
    }
    else {
        # 无 BOM：先按 UTF-8 严格解码，失败则回退 ANSI/GBK
        $strict = New-Object System.Text.UTF8Encoding($false, $true)
        try { $apiKey = $strict.GetString($bytes) }
        catch { $apiKey = [Text.Encoding]::Default.GetString($bytes) }
    }

    # Trim：去掉 BOM 残留、空白与所有控制字符
    $apiKey = ($apiKey -replace '^[\s\uFEFF\u200B]+', '') -replace '[\s\uFEFF\u200B]+$', ''
    if ([string]::IsNullOrEmpty($apiKey)) { throw "apikey.txt 内容为空：$keyPath" }
    return $apiKey
}

#endregion

#region ========== 终端美化 ==========

# 统一调色板：所有输出颜色集中管理（RGB 用于 ANSI 真彩，Fallback 用于传统终端）
$script:Palette = @{
    Border    = "0,200,200"   # 青色边框
    Title     = "255,255,255" # 白色标题
    Badge     = "255,210,0"   # 黄色徽章
    Accent    = "0,220,255"   # 强调青
    Success   = "0,255,100"
    Error     = "255,80,80"
    Warning   = "255,200,0"
    Info      = "180,180,180"
    Muted     = "120,120,120"
    Folder    = "0,180,255"   # 文档文件夹主题（青蓝）
    FolderHi  = "0,230,255"
    Workspace = "255,120,220" # 工作区主题（品红）
    WorkspaceHi = "255,170,255"
    Progress  = "0,255,255"
    ProgressWarn = "255,200,0"
    ProgressDone = "0,255,100"
}

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

function Write-Colored {
    # RGB 前景/背景；ANSI 不可用时回退传统色
    param(
        [string]$Text,
        [string]$Rgb = "0,255,255",
        [string]$FallbackColor = "Cyan",
        [string]$BgRgb = ""
    )
    if ($script:UseAnsi) {
        $esc = [char]27
        $rgbNorm = ($Rgb -replace '\s', '')
        $bg = if ($BgRgb) { ";48;2;$($BgRgb -replace '\s','')" } else { "" }
        Write-Host ("{0}[38;2;{1}{2}m{3}{0}[0m" -f $esc, $rgbNorm, $bg, $Text) -NoNewline
    } else {
        Write-Host $Text -ForegroundColor $FallbackColor -NoNewline
    }
}

function Write-Banner {
    # V1.0：统一 Format-Fixed 精确对齐，青边框 + 白标题 + 黄版本徽章 + 功能副标题
    $W = 54
    $top = '┌' + ('─' * $W) + '┐'
    $bot = '└' + ('─' * $W) + '┘'
    $blank = '│' + (' ' * $W) + '│'

    Write-Host ""
    Write-Colored $top $script:Palette.Border "Cyan"; Write-Host ""
    Write-Colored $blank $script:Palette.Border "Cyan"; Write-Host ""
    $t1 = Format-Fixed "  ◈  AnythingLLM 文档嵌入工具  ◈" $W 'Center'
    Write-Colored ('│' + $t1 + '│') $script:Palette.Title "White"; Write-Host ""
    $t2 = Format-Fixed ("  V1.0") $W 'Center'
    Write-Colored ('│' + $t2 + '│') $script:Palette.Badge "Yellow"; Write-Host ""
    $t3 = Format-Fixed "  拖入文件 → 选择文件夹 → 选择工作区 → 一键嵌入" $W 'Center'
    Write-Colored ('│' + $t3 + '│') $script:Palette.Info "Gray"; Write-Host ""
    Write-Colored $blank $script:Palette.Border "Cyan"; Write-Host ""
    Write-Colored $bot $script:Palette.Border "Cyan"; Write-Host ""
    Write-Host ""
}

function Write-Section {
    # V1.0：阶段徽章风格 —— ═══ [n] 标题 ═════════（n 可选）
    param([string]$Title, [string]$Color = "cyan", [int]$Number = 0)
    $colorMap = @{ "cyan" = "Cyan"; "yellow" = "Yellow"; "green" = "Green"; "magenta" = "Magenta"; "red" = "Red" }
    $fg = if ($colorMap.ContainsKey($Color)) { $colorMap[$Color] } else { "Cyan" }
    $rgb = switch ($Color) {
        'cyan'    { $script:Palette.Accent }
        'yellow'  { $script:Palette.Warning }
        'green'   { $script:Palette.Success }
        'magenta' { $script:Palette.Workspace }
        'red'     { $script:Palette.Error }
        default   { $script:Palette.Accent }
    }
    $num = if ($Number -gt 0) { "[$Number] " } else { "" }
    $head = "  $num$Title "
    $headW = Get-DisplayWidth $head
    $fillLen = [math]::Max(0, 52 - $headW)
    Write-Host ""
    Write-Colored $head $rgb $fg
    Write-Colored ("═" * $fillLen) $rgb $fg
    Write-Host ""
}

function Write-Status {
    param(
        [ValidateSet('success','error','warning','info')]
        [string]$Type,
        [string]$Message,
        [string]$Detail = ""
    )
    $icon = switch ($Type) {
        'success' { @{ Symbol = "✔"; Color = "Green";  Rgb = $script:Palette.Success } }
        'error'   { @{ Symbol = "✘"; Color = "Red";    Rgb = $script:Palette.Error } }
        'warning' { @{ Symbol = "⚠"; Color = "Yellow"; Rgb = $script:Palette.Warning } }
        'info'    { @{ Symbol = "ℹ"; Color = "Gray";   Rgb = $script:Palette.Info } }
    }
    Write-Colored "  $($icon.Symbol) " $icon.Rgb $icon.Color
    Write-Colored $Message $icon.Rgb $icon.Color
    Write-Host ""
    if ($Detail) { Write-Host "    $Detail" -ForegroundColor DarkGray }
}

function Write-BoxMessage {
    param(
        [string]$Title = "",
        [string[]]$Lines = @(),
        [string[]]$Footer = @()
    )
    $W = 46
    $top = '┌' + ('─' * $W) + '┐'
    $bot = '└' + ('─' * $W) + '┘'

    Write-Host ""
    Write-Host $top -ForegroundColor DarkGray
    if ($Title) {
        $titleLine = "  $Title"
        Write-Host ('│' + (Format-Fixed $titleLine $W) + '│') -ForegroundColor White
        Write-Host ('│' + (' ' * $W) + '│') -ForegroundColor DarkGray
    }
    foreach ($line in $Lines) {
        $inner = "  $line"
        Write-Host ('│' + (Format-Fixed $inner $W) + '│') -ForegroundColor Gray
    }
    foreach ($f in $Footer) {
        $inner = "  $f"
        Write-Host ('│' + (Format-Fixed $inner $W) + '│') -ForegroundColor DarkGray
    }
    Write-Host $bot -ForegroundColor DarkGray
}

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
        if ($show) { Write-Host ("`r" + (' ' * 78) + "`r") -NoNewline }
    }
}

function Write-CountProgress {
    param([int]$Current, [int]$Total, [string]$Label)
    if ($script:IsOutputRedirected) { return }
    $pct = if ($Total -gt 0) { [int](($Current / $Total) * 100) } else { 0 }
    $barLen = 30
    $filled = [math]::Round($barLen * $pct / 100)
    $bar = ('█' * $filled) + ('░' * ($barLen - $filled))
    # 进度变色：<50% 黄 → <100% 青 → 100% 绿
    $rgb = if ($pct -ge 100) { $script:Palette.ProgressDone }
           elseif ($pct -ge 50) { $script:Palette.Progress }
           else { $script:Palette.ProgressWarn }
    $fg  = if ($pct -ge 100) { "Green" } elseif ($pct -ge 50) { "Cyan" } else { "Yellow" }
    Write-Colored ("`r  {0} [" -f $Label) $script:Palette.Info "Gray"
    Write-Colored $bar $rgb $fg
    Write-Colored ("] {0}/{1} ({2}%)   " -f $Current, $Total, $pct) $script:Palette.Info "Gray"
    if ($Current -ge $Total) { Write-Host "" }
}

function Write-SummaryBox {
    param([hashtable]$Stat)
    $W = 46
    $ok  = ([int]$Stat.Failed -eq 0)
    $brdRgb = if ($ok) { $script:Palette.Success } else { $script:Palette.Error }
    $brdFg  = if ($ok) { "Green" } else { "Red" }
    $top = '┌' + ('─' * $W) + '┐'
    $bot = '└' + ('─' * $W) + '┘'

    Write-Host ""
    Write-Colored $top $brdRgb $brdFg; Write-Host ""
    $title = Format-Fixed "  执行汇总" $W
    Write-Colored ('│' + $title + '│') $script:Palette.Title "White"; Write-Host ""
    Write-Colored ('│' + (' ' * $W) + '│') $script:Palette.Muted "DarkGray"; Write-Host ""

    $items = @(
        @{ Label = "总计"; Value = $Stat.Total;    Color = "White";   Rgb = $script:Palette.Title },
        @{ Label = "已上传"; Value = $Stat.Uploaded; Color = "Green"; Rgb = $script:Palette.Success },
        @{ Label = "已验证"; Value = $Stat.Verified; Color = "Green"; Rgb = $script:Palette.Success },
        @{ Label = "已跳过"; Value = $Stat.Skipped;  Color = "Yellow"; Rgb = $script:Palette.Warning },
        @{ Label = "失败"; Value = $Stat.Failed;   Color = "Red";    Rgb = $script:Palette.Error },
        @{ Label = "总耗时"; Value = "$($Stat.ElapsedSec)s"; Color = "Cyan"; Rgb = $script:Palette.Accent }
    )
    foreach ($item in $items) {
        $line = "  $($item.Label): $($item.Value)"
        $inner = Format-Fixed $line $W
        Write-Colored ('│' + $inner + '│') $item.Rgb $item.Color; Write-Host ""
    }

    Write-Colored $bot $brdRgb $brdFg; Write-Host ""
}

#endregion

#region ========== API 统一层 ==========

function Get-HttpClient {
    if (-not $script:Http) {
        $script:Http = New-Object System.Net.Http.HttpClient
        # 使用最大超时值（上传 300s），避免修改已使用的 HttpClient.Timeout
        $maxTimeout = [Math]::Max($script:Config.UploadTimeoutSec,
                      [Math]::Max($script:Config.EmbedTimeoutSec,
                      [Math]::Max($script:Config.ChatTimeoutSec, $script:Config.ApiTimeoutSec)))
        $script:Http.Timeout = [TimeSpan]::FromSeconds($maxTimeout)
    }
    return $script:Http
}

function Invoke-Api {
    param(
        [ValidateSet('GET','POST','DELETE')] [string]$Method,
        [string]$Path,
        [string]$ApiKey = $script:ApiKey,
        [object]$Body = $null,
        [int]$TimeoutSec = 0
    )

    $client = Get-HttpClient

    $uri  = "$($script:Config.BaseUrl.TrimEnd('/'))$Path"
    $req  = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::$Method, $uri)
    if ($ApiKey) { $req.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $ApiKey) }

    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 8 -Compress
        $req.Content = New-Object System.Net.Http.StringContent($json, (New-Object System.Text.UTF8Encoding($false)), "application/json")
    }

    try {
        $resp = $client.SendAsync($req).GetAwaiter().GetResult()
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

function Start-Api {
    param(
        [ValidateSet('GET','POST','DELETE')] [string]$Method,
        [string]$Path,
        [string]$ApiKey = $script:ApiKey,
        [object]$Body = $null,
        [int]$TimeoutSec = 0
    )
    $client = Get-HttpClient

    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::$Method,
               "$($script:Config.BaseUrl.TrimEnd('/'))$Path")
    if ($ApiKey) { $req.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $ApiKey) }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 8 -Compress
        $req.Content = New-Object System.Net.Http.StringContent($json, (New-Object System.Text.UTF8Encoding($false)), "application/json")
    }
    return @{ Request = $req; Task = $client.SendAsync($req) }
}

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
    try { $Response.Dispose() } catch { }
    return $result
}

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

function Invoke-FullDiagnose {
    Write-Host ""
    Write-Host "  === 环境诊断 ===" -ForegroundColor Cyan

    # 1. 操作系统
    try {
        $os = Get-CimInstance Win32_OperatingSystem -ErrorAction SilentlyContinue
        if ($os) { Write-Host "  OS: $($os.Caption) $($os.Version)" -ForegroundColor Gray }
        else { Write-Host "  OS: 非 Windows 或无法获取" -ForegroundColor Gray }
    } catch { Write-Host "  OS: 无法获取" -ForegroundColor Gray }

    # 2. PowerShell
    Write-Host "  PowerShell: $($PSVersionTable.PSVersion)" -ForegroundColor Gray

    # 3. WSL（带超时，防挂起）
    try {
        $wsl = Start-Process -FilePath "wsl" -ArgumentList "--version" `
               -NoNewWindow -Wait -PassThru -ErrorAction Stop
        if ($wsl.ExitCode -eq 0) { Write-Host "  WSL: 已安装" -ForegroundColor Gray }
        else { Write-Host "  WSL: 已安装但版本查询失败" -ForegroundColor Yellow }
    } catch {
        Write-Host "  WSL: 未安装（不影响功能）" -ForegroundColor Yellow
    }

    # 4. Docker（不通过 wsl 间接调用）
    try {
        $dockerVer = & docker --version 2>&1
        if ($LASTEXITCODE -eq 0) { Write-Host "  Docker: $dockerVer" -ForegroundColor Gray }
        else { Write-Host "  Docker: 已安装但运行异常" -ForegroundColor Yellow }
    } catch {
        Write-Host "  Docker: 未安装（桌面版可正常运行）" -ForegroundColor Yellow
    }

    # 5. API 可达性：ping + auth
    Write-Host ""
    Write-Host "  --- API 可达性 ---" -ForegroundColor Cyan
    if ($script:Config.BaseUrl) {
        try {
            $pingResp = Invoke-Api -Method GET -Path "/api/ping" -TimeoutSec $script:Config.ProbeTimeoutSec
            if ($pingResp.Success) { Write-Host "  /api/ping: OK (200)" -ForegroundColor Green }
            else { Write-Host "  /api/ping: 失败 (StatusCode=$($pingResp.StatusCode))" -ForegroundColor Yellow }
        } catch { Write-Host "  /api/ping: 无法连接" -ForegroundColor Yellow }

        if ($script:ApiKey) {
            try {
                $authResp = Invoke-Api -Method GET -Path "/api/v1/auth" -ApiKey $script:ApiKey -TimeoutSec $script:Config.ProbeTimeoutSec
                if ($authResp.Success) { Write-Host "  /api/v1/auth: OK (认证有效)" -ForegroundColor Green }
                else { Write-Host "  /api/v1/auth: 失败 (StatusCode=$($authResp.StatusCode))" -ForegroundColor Yellow }
            } catch { Write-Host "  /api/v1/auth: 无法连接" -ForegroundColor Yellow }
        } else {
            Write-Host "  /api/v1/auth: 跳过（无 API Key）" -ForegroundColor Yellow
        }
    } else {
        Write-Host "  BaseUrl 未配置，跳过 API 可达性检测" -ForegroundColor Yellow
    }

    # 6. API Key 来源 + 脱敏
    Write-Host ""
    Write-Host "  --- API Key ---" -ForegroundColor Cyan
    if ($script:ApiKey) {
        $keyLen = $script:ApiKey.Length
        $last4 = if ($keyLen -ge 4) { $script:ApiKey.Substring($keyLen - 4) } else { $script:ApiKey }
        Write-Host "  长度: $keyLen 字符 | 末4位: ****$last4" -ForegroundColor Gray
    } else {
        Write-Host "  未加载" -ForegroundColor Yellow
    }

    # 7. 嵌入模型
    Write-Host ""
    Write-Host "  --- 嵌入模型 ---" -ForegroundColor Cyan
    if ($script:ApiKey) {
        try {
            $embedResp = Invoke-Api -Method GET -Path "/api/v1/system/embedder" -ApiKey $script:ApiKey -TimeoutSec $script:Config.ProbeTimeoutSec
            if ($embedResp.Success -and $embedResp.Data) {
                $embedInfo = $embedResp.Data
                $modelName = if ($embedInfo.model) { $embedInfo.model } elseif ($embedInfo.embedder) { $embedInfo.embedder } else { "未知" }
                Write-Host "  模型: $modelName" -ForegroundColor Green
            } else {
                Write-Host "  无法获取嵌入模型信息" -ForegroundColor Yellow
            }
        } catch { Write-Host "  无法获取嵌入模型信息" -ForegroundColor Yellow }
    } else {
        Write-Host "  跳过（无 API Key）" -ForegroundColor Yellow
    }

    # 8. config 生效值
    Write-Host ""
    Write-Host "  --- Config 生效值 ---" -ForegroundColor Cyan
    if ($script:Config) {
        $cfg = $script:Config
        Write-Host "  BaseUrl:            $($cfg.BaseUrl)" -ForegroundColor Gray
        Write-Host "  UploadTimeoutSec:   $($cfg.UploadTimeoutSec)" -ForegroundColor Gray
        Write-Host "  EmbedTimeoutSec:    $($cfg.EmbedTimeoutSec)" -ForegroundColor Gray
        Write-Host "  ChatTimeoutSec:     $($cfg.ChatTimeoutSec)" -ForegroundColor Gray
        Write-Host "  ProbeTimeoutSec:    $($cfg.ProbeTimeoutSec)" -ForegroundColor Gray
        Write-Host "  MaxFileSizeMB:      $($cfg.MaxFileSizeMB)" -ForegroundColor Gray
        Write-Host "  AllowedExtensions:  $($cfg.AllowedExtensions -join ', ')" -ForegroundColor Gray
        Write-Host "  DetectDuplicates:   $($cfg.DetectDuplicates)" -ForegroundColor Gray
        Write-Host "  DuplicateDefault:   $($cfg.DuplicateDefaultAction)" -ForegroundColor Gray
        Write-Host "  AskForChatTest:     $($cfg.AskForChatTest)" -ForegroundColor Gray
        Write-Host "  ChatMode:           $($cfg.ChatMode)" -ForegroundColor Gray
        Write-Host "  LogRetentionDays:   $($cfg.LogRetentionDays)" -ForegroundColor Gray
        Write-Host "  LogDir:             $($cfg.LogDir)" -ForegroundColor Gray
    }

    # 9. 日志治理
    Write-Host ""
    Write-Host "  --- 日志目录 ---" -ForegroundColor Cyan
    $logDir = $script:Config.LogDir
    if (Test-Path -LiteralPath $logDir) {
        $logFiles = @(Get-ChildItem -LiteralPath $logDir -Filter "*_embed.jsonl" -File -ErrorAction SilentlyContinue)
        $totalMB = [math]::Round(($logFiles | Measure-Object -Property Length -Sum).Sum / 1MB, 2)
        Write-Host "  文件数: $($logFiles.Count) | 总大小: $totalMB MB" -ForegroundColor Gray
    } else {
        Write-Host "  日志目录不存在: $logDir" -ForegroundColor Yellow
    }

    # 10. 文档残留
    Write-Host ""
    Write-Host "  --- 文档残留 ---" -ForegroundColor Cyan
    if ($script:ApiKey) {
        try {
            $docsResp = Invoke-Api -Method GET -Path "/api/v1/documents" -ApiKey $script:ApiKey -TimeoutSec $script:Config.ProbeTimeoutSec
            if ($docsResp.Success -and $docsResp.Data) {
                $allDocs = @($docsResp.Data.documents)
                $customDocs = @($allDocs | Where-Object { $_.location -like "custom-documents/*" })
                $tPrefix = @($customDocs | Where-Object { $_.filename -match "^t_" })
                Write-Host "  custom-documents 总数: $($customDocs.Count) | t_* 残留: $($tPrefix.Count)" -ForegroundColor Gray
                if ($tPrefix.Count -gt 0) {
                    Write-Host "  残留文件:" -ForegroundColor Yellow
                    foreach ($d in $tPrefix) { Write-Host "    • $($d.filename)" -ForegroundColor Yellow }
                }
            } else {
                Write-Host "  无法获取文档列表" -ForegroundColor Yellow
            }
        } catch { Write-Host "  无法获取文档列表" -ForegroundColor Yellow }
    } else {
        Write-Host "  跳过（无 API Key）" -ForegroundColor Yellow
    }

    Write-Host ""
}

function Get-Workspaces {
    param([string]$ApiKey)
    $r = Invoke-Api -Method GET -Path "/api/v1/workspaces" -ApiKey $ApiKey
    if (-not $r.Success) { return @() }
    return @($r.Data.workspaces)
}

function New-Workspace {
    # 创建新工作区（POST /api/v1/workspace/new），返回工作区对象或 $null
    param([string]$ApiKey, [string]$Name)
    if ([string]::IsNullOrWhiteSpace($Name)) { return $null }
    $r = Invoke-Api -Method POST -Path "/api/v1/workspace/new" -ApiKey $ApiKey -Body @{ name = $Name }
    if (-not $r.Success -or -not $r.Data -or -not $r.Data.workspace) { return $null }
    return $r.Data.workspace
}

function Select-Workspace {
    # 交互选择工作区：菜单列出已有工作区 + "＋ 新建工作区…" 选项（与文件夹选择一致）。
    # 选择新建后立即输入名称并创建：重名时提示并重新命名，直到成功或取消。
    # 返回新工作区对象；用户取消返回 $null。
    param(
        [object[]]$Workspaces,
        [string]$Title = "选择目标工作区"
    )
    $existing = @($Workspaces)

    $items = New-Object System.Collections.ArrayList
    foreach ($ws in $existing) {
        [void]$items.Add([pscustomobject]@{ Label = $ws.name; Slug = $ws.slug; New = $false })
    }
    if ($existing.Count -eq 0) {
        Write-Status -Type "info" -Message "没有可用工作区，将引导新建"
    } else {
        [void]$items.Add([pscustomobject]@{ Label = '＋ 新建工作区…'; Slug = ''; New = $true })
        $sel = Select-MenuFromList -Items @($items) -Title $Title -DisplayLabel { $_.Label } -Theme 'workspace'
        if (-not $sel) { return $null }
        if (-not $sel.New) {
            return ($existing | Where-Object { $_.slug -eq $sel.Slug } | Select-Object -First 1)
        }
    }

    # 新建工作区：输入名称 → 重名则提示并重新命名 → 立即创建
    while ($true) {
        Write-Host ""
        Write-Host "  输入新工作区名称（回车取消）：" -ForegroundColor Cyan -NoNewline
        $name = Read-Host
        $name = $name.Trim()
        if ([string]::IsNullOrWhiteSpace($name)) { Write-Status -Type "warning" -Message "已取消新建"; return $null }
        $exists = $existing | Where-Object { $_.name -eq $name } | Select-Object -First 1
        if ($exists) {
            Write-Status -Type "warning" -Message "工作区「$name」已存在，请重新命名"
            continue
        }
        $new = New-Workspace -ApiKey $script:ApiKey -Name $name
        if (-not $new) { Write-Status -Type "error" -Message "创建工作区「$name」失败"; return $null }
        Write-Status -Type "success" -Message "已创建工作区「$name」($($new.slug))"
        return $new
    }
}

function Get-Workspace {
    param(
        [string]$ApiKey,
        [string]$Slug
    )
    $r = Invoke-Api -Method GET -Path "/api/v1/workspace/$Slug" -ApiKey $ApiKey
    if (-not $r.Success) { return $null }
    return $r.Data.workspace
}

function Get-DocumentFolders {
    # 获取"我的文档"文件夹列表（本地 documents 存储的一级文件夹）。
    # GET /api/v1/documents 返回 localFiles.items 树形结构，type=folder 即一级文件夹。
    param([string]$ApiKey)
    $r = Invoke-Api -Method GET -Path "/api/v1/documents" -ApiKey $ApiKey
    if (-not $r.Success -or -not $r.Data.localFiles -or -not $r.Data.localFiles.items) { return @('custom-documents') }
    $folders = @($r.Data.localFiles.items | Where-Object { $_.type -eq 'folder' } | ForEach-Object { $_.name })
    if ($folders -notcontains 'custom-documents') { $folders = @('custom-documents') + $folders }
    return @($folders | Select-Object -Unique)
}

function Clean-EmptyXlsxDirs {
    # 清理 AnythingLLM 官方 xlsx 转换残留的空目录。
    # 根因：collector asXlsx.js 处理 xlsx 时创建 <文件名>-<4位hash> 临时目录存放各 sheet 的
    # CSV，随后 moveProcessedDocsToFolder 把 CSV 移入目标文件夹，但空目录本身不会被删除，
    # 于是 documents 顶层留下 <name>.xlsx-xxxx 空文件夹（官方行为，不改服务端）。
    # 本函数在上传阶段完成后调用：匹配 *.xlsx-<4位hex> 且 items 为空的目录，用
    # DELETE /api/v1/document/remove-folder 删除（仅删空目录，绝不触碰有内容的文件夹）。
    param([string]$ApiKey)
    $r = Invoke-Api -Method GET -Path "/api/v1/documents" -ApiKey $ApiKey
    if (-not $r.Success -or -not $r.Data.localFiles -or -not $r.Data.localFiles.items) { return @{ Removed = 0 } }

    $removed = 0
    foreach ($f in $r.Data.localFiles.items) {
        if ($f.type -ne 'folder') { continue }
        if ($f.name -notmatch '\.xlsx-[0-9a-f]{4}$') { continue }
        if (@($f.items).Count -ne 0) { continue }
        $dr = Invoke-Api -Method DELETE -Path "/api/v1/document/remove-folder" -ApiKey $ApiKey -Body @{ name = $f.name }
        if ($dr.Success) {
            $removed++
            Write-Status -Type "success" -Message "已清理 xlsx 残留空目录: $($f.name)"
        } else {
            Write-Status -Type "warning" -Message "xlsx 残留目录清理失败: $($f.name) - $($dr.Error)"
        }
    }
    return @{ Removed = $removed }
}

function New-DocumentFolder {
    # 在"我的文档"中创建新文件夹（POST /api/v1/document/create-folder）
    param([string]$ApiKey, [string]$Name)
    if ([string]::IsNullOrWhiteSpace($Name)) { return $false }
    # 文件夹名须为单段（不含路径分隔符，服务端会拒绝嵌套）
    if ($Name -match '[/\\]') {
        Write-Status -Type "warning" -Message "文件夹名不能包含 / 或 \"
        return $false
    }
    $r = Invoke-Api -Method POST -Path "/api/v1/document/create-folder" -ApiKey $ApiKey -Body @{ name = $Name }
    return $r.Success
}

function Select-DocumentFolder {
    # 交互选择"我的文档"文件夹：菜单列出已有文件夹 + 新建选项。
    # 新建时输入名称：重名则提示并重新命名，直到成功或取消。
    # 返回 @{ Name; Created }；用户取消返回 $null。
    param(
        [object[]]$Folders,
        [string]$Title = "选择文档文件夹（我的文档）"
    )
    $existing = @($Folders)
    if ($existing.Count -eq 0) { return @{ Name = 'custom-documents'; Created = $false } }

    $items = New-Object System.Collections.ArrayList
    foreach ($f in $existing) { [void]$items.Add([pscustomobject]@{ Label = $f; Key = $f; New = $false }) }
    [void]$items.Add([pscustomobject]@{ Label = '＋ 新建文件夹…'; Key = '__new__'; New = $true })

    $sel = Select-MenuFromList -Items @($items) -Title $Title -DisplayLabel { $_.Label } -Theme 'folder'
    if (-not $sel) { return $null }

    if (-not $sel.New) { return @{ Name = $sel.Key; Created = $false } }

    # 新建文件夹：输入名称 → 重名则提示并重新命名 → 立即创建
    while ($true) {
        Write-Host ""
        Write-Host "  输入新文件夹名称（单段，不含 / 或 \，回车取消）：" -ForegroundColor Cyan -NoNewline
        $name = Read-Host
        $name = $name.Trim()
        if ([string]::IsNullOrWhiteSpace($name)) { Write-Status -Type "warning" -Message "已取消新建"; return $null }
        if ($name -match '[/\\]') { Write-Status -Type "warning" -Message "文件夹名不能包含 / 或 \"; continue }
        if ($existing -contains $name) {
            Write-Status -Type "warning" -Message "文件夹「$name」已存在，请重新命名"
            continue
        }
        if (-not (New-DocumentFolder -ApiKey $script:ApiKey -Name $name)) {
            Write-Status -Type "error" -Message "创建文件夹「$name」失败"
            return $null
        }
        Write-Status -Type "success" -Message "已创建文件夹「$name」"
        return @{ Name = $name; Created = $true }
    }
}

function Resolve-FolderSelection {
    # 解析目标文件夹：优先 -Folder 参数（不存在则自动创建），否则走交互菜单。
    # 返回 @{ Name; Created }；失败返回 $null。
    param(
        [string]$FolderArg,
        [object[]]$Folders,
        [string]$Title = "选择文档文件夹（我的文档）"
    )
    if (-not [string]::IsNullOrWhiteSpace($FolderArg)) {
        $name = $FolderArg.Trim().TrimStart('/').TrimStart('\')
        if ($name -match '[/\\]') {
            Write-Status -Type "error" -Message "文件夹名不能包含 / 或 \：$FolderArg"
            return $null
        }
        if ($Folders -notcontains $name -and $name -ne 'custom-documents') {
            if (-not (New-DocumentFolder -ApiKey $script:ApiKey -Name $name)) {
                Write-Status -Type "error" -Message "创建文件夹「$name」失败"
                return $null
            }
            Write-Status -Type "success" -Message "已创建文件夹「$name」"
        }
        return @{ Name = $name; Created = ($Folders -notcontains $name) }
    }
    return (Select-DocumentFolder -Folders $Folders -Title $Title)
}

function Convert-ToStorageName {
    # 工具侧文件名 ASCII 化：AnythingLLM 官方 collector 的 slugify() 会删除所有非 ASCII
    # 字符（中文文件名会被删成 .txt 乱码），这是官方行为、不改服务端无法绕过。
    # 因此在客户端上传前把中文转换为 uXXXX Unicode 码点（可逆、可读、零依赖），
    # 保证服务端存储名干净可读；中文原名通过 metadata.title 一并写入文档元数据。
    # Policy: keep=原样上传（乱码）/ unicode=中文转 uXXXX（默认）/ strip=移除非 ASCII
    param(
        [string]$Name,
        [string]$Policy = "unicode"
    )
    if ([string]::IsNullOrWhiteSpace($Name)) { return $Name }

    $ext  = [IO.Path]::GetExtension($Name)
    $base = [IO.Path]::GetFileNameWithoutExtension($Name)
    $result = ""
    $sb = New-Object System.Text.StringBuilder

    if ($Policy -eq "keep") { return $Name }

    foreach ($ch in $base.ToCharArray()) {
        $code = [int][char]$ch
        if ($code -ge 0x20 -and $code -le 0x7E) {
            [void]$sb.Append($ch)                       # 保留可打印 ASCII
        } elseif ($Policy -ne "strip") {
            [void]$sb.Append(('u{0:x4}' -f $code))      # 中文/其他非 ASCII → uXXXX
        }
    }
    $result = $sb.ToString().Trim()
    if ([string]::IsNullOrWhiteSpace($result)) {
        $result = "file-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)
    }
    # 防破坏 multipart header / 路径：去掉引号、反斜杠
    $result = $result -replace '["\\]', '-'
    return ($result + $ext)
}

function New-UploadRequest {
    param([string]$ApiKey, [string]$FilePath, [string]$Slug = "", [string]$Folder = "")

    $fs = [IO.File]::Open($FilePath, 'Open', 'Read', 'Read')
    $name = [IO.Path]::GetFileName($FilePath)
    $boundary = [Guid]::NewGuid().ToString("N")

    # 工具侧解决中文乱码：AnythingLLM 官方 collector 的 slugify() 会删除所有非 ASCII
    # 字符（中文名会被删成 .txt），官方行为不改服务端无法绕过。因此客户端先 ASCII 化：
    # 中文 → uXXXX Unicode 码点（可逆可读、零依赖），保证存储名干净；中文原名通过
    # metadata.title 写入文档元数据（embedding 的 sourceDocument 仍显示中文）。
    $storageName = Convert-ToStorageName -Name $name -Policy $script:Config.FilenamePolicy
    $meta = @{ title = $name } | ConvertTo-Json -Compress

    # 手工构造 multipart/form-data（body 字节流）。
    # 注意：metadata 文本字段必须放在 file 字段之前 —— 服务端 multer 只把 file part
    # 之前的文本字段放入 request.body，metadata 才能被正确解析。
    $metaHead = "--$boundary`r`nContent-Disposition: form-data; name=`"metadata`"`r`n`r`n$meta`r`n"
    $fileHead = "--$boundary`r`nContent-Disposition: form-data; name=`"file`"; filename=`"$storageName`"`r`nContent-Type: application/octet-stream`r`n`r`n"
    $tail = "`r`n--$boundary--`r`n"
    $head = $metaHead + $fileHead
    $headBytes = [Text.Encoding]::UTF8.GetBytes($head)
    $tailBytes = [Text.Encoding]::UTF8.GetBytes($tail)

    $mem = New-Object System.IO.MemoryStream
    try {
        $mem.Write($headBytes, 0, $headBytes.Length)
        $fs.CopyTo($mem)
        $mem.Write($tailBytes, 0, $tailBytes.Length)
        # 注意：不能用 New-Object 传数组参数（PS 5.1 会按参数个数展开），须用 ::new()
        $content = [System.Net.Http.ByteArrayContent]::new($mem.ToArray())
    } finally {
        $mem.Dispose()
    }
    # [void] 抑制 TryAddWithoutValidation 的 bool 输出，避免污染函数返回值
    [void]$content.Headers.TryAddWithoutValidation("Content-Type", "multipart/form-data; boundary=$boundary")

    # v0.9：上传到"我的文档"指定文件夹 —— URL 路径带 folderName（单段文件夹名），
    # 服务端 processDocument 后会把产物移动到 documents/<folder>/ 下。
    $url = if ($Slug) {
               "/api/v1/workspace/$Slug/upload"
           } else {
               $folder = if ([string]::IsNullOrWhiteSpace($Folder)) { "custom-documents" } else { $Folder }
               "/api/v1/document/upload/$([Uri]::EscapeDataString($folder))"
           }
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post,
               "$($script:Config.BaseUrl.TrimEnd('/'))$url")
    $req.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $ApiKey)
    $req.Content = $content

    return @{ Request = $req; Form = $content; Stream = $fs }
}

function Start-Upload {
    param([string]$ApiKey, [string]$FilePath, [string]$Slug = "", [string]$Folder = "")
    $p = New-UploadRequest -ApiKey $ApiKey -FilePath $FilePath -Slug $Slug -Folder $Folder
    $p.Task = (Get-HttpClient).SendAsync($p.Request)
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
            Location = $doc.location
            Title    = $doc.title
        }
    } catch {
        $ex = $_.Exception
        while ($ex -is [System.AggregateException] -and $ex.InnerException) { $ex = $ex.InnerException }
        return @{ Success = $false; Error = $ex.Message }
    } finally {
        if ($Pending.Stream)  { $Pending.Stream.Close(); $Pending.Stream.Dispose() }
        if ($Pending.Form)    { $Pending.Form.Dispose() }
        if ($Pending.Request) { $Pending.Request.Dispose() }
        if ($resp)            { $resp.Dispose() }
    }
}

function Upload-Document {
    param([string]$ApiKey, [string]$FilePath, [string]$Slug = "", [string]$Folder = "")
    return Complete-Upload -Pending (Start-Upload -ApiKey $ApiKey -FilePath $FilePath -Slug $Slug -Folder $Folder)
}

function Invoke-UploadWithRetry {
    param([string]$ApiKey, [string]$FilePath, [string]$Slug = "", [string]$Folder = "")
    $attempt = 0
    while ($true) {
        $attempt++
        $name = [IO.Path]::GetFileName($FilePath)

        $p = Start-Upload -ApiKey $ApiKey -FilePath $FilePath -Slug $Slug -Folder $Folder
        $w = Wait-TaskWithSpinner -Task $p.Task -Activity "上传 $name" `
                                  -TimeoutSec $script:Config.UploadTimeoutSec
        $r = if ($w.TimedOut) {
                 if ($p.Stream)  { $p.Stream.Close(); $p.Stream.Dispose() }
                 if ($p.Form)    { $p.Form.Dispose() }
                 if ($p.Request) { $p.Request.Dispose() }
                 @{ Success = $false; Error = "上传超时（$($w.ElapsedSec)s）" }
             }
             else { Complete-Upload -Pending $p }

        if ($r.Success -or $attempt -gt $script:Config.UploadRetryCount) { return $r }

        $delay = [int]($script:Config.UploadRetryBaseDelaySec * [math]::Pow(2, $attempt - 1))
        Write-Host "  ⚠ 第 $attempt 次上传失败，${delay}s 后重试：$($r.Error)" -ForegroundColor Yellow
        Start-Sleep -Seconds $delay
    }
}

function Set-WorkspaceEmbedding {
    param(
        [string]$ApiKey,
        [string]$Slug,
        [string[]]$Adds = @(),
        [string[]]$Deletes = @()
    )

    $r = Invoke-Api -Method POST -Path "/api/v1/workspace/$Slug/update-embeddings" `
                    -ApiKey $ApiKey -TimeoutSec $script:Config.EmbedTimeoutSec `
                    -Body @{ adds = @($Adds); deletes = @($Deletes) }

    if (-not $r.Success) { return @{ Success = $false; Error = $r.Error } }
    return @{ Success = $true; Workspace = $r.Data.workspace; Message = $r.Data.message }
}

function Remove-Documents {
    param(
        [string]$ApiKey,
        [string[]]$Locations,
        [string[]]$Slugs = @()
    )

    # 核心操作：通过 update-embeddings 解除工作区关联
    # 注意：DELETE /api/v1/system/remove-documents 在某些实例上返回假成功但不实际删除
    # 因此以 update-embeddings 为主，物理删除为辅
    $embedOk = $true
    $embedErrors = @()

    foreach ($slug in $Slugs) {
        $d = Set-WorkspaceEmbedding -ApiKey $ApiKey -Slug $slug -Deletes $Locations
        if (-not $d.Success) {
            $embedOk = $false
            $embedErrors += "解除关联失败 ($slug): $($d.Error)"
        }
    }

    if (-not $embedOk) {
        return @{ Success = $false; Error = ($embedErrors -join "; ") }
    }

    # 可选：尝试物理删除（不依赖成功与否）
    try {
        $r = Invoke-Api -Method DELETE -Path "/api/v1/system/remove-documents" `
                        -ApiKey $ApiKey -Body @{ names = @($Locations) } `
                        -TimeoutSec $script:Config.ApiTimeoutSec
        # 不检查结果，因为某些实例返回假成功
    } catch {
        # 忽略物理删除错误，关联已解除
    }

    return @{ Success = $true }
}

function Confirm-Embedding {
    param(
        [string]$ApiKey,
        [string]$Slug,
        [string[]]$Locations,
        [int]$TimeoutSec = $script:Config.VerifyTimeoutSec,
        [int]$BaseIntervalSec = $script:Config.VerifyBaseIntervalSec,
        [int]$MaxIntervalSec = $script:Config.VerifyMaxIntervalSec
    )

    # 构建待验证集合（location 格式: custom-documents/name-uuid.json）
    $pending = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($l in $Locations) { [void]$pending.Add($l) }

    $verified = New-Object System.Collections.ArrayList
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $interval = $BaseIntervalSec
    $lastError = $null

    while ($pending.Count -gt 0) {
        $r = Invoke-Api -Method GET -Path "/api/v1/workspace/$Slug" -ApiKey $ApiKey `
                        -TimeoutSec $script:Config.ApiTimeoutSec
        if ($r.Success -and $r.Data.workspace) {
            $docs = @($r.Data.workspace.documents)
            # 匹配字段: workspace 详情用 docpath（与 upload 返回的 location 值一致）
            $found = @($docs | Where-Object { $pending.Contains($_.docpath) } | ForEach-Object { $_.docpath })
            foreach ($f in $found) { [void]$pending.Remove($f); [void]$verified.Add($f) }
        } else {
            $lastError = $r.Error
            Write-Host "  ⚠ 轮询请求失败：$($r.Error)" -ForegroundColor DarkYellow
        }

        if ($pending.Count -eq 0) { break }
        if ($sw.Elapsed.TotalSeconds -ge $TimeoutSec) { break }

        Start-Sleep -Seconds $interval
        $interval = [math]::Min($interval * 1.5, $MaxIntervalSec)
    }

    return @{
        Verified   = @($verified)
        Pending    = @($pending)
        AllOk      = ($pending.Count -eq 0)
        ElapsedSec = [int]$sw.Elapsed.TotalSeconds
        LastError  = $lastError
    }
}

function Send-ChatMessage {
    param([string]$ApiKey, [string]$Slug, [string]$Message, [string]$Mode = "query")

    $r = Invoke-Api -Method POST -Path "/api/v1/workspace/$Slug/chat" -ApiKey $ApiKey `
                    -TimeoutSec $script:Config.ChatTimeoutSec `
                    -Body @{ message = $Message; mode = $Mode; stream = $false }

    if (-not $r.Success) { return @{ Success = $false; Error = $r.Error } }
    return @{ Success = $true; Text = $r.Data.textResponse; Sources = @($r.Data.sources) }
}

#endregion

#region ========== 文件处理 ==========

function Test-InputFile {
    param([string]$FilePath)

    if ([string]::IsNullOrWhiteSpace($FilePath)) { return @{ Ok = $false; Reason = "空路径" } }
    if (-not (Test-Path -LiteralPath $FilePath))  { return @{ Ok = $false; Reason = "文件不存在" } }
    if (Test-Path -LiteralPath $FilePath -PathType Container) { return @{ Ok = $false; Reason = "是目录，不是文件" } }

    # 长路径（ReadAllBytes 会失败）
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

    # 占用检查（必须用 FileShare.None —— 阅读器通常以 FileShare.Read 打开）
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

function Find-DuplicateDocuments {
    param(
        [object[]]$Assignments,
        [hashtable]$WorkspaceDocs
    )

    $result = New-Object System.Collections.ArrayList
    foreach ($a in $Assignments) {
        $docs = @($WorkspaceDocs[$a.Slug])
        $key  = $a.File.ToLowerInvariant()
        # workspace 文档的 filename 格式: <原文件名>-<uuid>.json，用前缀匹配
        $escapedKey = [regex]::Escape($key)
        $old  = @($docs | Where-Object { $_.filename -and $_.filename.ToLowerInvariant() -match ('^' + $escapedKey + '-') })
        if ($old.Count -gt 0) {
            [void]$result.Add([pscustomobject]@{
                Assignment = $a
                OldDocs    = $old
            })
        }
    }
    return @($result)
}

#endregion

#region ========== 交互菜单 ==========

function Select-MenuFromList {
    param(
        [object[]]$Items,
        [string]$Title,
        [scriptblock]$DisplayLabel,
        [int]$FilterThreshold = 20,
        [string]$Theme = 'default'   # default / folder（青蓝）/ workspace（品红）
    )

    if (-not $Items -or $Items.Count -eq 0) { return $null }

    # 输入或输出任一重定向 → 降级为数字选择
    if ($script:IsInputRedirected -or $script:IsOutputRedirected) {
        return Read-FilteredChoice -Items $Items -Title $Title -DisplayLabel $DisplayLabel
    }

    # 条目过多 → 先走关键字过滤
    if ($Items.Count -gt $FilterThreshold) {
        $r = Read-FilteredChoice -Items $Items -Title $Title -DisplayLabel $DisplayLabel
        if ($r) { return $r }
    }

    # 主题配置：文件夹=青蓝系，工作区=品红系，default=原样（灰边+青高亮）
    $themeCfg = switch ($Theme) {
        'folder'    { @{ BorderRgb = $script:Palette.Folder;     BorderFg = "Cyan";    HiRgb = $script:Palette.FolderHi;  HiFg = "Cyan";    HiBg = "20,55,80";  Mark = '▶' } }
        'workspace' { @{ BorderRgb = $script:Palette.Workspace;  BorderFg = "Magenta"; HiRgb = $script:Palette.WorkspaceHi; HiFg = "Magenta"; HiBg = "80,25,70";  Mark = '◆' } }
        default     { @{ BorderRgb = $script:Palette.Muted;      BorderFg = "DarkGray"; HiRgb = "0,220,255";                HiFg = "Cyan";    HiBg = "";         Mark = '▶' } }
    }

    $W       = 46
    $selected = 0
    $total    = $Items.Count
    $pageSize = 20
    $viewTop  = 0
    $menuHeight = 5 + [math]::Min($total, $pageSize)

    Write-Host ""
    $top = [Console]::CursorTop
    $buffer = $host.UI.RawUI.BufferHeight
    if ($top + $menuHeight -ge $buffer -or $top -lt 1) { Clear-Host; $top = 1 }
    $script:MenuTop = $top

    function Draw-Menu {
        param([int]$Highlight, [int]$From)
        $visible = [math]::Min($total - $From, $pageSize)
        [Console]::SetCursorPosition(0, $script:MenuTop)
        Write-Colored ('┌' + (Format-Fixed " $Title" $W) + '┐') $themeCfg.BorderRgb $themeCfg.BorderFg; Write-Host ""
        Write-Colored ('│' + (' ' * $W) + '│') $themeCfg.BorderRgb $themeCfg.BorderFg; Write-Host ""

        for ($i = 0; $i -lt $visible; $i++) {
            $idx   = $From + $i
            $label = $Items[$idx] | ForEach-Object { & $DisplayLabel $_ }
            $num   = $idx + 1
            if ($idx -eq $Highlight) {
                $inner = Format-Fixed (" {0,2} {1} {2}" -f $num, $themeCfg.Mark, $label) $W
            } else {
                $inner = Format-Fixed (" {0,2}   {1}" -f $num, $label) $W
            }
            [Console]::SetCursorPosition(0, $script:MenuTop + 2 + $i)
            if ($idx -eq $Highlight) {
                Write-Colored ('│' + $inner + '│') $themeCfg.HiRgb $themeCfg.HiFg -BgRgb $themeCfg.HiBg
                Write-Host ""
            } else {
                Write-Colored ('│' + $inner + '│') $script:Palette.Info "Gray"
                Write-Host ""
            }
        }

        [Console]::SetCursorPosition(0, $script:MenuTop + 2 + $visible)
        Write-Colored ('└' + ('─' * $W) + '┘') $themeCfg.BorderRgb $themeCfg.BorderFg; Write-Host ""
        $hint = if ($total -gt $pageSize) { "  [↑↓] 移动  [Enter] 确认  [ESC] 退出  [F] 过滤  ($($From+1)-$($From+$visible)/$total)" }
                else                      { "  [↑↓] 移动  [Enter] 确认  [ESC] 退出  [F] 过滤" }
        Write-Colored $hint $script:Palette.Info "DarkGray"; Write-Host ""
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
    # 非交互模式下自动返回第一项
    if ([Console]::IsInputRedirected -or $script:IsInputRedirected) {
        return $Items[0]
    }
    Write-Host ""
    Write-Host "  $Title（共 $($Items.Count) 项）— 输入关键字过滤，直接回车查看全部，Q 取消" -ForegroundColor Gray
    $kw = Read-Host "  关键字"
    if ($kw -eq 'Q' -or $kw -eq 'q') { return $null }

    $matched = if ([string]::IsNullOrWhiteSpace($kw)) { $Items }
               else { @($Items | Where-Object { (& $DisplayLabel $_) -like "*$kw*" }) }
    if ($matched.Count -eq 0) { Write-Host "  无匹配项" -ForegroundColor Yellow; return $null }

    for ($i = 0; $i -lt $matched.Count; $i++) {
        Write-Host ("  [{0,2}] {1}" -f ($i + 1), ($matched[$i] | ForEach-Object { & $DisplayLabel $_ }))
    }
    $sel = Read-Host "  选择序号（回车=第 1 项）"
    if ([string]::IsNullOrWhiteSpace($sel)) { return $matched[0] }
    $idx = 0
    if ([int]::TryParse($sel, [ref]$idx) -and $idx -ge 1 -and $idx -le $matched.Count) { return $matched[$idx - 1] }
    return $null
}

function Confirm-Action {
    param([string]$Prompt = "确认执行？")
    # 非交互模式下自动确认
    if ([Console]::IsInputRedirected -or $script:IsInputRedirected) {
        return $true
    }
    Write-Host ""
    Write-Host "  $Prompt [Y/N] " -NoNewline -ForegroundColor Yellow
    $key = [Console]::ReadKey($true)
    Write-Host ""
    return ($key.Key -eq 'Y')
}

function Select-DuplicateAction {
    param([string]$FileName, [int]$OldCount)

    if ($script:Config.DuplicateDefaultAction -ne 'ask') {
        return $script:Config.DuplicateDefaultAction
    }
    # -Answer 自动选择（1=keep/2=skip/3=replace/4=abort）
    if ($script:Answer -ge 1 -and $script:Answer -le 4) {
        $actions = @('keep','skip','replace','abort')
        $chosen = $actions[$script:Answer - 1]
        Write-Status -Type "info" -Message "自动选择: $chosen (-Answer $($script:Answer))"
        return $chosen
    }
    # 非交互模式下跳过重复文件（防止重复向量）
    if ([Console]::IsInputRedirected -or $script:IsInputRedirected) {
        Write-Status -Type "warning" -Message "非交互模式：「$FileName」已存在，跳过"
        return 'skip'
    }
    Write-Host ""
    Write-Host "  ⚠ 「$FileName」在工作区中已有 $OldCount 份同名文档" -ForegroundColor Yellow
    Write-Host "    [1] 保留  [2] 跳过  [3] 替换（删除旧版后嵌入）  [4] 中止" -ForegroundColor Gray
    $key = [Console]::ReadKey($true).Key
    switch ($key) {
        'D1' { return 'keep' }
        'D2' { return 'skip' }
        'D3' { return 'replace' }
        'D4' { return 'abort' }
        default { return 'keep' }
    }
}

#endregion

#region ========== 日志 ==========

function Write-OperationLog {
    param(
        [string]$File,
        [string]$Workspace,
        [string]$Status,
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

        # 不能用 Add-Content -Encoding UTF8（PS 5.1 会逐行写 BOM）
        [IO.File]::AppendAllText($logFile, $entry + "`n", (New-Object System.Text.UTF8Encoding($false)))
    } catch {
        Write-Host "  ⚠ 写日志失败（不影响主流程）：$($_.Exception.Message)" -ForegroundColor DarkYellow
    }
}

function Clear-OldLogs {
    param(
        [int]$RetentionDays = 30,
        [double]$MaxTotalMB = 0
    )
    $logDir = $script:Config.LogDir
    if (-not (Test-Path -LiteralPath $logDir)) { return @{ Deleted = 0; Kept = 0; SizeMB = 0 } }

    $allLogs = @(Get-ChildItem -LiteralPath $logDir -Filter "*_embed.jsonl" -File -ErrorAction SilentlyContinue |
                 Sort-Object LastWriteTime)
    $deletedCount = 0

    # 阶段1：按天数删除（KeepDays=0 表示删除全部）
    if ($RetentionDays -ge 0) {
        if ($RetentionDays -eq 0) {
            $old = @($allLogs)
        } else {
            $cut = (Get-Date).AddDays(-$RetentionDays)
            $old = @($allLogs | Where-Object { $_.LastWriteTime -lt $cut })
        }
        foreach ($f in $old) { Remove-Item -LiteralPath $f.FullName -Force -ErrorAction SilentlyContinue; $deletedCount++ }
    }

    # 阶段2：按总大小删除（从最旧开始）
    $remaining = @(Get-ChildItem -LiteralPath $logDir -Filter "*_embed.jsonl" -File -ErrorAction SilentlyContinue |
                   Sort-Object LastWriteTime)
    if ($MaxTotalMB -gt 0 -and $remaining.Count -gt 0) {
        $totalBytes = ($remaining | Measure-Object -Property Length -Sum).Sum
        $maxBytes = $MaxTotalMB * 1MB
        $i = 0
        while ($totalBytes -gt $maxBytes -and $i -lt $remaining.Count) {
            $totalBytes -= $remaining[$i].Length
            Remove-Item -LiteralPath $remaining[$i].FullName -Force -ErrorAction SilentlyContinue
            $deletedCount++
            $i++
        }
    }

    $final = @(Get-ChildItem -LiteralPath $logDir -Filter "*_embed.jsonl" -File -ErrorAction SilentlyContinue)
    $finalMB = [math]::Round(($final | Measure-Object -Property Length -Sum).Sum / 1MB, 2)
    return @{ Deleted = $deletedCount; Kept = $final.Count; SizeMB = $finalMB }
}

#endregion

#region ========== 主流程 ==========
function Main {
    param(
        [string[]]$Files,
        [string]$WorkspaceSlug,
        [int]$Answer = -1,
        [switch]$Diagnose,
        [switch]$CleanLogs,
        [switch]$Help
    )

    # ========== 初始化 ==========
    $script:ElapsedSw = [System.Diagnostics.Stopwatch]::StartNew()
    $script:Answer = $Answer
    if ($Answer -ge 1 -and $Answer -le 4) {
        $actions = @('keep','skip','replace','abort')
        Write-Status -Type "info" -Message "自动选择模式: -Answer $Answer ($($actions[$Answer-1]))"
    } elseif ($Answer -gt 4) {
        Write-Status -Type "warning" -Message "-Answer $Answer 超出范围(1-4)，回退交互模式"
        $script:Answer = -1
    }
    Read-Config
    Initialize-Console
    Assert-Config
    foreach ($w in $script:AssertWarnings) { Write-Host "  ⚠ $w" -ForegroundColor Yellow }

    # ========== --help ==========
    if ($Help) {
        Write-Host "用法: embed.ps1 <file...> [-WorkspaceSlug <slug>] [--diagnose] [--clean-logs] [--no-pause] [--help]"
        Write-Host ""
        Write-Host "参数:"
        Write-Host "  <file...>              拖入一个或多个文件"
        Write-Host "  -WorkspaceSlug <slug>  指定工作区 slug，跳过菜单选择（适合自动化）"
        Write-Host "  --diagnose             深度环境诊断"
        Write-Host "  --clean-logs           清理过期日志"
        Write-Host "  --no-pause             自动化调用时不等待按键"
        Write-Host "  --help                 显示帮助"
        Write-Host ""
        Write-Host "退出码: 0 成功 / 1 错误 / 2 用户取消 / 3 部分失败"
        return 0
    }

    Write-Banner

    # ========== 独立开关 ==========
    if ($CleanLogs) {
        $retention = if ($KeepDays -ge 0) { $KeepDays } else { $script:Config.LogRetentionDays }
        $maxMB = if ($MaxTotalMB -ge 0) { $MaxTotalMB } else { 0 }
        $result = Clear-OldLogs -RetentionDays $retention -MaxTotalMB $maxMB
        Write-Status -Type "success" -Message "已清理 $($result.Deleted) 个日志，保留 $($result.Kept) 个（$($result.SizeMB) MB）"
        if (-not $Files) { return 0 }
    }
    if ($Diagnose) {
        Invoke-FullDiagnose
        if (-not $Files) { return 0 }
    }

    # ========== 参数校验 ==========
    if (-not $Files -or $Files.Count -eq 0) {
        Write-Status -Type "error" -Message "未拖入任何文件"
        return 2
    }
    $Files = Remove-DuplicatePaths -Paths $Files

    # ========== 文件预校验 ==========
    Write-Section -Title "文件校验" -Color "cyan" -Number 1
    $validFiles = New-Object System.Collections.ArrayList
    $filteredOut = New-Object System.Collections.ArrayList
    foreach ($f in $Files) {
        $check = Test-InputFile -FilePath $f
        if ($check.Ok) { [void]$validFiles.Add($f); Write-Status -Type "success" -Message "OK  $(Split-Path $f -Leaf)" }
        else           {
            Write-Status -Type "error" -Message "跳过 $(Split-Path $f -Leaf)" -Detail $check.Reason
            [void]$filteredOut.Add([pscustomobject]@{ File = (Split-Path $f -Leaf); Reason = $check.Reason })
        }
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
    if ($workspaces.Count -eq 0) {
        Write-Status -Type "error" -Message "没有可用的工作区，请先在 AnythingLLM 中创建"
        return 1
    }
    Write-Status -Type "info" -Message "已加载 $($workspaces.Count) 个工作区"

    # ========== 获取"我的文档"文件夹 ==========
    # v0.9：文档全局共享（我的文档），先选文件夹（custom-documents 或子文件夹），再选工作区
    $docFolders = @(Get-DocumentFolders -ApiKey $script:ApiKey)
    Write-Status -Type "info" -Message "已加载文档文件夹: $(@($docFolders) -join '、')"

    # ========== 非交互模式必须指定 -WorkspaceSlug（逐项模式除外） ==========
    if (($script:IsInputRedirected -or [Console]::IsInputRedirected) -and -not $WorkspaceSlug -and $Mode -ne 'per-file') {
        Write-Status -Type "error" -Message "非交互模式（stdin 重定向）必须指定 -WorkspaceSlug 参数"
        Write-Host "  用法: embed.ps1 <file...> -WorkspaceSlug <slug> [-Folder <name>]" -ForegroundColor Yellow
        Write-Host "  可用工作区:" -ForegroundColor Yellow
        foreach ($ws in $workspaces) {
            Write-Host "    $($ws.name) → $($ws.slug)" -ForegroundColor Gray
        }
        return 1
    }

    # ========== 模式选择（多文件时，先于文件夹/工作区选择） ==========
    $processingMode = 'unified'
    if ($Mode -eq 'unified' -or $Mode -eq 'per-file') {
        $processingMode = $Mode
        $modeLabel = if ($Mode -eq 'unified') { '统一存入' } else { '逐个存入' }
        Write-Status -Type "info" -Message "处理模式: $modeLabel（-Mode 指定）"
    } elseif ($Files.Count -gt 1 -and -not $WorkspaceSlug) {
        $mode = Select-MenuFromList `
            -Items @([pscustomobject]@{ Label='统一存入'; Desc='所有文件 → 同一文档文件夹 + 同一工作区'; Key='unified' },
                     [pscustomobject]@{ Label='逐个存入'; Desc='每个文件分别选择文档文件夹 + 工作区'; Key='per-file' }) `
            -Title "检测到 $($Files.Count) 个文件，选择存入模式" `
            -DisplayLabel { "$($_.Label)  —  $($_.Desc)" }
        if (-not $mode) { Write-Status -Type "warning" -Message "用户取消"; return 2 }
        $processingMode = $mode.Key
    }

    # ========== 文件夹 + 工作区选择（v0.9 两段式） ==========
    # 单文件：先选文档文件夹 → 再选工作区
    # 多文件统一存入：先统一选文件夹 → 再统一选工作区
    # 多文件逐个存入：不在此处，由下方分配循环为每个文件单独选择文件夹 + 工作区
    $selectedWorkspace = $null
    $selectedFolder    = $null

    if ($WorkspaceSlug) {
        # 非交互模式：直接使用指定的 slug 与文件夹
        $selectedWorkspace = $workspaces | Where-Object { $_.slug -eq $WorkspaceSlug } | Select-Object -First 1
        if (-not $selectedWorkspace) {
            Write-Status -Type "error" -Message "找不到 slug 为 '$WorkspaceSlug' 的工作区"
            Write-Status -Type "info" -Message "可用工作区: $(@($workspaces | ForEach-Object { "$($_.name) ($($_.slug))" }) -join ', ')"
            return 1
        }
        Write-Status -Type "success" -Message "已选择工作区: $($selectedWorkspace.name) ($($selectedWorkspace.slug))"
        $folderSel = Resolve-FolderSelection -FolderArg $Folder -Folders $docFolders
        if (-not $folderSel) { return 1 }
        $selectedFolder = $folderSel.Name
        if ($folderSel.Created) { $docFolders = @($docFolders + $selectedFolder) }
        Write-Status -Type "success" -Message "已选择文档文件夹: $selectedFolder"
    } elseif ($processingMode -eq 'unified' -or $Files.Count -eq 1) {
        # 单文件 / 多文件统一存入：先选文件夹，再选工作区
        if ($Files.Count -eq 1) {
            $folderTitle = "为「$(Split-Path $Files[0] -Leaf)」选择文档文件夹"
            $wsTitle     = "为「$(Split-Path $Files[0] -Leaf)」选择目标工作区"
        } else {
            $folderTitle = "为 $($Files.Count) 个文件统一选择文档文件夹"
            $wsTitle     = "为 $($Files.Count) 个文件统一选择目标工作区"
        }
        $folderSel = Resolve-FolderSelection -FolderArg $Folder -Folders $docFolders -Title $folderTitle
        if (-not $folderSel) { Write-Status -Type "warning" -Message "用户取消"; return 2 }
        $selectedFolder = $folderSel.Name
        if ($folderSel.Created) { $docFolders = @($docFolders + $selectedFolder) }
        $selectedWorkspace = Select-Workspace -Workspaces $workspaces -Title $wsTitle
        if (-not $selectedWorkspace) { Write-Status -Type "warning" -Message "用户取消"; return 2 }
    }
    # 逐项模式：不在此处选择，由下方分配循环为每个文件单独选择文件夹 + 工作区

    # ========== 阶段一：文件夹 + 工作区分配 ==========
    $assignments = New-Object System.Collections.ArrayList
    if ($processingMode -eq 'unified' -or $WorkspaceSlug) {
        # 统一模式或指定了 slug：所有文件使用同一个文件夹 + 同一个工作区
        foreach ($f in $Files) {
            [void]$assignments.Add([pscustomobject]@{
                File = Split-Path $f -Leaf; FilePath = $f
                Folder = $selectedFolder
                Workspace = $selectedWorkspace.name; Slug = $selectedWorkspace.slug })
        }
    } else {
        # 逐项模式：每个文件分别选择文档文件夹 + 工作区（v0.9）
        foreach ($f in $Files) {
            $folderSel = Resolve-FolderSelection -FolderArg $null -Folders $docFolders -Title "为「$(Split-Path $f -Leaf)」选择文档文件夹"
            if (-not $folderSel) { Write-Status -Type "warning" -Message "已放弃该文件"; continue }
            if ($folderSel.Created) { $docFolders = @($docFolders + $folderSel.Name) }
            $ws = Select-Workspace -Workspaces $workspaces -Title "为「$(Split-Path $f -Leaf)」选择工作区"
            if (-not $ws) { Write-Status -Type "warning" -Message "已放弃该文件"; continue }
            if ($ws.slug -notin @($workspaces | ForEach-Object { $_.slug })) {
                $workspaces = @($workspaces + $ws)
                Write-Status -Type "success" -Message "新工作区已加入后续选择列表"
            }
            [void]$assignments.Add([pscustomobject]@{
                File = Split-Path $f -Leaf; FilePath = $f
                Folder = $folderSel.Name
                Workspace = $ws.name; Slug = $ws.slug })
        }
        if ($assignments.Count -eq 0) { Write-Status -Type "warning" -Message "未分配任何文件"; return 2 }
        Write-Section -Title "文件分配预览" -Color "yellow" -Number 2
        foreach ($a in $assignments) { Write-Host "  $($a.File) → [$($a.Folder)] $($a.Workspace)" -ForegroundColor Gray }
        if (-not (Confirm-Action -Prompt "确认开始批量嵌入？")) { return 2 }
    }

    $assignedCount = $assignments.Count

    # ========== 阶段二：重复检测 ==========
    if ($script:Config.DetectDuplicates) {
        Write-Section -Title "重复检测" -Color "yellow" -Number 3
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
                    $docpaths = @($d.OldDocs | ForEach-Object { $_.docpath })
                    $rm = Set-WorkspaceEmbedding -ApiKey $script:ApiKey -Slug $d.Assignment.Slug -Deletes $docpaths
                    if ($rm.Success) {
                        Write-Status -Type "success" -Message "旧版本 $($docpaths.Count) 份已解除，新版本已嵌入"
                    } else {
                        Write-Status -Type "warning" -Message "旧版本解除失败：$($rm.Error)"
                        Write-OperationLog -File $d.Assignment.File -Workspace $d.Assignment.Workspace -Status "warning" -Detail "旧版本解除失败: $($rm.Error)"
                    }
                }
                'abort' {
                    $assignments.Remove($d.Assignment)
                    Write-OperationLog -File $d.Assignment.File -Workspace $d.Assignment.Workspace -Status "skipped" -Detail "用户中止"
                }
                default { }
            }
            if (-not $applyAll -and $dups.Count -gt 1) {
                if (Confirm-Action -Prompt "将本次选择应用于其余 $($dups.Count - 1) 个重复文件？") { $applyAll = $action }
            }
        }
        if ($assignments.Count -eq 0) { Write-Status -Type "warning" -Message "所有文件均已存在且选择跳过"; return 0 }
    }

    # ========== 阶段三：批量上传 ==========
    Write-Section -Title "上传阶段" -Color "cyan" -Number 4
    $folderSummary = @($assignments | ForEach-Object { $_.Folder } | Select-Object -Unique) -join '、'
    Write-Host "  目标文档文件夹: $folderSummary" -ForegroundColor DarkGray
    $uploaded = New-Object System.Collections.ArrayList
    $failed   = New-Object System.Collections.ArrayList

    for ($i = 0; $i -lt $assignments.Count; $i++) {
        $a  = $assignments[$i]
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        Write-CountProgress -Current $i -Total $assignments.Count -Label "上传"

        $result = Invoke-UploadWithRetry -ApiKey $script:ApiKey -FilePath $a.FilePath -Folder $a.Folder
        $sw.Stop()

        if ($result.Success) {
            $a | Add-Member -NotePropertyName Location -NotePropertyValue $result.Location -Force
            $a | Add-Member -NotePropertyName Title    -NotePropertyValue $result.Title    -Force
            [void]$uploaded.Add($a)
            # 原名 → 存储名映射提示（ASCII 化策略生效时）
            $sn = Convert-ToStorageName -Name $a.File -Policy $script:Config.FilenamePolicy
            if ($sn -ne $a.File) {
                Write-Host "  ↳ 存储名: $sn  （中文名已按 ASCII 化策略转换，原名见元数据 title）" -ForegroundColor DarkGray
            }
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

    # ========== 清理 xlsx 转换残留空目录（官方行为，工具侧清理） ==========
    $xlsxClean = Clean-EmptyXlsxDirs -ApiKey $script:ApiKey
    if ($xlsxClean.Removed -gt 0) {
        Write-Status -Type "info" -Message "已清理 $($xlsxClean.Removed) 个 xlsx 残留空目录"
    }

    # ========== 阶段四：按 slug 分组嵌入 ==========
    Write-Section -Title "嵌入阶段" -Color "green" -Number 5
    $verifiedAll = New-Object System.Collections.ArrayList
    $grouped = @($uploaded | Group-Object -Property Slug)

    foreach ($group in $grouped) {
        $slug      = $group.Name
        $wsName    = ($group.Group | Select-Object -First 1).Workspace
        $locations = @($group.Group | ForEach-Object { $_.Location })

        Write-Host ""
        $embedAttempt = 0
        $embed = $null
        while ($true) {
            $embedAttempt++
            $p = Start-Api -Method POST -Path "/api/v1/workspace/$slug/update-embeddings" `
                           -TimeoutSec $script:Config.EmbedTimeoutSec `
                           -Body @{ adds = $locations; deletes = @() }
            $w = Wait-TaskWithSpinner -Task $p.Task -Activity "嵌入到「$wsName」（$($locations.Count) 个文档）"
            $resp = if ($w.TimedOut)      { $null }
                    elseif ($p.Task.IsFaulted -or $p.Task.IsCanceled) { $null }
                    else { Complete-ApiResponse -Response $p.Task.GetAwaiter().GetResult() }
            $p.Request.Dispose()

            $embed = if ($null -eq $resp)       { @{ Success = $false; Error = "嵌入请求超时或已中止" } }
                     elseif (-not $resp.Success) { @{ Success = $false; Error = $resp.Error } }
                     else { @{ Success = $true; Workspace = $resp.Data.workspace; Message = $resp.Data.message } }

            if ($embed.Success -or $embedAttempt -gt 1) { break }
            Write-Host "  ⚠ 第 $embedAttempt 次嵌入失败，5s 后重试：$($embed.Error)" -ForegroundColor Yellow
            Start-Sleep -Seconds 5
        }

        if (-not $embed.Success) {
            Write-Status -Type "error" -Message "嵌入失败: $($embed.Error)"
            foreach ($item in $group.Group) {
                Write-OperationLog -File $item.File -Workspace $item.Workspace -Status "error" -Detail "嵌入失败: $($embed.Error)"
            }
            continue
        }

        # ========== 阶段五：批量验证 ==========
        Write-Section -Title "验证阶段" -Color "magenta" -Number 6
        $v = Confirm-Embedding -ApiKey $script:ApiKey -Slug $slug -Locations $locations

        $verifiedSet = @{}
        foreach ($loc in $v.Verified) { $verifiedSet[$loc] = $true }

        foreach ($item in $group.Group) {
            if ($verifiedSet.ContainsKey($item.Location)) {
                Write-Status -Type "success" -Message "$($item.File) 验证通过"
                [void]$verifiedAll.Add($item)
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

    # ========== 阶段六：测试检索 ==========
    if ($script:Config.AskForChatTest -and $verifiedAll.Count -gt 0) {
        # 非交互模式跳过 chat 测试（LLM 推理耗时长，自动化场景不需要）
        if ($script:IsInputRedirected -or [Console]::IsInputRedirected) {
            Write-Status -Type "info" -Message "非交互模式跳过 chat 验证"
        } else {
            Write-Section -Title "测试" -Color "yellow" -Number 7
            if (Confirm-Action -Prompt "是否测试检索？") {
                $first = $verifiedAll[0]
                $p = Start-Api -Method POST -Path "/api/v1/workspace/$($first.Slug)/chat" `
                               -TimeoutSec $script:Config.ChatTimeoutSec `
                               -Body @{ message = "请简要介绍最近嵌入的文档内容"; mode = $script:Config.ChatMode; stream = $false }
                $w = Wait-TaskWithSpinner -Task $p.Task -Activity "等待模型响应"
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
    }

    # ========== 汇总 ==========
    $stat = [ordered]@{
        Total    = $assignedCount + $filteredOut.Count
        Uploaded = $uploaded.Count
        Verified = $verifiedAll.Count
        Skipped  = $assignedCount - $uploaded.Count - $failed.Count
        Failed   = $failed.Count + ($uploaded.Count - $verifiedAll.Count) + $filteredOut.Count
        ElapsedSec = [int]$script:ElapsedSw.Elapsed.TotalSeconds
    }
    Write-SummaryBox -Stat $stat
    if ($filteredOut.Count -gt 0) {
        Write-Host ""
        Write-Host "  预校验失败:" -ForegroundColor Red
        foreach ($fo in $filteredOut) {
            Write-Host "    • $($fo.File): $($fo.Reason)" -ForegroundColor Red
        }
    }
    if ($failed.Count -gt 0) {
        Write-Host ""
        Write-Host "  上传/嵌入失败:" -ForegroundColor Red
        foreach ($f in $failed) {
            $reason = if ($f.Error) { $f.Error } else { "上传失败" }
            Write-Host "    • $($f.File): $reason" -ForegroundColor Red
        }
    }
    if ($uploaded.Count -gt $verifiedAll.Count -and $verifiedAll.Count -gt 0) {
        $unverified = $uploaded | Where-Object { -not ($verifiedAll -contains $_) }
        if ($unverified) {
            Write-Host ""
            Write-Host "  未验证明细:" -ForegroundColor Yellow
            foreach ($u in $unverified) {
                Write-Host "    • $($u.File): 轮询超时未确认" -ForegroundColor Yellow
            }
        }
    }
    Write-Host ""
    Write-Host "  日志路径: $($script:Config.LogDir)" -ForegroundColor DarkGray

    # ========== 退出码 ==========
    if ($stat.Failed -eq 0) { return 0 }
    if ($stat.Verified -gt 0) { return 3 }
    return 1
}
#endregion

# ================= 入口 =================
try {
    $code = Main -Files $Files -WorkspaceSlug $WorkspaceSlug -Answer $Answer -Diagnose:$Diagnose -CleanLogs:$CleanLogs -Help:$Help
} catch {
    Write-Host ""
    Write-Host "  ✘ 未处理异常：" -ForegroundColor Red
    Write-Host "    $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "    $($_.ScriptStackTrace)" -ForegroundColor DarkGray
    $code = 1
} finally {
    try { if ($script:UseAnsi) { Write-Host "$([char]27)[0m" -NoNewline } } catch { }
    if (-not $NoPause -and -not [Console]::IsInputRedirected) {
        Write-Host ""
        Write-Host "  按任意键关闭..." -ForegroundColor DarkGray
        [Console]::ReadKey($true) | Out-Null
    }
}
exit $code
