#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""审查脚本（只读）：权重分布 / 重复词条 / 简拼撞车 / 与 luna_pinyin 同编码撞车。

用法:
    C:\\Python314\\python.exe tools/review_weights.py

产出: tools/_review_weights.txt
"""
import os
import re
import sys
from collections import defaultdict, Counter

from _console import ensure_utf8_stdout

ensure_utf8_stdout()

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MYD = os.path.join(ROOT, 'app/data/rime/myvocab/mydomain.dict.yaml')
LUNA = os.path.join(ROOT, 'app/src/main/assets/shared/luna_pinyin.dict.yaml')
OUT = os.path.join(ROOT, 'tools/_review_weights.txt')

SECTIONS = [
    (31, 114, '一、科技/AI/网络代理'),
    (119, 198, '二、原神'),
    (203, 278, '三、崩坏星穹铁道'),
    (283, 329, '四、蔚蓝档案'),
    (332, 359, '英文键名简拼别名'),
    (362, 365, '易混词兜底'),
    (368, 427, 'keywords 生成块'),
    (432, 526, '〇、常用字词'),
    (531, 594, '五、联想搭配常用词'),
]


def section_of(lineno):
    for a, b, name in SECTIONS:
        if a <= lineno <= b:
            return name
    return '(头部/其他)'


def parse(path, only_two_plus=True):
    out = []
    with open(path, encoding='utf-8') as f:
        for i, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line or line.startswith('#') or line.startswith('---'):
                continue
            parts = line.split('\t')
            if len(parts) < 2:
                continue
            w = 0.0
            ws = parts[2].strip() if len(parts) > 2 else ''
            if ws.endswith('%'):
                try:
                    w = float(ws[:-1]) / 100.0
                except ValueError:
                    w = 0.0
            elif ws:
                try:
                    w = float(ws)
                except ValueError:
                    w = 0.0
            out.append((i, parts[0].strip(), parts[1].strip(), ws, w))
    return out


def abbrev_of(code):
    """每音节首字母拼接。"""
    return ''.join(s[0] for s in code.split() if s)


def main():
    myd = parse(MYD)
    lines = []
    w = lines.append

    # ---------- 1. 权重分布 ----------
    weights = [e[4] for e in myd]
    n = len(weights)
    buckets = Counter()
    for x in weights:
        if x >= 1200:
            buckets['>=1200 (注释「专有名词」档)'] += 1
        elif x >= 700:
            buckets['700~1199 (注释「普通词」档)'] += 1
        elif x >= 200:
            buckets['200~699'] += 1
        elif x >= 100:
            buckets['100~199'] += 1
        elif x > 0:
            buckets['<100'] += 1
        else:
            buckets['0/空'] += 1
    w('== 1. 权重分布（共 %d 条）==' % n)
    for k in ['>=1200 (注释「专有名词」档)', '700~1199 (注释「普通词」档)', '200~699', '100~199', '<100', '0/空']:
        c = buckets.get(k, 0)
        w('  %-32s %4d  %5.1f%%' % (k, c, 100.0 * c / n if n else 0))
    w('  min=%g  max=%g  中位数=%g' % (min(weights), max(weights), sorted(weights)[n // 2]))
    w('')
    w('  -- 按分区（只统计 <700 的条数 / 该区总数）--')
    sec = defaultdict(lambda: [0, 0])
    for lineno, word, code, ws, val in myd:
        s = section_of(lineno)
        sec[s][1] += 1
        if val < 700:
            sec[s][0] += 1
    for a, b, name in SECTIONS:
        c, t = sec[name]
        w('  %-24s %4d / %4d 低于 700' % (name, c, t))
    w('')
    w('  -- 权重 >= 700 的全部条目 --')
    for lineno, word, code, ws, val in myd:
        if val >= 700:
            w('    mydomain.dict.yaml:%d  %s\t%s\t%s' % (lineno, word, code, ws))
    w('')

    # ---------- 2. 重复 ----------
    by_pair = defaultdict(list)
    by_word = defaultdict(list)
    for lineno, word, code, ws, val in myd:
        by_pair[(word, code)].append((lineno, ws, val))
        by_word[word].append((lineno, code, ws, val))
    w('== 2a. 完全重复的 (词, 编码) 对 ==')
    dup_pair = {k: v for k, v in by_pair.items() if len(v) > 1}
    if not dup_pair:
        w('  无')
    for (word, code), v in sorted(dup_pair.items()):
        w('  %s\t%s  出现 %d 次: %s' % (word, code, len(v),
          '; '.join('line %d w=%s' % (a, b) for a, b, _ in v)))
    w('')
    w('== 2b. 同词多编码 ==')
    for word, v in sorted(by_word.items()):
        if len(v) > 1:
            w('  %s  共 %d 条: %s' % (word, len(v),
              ' | '.join('line %d code=%s w=%s' % (a, b, c) for a, b, c, _ in v)))
    w('')
    w('== 2c. 同编码多词（按权重降序，前 40 组）==')
    by_code = defaultdict(list)
    for lineno, word, code, ws, val in myd:
        by_code[code].append((val, word, lineno, ws))
    groups = [(c, v) for c, v in by_code.items() if len(v) > 1]
    groups.sort(key=lambda kv: -len(kv[1]))
    for code, v in groups[:40]:
        v.sort(key=lambda t: -t[0])
        w('  [%s] %d 词: %s' % (code, len(v),
          ', '.join('%s(%g)@%d' % (t[1], t[0], t[2]) for t in v)))
    w('  同编码组总数: %d' % len(groups))
    w('')

    # ---------- 3. 简拼撞车 ----------
    ab = defaultdict(set)
    abw = defaultdict(list)
    for lineno, word, code, ws, val in myd:
        a = abbrev_of(code)
        if not a:
            continue
        ab[a].add(word)
        abw[a].append((val, word, lineno))
    coll = [(a, s) for a, s in ab.items() if len(s) > 1]
    coll.sort(key=lambda kv: -len(kv[1]))
    w('== 3. 简拼撞车：共 %d 组撞车 / %d 个简拼编码 ==' % (len(coll), len(ab)))
    for a, s in coll:
        v = sorted(abw[a], key=lambda t: -t[0])
        w('  [%s] %d 词: %s' % (a, len(s),
          ', '.join('%s(%g)@%d' % (t[1], t[0], t[2]) for t in v)))
    w('')

    # ---------- 4. 与 luna_pinyin 同编码撞车 ----------
    luna = parse(LUNA)
    luna_by_code = defaultdict(list)
    for lineno, word, code, ws, val in luna:
        if len(word) >= 2 and ' ' in code:
            luna_by_code[code].append((val, word))
    w('== 4. mydomain 编码在 luna_pinyin 里也有词条（前 60 组，含双方最高权重）==')
    hits = []
    for code, v in by_code.items():
        if code in luna_by_code:
            mv = max(x[0] for x in v)
            lv = max(x[0] for x in luna_by_code[code])
            top_m = max(v, key=lambda t: t[0])[1]
            top_l = max(luna_by_code[code], key=lambda t: t[0])[1]
            hits.append((code, top_m, mv, top_l, lv, len(luna_by_code[code]), len(v)))
    hits.sort(key=lambda t: -(t[2] - t[4]))
    for code, tm, mv, tl, lv, nl, nm in hits[:60]:
        w('  [%s] mydomain 最高 %s(%g) / %d 条   vs   luna 最高 %s(%g) / %d 条   Δ=%+g'
          % (code, tm, mv, nm, tl, lv, nl, mv - lv))
    w('  mydomain 编码总数 %d，其中 %d 个编码在 luna 里也存在' % (len(by_code), len(hits)))
    w('')

    text = '\n'.join(lines)
    with open(OUT, 'w', encoding='utf-8') as f:
        f.write(text + '\n')
    print('[written] %s  (%d lines)' % (OUT, len(lines)))


if __name__ == '__main__':
    sys.exit(main())
