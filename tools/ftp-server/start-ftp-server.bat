@echo off
rem AnythingLLM-Android v1.3 FTP 同步服务启动器 (内容保持 ASCII,防中文乱码)
chcp 65001 >nul
setlocal

where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Python not found. Install Python 3.9+ first: https://www.python.org/downloads/
    pause
    exit /b 1
)

python -c "import pyftpdlib" >nul 2>nul
if errorlevel 1 (
    echo [ERROR] pyftpdlib not installed. Run:  pip install pyftpdlib
    pause
    exit /b 1
)

echo Starting AnythingLLM-Android v1.3 FTP sync server...
python "%~dp0ftp_server.py" %*
