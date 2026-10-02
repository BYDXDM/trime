#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_key_refs.py —— 键盘引用完整性校验器

为什么需要它：
    trime.yaml 里 click / long_click / swipe_* 的值是一个「名字」，运行时由
    KeyAction 按四级顺序解析（KeyAction.kt init 块）：

        1. preset_keys 里定义的预设名
        2. KeyCode.parse(name)        —— 带修饰键的 "Control+BackSpace"
        3. KeyCode.nameToKeyCode(name) —— Return / BackSpace / space ...
        4. 键盘名（Keyboard_xxx）      —— 切到另一块键盘

    四级全都落空时，KeyCode.parse 返回 (0, 0)、nameToKeyCode 返回
    KEYCODE_UNKNOWN，按键**静默失效**：没有异常、没有日志、没有视觉差异，
    只是点了没反应。手改 YAML 时这类错误极难自己发现，所以用脚本兜住。

    同理检查 preset_keys 自身的 send 字段：preset 存在但 send 拼错时，
    KeyAction 只在 logcat 打一行 Timber.e，用户完全无感。

用法：
    python3 check_key_refs.py <trime.yaml>
退出码：
    0 = 全部通过，1 = 有问题
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _console import ensure_utf8_stdout  # noqa: E402

ensure_utf8_stdout()

KEY_RE = re.compile(r'^\s*-\s*\{(.*)\}\s*(#.*)?$')
SECTION_RE = re.compile(r'^  ([A-Za-z_][A-Za-z0-9_]*):\s*$')
PROP_RE = re.compile(r'^    ([A-Za-z_][A-Za-z0-9_]*):\s*(.*)$')

ACTION_FIELDS = (
    'click', 'long_click', 'swipe_up', 'swipe_down', 'swipe_left', 'swipe_right',
    'composing', 'double_click',
)

MODIFIERS = {'Shift', 'Control', 'Alt', 'Lock'}

# 合法键名的权威来源是 RimeKeyMapping（由 KSP 从 librime 的 key_table 生成）。
# 手抄白名单会随时间漂移，所以这里直接从生成源码里抽表；
# 抽不到时（比如还没跑过一次编译）退回到内置快照，并打印提示。
RIME_MAPPING_PATH = os.path.join(
    'app', 'build', 'generated', 'ksp', 'debug', 'kotlin',
    'com', 'osfans', 'trime', 'core', 'RimeKeyMapping.kt',
)

# 内置快照：抽取失败时的兜底（取自 2026-10-02 的生成产物）
_FALLBACK_KEY_NAMES = {
    'space', 'numbersign', 'apostrophe', 'asterisk', 'plus', 'comma', 'minus',
    'period', 'slash', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    'semicolon', 'equal', 'at', 'bracketleft', 'backslash', 'bracketright',
    'grave', 'Shift_L', 'Shift_R', 'Control_L', 'Control_R', 'Caps_Lock',
    'Meta_L', 'Meta_R', 'Alt_L', 'Alt_R', 'Insert', 'Delete', 'Home', 'End',
    'Page_Down', 'Page_Up', 'Tab', 'BackSpace', 'Return', 'Escape', 'Up',
    'Down', 'Left', 'Right', 'KP_Divide', 'KP_Multiply', 'KP_Subtract',
    'KP_Add', 'KP_Enter', 'KP_Decimal', 'Eisu_toggle', 'Kana_Lock',
    'Hiragana_Katakana', 'Zenkaku_Hankaku', 'VoidSymbol',
    'exclam', 'quotedbl', 'dollar', 'percent', 'ampersand', 'colon', 'less',
    'greater', 'question', 'asciicircum', 'underscore', 'braceleft', 'bar',
    'braceright', 'asciitilde',
} | {'F%d' % i for i in range(1, 13)} | set('ABCDEFGHIJKLMNOPQRSTUVWXYZ')

