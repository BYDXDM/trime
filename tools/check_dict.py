#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校验 Rime 词库文件：格式 / 重复词条 / 编码合法性 / 简拼可达性。

用法:
    python3 tools/check_dict.py app/data/rime/myvocab/mydomain.dict.yaml

检查项:
    1. 头部 --- name/version/sort ... --- 结构完整
    2. 每行必须是  词语<TAB>拼音[<TAB>权重]  三段
    3. 拼音只能是字母+空格+单引号（Rime 编码字符集）
    4. 同一词语重复出现（后者会盖前者）
    5. 简拼可达性：算首字母串，指出哪些词能被打出来
    6. 权重是否在合理区间
"""
import os
import sys
import re
import collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _console import ensure_utf8_stdout  # noqa: E402

ensure_utf8_stdout()

# Rime 编码字符集：字母（含 ü）、空格、单引号；英文品牌名词允许数字（v2ray/k8s）
PINYIN_OK = re.compile(r"^[a-z\u00fc0-9'\s]+$")
WEIGHT_WARN_LOW = 1
WEIGHT_WARN_HIGH = 99999


def parse_dict(path):
    """返回 (meta, entries)。entries = [(word, pinyin, weight, lineno)]"""
    meta = {}
    entries = []
    state = 'head'
    with open(path, encoding='utf-8') as f:
        for lineno, raw in enumerate(f, 1):
            line = raw.rstrip('\n')
            if line.strip() == '---' and state == 'head':
                state = 'meta'
                continue
            if line.strip() == '...' and state == 'meta':
                state = 'body'
                continue
            if state == 'meta':
                if not line.strip() or line.lstrip().startswith('#'):
                    continue
                m = re.match(r'^([A-Za-z_]+):\s*(.*)$', line)
                if m:
                    meta[m.group(1)] = m.group(2).strip()
                continue
            if state != 'body':
                continue
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) == 2:
                word, py = parts
                weight = ''
            elif len(parts) >= 3:
                word, py, weight = parts[0], parts[1], parts[2]
            else:
                entries.append((line, None, None, lineno))
                continue
            entries.append((word, py, weight, lineno))
    return meta, entries


def abbrev_of(pinyin):
    """全拼 → 首字母串。'beng tie' → 'bt'"""
    return ''.join(s[0] for s in pinyin.split() if s)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    path = sys.argv[1]

    print('校验词库: %s' % path)
    print('=' * 66)

    meta, entries = parse_dict(path)

    # ---- 1. 头部结构 ----
    problems = []
    for k in ('name', 'version', 'sort'):
        if k not in meta:
            problems.append('头部缺少字段: %s' % k)
    print('头部: name=%s version=%s sort=%s use_preset_vocabulary=%s'
          % (meta.get('name'), meta.get('version'), meta.get('sort'),
             meta.get('use_preset_vocabulary')))

    # ---- 2/3/6. 逐条校验 ----
    bad_fmt, bad_py, bad_weight = [], [], []
    for word, py, weight, lineno in entries:
        if py is None or py == '':
            bad_fmt.append((lineno, word))
            continue
        if not PINYIN_OK.match(py):
            bad_py.append((lineno, word, py))
        if weight != '':
            try:
                w = int(weight)
                if w < WEIGHT_WARN_LOW or w > WEIGHT_WARN_HIGH:
                    bad_weight.append((lineno, word, w))
            except ValueError:
                bad_fmt.append((lineno, word))
    if bad_fmt:
        problems.append('格式错误 %d 条（应为 词语TAB拼音TAB权重）' % len(bad_fmt))
        for lineno, w in bad_fmt[:8]:
            print('    行%-5d %r' % (lineno, w))
    if bad_py:
        problems.append('拼音含非法字符 %d 条' % len(bad_py))
        for lineno, w, py in bad_py[:8]:
            print('    行%-5d %s → %r' % (lineno, w, py))
    if bad_weight:
        problems.append('权重超出建议区间 %d 条' % len(bad_weight))

    # ---- 4. 重复词条 ----
    # 注意：同一个词可以有多条不同编码（全拼 + 简拼别名），这是合法的。
    # 真正的错误是「完全相同的 (词, 编码) 对」出现两次，后者会静默覆盖前者。
    words = [e[0] for e in entries]
    pairs = collections.Counter((e[0], e[1]) for e in entries if e[1])
    dup2 = [p for p, c in pairs.items() if c > 1]
    if dup2:
        problems.append('完全重复的 (词,编码) %d 组（后者会覆盖前者）: %s'
                        % (len(dup2), ', '.join('%s/%s' % p for p in dup2[:8])))

    # ---- 5. 简拼可达性 ----
    abbrev_map = collections.defaultdict(list)
    for word, py, weight, lineno in entries:
        if py:
            abbrev_map[abbrev_of(py)].append(word)

    # 多字词但首字母串太短 → 简拼会撞车
    collide = {k: v for k, v in abbrev_map.items() if len(v) > 1}
    print('\n统计:')
    print('    词条总数      : %d' % len(entries))
    print('    去重后词语数  : %d' % len(set(words)))
    print('    简拼编码数    : %d' % len(abbrev_map))
    print('    简拼撞车编码  : %d 组' % len(collide))

    if collide:
        print('\n    简拼撞车明细（打这几个首字母会出多个候选，按权重排序）:')
        by_weight = sorted(
            entries, key=lambda e: -(int(e[2]) if (e[2] or '').isdigit() else 0))
        rank = {}
        for i, (w, py, wt, ln) in enumerate(by_weight):
            rank.setdefault(w, i)
        for k in sorted(collide, key=lambda x: -len(collide[x]))[:12]:
            items = sorted(collide[k], key=lambda w: rank.get(w, 9999))
            print('      %-8s → %s' % (k, ' > '.join(items[:5])))

    # ---- 关键简拼抽样 ----
    # 查两件事：① 自动首字母串（多字中文词）② 显式别名编码（英文品牌词）
    explicit = collections.defaultdict(list)
    for word, py, weight, lineno in entries:
        if py:
            explicit[py.replace(' ', '')].append(word)

    print('\n    关键简拼抽样（自动首字母 / 显式别名）:')
    for code in ('bt', 'xt', 'ys', 'ba', 'cl', 'cf', 'gh', 'v2', 'yt', 'bh',
                 'ys', 'ql', 'sy', 'gc', 'xt', 'bt'):
        auto = abbrev_map.get(code, [])
        expl = explicit.get(code, [])
        merged = []
        for w in expl + auto:
            if w not in merged:
                merged.append(w)
        print('      %-6s → %s' % (code, ', '.join(merged[:4]) if merged else '(无)'))

    print('\n' + '=' * 66)
    if problems:
        print('发现 %d 个问题：\n' % len(problems))
        for i, s in enumerate(problems, 1):
            print('  %2d. %s' % (i, s))
        return 1
    print('全部通过 ✓')
    return 0


if __name__ == '__main__':
    sys.exit(main())
