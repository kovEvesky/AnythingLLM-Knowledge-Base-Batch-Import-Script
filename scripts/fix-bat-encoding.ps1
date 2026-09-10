<#
.SYNOPSIS
    修复 Windows .bat/.cmd 批处理文件的编码：转为 UTF-8 无 BOM + CRLF。
.DESCRIPTION
    将指定的 .bat 或 .cmd 文件转换为 UTF-8 编码（无 BOM）并确保行尾为 CRLF。
    PS 5.1 对带 BOM 的 BAT 可能解析异常，因此 BAT 必须无 BOM。
.PARAMETER Path
    要处理的 .bat 或 .cmd 文件路径（支持通配符）。
.EXAMPLE
    .\fix-bat-encoding.ps1 -Path "..\tools\EmbedIntoWorkspace.bat"
    .\fix-bat-encoding.ps1 -Path "..\tools\*.bat"
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
    $ext = $file.Extension.ToLowerInvariant()
    if ($ext -ne '.bat' -and $ext -ne '.cmd') {
        Write-Warning "跳过非 BAT/CMD 文件: $($file.FullName)"
        continue
    }

    Write-Host "处理: $($file.FullName)" -ForegroundColor Cyan

    # 读取原始内容
    $raw = [IO.File]::ReadAllBytes($file.FullName)

    # 去掉已有的 BOM（如有）
    $start = 0
    if ($raw.Length -ge 3 -and $raw[0] -eq 0xEF -and $raw[1] -eq 0xBB -and $raw[2] -eq 0xBF) {
        $start = 3
        Write-Host "  去除 UTF-8 BOM" -ForegroundColor Yellow
    }

    # 按 UTF-8 解码（无 BOM）
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false, $true)
    $content = $utf8NoBom.GetString($raw, $start, $raw.Length - $start)

    # 统一为 CRLF
    $content = $content -replace "`r`n", "`n"    # 先去 CR
    $content = $content -replace "`n", "`r`n"    # 再加回 CRLF

    # 写回文件（UTF-8 无 BOM + CRLF）
    [IO.File]::WriteAllText($file.FullName, $content, $utf8NoBom)

    # 验证
    $verify = [IO.File]::ReadAllBytes($file.FullName)
    $hasBom = ($verify.Length -ge 3 -and $verify[0] -eq 0xEF -and $verify[1] -eq 0xBB -and $verify[2] -eq 0xBF)
    $hasLfOnly = $false
    for ($i = 0; $i -lt $verify.Length - 1; $i++) {
        if ($verify[$i] -eq 0x0A -and ($i -eq 0 -or $verify[$i - 1] -ne 0x0D)) {
            $hasLfOnly = $true; break
        }
    }

    if ($hasBom) {
        Write-Warning "  验证失败: 文件仍含 BOM"
    } elseif ($hasLfOnly) {
        Write-Warning "  验证失败: 文件仍含 LF-only 行尾"
    } else {
        Write-Host "  完成: UTF-8 无 BOM + CRLF" -ForegroundColor Green
    }
}

Write-Host "全部完成" -ForegroundColor Green
