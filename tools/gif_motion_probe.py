"""Characterise the motion structure of the 512x512 asset to design a seam fix.

Prints the full self-distance matrix (as a text heatmap), k-step differences, and
evaluates candidate loop constructions (drop-k, ping-pong, cross-dissolve).
"""

import numpy as np
from PIL import Image

DST = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"


def load(path):
    im = Image.open(path)
    out = []
    for i in range(im.n_frames):
        im.seek(i)
        out.append(np.asarray(im.convert("RGB"), dtype=np.float32))
    return np.stack(out)


f = load(DST)
n = len(f)
print(f"asset: {n} frames, {f.shape[2]}x{f.shape[1]}")

# Downsample for fast distance computation; structure is preserved.
small = np.stack([np.asarray(Image.fromarray(x.astype(np.uint8)).resize((128, 128),
                                                                       Image.LANCZOS),
                                dtype=np.float32) for x in f])


def mad(a, b):
    return float(np.abs(a - b).mean())


D = np.array([[mad(small[i], small[j]) for j in range(n)] for i in range(n)])

print("\n--- SELF-DISTANCE MATRIX (rows/cols = frame index; '.'<3 ':'<6 '+'<9 '*'<12 '#'>=12) ---")
hdr = "     " + "".join(f"{j % 10}" for j in range(n))
print(hdr)
for i in range(n):
    row = "".join(
        "." if D[i, j] < 3 else ":" if D[i, j] < 6 else "+" if D[i, j] < 9
        else "*" if D[i, j] < 12 else "#" for j in range(n))
    print(f"  {i:2d} {row}")

print("\n--- K-STEP DIFFERENCES ---")
for k in range(1, 6):
    d = np.array([mad(small[i], small[(i + k) % n]) for i in range(n)])
    print(f"  k={k}: mean={d.mean():7.3f} min={d.min():7.3f} max={d.max():7.3f}")

print("\n--- 1-STEP SERIES vs SEAM ---")
d1 = np.array([mad(small[i], small[(i + 1) % n]) for i in range(n)])
print(f"  1-step interior median = {np.median(d1[:-1]):.3f}")
print(f"  seam (29->0)           = {d1[-1]:.3f}")
print(f"  seam / median          = {d1[-1] / np.median(d1[:-1]):.3f}x")
for k in range(1, 5):
    dk = mad(small[n - 1], small[(n - 1 + k) % n])
    print(f"  D(29, 29+{k}) = {dk:7.3f}   ratio to median 1-step = {dk / np.median(d1[:-1]):.2f}x")

print("\n--- DROPPING FRAMES AT THE SEAM ---")
for name, idx in (
    ("baseline (keep all 30)", list(range(n))),
    ("drop frame 0", list(range(1, n))),
    ("drop frame 29", list(range(0, n - 1))),
    ("drop frames 0 and 29", list(range(1, n - 1))),
):
    sub = small[idx]
    m = len(idx)
    s = mad(sub[-1], sub[0])
    interior = np.array([mad(sub[i], sub[(i + 1) % m]) for i in range(m - 1)])
    print(f"  {name:24s}: n={m:3d} seam={s:7.3f} med={np.median(interior):6.3f} "
          f"ratio={s / np.median(interior):6.3f}x")

print("\n--- PING-PONG (forward then reversed) ---")
for drop_ends in (0, 1):
    seq = list(range(n - 1 - drop_ends)) + list(range(n - 2 - drop_ends, 0, -1))
    sub = small[seq]
    m = len(sub)
    interior = np.array([mad(sub[i], sub[i + 1]) for i in range(m - 1)])
    s = mad(sub[-1], sub[0])
    print(f"  ping-pong drop_ends={drop_ends}: n={m:3d} seam={s:7.3f} "
          f"med={np.median(interior):6.3f} ratio={s / np.median(interior):6.3f}x")

print("\n--- WHERE IS THE MOTION 'PHASE'? (correlation of each frame with frame 0) ---")
ref = small[0]
for i in range(n):
    a = small[i].ravel() - small[i].mean()
    b = ref.ravel() - ref.mean()
    c = float((a * b).sum() / np.sqrt((a * a).sum() * (b * b).sum()))
    bar = "#" * int(round((c + 1) * 20))
    print(f"  [{i:2d}] corr(f{i}, f0) = {c:+.4f}  {bar}")
