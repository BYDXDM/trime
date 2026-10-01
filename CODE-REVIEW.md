# myime fork 代码审查报告

审查对象：`https://github.com/BYDXDM/trime`（fork 自 osfans/trime），分支 `develop`，
HEAD `7fd1feb5`（2026-09-30 `fix(en): 在词边界前执行纠错并加强安全校验`）。

审查方式：**实测**而非推测。先用 `git merge-base` 与上游 `osfans/trime` 比对，
确定 fork 相对上游只有 **9 条独有提交、37 个文件**，把审查范围收敛到这 9 条提交；
再逐文件静态阅读 + 运行仓库自带校验脚本 + 完整构建验证。

---

## 一、构建环境（已全部落在 D 盘）

| 组件 | 版本 | 位置 |
|---|---|---|
| Android SDK | platforms 36 / build-tools 36.0.0 | `D:\Android\Sdk` |
| NDK | 28.0.13004108 | `D:\Android\Sdk\ndk\28.0.13004108` |
| CMake | 3.31.6 | `D:\Android\Sdk\cmake\3.31.6` |
| Gradle | 9.7.1 | `D:\gradle-home\tools\gradle-9.7.1` |
| GRADLE_USER_HOME | — | `D:\gradle-home` |
| JDK | Temurin 17.0.17 | `C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot` |

NDK 通过自写脚本 `D:\gradle-home\fetch-ndk.ps1` 分 358 片（2 MB/片）并发下载后合并，
**SHA-1 校验通过**：`f79a00c721dc5c15b2bf093d7bb2af96496a42b2`，748,943,210 字节，与官方值一致。

### 构建过程中踩到并解决的 3 个环境障碍

这些**不是仓库的 bug**，但会让 Windows 本地构建失败，已修好并记录：

1. **代理端口会变**。系统代理从 `10808`（SOCKS5）变成 `7897`（HTTP），
   Gradle 报 `Can't connect to SOCKS proxy: Connection refused`。
   已把代理写进 `D:\gradle-home\gradle.properties`（用户级，不动仓库文件）。

2. **AGP 拒绝非 ASCII 项目路径**。`D:\ai\工作\trime` 含中文，
   AGP 直接报错 `Your project path contains non-ASCII characters`。
   已在用户级 gradle.properties 加 `android.overridePathCheck=true`。

3. **构建脚本硬编码 `python3`**。`OpenCCDataPlugin.kt:112` 调用 `python3`，
   而 Windows 上 `C:\...\WindowsApps\python3.exe` 是 **0 字节的 Store 存根**，
   返回 9009。已在 `D:\gradle-home\tools\bin\python3.exe` 放了真实启动器
   （复制 `py.exe`，它经注册表定位 Python，不依赖自身目录）。

---

## 二、已验证健康的部分

`tools/` 下 **10 项校验脚本全部通过**（实测）：

| 脚本 | 结果 |
|---|---|
| `sync_panels.py --check` | trime.yaml 与 panels.yaml 一致 |
| `gen_keywords.py --check` | 57 条关键词已合并 |
| `check_layout.py` | 5 个键盘所有行宽 = 100.00，无重复键 |
| `check_dict.py` | 通过 |
| `test_input_sim.py` | 通过 |
| `check_keywords.py` | 关键词联想可达 |
| `test_edit_distance.py` | 通过（含 3 个原误判回归） |
| `test_corrector_words.py` | 召回 32/32，误纠 0/73 |
| `test_english_buffer.py` | 12 个场景通过 |
| `test_english_corrector.py` | 通过 |

**构建验证**：`:app:assembleDebug` 成功（arm64-v8a），产出
`com.osfans.trime-v0.1.8-0-g7fd1feb5-arm64-v8a-debug.apk`，19.21 MB，
含 `lib/arm64-v8a/librime_jni.so`（13 MB），zip 完整性校验 OK。
`spotlessCheck` 通过。

结论：布局权重、词库编码、英文纠错逻辑、native 构建链路本身是健康的。

---

## 三、确认的缺陷与修复

### P1 ✅ 已修复：`tools/` 脚本在中文 Windows 下必然崩溃

README 要求「改任何配置后必跑 `sh tools/all_checks.sh`」，但脚本输出 `✓`/`✗`/`→`
等字符，Windows 控制台默认 GBK 编码下抛：

```
UnicodeEncodeError: 'gbk' codec can't encode character '\u2713'
```

`all_checks.sh` 开头是 `set -e`，第一步就中断，**整套校验形同虚设**。

**修复**：新增 `tools/_console.py`（`ensure_utf8_stdout()`：优先 `reconfigure(encoding='utf-8')`，
不可用时退化为 `errors='replace'`，保证任何情况都不崩），8 个脚本导入后调用；
`all_checks.sh` 额外导出 `PYTHONIOENCODING=utf-8` / `PYTHONUTF8=1`；
`.gitignore` 补 `__pycache__/`、`*.py[cod]`。

**验证（含对照组）**：强制 `PYTHONIOENCODING=gbk` 下，
打补丁的脚本正常输出 `✓` 且 exit 0；对照组（直接把 stdout 设为 gbk 打印同样字符）
必然抛 `UnicodeEncodeError` — 证明修复既必要又有效。

