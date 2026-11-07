import sys
from PIL import Image, ImageSequence

def info(p):
    im = Image.open(p)
    print("=== %s ===" % p)
    print("  size=%s mode=%s n_frames=%s" % (im.size, im.mode, getattr(im,'n_frames','?')))
    print("  info: loop=%r duration=%r background=%r" % (im.info.get('loop'), im.info.get('duration'), im.info.get('background')))
    durs=[]
    for i, fr in enumerate(ImageSequence.Iterator(im)):
        durs.append(fr.info.get('duration', 0))
        if i>400: break
    print("  frames=%d total_ms=%d avg_ms=%.1f" % (len(durs), sum(durs), sum(durs)/max(1,len(durs))))
    print("  first10 durations=%s" % durs[:10])
    print("  last10  durations=%s" % durs[-10:])
    print("  unique durations=%s" % sorted(set(durs))[:12])

for p in [r"D:\preview.gif", r"D:\trime-build\app\src\main\assets\shared\backgrounds\mybg\preview.gif"]:
    try: info(p)
    except Exception as e: print("ERR", p, e)
