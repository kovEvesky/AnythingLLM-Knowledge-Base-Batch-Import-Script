# AnythingLLM 文档嵌入工具 — 开发与测试全记录

**版本**：v0.8（工具） / v1.4（测试脚本）
**日期**：2026-09-10
**状态**：全量回归 PASS=41 / FAIL=0 / SKIP=1（第 10 轮）
**数据来源**：本文件融合了开发全程的真实执行记录，包括 opencode Agent 会话（`ses_f7ae00645ffeXiGP7KshL2T6po`，530 条消息，迭代驱动方为用户与外部测试）与本地测试报告。

---

## 1. 项目概览

### 1.1 目标

将"本地文件拖拽到 BAT → 自动上传并嵌入 AnythingLLM 工作区"的日常操作，做成**经过全面测试、可稳定交付**的成品工具。交付物：

| 产物 | 路径 | 说明 |
|---|---|---|
| 主工具 | `tools/embed.ps1` | PowerShell 5.1 脚本，约 1400 行，负责预校验/上传/嵌入/验证/日志 |
| 入口 | `tools/EmbedIntoWorkspace.bat` | 拖拽入口，调用 embed.ps1 |
| 配置 | `tools/config.json` | 外部配置（21 字段），缺省全部走内置默认值 |
| 测试脚本 | `tests/run-comprehensive-tests.ps1` | v1.4，42 个用例（41 PASS + 1 SKIP） |
| 测试方案 | `tests/testplan-comprehensive.md` | 测试体系设计文档（v1.3） |
| 优化方案 | `tests/optimization-plan-v0.8.md` | 本轮优化方案（v0.8） |
| 冒烟钩子 | `tests/smoke-hook.ps1` | 快速回归（约 4s） |

### 1.2 里程碑时间线（完整）

| 阶段 | 时间 | 结果 | 驱动者 |
|---|---|---|---|
| 设计文档 v4.0 + 实施规划 v1.0 | 09-09 | 54 项测试清单、7 阶段规划 | 用户提供 |
| V0.1–V0.7 分版本实施 | 09-09 15:46–15:58 | 全部 7 阶段完成，embed.ps1 1313 行 | Agent |
| Windows 侧调试（win-shell MCP） | 09-09 16:05–18:20 | 修复 B1–B3（环境缺陷） | Agent |
| 全链路验证 | 09-09 18:24–18:28 | 上传/嵌入/检索全通 | Agent |
| 优化方案 v1.0 → v1.1 | 09-09 18:33–18:41 | 9.1KB → 11.3KB | Agent |
| 非交互修复（-WorkspaceSlug 等） | 09-09 20:59–21:07 | 菜单降级、--help 解析 | Agent |
| 外部测试发现 Bug 1/2 | 09-09 22:06 | 验证字段 + 假删除 | **外部测试** |
| 测试脚本 v1.2 全面测试 | 09-09 22:16 | 简化验证通过（MCP 超时限制） | Agent |
| E3 非交互无 slug 卡死修复 | 09-09 23:11 | exit=1 快速报错 | 外部测试 |
| Bug A/B（第二轮） | 09-10 00:06 | [int]→[double]、非交互跳过 chat | 外部测试 |
| Bug 4 判重失效（第三轮） | 09-10 01:00 | title→filename 前缀 + 非交互 skip | 外部测试 |
| R1–R3 测试体系迭代 | 09-09 23:04–09-10 01:13 | 23/14/1 → 37/0/1 | 外部（豆包侧） |
| v1.4 测试增强 | 09-10 01:20 前后 | X1/T1/T2/E5/CaseFilter | 外部（豆包侧） |
| BAT Bug 6/7 修复 | 09-10 01:5x | shift %0 + GBK 编码 | 外部（豆包侧） |
| v0.8 增强（A1–A4 + -Answer） | 09-10 02:06–02:23 | R9 = 41/0/1 | Agent + 外部 |
| 工程治理 C1–C4 | 09-10 02:2x–02:3x | git/tag/钩子/清理/文档 | 外部（豆包侧） |
| C3 系统库清理 | 09-10 02:35 | 169 份残留删除，R10 = 41/0/1 | 外部（豆包侧） |

---

## 2. 运行环境与系统架构

### 2.1 物理环境

