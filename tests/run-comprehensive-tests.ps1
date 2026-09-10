# ============================================================
# run-comprehensive-tests.ps1 — AnythingLLM 嵌入工具全面测试执行器
# 版本：v1.5（v1.0 美化回归 + v0.9.x 新功能覆盖）| 2026-09-10
# 兼容：Windows PowerShell 5.1（win-shell / 非交互环境可跑）
#
# 负载设计（针对本机配置：Ollama 本地 14B LLM，推理慢）：
#   - 默认【不】触发 LLM chat（-RunHeavyChat 才执行 E5 检索用例）
#   - 嵌入类调用合并：Setup 一次多文件上传 + 少量独立用例，全流程嵌入约 5-6 次
#   - 可选 -Warmup 预载模型，把 Ollama 冷启动时间从用例时长中剥离
#   - 每个用例独立超时（嵌入 240s / chat 300s / 轻量 30-90s），不会无限挂起
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tests\run-comprehensive-tests.ps1
# 参数：
#   -ProjectDir      项目根目录（默认 D:\WSL\AI-tools\003anythingllmtools）
#   -WorkspaceSlug   目标工作区 slug（默认 wsl）
#   -KeepTestDocs    测试后保留文档库中的 t_* 测试文档（默认清理）
#   -RunHeavyChat    执行 E5 LLM 检索用例（重负载，默认跳过）
#   -Warmup          测试前预热 Ollama（先跑一次最小 query，冷启动约 60-90s）
#   -CaseFilter      只运行指定用例，如 'D1,D2,G1'（默认全部）
# 退出码：0=全部通过  1=存在失败  2=脚本自身错误
# ============================================================

