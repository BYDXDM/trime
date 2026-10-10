# 交接文档 · 白洲梓输入法（trime fork）

> 重写于 2026-10-10。**当前状态：无已知未解决缺陷，工作树干净，本地与远端同步。**
> 本文档记录「已经做过什么」「为什么这么做」「还剩什么没做」，以及踩过的坑。

---

## 0. 一句话现状

崩溃、候选栏空白/字号、外观（去边框、只留背景 GIF）三项已修完并推送。
2026-10-10 接手后完成：用户**亲眼确认**去边框观感 ✅、**首次发出可下载的签名包**
（GitHub Release `v0.1.21`）✅、真机全量回归 ✅，并**新增「删除键上滑清空待选拼音」**
（见 §2.4）。

```
接手时 HEAD = 6fd825f7d53c695558350ad8ffe9ce52f636c788（origin/develop 同值，无 ahead）
本任新增提交：见 §3.1
```

> ⚠ `dist/trime-release.apk` 已被本任重构建覆盖（含上滑修复），不再是 6fd825f7 的产物。

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

## 2. 已完成且真机验证通过的

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

**修复**（用户确认的分工：**上滑清空 / 单击删 1 字**）：

| 文件 | 改动 |
|---|---|
| `app/src/main/assets/shared/trime.yaml` | 新增预设 `ClearPinyin: { label: 清空拼音, send: FUNCTION, command: clear_composition }`；两处退格键（`my_pinyin`、`my_english`）`swipe_up: Clear` → `swipe_up: ClearPinyin` |
| `app/src/main/java/com/osfans/trime/ime/keyboard/CommonKeyboardActionListener.kt` | `handleFunctionCommand` 新增 `"clear_composition" -> clearComposition()`；新增私有 `clearComposition()`（**不做** `hasComposition()` 前置判断，避免重蹈死分支覆辙） |

真机验证（HUAWEI ANA-TN00 / HarmonyOS 4.2 / API 31）：
- 有 composition（`ni hao`）时上滑 → 预编辑串清空，候选栏回落为联想态 ✅
- 正文已有「你好」时上滑 → **正文完好无损**，旧的「误删整篇」行为消除 ✅
- 单击退格仍逐字删（`ni hao` → `ni ha`）✅

> 遗留：`clearCompositionIfAny()` 现在成了无用代码（`KEYCODE_DEL` 分支恒返回 false）。
> 若日后有人「修好」`hasComposition()`，单击行为会静默变成整体清空 —— 与本次约定的
> 「单击逐字删」冲突，改动前请先看这里。

### 2.5 中英切换键的高亮：文档描述与实现不符（非回归）

旧 §2.3 称 `on_key_back_color: 0x3975CE` 是「中/英与大小写状态的唯一视觉信号」。
实测只对**修饰键**成立：

- Shift 激活 → 键面亮蓝底 `rgb(48,112,192)`（= 0x3975CE 的量化值）✅
- 中/EN 键 → **始终不亮蓝底**。原因：`Key.appearanceType` 只有「修饰键且 modifier
  命中」或 `isOn` 才取 `on` 态，而 `Mode_switch`（`toggle: ascii_mode`）两者都不满足，
  只能靠标签 `中 ↔ EN` 区分。

这不是 6fd825f7 引入的回归（该键原用 `off_key_back_color: 0xD8DBE0` 的浅灰底，
随「去边框」一并变透明），但**文档描述需要更正**。

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
**没有「未提交的文件」了**——旧版本文档 §8 列的 10 个文件已全部入库。

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
   确实会吃掉格宽（见 §6.2），但那只是放大器；真根因是 §2.2 的三处叠加。

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

---

## 8. 还没做的

1. **可选：把「上滑清空拼音」也发一版 CI 包**——`v0.1.21` 是修复**之前**构建的
   （对应 `6fd825f7`）。修复推送后需再 `workflow_dispatch` 一次
   （`variant=release` + `publish_release=true`）才会产出含修复的 `v0.1.22`。
2. **可选**：`clearCompositionIfAny()` 已成死代码（见 §2.4 遗留说明），删不删由下一任定。
3. `rebuild_predict_db.sh` / `relink_plugin.bat` 仍指向旧仓库路径 `D:\ai\工作\trime`，
   未跟踪、未清理（本任只删了 `_arch*.json` / `_shot*.png` 那批历史残留）。

---

## 9. 工作树里那些看起来像垃圾的东西

2026-10-10 已清理 21 个文件：`_arch.json` `_arch2.json` `_arch_payload.json`
`_del.json` `_dict_check.yaml` `_fork.json` `_hdr.txt` `_newrepo.json` `_r.json`
`_repo.json` `_schema_check.yaml` `_setup.png` `_shot1..7.png` `_t1.txt` `_t2.txt`
（备份在 `%TEMP%\trime-cleanup-20261010\`，确认无用后可删）。

`git status` 现在只剩：

```
 m app/src/main/jni/librime-lua-deps         ← 子模块工作区脏，**绝对不要动**
?? dist/  tools/verify-ui/                   ← 产物与验收工具，保留
?? rebuild_predict_db.sh  relink_plugin.bat  ← 指向旧仓库 D:\ai\工作\trime，待定
```

> 旧文档提到的两个 typechange（`build-logic/gradle/wrapper/gradle-wrapper.properties`、
> `fastlane/metadata/android/en-US/images/icon.png`）在当前 `git status` 里**已不存在**，
> 无需处理。

---

## 10. 给接手人的四句话

1. **动手前先 `git status -sb`**。当前是干净的、与远端同步的；如果你看到一堆
   `??` 文件，那是 §9 里那些历史残留，别慌也别乱删。
2. **涉及 IME 生命周期/时序的改动，必须插真机验**。那个崩溃在模拟器上 100% 不复现，
   靠猜浪费了好几轮；模拟器 320×640 上候选还会被挤成细条，同样不能用来判断字号。
3. **改候选栏/主题数值前，先把 §5.1 的单位表看一遍**，尤其是
   「`TextView.textSize` 收 sp，而本仓库 `Context.sp()` 返回 px」这一条。
4. **不要在用户手机上做破坏性操作**（删 `rime/build/*.bin` 之类）。
   真需要重建词典时用 §5.4 的广播。
