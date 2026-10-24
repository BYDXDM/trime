# 交接文档 · 白洲梓输入法（trime fork）

> 重写于 2026-10-10，2026-10-11 补记第三轮，2026-10-18 补记第四轮（见 §0.1），
> 2026-10-24 补记第五轮（见 **§0.3**）。
> **当前状态以 §0.3 为准**：第三/四轮 + 五个真机 bug 已提交（`7059b57c`，28 个文件），
> 新包已出（14227148 字节）并装到真机，但功能项大多**还没逐条验收**。
> 本文档记录「已经做过什么」「为什么这么做」「还剩什么没做」，以及踩过的坑。

---

## 0. 一句话现状

崩溃、候选栏空白/字号、外观（去边框、只留背景 GIF）三项已修完并推送。
2026-10-10～11 接手后完成：用户**亲眼确认**去边框观感 ✅、**首次发出可下载的签名包** ✅
（GitHub Release）、真机全量回归 ✅。

**本任新增功能**：

- 「删除键上滑 = 清空输入框正文 + 待选拼音」（§2.4）
- 「颜文字面板」（§2.6）
- 「主界面近 7 天输入走势」（§2.6）
- 顺带修掉 7 个既有符号面板的底栏权重缺陷（90 → 100，§2.6）
- 「自定义短语编辑界面」（§2.8，真机端到端已验证）
- 候选栏横向滚动能滑到末端（§2.7）
- 背景 GIF 换素材，循环缝 2.76× → 0.845×（§2.9）
- 修右上角 `)` 误触（§2.10）、左括号自动补右括号（§2.11，仅英文模式）
- 候选栏不再显示缺字形的豆腐块（§2.12，未装机验证）

**下一批功能的方向与优先级见 `PLAN-功能扩展.md`**（P0/P1/P2 分级 + 实施要点）。

**验证环境**：真机掉线后改用 AVD `smoke`（API 35 / x86_64）完成功能验证；
启动方法、坑与坐标见 **§7.1**。

> ⚠ `dist/trime-release.apk` 已被本任重构建覆盖，不再是 `6fd825f7` 的产物。
> 接手时基线：HEAD = `6fd825f7d53c695558350ad8ffe9ce52f636c788`。

### 0.1 2026-10-11 第三轮 + 2026-10-18 第四轮

第三轮的 ① ② ③ 全部已落地；第四轮又做了 ④ ⑤ ⑥ ⑦。除 ⑦ 外都已编译、单测通过。

| # | 工作 | 状态 |
|---|---|---|
| ① | 自定义短语编辑界面（新功能） | ✅ **真机端到端验证通过**（`ceshi` → 首候选 `OpenAI`），见 §2.8 |
| ② | 候选栏横向滚动只能滑一屏（bug 修复） | ✅ 已出包、**真机实测有效**（1066 → 2102 px），根因见 §2.7 |
| ③ | 清死代码 `clearCompositionIfAny()` | ✅ 已按「有意的行为变更」处理完（不是死代码，见下） |
| ④ | 背景 GIF 换素材 + 循环缝优化 | ⚠ 离线指标已达标（0.845×），**未装机看观感**，见 §2.9 |
| ⑤ | 右上角 `)` 误触 | ⚠ 布局检查通过，**未装机验证**，见 §2.10 |
| ⑥ | 左括号自动补右括号（英文模式） | ⚠ 单测通过，**未装机验证**；中文模式是否也补全角待定，见 §2.11 |
| ⑦ | 候选栏「空白词条」（缺字形豆腐块） | ⚠ 代码接完、编译+单测通过，**未装机验证**，见 §2.12 |

**① 自定义短语编辑界面**

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/osfans/trime/data/userdict/CustomPhraseStore.kt` | **新增**。纯逻辑：解析/序列化/读写 `custom_phrase.txt`；`data class CustomPhrase(text, code, weight = 0)`；`parse` / `serialize` / `load` / `save` / `upsert` / `remove`；空列表时删除文件 |
| `app/src/main/java/com/osfans/trime/ui/main/settings/customphrase/CustomPhraseListEntryUi.kt`、`CustomPhraseListAdapter.kt`、`CustomPhraseListUi.kt`、`CustomPhraseDialog.kt`、`CustomPhraseFragment.kt` | **新增**。列表界面 / 列表项 / 适配器 / 新增-编辑对话框 / 宿主 Fragment |
| `app/src/main/java/com/osfans/trime/data/base/DataManager.kt` | L44 新增 `const val CUSTOM_PHRASE_FILE_NAME = "custom_phrase.txt"`，并加进 L46 的 `POST_SCHEMA_DEPLOY_EXPORT_FILES`（L50）→ 参与部署后导出、不会被 OrphanCleaner 当孤儿删 |
| `app/src/main/java/com/osfans/trime/ui/main/NavigationRoute.kt`、`app/src/main/java/com/osfans/trime/ui/main/MainFragment.kt`、`app/src/main/res/values/strings.xml` + `values-zh-rCN` + `values-zh-rTW` | 路由、设置入口、三份字串 |
| `app/src/test/java/com/osfans/trime/data/userdict/CustomPhraseStoreTest.kt` | **新增**单测（13 例） |

- ✅ 真机已验证：设置主页在「用户词典」与「配置」之间出现「自定义短语」入口。
- ✅ **真机端到端已通过**：新增 `OpenAI` / `ceshi` / 权重 10000 → 打字 `ceshi` → 候选栏首项即 `OpenAI`。

**③ `clearCompositionIfAny()` 的处理结论**

原判断「无调用者 = 死代码」**不成立**：`hasComposition()` 在 inline preedit 与
SegmentsWindow 路径下会为 true，`KEYCODE_DEL` 走它会把预编辑一次清空。
按「有意的行为变更」处理：`KeyEvent.KEYCODE_DEL -> handleDefaultKeyAction(action)`，
函数本体与配套注释一并删除，删除键恢复为「逐字删」。`NEXT-STEPS.md` §5 里
「无调用者」的前提已订正。

**第四轮产物**：`dist/` 里的包已过期（第四轮尚未出包）。

---

## 1. 项目与环境

| 项 | 值 |
|---|---|
| 仓库 | `D:\trime-build`（fork 自 osfans/trime，Android 输入法，Kotlin + native librime） |
| 远端 | `https://github.com/BYDXDM/trime.git`，工作分支 **`develop`** |
| 产物 | `D:\trime-build\dist\trime-release.apk`（14212811 bytes，arm64 单包，**已签名** release） |
| 真机 | `EAT0220316001355` = HUAWEI ANA-TN00 / HarmonyOS 4.2 / API 31 / arm64-v8a / 1080×2340 / density 480（**3.0**） |
| adb | `D:\Android\Sdk\platform-tools\adb.exe` |
| 构建 | `$env:BUILD_ABI="arm64-v8a"; .\gradlew.bat :app:assembleRelease`（见 §7） |
| 打包标识 | IME 组件 `com.osfans.trime/.ime.core.TrimeInputMethodService`；主界面 `com.osfans.trime/.ui.main.MainActivity` |

### ⚠ adb 不稳定（老问题，2026-10-10 复查时设备未连接）

手机**闪连**：`adb devices` 里出现几秒就消失。恢复手法：

```powershell
$ADB='D:\Android\Sdk\platform-tools\adb.exe'
& $ADB kill-server; Start-Sleep 3; & $ADB start-server; Start-Sleep 5; & $ADB devices -l
```
**所有 adb 命令都应写成重试循环。** 2026-10-10 复写本文档时 USB 上
`Get-PnpDevice` 已枚举不到任何 ADB/Android/HDB 设备，`adb devices` 为空——
这是**手机没插或线松了**，不是代码问题。

### ⚠ 抓图必须走 pull，不能走重定向

```powershell
# ✗ PowerShell 会把重定向写成 UTF-16，PNG 直接损坏（Unsupported or malformed image data）
adb exec-out screencap -p > f.png
# ✓
adb shell screencap -p /sdcard/x.png ; adb pull /sdcard/x.png .
```

- 安装 APK 必须带 `-t`：`adb install -r -t <apk>`（不带时失败且**错误信息为空字符串**）。
- **不要用 `adb reconnect offline`**——在这台设备上它会把设备整个弄没。

---

## 2. 已完成的工作

> §2.1–§2.8 全部在真机上验证过。§2.9–§2.12 是第四轮的改动，
> 只做到「编译 + 单测通过」，**装机验证还没做**，每条末尾都标了状态。

### 2.1 全新安装崩溃（最重要的一个）

`TrimeInputMethodService.replaceInputViews()` 末尾读 `currentInputEditorInfo`——
它是 `InputMethodService` 的**框架属性，在 `onStartInput` 之前为 null**。
而「Rime 就绪后补建输入视图」这段 post 可能早于 `onStartInput`，于是传 null →
读 `EditorInfo.imeOptions` → NPE → **崩溃循环**。

真机堆栈：
```
FATAL EXCEPTION: main
java.lang.NullPointerException: Attempt to read from field
    'int android.view.inputmethod.EditorInfo.imeOptions' on a null object reference
  at com.osfans.trime.ime.core.InputView.updateEnterKeyLabel
  at com.osfans.trime.ime.core.TrimeInputMethodService.replaceInputViews
  at TrimeInputMethodService$$ExternalSyntheticLambda7.run
```
**这个 bug 在模拟器上完全不复现**（时序不同）。靠静态分析猜（R8、资源压缩、
native 的 -O2、@hide 常量）**全部猜错**，插真机一次命中。

修复：`currentInputEditorInfo?.let { inputView?.updateEnterKeyLabel(it) }`
（`app/src/main/java/com/osfans/trime/ime/core/TrimeInputMethodService.kt`），
并给 `handleReturnKey()` 里同源的 `currentInputEditorInfo.run {}` 加了保护。
验证：真机 `FATAL EXCEPTION` 计数归零。

### 2.2 候选栏整行空白（接手时用户报的「候选字太小/越改越小」）

**真实症状不是「小」，是「整行零墨迹」。** 用像素投影测出候选栏所在带
y≈1453–1499 在 `_shot3`…`_shot7` 上 `inkpx` 全为 0（`_shot1/_shot2` 还有墨迹），
裁剪目视确认是一条**完全空白的横带**，只有左侧「…」和右侧下箭头。

根因是三处叠加：

1. `CompactCandidateViewAdapter.onCreateViewHolder` 里 `minimumWidth = dp(40)`
   —— 格子最窄只有 40dp（120px）；
2. Flexbox 在 `NOWRAP` 下仍按 flexBasis 分配空间，item 的 `flexShrink` **默认为 1**
   —— 候选一多，每格都被压到 40dp；
3. `CandidateItemUi` 的两个 `AutoScaleTextView` 用 `Mode.Proportional`，
   公式 `val textScale = min(textXScale, textYScale)` **没有下限**
   —— 40dp 盒子装 18sp 的字 → 缩放比被压到 ≈0 → 画布上零墨迹。