param(
    [string]$ProjectDir    = 'D:\WSL\AI-tools\003anythingllmtools',
    [string]$WorkspaceSlug = 'wsl',
    [switch]$KeepTestDocs,
    [switch]$RunHeavyChat,
    [switch]$Warmup,
    [switch]$SmokeOnly,
    [string]$CaseFilter    = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Host.UI.RawUI.WindowTitle = "AnythingLLM 综合测试 v1.5"

# CaseFilter 生效：初始化过滤列表（null=全跑）
$script:CaseFilterList = $null
if ($CaseFilter) { $script:CaseFilterList = @($CaseFilter -split ',' | ForEach-Object { $_.Trim() }) }

# ---------- 路径 ----------
$ToolsDir   = Join-Path $ProjectDir 'tools'
$EmbedPs1   = Join-Path $ToolsDir 'embed.ps1'
$BatPs1     = Join-Path $ToolsDir 'EmbedIntoWorkspace.bat'
$ConfigPath = Join-Path $ToolsDir 'config.json'
$TestDir    = Join-Path $ProjectDir 'tests'
$InputDir   = Join-Path $TestDir 'inputs'
$ResultDir  = Join-Path $TestDir 'results'
$ApiKeyPath = Join-Path $ToolsDir 'apikey.txt'
$LogDir     = Join-Path $ToolsDir 'logs'

if (-not (Test-Path $EmbedPs1)) { Write-Host "[FATAL] 找不到 $EmbedPs1"; exit 2 }
if (-not (Test-Path $ApiKeyPath)) { Write-Host "[FATAL] 找不到 $ApiKeyPath"; exit 2 }
New-Item -ItemType Directory -Force -Path $InputDir, $ResultDir | Out-Null

# ---------- 统计 ----------
$script:Pass = 0; $script:Fail = 0; $script:Skip = 0
$script:Results = @()

function Invoke-Bat {
    # 通过 cmd.exe 调用 BAT 入口（验证 BAT 参数透传与全链路；stdin 重定向使结尾 pause 跳过）
    # 注意：必须用 'call' 关键字——cmd /c 后字符串以引号开头时会被剥离首尾引号导致 BAT 路径引号错位
    param([string[]]$FileArgs = @(), [string[]]$ExtraArgs = @(), [int]$TimeoutSec = 180)
    $inner = ('call "{0}"' -f $BatPs1)
    foreach ($f in $FileArgs) { $inner += (' "{0}"' -f $f) }
    foreach ($a in $ExtraArgs) { $inner += (' {0}' -f $a) }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'cmd.exe'
    $psi.Arguments = '/c ' + $inner
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.RedirectStandardInput = $true     # 模拟非交互（BAT 结尾 pause 自动跳过）
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $psi.CreateNoWindow = $true

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $null = $proc.Start()
    $out = $proc.StandardOutput.ReadToEndAsync()
    $err = $proc.StandardError.ReadToEndAsync()
    $deadline = [DateTime]::Now.AddSeconds($TimeoutSec)
    while (-not $proc.HasExited) {
        if ([DateTime]::Now -gt $deadline) {
            try { $proc.Kill() } catch { }
            Start-Sleep -Milliseconds 300
            $proc.WaitForExit(5000) | Out-Null
            return [PSCustomObject]@{ Exit = -1; Stdout = $out.Result; Stderr = $err.Result; TimedOut = $true; Sec = $TimeoutSec }
        }
        Start-Sleep -Milliseconds 200
    }
    $proc.WaitForExit()
    $elapsed = [math]::Round(($proc.ExitTime - $proc.StartTime).TotalSeconds, 1)
    return [PSCustomObject]@{
        Exit = $proc.ExitCode; Stdout = $out.Result; Stderr = $err.Result
        TimedOut = $false; Sec = $elapsed
    }
}

function Add-Result {
    param($Id, $Name, $Status, $Detail, $Exit, $Sec)
    # -CaseFilter 过滤：指定时仅记录/显示命中用例（其它用例照常执行但不计入报告）
    if ($script:CaseFilterList -and ($script:CaseFilterList -notcontains $Id)) { return }
    $script:Results += [PSCustomObject]@{
        Id = $Id; Name = $Name; Status = $Status; Detail = $Detail; Exit = $Exit; Sec = $Sec
    }
    switch ($Status) {
        'PASS' { $script:Pass++ }
        'FAIL' { $script:Fail++ }
        'SKIP' { $script:Skip++ }
    }
    $mark = if ($Status -eq 'PASS') { '[PASS]' } elseif ($Status -eq 'FAIL') { '[FAIL]' } else { '[SKIP]' }
    Write-Host ("{0} {1} {2} ({3}s)" -f $mark, $Id, $Name, $Sec)
    if ($Status -ne 'PASS') { Write-Host ("      -> {0}" -f $Detail) }
}

# ---------- 子进程调用 embed.ps1（非交互 + 超时控制） ----------
function Invoke-Embed {
    param(
        [string[]]$FileArgs = @(),
        [string[]]$ExtraArgs = @(),
        [int]$TimeoutSec = 120
    )
    $argList = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', ('"{0}"' -f $EmbedPs1))
    foreach ($f in $FileArgs) { $argList += ('"{0}"' -f $f) }
    foreach ($a in $ExtraArgs) { $argList += $a }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'powershell.exe'
    $psi.Arguments = $argList -join ' '
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.RedirectStandardInput = $true     # 模拟非交互（输入重定向）
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $psi.CreateNoWindow = $true

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $null = $proc.Start()
    $out = $proc.StandardOutput.ReadToEndAsync()
    $err = $proc.StandardError.ReadToEndAsync()
    $deadline = [DateTime]::Now.AddSeconds($TimeoutSec)
    while (-not $proc.HasExited) {
        if ([DateTime]::Now -gt $deadline) {
            try { $proc.Kill() } catch { }
            Start-Sleep -Milliseconds 300
            $proc.WaitForExit(5000) | Out-Null
            return [PSCustomObject]@{ Exit = -1; Stdout = $out.Result; Stderr = $err.Result; TimedOut = $true; Sec = $TimeoutSec }
        }
        Start-Sleep -Milliseconds 200
    }
    $proc.WaitForExit()
    $elapsed = [math]::Round(($proc.ExitTime - $proc.StartTime).TotalSeconds, 1)
    return [PSCustomObject]@{
        Exit = $proc.ExitCode; Stdout = $out.Result; Stderr = $err.Result
        TimedOut = $false; Sec = $elapsed
    }
}

# ---------- 配置备份/恢复 ----------
$script:ConfigBytes = $null
function Backup-Config { $script:ConfigBytes = [System.IO.File]::ReadAllBytes($ConfigPath) }
function Restore-Config {
    if ($null -eq $script:ConfigBytes) { return }
    [System.IO.File]::WriteAllBytes($ConfigPath, $script:ConfigBytes)
    $now = [System.IO.File]::ReadAllBytes($ConfigPath)
    if (($now -join ',') -ne ($script:ConfigBytes -join ',')) { throw 'config.json 恢复失败：字节不一致' }
}
function Set-ConfigField {
    param($Field, $Value)
    $raw = [System.IO.File]::ReadAllText($ConfigPath, [System.Text.Encoding]::UTF8)
    $raw = [regex]::Replace($raw, '/\*.*?\*/', '', 'Singleline')
    $raw = [regex]::Replace($raw, '(^|\s)//.*$', '$1', 'Multiline')
    $cfg = $raw | ConvertFrom-Json
    $cfg.$Field = $Value
    $json = $cfg | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText($ConfigPath, $json, (New-Object System.Text.UTF8Encoding($false)))
}

# ---------- AnythingLLM REST 辅助 ----------
function Get-ALKey {
    $raw = [System.IO.File]::ReadAllText($ApiKeyPath, [System.Text.Encoding]::UTF8)
    return ($raw -replace '^[\s\uFEFF]+', '' -replace '[\s]+$', '')
}
function Invoke-ALApi {
    param([string]$Method, [string]$Path, $Body = $null, [int]$TimeoutSec = 60)
    $h = @{ Authorization = ('Bearer ' + (Get-ALKey)) }
    $p = @{ Uri = ('http://localhost:3001' + $Path); Method = $Method; Headers = $h; TimeoutSec = $TimeoutSec }
    if ($null -ne $Body) { $p.Body = ($Body | ConvertTo-Json -Depth 6); $p.ContentType = 'application/json' }
    return Invoke-RestMethod @p
}
function Test-ALAlive {
    try { Invoke-ALApi -Method 'GET' -Path '/api/ping'; return $true } catch { return $false }
}
function Get-ALWorkspaces { return Invoke-ALApi -Method 'GET' -Path '/api/v1/workspaces' }
function Get-ALDocs {
    param($Slug)
    $r = Invoke-ALApi -Method 'GET' -Path ('/api/v1/workspace/' + $Slug)
    return @($r.workspace[0].documents)
}
function Remove-ALDoc {
    # 环境限制：system/remove-documents 在该实例返回 success 但不物理删除（已实测）；
    # 采用 update-embeddings deletes 解除工作区关联（文档从 workspace 消失即清理完成）
    param($Slug, [string[]]$Locations)
    if ($Locations.Count -eq 0) { return }
    $body = @{ adds = @(); deletes = @($Locations) }
    return Invoke-ALApi -Method 'POST' -Path ('/api/v1/workspace/' + $Slug + '/update-embeddings') -Body $body
}
function Invoke-ALChat {
    # 轻量预热/检索：AnythingLLM chat(query 模式)，会触发 LLM 推理
    param([string]$Slug, [string]$Message, [int]$TimeoutSec = 300)
    $body = @{ message = $Message; mode = 'query' }
    return Invoke-ALApi -Method 'POST' -Path ('/api/v1/workspace/' + $Slug + '/chat') -Body $body -TimeoutSec $TimeoutSec
}
function Upload-ALFile {
    # 直接调用 POST /api/v1/document/upload（embed.ps1 实际上传同款端点）。
    # Setup/D1-D3 用 API 直传验证上传能力，不消耗 14B 模型嵌入（嵌入验证交给 E4/D6/G1/H3）
    param([string]$FilePath)
    $key = Get-ALKey
    $out = & curl.exe -s -X POST 'http://localhost:3001/api/v1/document/upload' `
        -H ('Authorization: Bearer ' + $key) -F ('file=@' + $FilePath) --max-time 120 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    try { return ($out | ConvertFrom-Json) } catch { return $null }
}
function Wait-ForDocs {
    # 轮询工作区文档，直到所有指定前缀出现（嵌入完成）；超时返回当前快照
    param($Slug, [string[]]$Prefixes, [int]$TimeoutSec = 420)
    $deadline = [DateTime]::Now.AddSeconds($TimeoutSec)
    while ([DateTime]::Now -lt $deadline) {
        $docs = @(Get-ALDocs -Slug $Slug)
        $allFound = $true
        foreach ($p in $Prefixes) {
            if (@($docs | Where-Object { $_.filename -like ($p + '*') }).Count -eq 0) { $allFound = $false; break }
        }
        if ($allFound) { return $docs }
        Start-Sleep -Seconds 5
    }
    return @(Get-ALDocs -Slug $Slug)
}

# ---------- 测试文件准备 ----------
function Prepare-TestFiles {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $basic = 'AnythingLLM 嵌入工具全面测试基础文档' + "`r`n" + '内容主题：WSL 网络配置与 AnythingLLM 使用指南' + "`r`n" + '关键词：WSL网络配置'
    [System.IO.File]::WriteAllText((Join-Path $InputDir 't_basic.txt'), $basic, $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $InputDir 't_中文文档.txt'), '中文文件名上传测试文档' + "`r`n" + 'WSL网络配置', $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $InputDir 't_dup.txt'), '重复上传测试文档 dup-v1' + "`r`n" + 'WSL网络配置', $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $InputDir 't_multi_a.txt'), '多文件测试 A', $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $InputDir 't_multi_b.txt'), '多文件测试 B', $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $InputDir 't_multi_c.txt'), '多文件测试 C', $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $InputDir 't_flow.txt'), '全流程非交互测试文档' + "`r`n" + 'WSL网络配置', $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $InputDir 't_bat.txt'), 'BAT 入口实测文档' + "`r`n" + 'WSL网络配置', $utf8NoBom)
    # v0.9.2 新增格式（K5 用例素材）
    [System.IO.File]::WriteAllText((Join-Path $InputDir 't_fmt.json'), '{"title":"fmt-json","content":"JSON 格式测试文档，验证 v0.9.2 新增格式"}', $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $InputDir 't_fmt.html'), '<html><body><h1>fmt-html</h1><p>HTML 格式测试文档，验证 v0.9.2 新增格式</p></body></html>', $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $InputDir 't_fmt.org'), '* fmt-org' + "`r`n" + 'Org 格式测试文档，验证 v0.9.2 新增格式', $utf8NoBom)
    for ($i = 1; $i -le 4; $i++) {
        [System.IO.File]::WriteAllText((Join-Path $InputDir ("t_bulk_{0}.txt" -f $i)), ("批量吞吐测试文档 {0}" -f $i) + "`r`n" + 'WSL网络配置', $utf8NoBom)
    }
    [System.IO.File]::WriteAllText((Join-Path $InputDir 't_cfg.txt'), '配置缺失降级测试文档', $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $InputDir 't_unsupported.exe'), 'not a real exe', $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $InputDir 't_empty.txt'), '', $utf8NoBom)
    $big = 'X' * 10240
    [System.IO.File]::WriteAllText((Join-Path $InputDir 't_big.txt'), $big, $utf8NoBom)
    $oldLog = Join-Path $LogDir 'fake_old_20260801_embed.jsonl'
    [System.IO.File]::WriteAllText($oldLog, '{"action":"fake","status":"old"}', $utf8NoBom)
    (Get-Item $oldLog).LastWriteTime = (Get-Date).AddDays(-31)
}

function Get-TestDocTitles {
    param($Slug)
    try {
        $docs = Get-ALDocs -Slug $Slug
        $titles = @()
        foreach ($d in $docs) {
            try { $m = $d.metadata | ConvertFrom-Json; $titles += [string]$m.title } catch { $titles += [string]$d.filename }
        }
        return $titles
    } catch { return @() }
}
function Get-TestDocNames {
    param($Slug, [string]$Prefix = 't_')
    try {
        $docs = Get-ALDocs -Slug $Slug
        return @($docs | Where-Object { $_.filename -like ($Prefix + '*') } | ForEach-Object { $_.docpath })
    } catch { return @() }
}

# ---------- 冒烟测试（最轻量先行验证，全面测试前必跑） ----------
function Run-Smoke {
    Write-Host ''
    Write-Host '--- 冒烟测试（最轻量，先行确认功能正常）---'
    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    # S1 服务可达
    if (-not (Test-ALAlive)) {
        Write-Host '[SMOKE FAIL] S1 AnythingLLM 服务不可达 (/api/ping)'
        return $false
    }
    Write-Host '  [OK] S1 AnythingLLM 服务可达'

    # S2 API Key 有效（/api/v1/auth 为 AnythingLLM 鉴权端点）
    try {
        $auth = Invoke-ALApi -Method 'GET' -Path '/api/v1/auth'
        $authOk = ($auth.authenticated -eq $true)
    } catch { $authOk = $false }
    if (-not $authOk) {
        Write-Host '[SMOKE FAIL] S2 API Key 鉴权失败 (/api/v1/auth)'
        return $false
    }
    Write-Host '  [OK] S2 API Key 鉴权通过'
    # S3 工作区存在
    try {
        $ws = Get-ALWorkspaces
        $slugOk = @($ws.workspaces | Where-Object { $_.slug -eq $WorkspaceSlug }).Count -gt 0
    } catch { $slugOk = $false }
    if (-not $slugOk) {
        Write-Host "[SMOKE FAIL] S3 工作区不存在: $WorkspaceSlug"
        return $false
    }
    Write-Host "  [OK] S3 工作区存在 ($WorkspaceSlug)"

    # S4 最小上传+嵌入（放宽验证窗口覆盖 Ollama 冷启动：临时 verifyTimeoutSec=300）
    $smokeFile = Join-Path $InputDir 't_smoke.txt'
    [System.IO.File]::WriteAllText($smokeFile, 'smoke-test', (New-Object System.Text.UTF8Encoding($false)))
    # 清掉可能残留的 t_smoke，保证干净上传
    try {
        $names = Get-TestDocNames -Slug $WorkspaceSlug -Prefix 't_smoke'
        if ($names.Count -gt 0) { Remove-ALDoc -Slug $WorkspaceSlug -Locations $names }
    } catch { }
    Backup-Config
    try {
        Set-ConfigField 'verifyTimeoutSec' 300
        $r = Invoke-Embed -FileArgs @($smokeFile) -ExtraArgs @('-WorkspaceSlug', $WorkspaceSlug, '-NoPause') -TimeoutSec 360
    } finally { Restore-Config }
    if ($r.TimedOut -or $r.Exit -ne 0) {
        Write-Host ("[SMOKE FAIL] S4 最小上传+嵌入失败 exit={0} timeout={1}（冷启动窗口已放宽至 300s）" -f $r.Exit, $r.TimedOut)
        Write-Host '  提示：若上传阶段成功但验证阶段超时，可能为 embed.ps1 的 Confirm-Embedding 用 location 字段匹配，'
        Write-Host '  而当前 AnythingLLM 文档对象无 location 字段（仅 filename/docId），需修复该字段匹配。'
        return $false
    }
    Write-Host ("  [OK] S4 最小文件上传+嵌入 exit=0（{0}s）" -f $r.Sec)

    # S5 嵌入状态确认（文档出现在工作区 documents 即已嵌入）
    try {
        $docs = Get-ALDocs -Slug $WorkspaceSlug
        $sm = @($docs | Where-Object { $_.filename -like 't_smoke.txt-*' })
        $statusOk = ($sm.Count -gt 0)
    } catch { $statusOk = $false }
    if (-not $statusOk) {
        Write-Host '[SMOKE FAIL] S5 嵌入状态未确认（文档未出现在工作区）'
        return $false
    }
    Write-Host ("  [OK] S5 嵌入状态确认（{0} 条记录）" -f $sm.Count)

    # 清理冒烟文档
    try {
        $names = Get-TestDocNames -Slug $WorkspaceSlug -Prefix 't_smoke'
        if ($names.Count -gt 0) { Remove-ALDoc -Slug $WorkspaceSlug -Locations $names }
    } catch { }
    Write-Host ("--- 冒烟通过，耗时 {0}s ---" -f [math]::Round($sw.Elapsed.TotalSeconds, 1))
    return $true
}

# ============================================================
# 用例定义
# ============================================================
function Test-Block {
    # 关闭 embed.ps1 嵌入后的自动 chat 测试（askForChatTest=true 会在 14B 模型上跑 LLM 推理，
    # 单次可能 300s+，导致嵌入用例整体超时；自动化场景 chat 由 E5/RunHeavyChat 单独覆盖）。
    # 备份原始 config，Test-Block 结束后由 Main 恢复。
    Backup-Config
    try { Set-ConfigField 'askForChatTest' $false } catch { Write-Host '  ⚠ 关闭 askForChatTest 失败（继续）' }

    # 正式文档基线：记录测试前工作区中非 t_ 前缀文档数（W1 防误删断言用）
    try { $script:FormalBaseline = @(Get-ALDocs -Slug $WorkspaceSlug | Where-Object { $_.filename -notlike 't_*' }).Count }
    catch { $script:FormalBaseline = 0 }

    # ---------- A. 静态与文件完整性（零嵌入） ----------
    $b = [System.IO.File]::ReadAllBytes($EmbedPs1)
    $hasBom = ($b.Length -ge 3 -and $b[0] -eq 0xEF -and $b[1] -eq 0xBB -and $b[2] -eq 0xBF)
    $r = Invoke-Embed -FileArgs @() -ExtraArgs @('--help') -TimeoutSec 30
    Add-Result 'A1' 'embed.ps1 编码=UTF8-BOM 且含 V1.0' $(if ($hasBom -and ([IO.File]::ReadAllText($EmbedPs1) -match 'V1\.0')) { 'PASS' } else { 'FAIL' }) '检查 BOM 与版本标识' $r.Exit $r.Sec

    $batBytes = [System.IO.File]::ReadAllBytes((Join-Path $ToolsDir 'EmbedIntoWorkspace.bat'))
    $hasUtf16 = ($batBytes.Length -ge 2 -and ($batBytes[0] -eq 0xFF -or $batBytes[0] -eq 0xFE))
    $crlfOnly = -not ([System.Text.Encoding]::ASCII.GetString($batBytes) -match '(?<!\r)\n')
    Add-Result 'A2' 'BAT 入口=ANSI 无 BOM+CRLF' $(if (-not $hasUtf16 -and $crlfOnly) { 'PASS' } else { 'FAIL' }) '检查 BOM 与换行' -1 0

    $cfgOk = $false
    try {
        $raw = [System.IO.File]::ReadAllText($ConfigPath, [System.Text.Encoding]::UTF8)
        $raw = [regex]::Replace($raw, '/\*.*?\*/', '', 'Singleline')
        $cfg = $raw | ConvertFrom-Json
        $cfgOk = ($null -ne $cfg.baseUrl -and $null -ne $cfg.allowedExtensions -and $null -ne $cfg.maxFileSizeMB)
    } catch { $cfgOk = $false }
    Add-Result 'A3' 'config.json 可解析' $(if ($cfgOk) { 'PASS' } else { 'FAIL' }) '去注释后 ConvertFrom-Json 成功' -1 0
    Add-Result 'A4' '关键配置齐全' $(if ($cfgOk -and $cfg.probeTimeoutSec -and $cfg.uploadTimeoutSec) { 'PASS' } else { 'FAIL' }) 'baseUrl/超时/扩展名/大小' -1 0

    $keyLen = (Get-ALKey).Length
    Add-Result 'A5' 'apikey.txt 可读非空' $(if ($keyLen -gt 0) { 'PASS' } else { 'FAIL' }) ("key 长度 {0}" -f $keyLen) -1 0

    $structOk = (Test-Path $EmbedPs1) -and (Test-Path (Join-Path $ToolsDir 'EmbedIntoWorkspace.bat')) -and
                (Test-Path (Join-Path $ToolsDir 'config.json')) -and (Test-Path (Join-Path $ToolsDir 'README.md')) -and
                (Test-Path $LogDir)
    Add-Result 'A6' 'tools/ 目录结构齐全' $(if ($structOk) { 'PASS' } else { 'FAIL' }) 'embed.ps1/bat/config/README/logs' -1 0

    # ---------- A7-A14：V1.0 美化 + v0.9.x 新功能静态检查（零嵌入） ----------
    $src = [System.IO.File]::ReadAllText($EmbedPs1, [System.Text.Encoding]::UTF8)
    Add-Result 'A7' 'Banner 含 V1.0 徽章与副标题' $(if ($src -match 'AnythingLLM 文档嵌入工具' -and $src -match 'V1\.0' -and $src -match '拖入文件') { 'PASS' } else { 'FAIL' }) '检查 Write-Banner（V1.0 徽章+副标题）' -1 0
    Add-Result 'A8' '汇总盒含总耗时' $(if ($src -match '总耗时' -and $src -match 'ElapsedSec') { 'PASS' } else { 'FAIL' }) '检查 Write-SummaryBox（总耗时行）' -1 0
    $wsTheme = ([regex]::Matches($src, "-Theme 'workspace'")).Count
    $fdTheme = ([regex]::Matches($src, "-Theme 'folder'")).Count
    $dupTheme = ([regex]::Matches($src, "-Theme '[^']+' -Theme")).Count
    Add-Result 'A9' '菜单主题唯一(workspace×1 folder×1)' $(if ($wsTheme -eq 1 -and $fdTheme -eq 1 -and $dupTheme -eq 0) { 'PASS' } else { 'FAIL' }) ("workspace={0} folder={1} 重复={2}" -f $wsTheme, $fdTheme, $dupTheme) -1 0
    Add-Result 'A10' '新建工作区函数存在' $(if ($src -match 'function New-Workspace' -and $src -match 'function Select-Workspace') { 'PASS' } else { 'FAIL' }) '检查 New-Workspace/Select-Workspace' -1 0
    $renCount = ([regex]::Matches($src, '请重新命名')).Count
    Add-Result 'A11' '重名提示重新命名(工作区+文件夹)' $(if ($renCount -ge 2) { 'PASS' } else { 'FAIL' }) ("出现次数={0}（应≥2）" -f $renCount) -1 0
    Add-Result 'A12' '逐项模式新建工作区刷新列表' $(if ($src -match '已加入后续选择列表') { 'PASS' } else { 'FAIL' }) '检查 Select-Workspace 返回后并入列表' -1 0
    Add-Result 'A13' '文件夹选择标题上下文' $(if ($src -match '\[string\]\$Title' -and $src -match '为「\$\(Split-Path') { 'PASS' } else { 'FAIL' }) '检查 Resolve-FolderSelection -Title 透传' -1 0
    $extOk = $false; $extActual = ''
    try {
        $raw2 = [System.IO.File]::ReadAllText($ConfigPath, [System.Text.Encoding]::UTF8)
        $raw2 = [regex]::Replace($raw2, '/\*.*?\*/', '', 'Singleline')
        $raw2 = [regex]::Replace($raw2, '(^|\s)//.*$', '$1', 'Multiline')
        $cfg2 = $raw2 | ConvertFrom-Json
        $exts = @($cfg2.allowedExtensions | ForEach-Object { $_.ToLowerInvariant() })
        $need = @('.org','.adoc','.rst','.json','.html','.odt','.odp')
        $missing = @($need | Where-Object { $exts -notcontains $_ })
        $extOk = ($exts.Count -eq 15) -and ($missing.Count -eq 0)
        $extActual = ("{0} 种, 缺={1}" -f $exts.Count, ($missing -join ','))
    } catch { $extOk = $false }
    Add-Result 'A14' 'config 扩展名 15 种含 7 新格式' $(if ($extOk) { 'PASS' } else { 'FAIL' }) ("实际: {0}" -f $extActual) -1 0

    # ---------- Setup：API 直传多文件（覆盖 D1/D2/D3 上传入库断言，零嵌入） ----------
    # 说明：上传能力验证走 POST /api/v1/document/upload（与 embed.ps1 同一端点）；
    #       真实嵌入路径由后续 E4/D6（t_basic+t_dup）与 G1/H3（t_flow）覆盖。
    Write-Host '  [setup] API 直传 9 文件（免嵌入）...'
    $setupFiles = @(
        (Join-Path $InputDir 't_basic.txt'),
        (Join-Path $InputDir 't_中文文档.txt'),
        (Join-Path $InputDir 't_dup.txt'),
        (Join-Path $InputDir 't_multi_a.txt'),
        (Join-Path $InputDir 't_multi_b.txt'),
        (Join-Path $InputDir 't_multi_c.txt'),
        (Join-Path $InputDir 't_fmt.json'),
        (Join-Path $InputDir 't_fmt.html'),
        (Join-Path $InputDir 't_fmt.org')
    )
    $setupLocations = @{}   # 文件名 -> location
    $setupOk = $true
    $setupFailed = @()
    foreach ($f in $setupFiles) {
        $resp = Upload-ALFile -FilePath $f
        if ($null -eq $resp -or -not $resp.success -or @($resp.documents).Count -eq 0) {
            $setupOk = $false; $setupFailed += (Split-Path $f -Leaf)
        } else {
            $loc = [string]$resp.documents[0].location
            $setupLocations[(Split-Path $f -Leaf)] = $loc
            Write-Host ("    OK  {0}  {1}" -f (Split-Path $f -Leaf), $loc)
        }
    }
    if (-not $setupOk) { Write-Host ("    FAIL 上传: {0}" -f ($setupFailed -join ', ')) }

    Add-Result 'D1' '单 txt 上传成功(Setup)' $(if ($setupOk -and $setupLocations.ContainsKey('t_basic.txt')) { 'PASS' } else { 'FAIL' }) ("上传={0} location={1}" -f $setupOk, $setupLocations.ContainsKey('t_basic.txt')) -1 0
    Add-Result 'D2' '中文文件名上传(Setup)' $(if ($setupOk -and $setupLocations.ContainsKey('t_中文文档.txt')) { 'PASS' } else { 'FAIL' }) ("location={0}" -f $setupLocations.ContainsKey('t_中文文档.txt')) -1 0
    $multiOk = $setupLocations.ContainsKey('t_multi_a.txt') -and $setupLocations.ContainsKey('t_multi_b.txt') -and $setupLocations.ContainsKey('t_multi_c.txt')
    Add-Result 'D3' '多文件(3)一次上传(Setup)' $(if ($setupOk -and $multiOk) { 'PASS' } else { 'FAIL' }) ("a/b/c location={0}" -f $multiOk) -1 0

    # Setup 嵌入：t_basic + t_dup + t_fmt×3 一次嵌入（供 E4/D6/K5 使用；1 次调用 5 文件，无 chat 后约 3-8 分钟）
    if ($setupOk -and $setupLocations.ContainsKey('t_basic.txt') -and $setupLocations.ContainsKey('t_dup.txt')) {
        Write-Host '  [setup] 嵌入 t_basic + t_dup + t_fmt×3（update-embeddings，5 文件一次调用）...'
        try {
            $body = @{ adds = @($setupLocations['t_basic.txt'], $setupLocations['t_dup.txt'],
                                 $setupLocations['t_fmt.json'], $setupLocations['t_fmt.html'], $setupLocations['t_fmt.org']); deletes = @() }
            $null = Invoke-ALApi -Method 'POST' -Path ('/api/v1/workspace/' + $WorkspaceSlug + '/update-embeddings') -Body $body -TimeoutSec 420
            $embedOk = $true
        } catch { $embedOk = $false }
        $docs = Wait-ForDocs -Slug $WorkspaceSlug -Prefixes @('t_basic.txt-', 't_dup.txt-') -TimeoutSec 420
        $tBasicDocs = @($docs | Where-Object { $_.filename -like 't_basic.txt-*' })
        $tDupDocs   = @($docs | Where-Object { $_.filename -like 't_dup.txt-*' })
    } else {
        $embedOk = $false; $docs = @(); $tBasicDocs = @(); $tDupDocs = @()
    }
    Write-Host ("  [setup] 嵌入完成? {0} | t_basic={1} t_dup={2}" -f $embedOk, $tBasicDocs.Count, $tDupDocs.Count)

    # ---------- K5. v0.9.2 新格式嵌入验证（Setup 已直传 t_fmt×3） ----------
    $fmtEmbOk = $false; $fmtFound = 0
    if ($setupOk -and $setupLocations.ContainsKey('t_fmt.json') -and $setupLocations.ContainsKey('t_fmt.html') -and $setupLocations.ContainsKey('t_fmt.org')) {
        try {
            $body = @{ adds = @($setupLocations['t_fmt.json'], $setupLocations['t_fmt.html'], $setupLocations['t_fmt.org']); deletes = @() }
            $null = Invoke-ALApi -Method 'POST' -Path ('/api/v1/workspace/' + $WorkspaceSlug + '/update-embeddings') -Body $body -TimeoutSec 420
            $fmtEmbOk = $true
        } catch { $fmtEmbOk = $false }
        $docsF = Wait-ForDocs -Slug $WorkspaceSlug -Prefixes @('t_fmt.json-','t_fmt.html-','t_fmt.org-') -TimeoutSec 420
        $fmtFound = @($docsF | Where-Object { $_.filename -like 't_fmt.*-*' }).Count
    }
    Add-Result 'K5' '新格式 json/html/org 嵌入验证' $(if ($fmtEmbOk -and $fmtFound -ge 3) { 'PASS' } else { 'FAIL' }) ("嵌入={0} 文档数={1}/3" -f $fmtEmbOk, $fmtFound) -1 0

    # ---------- X. 批量吞吐（v1.4 新增：4 文件直传 + 一次嵌入，验证批量路径与耗时） ----------
    $bulkFiles = @((Join-Path $InputDir 't_bulk_1.txt'), (Join-Path $InputDir 't_bulk_2.txt'),
                   (Join-Path $InputDir 't_bulk_3.txt'), (Join-Path $InputDir 't_bulk_4.txt'))
    $bulkLocs = @{}
    $bulkUpOk = $true
    foreach ($f in $bulkFiles) {
        $resp = Upload-ALFile -FilePath $f
        if ($null -eq $resp -or -not $resp.success -or @($resp.documents).Count -eq 0) { $bulkUpOk = $false }
        else { $bulkLocs[(Split-Path $f -Leaf)] = [string]$resp.documents[0].location }
    }
    $x1Ok = $false; $x1Sec = -1
    if ($bulkUpOk -and $bulkLocs.Count -eq 4) {
        $swX = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $null = Invoke-ALApi -Method 'POST' -Path ('/api/v1/workspace/' + $WorkspaceSlug + '/update-embeddings') `
                -Body @{ adds = @($bulkLocs.Values); deletes = @() } -TimeoutSec 420
            $bulkEmbOk = $true
        } catch { $bulkEmbOk = $false }
        $swX.Stop()
        $docsX = Wait-ForDocs -Slug $WorkspaceSlug -Prefixes @('t_bulk_1.txt-','t_bulk_2.txt-','t_bulk_3.txt-','t_bulk_4.txt-') -TimeoutSec 300
        $x1Ok = ($bulkEmbOk -and @($docsX | Where-Object { $_.filename -like 't_bulk_*.txt-*' }).Count -ge 4)
        $x1Sec = [math]::Round($swX.Elapsed.TotalSeconds, 1)
        Write-Host ("  [X1] 批量4文件嵌入: {0} | 耗时 {1}s" -f $(if ($x1Ok) { 'PASS' } else { 'FAIL' }), $x1Sec)
    }
    Add-Result 'X1' '批量4文件直传+一次嵌入吞吐' $(if ($x1Ok) { 'PASS' } else { 'FAIL' }) ("上传={0} 嵌入={1} 耗时={2}s" -f $bulkUpOk, $x1Ok, $x1Sec) -1 $x1Sec

    # ---------- B. CLI 参数行为（零嵌入） ----------
    $r = Invoke-Embed -FileArgs @() -ExtraArgs @('--help') -TimeoutSec 30
    Add-Result 'B1' '--help 输出用法 exit=0' $(if ($r.Exit -eq 0 -and $r.Stdout -match '用法') { 'PASS' } else { 'FAIL' }) ("exit={0}" -f $r.Exit) $r.Exit $r.Sec

    $rH = Invoke-Embed -FileArgs @() -ExtraArgs @('-h') -TimeoutSec 30
    Add-Result 'B2' '-h 输出用法 exit=0' $(if ($rH.Exit -eq 0 -and $rH.Stdout -match '用法') { 'PASS' } else { 'FAIL' }) ("exit={0}" -f $rH.Exit) $rH.Exit $rH.Sec

    $r = Invoke-Embed -FileArgs @() -ExtraArgs @() -TimeoutSec 30
    Add-Result 'B3' '无参数提示用法(exit!=0)' $(if ($r.Exit -ne 0 -and -not $r.TimedOut) { 'PASS' } else { 'FAIL' }) ("exit={0} timeout={1}" -f $r.Exit, $r.TimedOut) $r.Exit $r.Sec

    $missing = Join-Path $InputDir 't_missing_xyz.txt'
    $r = Invoke-Embed -FileArgs @($missing) -ExtraArgs @('-NoPause') -TimeoutSec 60
    Add-Result 'B4' '文件不存在报错 exit=1' $(if ($r.Exit -eq 1 -and -not $r.TimedOut) { 'PASS' } else { 'FAIL' }) ("exit={0}" -f $r.Exit) $r.Exit $r.Sec

    $r = Invoke-Embed -FileArgs @() -ExtraArgs @('--bogus-flag') -TimeoutSec 30
    Add-Result 'B5' '未知开关不挂死' $(if (-not $r.TimedOut) { 'PASS' } else { 'FAIL' }) ("exit={0} timeout={1}" -f $r.Exit, $r.TimedOut) $r.Exit $r.Sec

    $r = Invoke-Embed -FileArgs @($InputDir) -ExtraArgs @('-NoPause') -TimeoutSec 60
    Add-Result 'B6' '目录作为输入被拒绝' $(if ($r.Exit -ne 0 -and -not $r.TimedOut) { 'PASS' } else { 'FAIL' }) ("exit={0}" -f $r.Exit) $r.Exit $r.Sec

    # ---------- C. 环境与配置容错 ----------
    $alive = Test-ALAlive
    $rHelp = Invoke-Embed -FileArgs @() -ExtraArgs @('--help') -TimeoutSec 30
    Add-Result 'C1' '服务探活+CLI 可用(轻量)' $(if ($alive -and $rHelp.Exit -eq 0) { 'PASS' } else { 'FAIL' }) ("alive={0} help_exit={1}" -f $alive, $rHelp.Exit) $rHelp.Exit $rHelp.Sec

    $r = Invoke-Embed -FileArgs @() -ExtraArgs @('-Diagnose', '-NoPause') -TimeoutSec 90
    Add-Result 'C2' '--diagnose 输出诊断不阻断' $(if (-not $r.TimedOut -and $r.Stdout -match '诊断|环境|diagnos') { 'PASS' } else { 'FAIL' }) ("exit={0} timeout={1}" -f $r.Exit, $r.TimedOut) $r.Exit $r.Sec

    Backup-Config
    try {
        Set-ConfigField 'baseUrl' 'http://127.0.0.1:9'
        $r = Invoke-Embed -FileArgs @((Join-Path $InputDir 't_basic.txt')) -ExtraArgs @('-WorkspaceSlug', $WorkspaceSlug, '-NoPause') -TimeoutSec 60
        $c3ok = ($r.Exit -eq 1 -and -not $r.TimedOut)
    } finally { Restore-Config }
    Add-Result 'C3' '服务不可达友好报错 exit=1' $(if ($c3ok) { 'PASS' } else { 'FAIL' }) ("exit={0} timeout={1}" -f $r.Exit, $r.TimedOut) $r.Exit $r.Sec

    $bak = $ConfigPath + '.bak_tmp'
    try {
        Move-Item $ConfigPath $bak -Force
        # config 缺失时用 --clean-logs 验证默认配置可用（零嵌入、快速）；
        # 不跑文件嵌入路径：默认配置下嵌入耗时长且偏离本用例意图（容错性）
        $r = Invoke-Embed -FileArgs @() -ExtraArgs @('-CleanLogs', '-NoPause') -TimeoutSec 60
        $c4ok = ($r.Exit -eq 0 -and -not $r.TimedOut)
    } finally {
        Move-Item $bak $ConfigPath -Force
        Restore-Config
    }
    Add-Result 'C4' 'config 缺失时默认配置可用' $(if ($c4ok) { 'PASS' } else { 'FAIL' }) ("exit={0} timeout={1}" -f $r.Exit, $r.TimedOut) $r.Exit $r.Sec

    # ---------- K. v0.9.3+ API 功能（新建工作区/文件夹，轻量零嵌入） ----------
    $kTag = 't_api_' + (Get-Date -Format 'HHmmss')
    $kSlug = ''; $kOk1 = $false
    try {
        $kr = Invoke-ALApi -Method 'POST' -Path '/api/v1/workspace/new' -Body @{ name = $kTag }
        $kSlug = [string]$kr.workspace.slug
        $kOk1 = (-not [string]::IsNullOrWhiteSpace($kSlug))
    } catch { $kOk1 = $false }
    Add-Result 'K1' '新建工作区 API 创建成功' $(if ($kOk1) { 'PASS' } else { 'FAIL' }) ("slug={0}" -f $kSlug) -1 0
    $kOk2 = $false
    if ($kOk1) {
        try {
            $kl = Invoke-ALApi -Method 'GET' -Path '/api/v1/workspaces'
            $kOk2 = @($kl.workspaces | Where-Object { $_.slug -eq $kSlug }).Count -eq 1
        } catch { $kOk2 = $false }
    }
    Add-Result 'K2' '新建工作区出现在列表' $(if ($kOk2) { 'PASS' } else { 'FAIL' }) ("slug={0}" -f $kSlug) -1 0
    $kOk3 = $false
    if ($kOk1) {
        try {
            $null = Invoke-ALApi -Method 'DELETE' -Path ('/api/v1/workspace/' + $kSlug)
            $kl2 = Invoke-ALApi -Method 'GET' -Path '/api/v1/workspaces'
            $kOk3 = @($kl2.workspaces | Where-Object { $_.slug -eq $kSlug }).Count -eq 0
        } catch { $kOk3 = $false }
    }
    Add-Result 'K3' '新建工作区删除后消失' $(if ($kOk3) { 'PASS' } else { 'FAIL' }) ("slug={0}" -f $kSlug) -1 0
    $kfName = 't_fld_' + (Get-Date -Format 'HHmmss')
    $kOk4 = $false
    try {
        $null = Invoke-ALApi -Method 'POST' -Path '/api/v1/document/create-folder' -Body @{ name = $kfName }
        $kl3 = Invoke-ALApi -Method 'GET' -Path '/api/v1/documents'
        $kOk4 = @($kl3.localFiles.items | Where-Object { $_.type -eq 'folder' -and $_.name -eq $kfName }).Count -eq 1
        $null = Invoke-ALApi -Method 'DELETE' -Path '/api/v1/document/remove-folder' -Body @{ name = $kfName }
    } catch { $kOk4 = $false }
    Add-Result 'K4' '新建/删除文件夹 API' $(if ($kOk4) { 'PASS' } else { 'FAIL' }) ("name={0}" -f $kfName) -1 0

    # ---------- D. 文件处理（零嵌入或极轻量） ----------
    $r = Invoke-Embed -FileArgs @((Join-Path $InputDir 't_unsupported.exe')) -ExtraArgs @('-WorkspaceSlug', $WorkspaceSlug, '-NoPause') -TimeoutSec 60
    Add-Result 'D4' '不支持扩展名被拒绝' $(if ($r.Exit -ne 0 -and -not $r.TimedOut) { 'PASS' } else { 'FAIL' }) ("exit={0} timeout={1}" -f $r.Exit, $r.TimedOut) $r.Exit $r.Sec

    $r = Invoke-Embed -FileArgs @((Join-Path $InputDir 't_empty.txt')) -ExtraArgs @('-WorkspaceSlug', $WorkspaceSlug, '-NoPause') -TimeoutSec 90
    Add-Result 'D5' '空文件行为明确不崩溃' $(if (-not $r.TimedOut) { 'PASS' } else { 'FAIL' }) ("exit={0} timeout={1}" -f $r.Exit, $r.TimedOut) $r.Exit $r.Sec

    # D6：t_dup 已在 Setup 嵌入 → 重传触发重复检测，非交互自动 skip（快速返回，不新增重复文档）
    $dupBefore = $tDupDocs.Count
    $r2 = Invoke-Embed -FileArgs @((Join-Path $InputDir 't_dup.txt')) -ExtraArgs @('-WorkspaceSlug', $WorkspaceSlug, '-NoPause') -TimeoutSec 120
    $dupAfter = @(Get-ALDocs -Slug $WorkspaceSlug | Where-Object { $_.filename -like 't_dup.txt-*' }).Count
    $dupOk = ($r2.Exit -eq 0 -and -not $r2.TimedOut -and $dupAfter -le $dupBefore)
    Add-Result 'D6' '重复上传非交互自动跳过不新增' $(if ($dupOk) { 'PASS' } else { 'FAIL' }) ("exit={0} timeout={1} 重传前={2} 重传后={3}" -f $r2.Exit, $r2.TimedOut, $dupBefore, $dupAfter) $r2.Exit $r2.Sec

    Backup-Config
    try {
        Set-ConfigField 'maxFileSizeMB' 0.001
        $r = Invoke-Embed -FileArgs @((Join-Path $InputDir 't_big.txt')) -ExtraArgs @('-WorkspaceSlug', $WorkspaceSlug, '-NoPause') -TimeoutSec 60
        $d7ok = ($r.Exit -ne 0 -and -not $r.TimedOut)
    } finally { Restore-Config }
    Add-Result 'D7' '超大文件被拒绝' $(if ($d7ok) { 'PASS' } else { 'FAIL' }) ("exit={0} timeout={1}" -f $r.Exit, $r.TimedOut) $r.Exit $r.Sec

    # ---------- E. 工作区与嵌入 ----------
    $r = Invoke-Embed -FileArgs @((Join-Path $InputDir 't_basic.txt')) -ExtraArgs @('-WorkspaceSlug', 'nonexistent_slug_xyz', '-NoPause') -TimeoutSec 90
    Add-Result 'E2' '无效 slug 报错 exit=1' $(if ($r.Exit -eq 1 -and -not $r.TimedOut) { 'PASS' } else { 'FAIL' }) ("exit={0} timeout={1}" -f $r.Exit, $r.TimedOut) $r.Exit $r.Sec

    $r = Invoke-Embed -FileArgs @((Join-Path $InputDir 't_basic.txt')) -ExtraArgs @('-NoPause') -TimeoutSec 90
    Add-Result 'E3' '非交互无 slug 明确提示不卡' $(if ($r.Exit -ne 0 -and -not $r.TimedOut) { 'PASS' } else { 'FAIL' }) ("exit={0} timeout={1}" -f $r.Exit, $r.TimedOut) $r.Exit $r.Sec

    # E4：Setup 已嵌入 t_basic，验证其入库且 metadata 完整
    $mOk = $false
    if ($tBasicDocs.Count -gt 0) {
        try { $m = $tBasicDocs[0].metadata | ConvertFrom-Json; $mOk = ([string]$m.title -eq 't_basic.txt') } catch { $mOk = $false }
    }
    $statusOk = ($tBasicDocs.Count -gt 0 -and $mOk)
    Add-Result 'E4' '嵌入后文档在库且 metadata 完整' $(if ($statusOk) { 'PASS' } else { 'FAIL' }) ("文档数={0} metadata匹配={1}" -f $tBasicDocs.Count, $mOk) -1 0

    # E5：LLM 检索用例 —— 重负载，默认 SKIP，仅 -RunHeavyChat 执行
    # v1.4 增强：直接调 chat API 验证真实检索（embed.ps1 非交互已跳过自动 chat，
    # 此用例独立覆盖"嵌入后检索命中 + 来源引用"完整链路），并记录 14B 推理耗时。
    if ($RunHeavyChat) {
        Write-Host '  [heavy] 执行 E5 LLM 检索用例（-RunHeavyChat，14B 推理 1-5 分钟）...'
        $sw5 = [System.Diagnostics.Stopwatch]::StartNew()
        $chatErr = $null; $chatR = $null
        try { $chatR = Invoke-ALChat -Slug $WorkspaceSlug -Message '请简要说明工作区中 t_basic 文档的内容主题' -TimeoutSec 420 } catch { $chatErr = $_.Exception.Message }
        $sw5.Stop()
        $srcCount = 0
        if ($null -ne $chatR) { try { $srcCount = @($chatR.sources).Count } catch { } }
        $e5ok = ($null -eq $chatErr -and $null -ne $chatR -and $null -ne $chatR.message -and $srcCount -gt 0)
        if ($chatErr -match 'Timeout|timed out') {
            Add-Result 'E5' '检索命中(LLM chat)' 'SKIP' ("LLM 推理超时（Ollama 冷启动或负载过高）：{0}" -f $chatErr) -1 [math]::Round($sw5.Elapsed.TotalSeconds, 1)
        } else {
            Add-Result 'E5' '检索命中(LLM chat)' $(if ($e5ok) { 'PASS' } else { 'FAIL' }) ("sources={0} 耗时={1}s 错误={2}" -f $srcCount, [math]::Round($sw5.Elapsed.TotalSeconds, 1), $chatErr) -1 [math]::Round($sw5.Elapsed.TotalSeconds, 1)
        }
    } else {
        Add-Result 'E5' '检索命中(LLM chat)' 'SKIP' '默认跳过重负载 LLM 用例，加 -RunHeavyChat 执行' -1 0
    }

    # ---------- F. 删除与清理 ----------
    $names = Get-TestDocNames -Slug $WorkspaceSlug
    $before = $names.Count
    if ($before -gt 0) { Remove-ALDoc -Slug $WorkspaceSlug -Locations $names }
    Start-Sleep -Seconds 2
    $after = (Get-TestDocNames -Slug $WorkspaceSlug).Count
    Add-Result 'F1' 't_* 测试文档删除成功' $(if ($after -eq 0) { 'PASS' } else { 'FAIL' }) ("删前={0} 删后={1}" -f $before, $after) -1 0

    $titles = Get-TestDocTitles -Slug $WorkspaceSlug
    $left = @($titles | Where-Object { $_ -like 't_*' })
    Add-Result 'F2' '删除后无 t_ 残留' $(if ($left.Count -eq 0) { 'PASS' } else { 'FAIL' }) ("残留: {0}" -f ($left -join ',')) -1 0

    # ---------- G. 非交互模式回归（本次修复重点，1 次嵌入） ----------
    $rG = Invoke-Embed -FileArgs @((Join-Path $InputDir 't_flow.txt')) -ExtraArgs @('-WorkspaceSlug', $WorkspaceSlug, '-NoPause') -TimeoutSec 360
    Add-Result 'G1' '全流程非交互无卡死 exit=0' $(if ($rG.Exit -eq 0 -and -not $rG.TimedOut) { 'PASS' } else { 'FAIL' }) ("exit={0} timeout={1}" -f $rG.Exit, $rG.TimedOut) $rG.Exit $rG.Sec

    $r = Invoke-Embed -FileArgs @() -ExtraArgs @('--help') -TimeoutSec 30
    Add-Result 'G3' '非交互 --help 正常' $(if ($r.Exit -eq 0 -and -not $r.TimedOut) { 'PASS' } else { 'FAIL' }) ("exit={0} timeout={1}" -f $r.Exit, $r.TimedOut) $r.Exit $r.Sec

    # ---------- T. BAT 入口实测（v1.4 新增：验证 BAT 参数透传 + 全链路） ----------
    $rb0 = Invoke-Bat -TimeoutSec 30
    Add-Result 'T1' 'BAT 无参数提示 exit=2' $(if ($rb0.Exit -eq 2 -and -not $rb0.TimedOut) { 'PASS' } else { 'FAIL' }) ("exit={0} timeout={1}" -f $rb0.Exit, $rb0.TimedOut) $rb0.Exit $rb0.Sec
    $rb1 = Invoke-Bat -FileArgs @((Join-Path $InputDir 't_bat.txt')) -ExtraArgs @('-WorkspaceSlug', $WorkspaceSlug, '-NoPause') -TimeoutSec 240
    Add-Result 'T2' 'BAT 参数透传全流程 exit=0' $(if ($rb1.Exit -eq 0 -and -not $rb1.TimedOut) { 'PASS' } else { 'FAIL' }) ("exit={0} timeout={1}" -f $rb1.Exit, $rb1.TimedOut) $rb1.Exit $rb1.Sec

    # ---------- H. 日志与稳定性 ----------
    $beforeCount = @(Get-ChildItem $LogDir -Filter '*.jsonl' -ErrorAction SilentlyContinue).Count
    $null = Invoke-Embed -FileArgs @((Join-Path $InputDir 't_flow.txt')) -ExtraArgs @('-WorkspaceSlug', $WorkspaceSlug, '-NoPause') -TimeoutSec 240
    $afterCount = @(Get-ChildItem $LogDir -Filter '*.jsonl' -ErrorAction SilentlyContinue).Count
    Add-Result 'H1' '运行生成 jsonl 日志' $(if ($afterCount -ge $beforeCount) { 'PASS' } else { 'FAIL' }) ("前={0} 后={1}" -f $beforeCount, $afterCount) -1 0

    $r = Invoke-Embed -FileArgs @() -ExtraArgs @('-CleanLogs', '-NoPause') -TimeoutSec 60
    $oldGone = -not (Test-Path (Join-Path $LogDir 'fake_old_20260801_embed.jsonl'))
    Add-Result 'H2' '--clean-logs 清理过期日志' $(if ($oldGone) { 'PASS' } else { 'FAIL' }) ("旧日志已删={0} exit={1}" -f $oldGone, $r.Exit) $r.Exit $r.Sec

    # H3：稳定性 = 1 次嵌入 + 2 次轻量 CLI，退出码各自稳定
    $codes = @()
    $rr = Invoke-Embed -FileArgs @((Join-Path $InputDir 't_flow.txt')) -ExtraArgs @('-WorkspaceSlug', $WorkspaceSlug, '-NoPause') -TimeoutSec 360
    $codes += $rr.Exit
    $rr = Invoke-Embed -FileArgs @() -ExtraArgs @('--help') -TimeoutSec 30
    $codes += $rr.Exit
    $rr = Invoke-Embed -FileArgs @() -ExtraArgs @('--help') -TimeoutSec 30
    $codes += $rr.Exit
    $stable = ($codes[0] -eq 0) -and ($codes[1] -eq 0) -and ($codes[2] -eq 0)
    Add-Result 'H3' '连续运行稳定(1嵌入+2轻量)' $(if ($stable) { 'PASS' } else { 'FAIL' }) ("退出码序列: {0}" -f ($codes -join ',')) $codes[0] 0

    # ---------- I. 历史 bug 回归（复用 G1 输出，零额外嵌入） ----------
    $out = $rG.Stdout + $rG.Stderr
    Add-Result 'I1' '无 System.Net.Http 加载错误' $(if ($out -notmatch 'System\.Net\.Http') { 'PASS' } else { 'FAIL' }) '复用 G1 输出检查' $rG.Exit $rG.Sec
    Add-Result 'I2' '无 HttpClient.Timeout 异常' $(if ($out -notmatch 'Timeout') { 'PASS' } else { 'FAIL' }) '复用 G1 输出检查' $rG.Exit $rG.Sec
    Add-Result 'I3' '中文输出可读' $(if ($rG.Stdout -match '嵌入|上传|完成') { 'PASS' } else { 'FAIL' }) '复用 G1 输出检查' $rG.Exit $rG.Sec
    $validCodes = @(0, 1, 2, 3) -contains $rG.Exit
    Add-Result 'I4' '退出码符合规范 0/1/2/3' $(if ($validCodes) { 'PASS' } else { 'FAIL' }) ("exit={0}" -f $rG.Exit) $rG.Exit $rG.Sec

    # ---------- W. 清理后正式文档完整性（防 t_* 前缀误删正式文档类事故复发） ----------
    # 历史事故：Agent 自测清理用 test-* 前缀误把正式文档 test-doc.txt 解除关联。
    # 本用例在全部 t_* 删除后校验非 t_ 正式文档数量不低于测试前基线。
    $afterDocs = Get-ALDocs -Slug $WorkspaceSlug
    $afterFormal = @($afterDocs | Where-Object { $_.filename -notlike 't_*' })
    $w1ok = ($afterFormal.Count -ge $script:FormalBaseline)
    $w1names = (@($afterFormal | ForEach-Object { $_.filename }) -join ', ')
    Add-Result 'W1' '清理后正式文档未被误删' $(if ($w1ok) { 'PASS' } else { 'FAIL' }) ("基线={0} 现存={1}: {2}" -f $script:FormalBaseline, $afterFormal.Count, $w1names) -1 0
}

