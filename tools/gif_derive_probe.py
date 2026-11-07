"""Diagnose how the 512x512/30-frame repo asset was derived from D:\\preview.gif.

Checks:
  1. Periodicity of the source (is it N identical sub-loops?).
  2. Best-matching source frame index for each repo frame -> reveals the actual
     resampling map (uniform time mapping vs. uneven frame dropping).
  3. Contact sheets for visual inspection.

Usage: python tools/gif_derive_probe.py
"""

import numpy as np
from PIL import Image

SRC = r"D:\preview.gif"
DST = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"


def load(path, size=None):
    im = Image.open(path)
    out = []
    for i in range(im.n_frames):
        im.seek(i)
        f = im.convert("RGB")
        if size:
            f = f.resize(size, Image.LANCZOS)
        out.append(np.asarray(f, dtype=np.float32))
    return np.stack(out)


src = load(SRC)                       # 48 x 192 x 192 x 3
dst = load(DST, (192, 192))           # 30 x 192 x 192 x 3

print(f"src frames={len(src)}  dst frames={len(dst)}")

print("\n--- 1. PERIODICITY OF SOURCE (MAD between frame i and frame i+lag) ---")
for lag in (8, 12, 16, 24):
    d = [np.abs(src[i] - src[i + lag]).mean() for i in range(len(src) - lag)]
    print(f"  lag={lag:3d}  mean={np.mean(d):7.4f}  min={np.min(d):7.4f}  max={np.max(d):7.4f}")
    if lag in (16, 24):
        best = int(np.argmin(d))
        print(f"          best-aligned i={best}  MAD={d[best]:.4f}  "
              f"(interior median for comparison = {np.median([np.abs(src[i]-src[i+1]).mean() for i in range(47)]):.4f})")

print("\n--- 2. REPO FRAME -> BEST SOURCE FRAME (reveals resampling map) ---")
print("  idx | best src idx | MAD      | 2nd best | expected if uniform (i*48/30)")
best_idx = []
for i in range(len(dst)):
    d = np.abs(src - dst[i]).mean(axis=(1, 2, 3))
    order = np.argsort(d)
    best_idx.append(int(order[0]))
    exp = i * 48.0 / 30.0
    print(f"  {i:3d} | {order[0]:12d} | {d[order[0]]:8.4f} | {order[1]:8d} | {exp:6.2f}   "
          f"({'MATCH' if abs(order[0] - exp) < 0.6 else 'off by %+.2f' % (order[0] - exp)})")

best_idx = np.array(best_idx)
steps = np.diff(best_idx)
print(f"\n  best-src-index sequence : {best_idx.tolist()}")
print(f"  consecutive steps       : {steps.tolist()}")
print(f"  step histogram          : {dict(zip(*np.unique(steps, return_counts=True)))}")
print(f"  wrap step (last->first) : {best_idx[0] + 48 - best_idx[-1]}")

print("\n--- 3. SOURCE FRAME SIMILARITY ACROSS THE SEAM ---")
mad = lambda a, b: np.abs(a - b).mean()
print(f"  src[47] vs src[0]  MAD = {mad(src[47], src[0]):.4f}")
print(f"  src[46] vs src[47] MAD = {mad(src[46], src[47]):.4f}")
print(f"  src[0]  vs src[1]  MAD = {mad(src[0], src[1]):.4f}")
print(f"  src[47] vs src[1]  MAD = {mad(src[47], src[1]):.4f}   <- if small, frame 0 is a stray dupe")
print(f"  src[46] vs src[0]  MAD = {mad(src[46], src[0]):.4f}   <- seam if frame 47 were dropped")

print("\n--- 4. CONTACT SHEET ---")
def sheet(frames, cols, path, cell=96):
    n = len(frames)
    rows = (n + cols - 1) // cols
    canvas = Image.new("RGB", (cols * cell, rows * cell), (20, 20, 20))
    for i, f in enumerate(frames):
        im = Image.fromarray(f.astype(np.uint8)).resize((cell, cell), Image.LANCZOS)
        canvas.paste(im, ((i % cols) * cell, (i // cols) * cell))
    canvas.save(path)
    print(f"  wrote {path}  ({cols} cols x {rows} rows, frame 0 top-left, reading order)")


sheet(src, 8, r"D:\trime-build\tools\verify-ui\_src48_sheet.png")
sheet(dst, 8, r"D:\trime-build\tools\verify-ui\_dst30_sheet.png")