三处修复（都已提交在 `cc6318bd`）：

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/osfans/trime/ime/candidates/compact/CompactCandidateViewAdapter.kt` | `layoutParams = FlexboxLayoutManager.LayoutParams(wrapContent, matchParent).apply { flexShrink = 0f }` |
| `app/src/main/java/com/osfans/trime/ime/candidates/CandidateItemUi.kt` | text 与 comment 两个 `AutoScaleTextView` 都改 `scaleMode = AutoScaleTextView.Mode.None`（文字不缩放，放不下就横向溢出，靠候选栏横向滚动滑出） |
| `app/src/main/java/com/osfans/trime/ime/core/AutoScaleTextView.kt` | 兜底：Proportional 分支支持 `minScale`（默认 0 保持上游行为） |

> ⚠ 注意 `AutoScaleTextView.kt` 里 `enum class Mode { None, Horizontal, Proportional }`。
> 临时加过的 `minScale = 0.85` 后来**被完整删除**了——因为 `Mode.None` 已经根治，
> 加下限只会掩盖问题。不要以为它还在。

### 2.3 外观（用户 m00814：「去掉边框，我喜欢只看到底部的 gif」）

用户经 `ask_user_question` 同时勾选了 ①候选栏下方的灰色横条 ②每个按键的圆角底框。

| 项 | 改动 |
|---|---|
| 候选栏灰横条 | 就是 RecyclerView 的**水平滚动条**——`CompactCandidateDelegate.kt` 内 `context.recyclerView(R.id.candidate_view) { isHorizontalScrollBarEnabled = false }`（判定依据：其 x 起点 9px = dp(3)，正好等于 `CandidateUi` 的 `startOfParent(dp(candidatePadding/2))`；宽 387px ≈ 可见/总候选比 0.39） |
| 按键圆角底框 | `app/src/main/assets/shared/trime.yaml` 的 `default` 方案：`key_back_color: 0x00000000`、`off_key_back_color: 0x00000000` |
| 保留项 | `on_key_back_color: 0x3975CE` **故意保留**——它是中/英与大小写状态的唯一视觉信号 |

真机验证（`tools/verify-ui/_probe.py` 沿行取色）：y=1575/1580/1585 全为均匀
`rgb(240,240,240)`，旧的那条 `x 9..395 rgb(144,144,156)` 已消失；底部
符/123/空格/中/Enter 行完整，无回归。

**只改了 `default` 方案**；`dark`/`user_light`/`user_dark` 仍各自带着
`key_back_color`（0x3C4043 / 0xB3FFFFFF / 0x8C000000）与 `key_border_color`。

### 2.4 删除键上滑清空待选拼音（2026-10-10 新增）

用户要求（原话）：「我可以直接删除待选的拼音（删除键上滑）」。

**改动前的真实行为（真机实测，与旧文档/代码注释不符）**：

| 手势 | 主题绑定 | 实测行为 |
|---|---|---|
| 单击退格 | `backspace_clear` → `send: BackSpace` | 删 **1 个字符** |
| 上滑退格 | `Clear` → `text: "{Control+a}{BackSpace}"` | 作用于**编辑器正文**（把整篇笔记全选删掉），对拼音**无效** |
| 左滑退格 | `BackToPreviousSyllable` | 删一个音节（`Control+BackSpace`） |

两条根因：

1. **上滑绑错了对象**：`Clear` 是「全选+删除」的编辑器动作，根本不碰 Rime 预编辑串。
2. **代码里「单击清空拼音」是死分支**：`CommonKeyboardActionListener` 写了
   `KEYCODE_DEL -> if (!clearCompositionIfAny())`，但 `clearCompositionIfAny()`
   依赖 `service.hasComposition()`（= `composingText.isNotEmpty()`），真机预编辑期间
   该判定**实测为假** → 分支恒不生效，一直退化成逐字删。
   （旧 §3 记载的「退格清空拼音」**从未真正生效**。）

**修复**（用户确认的分工：**上滑 = 清空 / 单击 = 逐字删**）：

| 文件 | 改动 |
|---|---|
| `app/src/main/assets/shared/trime.yaml` | 新增预设 `ClearAll: { label: 清空, send: FUNCTION, command: clear_all }`；两处退格键（`my_pinyin`、`my_english`）`swipe_up: Clear` → `swipe_up: ClearAll` |
| `app/src/main/java/com/osfans/trime/ime/keyboard/CommonKeyboardActionListener.kt` | `handleFunctionCommand` 新增 `"clear_all" -> clearInputAndComposition()`；新增私有 `clearInputAndComposition()`（**不做** `hasComposition()` 前置判断，避免重蹈死分支覆辙） |

⚠ **「清空」的实现踩了三个坑**（前两版都被实测退回）：

1. **第一版**：`swipe_up` 只清 Rime 预编辑串 → 正文一个字没删。
   用户当场退回：「不是，我原来的上滑删除清空输入框呢」。
2. **第二版**：在 `handleFunctionCommand` 里调 `onText("{Control+a}{BackSpace}")`
   → **完全不生效**（正文没被删）。同一个字符串走主题的 `text:` 分支是好的，
   说明问题出在**调用点**，不在字符串本身。
   **结论：别在这条路径上依赖组合键回放。**
3. **第三版（现行）**：直接操作 `InputConnection` —— 光标移到末尾 + 删掉光标前的
   全部内容。这个语义不依赖输入框对 Ctrl+A 的支持，各输入框行为一致。

最终实现：

```kotlin
private fun clearInputAndComposition() {
    rime.launchOnReady { api ->
        service.lifecycleScope.launch {
            api.clearComposition()   // 1) 清 Rime 预编辑串
            delay(CLEAR_SETTLE_MS)   // 2) 等清空结果落到输入框
            clearInputBox()          // 3) 光标移到末尾 + deleteSurroundingText
        }
    }
}
private fun clearInputBox() {
    val ic = service.currentInputConnection ?: return
    ic.beginBatchEdit()
    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END))
    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_END))
    ic.deleteSurroundingText(CLEAR_MAX_CHARS, 0)
    ic.endBatchEdit()
}
```

真机 + 模拟器验证（2026-10-11）：
- 正文有「你好」+ 待选拼音 `ni hao` 时上滑 → **正文与拼音都清空** ✅
- 单击退格仍逐字删（`ni hao` → `ni ha`）✅

> 遗留：`clearCompositionIfAny()` 现在成了无用代码（`KEYCODE_DEL` 分支恒返回 false）。
> 若日后有人「修好」`hasComposition()`，单击行为会静默变成整体清空 —— 与「单击逐字删」
> 的约定冲突，改动前请先看这里。

### 2.5 中英切换键的高亮：文档描述与实现不符（非回归）

旧 §2.3 称 `on_key_back_color: 0x3975CE` 是「中/英与大小写状态的唯一视觉信号」。
实测只对**修饰键**成立：

- Shift 激活 → 键面亮蓝底 `rgb(48,112,192)`（= 0x3975CE 的量化值）✅
- 中/EN 键 → **始终不亮蓝底**。原因：`Key.appearanceType` 只有「修饰键且 modifier
  命中」或 `isOn` 才取 `on` 态，而 `Mode_switch`（`toggle: ascii_mode`）两者都不满足，
  只能靠标签 `中 ↔ EN` 区分。

这不是 6fd825f7 引入的回归（该键原用 `off_key_back_color: 0xD8DBE0` 的浅灰底，
随「去边框」一并变透明），但**文档描述需要更正**。

### 2.6 颜文字面板 + 主界面近 7 天走势（2026-10-10 新增）

**颜文字面板**：符号面板新增第 8 组「颜文字」，12 个常用 kaomoji 一键上屏。

| 文件 | 改动 |
|---|---|
| `assets/shared/panels.yaml` | 新增 `kaomoji_panel`（4 键/行 × 3 行）；7 个既有符号面板的页签行从 8 项扩到 9 项 |
| `tools/sync_panels.py` | `PANEL_MAP` 加 `kaomoji_panel` → 键盘名 `kaomoji` |
| `assets/shared/trime.yaml` | `preset_keys` 加 `SymbolsTab_kaomoji`（页签行由 sync 脚本重新生成） |
| 测试 | `ThemeGoldenTest` / `ThemeDslExpanderTest` / `DefaultKeyboardResolutionTest` / `ThemeProductionLoadTest` 的键盘与预设计数基线 11→12、42→43 |

⚠ **两条硬约束（踩过）**：

1. 颜文字比单个符号长得多，**必须** `width: 25` + `key_text_size: 10`。
   不写 `key_text_size` 会走 `key_long_text_size`(14sp) → 溢出键框被裁。
2. 页签行权重 = **8 页签 × 11 + ABC × 12 = 100**。`tools/check_layout.py` 要求
   每行**精确**等于 100.00（容差 1e-6），差 0.5 都会报错。

> 顺带修掉的既有缺陷：7 个符号面板的底栏权重只有 **90**
> （`123(10)+☺(10)+空格(30)+，(10)+中英(15)+⏎(15)`），`check_layout.py` 一直在报
> 「会错位/换行」。本轮把 `空格` 提到 **40** → 全部 100。
> ⚠ 改这条时注意：`number` 面板底栏也含 `空格`，但它是 7 键布局
> （多一个 `ABC`、少一个 `☺`），**保持 30** 才是 100，别一起批量替换。

**主界面近 7 天走势**：`TypedCharCounter` 新增 `history(context, days)`（升序、缺日补 0），
`MainFragment` 用它生成 `▁▂▃▄▅▆▇█` 一行迷你柱状图，作为「今日已输入 N 字」的 summary；
新增字串 `typed_week_spark`。全 0 时不显示 summary。

### 2.7 候选栏横向滚动只能滑一屏（2026-10-11 修复，**已真机实测**）

**症状**：候选词右滑只能滑一部分，滑不出所有候选。

**根因**：`app/src/main/java/com/osfans/trime/ime/candidates/compact/CompactCandidateDelegate.kt`
里的匿名 `FlexboxLayoutManager` 子类，在 `NOWRAP` 单行模式下走父类
`scrollHorizontallyBy` → `handleScrollingMainOrientation` → `fill()`。
`fill()` 的循环 `while ((remainingSpace > 0 || mInfinite) && hasMore(...))`
在**只有一条 flex line** 时最多转一圈，而每圈扣的是 `flexLine.getCrossSize()`（**行高**）
而不是主轴溢出量 → `consumed` 恒在行高量级 → 位移被钳死。

**实测证据**：第一次右滑刚好 **1066px**（≈ 一屏），之后无论滑多少次，像素逐列**完全一致**。

**修法**：在同一匿名子类里 **override `scrollHorizontallyBy`**，自己
`offsetChildrenHorizontal(-real)`，边界按「内容总宽 − 视口宽」裁剪；
同时 override `computeHorizontalScrollRange` / `computeHorizontalScrollOffset` /
`computeHorizontalScrollExtent` 给准数。
理由：NOWRAP 下单行会一次性 measure + layout 全部候选，子项本来都在，**平移即可**。

**实测结果**：修复前总位移恒 **1066px** → 修复后达 **2102px**
（能滚到内容末端，16 个候选，与 JNI 上限一致）。

**产物**：`app/build/outputs/apk/release/trime-arm64-v8a-release.apk`
（14224974 字节，已签名 `CN=BYDXDM trime`，已装真机 `EAT0220316001355`）。
⚠ 第四轮又改了候选栏（见 §2.12），这个包已不是最新。

### 2.8 自定义短语编辑界面（2026-10-11 新增，真机已验证）

**机制**（这一步走错过，记下来）：`mydomain.schema.yaml` 的 `custom_phrase` 用的是
`db_class: stabledb`，librime 的 `StableDb` 从 **`user_data_dir`** 读
`custom_phrase.txt`（`db.cc` 的 `FallbackResourceResolver` root=user_data_dir、
fallback=shared_data_dir），格式是 `短语<TAB>编码[<TAB>权重]`，权重用 `std::stoi`
解析成**整数** commits。所以既不是 `.dict.yaml`（那要重编词典），也不是 userdb。

- `#` 开头的行是注释；但**绝不能写 `# no comment`**——librime 见到它会关掉注释解析。
- 文件放在 `<userDataDir>/custom_phrase.txt`，`DataManager.sync()` 不动用户自建文件，
  所以升级/部署不会覆盖它；但它必须进 `POST_SCHEMA_DEPLOY_EXPORT_FILES`，否则
  EXTERNAL_SYNC 模式下 `OrphanCleaner` 会把它当孤儿删掉。

