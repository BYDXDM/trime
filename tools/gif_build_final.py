"""Final candidate build + verification for the ping-pong keyboard background.

One verified reference path (PIL direct), one metric implementation, and a
labelled montage so panels cannot be misidentified.

Candidate: frames 0..19 then 18..1 (38 frames @ 100 ms = 3800 ms) -- the
reconstructed sinusoidal sway period, which is what removes the loop stutter.

Quantiser: MAXCOVERAGE beats MEDIANCUT ~3x on the saturated flower at equal
colour count, because MEDIANCUT minimises global MSE and starves small
high-chroma regions.
"""

import os

import numpy as np
from PIL import Image, ImageDraw, ImageSequence

ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
VERIFY = r"D:\trime-build\tools\verify-ui"
SEQ = list(range(20)) + list(range(18, 0, -1))
DUR = 100
FLOWER = (135, 45, 285, 205)
EYE = (300, 230, 400, 292)
CROP = (110, 20, 310, 220)


def load_rgb(path):
    """Single reference path: seek + convert. Never re-palettise by hand."""
    im = Image.open(path)
    return [np.asarray(f.convert("RGB"), np.float32) for f in ImageSequence.Iterator(im)]


def load_idx(path):
    im = Image.open(path)
    return [np.asarray(f if f.mode == "P" else f.convert("P"), dtype=np.uint8)
            for f in ImageSequence.Iterator(im)]


def read_gct(path):
    data = open(path, "rb").read()
    n = 2 << (data[10] & 7)
    return np.frombuffer(data[13:13 + 3 * n], dtype=np.uint8).reshape(n, 3)


def to_rgb_via_palette(idx_frames, table):
    out = []
    for a in idx_frames:
        im = Image.fromarray(a, "P")
        im.putpalette(table.tobytes())
        out.append(np.asarray(im.convert("RGB"), np.float32))
    return out


def region_mad(a, b, box):
    x0, y0, x1, y1 = box
    return float(np.mean([np.abs(f[y0:y1, x0:x1] - g[y0:y1, x0:x1]).mean()
                          for f, g in zip(a, b)]))


def chroma(frames, box):
    x0, y0, x1, y1 = box
    return float(np.mean([(f[y0:y1, x0:x1].max(axis=2) - f[y0:y1, x0:x1].min(axis=2)).mean()
                          for f in frames]))


def build_palette(rgb_frames, colors, method):
    px = np.concatenate([f[::2, ::2].reshape(-1, 3) for f in rgb_frames], axis=0)
    px = np.clip(px + 0.5, 0, 255).astype(np.uint8)
    h = px.shape[0] // 256
    mont = Image.fromarray(px[:h * 256].reshape(h, 256, 3))
    return mont.quantize(colors=colors, method=method, dither=Image.Dither.NONE)


def encode(rgb_frames, pal, path, duration=DUR):
    q = [Image.fromarray(np.clip(f + 0.5, 0, 255).astype(np.uint8))
         .quantize(palette=pal, dither=Image.Dither.NONE) for f in rgb_frames]
    q[0].save(path, format="GIF", save_all=True, append_images=q[1:],
              duration=duration, loop=0, optimize=True, disposal=1)
    return os.path.getsize(path)


def main():
    orig_bytes = os.path.getsize(ASSET)
    src = load_rgb(ASSET)
    ref = [src[i] for i in SEQ]
    print(f"asset: {len(src)} frames, {orig_bytes} bytes, gct={len(read_gct(ASSET))} colors")
    print(f"reference mean RGB = {np.mean([f.mean() for f in ref]):.2f}, "
          f"flower chroma = {chroma(ref, FLOWER):.3f}")

    # cross-check the hand-palettised path against the direct one
    via = to_rgb_via_palette([load_idx(ASSET)[i] for i in SEQ], read_gct(ASSET))
    print(f"palette-path vs direct-path max |diff| = "
          f"{max(float(np.abs(a - b).max()) for a, b in zip(via, ref)):.1f}")

    interior = [float(np.abs(ref[i + 1] - ref[i]).mean()) for i in range(len(ref) - 1)]
    med = float(np.median(interior))
    print(f"PRE-ENCODE: seam={float(np.abs(ref[0] - ref[-1]).mean()):.4f} "
          f"median={med:.4f} max={max(interior):.4f} ratio={float(np.abs(ref[0] - ref[-1]).mean()) / med:.3f}\n")

    MC = Image.Quantize.MEDIANCUT
    MX = Image.Quantize.MAXCOVERAGE
    specs = [("MC", MC, 256), ("MAXCOV", MX, 256), ("MAXCOV", MX, 224),
             ("MAXCOV", MX, 192), ("MAXCOV", MX, 160), ("MAXCOV", MX, 128)]
    print(f"{'scheme':>8s} {'colors':>6s} {'bytes':>9s} {'vs orig':>9s} {'global':>7s} "
          f"{'flower':>7s} {'eye':>6s} {'flChroma':>8s} {'seam':>7s} {'med':>7s} {'ratio':>6s}")
    built = {}
    for name, m, n in specs:
        pal = build_palette(ref, n, m)
        path = os.path.join(VERIFY, f"_fin_{name}{n}.gif")
        size = encode(ref, pal, path)
        dec = load_rgb(path)
        assert len(dec) == len(ref), f"{name}{n}: {len(dec)} frames"
        din = [float(np.abs(dec[i + 1] - dec[i]).mean()) for i in range(len(dec) - 1)]
        dmed = float(np.median(din))
        dseam = float(np.abs(dec[0] - dec[-1]).mean())
        built[(name, n)] = (path, size)
        print(f"{name:>8s} {n:6d} {size:9d} {size - orig_bytes:+9d} "
              f"{float(np.mean([np.abs(a - b).mean() for a, b in zip(dec, ref)])):7.3f} "
              f"{region_mad(dec, ref, FLOWER):7.3f} {region_mad(dec, ref, EYE):6.3f} "
              f"{chroma(dec, FLOWER):8.3f} {dseam:7.4f} {dmed:7.4f} {dseam / dmed:6.3f}")

    # ---- labelled montage --------------------------------------------------
    order = [("orig", None)] + [(f"{n}{c}", (n, c)) for n, c in built]
    tiles = []
    for label, key in order:
        if key is None:
            a = ref[5]
        else:
            a = load_rgb(built[key][0])[5]
        t = a[CROP[1]:CROP[3], CROP[0]:CROP[2]].astype(np.uint8)
        tiles.append(t)
    row = np.concatenate(tiles, axis=1)
    row = np.asarray(Image.fromarray(row).resize((row.shape[1] * 2, row.shape[0] * 2), Image.NEAREST))
    lab = Image.new("RGB", (row.shape[1], 26), (0, 0, 0))
    d = ImageDraw.Draw(lab)
    for i, (label, _) in enumerate(order):
        d.text((i * 400 + 6, 7), label, fill=(255, 255, 255))
    out = np.concatenate([np.asarray(lab), row], axis=0)
    p = os.path.join(VERIFY, "_final_compare.png")
    Image.fromarray(out).save(p)
    print(f"\nwrote {p}  order: {' | '.join(l for l, _ in order)}")
    for k, (path, size) in built.items():
        print(f"  {k[0]}{k[1]}: {path}  {size} B")


if __name__ == "__main__":
    main()
