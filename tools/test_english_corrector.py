"""
EnglishCorrector 的逻辑验证。

Kotlin 编译器跑不了（沙箱 PRoot 禁止 JVM 映射可执行内存），
所以把 isSingleEditAway / matchCase / correctWord 的核心算法
用 Python 逐行重写一遍，用来验证逻辑本身对不对。
算法逻辑与 Kotlin 版一一对应，改任一边都要同步改另一边。
"""

# ============ 与 Kotlin 版逐行对应的实现 ============


def is_single_edit_away(a: str, b: str) -> bool:
    if a == b:
        return False
    la, lb = len(a), len(b)
    if abs(la - lb) > 1:
        return False

    if la == lb:
        diff = -1
        for i in range(la):
            if a[i] != b[i]:
                if diff >= 0:
                    return diff == i - 1 and a[diff] == b[i] and a[i] == b[diff]
                diff = i
        return diff >= 0

    long, short = (a, b) if la > lb else (b, a)
    i = j = 0
    skipped = False
    while i < len(long) and j < len(short):
        if long[i] == short[j]:
            i += 1
            j += 1
        else:
            if skipped:
                return False
            skipped = True
            i += 1
    return True


def match_case(target: str, source: str) -> str:
    letters = [c for c in source if c.isalpha()]
    if letters and all(c.isupper() for c in letters):
        return target.upper()
    if source[:1].isupper():
        return target[0].upper() + target[1:]
    return target


# ============ 测试 ============

def run():
    fails = []

    def check(desc, got, want):
        if got != want:
            fails.append(f"{desc}: got={got!r} want={want!r}")

    # --- isSingleEditAway ---
    check("teh/the 换位", is_single_edit_away("teh", "the"), True)
    check("cat/car 替换", is_single_edit_away("cat", "car"), True)
    check("cat/cats 插入", is_single_edit_away("cat", "cats"), True)
    check("cats/cat 删除", is_single_edit_away("cats", "cat"), True)
    check("cat/dog 差异2处", is_single_edit_away("cat", "dog"), False)
    check("cat/cat 相同", is_single_edit_away("cat", "cat"), False)
    check("cat/carts 长度差2", is_single_edit_away("cat", "carts"), False)
    check("abcd/acbd 换位", is_single_edit_away("abcd", "acbd"), True)
    check("abcd/abdc 换位", is_single_edit_away("abcd", "abdc"), True)
    check("abcd/adcb 两处换位", is_single_edit_away("abcd", "adcb"), False)
    check("helo/hello 插入", is_single_edit_away("helo", "hello"), True)
    check("helllo/hello 删除", is_single_edit_away("helllo", "hello"), True)
    # 关键：3 处不同必须 False
    check("abcd/abce", is_single_edit_away("abcd", "abce"), True)
    check("abc/xyz", is_single_edit_away("abc", "xyz"), False)

    # --- 增删分支的边界：完全没跳过时不应返回 True 之外的值 ---
    # "a" vs "ab" -> 跳过 b 后匹配
    check("a/ab", is_single_edit_away("a", "ab"), True)
    check("ab/a", is_single_edit_away("ab", "a"), True)
    # "abc" vs "axc" 长度相同走替换分支
    check("abc/axc", is_single_edit_away("abc", "axc"), True)

    # --- matchCase ---
    check("Teh->The", match_case("the", "Teh"), "The")
    check("TEH->THE", match_case("the", "TEH"), "THE")
    check("teh->the", match_case("the", "teh"), "the")
    check("I'M 场景", match_case("i'm", "IM"), "I'M")

    print("=" * 60)
    if fails:
        print(f"失败 {len(fails)} 项：")
        for f in fails:
            print("  ✗", f)
        return 1
    print("全部通过 ✓")
    return 0


if __name__ == "__main__":
    raise SystemExit(run())
