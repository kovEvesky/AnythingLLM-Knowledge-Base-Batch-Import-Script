# AnythingLLM 嵌入工具 下一轮优化方案（v0.8 / 测试 v1.4）

> 基于前 6 轮测试（R1→R6，最终 PASS=38/FAIL=0/SKIP=1）与 5 处已修复缺陷的研究结论制定。
> 配套脚本：`tests/run-comprehensive-tests.ps1`（v1.4，已落地 B 类低风险增强）
> 文档日期：2026-09-10

---

## 一、总览

| 层 | 项 | 内容 | 状态 |
|---|---|---|---|
| 测试 | v1.4 脚本 | X1 批量吞吐 / T1-T2 BAT 入口 / E5 chat 直测 / CaseFilter 修复 | ✅ 本轮已落地 |
| 工具 | A1 | 判重"替换"语义（同名+内容变更） | ⏳ 待 Agent |
| 工具 | A2 | 批量失败容错 + 嵌入超时重试 | ⏳ 待 Agent |
| 工具 | A3 | --diagnose 增强（模型/key/残留/日志全量诊断） | ⏳ 待 Agent |
| 工具 | A4 | --clean-logs 按天数/大小保留 | ⏳ 待 Agent |
| 测试 | B2 | 交互模式回归（stdin 注入菜单测试） | 📋 设计完成，下轮 |
| 工程 | C1 | git 提交规范化（每次修复 1 commit + tag） | 📋 待执行 |
| 工程 | C2 | 修改后自动 -SmokeOnly 快速回归钩子 | 📋 待执行 |
| 工程 | C3 | 系统库残留清理（WSL 数据目录，高风险） | 📋 谨慎评估 |
| 工程 | C4 | README / CHANGELOG 同步 | 📋 待执行 |

---

## 二、已落地：测试脚本 v1.4（本轮交付）

### 2.1 新增用例

| 用例 | 内容 | 验证点 | 成本 |
|---|---|---|---|
| **X1** | 4 文件直传 + 一次 update-embeddings 批量嵌入 | 批量上传/嵌入路径可用性 + 吞吐耗时 | 低（embedding 模型秒级） |
| **T1** | BAT 无参数调用 | BAT 提示逻辑 exit=2 | 极低 |
| **T2** | BAT + 文件 + slug + NoPause 全流程 | BAT 参数透传 → embed.ps1 全链路 exit=0 | 低（1 次嵌入） |
| **E5（增强）** | -RunHeavyChat 时直调 chat API | 嵌入后真实检索命中 + sources 来源引用 + 14B 推理耗时记录（修复后首次真实验证 chat 路径） | 高（14B 推理 1-5 分钟，仅显式开启） |

### 2.2 缺陷修复

- **-CaseFilter 参数从未生效**：参数已声明但 Add-Result 无过滤逻辑 → 已实现过滤（指定时仅报告命中用例，其余照常执行不破坏依赖）。

### 2.3 配套

- `Invoke-Bat`：cmd.exe 调用 BAT（stdin 重定向跳过结尾 pause），与 Invoke-Embed 同构。
- `Invoke-ALChat` 增加 `-TimeoutSec`（chat 420s 上限）。
- 测试文件新增 `t_bat.txt`、`t_bulk_1..4.txt`。

---

## 三、工具增强（embed.ps1 v0.8，待 Agent 实施）

> 每项均含验收标准；实施后必须回归测试（-SmokeOnly + 全量）+ 双侧 MD5 同步核验 + 完整备份。

### A1 判重"替换"语义
- **现状**：判重仅按文件名（同名即 skip），同名但内容已更新时无法覆盖旧版本。
- **设计**：判重命中后比较本地文件与库内文档内容哈希（上传前本地计算 SHA256；库内文档正文不可直接取，可比较 metadata 中的 wordCount/大小，或对比文件名 uuid 的时间戳提示"已有 N 个版本"）。
  - 交互：菜单追加选项「3) 替换（删除旧关联后重新嵌入）」。
  - 非交互：默认 skip（与现行为一致，不擅自替换）。
- **验收**：同名不同内容重复上传 → 交互出现"替换"选项；选替换后工作区旧文档被解除、新版本嵌入。
- **回归风险**：D6 用例（重传不新增）须保持 PASS。

### A2 批量失败容错
- **现状**：多文件嵌入时任一失败即中断整体（推断，需实测确认）。
- **设计**：逐文件执行，失败仅记入 `$failed` 列表并继续；结尾汇总"成功 N / 失败 M / 明细"；嵌入接口调用超时自动重试 1 次（间隔 5s）。
- **验收**：10 个文件（含 1 个超限）批量嵌入 → 其余 9 个成功、汇总列出 1 个失败原因，退出码按"部分失败"规范（若工具定义 3 则用 3）。
- **回归风险**：G1/H3 全流程退出码不变。

### A3 --diagnose 增强
- **现状**：C2 通过（输出诊断不阻断），但内容有限。
- **设计**：新增输出段——API 可达性（ping/auth 状态码）、API key 来源文件与长度（脱敏末 4 位）、嵌入模型加载状态（GET /api/v1/system/embedder）、config 逐项生效值、日志目录大小与文件数、系统库文档总数与 t_* 残留数。
- **验收**：`--diagnose` 输出以上全部信息，服务不可达时仍能输出"无法连接"而非抛错。

### A4 --clean-logs 保留策略
- **现状**：仅按文件名模式 `*_embed.jsonl` 删除。
- **设计**：新增可选参数 `-KeepDays N`（默认 30）与 `-MaxTotalMB N`；同时满足两者才清理；无参数时保持现有行为（向后兼容）。
- **验收**：H2 用例（旧日志清理）保持 PASS；`-KeepDays 0` 清空全部。

