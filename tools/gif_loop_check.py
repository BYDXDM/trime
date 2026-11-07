"""Final acceptance artifact: old wrap vs new wrap, labelled and annotated.

Top row    = the shipped file's wrap point (frames 27,28,29 -> 0,1,2).
Bottom row = the fixed file's wrap point (frames 35,36,37 -> 0,1,2).

In the old file the last three frames have not finished returning, so the step
into frame 0 is ~2.8x a normal step. In the new file the period is complete and
the wrap step is an ordinary one.
"""

import os

import numpy as np
from PIL import Image, ImageDraw, ImageSequence

VERIFY = r"D:\trime-build\tools\verify-ui"
ASSET = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
OLD = os.path.join(VERIFY, "_orig_preview.gif")
OUT = os.path.join(VERIFY, "gif_loop_check.png")
BOX = (130, 40, 410, 300)
S = 2
GAP = 10


def load(path):
    return [np.asarray(f.convert("RGB"), np.float32)
            for f in ImageSequence.Iterator(Image.open(path))]


def seam(frames):
    inner = [float(np.abs(frames[i + 1] - frames[i]).mean()) for i in range(len(frames) - 1)]
    s = float(np.abs(frames[0] - frames[-1]).mean())
    return s, float(np.median(inner)), s / float(np.median(inner))


def row(frames, idxs):
    x0, y0, x1, y1 = BOX
    t = np.concatenate([frames[i][y0:y1, x0:x1].astype(np.uint8) for i in idxs], axis=1)
    return np.asarray(Image.fromarray(t).resize((t.shape[1] * S, t.shape[0] * S), Image.NEAREST))


def main():
    old, new = load(OLD), load(ASSET)
    assert len(old) == 30 and len(new) == 38, (len(old), len(new))
    os_, om, orr = seam(old)
    ns, nm, nr = seam(new)

    r_old = row(old, [27, 28, 29, 0, 1, 2])
    r_new = row(new, [35, 36, 37, 0, 1, 2])
    w = r_old.shape[1]
    h = r_old.shape[0]

    sep = np.full((GAP, w, 3), 255, np.uint8)
    body = np.concatenate([r_old, sep, r_new], axis=0)

    hdr = 26
    lab = Image.new("RGB", (w, hdr * 2 + 24), (0, 0, 0))
    d = ImageDraw.Draw(lab)
    labels = [27, 28, 29, 0, 1, 2]
    for k, i in enumerate(labels):
        x = k * (BOX[2] - BOX[0]) * S + 8
        d.text((x, 7), f"f{i}", fill=(255, 255, 255))
        d.text((x, 7 + hdr), f"f{i}", fill=(255, 255, 255))
    d.text((8, 7 + hdr * 2), f"TOP = old (seam {os_:.2f} = {orr:.2f}x median)    "
                              f"BOTTOM = fixed (seam {ns:.2f} = {nr:.2f}x median)",
           fill=(255, 255, 0))

    out = np.concatenate([np.asarray(lab), body], axis=0)
    Image.fromarray(out).save(OUT)
    print(f"wrote {OUT}  {out.shape[1]}x{out.shape[0]}")
    print(f"old: seam={os_:.4f} median={om:.4f} ratio={orr:.3f}")
    print(f"new: seam={ns:.4f} median={nm:.4f} ratio={nr:.3f}")


if __name__ == "__main__":
    main()
