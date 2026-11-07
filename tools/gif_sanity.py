"""Settle an apparent contradiction: a candidate file reports tiny static error
vs the original yet a 3x larger frame-to-frame MAD.  Compute all three numbers
from one load path so the comparison is apples-to-apples.
"""

import numpy as np
from PIL import Image, ImageSequence

ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
SEQ = list(range(20)) + list(range(18, 0, -1))


def load(path):
    return [np.asarray(f.convert("RGB"), np.float32)
            for f in ImageSequence.Iterator(Image.open(path))]


def mad(a, b):
    return float(np.abs(a - b).mean())


def main():
    src = load(ASSET)
    ref = [src[i] for i in SEQ]

    print(f"{'pair':>8s} {'orig MAD':>10s}")
    for i in (0, 1, 5, 10, 18, 19, 30, 36, 37):
        j = (i + 1) % len(ref)
        print(f"{i:4d}->{j:<3d} {mad(ref[i], ref[j]):10.4f}")
    interior = [mad(ref[i], ref[i + 1]) for i in range(len(ref) - 1)]
    print(f"ref interior median = {np.median(interior):.4f}")

    for name in ("_mx_128.gif", "_mx_96.gif", "_sub_128.gif", "_cand_pp38_c256.gif",
                 "_enc_full.gif", "_cand_remux.gif"):
        path = r"D:\trime-build\tools\verify-ui\\" + name
        try:
            dec = load(path)
        except Exception as e:  # noqa: BLE001
            print(f"\n{name}: cannot load ({e})")
            continue
        if len(dec) != len(ref):
            print(f"\n{name}: frame count {len(dec)} != {len(ref)}")
            continue
        static = float(np.mean([mad(a, b) for a, b in zip(dec, ref)]))
        interior = [mad(dec[i], dec[i + 1]) for i in range(len(dec) - 1)]
        seam = mad(dec[-1], dec[0])
        print(f"\n{name}: frames={len(dec)} static-vs-orig={static:.4f} "
              f"interior med={np.median(interior):.4f} min={min(interior):.4f} "
              f"max={max(interior):.4f} seam={seam:.4f} ratio={seam / np.median(interior):.3f}")


if __name__ == "__main__":
    main()
