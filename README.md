# AnythingLLM 知识库批量导入脚本

> **AnythingLLM-Knowledge-Base-Batch-Import-Script** · 版本 v1.0.1

一个用于 **AnythingLLM** 的本地批量文档嵌入工具：在 Windows 上把文件（支持一次拖入多个）自动上传到 AnythingLLM 的"我的文档"，并嵌入到指定工作区，实现知识库的快速构建。配合 WSL 内 Docker 部署的 AnythingLLM 使用，全部通过官方 REST API 驱动，无需修改 AnythingLLM 安装文件。

---

## 目录

- [核心功能](#核心功能)
- [环境要求](#环境要求)
- [文件目录](#文件目录)
- [运行原理](#运行原理)
- [默认配置（代码中的地址与参数）](#默认配置代码中的地址与参数)
- [用户需要配置的内容](#用户需要配置的内容)
- [使用方法](#使用方法)
- [测试](#测试)
- [版本历史](#版本历史)

---

## 核心功能

- **两段式传入逻辑**：先选择"我的文档"中的文档文件夹，再选择目标工作区，最后统一执行嵌入 —— 文档（Document）与工作区（Workspace）解耦，文档全局共享，可多工作区复用
- **多文件两种处理模式**：
  - **统一存入**：所有文件 → 同一个文档文件夹 + 同一个工作区
  - **逐个存入**：每个文件分别选择文档文件夹 + 工作区（可逐项新建文件夹/工作区），最后统一嵌入
- **文档文件夹管理**：列出已有文件夹、新建文件夹、重名提示重新命名
- **工作区管理**：列出已有工作区、**新建工作区**、重名提示重新命名
- **中文文件名兼容**：上传前将中文名 ASCII 化（`uXXXX` 码点编码），中文原名写入文档元数据 `title`，避免 AnythingLLM 官方 slugify 处理中文名导致乱码
- **重复检测**：检测文档库中是否已有同名文件，可选择跳过/保留/替换/中止
- **15 种格式支持**：PDF、DOCX、DOC、TXT、MD、CSV、XLSX、PPTX、ORG、ADOC、RST、JSON、HTML、ODT、ODP
- **健壮性**：上传自动重试（2 次退避）、嵌入结果轮询验证、超时保护、操作日志（jsonl）、非交互自动化路径
- **界面美化（v1.0）**：彩色终端 Banner、阶段进度徽章、三色进度条、文件夹菜单（青蓝系）/ 工作区菜单（品红系）区分、汇总盒（含总耗时）

---

## 环境要求

| 组件 | 要求 | 说明 |
|---|---|---|
| Windows | 10 / 11，PowerShell 5.1+ | 主程序 embed.ps1 在 Windows 侧运行 |
| WSL | Ubuntu 发行版（本项目为 Ubuntu-26.04） | 承载 Docker 与 AnythingLLM |
| Docker | WSL 内 Docker Engine | 运行 anythingllm 容器 |
| AnythingLLM | Docker 部署，开放 3001 端口 | 官方镜像，**无需修改任何安装文件** |
| API Key | AnythingLLM 管理后台生成 | 写入 `tools\apikey.txt` |
| LLM / 嵌入器 | AnythingLLM 内配置（本地 Ollama 或在线模型） | 嵌入使用本地嵌入器，检索使用 LLM |

---

## 文件目录

```
003anythingllmtools/
├── README.md                        # 项目说明（本文件）
├── CHANGELOG.md                     # 完整迭代记录（v0.8 → v1.0.1 + 测试）
├── .gitignore                       # 忽略 apikey.txt / logs / backup
│
├── tools/                           # ★ 核心工具目录
│   ├── embed.ps1                    # 主程序（UTF-8 BOM，PowerShell 5.1+）
│   ├── EmbedIntoWorkspace.bat       # 拖拽入口（ANSI/GBK + CRLF，双击或拖文件到其上）
│   ├── config.json                  # 配置文件（服务地址、超时、扩展名白名单、大小限制等）
│   ├── apikey.txt                   # AnythingLLM API Key（⚠ 已 gitignore，勿上传）
│   ├── README.md                    # 工具级说明
│   └── logs/                        # 运行日志（jsonl，按日期命名）
│
├── tests/                           # 测试套件
│   ├── run-comprehensive-tests.ps1  # 全面测试执行器（v1.5，54 用例）
│   ├── smoke-hook.ps1               # 冒烟测试钩子（最轻量先行验证）
│   ├── testplan-comprehensive.md    # 测试计划文档
│   ├── inputs/                      # 测试输入文件（自动生成/保留素材）
│   ├── results/                     # 测试报告（txt）
│   └── optimization-plan-v0.8.md    # v0.8 优化方案
│
├── docs/                            # 开发文档
│   ├── AnythingLLM-EmbedTool-DevDoc-v0.8.md   # v0.8 开发文档
│   ├── EmbedIntoWorkspace-Optimization-4.0-*.md  # 优化方案
│   ├── 4.0/                         # v4.0 设计与项目计划
│   └── 过期/                        # 早期设计文档（保留存档）
│
├── scripts/                         # 编码治理脚本
│   ├── fix-bat-encoding.ps1         # BAT 中文乱码修复
│   ├── fix-ps1-encoding.ps1         # PS1 编码修复
│   └── README.md
│
├── backup/                          # 版本备份
│   ├── v0.8-20260910/               # v0.8 快照
│   └── v0.9-20260910/               # v0.9→v1.0.1 快照
│
└── txt/                             # 多格式测试文档（10 种格式样例）
```

---

## 运行原理

### 架构

```
 ┌────────────────────────────────────────────────────────────┐
 │ Windows 侧                                                    │
 │                                                              │
 │  拖入文件 → embed.ps1（PowerShell 5.1）                       │
 │              │  ① 校验（扩展名/大小/重复）                      │
 │              │  ② 上传（curl.exe multipart）                   │
 │              │  ③ 嵌入（批量 update-embeddings）               │
 │              │  ④ 轮询验证                                    │
 │              ▼                                                │
 │   AnythingLLM REST API  ── http://localhost:3001 ──┐         │
 └────────────────────────────────────────────────────┼─────────┘
                                                      ▼
 ┌────────────────────────────────────────────────────────────┐
 │ WSL 内 Docker（容器名 anythingllm）                           │
 │   文档存储：/app/server/storage/documents/                    │
 │   ├── custom-documents/          （"我的文档"默认文件夹）       │
 │   └── <自定义文件夹>/                                        │
 │   向量库：工作区嵌入向量（随工作区）                             │
 └────────────────────────────────────────────────────────────┘
```

### 处理流程

```
[1] 文件校验 → 过滤不支持的扩展名（15 种白名单）、超限文件（默认 100MB）、
              检测重复（detectDuplicates，非交互自动 skip）
[2] 选择文档文件夹 → 列出"我的文档"下文件夹（青蓝菜单），可新建，重名提示
[3] 选择工作区 → 列出已有工作区（品红菜单），可新建（调用 POST /api/v1/workspace/new），重名提示
[4] 上传 → POST /api/v1/document/upload（multipart，中文名先 ASCII 化，原名存 metadata.title）
[5] 嵌入 → POST /api/v1/workspace/:slug/update-embeddings（adds 批量一次调用）
[6] 验证 → 轮询 GET /api/v1/workspace/:slug，指数退避（2s→5s），直到文档出现
[7] 汇总 → 成功/失败/跳过计数 + 总耗时；写入日志 logs\yyyy-MM-dd_embed.jsonl
```

### 关键设计

- **文档与工作区解耦**：上传只进"我的文档"，嵌入才绑定工作区；同一文档可被多个工作区引用，删除工作区关联不影响文档库
- **中文名策略**：`filenamePolicy=unicode` 时，上传文件名转为 ASCII（`WSL网络配置指南` → `WSLu7f51u7edcu914du7f6eu6307u5357`），原始中文名写入 `metadata.title`，检索与显示均正常
- **批量嵌入**：多个文件合并为一次 `update-embeddings` 调用，减少 API 往返与嵌入队列压力（40 文件批量实测约 9s 完成）
- **非交互自动化**：`-WorkspaceSlug` / `-Folder` / `-Mode` 参数跳过全部菜单，CI/脚本可直接调用

---

## 默认配置（代码中的地址与参数）

### 服务地址

| 配置项 | 默认值 | 位置 |
|---|---|---|
| AnythingLLM API 地址 | `http://localhost:3001` | `tools\config.json` → `baseUrl` |
| 健康检查端点 | `GET /api/ping` | embed.ps1 内置 |
| 鉴权端点 | `GET /api/v1/auth` | embed.ps1 内置 |
| 上传端点 | `POST /api/v1/document/upload` | embed.ps1 内置 |
| 新建文件夹 | `POST /api/v1/document/create-folder` | embed.ps1 内置 |
| 删除文件夹 | `DELETE /api/v1/document/remove-folder` | embed.ps1 内置 |
| 工作区列表 | `GET /api/v1/workspaces` | embed.ps1 内置 |
| 新建工作区 | `POST /api/v1/workspace/new` | embed.ps1 内置 |
| 嵌入 | `POST /api/v1/workspace/:slug/update-embeddings` | embed.ps1 内置 |
| 验证轮询 | `GET /api/v1/workspace/:slug` | embed.ps1 内置 |

### config.json 默认值

| 字段 | 默认值 | 说明 |
|---|---|---|
| `baseUrl` | `http://localhost:3001` | AnythingLLM 服务地址 |
| `probeTimeoutSec` | `5` | 服务探活超时 |
| `uploadTimeoutSec` | `300` | 单文件上传超时 |
| `embedTimeoutSec` | `120` | 嵌入 API 调用超时 |
| `chatTimeoutSec` | `300` | LLM 检索超时（5 分钟上限） |
| `apiTimeoutSec` | `60` | 常规 API 调用超时 |
| `verifyTimeoutSec` | `300` | 嵌入结果轮询总超时 |
| `verifyBaseIntervalSec` / `verifyMaxIntervalSec` | `2` / `5` | 轮询间隔（指数退避） |
| `uploadRetryCount` | `2` | 上传失败重试次数 |
| `allowedExtensions` | 15 种（见上） | 扩展名白名单 |
| `maxFileSizeMB` | `100` | 单文件大小上限 |
| `defaultWorkspace` | `""` | 默认工作区（空=菜单选择） |
| `askForChatTest` | `false` | 嵌入后是否自动跑 LLM 问答测试 |
| `chatMode` | `query` | 检索模式（query=检索增强问答） |
| `detectDuplicates` | `true` | 重复检测开关 |
| `duplicateDefaultAction` | `ask` | 重复处理默认动作（ask/keep/skip/replace/abort） |
| `logDir` | `logs` | 日志目录（相对 tools/） |
| `logRetentionDays` | `30` | 日志保留天数 |
| `useTrueColor` | `true` | 终端真彩色（不支持时自动降级） |
| `filenamePolicy` | `unicode` | 中文文件名策略（unicode/strip/keep） |

### 代码内默认值（embed.ps1）

| 参数/默认 | 默认值 | 说明 |
|---|---|---|
| `-Folder` | `custom-documents` | 非交互模式默认文档文件夹 |
| `--clean-logs` 保留天数 | `30` | 与 config `logRetentionDays` 一致 |
| 窗口标题 | `AnythingLLM 嵌入工具 V1.0` | 版本标识 |
| 退出码 | `0` 成功 / `1` 错误 / `2` 用户取消 / `3` 部分失败 | — |

---

## 用户需要配置的内容

### 1. API Key（必配）

1. 打开 AnythingLLM 管理后台 → 开发者/API 设置 → 生成 API Key
2. 将 Key 保存到 `tools\apikey.txt`（纯文本，无换行符要求）

> ⚠ 该文件已被 `.gitignore` 排除，**切勿提交到 GitHub**。

### 2. 服务地址（默认已指向本机）

若 AnythingLLM 不在 `http://localhost:3001`（如改端口、远程部署），修改 `tools\config.json` 的 `baseUrl`。

### 3. 容器与 WSL 名称（脚本内常量，仅跨环境部署时需要）

- WSL 分发名：`Ubuntu-26.04`（脚本内部 docker exec 场景使用）
- Docker 容器名：`anythingllm`（官方 docker compose 默认名称）

### 4. 可选配置项

| 场景 | 配置 |
|---|---|
| 限制上传格式 | 编辑 `config.json` → `allowedExtensions` |
| 调整单文件上限 | `config.json` → `maxFileSizeMB`（默认 100MB） |
| 关闭重复检测 | `config.json` → `detectDuplicates: false` |
| 更换中文名策略 | `config.json` → `filenamePolicy`（`unicode` 推荐） |
| 自动 LLM 问答测试 | `config.json` → `askForChatTest: true`（注意会触发模型推理，较慢） |

---

## 使用方法

### 方式一：拖拽（推荐）

1. 打开 `tools\EmbedIntoWorkspace.bat`
2. 将文件直接拖到 BAT 图标上（支持多选拖入）
3. 按菜单提示：选择文档文件夹 → 选择工作区（可新建）→ 自动上传嵌入

### 方式二：命令行交互

```powershell
cd D:\WSL\AI-tools\003anythingllmtools\tools
powershell -ExecutionPolicy Bypass -File embed.ps1 "C:\docs\报告.pdf" "C:\docs\笔记.md"
```

### 方式三：非交互自动化

```powershell
# 全部文件 → custom-documents → 工作区 wsl
powershell -ExecutionPolicy Bypass -File embed.ps1 a.txt b.txt -WorkspaceSlug wsl -Folder custom-documents

# 多文件逐个存入
powershell -ExecutionPolicy Bypass -File embed.ps1 a.txt b.txt -Mode per-file

# 只诊断环境
powershell -ExecutionPolicy Bypass -File embed.ps1 --diagnose

# 清理过期日志（保留 30 天）
powershell -ExecutionPolicy Bypass -File embed.ps1 --clean-logs
```

### 参数一览

| 参数 | 说明 |
|---|---|
| `<file...>` | 拖入/传入的一个或多个文件 |
| `-WorkspaceSlug <slug>` | 指定工作区，跳过菜单（非交互必填） |
| `-Folder <name>` | 指定文档文件夹，不存在自动创建（默认 custom-documents） |
| `-Mode <unified\|per-file>` | 多文件处理模式（自动化用） |
| `-KeepDays <N>` | 日志保留天数（配合 --clean-logs） |
| `-MaxTotalMB <N>` | 日志最大总大小（配合 --clean-logs） |
| `-Answer <N>` | 自动选择重复检测动作（1=keep 2=skip 3=replace 4=abort） |
| `--diagnose` | 深度环境诊断 |
| `--clean-logs` | 清理过期日志 |
| `--no-pause` | 自动化不等待按键 |
| `--help` / `-h` | 显示帮助 |

---

## 测试

项目内置完整测试套件（`tests/run-comprehensive-tests.ps1`，v1.5，**54 用例**）：

```powershell
# 冒烟先行（最轻量，确认服务/Key/嵌入链路正常）
powershell -ExecutionPolicy Bypass -File tests\smoke-hook.ps1

# 全面测试（默认跳过 LLM 重负载用例）
powershell -ExecutionPolicy Bypass -File tests\run-comprehensive-tests.ps1

# 含 LLM 检索重负载（14B 本地模型，单次 1-5 分钟）
powershell -ExecutionPolicy Bypass -File tests\run-comprehensive-tests.ps1 -RunHeavyChat

# 只跑指定用例
powershell -ExecutionPolicy Bypass -File tests\run-comprehensive-tests.ps1 -CaseFilter "A1,D1,G1"
```

覆盖：静态文件完整性（BOM/编码/版本）、CLI 参数行为、环境容错、上传/嵌入/检索、批量吞吐、重复检测、BAT 入口、日志稳定性、历史 bug 回归、正式文档防误删等。最新结果见 `tests\results\`。

---

## 版本历史

完整迭代记录见 [CHANGELOG.md](CHANGELOG.md)。里程碑：

| 版本 | 内容 |
|---|---|
| v0.8 | 单文件上传嵌入、中文名处理、日志、诊断 |
| v0.9.2 | 扩展名白名单 8 → 15 种（新增 org/adoc/rst/json/html/odt/odp） |
| v0.9.3 | 支持新建工作区 |
| v0.9.4 | 逐项模式新建工作区修复、全局重名提示 |
| v0.9.5 | 菜单标题带文件上下文 |
| v1.0 | 界面全面美化（Banner/徽章/进度条/主题菜单）、正式版 |
| v1.0.1 | 修复菜单 Theme 参数重复 |
