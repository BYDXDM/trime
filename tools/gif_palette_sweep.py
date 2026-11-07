"""Fine palette-size sweep for the fixed (ping-pong) keyboard background GIF.

Candidate = frames 0..19 then 18..1  (38 frames @ 100 ms = 3800 ms).
This reconstructs the truncated return leg of the sinusoidal sway, so the wrap
lands on the smallest step in the sequence (MAD(f1,f0)) instead of a snap.

Palette is built by strided subsampling of the FULL-RESOLUTION frames (no
resampling blur), then one global color table is shared by every frame.

Prints size / round-trip MAD / loop-seam ratio per palette size, and writes a
side-by-side crop so banding can be judged by eye.
"""

import io
import os

import numpy as np
from PIL import Image, ImageSequence

ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
VERIFY = r"D:\trime-build\tools\verify-ui"
SEQ = list(range(20)) + list(range(18, 0, -1))
DUR = 100


def load_rgb(path):
    im = Image.open(path)
    return [f.convert("RGB") for f in ImageSequence.Iterator(im)]


def build_palette(frames, colors):
    """Global palette from strided full-res samples of every frame."""
    tiles = []
    for f in frames:
        a = np.asarray(f)[::2, ::2]  # 256x256, real pixels
        tiles.append(a.reshape(-1, 3))
    px = np.concatenate(tiles, axis=0)
    h = px.shape[0] // 256
    mont = Image.fromarray(px[: h * 256].reshape(h, 256, 3))
    return mont.quantize(colors=colors, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)


def encode(frames, pal, duration, optimize=True):
    q = [f.quantize(palette=pal, dither=Image.Dither.NONE) for f in frames]
    buf = io.BytesIO()
    q[0].save(buf, format="GIF", save_all=True, append_images=q[1:],
              duration=duration, loop=0, optimize=optimize, disposal=1)
    return buf.getvalue()


def decode(data):
    im = Image.open(io.BytesIO(data))
    return [f.convert("RGB") for f in ImageSequence.Iterator(im)]


def mad_matrix(frames):
    arr = np.stack([np.asarray(f, dtype=np.float32).reshape(-1) for f in frames])
    M = np.zeros((len(arr), len(arr)), dtype=np.float32)
    for i in range(len(arr)):
        M[i] = np.abs(arr - arr[i]).mean(axis=1)
    return M


def seam_stats(M):
    n = len(M)
    inner = [M[i, i + 1] for i in range(n - 1)]
    med = float(np.median(inner))
    seam = float(M[n - 1, 0])
    return seam, med, float(np.max(inner)), (seam / med if med else float("inf"))


def main():
    src = load_rgb(ASSET)
    frames = [src[i] for i in SEQ]
    print(f"candidate: {len(frames)} frames @ {DUR}ms = {len(frames) * DUR}ms   asset orig bytes={os.path.getsize(ASSET)}")

    Msrc = mad_matrix(frames)
    s, med, mx, r = seam_stats(Msrc)
    print(f"PRE-ENCODE geometry: seam={s:.4f} med={med:.4f} max={mx:.4f} ratio={r:.3f}\n")

    print(f"{'colors':>6s} {'bytes':>9s} {'vs orig':>9s} {'rtMAD':>7s} {'seam':>8s} {'med':>8s} {'max':>8s} {'ratio':>6s}")
    best = None
    keep = {}
    for colors in (32, 48, 64, 80, 96, 112, 128, 144, 160, 192, 224, 256):
        pal = build_palette(frames, colors)
        data = encode(frames, pal, DUR)
        dec = decode(data)
        assert len(dec) == len(frames), f"{colors}: {len(dec)} frames"
        rt = float(np.mean([np.abs(np.asarray(a, np.float32) - np.asarray(b, np.float32)).mean()
                            for a, b in zip(dec, frames)]))
        M = mad_matrix(dec)
        s, med, mx, r = seam_stats(M)
        delta = len(data) - os.path.getsize(ASSET)
        print(f"{colors:6d} {len(data):9d} {delta:+9d} {rt:7.3f} {s:8.4f} {med:8.4f} {mx:8.4f} {r:6.3f}")
        keep[colors] = (data, pal)
        if best is None or len(data) < best[1]:
            best = (colors, len(data))

    # visual banding check: 256 vs 96 vs 64 on a smooth-gradient crop
    crop = (150, 40, 400, 290)
    strips = []
    labels = [256, 96, 64]
    for c in labels:
        dec = decode(keep[c][0])
        strips.append(np.asarray(dec[5].crop(crop), dtype=np.uint8))
    out = np.concatenate(strips, axis=1)
    Image.fromarray(out).resize((out.shape[1] * 2, out.shape[0] * 2), Image.NEAREST).save(
        os.path.join(VERIFY, "_palette_compare.png"))
    print(f"\nwrote {os.path.join(VERIFY, '_palette_compare.png')} (left=256, mid=96, right=64 colors)")

    print(f"\nsmallest palette: {best[0]} colors -> {best[1]} bytes")
    for c in labels:
        print(f"  colors={c}: {len(keep[c][0])} bytes")
    # persist the 256-color result for the caller
    with open(os.path.join(VERIFY, "_cand_pp38_c256.gif"), "wb") as fh:
        fh.write(keep[256][0])
    print(f"wrote {os.path.join(VERIFY, '_cand_pp38_c256.gif')}")


if __name__ == "__main__":
    main()
