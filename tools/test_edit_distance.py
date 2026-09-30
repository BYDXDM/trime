"""
验证修正后的 isSingleEditAway —— 换位后必须检查剩余字符是否全部相同。
"""
def is_single_edit_away(a: str, b: str) -> bool:
    if a == b:
        return False
    la, lb = len(a), len(b)
    if abs(la - lb) > 1:
        return False

    if la == lb:
        # 找到第一处不同
        i = 0
        while i < la and a[i] == b[i]:
            i += 1
        if i == la:
            return False  # 完全相同
        # 情形 A：此处是替换（跳过这一位后，剩余必须全同）
        if a[i + 1:] == b[i + 1:]:
            return True
        # 情形 B：此处与下一位互换（跳两位后，剩余必须全同）
        if i + 1 < la and a[i] == b[i + 1] and a[i + 1] == b[i] and a[i + 2:] == b[i + 2:]:
            return True
        return False

    # 增/删：把长的去掉一个字符后应等于短的
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


fails = []
def check(desc, got, want):
    if got != want:
        fails.append(f"{desc}: got={got} want={want}")

# 换位（应为 True）
check("teh/the", is_single_edit_away("teh", "the"), True)
check("adn/and", is_single_edit_away("adn", "and"), True)
check("abcd/acbd", is_single_edit_away("abcd", "acbd"), True)
check("abcde/abced", is_single_edit_away("abcde", "abced"), True)
# 替换（应为 True）
check("cat/car", is_single_edit_away("cat", "car"), True)
check("hello/hallo", is_single_edit_away("hello", "hallo"), True)
# 增删（应为 True）
check("cat/cats", is_single_edit_away("cat", "cats"), True)
check("helo/hello", is_single_edit_away("helo", "hello"), True)

# ★ 回归：之前误判的
check("success/usually", is_single_edit_away("success", "usually"), False)
check("redis/error", is_single_edit_away("redis", "error"), False)
check("nginx/night", is_single_edit_away("nginx", "night"), False)
# 其他多差异
check("cat/dog", is_single_edit_away("cat", "dog"), False)
check("abc/xyz", is_single_edit_away("abc", "xyz"), False)
check("abcd/adcb", is_single_edit_away("abcd", "adcb"), False)
check("success/successful", is_single_edit_away("success", "successful"), False)

print("=" * 55)
if fails:
    print(f"失败 {len(fails)}：")
    for f in fails:
        print("  ✗", f)
    raise SystemExit(1)
print("全部通过 ✓  （含 3 个原误判回归）")
