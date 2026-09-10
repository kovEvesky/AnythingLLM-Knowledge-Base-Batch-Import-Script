<#
.SYNOPSIS
    修复 PowerShell 脚本文件的编码：转为 UTF-8 带 BOM + CRLF。
.DESCRIPTION
    将指定的 .ps1 文件转换为 UTF-8 编码（带 BOM）并确保行尾为 CRLF。
    PS 5.1 对无 BOM 的 .ps1 会按系统默认编码（ANSI）解析，中文注释和字符串会乱码。
    带 BOM 时 PS 5.1 会正确识别为 UTF-8。
.PARAMETER Path
    要处理的 .ps1 文件路径（支持通配符）。
.EXAMPLE
    .\fix-ps1-encoding.ps1 -Path "..\tools\embed.ps1"
    .\fix-ps1-encoding.ps1 -Path "..\tools\*.ps1"
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Path
)

$ErrorActionPreference = 'Stop'

$files = @(Get-Item -LiteralPath $Path -ErrorAction SilentlyContinue)
if ($files.Count -eq 0) {
    Write-Warning "未找到文件: $Path"
    exit 1
}

foreach ($file in $files) {
    if ($file.Extension.ToLowerInvariant() -ne '.ps1') {
        Write-Warning "跳过非 PS1 文件: $($file.FullName)"
        continue
    }

    Write-Host "处理: $($file.FullName)" -ForegroundColor Cyan

    # 读取原始字节
    $raw = [IO.File]::ReadAllBytes($file.FullName)

    # 去掉已有的 BOM（如有）
    $start = 0
    if ($raw.Length -ge 3 -and $raw[0] -eq 0xEF -and $raw[1] -eq 0xBB -and $raw[2] -eq 0xBF) {
        $start = 3
        Write-Host "  已有 UTF-8 BOM，跳过" -ForegroundColor Yellow
    } else {
        # 按 UTF-8 严格解码，失败则回退 ANSI
        $utf8Strict = New-Object System.Text.UTF8Encoding($false, $true)
        try {
            $content = $utf8Strict.GetString($raw)
        } catch {
            Write-Warning "  文件非 UTF-8 编码，按系统默认编码读取"
            $content = [Text.Encoding]::Default.GetString($raw)
        }

        # 统一为 CRLF
        $content = $content -replace "`r`n", "`n"
        $content = $content -replace "`n", "`r`n"

        # 写回 UTF-8 带 BOM
        $utf8Bom = New-Object System.Text.UTF8Encoding($true, $true)
        [IO.File]::WriteAllText($file.FullName, $content, $utf8Bom)
    }

    # 验证
    $verify = [IO.File]::ReadAllBytes($file.FullName)
    $hasBom = ($verify.Length -ge 3 -and $verify[0] -eq 0xEF -and $verify[1] -eq 0xBB -and $verify[2] -eq 0xBF)
    $hasLfOnly = $false
    for ($i = 0; $i -lt $verify.Length - 1; $i++) {
        if ($verify[$i] -eq 0x0A -and ($i -eq 0 -or $verify[$i - 1] -ne 0x0D)) {
            $hasLfOnly = $true; break
        }
    }

    if (-not $hasBom) {
        Write-Warning "  验证失败: 文件不含 BOM"
    } elseif ($hasLfOnly) {
        Write-Warning "  验证失败: 文件仍含 LF-only 行尾"
    } else {
        Write-Host "  完成: UTF-8 带 BOM + CRLF" -ForegroundColor Green
    }
}

Write-Host "全部完成" -ForegroundColor Green
