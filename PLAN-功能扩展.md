# PLAN-功能扩展.md · 可新增功能清单与实施计划

> 写于 2026-10-10，基于对仓库现状的通读（`README-FORK.md` / `HANDOVER.md` /
> `ISSUE-ANALYSIS.md` / `CODE-REVIEW.md` / `PLAN-联想猜想.md`）与 `ime/`、`data/`
> 模块盘点得出。「改动面」是相对本仓库的判断，不是绝对工作量。
>
> 优先级口径：**高** = 用户日常可感知且改动可控；**中** = 有价值但要动渲染/IME 时序；
> **低** = 投入产出比差或依赖外部条件，暂缓。

---

## 0. 现状盘点（已具备，不要重复造）

| 领域 | 已实现 | 主要位置 |
|---|---|---|
| 键盘 | `my_pinyin` / `my_english` / 8 个符号分组（含颜文字）/ `number` / `emoji` | `assets/shared/{trime,panels}.yaml` |
| 主题 | 4 套配色、GIF 动图背景、背景导入界面 | `data/theme/`、`ui/main/settings/` |
| 输入体验 | 中英切换、英文纠错、关键词联想、长按空格拖光标、退格上滑清空拼音 | `ime/text/`、`ime/core/` |
| 编辑 | 剪贴板建议上屏 + 历史窗口、用户词典导入导出、成对符号 | `ime/bar/`、`ime/clipboard/`、`data/userdict/` |
| 反馈 | 按键震动 / 音效 / TTS、按键预览气泡 | `ime/keyboard/InputFeedbackManager.kt` |
| 统计 | 每日字数（保留 30 天）、主界面今日字数 + 近 7 天迷你走势 | `data/stats/TypedCharCounter.kt` |
| 工程 | 10 步开发校验、CI 出签名包、词库/面板生成脚本 | `tools/`、`.github/workflows/build-fork.yml` |

---

## 1. P0 — 本轮已实施（均在 AVD `smoke` 上验证通过）

> 另：本轮还修了「删除键上滑」——原行为是 `Clear`（`{Control+a}{BackSpace}`），
> 清不掉待选拼音；现改为「清预编辑串 + 清输入框正文」两步，
> 详见 `HANDOVER.md` §2.4（那里记了三个踩过的坑）。

### 1.1 颜文字面板 ✅

- **目标**：符号面板新增第 8 组「颜文字」，一键上屏常用 kaomoji。
- **用户价值**：本 fork 是二次元向（词库里已有原神/崩铁/蔚蓝档案），颜文字是高频表达。
- **改动面**：小（纯主题层，零 Java 改动）。
- **实施**：`panels.yaml` 新增 `kaomoji_panel`（4 键/行 × 3 行）；`sync_panels.py` 的
  `PANEL_MAP` 加一条；`trime.yaml` 的 `preset_keys` 加 `SymbolsTab_kaomoji`；
  7 个既有符号面板的页签行从 8 项扩到 9 项。
- **关键约束（踩过）**：
  - 颜文字比单个符号长得多，**必须** `width: 25` + `key_text_size: 10`，
    否则按 `key_long_text_size`(14sp) 渲染会溢出键框。
  - 页签行权重：8 页签 × 11 + ABC × 12 = 100（`check_layout.py` 要求**精确**等于 100）。
- **顺带修掉的既有缺陷**：7 个符号面板的底栏权重只有 **90**（`空格 width: 30`），
  `check_layout.py` 一直在报「会错位/换行」。本轮把 `空格` 提到 40 → 全部 100。

### 1.2 主界面「近 7 天输入走势」✅

- **目标**：把已有的每日字数数据以迷你柱状图展示，一眼看出趋势。
- **用户价值**：成就反馈；也让「今日字数」从孤立数字变成有上下文的指标。
- **改动面**：小。
- **实施**：`TypedCharCounter` 新增 `history(context, days)`（升序、缺日补 0）；
  `MainFragment` 用它生成 `▁▂▃▄▅▆▇█` 一行走势，作为「今日已输入 N 字」的 summary；
  新增字串 `typed_week_spark`（中/英）。
- **边界**：全 0 时不显示 summary（不摆一条没意义的平地线）；0 仍占一格，保证柱数 = 天数。

---

## 2. P1 — 建议下一步做

### 2.1 自定义短语（快捷短语）编辑界面

- **目标**：设置内增删「编码 → 上屏文本」，例如 `dz` → 收货地址。
- **用户价值**：**日常效率最高的一项**，主流输入法标配。
- **改动面**：中。
- **实施要点**：Rime 侧已有 `table_translator@custom_phrase`，词典文件是
  `app/data/rime/myvocab/custom_phrase.dict.yaml`（或用户目录下的 userdb）。
  界面可仿 `ui/main/settings/` 下已有的列表编辑页；写入后需触发部署
  （`am broadcast -a com.osfans.trime.action.DEPLOY`，见 HANDOVER §5.4）。
- **风险**：写 userdb 的时机与部署失败的回滚；建议先做「只读展示 + 编辑 .dict.yaml」的简单版。

### 2.2 模糊音开关

- **目标**：设置里开关 zh/z、ch/c、sh/s、n/l、an/ang 等常见模糊音。
- **用户价值**：南方口音用户误码率显著下降。
- **改动面**：小～中。
- **实施要点**：规则本就在 `mydomain.custom.yaml` 的 `speller/algebra`。
  开关 = 往 `speller/algebra` 增删对应 `derive` 规则，写受管补丁后部署。
