# myime — Trime 二次开发输入法

Android 中文输入法，基于 [Trime](https://github.com/osfans/trime)（Rime 引擎）二次开发。

**键盘**只保留 26 键拼音（`my_pinyin`）和英文（`my_english`）两套，
外加 `symbols` / `number` / `emoji` 三个符号面板（共 5 个键盘）。

**Rime 方案**仍随包附带 7 个（luna_pinyin 系列、stroke、mydomain），
但 `default.custom.yaml` 的 `schema_list` 只启用两个：`mydomain` 与 `luna_pinyin_simp`。
英文不是 Rime 方案，而是 `my_english` 键盘的 `ascii_mode: 1`。

## 相对原版 Trime 的改动

### 1. 键盘布局（`app/src/main/assets/shared/trime.yaml`）

5 个键盘：`my_pinyin` / `my_english` / `symbols` / `number` / `emoji`。
每行宽度的权重和严格 = 100（10 列 × 10 权重）。

```
Q W E R T Y U I O P     ← 每键下滑出 1-0
  A S D F G H J K L     ← 左右半键内缩
⇧ Z X C V B N M ⌫
符 123 ， 空格 。 中英 ⏎
```

### 2. 长按空格拖动光标（iOS 手感）

`preset_keys.space_cursor` 使用 Trime 原生的 `slide_cursor`：

```yaml
space_cursor:
  label: 空格
  send: space
  repeatable: true
  slide_cursor: true     # ← 按住拖动 = 按 ← → 移动光标
```

⚠ 空格**不能**再配 `long_click`。见 `GestureFrame.kt:121`：`isLongPressed`
为真时不触发滑动，配了长按会吃掉拖动。

### 3. 退格上滑清空 / 左滑删整词

```yaml
# 在 preset_keys 里只放「按下行为」，不要在这里写 swipe_*（见下面的坑 1）
backspace_clear:
  send: BackSpace
  repeatable: true

# swipe_* 必须写在 preset_keyboards 的行内按键条目上
- { click: backspace_clear, swipe_up: Clear, swipe_left: BackToPreviousSyllable, width: 15 }
```

- 上滑 → `Clear`，定义是 `text: "{Control+a}{BackSpace}"`（全选并删除），
  也就是把输入框内容整段清掉。
- 左滑 → `BackToPreviousSyllable`，定义是 `send: Control+BackSpace`，
  librime 把它绑到 `Editor::BackToPreviousSyllable`（`editor.cc:193/209`），
  删掉光标前一个音节／整词。

这两个预设原本被删掉了（见坑 5），已从上游补回。

> ⚠️ 「下滑恢复误删」（`swipe_down: Redo`）**已移除**：`Redo` 虽然是合法的
> keysym（`key_table.cc:1236`），但 librime **没有任何地方绑定它**，
> 写上去是静默无操作；上游 trime.yaml 也没有 `Redo` 这个预设。
> Trime 的「删了还能恢复」机制是 `deletedTextBuffer`，只挂在
> `slide_delete: true` 上（`KeyView.kt:141-158`），要那个效果请配 `slide_delete`。

### 4. 剪贴板快捷上屏（用的是 Trime 原生能力，非本 fork 新增）

复制文字后会**立即**在输入栏出现剪贴板内容，**点一下直接上屏**，
长按可进入剪贴板编辑，右侧 ✕ 手动关闭，超过设定时间自动恢复工具栏。

这条链路是 Trime 上游本来就有的，实现在：

- `ime/bar/InputBarDelegate.kt:146-158` — 点击 → `service.commitText(...)` 上屏
- `ime/bar/InputBarDelegate.kt:91-115` — 监听剪贴板变化 + 超时（默认 20 秒）
- `ime/bar/InputBarDelegate.kt:265` — `ClipboardHelper.addOnUpdateListener(...)` 注册
- `data/db/ClipboardHelper.kt` — 数据库、去重规则、条数上限

开关在「设置 → 剪贴板 → 剪贴板建议」（默认开启，依赖「剪贴板监听」）。

> ⚠️ `ime/candidates/ClipboardCandidateInjector.kt` **从未被接入**，也没有任何调用点。
> 它想做的「把剪贴板作为候选词第一条」与上面这条上游链路功能重复，
> 且缺少触发时机、超时、开关，接进去还可能与 Rime 的候选下标冲突。
> 详见 `CODE-REVIEW.md`。建议直接删除该文件。

### 5. 关键词联想

输入 `youxiang` 会同时给出 `邮箱` / `qq.com` / `163.com` / `gmail.com` /
`outlook.com`；输入 `gmail` 直接出 `gmail.com`。

词表维护在 `app/data/rime/myvocab/keywords.tsv`，用脚本合并进主词库。

### 6. GIF 背景真正动起来

Trime 原生已支持图片背景（`ColorTable.Value.Image` → `ColorManager.imageDrawable()`），
但用的是 `BitmapFactory.decodeFile()`，**GIF 只会解出第一帧**。

现已在 `ColorManager.imageDrawable()` 接入：`.gif` 且 Android 9+ 时走
`createAnimatedGif()` 返回自动播放的 `AnimatedImageDrawable`
（`REPEAT_INFINITE` + `start()`），解码失败／尺寸超限／旧系统自动回落到原静态路径。

尺寸保护在 `data/theme/KeyboardBackground.kt` 的 `gifWithinSizeLimit()`：
`Drawable.createFromPath` 不支持 `inSampleSize`，GIF 只能全尺寸解码，
所以超过 `MAX_GIF_DIM = 1440` 的动图不按动图处理。

**用法**：设置 → 键盘样式 → **键盘背景** → 「导入背景图」选 JPG/PNG/WebP/GIF（可多选）
→ 点「应用」。程序会按图片亮度生成一个自带半透明蒙层的配色（亮图配深字、
暗图配浅字）并自动切换过去。

APK 里内置了一张示例动图（`res/raw/bg_preview.gif`，192×192 / 48 帧 / 0.99 MB）：
**首次打开本页会把它铺到背景目录**，直接点「应用」就能看到效果，不用自己找文件传进手机。
铺入只在目录为空或该文件不存在时发生，不覆盖你导入过的图。
用 `res/raw` 而不是 `assets/shared`，是为了绕开 Rime 那套
assets → sharedDataDir 的部署与 `checksums.json` 校验 —— 背景图不是 Rime 配置。

> ⚠️ GIF 的两条限制：
> - **单张 ≤ 4 MB**（`BackgroundPickerFragment.MAX_BYTES`，导入时校验）。
> - **源图最长边 ≤ 4096 px**；更大的会退回静态解码。
>
> 内存控制靠**解码时缩放**而不是拒绝动画：`ColorManager.createAnimatedGif()`
> 用 `ImageDecoder.setTargetSize` 把帧尺寸压到 `ANIMATED_MAX_PIXELS`（≈1 MP）以内
> —— 每帧内存 = 像素数 × 4 字节 ≈ 4 MB，按 12.5 fps 约 50 MB/s 的解码与上传量。
> 所以 **1080p（1920×1080）能正常当动图播**，只是被缩到约 1 MP；
> `Drawable.createFromPath` 做不到这点（它不接受 `inSampleSize`），
> 这也是改用 `ImageDecoder` 的唯一原因。
> 嫌费电就调小 `ANIMATED_MAX_PIXELS`，想要更清晰可调到 2 MP 左右。

写入位置：`<外部files>/rime/<主题id>.custom.yaml` 里的受管区块
（`BackgroundPatch.kt`，带 `# >>> myime background` 标记；
用户在该文件里的其它 patch 不会被覆盖）。

> ⚠️ 补丁的写法有讲究（这里踩过一个大坑，**曾导致输入法界面空白**）：
>
> 配色键必须写成**普通 YAML 嵌套**的 `preset_color_schemes:`，**不要**用 librime
> 的路径语法 `preset_color_schemes/<id>`。原因：
> `ThemeDslExpander.checkPlainKey` 会拒绝含 `/` 的键并抛 `UnsupportedDsl`，
> 于是应用内「读源码」的通道**每次都失败**，主题被迫走
> `ThemeLoader.loadDeployedTheme()` → 调 JNI 的 `Rime.deployRimeConfigFile`
> 做完整部署；native 未就绪或部署失败时主题加载就整个失败，**键盘打不开**。
>
> 而 `applyPatch` 对顶层键是**整体替换**（不深合并），所以写成嵌套时
> **必须把整个 `preset_color_schemes` 表带上**，只写自己那一项会把其它配色全删掉。
> 整表由 `BackgroundPatch.buildPatchBody()` 从当前主题现取，不会过期。
>
> 兜底：`ThemeLoader.loadTheme()` 现在会在「源码 + 已部署产物」都失败时，
> **摘掉用户补丁再读一次源码**（`loadSourceIgnoringCustomPatch`）——
> 一份写坏的用户补丁绝不该让输入法用不了。如果你之前装过会白屏的版本，
> 刷上新版即可自动恢复；也可以手动删掉
> `Android/data/com.osfans.trime/files/rime/trime.custom.yaml`。

> ⚠️ `KeyboardBackground` 里的 `overlayColorFor()` / `textColorFor()`
> （自动对比度蒙层）**仍未接入**：它需要 `KeyboardView.onDraw()` 先画一层蒙层，
> 会改变渲染结果与每帧开销。类注释里已标注未启用。

### 7. 自动学习 + 词库导入导出

学习不需要写代码，靠 Rime 原生机制（`mydomain.schema.yaml`）：

```yaml
translator:
  enable_user_dict: true
  user_dict: mydomain.user
  db_class: userdb
```

用户词库落在 `<外部files目录>/rime/mydomain.userdb`，即默认：

```
/storage/emulated/0/Android/data/com.osfans.trime/files/rime/mydomain.userdb
```

**升级不覆盖**（APK 的 assets 才被覆盖）。
路径来自 `DataManager`：`USER_DIR_NAME = "rime"`，基目录是
`appContext.getExternalFilesDir(null)`（`DataManager.kt:86-96`）——
**不是** `/data/data/...`。若在设置里启用了外部同步（SAF），位置由用户选的目录决定。

导入导出沿用 Trime 自带的 `UserDictManager`（设置 → 用户词典）。

## 内置词库

`app/data/rime/myvocab/mydomain.dict.yaml`：**376 行词条**（319 行正文 + 57 行合并进来的关键词），
去重后 **336 个不同的词**。注意同一词的多编码会占多行，所以「行数 ≠ 词数」。

- **科技/代理** — 科技、Clash、V2Ray、Cloudflare、GitHub、Docker…
- **原神** — 原神、原石、圣遗物、深渊、祈愿、蒙德、璃月、胡桃、雷电将军…
- **崩坏：星穹铁道** — 崩铁、星铁、星琼、开拓者、命途、光锥、模拟宇宙…
- **蔚蓝档案** — 蔚蓝档案、BA、夏莱、阿罗娜、总力战、战术对抗赛…
- **邮箱/域名** — qq.com、163.com、gmail.com、outlook.com、@qq.com…

英文词条用**双编码**兼顾全拼和简拼：

```yaml
Clash	clash	1500     # 打 clash
Clash	cl	1450        # 打 cl
```

## 开发校验

改任何配置后必跑：

```sh
sh tools/all_checks.sh
```

| 脚本 | 检查内容 |
|---|---|
| `sync_panels.py --check` | `panels.yaml` 是否已同步进 `trime.yaml` |
| `gen_keywords.py --check` | `keywords.tsv` 是否已合并进主词库 |
| `check_layout.py` | **每行权重和 = 100**、行内键名重复、`columns` 设置 |
| `check_key_refs.py` | **键名引用能否解析**（见下方「哑键」）、`send` 拼写、手势字段位置 |
| `check_dict.py` | 编码合法性、(词,编码) 完全重复、简拼可达性 |
| `test_input_sim.py` | 模拟 librime 排序，验证全拼/简拼/用户学习 |
| `check_keywords.py` | 关键词联想可达性 |
| `test_edit_distance.py` | 英文纠错：编辑距离算法（含 3 个原误判回归）|
| `test_corrector_words.py` | 英文纠错：词表召回 / 误纠 |
| `test_english_buffer.py` | 英文纠错：输入缓冲状态机（12 个场景）|
| `test_english_corrector.py` | 英文纠错：核心算法（Kotlin 逻辑的 Python 镜像）|

`all_checks.sh` 按上面顺序跑完整 10 个步骤（第 1 步含 2 个脚本），任一失败即中断。

### 哑键：`check_key_refs.py` 防的那个坑

`click` / `long_click` / `swipe_*` 的值要经 `KeyAction` 四级解析
（`KeyAction.kt` init 块）：

1. `preset_keys` 里的预设名
2. `KeyCode.parse` —— 带修饰键的 `Control+BackSpace`
3. `KeyCode.nameToKeyCode` —— `Return` / `space` / `Eisu_toggle` …
4. 键盘名 `Keyboard_xxx` —— 切到另一块键盘

**四级全落空时按键静默失效**：不抛异常、不打日志、外观无差别，只是点了没反应。
`preset_keys` 自身的 `send` 拼错时只在 logcat 留一行 `Timber.e`，用户照样无感。

`check_key_refs.py` 把上面四级在 Python 里镜像一遍，并**直接从
`RimeKeyMapping` 的生成源码抽键名表**（178 个），避免手抄白名单漂移；
生成源码还没产出时退回到内置快照并提示。

两个容易误判的点，脚本已按源码放行：

- `SWITCH_CHARSET` / `LANGUAGE_SWITCH` / `SETTINGS` / `PROG_RED`
  **不在** librime 的 `key_table` 里，走的是 Android 那一级
  （`KeyEvent.keyCodeFromString("KEYCODE_$name")`），由
  `CommonKeyboardActionListener.kt:138-143` 处理，所以这样写是对的。
- 非 ASCII 的纯文字值（如 `long_click: '——'`）是「长按输入这个符号」，
  由 `KeyAction` 的 `text = token.token` 分支直接上屏，同样合法。

### 四个反直觉的坑

1. **手势字段只能写在键盘行内，写在 `preset_keys` 里是死配置**。
   `swipe_up` / `swipe_left` / `swipe_down` / `long_click` 这些字段由
   `TextKeyboard.TextKey` 的行内节点解析（`TextKeyboard.kt:122-123`）；
   而 `preset_keys` 走的是另一个模型 `PresetKey`（`PresetKey.kt:38-55`），
   它**根本没有 swipe 字段**，`KeyAction` 从预设继承字段时也不含 swipe
   （`KeyAction.kt:181-197`）。
   所以在预设里写 `swipe_up: xxx` 会被静默丢弃，滑动退化成普通点击。
   （`slide_cursor` / `slide_delete` 是例外 —— 它们在 `PresetKey` 里，
   所以 `space_cursor` 的长按拖光标是能生效的。）

2. **`columns` 必须设 `-1`**。Trime 换行条件是
   `column >= maxColumns || x + widthPx > allowedWidth`。
   含半键占位（`{width: 5}`）的行键个数 > 10，设 `columns: 10` 会被提前换行。

3. **每行权重和必须严格等于 100，删键后要同步补宽度**。Z 行是
   `Shift(15) + 7 个字母键 + BackSpace(15)`，中间 70 权重由 7 键等分，
   所以每键 `width: 10`。**删掉行内某个键时（例如与上一行重复的 `l/@`），
   必须把这 70 权重重新摊给剩下的键** —— 否则行宽不足 100，整行错位。
   `tools/check_layout.py` 会直接报出来。

4. **简拼 `initial_quality` 不能设 0.6**。会把「崩铁」(2000) 压到 1200，
   排在所有全拼候选之后。设 1.0 与全拼平权。

5. **`click:` / `long_click:` / `swipe_*:` 引用的名字必须真的存在。**
   名字要么是 `preset_keys` 里的预设，要么是 librime 认得的按键名
   （`Return` / `BackSpace` / `Escape` / `Delete` / `Shift_L` / `space` 等）。
   两者都不是的话 `KeyCode.parse` 解析失败，**按键静默失效**（没有报错、没有日志提示）。

   本 fork 重写 `trime.yaml` 时删掉了上游的预设，但键盘里还在引用，
   于是 `copy` / `cut` / `paste` / `undo` / `redo` / `select_all` /
   `CommitComment` / `Return1` / `BackSpace` / `Shift_L` 这些**全都变成了哑键**
   （`Shift_L` 还丢了 `shift_lock: ascii_long`，即自动首句大写；
   `Keyboard_emoji` 则指向一个不存在的键盘名）。
   **已全部从上游补回**（`preset_keys` 从 16 个增至 34 个），
   现在所有引用都能解析。

   **排查已自动化**：`python3 tools/check_key_refs.py <trime.yaml>`
   （已接入 `all_checks.sh` 第 3 步）—— 它把上述四级解析镜像一遍，
   键名表直接取自 `RimeKeyMapping` 的生成源码，比手抄白名单可靠。
   改动主题后跑一遍即可，不必再手工求差集。

## 构建

### GitHub Actions（推荐）

**主仓库是 [`BYDXDM/trime`](https://github.com/BYDXDM/trime)（fork 自 osfans/trime）**，
工作流 `.github/workflows/build-fork.yml` 监听 `main` / `master` / `develop`
三个分支的 push（以及手动 `workflow_dispatch`）。

每次 push 会自动：
1. `git submodule update --init --recursive` 拉取 11 个 C++ 依赖
   （**必须不带 `--depth`** —— `librime-lua-deps` 的 gitlink 指向
   `thirdparty` 分支上的 `9c53b362`，浅克隆取不到会让目录静默留空，
   最终在 NDK r27+ 上炸出 `fseeko` 未声明）
2. `make patch-apply` 打上 `patches/lua.patch`（修 32 位 Android 的
   `fseeko`/`ftello` 可用性判断）
3. `./gradlew :app:assembleDebug`
4. 自动创建 Release `v0.1.<run_number>`，挂上第 3 步产出的**全部 APK**
   （即下面两个 ABI，不是 Trime 支持的全部 4 个 ABI）

> 工作流里那段「必须走 Makefile 的 `make debug`」注释与实际命令不符：
> 实际是 `make patch-apply` + `./gradlew :app:assembleDebug` 两步。
> 补丁仍然会被打上，所以构建结果是正确的，只是注释容易误导人。

### 产物与 ABI

CI 只编 **`arm64-v8a` + `armeabi-v7a`**（真机用的两个），通过环境变量控制：

```yaml
env:
  BUILD_ABI: arm64-v8a,armeabi-v7a
```

它由 `NativeBaseConventionPlugin` 读取（`target.buildAbiOverride`，来自
`BUILD_ABI` 环境变量）：`(target.buildAbiOverride?.split(",") ?: Versions.supportedAbis).forEach { include(it) }`；
不设则回退到 `Versions.supportedAbis`（全部 4 个，含 x86/x86_64 模拟器版）。

| APK | 适用设备 |
|---|---|
| `*-arm64-v8a-debug.apk` | **绝大多数手机** |
| `*-armeabi-v7a-debug.apk` | 老旧 32 位设备 |

> ⚠️ 曾经踩过的坑：`find ... -name "*.apk" | head -1` 会按字母序
> 拿到 `x86_64`（模拟器版），Release 里挂的就是装不上手机的那个。
> 现在改成索引全部 APK，并断言 `arm64-v8a` 必须存在。

### 本地

```sh
git clone https://github.com/BYDXDM/trime.git
cd trime
git submodule update --init --recursive   # 必须不带 --depth
make patch-apply                          # 必须，否则 fseeko 报错
./gradlew :app:assembleDebug
```

**必须 `--recursive`**，否则 librime 缺失、native 层编译失败。

## 上游同步

```sh
git remote add upstream https://github.com/osfans/trime.git
git fetch upstream develop
git merge upstream/develop
```

改动集中在：

| 路径 | 内容 |
|---|---|
| `app/data/rime/myvocab/` | 新增词库 / 关键词表 / 方案（升级不会被上游覆盖）|
| `app/src/main/assets/shared/{trime.yaml,panels.yaml}` | 键盘布局与配色 |
| `app/src/main/java/com/osfans/trime/ime/text/` | **英文输入纠错**（`EnglishCorrector.kt`、`EnglishInputBuffer.kt`）|
| `app/src/main/java/com/osfans/trime/ime/core/TrimeInputMethodService.kt` | 纠错接入点（词边界提交）|
| `app/src/main/java/com/osfans/trime/data/theme/` | GIF 背景（`KeyboardBackground.kt`、`ColorManager.kt`）|
| `app/src/main/java/com/osfans/trime/ui/main/settings/` | 背景选择界面 |
| `build-logic/`、`.github/workflows/build-fork.yml` | 构建与 CI（ABI 裁剪、`runCmd` 容错）|
| `tools/`、`docs/` | 开发校验脚本与文档 |

## 英文输入纠错

英文模式（`ascii_mode: 1`）下 Rime 的 `ascii_composer` 把字母**原样直通上屏**，
不经过词典，因此没有纠错能力。本 fork 在**提交文本的单一出口**加了一层后处理：

- `ime/text/EnglishInputBuffer.kt` —— 把逐字符提交攒成完整单词，到**词边界**才结算
  （空格 / 回车 / 标点 / 中英切换 / 光标离开）。
  绝不在打字途中纠错，否则 `helo` 打到第三个字母就被改掉。
- `ime/text/EnglishCorrector.kt` —— 只纠「编辑距离 1 且唯一候选」或命中已知 typo 表的词，
  宁可漏纠不可误纠。内嵌高频词表 + `NEVER_CORRECT` 白名单（技术词/缩写一律放过）。
- `TrimeInputMethodService.kt` —— 在 `commitText()` 里于**边界字符上屏前**执行纠错；
  回改前会校验原词确实位于光标左侧末尾，对不上就放弃，避免删错用户内容。

原则：**不动 Rime 的 C++**（ASCII 直通本就是 Rime 的设计），只在提交出口做后处理。
详见 [`docs/english-correction.md`](docs/english-correction.md)。

## License

GPL-3.0-or-later（继承自 Trime）
