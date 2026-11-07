"""Structural audit of the shipped GIF, using Pillow's own parser.

The previous hand-rolled container parser drifted (reported 7 frames and
garbage rects), so container facts come from Pillow here.
"""

import os

import numpy as np
from PIL import Image, ImageSequence

ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
OLD = r"D:\trime-build\tools\verify-ui\_orig_preview.gif"
ORDER = list(range(20)) + list(range(18, 0, -1))


def describe(path, tag):
    im = Image.open(path)
    print(f"[{tag}] {path}")
    print(f"  bytes={os.path.getsize(path)} mode={im.mode} size={im.size} "
          f"n_frames={getattr(im, 'n_frames', 1)}")
    print(f"  info: loop={im.info.get('loop')} duration={im.info.get('duration')} "
          f"version={im.info.get('version')} background={im.info.get('background')}")
    pal = im.palette
    print(f"  global palette: mode={pal.mode} entries={len(pal.colors) if pal else None}")
    tiles, modes = [], []
    for i in range(getattr(im, "n_frames", 1)):
        im.seek(i)
        tiles.append(tuple(im.tile[0][1]) if im.tile else None)
        modes.append(im.mode)
    print(f"  per-frame modes = {sorted(set(modes))}")
    print(f"  frame 0 tile args = {tiles[0]}")
    print(f"  distinct tile arg tuples = {len(set(tiles))}")
    return im


def main():
    describe(ASSET, "installed")
    print()
    describe(OLD, "original")

    def rgb(path):
        return [np.asarray(f.convert("RGB"), np.float32)
                for f in ImageSequence.Iterator(Image.open(path))]

    new, old = rgb(ASSET), rgb(OLD)
    print(f"\ninstalled frames decoded = {len(new)}  original = {len(old)}")
    assert len(new) == 38 and len(old) == 30

    worst = max(float(np.abs(new[k] - old[i]).mean()) for k, i in enumerate(ORDER))
    print(f"max static MAD, each new frame vs the original frame it reuses = {worst:.3f}")

    for tag, frames in (("original", old), ("installed", new)):
        inner = [float(np.abs(frames[i + 1] - frames[i]).mean()) for i in range(len(frames) - 1)]
        seam = float(np.abs(frames[0] - frames[-1]).mean())
        med = float(np.median(inner))
        rank = sorted(inner + [seam]).index(seam) + 1
        print(f"{tag:>9s}: n={len(frames)} seam={seam:7.4f} median={med:7.4f} "
              f"max={max(inner):7.4f} ratio={seam / med:5.3f} seam rank={rank}/{len(inner) + 1}")


if __name__ == "__main__":
    main()