### P2 ✅ 已查清：`ClipboardCandidateInjector` 是死代码，且**功能上游早已原生提供**

**第一层结论**：该类在仓库中**没有任何调用点**；它注释里引用的 `INTEGRATION.md`
不存在；它给出的接入点 `TrimeInputMethodService.updateCandidatesView()` 在这个
代码库里**根本没有这个方法**（真实候选流是消息驱动的
`CandidatesView.handleRimeMessage` → `Candidates.Paged` → `PagedCandidatesUi`），
它的 `shouldInject(..., currentInput: List<String>, ...)` 签名也和真实的
`Candidates.Paged`/`CandidateProto` 类型对不上。也就是说这个类是照着**想象出来的 API**
写的。

**第二层结论（更要紧）**：它想做的事 ——「复制后点一下上屏」——
**Trime 上游已经完整实现，而且更完善**，链路经逐行核对确认是活的：

| 环节 | 位置 | 证据 |
|---|---|---|
| 剪贴板变化监听 | `data/db/ClipboardHelper.kt:59-68` | 注册系统 `OnPrimaryClipChangedListener`，受「剪贴板监听」开关控制（默认开）|
| 监听器注册 | `ime/bar/InputBarDelegate.kt:265` | `ClipboardHelper.addOnUpdateListener(onClipboardUpdateListener)` |
| 事件处理 | `ime/bar/InputBarDelegate.kt:91-103` | 内容变化 → 输入栏切到「剪贴板」状态显示内容 |
| **点击上屏** | `ime/bar/InputBarDelegate.kt:146-151` | `suggestionView.setOnClickListener { service.commitText(it) }` |
| 超时消失 | `ime/bar/InputBarDelegate.kt:105-115` | 默认 20 秒后自动恢复工具栏 |
| 长按编辑 / 关闭 | `ime/bar/InputBarDelegate.kt:152-161` | 进剪贴板编辑页 / ✕ 手动关闭 |
| 用户开关 | `AppPrefs.kt:415-427` | 「剪贴板建议」默认开启，超时可配 |

**所以：我没有接入它，而且不建议接入。** 理由：

1. 功能与上游重复，接进去会出现**两个剪贴板上屏入口**；
2. 它**没有触发时机** —— Rime 空闲时不发消息，没人会调用它，要让它工作就得
   自己再实现一遍剪贴板监听（重复 `ClipboardHelper`）；
3. 把合成项插到候选列表头部会与 `selectCandidate(index)` 的下标语义冲突，
   守卫一旦放宽就可能**选错候选**（这是输入法里最严重的错误类型）；
4. 候选栏在空闲时是 `INVISIBLE`（`CandidatesView.evaluateVisibility()`），
   还要额外改可见性逻辑，等于在别人的渲染路径上开洞。

**已做的处理**：

- `ClipboardCandidateInjector.kt` 的类注释重写为如实说明「未接入、与上游重复、
  建议删除」，并给出可核对的 `文件:行号` 证据，避免后人再被误导。
- `README-FORK.md` 第 4 节从「本 fork 新增的剪贴板首候选」改为如实描述
  「用的是 Trime 原生剪贴板建议」+ 指出现存死文件。
- 删除了对不存在的 `INTEGRATION.md` 的引用。

**建议你决定**：是否删除 `ClipboardCandidateInjector.kt`。
我没有替你删——它是 fork 作者的原始文件，删除不可逆。

### P3 ✅ 已修复：GIF 动图背景真正接入 `ColorManager`

`KeyboardBackground.kt` 同样是死代码，`AnimatedImageDrawable` 在全仓库仅出现在它内部。
`ColorManager.imageDrawable()` 用的仍是上游 `BitmapFactory.decodeFile()`，
对 GIF 只解出第一帧 —— README 第 59-65 行声称的「GIF 背景真正动起来」不成立。

**修复**：`ColorManager.imageDrawable()` 加 GIF 前置分支（`.gif` + Android 9+），
新增 `createAnimatedGif()`：先过尺寸校验，再解码为 `AnimatedImageDrawable`，
设 `REPEAT_INFINITE` 并 `start()`；解码失败/旧系统自动回落原静态路径。
`KeyboardBackground.gifWithinSizeLimit()` 由 `private` 改 `internal` 供复用；
删除 `KeyboardBackground` 里未被调用的重复实现（`loadDrawable`、`resolveImageFile`），
避免两套图片解析逻辑再次漂移。

`.gif` 本就在 `ColorTable.IMAGE_SUFFIXES`（`ColorTable.kt:44`），
所以主题里写 `keyboard_back_color: girl.gif` 会走到这条新分支。

### P4 ✅ 部分修复：`KeyboardBackground` 自身的蒙层/文字色矛盾

- `overlayColorFor()`：亮背景 → 盖 `0x8C000000` **深色**蒙层压暗。
- `textColorFor()`（原实现）：亮背景 → 用 `0xFF1A1A1A` **深色**字。

蒙层压暗后仍配深色字，文字会糊进背景 —— **这是真实的自相矛盾**，已改为同向
（亮背景压暗 → 浅色字；暗背景提亮 → 深色字）。

