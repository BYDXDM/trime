"""Decide the source<->asset relationship AND measure Pillow's GIF size budget.

A. Direct visual comparison: src[0] upscaled to 512 next to dst[0].
B. 2-D search (scale + offset) for the best src->dst alignment.
C. Size budget: how small can 30 frames / 58 frames of 512x512 be encoded?
"""

import os
import numpy as np
from PIL import Image

SRC = r"D:\preview.gif"
DST = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
OUT = r"D:\trime-build\tools\verify-ui"


def load(path):
    im = Image.open(path)
    out = []
    for i in range(im.n_frames):
        im.seek(i)
        out.append(im.convert("RGB").copy())
    return out


src = load(SRC)
dst = load(DST)
print(f"src {len(src)} frames {src[0].size} | dst {len(dst)} frames {dst[0].size}")

print("\n--- A. side-by-side: src[0] upscaled -> 512 | dst[0] | abs-diff ---")
s0 = src[0].resize((512, 512), Image.LANCZOS)
d0 = dst[0]
diff = np.abs(np.asarray(s0, np.float32) - np.asarray(d0, np.float32)).mean(axis=2)
canvas = Image.new("RGB", (512 * 3 + 20, 532), (0, 0, 0))
canvas.paste(s0, (0, 20))
canvas.paste(d0, (522, 20))
canvas.paste(Image.fromarray(np.clip(diff * 3, 0, 255).astype(np.uint8)).convert("RGB"), (1044, 20))
canvas.save(f"{OUT}\\_src_vs_dst.png")
print(f"  wrote {OUT}\\_src_vs_dst.png   (mean |diff| = {diff.mean():.2f})")

print("\n--- B. 2-D alignment search (scale k, offset ox,oy) src[0] -> dst[0] ---")
D = np.asarray(d0.resize((160, 160), Image.LANCZOS), np.float32)
best = None
for k in np.arange(0.80, 1.61, 0.05):
    big = src[0].resize((int(round(192 * k)), int(round(192 * k))), Image.LANCZOS)
    big = np.asarray(big, np.float32)
    hh, ww = big.shape[:2]
    if hh < 160 or ww < 160:
        continue
    step = 3
    for oy in range(0, hh - 160 + 1, step):
        for ox in range(0, ww - 160 + 1, step):
            c = big[oy:oy + 160, ox:ox + 160]
            m = float(np.abs(c - D).mean())
            if best is None or m < best[0]:
                best = (m, k, ox, oy)
print(f"  best: MAD={best[0]:.3f} at scale k={best[1]:.2f} offset=({best[2]},{best[3]})")
print(f"  (identical content should reach MAD < 5; dst interior frame step is ~5.7 at 512)")

print("\n--- C. SIZE BUDGET ---")
seq = dst


def encode(frames, colors, duration, dither=Image.Dither.FLOYDSTEINBERG, opt=True, path=None):
    sample = 96
    mont = Image.new("RGB", (sample * len(frames), sample))
    for i, f in enumerate(frames):
        mont.paste(f.resize((sample, sample), Image.LANCZOS), (i * sample, 0))
    pal = mont.quantize(colors=colors, method=Image.Quantize.MEDIANCUT)
    ps = [f.quantize(palette=pal, dither=dither) for f in frames]
    buf = path or os.path.join(OUT, "_budget.gif")
    ps[0].save(buf, save_all=True, append_images=ps[1:], duration=duration,
               loop=0, optimize=opt, disposal=1)
    return os.path.getsize(buf)


boomerang = dst + dst[-2:0:-1]
print(f"  original file                      : {os.path.getsize(DST):9d} bytes")
for n_colors in (256, 128, 64):
    b30 = encode(seq, n_colors, 100)
    b58 = encode(boomerang, n_colors, 70)
    print(f"  colors={n_colors:3d}  n=30 -> {b30:9d}   n=58 -> {b58:9d}")
