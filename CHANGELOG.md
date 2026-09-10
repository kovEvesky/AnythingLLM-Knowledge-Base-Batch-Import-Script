# Changelog

## 重负载测试 (2026-09-10) — 大批量导入 + LLM 5 分钟限时
- **大批量导入压力测试**：40 文件（30 txt + 5 md + 5 json）一次批量传入（embed.ps1 非交互多文件路径，-WorkspaceSlug wsl）。
  - 结果：exit=0，上传 35 / 跳过 5（冒烟已传的 001-005 重复检测跳过）/ 失败 0 / 验证 35，**9.3s** 完成。
  - 工作区 wsl 40 个文档全部入库；无超时、无错误；重复检测逻辑正确（冒烟 5 文件不重复入库）。
- **LLM 重负载检索**（chat query 模式，14B 本地模型）：**10s 完成**（远低于 5 分钟限时），sources=4 检索命中，无超时。
- 结论：当前配置（verifyTimeoutSec=300 / uploadTimeoutSec=300）下大批量导入无错误；5 分钟模型限时绰绰有余。
- 清理：40 个 t_stress 关联已解除、custom-documents 物理删除、本地压力文件归档。
- 插曲：清理时 PowerShell 5.1 `Move-Item -Destination` 管道坑导致目标被写成文件（_trash 被 t_stress_040.json 内容覆盖、39 个测试垃圾文件被覆盖消失，无实质损失）；已重建 _trash 目录，tools/ 恢复整洁。

## 测试套件 v1.5 (2026-09-10) — 全量测试升级
- 在 v1.4（44 用例）基础上扩展至 **54 用例（PASS=54 / FAIL=0 / SKIP=1，E5 默认跳过）**。
- 新增 A7-A14 静态检查：Banner V1.0 徽章与副标题、汇总盒总耗时、菜单主题参数唯一（workspace×1/folder×1/无重复）、新建工作区函数存在、重名提示（工作区+文件夹≥2 处）、逐项模式刷新列表、文件夹标题上下文、config 15 种扩展名含 7 新格式。
- 新增 K 系列 API 功能：K1-K3 新建工作区创建/列表/删除、K4 新建/删除文件夹 API、K5 新格式 json/html/org 嵌入验证（Setup 直传+一次嵌入）。
- W1 改造：从"WSL 知识库 2 份锚点"改为"清理 t_* 后正式文档（非 t_ 前缀）数量不低于测试前基线"，消除对已清理环境的硬依赖。
- Setup 扩展：直传 9 文件（+t_fmt.json/html/org），Setup 嵌入 5 文件一次调用（t_basic+t_dup+t_fmt×3）。
- 实测：本轮全量测试 31.1s（短文档+本地嵌入器，与 v1.0 耗时趋势一致，非假阳性）；测试后物理清理 custom-documents 全部 t_* 残留（含 D5 空文件的 t_.txt 派生记录）。

## v1.0.1 (2026-09-10) — 修复菜单 Theme 参数重复
- 修复多文件拖入选择工作区时抛错"参数 Theme 被指定了多次"：替换脚本误将 `-Theme 'folder'` 重复注入工作区菜单调用（`-Theme 'folder' -Theme 'workspace'`）。
- 修正后：工作区菜单 `-Theme 'workspace'`（品红系）、文件夹菜单 `-Theme 'folder'`（青蓝系），两处调用唯一。
- 实测：语法通过；冒烟 5/5；-WorkspaceSlug 回归上传 1/1；测试残留已清理；已同步 WSL 与备份。