> ⚠️ **纠正我上一轮的一个误判**：我最初把 `BackgroundPickerFragment` 的同类逻辑
> 也判定为「写反了」并反转了方向，这是错的。那里写入的是 `key_back_color`——
> **按键自身的半透明底色**（叠在背景图上，文字再画在按键之上），不是整键盘蒙层。
> 原逻辑「亮背景 → 半透明白键 + 深色字」是**自洽**的（键比背景更亮）。
> 已**还原为原方向**，只保留真正的清理。两处机制不同，不构成矛盾。

### P5 ✅ 已修复：恒等分支

```kotlin
val overlayAlpha = if (light) 0x99 else 0x99   // ← 两分支同值，if 无意义
```

已替换为命名常量 `OVERLAY_ALPHA`；魔法数字 `140` 提取为 `LUM_THRESHOLD`，
并加注释说明该常量与 `KeyboardBackground.LUM_THRESHOLD` 需同步。

### P6 ✅ 已修复：GIF 绕过降采样

`Drawable.createFromPath()` 不接受 `BitmapFactory.Options`，GIF 必然全尺寸解码。
已加 `gifWithinSizeLimit()`：用 `inJustDecodeBounds` 只读头部量尺寸，
超过 `MAX_GIF_DIM = 1440` 就放弃动图、回落静态降采样路径，
避免一张 4000×4000 的动图（单帧 64 MB）把输入法进程拖到被系统回收。

### P7 ✅ 已修复（本轮新发现）：项目自身的 `spotlessCheck` 不通过

CI 风格检查是 `spotless + ktlint 1.8.0`，但跑 `spotlessCheck` **失败**，
违规文件 4 个：

| 文件 | 来源 |
|---|---|
| `KeyboardBackground.kt` | fork 新增 |
| `BackgroundPickerFragment.kt` | fork 新增 |
| `EnglishInputBuffer.kt` | fork 新增（我未改过） |
| `ProjectExtensions.kt` | fork 修改过（我未改过） |
| `EnglishCorrector.kt` | fork 新增（我未改过） |

后三个是我**改动之前就存在**的违规 —— 也就是说 fork 从来没通过 ktlint。
已用 `spotlessApply` 统一格式化（仅换行/缩进/注释空格，无语义变化），
现在 `spotlessCheck` 通过。

**验证**：格式化后重跑 10 项校验脚本全部通过 —— 这一步很关键，因为
`test_english_buffer.py` / `test_english_corrector.py` 会**解析 `EnglishCorrector.kt`
源码**做验证，格式化有可能破坏其解析，实测未受影响。随后 `assembleDebug` 重新编译成功。

---

## 四、说明（非缺陷）

`app/src/main/assets/shared/` 下有 18 个显示为 0 字节的文件
（`default.yaml`、`luna_pinyin.dict.yaml`、`essay.txt`、`mydomain.schema.yaml` 等）。
经核实它们是**符号链接**，指向 `app/data/rime/<submodule>/` 下的真实文件
（例：`default.yaml → ..\..\..\..\data\rime\prelude\default.yaml`），属正常设计。

`EnglishCorrector.enabled` 是 `object` 上的 `@Volatile var` 全局状态。
对单输入法进程可接受，且已在 `onStartInputView` 与 `Rime.kt:393` 两处同步，
属有意取舍（注释已说明「为保持 `correct` 纯函数便于测试」），未改动。

---

## 五、产物

| APK | 大小 | ABI | native |
|---|---|---|---|
| `com.osfans.trime-v0.1.8-0-g7fd1feb5-arm64-v8a-debug.apk` | 19.21 MB | arm64-v8a | `librime_jni.so` 13 MB |
| `com.osfans.trime-v0.1.8-0-g7fd1feb5-armeabi-v7a-debug.apk` | 18.16 MB | armeabi-v7a | `librime_jni.so` |

产物在 `app/build/outputs/apk/debug/`，两个 APK 的 zip 完整性均校验 OK。

**v7a 这个 32 位构建顺带验证了 `patches/lua.patch` 是必需的**：
该补丁改的正是 `#if defined(LUA_USE_POSIX)` 里 32 位 Android 下
`fseeko`/`ftello` 的可用性判断（`ANDROID_PLATFORM >= 24` / `__LP64__`）。
打补丁前 32 位会报 `fseeko` 未声明；本次打完补丁后 32 位编译通过。

## 六、决策与落实

1. ✅ **已删除** `ime/candidates/ClipboardCandidateInjector.kt`：查清是死代码，
   且与上游剪贴板建议功能重复（见 P2）。删除后 `ime/candidates` 下只剩正常组件，
   全量编译与测试均通过。
2. ⏸ **`KeyboardBackground.overlayColorFor` / `textColorFor` 仍未接入**：
   「自动对比度蒙层」需要 `KeyboardView.onDraw()` 先画一层
   （该方法当前只有 `super.onDraw(canvas)`），会改变渲染结果与每帧开销。
   已在类注释标注为未启用。
   （注：`textColorFor` 与 `overlayColorFor` 的自相矛盾已修正，但因为未接入，
   目前对运行时无影响，属于「将来接入时才生效」的预防性修复。）
