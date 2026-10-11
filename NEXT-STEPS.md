# NEXT-STEPS.md · 下一步交给 agent 的工作单

> 写于 2026-10-11。前置：先通读 **`HANDOVER.md`**（项目现状、根因、坑位全在里面）。
> 本文档只讲「要做什么、怎么算做完、碰到岔路怎么选」。
>
> 上一版（10-10）的 A–E 已**全部完成**（用户确认去边框、首个签名包、真机回归、
> 仓库清理、文档入库），本版是新一轮。

---

## 0. 开工前必须自己复核一遍

```bash
cd D:/trime-build
git status -sb          # 期望：## develop...origin/develop，无 ahead
git rev-parse HEAD      # 期望：82132c907b46ad9f006ecb750d86e9fef36fc210
git log --oneline -5
```

- 上一轮（10-10～11）还做了三个功能：**颜文字面板**、**主界面近 7 天输入走势**、
  **删除键上滑清空（拼音 + 正文）**，并修掉了 7 个符号面板的底栏权重缺陷。
- **无已知缺陷。**
- 若用户在此期间给了新指令，**以用户为准**，本文档退为参考。

---

## 1. 待办总表

| # | 任务 | 依赖 | 优先级 | 能否自动做 |
|---|---|---|---|---|
| A | 触发 CI 发一版含新功能的签名包 | 网络/代理 | ★★★ | 可以（§2） |
| B | 按 `PLAN-功能扩展.md` 的 P1 做下一项功能 | 无 | ★★ | 可以（§3） |
| C | 真机连上后把三个新功能在真机上复验 | 真机 | ★★ | 可以（§4） |
| D | 清掉 `clearCompositionIfAny()` 死代码 | 无 | ★ | 可以（§5） |

**推荐顺序：A → B →（真机回来后）C → D。**

---

## 2. 任务 A：触发 CI 发签名包

上一轮**没发成**：推送完成后代理突然全失效（10808 / 10812 / 8708 与直连都返回 000），
全端口扫描也没找到可用代理。先探端口（见 §6），再：

```bash
P=http://127.0.0.1:10808      # 先探测，别照抄
curl -s -o /dev/null -w "%{http_code}\n" --max-time 30 -x $P -X POST \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "Content-Type: application/json" \
  -d '{"ref":"develop","inputs":{"variant":"release","publish_release":"true"}}' \
  https://api.github.com/repos/BYDXDM/trime/actions/workflows/build-fork.yml/dispatches
# 期望 HTTP 204
```

⚠ 工作流有 `concurrency.cancel-in-progress`：**CI 在跑时别往同一分支 push**，会取消它。
⚠ 签名 Secret 已配好（4 个），不用再动。

### 怎么算做完
GitHub 上出现新 Release（tag `v0.1.<run_number>`），资产里有
`trime-arm64-v8a-release.apk`，把下载链接给用户。

---

## 3. 任务 B：按 `PLAN-功能扩展.md` 做下一项

`PLAN-功能扩展.md` §2 列了 P1 三项，建议顺序：

1. **自定义短语编辑界面** —— 用户价值最高（常用语 / 地址一键上屏）
2. **模糊音开关** —— 南方口音用户误码率显著下降
3. **键盘高度 / 键距调节** —— 直接改善手感

⚠ 三项都要往 `<主题id>.custom.yaml` 写**受管补丁**。动手前**必读**：

- `HANDOVER.md` §5.1 的**单位表**（`key_text_size` / `candidate_text_size` 是 sp，
  `candidate_view_height` / `candidate_padding` 是 dp，`candidate_spacing` 会被
  `max(spacing, dp(spacing))` 放大）
- `README-FORK.md` §6 的补丁坑：配色键必须写成**嵌套 `preset_color_schemes:`**，
  **不要**用 `preset_color_schemes/<id>` 路径语法 —— 会触发 `ThemeDslExpander`
  抛 `UnsupportedDsl`，主题加载整个失败、**键盘打不开**

### 怎么算做完
模拟器（或真机）实测功能可用 + `sh tools/all_checks.sh` 通过 + 单测全绿。

> ⚠ `all_checks.sh` 第 1 步的 `gen_keywords.py --check` 是**既有失败**
> （`app/data/` 与 `keywords.tsv` 本来就不一致，与本轮改动无关）。
> 想让它整条绿，先跑一次 `python3 tools/gen_keywords.py` 把词库合上。

---

## 4. 任务 C：真机复验三个新功能

真机 `EAT0220316001355` 回来后，把这三个在真机上再走一遍 ——
**模拟器验不了 IME 时序与候选栏观感**（见 `HANDOVER.md` §7.1 末尾）。

