"""Print colour runs along a row, so a stray bar/line can be identified by extent + colour.

usage: python _probe.py <png> <y> [quant=16]
"""
import os
import sys

exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "_pngload.py"), encoding="utf-8").read())

src = sys.argv[1]
y = int(sys.argv[2])
quant = int(sys.argv[3]) if len(sys.argv) > 3 else 16

w, h, nch, px = load_png(src)
stride = w * nch
row = px[y * stride:(y + 1) * stride]

def rgb(x):
    i = x * nch
    if nch >= 3:
        return row[i], row[i + 1], row[i + 2]
    return row[i], row[i], row[i]

def q(v):
    return (v // quant) * quant

runs = []
for x in range(w):
    c = tuple(q(v) for v in rgb(x))
    if runs and runs[-1][2] == c:
        runs[-1][1] = x
    else:
        runs.append([x, x, c])

print(f"{os.path.basename(src)} y={y}  {len(runs)} run(s), quant={quant}")
for x0, x1, c in runs:
    if x1 - x0 + 1 >= 6:
        print(f"  x {x0:4d}..{x1:4d}  len={x1 - x0 + 1:4d}  rgb={c}")
