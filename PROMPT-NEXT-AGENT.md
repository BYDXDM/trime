# 白洲梓输入法 · 下一个 agent 开工提示词

> 用法：把下面 `---` 之间的全部内容复制，粘贴到新 agent 的第一条消息里。

---

接手 trime 输入法 fork（白洲梓输入法），仓库 `D:\ai\工作\trime-build`，分支 `develop`。

## 先读这两份，别先动代码

- `HANDOVER.md` —— **§0.4**（第六轮：GIF 正解、字体根因、BA 根因）、§0.3（第五轮：五个真机 bug 修完）、§5（单位与主题的坑）
- `NEXT-STEPS.md` —— **§11**（当前优先级，以这节为准）、§6（环境坑速查）

两份文档都写到了 2026-10-24 第六轮，内容互相印证，不用再翻第三份。

## 当前状态（2026-10-24 第六轮停手）

- `HEAD = 7059b57c`，上一个 `7df96714`。第五轮 28 个文件已提交。
- **工作树里有两个东西必须一起提交，别丢**：
  - `M app/src/main/java/com/osfans/trime/ime/core/InputView.kt` —— -46 行，是**故意改回去的**
    （我上一版加的背景图 `imageMatrix` 放大逻辑把头撑爆了，用户骂过）
  - `M app/src/main/assets/shared/backgrounds/mybg/preview.gif` —— 已换成 3471816 字节的
    `tools/verify-ui/_install_candidate.gif`（循环缝 2.04 vs 旧版 15.69），**但还没编译没装机**
- `dist/` 里是 14:11 那版旧包（14227148 字节），**不含**上面这个新 GIF。

## 立刻要做的三件事（按顺序）

### 1. 构建 + 装机验证新 GIF 的循环衔接

`preview.gif` 已经换好了，只差构建：

```powershell
cd D:\ai\工作\trime-build
Remove-Item app\build\intermediates\assets -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item app\build\outputs\apk\release\*.apk -Force -ErrorAction SilentlyContinue
$env:BUILD_ABI="arm64-v8a"
.\gradlew.bat :app:assembleRelease
```

然后 `adb -s EAT0220316001355 install -r -t dist\trime-arm64-v8a-release.apk`，
进华为备忘录弹出键盘，看背景循环顺不顺。

### 2. 字体：用户已经拍板「只加拉丁字体 latin.ttf（约 +0.2MB）」

根因：`app/src/main/assets/shared/` 下一个字体文件都没有，`trime.yaml:14-22` 配的
`latin.ttf` / `han.ttf` / `comment.ttf` / `hanb.ttf` / `symbol.ttf` 全部落空；
`app/src/main/java/com/osfans/trime/data/theme/FontManager.kt:33` 只从
`<userDataDir>/fonts` 读、**不从 assets 读**；真机上那个目录压根不存在 → 全部落到系统 sans-serif。

要做两处改动：
1. 把选好的 `latin.ttf` 放进 `app/src/main/assets/shared/fonts/`
2. `app/src/main/java/com/osfans/trime/data/base/DataManager.kt:58` 的
   `private val PACKAGED_MIRROR_DIRS = arrayOf("backgrounds", "soundeffect")` 里加上 `fonts`
   （加进去后 `mirrorPackagedDirs()` 就会把 assets/fonts 镜像到 `<userDataDir>/fonts`）

中文继续用系统字体，不动 `candidate_font` / `comment_font`。

### 3. bug 3：`ba` 打不出 `BA`

词条**是有的**：`app/src/main/assets/shared/mydomain.dict.yaml:285` 就是 `BA<tab>ba<tab>200`。
问题在 `mydomain.schema.yaml`：

- `mymain` 是主翻译器，但它的 `dictionary: luna_pinyin` —— **底库不是 mydomain，压根看不到 BA**
- `mydomain_terms` 用 `dictionary: mydomain`，可 `initial_quality: 0.5` 把权重砍了一半
- `mydomain_abbrev` 也用 mydomain，但只管简拼（首字母）

