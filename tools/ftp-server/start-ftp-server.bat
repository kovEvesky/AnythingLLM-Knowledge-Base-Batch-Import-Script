@echo off
rem AnythingLLM-Android v1.4 FTP sync server launcher (ASCII only, no mojibake)
rem Auto-installs pyftpdlib when missing; retries with a mirror hint on failure.
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
    echo [INFO] pyftpdlib not found. Installing automatically...
    python -m pip install --disable-pip-version-check pyftpdlib
    if errorlevel 1 (
        echo.
        echo [ERROR] pip install failed. If network is slow, retry with a mirror, e.g.:
        echo         python -m pip install -i https://pypi.tuna.tsinghua.edu.cn/simple pyftpdlib
        pause
        exit /b 1
    )
    python -c "import pyftpdlib" >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] pyftpdlib still not importable after install. Check your Python environment.
        pause
        exit /b 1
    )
    echo [INFO] pyftpdlib installed.
)

echo Starting AnythingLLM-Android v1.4 FTP sync server...
python "%~dp0ftp_server.py" %*