- **宿主机**：Windows 11 专业版（10.0.26200），PowerShell 5.1（无 pwsh 7）
- **WSL**：2.x，默认发行版
- **AnythingLLM**：**docker 容器** `anythingllm`（镜像 `mintplexlabs/anythingllm:latest`），端口 `3001`
  - Windows 侧访问 `http://localhost:3001` = wslrelay 转发到容器
  - 容器数据卷：`/var/lib/docker/volumes/anythingllm_data/_data` → 容器内 `/app/server/storage`
  - 文档存储：`<数据卷>/documents/custom-documents/*.json`
- **LLM**：Ollama `qwen2.5:14b-instruct`（8.37GB，KeepAlive 300s）
- **嵌入模型**：Ollama `bge-m3`（1.08GB）
- **测试用 API Key**：`tools/apikey.txt`（31 字符）

### 2.2 工作区清单

| 工作区 | slug |
|---|---|
| wsl子系统相关（测试用） | `fab03910-efd1-4985-bba8-ccfac8f316b5` |
| 人文社科 | `16dc4cef-7e2b-4b79-8d83-4de48a1c46c4` |
| 网络维护 | `01c465e1-9b58-4353-af2e-0f2e9194e2fc` |
| crx开发 | `crx` |
| 测试工作区 | `b63747b9-de84-47ef-a84e-385f1a45f2f1` |
| bug收集 | `bug` |

### 2.3 正式文档（必须保留，测试清理只动 `t_*` 前缀）

1. `WSL.md-9e94e4ac-44a0-40d7-8cdb-1e81dce522f7.json`
2. `raw-5-opencode-skills-ai-300percent-33d18a95-c734-455f-9808-811e684dfd78.json`
3. `test-doc.txt-733a58e7-0a1b-4a3a-b801-16f5ccf924a3.json`

### 2.4 Windows / WSL 双目录与同步机制

实测确认（探针验证）：`D:\WSL\AI-tools\003anythingllmtools` 与 WSL `/home/admin/003anythingllmtools` 是**两个独立目录**，互为副本，**无自动同步**。当前 `embed.ps1` 两侧 MD5 一致（`1bc9913bac2a640cdf262944ae3fd610`）。

**同步机制演化（重要教训）**：
- 早期手动注入代码 → **文件损坏为仅 6 字节**（B3，PowerShell 字符串操作导致 BOM 叠加/截断）
- 稳定方案：`python -c "import subprocess; data = subprocess.check_output(['wsl','cat',...]); open(win_path,'wb').write(data)"` —— 唯一可靠方法；WSL UNC 路径（`\\wsl.localhost\...`）从 Windows PowerShell 不可用
- 最终管理策略：git 仓库以 WSL 侧为权威（baseline + tag 均在此），Windows 侧为可执行副本；任何新增/修改需显式同步 + MD5 核对

---

## 3. 工具设计

### 3.1 功能清单（10 项）

1. 拖拽即用，支持单/多文件批量
2. 多文件两种模式：统一模式（同一工作区）/ 逐项模式（每文件自选工作区）
3. 通用方向键菜单选择工作区，支持分页与关键字过滤
4. 文件预校验：扩展名白名单、大小上限、空文件、占用检查（FileShare.None）、长路径
5. 上传前重复检测（按 filename 前缀匹配）：跳过 / 替换 / 全部保留
6. 嵌入后批量轮询验证（60s 窗口 + 退避）
7. 可选测试检索（query 模式）
8. 结构化 JSONL 日志 + 过期清理
9. 外部 config.json 配置（注释、校验、默认值回退）
10. 语义化退出码：0 成功 / 1 错误 / 2 取消 / 3 部分失败

### 3.2 参数表（v0.8）

```
embed.ps1 <file...> [-WorkspaceSlug <slug>] [--diagnose] [--clean-logs]
           [-KeepDays N] [-MaxTotalMB N] [-Answer N] [--no-pause] [--help]
```

| 参数 | 说明 | 来源 |
|---|---|---|
| `-WorkspaceSlug` | 指定工作区，非交互模式必需 | E1（非交互自动化） |
| `--diagnose` | 六段环境诊断 | v0.8 A3 |
| `--clean-logs` | 清理日志；`-KeepDays`/`-MaxTotalMB` | v0.8 A4 |
| `-Answer N` | 自动化测试，直接选菜单项；越界回退交互 | v0.8 |
| `--no-pause` | 结束不暂停 | — |