## v1.0 (2026-09-10) — 界面美化 + 正式版
- 版本号统一升级 V1.0（文件头、窗口标题、Banner 版本徽章、测试断言 A1）。
- **Banner 重构**：Format-Fixed 精确对齐框线（消除错位），青边框 + 白标题 + 黄 V1.0 徽章 + 功能副标题。
- **阶段徽章**：Write-Section 升级为 `[n] 标题 ═══` 徽章风格，7 个阶段带序号与主题色（校验=青/上传=青/嵌入=绿/验证=品红/重复检测·预览·测试=黄）。
- **Status 着色**：消息主体随类型着色（✔绿/✘红/⚠黄/ℹ灰），不再全灰。
- **进度条变色**：<50% 黄 → <100% 青 → 100% 绿。
- **汇总盒增强**：边框随结果变色（全成功绿/有失败红），新增"总耗时"行（Main 记录 Stopwatch）。
- **菜单主题化**：Select-MenuFromList 支持主题——**文件夹=青蓝系、工作区=品红系**（边框/高亮/选中标记双色区分），选中项反白背景 + 序号显示，非选中项灰底序号。
- **调色板统一**：所有颜色集中 $script:Palette（ANSI 真彩 + 传统色 fallback 双轨）。
- 修复：Write-Colored ANSI 拼接 bug（m 前多余空格，改用 -f 格式化）；Main 内联旧 Banner 替换为函数调用。
- 实测：语法通过；ANSI 序列字节级验证正确；冒烟 5/5；非交互全流程渲染回归（Banner/Section/汇总盒正常）；测试断言同步 V1.0。

## v0.9.5 (2026-09-10) — 文件夹/工作区选择标题带文件上下文
- 逐项模式（per-file）：选择文档文件夹的菜单标题由固定"选择文档文件夹（我的文档）"改为"为「当前文件名」选择文档文件夹"，与工作区选择提示对齐。
- 单文件：文件夹/工作区标题均带文件名（"为「xxx」选择…"）。
- 多文件统一存入（unified）：标题提示文件数（"为 N 个文件统一选择…"）。
- `Resolve-FolderSelection` 新增 `-Title` 参数透传（非交互 -Folder 路径不受影响）。
- 实测：语法通过；冒烟 5/5（基线工作区 wsl 已重建）；-Folder 非交互回归 1/1；测试残留已清理。

## v0.9.4 (2026-09-10) — 修复逐项新建工作区 + 重名提示重新命名
- 修复逐项模式（per-file）逐个新建工作区问题：此前新建的工作区不会加入循环内选择列表，后续文件菜单看不到；现每次新建后立即并入 `$workspaces`，后续文件可直接选择。
- 全局重名策略变更：工作区 / 文件夹新建时若名称已存在，不再自动复用，改为提示"已存在，请重新命名"并循环重新输入（回车取消）；文件夹名含 / 或 \ 时同样提示重输。
- 统一模式与逐项模式均遵循"选择新建 → 输入名称 → 立即创建 → 继续下一步"。
- 实测：语法通过；冒烟 5/5；per-file 非交互回归（自动逐项分配）2/2 上传+嵌入+验证；测试文档已清理。

## v0.9.3 (2026-09-10) — 选择工作区时支持"新建工作区"
- 新增 `New-Workspace`（POST /api/v1/workspace/new）与 `Select-Workspace` 函数：工作区选择菜单在已有工作区末尾追加"＋ 新建工作区…"选项，与 v0.9 文件夹选择的交互一致。
- 新建流程：选择"＋ 新建工作区…" → 输入名称 → 创建（重名则直接复用）→ 返回新工作区继续嵌入；无任何工作区时自动引导新建。
- 两处工作区选择（统一模式"选择目标工作区"、逐项模式"为「文件」选择工作区"）均替换为 `Select-Workspace`。
- 非交互/自动化（-WorkspaceSlug）路径不受影响；stdin 重定向时仍强制要求 -WorkspaceSlug。
- 实测：语法通过；API 层新建/删除工作区验证成功（slug 正确、中文名正常存储）；-WorkspaceSlug 回归上传+嵌入+验证 1/1；冒烟 5/5。

## v0.9.2 (2026-09-10) — 扩展支持格式至 15 种
- 依据 AnythingLLM 官方 collector 支持清单（31 种），将纯文本/办公类 7 种补入 config.json allowedExtensions：`.org .adoc .rst .json .html .odt .odp`（原 8 种 → 15 种）。
- 实测验证：html/json/org 三种上传+嵌入+验证全部通过（3/3），中文名 ASCII 化正常。
- 顺带核查：官方格式中仅 asXlsx 会创建临时目录（已由 v0.9.1 清理函数覆盖），其余格式无残留问题；音频/图片/mbox/epub 未纳入（按需再开）。
- 清理测试残留：t_* 测试文档（20+）、xlsx sheet 产物、fmt 测试文档，documents 仅保留正式文档。