3. ✅ **10 个失败的单元测试已改成 fork 基准**（290 个用例全绿），CI 也补上了
   `spotlessCheck` + `testDebugUnitTest`，见下面的「八」。
4. ✅ **README-FORK.md 已逐条核对并订正**，见下节。

---

## 七、README-FORK.md 逐条核对（「写了但没实现」专项）

方法：把 README 中所有可证伪的声明（配置值、数量、路径、脚本、工作流）逐条
对照代码与产物验证，而不是靠印象。结论分三类。

### A. 写了但**功能完全不生效**（最严重）

**A1. `swipe_up: Clear` / `swipe_left: BackToPreviousSyllable` / `swipe_down: Redo`
是死配置 —— 『退格上滑清空』从来没生效过。**

四处独立证据：

1. 这三行写在 `trime.yaml` 的 `preset_keys` 段（`trime.yaml:242/244/246`，
   该段范围 `L203~L248`），不在 `preset_keyboards` 的行内按键上。
2. `preset_keys` 由 `PresetKey` 模型解码（`Theme.kt:69-71`），而
   `PresetKey.decode()`（`PresetKey.kt:38-55`）**没有 swipe_up / swipe_left /
   swipe_down 字段** —— YAML 里这几个键连读都没读。
3. `KeyAction` 从预设继承字段时拷贝的是 command / option / select / toggle /
   preview / shiftLock / commit / text / sticky / repeatable / functional /
   **slideCursor** / slideDelete / states / label / send
   （`KeyAction.kt:181-197`）—— **同样不含任何 swipe 字段**。
   （`slide_cursor` 在里面，所以 `space_cursor` 的长按拖光标是**能生效**的；
   这个不对称很可能就是作者误以为 swipe 也能用的原因。）
4. 键盘引用方式是 `- { click: backspace_clear, width: 15 }`
   （`trime.yaml:315/380`），行内节点只有 `click`；而 `Key.keyActions` 只从
   行内节点构建（`Key.kt:28-35` ← `TextKeyboard.kt:122-123`，
   字段名 = `KeyBehavior` 枚举名小写）。

**实际后果**：在 ⌫ 上滑动会走到 `Key.getAction()`（`Key.kt:252`）的兜底分支
`?: click`，也就是**发送普通的 BackSpace** —— 和点一下完全一样。

**附带发现**：`Clear` 与 `Redo` 虽然是合法 keysym
（`key_table.cc:1181` / `1236`），但 librime **没有任何地方绑定它们**
（editor.cc 的 `keymap.Bind` 全表里没有），所以即便内联也是静默无操作。
`BackToPreviousSyllable` 则**不在** librime 的 1266 条按键名表里，
无法解析成按键；它只是 librime 内部的处理函数名
（`editor.cc:28` 的 `{"back_syllable", &Editor::BackToPreviousSyllable}`），
真正可用的目标键是 `Control+BackSpace`（`editor.cc:193/209`）。

**已修复**：把手势移到键盘行内，并换成有真实绑定的目标（见下一节）。

**A2（已修）**：`KeyboardBackground` / `ClipboardCandidateInjector` 两个类未接入 ——
详见第三节 P2、P3。

**A3. 自定义背景的「导入背景图」界面完全不可达 —— 三层都是断的。**

`ui/main/settings/BackgroundPickerFragment.kt` 全仓只有自身定义引用
（排除构建产物后仅 `CODE-REVIEW.md` 提及），三层问题：

1. **进不去**：所有设置页都在 `ui/main/NavigationRoute.kt` 的
   `createGraph()` 里注册（`fragment<X, Route> { label = ... }`），
   那里**没有** `BackgroundPickerFragment`，也没有对应的 `NavigationRoute` 子类。
   用户没有任何入口能打开它。
2. **没人建目录**：`<userDataDir>/backgrounds/mybg/` 唯一的创建点是
   `BackgroundPickerFragment.kt:101` 的 `mkdirs()` —— 而这个页面永远不会运行，
   所以用户得自己手建目录。（`ColorManager.kt:246/248` 的读取端是好的。）
3. **就算进去了也激活不了**：它把背景写进 `preset_color_schemes/user_bg`
   （**定义**一个新配色），但没有任何代码把「当前配色」切到 `user_bg`；
   之后调的 `ThemeManager.selectTheme("user_bg")` 语义也不对 ——
   `selectTheme(configId)` 选的是**主题配置**（`trime.yaml` 这类文件，
   见 `ThemeManager.kt:147-159`），不是配色方案，
   而 `user_bg` 是它刚写进去的配色名，`getThemeById("user_bg")` 必然落空并回退。

**结论：「自定义背景」的图形界面方式原本不可用。** 已在本次修复中接通（见下）。

**A3 已修复：背景设置页接通**

- 注册路由：`NavigationRoute` 新增 `KeyboardBackground`，并在 `createGraph()`
  里 `fragment<BackgroundPickerFragment, KeyboardBackground>`。
- 入口：设置 → 键盘样式（`ThemeSettingsFragment`）新增「键盘背景」一项。
  新增字符串资源 `keyboard_background` / `keyboard_background_summary`
  （`values/` 英文、`values-zh-rCN/` 中文）。
