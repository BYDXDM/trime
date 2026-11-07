
from PIL import Image
import os
D = r"D:\trime-build\tools\verify-ui"
out = os.path.join(D, "_cmp")
im = Image.open(os.path.join(D, "_install_candidate.gif"))
n = im.n_frames
for idx in (0, n-1):
    im.seek(idx)
    im.convert("RGB").save(os.path.join(out, f"cand_f{idx:02d}.png"))
print("n_frames:", n, "-> saved f0 and f", n-1)

# durations
durs = []
im2 = Image.open(os.path.join(D, "_install_candidate.gif"))
for i in range(n):
    im2.seek(i)
    durs.append(im2.info.get("duration"))
print("durations:", durs)
print("total ms:", sum(d or 0 for d in durs))

im3 = Image.open(os.path.join(D, "_old_preview_handover.gif"))
d3 = []
for i in range(im3.n_frames):
    im3.seek(i)
    d3.append(im3.info.get("duration"))
print("old durations:", d3)
print("old total ms:", sum(d or 0 for d in d3))

