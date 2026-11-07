"""Work out *what* differs between D:\\preview.gif and the repo asset."""

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


src = load(SRC)
dst = load(DST, (192, 192))

print("--- GLOBAL STATS ---")
for name, a in (("src", src), ("dst", dst)):
    print(f"  {name}: shape={a.shape} mean_rgb={a.mean(axis=(0,1,2)).round(2)} "
          f"std={a.std():.2f} min={a.min():.0f} max={a.max():.0f}")

print("\n--- BEST MATCH src[i] vs dst[0], with variants ---")
d0 = dst[0]
variants = {
    "as-is": d0,
    "hflip": d0[:, ::-1],
    "vflip": d0[::-1, :],
    "both": d0[::-1, ::-1],
}
for vname, v in variants.items():
    d = np.abs(src - v).mean(axis=(1, 2, 3))
    print(f"  {vname:6s}: best src idx={int(np.argmin(d)):2d}  MAD={d.min():8.4f}")

# brightness/contrast-normalised
for vname, v in variants.items():
    a_ = src.mean(axis=(1, 2, 3), keepdims=True)
    b_ = v.mean(axis=(1, 2, 3), keepdims=True)
    d = np.abs((src - a_) - (v - b_)).mean(axis=(1, 2, 3))
    print(f"  {vname:6s} mean-centered: best={int(np.argmin(d)):2d} MAD={d.min():8.4f}")

print("\n--- PER-CHANNEL DIFFERENCE (src best frame vs dst[0]) ---")
d = np.abs(src - d0).mean(axis=(1, 2, 3))
b = int(np.argmin(d))
print(f"  best src frame {b}; per-channel |diff| = "
      f"{np.abs(src[b] - d0).mean(axis=(0, 1)).round(2)}")
print(f"  src[{b}] mean_rgb={src[b].mean(axis=(0,1)).round(2)}  "
      f"dst[0] mean_rgb={d0.mean(axis=(0,1)).round(2)}")

print("\n--- SELF-SIMILARITY: is the DST sequence even the same animation? ---")
dm = np.array([[np.abs(dst[i] - dst[j]).mean() for j in range(len(dst))] for i in range(len(dst))])
print(f"  dst self-distance: min off-diagonal={dm[~np.eye(len(dst), dtype=bool)].min():.4f}")
sm = np.array([[np.abs(src[i] - src[j]).mean() for j in range(len(src))] for i in range(len(src))])
print(f"  src self-distance: min off-diagonal={sm[~np.eye(len(src), dtype=bool)].min():.4f}")

print("\n--- STRUCTURE: where is the difference concentrated? ---")
diffmap = np.abs(src[b] - d0).mean(axis=2)
h, w = diffmap.shape
print(f"  quadrant mean |diff|: TL={diffmap[:h//2,:w//2].mean():.2f} TR={diffmap[:h//2,w//2:].mean():.2f} "
      f"BL={diffmap[h//2:,:w//2].mean():.2f} BR={diffmap[h//2:,w//2:].mean():.2f}")
print(f"  overall mean={diffmap.mean():.2f}  max={diffmap.max():.2f}")

# Save a comparison strip: src row 0,12,24,36,47 vs dst row 0,8,16,24,29
def strip(path):
    idx_s = [0, 12, 24, 36, 47]
    idx_d = [0, 8, 16, 24, 29]
    cell = 160
    canvas = Image.new("RGB", (cell * 5, cell * 2 + 24), (0, 0, 0))
    for k, i in enumerate(idx_s):
        canvas.paste(Image.fromarray(src[i].astype(np.uint8)).resize((cell, cell), Image.LANCZOS),
                     (k * cell, 0))
    for k, i in enumerate(idx_d):
        canvas.paste(Image.fromarray(dst[i].astype(np.uint8)).resize((cell, cell), Image.LANCZOS),
                     (k * cell, cell + 24))
    canvas.save(path)
    print(f"  wrote {path}  (top=src 0/12/24/36/47, bottom=dst 0/8/16/24/29)")


strip(r"D:\trime-build\tools\verify-ui\_cmp_strip.png")
