#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""F13 回归：两个键盘解析器在离开 preset_keyboards / 遇到注释 / 到达 EOF 时
都必须保留当前键盘，且末盘的错误要真的被报出来。

样本全部是临时目录里的合成 YAML，不依赖设备、构建产物或真实主题内容。
用法：
    python tools/test_keyboard_parsers.py [tools 目录]
默认测本文件所在目录的 check_key_refs.py 与 check_layout.py；传旧版本所在目录
即可拿到「红」证据。
"""

import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = sys.argv[1] if len(sys.argv) > 1 else HERE
REPO = os.path.dirname(HERE)
REAL_THEME = os.path.join(
    REPO, 'app', 'src', 'main', 'assets', 'shared', 'tongwenfeng.trime.yaml')

CLICKS = 'qwertyuiop'   # 同一行内 click 不能重复，否则 check_layout 会另报重复键
BAD_KEY = 'NotARealKey_xyz'


def board(name, keys=10, bad_click=False, bad_width=False):
    """生成一个键盘块：默认 10 个宽 10 的键（整行 100）。"""
    out = '  %s:\n    width: 10\n    height: 40\n    keys:\n' % name
    count = 6 if bad_width else keys
    for i in range(count):
        click = BAD_KEY if (bad_click and i == 0) else CLICKS[i]
        width = 10 if not bad_width else 16.6
        out += '      - {click: %s, width: %s}\n' % (click, width)
    return out


def run(script, path):
    p = subprocess.run(
        [sys.executable, os.path.join(TOOLS, script), path],
        capture_output=True, text=True, encoding='utf-8', errors='replace')
    return p.returncode, p.stdout, p.stderr


def refs_boards(out):
    for line in out.splitlines():
        if line.startswith('键盘 ') and ' 个:' in line:
            return int(line.split()[1])
    return 0


def layout_boards(out):
    for line in out.splitlines():
        if line.startswith('共 ') and ' 个键盘' in line:
            return int(line.split()[1])
    return 0


failures = []


def check(name, cond, detail=''):
    if cond:
        print('PASS ' + name)
    else:
        failures.append(name)
        print('FAIL ' + name + ('  ' + detail if detail else ''))


tmp = tempfile.mkdtemp(prefix='keyboard-parsers-')
try:
    def write(fname, body):
        path = os.path.join(tmp, fname)
        with open(path, 'w', encoding='utf-8') as f:
            f.write(body)
        return path

    # 1. 单键盘 + EOF：两个解析器都该看到 1 个键盘
    p = write('single.yaml', 'preset_keyboards:\n' + board('only'))
    rc, out, err = run('check_key_refs.py', p)
    check('single-board/refs', refs_boards(out) == 1 and rc == 0, out.strip()[-200:])
    rc, out, err = run('check_layout.py', p)
    check('single-board/layout', layout_boards(out) == 1 and rc == 0, out.strip()[-200:])

    # 2. 多键盘 + EOF
    p = write('multi.yaml', 'preset_keyboards:\n' + board('a') + board('b'))
    rc, out, _ = run('check_key_refs.py', p)
    check('multi-board/refs', refs_boards(out) == 2, out.strip()[-200:])
    rc, out, _ = run('check_layout.py', p)
    check('multi-board/layout', layout_boards(out) == 2, out.strip()[-200:])

    # 3. 章节切换：preset_keyboards 后面还有顶层区块，前面的键盘一个都不能丢
    p = write('switch.yaml', 'preset_keyboards:\n' + board('a') + board('b')
              + 'preset_keys:\n  space1: {click: space}\n')
    rc, out, _ = run('check_key_refs.py', p)
    check('section-switch/refs keeps last board', refs_boards(out) == 2,
          out.strip()[-200:])
    rc, out, _ = run('check_layout.py', p)
    check('section-switch/layout keeps last board', layout_boards(out) == 2,
          out.strip()[-200:])

    # 4. 顶格注释：既不算新章节，也不能丢键盘（真实主题就是这种写法）
    p = write('comment.yaml', 'preset_keyboards:\n' + board('a')
              + '#符号键盘之二\n' + board('b'))
    rc, out, _ = run('check_key_refs.py', p)
    check('top-level-comment/refs', refs_boards(out) == 2, out.strip()[-200:])
    rc, out, _ = run('check_layout.py', p)
    check('top-level-comment/layout', layout_boards(out) == 2, out.strip()[-200:])

    # 5. 末盘（EOF 前最后一个键盘）里的错误必须报出来
    p = write('eof-bad.yaml', 'preset_keyboards:\n' + board('a')
              + board('badref', bad_click=True))
    rc, out, _ = run('check_key_refs.py', p)
    check('eof-last-board/refs reports error',
          rc == 1 and 'badref' in out and BAD_KEY in out, out.strip()[-300:])

    p = write('eof-bad-width.yaml', 'preset_keyboards:\n' + board('a')
              + board('badwidth', bad_width=True))
    rc, out, _ = run('check_layout.py', p)
    check('eof-last-board/layout reports error',
          rc == 1 and 'badwidth' in out, out.strip()[-300:])

    # 6. 末盘之后还有顶层区块（F13 的原始触发条件）：错误同样要报出来
    p = write('switch-bad.yaml', 'preset_keyboards:\n' + board('a')
              + board('badref', bad_click=True)
              + 'preset_keys:\n  space1: {click: space}\n')
    rc, out, _ = run('check_key_refs.py', p)
    check('section-switch-last-board/refs reports error',
          rc == 1 and 'badref' in out, out.strip()[-300:])

    p = write('switch-bad-width.yaml', 'preset_keyboards:\n' + board('a')
              + board('badwidth', bad_width=True)
              + 'preset_keys:\n  space1: {click: space}\n')
    rc, out, _ = run('check_layout.py', p)
    check('section-switch-last-board/layout reports error',
          rc == 1 and 'badwidth' in out, out.strip()[-300:])

    # 7. YAML 锚点别名：主题用 height: *jpgd5 写行高，检查器不能抛 ValueError
    body = ('preset_keyboards:\n'
            '  anchored:\n    width: 10\n    height: *h1\n    keys:\n')
    for click in CLICKS:
        body += '      - {click: %s, width: 10, height: *h2}\n' % click
    p = write('anchor.yaml', 'anchor_defs:\n  a: &h1 40\n  b: &h2 26\n' + body)
    rc, out, err = run('check_layout.py', p)
    check('anchor-alias/no crash',
          'ValueError' not in err and 'Traceback' not in err,
          err.strip()[-300:])
    check('anchor-alias/resolved height',
          layout_boards(out) == 1 and '没有 height' not in out,
          out.strip()[-300:])

    # 9. R5：非空字面量解析不出来必须报成问题（rc=1），别名解析出来的数字不能被误报
    keys10 = ''.join('      - {click: %s, width: 10}\n' % c for c in CLICKS)

    # 键宽不是数字：默认值兜底后行宽会变形，但必须明确指出是哪个值解析失败
    p = write('bad-number-width.yaml',
              'preset_keyboards:\n  badnum:\n    width: 10\n    height: 40\n    keys:\n'
              '      - {click: q, width: abc}\n'
              + keys10)
    rc, out, _ = run('check_layout.py', p)
    check('bad-number/key width',
          rc == 1 and 'badnum' in out and 'abc' in out, out.strip()[-300:])

    # 键高不是数字：这一条是唯一的问题，rc 只能由它产生
    p = write('bad-number-height.yaml',
              'preset_keyboards:\n  badnum:\n    width: 10\n    height: 40\n    keys:\n'
              '      - {click: q, width: 10, height: xyz}\n'
              + keys10)
    rc, out, _ = run('check_layout.py', p)
    check('bad-number/key height', rc == 1 and 'xyz' in out, out.strip()[-300:])

    # 键盘级 width 不是数字：同样是唯一的问题
    p = write('bad-number-prop.yaml',
              'preset_keyboards:\n  badnum:\n    width: abc\n    height: 40\n    keys:\n'
              + keys10)
    rc, out, _ = run('check_layout.py', p)
    check('bad-number/board width', rc == 1 and 'abc' in out, out.strip()[-300:])

    # 别名（*h1/*h2）解析出的数字是合法值，不能被当成解析失败
    rc, out, err = run('check_layout.py', os.path.join(tmp, 'anchor.yaml'))
    check('bad-number/alias not reported', rc == 0 and '不是数字' not in out,
          out.strip()[-300:])

    # 10. R6：引号内的 {…} 属于待上屏文本，不能变成 __NESTED 占位符
    p = write('quoted-braces.yaml',
              'preset_keyboards:\n  quoted:\n    width: 10\n    height: 40\n    keys:\n'
              "      - {click: '(){Left}', width: 10}\n")
    rc, out, _ = run('check_key_refs.py', p)
    check('nested-in-quote/refs', rc == 0 and '__NESTED' not in out,
          out.strip()[-300:])

    # 纯 ASCII 颜文字（含键名不可能出现的 ^ ( ) 等字符）按文本上屏处理
    p = write('kaomoji.yaml',
              'preset_keyboards:\n  kao:\n    width: 10\n    height: 40\n    keys:\n'
              "      - {click: '^_^', width: 10}\n"
              "      - {click: 'Y(^_^)Y', width: 10}\n")
    rc, out, _ = run('check_key_refs.py', p)
    check('kaomoji/text not reported', rc == 0, out.strip()[-300:])

    # 同一个文件里的真拼写错误仍然要报（豁免不能吞掉真问题）
    p = write('kaomoji-typo.yaml',
              'preset_keyboards:\n  kao:\n    width: 10\n    height: 40\n    keys:\n'
              "      - {click: '^_^', width: 10}\n"
              '      - {click: %s, width: 10}\n' % BAD_KEY)
    rc, out, _ = run('check_key_refs.py', p)
    check('kaomoji/real typo still reported',
          rc == 1 and BAD_KEY in out and '^_^' not in out, out.strip()[-300:])

    # 8. 真实主题：preset_keyboards 段内的顶格注释之后仍有键盘（fbjzj 是最后一个）
    if os.path.isfile(REAL_THEME):
        rc, out, _ = run('check_key_refs.py', REAL_THEME)
        check('real-theme/refs sees last board',
              refs_boards(out) > 3 and 'fbjzj' in out, out.strip()[:200])
        check('real-theme/no placeholder leak', '__NESTED' not in out,
              out.strip()[:200])
        check('real-theme/kao_happy text not reported',
              '^_^' not in out and '^o^' not in out, out.strip()[:200])
        check('real-theme/real problems kept',
              'CLEAR' in out and 'HELP' in out and 'INFO' in out
              and 'date_time' in out, out.strip()[:200])
        rc, out, err = run('check_layout.py', REAL_THEME)
        check('real-theme/layout sees last board',
              layout_boards(out) > 3 and 'Traceback' not in err,
              out.strip()[:200])
    else:
        print('SKIP real-theme (主题文件不存在)')
finally:
    import shutil
    shutil.rmtree(tmp, ignore_errors=True)

if failures:
    print('\n%d case(s) failed: %s' % (len(failures), ', '.join(failures)))
    raise SystemExit(1)
print('\nall cases passed')
