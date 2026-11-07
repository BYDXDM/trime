
from PIL import Image, ImageSequence
import numpy as np, os
D = r"D:\trime-build\tools\verify-ui"

def load(fn):
    im = Image.open(os.path.join(D, fn))
    return [np.asarray(f.convert("RGB"), dtype=np.float32) for f in ImageSequence.Iterator(im)]

old = load("_old_preview_handover.gif")
cand = load("_install_candidate.gif")

print("== cand[i] vs old[i] ==")
for i in range(len(cand)):
    if i < len(old):
        d = np.abs(cand[i]-old[i]).mean()
        print(f"  i={i:2d} diff={d:6.3f}")
    else:
        print(f"  i={i:2d} (no old frame)")

print()
print("== ping-pong 假设: cand[30+k] vs old[28-k] ==")
for k in range(8):
    ci = 30+k
    oi = 28-k
    if 0 <= oi < len(old):
        d = np.abs(cand[ci]-old[oi]).mean()
        print(f"  cand[{ci}] vs old[{oi}] diff={d:6.3f}")

