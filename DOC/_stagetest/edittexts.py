import re
import sys

d = open(sys.argv[1], encoding="utf-8").read()
for m in re.finditer(
    r'<node[^>]*class="android.widget.EditText"[^>]*>', d
):
    n = m.group(0)
    hint = re.search(r'hint="([^"]*)"', n)
    text = re.search(r'text="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if b:
        x = (int(b.group(1)) + int(b.group(3))) // 2
        y = (int(b.group(2)) + int(b.group(4))) // 2
        print(f"({x},{y}) hint={hint.group(1) if hint else ''!r} text={text.group(1) if text else ''!r}")
