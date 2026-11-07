import zlib, struct, sys, os
src,dst,x0,y0,x1,y1 = sys.argv[1],sys.argv[2],*map(int,sys.argv[3:7])
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "_pngload.py"), encoding="utf-8").read())
w,h,nch,px = load_png(src)
stride=w*nch; cw,ch=x1-x0,y1-y0
raw=bytearray()
for y in range(y0,y1):
    raw.append(0)
    row=px[y*stride:(y+1)*stride]
    for x in range(x0,x1):
        i=x*nch
        raw += bytes(row[i:i+3]) if nch>=3 else bytes([row[i]]*3)
def chunk(t,d):
    c=t+d
    return struct.pack(">I",len(d))+c+struct.pack(">I",zlib.crc32(c)&0xffffffff)
png=b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",struct.pack(">IIBBBBB",cw,ch,8,2,0,0,0))+chunk(b"IDAT",zlib.compress(bytes(raw),6))+chunk(b"IEND",b"")
open(dst,"wb").write(png)
print("wrote",dst,cw,ch,len(png))