**保存后重载**：`RimeDaemon.createSession("custom-phrase-persist")` →
`runOnReady { updateConfig() }`（`exitRime(); startRime(false)`，重建 translator
即重新读 `.txt`，不需要全量 deploy），再按需
`RimeDataSync.exportConfigFilesToExternal`。

**真机验证**：`OpenAI` / `ceshi` / 权重 10000 → 打 `ceshi` 首候选就是 `OpenAI`，
排在 `测试/车市/侧室` 之前，说明 `initial_quality: 2` 生效。

### 2.9 背景 GIF 换素材（2026-10-18，**未装机验证**）

源素材 `C:\Users\Administrator\Downloads\Desktop 2026.10.11 - 12.17.17.02.mp4`
（1920×1080 / 60fps / 1072 帧）。取右侧方形 `X=643 Y=67 S=971`、帧 `522..702`
step 6（31 帧），裁掉底部任务栏（y ≥ 1040）与顶部 OSD（y ≤ 66），写入
`app/src/main/assets/shared/backgrounds/mybg/preview.gif`。

循环缝：首尾帧差 MAD **3.447** = 内部中位的 **0.845×**（旧素材 2.76×，所以旧版会「卡一下」）；
体积 3.9 MB → **2.9 MB**。以上都是离线算出来的数，**装机后的实际观感还没看过**。

### 2.10 右上角 `)` 误触（2026-10-18，**未装机验证**）

`app/src/main/assets/shared/trime.yaml` 删掉 `my_pinyin` 与 `my_english` 两处
p 键的 `swipe_down: ')'`。理由：p 在键盘右上角，拇指斜向够它天然带出向下位移，
而键面角标写的是 `0`——用户根本看不出下滑会打出右括号，属「无提示的隐形绑定」。
`)` 仍有 5 条可达路径：x 上滑（两套键盘）、中文符号面板长按、symbols_en 面板、123 面板。
`python tools/check_layout.py` 已通过（12 个键盘、行宽全 100）。

### 2.11 左括号自动补右括号（2026-10-18，**未装机验证**）

`app/src/main/java/com/osfans/trime/ime/keyboard/CommonKeyboardActionListener.kt`：
`AUTO_PAIR_CLOSERS` 加 `(` `[` `{`；新增 `AUTO_PAIR_BY_KEYCODE`
（`[` → `KEYCODE_LEFT_BRACKET`、`{` → 符号码 10011，这两个的 `KeyAction.text` 为空，
所以走键码分支）。单测在 `TypingEnhancementsTest.kt`，需要
`beforeTest { KeyCode.androidKeyNameToCode = { 0 } }`（本模块没有开
`isReturnDefaultValues`）。单测已过。

⚠ **只在英文模式生效**：`commitAutoPair` 里有 `text[0].isAsciiPrintable() &&
!isAsciiMode → return false`，中文模式故意放行给 rime 标点表转全角 `（`。
**中文模式要不要也补全角 `（）`，待用户决定。**

⚠ 一个没改的已知风险：`commitPairedText` 用 `sendKeyEvent(DPAD_LEFT)` 回退光标，
WebView / 老 EditText 可能吞掉这个导航键，那样光标会停在 `)` 右边。
`TrimeInputMethodService.kt` 里有 `setSelection` 的先例（L1036/1049/1063），
但那要 `getExtractedText` 取绝对偏移，在 `beginBatchEdit` 内不可靠，所以没换。

### 2.12 候选栏「空白词条」= 缺字形豆腐块（2026-10-18，**未装机验证**）

**根因**：不是渲染 bug。librime 会返回**补充平面汉字**（CJK 扩展 B/C/D，U+20000 以上），
而 `candidate_font`（用户没放 `han.ttf` 时就是 `Typeface.DEFAULT`）没有这些码位，
`AutoScaleTextView` 只能画 `.notdef` 豆腐块。证据：打 `a` 的 13 个候选里恰好 3 个
是补充平面字，屏幕上恰好 3 个豆腐块。

**否决过的方案**：

- `Paint.hasGlyph`——它只查当前 Typeface 自己的字形表、**不查系统 fallback 链**，
  用 `Typeface.DEFAULT` 时连「你」都判 false，会把整栏中文候选滤光。
- 剔词库——`luna_pinyin.dict.yaml` 70803 条里 15822 条含补充平面码位，
  `stroke.dict.yaml` 111510 行里 68937 行，删了会连生僻字一起删掉。
- 打包 Ext-B 字体——仓库里没有字体文件。

**做法**：`app/src/main/java/com/osfans/trime/ime/candidates/CandidateGlyphFilter.kt`
按**码位**判断（`Character.codePointAt > 0xFFFF` → 过滤），BMP 一律放行。
`CompactCandidateViewAdapter` 存 `originalIndices`（过滤后第 i 项在 librime 原始列表
里的下标）+ `filteredOut`，`CompactCandidateDelegate` 的点击/长按改走
`originalIndex(position)`（`selectCandidate` 收的是 librime 下标，直接用位置会选错词）。

`refreshUnrolled` 的判据改成 `childCount + adapter.filteredOut >= adapter.total`，
否则过滤掉几条后展开按钮会一直显示。`total` 保持 librime 原始条数（展开面板的
`CandidatesPagingSource` 按下标取页）。

**已知作用域缺口**：展开面板（`CandidatesPagingSource` / `BaseUnrolledCandidateWindow`）
与 `ime/composition/CandidatesView.kt` 仍会显示豆腐块。

**验证状态**：`:app:compileDebugKotlin` 与 `:app:testDebugUnitTest` 通过
（新增 `CandidateGlyphFilterTest`，5 例）；**还没装机看图**。


---

## 3. 已提交的 11 个 commit（基线 `4550be59`）

```
6fd825f7  feat(theme)   候选栏去掉滚动条、按键去掉圆角底框，键盘只留背景图
4e46674c  ci            精简产物为 arm64 单包
38925412  fix(ui)       首页背景图改用 ImageView + centerCrop
cc6318bd  fix(candidate)候选栏整行空白 + 字号/单位/间距
d20ffe99  fix(ime)      修复首次进入输入场景时的崩溃循环
e1bb9bcf  docs          补分析报告、进度记录与自审章节
13fa9986  ci            支持构建已签名 release 包并下载产物
d8e6657f  feat(theme)   符号按搜狗分 7 组、无边框、候选栏横向滚动、背景图与动画
5ee87fc8  feat(ime)     中英切换接线、退格清空拼音、今日字数、切换输入法提示
da977214  fix(schema)   修复候选极少/组不成句，并让垂直领域词不再霸榜
77e3bc39  fix(rime)     修复全新安装时 Rime 不启动导致的打不出中文
4550be59  test(theme)   ← 基线（改黄金测试：首键上滑=角标符号）
```

**全部 11 个已 push 到 `origin/develop`**（`4550be59..6fd825f7`）。
⚠ 那是**第三、四轮之前**的状态：10-11 与 10-18 两轮的改动都还在工作树里，
清单见 §8.1。

---

## 4. 上一版文档里被推翻的两条结论（**勿再沿用**）

这两条写在旧 `HANDOVER.md` §3 与 §5，**是错的**，实测已推翻：

1. ❌「候选字号『22 当 px 用、实际只有 7.3sp』」——**错**。HEAD 里
   `candidate_text_size` 本来就是 **18sp**，`CandidateItemUi` 走的是
   `TextView.textSize`（入参即 sp）。真正的坑是**有人给它套了一层 `ctx.sp()`**：
   本仓库 `Context.sp()` 返回的是 **px**（`TypedValue.applyDimension(COMPLEX_UNIT_SP, ...)`），
   而 `TextView.textSize` 入参是 sp → **乘了两次 density**，18 变 54sp=162px，
   这才是当时看到的「字号太大、显示框太小」。该包装已撤销，
   `import com.osfans.trime.util.sp` 已删。
2. ❌「越改越小的根因是 `candidate_spacing`」——**不准确**。`candidate_spacing`
   确实会吃掉格宽（见 §5.2），但那只是放大器；真根因是 §2.2 的三处叠加。

---

## 5. 关键知识（全部踩过，血泪）

### 5.1 Trime 主题的**单位是混的**

| 值 | 单位 | 证据 |
|---|---|---|
| `key_text_size` | **sp** | `KeyView.kt:316` `sp(key.keyTextSize)` |
| `candidate_text_size` / `comment_text_size` | **sp** | `CandidateItemUi` 直接赋给 `TextView.textSize` |
| `candidate_view_height` / `comment_height` / `candidate_padding` | **dp** | `dp(theme.generalStyle.candidateViewHeight)` 等 |
| `candidate_spacing` | 会被 `max(spacing, dp(spacing))` **放大** | `CompactCandidateDelegate` |

**改主题前必须先确认该值在代码里的取用处。** 两条硬规则：

- `TextView.textSize = x` 把 x 当 **sp**；本仓库 `Context.sp()` 返回 **px**。
  **给 TextView 设置字号时不要再套 `sp()`**，否则乘两次 density。
  `sp()` 只该用在 `Paint.textSize` 上。
- 候选栏总高 = `candidateViewHeight + commentHeight`（`ime/bar/InputBarDelegate.kt:76`）。
  默认方案当前是 `32 + 12 = 44dp`（=132px @3.0）。

### 5.2 `candidate_spacing` 会**吃掉候选格子宽度**

