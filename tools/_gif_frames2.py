
from PIL import Image
import os
D = r"D:\trime-build\tools\verify-ui"
out = os.path.join(D, "_cmp")
im = Image.open(os.path.join(D, "_install_candidate.gif"))
for idx in (20, 24, 28, 32, 35):
    im.seek(idx)
    im.convert("RGB").save(os.path.join(out, f"cand_f{idx:02d}.png"))
print("saved")

