# 英文输入纠错

在英文模式下打字时，自动把常见拼写错误纠正过来。
例如 `teh` → `the`、`recieve` → `receive`、`fucntion` → `function`。

**只对英文（ASCII）模式生效**，中文模式完全不受影响。

---

## 为什么需要单独做

Trime 的英文模式走 Rime 的 `ascii_composer`，它对每个按键执行
`engine_->CommitText(string(1, ch))`（`ascii_composer.cc:179`），
字母**原样直通上屏**，不经过任何编码、词典或排序。

对比中文侧：中文有 `mydomain` 词库兜底，打拼音会出候选、可以选词；
英文侧是"打什么就是什么"，没有任何纠错能力。所以这块得自己加。

---

## 实现

三个文件，职责分离，逻辑与 Android API 解耦：

| 文件 | 职责 |
|---|---|
| `ime/text/EnglishCorrector.kt` | 判定逻辑：一个词该不该纠、纠成什么 |
| `ime/text/EnglishInputBuffer.kt` | 攒词：把逐字符的输入拼成完整单词 |
| `ime/core/TrimeInputMethodService.kt` | 接线：调上面两个，执行实际的回改 |

### 为什么需要「攒词」这一步

因为**一个字母一次 commit**。`commitText()` 每次只拿到一个字符，
在那一层根本无法判断"用户这个单词打完了没有"。

所以 `EnglishInputBuffer` 负责累积：字母不断攒，遇到词边界就结算。

**词边界**（此时才判断要不要纠）：

- 空格、标点、回车等非字母字符
- 多字符提交（中文候选上屏、粘贴等）
- 切换输入框（清空缓冲，丢弃残留）

这点很关键。如果每敲一个字母就纠，用户打 `helo` 时会在第三个字母
就被改成 `help`，后面的输入全乱。

### 回改怎么实现

单词已经上屏了，要改只能"删掉重打"：

```kotlin
ic.beginBatchEdit()
ic.deleteSurroundingText(result.backspaces, 0)   // 删掉原词
ic.commitText(result.replacement, 1)             // 重打正确词
ic.endBatchEdit()
```

用 `deleteSurroundingText` 而不是循环发退格键 —— 后者会触发编辑器的
按键监听，而且慢得多。

**安全校验**：`deleteSurroundingText` 删的是**光标左侧**的字符。
如果用户中途移动了光标，删掉的可能不是我们想删的词。
所以回改前会确认原词位于光标左侧末尾，且原词前不是另一个英文单词的一部分；
对不上就放弃：

```kotlin
val before = getTextAroundCursor(original.length + 1, before = true)
val preceding = before.dropLast(original.length).lastOrNull()
if (!before.endsWith(original) || preceding?.isLetter() == true) return
// 宁可漏纠，绝不删错用户内容
```

---

## 判定规则

一个词要被纠，必须**全部**满足：

1. 英文模式开启，且长度 ≥ 3（`is`/`it` 这种太短，纠错风险高于收益）
2. 不在**技术词白名单**里（`redis`/`nginx`/`docker`/`http`/`json` …）
3. 不是已知正确词
4. 要么命中**已知错拼表**，要么在词表里存在**唯一一个**编辑距离为 1 的词

第 4 条是核心。编辑距离 1 若命中多个候选就**放弃**：
`cat` 可能是 `car`/`cut`/`can`，歧义太大，宁可不纠。

### 为什么要有「已知错拼表」

有些 typo 靠编辑距离判断会有歧义，必须查表。例如 `teh` 编辑距离 1 的
候选有一堆（`ten`/`tea`/`the`/`tech`），只有查表才知道用户要 `the`。

表里都是 English 世界里公认的 typo，误纠概率极低。

### 为什么要有「技术词白名单」

这是被测试逼出来的。早期版本把 `redis` 纠成了 `error`、`nginx` 纠成
`night` —— 都是编辑距离 1 的唯一候选，但用户会直接崩溃。

白名单里的词一律原样放过，判定顺序放在最前面。

---

## 踩过的坑

### 编辑距离必须看「全串」

早期实现找到前两处不同就下结论，没检查后面的字符：

```
success vs usually
  ↑ 前两位正好是 su ↔ us（换位）
  → 判定为编辑距离 1  →  把正确词 success 纠成了 usually
```

实际上后面 5 位完全不同。修法：换位/替换判定后**必须继续比对剩余字符**。

教训：编辑距离是全串性质，不能只看局部。见 `isSingleEditAway` 的注释。

### 大小写要保留

`Teh` 应该纠成 `The` 而不是 `the`，`TEH` → `THE`。
由 `matchCase` 处理，让结果的大小写风格跟原词一致。

---

## 测试

```sh
sh tools/all_checks.sh     # 全部检查（含英文纠错 3 项）
```

单独的测试：

| 脚本 | 验证内容 |
|---|---|
| `tools/test_edit_distance.py` | 编辑距离算法，含上面那个 `success` 回归 |
| `tools/test_corrector_words.py` | 词表判定：召回 / 误纠（当前 32/32 召回、0/73 误纠） |
| `tools/test_english_buffer.py` | 缓冲状态机，12 个场景（边界结算、大小写、白名单…） |

测试脚本从 Kotlin 源码里**直接抽取词表**，所以不会出现两边词表不一致。

⚠️ 沙箱里 JVM 跑不起来（PRoot 禁止可执行内存映射），**本地跑不了 kotlinc**。
算法逻辑用 Python 重写验证，实际编译结果以 CI 为准。
如果改了 Kotlin 里的算法，记得同步改测试里的实现。

---

## 目前没做的

诚实记录，避免以后误以为已经支持：

- **撤销纠错**：用户想"我就要打 teh"目前没有快捷入口。
  `EnglishCorrector.reject()` 已实现但没接 UI 和按键。
  做这个需要拦截退格键路径（走 `postRimeJob` → Rime `processKey`，
  不经过 `commitText`），改动面比纠错本身还大，所以先不做。
- **用户词频学习**：`prefer()` 同理，已实现未接线。
- **开关设置项**：目前跟着 `ascii_mode` 自动开关，没有单独的用户设置。
  想关掉的话切回中文模式即可。
