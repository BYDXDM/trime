#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""review_dict_audit.py — 只读审查 mydomain 词库

用法:
    python tools/review_dict_audit.py <dict.yaml> <luna.dict.yaml>

输出:
    1. 编码合法性 / 中文-拼音语义核对（用 luna_pinyin 字表做 oracle）
    2. 重复词条（同词多编码、同词同编码）
    3. 权重分布
    4. 简拼撞车 top 20
    5. keywords.tsv 覆盖核对
"""
import re
import sys
from collections import defaultdict

import _console  # noqa: F401  (UTF-8 stdout)

CJK = re.compile(r"^[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]+$")
SYL = re.compile(r"^[a-z\u00fc]+$")
LATIN = re.compile(r"^[\x21-\x7e]+$")


def load_dict(path):
    """Return (meta, entries) where entries = list of dict(word, code, weight, line)."""
    meta = {}
    entries = []
    in_header = True
    with open(path, encoding="utf-8") as f:
        for lineno, raw in enumerate(f, 1):
            line = raw.rstrip("\r\n")
            stripped = line.strip()
            if in_header:
                if stripped == "---":
                    in_header = False
                    continue
                if ":" in stripped and not stripped.startswith("#"):
                    k, _, v = stripped.partition(":")
                    meta[k.strip()] = v.strip()
                continue
            if stripped == "...":
                continue
            if not stripped or stripped.startswith("#"):
                continue
            cols = line.split("\t")
            if len(cols) < 2:
                continue
            word = cols[0].strip()
            code = cols[1].strip()
            if len(cols) > 2 and cols[2].strip():
                try:
                    weight = float(cols[2].strip().rstrip("%"))
                except ValueError:
                    weight = None
            else:
                weight = None
            entries.append(dict(word=word, code=code, weight=weight, line=lineno))
    return meta, entries


def load_luna_char_readings(path):
    """char -> {syllable: weight}.  Only single-char entries with single-syllable codes."""
    table = defaultdict(dict)
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\r\n")
            if not line or line.startswith("#"):
                continue
            cols = line.split("\t")
            if len(cols) < 2:
                continue
            word, code = cols[0].strip(), cols[1].strip()
            if len(word) != 1 or " " in code or "'" in code:
                continue
            if not SYL.match(code):
                continue
            w = 1.0
            if len(cols) > 2 and cols[2].strip():
                s = cols[2].strip()
                if s.endswith("%"):
                    try:
                        w = float(s[:-1]) / 100.0
                    except ValueError:
                        w = 1.0
                else:
                    try:
                        w = float(s)
                    except ValueError:
                        w = 1.0
            table[word][code] = max(table[word].get(code, 0.0), w)
    return table


def norm_latin(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


def main():
    dict_path, luna_path = sys.argv[1], sys.argv[2]
    meta, entries = load_dict(dict_path)
    luna = load_luna_char_readings(luna_path)

    print("=" * 72)
    print("词库:", dict_path)
    print("头部:", {k: v for k, v in meta.items()})
    print("词条总数:", len(entries), " 唯一词语:", len({e['word'] for e in entries}))
    print("luna 单字读音表:", len(luna), "字")

    # ---------------- 1. 编码核对 ----------------
    print()
    print("=" * 72)
    print("【1】编码核对（oracle = luna_pinyin 单字读音表）")
    bad_charset, len_mismatch, wrong_reading, unknown_char = [], [], [], []
    latin_ok, latin_bad = [], []
    for e in entries:
        w, c = e["word"], e["code"]
        if CJK.match(w):
            if not re.match(r"^[a-z\u00fc' ]+$", c):
                bad_charset.append(e)
                continue
            syls = [s for s in c.split(" ") if s]
            chars = list(w)
            if len(syls) != len(chars):
                len_mismatch.append((e, syls))
                continue
            for ch, s in zip(chars, syls):
                if ch not in luna:
                    unknown_char.append((e, ch, s))
                    continue
                if s not in luna[ch]:
                    wrong_reading.append((e, ch, s, sorted(luna[ch].items(),
                                                           key=lambda x: -x[1])))
        elif LATIN.match(w):
            if norm_latin(w) == norm_latin(c):
                latin_ok.append(e)
            else:
                latin_bad.append(e)
        else:
            bad_charset.append(e)

    print(f"  中文词条读音与 luna 字表不一致: {len(wrong_reading)}")
    for e, ch, s, valid in wrong_reading:
        vs = ", ".join(f"{k}({v:.4g})" for k, v in valid[:4])
        print(f"    :{e['line']:4d}  {e['word']}\t{e['code']}\t{e['weight']}"
              f"   ← 字「{ch}」用了「{s}」，luna 合法读音: {vs}")
    print(f"  拼音音节数 != 字数: {len(len_mismatch)}")
    for e, syls in len_mismatch:
        print(f"    :{e['line']:4d}  {e['word']}\t{e['code']}\t{e['weight']}"
              f"   ← {len(e['word'])} 字 vs {len(syls)} 音节")
    print(f"  字数不在 luna 字表内（无法核对）: {len(unknown_char)}")
    for e, ch, s in unknown_char:
        print(f"    :{e['line']:4d}  {e['word']}\t{e['code']}   ← 字「{ch}」")
    print(f"  编码字符集非法: {len(bad_charset)}")
    for e in bad_charset:
        print(f"    :{e['line']:4d}  {e['word']}\t{e['code']}")
    print(f"  拉丁词条编码 == 词去符号小写: {len(latin_ok)} / 不一致: {len(latin_bad)}")
    for e in latin_bad:
        print(f"    :{e['line']:4d}  {e['word']}\t{e['code']}\t{e['weight']}"
              f"   ← 期望 {norm_latin(e['word'])}")

    # ---------------- 2. 重复 ----------------
    print()
    print("=" * 72)
    print("【2】重复词条")
    by_word = defaultdict(list)
    for e in entries:
        by_word[e["word"]].append(e)
    dup_word = {w: v for w, v in by_word.items() if len(v) > 1}
    print(f"  同词多编码: {len(dup_word)} 个词")
    for w, v in sorted(dup_word.items(), key=lambda x: -len(x[1])):
        print(f"    {w}: " + " | ".join(
            f":{e['line']} [{e['code']}] w={e['weight']}" for e in v))
    by_pair = defaultdict(list)
    for e in entries:
        by_pair[(e["word"], e["code"])].append(e)
    dup_pair = {k: v for k, v in by_pair.items() if len(v) > 1}
    print(f"  完全相同的 (词,编码) 重复: {len(dup_pair)} 组")
    for k, v in dup_pair.items():
        print(f"    {k[0]}\t{k[1]}: " + " | ".join(
            f":{e['line']} w={e['weight']}" for e in v))

    # 同编码多词
    by_code = defaultdict(list)
    for e in entries:
        by_code[e["code"]].append(e)
    print(f"  唯一编码数: {len(by_code)}")

    # ---------------- 3. 权重分布 ----------------
    print()
    print("=" * 72)
    print("【3】权重分布")
    ws = sorted((e["weight"] for e in entries if e["weight"] is not None))
    n = len(ws)
    buckets = [(0, 100), (100, 200), (200, 400), (400, 700), (700, 1000),
               (1000, 1200), (1200, 2000), (2000, 10**9)]
    print(f"  有权重词条 {n} / {len(entries)}；min={ws[0]} max={ws[-1]} "
          f"中位={ws[n // 2]} 均值={sum(ws) / n:.1f}")
    for lo, hi in buckets:
        cnt = sum(1 for x in ws if lo <= x < hi)
        bar = "#" * min(60, cnt)
        print(f"    [{lo:5d},{hi:5d})  {cnt:4d}  {bar}")
    for label, lo, hi in [("注释承诺:专有名词 1200~2000", 1200, 2001),
                          ("注释承诺:普通词 700~1000", 700, 1001)]:
        cnt = sum(1 for x in ws if lo <= x < hi)
        print(f"  {label}: 实际 {cnt} 条 ({100.0 * cnt / n:.1f}%)")
    lt700 = sum(1 for x in ws if x < 700)
    print(f"  低于 700 的词条: {lt700} 条 ({100.0 * lt700 / n:.1f}%)")

    # ---------------- 4. 简拼撞车 ----------------
    print()
    print("=" * 72)
    print("【4】简拼撞车")
    abbr = defaultdict(list)
    for e in entries:
        syls = [s for s in e["code"].split(" ") if s]
        if not syls:
            continue
        a = "".join(s[0] for s in syls)
        abbr[a].append(e)
    coll = {a: v for a, v in abbr.items() if len(v) > 1}
    print(f"  唯一简拼 {len(abbr)}；撞车简拼 {len(coll)} 组")
    rank = sorted(coll.items(), key=lambda x: -len(x[1]))[:20]
    for a, v in rank:
        vs = sorted(v, key=lambda e: -(e["weight"] or 0))
        print(f"    {a:8s} ({len(v)})  " + " > ".join(
            f"{e['word']}({e['weight']})" for e in vs))

    # ---------------- 5. keywords.tsv ----------------
    print()
    print("=" * 72)
    print("【5】keywords.tsv 覆盖核对")
    import os
    tsv = os.path.join(os.path.dirname(dict_path), "keywords.tsv")
    if not os.path.exists(tsv):
        print("  未找到", tsv)
    else:
        rows = []
        with open(tsv, encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\r\n")
                if not line.strip() or line.startswith("#"):
                    continue
                cols = line.split("\t")
                if len(cols) < 3:
                    continue
                try:
                    rows.append((cols[0].strip(), cols[1].strip(),
                                 float(cols[2].strip())))
                except ValueError:
                    continue
        print(f"  tsv 词条 {len(rows)} 条")
        dict_pair = {(e["word"], e["code"]): e for e in entries}
        missing, wdiff = [], []
        for w, c, t in rows:
            e = dict_pair.get((w, c))
            if e is None:
                missing.append((w, c, t))
            elif e["weight"] != t:
                wdiff.append((w, c, t, e["weight"], e["line"]))
        print(f"  tsv 中 (词,编码) 在 dict 里缺失: {len(missing)}")
        for w, c, t in missing:
            print(f"    {w}\t{c}\t{t}")
        print(f"  tsv 与 dict 权重不一致: {len(wdiff)}")
        for w, c, t, dw, ln in wdiff[:20]:
            print(f"    {w}\t{c}: tsv={t} dict={dw} (dict:{ln})")
        if len(wdiff) > 20:
            print(f"    ... 另 {len(wdiff) - 20} 条")
        ratios = defaultdict(int)
        for w, c, t, dw, ln in wdiff:
            ratios[round(dw / t, 4) if t else None] += 1
        print("  dict/tsv 权重比分布:", dict(ratios))


if __name__ == "__main__":
    main()