- **风险**：改 `speller/algebra` 会触发整表重编译，首次生效有延迟；
  关闭时若残留 `derive` 规则会出现「该分不分」的怪候选。

### 2.3 键盘高度 / 键距调节

- **目标**：滑块调整 `keyboard_height`、候选栏高度、键距。
- **用户价值**：直接改善手感，是大屏/小手用户的刚需。
- **改动面**：中。
- **实施要点**：复用 `data/theme/BackgroundPatch.kt` 的**受管补丁**写法，
  写进 `<主题id>.custom.yaml`。**必须先读 HANDOVER §5.1 的单位表**：
  `key_text_size`/`candidate_text_size` 是 **sp**，`candidate_view_height`/`candidate_padding`
  是 **dp**，`candidate_spacing` 会被 `max(spacing, dp(spacing))` 放大。
- **风险**：配色键必须写成嵌套 `preset_color_schemes:`，不能写 `preset_color_schemes/<id>`
  （会触发 `ThemeDslExpander` 抛 `UnsupportedDsl` → 键盘打不开，见 README-FORK 坑）。

### 2.4 自动对比度蒙层

- **目标**：按背景图亮度自动叠一层半透明蒙层并切换字色，保证任何背景图下都可读。
- **用户价值**：直击本 fork 的核心玩法（用户会换各种动图背景）。
- **改动面**：中。
- **实施要点**：`data/theme/KeyboardBackground.kt` 里的
  `overlayColorFor()` / `textColorFor()` **已实现但从未接入**。
  需要在 `KeyboardView.onDraw()` 里先画蒙层。
- **风险**：改动渲染结果 + 每帧开销（GIF 背景本来就在解码）；
  建议做成可开关，默认关。

---

## 3. P2 — 有价值但暂缓

| 功能 | 目标 | 为什么暂缓 |
|---|---|---|
| 联想词库扩容 | `predict.txt` 从 107 条扩到数千 | 需在设备侧重新生成 `predict.db`（`rebuild_predict_db.sh` 依赖模拟器链路），且质量取决于语料 |
| 英文纠错开关 + 撤销 | 让用户能关掉纠错、能撤回误纠 | `EnglishCorrector.enabled` 已是全局开关，接 UI 简单；但「撤销」要拦退格 `postRimeJob` 路径，风险高于收益 |
| 双拼方案 | 内置小鹤/微软双拼 | 只需加 schema + 挂 `schema_list`，但本 fork 用户群未必用双拼 |
| 单手模式 | 键盘整体左/右缩窄 | 要动 `KeyboardView` 布局与 `GestureFrame` 触摸映射，牵动候选栏与悬浮窗，回归面大 |
| 滑行输入 | 滑过字母出整词 | librime 无此能力，需自研轨迹解码，准确率风险高 |
| 剪贴板云同步 | 历史跨设备 | 需要后端 + 账号体系，隐私成本高，与「纯本地输入法」定位冲突 |

---

## 4. 已知遗留（不新增功能，但要修）

1. **`clearCompositionIfAny()` 是死代码**：`KEYCODE_DEL` 分支依赖
   `service.hasComposition()`，真机预编辑期间恒为假，所以分支从不生效。
   现行为是「单击逐字删」，与用户确认的行为一致，**暂不改**；
   但若日后有人「修好」`hasComposition()`，单击会静默变成整体清空。
2. **`ime/candidates/ClipboardCandidateInjector.kt` 从未被接入**，且与上游剪贴板链路
   功能重复。`CODE-REVIEW.md` 建议删除。
3. **`check_key_refs.py` 有 1 处误报**：`symbols_en` 的 `click: '''`（ASCII 单引号）。
   脚本没建模 `KeyAction.kt:221-223` 的「键名解析失败 → 当纯文本上屏」回退，
   所以把合法的文本键判成了哑键。
4. **`gen_keywords.py --check` 在改动前就不过**（`app/data/` 与 `keywords.tsv` 不一致），
   会让 `tools/all_checks.sh` 第 1 步中断。属于既有状态，与本轮改动无关。

---

## 5. 开发闭环（每项功能都按这个走）

```
改配置/代码
  → sh tools/all_checks.sh            # 布局/词库/引用/纠错 共 10 步
  → ./gradlew.bat spotlessApply :app:testDebugUnitTest
  → BUILD_ABI=arm64-v8a ./gradlew.bat :app:assembleRelease
  → adb install -r -t dist/trime-release.apk
  → 真机抓图验证（tools/verify-ui/）
  → git commit + push
  → workflow_dispatch(variant=release, publish_release=true) 发签名包
```

**硬约束**（违反会出事，详见 HANDOVER §6）：

- 改 ABI 前先删 `app/build/outputs/apk/release/*.apk`；`-PbuildAbiOverride` 不生效，必须用环境变量。
- 抓图必须 `screencap` 到 sdcard 再 `pull`，**不要** `exec-out > f.png`（PowerShell 会写成 UTF-16）。
- `adb install` 必须带 `-t`；**不要** `adb reconnect offline`。
- 涉及 IME 生命周期/时序的改动**必须插真机验**（模拟器不复现）。
- CI 有 `concurrency.cancel-in-progress`：CI 在跑时 push 会取消它。
