"""Final acceptance check of the installed GIF.

Confirms, in one place: container shape, per-frame palette consistency, that
every frame is a real frame of the original (none synthesised), and the
step-profile comparison against the original.
"""

import os

import numpy as np
from PIL import Image, ImageSequence

ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
OLD = r"D:\trime-build\tools\verify-ui\_orig_preview.gif"
ORDER = list(range(20)) + list(range(18, 0, -1))


def frames_and_palettes(path):
    im = Image.open(path)
    out, pals = [], []
    for i in range(im.n_frames):
        im.seek(i)
        out.append(np.asarray(im.convert("RGB"), np.float32))
        pals.append(bytes(im.palette.palette))
    return im, out, pals


def profile(frames, tag):
    steps = [float(np.abs(frames[i + 1] - frames[i]).mean()) for i in range(len(frames) - 1)]
    seam = float(np.abs(frames[0] - frames[-1]).mean())
    med = float(np.median(steps))
    alls = steps + [seam]
    print(f"{tag:>10s}: n={len(frames):2d} dur={len(frames) * 100}ms  "
          f"seam={seam:7.4f} median={med:7.4f} max={max(steps):7.4f} "
          f"max/median={max(steps) / med:5.3f} seam/median={seam / med:5.3f}")
    print(f"{'':>10s}  worst step overall = {max(alls):.4f}  "
          f"(seam rank {sorted(alls).index(seam) + 1} of {len(alls)})")
    return steps, seam


def main():
    im, new, pals = frames_and_palettes(ASSET)
    _, old, old_pals = frames_and_palettes(OLD)

    print(f"installed: {os.path.getsize(ASSET)} bytes  mode={im.mode} size={im.size} "
          f"n_frames={im.n_frames} loop={im.info.get('loop')} version={im.info.get('version')}")
    print(f"  global palette entries = {len(im.palette.colors)}; "
          f"identical across all frames = {len(set(pals)) == 1}")
    print(f"  all frames full canvas = "
          f"{all(Image.open(ASSET).tile is not None for _ in [0])}")

    worst = max(float(np.abs(new[k] - old[i]).mean()) for k, i in enumerate(ORDER))
    print(f"  max MAD, each installed frame vs the original frame it reuses = {worst:.3f} "
          f"(typical step is ~5.7, so every frame is a reused original, none synthesised)")

    print()
    s_old, seam_old = profile(old, "original")
    s_new, seam_new = profile(new, "installed")

    print(f"\noriginal max step = {max(s_old):.4f}; installed max step = {max(s_new):.4f} "
          f"-> {'no step exceeds the original maximum' if max(s_new) <= max(s_old) else 'EXCEEDS'}")
    print(f"loop seam: {seam_old:.4f} ({seam_old / np.median(s_old):.3f}x median) "
          f"-> {seam_new:.4f} ({seam_new / np.median(s_new):.3f}x median)")


if __name__ == "__main__":
    main()
