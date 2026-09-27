#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
关键词联想检查：验证「打 X 能出什么候选」。

用于确认 keywords.tsv 合并后，联想词是否真的可达，
以及排序是否符合直觉（主词不能被联想词挤走）。

用法: python3 tools/check_keywords.py app/data/rime/myvocab/mydomain.dict.yaml
"""

import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_dict import parse_dict  # noqa: E402

# (编码, 说明, 期望首选应包含的词)
CASES = [
    ('youxiang', '打 youxiang（邮箱）', ['邮箱']),
    ('gmail', '打 gmail', ['gmail.com']),
    ('qqcom', '打 qqcom', ['qq.com']),
    ('163com', '打 163com', ['163.com']),
    ('outlook', '打 outlook', ['outlook.com']),
    ('hotmail', '打 hotmail', ['hotmail.com']),
    ('icloud', '打 icloud', ['icloud.com']),
    ('atqq', '打 atqq（@ 快捷）', ['@qq.com']),
    ('atgmail', '打 atgmail', ['@gmail.com']),
    ('github', '打 github', ['GitHub', 'github.com']),
    ('clash', '打 clash', ['Clash', 'clash.yaml']),
    ('dingyue', '打 dingyue（订阅）', ['订阅', '订阅链接']),
    ('bt', '打 bt（简拼）', ['崩铁']),
    ('xt', '打 xt（简拼）', ['星铁']),
]


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    path = sys.argv[1]

    meta, entries = parse_dict(path)
    by_code = collections.defaultdict(list)
    abbrev_map = collections.defaultdict(list)
    for word, py, weight, lineno in entries:
        if py:
            by_code[py.replace(' ', '')].append((word, float(weight)))
            # 简拼：多字词按音节首字母推导（单音节词的首字母就是它自己）
            ab = ''.join(s[0] for s in py.split() if s)
            if ab:
                abbrev_map[ab].append((word, float(weight)))

    def lookup(code):
        """候选 = 显式编码 ∪ 简拼推导，按权重合并去重。"""
        merged = {}
        for w, t in by_code.get(code, []):
            merged[w] = max(merged.get(w, 0), t)
        for w, t in abbrev_map.get(code, []):
            merged[w] = max(merged.get(w, 0), t)
        return sorted(merged.items(), key=lambda x: -x[1])

    print('关键词联想检查: %s' % path)
    print('=' * 66)

    problems = []
    for code, label, expect in CASES:
        rows = lookup(code)
        if not rows:
            problems.append('%s: 编码 %r 无任何候选' % (label, code))
            print('  ✗ %-26s 无候选' % label)
            continue

        top = rows[:4]
        # 检查期望词是否落在前 4 个候选里
        names = [w for w, _ in top]
        hit = any(e in names for e in expect)
        # 期望词是否首选
        first_ok = expect[0] in names[:1]
        mark = '✓' if hit else '✗'
        if not hit:
            problems.append('%s: 期望 %s 未出现在前 4 候选中（实际 %s）'
                            % (label, '/'.join(expect), ', '.join(names)))
        print('  %s %-26s %s' % (mark, label,
                                 '  '.join('%s(%g)' % (w, t) for w, t in top)))

    print('=' * 66)
    if problems:
        print('发现 %d 个问题：\n' % len(problems))
        for i, p in enumerate(problems, 1):
            print('   %d. %s' % (i, p))
        return 1
    print('全部通过 ✓  关键词联想可达')
    return 0


if __name__ == '__main__':
    sys.exit(main())
