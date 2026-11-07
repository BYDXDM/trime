import zlib, struct
def load_png(path):
    data=open(path,"rb").read(); pos,idat,w,h,bd,ct=8,bytearray(),0,0,0,0
    while pos<len(data):
        ln=struct.unpack(">I",data[pos:pos+4])[0]; typ=data[pos+4:pos+8]; chunk=data[pos+8:pos+8+ln]
        if typ==b"IHDR": w,h,bd,ct=struct.unpack(">IIBB",chunk[:10])
        elif typ==b"IDAT": idat+=chunk
        elif typ==b"IEND": break
        pos+=12+ln
    nch={0:1,2:3,3:1,4:2,6:4}[ct]
    raw=zlib.decompress(bytes(idat)); stride=w*nch; out=bytearray(h*stride); prev=bytearray(stride); p=0
    for y in range(h):
        f=raw[p]; p+=1; line=bytearray(raw[p:p+stride]); p+=stride
        if f==1:
            for i in range(nch,stride): line[i]=(line[i]+line[i-nch])&255
        elif f==2:
            for i in range(stride): line[i]=(line[i]+prev[i])&255
        elif f==3:
            for i in range(stride):
                a=line[i-nch] if i>=nch else 0
                line[i]=(line[i]+((a+prev[i])>>1))&255
        elif f==4:
            for i in range(stride):
                a=line[i-nch] if i>=nch else 0
                c=prev[i-nch] if i>=nch else 0
                b=prev[i]; pp=a+b-c; pa,pb,pc=abs(pp-a),abs(pp-b),abs(pp-c)
                pr=a if (pa<=pb and pa<=pc) else (b if pb<=pc else c)
                line[i]=(line[i]+pr)&255
        out[y*stride:(y+1)*stride]=line; prev=line
    return w,h,nch,out
