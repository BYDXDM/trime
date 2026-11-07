"""Decide the exact loop reconstruction for the swaying keyboard background.

The motion is a sinusoid. Its turning points are near frame 0 and near frame 19,
so the true period is either 38 or 39 frames. That single frame decides whether
the shipped file can keep the artist's real return-pass frames (f20..f29) or has
to discard them.

Settled empirically: for each late frame k, find which early frame it matches
best. A period-P motion sampled uniformly satisfies f(k) == f(P - k), so the
winning P is the one whose predicted partner is consistently the nearest match.

Candidate loops are then scored on their full step sequence -- every step must
be an ordinary frame-to-frame step, with the wrap step no worse than the rest.
"""

import os

import numpy as np
from PIL import Image, ImageSequence

ASSET = r"D:\trime-build\tools\verify-ui\_orig_preview.gif"  # pristine 30-frame original
VERIFY = r"D:\trime-build\tools\verify-ui"


def load_rgb(path):
    return [np.asarray(f.convert("RGB"), np.float32)
            for f in ImageSequence.Iterator(Image.open(path))]


def mad(a, b):
    return float(np.abs(a - b).mean())


def main():
    src = load_rgb(ASSET)
    n = len(src)
    assert n == 30, n

    D = np.zeros((n, n))
    for i in range(n):
        for j in range(n):
            D[i, j] = mad(src[i], src[j])

    print("late frame -> best early partner (expect f(k) ~ f(P-k))")
    for k in range(20, n):
        order = sorted((j for j in range(n) if j != k), key=lambda j: D[k, j])
        top = ", ".join(f"f{j}({D[k, j]:.2f})" for j in order[:3])
        p38, p39 = 38 - k, 39 - k
        print(f"  f{k:2d}: {top:34s} | P=38 -> f{p38:2d} ({D[k, p38]:5.2f})   "
              f"P=39 -> f{p39:2d} ({D[k, p39]:5.2f})")

    print()
    for P in (38, 39):
        vals = [D[k, P - k] for k in range(20, n) if 0 <= P - k < n]
        print(f"period {P}: mean MAD to predicted partner = {np.mean(vals):.3f}")

    # ---- candidate loops ---------------------------------------------------
    cands = {
        "A_38_pp19": list(range(20)) + list(range(18, 0, -1)),
        "B_39_pp19": list(range(20)) + list(range(19, 0, -1)),
        "C_39_real": list(range(30)) + list(range(9, 0, -1)),
        "C2_39_real": list(range(30)) + list(range(10, 1, -1)),
        "D_38_pp20": list(range(21)) + list(range(19, 1, -1)),
    }
    base_max = max(mad(src[i + 1], src[i]) for i in range(n - 1))
    base_med = float(np.median([mad(src[i + 1], src[i]) for i in range(n - 1)]))
    print(f"\noriginal interior: median={base_med:.4f} max={base_max:.4f}\n")

    print(f"{'cand':>12s} {'n':>3s} {'ms':>5s} {'seam':>7s} {'med':>7s} {'max':>7s} "
          f"{'ratio':>6s} {'worst step @':>26s}")
    for tag, seq in cands.items():
        f = [src[i] for i in seq]
        steps = [mad(f[i + 1], f[i]) for i in range(len(f) - 1)]
        seam = mad(f[0], f[-1])
        med = float(np.median(steps))
        w = int(np.argmax(steps))
        print(f"{tag:>12s} {len(f):3d} {len(f) * 100:5d} {seam:7.4f} {med:7.4f} "
              f"{max(steps):7.4f} {seam / med:6.3f} "
              f"{f'{seq[w]}->{seq[w + 1]} ({max(steps):.2f})':>26s}")

    # ---- per-step detail of the most faithful candidate --------------------
    for tag in ("C_39_real", "A_38_pp19"):
        seq = cands[tag]
        f = [src[i] for i in seq]
        steps = [mad(f[i + 1], f[i]) for i in range(len(f) - 1)]
        seam = mad(f[0], f[-1])
        alls = steps + [seam]
        worst = max(alls)
        print(f"\n{tag}: steps (seam last), original max was {base_max:.4f}")
        print("  " + " ".join(f"{s:.2f}" for s in steps) + f" | seam {seam:.2f}")
        print(f"  any step above original max? "
              f"{[f'{seq[i]}->{seq[i+1]}' for i, s in enumerate(steps) if s > base_max + 0.01] or 'none'}")
        print(f"  worst step = {worst:.3f} ({worst / base_max:.2f}x original max)")


if __name__ == "__main__":
    main()
