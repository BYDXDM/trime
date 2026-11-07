"""Frame-grid proof of the installed GIF: all 38 frames, in playback order.

If any frame were synthesised or misordered it would show up here as a jump.
The sway should reach its extreme at the middle and return symmetrically.
"""

import os

import numpy as np
from PIL import Image, ImageDraw, ImageSequence

ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
OUT = r"D:\trime-build\tools\verify-ui\gif_loop_check_frames.png"
COLS = 10
TILE = 128


def main():
    frames = [np.asarray(f.convert("RGB")) for f in ImageSequence.Iterator(Image.open(ASSET))]
    n = len(frames)
    rows = (n + COLS - 1) // COLS
    grid = Image.new("RGB", (COLS * TILE, rows * (TILE + 16)), (16, 16, 16))
    d = ImageDraw.Draw(grid)
    for i, f in enumerate(frames):
        r, c = divmod(i, COLS)
        t = Image.fromarray(f).resize((TILE, TILE), Image.LANCZOS)
        x, y = c * TILE, r * (TILE + 16)
        grid.paste(t, (x, y + 16))
        d.text((x + 4, y + 3), f"#{i}", fill=(255, 255, 255))
    grid.save(OUT)
    print(f"wrote {OUT}  {grid.size[0]}x{grid.size[1]}  frames={n}")

    # symmetry check: a ping-pong is symmetric about its two turning points
    for a, b in ((0, n - 1), (1, n - 2), (9, n - 10), (19, 19)):
        if a <= b:
            mad = float(np.abs(frames[a].astype(np.float32) - frames[b].astype(np.float32)).mean())
            print(f"  MAD(frame {a}, frame {b}) = {mad:.4f}")


if __name__ == "__main__":
    main()
