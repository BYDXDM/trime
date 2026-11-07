"""Inspect GIF container structure + render key frames to identify the motion."""

import numpy as np
from PIL import Image, ImageDraw

DST = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
OUT = r"D:\trime-build\tools\verify-ui"

im = Image.open(DST)
print("--- CONTAINER STRUCTURE ---")
print(f"  global palette entries : {len(im.getpalette()) // 3 if im.getpalette() else 0}")
print(f"  info keys              : {sorted(im.info.keys())}")
prev_tile = None
for i in range(im.n_frames):
    im.seek(i)
    p = im.getpalette()
    tile = im.tile[0][1] if im.tile else None
    extra = im.tile[0][3] if im.tile and len(im.tile[0]) > 3 else None
    print(f"  f{i:2d}: mode={im.mode} size={im.size} local_pal={len(p)//3 if p else 0} "
          f"disposal={im.disposal_method if hasattr(im,'disposal_method') else '?'} "
          f"tile_bbox={extra}")

print("\n--- KEY FRAMES AT HIGH RES (0, 9, 19, 29) ---")
im2 = Image.open(DST)
frames = []
for i in range(im2.n_frames):
    im2.seek(i)
    frames.append(im2.convert("RGB").copy())

sel = [0, 5, 9, 14, 19, 24, 29]
c = 240
cv = Image.new("RGB", (c * len(sel), c + 22), (0, 0, 0))
dr = ImageDraw.Draw(cv)
for k, i in enumerate(sel):
    cv.paste(frames[i].resize((c, c), Image.LANCZOS), (k * c, 22))
    dr.text((k * c + 4, 5), f"f{i}", fill=(255, 255, 0))
cv.save(f"{OUT}\\_keyframes.png")
print(f"  wrote {OUT}\\_keyframes.png")

# Track the hat's dark band as a feature proxy: column-wise darkness centroid
print("\n--- FEATURE TRACKING (dark hat-band centroid + image energy spread) ---")
for i in range(0, len(frames), 2):
    g = np.asarray(frames[i].convert("L"), dtype=np.float32)
    mask = g < 90                     # dark hat band / hair shadow
    if mask.sum() > 50:
        ys, xs = np.nonzero(mask)
        cy, cx = ys.mean(), xs.mean()
        spread = np.sqrt(((ys - cy) ** 2).mean() + ((xs - cx) ** 2).mean())
    else:
        cy = cx = spread = float("nan")
    print(f"  f{i:2d}: dark_px={int(mask.sum()):6d} centroid=({cx:6.1f},{cy:6.1f}) spread={spread:6.2f} "
          f"std={g.std():6.2f}")
