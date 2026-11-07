"""Absolute trajectory + encoding experiments for the looping background GIF.

Part 1: align every frame to frame 0 (sub-pixel) -> true motion trajectory.
Part 2: residual after best alignment -> is the frame change pure translation?
Part 3: ping-pong loop candidates + encoding matrix (size vs quality), including a
        transparency-delta encoder (unchanged pixels = transparent index) which lets
        GIF LZW collapse the static area.
"""

import os
import numpy as np
from PIL import Image

DST = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"
OUT = r"D:\trime-build\tools\verify-ui"

im = Image.open(DST)
frames = []
for i in range(im.n_frames):
    im.seek(i)
    frames.append(im.convert("RGB").copy())
n = len(frames)
print(f"frames={n} size={frames[0].size} bytes={os.path.getsize(DST)}")

g = [np.asarray(f.convert("L").resize((256, 256), Image.LANCZOS), np.float32) for f in frames]


def surf(a, b, r):
    s = {}
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            s[(dy, dx)] = float(np.abs(np.roll(np.roll(a, dy, 0), dx, 1) - b).mean())
    return s


def subpix(s, key):
    dy0, dx0 = key
    m0 = s[key]

    def p(ax):
        kp = (dy0 + (ax == 0), dx0 + (ax == 1))
        km = (dy0 - (ax == 0), dx0 - (ax == 1))
        if kp not in s or km not in s:
            return 0.0
        den = s[kp] - 2 * m0 + s[km]
        return 0.0 if den <= 1e-9 else float(np.clip(0.5 * (s[km] - s[kp]) / den, -1, 1))

    return dy0 + p(0), dx0 + p(1), m0


print("\n--- P1/P2: absolute alignment of frame i onto frame 0 (256px search) ---")
traj = []
for i in range(n):
    s = surf(g[i], g[0], 20)
    key = min(s, key=s.get)
    dy, dx, m = subpix(s, key)
    # residual at full resolution after applying the sub-pixel shift
    if i:
        sh = frames[i].transform((512, 512), Image.AFFINE, (1, 0, dx, 0, 1, dy), resample=Image.BICUBIC)
        res = float(np.abs(np.asarray(sh, np.float32) - np.asarray(frames[0], np.float32)).mean())
    else:
        res = 0.0
    traj.append((dy, dx))
    print(f"  f{i:2d}: dy={dy:+7.2f} dx={dx:+7.2f}  searchMAD={m:6.2f}  fullres-residual={res:6.2f}")

t = np.array(traj)
print(f"  extreme: dx {t[:,1].min():+.2f} @ f{int(t[:,1].argmin())}, dy {t[:,0].max():+.2f} @ f{int(t[:,0].argmax())}")
print(f"  f29 vs f0 position gap: dy={t[29,0]:+.2f} dx={t[29,1]:+.2f}  (px)")

print("\n--- P3: encoding matrix ---")
pal_src = Image.new("RGB", (512, 512 * 8))
for k, idx in enumerate([0, 4, 8, 12, 16, 19, 24, 29]):
    pal_src.paste(frames[idx], (0, k * 512))


def build_palette(colors):
    return pal_src.quantize(colors=colors, method=Image.Quantize.MEDIANCUT)


def seq_pp(turn, step):
    fwd = list(range(0, turn + 1, step))
    return fwd + fwd[-2:0:-1]


def plain(frames_l, pal, dither):
    return [f.quantize(palette=pal, dither=dither) for f in frames_l]


def delta(frames_l, pal, dither):
    q = [np.asarray(f.quantize(palette=pal, dither=dither), np.uint8).astype(np.int16) + 1
         for f in frames_l]
    out = []
    for k, a in enumerate(q):
        d = a.astype(np.uint8).copy()
        if k:
            d[a == q[k - 1]] = 0
        img = Image.fromarray(d, "P")
        img.putpalette(pal.getpalette())
        out.append(img)
    return out


def save(fr, dur, path, transparency=None):
    kw = dict(save_all=True, append_images=fr[1:], duration=dur, loop=0, disposal=1, optimize=True)
    if transparency is not None:
        kw["transparency"] = transparency
    fr[0].save(path, **kw)
    return os.path.getsize(path)


def roundtrip_mad(path, ref):
    r = Image.open(path)
    ds = []
    for i in range(r.n_frames):
        r.seek(i)
        ds.append(float(np.abs(np.asarray(r.convert("RGB"), np.float32)
                               - np.asarray(ref[i], np.float32)).mean()))
    return float(np.mean(ds)), r.n_frames, r.info.get("duration")


for turn, step in ((19, 1), (19, 2)):
    seq = seq_pp(turn, step)
    ref = [frames[i] for i in seq]
    print(f"\n  == ping-pong turn=f{turn} step={step} -> {len(seq)} frames ==")
    for colors in (256, 128, 64):
        pal = build_palette(colors)
        for dname, dth in (("FS", Image.Dither.FLOYDSTEINBERG), ("none", Image.Dither.NONE)):
            for mode in ("plain", "delta"):
                fr = plain(ref, pal, dth) if mode == "plain" else delta(ref, pal, dth)
                p = os.path.join(OUT, f"_cand_{turn}_{step}_{colors}_{dname}_{mode}.gif")
                sz = save(fr, 100, p, transparency=0 if mode == "delta" else None)
                mad, nf, dur = roundtrip_mad(p, ref)
                print(f"     colors={colors:3d} dither={dname:4s} {mode:5s} -> {sz:9d} bytes "
                      f"| decoded frames={nf} dur={dur} roundtripMAD={mad:6.3f}")
                os.remove(p)
