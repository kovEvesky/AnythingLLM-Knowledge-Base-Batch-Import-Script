@echo off
rem AnythingLLM-Android v1.5 FTP sync server launcher (ASCII only, no mojibake)
rem Auto-installs pyftpdlib + qrcode when missing; retries with a mirror hint on failure.
chcp 65001 >nul
setlocal

where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Python not found. Install Python 3.9+ first: https://www.python.org/downloads/
    pause
    exit /b 1
)

python -c "import pyftpdlib, qrcode" >nul 2>nul
if errorlevel 1 (
    echo [INFO] Missing dependency pyftpdlib or qrcode. Installing automatically...
    python -m pip install --disable-pip-version-check pyftpdlib qrcode
    if errorlevel 1 (
        echo.
        echo [ERROR] pip install failed. If network is slow, retry with a mirror, e.g.:
        echo         python -m pip install -i https://pypi.tuna.tsinghua.edu.cn/simple pyftpdlib qrcode
        pause
        exit /b 1
    )
    python -c "import pyftpdlib, qrcode" >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] Dependencies still not importable after install. Check your Python environment.
        pause
        exit /b 1
    )
    echo [INFO] Dependencies installed.
)

echo Starting AnythingLLM-Android v1.5 FTP sync server...
python "%~dp0ftp_server.py" %*
