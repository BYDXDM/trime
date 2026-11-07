"""Sub-pixel trajectory of the 30-frame asset via phase correlation, plus a
direct test of the 'palindrome / mirror-axis' hypothesis and geometry-only
scoring of candidate loop sequences.

Goal: decide WHERE the motion turns around and WHICH extra frames would close
the loop without a visible hitch.
"""

import numpy as np
from PIL import Image, ImageSequence

ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
FULL = 512
MADPX = 256


def load(path, size):
    im = Image.open(path)
    out = []
    for f in ImageSequence.Iterator(im):
        g = f.convert("L").resize((size, size), Image.LANCZOS)
        out.append(np.asarray(g, dtype=np.float32))
    return np.stack(out)


def subpix_shift(a, b):
    """Content displacement from a to b: b(x) ~= a(x - d). Returns (dx, dy)."""
    h, w = a.shape
    win = np.outer(np.hanning(h), np.hanning(w)).astype(np.float32)
    A = np.fft.fft2((a - a.mean()) * win)
    B = np.fft.fft2((b - b.mean()) * win)
    R = A * np.conj(B)
    R /= np.abs(R) + 1e-9
    r = np.real(np.fft.ifft2(R))
    idx = np.unravel_index(int(np.argmax(r)), r.shape)
    py, px = idx
    peak = r[py, px]

    def refine(c, l, rr):
        denom = (l - 2 * c + rr)
        if abs(denom) < 1e-12:
            return 0.0
        d = 0.5 * (l - rr) / denom
        return float(np.clip(d, -0.5, 0.5))

    dy = refine(peak, r[(py - 1) % h, px], r[(py + 1) % h, px])
    dx = refine(peak, r[py, (px - 1) % w], r[py, (px + 1) % w])
    sy = py + dy
    sx = px + dx
    if sy > h / 2:
        sy -= h
    if sx > w / 2:
        sx -= w
    return sx, sy


def mad(a, b):
    return float(np.abs(a - b).mean())


def score(seq, M, dur):
    n = len(seq)
    seam = M[seq[-1], seq[0]]
    inner = [M[seq[i], seq[i + 1]] for i in range(n - 1)]
    med = float(np.median(inner))
    mx = float(np.max(inner))
    return dict(n=n, ms=n * dur, seam=seam, med=med, mx=mx, ratio=seam / med if med else float("inf"))


def main():
    gray = load(ASSET, FULL)
    small = load(ASSET, MADPX)
    n = len(gray)
    print(f"frames={n}  full={FULL}  madpx={MADPX}")

    print("\n=== sub-pixel per-frame displacement (phase correlation, 512px) ===")
    steps = []
    for i in range(n - 1):
        dx, dy = subpix_shift(gray[i], gray[i + 1])
        steps.append((dx, dy))
    cum = np.cumsum(np.array(steps), axis=0)
    print(" i     step_dx   step_dy   cum_dx    cum_dy")
    print(f"{0:3d}                        {0.0:8.3f}  {0.0:8.3f}")
    for i, (dx, dy) in enumerate(steps):
        print(f"{i + 1:3d}   {dx:8.3f}  {dy:8.3f}  {cum[i, 0]:8.3f}  {cum[i, 1]:8.3f}")

    dxs = np.array([s[0] for s in steps])
    print(f"\nstep_dx: mean={dxs.mean():.4f} std={dxs.std():.4f} min={dxs.min():.3f} max={dxs.max():.3f}")
    # where does the sign flip?
    sign = np.sign(dxs)
    flips = [i for i in range(1, len(sign)) if sign[i] != sign[i - 1] and sign[i] != 0]
    print(f"sign flips of step_dx at frame index {flips} (i means between f{i} and f{i+1})")

    print("\n=== mirror-axis scan: sum_k MAD(a-k, a+k) ===")
    M = np.zeros((n, n), dtype=np.float32)
    for i in range(n):
        for j in range(n):
            M[i, j] = mad(small[i], small[j])
    rows = []
    for a2 in range(4, 2 * n - 4):  # axis = a2/2, so pairs (a2-k, ...) -> use half-integers
        tot, cnt = 0.0, 0
        for k in range(1, 12):
            i, j = a2 - k, a2 + k
            if 0 <= i < n and 0 <= j < n:
                tot += M[i, j]
                cnt += 1
        if cnt >= 6:
            rows.append((tot / cnt, a2 / 2.0, cnt))
    rows.sort()
    for v, axis, cnt in rows[:8]:
        print(f"  axis={axis:5.1f}  meanMAD={v:8.4f}  pairs={cnt}")

    print("\n=== targeted pair MAD (256px) ===")
    pairs = [(0, 1), (0, 2), (0, 3), (0, 4), (0, 5), (0, 6), (0, 7), (0, 8), (0, 9), (0, 10),
             (29, 0), (29, 1), (29, 2), (29, 3), (29, 4), (29, 5), (29, 6), (29, 7), (29, 8),
             (29, 9), (29, 10), (29, 11), (29, 12), (28, 11), (27, 12), (20, 19), (19, 18),
             (18, 17), (17, 16), (16, 15), (15, 14), (14, 13), (13, 12), (12, 11), (11, 10),
             (10, 9), (9, 8), (8, 7), (7, 6), (6, 5), (5, 4), (4, 3), (3, 2), (2, 1), (1, 0)]
    for i, j in pairs:
        print(f"  M[{i:2d},{j:2d}] = {M[i, j]:8.4f}")

    print("\n=== candidate loop sequences (geometry only) ===")
    cands = {
        "orig 0..29": list(range(30)),
        "pp_full 0..29,28..1": list(range(30)) + list(range(28, 0, -1)),
        "pp_19 0..19,18..1": list(range(20)) + list(range(18, 0, -1)),
        "pp_19_s2": list(range(0, 19, 2)) + list(range(17, 0, -2)),
        "pp_20_s2": list(range(0, 21, 2)) + list(range(19, 0, -2)),
        "pp_24_s2": list(range(0, 25, 2)) + list(range(23, 0, -2)),
        "pp_15_s2": list(range(0, 16, 2)) + list(range(13, 0, -2)),
        "ext 0..29,9..6": list(range(30)) + [9, 8, 7, 6],
        "ext 0..29,9..4": list(range(30)) + [9, 8, 7, 6, 5, 4],
        "ext 0..29,9..2": list(range(30)) + [9, 8, 7, 6, 5, 4, 3, 2],
        "ext 0..29,10..4": list(range(30)) + [10, 9, 8, 7, 6, 5, 4],
        "ext 0..29,10..2": list(range(30)) + [10, 9, 8, 7, 6, 5, 4, 3, 2],
        "ext 0..29,11..5": list(range(30)) + [11, 10, 9, 8, 7, 6, 5],
        "ext 0..29,12..6": list(range(30)) + [12, 11, 10, 9, 8, 7, 6],
    }
    print(f"{'candidate':24s} {'n':>3s} {'ms@100':>7s} {'seam':>8s} {'med':>8s} {'max':>8s} {'ratio':>7s}")
    for name, seq in cands.items():
        s = score(seq, M, 100)
        print(f"{name:24s} {s['n']:3d} {s['ms']:7d} {s['seam']:8.4f} {s['med']:8.4f} {s['mx']:8.4f} {s['ratio']:7.3f}")


if __name__ == "__main__":
    main()
