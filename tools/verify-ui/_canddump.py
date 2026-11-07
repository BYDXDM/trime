import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "uf.xml"
s = open(path, encoding="utf-8", errors="replace").read()
for m in re.finditer(r"<node[^>]*>", s):
    t = m.group(0)
    if "candidate" in t.lower() or "AutoScale" in t:
        print(t[:400])
