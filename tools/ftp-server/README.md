# FTP 同步服务(PC 端)— AnythingLLM-Android v1.3

未安装 AnythingLLM 时,手机 App 作为同步软件,把「资料库」内容通过 FTP 上传到 PC 目录。
本脚本在 PC 上开启一个轻量 FTP 服务(无需安装 AnythingLLM,无需管理员权限)。

## 1. 一次性安装依赖

```bat
pip install pyftpdlib
```

(已安装 Python 3.9+;若 `pip` 不在 PATH,用 `python -m pip install pyftpdlib`。)

## 2. 启动服务

双击 `start-ftp-server.bat`,或命令行:

```bat
python ftp_server.py
```

可自定义参数(全部可选):

```bat
python ftp_server.py --root D:\AnySync --port 2121 --user sync --password sync123
python ftp_server.py --password-env FTP_PWD        :: 从环境变量读密码,避免命令行泄露
```

默认: 目录 = 脚本同级 `any-sync`(自动创建) / 端口 2121 / 账号 `sync` / 密码 `sync123`。
**首次使用请修改默认密码**(`--password` 或 `--password-env`)。

启动后会打印本机局域网 IP(如 `ftp://192.168.1.100:2121`),手机端填这个地址。

## 3. Windows 防火墙放行(手机连不上时执行)

管理员 PowerShell 执行(把 2121 换成实际端口):

```powershell
netsh advfirewall firewall add rule name="AnythingLLM FTP Sync" dir=in action=allow protocol=TCP localport=2121
```

## 4. 手机端配置

1. 打开 App → 底部「资料库」→ 右上角设置(FTP 设置)。
2. 填写: FTP 主机 = PC 局域网 IP;端口 = 2121;用户名/密码 = 启动脚本时的账号密码;远端根目录 = `Library`(默认)。
3. 手机与 PC 在同一局域网,点「同步到 PC」即可上传。

## 5. 同步说明

- 资料库文件夹树会在 PC 目录下完整镜像:`any-sync/Library/文件夹1/子文件夹/文件名`。
- 链接条目以 `.url` 文件落盘(Windows 双击可打开原网页)。
- 增量同步:已同步且未变更的文件自动跳过;文件变更后重新上传。
- 仅手机 → PC 单向上传;PC 目录的删除不会影响手机。

## 6. 停止

在脚本窗口按 `Ctrl+C`。