### 3.3 config.json 关键配置

`baseUrl` / `probeTimeoutSec` / `uploadTimeoutSec` / `embedTimeoutSec` / `chatTimeoutSec` / `apiTimeoutSec` / `verifyTimeoutSec` / `verifyBaseIntervalSec` / `verifyMaxIntervalSec` / `uploadRetryCount` / `allowedExtensions` / `maxFileSizeMB`（**double**）/ `defaultWorkspace` / `askForChatTest` / `chatMode` / `detectDuplicates` / `duplicateDefault` / `logDir` / `logRetentionDays` / `useTrueColor`。

---

## 4. 测试体系设计

### 4.1 设计原则（来自用户全程约束）

1. **测试脚本先审阅再执行**：设计好全面测试后先给用户看，确认后才交给 Agent
2. **冒烟先行**：先跑最轻量 S1-S5（约 3s），失败 exit=1 中止
3. **负载控制**：E5（LLM 检索）默认 SKIP，需 `-RunHeavyChat` 显式开启；默认不开 chat
4. **测试后清理**：`t_*` 文档必须清理，不留残留
5. **每次变更前完整备份**：robocopy /MIR + SHA256 核对
6. **全程中文**

### 4.2 版本演进

| 版本 | 变更 |
|---|---|
| v1.0 | 9 类 35 例 |
| v1.1 | E5 默认 SKIP、嵌入调用合并、单用例硬超时 |
| v1.2 | 冒烟 S1-S5（失败 exit=1）；Setup 改 API 直传；关闭 askForChatTest；修 F 类参数名；E4 断言 |
| v1.3 | D6 断言加强为"重传不新增"；新增 W1 正式文档完整性用例；报告加残留统计与已知限制段 |
| v1.4 | X1 批量吞吐、T1/T2 BAT 实测、E5 改直调 chat API；修复 -CaseFilter 从未生效的 bug |

### 4.3 用例矩阵（v1.4）

A1-A6（自动化）、B1-B6（批量）、C1-C4（配置）、D1-D7（判重）、E2-E5（端到端）、F1-F2（文件）、G1/G3（常规）、H1-H3（日志）、I1-I4（基础设施）、W1（正式文档完整性）、X1（批量吞吐）、T1-T2（BAT 实测）。默认 SKIP=E5。

### 4.4 执行历史

| 轮次 | 报告 | 结果 | 耗时 | 备注 |
|---|---|---|---|---|
| R1 | 230443 | 23/14/1 | 1549.6s | 暴露 Confirm-Embedding 缺陷 |
| R2 | 234833 | 28/9/1 | 1159.6s | 测试脚本 v1.2 大改后 |
| R3 | 004706 | 37/0/1 | 22.2s | 全绿基线 |
| R4/R5 | 010639/011347 | 37/0/1 | — | 复验 |
| R6 | 011918 | 38/0/1 | — | v1.3 后，备份 012147 |
| R8 | 020059 | 41/0/1 | — | BAT 修复后，备份 020247 |
| R9 | 022905 | 41/0/1 | 29.4s | v0.8 增强后 |
| R10 | 023644 | 41/0/1 | 29s | C3 清理后，最终 |

### 4.5 测试执行环境约束（重要）

- **Agent（MCP 环境）无法直接跑全量测试**：win-shell MCP 单次调用超时约 120s，且非交互管道下 `Start-Process` 与交互提示会卡住 → 全量测试必须由**外部（豆包侧）在 Windows 终端直接运行**，Agent 只能做简化验证
- **pwsh 7 不存在**：测试命令固定用 `powershell`（PS 5.1）
- **14B 负载**：Ollama 冷启动首次调用 >60s（模型加载到显存），预热后可降至秒级

---

## 5. 缺陷发现与修复（完整迭代史）

### 5.1 设计阶段缺陷（v4.0 自带 B1–B9，实施前已修）