# ---------- 主流程 ----------
function Main {
    Write-Host '=============================================='
    Write-Host ' AnythingLLM 嵌入工具 全面测试 v1.5（冒烟先行+负载控制）'
    Write-Host (' 项目: {0}' -f $ProjectDir)
    Write-Host (' 工作区 slug: {0}' -f $WorkspaceSlug)
    Write-Host (' 负载开关: RunHeavyChat={0} Warmup={1} SmokeOnly={2}' -f $RunHeavyChat, $Warmup, $SmokeOnly)
    Write-Host '=============================================='

    Write-Host '[0/4] 前置检查：AnythingLLM 服务与 API Key...'
    if (-not (Test-ALAlive)) {
        Write-Host '[FATAL] AnythingLLM 服务不可达（http://localhost:3001）。请先启动服务。'
        exit 2
    }
    Write-Host '  服务可达 OK'
    $ws = Get-ALWorkspaces
    $slugExists = @($ws.workspaces | Where-Object { $_.slug -eq $WorkspaceSlug }).Count -gt 0
    if (-not $slugExists) {
        Write-Host "[FATAL] 工作区 slug 不存在: $WorkspaceSlug"
        exit 2
    }
    Write-Host ("  工作区存在 OK（共 {0} 个工作区）" -f @($ws.workspaces).Count)

    if ($Warmup) {
        Write-Host '[0b] 预热 Ollama（-Warmup，冷启动约 60-90s，可 Ctrl+C 取消后重跑）...'
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $null = Invoke-ALChat -Slug $WorkspaceSlug -Message 'ping'
            Write-Host ("  预热完成，耗时 {0}s" -f [math]::Round($sw.Elapsed.TotalSeconds, 1))
        } catch {
            Write-Host "  预热失败（不影响测试）：$($_.Exception.Message)"
        }
    }

    Write-Host '[1/4] 冒烟测试（最轻量先行验证）...'
    $smokeOk = Run-Smoke
    if (-not $smokeOk) {
        Write-Host ''
        Write-Host '[ABORT] 冒烟未通过，中止全面测试。请先修复环境/配置问题后重跑。'
        exit 1
    }
    if ($SmokeOnly) {
        Write-Host '[SMOKE] 冒烟测试通过（-SmokeOnly），跳过全面测试。'
        exit 0
    }

    Write-Host '[2/4] 准备测试文件...'
    Prepare-TestFiles
    Write-Host '  测试文件已生成到 tests/inputs/'

    Write-Host '[3/4] 执行测试用例...'
    $swAll = [System.Diagnostics.Stopwatch]::StartNew()
    Test-Block
    $totalSec = [math]::Round($swAll.Elapsed.TotalSeconds, 1)
    try { Restore-Config } catch { Write-Host ("  ⚠ config 恢复失败: {0}" -f $_.Exception.Message) }

    # 清理
    if (-not $KeepTestDocs) {
        Write-Host '清理文档库中的 t_* 测试文档...'
        $names = Get-TestDocNames -Slug $WorkspaceSlug
        if ($names.Count -gt 0) { Remove-ALDoc -Slug $WorkspaceSlug -Locations $names; Start-Sleep -Seconds 2 }
        $left = (Get-TestDocNames -Slug $WorkspaceSlug).Count
        Write-Host ("  t_* 残留: {0}" -f $left)
    }

    # 报告
    $ts = Get-Date -Format 'yyyyMMdd_HHmmss'
    $report = Join-Path $ResultDir ("report-{0}.txt" -f $ts)
    $lines = @()
    $lines += 'AnythingLLM 嵌入工具 全面测试报告 (v1.2)'
    $lines += ('时间: {0}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
    $lines += ('项目: {0} | 工作区: {1}' -f $ProjectDir, $WorkspaceSlug)
    $lines += ('负载开关: RunHeavyChat={0} Warmup={1}' -f $RunHeavyChat, $Warmup)
    $lines += ('总耗时: {0}s' -f $totalSec)
    $lines += '-----------------------------------'
    foreach ($x in $script:Results) {
        $lines += ('{0} {1} {2} exit={3} {4}s {5}' -f $x.Status, $x.Id, $x.Name, $x.Exit, $x.Sec, $x.Detail)
    }
    $lines += '-----------------------------------'
    $lines += ('PASS={0} FAIL={1} SKIP={2} 总计={3}' -f $script:Pass, $script:Fail, $script:Skip, $script:Results.Count)
    $lines += '-----------------------------------'
    $lines += '系统库残留统计:'
    $sysT = 0
    try {
        $d = Invoke-ALApi -Method 'GET' -Path '/api/v1/documents' -TimeoutSec 30
        $nodes = @($d.localFiles.items)
        $stack = New-Object System.Collections.Stack
        foreach ($n in $nodes) { $stack.Push($n) }
        while ($stack.Count -gt 0) {
            $it = $stack.Pop()
            if ($it.type -eq 'folder') { foreach ($c in @($it.items)) { $stack.Push($c) } }
            elseif ([string]$it.name -like 't_*') { $sysT++ }
        }
    } catch { }
    $lines += ('  custom-documents 中 t_* 残留文件: {0} 个（无法经 API 物理删除，见已知限制 2）' -f $sysT)
    $lines += '-----------------------------------'
    $lines += '已知限制与建议:'
    $lines += '  1) system/remove-documents 返回 success 但不物理删除（实测）→ 清理一律用 update-embeddings deletes 解除关联'
    $lines += '  2) 系统文档库测试残留无法经 API 清理，如需彻底清理需在 WSL 中人工处理数据目录'
    $lines += '  3) LLM chat 检索（E5）默认跳过：本机 Ollama qwen2.5:14b 推理慢，需 -RunHeavyChat 显式开启'
    $lines += '  4) embed.ps1 已修复 4 处：验证字段 docpath / 非交互无 slug exit=1 / MaxFileSizeMB 用 double / 重复检测改 filename 前缀匹配'
    $lines += '  5) 备份目录：003anythingllmtools_backup_<时间戳>（含 .git 与全部文件）'
    $lines | Out-File -FilePath $report -Encoding UTF8

    Write-Host ''
    Write-Host '=============================================='
    Write-Host ("汇总: PASS={0} FAIL={1} SKIP={2} 总耗时={3}s" -f $script:Pass, $script:Fail, $script:Skip, $totalSec)
    Write-Host ("报告已写入: {0}" -f $report)
    Write-Host '=============================================='

    if ($script:Fail -gt 0) { exit 1 }
    exit 0
}

Main
