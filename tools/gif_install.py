"""Build, install and verify the fixed keyboard background GIF.

Fix: the asset's motion is a sinusoidal sway whose turning points are frame 0
and frame 19, i.e. a full period of 38 frames at 100 ms. The shipped file has
only the first 30 frames, so it stops 8.5 px short of returning to the start
and the loop wraps with a hard snap -- that snap is the visible stutter.

The rebuilt file plays 0..19 then 18..1, which completes the period using only
real frames (no interpolation) and lands the loop seam at the size of an
ordinary frame-to-frame step.

Palette: MAXCOVERAGE 160, dither NONE. MEDIANCUT starves the small saturated
flower region; dithering costs ~1.2 MB here because the art is already
dithered, so a flat re-quantise with dither=NONE pays for the 8 extra frames
and still comes out smaller than the original.
"""

import os
import shutil

import numpy as np
from PIL import Image, ImageDraw, ImageSequence

ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
VERIFY = r"D:\trime-build\tools\verify-ui"
BACKUP = os.path.join(VERIFY, "_orig_preview.gif")
SEQ = list(range(20)) + list(range(18, 0, -1))
COLORS = 160
DUR = 100
BOX = (130, 40, 410, 300)


def load_rgb(path):
    return [np.asarray(f.convert("RGB"), np.float32)
            for f in ImageSequence.Iterator(Image.open(path))]


def stats(frames, tag):
    inner = [float(np.abs(frames[i + 1] - frames[i]).mean()) for i in range(len(frames) - 1)]
    seam = float(np.abs(frames[0] - frames[-1]).mean())
    med = float(np.median(inner))
    print(f"{tag:<10s} n={len(frames):3d} seam={seam:7.4f} median={med:7.4f} "
          f"max={max(inner):7.4f} ratio={seam / med:5.3f}")
    return seam, med


def boundary_strip(path, idxs, out, label):
    """Labelled horizontal strip of the given frame indices."""
    frames = load_rgb(path)
    x0, y0, x1, y1 = BOX
    tiles = [frames[i][y0:y1, x0:x1].astype(np.uint8) for i in idxs]
    row = np.concatenate(tiles, axis=1)
    s = 2
    row = np.asarray(Image.fromarray(row).resize(
        (row.shape[1] * s, row.shape[0] * s), Image.NEAREST))
    lab = Image.new("RGB", (row.shape[1], 26), (0, 0, 0))
    d = ImageDraw.Draw(lab)
    for k, i in enumerate(idxs):
        d.text((k * (x1 - x0) * s + 8, 8), f"{label}f{i}", fill=(255, 255, 255))
    Image.fromarray(np.concatenate([np.asarray(lab), row], axis=0)).save(out)
    return out


def main():
    assert os.path.isfile(ASSET), ASSET
    orig_bytes = os.path.getsize(ASSET)
    src = load_rgb(ASSET)
    ref = [src[i] for i in SEQ]
    print(f"original: {len(src)} frames, {orig_bytes} bytes")
    stats(src, "orig")

    px = np.concatenate([f[::2, ::2].reshape(-1, 3) for f in ref], axis=0)
    px = np.clip(px + 0.5, 0, 255).astype(np.uint8)
    h = px.shape[0] // 256
    pal = Image.fromarray(px[:h * 256].reshape(h, 256, 3)).quantize(
        colors=COLORS, method=Image.Quantize.MAXCOVERAGE, dither=Image.Dither.NONE)

    q = [Image.fromarray(np.clip(f + 0.5, 0, 255).astype(np.uint8))
         .quantize(palette=pal, dither=Image.Dither.NONE) for f in ref]

    staging = os.path.join(VERIFY, "_install_candidate.gif")
    q[0].save(staging, format="GIF", save_all=True, append_images=q[1:],
              duration=DUR, loop=0, optimize=True, disposal=1)
    new_bytes = os.path.getsize(staging)

    # verify the staged file before it replaces the asset
    dec = load_rgb(staging)
    assert len(dec) == len(ref), f"frame count {len(dec)} != {len(ref)}"
    assert all(im.size == (512, 512) for im in
               [f for f in ImageSequence.Iterator(Image.open(staging))]), "size changed"
    glob = float(np.mean([np.abs(a - b).mean() for a, b in zip(dec, ref)]))
    print(f"staged: {len(dec)} frames, {new_bytes} bytes "
          f"({new_bytes - orig_bytes:+d}), static MAD vs original = {glob:.3f}")
    stats(dec, "new")

    if not os.path.isfile(BACKUP):
        shutil.copy2(ASSET, BACKUP)
        print(f"backed up original -> {BACKUP}")
    shutil.copy2(staging, ASSET)
    print(f"installed -> {ASSET} ({os.path.getsize(ASSET)} bytes)")

    installed = Image.open(ASSET)
    print(f"installed check: mode={installed.mode} size={installed.size} "
          f"frames={getattr(installed, 'n_frames', 1)} loop={installed.info.get('loop')} "
          f"duration={installed.info.get('duration')}")

    # boundary strips: old wrap point vs new wrap point
    boundary_strip(BACKUP, [27, 28, 29, 0, 1, 2],
                   os.path.join(VERIFY, "gif_loop_check_old.png"), "old")
    boundary_strip(ASSET, [35, 36, 37, 0, 1, 2],
                   os.path.join(VERIFY, "gif_loop_check_new.png"), "new")

    old = load_rgb(BACKUP)
    new = load_rgb(ASSET)
    tiles = []
    for frames, idxs in ((old, [27, 28, 29, 0, 1, 2]), (new, [35, 36, 37, 0, 1, 2])):
        x0, y0, x1, y1 = BOX
        tiles.append(np.concatenate([frames[i][y0:y1, x0:x1].astype(np.uint8) for i in idxs], axis=1))
    sep = np.full((tiles[0].shape[0], 8, 3), 255, np.uint8)
    stack = np.concatenate([tiles[0], sep, tiles[1]], axis=1)
    out = os.path.join(VERIFY, "gif_loop_check.png")
    Image.fromarray(stack).save(out)
    print(f"wrote {out} (top row = old wrap, bottom row = new wrap)")


if __name__ == "__main__":
    main()
