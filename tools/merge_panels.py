# -*- coding: utf-8 -*-
"""一次性迁移：把每个分组的 p1/p2 两页合并成一个可滚动长面板。

合并规则：
  行 1-4  = p1 原有 4 行（4 符号 + 右侧功能键）
  行 5-8  = p2 的 16 个符号，每行 4 个，第 5 位循环放 ∧/∨/123/⌫
  行 9    = 页签行（沿用 p1）
合并后每面板 9 行 = 视口 5 行 + 可滚动 4 行。
"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PANELS = os.path.join(ROOT, 'app/src/main/assets/shared/panels.yaml')

GROUPS = [
    ('symbols', '常用'),
    ('symbols_cn', '中文'),
    ('symbols_en', '英文'),
    ('symbols_net', '网络'),
    ('symbols_math', '数学'),
    ('symbols_arrow', '箭头'),
    ('symbols_num', '序号'),
]

FUNC_CLICKS = ('SymbolsPageUp', 'SymbolsPageDown', 'Keyboard_number', 'BackSpace')
FUNC_KEYS = [
    "- { click: SymbolsPageUp, label: '∧', key_text_size: 24, width: 20 }",
    "- { click: SymbolsPageDown, label: '∨', key_text_size: 24, width: 20 }",
    "- { click: Keyboard_number, label: '123', width: 20 }",
    "- { click: BackSpace, label: '⌫', width: 20 }",
]


def read_raw():
    with open(PANELS, encoding='utf-8') as f:
        return f.read()


def split_panels(text):
    """返回 (文件头注释列表, [(面板键, [行...])])"""
    head, panels, cur = [], [], None
    for line in text.splitlines():
        m = re.match(r'^([a-z_0-9]+):\s*$', line)
        if m:
            cur = m.group(1)
            panels.append((cur, []))
            continue
        if cur is None:
            head.append(line)
            continue
        panels[-1][1].append(line)
    return head, panels


def key_lines(lines):
    out = []
    for line in lines:
        s = line.strip()
        if s.startswith('- {') or s.startswith('- '):
            out.append(s)
    return out


def is_func(line):
    return any(c in line for c in FUNC_CLICKS)


def build():
    head, panels = split_panels(read_raw())
    data = {k: key_lines(v) for k, v in panels}
    for k in data:
        want = 20 if k == 'number_panel' else 28
        assert len(data[k]) == want, '%s 键数 %d != %d' % (k, len(data[k]), want)

    out = []
    out.append('# =============================================================================')
    out.append('#  myime 符号面板 / 数字面板')
    out.append('#  这些键盘由 tools/sync_panels.py 同步进 trime.yaml 的 preset_keyboards 段落')
    out.append('#  （Trime 的 YAML 不支持跨文件 include 键盘，面板定义只能物理地待在')
    out.append('#    trime.yaml 里。本文件是权威版本，改这里后跑 tools/sync_panels.py）')
    out.append('#')
    out.append('#  设计约束（务必遵守，否则会错位）：')
    out.append('#    columns: -1      → 不按键数截断，只按权重换行')
    out.append('#    每行权重和 = 100')
    out.append('#')
    out.append('#  2026-10-25 改为可滚动长面板（scrollable: true）：')
    out.append('#    · 每个分组 = 原第 1 页 4 行 + 原第 2 页 4 行 + 页签行 = 9 行')
    out.append('#    · 视口固定 5 行（Keyboard.VIEWPORT_ROWS），多出的 4 行靠手指上下滑查看')
    out.append('#    · 右侧功能列（∧/∨/123/⌫）纵向贯穿，照搜狗')
    out.append('#    · ↑/↓ 仍是上/下一个分组（循环），页签行仍在最底')
    out.append('# -----------------------------------------------------------------------------')

    for base, label in GROUPS:
        p1 = data[base + '_panel']
        p2 = data[base + '_p2_panel']
        rows1 = [p1[i * 5:(i + 1) * 5] for i in range(4)]
        sym2 = [k for k in p2[:20] if not is_func(k)]
        assert len(sym2) == 16, '%s p2 符号数 %d' % (base, len(sym2))

        out.append('')
        out.append('# %s（9 行可滚动：原第 1 页 4 行 + 原第 2 页 4 行 + 页签）' % label)
        out.append('%s_panel:' % base)
        for i, row in enumerate(rows1):
            out.append('')
            out.append('  # ---- 第 %d 行 ----' % (i + 1))
            for k in row:
                out.append('  ' + k)
        for i in range(4):
            out.append('')
            out.append('  # ---- 第 %d 行 ----' % (i + 5))
            for k in sym2[i * 4:(i + 1) * 4]:
                out.append('  ' + k)
            out.append('  ' + FUNC_KEYS[i])
        out.append('')
        out.append('  # ---- 分组页签 ----')
        for k in p1[20:]:
            out.append('  ' + k)

    out.append('')
    out.append('# -----------------------------------------------------------------------------')
    out.append('#  数字面板（照搜狗布局：左 %/− 右 ⌫/./@，中 3×4 数字）')
    out.append('#  4 行正好一屏，不滚动')
    out.append('# -----------------------------------------------------------------------------')
    out.append('number_panel:')
    for i in range(4):
        out.append('')
        out.append('  # ---- 第 %d 行 ----' % (i + 1))
        for k in data['number_panel'][i * 5:(i + 1) * 5]:
            out.append('  ' + k)
    out.append('')
    return '\n'.join(out) + '\n'


if __name__ == '__main__':
    text = build()
    with open(PANELS, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('written, lines =', len(text.splitlines()))