```kotlin
separatorDrawable.intrinsicWidth = max(candidateSpacing, dp(candidateSpacing))
layoutMinWidth = w / maxSpanCount - separatorDrawable.intrinsicWidth
```
设 16 → 实际 **48px** → 每格被扣 48px → 候选被压成细条甚至看不见。
**填 6（实际 18px）才对。**「间隔可见」和「候选够宽」是同一个旋钮。

### 5.3 `candidate_separator_color` 是候选间隔的**唯一载体**

候选之间的「间隔」**不是空白**，而是 `FlexboxVerticalDecoration` 用
`candidateSpacing` 当尺寸、`candidateSeparatorColor` 当填充画的**一个色块**。
把它降到 10% alpha 就等于把间隔变透明 → 用户反馈过「没有间隔」。

### 5.4 强制重新部署词典

```powershell
adb shell am broadcast -a com.osfans.trime.action.DEPLOY -p com.osfans.trime
```
- action 在 `receiver/RimeIntentReceiver.kt:39`，值 `com.osfans.trime.action.DEPLOY`
- receiver 是**动态注册**的，所以用 `-p <包名>` 而不是 `-n <组件>`
- ⚠ `DataManager` **只在资产校验和变化时**才自动重部署。
  **单纯删掉 `rime/build/*.bin` 不会触发重建**——上一任误删过用户手机的词典产物，
  靠这个广播救回来的。**不要重复这个操作。**

### 5.5 主题文件是生成的

`trime.yaml` 里的 `symbols`/`number`/`emoji` 等键盘由
`tools/sync_panels.py` **从 `panels.yaml` 生成**（文件里写了「不要直接改这里」）。
改完跑 `python tools/sync_panels.py`，`--check` 自检。

### 5.6 `border_color` 是死键

`default` 有 `candidate_border_color: 0x33D8DBE0`（`border_color` 默认 0x33D8DBE0 /
dark 0x332C2F33），但**全代码库没有任何消费者**，改它没有任何效果。

### 5.7 主题 -> 真机生效

改 `app/src/main/assets/shared/trime.yaml` 后必须**重新构建并安装 APK**，
装完在系统设置里重选一次输入法（或切走再切回）才能看到新主题。

---

## 6. 构建、测试与产物

```powershell
cd D:\trime-build
$env:BUILD_ABI = "arm64-v8a"
.\gradlew.bat :app:assembleRelease
# ⚠ -PbuildAbiOverride=arm64-v8a 不生效，必须用环境变量
# ⚠ 改 ABI 前先删 app\build\outputs\apk\release\*.apk，否则旧 ABI 的包会残留

.\gradlew.bat spotlessApply                    # ktlint 格式化（检查不通过时先跑这个）
.\gradlew.bat spotlessCheck :app:testDebugUnitTest   # 单元测试
```
- 产物名靠 `app/build.gradle.kts` 的 `archivesName = "trime"`
- `dist/` 当前只有 `trime-release.apk` + `release.jks.base64.txt`（**签名密钥，勿外传**）
- **不要把产物拷到用户桌面**——用户明确要求过。只放 `dist/`。

> ⚠⚠ **绝对不要一次编多个 ABI**（2026-10-11 踩到，差点把坏包发出去）：
>
> `BUILD_ABI=arm64-v8a,x86_64` 会让 **arm64 那个包变成坏包** —— 只有 83 个条目、
> **没有 `AndroidManifest.xml` / `resources.arsc` / `res/`**，安装时报
> `INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION: ... AndroidManifest.xml`。
> 单编 `BUILD_ABI=arm64-v8a`（**CI 就是这么编的**，所以已发布的 Release 没问题）
> 产出的包是完整的（510 条目）。
>
> **要两个 ABI 就分两次编，每次只给一个 ABI。** 编完务必自检：
>
> ```bash
> python -c "import zipfile,sys;n=zipfile.ZipFile(sys.argv[1]).namelist();print(len(n),'AndroidManifest.xml' in n,'resources.arsc' in n)" dist/trime-release.apk
> ```
>
> 期望 `510 True True`。看到 `83 False False` 就是坏包，别发。

### CI

`.github/workflows/build-fork.yml` 是唯一的 workflow。触发方式：

- **push 到 main/master/develop** → 只编 debug 包（`VARIANT` 默认 debug），**不发 Release**。
- **手动 `workflow_dispatch`** → 输入项是 `variant`（choice: `debug`/`release`/`both`）
  与 `publish_release`（boolean，默认 `true`）。要发签名包就传
  `{"ref":"develop","inputs":{"variant":"release","publish_release":"true"}}`，
  产出 GitHub Release，tag 为 `v0.1.<run_number>`。

签名 Secret **已于 2026-10-10 配置完毕**（此前从未配过，所以历史上 7 个 Release 全是
debug 包）：`KEYSTORE_BASE64` / `KEYSTORE_STORE_PASSWORD` / `KEYSTORE_KEY_ALIAS` /
`KEYSTORE_KEY_PASSWORD`，值取自本机 `release.jks` + `keystore.properties`。

> ⚠ workflow 有 `concurrency: { group: <workflow>-<ref>, cancel-in-progress: true }`。
> **CI 正在跑时不要往同一分支 push**，否则会把正在跑的构建直接取消。

> ⚠ **本机没装 `gh` CLI**，触发/查状态请走 REST API（`curl -x <proxy>`）。

---

## 7. 真机验收工具（**本机视觉模型不可用时靠它**）

`D:\trime-build\tools\verify-ui\`，纯 Python、零第三方依赖、自实现 zlib 解压与
全部 5 种 PNG filter：

| 脚本 | 用法 | 作用 |
|---|---|---|
| `_pngload.py` | 被其它脚本 import | PNG → 像素矩阵 |
| `_analyze_bar.py` | `python _analyze_bar.py <png> <y0> <y1>` | 按行投影，输出 `bg`/`min`/`inkpx`/`runs`；**`inkpx > 0` = 该行有内容** |
| `_probe.py` | `python _probe.py <png> <y> [quant]` | 沿某一行取色，打印颜色区段与 x 范围 |
| `_crop_png.py` | `python _crop_png.py <src> <dst> <x0> <y0> <x1> <y1>` | 裁图（裁完可直接 `read_image` 看） |
| `after-full.png` | — | 去边框之后的整屏证据图 |

**`read_image` 能原生识图，优先直接看图**；只有在需要精确到像素/颜色时才用上面这些脚本。

**坐标基线**（1080 宽、density 3.0）：`x = 77 + 108 * n`；数字行 y≈1600、
QWERTY y≈1709、ASDF y≈1882、ZXCV y≈2053。

### 7.1 真机连不上时：改用模拟器（2026-10-11 补）

真机 `EAT0220316001355` 会掉线。掉线时用 AVD **`smoke`**（Android 15 / API 35 / x86_64）。

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/Android/Sdk/platform-tools/adb.exe
"$ADB" start-server                 # ⚠ 必须先起 adb
cd /d/Android/Sdk/emulator
env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY \
  ./emulator.exe -avd smoke -no-snapshot -no-audio -no-boot-anim \
                 -gpu swiftshader_indirect -no-window
```

⚠ **三个坑（都踩过）**：

1. **必须用工具的后台模式启动**（`run_in_background`），**不要** `nohup ... &`。
   后者起的进程会在**本次工具调用结束时被回收**，表现为「刚 boot 完就消失」。
2. **清掉 `http_proxy` 等环境变量**：netsimd 会读它并指向一个死端口。
3. **AVD 必须选 `smoke`**。`api31` 起不来（日志停在 `Failed to load opengl32sw`）；
   另外 adb 没先起时模拟器会报 `Unable to connect to adb daemon on port: 5037`。

**装包必须用 x86_64**（模拟器是 x86_64，arm64 包装不上）。
**但要分两次编**——`BUILD_ABI=arm64-v8a,x86_64` 一次编两个会让 arm64 那个变成坏包（见 §6）：

```bash
cd /d/trime-build
export BUILD_ABI=x86_64                # 只编模拟器要的那个
./gradlew.bat :app:assembleRelease
"$ADB" install -r -t app/build/outputs/apk/release/trime-x86_64-release.apk
```

> 给真机出包时再单独 `export BUILD_ABI=arm64-v8a` 编一次。

**输入目标**：模拟器没有华为记事本。用**应用自带的测试输入框** ——
`MainActivity` 工具栏的「Test input」（看 `content-desc`）→ 面板里那个 `EditText`
占位符是 `Type text`。首次装完要先过 3 步 Setup 向导
（第 1 步选 **Use app-specific storage**，后两步直接 Done/DONE）。
用 `uiautomator dump` 拿控件 `bounds`，别靠肉眼估坐标。

**模拟器坐标**（1080×2340 / **density 440**，真机是 480，行位置不同）：

| 行 | y |
|---|---|
| QWERTY | 1613 |
| ASDF | 1784 |
| ZXCV | 1955 |
| 底栏（符/123/…） | 2132 |
| 符号面板页签行 | 1950 |

x：QWERTY `68 + 105n`；ASDF `110 + 105n`；ZXCV `235 + 105n`（⌫ 在 x≈1010）。
删除键上滑：`input swipe 1010 2030 1010 1880 250`。

⚠ **定位行位置别靠肉眼看缩略图**（会差 100px 以上）。用 `_pngload.py` 扫
「近黑像素」（`r,g,b` 全 < 45）的连续带，带中心即行中心。
删掉 `tools/verify-ui/_locate.py` 之类的一次性脚本前先想一下——这次就是靠它定的行。

⚠ **模拟器验不了的**：IME 生命周期/时序类缺陷（见 §2.1），以及候选栏字号观感
（会被挤成细条）。上滑清空这类**功能**验证可以，**时序/观感**仍须真机。

---

## 8. 还没做的

### 8.1 第三轮 + 第四轮的遗留（**全部改动仍未 commit**，见 §0.1）

1. **⑦ 候选栏豆腐块：装机验证还没做**。`CandidateGlyphFilter` 已接进紧凑候选栏，
   编译与单测通过，但没在设备上看过「打 `a` 不再出现豆腐块」。
   验的时候顺手确认：点击第 2 个候选选中的确实是屏幕上那个词（下标映射没串）。
   已知未覆盖：展开面板与 `ime/composition/CandidatesView.kt` 仍会显示豆腐块。
2. **④⑤⑥ 的装机验证**（GIF 观感、`p` 键不再出 `)`、英文模式 `(` → `()`）。
   ② 也要重测一遍——第四轮又改了候选栏，怕回归。
3. **中文模式要不要补全角 `（）`** —— 需用户拍板，代码里 `commitAutoPair` 的
   asciiMode 守卫就是那个开关位置。
