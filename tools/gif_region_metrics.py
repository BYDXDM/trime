"""Region-aware quality metrics for the ping-pong GIF candidates.

The purple flower is the most saturated, smallest-area feature in the artwork,
so a global MSE-minimising quantiser (MEDIANCUT) starves it.  Measure error
separately in the flower box and the eye box, plus the tail of the per-pixel
error distribution, then render 1:1 comparisons of the leading candidates.
"""

import io
import os

import numpy as np
from PIL import Image, ImageSequence

ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
VERIFY = r"D:\trime-build\tools\verify-ui"
SEQ = list(range(20)) + list(range(18, 0, -1))
DUR = 100
FLOWER = (135, 45, 285, 205)   # x0, y0, x1, y1
EYE = (300, 230, 400, 292)


def build_palette(frames, colors, method):
    tiles = [np.asarray(f)[::2, ::2].reshape(-1, 3) for f in frames]
    px = np.concatenate(tiles, axis=0)
    h = px.shape[0] // 256
    mont = Image.fromarray(px[: h * 256].reshape(h, 256, 3))
    return mont.quantize(colors=colors, method=method, dither=Image.Dither.NONE)


def encode(frames, pal, duration=DUR):
    q = [f.quantize(palette=pal, dither=Image.Dither.NONE) for f in frames]
    buf = io.BytesIO()
    q[0].save(buf, format="GIF", save_all=True, append_images=q[1:],
              duration=duration, loop=0, optimize=True, disposal=1)
    return buf.getvalue()


def decode(data):
    im = Image.open(io.BytesIO(data))
    return [f.convert("RGB") for f in ImageSequence.Iterator(im)]


def region_mad(dec, ref, box):
    x0, y0, x1, y1 = box
    a = np.stack([np.asarray(f, np.float32)[y0:y1, x0:x1] for f in dec])
    b = np.stack([np.asarray(f, np.float32)[y0:y1, x0:x1] for f in ref])
    return float(np.abs(a - b).mean())


def err_stats(dec, ref):
    a = np.stack([np.asarray(f, np.float32) for f in dec])
    b = np.stack([np.asarray(f, np.float32) for f in ref])
    e = np.abs(a - b)
    return float(e.mean()), float(np.percentile(e, 99.0)), float(e.max())


def main():
    src = [f.convert("RGB") for f in ImageSequence.Iterator(Image.open(ASSET))]
    cand = [src[i] for i in SEQ]
    orig_bytes = os.path.getsize(ASSET)

    MC = Image.Quantize.MEDIANCUT
    MX = Image.Quantize.MAXCOVERAGE
    specs = [("MC", MC, c) for c in (128, 256)] + \
            [("MAXCOV", MX, c) for c in (64, 96, 128, 160, 192)]

    print(f"{'method':>7s} {'colors':>6s} {'bytes':>9s} {'vs orig':>9s} {'global':>7s} "
          f"{'flower':>7s} {'eye':>6s} {'p99':>6s} {'max':>5s}")
    keep = {}
    for name, m, c in specs:
        pal = build_palette(cand, c, m)
        data = encode(cand, pal)
        dec = decode(data)
        g, p99, mx = err_stats(dec, cand)
        fl = region_mad(dec, cand, FLOWER)
        ey = region_mad(dec, cand, EYE)
        keep[(name, c)] = (data, dec, pal)
        print(f"{name:>7s} {c:6d} {len(data):9d} {len(data) - orig_bytes:+9d} {g:7.3f} "
              f"{fl:7.3f} {ey:6.3f} {p99:6.2f} {mx:5.0f}")

    # ---- 1:1 comparison of frame 5, plus 8x amplified error maps -------------
    def err_map(img, ref):
        e = np.abs(np.asarray(img, np.float32) - np.asarray(ref, np.float32)).mean(axis=2)
        return np.clip(e * 8, 0, 255).astype(np.uint8)

    picks = [("orig", None), ("MAXCOV128", ("MAXCOV", 128)), ("MC128", ("MC", 128)),
             ("MAXCOV96", ("MAXCOV", 96)), ("MAXCOV160", ("MAXCOV", 160)), ("ORIGPAL", ("MC", 256))]
    f = 5
    tiles = []
    for label, key in picks:
        if key is None:
            tiles.append(np.asarray(cand[f]))
        else:
            tiles.append(np.asarray(keep[key][1][f]))
    strip = np.concatenate(tiles, axis=1)
    Image.fromarray(strip).save(os.path.join(VERIFY, "_full_frame_compare.png"))

    emaps = []
    for label, key in picks[1:]:
        emaps.append(err_map(keep[key][1][f], cand[f]))
    emaps = np.concatenate(emaps, axis=1)
    Image.fromarray(emaps).save(os.path.join(VERIFY, "_error_maps.png"))

    print(f"\nwrote {os.path.join(VERIFY, '_full_frame_compare.png')}")
    print(f"  order: {' | '.join(l for l, _ in picks)}")
    print(f"wrote {os.path.join(VERIFY, '_error_maps.png')}")
    print(f"  order: {' | '.join(l for l, _ in picks[1:])}  (error x8)")

    for label, key in picks[1:]:
        print(f"  {label:>10s}: flower MAD={region_mad(keep[key][1], cand, FLOWER):.3f}")


if __name__ == "__main__":
    main()
