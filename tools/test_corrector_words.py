"""
端到端验证 EnglishCorrector.correctWord 的判定行为。
算法与 test_edit_distance.py 里验证通过的版本一致。
"""
import re
import sys

KT = "app/src/main/java/com/osfans/trime/ime/text/EnglishCorrector.kt"
src = open(KT, encoding="utf-8").read()


def extract_set(name, text):
    m = re.search(rf"{name}: Set<String> = setOf\((.*?)\n    \)", text, re.S)
    assert m, f"抽不到 {name}"
    block = re.sub(r"//[^\n]*", "", m.group(1))
    return set(re.findall(r'"([a-zA-Z0-9\']+)"', block))


COMMON_WORDS = extract_set("COMMON_WORDS", src)
NEVER_CORRECT = extract_set("NEVER_CORRECT", src)

m = re.search(r"KNOWN_MISSPELLINGS: Map<String, String> = mapOf\((.*?)\n    \)", src, re.S)
kblock = re.sub(r"//[^\n]*", "", m.group(1))
KNOWN = dict(re.findall(r'"([a-zA-Z\']+)"\s+to\s+"([a-zA-Z\']+)"', kblock))

print(f"COMMON_WORDS  : {len(COMMON_WORDS)}")
print(f"NEVER_CORRECT : {len(NEVER_CORRECT)}")
print(f"KNOWN_TYPOS   : {len(KNOWN)}")


def is_single_edit_away(a, b):
    if a == b: return False
    la, lb = len(a), len(b)
    if abs(la - lb) > 1: return False
    if la == lb:
        i = 0
        while i < la and a[i] == b[i]:
            i += 1
        if i == la: return False
        if a[i + 1:] == b[i + 1:]: return True
        if i + 1 < la and a[i] == b[i + 1] and a[i + 1] == b[i] and a[i + 2:] == b[i + 2:]:
            return True
        return False
    long, short = (a, b) if la > lb else (b, a)
    i = j = 0; skipped = False
    while i < len(long) and j < len(short):
        if long[i] == short[j]: i += 1; j += 1
        else:
            if skipped: return False
            skipped = True; i += 1
    return True


def correct_word(word, min_len=3):
    if len(word) < min_len: return None
    lo = word.lower()
    if lo in NEVER_CORRECT: return None
    if lo in COMMON_WORDS: return None
    if lo in KNOWN: return KNOWN[lo]
    cands = [w for w in COMMON_WORDS if is_single_edit_away(lo, w)]
    return cands[0] if len(cands) == 1 else None


SHOULD_FIX = [
    "teh", "adn", "taht", "waht", "yuor", "jsut", "wiht", "beacuse",
    "recieve", "definately", "occured", "wich", "thier", "woudl",
    "fucntion", "reutrn", "strign", "defualt", "commti", "brach",
    "peopel", "realy", "probly", "begining", "sucess", "langauge",
    "seperate", "neccessary", "tommorow", "wether", "picutre", "servie",
]

SHOULD_NOT_FIX = [
    "the", "and", "that", "what", "your", "just", "with", "because",
    "receive", "definitely", "occurred", "which", "their", "would",
    "function", "return", "string", "default", "commit", "branch",
    "people", "really", "probably", "beginning", "success", "language",
    "separate", "necessary", "tomorrow", "whether", "picture", "service",
    # 短词
    "it", "is", "to", "of", "in", "on", "at", "be", "do", "go",
    # 技术词（白名单）
    "redis", "nginx", "docker", "github", "kubernetes", "linux", "gradle",
    "kotlin", "python", "java", "json", "http", "yaml", "sql", "rime",
    "trime", "api", "sdk", "ide", "cli", "cpu", "gpu", "url", "ascii",
    # 歧义词
    "cat", "car", "cut", "can", "bit", "bat", "bad",
]

print("\n--- 应该纠正 ---")
miss = []
for w in SHOULD_FIX:
    got = correct_word(w)
    print(f"  {w:14s} -> {got}" if got else f"  {w:14s} -> (未纠) ← 漏")
    if not got:
        miss.append(w)

print("\n--- 不该纠正 ---")
wrong = []
for w in SHOULD_NOT_FIX:
    got = correct_word(w)
    if got:
        wrong.append((w, got))
        print(f"  {w:14s} -> {got}  ← 误纠!")
    else:
        print(f"  {w:14s} -> ok")

print("\n" + "=" * 58)
print(f"召回 {len(SHOULD_FIX)-len(miss)}/{len(SHOULD_FIX)}   误纠 {len(wrong)}/{len(SHOULD_NOT_FIX)}")
if miss:
    print("漏纠:", miss)
if wrong:
    print("误纠:", wrong)
sys.exit(1 if wrong else 0)