4. **提交 + 出包**。工作树现在**不干净**，本轮改动的文件：
   - ` M`：`HANDOVER.md`、`NEXT-STEPS.md`、`app/src/main/assets/shared/backgrounds/mybg/preview.gif`、
     `app/src/main/assets/shared/trime.yaml`、`app/src/main/java/com/osfans/trime/data/base/DataManager.kt`、
     `app/src/main/java/com/osfans/trime/ime/candidates/compact/CompactCandidateDelegate.kt`、
     `app/src/main/java/com/osfans/trime/ime/candidates/compact/CompactCandidateViewAdapter.kt`、
     `app/src/main/java/com/osfans/trime/ime/core/TouchEventReceiverWindow.kt`、
     `app/src/main/java/com/osfans/trime/ime/keyboard/CommonKeyboardActionListener.kt`、
     `app/src/main/java/com/osfans/trime/ui/main/MainFragment.kt`、
     `app/src/main/java/com/osfans/trime/ui/main/NavigationRoute.kt`、
     `app/src/main/res/values/strings.xml` + `values-zh-rCN` + `values-zh-rTW`、
     `app/src/test/java/com/osfans/trime/ime/keyboard/TypingEnhancementsTest.kt`
   - `??`：`app/src/main/java/com/osfans/trime/data/userdict/CustomPhraseStore.kt`、
     `app/src/main/java/com/osfans/trime/ime/candidates/CandidateGlyphFilter.kt`、
     `app/src/main/java/com/osfans/trime/ui/main/settings/customphrase/`、
     `app/src/test/java/com/osfans/trime/data/userdict/CustomPhraseStoreTest.kt`、
     `app/src/test/java/com/osfans/trime/ime/candidates/CandidateGlyphFilterTest.kt`、
     `tools/verify-ui/`、28 个 `tools/gif_*.py`、6 个 `tools/review_*.py`
   - **非本轮、不要提交**：`T build-logic/gradle/wrapper/gradle-wrapper.properties`、
     `T fastlane/metadata/android/en-US/images/icon.png`（typechange），
     以及 ` m app/src/main/jni/librime-lua-deps`（子模块，**绝对不要动**）。
5. **出包**：`dist/` 里的包是第三轮的（`dist/trime-release.apk` 14215432 字节），
   不含第四轮的候选栏过滤与 GIF。`app/build/outputs/apk/release/` 里那个
   14224974 字节的包是第三轮末尾的，同样不含第四轮改动。

### 8.2 更早的遗留

1. **可选：把「上滑清空拼音」也发一版 CI 包**——`v0.1.21` 是修复**之前**构建的
   （对应 `6fd825f7`）。修复推送后需再 `workflow_dispatch` 一次
   （`variant=release` + `publish_release=true`）才会产出含修复的 `v0.1.22`。
2. `rebuild_predict_db.sh` / `relink_plugin.bat` 仍指向旧仓库路径 `D:\ai\工作\trime`，
   未跟踪、未清理（本任只删了 `_arch*.json` / `_shot*.png` 那批历史残留）。

---

## 9. 工作树里那些看起来像垃圾的东西

2026-10-10 已清理 21 个文件：`_arch.json` `_arch2.json` `_arch_payload.json`
`_del.json` `_dict_check.yaml` `_fork.json` `_hdr.txt` `_newrepo.json` `_r.json`
`_repo.json` `_schema_check.yaml` `_setup.png` `_shot1..7.png` `_t1.txt` `_t2.txt`
（备份在 `%TEMP%\trime-cleanup-20261010\`，确认无用后可删）。
2026-10-18 又删掉了 `.tmpgif/`、`.tmpv/` 与 4 个 `tools/_review_*.txt`。

`git status` 里**不属于本轮功能改动**的条目：

```
 m app/src/main/jni/librime-lua-deps         ← 子模块工作区脏，**绝对不要动**
 T build-logic/gradle/wrapper/gradle-wrapper.properties   ← typechange，非本轮
 T fastlane/metadata/android/en-US/images/icon.png        ← typechange，非本轮
