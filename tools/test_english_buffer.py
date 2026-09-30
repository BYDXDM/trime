"""
EnglishInputBuffer 状态机验证。

把 Kotlin 的缓冲逻辑用 Python 重写，验证：
  - 字母累积、遇边界结算
  - 纠错时给出的 backspaces/replacement 正确
  - 只接收 ASCII 字母
  - 切模式/焦点变化会清空
"""
import re

KT = "app/src/main/java/com/osfans/trime/ime/text/EnglishCorrector.kt"
src = open(KT, encoding="utf-8").read()


def extract_set(name, text):
    m = re.search(rf"{name}: Set<String> = setOf\((.*?)\n    \)", text, re.S)
    block = re.sub(r"//[^\n]*", "", m.group(1))
    return set(re.findall(r'"([a-zA-Z0-9\']+)"', block))


COMMON_WORDS = extract_set("COMMON_WORDS", src)
NEVER_CORRECT = extract_set("NEVER_CORRECT", src)
m = re.search(r"KNOWN_MISSPELLINGS: Map<String, String> = mapOf\((.*?)\n    \)", src, re.S)
KNOWN = dict(re.findall(r'"([a-zA-Z\']+)"\s+to\s+"([a-zA-Z\']+)"', re.sub(r"//[^\n]*", "", m.group(1))))


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


def match_case(target, source):
    """与 Kotlin matchCase 对应。"""
    letters = [c for c in source if c.isalpha()]
    if letters and all(c.isupper() for c in letters):
        return target.upper()
    if source[:1].isupper():
        return target[0].upper() + target[1:]
    return target


class Corrector:
    enabled = True

    @staticmethod
    def correct(word):
        if len(word) < 3: return None
        lo = word.lower()
        if lo in NEVER_CORRECT: return None
        if lo in COMMON_WORDS: return None
        if lo in KNOWN: return match_case(KNOWN[lo], word)
        c = [w for w in COMMON_WORDS if is_single_edit_away(lo, w)]
        return match_case(c[0], word) if len(c) == 1 else None


class Buffer:
    def __init__(self):
        self.letters = ""
        self.last_enabled = False

    def on_commit(self, text):
        enabled = Corrector.enabled
        if enabled != self.last_enabled:
            self.letters = ""
            self.last_enabled = enabled
        if not enabled:
            self.letters = ""
            return None
        if len(text) != 1:
            return self.flush()
        ch = text[0]
        if ("a" <= ch <= "z") or ("A" <= ch <= "Z"):
            self.letters += ch
            return None
        return self.flush()

    def flush(self):
        if not self.letters:
            return None
        word = self.letters
        self.letters = ""
        if not Corrector.enabled:
            return None
        fixed = Corrector.correct(word)
        if fixed is None:
            return None
        return ("fix", word, word, fixed)

    def clear(self):
        self.letters = ""


fails = []
def check(desc, got, want):
    if got != want:
        fails.append(f"{desc}\n     got={got}\n    want={want}")


# --- 场景1：打 teh + 空格 -> 应纠成 the ---
b = Buffer()
for ch in "teh":
    check(f"累积 {ch} 不触发", b.on_commit(ch), None)
r = b.on_commit(" ")
check("空格触发纠错", r, ("fix", "teh", "teh", "the"))
check("缓冲已清空", b.letters, "")

# --- 场景2：正确词不纠 ---
b = Buffer()
for ch in "the":
    b.on_commit(ch)
check("正确词不纠", b.on_commit(" "), None)

# --- 场景3：多字符提交（中文候选上屏）先结算 ---
b = Buffer()
for ch in "teh":
    b.on_commit(ch)
r = b.on_commit("你好")     # 中文上屏，长度 != 1
check("中文上屏前先结算", r, ("fix", "teh", "teh", "the"))

# --- 场景6：标点触发结算 ---
for punct in [".", ",", "!", "?", ";", ":", "-", "1"]:
    b = Buffer()
    for ch in "adn":
        b.on_commit(ch)
    r = b.on_commit(punct)
    check(f"标点 {punct!r} 触发", r, ("fix", "adn", "adn", "and"))

# --- 场景7：回车触发 ---
b = Buffer()
for ch in "waht":
    b.on_commit(ch)
check("回车触发", b.on_commit("\n"), ("fix", "waht", "waht", "what"))

# --- 场景8：大写保留样式 ---
b = Buffer()
for ch in "Teh":
    b.on_commit(ch)
r = b.on_commit(" ")
check("首字母大写保留", r, ("fix", "Teh", "Teh", "The"))

b = Buffer()
for ch in "TEH":
    b.on_commit(ch)
r = b.on_commit(" ")
check("全大写保留", r, ("fix", "TEH", "TEH", "THE"))

# --- 场景9：白名单词不纠 ---
b = Buffer()
for ch in "redis":
    b.on_commit(ch)
check("redis 不纠", b.on_commit(" "), None)

# --- 场景8：clear 后不残留 ---
b = Buffer()
for ch in "teh":
    b.on_commit(ch)
b.clear()
check("clear 后缓冲空", b.letters, "")

# --- 场景9：非 ASCII 字母不进入英文缓冲 ---
b = Buffer()
for ch in "te":
    b.on_commit(ch)
check("非 ASCII 字母作为边界", b.on_commit("é"), None)
check("非 ASCII 字母不残留", b.letters, "")

# --- 场景10：关闭纠错时清空旧缓冲 ---
b = Buffer()
Corrector.enabled = True
for ch in "teh":
    b.on_commit(ch)
Corrector.enabled = False
check("关闭纠错后丢弃残留", b.on_commit(" "), None)
check("关闭后缓冲为空", b.letters, "")
Corrector.enabled = True

# --- 场景11：短词不纠 ---
b = Buffer()
for ch in "ad":
    b.on_commit(ch)
check("2 字符不纠", b.on_commit(" "), None)

# --- 场景12：长词 -> 正确词，backspaces 长度正确 ---
b = Buffer()
for ch in "beacuse":
    b.on_commit(ch)
r = b.on_commit(" ")
check("beacuse -> because", r, ("fix", "beacuse", "beacuse", "because"))
check("backspaces 长度 = 原词长度", len(r[2]), 7)

print("=" * 58)
if fails:
    print(f"失败 {len(fails)}：")
    for f in fails:
        print("  ✗", f)
    raise SystemExit(1)
print("缓冲状态机全部通过 ✓  （12 个场景）")
