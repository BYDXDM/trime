"""Sweep loop-closing candidates x palette sizes for the keyboard background GIF.

Measured baseline: the 30-frame asset is an open arc of a ~38-frame sinusoidal
sway.  Velocity is ~0 at f0 and at f19 (the two turning points), so the natural
period is 38 frames; the file stops at f29 while still 8.5 px from home, so the
wrap snaps back and reads as a hitch (seam MAD 15.69 = 2.76x the median step).

Fix: ping-pong the outbound half (0..turn, turn-1..1).  That reuses real frames
only -- nothing is interpolated -- and reconstructs the missing return leg,
because the sway is mirror-symmetric about the turn.

This script encodes every candidate, then measures the DECODED bytes: size,
round-trip MAD against the source frames, and the loop-seam ratio.
"""

import io
import os

import numpy as np
from PIL import Image, ImageSequence

ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
VERIFY = r"D:\trime-build\tools\verify-ui"


def load_rgb(path):
    im = Image.open(path)
    return [f.convert("RGB") for f in ImageSequence.Iterator(im)]


def seq_pp(turn, step):
    """Forward leg then mirrored return leg; wraps back to the first frame."""
    return list(range(0, turn + 1, step)) + list(range(turn - step, 0, -step))


def build_palette(frames, colors, sample=256):
    """One global palette shared by every frame (keeps a single color table)."""
    smalls = [f.resize((sample, sample), Image.LANCZOS) for f in frames]
    mont = Image.new("RGB", (sample, sample * len(smalls)))
    for i, f in enumerate(smalls):
        mont.paste(f, (0, i * sample))
    return mont.quantize(colors=colors, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)


def encode(frames, pal, duration, optimize=True):
    q = [f.quantize(palette=pal, dither=Image.Dither.NONE) for f in frames]
    buf = io.BytesIO()
    q[0].save(
        buf,
        format="GIF",
        save_all=True,
        append_images=q[1:],
        duration=duration,
        loop=0,
        optimize=optimize,
        disposal=1,
    )
    return buf.getvalue()


def decode(data):
    im = Image.open(io.BytesIO(data))
    return [f.convert("RGB") for f in ImageSequence.Iterator(im)]


def mad_matrix(frames):
    arr = np.stack([np.asarray(f, dtype=np.float32).reshape(-1) for f in frames])
    n = len(arr)
    M = np.zeros((n, n), dtype=np.float32)
    for i in range(n):
        M[i] = np.abs(arr - arr[i]).mean(axis=1)
    return M


def seam_stats(M):
    n = len(M)
    inner = [M[i, i + 1] for i in range(n - 1)]
    med = float(np.median(inner))
    mx = float(np.max(inner))
    seam = float(M[n - 1, 0])
    return seam, med, mx, (seam / med if med else float("inf"))


def main():
    src = load_rgb(ASSET)
    n0 = len(src)
    print(f"asset: {n0} frames {src[0].size}  {os.path.getsize(ASSET)} bytes")
    Mb = mad_matrix(src)
    s, med, mx, r = seam_stats(Mb)
    print(f"BASELINE (native 512 MAD): seam={s:.4f} med={med:.4f} max={mx:.4f} ratio={r:.3f}\n")

    cands = [
        ("pp19_s1", seq_pp(19, 1), 100),
        ("pp19_s2", seq_pp(19, 2), 200),
        ("pp20_s2", seq_pp(20, 2), 181),
    ]
    for name, seq, dur in cands:
        print(f"{name}: n={len(seq)} turn={seq.index(max(seq[:len(seq) // 2 + 1]))} first={seq[:4]} last={seq[-4:]}")

    print(f"\n{'cand':10s} {'col':>4s} {'opt':>4s} {'n':>3s} {'dur':>5s} {'total':>6s} "
          f"{'bytes':>9s} {'rtMAD':>7s} {'seam':>8s} {'med':>8s} {'max':>8s} {'ratio':>6s}")

    results = []
    for name, seq, dur in cands:
        frames = [src[i] for i in seq]
        for colors in (256, 192, 128):
            pal = build_palette(frames, colors)
            data = encode(frames, pal, dur)
            dec = decode(data)
            if len(dec) != len(frames):
                print(f"{name} col={colors}: FRAME COUNT MISMATCH dec={len(dec)} exp={len(frames)}")
                continue
            rt = float(np.mean([np.abs(np.asarray(a, np.float32) - np.asarray(b, np.float32)).mean()
                                for a, b in zip(dec, frames)]))
            M = mad_matrix(dec)
            s, med, mx, r = seam_stats(M)
            total = len(dec) * dur
            print(f"{name:10s} {colors:4d} {'Y':>4s} {len(dec):3d} {dur:5d} {total:6d} "
                  f"{len(data):9d} {rt:7.3f} {s:8.4f} {med:8.4f} {mx:8.4f} {r:6.3f}")
            results.append((name, colors, len(dec), dur, total, len(data), rt, s, med, mx, r, data))

    # optimize off, best candidate
    name, seq, dur = cands[1]
    frames = [src[i] for i in seq]
    pal = build_palette(frames, 256)
    data = encode(frames, pal, dur, optimize=False)
    dec = decode(data)
    M = mad_matrix(dec)
    s, med, mx, r = seam_stats(M)
    print(f"{name:10s} {256:4d} {'N':>4s} {len(dec):3d} {dur:5d} {len(dec) * dur:6d} "
          f"{len(data):9d} {'-':>7s} {s:8.4f} {med:8.4f} {mx:8.4f} {r:6.3f}")

    best = min(results, key=lambda t: t[5])
    print(f"\nsmallest: {best[0]} colors={best[1]} {best[5]} bytes (n={best[2]}, {best[4]}ms)")


if __name__ == "__main__":
    main()
