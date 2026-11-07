import glob
import os
import sys

import numpy as np
from PIL import Image


def analyze(path, row=None):
    im = Image.open(path).convert("L")
    a = np.asarray(im)
    h, w = a.shape
    band = a[row[0] : row[1]] if row else a[h // 3 :]
    bg = np.bincount(band.ravel()).argmax()
    ink = (band < 128).sum(axis=0) > 0
    sep = ((band > bg + 3) & (band < 200)).sum(axis=0) > band.shape[0] * 0.5
    # runs of separator columns => cell boundaries
    bounds = []
    i = 0
    while i < w:
        if sep[i]:
            j = i
            while j < w and sep[j]:
                j += 1
            bounds.append((i, j))
            i = j
        else:
            i += 1
    cells = []
    prev = 0
    for s, e in bounds:
        cells.append((prev, s))
        prev = e
    cells.append((prev, w))
    out = []
    for s, e in cells:
        if e - s < 8:
            continue
        has_ink = ink[s:e].any()
        out.append((s, e, e - s, bool(has_ink)))
    return bg, bounds, out


for f in sorted(glob.glob(sys.argv[1])):
    try:
        bg, bounds, cells = analyze(f)
    except Exception as exc:  # noqa: BLE001
        print(f, "ERR", exc)
        continue
    blank = [c for c in cells if not c[3]]
    print(
        os.path.basename(f),
        "bg=",
        bg,
        "seps=",
        len(bounds),
        "cells=",
        len(cells),
        "blankcells=",
        blank,
    )