?? dist/  tools/verify-ui/                   ← 产物与验收工具，保留
?? rebuild_predict_db.sh  relink_plugin.bat  ← 指向旧仓库 D:\ai\工作\trime，待定
?? tools/gif_*.py（28 个）  tools/review_*.py（6 个）  ← 一次性分析脚本，见下
```

**那 34 个脚本是一次性分析工具**：`tools/gif_*.py` 是挑 GIF 素材时算循环缝、
量化调色板、比 MAD 用的；`tools/review_*.py` 是审词库权重用的。跑完就没再用了，
但删掉的话下次想复现某个数字要重写，所以留着没删。**提交时不要带上它们**，
要清理就整体删掉。

---

## 10. 给接手人的四句话

1. **动手前先 `git status -sb`**。当前**不干净**：第三、四轮的功能改动都还在工作树里
   （清单见 §8.1），另外还有 §9 那批历史残留。别 `git checkout` / `git stash`
   清工作树，那会丢掉两轮的成果；提交时逐个点名文件，不要 `git add -A`。
2. **涉及 IME 生命周期/时序的改动，必须插真机验**。那个崩溃在模拟器上 100% 不复现，
   靠猜浪费了好几轮；模拟器 320×640 上候选还会被挤成细条，同样不能用来判断字号。
3. **改候选栏/主题数值前，先把 §5.1 的单位表看一遍**，尤其是
   「`TextView.textSize` 收 sp，而本仓库 `Context.sp()` 返回 px」这一条。
4. **不要在用户手机上做破坏性操作**（删 `rime/build/*.bin` 之类）。
   真需要重建词典时用 §5.4 的广播。

---

## 0.2 2026-10-18 11:10 停手交接（本轮 = 用户报的 5 个 bug）

> 用户明确要求：**"不论进行到哪，直接停手写接手文档"**。所以这一节是当前状态的权威描述，优先于 §0.1 和 §8.1。

### 一句话现状

第三轮 + 第四轮 + 本轮的修复**全部还在工作树里，一个 commit 都没有**（HEAD 仍是 `7df96714`）。
最新包 `app/build/outputs/apk/release/trime-arm64-v8a-release.apk` = **13379589 字节，2026-10-18 11:04:20**，已在 11:06:09 装到真机（`lastUpdateTime=2026-10-18 11:06:09`）。
但这个包**不含** `symbols.yaml` 的 @ / ~ 改动（原因见下面"坑"），且 bug 3 / 4 / 5 的真机效果**都还没验**。

### 用户本轮的 5 个 bug（原文，m00062–m00064）

1. 候选栏空白字块（豆腐块）
2. 右滑不能滑到底
3. `ba` 词库没生效
4. 帮忙调一下词库，这个词库有点难用
5. 输入框有字的时候，打字打到一半会触发括号

### 已完成（代码在树里，单测绿）

**bug 5 —— 打字中途触发括号（本轮唯一完整修完的）**

- `CommonKeyboardActionListener` 把原来内联的守卫提成纯函数
  `resolveAutoPair(text, isComposing, isAsciiMode, hasLocalComposition)`，三道顺序守卫：
  组词中 / 有本地预编辑 → 不补；非英文模式 → 不补；再查左半表。`commitAutoPair` 改调它。
- `TrimeInputMethodService.commitPairedText` 加两道兜底：`if (hasComposition()) return false`、`if (!ic.getSelectedText(0).isNullOrEmpty()) return false`。
- `TypingEnhancementsTest` 新增 4 个 case（中文模式不补、组词中不补、有本地预编辑不补、英文模式才补）。
- **根因**（子代理 0233315d 定位）：`AUTO_PAIR_CLOSERS` 里**同时注册了全角** `（ “ ‘ 《 【 「 『`，而旧守卫写的是
  `if (text[0].isAsciiPrintable() && !rime.statusCached.isAsciiMode) return false` ——
  `isAsciiPrintable()` 对全角恒 false，`&&` 直接短路，**中文模式照样补**。第二层原因：`statusCached` 由
  `Rime.emitResponse()` 最后一步才刷新，比按键晚一拍，连打时组词刚建立它还是 `isComposing=false`。
  物理入口：中文键盘 z 键 `swipe_down: '（'`，滑动阈值极低（`swipeTravel` 20dp / `swipeVelocity` 800dp/s，或关系）。

**bug 1 / bug 2 的代码（第四轮已有，本轮只装包没验完）**

- bug 1：`CandidateGlyphFilter`（只放行 BMP，否决 `Paint.hasGlyph` 与剔词库）+ `CompactCandidateViewAdapter` 的
  `originalIndices` / `originalIndex(position)` + `CompactCandidateDelegate` 点击/长按走 `originalIndex`。
- bug 2：`CompactCandidateDelegate.scrollHorizontallyBy` 重写（`maxScroll` / `contentExtent()` / `decoratedLeftEdge()` +
  `computeHorizontalScrollRange/Offset/Extent`）。根因是父类 `fill()` 在 NOWRAP 下每圈扣的是 `flexLine.getCrossSize()`（行高）而非主轴溢出量。

**已跑通的单测**

```
.\gradlew.bat :app:testDebugUnitTest --tests "com.osfans.trime.ime.keyboard.TypingEnhancementsTest" `
  --tests "com.osfans.trime.ime.candidates.CandidateGlyphFilterTest" `
  --tests "com.osfans.trime.data.userdict.CustomPhraseStoreTest"
→ BUILD SUCCESSFUL in 47s
```

### 装机验证进度（真机 EAT0220316001355，11:09–11:11）

| 项 | 结果 |
|---|---|
| 打 `a` 有无空白方块 | ✅ 7 个候选「安 啊 按 阿 吖 钢 嘎」，**无空白方块** |
| 点第 2 个候选选中的是不是屏幕上那个词 | ⚠️ **疑似串位**：点屏幕上的「啊」(197,1518) → 上屏 **「锕」**，既不是「啊」也不在当前候选栏里 |
| 右滑能不能滑到底 | ❌ 没验 |
| bug 5 中文模式中途触发括号 | ❌ 没验 |
| @ / ~ 直接上屏半角 | ❌ 没验（改动本身也没进包） |

⚠️ 关于"下标映射串位"：**只有一个样本**，先复测排除屏幕坐标误差 / 候选刷新时序，再判定是不是
`originalIndex(position)` 真错。别直接照着改代码。

### 本轮踩到 / 确认的坑（重要）

1. **改 `app/src/main/assets/shared/` 下的文件，`:app:mergeReleaseAssets` 会 UP-TO-DATE 不重新合并**，
   打出来的包仍是旧资源。证据：`app/build/intermediates/assets/release/mergeReleaseAssets/shared/symbols.yaml`
   的 mtime 是 **2026-10-05 07:41**，而包内 `assets/shared/symbols.yaml` 第 61/63 行仍是旧的列表形式。
   → 改 assets 后必须先删 `app/build/intermediates/assets/`（或 `clean`）再编。
2. **`symbols.yaml` 的 @ / ~ 改动没落盘**：复查 `git status` 里没有这个文件，第 61 行仍是
   `'~' : [ '~', ～, … ]`。**要重做**。
3. 华为装完 APK 会自动跳到 `com.huawei.appmarket` 搜索页（不是"安装成功"页）；
   `am start -n com.huawei.notepad/...` 会被 `HW_SIGNATURE_OR_SYSTEM` 拒绝，要用
   `adb shell monkey -p com.huawei.notepad -c android.intent.category.LAUNCHER 1`。
4. 候选栏坐标（1080×2340，density 440）：候选行 **y≈1518**；第 1 格 x≈63，之后每格约 137px。

### 没动的

- **bug 3（`ba` 不出现 `BA`）**：`mydomain.dict.yaml` 里确实有 `BA\tba\t200`，但四个 translator 里只有
  ③`mydomain_abbrev`(initial_quality 0.6) 和 ④`mydomain_terms`(**0.5**) 用 `dictionary: mydomain`，
  ②`mymain` 用的是 `luna_pinyin` —— 领域词权重被压在 0.5，排在 `luna_pinyin` 的 吧 / 把 / 八 之后，第一页根本看不到。
  **方案未定、未改。**
- **bug 4（词库难用）**：未动。已有线索：`menu/page_size`=8；`mymain/initial_quality`=1.0；
  `mydomain_abbrev/initial_quality`=0.6；`engine/processors/@before 0: predictor` + `predict.db` 会往候选栏塞预测词；
  `speller/algebra` 打了 `pinyin:/zh_z_bufen`、`n_l_bufen`、`en_eng_bufen` 三组模糊音补丁。
- **`symbols.yaml` 的 @ / ~ 改半角**（用户 m00380：「不要让我选，默认半角」）—— 要重做。
- **commit + 出包**（E）：没做。
- `dist/` 里堆了约 20 个 `_*.png` 调试截图，收尾时该清掉（`dist/` 只该放构建产物）。

### 待用户拍板（**不要自己决定**）

**中文模式打 `(` 要不要也自动补全角 `（）`？** 现在只有英文模式补（`resolveAutoPair` 的 `isAsciiMode` 守卫）。
注意：本轮修完 bug 5 之后，中文模式**连全角 `（` 都不再自动补**，交回 rime 的标点表处理。

### 提交清单（子代理 0630057e 逐个核过，25 个文件，可直接抄）

已跟踪已修改（16）：
```
HANDOVER.md
NEXT-STEPS.md
app/src/main/assets/shared/backgrounds/mybg/preview.gif
app/src/main/assets/shared/trime.yaml
app/src/main/java/com/osfans/trime/data/base/DataManager.kt
app/src/main/java/com/osfans/trime/ime/candidates/compact/CompactCandidateDelegate.kt
app/src/main/java/com/osfans/trime/ime/candidates/compact/CompactCandidateViewAdapter.kt
app/src/main/java/com/osfans/trime/ime/core/TouchEventReceiverWindow.kt
app/src/main/java/com/osfans/trime/ime/core/TrimeInputMethodService.kt
app/src/main/java/com/osfans/trime/ime/keyboard/CommonKeyboardActionListener.kt
app/src/main/java/com/osfans/trime/ui/main/MainFragment.kt
app/src/main/java/com/osfans/trime/ui/main/NavigationRoute.kt
app/src/main/res/values/strings.xml
app/src/main/res/values-zh-rCN/strings.xml
app/src/main/res/values-zh-rTW/strings.xml
app/src/test/java/com/osfans/trime/ime/keyboard/TypingEnhancementsTest.kt
```
未跟踪新增（9）：
```
app/src/main/java/com/osfans/trime/data/userdict/CustomPhraseStore.kt
app/src/main/java/com/osfans/trime/ime/candidates/CandidateGlyphFilter.kt
app/src/main/java/com/osfans/trime/ui/main/settings/customphrase/CustomPhraseDialog.kt
app/src/main/java/com/osfans/trime/ui/main/settings/customphrase/CustomPhraseFragment.kt
app/src/main/java/com/osfans/trime/ui/main/settings/customphrase/CustomPhraseListAdapter.kt
app/src/main/java/com/osfans/trime/ui/main/settings/customphrase/CustomPhraseListEntryUi.kt
app/src/main/java/com/osfans/trime/ui/main/settings/customphrase/CustomPhraseListUi.kt
app/src/test/java/com/osfans/trime/data/userdict/CustomPhraseStoreTest.kt
app/src/test/java/com/osfans/trime/ime/candidates/CandidateGlyphFilterTest.kt
```

**必须排除**：两个 typechange（`build-logic/gradle/wrapper/gradle-wrapper.properties`、`fastlane/metadata/android/en-US/images/icon.png`）、
脏子模块 `app/src/main/jni/librime-lua-deps`、以及历史残留 `tools/gif_*.py`(30) / `tools/review_*.py`(6) /
`tools/verify-ui/`(234) / `rebuild_predict_db.sh` / `relink_plugin.bat`。
**不要用 `git add -A`，逐个点名。不要用 `git add -f dist/...`**（`dist/` 是整目录 ignore，`-f` 能强加进去）。

---

## 0.3 2026-10-24 停手交接（第五轮 = 用户报的 5 个 bug 收尾）

> 用户要求「别干了，写接收文档，不用验收，写文档就好」。**本节是当前状态的权威描述，覆盖 §0.1、§0.2、§8.1 与 `NEXT-STEPS.md` §9。**

### 一句话现状

第三轮 + 第四轮 + 本轮五个 bug 的修复**已全部提交**（HEAD = `7059b57c`，28 个文件，上一版是 `7df96714`）。
新包 **14227148 字节、511 项**，`2026-10-24 14:11` 构建，`14:12:14` 装到真机 EAT0220316001355
（`versionName=3.3.13`、`versionCode=20261101`）。两份都在 `dist/`：
`trime-arm64-v8a-release.apk` 与 `trime-release.apk`（内容相同）。

**除了背景 GIF 之外的功能项都没有逐条验收。** 背景 GIF 目前的状态是「头太大已解决、循环衔接不流畅」，
详见下面的专节 —— 这是用户当前唯一还挂着的明确诉求。

### 工作树里未提交的两个回滚（**必须一起提交，别丢**）

`git status` 剩下的（除老三样外）只有这两个，都是本轮**故意改回去的**：

```
 M app/src/main/assets/shared/backgrounds/mybg/preview.gif   2905549 → 3751952 字节
 M app/src/main/java/com/osfans/trime/ime/core/InputView.kt   -46 行
```

- `InputView.kt` 删掉了本轮加的 `BG_SUBJECT_RATIO` / `BG_SUBJECT_TARGET` / `BG_MAX_ZOOM` 三个常量、
  `applyBackgroundMatrix()`、`addOnLayoutChangeListener` 和 `import android.graphics.Matrix`。
  **`git diff 7df96714 -- <该文件>` 已确认输出为空**，即与接手时基线逐字一致。
- `preview.gif` 退回成接手时那份（`git show 7df96714:...` 取出的原件，同时也在 `tools/verify-ui/_orig_preview.gif`）。
- 老三样照旧排除：两个 typechange（`build-logic/gradle/wrapper/gradle-wrapper.properties`、
  `fastlane/metadata/android/en-US/images/icon.png`）+ 脏子模块 `app/src/main/jni/librime-lua-deps`。

### 本轮修完并提交的 5 件事

**1. bug 5 —— 打字打到一半冒出括号（本轮最硬的一个）**

- 根因两层：
  - `AUTO_PAIR_CLOSERS` 里**同时注册了全角** `（ “ ‘ 《 【 「 『`，而旧守卫写的是
    `if (text[0].isAsciiPrintable() && !rime.statusCached.isAsciiMode) return false` ——
    `isAsciiPrintable()`（`app/src/main/java/com/osfans/trime/ime/keyboard/Char.kt:8`，`code in 32 until 127`）
    对全角恒 false，`&&` 直接短路，**中文模式照样补**。
  - `statusCached` 由 `Rime.emitResponse()`（`app/src/main/java/com/osfans/trime/core/Rime.kt:343-355`）
    最后一步才刷新，比按键晚一拍；默认 `InlinePreeditMode.DISABLE` 下 `composingText` 恒空。
  - 物理入口：中文键盘 z 键 `swipe_down: '（'`（`trime.yaml:400`），
    阈值 `AppPrefs.kt:262-286` 的 swipeTravel 20dp / swipeVelocity 800dp/s 是**或**关系，极易误触。
- 修法：提成纯函数 `resolveAutoPair(text, isComposing, hasLocalComposition)`，**组词中 / 有本地预编辑一律不补**；
  `commitPairedText`（`TrimeInputMethodService.kt:696-713`）再加 `hasComposition()` 与选区两道兜底。

**2. 中文模式也补全角括号（用户拍板，已实现）**

用户 m00994 原话：「中文也要」。所以删掉 `resolveAutoPair` 的 `isAsciiMode` 参数与守卫，
保留「组词中不补」。按字符查表天然分流：中文 `（` → `）`、英文 `(` → `)`。
`TypingEnhancementsTest` 重写 5 个 case（中文补全角、中文补 ASCII、组词中不补、有本地预编辑不补、右半与非左半 null）。

**3. 空格长按不能拖光标**

- 根因：`GestureFrame.kt` 的 `startLongPressJob` 长按超时后**无条件** `isLongPressed = true`，
  而 ACTION_MOVE 的滑动分支条件是 `!isLongPressed` → 光标永远拖不动；`repeatable: true` 还让空格持续重复上屏。
- 修法：新增 `private var slideArmed = false`。长按超时里
  `if (isSlideCursor && onSlide != null) { slideArmed = true; return@launch }`
  （**不置 isLongPressed、不 startRepeatJob**）；滑动条件改 `(!isLongPressed || slideArmed)`；
  ACTION_DOWN 与 ACTION_CANCEL 都复位 `slideArmed = false`。
- 相关配置：`trime.yaml:272-277` 的 `space_cursor` = `send: space` + `repeatable: true` + `slide_cursor: true`。

**4. 快捷 `@` / `~` 直接上屏半角（用户 m00380 要求）**

- **关键坑：`assets/shared/symbols.yaml` 根本改不动。**
  `build-logic/convention/src/main/kotlin/RimeDataSyncPlugin.kt` 的 `syncRimeSharedData`
  每次构建都把 `app/data/rime/prelude/symbols.yaml` **镜像覆盖**到 `app/src/main/assets/shared/symbols.yaml`，
  而且 `outputs.upToDateWhen { false }` 永不跳过。前两次改都这么被冲没了。
- 正解：新增 **`app/src/main/assets/shared/symbols.custom.yaml`**（rime 原生补丁机制），
  `patch:` 下把 `half_shape/@` 与 `half_shape/~` 改成 `{ commit: '@' }` / `{ commit: '~' }`。
  该文件在 `data/rime` 下无对应源，sync 任务不会删它。
- `symbols.yaml` 里要改的位置：`half_shape` 段的 `'~'`（61 行）与 `'@'`（63 行），原本是**列表形式**（= 弹候选）。

**5. 背景 GIF —— 换了素材，但方向错了，已退回（见下一节）**

### 背景 GIF：完整来龙去脉（**接手人务必先读完再动手**）

用户原始诉求（m01010）：「我的 gif 人物偏右问题」。本轮追加反馈：
m01172「改错了啊!你这样直接让头占据了画面，吓人的，你看看你第一次接手的那个」、
m01244「嗯，太大问题解决了，现在是衔接不流畅问题」。

**两份素材的客观指标（都量过，可复现）**

| | 接手时那份（**现在用的**） | 第四轮换的那份（**已弃用**） |
|---|---|---|
| 字节 | 3751952 | 2905549 |
| 尺寸 / 帧数 | 512×512，30 帧，mode P | 512×512，31 帧，mode P |
| 循环缝（首末帧平均差） | **15.69** | **3.45** |
| 相邻帧平均差 | 5.06 | 3.92 |
| 循环缝 / 相邻帧 比值 | **3.10×（明显跳帧）** | **0.88×（无感循环）** |
| 主体水平加权中心 | 0.622（饱和度加权；另一种加权算得 0.693） | 0.634 |
| 构图 | **远景**，头占比小 | **近景**，头占比大 |

**为什么代码层面挪不动（试过，失败，别再试）**

- 键盘区 view 约 1080×750，素材 512×512。
- `CENTER_CROP` 的 scale = max(1080/512, 750/512) = **2.109** → 缩放后 **1080×1080**，
  **横向刚好铺满、零余量**，只裁上下 → 主体 x 坐标恒在 62%~69%，任何平移都会露底。
- 想平移必须放大 → 头必然变大。我按 `BG_SUBJECT_TARGET=0.42f` 反推需要 `base × 2.2 ≈ 4.6` 倍，
  装机后就是用户说的「头占据了画面」。**结论：只能改素材，不能靠 `imageMatrix`。**
- 那段代码已全部删除，`InputView.kt` 与 `7df96714` 逐字一致。

**当前状态**

- 头太大 ✅ 解决（素材退回远景那份）
- 循环衔接不流畅 ❌ **回来了**（3.10× vs 新素材的 0.88×）—— 这就是用户 m01244 说的「衔接不流畅」

**下一步该怎么做**

以**接手时那份的构图**为准（远景、主体 ~0.62、头占比小），重新生成一个循环缝达标的版本。
上一轮做这件事的工具链都还在 `tools/gif_*.py`（30 个，2026-10-11 11:28–12:09 那串），顺序大致是：

```
gif_loop_analyze.py → gif_motion_features.py → gif_align_and_budget.py
→ gif_traj_and_encode.py → gif_encode_sweep.py → gif_quant_compare.py
→ gif_rect_encode.py → gif_palette_subset.py → gif_build_final.py
→ gif_finalize.py → gif_install.py → gif_loop_check.py → gif_accept.py
```

- 原件：`tools/verify-ui/_orig_preview.gif`（3751952 字节，与现用素材一致）
- 两份对比件：`tools/verify-ui/_old_preview_handover.gif`（3751952，远景、缝 3.10×）、
  `tools/verify-ui/_new_preview_4th.gif`（2905549，近景、缝 0.88×）
- 现成的中间产物可直接对比：`tools/verify-ui/_fin_MAXCOV*.gif`、`_pick_*.gif`、`_sub_*.gif` 等 20 多个
- 素材装在 `app/src/main/assets/shared/backgrounds/mybg/preview.gif`，主题里由 `trime.yaml:72-73` 的
  `background_folder: mybg` 指定

### 验收进度（如实记录，别当成已通过）

| 项 | 状态 |
|---|---|
| 候选栏豆腐块过滤（bug 1） | ✅ 在 2026-10-18 那版包上验过：清空拼音按 `a`，候选「啊 安 按 阿 吖 锕 嘎」无空白方块。**本版包未复验** |
| 候选下标映射 | ⚠️ 当时点第 2 个候选上屏的是「安」，但**另有一次点「啊」上屏了「锕」**，串位未彻底排除 |
| 右滑到底（bug 2） | ❌ 未验 |
| 中文补全角括号 | ❌ 未验 |
| 空格长按拖光标 | ❌ 未验 |
| `@` / `~` 上屏半角 | ❌ 未验。**尤其要确认 rime 是否真的认 `symbols.custom.yaml` 这个补丁** |
| 背景 GIF 位置 | ✅ 头太大已解决；❌ 循环衔接不流畅（见上节） |
| bug 3：`ba` 打不出 `BA` | ❌ **完全没动** |
| bug 4：词库整体难用 | ❌ **完全没动** |

### 本轮踩到 / 确认的坑（新增部分）

1. **`assets/shared/symbols.yaml` 改了等于没改** —— 构建时被 `syncRimeSharedData` 覆盖。用 `.custom.yaml` 补丁。
2. **背景图不能靠 `imageMatrix` 平移** —— 键盘区宽扁，`CENTER_CROP` 横向零余量，只能改素材。
3. **一次只编一个 ABI** —— `BUILD_ABI` 只给一个；同时编两个会让 arm64 包缺 `AndroidManifest.xml` 变坏包。
   改 ABI 前先删 `app\build\outputs\apk\release\*.apk`。
4. **真机掉线会自己回来** —— `adb devices` 空了先等几分钟，`adb kill-server` 反而更慢。
   本次 13:57 恢复，`transport_id` 从 2 变 1。
5. **华为备忘录必须用 monkey 启动** —— `am start` 会被 `HW_SIGNATURE_OR_SYSTEM` 拒：
   `adb shell monkey -p com.huawei.notepad -c android.intent.category.LAUNCHER 1`
6. **截图必须 `screencap` 到 sdcard 再 `adb pull`** —— PowerShell 重定向会写成 UTF-16，PNG 直接坏。
7. **候选栏坐标（1080×2340 / density 440）**：候选行 y≈1518，第 1 格 x≈63，每格约 137px。
8. 前台检查：`adb shell dumpsys window | grep mCurrentFocus`
   —— 主界面是 `NotePadActivity`，编辑页是 `NoteEditor`，两者要分清。

### 还没做的（按优先级）

1. **背景 GIF 循环衔接** —— 用户当前唯一明确的诉求（m01244「衔接不流畅」）
2. **推送 `develop` 触发 CI 发签名包**（任务 A）。代理端口会变，先探测：
   `(Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings').ProxyServer`
3. 上表里所有 ❌ 的功能项装机复验
4. bug 3（`ba` 词库）、bug 4（词库难用）—— 一直没动
5. 按 `PLAN-功能扩展.md` 做下一项功能（任务 B）

### 待用户拍板

**无。** 上一版挂着的「中文模式要不要也补全角 `（）`」已在 m00994 拍板为「中文也要」，并已实现。

### 给接手人的五句话

1. 先 `git add` 那两个回滚（`InputView.kt` + `preview.gif`）再动手，否则下次构建又带出「头太大」的包。
2. 背景图的问题**只能在素材上解决**，代码里怎么算都是放大 → 头变大。
3. 要改 rime 的 yaml，先想清楚它在不在 `MIRRORED_DIRS` 里；在的话一律用 `.custom.yaml` 补丁。
4. 用户对观感很敏感，改视觉相关的东西**先小步装机看图**，别一次改到位再装。
5. 一次只编一个 ABI；产物只放 `D:\trime-build\dist\`，别拷到桌面。

---

## 0.4 2026-10-24 第六轮（**只调研 + 换了一个 GIF 文件，没编译没装机**，换 agent 时停在这里）

> 本节记录 §0.3 之后新查实的三件事。代码**一行没改**，工作树相对 §0.3 只多两样：
> ①两份文档（本文件 + `NEXT-STEPS.md`）更新；②`preview.gif` 从 3751952 换成了 3471816
> （即下一节说的 `_install_candidate.gif`）。
> `HEAD` 仍是 `7059b57c`；`InputView.kt` 的 -46 行回滚也还在工作树里没提交。
> **新包没有构建**，`dist/` 里仍是 14:11 那份旧的 14227148 字节包。

### 1. 背景 GIF 循环衔接 —— **解法已经存在，不用再生成素材**

§0.3 说「要以接手时那份的构图重新生成循环缝达标的素材」，**这句已过时**。
2026-10-11 12:02 那轮 GIF 工具链其实已经产出过一份达标的成品，只是**从没装进仓库**：

`tools/verify-ui/_install_candidate.gif` —— 3471816 字节，38 帧，
SHA256 `c6b7ad55c429f657`（与 `tools/verify-ui/_fin_MAXCOV160.gif` 逐字节相同）。

| 指标 | 现用 `_old_preview_handover.gif` | **候选 `_install_candidate.gif`** | 已弃 `_new_preview_4th.gif` |
|---|---|---|---|
| 字节 | 3751952 | 3471816 | 2905549 |
| 帧数 | 30 | 38 | 31 |
| 循环缝 seam（首末帧平均差） | 15.69 | **2.04** | 3.45 |
| 相邻帧均值 adj | 5.06 | 5.20 | 3.92 |
| seam / adj | 3.10×（明显跳帧） | **0.39×（无感循环）** | 0.88× |
| 主体水平中心 cx（饱和度加权） | 0.624 | **0.610** | 0.638 |
| 每帧时长 | 100ms | 100ms | — |
| 构图 | 远景、头占比小 | **远景、头占比小（与现用完全一致）** | 近景大头（用户骂的那份） |

- **构图与现用素材一致**：导出首帧目视对比，远景、脸偏右、草帽+紫花、白裙肩带，全部相同。
- 逐帧比对：cand[i] vs old[i] 在 i=0..19 差值稳定 1.73~1.80（同一帧序列重编码），
  i=20 起升到 5~9.7 —— **后 18 帧是交叉淡入淡出的缝合段**，这就是它把循环缝从 15.69 压到 2.04 的手法。
- 首帧 f00 与末帧 f37 目视几乎相同，无跳变。ping-pong 假设已排除（差值 12~19，不成立）。
- **它从未提交过**：`git log --follow` 该路径只有 `f49115f5`（3751952）与 `7059b57c`（2905549）两条。

**文件名已经换好了**（本轮唯一改动的文件）：`app/src/main/assets/shared/backgrounds/mybg/preview.gif`
现在是 3471816 字节。**但没编译、没装机**，所以循环效果还没在真机上看过。

剩下两步：

```powershell
Remove-Item app\build\intermediates\assets -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item app\build\outputs\apk\release\*.apk -Force -ErrorAction SilentlyContinue
$env:BUILD_ABI="arm64-v8a"
.\gradlew.bat :app:assembleRelease
```

然后 `adb -s EAT0220316001355 install -r -t` 装 `dist\trime-arm64-v8a-release.apk`，看键盘背景循环是否顺。

背景图走 `DataManager.kt:58` 的
`PACKAGED_MIRROR_DIRS = arrayOf("backgrounds", "soundeffect")` + `mirrorPackagedDirs()`
的「不存在或尺寸变了才覆盖」逻辑，尺寸 3751952→3471816 会触发覆盖，不用手动清。
另外 `RimeDataSyncPlugin` 的 `MIRRORED_DIRS` 不含 `backgrounds`，所以这个素材不会被构建冲掉。

**复用脚本**（`tools/` 下，未提交）：`_gif_scan.py`（批量扫 seam/adj/ratio/cx）、
`_gif_frames.py` / `_gif_frames2.py`（导出帧）、`_gif_verify.py`（帧级匹配 + SHA256 去重）、
`_gif_loop.py`（帧时长）、`_gif_pingpong.py`。目视对比图在 `tools/verify-ui/_cmp/`。

### 2. 字体难看 —— 根因是**仓库里一个字体文件都没有**

`app/src/main/assets/shared/trime.yaml:14-22` 配了 9 个字体名，全部落空：

```
key_font: latin.ttf        label_font: latin.ttf      preview_font: latin.ttf
text_font: latin.ttf       candidate_font: han.ttf    comment_font: comment.ttf
hanb_font: hanb.ttf        latin_font: latin.ttf      symbol_font: symbol.ttf
```

字号：`font_size: 16`（90 行）、`label_font_size: 20`（99）、
`text_font_size: 20`（100）、`comment_font_size: 16`（101）。

**关键事实**：
- `app/src/main/assets/shared/` 下**没有任何 .ttf/.otf/.ttc**（全仓库只有 boost 子模块里的文档示例，无关）。
- `app/src/main/java/com/osfans/trime/data/theme/FontManager.kt:33`：
  `private val fontDir get() = File(DataManager.userDataDir, "fonts")`
  —— **只从用户数据目录读，不从 assets 读**。
- `handler(fontName)`（90-97 行）找不到文件就打 `Timber.w("font %s not found")` 返回 null；
  `getTypeface` 的 fallback 链是 LATIN_FONT → key 字体 → HANB_FONT → `setSystemFallback("sans-serif")`，
  所以**现在全部落到系统 sans-serif**。
- 真机实测：`/sdcard/Android/data/com.osfans.trime/files/rime/fonts` **不存在**。
- `DataManager.kt:58` 的 `PACKAGED_MIRROR_DIRS` 只有 `backgrounds` / `soundeffect`，
  **`fonts` 不在其中** —— 就算往 assets 放字体也不会被镜像到用户目录。

**两条可选修法**（未实施，**要用户先拍板能接受多大 APK**）：
- (a) 放字体到 `app/src/main/assets/shared/fonts/`，并把 `fonts` 加进 `PACKAGED_MIRROR_DIRS`。
  改动最小（一行 Kotlin + 字体文件），但会增大 APK。
- (b) 在 `DataManager.sync()` 里单独写一段把 assets/fonts 复制到 `userDataDir/fonts`，逻辑同上但更可控。

**体积参考**：APK 现在 14.2MB。本机 `C:\Windows\Fonts` 可取的：
`Deng.ttf` 16.3MB、`NotoSansSC-VF.ttf` 17.8MB、`simhei.ttf` 9.8MB、`simsunb.ttf` 21.9MB。
**加一个中文字体 APK 就翻倍**。折中：只放 latin（`latin.ttf` 一个文件覆盖 key/label/preview/text 四个槽），中文继续靠系统 fallback。

### 3. bug 3 `ba` 打不出 `BA` —— 根因已定位，方案未定

- 词条**存在**：`app/src/main/assets/shared/mydomain.dict.yaml:285` = `BA<TAB>ba<TAB>200`，
  权重 200 是全库最高档之一。
- 但 `mydomain.schema.yaml` 的四个 translator 里，**只有两个用 `dictionary: mydomain`**：
  - `custom_phrase`（stabledb，`initial_quality: 2`）—— 读 `custom_phrase.txt`，不含 BA
  - `mymain`（`initial_quality: 1.0`，`dictionary: luna_pinyin`）
    —— **底库是 luna_pinyin，根本看不到 mydomain 里的 BA**
  - `mydomain_terms`（`initial_quality: 0.5`，`dictionary: mydomain`）—— 有 BA，但权重被砍半
  - `mydomain_abbrev`（`initial_quality: 1.0`，`dictionary: mydomain`，`enable_completion: true`）—— 有 BA
- 所以 `ba` 的候选里 BA 排在 luna_pinyin 的 吧 / 把 / 八 之后，
  **第一页（`menu/page_size: 9`）看不到**。
- 可选修法（未实施）：抬 `mydomain_terms/initial_quality` 到 1.5~2.0；
  或给 `mymain` 再加一个 `dictionary: mydomain` 的兄弟 translator；
  或把 BA / 蔚蓝档案 / 白子 / 阿拜多斯 等词条权重抬到 2000 量级。
- **改完必须重新部署**，并在真机上确认 `ba` 第一页就能看到 `BA`。

### 本轮确认的环境事实

- 设备 EAT0220316001355 中途又掉过一次线，**自己回来了**（与 §0.3 第 4 条一致，别 kill-server）。
- 真机上 `files/shared/` 是 assets 同步目标，`files/rime/` 是用户数据目录
  （含 `mydomain.user.userdb`、`installation.yaml`、`user.yaml`）。
  `files/shared/` 下已有 `symbols.custom.yaml`，说明第四轮的补丁机制确实生效过。

---

## 0.5 2026-10-24 第七轮（GIF 装机 + 字体 + ba 词库，已完成并装机验证）

> 三个诉求全部落地，新包 `dist/trime-arm64-v8a-release.apk` = **14016468 字节**（20:12 构建，
> 513 项，`AndroidManifest.xml`/`resources.arsc` 自检通过），已装真机 EAT0220316001355。
> 第六轮的两份文档改动（§0.4/§11）当时未提交，本轮一并提交。

### 1. 背景 GIF —— 已装机，循环衔接待用户观感确认

§0.4 的 `_install_candidate.gif`（3471816 字节）随本轮包装机；键盘背景确认在动、
构图与接手时一致（远景、头占比小）。**「循环顺不顺」要用户自己看**，离线指标
seam 2.04 / 0.39× 已达标。`InputView.kt` 的 -46 行回滚 + 新 GIF 已提交（必须一起的那两个）。

### 2. 字体 —— 真根因是 GeneralStyle 的解析 bug，已修 + 换 Quicksand

- **§0.4 的根因分析只对了一半**。就算字体文件就位也加载不了：
  `GeneralStyle.kt` 的 `stringList()` 只认 YAML **序列**，而 `key_font: latin.ttf`
  是**标量** → 全部 9 个字体名解析成空列表 → FontManager 永远拿到 DEFAULT。
  历史上 2bcdf382 把「标量→空列表」写进了测试（GeneralStyleTest）当设计。
- 修复：`stringList()` 标量按单元素列表解析（列表写法继续支持）；
  GeneralStyleTest 断言改为 `keyFont == listOf("latin.ttf")`。
- 配套：`DataManager.PACKAGED_MIRROR_DIRS` 加 `"fonts"`（FontManager 只读
  `<userDataDir>/fonts`），assets/shared/fonts/ 随包镜像；装机后自动落地 ✓。
- 字体两换：先 Varela Round（已生效，键面字母明显变圆），用户要求「更小更圆」
  后换 **Quicksand**（OFL，124824 字节，variable 默认 Regular），
  `key_text_size` 22 → **20**。`preview_font` 是无效键，已改名 `popup_font`。
- ⚠ 中文槽（candidate/comment/hanb/symbol）继续系统 fallback，APK 仅 +0.1MB。

### 3. ba 词库 —— 根因是权重刻度差 10 倍，已修（白子首页第 5、蔚蓝档案第 1）

- **用户澄清（重要）**：「我要的是 ba 词库，而不是 ba 这个词」——goal 是蔚蓝档案
  词库能打出来，不是字面词条 BA 霸榜。第六轮 §0.4.3 的方案（全局抬 quality /
  抬 BA 词条权重）方向全错。
- **真正根因**：mydomain 整库权重（80~2000）比 essay 词频刻度低约 10 倍——
  essay 多字词普遍 500~800（白字=557、蔚然=580），顶常用字 7591~385554
  （八/把/吧）。白子 285 输给白字 557 就是这么来的；整段领域词被系统性掩埋。
- 修复：蔚蓝档案段（47 条）×10 → 2500~3400；字面 BA → 2000（普通专有名词档，
  打 ba 时排 吧/把/八 之后、首页可见，不霸榜）。原神/崩铁段 ≤200 未动。
- 真机验证：`baizi` → 白子第 5（首页）；`weilangdangan` → 蔚蓝档案第 1；
  `bengtie` → 崩铁第 3（userdb 加权）；`clash` → Clash 第 1。check_dict 通过
  （仅 BA 外一条「权重超出建议区间」提示——区间建议已过时，勿改回）。
- **改权重改的是 `app/data/rime/myvocab/`（权威源）**，assets/shared 是构建镜像。

### 4. 第七轮踩坑（都验证过，勿重蹈）

1. **shell 与 app 进程的文件视图可能不一致**：`adb shell sed` 改 assets、
   `adb pull` 读文件做字节比对，都可能和 app 实际读到的不一样。设备端调参
   **只能走 APK 重装**（让 app 自己写文件），sed/广播那些循环全是空转。
2. **librime 表内权重编码不是明文 float**（Java 端全量字节扫 200/340/385554/
   2e7 的线性与 log 值全部 0 命中，疑似压缩/变长）——**别做表字节考古**，
   直接看部署日志（`grep rime.trime`，有 checksum、collecting entries、
   dropping 告警）+ 引擎候选日志。
3. `script_translator` 候选质量 = `exp(entry.weight)`（script_translator.cc:646）；
   essay 值直存表，排序实际是线性值比大小。合并选举（MergedTranslation::Elect）
   只比较**相邻**翻译器首候选。
4. **键码表**：Z=54、M=41、N=42、O=43（此前把 Z 敲成 44=P，浪费两轮判别实验）。
5. 键盘字体改后不生效 ≠ 失败：先排除「首启时字体还没镜像」的时序（force-stop
   重启 IME），再用 6 倍放大裁单字母比对（小图+花背景会看走眼，Q 尤其误导——
   HarmonyOS Sans 和 Varela 的 Q 都是圆环）。
6. 真机安装密码 100000（adb install 未触发，备用）。
7. 诊断日志走 release 版也能出：TrimeApplication 在 release 也 plant 了
   Timber 树（INFO+，tag=线程名）；一次性日志会被刷掉，写文件更稳
   （`files/shared/dictdbg.txt` 手法）。本轮诊断代码已全部移除。

### 5. 还没做的

1. GIF 循环观感 —— 等用户看真机。
2. **词库其他段是否也对齐 essay 刻度**（科技/原神/崩铁段 ≤200 仍被掩埋，
   这就是 bug 4「词库难用」的根因）——抬权会霸榜，**要用户拍板**。
3. 装机复验清单（右滑到底、中文全角括号、空格拖光标、`@`/`~` 半角、下标映射）。
4. 任务 A（推送 develop 触发 CI，当前本地 ahead 2）、任务 B（PLAN-功能扩展.md）。
5. `tools/verify-ui/` 本轮新增约 30 个 `_v*.png/_font*.png/_deploy_log*.txt`
   调试文件，未跟踪，收尾可清。

