"""Extract precise per-frame motion features via phase correlation + region stats.

Determines the motion model (translation? zoom? oscillation?) so the loop seam
can be fixed with the right technique.
"""

import numpy as np
from PIL import Image

DST = r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"

im = Image.open(DST)
frames = []
for i in range(im.n_frames):
    im.seek(i)
    frames.append(np.asarray(im.convert("L"), dtype=np.float32))
F = np.stack(frames)
n = len(F)
H, W = F.shape[1:]


def phase_corr(a, b, window=True):
    """Return (dy, dx) shift of b relative to a, via phase correlation."""
    A = np.fft.fft2(a - a.mean())
    B = np.fft.fft2(b - b.mean())
    if window:
        wy = np.hanning(H)[:, None]
        wx = np.hanning(W)[None, :]
        A = A * (wy * wx)
        B = B * (wy * wx)
    R = A * np.conj(B)
    R /= np.maximum(np.abs(R), 1e-9)
    r = np.fft.ifft2(R).real
    peak = np.unravel_index(np.argmax(r), r.shape)
    dy, dx = peak
    if dy > H // 2:
        dy -= H
    if dx > W // 2:
        dx -= W
    return dy, dx


print("--- PHASE-CORRELATION SHIFT of frame i relative to frame 0 (dy, dx) ---")
shifts = []
for i in range(n):
    dy, dx = phase_corr(F[0], F[i])
    shifts.append((dy, dx))
    print(f"  f{i:2d}: dy={dy:+4d} dx={dx:+4d}")

print("\n--- 1-STEP SHIFTS (frame i -> i+1) ---")
step = []
for i in range(n - 1):
    dy, dx = phase_corr(F[i], F[i + 1])
    step.append((dy, dx))
    print(f"  f{i:2d}->f{i+1:2d}: dy={dy:+4d} dx={dx:+4d}")
dy, dx = phase_corr(F[n - 1], F[0])
print(f"  f{n-1:2d}->f{0:2d}: dy={dy:+4d} dx={dx:+4d}   <-- SEAM STEP")

sd = np.array(step)
print(f"\n  interior step dy: mean={sd[:,0].mean():+.3f} std={sd[:,0].std():.3f} "
      f"min={sd[:,0].min()} max={sd[:,0].max()}")
print(f"  interior step dx: mean={sd[:,1].mean():+.3f} std={sd[:,1].std():.3f} "
      f"min={sd[:,1].min()} max={sd[:,1].max()}")
print(f"  seam step       : dy={dy:+d} dx={dx:+d}")

print("\n--- REGION SCALE / SIZE (hat bbox from dark mask) ---")
for i in range(0, n, 3):
    g = F[i]
    m = g < 90
    ys, xs = np.nonzero(m)
    if len(ys):
        print(f"  f{i:2d}: dark bbox w={xs.max()-xs.min():4d} h={ys.max()-ys.min():4d} "
              f"area={int(m.sum()):6d}  bright_frac={(g > 200).mean():.4f}")

print("\n--- GLOBAL BRIGHTNESS / CONTRAST PER FRAME ---")
for i in range(n):
    print(f"  f{i:2d}: mean={F[i].mean():7.3f} std={F[i].std():7.3f} "
          f"p1={np.percentile(F[i],1):6.1f} p99={np.percentile(F[i],99):6.1f}")