- 修掉激活逻辑：原来写的是「定义一个新配色 `user_bg` → 调
  `ThemeManager.selectTheme("user_bg")`」。`selectTheme(configId)` 选的是**主题配置**
  不是配色方案，`user_bg` 必然查不到而回退。现在改为：
  写补丁 → `ThemeManager.selectTheme(当前主题id)` 重载主题 →
  `ColorManager.setColorScheme(该配色)`（该方法会同时持久化
  `prefs.normalModeColor`）。
- 补丁写入抽成 `data/theme/BackgroundPatch.kt` 的纯函数 `merge()`，
  保证**顶层 `patch:` 唯一**（重复会成为非法 YAML）且**只覆盖自己上次写的块**
  （`# >>> myime background` / `# <<< myime background` 标记之间），
  用户在同一 `.custom.yaml` 里的其它 patch 不会被冲掉。
- 顺带修了多选导入：界面允许一次选多张（`EXTRA_ALLOW_MULTIPLE`），
  但原实现只读 `result.data.data`，第二张起被丢弃；现在读 `clipData` 全量导入。
- 新增单元测试 `app/src/test/.../BackgroundPatchTest.kt`（7 个用例，全通过），
  覆盖：空文件、用户已有 `patch:`、无 `patch:`、重复应用、受管块夹在用户内容中间，
  并用 kaml 验证输出可解析。

> ⚠️ **本节原先把结论写反，现订正。** 最初 `BackgroundPatch` 用的是 librime 路径语法
> `preset_color_schemes/<id>`，但 `ThemeDslExpander.checkPlainKey()` 对含 `/` 的键
> 直接抛 `UnsupportedDsl`（`ThemeDslExpander.kt:241`），于是应用内「读源码」的通道
> **每次都失败**，主题被迫回落到 `loadDeployedTheme()` → 调 JNI 做完整部署；
> native 未就绪或部署失败时主题加载整个失败 —— **键盘打不开、界面空白**。

**所以最终采用的是普通嵌套 YAML**（与 README-FORK 一致）：

```yaml
patch:
  preset_color_schemes:
    user_bg:
      keyboard_back_color: girl.jpg
```

代价是 `applyPatch` 对顶层键**整体替换**、不做深合并，所以嵌套写法
**必须把整个 `preset_color_schemes` 表带上**，只写自己那一项会把其它配色全删掉。
整表由 `BackgroundPatch.buildPatchBody()` 从当前主题现取，不会过期。

兜底：`ThemeLoader.loadTheme()` 会在「源码 + 已部署产物」都失败时，
**摘掉用户补丁再读一次源码**（`loadSourceIgnoringCustomPatch`）——
一份写坏的用户补丁绝不该让输入法用不了。

**A4. `preset_keys` 缺了 15 个被引用的预设 —— 一批按键是哑键。**

把 `preset_keys` 里定义的名字与键盘中 `click` / `long_click` / `swipe_*` 的取值
求差集，得到 15 个「引用了但没定义」的名字。上游 `trime.yaml` 里全都有定义
（L878-L913），是这个 fork 重写主题时删掉的：

| 被引用 | 出现处 | 上游定义 | 后果 |
|---|---|---|---|
| `copy` | Z 行长按 | `send: Control+c` | **哑键** |
| `cut` | Z 行长按 | `send: Control+x` | **哑键** |
| `paste` | Z 行长按 ×2 | `send: Control+v` | **哑键** |
| `undo` | Z 行长按 | `send: Control+z` | **哑键** |
| `redo` | Z 行长按 | `send: Control+Shift+z` | **哑键** |
| `select_all` | A 行长按 | `send: Control+a` | 已补回 |
| `CommitComment` | 回车长按 ×5 | `send: Control+Shift+Return` | **哑键** |
| `Return1` | 回车 composing ×5 | `send: Return` | **哑键** |
| `Shift_L` | Shift 键 | `send: Shift_L, shift_lock: ascii_long` | 按键靠名字回退能用，但**丢了自动首句大写** |
| `Clear` | 退格上滑 | `text: "{Control+a}{BackSpace}"` | 已补回 |
| `BackToPreviousSyllable` | 退格左滑 | `send: Control+BackSpace` | 已补回 |
| `Keyboard_emoji` | 符号面板 ×2 | 上游也没有 | 指向不存在的键盘，**哑键** |
| `Return` `Delete` `Insert` `Escape` `space` | 各键 | 同名按键 | 靠 librime 按键名回退**仍可用** |

**已全部补回**（`preset_keys` 由 16 个增至 34 个），并把退格的滑动指回
`Clear` / `BackToPreviousSyllable`（作者原意）。补回清单：

