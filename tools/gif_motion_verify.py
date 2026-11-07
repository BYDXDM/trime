"""Verify the asset's motion model and score seamless-loop candidates.

1. Per-frame sub-pixel alignment (integer search + parabolic refine) -> cumulative drift.
2. Frame-recurrence check: does any frame repeat an earlier one?
3. Candidate loops (ping-pong variants) scored on seam MAD vs interior MAD.
"""

import numpy as np
from PIL import Image

DST = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"

im = Image.open(DST)
frames = []
for i in range(im.n_frames):
    im.seek(i)
    frames.append(im.convert("RGB").copy())
n = len(frames)
rgb = [np.asarray(f, np.float32) for f in frames]
gray = [np.asarray(f.convert("L").resize((256, 256), Image.LANCZOS), np.float32) for f in frames]
print(f"frames={n} size={frames[0].size}")

R, SR = 16, 10


def surf_of(a, b, r=SR):
    s = {}
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            aa = np.roll(np.roll(a, dy, 0), dx, 1)
            s[(dy, dx)] = float(np.abs(aa[R:-R, R:-R] - b[R:-R, R:-R]).mean())
    return s


def align(a, b):
    s = surf_of(a, b)
    (dy0, dx0), m0 = min(s.items(), key=lambda kv: kv[1])

    def par(axis):
        kp = (dy0 + (1 if axis == 0 else 0), dx0 + (1 if axis == 1 else 0))
        km = (dy0 - (1 if axis == 0 else 0), dx0 - (1 if axis == 1 else 0))
        if kp not in s or km not in s:
            return 0.0
        p, m = s[kp], s[km]
        den = p - 2 * m0 + m
        return 0.0 if den <= 1e-9 else float(np.clip(0.5 * (m - p) / den, -1, 1))

    return dy0 + par(0), dx0 + par(1), m0


print("\n--- per-frame alignment: shift(frame i) -> frame i+1 ---")
print(" i->i+1    dy      dx     MAD     cum_dy  cum_dx   (cum = shift bringing f_i onto f_0)")
cy = cx = 0.0
steps = []
for i in range(n - 1):
    dy, dx, m = align(gray[i], gray[i + 1])
    cy += dy
    cx += dx
    steps.append((dy, dx, m))
    print(f"  {i:2d}->{i+1:<2d}  {dy:+6.2f}  {dx:+6.2f}  {m:6.2f}   {cy:+7.2f} {cx:+7.2f}")
arr = np.array([(s[0], s[1]) for s in steps])
print(f"  per-step dy: mean {arr[:,0].mean():+.3f} std {arr[:,0].std():.3f} | "
      f"dx: mean {arr[:,1].mean():+.3f} std {arr[:,1].std():.3f}")
print(f"  total drift (content moved) dy={-cy:+.2f} dx={-cx:+.2f}")

print("\n--- frame recurrence: for each i, best-matching earlier frame j<=i-2 ---")
small = [np.asarray(f.convert("L").resize((128, 128), Image.LANCZOS), np.float32) for f in frames]
for i in range(2, n):
    ds = [(float(np.abs(small[i] - small[j]).mean()), j) for j in range(i - 1)]
    dmin, jmin = min(ds)
    dstep = float(np.abs(small[i] - small[i - 1]).mean())
    print(f"  f{i:2d}: best j={jmin:2d} MAD={dmin:6.2f} | step(i-1,i)={dstep:6.2f} | ratio={dmin/dstep:.3f}")

print("\n--- candidate loops ---")
CANDS = {
    "orig 0..29 (100ms)": (list(range(30)), 100),
    "pp full 0..29+28..1 (100ms)": (list(range(30)) + list(range(28, 0, -1)), 100),
    "pp half 0..19+18..1 (100ms)": (list(range(20)) + list(range(18, 0, -1)), 100),
    "pp half dec2 (200ms)": (list(range(0, 20, 2)) + list(range(17, 0, -2)), 200),
    "pp half dec2 (150ms)": (list(range(0, 20, 2)) + list(range(17, 0, -2)), 150),
    "pp full dec2 (130ms)": (list(range(0, 30, 2)) + list(range(27, 0, -2)), 130),
    "pp 0..24+23..1 (100ms)": (list(range(25)) + list(range(23, 0, -1)), 100),
    "drop f29 (100ms)": (list(range(29)), 100),
}
for name, (seq, dur) in CANDS.items():
    d = [float(np.abs(rgb[seq[k]] - rgb[seq[k + 1]]).mean()) for k in range(len(seq) - 1)]
    seam = float(np.abs(rgb[seq[-1]] - rgb[seq[0]]).mean())
    med = float(np.median(d))
    print(f"  {name:34s} n={len(seq):3d} {len(seq)*dur:5d}ms  "
          f"seam={seam:6.3f} med={med:6.3f} max={max(d):6.3f} ratio={seam/med:6.3f} "
          f"turnaround={max(d):.2f}")
