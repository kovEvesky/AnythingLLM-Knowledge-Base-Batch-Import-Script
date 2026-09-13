import re
import sys

path = sys.argv[1]
kind = sys.argv[2]  # e.g. class="android.widget.EditText" or text keyword
d = open(path, encoding="utf-8").read()
if kind.startswith("class="):
    cls = kind[len("class="):]
    pat = re.compile(r'class="' + re.escape(cls) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
elif kind.startswith("desc="):
    cls = "content-desc"
    pat = re.compile(r'content-desc="([^"]*' + re.escape(kind[len("desc="):]) + r'[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
else:
    pat = re.compile(r'text="([^"]*' + re.escape(kind) + r'[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
for m in pat.finditer(d):
    g = m.groups()
    if len(g) == 4:
        x = (int(g[0]) + int(g[2])) // 2
        y = (int(g[1]) + int(g[3])) // 2
        print(f"{cls or kind} center=({x},{y})")
    else:
        x = (int(g[1]) + int(g[3])) // 2
        y = (int(g[2]) + int(g[4])) // 2
        print(f"{g[0]} | center=({x},{y})")