| 补回的预设 | 定义（取自上游）|
|---|---|
| `Shift_L` | `{label: Shift, send: Shift_L, shift_lock: ascii_long}` |
| `Return` / `Return1` | `{label: enter_labels, send: Return}` / `{label: Enter, send: Return}` |
| `BackSpace` | `{label: 退格, repeatable: true, send: BackSpace}` |
| `space` | `{repeatable: false, functional: false, send: space}` |
| `Escape` / `Insert` / `Delete` | `{send: Escape}` / `{send: Insert}` / `{send: Delete}` |
| `select_all` | `{label: 全选, send: Control+a}` |
| `Clear` | `{label: 清除, text: "{Control+a}{BackSpace}"}` |
| `cut` / `copy` / `paste` | `Control+x` / `Control+c` / `Control+v` |
| `undo` / `redo` | `Control+z` / `Control+Shift+z` |
| `BackToPreviousSyllable` | `{label: 删音节, send: Control+BackSpace}` |
| `CommitComment` | `{label: 编码, send: Control+Shift+Return}` |
| `Keyboard_emoji` | `{label: '☺', send: Eisu_toggle, select: emoji}`（上游没有，按同族 `Keyboard_*` 的写法补，指向 fork 自己的 `emoji` 键盘）|

`Keyboard_emoji` 是符号/数字面板里两个「☺」键的入口，之前引用了不存在的名字 →
**两个键是哑键**；`select` 的语义见 `CommonKeyboardActionListener.kt:139`
（`send: Eisu_toggle` + `select: <键盘名>` = 切换键盘）。

补完后**所有引用都能解析**（用脚本对 `preset_keys` 定义集与键盘引用集求差集验证，
结果为 0 个悬空）。`trime.yaml` 的 `preset_keys` 现在与上游对齐，
其余差异只在于 fork 只保留 5 个键盘。

> ⚠️ 注意 `Shift_L` 补回后**行为有变化**：恢复上游的 `shift_lock: ascii_long`
> （长按 Shift 锁定 / 自动首句大写）。这正是单元测试 `KeyActionTest` 期望的行为，
> 也是上游默认；如果你原来不想要它，把这一项删掉即可。


**A5. 单元测试有 10 个失败，但 fork 的 CI 根本不跑测试。**

`./gradlew :app:testDebugUnitTest` 实测 **299 个用例、10 个失败**。
失败全部集中在「断言上游主题内容」的测试上：期望作者是 `預設`（fork 是 `myime`）、
期望 18 个键盘（fork 是 5 个）、期望上游的 `letter` / `scj6` 键盘与 37 个预设。
也就是说这些测试是**按上游 trime.yaml 写死的**，fork 重写主题后必然失败。

本次改动把失败数从 **17 降到 10**：
补回预设直接修好 6 个（`KeyActionTest` 现在全绿），另 1 个是我自己测试的断言写错（已修正）。
剩下的 10 个需要决定方向：是让这些测试跟着 fork 的主题更新，还是把上游那 13 个键盘
也搬回来。**在一部只保留 5 个键盘的 fork 里，这些断言不可能靠改配置满足。**

`build-fork.yml` 只跑 `:app:assembleDebug`，既没有 `spotlessCheck` 也没有
`testDebugUnitTest`（上游 `commit-ci.yml` 是有的，被这个 fork 删掉了）。
所以风格违规和测试失败都能长期潜伏。**建议给 CI 补上这两步**。



**手写配置的方式可用**（本次已顺带把 GIF 接通）：

1. 自己建目录并放图：`<外部files>/rime/backgrounds/mybg/girl.jpg`
   （`trime.yaml` 的 `general_style/background_folder: mybg` 决定子目录名）
2. 在 `<外部files>/rime/trime.custom.yaml` 里 patch 对应配色，
   把图片键改成文件名：
   ```yaml
   patch:
     "preset_color_schemes/user_light":
       keyboard_back_color: girl.jpg
       candidate_back_color: girl.jpg
       root_background: girl.jpg
   ```
   **不需要 Rime 部署** —— `ThemeLoader.kt:260-274` 会自己按 librime 的
   自动 patch 规则把 `<id>.custom.yaml` 注入进来。
3. 在「设置 → 主题」里选中「自定义背景·浅 / 深」
   （激活逻辑见 `ColorSchemeResolver.resolve()`）。

`trime.yaml` 里 `user_light` / `user_dark` 两个配色已经备好（含蒙层与文字色），
但 `keyboard_back_color` 目前是占位值 `0x00000000`，所以**开箱没有任何背景图**。

### B. 数字与路径写错（已订正）

| README 原文 | 实测 |
|---|---|
| `mydomain.dict.yaml`，**349 词条** | **376 行**（319 正文 + 57 关键词），去重后 **336 个词** |
| 词表含 **「AI」** | 词库里没有 `AI`（其他列出的词全部命中）|
| 用户词库在 `/data/data/com.osfans.trime/files/rime/` | 在 **`getExternalFilesDir(null)`** 下：`/storage/emulated/0/Android/data/com.osfans.trime/files/rime/`（`DataManager.kt:86-96`，`USER_DIR_NAME="rime"`）|
| 「只保留 26 键拼音和英文**两种输入模式**」| 键盘确实是 2 套（+3 符号面板），但 APK 仍打包 **7 个 Rime 方案**；`default.custom.yaml` 启用的是 `mydomain` + **`luna_pinyin_simp`**（不是「英文」）。英文是键盘的 `ascii_mode`，不是 Rime 方案 |
| 工作流「监听 `develop` 分支」| 实际 `main` / `master` / `develop` |
| Release「挂上**全部 ABI** 的 APK」| 与同页「只编 2 个 ABI」自相矛盾；实际是 2 个 |
| 工作流注释说「必须走 Makefile 的 `make debug`」| 实际命令是 `make patch-apply` + `./gradlew :app:assembleDebug`（补丁会打，结果正确，只是注释误导）|

