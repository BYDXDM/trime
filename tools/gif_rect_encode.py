"""Sub-rectangle delta encoding for the ping-pong GIF, using the ORIGINAL palette.

The 38-frame ping-pong only reorders frames that already exist, so if each frame
is stored as a rectangle covering just the pixels that changed since the
previous frame (disposal=1, no transparency), the result is bit-identical to the
source artwork while costing far less than 38 full frames.

This writer bypasses Pillow's GIF muxer entirely: it takes LZW streams from
single-frame Pillow saves and re-wraps them with the correct image descriptor.
"""

import io
import os
import struct

import numpy as np
from PIL import Image, ImageSequence

ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
VERIFY = r"D:\trime-build\tools\verify-ui"
SEQ = list(range(20)) + list(range(18, 0, -1))
DUR_CS = 10


# ------------------------------------------------------------------ container
def parse_gif(path):
    data = open(path, "rb").read()
    w, h, flags, _bg, _ar = struct.unpack("<HHBBB", data[6:13])
    pos = 13
    gct = b""
    if flags & 0x80:
        n = 2 << (flags & 7)
        gct = data[pos:pos + 3 * n]
        pos += 3 * n
    blocks = []           # (kind, bytes)  kind in {"ext","img"}
    descs = []
    while pos < len(data):
        b = data[pos]
        if b == 0x3B:
            break
        start = pos
        if b == 0x21:
            pos += 2
            while data[pos]:
                pos += data[pos] + 1
            pos += 1
            blocks.append(("ext", data[start:pos]))
        elif b == 0x2C:
            descs.append(struct.unpack("<HHHHB", data[pos + 1:pos + 10]))
            pos += 10
            lflags = data[pos - 1]
            if lflags & 0x80:
                pos += 3 * (2 << (lflags & 7))
            pos += 1
            while data[pos]:
                pos += data[pos] + 1
            pos += 1
            blocks.append(("img", data[start:pos]))
        else:
            raise ValueError(f"block 0x{b:02x} at {pos}")
    return (w, h), gct, blocks, descs


def lzw_payload(pil_p, gct):
    """Return (descriptor_fields, lzw_bytes) for one P-mode frame."""
    buf = io.BytesIO()
    pil_p.save(buf, format="GIF", optimize=False, disposal=1)
    data = buf.getvalue()
    pos = 13 + (3 * (2 << (data[10] & 7)) if data[10] & 0x80 else 0)
    assert data[pos] == 0x2C, hex(data[pos])
    desc = struct.unpack("<HHHHB", data[pos + 1:pos + 10])
    p = pos + 10
    if desc[4] & 0x80:
        p += 3 * (2 << (desc[4] & 7))
    return desc, data[p:]


def frame_block(pil_p, left, top, gct, delay_cs=DUR_CS, transparent=None):
    """Assemble GCE + image descriptor (at left/top) + LZW data."""
    _d, payload = lzw_payload(pil_p, gct)
    packed = 0x00 if transparent is None else 0x01
    gce = b"\x21\xF9\x04" + bytes([packed]) + struct.pack("<H", delay_cs) + (
        b"\x00" if transparent is None else bytes([transparent])) + b"\x00"
    w, h = pil_p.size
    desc = struct.pack("<BHHHHB", 0x2C, left, top, w, h, 0x00)
    return gce + desc + payload


def write_gif(path, canvas, gct, frames, delays):
    out = [b"GIF89a", struct.pack("<HHBBB", canvas[0], canvas[1], 0xF7, 0, 0), gct,
           b"\x21\xFF\x0BNETSCAPE2.0\x03\x01\x00\x00\x00"]
    for im, (left, top), d in zip(frames, [f[1] for f in frames], delays):
        pass
    raise SystemExit


# ------------------------------------------------------------------ diff logic
def main():
    canvas, gct, blocks, descs = parse_gif(ASSET)
    print(f"canvas={canvas}  gct={len(gct)//3} colors  frames={len(descs)}")
    print(f"descriptors (left,top,w,h,flags): {descs[:3]} ... {descs[-1]}")
    full = [d == (0, 0, canvas[0], canvas[1], 0) for d in descs]
    print(f"full-canvas frames: {sum(full)}/{len(descs)}")

    im = Image.open(ASSET)
    idx = []
    for f in ImageSequence.Iterator(im):
        idx.append(np.asarray(f if f.mode == "P" else f.convert("P"), dtype=np.uint8))
    print(f"decoded {len(idx)} frames, each {idx[0].shape}, dtype={idx[0].dtype}")

    seq = [idx[i] for i in SEQ]
    print(f"\nping-pong: {len(seq)} frames @ {DUR_CS*10}ms = {len(seq)*DUR_CS*10}ms")

    # per-step changed area
    fracs, boxes = [], []
    for i in range(1, len(seq)):
        diff = seq[i] != seq[i - 1]
        fracs.append(diff.mean())
        ys, xs = np.nonzero(diff)
        if len(ys) == 0:
            boxes.append(None)
        else:
            boxes.append((int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1))
    fr = np.array(fracs)
    print(f"changed-pixel fraction per step: mean={fr.mean():.3f} med={np.median(fr):.3f} "
          f"min={fr.min():.3f} max={fr.max():.3f}")
    areas = [((b[2] - b[0]) * (b[3] - b[1]) / (canvas[0] * canvas[1])) if b else 0.0 for b in boxes]
    ar = np.array(areas)
    print(f"bbox area fraction:             mean={ar.mean():.3f} med={np.median(ar):.3f} "
          f"min={ar.min():.3f} max={ar.max():.3f}")

    # ---- encode: full frames vs bbox rects ---------------------------------
    def encode_rect(use_rect):
        parts = [b"GIF89a", struct.pack("<HHBBB", canvas[0], canvas[1], 0xF7, 0, 0), gct,
                 b"\x21\xFF\x0BNETSCAPE2.0\x03\x01\x00\x00\x00"]
        for i, a in enumerate(seq):
            if i == 0 or not use_rect or boxes[i - 1] is None:
                rect, left, top = a, 0, 0
            else:
                x0, y0, x1, y1 = boxes[i - 1]
                rect, left, top = a[y0:y1, x0:x1], x0, y0
            parts.append(frame_block(Image.fromarray(rect, "P"), left, top, gct))
        parts.append(b"\x3B")
        return b"".join(parts)

    for label, use_rect in (("full 512x512 frames", False), ("bbox sub-rects", True)):
        blob = encode_rect(use_rect)
        open(os.path.join(VERIFY, f"_enc_{'rect' if use_rect else 'full'}.gif"), "wb").write(blob)
        print(f"{label:>20s}: {len(blob)} bytes  ({len(blob) - os.path.getsize(ASSET):+d} vs orig)")

    # ---- verify the rect version decodes back to the same indices ----------
    blob = open(os.path.join(VERIFY, "_enc_rect.gif"), "rb").read()
    dec = [np.asarray(f if f.mode == "P" else f.convert("P"), dtype=np.uint8)
           for f in ImageSequence.Iterator(Image.open(io.BytesIO(blob)))]
    print(f"\nrect file decodes to {len(dec)} frames")
    if len(dec) == len(seq):
        bad = [i for i in range(len(seq)) if not np.array_equal(dec[i], seq[i])]
        print(f"index-exact frames: {len(seq) - len(bad)}/{len(seq)}   mismatches={bad[:10]}")
    else:
        print("!! frame count mismatch")


if __name__ == "__main__":
    main()
