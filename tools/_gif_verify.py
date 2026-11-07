
from PIL import Image, ImageSequence
import numpy as np, os, hashlib

D = r"D:\trime-build\tools\verify-ui"

def load(fn):
    im = Image.open(os.path.join(D, fn))
    return [np.asarray(f.convert("RGB"), dtype=np.float32) for f in ImageSequence.Iterator(im)]

old = load("_old_preview_handover.gif")
cand = load("_install_candidate.gif")
print("old frames:", len(old), " cand frames:", len(cand))

# 首帧逐像素差
d0 = np.abs(old[0] - cand[0]).mean()
print(f"frame0 mean abs diff: {d0:.3f}")

# 找 old 每帧在 cand 里的最近匹配
for oi in (0, 5, 10, 15, 20, 25, 29):
    best = min(range(len(cand)), key=lambda ci: np.abs(old[oi] - cand[ci]).mean())
    print(f"old[{oi:2d}] -> cand[{best:2d}] diff={np.abs(old[oi]-cand[best]).mean():.3f}")

print()
for fn in ("_install_candidate.gif","_fin_MAXCOV160.gif","_fin_MAXCOV192.gif","_fin_MAXCOV224.gif","_fin_MAXCOV256.gif"):
    p = os.path.join(D, fn)
    h = hashlib.sha256(open(p,"rb").read()).hexdigest()[:16]
    im = Image.open(p)
    cols = len(im.convert("RGB").getcolors(maxcolors=1<<24) or [])
    print(f"{fn:26s} {os.path.getsize(p):9d} sha={h} colors={cols}")

