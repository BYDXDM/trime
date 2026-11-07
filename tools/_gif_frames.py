
from PIL import Image
import os
out = r"D:\trime-build\tools\verify-ui\_cmp"
os.makedirs(out, exist_ok=True)
pairs = [
    ("_old_preview_handover.gif", "old"),
    ("_install_candidate.gif", "cand160"),
    ("_new_preview_4th.gif", "new4th"),
]
for fn, tag in pairs:
    p = os.path.join(r"D:\trime-build\tools\verify-ui", fn)
    im = Image.open(p)
    for idx in (0, 15):
        im.seek(idx)
        im.convert("RGB").save(os.path.join(out, f"{tag}_f{idx:02d}.png"))
        print(fn, "frame", idx, "saved")