# Android KeyEvent 键名（KeyCode.nameToKeyCode 的末级兜底）。
#
# 注意：librime 的 key_table 里**没有** SWITCH_CHARSET / LANGUAGE_SWITCH /
# SETTINGS / PROG_RED 这些名字 —— 它们走的是 Android 这一级，
# 由 KeyEvent.keyCodeFromString("KEYCODE_$name") 解析。
# 本 fork 的 CommonKeyboardActionListener.kt:138-143 正是在处理它们，
# 所以 trime.yaml 里这么写是对的。
ANDROID_KEY_NAMES = {
    'SHIFT_LEFT', 'SHIFT_RIGHT', 'CTRL_LEFT', 'CTRL_RIGHT',
    'ALT_LEFT', 'ALT_RIGHT', 'META_LEFT', 'META_RIGHT',
    'SYM', 'FUNCTION', 'MENU', 'SEARCH', 'ENTER', 'DEL', 'BACK',
    'FORWARD', 'CAPS_LOCK', 'NUM_LOCK', 'SCROLL_LOCK',
    # 输入法/系统控制类（上游 trime.yaml 与 tongwenfeng 主题都在用）
    'SWITCH_CHARSET', 'LANGUAGE_SWITCH', 'SETTINGS', 'PROG_RED',
    'STB_POWER', 'STB_SLEEP', 'STB_WAKEUP',
} | {'F%d' % i for i in range(1, 13)}


def load_rime_key_names(repo_root):
    """从 RimeKeyMapping 生成源码里抽出全部合法键名。"""
    path = os.path.join(repo_root, RIME_MAPPING_PATH)
    if not os.path.isfile(path):
        return None
    names = set()
    in_fun = None
    with open(path, encoding='utf-8') as f:
        for line in f:
            if re.search(r'public fun (nameToKeyCode|symbolNameToCode|upperNameToCode|charToCode)\(', line):
                in_fun = True
                continue
            if in_fun and re.match(r'^  \}$', line):
                in_fun = None
                continue
            if in_fun:
                m = re.match(r'^\s*"([^"]+)"\s*->', line)
                if m:
                    names.add(m.group(1))
    return names or None

SWIPE_FIELDS = ('swipe_up', 'swipe_down', 'swipe_left', 'swipe_right')


def unquote(v):
    v = v.strip().strip(",")
    if len(v) >= 2 and v[0] == v[-1] and v[0] in ('"', "'"):
        return v[1:-1]
    return v


def split_top_level(body):
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


def parse_inline_dict(body):
    d = {}
    for item in split_top_level(body):
        if ':' not in item:
            continue
        k, v = item.split(':', 1)
        d[k.strip()] = unquote(v)
    return d


def parse_key_line(line):
    """返回 (顶层字段 dict, 行内嵌套 dict 列表)。"""
    m = KEY_RE.match(line)
    if not m:
        return None, []
    body = m.group(1)
    top, nested = {}, []

    def grab(match):
        nested.append(parse_inline_dict(match.group(1)))
        return '__NESTED%d__' % (len(nested) - 1)

    flattened = re.sub(r'\{([^{}]*)\}', grab, body)
    for item in split_top_level(flattened):
        if ':' not in item:
            top.setdefault('click', unquote(item))
            continue
        k, v = item.split(':', 1)
        v = v.strip()
        mm = re.match(r'^__NESTED(\d+)__$', v)
        if mm:
            nested[int(mm.group(1))]['__field__'] = k.strip()
            continue
        top[k.strip()] = unquote(v)
    return top, nested


def load_yaml_model(path):
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()

    keyboards, presets = [], {}
    top_section = None
    cur_board = None
    cur_preset = None

    for raw in lines:
        line = raw.rstrip()
        stripped = line.strip()

        if line and not line[0].isspace():
            top_section = stripped.split(':')[0] if ':' in stripped else None
            cur_board = None
            cur_preset = None
            continue
        if not stripped or stripped.startswith("#"):
            continue

        if top_section == 'preset_keyboards':
            m = SECTION_RE.match(line)
            if m and m.group(1) != 'keys':
                if cur_board:
                    keyboards.append(cur_board)
                cur_board = {'name': m.group(1), 'rows': []}
                continue
            if cur_board is None:
                continue
            if re.match(r'^\s*-\s*\{', line):
                top, nested = parse_key_line(line)
                if top is not None:
                    cur_board['rows'].append((top, nested))
            continue

        if top_section == 'preset_keys':
            # 两种写法都要认：
            #   Mode_switch: { toggle: ascii_mode, send: SWITCH_CHARSET }   ← 行内 map
            #   space_cursor:                                                ← 块式
            #     label: 空格
            m = re.match(r'^  ([A-Za-z_][A-Za-z0-9_]*):\s*(.*)$', line)
            if m:
                cur_preset = m.group(1)
                presets[cur_preset] = {}
                rest = m.group(2).split('#')[0].strip()
                if rest.startswith('{') and rest.endswith('}'):
                    presets[cur_preset].update(parse_inline_dict(rest[1:-1]))
                continue
            m = PROP_RE.match(line)
            if m and cur_preset:
                presets[cur_preset][m.group(1)] = unquote(m.group(2).split('#')[0].strip())
            continue

    if cur_board:
        keyboards.append(cur_board)
    return keyboards, presets


