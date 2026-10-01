#!/usr/bin/env python3
"""校验 predict.db (v2) 的元数据与候选编码能否被正确读出。

只读不写，用来在 CI / 本机快速确认「设备上生成的词库」确实是 v2 且内容可用，
不必真跑一轮输入法。格式定义见 librime-predict/src/predict_db.h。
"""
import struct
import sys

DB = sys.argv[1] if len(sys.argv) > 1 else \
    r"D:/ai/工作/trime/app/data/rime/myvocab/predict.db"


def main():
    d = open(DB, "rb").read()
    fmt = d[:32].split(b"\0")[0].decode()
    print(f"size   = {len(d)}")
    print(f"format = {fmt!r}")
    if not fmt.startswith("Rime::Predict/2.0"):
        print("!! 不是 v2，联想重排不会生效")
        return 1

    # Metadata: char format[32]; u32 checksum; i32 key_trie; u32 key_trie_size;
    #           i32 value_trie; u32 value_trie_size
    checksum, key_trie, kt_size, value_trie, vt_size = struct.unpack_from(
        "<IiIiI", d, 32)

    print(f"  db_checksum    = {checksum}")
    print(f"  key_trie       = {key_trie} @{36 + key_trie}")
    print(f"  key_trie_size  = {kt_size}")
    print(f"  value_trie     = {value_trie} @{44 + value_trie}")
    print(f"  value_trie_size= {vt_size}")

    for name, off, size in (("key_trie", 36 + key_trie, kt_size),
                            ("value_trie", 44 + value_trie, vt_size)):
        if off < 0 or off + 1 > len(d):
            print(f"!! {name} 指针越界，词库已损坏")
            return 1
        if size and off + size > len(d):
            print(f"!! {name} 数据超出文件末尾")

    print("OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
