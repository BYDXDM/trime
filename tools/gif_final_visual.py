"""Side-by-side visual check of the two size-reducing candidates vs the original.

Judged at 2x on the most detail-dense region (flower + eye) so that any
quantisation damage is visible before committing the asset.
"""

import os

import numpy as np
from PIL import Image, ImageDraw, ImageSequence

VERIFY = r"D:\trime-build\tools\verify-ui"
ORIG = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
CROP = (130, 40, 410, 300)   # flower + eye
SCALE = 2
PANELS = [("orig", ORIG), ("MX160", os.path.join(VERIFY, "_pick_MX160.gif")),
          ("MX128", os.path.join(VERIFY, "_pick_MX128.gif"))]


def frame5(path):
    it = ImageSequence.Iterator(Image.open(path))
    for i, f in enumerate(it):
        if i == 5:
            return np.asarray(f.convert("RGB"), np.float32)
    raise SystemExit(f"{path}: fewer than 6 frames")


def main():
    x0, y0, x1, y1 = CROP
    tiles = []
    for name, path in PANELS:
        a = frame5(path)[y0:y1, x0:x1].astype(np.uint8)
        t = np.asarray(Image.fromarray(a).resize(
            ((x1 - x0) * SCALE, (y1 - y0) * SCALE), Image.NEAREST))
        tiles.append(t)
    row = np.concatenate(tiles, axis=1)
    lab = Image.new("RGB", (row.shape[1], 26), (0, 0, 0))
    d = ImageDraw.Draw(lab)
    for i, (name, _) in enumerate(PANELS):
        d.text((i * (x1 - x0) * SCALE + 8, 8), name, fill=(255, 255, 255))
    out = os.path.join(VERIFY, "_final_visual.png")
    Image.fromarray(np.concatenate([np.asarray(lab), row], axis=0)).save(out)
    print(f"wrote {out}  {row.shape[1]}x{row.shape[0] + 26}")


if __name__ == "__main__":
    main()