def is_resolvable(name, preset_names, keyboard_names, rime_key_names):
    """镜像 KeyAction 的解析顺序；可解析返回 None，否则返回原因。"""
    if not name:
        return None
    if name in preset_names:
        return None
    if name in keyboard_names:
        return None

    # KeyCode.parse 只在 '+' 位于**非末位**时把它当修饰键分隔符；
    # 单独的 '+' 本身就是可打印字符，走 nameToKeyCode 的 charToCode。
    if '+' in name[:-1]:
        parts = name.split('+')
        for token in parts[:-1]:
            if token not in MODIFIERS:
                return "修饰键 '%s' 无法识别" % token
        last = parts[-1]
        if last in preset_names or last in rime_key_names or last in ANDROID_KEY_NAMES:
            return None
        if len(last) == 1:
            return None
        return "修饰键组合的末段 '%s' 无法识别" % last

    if name in rime_key_names or name in ANDROID_KEY_NAMES:
        return None
    if len(name) == 1:
        return None
    # 非 ASCII 的纯文字值（如 long_click: '——'）：KeyCode.parse 认不出时
    # KeyAction 会把它当文本直接上屏（KeyAction.kt 的 text = token.token 分支），
    # 这是「长按输入这个符号」的正常写法，不算哑键。
    if any(ord(ch) > 127 for ch in name):
        return None
    if '{' in name and '}' in name:
        return None
    return '不是 preset_keys 预设、不是键盘名，也不是 librime/Android 认识的键名'


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    path = sys.argv[1]
    print('校验文件: %s' % path)
    print('=' * 66)

    keyboards, presets = load_yaml_model(path)
    board_names = {b['name'] for b in keyboards}
    preset_names = set(presets)

    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    rime_key_names = load_rime_key_names(repo_root)
    if rime_key_names:
        print('键名表: 从 RimeKeyMapping 生成源码读取 %d 个' % len(rime_key_names))
    else:
        rime_key_names = _FALLBACK_KEY_NAMES
        print('键名表: 生成源码不存在，使用内置快照 %d 个'
              '（先跑一次 :app:compileDebugKotlin 可获得精确表）' % len(rime_key_names))

    print('键盘 %d 个: %s' % (len(keyboards), ', '.join(sorted(board_names))))
    print('preset_keys %d 个' % len(preset_names))

    issues = []

    for board in keyboards:
        for idx, (top, nested) in enumerate(board['rows'], 1):
            for field in ACTION_FIELDS:
                if field not in top:
                    continue
                value = top[field]
                reason = is_resolvable(value, preset_names, board_names, rime_key_names)
                if reason:
                    issues.append(
                        "键盘 %s 第 %d 行 %s: '%s' —— %s"
                        % (board['name'], idx, field, value, reason)
                    )

    for name in sorted(presets):
        send = presets[name].get('send', '')
        if not send:
            continue
        reason = is_resolvable(send, preset_names, board_names, rime_key_names)
        if reason:
            issues.append("preset_keys '%s' 的 send: '%s' —— %s" % (name, send, reason))

    for name in sorted(presets):
        for f in SWIPE_FIELDS:
            if f in presets[name]:
                issues.append(
                    "preset_keys '%s' 里有 %s —— 手势字段只在键盘行内解析，"
                    '写在这里会被静默丢弃' % (name, f)
                )
        if 'long_click' in presets[name] and presets[name].get('slide_cursor') == 'true':
            issues.append(
                "preset_keys '%s' 同时有 long_click 与 slide_cursor —— "
                '长按会吃掉滑动，拖动光标失效' % name
            )

    print('=' * 66)
    if issues:
        print('\n发现 %d 个问题：\n' % len(issues))
        for i, s in enumerate(issues, 1):
            print('  %2d. %s' % (i, s))
        return 1

    print('全部通过 ✓  所有键名引用都能解析')
    return 0


if __name__ == '__main__':
    sys.exit(main())
