# NEXT-STEPS.md · 下一步交给 agent 的工作单

> 写于 2026-10-10。前置：先通读 **`D:\trime-build\HANDOVER.md`**（项目现状、根因、坑位全在里面）。
> 本文档只讲「要做什么、怎么算做完、碰到岔路怎么选」。

---

## 0. 现在的状态（开工前必须自己复核一遍）

```powershell
cd D:\trime-build
git status -sb          # 期望：## develop...origin/develop   且无 ahead
git rev-parse HEAD      # 期望：6fd825f7d53c695558350ad8ffe9ce52f636c788
git log --oneline -3
```

- **无已知缺陷。** 崩溃、候选栏空白、去边框三项都已修完并推送。
- **真机当前没连上**（2026-10-10 复查时 `adb devices` 为空、
  `Get-PnpDevice` 枚举不到 Android/HDB 设备）。运到任何要动手机的步骤，
  第一步都是让用户插好手机，然后：
  ```powershell
  $ADB='D:\Android\Sdk\platform-tools\adb.exe'
  & $ADB kill-server; Start-Sleep 3; & $ADB start-server; Start-Sleep 5; & $ADB devices -l
  ```
  目标设备序列号应为 `EAT0220316001355`。
- 若用户在此期间已给你新指令，**以用户为准**，本文档退为参考。

---

## 1. 任务总表

| # | 任务 | 依赖 | 优先级 | 能否自动做 |
|---|---|---|---|---|
| A | 让用户亲手确认「去边框」后的观感 | 手机连接 | ★★★ | 半自动（要用户看一眼） |
| B | 发一个可下载的签名 APK | 无（只需网络/CI） | ★★ | 可以 |
| C | 真机复装 + 全量回归验收 | 手机连接 | ★★ | 可以 |
| D | 清理仓库里的历史残留文件 | 无 | ★ | **不可以，先问用户** |
| E | 把 `HANDOVER.md` 纳入版本管理 | 无 | ★ | 可以（但先问） |

**推荐顺序：A → C → B →（用户点头后）D/E。**
A 是唯一「欠用户的」，先做；B 不必等手机。

---

## 2. 任务 A：让用户亲眼确认外观（欠他的唯一一件）

### 背景
用户原话（m00814）：**「现在把去掉边框，我喜欢只看到底部的 gif」**。
我已经改完并用像素探针验证过无回归，但**用户本人还没看到成品键盘**。
这是当前唯一真正未闭环的事项。

### 做什么
1. 等手机连上，装当前 HEAD 的包：
   ```powershell
   & $ADB install -r -t D:\trime-build\dist\trime-release.apk
   ```
   （**必须带 `-t`**；不带时失败且报错是空字符串。）
2. 打开任意可输入的地方（测试用 app：`com.huawei.notepad/com.example.android.notepad.NotePadActivity`），
   切到白洲梓输入法，随便打几个字让候选栏出现。
3. 抓图（**不能走重定向**）：
   ```powershell
   & $ADB shell screencap -p /sdcard/x.png
   & $ADB pull /sdcard/x.png D:\trime-build\tools\verify-ui\proof.png
   ```
4. 用 `read_image` 直接把图给用户看（本模型原生识图，**不要绕 modlens**），
   并明确问一句：**边框去掉后的观感对不对**。

### 怎么算做完
用户明确回复「可以/好看/继续」，或给出要改的地方（那就转成一个新的修复任务）。

### 岔路
- 用户说要**再调**（例如圆点大小、按键间距、背景图裁切位置）→ 走正常流程：
  改 `app/src/main/assets/shared/trime.yaml` → 重新构建 → 装机 → 抓图确认 → 提交。
  **改主题后必须重装 APK 才生效**（asset 打包在里面）。
- 用户说**想恢复按键底框** → 把 `default` 方案的
  `key_back_color` / `off_key_back_color` 从 `0x00000000` 改回带 alpha 的色值即可
  （不要动 `on_key_back_color: 0x3975CE`，它是中英/大小写状态的唯一信号）。

