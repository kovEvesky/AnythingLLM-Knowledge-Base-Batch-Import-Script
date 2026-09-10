@echo off
setlocal EnableExtensions

REM ---- 第一行即切换代码页，其后所有中文按 UTF-8 解析 ----
chcp 936 >nul 2>&1

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
set "SCRIPT_DIR=%~dp0"
:argloop
if "%~1"=="" goto argdone
set "PSARGS=%PSARGS% "%~1""
shift
goto argloop
:argdone

powershell -ExecutionPolicy Bypass -NoProfile -File "%SCRIPT_DIR%embed.ps1" %PSARGS%
set "RC=%errorlevel%"
chcp 936 >nul 2>&1
echo.
echo ===== 执行结束，退出码 %RC% （0=成功 1=错误 2=已取消 3=部分失败）=====
pause
exit /b %RC%