所以 `ba` 第一页（`menu/page_size: 9`）被 luna_pinyin 的吧/把/八占满。
可选修法：抬 `mydomain_terms/initial_quality` 到 1.5~2.0，或把 BA / 蔚蓝档案 / 白子 / 阿拜多斯
这些词条的权重抬到 2000 量级。

**改完必须重新部署**（主界面「部署」按钮，坐标 `(744, 225)`），再真机确认 `ba` 第一页有 `BA`。

## 硬约束（违反会出事）

- 一律中文回复
- **不要设置 `sandbox_permissions`**，审批弹窗已禁用
- 构建产物只放 `D:\ai\工作\trime-build\dist\`，不要拷到桌面
- 不要动 `app/src/main/jni/librime-lua-deps` 子模块
- 不要在用户手机上删 `rime/build/*.bin`
- **一次只编一个 ABI**：真机用 `BUILD_ABI=arm64-v8a`，模拟器用 `x86_64`。
  一次编两个会让 arm64 包缺 `AndroidManifest.xml` 变成坏包。换 ABI 前先删
  `app\build\outputs\apk\release\*.apk`
- 提交时排除两个 typechange（`build-logic/gradle/wrapper/gradle-wrapper.properties`、
  `fastlane/metadata/android/en-US/images/icon.png`）和脏子模块 `librime-lua-deps`。
  **不要 `git add -A`**，逐个点名文件

## 环境坑（都踩过）

- **代理端口会变**（历史：clash 是 7897，2026-10-05 起 v2ray 是 10808），用前先探：
  `(Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings').ProxyServer`
  以及 `Get-NetTCPConnection -State Listen` 看 127.0.0.1 的端口。
  环境变量里的 `http_proxy` 可能是死端口，**四个大小写都要显式覆盖**
- **adb 抓图必须 `screencap` 到 sdcard 再 `pull`**，别用 `exec-out ... > f.png`
  （PowerShell 会把重定向写成 UTF-16，PNG 直接坏）
- 模拟器 AVD 用 `smoke`，启动前清 `http_proxy`
- **真机掉线会自己回来**：`adb devices` 空了先等几分钟，别急着 `adb kill-server`
- 华为备忘录启动：`am start` 会被 `HW_SIGNATURE_OR_SYSTEM` 拒，
  要用 `adb shell monkey -p com.huawei.notepad -c android.intent.category.LAUNCHER 1`
- 候选栏坐标（1080x2340 / density 440）：候选行 y 约 1518，第 1 格 x 约 63，每格约 137px
- **要改 rime 的 yaml，先想清楚它在不在 `MIRRORED_DIRS` 里**；在的话一律用 `.custom.yaml` 补丁。
  `app/src/main/assets/shared/symbols.yaml` 就是被 `build-logic/convention/src/main/kotlin/RimeDataSyncPlugin.kt`
  每次构建覆盖的（`outputs.upToDateWhen { false }`），改它等于白改

## 不要再做的事

- **不要再生成背景 GIF 素材**。`tools/verify-ui/_install_candidate.gif` 就是成品，已经用上了。
  想换别的看 `HANDOVER.md §0.4` 的指标表，`tools/` 下有 `_gif_scan.py` 等 5 个扫描脚本可复用。
- **不要试图用代码平移背景图**。键盘区宽扁，`CENTER_CROP` 横向零余量，只能靠放大，
  放大就是头变大——上一轮已经翻车一次。

## 剩下的老账

- 装机复验：右滑到底、中文补全角括号、空格长按拖光标、`@` / `~` 半角、候选下标映射
- bug 4：词库整体难用
- 任务 A：推送 `develop` 触发 CI 发签名包
- 任务 B：按 `PLAN-功能扩展.md` 做下一项

---

## 附：这份提示词对应的事实来源

- 第三/四轮的改动与 5 个 bug 的修法 → `HANDOVER.md` §0.1、§0.2、§0.3
- GIF 素材的完整指标表与 git 历史 → `HANDOVER.md` §0.4 第 1 节
- 字体加载链与体积选项 → `HANDOVER.md` §0.4 第 2 节
- BA 翻译器权重分析 → `HANDOVER.md` §0.4 第 3 节
- 当前优先级与构建命令 → `NEXT-STEPS.md` §11