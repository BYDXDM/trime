"""Render diagnostic images of the asset's 30 frames so the motion can be seen."""

import numpy as np
from PIL import Image, ImageDraw

DST = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
OUT = r"D:\trime-build\tools\verify-ui"

im = Image.open(DST)
frames = []
for i in range(im.n_frames):
    im.seek(i)
    frames.append(im.convert("RGB").copy())
n = len(frames)
print(f"{n} frames {frames[0].size}")

# (a) all 30 in a labelled 6x5 grid
cell = 150
cols, rows = 6, 5
canvas = Image.new("RGB", (cols * cell, rows * cell), (0, 0, 0))
dr = ImageDraw.Draw(canvas)
for i, f in enumerate(frames):
    x, y = (i % cols) * cell, (i // cols) * cell
    canvas.paste(f.resize((cell, cell), Image.LANCZOS), (x, y))
    dr.rectangle([x, y, x + 30, y + 14], fill=(0, 0, 0))
    dr.text((x + 3, y + 2), f"{i}", fill=(255, 255, 0))
canvas.save(f"{OUT}\\_all30.png")
print(f"wrote {OUT}\\_all30.png")

# (b) seam neighbourhood at higher res: 27,28,29 | 0,1,2
seam = [27, 28, 29, 0, 1, 2]
c2 = 220
cv = Image.new("RGB", (c2 * 6, c2 + 20), (0, 0, 0))
d2 = ImageDraw.Draw(cv)
for k, i in enumerate(seam):
    cv.paste(frames[i].resize((c2, c2), Image.LANCZOS), (k * c2, 20))
    d2.rectangle([k * c2, 0, k * c2 + 60, 18], fill=(0, 0, 0))
    d2.text((k * c2 + 3, 3), f"f{i}", fill=(255, 255, 0))
cv.save(f"{OUT}\\_seam_before.png")
print(f"wrote {OUT}\\_seam_before.png")

# (c) difference heatmap across the seam: |f29 - f0| vs |f28 - f29|
a = np.asarray(frames[29], dtype=np.float32)
b = np.asarray(frames[0], dtype=np.float32)
c = np.asarray(frames[28], dtype=np.float32)
d = np.asarray(frames[1], dtype=np.float32)
for name, (x, y) in {
    "seam_29_0": (a, b),
    "interior_28_29": (c, a),
    "interior_0_1": (b, d),
}.items():
    dm = np.abs(x - y).mean(axis=2)
    print(f"  {name:16s} mean={dm.mean():7.3f} max={dm.max():7.1f} "
          f"p99={np.percentile(dm, 99):6.2f}")
    Image.fromarray(np.clip(dm * 4, 0, 255).astype(np.uint8)).resize((300, 300)).save(
        f"{OUT}\\_diff_{name}.png")
print(f"wrote diff heatmaps to {OUT}")
