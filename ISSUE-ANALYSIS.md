# Trime（白州梓输入法）三症状根因分析

分析对象：`https://github.com/BYDXDM/trime`（develop）
分析基线：本机 `D:\trime-build`，HEAD = `f49c008c`（ahead origin/develop 7），另含 20+ 项未提交改动。
用户报告：① 无法输入中文 ② 右下角中英切换按钮无用 ③ 按 Shift 无法输入大写字母。

---

## 0. 结论摘要

| # | 症状 | 根因 | 关键位置 | 现状 |
|---|---|---|---|---|
| 1 | 无法输入中文 | **主因**：默认方案 `mydomain` 缺 `translator: dictionary/prism` 部署基座 → librime「部署成功但零候选」<br>**次因**：主题把字母键主显示（`label`）写成角标数字/符号 → 26 键渲染成数字符号盘 | `app/data/rime/myvocab/mydomain.schema.yaml`<br>`app/src/main/assets/shared/trime.yaml` | 均**已在工作区修好，未提交** |
| 2 | 中英切换按钮无用 | 主题声明了 `ascii_keyboard: my_english`，但**没有任何代码在 `ascii_mode` 变化时切换键盘** | `KeyboardWindow.kt:402-419` | **已修复（本次，见 §7）**（上游亦无此逻辑） |
| 3 | Shift 无法输入大写 | `preset_keys` 缺 `Shift_L` → `shift_lock` 为空 → `isShiftLock=false` → `clickModifierKey(on=false)` 不置 `mShiftKey.isOn`，随后的 `refreshModifier()` 把 SHIFT 掩码复位 | `trime.yaml` `preset_keys`<br>`Keyboard.kt:388-413` | 已由 `c3dbd6c7` 修复（已提交） |

**贯穿性根因**：Trime 对三类配置错误——「引用了不存在的 preset 名」「缺字段」「缺部署基座」——**全部静默失败**：不崩溃、不打日志、无 Toast。三个症状因此都表现为「按了没反应」，极易被误判为引擎/打包问题。

**前置动作**：用户安装的包是 `v0.1.8-0-g7fd1feb5-debug`（Build Git Hash `7fd1feb5`，2026-10-01T01:16:15Z），而症状 1、3 的修复位于其后的 7 个提交与当前工作区。**先重装到含修复的构建，再逐条验收**，否则会在已修好的问题上重复排查。

---

## 1. 基线证据

### 1.1 已安装构建落后于修复
问题报告头部（`D:\com.osfans.trime.debug-2026-10-01T01_21_37Z.txt`）：

```
Version Name: v0.1.8-0-g7fd1feb5-debug
Build Git Hash: 7fd1feb5490ca2dcb395df2e16252305e7bcaac9
```

`7fd1feb5` 之后的提交（含直接对症的修复）：

| 提交 | 说明 |
|---|---|
| `0adc8efd` | fix(tools): 校验脚本在中文 Windows 下不再因 GBK 崩溃 |
| `c3dbd6c7` | **fix(theme): 补回 15 个被引用的预设，修掉一批哑键**（含 `Shift_L`） |
| `676fa3c9` | refactor(keyboard): 抽出选键盘纯函数 |
| `c10816d7` | test: 把 10 个失败用例改成 fork 基准，CI 补测试 |
| `b15c86c1` | feat(theme): GIF 动图背景 |
| `15498aad` / `f49c008c` | chore |

### 1.2 引擎层是健康的（排除法）
运行日志 1386 行，其中 **1335 行是 `update db entry: <词> => <权重>`**，其余 51 行只有设备信息、Activity 生命周期、`LogActivity` 启动。**检索 `error|fail|invalid|missing|not found|exception|warn|cannot|unable|deploy|schema` 命中 0 条**。

结论：Rime 正常启动、正常部署，问题**不在** librime 启动/部署/打包层，而在下游的**方案内容**与**键盘映射**。

### 1.3 截图直接复现了症状 1
`_shot1/2/3.png`（修复主题后所拍）显示：字母已恢复（`Q W E R T Y U I O P` …），但键入 `qidong` 后**候选栏全空**（只有 `···` / `▼` 两个装饰），随后 `qidong` 被当作**裸拉丁文**上屏到地址栏。`_shot7.png` 则已是 Android 系统输入法（`?123` / 地球键 / 蓝色发送箭头）——用户放弃了 Trime。

即：**症状 1 在"字母显示"修复之后依然存在**，说明 `label` 问题只是次因，真正的阻断点在方案部署。

---

## 2. 症状一：无法输入中文

### 2.1 主因：默认方案「部署成功但零候选」

`app/data/rime/myvocab/default.custom.yaml`（`HEAD` 与 `7fd1feb5` 内容一致）把默认方案指向自定义方案：

```yaml
patch:
  schema_list:
    - schema: mydomain          # 26 键拼音，内置科技/游戏词库
    - schema: luna_pinyin_simp  # 明月拼音·简化字（备用）
```

而 `HEAD` 版 `app/data/rime/myvocab/mydomain.schema.yaml` **没有**顶层 `translator:` 节点：

```
$ git show HEAD:app/data/rime/myvocab/mydomain.schema.yaml | grep -n '^translator:'
→ 不含（部署基座缺失）
```

该方案的翻译器**全部是命名空间形式**（`script_translator@mymain`、`script_translator@mydomain_abbrev`、`table_translator@custom_phrase`）。librime 的 `SchemaUpdate`（`deployment_tasks.cc`）只对**非命名空间**的 `translator/dictionary` 编译词典与棱镜表；缺这个节点 → 部署不报错，但**不产出任何 `.table.bin` / `.prism.bin`** → 该方案**零候选**。

工作区已补上（`app/data/rime/myvocab/mydomain.schema.yaml:77-79`）：

```yaml
translator:
  dictionary: mydomain
  prism: mydomain
```

代码里的注释已把这个坑写得很清楚：

> ★ 部署基座：…… 缺这个节点会导致部署"成功"却没有任何 table/prism 产物、方案零候选。

**因果链**：`schema_list` 首位 = `mydomain` → 用户默认进入 `mydomain` → 该方案无 table/prism → 键入 `qidong` 得不到任何候选 → 编辑器把裸编码交给宿主 → 表现为"打不出中文"。

> 旁证：日志里 1335 行 `update db entry` 的条目是繁体词（`畫餅`、`異世界`…），正是 `schema_list` 第二项 `luna_pinyin`（繁体明月）的词典在部署；`mydomain` 则**一行产物都没有**，与"零候选"完全吻合。

### 2.2 次因：字母键主显示被角标顶掉

`7fd1feb5` 的 `trime.yaml` 把 26 键的字母键写成：

```yaml
- { click: 'q', label: '1', swipe_down: '1', swipe_up: '!', hint: '1' }
- { click: 'a', label: '~', swipe_down: '~', swipe_up: '`', hint: '~' }
```

`Key.getLabel()`（`Key.kt:273`）在 `label` 非空且非 ascii 态时**直接返回 `label`**，于是 26 键键盘渲染成**数字/符号盘**，一个字母都看不见。第 3 行还混入了一个**重复的 `l` 键**（`keys.size` 38，应为 37），把行宽权重挤到 100 以外。

工作区已改为把角标放到 `label_symbol`（受支持字段：`TextKeyboard.kt:66`、`Key.kt:57`、`Key.kt:287-288`），并删掉重复键：

```yaml
- { click: 'q', label_symbol: '1', swipe_down: '1', swipe_up: '!' }
```

### 2.3 为什么症状 1 修复后仍可能不生效

`DataManager.sync()` 只做**增量**复制（资源 checksum 比对）。方案列表变化后，普通启动（`fullCheck=false`）**不会重编译既有缓存**；升级安装时外置 `rime/` 目录往往整体残留，旧 `build/` 里没有新方案。工作区已在默认补丁内容变化时 `FileUtils.delete(stagingDir)` 强制下次启动全量重部署（`DataManager.kt`），这是让设备端真正生效的必要一步。

---

## 3. 症状二：右下角中英切换按钮无用

### 3.1 现状

主题里该键**定义是完整的**（`7fd1feb5` 已如此）：

```yaml
preset_keys:
  Mode_switch: { toggle: ascii_mode, send: SWITCH_CHARSET, states: [ 中, EN ] }