| 功能 | 期望 |
|---|---|
| 颜文字面板 | 符号面板第 8 页签能进；点 kaomoji 能上屏 |
| 近 7 天走势 | 主界面「今日已输入 N 字」下面有一行 `▁▂▃▄▅▆▇█` |
| 删除键上滑 | 有正文 + 有待选拼音时上滑 → **两者都清空**；单击仍逐字删 |

真机坐标基线见 `HANDOVER.md` §7；模拟器的见 §7.1。**两者不同**
（真机 density 480、模拟器 440），别混用。

---

## 5. 任务 D：清死代码

`CommonKeyboardActionListener.clearCompositionIfAny()` 依赖
`service.hasComposition()`（= `composingText` 非空），真机预编辑期间**恒为假**，
于是 `KEYCODE_DEL -> if (!clearCompositionIfAny())` 这个分支**从不生效** ——
现在单击退格就是普通的逐字删。

按用户确认的行为，这个分支**本来就不该生效**，所以直接删掉它
（连同 `clearCompositionIfAny()`），让代码与实际行为一致。
不删的隐患：日后有人「修好」`hasComposition()`，单击行为会**静默变成整体清空**，
与「单击逐字删」的约定冲突。

### 怎么算做完
删干净 + 单测全绿 + 单击退格行为不变（仍是逐字删）。

---

## 6. 环境坑位速查（每一条都踩过）

### 代理（端口会变，**用之前先探测**）

```bash
netstat -ano 2>/dev/null | grep LISTENING | grep "127.0.0.1:" | awk '{print $2}' | sed 's/.*://' | sort -n | uniq
for P in <候选端口>; do
  curl -s -o /dev/null -w "$P -> %{http_code}\n" --max-time 3 -x http://127.0.0.1:$P https://api.github.com
done
```

- 历史值：clash `7897` → v2ray `10808`（10-05～10-11）→ 之后**又变过**。**别照抄。**
- ⚠ `reg.exe` 被安全策略黑名单拦截，**不要**再用 `reg query` 读 WinINET。
- git 走代理：`git -c http.proxy=http://127.0.0.1:<port> push origin develop`
- push 偶尔会挂住；**放后台写日志再 tail** 更稳：
  `git -c http.proxy=... push --verbose origin develop > /tmp/push.log 2>&1`

### 构建（**最容易出事的环节**）

```bash
export BUILD_ABI=arm64-v8a      # ⚠⚠ 一次只编一个 ABI！
./gradlew.bat spotlessApply :app:testDebugUnitTest :app:assembleRelease
```

⚠⚠ **多 ABI 会产出坏包**：`BUILD_ABI=arm64-v8a,x86_64` 会让 arm64 那个包只剩
83 个条目、**缺 `AndroidManifest.xml` / `resources.arsc` / `res/`**，装不上
（`INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION`）。编完**必自检**：

```bash
python -c "import zipfile,sys;n=zipfile.ZipFile(sys.argv[1]).namelist();print(len(n),'AndroidManifest.xml' in n,'resources.arsc' in n)" dist/trime-release.apk
# 期望：510 True True
```

要 x86_64 给模拟器用，就**单独再编一次** `BUILD_ABI=x86_64`。

### 模拟器（真机掉线时的替代）

完整方法见 `HANDOVER.md` **§7.1**。要点：AVD 用 `smoke`（API 35 / x86_64）、
**必须用工具的后台模式启动**（`nohup ... &` 起的进程会在本次调用结束时被回收）、
启动前清掉 `http_proxy` 等变量、装 x86_64 包、输入目标用应用自带的
`MainActivity` →「Test input」面板。

### 真机

- `adb install -r -t <apk>`（**必须 `-t`**）。
- 抓图用 `screencap` 到 sdcard 再 `pull`，**不要** `exec-out ... > f.png`
  （PowerShell 会写成 UTF-16，PNG 损坏）。
- **不要用 `adb reconnect offline`**（会把设备整个弄没）。
- Git Bash 里 adb 命令前加 `export MSYS_NO_PATHCONV=1`，否则 `/sdcard/x.png`
  会被误转成 Windows 路径。
- 华为装完 APK 会弹全屏「安装成功」页挡住后续操作，先点「完成」。

### 硬约束（不要违反）

- **回复一律用中文。**
- **永远不要设置 `sandbox_permissions`。**
- **不要把产物拷到用户桌面**，只放 `D:\trime-build\dist\`。
- **不要动 `app/src/main/jni/librime-lua-deps` 子模块。**
- **不要在用户手机上删 `rime/build/*.bin`**（会毁词典产物；重建用广播，见 HANDOVER §5.4）。

---

## 7. 完成后请更新

做完 A / B / C 中任何一项，**回头更新 `HANDOVER.md` 的 §0 与 §8**，
让下一任不用重新考古。具体要写：做了什么、验证数据是什么、还剩什么。
