
import re, io, sys
p = r"D:\ai\工作\trime-build\app\src\main\assets\shared\panels.yaml"
src = open(p, encoding="utf-8").read()
lines = src.split("\n")
FUNC = ("SymbolsPageUp", "SymbolsPageDown", "Keyboard_number", "BackSpace")
panel = None
changed = 0
out = []
for ln in lines:
    m = re.match(r"^([a-z_0-9]+):\s*$", ln)
    if m:
        panel = m.group(1)
    if (panel and panel != "number_panel" and "scrollable" not in panel
            and ln.lstrip().startswith("- {")):
        if any(("click: " + f) in ln for f in FUNC) and "slide_fixed" not in ln:
            new = ln.replace("width:", "slide_fixed: true, width:")
            out.append(new)
            changed += 1
            continue
    out.append(ln)
open(p, "w", encoding="utf-8", newline="\n").write("\n".join(out))
print("changed lines:", changed)
