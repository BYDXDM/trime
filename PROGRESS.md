# PROGRESS — Trime 安装后无法输入中文 修复记录

## 目标 / 步骤 / 最大风险（≤10 行）
- 目标：修好已安装 APK 无法输入中文的问题；启用输入法后在普通文本框键入拼音能看到真实 Rime 中文候选并提交，且保留中英切换与现有键盘功能。
- 步骤：①基线核对 ②模拟器复现断点（证据链：资源打包→同步→部署→选中方案→键入→候选）③先写复现断点的自动化测试 ④最小修复 ⑤按 CI 顺序全量验证 ⑥模拟器干净安装验收（nihao→中文→提交，中英切换）。
- 初步代码证据（待运行时确认）：主题 trime.yaml 的 my_pinyin/my_english 把字母键 `label`（主显示文本）写成角标数字/符号，字母全部被顶掉，键盘看起来是数字符号盘；行 3 还多出一个重复的 `l` 键，与主题头部注释“Z 行 7 键”矛盾。
- 最大风险：若运行时证明部署层另有断点（方案未部署/未选中），需在白名单内追加修复；主题字段改动只允许恢复“字母为主、角标在上”的原设计，不得动外观风格。

## 任务 0 基线核对（2026-10-01）
- `git status --short` → 空（干净）；`git rev-parse --short HEAD` → `f49c008c`（与任务书一致）。
- `./gradlew.bat spotlessCheck :app:testDebugUnitTest --console=plain` → **BUILD SUCCESSFUL in 12m 26s**；测试报告统计 `app/build/test-results/testDebugUnitTest/`：**tests=314，failures=0，skipped=0**（≥基线 290，0 失败 0 跳过）。
- `Get-Command adb` 等价检查：adb 不在 PATH；`D:\Android\Sdk\platform-tools\adb.exe` 存在，`adb devices -l` 初始无设备（后续启动专用 smoke 模拟器：Android 15 / API 35）。
- 代码链路已逐层核对：DataManager.sync()（资产 checksum 增量复制 + 首次写用户 default.custom.yaml，schema_list=luna_pinyin/luna_pinyin_simp）→ Rime.startup→startupRime(fullCheck) → 键盘 Eisu_toggle→KEYCODE_EISU→switchKeyboard、Mode_switch→SWITCH_CHARSET→ascii_mode、字母 click→processKey 进 Rime；初始键盘 smartMatchKeyboard/pickFallbackKeyboard 兜底选 my_pinyin。
- 环境备注（不改仓库）：①wrapper 下载 gradle-9.7.1 直连超时，改用 curl 直下放入 `~/.gradle/wrapper/dists`；②项目路径含中文，AGP 拒绝构建 → 在**用户级** `~/.gradle/gradle.properties` 加 `android.overridePathCheck=true`（仓库文件零改动）；③应用户要求 C 盘瘦身的 `.gradle`(6.8G) 与 AVD(4.4G) 已迁至 `D:\Android\gradle-home`、`D:\Android\avd`，并 setx `GRADLE_USER_HOME`/`ANDROID_AVD_HOME`（C 盘余量 22G→55G）；④本机无 `make`，CI 的 `make patch-apply` 以等效原句 `git apply --directory=app/src/main/jni/librime-lua-deps patches/lua.patch` 执行（已应用成功）；⑤OpenCC 插件硬编码 `python3`，本机仅有 WindowsApps 占位 stub → 用 `C:\Python314\python.exe` 复制为 `D:\Android\shims\python3.exe` 并前置 PATH（仓库零改动）。

## 验证日志
- [待补] 修复前模拟器复现、干净安装验收（JVM 单测加载不了 librime，这两项只能真机做）。

### 2026-10-04 补充

- **自动化测试 / 全量验证：已完成。**
  `./gradlew.bat spotlessCheck :app:testDebugUnitTest --console=plain` → `BUILD SUCCESSFUL`
  （34 个测试类、**348 用例、0 失败 0 跳过**）。
- 新增守卫（`ProductionKeyboardTypingTest`）：preset 悬空检查、`schema_list` 部署基座检查。
  反向验证过非空转：去掉 `mydomain.schema.yaml` 的顶层 `translator:` 后判定为 BROKEN。
- 另修：中英切换的键盘联动（`KeyboardWindow.onRimeOptionUpdated` 原先只认 `_keyboard_*` /
  `_key_*`，`ascii_mode` 变化时键盘不换）；`default.custom.yaml` 里删掉无效的
  `"punctuator/half_shape": false` 与静默无效的 `translator/*` 三条。
  详见 `ISSUE-ANALYSIS.md` §7。
- **待做**：真机复现/验收；以及提交前必须排除 `shared/*.yaml` 的硬链接 typechange
  （见 `ISSUE-ANALYSIS.md` §7.5）。

### 2026-10-05 补充：干净安装验收已完成（模拟器）

在 AVD `smoke`（Android 15 / API 35 / x86_64）上用 **release 包**做 `pm uninstall` → `install`
的全新安装验收：

- **抓到并修掉真正的阻断点**：`data/sync/RimeStartupGate.kt` 的
  `if (!storageChoiceDone) return true` 在默认配置下必然成立（默认
  `dataStorageMode=EXTERNAL_SYNC` + 空 tree URI），于是**全新安装时 Rime 根本不启动**
  → `ThemeScope is not ready yet; deferring input view creation` → 键盘整块空白。
  已恢复 fork 原本的语义（「从未做过存储选择」按应用内存储直接启动）并补测试。
- 修复后：Rime 正常部署（`mydomain.prism.bin` / `.table.bin` / `.reverse.bin` 齐全），
  键盘渲染正常，**点 `nihao` 出候选 `你好 你 里`**，中英切换双向、Shift 出大写、联想候选均正常。
- 全量单测 353 用例 / 0 失败。详见 `ISSUE-ANALYSIS.md` §9。