### C. 文档缺漏（已补）

- **校验脚本表格只列了 6 个**，`tools/` 实有 10 个；缺
  `test_edit_distance.py`、`test_corrector_words.py`、`test_english_buffer.py`、
  `test_english_corrector.py`。已补全为 10 行表格。
- **`all_checks.sh` 漏跑 `test_english_corrector.py`** —— README 说「改完必跑」，
  但 10 个脚本里有 1 个从不执行。已加入并重排为 9 步。
- **完全没记录英文纠错功能**：fork 最近两条提交加了
  `EnglishCorrector.kt`(416 行) + `EnglishInputBuffer.kt`(112 行) +
  `docs/english-correction.md`(158 行)，README 的「改动」清单里一个字都没有。
  已新增「英文输入纠错」一节。
- **「改动集中在」清单不全**：漏了 `ime/text/`（整个英文纠错）、
  `ime/core/TrimeInputMethodService.kt`、`build-logic/`、`.github/workflows/`、
  `docs/`；却把 `ime/candidates` 列为改动区（实际只有那个死文件）。已改为表格。
- 新增「坑 1：手势字段只能写在键盘行内」—— 正是 A1 的成因，属同类陷阱。

### D. 核对为**属实**的声明（无需改动）

- 5 个键盘名恰为 `my_pinyin` / `my_english` / `symbols` / `number` / `emoji` ✓
- 每行权重和 = 100（`check_layout.py` 实测 5 个键盘全部通过）；Z 行 8 键确为 `width: 8.75`（15+70+15=100）✓
- `columns: -1` 在 5 个键盘上都已设置 ✓
- `space_cursor` 的 `slide_cursor: true` **确实生效**（`sliderCursor` 在 `KeyAction.kt:193` 被继承）✓
- 「空格不能配 `long_click`」及所引 `GestureFrame.kt:121` —— 该行正是
  `!isLongPressed` 判断，**引用准确** ✓
- `mydomain.schema.yaml` 的 `enable_user_dict: true` / `user_dict: mydomain.user` /
  `db_class: userdb` ✓；`initial_quality: 1.0` ✓
- `UserDictManager` 存在（`data/userdict/UserDictManager.kt`），设置项也在 ✓
- `Clash → clash(1500)` 与 `Clash → cl(1450)` 双编码确如所写 ✓
- 关键词联想用例（`youxiang` / `gmail` 等）由 `check_keywords.py` 实测通过 ✓
- `BUILD_ABI: arm64-v8a,armeabi-v7a` 与 `NativeBaseConventionPlugin` 的读取方式 ✓
- `java-version: 25` —— 与上游 `commit-ci.yml` 一致，**不是** fork 的问题（已排除）
- 「Release 里挂错 ABI」的踩坑记录与工作流里 `grep -q arm64-v8a` 断言相符 ✓

### E. 本轮顺带修掉的一个我自己引入的回归

我用 PowerShell 改 `all_checks.sh` 时，`Set-Content -Encoding UTF8` 给它加了
**BOM** 并把换行变成 **CRLF** —— 这会让 `#!/bin/sh` 在 Linux 上失效
（实测 `bash` 报 `line 1: ﻿#!/bin/sh: No such file or directory`）。
已恢复为**无 BOM + LF**，并用 `bash -n` 验证语法通过、`git diff --numstat`
确认为 20 增 8 删（不是整文件重写）。


---

## 八、本轮落实（2026-10-01）

针对上面「六」的决策项与 A5，本轮做了三件事，全部有实测证据。

### 1. 删除死代码 `ClipboardCandidateInjector.kt`

删除前用全仓检索确认**零调用点**（只有自身定义那一行命中），删后编译与测试均通过。

```
删除前: app/.../ime/candidates/ClipboardCandidateInjector.kt:40  object ClipboardCandidateInjector {
        → 全仓仅此 1 处命中
删除后: ime/candidates/ 下只剩 CandidateItemUi / CandidateViewHolder / compact / popup / unrolled
```

### 2. 10 个失败测试改成 fork 基准 —— 290 个用例全绿

失败根因是这些测试**按上游 trime.yaml 写死**（期望作者 `預設`、18 个键盘、
`letter` / `scj6` / `cangjie5`、106 个预设、37 个配色），而 fork 是
`myime` / 5 个键盘 / 34 个预设 / 4 个配色。在一个只保留 5 个键盘的 fork 里，
这些断言不可能靠改配置满足，只能改基准。

改法（**不是简单删断言**，而是换成 fork 的真实值并保留有价值的约束）：

