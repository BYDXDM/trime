"""Reconstruct the 1-D motion trajectory of the 30-frame asset and test whether
the second half is a clean time-reversal of the first half.

Prints:
  - per-frame best matching frame (excluding +-1 neighbours)
  - MAD of each frame against f0 and f1
  - cumulative sub-pixel alignment position of every frame in f0's frame of reference
"""

import sys

import numpy as np
from PIL import Image, ImageSequence

ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
WORK = 256


def load(path):
    im = Image.open(path)
    frames = [f.convert("RGB").resize((WORK, WORK), Image.LANCZOS) for f in ImageSequence.Iterator(im)]
    return np.stack([np.asarray(f, dtype=np.float32) for f in frames])


def mad(a, b):
    return float(np.abs(a - b).mean())


def align(src, dst, rad=8):
    """Return (dx, dy) that maps src onto dst, integer search + parabolic refine."""
    best = None
    h, w = src.shape[:2]
    for dy in range(-rad, rad + 1):
        for dx in range(-rad, rad + 1):
            ys0, ys1 = max(0, dy), min(h, h + dy)
            xs0, xs1 = max(0, dx), min(w, w + dx)
            yd0, yd1 = max(0, -dy), min(h, h - dy)
            xd0, xd1 = max(0, -dx), min(w, w - dx)
            a = src[ys0:ys1, xs0:xs1]
            b = dst[yd0:yd1, xd0:xd1]
            v = float(np.abs(a - b).mean())
            if best is None or v < best[0]:
                best = (v, dx, dy)
    v0, dx0, dy0 = best
    return dx0, dy0, v0


def main():
    f = load(ASSET)
    n = len(f)
    print(f"asset: {n} frames, work size {WORK}px")
    print(f"size bytes: {__import__('os').path.getsize(ASSET)}")

    # full MAD matrix
    M = np.zeros((n, n), dtype=np.float32)
    for i in range(n):
        for j in range(n):
            M[i, j] = mad(f[i], f[j])

    print("\n=== best match per frame (|i-j|>1 excluded) ===")
    print(" i   bestj  mad_best   mad(i->0)  mad(i->1)  mad(i->2)  mad(i->j2)")
    for i in range(n):
        row = M[i].copy()
        for d in (-1, 0, 1):
            if 0 <= i + d < n:
                row[i + d] = 1e9
        j = int(row.argmin())
        print(f"{i:3d}  {j:5d}  {M[i, j]:8.4f}   {M[i, 0]:8.4f}  {M[i, 1]:8.4f}  {M[i, 2]:8.4f}")

    print("\n=== cumulative alignment position (in f0 coords) ===")
    # position of frame i expressed as displacement of f_i relative to f_0
    # chain: p[i+1] = p[i] + align(f_i, f_{i+1}) ; align returns shift that maps src onto dst
    cum = np.zeros((n, 2), dtype=np.float64)
    step = np.zeros((n - 1, 2), dtype=np.float64)
    for i in range(n - 1):
        dx, dy, v = align(f[i], f[i + 1])
        step[i] = (dx, dy)
        cum[i + 1] = cum[i] + (dx, dy)
    print(" i    cum_dx   cum_dy    step_dx  step_dy")
    for i in range(n):
        if i == 0:
            print(f"{i:3d}  {cum[i, 0]:8.3f} {cum[i, 1]:8.3f}")
        else:
            print(f"{i:3d}  {cum[i, 0]:8.3f} {cum[i, 1]:8.3f}   {step[i - 1, 0]:8.3f} {step[i - 1, 1]:8.3f}")

    print("\n=== is the trajectory monotone in arc length? ===")
    # project cumulative position onto its principal axis to get 1-D parameter t_i
    pts = cum - cum.mean(axis=0)
    u, s, vt = np.linalg.svd(pts, full_matrices=False)
    axis = vt[0]
    t = pts @ axis
    t = t - t[0]
    print(" i        t_i     dt")
    prev = None
    for i in range(n):
        d = "" if prev is None else f"{t[i] - prev:8.4f}"
        print(f"{i:3d}  {t[i]:9.4f}   {d}")
        prev = t[i]

    print("\n=== mirror test: MAD(f_i, f_j) for candidate time-reversal pairs ===")
    print("  i   j    MAD")
    for i, j in [(20, 18), (21, 17), (22, 16), (23, 15), (24, 15), (24, 14), (25, 14), (25, 13),
                 (26, 13), (26, 12), (27, 12), (27, 11), (28, 11), (28, 10), (29, 10), (29, 9),
                 (29, 8), (29, 7), (29, 6), (29, 5), (29, 4), (29, 3), (29, 2), (29, 1), (29, 0)]:
        print(f"{i:3d} {j:3d}  {M[i, j]:8.4f}")


if __name__ == "__main__":
    sys.exit(main())
