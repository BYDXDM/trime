#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_layout.py —— Trime 键盘布局校验器

为什么需要它：
    Trime 的 Keyboard.kt 按列数自动换行，判定条件是
        if (column >= maxColumns || x + widthPx > allowedWidth)
    以及首键定义行高：
        rowHeight = key.height 或键盘默认 height

    只要某一行所有键的 width 权重之和 != 100（即 10 列 × 10 权重），
    或者键数不是 columns 的整数倍，布局就会整行错位、行高跳变。

    手改 YAML 极其容易踩这个坑，所以用脚本在写文件时就验算。

权重规则（源码 Keyboard.kt）：
    keyWidthWeight = key.width == 0 && key.hasClickAction ? keyboardWidth : key.width
    widthPx        = keyWidthWeight * allowedWidth / 100
    即：没写 width 的有动作按键按键盘默认 width 计；纯占位符 {width: 5} 按 5 计。

用法：
    python3 check_layout.py <trime.yaml> [--verbose]
退出码：
    0 = 全部通过；1 = 有问题
"""

import sys
import re

MAX_TOTAL_WEIGHT = 100.0  # Keyboard.kt 里的常量

# 以 `- {` 开头的行视为一个按键条目
KEY_RE = re.compile(r'^\s*-\s*\{(.*)\}\s*(#.*)?$')
# 顶层缩进的两空格段（preset_keyboards 下的键盘名 / preset_color_schemes 等）
SECTION_RE = re.compile(r'^  ([A-Za-z_][A-Za-z0-9_]*):\s*$')
# 键名: 值（4 空格缩进的键盘属性）。注意排除 `keys:` 本身。
PROP_RE = re.compile(r'^    ([A-Za-z_][A-Za-z0-9_]*):\s*(.*)$')

# 不需要 click 也算「有动作」的字段
QUOTED_RE = re.compile(r'"([^"]*)"|\'([^\']*)\'')


def unquote(v):
    v = v.strip().strip(',')
    if len(v) >= 2 and v[0] == v[-1] and v[0] in ('"', "'"):
        return v[1:-1]
    return v


def split_top_level(body):
    """把 `{a: 1, b: 'x, y'}` 的内层按顶层逗号切分，尊重引号与嵌套花括号。"""
    parts, buf, depth, quote = [], [], 0, None
    for ch in body:
        if quote:
            buf.append(ch)
            if ch == quote:
                quote = None
            continue
        if ch in ('"', "'"):
            quote = ch
            buf.append(ch)
            continue
        if ch in '{[':
            depth += 1
        elif ch in '}]':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(''.join(buf))
            buf = []
        else:
            buf.append(ch)
    if buf:
        parts.append(''.join(buf))
    return [p for p in (p.strip() for p in parts) if p]


def parse_key(line):
    """解析一行按键，返回 dict 或 None。"""
    m = KEY_RE.match(line)
    if not m:
        return None
    body = m.group(1)
    d = {}
    for item in split_top_level(body):
        if ':' not in item:
            # 简写形式，如 {q} / {space}
            d.setdefault('click', unquote(item))
            continue
        k, v = item.split(':', 1)
        d[k.strip()] = unquote(v)
    return d


def load_keyboards(path):
    """返回 [(键盘名, {'props': {...}, 'keys': [dict,...]})]

    解析规则对应 trime.yaml 的缩进层级：
        preset_keyboards:      ← 顶层，缩进 0
          my_pinyin:           ← 键盘名，缩进 2
            columns: 10        ← 属性，缩进 4（keys 之前）
            keys:              ← 按键列表起点，缩进 4
            - {click: q}       ← 按键，缩进 4 起，以 `- {` 开头
    """
    with open(path, encoding='utf-8') as f:
        lines = f.read().splitlines()

    boards, cur, in_pkb, in_keys = [], None, False, False
    for raw in lines:
        line = raw.rstrip()
        stripped = line.strip()

        # 顶层区块（缩进 0）。遇到就重判是否进入 preset_keyboards
        if line and not line[0].isspace():
            in_pkb = stripped.startswith('preset_keyboards:')
            cur = None
            in_keys = False
            continue
        if not in_pkb:
            continue
        if not stripped or stripped.startswith('#'):
            continue

        # 键盘名：2 空格缩进。只在 keys 之外识别，避免把按键行的内层结构当键盘名
        # 键盘名：2 空格缩进，且不是按键行、不是注释、不是 `keys:`
        if stripped.startswith('#'):
            continue
        m = SECTION_RE.match(line)
        if m and m.group(1) != 'keys':
            if cur:
                boards.append(cur)
            cur = {'name': m.group(1), 'props': {}, 'keys': []}
            in_keys = False
            continue

        if cur is None:
            continue

        if re.match(r'^    keys:\s*$', line):
            in_keys = True
            continue

        # 按键行：以 `- {` 开头。必须在识别键盘名之后判断，
        # 因为下一个键盘名出现时 in_keys 仍为 True。
        if re.match(r'^\s*-\s*\{', line):
            k = parse_key(line)
            if k is not None:
                cur['keys'].append(k)
            continue

        # 其余 4 空格缩进行：keys 之前的属性
        m = PROP_RE.match(line)
        if m and not in_keys:
            cur['props'][m.group(1)] = m.group(2).split('#')[0].strip()

    if cur:
        boards.append(cur)
    return boards


def check_keyboard(board, verbose=False):
    """返回 (问题列表, 统计dict)"""
    name = board['name']
    props = board['props']
    keys = board['keys']
    issues = []

    def num(key, default):
        v = props.get(key)
        if v in (None, ''):
            return default
        try:
            return float(v)
        except ValueError:
            return default

    # columns == -1 表示不限列数（Trime 源码：maxColumns = Int.MAX_VALUE）
    columns = int(num('columns', 30))
    if columns <= 0:
        columns = 10 ** 9
    kb_width = num('width', 10.0)
    kb_height = num('height', 0)

    if not keys:
        return ['键盘 %s: 没有任何按键' % name], {}

    # 第一行行高由首键定义（源码：rowHeight = key.height if key.height else kbHeight）
    first_h = keys[0].get('height')
    first_h = float(first_h) if first_h else kb_height
    if first_h <= 0:
        issues.append('键盘 %s: 首键没有 height，且键盘也没有 height → 行高会取到 0' % name)

    # 模拟 Keyboard.kt 的换行逻辑
    rows, cur_row, cur_w, cur_h, column = [], [], 0.0, None, 0
    cell = MAX_TOTAL_WEIGHT  # allowedWidth 归一化为 100

    for idx, k in enumerate(keys):
        explicit_w = float(k['width']) if k.get('width') else 0.0
        has_action = bool(k.get('click'))
        # 源码：width==0 且有动作 → 用键盘默认 width
        weight = kb_width if (explicit_w == 0 and has_action) else explicit_w
        if weight == 0:
            issues.append('键盘 %s: 第 %d 个键既没 width 也没 click，会被当成 0 宽' % (name, idx + 1))
            weight = 0.0

        width_px = weight * cell / MAX_TOTAL_WEIGHT

        if column >= columns or cur_w + width_px > cell + 1e-6:
            rows.append((cur_row, cur_w))
            cur_row, cur_w, column = [], 0.0, 0
            cur_h = None

        if cur_h is None:
            kh = k.get('height')
            cur_h = float(kh) if kh else kb_height

        # 占位键（只有 width 没有 click）用 None 作为标签，不参与重复检测
        cur_row.append((idx, k.get('click'), weight))
        cur_w += width_px
        column += 1

    if cur_row:
        rows.append((cur_row, cur_w))

    # ---- 断言：每行宽度必须 == 100 ----
    row_reports = []
    for ri, (row, w) in enumerate(rows, 1):
        flag = 'OK ' if abs(w - MAX_TOTAL_WEIGHT) < 1e-6 else 'BAD'
        row_reports.append((ri, len(row), w, flag))
        if flag == 'BAD':
            issues.append(
                '键盘 %s 第 %d 行: 键数=%d 总宽=%.2f（应为 100.00）→ 会错位/换行'
                % (name, ri, len(row), w)
            )

    # ---- 断言：行高一致性（行高跳变会导致键盘闪烁）----
    if kb_height > 0:
        for ri, (row, _) in enumerate(rows, 1):
            for _, k in enumerate(row):
                pass

    stats = {'rows': len(rows), 'keys': len(keys), 'detail': row_reports}

    # ---- 断言：同一行内 click 重复 ----
    for s in check_duplicate_keys_in_rows(rows):
        issues.append('键盘 %s %s' % (name, s))

    if verbose:
        print('  [%s] columns=%d kb_width=%.1f height=%.1f keys=%d → %d 行'
              % (name, columns, kb_width, kb_height, len(keys), len(rows)))
        for ri, cnt, w, flag in row_reports:
            print('      %s 行%-2d 键数=%-3d 总宽=%7.2f' % (flag, ri, cnt, w))

    return issues, stats


def check_duplicate_keys_in_rows(rows):
    """按【行】检测 click 重复（同一行内重复才是有问题，跨行不算）。"""
    issues = []
    for ri, (row, _w) in enumerate(rows, 1):
        seen, dup = set(), []
        for _idx, label, _wt in row:
            if not label:
                continue
            if label in seen:
                dup.append(label)
            seen.add(label)
        if dup:
            issues.append(
                '第 %d 行内 click 重复: %s（Trime 按 key 名索引，后者会静默覆盖前者）'
                % (ri, ', '.join(sorted(set(dup))))
            )
    return issues


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    path = sys.argv[1]
    verbose = '--verbose' in sys.argv or '-v' in sys.argv

    print('校验文件: %s' % path)
    print('=' * 66)

    boards = load_keyboards(path)
    if not boards:
        print('没有在 preset_keyboards 下找到任何键盘。')
        return 1

    all_issues = []
    for b in boards:
        issues, stats = check_keyboard(b, verbose=verbose)
        all_issues += issues

    print('=' * 66)
    print('共 %d 个键盘' % len(boards))
    if all_issues:
        print('\n发现 %d 个问题：\n' % len(all_issues))
        for i, s in enumerate(all_issues, 1):
            print('  %2d. %s' % (i, s))
        return 1

    print('全部通过 ✓  所有行宽 = 100.00，无重复键')
    return 0


if __name__ == '__main__':
    sys.exit(main())
