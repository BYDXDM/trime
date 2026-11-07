
import glob, os, sys
from PIL import Image, ImageSequence
import numpy as np

def metrics(path):
    im = Image.open(path)
    n = getattr(im, "n_frames", 1)
    frames = []
    for f in ImageSequence.Iterator(im):
        frames.append(np.asarray(f.convert("RGB"), dtype=np.float32))
    a = np.stack(frames)
    diff = np.abs(np.diff(a, axis=0)).mean(axis=(1,2,3))
    seam = np.abs(a[0] - a[-1]).mean()
    adj = diff.mean() if len(diff) else 0.0
    # subject horizontal weighted center via saturation
    mid = a[len(a)//2]
    mx = mid.max(axis=2); mn = mid.min(axis=2)
    sat = (mx - mn) / (mx + 1e-6)
    colw = sat.mean(axis=0)
    tot = colw.sum()
    cx = (colw * np.arange(len(colw))).sum() / tot / len(colw) if tot > 0 else 0
    return dict(path=os.path.basename(path), size=os.path.getsize(path),
                w=im.size[0], h=im.size[1], n=n, seam=seam, adj=adj,
                ratio=(seam/adj if adj else 0), cx=cx)

files = sorted(glob.glob(r"D:\trime-build\tools\verify-ui\_*.gif"))
rows = []
for f in files:
    try:
        rows.append(metrics(f))
    except Exception as e:
        rows.append(dict(path=os.path.basename(f), err=str(e)))

print(f"{'file':28s} {'bytes':>9s} {'WxH':>10s} {'n':>3s} {'seam':>7s} {'adj':>6s} {'ratio':>6s} {'cx':>6s}")
for r in rows:
    if "err" in r:
        print(f"{r['path']:28s} ERR {r['err']}")
        continue
    print(f"{r['path']:28s} {r['size']:9d} {str(r['w'])+'x'+str(r['h']):>10s} {r['n']:3d} {r['seam']:7.2f} {r['adj']:6.2f} {r['ratio']:6.2f} {r['cx']:6.3f}")

