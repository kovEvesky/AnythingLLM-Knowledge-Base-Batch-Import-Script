# 编码修复脚本

## fix-bat-encoding.ps1

将 `.bat` / `.cmd` 文件转换为 **UTF-8 无 BOM + CRLF**。

```powershell
.\fix-bat-encoding.ps1 -Path "..\tools\EmbedIntoWorkspace.bat"
```

**为什么 BAT 要无 BOM**：BAT 首行 `chcp 65001` 后 cmd 按 UTF-8 解析，带 BOM 可能导致解析异常。

## fix-ps1-encoding.ps1

将 `.ps1` 文件转换为 **UTF-8 带 BOM + CRLF**。

```powershell
.\fix-ps1-encoding.ps1 -Path "..\tools\embed.ps1"
```

**为什么 PS1 要带 BOM**：PS 5.1 对无 BOM 的 .ps1 按系统默认编码（ANSI）解析，中文注释和字符串会乱码。带 BOM 时 PS 5.1 会正确识别为 UTF-8。

## 注意事项

- 两个脚本目标单一，互不影响
- 使用 `[IO.File]::WriteAllText` + `UTF8Encoding` 精确控制 BOM
- 修复后可运行十六进制验证确认编码正确