| 测试 | 改动 |
|---|---|
| `GeneralStyleTest` | `name` → `myime`；`candidatePadding` 5→0、`keyHeight` 44→52、`labelTextSize` 22→13、`keyboardPaddingRight` 40→0（均为**实测**值）|
| `ThemeGoldenTest` | 整段换成 fork 基准：4 个配色、34 个预设、5 个键盘；断言 `copy`/`paste`/`BackToPreviousSyllable` 的 send 值（守住「哑键」回归）；保留「每个键盘非空」这条**防空白键盘**的关键约束 |
| `ThemeDslExpanderTest` | 原依赖 fork 已删的 `letter`/`scj6`；改为用**同文件 fixture** 继续覆盖 `__include` 展开能力，对内置主题只断言「展开后 5 个键盘可解码且都有按键」|
| `ThemeDiagnosticsTest` | 期望清单更新为实测的 4 条（`/android_keys` 已不存在，顺序亦变）|

`ThemeGoldenTest` 第一段（`tongwenfeng.trime.yaml`，上游自带）**未改动**，仍然全绿。

所有期望值都来自**实跑探针**取到的真实值，不是照抄文档 —— 过程中就纠正了两处
凭印象会写错的：`my_pinyin` 的 `swipe_up` 是 `!`（键面上的 `1` 只是 label/hint）、
`labelTransform` 是 `UPPERCASE` 而 `my_english` 是 `NONE`。

#### 附带修掉一个「测试测的是副本」的问题

上一轮新增的 `DefaultKeyboardResolutionTest` 是把 `KeyboardWindow` 的选键盘逻辑
**复制**了一份来断言 —— 生产代码改了它**不会失败**，等于没测。

现按仓库已有模式（`KeyboardResolve.kt` 把可测逻辑抽成 `internal` 顶层函数、
`KeyboardResolveTest` 直接调用生产代码）改造成：

- `KeyboardResolve.kt` 新增纯函数 `layoutNameForAlphabet()`（方案 → 布局名，
  **并修掉空 `alphabet` 因 `all {}` 空真值而误判成 `qwerty` 的老问题**）
  与 `pickFallbackKeyboard()`（挑一个真有按键的键盘，保证画得出来）。
- `KeyboardWindow.smartMatchKeyboard()` 改为**调用这两个函数**，不再内联那段逻辑。
- `DefaultKeyboardResolutionTest` 移到 `com.osfans.trime.ime.keyboard` 包，
  **直接断言生产函数**，并保留「fork 5 个键盘齐全」「兜底落到 my_pinyin」等真实约束。

### 3. CI 补上 `spotlessCheck` + `testDebugUnitTest`

`.github/workflows/build-fork.yml` 在 `make patch-apply` 与 `assembleDebug` 之间插入两步：

```yaml
- name: Check code style
  run: ./gradlew spotlessCheck --stacktrace

- name: Run unit tests
  run: ./gradlew :app:testDebugUnitTest --stacktrace
```

放在编译 APK **之前**：失败得快，也省得白编译一次 native 层。
补上后工作流共 16 个步骤。

### 4. 顺带订正本文档一处**写反的结论**

「关键机制」一节原文说配色键**必须**用 librime 路径形式 `preset_color_schemes/<id>`、
**不能**用嵌套 YAML —— 这与 `README-FORK.md`、以及 `BackgroundPatch.kt` 的实际
实现**完全相反**。经核对 `ThemeDslExpander.checkPlainKey()`（`:241` 对含 `/` 的键
抛 `UnsupportedDsl`）与 `BackgroundPatch.buildPatchBody()`（生成 `preset_color_schemes:`
缩进块），确认**正确做法是嵌套 YAML**，原文描述的是修复前的旧状态。已订正。

### 验收命令与结果

```sh
# 配置层（10 项，本轮全部通过）
sh tools/all_checks.sh

# 代码层
./gradlew spotlessCheck          # BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest # 290 tests, 0 failed, 0 skipped
./gradlew :app:assembleDebug     # 4 个 ABI 的 APK 全部产出
```

APK 产物：

| APK | 体积 |
|---|---|
| `com.osfans.trime-v0.1.8-0-g7fd1feb5-arm64-v8a-debug.apk` | 20.26 MB |
| `com.osfans.trime-v0.1.8-0-g7fd1feb5-armeabi-v7a-debug.apk` | 19.22 MB |
| `com.osfans.trime-v0.1.8-0-g7fd1feb5-x86-debug.apk` | 20.68 MB |
| `com.osfans.trime-v0.1.8-0-g7fd1feb5-x86_64-debug.apk` | 20.32 MB |

（本机全量构建用的是默认的 4 个 ABI；CI 里 `BUILD_ABI=arm64-v8a,armeabi-v7a` 只出前两个。）

### 仍未做的事（明确留白，不是遗漏）

- `KeyboardBackground.overlayColorFor` / `textColorFor` 的**自动对比度蒙层**仍未接入键盘渲染。
- `KeyboardWindow.smartMatchKeyboard()` 里的 `qwerty` / `qwerty_` / `qwerty0` 分支，
  在 fork 主题里**永远不可能命中**（主题没有这些名字），实际每次都走末尾兜底。
  兜底本身是正确且必要的（已由 `DefaultKeyboardResolutionTest` 的 5 个用例守住），
  但那三个分支属于死逻辑 —— 想清理可以删掉「布局名」这一层，直接兜底。