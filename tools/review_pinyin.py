#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""审查脚本（只读）：用 luna_pinyin 单字读音作 oracle，逐条核对 mydomain 拼音编码。

用法:
    C:\\Python314\\python.exe tools/review_pinyin.py

产出: tools/_review_pinyin.txt
"""
import os
import re
import sys
from collections import defaultdict

from _console import ensure_utf8_stdout

ensure_utf8_stdout()

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LUNA = os.path.join(ROOT, 'app/src/main/assets/shared/luna_pinyin.dict.yaml')
MYD = os.path.join(ROOT, 'app/data/rime/myvocab/mydomain.dict.yaml')
OUT = os.path.join(ROOT, 'tools/_review_pinyin.txt')

CJK = re.compile(r'[\u3400-\u9fff\u3007]')


def is_cjk(s):
    return bool(CJK.fullmatch(s))


def parse_luna():
    """返回 char -> {pinyin: weight}。只看单字条目。"""
    table = defaultdict(dict)
    with open(LUNA, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not line or line.startswith('#') or line.startswith('---'):
                continue
            parts = line.split('\t')
            if len(parts) < 2:
                continue
            word = parts[0].strip()
            if len(word) != 1 or not is_cjk(word):
                continue
            code = parts[1].strip()
            if ' ' in code:
                continue
            w = 0.0
            if len(parts) >= 3:
                ws = parts[2].strip()
                try:
                    if ws.endswith('%'):
                        w = float(ws[:-1]) / 100.0
                    else:
                        w = float(ws)
                except ValueError:
                    w = 0.0
            if code not in table[word] or w > table[word][code]:
                table[word][code] = w
    return table


def parse_mydomain():
    """返回 [(lineno, word, code, weight)]。"""
    out = []
    with open(MYD, encoding='utf-8') as f:
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


def main():
    luna = parse_luna()
    myd = parse_mydomain()
    lines = []
    A = []   # 音节不属于该字任何已知读音
    B = []   # 音节合法但不是最高频读音（多音字存疑）
    C = []   # 音节数 != 字数
    EN = []  # 英文/拉丁词条编码规范
    MISS = []  # 字在 luna 里查不到

    for lineno, word, code, weight in myd:
        syls = code.split()
        if not any(is_cjk(ch) for ch in word):
            # 拉丁词条：约定 = 首字母小写串（去掉非字母数字）
            norm = re.sub(r'[^0-9a-zA-Z]', '', word).lower()
            if code.replace(' ', '') != norm:
                EN.append((lineno, word, code, weight, norm))
            continue
        if len(syls) != len(word):
            C.append((lineno, word, code, weight, len(syls), len(word)))
            continue
        for ch, syl in zip(word, syls):
            readings = luna.get(ch)
            if not readings:
                MISS.append((lineno, word, code, weight, ch, syl))
                continue
            if syl not in readings:
                A.append((lineno, word, code, weight, ch, syl, sorted(readings.items(), key=lambda x: -x[1])[:4]))
            else:
                best = max(readings.items(), key=lambda x: x[1])
                if readings[syl] < best[1]:
                    B.append((lineno, word, code, weight, ch, syl, readings[syl], best[0], best[1]))

    def w(s):
        lines.append(s)

    w('== A. 音节不属于该字任何已知读音（确定错误）== %d 条' % len(A))
    for lineno, word, code, weight, ch, syl, top in A:
        w('  mydomain.dict.yaml:%d  %s\t%s\t%s   字「%s」用了 %s，luna 收录读音: %s'
          % (lineno, word, code, weight, ch, syl, ', '.join('%s(%g)' % t for t in top)))
    w('')
    w('== B. 音节合法但非最高频读音（多音字存疑）== %d 条' % len(B))
    for lineno, word, code, weight, ch, syl, sw, bw, bv in B:
        w('  mydomain.dict.yaml:%d  %s\t%s\t%s   字「%s」用 %s(%g)，最高频是 %s(%g)'
          % (lineno, word, code, weight, ch, syl, sw, bw, bv))
    w('')
    w('== C. 音节数 != 字数（编码与词长不符）== %d 条' % len(C))
    for lineno, word, code, weight, ns, nw in C:
        w('  mydomain.dict.yaml:%d  %s\t%s\t%s   %d 音节 / %d 字'
          % (lineno, word, code, weight, ns, nw))
    w('')
    w('== D. 拉丁词条编码不符合「首字母小写串」约定 == %d 条' % len(EN))
    for lineno, word, code, weight, norm in EN:
        w('  mydomain.dict.yaml:%d  %s\t%s\t%s   约定应为 %s'
          % (lineno, word, code, weight, norm))
    w('')
    w('== E. 汉字在 luna_pinyin 里查不到读音 == %d 条' % len(MISS))
    for lineno, word, code, weight, ch, syl in MISS:
        w('  mydomain.dict.yaml:%d  %s\t%s\t%s   字「%s」音节 %s'
          % (lineno, word, code, weight, ch, syl))
    w('')
    w('== 统计 ==')
    w('  mydomain 条目 %d；luna 单字读音表 %d 字' % (len(myd), len(luna)))

    text = '\n'.join(lines)
    with open(OUT, 'w', encoding='utf-8') as f:
        f.write(text + '\n')
    print(text)
    print('\n[written] %s' % OUT)


if __name__ == '__main__':
    sys.exit(main())
