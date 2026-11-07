#!/usr/bin/env python3
"""只读审查 predict.txt：上文词/候选词能否在词库里找到。

predict.txt 的每一行是 <上文词>\t<候选词>\t<权重>[\t<拼音编码>]。
predictor 只有在输入序列里**已经出现**上文词时才会给出候选，
所以「上文词不在任何词库里」= 这条联想永远不会触发。
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _console import ensure_utf8_stdout  # noqa: E402

ensure_utf8_stdout()

ROOT = Path(__file__).resolve().parent.parent
PREDICT = ROOT / "app/data/rime/myvocab/predict.txt"
DICT = ROOT / "app/data/rime/myvocab/mydomain.dict.yaml"
LUNA = ROOT / "app/src/main/assets/shared/luna_pinyin.dict.yaml"
ESSAY = ROOT / "app/src/main/assets/shared/essay.txt"


def load_dict_texts(path):
    """取 .dict.yaml 的 text 列。

    头部是 YAML 元数据（`---` ... `...`），正文在 `...` 之后；
    正文里的 `#` 是注释行，不是终止符。
    """
    out = set()
    body = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if not body:
            if line.strip() == "...":
                body = True
            continue
        if not line or line[0] == "#":
            continue
        cols = line.split("\t")
        if cols and cols[0]:
            out.add(cols[0])
    return out


def load_essay(path):
    out = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line[0] == "#":
            continue
        cols = line.split("\t")
        if cols and cols[0]:
            out.add(cols[0])
    return out


def main():
    mydomain = load_dict_texts(DICT)
    luna = load_dict_texts(LUNA)
    essay = load_essay(ESSAY)
    known = mydomain | luna | essay
    print(f"词表规模: mydomain={len(mydomain)} luna={len(luna)} essay={len(essay)}")

    rows = []
    for i, line in enumerate(PREDICT.read_text(encoding="utf-8").splitlines(), 1):
        if not line or line.lstrip().startswith("#"):
            continue
        cols = line.split("\t")
        if len(cols) < 3:
            print(f"  !! predict.txt:{i} 列数不足: {line!r}")
            continue
        rows.append((i, cols[0], cols[1], cols[2], cols[3] if len(cols) > 3 else ""))

    print(f"predict.txt 数据行 = {len(rows)}")

    print("\n=== 上文词不在任何词库中（该条联想永远不触发）===")
    bad = [(i, a, b, w) for (i, a, b, w, _c) in rows if a != "$" and a not in known]
    for i, a, b, w in bad:
        print(f"  predict.txt:{i}  上文「{a}」→ 「{b}」 w={w}")
    print(f"  小计: {len(bad)} 条")

    print("\n=== 候选词不在任何词库中（仍可上屏，但无法单独打出）===")
    miss = [(i, a, b, w) for (i, a, b, w, _c) in rows if b not in known]
    for i, a, b, w in miss:
        print(f"  predict.txt:{i}  「{a}」→「{b}」 w={w}")
    print(f"  小计: {len(miss)} 条")

    print("\n=== 句首哨兵 $ 行 ===")
    for i, a, b, w, c in rows:
        if a == "$":
            print(f"  predict.txt:{i}  $ → {b} w={w} code={c}  候选在词库: {b in known}")

    print("\n=== 候选词带拼音编码时，与 mydomain 词典编码是否一致 ===")
    dict_code = {}
    body = False
    for line in DICT.read_text(encoding="utf-8").splitlines():
        if not body:
            if line.strip() == "...":
                body = True
            continue
        if not line or line[0] == "#":
            continue
        cols = line.split("\t")
        if len(cols) >= 2 and cols[0]:
            dict_code.setdefault(cols[0], set()).add(cols[1])
    diff = 0
    for i, a, b, w, c in rows:
        if not c:
            continue
        if b in dict_code and c not in dict_code[b]:
            print(f"  predict.txt:{i}  「{b}」拼音={c}  词典里是 {sorted(dict_code[b])}")
            diff += 1
    print(f"  不一致: {diff} 条")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
