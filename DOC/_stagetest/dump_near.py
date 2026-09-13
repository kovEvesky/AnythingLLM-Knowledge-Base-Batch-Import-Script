import re
import sys

d = open(sys.argv[1], encoding="utf-8").read()
for m in re.finditer(
    r'<node[^>]*text="([^"]{1,40})"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', d
):
    t, x1, y1, x2, y2 = m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5))
    if y2 >= 1400 or t in ("Share", "Just once", "Always", "AnythingLLM 批量导入"):
        print(f"{t!r} bounds=({x1},{y1})-({x2},{y2})")
