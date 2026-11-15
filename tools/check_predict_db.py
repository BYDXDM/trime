#!/usr/bin/env python3
"""校验 predict.db (v2) 的元数据与各段区间能否被安全读出。

只读不写，用来在 CI / 本机快速确认「设备上生成的词库」确实是 v2 且每一段都在文件内，
不必真跑一轮输入法。格式定义见 librime-predict/src/predict_db.h，
区间契约见 librime-predict/src/predict_db.cc:105-154。

F12：这是检查器，任何一处越界都必须以**非零**退出，并且判失败后不能再打印 OK。
旧实现有两个问题：
  1. size 越界只 print 一句警告就继续往下走，最后照样打印 OK 并 return 0；
  2. key_trie_size 是 Darts 的 **unit 数**（每 unit 4 字节，见 darts.h:194 与
     predict_db.cc:122-131），旧实现把它当字节数比较，于是「units 够小、字节越界」
     的坏库会被判成合格。
"""
import struct
import sys

DB = sys.argv[1] if len(sys.argv) > 1 else \
    r"D:/ai/工作/trime/app/data/rime/myvocab/predict.db"

# sizeof(predict::Metadata)：char format[32] + u32 checksum + i32 key_trie +
# u32 key_trie_size + i32 value_trie + u32 value_trie_size
METADATA_SIZE = 52
# darts.h:194「unit_size() returns the size of each unit. The size must be 4 bytes.」
TRIE_UNIT_SIZE = 4
FORMAT_V2 = "Rime::Predict/2.0"


def segment_problems(name, offset, size_bytes, total):
    """返回该段区间的问题列表；空列表表示这一段可以安全读出。"""
    problems = []
    if offset < METADATA_SIZE:
        problems.append(f"{name} 偏移 {offset} 落在 metadata 区内或为负")
    if size_bytes <= 0:
        problems.append(f"{name} 长度为 {size_bytes}")
    if offset + size_bytes > total:
        problems.append(
            f"{name} 区间 [{offset}, {offset + size_bytes}) 超出文件长度 {total}")
    return problems


def main():
    try:
        with open(DB, "rb") as f:
            d = f.read()
    except OSError as e:
        print(f"!! 无法读取 {DB}: {e}")
        return 1

    print(f"size   = {len(d)}")
    if len(d) < METADATA_SIZE:
        print(f"!! 文件只有 {len(d)} 字节，不足 metadata 的 {METADATA_SIZE} 字节")
        return 1

    fmt = d[:32].split(b"\0")[0].decode("utf-8", "replace")
    print(f"format = {fmt!r}")
    if not fmt.startswith(FORMAT_V2):
        print("!! 不是 v2，联想重排不会生效")
        return 1

    checksum, key_trie, kt_units, value_trie, vt_size = struct.unpack_from(
        "<IiIiI", d, 32)
    kt_offset = 36 + key_trie
    vt_offset = 44 + value_trie
    # key_trie 的 size 是 unit 数，必须按实际 unit 大小换算成字节再比较区间
    kt_bytes = kt_units * TRIE_UNIT_SIZE

    print(f"  db_checksum    = {checksum}")
    print(f"  key_trie       = {key_trie} @{kt_offset} "
          f"({kt_units} units = {kt_bytes} bytes)")
    print(f"  key_trie_size  = {kt_units}")
    print(f"  value_trie     = {value_trie} @{vt_offset}")
    print(f"  value_trie_size= {vt_size}")

    problems = []
    problems += segment_problems("key_trie", kt_offset, kt_bytes, len(d))
    problems += segment_problems("value_trie", vt_offset, vt_size, len(d))
    if kt_units == 0:
        problems.append("key_trie_size 为 0（native 侧会拒绝加载）")
    if vt_size == 0:
        problems.append("value_trie_size 为 0（native 侧会拒绝加载）")

    if problems:
        for p in problems:
            print(f"!! {p}")
        print("FAILED")
        return 1

    print("OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
