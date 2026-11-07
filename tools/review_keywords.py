#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""审查脚本（只读）：keywords 块缩放核对 + essay.txt 游戏/科技词覆盖 + predict 交叉核对。

用法:
    C:\\Python314\\python.exe tools/review_keywords.py

产出: tools/_review_keywords.txt
"""
import os
import re
import sys
from collections import defaultdict

from _console import ensure_utf8_stdout

ensure_utf8_stdout()

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MYD = os.path.join(ROOT, 'app/data/rime/myvocab/mydomain.dict.yaml')
KW = os.path.join(ROOT, 'app/data/rime/myvocab/keywords.tsv')
ESSAY = os.path.join(ROOT, 'app/src/main/assets/shared/essay.txt')
PRED = os.path.join(ROOT, 'app/data/rime/myvocab/predict.txt')
OUT = os.path.join(ROOT, 'tools/_review_keywords.txt')

START = '# ===== BEGIN keywords（由 tools/gen_keywords.py 生成，勿手改）====='
END = '# ===== END keywords ====='


def read_tsv():
    out = []
    with open(KW, encoding='utf-8') as f:
        for i, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line.strip() or line.strip().startswith('#'):
                continue
            if re.match(r'^\s*---.*---\s*$', line):
                continue
            parts = line.split('\t')
            if len(parts) != 3:
                out.append((i, None, None, None))
                continue
            out.append((i, parts[0].strip(), parts[1].strip(), parts[2].strip()))
    return out


def read_dict():
    out = []
    with open(MYD, encoding='utf-8') as f:
        for i, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line or line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) < 2:
                continue
            out.append((i, parts[0].strip(), parts[1].strip(),
                        parts[2].strip() if len(parts) > 2 else ''))
    return out


def main():
    tsv = read_tsv()
    dic = read_dict()
    lines = []
    w = lines.append

    tsv_map = defaultdict(list)
    for lineno, word, code, weight in tsv:
        if word is None:
            w('⚠ keywords.tsv:%d 格式错误（非 3 列）' % lineno)
            continue
        tsv_map[(word, code)].append((lineno, weight))

    dic_map = defaultdict(list)
    for lineno, word, code, weight in dic:
        dic_map[(word, code)].append((lineno, weight))

    w('== 5a. keywords.tsv 的每条 (词,编码) 是否出现在 dict 里 / 权重倍数 ==')
    miss = []
    ratio = defaultdict(int)
    for key, v in sorted(tsv_map.items()):
        if key not in dic_map:
            miss.append(key)
            w('  ✗ 缺失  %s\t%s  (tsv 行 %s 权重 %s)' % (key[0], key[1],
              ','.join(str(a) for a, _ in v), ','.join(b for _, b in v)))
            continue
        tv = float(v[0][1])
        for dlineno, dw in dic_map[key]:
            try:
                dv = float(dw)
            except ValueError:
                dv = 0.0
            r = tv / dv if dv else float('inf')
            ratio[round(r, 3)] += 1
            w('  %s\t%s  tsv:%s(%s) → dict:%d(%s)  倍数=%.4g'
              % (key[0], key[1], v[0][0], v[0][1], dlineno, dw, r))
    w('')
    w('  tsv 条目 %d 条，其中 %d 条在 dict 里找不到' % (len(tsv_map), len(miss)))
    w('  权重倍数分布: %s' % dict(ratio))
    w('')

    w('== 5b. dict 里有但 tsv 里没有的 keywords 块条目（残留）==')
    blk = None
    with open(MYD, encoding='utf-8') as f:
        txt = f.read()
    m = re.search(re.escape(START) + r'(.*?)' + re.escape(END), txt, re.S)
    if m:
        blk = m.group(1)
        s_line = txt[:m.start()].count('\n') + 1
        w('  BEGIN 行号=%d' % s_line)
    extra = 0
    for lineno, word, code, weight in dic:
        if (word, code) not in tsv_map and START not in '':
            pass
    if blk is not None:
        for j, bl in enumerate(blk.split('\n')):
            bl = bl.strip()
            if not bl or bl.startswith('#'):
                continue
            parts = bl.split('\t')
            if len(parts) < 3:
                continue
            key = (parts[0].strip(), parts[1].strip())
            if key not in tsv_map:
                extra += 1
                w('  dict 独有: %s\t%s\t%s' % key[0], )
        w('  dict keywords 块独有条目数: %d' % extra)
    w('')

    w('== 5c. essay.txt 是否收录这些词（决定 mymain 会不会产生同码强候选）==')
    words = ['原神', '崩铁', '崩坏', '星穹铁道', '蒙德', '璃月', '钟离', '原石', '圣遗物',
             '遗器', '光锥', '命途', '深渊', '派蒙', '旅行者', '蔚蓝档案', '白子', '优香',
             '初音', '保底', '抽卡', '双暴', '专武', '树脂', '秘境', '地脉', '天赋', '武器',
             '周本', '十连', '星琼', '仙舟', '镜流', '花火', '藿藿', '希儿', '罗刹', '砂金',
             '丹恒', '飞霄', '符玄', '温迪', '神里绫华', '邮箱', '代理', '机场', '节点',
             '订阅', '测速', '加密', '解密', '部署', '编译', '调试', '参数', '复杂度',
             '备份', '重启', '断开', '带宽', '并发', '算力', '伪装', '证书', '云端',
             '邮箱地址', '订阅链接', '节点列表', '代理规则', '机场订阅', '免费节点',
             '订阅地址', '原石兑换码', '深渊螺旋', '角色培养', '体力恢复', '网易邮箱',
             '谷歌邮箱', '企业邮箱', '电子邮箱', 'QQ邮箱']
    essay = {}
    with open(ESSAY, encoding='utf-8') as f:
        for i, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line or line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) < 2:
                continue
            try:
                essay[parts[0]] = (float(parts[1]), i)
            except ValueError:
                pass
    hit = 0
    for x in words:
        if x in essay:
            hit += 1
            w('  essay.txt:%d  %s\t%g   ← mydomain 同词条也在' % (essay[x][1], x, essay[x][0]))
        else:
            w('  (essay 无)  %s' % x)
    w('  抽查 %d 词，essay.txt 收录 %d 个' % (len(words), hit))
    w('')

    w('== 5d. predict.txt 的「上文词」是否都在 dict 里 ==')
    dic_words = set(x[1] for x in dic)
    bad = 0
    n = 0
    with open(PRED, encoding='utf-8') as f:
        for i, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line or line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) < 3:
                w('  ⚠ predict.txt:%d 列数不足: %r' % (i, line))
                continue
            n += 1
            ctx = parts[0]
            if ctx == '$':
                continue
            if ctx not in dic_words and ctx not in essay:
                bad += 1
                w('  predict.txt:%d 上文词「%s」既不在 mydomain.dict.yaml 也不在 essay.txt' % (i, ctx))
    w('  predict.txt 数据行 %d，上文词缺失 %d' % (n, bad))
    w('')

    w('== 5e. predict.txt 里候选词带拼音编码的与 dict 是否一致 ==')
    mism = 0
    with open(PRED, encoding='utf-8') as f:
        for i, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line or line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) < 4:
                continue
            word, code = parts[1].strip(), parts[3].strip()
            if (word, code) in dic_map:
                continue
            if word in dic_words:
                mism += 1
                w('  predict.txt:%d  %s\t%s   dict 里该词的编码是: %s'
                  % (i, word, code,
                     ', '.join('%s@%d' % (c, l) for l, ww, c, _ in dic if ww == word)))
    w('  predict.txt 带拼音列且与 dict 不一致的候选词: %d' % mism)

    text = '\n'.join(lines)
    with open(OUT, 'w', encoding='utf-8') as f:
        f.write(text + '\n')
    print(text)


if __name__ == '__main__':
    sys.exit(main())