...
# 底栏
- { click: Mode_switch, long_click: Menu, width: 15 }
```

且 `my_pinyin` 声明了英文键盘：

```yaml
ascii_keyboard: my_english   # ★ 切到英文模式自动换英文键盘
```

按键链路本身是通的：
`Mode_switch` → `KeyCode.parse("SWITCH_CHARSET")` → `KEYCODE_SWITCH_CHARSET`（经 Android 键名兜底）→ `handleSwitchCharset(action)`（`CommonKeyboardActionListener.kt:141`、`:155-170`）→ `api.setRuntimeOption("ascii_mode", !enabled)`。

**Rime 侧的 `ascii_mode` 确实被切了**，`handleSwitchCharset` 还会顺带把已组词的内容上屏。

### 3.2 断点：没有任何代码在 `ascii_mode` 变化时切换键盘

`KeyboardWindow.onRimeOptionUpdated`（`KeyboardWindow.kt:402-419`）只处理两种前缀：

```kotlin
override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
    val option = value.option
    when {
        option.startsWith("_keyboard_") -> { ... switchKeyboard(target) }
        option.startsWith("_key_")      -> { ... }
    }
    currentKeyboardView?.invalidateAllKeys()
}
```

`ascii_mode` 落到 `when` 之外 → 只重绘，**不换键盘**。

全仓 `switchKeyboard` 调用点只有 4 处，没有任何一处由 `ascii_mode` 触发：

| 调用点 | 触发条件 |
|---|---|
| `CommonKeyboardActionListener.kt:142` | `KEYCODE_EISU` + `select: <键盘名>` |
| `KeyboardWindow.kt:347` | `onStartInput`（密码/邮箱/数字输入框） |
| `KeyboardWindow.kt:399` | `onRimeSchemaUpdated` → `.default` |
| `KeyboardWindow.kt:408` | `_keyboard_*` 选项 |

`.ascii` 这个目标只有 `evalKeyboard()`（`KeyboardWindow.kt:265-271`）认识，而它唯一的传入方是 `onStartInput`。**因此 `my_pinyin.ascii_keyboard: my_english` 永远不会被求值**。

结果：用户按中英 → Rime 内部切到 ASCII（打字行为变了）→ 但键盘布局、按键外观都不变 → 视觉上只有那个键的小字 `中`→`EN` 在动 → 主观感受就是"这个按钮没用"。

> 补充：上游 Trime 同样没有「`ascii_mode` → 换键盘」的逻辑（已核对 `upstream/develop` 的 `KeyboardWindow`）。所以这是 fork 自己**写了但没接线**的承诺（主题注释 `★ 切到英文模式自动换英文键盘`），不是上游回归。`CODE-REVIEW.md` 第七节的「写了但功能完全不生效」清单**未覆盖这一项**。

---

## 4. 症状三：按 Shift 无法输入大写字母

### 4.1 根因：`preset_keys` 缺 `Shift_L`

`7fd1feb5` 的主题第 3 行：

```yaml
- { click: Shift_L, label: '⬆', send_bindings: false, width: 15 }
```

而 `preset_keys` 里**没有** `Shift_L` 定义（`grep -n '^  Shift_L:'` → 无）。上游有（`upstream/develop` `trime.yaml:878`）：

```yaml
Shift_L: {label: Shift, send: Shift_L, shift_lock: ascii_long}
```

### 4.2 精确失效机制

1. `click: Shift_L` 走 `KeyAction.init` 的「preset 未命中」分支 → `KeyCode.parse("Shift_L")` → `(KEYCODE_SHIFT_LEFT, 0)`（生成的 `RimeKeyMapping` 有 `"Shift_L" -> KEYCODE_SHIFT_LEFT`）→ `isModifierKey == true`。**键本身没坏，仍是修饰键。**
2. 但 `shift_lock` 取不到 → `KeyAction.isShiftLock`（`KeyAction.kt:54-63`）落 `else -> false`。上游的 `ascii_long` 分支是 `!rime.statusCached.isAsciiMode`，即**中文态点击即锁定**。
3. `KeyView.processKeyAction`（`KeyView.kt:198-205`）随即调：

   ```kotlin
   keyboard.clickModifierKey(
       action.isShiftLock xor (behavior == KeyBehavior.LONG_CLICK),  // = false xor false = false
       action.modifierKeyOnMask,
   )
   ```

4. `Keyboard.clickModifierKey`（`Keyboard.kt:388-404`）在 `on == false` 时走：

   ```kotlin
   val keyDown = !modifier.hasFlag(keycode)   // shift 当前为 off → true
   val keepOn  = modifierKey?.setOn(false)    // ← mShiftKey.isOn 被置为 false
   return if (on) ... else setModifier(keycode, keyDown)  // ← 掩码靠 keyDown 置位
   ```

   即**掩码置位、但 Shift 键自身状态为"未按下"**。上游（`on == true`）走 `setModifier(SHIFT, keepOn = true)`，两者同时置位。
5. 任何后续非修饰键的按键处理都会调 `Keyboard.refreshModifier()`（`Keyboard.kt:406-414`）：

   ```kotlin
   if (mShiftKey != null && !mShiftKey!!.isOn) result = result || setModifier(META_SHIFT_ON, false)
   ```

   无 preset 时 `!mShiftKey.isOn == true` → **`META_SHIFT_ON` 被复位**；有 preset 时为 `false` → 掩码得以保持。

**净效果**：Shift 无法进入/保持大写态（不锁定，且掩码在紧随其后的按键处理中被复位），表现为"按 Shift 打不出大写"。

> 注：`CODE-REVIEW.md` A4 把这一条记为"按键靠名字回退能用，但丢了自动首句大写"。从上面的调用链看，后果比"丢自动首句大写"更重——**`on=false` 分支不置 `mShiftKey.isOn`，会被 `refreshModifier()` 复位**。建议在真机上补一条断言把这条语义钉死。

### 4.3 已修复

`c3dbd6c7` 已把 `Shift_L` 补回 `preset_keys`（工作区 `trime.yaml:262`），并由单元测试守卫（`KeyActionTest.kt:48-50`）：

```kotlin
val action = plain("Shift_L")
action.shiftLock shouldBe "ascii_long"
```

---

## 5. 修复方案

### 5.1 立即（把已修好的东西送到设备上）

1. 以当前工作区构建并安装（`.\gradlew assembleDebug` → `adb install -r`），确认包内 `Version Name` 的 git hash ≠ `7fd1feb5`。
2. **首次安装后必须触发一次全量重部署**：工作区的 `DataManager` 已在默认补丁变化时 `delete(stagingDir)`；若手工验证，可直接删除设备上 `<userData>/rime/build/` 后重启输入法。
3. 真机验收（JVM 单测无法加载 librime，这几条只能真机做）：
   - 键入 `nihao` → 出中文候选 → 上屏；
   - 检查 `<userData>/rime/build/` 下存在 `mydomain.*.prism.bin` / `.table.bin`（这是「部署基座」生效的硬证据）；
   - 中英切换、Shift 大写。

### 5.2 根治：把"静默失败"改成"响的失败"

三类错误目前都不留痕，建议各加一道断言（配置层，JVM 可跑，无需真机）：

1. **preset 悬空检查**：对 `preset_keys` 定义集与全部键盘的 `click/long_click/composing/swipe_*` 取值集求差集，非空即失败。（`CODE-REVIEW.md` A4 已用脚本做过一次，把它固化成测试。）
2. **修饰键语义检查**：断言 `preset_keys["Shift_L"].shiftLock == "ascii_long"`（已有）+ 断言 `KeyAction(Plain("Shift_L")).isModifierKey == true`。
3. **部署基座检查**：断言 `schema_list` 里每个方案的顶层 `translator.dictionary` 与 `prism` 非命名空间且存在；或在 CI 里对部署产物做存在性校验。
4. **CI**：`build-fork.yml` 目前只跑 `assembleDebug`，建议补 `spotlessCheck` + `testDebugUnitTest`（`c10816d7` 已补，确认在 `develop` 上生效）。

### 5.3 症状二的实际修法（**已落实，见 §7**）

思路：在 `KeyboardWindow.onRimeOptionUpdated` 增加 `ascii_mode` 分支，并把"该换到哪个
键盘"抽成纯函数 `resolveKeyboardForAsciiMode`，两个方向都由主题的 `ascii_keyboard`
声明推导——**代码里不写死键盘名**：

```kotlin
// 切英文：当前键盘自己声明的 ascii_keyboard
// 切中文：反查「谁把当前键盘声明为自己的 ascii_keyboard」
option == "ascii_mode" -> {
    resolveKeyboardForAsciiMode(value.value, currentKeyboardId, asciiKeyboardOf)
        ?.let { switchKeyboard(it, syncAsciiMode = false) }
}
```

三个必须注意的点（都已处理，见 §7）：

1. **`syncAsciiMode = false`**：`detachCurrentView()` 会把"离开那一刻的 ascii_mode"
   记进旧键盘的 `lastAsciiMode`（切去英文时旧键盘记下 `true`），若 attach 再按记忆值
   回写，就会把用户刚切到的状态打回去 —— 中英键又变成"按了没反应"。
2. **面板不换键盘**：符号 / 数字 / emoji 面板没声明 `ascii_keyboard`，两个方向都返回
   `null`，不会被从面板里拽走；想让它们跟随，只需在主题里给它们补 `ascii_keyboard`。
3. `switchKeyboard` 内部已用主线程 executor 且对 `target == currentKeyboardId` 短路，
   重复通知是幂等的，不会与 `onStartInput` 的 `tempAsciiMode` 逻辑打架。

### 5.4 顺带发现的配置问题

1. ~~**`"punctuator/half_shape": false` 会把半角标点表整体打掉。**~~
   **已修（见 §7）**：`default.yaml` 里 `punctuator/half_shape` 是 **map**
   （`__include: punctuation:/half_shape`），补丁赋成布尔 `false` 等于把整张半角标点映射
   替换掉。已删除该补丁并在原处留下警示注释；随包 `punctuation.yaml` 的 `half_shape`
   本来就映射到中文标点（`,` → `，`、`.` → `。`），删掉即恢复正确行为。
2. ~~**`default.custom.yaml` 里的 `translator/*` 补丁很可能是死配置。**~~
   **已查清并已删（见 §7）**。结论是**确定的**，证据在 librime 源码里：

   - `Schema(schema_id)` 的配置只从 `<id>.schema` 建（`schema.cc:16-19`）；
     `Config::Create("default")` 仅用于占位的 `.default` 方案。
   - 对 `*.schema` 资源，`DefaultConfigPlugin::ReviewLinkOutput`
     （`config/default_config_plugin.cc`）只从 `default` 引入 **`menu` / `navigator` /
     `selector`** 三节 —— **不含 `translator`**。
   - 三个方案里 `import_preset` 只用到 `symbols`（标点）、`default`（key_binder /
     recognizer），没有任何地方导入 `translator`。

   所以那三条（`enable_user_dict` / `user_dict` / `db_class`）写进 `default` 配置里
   是一个**没人读的键**，静默无效 —— 而注释却写着"全局兜底"，容易让人误以为有冗余保护。
   更糟的是：**万一它哪天真的生效**，`luna_pinyin` 也会把用户词写进 `mydomain.userdb`。
   真正的开关在 `mydomain.schema.yaml` 的 `mymain` / `mydomain_abbrev` 里（已由
   `PredictiveTypingTest` 守卫），所以删除是行为等价的。
3. **提交前务必过滤 `app/src/main/assets/shared/*.yaml` 的 `M`。**
   实测这些文件与 `app/data/rime/prelude/`、`app/data/rime/myvocab/` 下的同名文件是**硬链接**（`ls -i` inode 相同，`nlink=2`），而 `app/data/rime/prelude` 是**子模块**。`git status` 里那 18 个 `M` 是「符号链接被物化成实体文件」的产物，并非内容改动。若误提交，会把子模块内容固化进主仓库、破坏上游同步。真正的内容改动在 `app/data/rime/myvocab/` 与 `trime.yaml`。

---

## 6. 验收清单

已在 JVM 侧完成（见 §7.2）：

- [x] `spotlessCheck` 通过
- [x] `:app:testDebugUnitTest` 全量通过
- [x] 新增守卫可反向触发（去掉部署基座即失败，见 §7.3）

仍需真机（JVM 单测加载不了 librime）：

- [ ] 设备上的构建 hash ≠ `7fd1feb5`
- [ ] `<userData>/rime/build/` 出现 `mydomain.*.prism.bin`（部署基座生效的硬证据）
- [ ] 键入 `nihao` / `qidong` → 出中文候选并可上屏
- [ ] 26 键键盘可见全部字母，角标数字在上方小字位置
- [ ] 中英切换：键盘在 `my_pinyin` ↔ `my_english` 之间真实切换
- [ ] Shift：中文态点击锁定，连续输入为大写
- [ ] `git status` 中 `shared/*.yaml` 的 `M` 已排除在提交之外（见 §5.4.3）

---

## 7. 本次落实（2026-10-04）

### 7.1 改动清单

| 文件 | 改动 |
|---|---|
| `app/src/main/java/.../ime/keyboard/KeyboardResolve.kt` | 新增纯函数 `resolveKeyboardForAsciiMode`：按主题的 `ascii_keyboard` 声明**双向**推导目标键盘，不硬编码键盘名 |
| `app/src/main/java/.../ime/keyboard/KeyboardWindow.kt` | 新增 `asciiKeyboardOf` 字段；`attachKeyboard` / `switchKeyboard` 增加 `syncAsciiMode` 参数（默认 `true`，既有调用点不受影响）；`onRimeOptionUpdated` 增加 `ascii_mode` 分支 |
| `app/data/rime/myvocab/default.custom.yaml` | 删除无效的 `"punctuator/half_shape": false`；删除静默无效的 `translator/*` 三条；原处留下成因注释，防止再次写回 |
| `app/src/test/.../ime/keyboard/KeyboardResolveTest.kt` | 新增 7 条用例：`resolveKeyboardForAsciiMode` 真值表（含空 `currentKeyboardId`、自环、未知键盘、面板不换键盘） |
| `app/src/test/.../data/theme/ProductionKeyboardTypingTest.kt` | 新增 2 条用例：preset 悬空检查；`schema_list` 部署基座检查（含 `__include` 解析断言，避免被静默跳过） |

> `app/src/main/assets/shared/default.custom.yaml` 与 `app/data/rime/myvocab/default.custom.yaml`
> 是**同一 inode 的硬链接**（已用 `ls -i` 校验），改一处两处同时生效；
> 提交时按 §5.4.3 处理，不要把这个硬链接物化结果当成内容改动提交。

### 7.2 验证结果

命令：`./gradlew.bat spotlessCheck :app:testDebugUnitTest --console=plain`

```
BUILD SUCCESSFUL in 1m 24s
```

`app/build/test-results/testDebugUnitTest/` 汇总：

| 指标 | 值 |
|---|---|
| 测试类 | 34 |
| 用例总数 | **348** |
| 跳过 | 0 |
| 失败 | 0 |
| 错误 | 0 |

其中本次新增/扩充的两个类：

| 测试类 | 用例数 | 结果 |
|---|---|---|
| `KeyboardResolveTest` | 25（原 18 + 新增 7） | 全通过 |
| `ProductionKeyboardTypingTest` | 11（原 9 + 新增 2） | 全通过 |

### 7.3 守卫有效性（反向验证，确保不是空转）

- **部署基座守卫**：把 `mydomain.schema.yaml` 的 `translator:` 块去掉后，同一判定逻辑
  输出 `BROKEN` —— 即守卫会真的失败，不是恒真。
- **preset 悬空守卫**：对 `trime.yaml` 用同一规则离线扫描，得 34 个 preset /
  23 个标识符引用 / **0 悬空**，与测试断言一致。
- **`__include` 解析**：`luna_pinyin_simp` 自身只有 `translator: prism`，基座来自
  `__include: luna_pinyin.schema:/`。测试里显式断言它能解析出基座，避免"include 没被
  跟随 → 该方案被静默跳过 → 守卫形同虚设"。

### 7.4 本次未做（有意留白）

- **`Mode_switch` 在符号 / 数字 / emoji 面板上仍是"按了没反应"**：这三个面板没有声明
  `ascii_keyboard`，按设计就不换键盘。要让它们跟随，在主题里给它们补一行
  `ascii_keyboard: my_english` 即可，**无需改代码**。
- **未提交**：所有改动留在工作区，未 `git commit`，便于你先审阅。
- **未处理** `shared/*.yaml` 那批硬链接 `M`（属提交时的事，见 §7.6）。

### 7.5 四路独立审查后的修订（2026-10-04）

把改动拆成四块派独立子代理做对抗式审查（KeyboardWindow 接线 / 纯函数与单测 / Rime 配置 /
测试守卫与仓库卫生），**采纳**的修订：

| 来源 | 问题 | 处置 |
|---|---|---|
| 审查 A/B | **竞态**：`currentKeyboardId` 在通知回调里同步读，但 `switchKeyboard` 的 detach/attach 是投递给主队列后才更新它。快速连按中英键时第二次会读到过期 id → 错切/漏切（症状②复现） | 新增 `switchKeyboardForAsciiMode`，**把解析放进同一个主线程任务里** |
| 审查 A | **`lastAsciiMode` 污染**：`detachCurrentView()` 把"离开那刻的 ascii_mode"记进旧键盘记忆；`my_pinyin` 未声明 `reset_ascii_mode` ⇒ `Keyboard.resetAsciiMode=false` ⇒ 走记忆值分支，之后 `refreshKeyboards()` / `onStartInput()` 会按被污染的记忆把模式写回去 | `detachCurrentView(recordAsciiMode)`：ascii 驱动的卸载不记录；并把**目标键盘**的记忆值对齐到新模式 |
| 审查 A | `targetMode` 在 `syncAsciiMode=false` 时白算 | 移进 `if (syncAsciiMode)` |
| 审查 B | `isNotEmpty()` 挡不住空白串 `" "` | 改 `isNotBlank()` |
| 审查 B | 真值表缺口（空 map / 空白值 / 悬空目标 / 多键盘声明同一英文盘 / 反向自环）+ 一条用例命名误导 | 补 5 条 + 改名，该组共 11 条 |
| 审查 C | 注释把 `punctuation.yaml` 写成中文态标点来源 | 订正为 `symbols.yaml`（mydomain 走 `import_preset: symbols`），并补记"该布尔值对当前 `schema_list` 其实是**空操作**，只有 `stroke` 读 default 的 punctuator" |
| 审查 C | 复核 `translator/*` 是死配置 | **结论成立**（独立复现：`default_config_plugin.cc` 只引入 menu/navigator/selector；全库无 translator gear 回退读 default） |
| 审查 D | `PROGRESS.md` 验证日志仍"[待补]" | 已补实测结果 |

**未采纳 / 已驳回**：

- 审查 A 判断"`lastAsciiMode` 污染**当前安全**" —— **该理由不成立**。它依据
  `Keyboard.kt:83` 的 `selfConfig?.resetAsciiMode ?: true`，但 `my_pinyin` 的 `selfConfig`
  非空，实际取 `TextKeyboard.DEFAULTS.resetAsciiMode = false`（`TextKeyboard.kt:37/153`），
  走的正是记忆值分支。已按**真实风险**修复。
- 审查 D 称"`luna_pinyin_simp.schema.yaml` 实测无 `translator: prism`" —— **有误**，
  该文件 27-28 行就是 `translator:` / `prism: luna_pinyin_simp`，无需改动。
- 审查 D：`schemaTextWithIncludes` 只跟随**首个** `__include`、`hasNamespacedTranslator`
  只认块序列写法 —— 当前主题全是块序列且每方案最多一个 include，属**可疑但安全**；
  已由 §7.3 的显式断言兜住"include 被跟随"，未再改。
- 审查 D：`presetLike` 启发式有误报/漏报（`long_click: 'gmail'` 会误报；`my-key` 会漏报）
  —— 当前实测 0 悬空，属**可疑但安全**，未改。

### 7.6 ⚠ 提交前必读：不要 `git add -A`

审查 D 用 `git ls-files -s` 证实：`app/src/main/assets/shared/*.yaml` 在 **git 索引里是
符号链接（mode 120000）**，本地被物化成普通文件（nlink=2），指向 `app/data/rime/prelude/`
（**子模块**）与 `app/data/rime/myvocab/`。`git add -A` 会把 20 个 typechange 固化，
**把子模块内容写进主仓库**、破坏上游同步。

**建议提交清单**

- 源码：`KeyboardResolve.kt`、`KeyboardWindow.kt`
- 配置：`app/data/rime/myvocab/*.yaml`
- 主题：`app/src/main/assets/shared/trime.yaml`（普通文件，不是链接）
- 测试：`app/src/test/**`
- 资源：`app/src/main/assets/shared/backgrounds/`、`luna_pinyin.custom.yaml`、
  `luna_pinyin_simp.custom.yaml`、`app/data/rime/myvocab/predict.txt`
  （测试会读它们，不提交则 CI 抛 `FileNotFoundException`）
- 排除：`app/src/main/assets/shared/*.yaml`、`_*.json` / `_*.png` / `_*.txt`、
  `.mimosa/`、`build/`

**最关键的一条**：`ProductionKeyboardTypingTest.kt` 目前**未被 git 跟踪**
（`git status` 显示 `??`）。CI 从 git 检出后这个类**根本不存在** —— 本次新增的两条守卫，
以及早前那 9 条用例，**都不会执行**；本地全绿是假象。提交前必须先 `git add` 它。

> **§7.6 的前半段已被上游解决（2026-10-05）**：上游提交 `162dc6bf`
> （`assets/shared 19 个词库/配置改为普通文件`）与 `255836b8` 已把那些符号链接
> **正式转成普通文件**，并新增 `syncRimeSharedData` 构建任务消除 `data/rime` 与
> `assets/shared` 的漂移。所以"typechange 会固化子模块内容"的风险不再存在；
> 但"`ProductionKeyboardTypingTest.kt` 未跟踪"这条**仍然成立**。

---

## 8. 与远端同步（2026-10-05）

用户提示"远端别处有更新"。**我此前整段分析用错了基线**：本地 `origin/develop`
这个远端跟踪引用是陈旧的（`git status` 显示 ahead 7），`git fetch` 后真实关系是
**behind 20、ahead 0**（无分叉，可快进）。

### 8.1 那 20 个提交就是本地那批未提交改动的上游版本

作者在别处（10-01 ~ 10-04）把工作提交并推送了，内容与本工作区里那批未提交改动高度重合：
主题 `labelSymbol` 化、predict 库、钢琴音效（`.ogg` + `pianosound.yaml`）、
`RimeStartupGate`（迁到 `data/sync/`）、`PredictiveTypingTest`、`syncRimeSharedData`、
`assets/shared/*.yaml` 符号链接转普通文件、`tools/check_key_refs.py`、CI 预装 NDK/SDK。

### 8.2 三症状在上游的状态（**结论：② 仍需本次修复**）

| 症状 | 上游是否已修 | 证据 |
|---|---|---|
| ① 无法输入中文 | **已修** | `mydomain.schema.yaml:79` 已有 `translator: dictionary: mydomain / prism: mydomain` |
| ② 中英切换无用 | **未修** | `KeyboardWindow.kt` 仍无 `ascii_mode` 分支（只有 `_keyboard_*` / `_key_*`） |
| ③ Shift 打不出大写 | **已修** | `trime.yaml:268` 有 `Shift_L: { label: Shift, send: Shift_L, shift_lock: ascii_long }` |

另外 `default.custom.yaml` 的 `"punctuator/half_shape": false` 与静默无效的
`translator/*` 三条 **上游仍然在**（§5.4），本次修复依然必要。

### 8.3 同步动作与结果

1. **备份**：`git stash push -u`（`stash@{0}` = `11349bbd`，**保留勿删**）+
   `D:\trime-wip-backup-20261005\`（本地改动补丁 520 行、`librime-predict` 子模块补丁
   579 行、5 份文档副本）。
2. **快进**：`git reset --hard` + `git clean -fd` → `git merge --ff-only origin/develop`
   → HEAD 到 `4550be59`，与 `origin/develop` 完全一致。
3. **恢复**（`git checkout stash@{0} -- …`）：
   - 本次工作：3 个 Kotlin 文件、`ProductionKeyboardTypingTest.kt`、
     `default.custom.yaml` 两处修复、`ISSUE-ANALYSIS.md` / `PROGRESS.md`；
   - **上游没有的本地独有功能（3 个文件）**：成对符号自动补全、双击空格出句号
     （`commitAutoPair` / `isDoubleSpaceTap`）、`commitPairedText`、
     `ensureBundledPianoEffect`。**上游至今没有等价实现**（`autoPair` / `doubleSpace`
     / `成对` / `双击空格` 在 `origin/develop` 命中数均为 0），故予保留，
     但其中"首次自动激活钢琴音效"与上游新加的 `.ogg` + `pianosound.yaml` 方案
     可能重复，**建议作者复核后决定去留**；
   - 44 个上游没有的未跟踪文件（含 `_*.png` 调试产物、`luna_pinyin*.custom.yaml`）。
4. **丢弃为"上游已取代"**：`core/RimeStartupGate.kt` 与其测试（上游已迁到 `data/sync/`）、
   `soundeffect/piano.sound.yaml` + 10 个 `.wav`（上游改用 `.ogg` + `pianosound.yaml`）。
5. **配置改动的落点变了**：上游把 `assets/shared/default.custom.yaml` 从符号链接
   改成**独立普通文件**（不再与 `myvocab/default.custom.yaml` 同 inode），
   所以两处都要改 —— 本次已同步为一致内容。
6. **子模块**：`librime-predict` 上游已改用独立仓库 `BYDXDM/trime-predict`；
   已 `git submodule sync --recursive` 更新 URL。该子模块工作区内原有 6 个文件的
   本地未提交改动（413 行，已备份为
   `D:\trime-wip-backup-20261005\librime-predict-local-changes.patch`），
   阻止了 `git submodule update` 切换提交；丢弃后已成功检出上游提交 `0be0ec5d`。
   `librime-lua-deps` 仍是"脏"的（`m`）—— 那是**有意为之**：`patches/lua.patch`
   已打进该子模块，不要清理。

### 8.5 同步后的验证

命令：`./gradlew.bat spotlessCheck :app:testDebugUnitTest --console=plain` → **BUILD SUCCESSFUL**

| 指标 | 值 |
|---|---|
| 测试类 | 33 |
| 用例总数 | **347** |
| 跳过 | 0 |
| 失败 | 0 |
| 错误 | 0 |

`git status -sb` → `## develop...origin/develop`（无 ahead/behind）；
`git submodule status` 无 `+` / `-` 偏差。

修复过程中撞到并已处理的一处测试失效：`TypingEnhancementsTest` 原先钉的是**本地**钢琴音源
布局（`piano.sound.yaml` + 10 个 `.wav`），而上游改用 `pianosound.yaml` + 10 个 `.ogg`
（`00_C5.ogg` … `09_A6.ogg`）。已把断言对齐到**随包实际资源**，而不是把上游的资产改回来。

### 8.6 本地出包（2026-10-05）

```
./gradlew.bat :app:assembleDebug --console=plain
BUILD SUCCESSFUL in 1m 59s     # native 缓存命中（librime 子模块未变）
```

**不要用 `make debug`** —— 它会先跑 `patch-apply`，而 `patches/lua.patch` 早已打进
`librime-lua-deps`（该子模块的 `M lua5.4/liolib.c` 就是这个补丁），重复打会失败。

产物（`NativeBaseConventionPlugin.kt:40` 配的 ABI 拆分，无通用包）：

| APK | 大小 |
|---|---|
| `app/build/outputs/apk/debug/com.osfans.trime-v0.1.19-0-g4550be59-arm64-v8a-debug.apk` | 25.1 MB |
| `…-armeabi-v7a-debug.apk` | 24.0 MB |
| `…-x86-debug.apk` | 25.6 MB |
| `…-x86_64-debug.apk` | 25.2 MB |

版本名 `v0.1.19-0-g4550be59`：hash 即 `origin/develop` 顶端，说明这包含 20 个上游提交 +
本次恢复的全部工作。真机（HUAWEI ANA-TN00 / API 31）装 **arm64-v8a** 那个。

**已对包内资源逐项校验**：

| 检查 | 结果 |
|---|---|
| `lib/arm64-v8a/librime_jni.so` 存在 | ✅ |
| `assets/shared/mydomain.schema.yaml` 含顶层 `translator:` 部署基座 | ✅ |
| `assets/shared/trime.yaml` 含 `Shift_L` 预设（`shift_lock: ascii_long`） | ✅ |
| `assets/shared/trime.yaml` 含 `ascii_keyboard: my_english` 配对 | ✅ |
| `assets/shared/default.custom.yaml` 已无生效的 `"punctuator/half_shape": false` | ✅（只剩注释） |
| 同文件已无 `"translator/*"` 死配置 | ✅ |
| `assets/shared/soundeffect/pianosound.yaml`、`backgrounds/mybg/preview.gif` 随包 | ✅ |

装好后按 §6 的清单验收（`nihao`/`qidong` 出候选、中英切换真换键盘、Shift 锁定大写）。

### 8.7 签名（release 包）

**先厘清一件事**：debug 包**本来就是签过名的**（`CN=Android Debug`，
SHA-256 `b29f7e38…`，与本机 `~/.android/debug.keystore` 一致），所以它能装。
但 debug 变体的包名带 `applicationIdSuffix = ".debug"` → `com.osfans.trime.debug`，
和你设备上现在那个是同一个包，所以能原地升级；它**不是**发布用的包。

本机**没有任何 trime 的发布密钥库**（只有 `english-vocab-app` / `ipchecker-android` /
`qingyue-reader` / `Android/transfromed-local-release.jks` 这几个**别的项目**的），
而且 **CI 只跑 `assembleDebug`（`.github/workflows/build-fork.yml:120`），从来不签名**。
所以本次生成了一个专用密钥库：

| 项 | 值 |
|---|---|
| 密钥库 | `D:\trime-build\release.jks`（`*.jks` 已被 `.gitignore:34` 忽略） |
| 别名 | `trime` |
| 口令 | 见 `keystore.properties`（已被 `.gitignore:471` 忽略） |
| 证书 SHA-256 | `B7:CC:9B:B1:74:BB:17:EA:DB:47:6F:17:C4:FB:81:F4:FD:19:EA:05:5C:AC:6D:3B:9A:BF:02:FA:AD:7C:A3:E2` |

**⚠ 这个密钥库从此就是该应用的签名身份**：以后要覆盖安装，必须用同一把钥匙。
请把 `release.jks` 与口令一起备份到安全处（丢了就只能卸载重装、数据全失）。
若你另有发布密钥，把路径与口令给我，重新出包只要约 1.5 分钟。

**踩到的一个坑**：`keystore.properties` 里 `storeFile=release.jks` 这种**相对路径**
会被解析成 `<module>/release.jks`（即 `app/release.jks`），于是
`packageRelease` 直接失败：

```
property 'signingConfigData…storeFile' specifies file 'D:\trime-build\app\release.jks' which doesn't exist
```

所以 `storeFile` 必须写**绝对路径**（`D:/trime-build/release.jks`）。

**产物**（`app/build/outputs/apk/release/`，R8 + 资源压缩，`-dontobfuscate` 故无改名风险）：

| APK | 大小 |
|---|---|
| `com.osfans.trime-v0.1.19-0-g4550be59-arm64-v8a-release.apk` | **14.1 MB** |
| `…-armeabi-v7a-release.apk` | 13.8 MB |
| `…-x86-release.apk` | 14.2 MB |
| `…-x86_64-release.apk` | 14.1 MB |

已核验：签名者 `CN=BYDXDM trime…`（非 Android Debug）、`package: name='com.osfans.trime'`
（无 `.debug` 后缀）、`application-label:'白洲梓输入法'`、`native-code: 'arm64-v8a'`。

**注意**：release 路径在 CI 里从未被跑过，这是本仓库第一次本地出 release 包
（首次需为 RelWithDebInfo 重编 4 个 ABI 的 native，约 19 分钟；之后增量约 1.5 分钟）。
因此 release 包的**运行时行为尚未在真机/模拟器上验证过**（R8 收缩 + 无混淆，
理论上安全，但值得装一次看有没有崩溃）。

---

## 9. 模拟器冒烟测试（2026-10-05）

环境：AVD `smoke`（Android 15 / API 35 / x86_64），装 **release** 包，用 `pm uninstall`
后重装来模拟全新安装。

### 9.1 ★ 发现的阻断性缺陷：全新安装时 Rime 根本不启动（就是「打不出中文」）

**现象**：输入法已启用并设为默认，`dumpsys input_method` 显示
`mHaveConnection=true mBoundToMethod=true mInputShown=true`，但屏幕下方**没有键盘**；
且两个 data 目录里**都没有 `rime` 用户目录** —— `DataManager.sync()` 压根没跑。

**日志（决定性证据）**：

```
W [main]: Skip starting rime: storage not available!
I [main]: Scheduling Rime startup retry until storage is available
W [main]: Rime startup retry exhausted while sessions remain connected
W [main]: ThemeScope is not ready yet; deferring input view creation
```

**根因**：`data/sync/RimeStartupGate.kt` 里有一条

```kotlin
if (!storageChoiceDone) return true   // ← 直接跳过启动
```

而默认配置下 `isStorageChoiceDone()` **必然为 false**：`dataStorageMode` 默认
`EXTERNAL_SYNC`（`AppPrefs.kt:394`）、`externalRimeTreeUri` 默认 `""`（`AppPrefs.kt:395`），
于是 `EXTERNAL_SYNC -> treeUri.isNotEmpty()` = `false`。

即：**「用户还没做过存储方式选择」被当成了「存储不可用」**。用户只启用输入法、
从未打开过应用（首次安装最典型的路径）时，Rime 永远不启动 → ThemeScope 永不就绪
→ `onCreateInputView` 无限推迟 → **键盘整块空白、打不出中文**。

**这是 fork 自己修过、又被上游改回去的回归。** 本机 stash 里的旧实现
（`core/RimeStartupGate.kt`）逻辑是
`!runtimeReady -> true; !usesExternalSync -> false; hasExternalAccess -> false; else -> storageChoiceDone`，
其注释原文：

> 用户只启用输入法、从未打开过应用时，该选择尚未完成——若照搬「存储不可用即不启动」
> 的旧判定，Rime 永远不会启动，ThemeScope 永不就绪，onCreateInputView 无限推迟，
> **键盘整块不出现（首次安装即启用时必现）**。

**已修**（`data/sync/RimeStartupGate.kt` 恢复该语义），并新增回归测试
`app/src/test/java/com/osfans/trime/data/sync/RimeStartupGateTest.kt`
—— 上游这段逻辑原本**一条测试都没有**。

**修复后复测**（同样 uninstall → install 全新安装）：Rime 正常启动并开始部署

- native 日志出现 `rime.trime` 的部署输出，**不再有 `Skip starting rime`**
- `/data/user_de/0/com.osfans.trime/checksums.json` 生成（`DataManager.sync()` 跑了）
- Rime 用户目录出现：`<外部files>/rime/{build,default.custom.yaml,installation.yaml,opencc,predict.db,backgrounds,soundeffect}`
- **部署产物齐全**（症状①的硬证据）：
  `build/mydomain.prism.bin`（17 KB）、`mydomain.table.bin`（56 KB）、`mydomain.reverse.bin`（10 KB）
- **键盘正常渲染**：字母为主显示、角标数字在上（`label_symbol` 生效）、GIF 底图、
  底栏「符 123 ，空格 。**中** 前往」
- **端到端出中文**：点击键盘输入 `nihao` → 预编辑 `ni hao` → 候选栏出现
  **`你好`（首选高亮）/ `你` / `里`** ✅

截图留证：`D:\trime-emu\fixed1.png`（键盘渲染）、`D:\trime-emu\typed1.png`（候选 `你好`）。

### 9.3 交互式验收（同一台模拟器，修复后的 release 包）

坐标标定：键盘行距 **172 px**（= `keyboard_height` 250 dp ÷ 4），行中心 y =
`1607 / 1779 / 1951 / 2123`，候选栏 y ≈ 1483；底栏按权重 `符10 123 10 ，10 空格30 。10 中英15 前往15`。

| 验收项 | 结果 | 截图 |
|---|---|---|
| 键盘渲染（字母主显示 + 角标 + GIF 底图 + 底栏） | ✅ | `fixed1.png` |
| **中文输入**：点 `n i h a o` → 候选 `你好 你 里` | ✅ | `typed1.png` |
| **中英切换 中→英**：字母变小写、底栏变半角 `,` `.`、键显示 `EN` | ✅ | `switch2.png` / `rev_b.png` |
| **中英切换 英→中**：字母回大写、底栏回 `，` `。`、键显示 `中` | ✅ | `rev_c.png` |
| **Shift 出大写**（英文态）：Shift + `a` → 候选 `A` | ✅ | `shift1.png` |
| 联想（predictor 插件）：候选 `原神 我们 什么 可以 现在 谢谢 今天 请问` | ✅ | `rev_a.png` |
| 切模式时把未上屏的原始编码直接上屏（`handleSwitchCharset` 的既有行为） | ✅ | `switch2.png` |

**说明**：`switch2.png` / `rev_b.png` / `rev_c.png` 是**本次唯一新增代码修复（中英切换接线）
的端到端证据** —— 键盘在 `my_pinyin` ↔ `my_english` 之间真实切换，两个方向都通
（反方向依赖的正是 §7.5 里修的 `lastAsciiMode` 处理）。

### 9.4 单元测试

`./gradlew.bat spotlessCheck :app:testDebugUnitTest` → **BUILD SUCCESSFUL**

| 指标 | 值 |
|---|---|
| 测试类 | 34 |
| 用例总数 | **353** |
| 跳过 | 0 |
| 失败 | 0 |
| 错误 | 0 |

其中新增的 `RimeStartupGateTest` 6 条用例全通过（含「全新安装必须照常启动」这条回归钉）。

### 9.5 交付物

修复后的 release 包（含 `RimeStartupGate` 修复，时间戳 10-05 09:02）：

`app/build/outputs/apk/release/com.osfans.trime-v0.1.19-0-g4550be59-{arm64-v8a,armeabi-v7a,x86,x86_64}-release.apk`
（13.2 ~ 13.5 MB，签名者 `CN=BYDXDM trime`，包名 `com.osfans.trime`）

**注意**：更早（09:0x 之前）给过的那一版 release 包**不含** `RimeStartupGate` 修复，
全新安装会打不出中文，已作废。

---

## 10. 候选词排序：「只出一个词 / 组不成句 / 领域词霸榜」（2026-10-05）

用户反馈：连打多个拼音只返回单个词、组不成句；且「科学上网」类垂直领域词霸榜。
模拟器复现：**打 `shi` 只出 1 个候选 `是`**。

### 10.1 根因（三层，同一个源头）

1. **`mymain.dictionary` 指向本方案自己的小词库（535 条）**，而预置词库 `essay.txt`
   （44 万条）**只带「词 + 权重」、不带拼音** —— 它的拼音要靠词典已收集的词条反查
   （`ScriptEncoder::DfsEncode` → `EntryCollector::TranslateWord`，见
   `src/rime/algo/encoder.cc:305`）。535 条给不出这个映射，于是 essay.txt 的绝大多数
   词条编码失败被丢弃（`entry_collector.cc:144-155`，部署日志里刷屏的
   `Encode failure` 就是它），词表最终只剩几十条高权重领域词。
   **硬证据**：`mymain` 的产物 `mydomain.table.bin` 只有 **56 KB**，而
   `luna_pinyin.table.bin` 有 **13 MB**。
2. **权重错位**：`mydomain.dict.yaml` 用的是绝对权重 **1000~2000**，而 `essay.txt`
   的权重**中位数只有 265**（442,720 条，0 ~ 4,822,928）—— 少数领域词稳压常用词一头。
3. **librime 每个方案只编译一个词典**：`deployment_tasks.cc:350`
   `config->GetString("translator/dictionary", &dict_name)` 取的是单值，
   所以不能简单给一个翻译器挂两个词典。但**已编译的词典可被任意方案复用**
   （`luna_pinyin_simp.schema.yaml` 正是 `__include: luna_pinyin.schema:/` 复用它的词典）。

### 10.2 已实施修复

| 改动 | 文件 | 作用 |
|---|---|---|
| `mymain.dictionary/prism` → `luna_pinyin` | `mydomain.schema.yaml` | 完整拼音底座（≈9 万条「词→拼音」映射），essay.txt 因此能编码入表 |
| 新增 `schema.dependencies: [luna_pinyin]` | 同上 | 保证 luna_pinyin 的 table/prism 一定被部署（同 `luna_pinyin` 用 `dependencies: [stroke]` 取笔画词典的套路） |
| 新增 `script_translator@mydomain_terms`（`dictionary: mydomain`、`initial_quality: 0.5`） | 同上 | 垂直领域词单独承载，乘数调低即整体降权 |
| 领域词权重整体 ×0.1（1000~2000 → **65~200**） | `mydomain.dict.yaml`（535 条） | 压到 essay.txt 中位数 265 以下，保证常用词优先 |

`mydomain_abbrev`（简拼）仍挂在 `mydomain` 上，避免用大词典做补全时候选泛滥。

### 10.3 可调参数速查（回答「改哪个参数」）

| 想要的效果 | 参数 | 位置 | 说明 |
|---|---|---|---|
| **让某类词整体降权** | `initial_quality` | schema 里对应翻译器的块 | 候选权重 ≈ 词典权重 × 该值，**最直接的旋钮**；改一个数即可 |
| 调单个词 | 词典第三列（绝对权重） | `*.dict.yaml` | 想"不霸榜"就压到 `essay.txt` 中位数（265）以下 |
| 按比例打折 | 词典第三列写 `50%` | 同上 | 相对该词在预置词库里的权重打折（librime 原生支持） |
| 完全不让它出现 | 从词典删除 | 同上 | 最彻底 |
| 候选条数 | `menu/page_size` | `default.custom.yaml`（当前 9） | 条数越多，降权词越不容易被翻到 |
| 整句输入 | `enable_sentence` | schema 的翻译器块 | 需要足够大的词典支撑，否则组不出句 |
| 打一半就出候选 | `enable_completion` | 同上 | 开在大词典上会候选泛滥，`mydomain_terms` 因此设为 false |
| 简拼范围 | 简拼翻译器的 `dictionary` | 同上 | 挂小词库=只对领域词简拼；挂大词库=任意词简拼但候选多 |

**减少干扰词误触的推荐组合**：领域词走**独立翻译器 + 低 `initial_quality`**，
同时把词典权重压到中位数以下；不要直接删词（删了就再也打不出来），
也不要把大词典直接给简拼翻译器用。

### 10.4 验收（修复后，模拟器全新安装）

模拟器 AVD `smoke`（Android 15 / API 35 / x86_64），装 release 包，
`pm uninstall` → `install` 做全新安装。

| 验收项 | 修复前 | 修复后 |
|---|---|---|
| 打 `shi` 的候选 | **1 个**（`是`） | **8 个**：`是 时 事 死 使 市 四 式` ✅ |
| 打 `woaini` | 组不出句 | **首选 `我爱你`**，另有 `我爱 我 喔 窝 握 卧 沃` ✅ |
| 打 `daili` | 只有领域词 | `代理` 首选 + `带你 袋里 呆立 戴笠 待你 带离 代你` ✅ |
| **首次部署耗时** | **40 分钟以上仍未结束** | **40 秒** ✅ |

**部署耗时的对比是关键收获**：瓶颈不是词典编译本身，而是
`mydomain.dict.yaml` 那份 `use_preset_vocabulary: true` —— 535 条精选词给不出
「词→拼音」映射，essay.txt 的 44 万条逐条 `Encode failure`，**每失败一条就写一行
logcat**，日志 I/O 成了瓶颈。关掉后（§10.2 末行）耗时从 40 分钟级降到 40 秒级。

产物对照（部署后 `rime/build/`）：

| 文件 | 大小 | 含义 |
|---|---|---|
| `luna_pinyin.table.bin` | 13,018,232 B | 通用拼音词表（`mymain` 的底座） |
| `mydomain.table.bin` | 27,944 B | 只有 535 条精选垂直领域词，不再混入 essay |
| `mydomain.prism.bin` | 17,360 B | 对应棱镜表 |

截图留证：`D:\trime-emu\r_shi.png`（8 个候选）、`r_sentence.png`（`我爱你`）、
`r_daili.png`（普通词与领域词共存）。

---

## 11. 自审：结论的证据分级与不确定性（2026-10-05）

### 11.1 第一手确认（源码 + 端到端实测）

| 结论 | 依据 |
|---|---|
| `RimeStartupGate` 的 `if (!storageChoiceDone) return true` 会让全新安装的 Rime 不启动 | 设备日志原文（`Skip starting rime: storage not available!` → `ThemeScope is not ready yet`）+ 源码；修复后键盘渲染、`nihao`→`你好`、`woaini`→`我爱你` 实测通过 |
| 候选极少/组不成句源于小词库无法给 essay.txt 提供「词→拼音」映射 | `src/rime/algo/encoder.cc:305`（`DfsEncode` → `TranslateWord`）+ `entry_collector.cc:144-155`；产物 56 KB vs 13 MB；修复后 `shi` 8 个候选、`woaini` 出整句 |
| `use_preset_vocabulary: false` 大幅缩短首次部署 | 实测 40 分钟级 → 40 秒；机理是 44 万条 `Encode failure` 各写一行 logcat |
| `ascii_mode` 变化驱动换键盘（本次新增代码） | 模拟器双向实测（中→EN→中，字母大小写、底栏 `，`/`,`、键面 `中`/`EN` 均随动） |
| `punctuator/half_shape` 赋布尔会打掉整张映射表 | `src/rime/gear/punctuator.cc:30` `GetMap("punctuator/" + shape)` —— 非 map 返回 null |
| `schema/dependencies` 会强制编译依赖方案的词典 | `src/rime/lever/deployment_tasks.cc:236-244` `GetList("schema/dependencies")` → `build_schema(id, as_dependency=true)` |
| `default.custom.yaml` 里的 `translator/*` 是死配置 | `src/rime/config/default_config_plugin.cc` 只引入 `menu`/`navigator`/`selector` |
| `storeFile` 写相对路径会被解析成 `app/release.jks` | 真实构建报错原文 |
| 改动未破坏既有断言 | 复跑 `:app:testDebugUnitTest`：**353 用例 / 34 类 / 0 失败** |

**一处数字更正**：`mydomain.dict.yaml` 是 **535 条**（本文档早先写的 79 条是 grep 写错）。
不影响结论——535 相对 9 万一样给不出「词→拼音」映射。

### 11.2 未直接验证 / 存在不确定

1. **「降权」的排序位移本身没有直接测出来。**
   已验证的是「通用词回来了」（`shi` 8 候选、`daili` 出现 7 个普通词），
   但**没有构造出「同一编码下领域词与常用词竞争」的可判别场景**：
   词库里与常用词同编码的条目（代理、科学）本身就是该编码最常用的词，无法区分；
   拉丁编码条目（Clash/Trojan/V2Ray）在通用词典里没有竞争者。
   要直接验证需要：给 `mydomain_terms` 的候选加可见标记（已加
   `comment_format: 领域`，但当前主题的候选栏似乎不显示 comment），
   或做 `initial_quality` 高/低对比实验。
2. **GitHub Actions 工作流从未在 GitHub 上实跑**。只做了 YAML 语法校验；
   `env.VARIANT` 在 step `if` 中取值、`hashFiles` 用于 `if`、
   `publish` job 的 `github.event.inputs.*` 都属「照文档写、未实跑」。
3. **「科学上网」这个词条在 `mydomain.dict.yaml` 里并不存在**（只有 代理 / Clash /
   V2Ray / Trojan / v2rayN 等同类条目）。若用户指的是某个具体词条，
   降权对象可能与预期不一致，需确认具体是哪些词。
4. **权重 ×0.1 是否过头**：65~200 相对 essay.txt 中位数 265 确实压下去了，
   但常用词的实际权重分布未测（essay 权重 0 ~ 4,822,928，长尾很长），
   某些中文编码的领域词可能低到几乎不出现。
5. **`RimeStartupGate` 是设计取舍，不是纯 bug 修复。**
   上游注释担心「未完成向导就启动会解析到错误数据目录」；我实测未复现
   （应用落到 `<external>/rime` 且输入正常），但**无法证明其他场景
   （例如用户之后改用外部同步）不会出问题**。
6. **全部验证都在 x86_64 模拟器 + release 包上**；真机（arm64 / API 31）未验证。
7. 四路子代理审查里我采纳的二手结论，除上面已第一手复核的两条外，其余未逐条复核。

### 11.3 建议进一步核实的关键点

- [ ] 首次 push 后盯一次真实的 Actions run，确认签名与产物下载都正常
- [ ] 用 `initial_quality` 高/低对比实验，直接量出领域词的排序位移
- [ ] 确认要降权/屏蔽的**具体词表**（「科学上网」不在词库中）
- [ ] 真机（arm64）装一次，验证部署耗时与输入手感

### 9.2 模拟器环境注意事项（供后续复用）

- AVD `smoke` / `qy35` 都是 **x86_64 / Android 15 (API 35)** → 必须装 **x86_64** 包。
- 用 `-no-window -gpu swiftshader_indirect` 时 **WebView 应用渲染全黑**
  （`org.chromium.webview_shell` 实测），桌面与原生 View 正常 →
  验证键盘请用原生输入框（桌面搜索栏 `com.android.quicksearchbox`）。
- 本环境里 `adb` server 会被反复重启，表现为随机的 `device offline`。稳妥做法：
  每次调用先 `adb start-server; adb wait-for-device`，并把设备侧动作
  **合并进一次 `adb shell "a; b; c"`**。
- 设备侧 `grep -E` 会**自身 SIGSEGV**（toybox 的 `regexec` bug，与 Trime 无关）
  —— 别在设备上跑复杂正则，过滤放宿主机做。
- `adb pull /sdcard/...` 会被 Git Bash 的路径转换搞坏 → 用
  `adb exec-out screencap -p > file`，或 `export MSYS_NO_PATHCONV=1`。
- 镜像为 `userdebug/test-keys`，**`adb root` 可用** → 可直接读应用私有目录。
- **首次安装的全量部署在模拟器上要几分钟**（明月词典很大）；期间 `build/` 里只有
  编译好的 yaml，`.prism.bin` / `.table.bin` 要等字典编译完才出现。

### 8.4 恢复本地改动后需要留意的契约变化

- `trime.yaml` 的 `dark` 配色被上游**刻意改回纯色** `0x292C2F`（不再用 `preview.gif`），
  浅色 `default` 的 `key_back_color` 也调成 `0x66FFFFFF`。本报告 §7 里恢复的测试
  原先钉死了旧值，已改为断言"引用 `preview.gif` + 键面为半透明蒙层（alpha < 0xFF）"，
  避免把上游有意的改动当成回归。
- 上游主题的 `swipe_up` / `swipe_down` 与本地版本**对调过**
  （见上游提交 `4550be59`），`ThemeGoldenTest` 已随之更新。
