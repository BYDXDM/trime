#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
统一控制台输出编码，供 tools/ 下的检查脚本导入。

为什么需要：
    这些脚本会打印 ✓ / ✗ / → / ★ 等非 ASCII 字符。Windows 控制台默认
    编码是 GBK（cp936），Python 直接写入会抛：

        UnicodeEncodeError: 'gbk' codec can't encode character '\\u2713'

    结果就是 README 里要求「改完配置必跑」的 tools/all_checks.sh 在
    Windows 上第一步就中断。

用法（放在其它 import 之前）：
    from _console import ensure_utf8_stdout
    ensure_utf8_stdout()
"""

import sys

#: 依次尝试的编码。utf-8 优先，出错时退回仅替换不可编码字符的模式。
_PREFERRED = ('utf-8', 'utf8')


def ensure_utf8_stdout():
    """让标准输出尽量使用 UTF-8；无法设置时退化为「替换不可编码字符」。

    返回实际生效的编码名，便于测试断言。
    """
    stream = getattr(sys, 'stdout', None)
    if stream is None:
        return None

    for encoding in _PREFERRED:
        reconfigure = getattr(stream, 'reconfigure', None)
        if reconfigure is None:
            break
        try:
            reconfigure(encoding=encoding, errors='replace')
            return encoding
        except (ValueError, OSError):
            continue

    # Python 3.6 或已被包装过的流：至少保证不因编码问题崩溃
    try:
        stream.errors = 'replace'
    except (AttributeError, ValueError):
        pass
    return getattr(stream, 'encoding', None)
