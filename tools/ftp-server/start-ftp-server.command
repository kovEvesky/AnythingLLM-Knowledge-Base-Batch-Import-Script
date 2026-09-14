#!/bin/bash
# ============================================================
#  AnythingLLM-Android FTP 同步服务 — macOS 版启动器 (v1.6)
#  用法:
#    1) 双击本文件(首次可能提示"无法验证开发者",见下方提示)
#    2) 或终端执行: chmod +x start-ftp-server.command && ./start-ftp-server.command
#  可传参,如: ./start-ftp-server.command --port 2121 --user sync --password sync123
# ============================================================
cd "$(dirname "$0")" || exit 1

echo "============================================================"
echo "  macOS 权限提示(仅首次运行需要,请逐条确认):"
echo "  ① 若弹出『python3 想要接受传入连接』对话框"
echo "     → 务必点『允许』(这是放行 FTP 2121 端口供手机连接)"
echo "  ② 若之前误点过『拒绝』:"
echo "     系统设置 → 网络 → 防火墙 → 选项 →"
echo "     找到 python3/python,改为『允许传入连接』"
echo "  ③ 若手机仍连不上: 系统设置 → 网络 → 防火墙 需为『打开』"
echo "     并确认上方『选项』里 python3 已允许"
echo "  ④ macOS Sequoia 及以上如弹『本地网络』权限 → 点『允许』"
echo "============================================================"
echo ""

# ---- 1. 检查 python3 ----
if ! command -v python3 >/dev/null 2>&1; then
    echo "[错误] 未找到 python3。请先安装:"
    echo "   brew install python"
    echo "   或 https://www.python.org/downloads/macos/"
    read -r -p "按回车键退出..." _
    exit 1
fi

# ---- 2. 自动安装依赖(pyftpdlib + qrcode) ----
if ! python3 -c "import pyftpdlib, qrcode" >/dev/null 2>&1; then
    echo "[INFO] 缺少 pyftpdlib 或 qrcode,正在自动安装..."
    if ! python3 -m pip install --user --disable-pip-version-check pyftpdlib qrcode; then
        echo "[WARN] 默认源安装失败,改用清华镜像重试..."
        python3 -m pip install --user --disable-pip-version-check -i https://pypi.tuna.tsinghua.edu.cn/simple pyftpdlib qrcode
    fi
    echo "[INFO] 依赖安装完成。"
fi

# ---- 3. 校验依赖(安装失败则明确退出) ----
if ! python3 -c "import pyftpdlib" >/dev/null 2>&1 || ! python3 -c "import qrcode" >/dev/null 2>&1; then
    echo "[错误] 依赖安装失败,请手动执行:"
    echo "  python3 -m pip install --user pyftpdlib qrcode"
    read -r -p "按回车键退出..." _
    exit 1
fi

# ---- 4. 启动 FTP 服务(透传参数) ----
echo "[INFO] 启动 FTP 同步服务(Ctrl+C 停止)..."
echo ""
exec python3 ftp_server.py "$@"
