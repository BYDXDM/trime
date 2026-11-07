"""Subset the ORIGINAL global colour table instead of inventing a new palette.

The asset was authored against its own 256-entry table, so keeping the N most
used entries preserves the artist's exact colours -- notably the saturated
purple flower -- with zero colour invention.  Compare against MAXCOVERAGE, and
measure chroma directly: MAD understates a saturation loss.

NOTE: a P-mode Image only knows its colours through its palette, so every frame
must be re-palettised with putpalette() before convert("RGB").
"""

import os

import numpy as np
from PIL import Image, ImageSequence

ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
VERIFY = r"D:\trime-build\tools\verify-ui"
SEQ = list(range(20)) + list(range(18, 0, -1))
DUR = 100
FLOWER = (135, 45, 285, 205)
CROP = (120, 30, 300, 210)


def read_gct(path):
    data = open(path, "rb").read()
    n = 2 << (data[10] & 7)
    return np.frombuffer(data[13:13 + 3 * n], dtype=np.uint8).reshape(n, 3)


def load_idx(path):
    im = Image.open(path)
    return [np.asarray(f if f.mode == "P" else f.convert("P"), dtype=np.uint8)
            for f in ImageSequence.Iterator(im)]


def to_rgb(idx_frames, table):
    """P indices + palette -> float RGB array list (the only correct path)."""
    out = []
    for a in idx_frames:
        im = Image.fromarray(a, "P")
        im.putpalette(table.tobytes())
        out.append(np.asarray(im.convert("RGB"), np.float32))
    return out


def save_paletted(path, idx_frames, table, duration=DUR):
    q = []
    for a in idx_frames:
        im = Image.fromarray(a, "P")
        im.putpalette(table.tobytes())
        q.append(im)
    q[0].save(path, format="GIF", save_all=True, append_images=q[1:],
              duration=duration, loop=0, optimize=True, disposal=1)
    return os.path.getsize(path)


def mad(a, b, box=None):
    if box:
        x0, y0, x1, y1 = box
        a = [f[y0:y1, x0:x1] for f in a]
        b = [f[y0:y1, x0:x1] for f in b]
    return float(np.mean([np.abs(x - y).mean() for x, y in zip(a, b)]))


def chroma(frames, box):
    x0, y0, x1, y1 = box
    return float(np.mean([(f[y0:y1, x0:x1].max(axis=2) - f[y0:y1, x0:x1].min(axis=2)).mean()
                          for f in frames]))


def decoded_rgb(path):
    im = Image.open(path)
    return [np.asarray(f.convert("RGB"), np.float32) for f in ImageSequence.Iterator(im)]


def main():
    orig_bytes = os.path.getsize(ASSET)
    gct = read_gct(ASSET)
    idx = load_idx(ASSET)
    seq = [idx[i] for i in SEQ]
    ref = to_rgb(seq, gct)
    print(f"orig bytes={orig_bytes}  gct={len(gct)} colors  frames={len(seq)}")
    print(f"reference sanity: mean RGB={np.mean([f.mean() for f in ref]):.1f} "
          f"flower chroma={chroma(ref, FLOWER):.3f}")

    counts = np.zeros(256, dtype=np.int64)
    for a in idx:
        counts += np.bincount(a.ravel(), minlength=256)
    order = np.argsort(-counts)
    print(f"palette entries used: {(counts > 0).sum()}/256   "
          f"top16 share={counts[order][:16].sum() / counts.sum():.3f}")

    print(f"\n{'scheme':>10s} {'colors':>6s} {'bytes':>9s} {'vs orig':>9s} "
          f"{'global':>7s} {'flower':>7s} {'flChroma':>8s} {'medMAD':>7s} {'ratio':>6s}")
    print(f"{'ORIGINAL':>10s} {256:6d} {orig_bytes:9d} {0:+9d} {0.0:7.3f} {0.0:7.3f} "
          f"{chroma(ref, FLOWER):8.3f} {0.0:7.3f} {2.757:6.3f}")

    results = {}

    def report(scheme, n, path, dec):
        M = np.stack([np.asarray(f, np.float32).reshape(-1) for f in dec])
        inner = [np.abs(M[i + 1] - M[i]).mean() for i in range(len(M) - 1)]
        seam = float(np.abs(M[0] - M[-1]).mean())
        med = float(np.median(inner))
        size = os.path.getsize(path)
        print(f"{scheme:>10s} {n:6d} {size:9d} {size - orig_bytes:+9d} "
              f"{mad(dec, ref):7.3f} {mad(dec, ref, FLOWER):7.3f} {chroma(dec, FLOWER):8.3f} "
              f"{med:7.3f} {seam / med:6.3f}")
        results[(scheme, n)] = path

    # ---- 1. subset the artist's own palette --------------------------------
    for n in (96, 128, 160, 192, 224):
        keep = np.sort(order[:n])
        sub = gct[keep].astype(np.int32)
        lut = np.empty(256, dtype=np.uint8)
        for i in range(256):
            lut[i] = np.argmin(((sub - gct[i].astype(np.int32)) ** 2).sum(axis=1))
        pos = np.zeros(256, dtype=np.uint8)
        pos[keep] = np.arange(n, dtype=np.uint8)
        new_idx = [pos[lut[a]] for a in seq]
        compact = np.zeros((256, 3), dtype=np.uint8)
        compact[:n] = gct[keep]
        path = os.path.join(VERIFY, f"_sub_{n}.gif")
        save_paletted(path, new_idx, compact)
        report("subset", n, path, decoded_rgb(path))

    # ---- 2. MAXCOVERAGE over the artwork -----------------------------------
    MX = Image.Quantize.MAXCOVERAGE
    for n in (96, 128, 160, 192, 256):
        px = np.concatenate([f[::2, ::2].reshape(-1, 3) for f in ref], axis=0)
        h = px.shape[0] // 256
        mont = Image.fromarray(px[:h * 256].reshape(h, 256, 3).astype(np.uint8))
        pal = mont.quantize(colors=n, method=MX, dither=Image.Dither.NONE)
        q = [Image.fromarray(f.astype(np.uint8)).quantize(palette=pal, dither=Image.Dither.NONE)
             for f in ref]
        path = os.path.join(VERIFY, f"_mx_{n}.gif")
        q[0].save(path, format="GIF", save_all=True, append_images=q[1:],
                  duration=DUR, loop=0, optimize=True, disposal=1)
        report("MAXCOV", n, path, decoded_rgb(path))

    # ---- 3. 1:1 flower crop, 2x nearest ------------------------------------
    picks = [("orig", None)] + [(f"{s}{n}", (s, n)) for s, n in
                                [("subset", 224), ("subset", 192), ("subset", 160),
                                 ("subset", 128), ("MAXCOV", 128), ("MAXCOV", 96)]]
    tiles = []
    for label, key in picks:
        if key is None:
            a = to_rgb([seq[5]], gct)[0]
        else:
            a = np.asarray(Image.open(results[key]).convert("RGB"), np.float32)
        tiles.append(a[30:210, 120:300].astype(np.uint8))
    row = np.concatenate(tiles, axis=1)
    img = Image.fromarray(row).resize((row.shape[1] * 2, row.shape[0] * 2), Image.NEAREST)
    p = os.path.join(VERIFY, "_subset_vs_maxcov.png")
    img.save(p)
    print(f"\nwrote {p}\n  order: {' | '.join(l for l, _ in picks)}")


if __name__ == "__main__":
    main()
