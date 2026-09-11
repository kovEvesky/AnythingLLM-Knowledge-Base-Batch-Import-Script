#!/usr/bin/env python3
"""删除 TargetScreen.kt 150 行遗留的 AnythingLLMTheme 闭合。"""
import io

P = "/mnt/d/WSL/object/AnythingLLM-Android/app/src/main/java/com/anythingllm/importer/ui/import/TargetScreen.kt"
lines = io.open(P, encoding="utf-8").read().splitlines()

# 定位: Button 闭合(147 '                }') 后是 Column(148)/Scaffold(149)/Theme 闭合(150)/if(151)
assert lines[149].strip() == "}" and lines[150].startswith("    if (state.showCreateFolder)"), lines[148:152]
target = None
for i in range(len(lines) - 1):
    if lines[i].strip() == "}" and lines[i + 1].startswith("    if (state.showCreateFolder)"):
        target = i
        break
assert target is not None, "not found"
del lines[target]
io.open(P, "w", encoding="utf-8", newline="").write("\n".join(lines) + "\n")
print("DELETED_LINE", target + 1)