---

## 四、测试补强（B2，下轮）

### B2 交互模式回归
- **现状**：38 用例全为非交互（stdin 重定向），交互菜单（判重选择、工作区选择、确认提示）从未被自动化覆盖。
- **设计**：独立脚本 `tests/run-interactive-tests.ps1`（不入主脚本，避免与全量混跑）——用 `System.Diagnostics.Process` + 显式写入 `StandardInput` 模拟按键序列（如重复检测菜单选"2 keep"），逐场景断言输出行与退出码。场景：重复上传选 keep / skip / abort、多工作区无 slug 选择菜单、AskForChatTest=true 交互 chat。
- **成本**：高（菜单 ID/坐标依赖终端渲染，脆弱）；收益中等。建议放到工具功能稳定后再做。
- **替代**（更稳）：给 embed.ps1 增加 `-Answer <n>` 隐藏参数（自动化直接指定菜单项），测试脚本按 `-Answer` 驱动交互分支——成本更低、不依赖终端渲染。

---

## 五、工程与运维（C）

### C1 git 提交规范化
- 当前仓库含 .git；建立约定：**每次工具/脚本修复 = 1 个 commit + `vX.Y` 语义化 tag**；备份目录与 tag 对齐。
- 待办：确认当前工作树差异 → 提交 baseline commit → 打 `v0.7-fixes` tag。

### C2 快速回归钩子
- 修改 `tools/embed.ps1` 或 `tests/run-comprehensive-tests.ps1` 后，执行：
  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File tests\run-comprehensive-tests.ps1 -SmokeOnly
  ```
  （约 4s，S1-S5 全链路冒烟）；可通过 git hook（pre-commit）或手动脚本固化。

### C3 系统库残留清理（高风险，谨慎）
- **现状**：`custom-documents/` 累积约 40 份测试残留（t_*/test-*），物理删除接口假成功无法 API 清理。
- **方案**：在 WSL 中定位 AnythingLLM 数据目录（`/app/server/storage/documents/custom-documents` 或配置路径）→ **停止服务** → 删除 `t_*`/`test-*` json → 重启。需先确认数据目录真实路径与备份。
- **风险**：误删正式文档（WSL.md/raw-5/test-doc.txt 的 json）；必须严格按白名单前缀 + 删除前备份 + 删除后清单核对。

### C4 文档同步
- `tools/README.md`（如有）与 `tests/testplan-comprehensive.md` 更新：v0.8 变更说明、v1.4 用例清单、备份位置。

---

## 六、执行顺序与回归策略（建议）

1. **C1**（git baseline）→ 2. **A1**（Agent 实施 + 自测）→ 3. **A2/A3/A4**（Agent 同批实施）→ 4. **全量回归 v1.4**（PASS 基线 38）→ 5. **B2-替代方案**（-Answer 参数）→ 6. **C2/C4** → 7. **C3**（独立排期，含备份与回滚预案）。

每步完成标准：脚本语法 + 冒烟 + 关键用例回归 + 双侧 MD5 一致 + 完整备份（`003anythingllmtools_backup_<时间戳>`）。

---

## 七、风险与回滚

- **A1/A2 改动工具主流程**：回归失败即回滚 embed.ps1 至上一次备份版本（备份策略已就绪）。
- **B2-替代方案（-Answer）**：新增参数不影响现有调用；不实现则维持 B2 挂起。
- **C3**：唯一高危操作，未确认数据目录与备份前不执行。

---

## 八、预期收益

- 工具侧：重复内容治理（A1）、批量场景可靠性（A2）、排障效率（A3）、日志可治理（A4）。
- 测试侧：BAT 入口与批量路径纳入回归（X1/T1/T2）、chat 链路首次真实验证（E5）、交互分支可自动化（B2 替代）。
- 工程侧：版本可追溯（C1）、改动即时回归（C2）、残留可治理（C3）、文档同步（C4）。


---

## 九、v1.4 落地过程中新发现的缺陷（追加记录）

### Bug 6：BAT 入口 shift 破坏 %0（T2 首次实测暴露，已修复）
- **现象**：`EmbedIntoWorkspace.bat <文件> -WorkspaceSlug ... -NoPause` 执行失败，powershell 报 `-File` 参数为 `CWD\embed.ps1` 不存在。
- **根因**：BAT 的 argloop 用 `shift` 重组 PSARGS，`shift` 会移动 `%0`；循环结束后 `%0` = 最后一个参数（`-NoPause`），`%~dp0embed.ps1` 随之失效（解析为 CWD）。
- **修复**：argloop 前缓存 `set "SCRIPT_DIR=%~dp0"`，powershell 行改用 `-File "%SCRIPT_DIR%embed.ps1"`。
- **验证**：BAT 全流程 exit=0（上传+嵌入+验证），T2 用例转为 PASS。
- **教训**：BAT 此前仅静态检查（A2），从未真实执行——首次实测即暴露致命 bug；证明 BAT 入口必须纳入自动化实测。

### Bug 7（潜伏）：BAT 编码原为 UTF-8 无 BOM（A2 盲区）
- **现象**：修复前 BAT 实际为 UTF-8 编码（cmd 按 GBK 解析中文 REM 有乱码风险）。
- **根因**：A2 仅断言"无 BOM + CRLF"，未验证编码必须是系统 ANSI（GBK）。
- **修复**：bat-encoding-fixer 转 GBK + CRLF + 无 BOM（已备份 .bak）。
- **A2 增强建议**：增加编码断言——用 UTF-8 解码含 U+FFFD 替换符即证明为 GBK（已作为验证手段）。