## v0.9.1 (2026-09-10) — 修复 xlsx 残留空目录
- 问题：上传 xlsx 后"我的文档"目录顶层残留 `<文件名>.xlsx-<4位hash>` 空文件夹。
- 根因：AnythingLLM 官方 collector asXlsx.js 处理 xlsx 时创建临时目录存放各 sheet 的 CSV，随后 moveProcessedDocsToFolder 把 CSV 移入目标文件夹，但空目录本身不删除（官方行为）。
- 修复（工具侧，不改服务端）：embed.ps1 新增 Clean-EmptyXlsxDirs，在上传阶段完成后扫描 documents 顶层，匹配 `*.xlsx-<4位hex>` 且 items 为空的目录，用 DELETE /api/v1/document/remove-folder 删除；仅删空目录，绝不触碰有内容文件夹。
- 验证：上传 05产品需求规格书.xlsx 后新残留目录被当场清理；documents 顶层仅剩 1/2/custom-documents；冒烟 5/5 通过。

## v0.9 (2026-09-10) — 两段式传入逻辑（我的文档 → 工作区）
- 核心改造：新增"文档文件夹"维度（AnythingLLM 我的文档全局共享）。交互流程统一为「先选文档文件夹 → 再选工作区 → 最后统一嵌入」。
- 单文件：先选存入的文档文件夹，再选工作区，统一嵌入。
- 多文件：先判定存入模式 ——
  - 统一存入（unified）：统一选择文件夹 → 统一选择工作区 → 统一嵌入；
  - 逐个存入（per-file）：逐个选择文件夹 + 逐个选择工作区 → 最后统一嵌入作业。
- 文档文件夹能力：列出已有文件夹（GET /api/v1/documents）、新建文件夹（POST /api/v1/document/create-folder）、上传到指定文件夹（POST /api/v1/document/upload/:folderName，服务端将产物移动到 documents/<folder>/）。
- 新增参数：-Folder <name>（指定/自动创建文件夹）、-Mode <unified|per-file>（自动化指定存入模式，交互模式仍用菜单）。
- 交互模式菜单文案更新（统一存入 / 逐个存入），分配预览显示 文件 → [文件夹] 工作区。
- 新增 README.md（tools/），补齐 A6 目录结构基线。
- 测试基线更新：工作区 slug 适配（1→wsl）、A1 版本断言（V0.7→V0.9）、W1 正式文档锚点（WSL 知识库 2 份）；实机验证 3 场景（单文件+新文件夹 / 多文件统一 / 多文件逐个）全部通过。
- 回归结果：PASS=41 / FAIL=0 / SKIP=1（全面测试第 10 轮，冒烟先行通过）。
- 工程：改前备份 backup/v0.8-20260910，发布备份 backup/v0.9-20260910；本版本由豆包直接执行（未调用 opencode Agent）。

## v0.8 (2026-09-10)
- 修复 5 处工具缺陷：Confirm-Embedding 验证字段（location→docpath）、非交互无 slug exit=1、MaxFileSizeMB 整数截断（[int]→[double]）、非交互跳过自动 chat、判重字段（title→filename 前缀匹配）+ 非交互自动 skip。
- 修复 BAT 入口：shift 破坏 %0 导致 embed.ps1 路径失效（缓存 SCRIPT_DIR）；编码 UTF-8→GBK（cmd 兼容）。
- 新增 A1-A4 增强与 -Answer 自动化参数（详见 tools/README.md v0.8 段）。
- 测试 v1.4：X1/T1/T2/E5 增强、CaseFilter 生效；回归 PASS=41/FAIL=0/SKIP=1（第 9 轮）。
- 工程：git baseline commit + tag v0.8；apikey 移出跟踪；冒烟钩子 smoke-hook.ps1 + pre-commit 提示。

## v0.7 (2026-09-09)
- 初版功能：拖拽批量上传嵌入、工作区菜单、文件预校验、重复检测、嵌入轮询验证、可选 chat 测试、JSONL 日志、config.json 外部配置、语义化退出码。
- 测试 v1.0-v1.3 迭代：冒烟先行、负载控制（默认关闭 chat）、API 直传 Setup。