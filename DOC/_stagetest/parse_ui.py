import re
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as f:
    d = f.read()
seen = []
for m in re.finditer(r'text="([^"]{2,80})"', d):
    t = m.group(1)
    if t not in seen:
        seen.append(t)
for t in seen:
    print(t)
