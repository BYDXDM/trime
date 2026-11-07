"""Quantify adjacent-frame differences in an animated GIF, including the loop seam.

Usage:
    python tools/gif_loop_analyze.py <file.gif> [<file2.gif> ...]

For each file prints: frame count, per-frame duration, total duration, size on
disk, and the full adjacent-frame difference series (frame i -> frame i+1, with
the final entry being the loop seam last -> first). Differences are reported as
mean-absolute-difference per pixel per channel (MAD) on the 0..255 scale.
"""

import sys
from pathlib import Path

import numpy as np
from PIL import Image


def load_frames(path):
    """Return (frames float32 [n,h,w,3], durations list[int], info dict)."""
    im = Image.open(path)
    durations = []
    frames = []
    for i in range(getattr(im, "n_frames", 1)):
        im.seek(i)
        durations.append(im.info.get("duration", 0))
        frames.append(np.asarray(im.convert("RGB"), dtype=np.float32))
    return np.stack(frames), durations, dict(im.info)


def diff_series(frames):
    """MAD between frame i and frame (i+1) % n. Last entry is the loop seam."""
    n = len(frames)
    out = np.empty(n, dtype=np.float64)
    for i in range(n):
        j = (i + 1) % n
        out[i] = np.abs(frames[i] - frames[j]).mean()
    return out


def rms_series(frames):
    n = len(frames)
    out = np.empty(n, dtype=np.float64)
    for i in range(n):
        j = (i + 1) % n
        out[i] = np.sqrt(((frames[i] - frames[j]) ** 2).mean())
    return out


def report(path):
    path = Path(path)
    frames, durations, info = load_frames(path)
    n = len(frames)
    h, w = frames.shape[1:3]
    mad = diff_series(frames)
    rms = rms_series(frames)
    total = sum(durations)

    print("=" * 78)
    print(f"FILE      : {path}")
    print(f"SIZE      : {path.stat().st_size} bytes")
    print(f"GEOMETRY  : {w}x{h}   mode=P  frames={n}")
    print(f"LOOP      : {info.get('loop')!r}")
    print(f"DURATIONS : {durations}")
    print(f"UNIQUE DUR: {sorted(set(durations))}")
    print(f"TOTAL DUR : {total} ms")

    interior = mad[:-1]
    seam = mad[-1]
    med = float(np.median(interior))
    print()
    print("ADJACENT-FRAME MAD SERIES (i -> i+1; last entry = loop seam n-1 -> 0)")
    for i in range(n):
        j = (i + 1) % n
        tag = "  <-- LOOP SEAM" if i == n - 1 else ""
        bar = "#" * int(round(mad[i] * 2))
        print(f"  [{i:3d} -> {j:3d}]  MAD={mad[i]:8.4f}  RMS={rms[i]:8.4f}  {bar}{tag}")

    print()
    print("STATISTICS (interior pairs only, seam excluded)")
    print(f"  interior median MAD : {med:.4f}")
    print(f"  interior mean   MAD : {interior.mean():.4f}")
    print(f"  interior min    MAD : {interior.min():.4f}")
    print(f"  interior max    MAD : {interior.max():.4f}")
    print(f"  interior std    MAD : {interior.std():.4f}")
    print(f"  interior max/med    : {interior.max() / med:.3f}x")
    print(f"  interior CV (std/mean): {interior.std() / interior.mean():.3f}")
    print()
    print("LOOP SEAM")
    print(f"  seam MAD            : {seam:.4f}")
    print(f"  seam / interior med : {seam / med:.3f}x")
    print(f"  seam / interior mean: {seam / interior.mean():.3f}x")
    print(f"  seam rank           : {int((mad > seam).sum()) + 1} of {n} (1 = largest)")
    zeros = int((mad == 0).sum())
    print(f"  duplicate pairs     : {zeros} (MAD == 0)")
    return {
        "path": path,
        "n": n,
        "mad": mad,
        "durations": durations,
        "seam": seam,
        "median": med,
    }


if __name__ == "__main__":
    args = sys.argv[1:] or [r"D:\preview.gif"]
    for a in args:
        report(a)
