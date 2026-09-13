import re
import sys

d = open(sys.argv[1], encoding="utf-8").read()
keys = sys.argv[2:]
for m in re.finditer(
    r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', d
):
    t, x1, y1, x2, y2 = m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5))
    if any(k in t for k in keys):
        print(f"{t!r} ({x1},{y1})-({x2},{y2})")
