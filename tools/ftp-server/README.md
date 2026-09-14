# FTP 同步服务(PC 端)— AnythingLLM-Android v1.5

未安装 AnythingLLM 时,手机 App 作为同步软件,把「资料库」内容通过 FTP 上传到 PC 目录。
本脚本在 PC 上开启一个轻量 FTP 服务(无需安装 AnythingLLM,无需管理员权限)。

## 1. 启动服务(推荐,自动装依赖)

双击 `start-ftp-server.bat` 即可:
- 自动检测 Python;检测 `pyftpdlib` 或 `qrcode` 缺失时**自动执行 `pip install pyftpdlib qrcode`**;
- 安装失败会给出国内镜像重试命令(如清华源),无需手动查文档;
- 启动后**在命令窗口直接输出 FTP 配置二维码**(终端 ASCII 字符码),手机 App「资料库 → FTP 设置 → 扫码连接」对准屏幕即可一键填入地址/端口/账号/密码/远端根目录。

命令行启动(依赖需已安装,见下):

```bat
python ftp_server.py
```

## 2. 手动安装依赖(仅命令行方式需要)

```bat
pip install pyftpdlib qrcode
```

(已安装 Python 3.9+;若 `pip` 不在 PATH,用 `python -m pip install pyftpdlib qrcode`;国内网络慢可加 `-i https://pypi.tuna.tsinghua.edu.cn/simple`。)

可自定义参数(全部可选):

```bat
python ftp_server.py --root D:\AnySync --port 2121 --user sync --password sync123
python ftp_server.py --password-env FTP_PWD        :: 从环境变量读密码,避免命令行泄露
python ftp_server.py --qr-host 192.168.8.71        :: 指定二维码中的 IP(默认取检测到的第一个局域网 IP)
```

默认: 目录 = 脚本同级 `any-sync`(自动创建) / 端口 2121 / 账号 `sync` / 密码 `sync123`。
**首次使用请修改默认密码**(`--password` 或 `--password-env`)。

启动后会打印本机局域网 IP(如 `ftp://192.168.1.100:2121`),手机端填这个地址;
若手机不在脚本检测到的第一个网段,重启时加 `--qr-host <手机同网段的 PC IP>` 重新生成二维码。

## 3. Windows 防火墙放行(手机连不上时执行)

管理员 PowerShell 执行(把 2121 换成实际端口):

```powershell
netsh advfirewall firewall add rule name="AnythingLLM FTP Sync" dir=in action=allow protocol=TCP localport=2121
```

## 4. 手机端配置

1. 打开 App → 底部「资料库」→ 右上角设置(FTP 设置)。
2. **扫码连接(推荐)**:PC 端启动脚本后,手机点「扫码连接」对准命令窗口二维码,主机/端口/远端根目录/用户名/密码自动填入,点「保存并返回」生效。
3. 手动填写: FTP 主机 = PC 局域网 IP;端口 = 2121;用户名/密码 = 启动脚本时的账号密码;远端根目录 = `Library`(默认)。
4. 手机与 PC 在同一局域网,点「同步到 PC」即可上传。

## 5. 同步说明

- 资料库文件夹树会在 PC 目录下完整镜像:`any-sync/Library/文件夹1/子文件夹/文件名`。
- 链接条目以 `.url` 文件落盘(Windows 双击可打开原网页)。
- 增量同步:已同步且未变更的文件自动跳过;文件变更后重新上传。
- 仅手机 → PC 单向上传;PC 目录的删除不会影响手机。

## 6. 停止

在脚本窗口按 `Ctrl+C`。

## 7. macOS 使用(新增,AnySync v1.6)

`ftp_server.py` 本身跨平台,macOS 使用 `start-ftp-server.command` 双击启动(自动装依赖 + 权限提示)。

### 7.1 首次启动(三步)

1. **解除隔离(仅首次)**:双击 `start-ftp-server.command` 若提示『无法打开,因为无法验证开发者』,
   右键该文件 → 打开 → 再点『打开』;或终端执行:
   ```bash
   chmod +x start-ftp-server.command
   ./start-ftp-server.command
   ```
2. **防火墙放行(关键)**:弹出『python3 想要接受传入连接』→ 点**『允许』**(放行 FTP 2121 端口,手机才能连上)。
   若误点『拒绝』,修复路径:系统设置 → 网络 → 防火墙 → 选项 → 找到 python3/python → 改为『允许传入连接』。
3. **本地网络权限(macOS Sequoia 及以上)**:若弹『本地网络』权限请求 → 点『允许』。

### 7.2 常见问题

- **手机连不上**:
  1) 系统设置 → 网络 → 防火墙 需为『打开』且『选项』里 python3 已允许;
  2) 确认手机与 Mac 在同一 Wi-Fi/局域网;
  3) 若 Mac 有多个网卡(如虚拟机/VPN 虚拟网卡),二维码默认取 192.168 物理网段,
     手机不在该网段时用 `--qr-host` 指定: `./start-ftp-server.command --qr-host <Mac 局域网 IP>`。
- **依赖安装失败**:手动执行 `python3 -m pip install --user pyftpdlib qrcode`(国内慢加 `-i https://pypi.tuna.tsinghua.edu.cn/simple`)。
- **每次都在终端手动启动**:`cd tools/ftp-server && bash start-ftp-server.command`。

### 7.3 手机端不变

App「资料库 → FTP 设置 → 扫码连接」扫 Mac 终端二维码,或手动填 Mac 局域网 IP/2121/sync/sync123/Library。