---

## 3. 任务 B：发一个可下载的签名 APK

### 背景
`.github/workflows/build-fork.yml` 是仓库唯一的 workflow，支持
`workflow_dispatch`，输入项里有 `publish_release: true`，勾上就会产出可下载的签名包。

### 做什么
1. 确认仓库 `build-fork.yml` 里 `publish_release` 输入项的名字与默认值
   （**不要凭本文档的记忆下手，读文件**）。
2. 用 GitHub API 触发（本机有 curl；**注意代理**，见 §6）：
   ```powershell
   # token 从用户处要，不要问他贴在聊天里，让他放进环境变量
   $H = @{ Authorization = "Bearer $env:GH_TOKEN"; Accept = "application/vnd.github+json" }
   Invoke-RestMethod -Method Post -Headers $H `
     -Uri "https://api.github.com/repos/BYDXDM/trime/actions/workflows/build-fork.yml/dispatches" `
     -Body (@{ ref = "develop"; inputs = @{ publish_release = "true" } } | ConvertTo-Json)
   ```
3. 轮询 run 状态，成功后把 release 的下载链接给用户。

### 怎么算做完
GitHub 上出现一个新的 release，里面有 `.apk` 资产，链接给了用户。

### 岔路
- **不能用 gh CLI**（本机没装，已确认）。
- 没有 token → 让用户自己在网页上点一下 Actions → Run workflow，别卡住。
- CI 失败 → 先看是不是签名 secret 缺失（`release.jks.base64.txt` 在 `dist/` 里，
  内容是密钥的 base64，**不要外传、不要提交**）。

---

## 4. 任务 C：真机全量回归验收

用户此前明确要求过（m00219）：**「修完你就模拟真机操作验收，尤其是那个表的输出」**——
即要自己驱动真机、看工具输出的数据，不要只靠眼睛。

### 建议的验收清单
| 项 | 期望 |
|---|---|
| 全新场景不崩 | `adb logcat` 里 `FATAL EXCEPTION` 计数为 0 |
| 候选栏有内容 | `python tools/verify-ui/_analyze_bar.py <png> 1453 1499` → `inkpx > 0` |
| 候选字大小 ≈ 按键字母 | `_probe.py` 量字高，和按键字母比 |
| 候选之间有可见间隔 | `_probe.py` 行扫描能看到 `candidate_separator_color` 的色段 |
| 候选栏无滚动条 | 候选栏所在带左侧不再出现 dp(3) 起点的灰条 |
| 按键无圆角底框 | 键面区域内 `key_back_color` 为全透明 |
| 中英切换状态可见 | 高亮键是 `0x3975CE` |
| 底部功能键完整 | 符/123/空格/中/Enter 都在 |

### 怎么驱动
- **必须用 `adb shell input tap` 点 IME 自己的键**；`input text` 会绕过输入法，
  出不来候选。
- 坐标基线（1080 宽 / density 3.0）：`x = 77 + 108 * n`；
  数字行 y≈1600、QWERTY y≈1709、ASDF y≈1882、ZXCV y≈2053。
- 探针命令示例：
  ```powershell
  python tools/verify-ui/_probe.py tools/verify-ui/proof.png 1709
  python tools/verify-ui/_analyze_bar.py tools/verify-ui/proof.png 1453 1499
  ```

### 怎么算做完
上表逐项有数据支撑地为 ✅，或明确记录哪一项 ❌ 并写清复现步骤。

---

## 5. 任务 D / E：需要用户点头才能做

