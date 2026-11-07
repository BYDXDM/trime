import sys, os
sys.path.insert(0, r"D:\trime-build\tools\verify-ui")
from _pngload import load_png

def profile(path, y0=1495, y1=1556, thr=128):
    w,h,nch,px = load_png(path)
    cols=[]
    for x in range(w):
        dark=0
        for y in range(y0,min(y1,h)):
            i=(y*w+x)*nch
            if px[i]<thr and px[i+1]<thr and px[i+2]<thr: dark+=1
        cols.append(dark)
    return cols

def best_shift(a,b,maxs=1080):
    best=(0,1e9)
    for s in range(0,maxs+1):
        # b shifted: compare a[x] with b[x+s]
        n=len(a)-s
        if n<200: break
        mism=sum(1 for x in range(n) if (a[x]>0)!=(b[x+s]>0))
        r=mism/n
        if r<best[1]: best=(s,r)
    return best

names=[r"D:\trime-build\tools\verify-ui\h%d.png"%i for i in range(6)]
profs=[profile(p) for p in names]
print("first dark x per image:", [next((i for i,v in enumerate(pr) if v>0), -1) for pr in profs])
print("last  dark x per image:", [max((i for i,v in enumerate(pr) if v>0), default=-1) for pr in profs])
tot=0
for i in range(5):
    s,r=best_shift(profs[i],profs[i+1])
    tot+=s
    print("h%d -> h%d  shift=%4d mismatch=%.3f"%(i,i+1,s,r))
print("TOTAL forward displacement:", tot)
