"""Compare quantizers for the ping-pong GIF, and measure the zero-loss remux size.

Two questions:
  1) How small can the palette go before the vivid purple flower desaturates?
     MEDIANCUT minimises MSE and therefore starves small saturated regions;
     MAXCOVERAGE / FASTOCTREE may hold the flower at low colour counts.
  2) The ping-pong only REORDERS existing frames, so the original file's index
     data + global colour table can be remuxed verbatim -> bit-identical pixels.
     How big is that, versus a Pillow re-encode?
"""

import io
import os
import struct

import numpy as np
from PIL import Image, ImageSequence

ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
VERIFY = r"D:\trime-build\tools\verify-ui"
SEQ = list(range(20)) + list(range(18, 0, -1))
DUR = 100
CROP = (150, 40, 400, 290)


# ---------------------------------------------------------------- GIF parsing
def parse_gif(path):
    """Return (head, gct, [frame_bytes, ...]) for a GIF with a global palette."""
    data = open(path, "rb").read()
    assert data[:6] in (b"GIF87a", b"GIF89a"), data[:6]
    w, h, flags, _bg, _ar = struct.unpack("<HHBBB", data[6:13])
    pos = 13
    gct = b""
    if flags & 0x80:
        n = 2 << (flags & 7)
        gct = data[pos:pos + 3 * n]
        pos += 3 * n
    head = data[:13]
    frames = []
    while pos < len(data):
        b = data[pos]
        if b == 0x3B:  # trailer
            break
        start = pos
        if b == 0x21:  # extension
            pos += 2
            while data[pos]:
                pos += data[pos] + 1
            pos += 1
        elif b == 0x2C:  # image descriptor
            pos += 10
            lflags = data[pos - 1]
            if lflags & 0x80:
                pos += 3 * (2 << (lflags & 7))
            pos += 1  # LZW minimum code size
            while data[pos]:
                pos += data[pos] + 1
            pos += 1
            frames.append(data[start:pos])
        else:
            raise ValueError(f"unexpected block 0x{b:02x} at {pos}")
    return head, gct, frames, (w, h), data[-1:]


def build_palette(frames, colors, method):
    tiles = [np.asarray(f)[::2, ::2].reshape(-1, 3) for f in frames]
    px = np.concatenate(tiles, axis=0)
    h = px.shape[0] // 256
    mont = Image.fromarray(px[: h * 256].reshape(h, 256, 3))
    return mont.quantize(colors=colors, method=method, dither=Image.Dither.NONE)


def encode(frames, pal, duration, palette_bytes=None):
    q = [f.quantize(palette=pal, dither=Image.Dither.NONE) for f in frames]
    if palette_bytes is not None:
        q = [im.putpalette(palette_bytes) or im for im in q]
    buf = io.BytesIO()
    q[0].save(buf, format="GIF", save_all=True, append_images=q[1:],
              duration=duration, loop=0, optimize=True, disposal=1)
    return buf.getvalue()


def decode(data):
    im = Image.open(io.BytesIO(data))
    return [f.convert("RGB") for f in ImageSequence.Iterator(im)]


def rt_mad(dec, ref):
    return float(np.mean([np.abs(np.asarray(a, np.float32) - np.asarray(b, np.float32)).mean()
                          for a, b in zip(dec, ref)]))


def main():
    orig_bytes = os.path.getsize(ASSET)
    head, gct, frames, size, trailer = parse_gif(ASSET)
    print(f"asset: {size[0]}x{size[1]}  frames={len(frames)}  gct={len(gct) // 3} colors  bytes={orig_bytes}")
    lens = [len(f) for f in frames]
    print(f"per-frame block bytes: min={min(lens)} med={int(np.median(lens))} max={max(lens)} "
          f"sum={sum(lens)} overhead={orig_bytes - sum(lens)}")

    # ---- zero-loss remux ---------------------------------------------------
    remux = head + gct + b"".join(frames[i] for i in SEQ) + trailer
    print(f"\nREMUX (original palette + original LZW streams, order={'pp19'}): "
          f"{len(remux)} bytes  ({len(remux) - orig_bytes:+d} vs orig)  pixels bit-identical")
    open(os.path.join(VERIFY, "_cand_remux.gif"), "wb").write(remux)

    # ---- quantizer comparison ---------------------------------------------
    src = [f.convert("RGB") for f in ImageSequence.Iterator(Image.open(ASSET))]
    cand = [src[i] for i in SEQ]

    methods = {
        "MC": Image.Quantize.MEDIANCUT,
        "MAXCOV": Image.Quantize.MAXCOVERAGE,
        "OCTREE": Image.Quantize.FASTOCTREE,
    }
    results = {}
    print(f"\n{'method':>7s} {'colors':>6s} {'bytes':>9s} {'vs orig':>9s} {'rtMAD':>7s}")
    for name, m in methods.items():
        for colors in (96, 128, 160, 192, 256):
            pal = build_palette(cand, colors, m)
            data = encode(cand, pal, DUR)
            dec = decode(data)
            rt = rt_mad(dec, cand)
            results[(name, colors)] = (len(data), rt, data, pal)
            print(f"{name:>7s} {colors:6d} {len(data):9d} {len(data) - orig_bytes:+9d} {rt:7.3f}")

    # ---- exact-palette re-encode (original 256-color table, rtMAD 0) --------
    pal_orig = Image.frombytes("P", (1, 1), bytes([0])).convert("RGB")
    pal_orig = Image.new("P", (1, 1))
    pal_orig.putpalette(gct)
    data = encode(cand, pal_orig, DUR)
    dec = decode(data)
    print(f"\n{'ORIGPAL':>7s} {256:6d} {len(data):9d} {len(data) - orig_bytes:+9d} {rt_mad(dec, cand):7.3f}"
          f"   <- original global colour table, zero colour error")

    # ---- visual check on the flower ---------------------------------------
    panels = [
        ("orig", np.asarray(src[5].crop(CROP))),
        ("MC256", np.asarray(decode(results[("MC", 256)][2])[5].crop(CROP))),
        ("ORIGPAL", np.asarray(decode(data)[5].crop(CROP))),
        ("MC128", np.asarray(decode(results[("MC", 128)][2])[5].crop(CROP))),
        ("MAXCOV128", np.asarray(decode(results[("MAXCOV", 128)][2])[5].crop(CROP))),
        ("OCTREE128", np.asarray(decode(results[("OCTREE", 128)][2])[5].crop(CROP))),
    ]
    row1 = np.concatenate([p for _, p in panels[:3]], axis=1)
    row2 = np.concatenate([p for _, p in panels[3:]], axis=1)
    grid = np.concatenate([row1, row2], axis=0)
    out = Image.fromarray(grid).resize((grid.shape[1] * 2, grid.shape[0] * 2), Image.NEAREST)
    path = os.path.join(VERIFY, "_palette_methods.png")
    out.save(path)
    print(f"\nwrote {path}  row1={' | '.join(n for n, _ in panels[:3])}  row2={' | '.join(n for n, _ in panels[3:])}")


if __name__ == "__main__":
    main()
