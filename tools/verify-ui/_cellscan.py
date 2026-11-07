"""Scan a candidate-bar band: locate separator columns (item decoration) and
measure ink per cell. A cell with >=100px width and <5px ink is a blank cell."""
import sys
from collections import Counter
from PIL import Image


def modal_runs(im, y0, y1):
    W, H = im.size
    px = im.load()
    y1 = min(y1, H)
    cols = []
    for x in range(W):
        c = Counter(px[x, y] for y in range(y0, y1))
        cols.append(c.most_common(1)[0][0])
    runs = []
    st = 0
    for x in range(1, W):
        if cols[x] != cols[st]:
            runs.append((cols[st], st, x - 1))
            st = x
    runs.append((cols[st], st, W - 1))
    return cols, runs


def ink_profile(im, y0, y1, dark=140):
    W, H = im.size
    px = im.load()
    y1 = min(y1, H)
    out = []
    for x in range(W):
        n = 0
        for y in range(y0, y1):
            r, g, b = px[x, y]
            if (r * 299 + g * 587 + b * 114) // 1000 < dark:
                n += 1
        out.append(n)
    return out


def main(path, y0, y1):
    im = Image.open(path).convert("RGB")
    W, H = im.size
    print(f"== {path} {W}x{H} band {y0}..{y1}")
    cols, runs = modal_runs(im, y0, y1)
    ink = ink_profile(im, y0, y1)
    bg = Counter(cols).most_common(1)[0][0]
    print(f"   dominant band colour {bg}")
    # separators: modal colour slightly darker than bg, run width 10..30
    seps = []
    for c, a, b in runs:
        w = b - a + 1
        if 8 <= w <= 32 and c != bg and max(abs(c[i] - bg[i]) for i in range(3)) <= 20:
            seps.append((a, b))
    print(f"   separator-like runs ({len(seps)}): {seps}")
    # cells between separators
    bounds = [0] + [b + 1 for a, b in seps] + [W]
    for i in range(len(bounds) - 1):
        a, b = bounds[i], min(bounds[i + 1] - 1, W - 1)
        if b <= a:
            continue
        seg = ink[a:b + 1]
        inkcols = [j for j, v in enumerate(seg) if v > 0]
        inkpx = sum(seg)
        if inkcols:
            first, last = a + inkcols[0], a + inkcols[-1]
            iw = last - first + 1
        else:
            first = last = -1
            iw = 0
        flag = "  <== BLANK CELL" if (b - a + 1) >= 100 and iw < 5 else ""
        print(f"   cell{i}: x{a}-{b} w={b - a + 1} ink={inkpx} inkcols={iw}"
              f" glyphs x{first}-{last}{flag}")


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]))
