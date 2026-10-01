#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 panels.yaml 里的三个面板同步进 trime.yaml 的 preset_keyboards 段落。

为什么需要这个脚本？
    Trime 的 YAML **不支持跨文件 include 键盘**（`__include` 只对
    config 节点的 patch 生效，preset_keyboards 是普通节点，直接 include
    不会展开）。所以面板定义只能物理地待在 trime.yaml 里。

    但把 300 行符号全塞在 trime.yaml 里会让主布局文件难以阅读。
    折中方案：面板定义写在 panels.yaml（可读、可 diff），
    用本脚本生成 trime.yaml 里对应的段落。

用法:
    python3 tools/sync_panels.py                 # 同步
    python3 tools/sync_panels.py --check         # 只检查是否已同步（CI 用）
"""

import os
import re
import sys

from _console import ensure_utf8_stdout

ensure_utf8_stdout()

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TRIME = os.path.join(ROOT, 'app/src/main/assets/shared/trime.yaml')
PANELS = os.path.join(ROOT, 'app/src/main/assets/shared/panels.yaml')

# panels.yaml 里的顶层键 → trime.yaml 里的键盘名与显示属性
PANEL_MAP = {
    'symbols_panel': {
        'name': 'symbols',
        'label': '符号',
        'ascii_mode': 1,
    },
    'number_panel': {
        'name': 'number',
        'label': '数字',
        'ascii_mode': 1,
    },
    'emoji_panel': {
        'name': 'emoji',
        'label': '表情',
        'ascii_mode': 1,
    },
}

HEADER = """    # =========================================================================
    #  {label}面板 —— 由 tools/sync_panels.py 从 panels.yaml 生成
    #  ⚠ 不要直接改这里；改 panels.yaml 后跑：python3 tools/sync_panels.py
    # ========================================================================="""


def read_panels():
    """从 panels.yaml 提取 {面板键: [(类型, 内容), ...]}

    类型 'comment' → 面板内注释；'key' → 按键定义，返回原始字符串。
    """
    with open(PANELS, encoding='utf-8') as f:
        lines = f.read().splitlines()

    panels, cur = {}, None
    for line in lines:
        m = re.match(r'^([a-z_]+):\s*$', line)
        if m:
            cur = m.group(1)
            panels[cur] = []
            continue
        if cur is None:
            continue
        s = line.strip()
        if not s:
            continue
        if s.startswith('#'):
            panels[cur].append(('comment', s))
        elif s.startswith('- {') or s.startswith('- '):
            panels[cur].append(('key', s))
        # 其他内容（面板外的说明文字）忽略
    return panels


def render_panel(key, items):
    """渲染成一个完整的 preset_keyboards 子节点（键盘名 2 空格缩进）。"""
    meta = PANEL_MAP[key]
    out = [HEADER.format(label=meta['label'])]
    out.append('  %s:' % meta['name'])
    out.append('    name: %s' % meta['label'])
    out.append('    author: myime')
    out.append('    ascii_mode: %d' % meta['ascii_mode'])
    out.append('    columns: -1               # -1 = 不按键数截断，只按权重(100)换行')
    out.append('    width: 10')
    out.append('    height: 52')
    out.append('    keys:')
    for kind, content in items:
        if kind == 'comment':
            out.append('    ' + content)
        else:
            out.append('    ' + content)
    return '\n'.join(out)


def split_trime(text):
    """把 trime.yaml 切成 (前段, 面板段, 后段)。"""
    lines = text.splitlines()
    start = end = None
    for i, l in enumerate(lines):
        if l.startswith('preset_keyboards:'):
            start = i
            continue
        if start is not None and l and not l[0].isspace():
            end = i
            break
    if start is None:
        raise SystemExit('trime.yaml 里找不到 preset_keyboards:')
    if end is None:
        end = len(lines)
    return lines[:start], lines[start:end], lines[end:]


def main():
    check_only = '--check' in sys.argv

    panels = read_panels()
    missing = [k for k in PANEL_MAP if k not in panels]
    if missing:
        raise SystemExit('panels.yaml 缺少面板: %s' % ', '.join(missing))

    with open(TRIME, encoding='utf-8') as f:
        text = f.read()

    head, body, tail = split_trime(text)

    # 找出 preset_keyboards 段里已有的面板键盘（连同其生成注释一起删掉），
    # 保留 my_pinyin / my_english 及任意自定义键盘。
    panel_names = {m['name'] for m in PANEL_MAP.values()}
    keep, i = [], 0
    while i < len(body):
        line = body[i]

        # 生成注释块：跳过开头的 ==== 行，往后找 3 行内是否含生成标记
        if line.strip().startswith('# ===='):
            window = '\n'.join(body[i:i + 4])
            if 'sync_panels.py 从 panels.yaml' in window:
                # 吞掉整个注释块（到下一个 ==== 为止）+ 空行 + 键盘定义
                i += 1
                while i < len(body) and '=====' not in body[i]:
                    i += 1
                i += 1  # 跳过收尾的 ==== 行
                while i < len(body) and not body[i].strip():
                    i += 1
                if i < len(body):
                    m = re.match(r'^  ([a-z_0-9]+):\s*$', body[i])
                    if m and m.group(1) in panel_names:
                        i += 1
                        while i < len(body):
                            nxt = body[i]
                            if re.match(r'^  [a-z_0-9]+:\s*$', nxt) or (nxt and not nxt[0].isspace()):
                                break
                            i += 1
                continue

        # 没有生成注释的旧面板键盘：也一并替换掉
        m = re.match(r'^  ([a-z_0-9]+):\s*$', line)
        if m and m.group(1) in panel_names:
            i += 1
            while i < len(body):
                nxt = body[i]
                if re.match(r'^  [a-z_0-9]+:\s*$', nxt) or (nxt and not nxt[0].isspace()):
                    break
                i += 1
            continue

        keep.append(line)
        i += 1

    while keep and not keep[-1].strip():
        keep.pop()

    new_body = keep + ['']
    for key in PANEL_MAP:
        new_body.append('')
        new_body.append(render_panel(key, panels[key]))

    new_text = '\n'.join(head + new_body + tail).rstrip() + '\n'

    if check_only:
        if new_text == text:
            print('已同步 ✓  trime.yaml 与 panels.yaml 一致')
            return 0
        print('未同步 ✗  请运行: python3 tools/sync_panels.py')
        return 1

    with open(TRIME, 'w', encoding='utf-8') as f:
        f.write(new_text)

    total = sum(len(v) for v in panels.values())
    print('已同步 ✓  3 个面板，共 %d 行' % total)
    return 0


if __name__ == '__main__':
    sys.exit(main())