### D. 清理历史残留
`git status` 里这些**不是我这轮产生的**：
```
?? _arch.json _arch2.json _arch_payload.json _del.json _dict_check.yaml
?? _fork.json _hdr.txt _newrepo.json _r.json _repo.json _schema_check.yaml
?? _setup.png _shot1.png…_shot7.png _t1.txt _t2.txt
 T build-logic/gradle/wrapper/gradle-wrapper.properties
 T fastlane/metadata/android/en-US/images/icon.png
 m app/src/main/jni/librime-lua-deps
```
**⚠ `app/src/main/jni/librime-lua-deps` 是子模块工作区脏（lua 补丁），绝对不要动。**
其余项删除前**必须问用户**——`_shot3.png` 那批是候选栏空白的关键证据图，
`_t1.txt`/`_t2.txt` 之类可能有用户要留的内容。

### E. 把 `HANDOVER.md` 纳入 git
它现在未跟踪。要不要提交、提交到哪、要不要改名（例如 `docs/HANDOVER.md`），**问用户**。

---

## 6. 环境坑位速查（每一条都踩过）

### 代理（端口会变，**用之前先探测**）
```powershell
(Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings').ProxyServer
Get-NetTCPConnection -State Listen | Where-Object { $_.LocalAddress -eq '127.0.0.1' } |
  Select-Object -ExpandProperty LocalPort | Sort-Object -Unique
```
- 历史值：clash-verge 时期 `7897` → 2026-10-05 起 v2ray `10808`。**别照抄。**
- shell 里继承的 `http_proxy`/`https_proxy` 可能是**死端口**。
  **libcurl 只认小写**，四个大小写变量都要覆盖，或干脆单条命令指定：
  ```powershell
  git -c http.proxy=http://127.0.0.1:<端口> push origin develop
  ```
- 判断 git 实际用了哪个代理：`$env:GIT_CURL_VERBOSE="1"; git ls-remote origin HEAD`
  → 看 `== Info: Trying 127.0.0.1:<port>...`。
- 报 `Failed to connect to github.com:443 over proxy ... after 2xxx ms` = 用了死端口。
- **代理救不了路由规则**：clash 规则 `DOMAIN-KEYWORD,github,良心云` 曾把 GitHub
  全站路由到挂掉的节点组（google 通、github 全 `code=000`）。遇到这种组合往这查。

### git 凭据
`~/.gitconfig` 里若有一行**空的** `[credential] helper =`，会把系统级 `manager`
（Git Credential Manager）覆盖成「没有 helper」，于是 push 报
`could not read Username for 'https://github.com'`。已修（2026-10-05），
备份在 `C:\Users\Administrator\.gitconfig.bak-before-helper-fix`。
自查：`git config --show-origin --get-all credential.helper`。

### 识图
**本会话模型原生支持识图，直接用 `read_image`，不要绕 modlens。**
需要放大时先裁小区域再读（`tools/verify-ui/_crop_png.py`）。
`modlens` 只在确实看不到图时才作兜底，它现在只剩上游 503，不是配置问题。

### 真机
- `adb install -r -t <apk>`（**必须 `-t`**）。
- 抓图用 `screencap` 到 sdcard 再 `pull`，**不要用 `exec-out ... > f.png`**
  （PowerShell 会写成 UTF-16，PNG 损坏）。
- **不要用 `adb reconnect offline`**（会把设备整个弄没）。
- 系统设置里可以临时把 IME 换成别的再切回来，用来重载主题。

### 硬约束（不要违反）
- **回复一律用中文。**
- **审批提示已禁用：永远不要设置 `sandbox_permissions`。**
- **不要把产物拷到用户桌面**，只放 `D:\trime-build\dist\`。
- **不要动 `app/src/main/jni/librime-lua-deps` 子模块。**
- **不要在用户手机上删 `rime/build/*.bin`**（会毁掉词典产物；
  真需要重建词典用广播，见 HANDOVER §5.4）。

---

## 7. 完成后请更新

做完 A / B / C 中任何一项，**回头更新 `HANDOVER.md` 的 §0 与 §8**，
让下一任不用重新考古。具体要写：做了什么、验证数据是什么、还剩什么。
