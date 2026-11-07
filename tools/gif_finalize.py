"""Final pick: size vs quality for the 38-frame ping-pong rebuild.

The original asset is dithered, which is what hides banding in the flat hat
gradient but also what makes LZW expensive. Re-quantising with dither=NONE is
much cheaper per frame, so fewer colours can buy back the 8 extra frames the
reconstructed sway period needs.

Decision is made on a zoomed crop of the flattest region (hat crown), because
global MAD cannot see banding.
"""

import os

import numpy as np
from PIL import Image, ImageDraw, ImageSequence

ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
VERIFY = r"D:\trime-build\tools\verify-ui"
SEQ = list(range(20)) + list(range(18, 0, -1))
CROP = (120, 30, 260, 120)   # flat hat crown -> banding detector


def load_rgb(path):
    return [np.asarray(f.convert("RGB"), np.float32)
            for f in ImageSequence.Iterator(Image.open(path))]


def build_palette(rgb_frames, colors, method):
    px = np.concatenate([f[::2, ::2].reshape(-1, 3) for f in rgb_frames], axis=0)
    px = np.clip(px + 0.5, 0, 255).astype(np.uint8)
    h = px.shape[0] // 256
    return Image.fromarray(px[:h * 256].reshape(h, 256, 3)).quantize(
        colors=colors, method=method, dither=Image.Dither.NONE)


def encode(rgb_frames, pal, path, dither, disposal=1):
    q = [Image.fromarray(np.clip(f + 0.5, 0, 255).astype(np.uint8))
         .quantize(palette=pal, dither=dither) for f in rgb_frames]
    q[0].save(path, format="GIF", save_all=True, append_images=q[1:],
              duration=100, loop=0, optimize=True, disposal=disposal)
    return os.path.getsize(path)


def band_energy(a, box):
    """Std of the second difference across the crop: flat regions must stay flat."""
    x0, y0, x1, y1 = box
    g = a[y0:y1, x0:x1].mean(axis=2)
    d2 = g[:-2, 1:-1] - 2 * g[1:-1, 1:-1] + g[2:, 1:-1]
    return float(np.abs(d2).mean())


def main():
    orig_bytes = os.path.getsize(ASSET)
    src = load_rgb(ASSET)
    ref = [src[i] for i in SEQ]

    MX = Image.Quantize.MAXCOVERAGE
    NONE = Image.Dither.NONE
    FS = Image.Dither.FLOYDSTEINBERG
    specs = [
        ("MC256", MX, 256, NONE),
        ("MX192", MX, 192, NONE),
        ("MX160", MX, 160, NONE),
        ("MX128", MX, 128, NONE),
        ("MX160d", MX, 160, FS),
        ("MX128d", MX, 128, FS),
    ]

    print(f"orig {orig_bytes} B   band-energy(orig)={band_energy(ref[5], CROP):.3f}\n")
    print(f"{'tag':>7s} {'bytes':>9s} {'vs orig':>9s} {'global':>7s} {'flower':>7s} {'band':>7s}")
    built = {}
    for tag, m, n, d in specs:
        pal = build_palette(ref, n, m)
        path = os.path.join(VERIFY, f"_pick_{tag}.gif")
        size = encode(ref, pal, path, d)
        dec = load_rgb(path)
        assert len(dec) == len(ref)
        glob = float(np.mean([np.abs(a - b).mean() for a, b in zip(dec, ref)]))
        fl = float(np.mean([np.abs(a[45:205, 135:285] - b[45:205, 135:285]).mean()
                            for a, b in zip(dec, ref)]))
        be = band_energy(dec[5], CROP)
        built[tag] = (path, size)
        print(f"{tag:>7s} {size:9d} {size - orig_bytes:+9d} {glob:7.3f} {fl:7.3f} {be:7.3f}")

    # ---- zoomed crop strip ------------------------------------------------
    order = ["orig"] + [t for t, _, _, _ in specs]
    tiles = []
    for t in order:
        a = ref[5] if t == "orig" else load_rgb(built[t][0])[5]
        tiles.append(a[CROP[1]:CROP[3], CROP[0]:CROP[2]].astype(np.uint8))
    row = np.concatenate(tiles, axis=1)
    s = 3
    row = np.asarray(Image.fromarray(row).resize((row.shape[1] * s, row.shape[0] * s), Image.NEAREST))
    w = CROP[2] - CROP[0]
    lab = Image.new("RGB", (row.shape[1], 24), (0, 0, 0))
    d = ImageDraw.Draw(lab)
    for i, t in enumerate(order):
        d.text((i * w * s + 6, 6), t, fill=(255, 255, 255))
    out = os.path.join(VERIFY, "_pick_compare.png")
    Image.fromarray(np.concatenate([np.asarray(lab), row], axis=0)).save(out)
    print(f"\nwrote {out}  order: {' | '.join(order)}")


if __name__ == "__main__":
    main()
