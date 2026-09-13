#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AnythingLLM-Android v1.3 - PC FTP 同步服务
=========================================
未安装 AnythingLLM 时,手机 App 通过本脚本提供的 FTP 服务,
把「资料库」内容同步到 PC 指定目录(目录结构镜像手机端文件夹树)。

依赖: pyftpdlib (pip install pyftpdlib)
用法:
    python ftp_server.py [--root D:\\AnySync] [--port 2121]
                         [--user sync] [--password sync123]
                         [--password-env ENV_VAR_NAME]
默认 root = 本脚本同级 any-sync 目录(自动创建), port=2121, user=sync, password=sync123。

提示:
  - 手机与 PC 需在同一局域网;手机端填本机局域网 IP(启动时会打印)。
  - Windows 防火墙需放行该端口(管理员执行示例见 README)。
  - 首次使用建议修改默认密码。
"""
import argparse
import os
import socket
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def lan_ipv4_addresses():
    """列出本机局域网 IPv4 地址(供手机端填写)。"""
    addrs = set()
    try:
        # 通过 UDP 连接获取出口网卡 IP(不真正发包)
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        addrs.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        host = socket.gethostname()
        for info in socket.getaddrinfo(host, None, socket.AF_INET):
            addrs.add(info[4][0])
    except Exception:
        pass
    return sorted(a for a in addrs if not a.startswith("127."))


def main():
    parser = argparse.ArgumentParser(description="AnythingLLM-Android v1.3 FTP 同步服务")
    parser.add_argument("--root", default=None, help="PC 同步目录(默认:脚本同级 any-sync)")
    parser.add_argument("--port", type=int, default=2121, help="FTP 端口(默认 2121)")
    parser.add_argument("--user", default="sync", help="FTP 用户名(默认 sync)")
    parser.add_argument("--password", default="sync123", help="FTP 密码(默认 sync123)")
    parser.add_argument("--password-env", default=None, help="从环境变量读取密码(更安全)")
    args = parser.parse_args()

    if args.password_env:
        pwd = os.environ.get(args.password_env, "")
        if not pwd:
            print("[错误] 环境变量 %s 为空,无法启动" % args.password_env)
            sys.exit(1)
    else:
        pwd = args.password

    root = args.root
    if not root:
        root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "any-sync")
    root = os.path.abspath(root)
    os.makedirs(root, exist_ok=True)

    # ---- 依赖检查 ----
    try:
        from pyftpdlib.authorizers import DummyAuthorizer
        from pyftpdlib.handlers import FTPHandler
        from pyftpdlib.servers import FTPServer
    except ImportError:
        print("[错误] 缺少 pyftpdlib,请先执行:  pip install pyftpdlib")
        sys.exit(1)

    authorizer = DummyAuthorizer()
    authorizer.add_user(args.user, pwd, root, perm="elradfmwMT")

    handler = FTPHandler
    handler.authorizer = authorizer
    handler.encoding = "utf-8"
    handler.banner = "AnythingLLM-Android FTP Sync (v1.3)"

    class LoggedHandler(handler):
        def on_connect(self):
            print("[连接] %s:%s" % (self.remote_ip, self.remote_port), flush=True)

        def on_login(self, username):
            print("[登录] %s @ %s" % (username, self.remote_ip), flush=True)

        def on_file_sent(self, file):
            size = os.path.getsize(file) if os.path.exists(file) else -1
            print("[上传完成] %s <- %s (%d 字节)" % (file, self.remote_ip, size), flush=True)

        def on_file_received(self, file):
            size = os.path.getsize(file) if os.path.exists(file) else -1
            print("[接收完成] %s <- %s (%d 字节)" % (file, self.remote_ip, size), flush=True)

        def on_disconnect(self):
            print("[断开] %s" % (self.remote_ip,), flush=True)

    server = FTPServer(("0.0.0.0", args.port), LoggedHandler)
    print("=" * 60, flush=True)
    print("AnythingLLM-Android v1.3 FTP 同步服务已启动", flush=True)
    print("  本机目录 : %s" % root, flush=True)
    print("  端口     : %d" % args.port, flush=True)
    print("  账号     : %s / %s" % (args.user, "*" * len(pwd)), flush=True)
    print("  局域网 IP(手机端填):", flush=True)
    for ip in lan_ipv4_addresses():
        print("    ftp://%s:%d" % (ip, args.port), flush=True)
    if not lan_ipv4_addresses():
        print("    (未检测到局域网 IP,请用 ipconfig 查看)", flush=True)
    print("  提示: 手机端「设置 → FTP 同步」填写上述 IP/端口/账号密码,", flush=True)
    print("        再到「资料库」页点「同步到 PC」即可上传。", flush=True)
    print("  提示: 若手机连不上,请检查 Windows 防火墙是否放行 %d 端口。" % args.port, flush=True)
    print("  Ctrl+C 停止服务。", flush=True)
    print("=" * 60, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[停止] 服务已退出。", flush=True)


if __name__ == "__main__":
    main()
