"""Locate the IME candidate bar in a screenshot and measure glyph height / ink columns.

No external deps: pure-python PNG decoder (zlib) for the limited filter set
Android's screencap emits, then column/row projections.
"""
import sys, zlib, struct

def load_png(path):
    data = open(path, 'rb').read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    pos, idat, w, h, bd, ct = 8, bytearray(), 0, 0, 0, 0
    while pos < len(data):
        ln = struct.unpack('>I', data[pos:pos+4])[0]
        typ = data[pos+4:pos+8]
        chunk = data[pos+8:pos+8+ln]
        if typ == b'IHDR':
            w, h, bd, ct = struct.unpack('>IIBB', chunk[:10])
        elif typ == b'IDAT':
            idat += chunk
        elif typ == b'IEND':
            break
        pos += 12 + ln
    assert bd == 8, f'bit depth {bd}'
    nch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    raw = zlib.decompress(bytes(idat))
    stride = w * nch
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if f == 1:
            for i in range(nch, stride):
                line[i] = (line[i] + line[i-nch]) & 255
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                a = line[i-nch] if i >= nch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = line[i-nch] if i >= nch else 0
                c = prev[i-nch] if i >= nch else 0
                b = prev[i]
                pp = a + b - c
                pa, pb, pc = abs(pp-a), abs(pp-b), abs(pp-c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out[y*stride:(y+1)*stride] = line
        prev = line
    return w, h, nch, out

def main(path, y0, y1):
    w, h, nch, px = load_png(path)
    stride = w * nch
    print(f'{path}: {w}x{h} channels={nch}, rows {y0}..{y1}')
    for y in range(y0, min(y1, h)):
        row = px[y*stride:(y+1)*stride]
        if nch >= 3:
            vals = [row[i*nch] for i in range(w)]
        else:
            vals = list(row)
        mn, mx = min(vals), max(vals)
        # ink = pixels clearly darker than the row's background (max)
        dark = sum(1 for v in vals if v < mx - 60)
        runs, run = 0, 0
        for v in vals:
            if v < mx - 60:
                if run == 0: runs += 1
                run += 1
            else:
                run = 0
        print(f'y={y:4d} bg={mx:3d} min={mn:3d} inkpx={dark:4d} runs={runs:3d}')

if __name__ == '__main__':
    main(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]))
