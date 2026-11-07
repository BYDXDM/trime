#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""审查脚本（只读）：用 essay.txt 补全 luna_pinyin 的「有效权重」，做同编码撞车真实排序。

luna_pinyin.schema.yaml 的 translator 用 use_preset_vocabulary: true，
所以 dict.yaml 里权重列为空的多字词条，其权重来自 essay.txt。
只看 dict.yaml 会把它们当成 0，得出错误结论。

用法:
    C:\\Python314\\python.exe tools/review_luna.py [编码1 编码2 ...]

产出: tools/_review_luna.txt
"""
import os
import re
import sys
from collections import defaultdict

from _console import ensure_utf8_stdout

ensure_utf8_stdout()

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MYD = os.path.join(ROOT, 'app/data/rime/myvocab/mydomain.dict.yaml')
LUNA = os.path.join(ROOT, 'app/src/main/assets/shared/luna_pinyin.dict.yaml')
ESSAY = os.path.join(ROOT, 'app/src/main/assets/shared/essay.txt')
OUT = os.path.join(ROOT, 'tools/_review_luna.txt')


def parse_rows(path):
    out = []
    with open(path, encoding='utf-8') as f:
        for i, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line or line.startswith('#') or line.startswith('---'):
                continue
            parts = line.split('\t')
            if len(parts) < 2:
                continue
            out.append((i, parts[0].strip(), parts[1].strip(),
                        parts[2].strip() if len(parts) > 2 else ''))
    return out


def essay_weights():
    w = {}
    with open(ESSAY, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not line or line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) < 2:
                continue
            try:
                w[parts[0]] = float(parts[1])
            except ValueError:
                pass
    return w


def main():
    essay = essay_weights()
    luna = parse_rows(LUNA)
    myd = parse_rows(MYD)

    # luna: code -> [(word, effective_weight, explicit?)]
    luna_by_code = defaultdict(list)
    for lineno, word, code, ws in luna:
        if ws.endswith('%'):
            try:
                val = float(ws[:-1]) / 100.0 * 1000.0
            except ValueError:
                val = 0.0
            src = 'pct'
        elif ws:
            try:
                val = float(ws)
            except ValueError:
                val = 0.0
            src = 'explicit'
        else:
            val = essay.get(word, 0.0)
            src = 'essay' if word in essay else 'none'
        luna_by_code[code].append((word, val, src, lineno))

    myd_by_code = defaultdict(list)
    for lineno, word, code, ws in myd:
        try:
            val = float(ws)
        except ValueError:
            val = 0.0
        myd_by_code[code].append((word, val, lineno))

    lines = []
    w = lines.append

    codes = [a for a in sys.argv[1:]] or sorted(myd_by_code)
    only = set(sys.argv[1:])

    w('== luna_pinyin 有效权重（essay.txt 补全） vs mydomain，同编码排序 ==')
    w('   说明：mydomain 走 mydomain_terms(initial_quality 0.5) / mydomain_abbrev(0.6)；')
    w('         luna_pinyin 走 script_translator(initial_quality 0) + use_preset_vocabulary。')
    w('')
    hits = 0
    for code in codes:
        if code not in luna_by_code:
            continue
        if not only and code not in myd_by_code:
            continue
        hits += 1
        lv = sorted(luna_by_code[code], key=lambda t: -t[1])
        mv = sorted(myd_by_code.get(code, []), key=lambda t: -t[1])
        w('[%s]' % code)
        w('   luna  (%d 条): %s' % (len(lv), ', '.join(
            '%s(%g/%s)' % (a, b, c) for a, b, c, _ in lv[:6])))
        w('   mydom (%d 条): %s' % (len(mv), ', '.join(
            '%s(%g)@%d' % (a, b, c) for a, b, c in mv[:6]) if mv else '   mydom (0 条)'))
    w('')
    w('共比对 %d 个编码' % hits)
    w('')
    w('== essay.txt 里这些编码最高权重的 luna 词条（全部撞车编码） ==')
    rows = []
    for code in sorted(myd_by_code):
        if code not in luna_by_code:
            continue
        best_l = max(luna_by_code[code], key=lambda t: t[1])
        best_m = max(myd_by_code[code], key=lambda t: t[1])
        rows.append((best_m[1] - best_l[1], code, best_m, best_l))
    rows.sort(key=lambda t: t[0])
    for d, code, bm, bl in rows[:25]:
        w('  Δ=%+9.1f  [%s]  mydomain %s(%g)  vs  luna %s(%g, %s)'
          % (d, code, bm[0], bm[1], bl[0], bl[1], bl[2]))
    w('  ...')
    for d, code, bm, bl in rows[-10:]:
        w('  Δ=%+9.1f  [%s]  mydomain %s(%g)  vs  luna %s(%g, %s)'
          % (d, code, bm[0], bm[1], bl[0], bl[1], bl[2]))

    text = '\n'.join(lines)
    with open(OUT, 'w', encoding='utf-8') as f:
        f.write(text + '\n')
    print(text if only else '[written] %s (%d lines)' % (OUT, len(lines)))


if __name__ == '__main__':
    sys.exit(main())
