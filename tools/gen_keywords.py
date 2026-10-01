#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 keywords.tsv 合并进 mydomain.dict.yaml。

为什么要这个脚本：Rime 的 dict.yaml 是「词<TAB>编码<TAB>权重」的扁平面板，
没有 include 机制。关键词联想（打 youxiang 出 qq.com）靠的正是
「同编码多条记录」——但手动维护两处容易漏，所以用脚本合并。

用法:
    python3 tools/gen_keywords.py            # 合并进 dict.yaml
    python3 tools/gen_keywords.py --check    # 只检查是否已合并（CI 用）
"""

import os
import re
import sys

from _console import ensure_utf8_stdout

ensure_utf8_stdout()

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DICT = os.path.join(ROOT, 'app/data/rime/myvocab/mydomain.dict.yaml')
KW = os.path.join(ROOT, 'app/data/rime/myvocab/keywords.tsv')

START = '# ===== BEGIN keywords（由 tools/gen_keywords.py 生成，勿手改）====='
END = '# ===== END keywords ====='


def read_keywords():
    """解析 keywords.tsv，返回 [(词, 编码, 权重)]。

    `--- 说明 ---` 行会被跳过（当作分组标题），空行和 # 注释也跳过。
    """
    entries = []
    with open(KW, encoding='utf-8') as f:
        for lineno, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line.strip() or line.strip().startswith('#'):
                continue
            # 分组标题：--- xxx ---
            if re.match(r'^\s*---.*---\s*$', line):
                continue
            parts = line.split('\t')
            if len(parts) != 3:
                print('  ⚠ keywords.tsv 第 %d 行格式错误（需要 3 列）: %r' % (lineno, line))
                continue
            word, code, weight = parts
            entries.append((word.strip(), code.strip(), weight.strip()))
    return entries


def render(entries):
    """渲染成 dict.yaml 的正文片段。"""
    lines = [START]
    lines.append('# 关键词联想：同一编码下挂多条关联词，打拼音时可一起看到')
    for word, code, weight in entries:
        lines.append('%s\t%s\t%s' % (word, code, weight))
    lines.append(END)
    return '\n'.join(lines)


def current_block(text):
    """取出 dict.yaml 里已有的生成块（不含标记行）。没有则返回 None。"""
    m = re.search(re.escape(START) + r'(.*?)' + re.escape(END), text, re.S)
    return m.group(1) if m else None


def main():
    check_only = '--check' in sys.argv

    entries = read_keywords()
    if not entries:
        print('keywords.tsv 里没有有效词条')
        return 1

    block = render(entries)
    text = open(DICT, encoding='utf-8').read()

    existing = current_block(text)
    if existing is not None:
        new_text = re.sub(
            re.escape(START) + r'.*?' + re.escape(END),
            block.replace('\\', '\\\\'), text, flags=re.S)
        if new_text == text:
            print('已合并 ✓  %d 条关键词（共 %d 行）' % (len(entries), block.count('\n') + 1))
            return 0
        if check_only:
            print('未合并 ✗  请运行: python3 tools/gen_keywords.py')
            return 1
        open(DICT, 'w', encoding='utf-8').write(new_text)
        print('已更新 ✓  %d 条关键词' % len(entries))
        return 0

    # 首次合并：追加到文件末尾
    if check_only:
        print('未合并 ✗  请运行: python3 tools/gen_keywords.py')
        return 1

    if not text.endswith('\n'):
        text += '\n'
    text += '\n' + block + '\n'
    open(DICT, 'w', encoding='utf-8').write(text)
    print('已合并 ✓  %d 条关键词（新增 %d 行）' % (len(entries), block.count('\n') + 1))
    return 0


if __name__ == '__main__':
    sys.exit(main())
