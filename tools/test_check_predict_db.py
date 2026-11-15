#!/usr/bin/env python3
"""F12 回归：check_predict_db.py 必须对越界样本返回非零且不打印 OK。

样本全部是合成文件（v2 头 + 两段假数据），只用来验证检查器的区间判断，
不代表真实词库内容，也不需要设备或 native 库。

用法：
    python tools/test_check_predict_db.py [待测脚本路径]
默认测同目录的 check_predict_db.py；传旧版本路径即可拿到「红」证据。
"""
import os
import shutil
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
CHECKER = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "check_predict_db.py")
FORMAT = b"Rime::Predict/2.0"
META = 52


def build(path, kt_units, kt_rel, vt_size, vt_rel, total=None, fmt=FORMAT):
    """合成一个库：metadata 之后是两段零字节，长度按需分配。"""
    kt_off = 36 + kt_rel
    vt_off = 44 + vt_rel
    need = max(META, kt_off + kt_units * 4, vt_off + vt_size)
    size = need if total is None else total
    buf = bytearray(b"\x00" * max(size, META))
    buf[0:len(fmt)] = fmt
    struct.pack_into("<IiIiI", buf, 32, 0, kt_rel, kt_units, vt_rel, vt_size)
    with open(path, "wb") as f:
        f.write(bytes(buf[:size]))


def run_checker(path):
    p = subprocess.run(
        [sys.executable, CHECKER, path],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    return p.returncode, p.stdout, p.stderr


def printed_ok(stdout):
    lines = [line.strip() for line in stdout.splitlines() if line.strip()]
    return bool(lines) and lines[-1] == "OK"


failures = []


def case(name, build_args, want_rc, want_ok, want_stderr_empty=True):
    path = os.path.join(tmp, name + ".db")
    build(path, **build_args)
    rc, out, err = run_checker(path)
    problems = []
    if rc != want_rc:
        problems.append(f"退出码 {rc}，期望 {want_rc}")
    if printed_ok(out) != want_ok:
        problems.append(f"末行 OK={printed_ok(out)}，期望 {want_ok}")
    if want_stderr_empty and "Traceback" in err:
        problems.append("stderr 出现 Traceback（应以非零退出而不是抛栈）")
    if problems:
        failures.append(name)
        print(f"FAIL {name}: " + "；".join(problems))
        print("     stdout: " + " | ".join(out.strip().splitlines()[-3:]))
    else:
        print(f"PASS {name}")


tmp = tempfile.mkdtemp(prefix="check-predict-db-")
try:
    # 合法库：key_trie 52 起 2 units(8B)，value_trie 68 起 8B
    case("valid-v2",
         dict(kt_units=2, kt_rel=16, vt_size=8, vt_rel=24),
         want_rc=0, want_ok=True)

    # 核心用例：units 本身小于文件长度，但换算成字节后越界（旧实现会判 OK）
    #   文件 60 字节；key_trie @52 取 3 units = 12 字节 → 64 > 60
    #   value_trie @52 取 8 字节 → 60 == 60 合法
    case("key-trie-units-overflow",
         dict(kt_units=3, kt_rel=16, vt_size=8, vt_rel=8, total=60),
         want_rc=1, want_ok=False)

    # value_trie 越界：@52 取 9 字节 → 61 > 60
    case("value-trie-out-of-range",
         dict(kt_units=1, kt_rel=16, vt_size=9, vt_rel=8, total=60),
         want_rc=1, want_ok=False)

    # 文件不足 metadata 大小
    case("too-small",
         dict(kt_units=1, kt_rel=16, vt_size=8, vt_rel=8, total=40),
         want_rc=1, want_ok=False)

    # size 为 0：native 侧拒绝加载
    case("zero-key-trie",
         dict(kt_units=0, kt_rel=16, vt_size=8, vt_rel=8, total=60),
         want_rc=1, want_ok=False)

    # 非 v2 格式保持既有行为：报错退出
    case("format-v1",
         dict(kt_units=2, kt_rel=16, vt_size=8, vt_rel=24, fmt=b"Rime::Predict/1.0"),
         want_rc=1, want_ok=False)
finally:
    shutil.rmtree(tmp, ignore_errors=True)

if failures:
    print(f"\n{len(failures)} case(s) failed: {', '.join(failures)}")
    raise SystemExit(1)
print("\nall cases passed")
