"""Decide whether the 512x512 repo asset is geometrically the same content as
D:\\preview.gif (i.e. a plain upscale) or a different crop/zoom.

Tests:
  A. Normalised cross-correlation (invariant to brightness/contrast) between each
     source frame and dst[0]  -> high NCC means "same content, different tone".
  B. Mean-centred MAD -> removes global brightness offset.
  C. Brute-force scale search: centre-crop src to 192/k then resize to 192, find
     the (k, src frame) that best explains dst[0].
  D. Sharpness (high-frequency energy) of the native 512 asset vs the 192 source,
     which tells us whether the 512 was upscaled from the 192.
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


src = load(SRC)                    # 48 x 192 x 192 x 3
dst = load(DST, (192, 192))        # 30 x 192 x 192 x 3
dst_native = load(DST)             # 30 x 512 x 512 x 3


def ncc(a, b):
    a = a.ravel().astype(np.float64)
    b = b.ravel().astype(np.float64)
    a = a - a.mean()
    b = b - b.mean()
    denom = np.sqrt((a * a).sum() * (b * b).sum())
    return float((a * b).sum() / denom) if denom else 0.0


print("--- A. NCC of every source frame against dst[0] ---")
nccs = np.array([ncc(src[i], dst[0]) for i in range(len(src))])
o = np.argsort(nccs)[::-1]
for r in range(5):
    print(f"  rank{r+1}: src[{o[r]:2d}]  NCC={nccs[o[r]]:.4f}")
print(f"  NCC of src interior neighbours (0 vs 1) = {ncc(src[0], src[1]):.4f}")
print(f"  NCC of dst interior neighbours (0 vs 1) = {ncc(dst[0], dst[1]):.4f}")

print("\n--- B. Mean-centred MAD, src[best] vs dst[0] ---")
b = int(o[0])
a_ = src[b] - src[b].mean()
d_ = dst[0] - dst[0].mean()
print(f"  raw MAD          = {np.abs(src[b] - dst[0]).mean():.4f}")
print(f"  mean-centred MAD = {np.abs(a_ - d_).mean():.4f}")

print("\n--- C. SCALE SEARCH (which zoom of which source frame explains dst[0]) ---")


def crop_scale(img, k, out=96):
    """Take the centre 1/k of img and resize to out x out."""
    h, w = img.shape[:2]
    ch, cw = int(round(h / k)), int(round(w / k))
    y0, x0 = (h - ch) // 2, (w - cw) // 2
    sub = img[y0:y0 + ch, x0:x0 + cw]
    return np.asarray(Image.fromarray(sub.astype(np.uint8)).resize((out, out), Image.LANCZOS),
                      dtype=np.float32)


target = np.asarray(Image.fromarray(dst[0].astype(np.uint8)).resize((96, 96), Image.LANCZOS),
                    dtype=np.float32)
srcs96 = [np.asarray(Image.fromarray(s.astype(np.uint8)).resize((192, 192), Image.LANCZOS),
                     dtype=np.float32) for s in src]

best = None
for k in np.arange(0.60, 2.61, 0.05):
    for bi in range(len(src)):
        c = crop_scale(srcs96[bi], k, 96)
        m = float(np.abs(c - target).mean())
        if best is None or m < best[0]:
            best = (m, k, bi)
print(f"  coarse best: MAD={best[0]:.4f} at scale k={best[1]:.2f}, src[{best[2]}]")
bk = best[1]
for k in np.arange(bk - 0.05, bk + 0.051, 0.01):
    for bi in range(len(src)):
        c = crop_scale(srcs96[bi], k, 96)
        m = float(np.abs(c - target).mean())
        if m < best[0]:
            best = (m, k, bi)
print(f"  fine   best: MAD={best[0]:.4f} at scale k={best[1]:.3f}, src[{best[2]}]")
print(f"  (for reference: identical-content resample should give MAD well under 5; "
      f"dst interior frame-to-frame MAD is ~5.7)")

print("\n--- D. SHARPNESS (mean |Laplacian| per pixel; higher = more high-freq detail) ---")


def sharpness(a):
    g = a.mean(axis=2) if a.ndim == 3 else a
    lap = (4 * g[1:-1, 1:-1] - g[:-2, 1:-1] - g[2:, 1:-1] - g[1:-1, :-2] - g[1:-1, 2:])
    return float(np.abs(lap).mean())


s_src = np.mean([sharpness(f) for f in src])
s_dstn = np.mean([sharpness(f) for f in dst_native])
s_dst192 = np.mean([sharpness(f) for f in dst])
# what an upscale of the 192 source to 512 would look like
up = [np.asarray(Image.fromarray(f.astype(np.uint8)).resize((512, 512), Image.LANCZOS),
                 dtype=np.float32) for f in src]
s_up = np.mean([sharpness(f) for f in up])
print(f"  src native 192           : {s_src:8.3f}")
print(f"  asset native 512         : {s_dstn:8.3f}")
print(f"  asset downscaled to 192  : {s_dst192:8.3f}")
print(f"  src upscaled to 512      : {s_up:8.3f}   <- if asset were an upscale it would look like this")
