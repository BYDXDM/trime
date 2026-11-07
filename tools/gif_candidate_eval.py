"""Evaluate loop candidates x encoding settings for the background GIF.

Loop candidate = ping-pong (forward then backward) sampled with a uniform temporal
step, so the motion is a continuous oscillation and the loop seam equals the
neighbouring step by construction.

Encoders compared:
  plain : every frame full, global palette
  delta : unchanged pixels written as the transparent index (disposal=1 keeps the
          previous frame underneath), which lets LZW collapse the static area.
"""

import os
import numpy as np
from PIL import Image

DST = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
OUT = r"D:\trime-build\tools\verify-ui"

im = Image.open(DST)
frames = []
for i in range(im.n_frames):
    im.seek(i)
    frames.append(im.convert("RGB").copy())
N = len(frames)
print(f"source asset: {N} frames {frames[0].size} {os.path.getsize(DST)} bytes")


def pingpong(turn, step):
    fwd = list(range(0, turn + 1, step))
    if fwd[-1] != turn:
        fwd.append(turn)
    back = fwd[-2:0:-1]
    return fwd + back


def build_palette(idx, colors):
    mont = Image.new("RGB", (128 * len(idx), 128))
    for k, i in enumerate(idx):
        mont.paste(frames[i].resize((128, 128), Image.LANCZOS), (k * 128, 0))
    return mont.quantize(colors=colors, method=Image.Quantize.MEDIANCUT)


def enc_plain(seq, pal, dither):
    return [frames[i].quantize(palette=pal, dither=dither) for i in seq]


def enc_delta(seq, pal, dither, colors):
    q = [np.asarray(frames[i].quantize(palette=pal, dither=dither), np.uint8).astype(np.int16) + 1
         for i in seq]
    out = []
    for k, a in enumerate(q):
        d = a.astype(np.uint8).copy()
        if k:
            d[a == q[k - 1]] = 0
        img = Image.fromarray(d, "P")
        p = pal.getpalette()
        img.putpalette(p + [0] * (768 - len(p)))
        out.append(img)
    return out


def save(fr, dur, path, transparency=None):
    kw = dict(save_all=True, append_images=fr[1:], duration=dur, loop=0,
              disposal=1, optimize=True)
    if transparency is not None:
        kw["transparency"] = transparency
    fr[0].save(path, **kw)
    return os.path.getsize(path)


def quality(path, seq):
    r = Image.open(path)
    assert r.n_frames == len(seq), f"frame count changed: {r.n_frames} != {len(seq)}"
    ds = []
    for k in range(r.n_frames):
        r.seek(k)
        a = np.asarray(r.convert("RGB"), np.float32)
        b = np.asarray(frames[seq[k]], np.float32)
        ds.append(float(np.abs(a - b).mean()))
    return float(np.mean(ds)), r.n_frames, r.info.get("duration")


def seam_stats(seq, path=None):
    """MAD over the loop sequence, measured on the decoded file when given."""
    if path:
        r = Image.open(path)
        arr = []
        for k in range(r.n_frames):
            r.seek(k)
            arr.append(np.asarray(r.convert("RGB"), np.float32))
    else:
        arr = [np.asarray(frames[i], np.float32) for i in seq]
    steps = [float(np.abs(arr[k] - arr[k + 1]).mean()) for k in range(len(arr) - 1)]
    seam = float(np.abs(arr[-1] - arr[0]).mean())
    return seam, float(np.median(steps)), max(steps), seam / float(np.median(steps))


print("\n=== loop candidate geometry (no encoding) ===")
CANDS = {
    "pp turn20 step2": pingpong(20, 2),
    "pp turn20 step1": pingpong(20, 1),
    "pp turn18 step2": pingpong(18, 2),
    "pp turn16 step2": pingpong(16, 2),
    "pp turn24 step3": pingpong(24, 3),
}
for name, seq in CANDS.items():
    s, med, mx, ratio = seam_stats(seq)
    print(f"  {name:18s} n={len(seq):3d} {len(seq)*190:5d}ms  seam={s:6.3f} med={med:6.3f} "
          f"max={mx:6.3f} ratio={ratio:6.3f}")

SEQ = pingpong(20, 2)
print(f"\nchosen loop: turn=20 step=2 -> {len(SEQ)} frames {SEQ}")

print("\n=== encoding matrix (20 frames, 190ms) ===")
rows = []
for colors in (256, 192, 128, 96, 64):
    pal = build_palette(SEQ, colors)
    for dname, dth in (("FS", Image.Dither.FLOYDSTEINBERG), ("none", Image.Dither.NONE)):
        for mode in ("plain", "delta"):
            fr = enc_plain(SEQ, pal, dth) if mode == "plain" else enc_delta(SEQ, pal, dth, colors)
            p = os.path.join(OUT, "_m.gif")
            sz = save(fr, 190, p, transparency=0 if mode == "delta" else None)
            mad, nf, dur = quality(p, SEQ)
            s, med, mx, ratio = seam_stats(SEQ, p)
            rows.append((sz, colors, dname, mode, mad, ratio))
            print(f"  colors={colors:3d} dither={dname:4s} {mode:5s} -> {sz:9d}B "
                  f"rtMAD={mad:6.3f} seam={s:6.3f} med={med:6.3f} ratio={ratio:6.3f}")
            os.remove(p)

print("\n=== best by size among rtMAD<=2.5 and ratio<=1.0 ===")
ok = [r for r in rows if r[4] <= 2.5 and r[5] <= 1.0]
ok.sort()
for sz, colors, dname, mode, mad, ratio in ok[:6]:
    print(f"  {sz:9d}B colors={colors:3d} dither={dname:4s} {mode:5s} rtMAD={mad:6.3f} ratio={ratio:6.3f}")
