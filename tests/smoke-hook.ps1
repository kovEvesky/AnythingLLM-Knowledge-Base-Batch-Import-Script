# ============================================================
# AnythingLLM 嵌入工具 快速回归冒烟钩子 (C2)
# 用途：修改 tools/embed.ps1 / tools/EmbedIntoWorkspace.bat /
#       tests/run-comprehensive-tests.ps1 后执行快速冒烟（约 4s）。
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File tests\smoke-hook.ps1
# 退出码：0=冒烟通过  1=冒烟失败  2=服务不可达/脚本错误
# ============================================================
param(
    [string]$ProjectDir = 'D:\WSL\AI-tools\003anythingllmtools'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$script = Join-Path $ProjectDir 'tests\run-comprehensive-tests.ps1'
if (-not (Test-Path $script)) { Write-Error "找不到测试脚本: $script"; exit 2 }

Write-Host '=== 快速回归冒烟（-SmokeOnly）==='
& powershell -NoProfile -ExecutionPolicy Bypass -File $script -SmokeOnly
$rc = $LASTEXITCODE
Write-Host ('=== 冒烟退出码: {0} {1} ===' -f $rc, $(if ($rc -eq 0) { '(通过)' } else { '(失败!)' }))
exit $rc