设计文档评审阶段即修复 9 处代码缺陷与 6 处一致性问题。代表项：
- `VerifyBaseIntervalSec`→2、`VerifyMaxIntervalSec`→5（配置默认值）
- `Test-InputFile` 占用检查需 `FileShare.None`
- `Set-WorkspaceEmbedding` 的 `Adds` 参数默认 `@()`（防 null 报错）
- `Remove-Documents` 两步回退（un-embed→物理删除）
- PS 5.1：无 `` `e `` 转义（用 `[char]27`）、`$args` 在函数内是函数作用域

### 5.2 Windows 调试期环境缺陷（B1–B3）

| # | 缺陷 | 现象 | 根因 | 修复 |
|---|---|---|---|---|
| B1 | `System.Net.Http` 类找不到 | 脚本崩溃 | PS 5.1 不自动加载程序集 | `Add-Type -Assembly System.Net.Http -ErrorAction Stop` |
| B2 | `HttpClient.Timeout` 修改报错 | 上传/嵌入/聊天时抛"实例已启动请求" | Timeout 是实例级共享状态，首请求后不可改 | `Get-HttpClient` 初始化时按最大超时值一次性设置，移除所有后续修改 |
| B3 | Windows 端文件损坏 | embed.ps1 仅 6 字节 | 手动注入同步破坏编码/BOM | Python `wsl cat` 读原始字节直写（唯一可靠同步法） |

### 5.3 外部测试发现的功能缺陷（5 处）

| # | 缺陷 | 发现场景 | 根因 | 修复 |
|---|---|---|---|---|
| Bug 1 | 验证永不过/假超时 | 外部冒烟：上传嵌入成功但 exit=1，验证超时 64s | `Confirm-Embedding` 用不存在的 `$_.location` 匹配，workspace 文档对象实际字段为 `docpath` | 匹配字段 `location`→`docpath`（第 740 行）；修复后 `sec:0` 秒级确认 |
| Bug 2 | 删除假成功 | 多次实测 REST 与 MCP 桥 | `DELETE /api/v1/system/remove-documents` 返回 `success:true` 但不物理删除 | `Remove-Documents`（第 688 行）重写为以 `update-embeddings deletes` 解除关联为主，物理删除仅作不依赖结果的辅助 |
| Bug 3/E3 | 非交互无 slug 卡死 | v1.2 E3 用例超时（90s） | 非交互未指定 slug 时自动选第一个工作区并跑全流程（14B 嵌入慢） | Main 增加非交互守卫（第 1148 行）：`IsInputRedirected` + 无 slug → 报错"非交互模式必须指定 -WorkspaceSlug" + exit=1 |
| Bug A | 小数上限被截断 | v1.2 第二轮 D7 用例失效 | `[int]0.001` 截断为 0 → 误判非正数回退 100 | `Assert-Config`（第 157 行）`[int]`→`[double]`；同函数检查其它数值字段 |
| Bug B | 非交互自动跑 chat | 14B chat 300s+ 超时，拖慢自动化 | 未区分交互/非交互 | Main 阶段六（第 1335 行）：非交互输出"跳过 chat 验证"并跳过；耗时从 300s+ 降至 4.3s |

### 5.4 判重失效（Bug 4，第三轮实测确认）

| 项 | 内容 |
|---|---|
| 现象 | 同名文件重复上传**永远不提示重复**，每次都重新上传+嵌入（实测 t_dup 已在工作区仍"已上传: 1"） |
| 根因 | `Find-DuplicateDocuments`（第 846 行）用 `$_.title` 匹配，但 workspace 文档对象**没有 title 字段**（title 在 metadata JSON 字符串内）→ 判重条件恒 False |
| 修复 1 | filename 前缀匹配：`$_.filename -match ('^' + [regex]::Escape($key) + '-')`（filename 格式 `<原文件名>-<uuid>.json`；[regex]::Escape 防正则特殊字符；中文文件名同样成立） |
| 修复 2 | `Select-DuplicateAction`（第 995 行）非交互默认 `'keep'`→`'skip'`——**keep 仍会上传产生重复向量，skip 才真正阻止** |
| 自测 | 首次上传 t_dup.txt（工作区 4 文档）→ 二次上传被跳过（仍 4 文档，无新增） |

### 5.5 BAT 缺陷（2 处，v1.4 T1/T2 实测暴露）

| # | 缺陷 | 现象 | 根因 | 修复 |
|---|---|---|---|---|
| Bug 6 | BAT 路径失效 | 拖拽运行报 embed.ps1 找不到 | argloop 的 `shift` 移动 `%0`，循环后 `%~dp0` 解析为 CWD | 循环前缓存 `set "SCRIPT_DIR=%~dp0"` |
| Bug 7 | BAT 编码错误 | 中文乱码/命令拆断 | 文件实际为 UTF-8，cmd 按 GBK 解析 | 用 bat-encoding-fixer 转 **GBK + CRLF + 无 BOM** |

### 5.6 测试脚本缺陷

- **-CaseFilter 参数从未生效**（v1.4 修复）：声明了参数但未接入用例分发逻辑，指定过滤被忽略。

### 5.7 事故与恢复

Agent 自测清理时误用 `test-*` 前缀，把正式文档 `test-doc.txt` 解除工作区关联。恢复：`update-embeddings adds` 重新关联，storage 数据未损。**教训：清理必须严格白名单（只动 `t_*`），且用 W1 用例常驻监控正式文档完整性。**

---

## 6. 优化方案迭代（v1.0 → v1.1 → v0.8）

### 6.1 早期优化方案（09-09，首次验证后）

**v1.0（9.1KB）**：按"文档版"输出，P0-P2 共 9 项（--workspace / --dry-run / 同步脚本 / 验证日志 / 重复清理 / 冷启动 / skip 参数 / 性能基准）。

**v1.1（11.3KB）**：用户换大模型后按**实战复盘**重写，与 v1.0 的差异（实战驱动的优先级调整）：
- 文件同步不可靠：P0 无细节 → **P0 必做**（B3 曾致文件损坏为 6 字节）
- Ollama 冷启动：风险表 → **P1 必做**（首次 chat 60-90s 超时 MCP）
- 非交互菜单阻塞：风险表 → **P1 必做**（IsInputRedirected 自动降级）
- 重复文档清理：P1 → **P0**（调试产生 4 份副本污染数据）

### 6.2 非交互自动化修复链（对应 E1/E7）

| 修复 | 内容 |
|---|---|
| `-WorkspaceSlug` 参数 | 跳过交互菜单直接指定工作区（自动化前提） |
| `--help` 手动解析 | PowerShell 不识别 `--` 前缀 switch，需在 param() 后手动扫 `$args` |
| `Confirm-Action` 降级 | 非交互自动返回 `true` |
| `Select-DuplicateAction` 降级 | 非交互自动返回 `skip`（由 keep 修正而来） |
| `Read-FilteredChoice` 降级 | 非交互自动返回第一项 |
| 非交互无 slug | 报错 + exit=1，不自动选工作区 |

### 6.3 v0.8 优化方案（A1-A4 + -Answer）

#### A1 判重"替换"语义
交互菜单新增「替换」：`update-embeddings deletes` 解除旧 docpath 关联 → 重新上传嵌入；非交互保持自动 skip。验收：交互替换后工作区文档数量不增；D6 仍 PASS。

#### A2 批量失败容错
逐文件执行、单文件失败仅记录；嵌入超时自动重试 1 次（间隔 5s）；输出成功/失败/预校验失败明细；退出码 0=全成功 / 3=部分失败 / 1=全失败。自测：2 正常 + 1 超限 → exit=3 且汇总正确。

#### A3 --diagnose 六段诊断
API 可达性 / API Key（脱敏末 4 位）/ 嵌入模型 / Config 生效值（14 项）/ 日志治理 / 文档残留。修复 `--diagnose` 手动解析（PS 不识别 `--` 前缀 switch）。

#### A4 --clean-logs 保留策略
`-KeepDays N`（默认 30）+ `-MaxTotalMB N`（0=不限制）；策略：先按天数删、超大小再删最旧；修复 `-KeepDays 0` 原本不删任何文件的 bug。

#### -Answer N
1=keep / 2=skip / 3=replace / 4=abort；越界（非 1-4）警告并回退交互，不崩溃；--help 标注"自动化测试用"。

---

## 7. 工程治理（C1-C4）

### C1 git 规范化（含敏感泄露事故复盘）

仓库原为 git init 后**零提交**（全部 untracked）。执行：

1. `.gitignore`：`apikey.txt` / `**/apikey.txt` / `*.bak`
2. baseline commit `291480c`（38 文件）→ **发现 apikey.txt 被一并提交（敏感泄露）**
3. 修复：`git rm --cached apikey.txt tools/apikey.txt` + .gitignore 加固 → commit `0ff9653` → 重建 tag `v0.8`
4. 收尾 commit `cb12cf3`（smoke-hook + CHANGELOG + README）

验证：`git ls-files | grep apikey` → NONE。**教训：敏感文件提交后必须用 `git rm --cached` 移出跟踪，且 gitignore 用 `**/` 覆盖子目录。**

### C2 冒烟钩子

- `tests/smoke-hook.ps1`：调用测试脚本 `-SmokeOnly`（约 4s），exit=0 通过
- `.git/hooks/pre-commit`：非阻塞提示"建议先跑冒烟"（不拦截 commit）

### C3 系统库残留清理（高风险，备份先行）

**背景**：REST `remove-documents` 假成功，测试残留累积 169 份 `t_*`/`test-*` json。

**执行流程**（白名单严格校验）：
1. 探查：AnythingLLM 在 docker 容器，数据卷 `/var/lib/docker/volumes/anythingllm_data/_data`
2. 备份：`tar -czf` 备份整个 custom-documents（28K）
3. 清单白名单：`^(t_|test-)` 且**排除** `test-doc.txt-733a58e7`（正式文档以 test- 开头，极易误删）
4. `docker stop` → 删除 169 份 → `docker start`
5. 核验：文件层剩 3 份正式文档；**API 层残留 0**（DB 同步）

### C4 文档同步

- `tools/README.md` 追加 v0.8 增强段
- 新建 `CHANGELOG.md`（v0.8 / v0.7 两版）

---

## 8. 环境约束与已验证"死路"

### 8.1 AnythingLLM API 实战坑点（违反必踩坑）

| 坑点 | 说明 |
|---|---|
| **adds 传 location 不是 docId** | `POST /api/v1/document/upload` 返回的 `id` 不能用于嵌入；必须用响应里的 `location`（`custom-documents/xxx.json`）。传 docId 静默失败：接口仍 200、日志出现 telemetry，但工作区 documents 恒为空 |
| **telemetry 不代表成功** | `documents_embedded_in_workspace` 事件在 batch 开始时就发送；判断成功只看两处：工作区 documents 非空、chat 的 sources 命中 |
| **物理删除假成功** | `DELETE /api/v1/system/remove-documents` 返回 `success:true` 但不删（REST + MCP 桥 :3002 均实测）；可靠清理 = `update-embeddings deletes` 或容器数据卷白名单删文件 |
| **JSON body 落盘** | PowerShell 直接 `curl -d "..."` 引号被吞 → 400；写临时文件 `--data-binary "@file"` |
| **Docker 重启丢嵌入队列** | `docker restart` 后已触发的异步嵌入可能消失（接口 200 但没迁入）；重试 update-embeddings 即可 |
| **文档列表结构** | `GET /api/v1/documents` 返回 `{"localFiles":{...}}`，解析取 `localFiles.items[0].items` |
| **workspace 文档对象无 title** | title 在 metadata JSON 字符串内；可靠字段：filename / docpath |
| **路由不存在** | `/api/v1/workspace/{slug}/upload`、`delete-documents` 不存在（返回 SPA HTML） |

### 8.2 平台与编码

| 结论 | 说明 |
|---|---|
| PS 5.1 参数吞 | `-File` 位置参数与命名开关混用会吞参数 |
| `--` 前缀 switch | PowerShell 不识别 `--` 前缀，需手动解析 `$args` |
| BAT 引号剥离 | cmd `/c` 直接以引号开头会被剥离（需 `call` 前缀） |
| 编码矩阵 | `.bat`=GBK+CRLF+无BOM；`.sh`=UTF-8+LF；`.wslconfig`=UTF-8+LF；含中文 `.ps1`=UTF-8 **带 BOM**（PS 5.1 否则 GBK 误读崩溃） |
| `Add-Content -Encoding UTF8` | 每行重复写 BOM，JSONL 追加必须用 `AppendAllText` |
| PowerShell 调 wsl 丢反斜杠 | 需 `cmd /c` 复现 cmd 上下文 |
| MCP 超时 | win-shell MCP 单次约 120s，无法直接跑全量测试 |
| Ollama 冷启动 | qwen2.5:14b 首次加载到显存 60-90s+（KeepAlive 300s），后续秒级 |

### 8.3 技能与环境迁移

- 删除 opencode 侧 `anythingllm-knowledge-embed` 技能（opencode.jsonc 中无 skills 配置，无需移除）
- 豆包技能 `anythingllm-mcp` 重命名 `anythingllm-selfskill` 加入 opencode（`C:\Users\Administrator\.config\opencode\skills\`），提供 check_auth / list_workspaces / search / chat / upload / update_embeddings 等工具

---

## 9. 验证结果与交付

### 9.1 回归结果

| 轮次 | 场景 | 结果 |
|---|---|---|
| R9 | v0.8 增强后全量 | 41/0/1（29.4s） |
| R10 | C3 清理后全量 | 41/0/1（29s） |
| 冒烟 | smoke-hook | 1s exit=0 |

### 9.2 备份清单

| 备份 | 时间 | 内容 |
|---|---|---|
| `003anythingllmtools_backup_20260910_012147` | v1.3 后 | 全量 |
| `003anythingllmtools_backup_20260910_020247` | v1.4 后 | 全量（97 文件 SHA256 全 OK，含 .git） |
| `003anythingllmtools_backup_20260910_024025` | **最终** | 全量，关键文件 SHA256 全 OK |
| `custom-documents-backup-20260910_023524.tar.gz` | C3 前 | 系统库残留备份（回滚用） |

### 9.3 云盘交付（my.feishu.cn）

| 文件 | 链接 |
|---|---|
| 优化方案 v0.8 | https://my.feishu.cn/file/IDmHbWLXWosgTMx7zDpc6JwKnSb |
| 测试脚本 v1.4 | https://my.feishu.cn/file/K933bhRJPoD8HEx7gpxcdlKmnsg |
| 第 8 轮报告 | https://my.feishu.cn/file/RLl8bJK3LoPsryxOdpQcyOxPnKx |
| 测试方案 v1.3 | https://my.feishu.cn/file/PK5jbM1pZoBA9Wxlpq8cshI0nqf |

---

## 10. 经验教训与后续建议

### 10.1 核心教训

1. **测试要验证"真值"**：断言字段必须与真实 API 响应结构一致（`$_.location` → `$_.docpath`、`$_.title` → `$_.filename` 两次教训）。
2. **物理删除不可信**：API 返回 success ≠ 删除成功，必须回读核验（文件层 + API 层双重确认）。
3. **编码是 BAT 的第一性问题**：cmd 用 ANSI 代码页解析文件，UTF-8 BAT 必然拆断命令；chcp 65001 救不了。
4. **敏感文件要防在提交前**：git add -A 会把 apikey 带进仓库；.gitignore 必须用 `**/` 覆盖子目录并 `git ls-files` 验证。
5. **清理要白名单不要黑名单**：`test-*` 前缀会误伤 `test-doc.txt` 正式文档——用排除式白名单并常驻 W1 完整性用例。
6. **双目录是隐患**：Windows 与 WSL 侧互为独立副本，改一侧不会自动同步——所有变更需显式同步（Python wsl cat 法）并核对 MD5。
7. **负载决定测试策略**：14B 本地模型推理慢 + MCP 120s 超时，决定了"外部终端跑全量、Agent 跑简化验证"的分工与默认跳过重型 chat 用例。
8. **非交互是默认路径**：所有交互点（菜单/确认/判重/chat）都要做 `IsInputRedirected` 降级，否则自动化必然卡死。
9. **假成功接口要标注**：遇到"返回 success 但实际无效"的接口，立即改写语义并注释，不继续依赖。

### 10.2 后续建议

1. **v1.5 专项用例**：v0.8 新功能（A1 替换 / A4 KeepDays / -Answer / diagnose 六段）补进测试脚本，不再依赖 Agent 自测。
2. **残留监控**：把"custom-documents 中 t_* 残留数"纳入测试报告（可强化为断言）。
3. **同步机制**：为 Windows/WSL 双目录建立单向同步脚本（含 MD5 核对），避免再出现分叉。
4. **批量替换并发安全**：A1 的"批量替换"在极端并发下可能重复解除关联，可加幂等保护。
5. **--dry-run 与 --skip-chat**：早期 P0/P2 项仍未落地，自动化批处理场景可选补。

---

*本文件由开发全程记录整理，数据来源：测试报告（tests/results/report-*.txt）、备份目录、git 提交记录、opencode Agent 会话消息（530 条）。*
