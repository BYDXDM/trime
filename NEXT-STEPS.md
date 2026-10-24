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

> ⚠ **2026-10-18 更新**：HEAD 仍是 `7df96714`（`docs: 重写 NEXT-STEPS.md`），
> 上面那个 `82132c90` 的期望值已过期。
> 并且**工作树不再干净**：第三轮 + 第四轮的改动**全部没有 commit**
> （清单见 `HANDOVER.md` §8.1），另有两个非本轮的历史残留 typechange 和一个脏子模块。
> 开工前**不要** `git checkout` / `git stash` 清工作树，那会丢掉两轮的成果。

- 上一轮（10-10～11）还做了三个功能：**颜文字面板**、**主界面近 7 天输入走势**、
  **删除键上滑清空（拼音 + 正文）**，并修掉了 7 个符号面板的底栏权重缺陷。
- 第三轮（10-11）做了三项：**自定义短语编辑界面**（真机端到端已验）、
  **候选栏横向滚动只能滑一屏的修复**（已真机实测有效）、
  **`clearCompositionIfAny()` 的处理**（结论订正：不是死代码，已按行为变更删掉）。
- 第四轮（10-18）做了四项：**背景 GIF 换素材**（循环缝 2.76× → 0.845×）、
  **右上角 `)` 误触**（删 p 键的 `swipe_down`）、**左括号自动补右括号**（仅英文模式）、
  **候选栏空白词条 = 缺字形豆腐块**（已接完过滤，**未装机验证**）。详见 §8。
- 若用户在此期间给了新指令，**以用户为准**，本文档退为参考。

---

## 1. 待办总表

| # | 任务 | 依赖 | 优先级 | 能否自动做 |
|---|---|---|---|---|
| A | 触发 CI 发一版含新功能的签名包 | 网络/代理 | ★★★ | 可以（§2） |
| B | 按 `PLAN-功能扩展.md` 的 P1 做下一项功能 | 无 | ★★ | 可以（§3） |
| C | 真机连上后把三个新功能在真机上复验 | 真机 | ★★ | 可以（§4） |
| D | ~~清掉 `clearCompositionIfAny()` 死代码~~ | 无 | — | ✅ **已完成**（§5，结论已订正） |
| E | **提交第三、四轮改动 + 重新出包** | 无 | ★★★ | 可以（§8.1） |
| F | ~~① 自定义短语的真机端到端验证~~ | 真机 | — | ✅ **已通过**（§8.2） |
| G | **⑦ 候选栏豆腐块：装机看图验证** | 真机/模拟器 | ★★★ | 可以（§8.3） |

**推荐顺序：G → E → A → B →（真机回来后）C。**
（G/E 是第四轮的收尾，没做完的话后面每一步都建在半成品上。）

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

1. ~~**自定义短语编辑界面**~~ —— ✅ **已实现并真机验证**（2026-10-11 / 10-18，见 §8.2）
2. **模糊音开关** —— 南方口音用户误码率显著下降
3. **键盘高度 / 键距调节** —— 直接改善手感

⚠ 2、3 都要往 `<主题id>.custom.yaml` 写**受管补丁**（自定义短语走的是
`custom_phrase.txt`，见 `HANDOVER.md` §2.8，不是这条路）。动手前**必读**：

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

## 4. 任务 C：真机复验新功能

真机 `EAT0220316001355` 回来后，把下面这些在真机上再走一遍 ——
**模拟器验不了 IME 时序与候选栏观感**（见 `HANDOVER.md` §7.1 末尾）。

| 功能 | 期望 |
|---|---|
| 颜文字面板 | 符号面板第 8 页签能进；点 kaomoji 能上屏 |
| 近 7 天走势 | 主界面「今日已输入 N 字」下面有一行 `▁▂▃▄▅▆▇█` |
| 删除键上滑 | 有正文 + 有待选拼音时上滑 → **两者都清空**；单击仍逐字删 |
| 候选栏横向滚动 | 右滑能一路滑到最后一个候选（第四轮改过候选栏，要重测） |
| 候选栏豆腐块 | 打 `a` 不再出现空白方块；点第 2 个候选选中的是屏幕上那个词 |
| 右上角 `)` 误触 | 按 p 键不再打出 `)` |
| 左括号补全 | 英文模式打 `(` → 变 `()` 且光标在中间 |

真机坐标基线见 `HANDOVER.md` §7；模拟器的见 §7.1。**两者不同**
（真机 density 480、模拟器 440），别混用。

---

## 5. 任务 D：`clearCompositionIfAny()` —— ✅ 已完成（结论已订正）

> **原判断是错的**：这里曾写「依赖 `service.hasComposition()`，真机预编辑期间恒为假，
> 所以分支从不生效 = 死代码」。实测不成立：`hasComposition()` 在 **inline preedit**
> 与 **SegmentsWindow** 两条路径下会为 true，此时 `KEYCODE_DEL` 走它会把整段预编辑
> 一次清空——所以它不是死代码，而是**和「单击逐字删」的约定冲突的活分支**。

按「有意的行为变更」处理（用户已确认要单击逐字删）：
`KeyEvent.KEYCODE_DEL -> handleDefaultKeyAction(action)`，函数本体与配套注释一并删除。
「删除键上滑清空」的实现未动。

### 怎么算做完
删干净 + 单测全绿 + 单击退格行为不变（仍是逐字删）。→ **均已满足**。

---

## 8. 第三、四轮收尾（2026-10-18 更新）

> 两轮改动**都没有 commit**。`HANDOVER.md` §0.1 与 §8.1 有完整文件清单。

### 8.1 任务 E：提交 + 重新出包

工作树现有改动（`HANDOVER.md` §8.1 列全，这里只列新增文件）：

```
?? app/src/main/java/com/osfans/trime/data/userdict/CustomPhraseStore.kt
?? app/src/main/java/com/osfans/trime/ime/candidates/CandidateGlyphFilter.kt
?? app/src/main/java/com/osfans/trime/ui/main/settings/customphrase/
?? app/src/test/java/com/osfans/trime/data/userdict/CustomPhraseStoreTest.kt
?? app/src/test/java/com/osfans/trime/ime/candidates/CandidateGlyphFilterTest.kt
```

⚠ 工作树里还有**大量无关的历史残留**（两个 typechange、脏子模块
`app/src/main/jni/librime-lua-deps`、`tools/verify-ui/`、约 30 个 `tools/gif_*.py` /
`tools/review_*.py`）。**提交时逐个点名文件，不要 `git add -A`**，也不要把子模块带进去。

⚠ `dist/` 里的包是第三轮的（`dist/trime-release.apk` 14215432 字节），
不含第四轮的候选栏过滤与 GIF；出包走 `BUILD_ABI=arm64-v8a .\gradlew.bat :app:assembleRelease`，
产物再拷进 `dist/`。

### 8.2 任务 F：① 自定义短语真机端到端 —— ✅ 已通过

新增 `OpenAI` / `ceshi` / 权重 10000 → 打 `ceshi`，首候选即 `OpenAI`。
入口、编辑、删除、保存后重载均已验证。唯一没实测的是「`custom_phrase.txt` 在
EXTERNAL_SYNC 模式下部署后未被 `OrphanCleaner` 删掉」（代码已加进
`POST_SCHEMA_DEPLOY_EXPORT_FILES`）。

### 8.3 任务 G：⑦ 候选栏豆腐块装机验证（**下一步做这个**）

1. 起模拟器或连真机，装新包（模拟器要 `BUILD_ABI=x86_64` 单独编一次，
   **不要**一次编两个 ABI——那会让 arm64 包变坏包，见 §6）。
2. 打 `a`，数候选：过滤前 13 个里 3 个是豆腐块，过滤后应当只剩 10 个，且**没有豆腐块**。
3. 右滑到第二屏，确认能滑到末端（②的修复未回归）。
4. **点第 2 个候选**，确认选中的是屏幕上那个词——验证 `originalIndex(position)` 的下标映射没串。
5. 展开面板（`CandidatesPagingSource`）仍会显示豆腐块，这是已知作用域缺口，不算回归。

现存包（**第三轮的产物，不含第四轮改动**）：

```
app/build/outputs/apk/release/trime-arm64-v8a-release.apk   14224974 字节，签名 CN=BYDXDM trime
dist/trime-release.apk                                      14215432 字节，更旧
```

按硬约束**只放 `D:\trime-build\dist\`**（不要拷到用户桌面）。

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

做完 A / B / C / E / G 中任何一项，**回头更新 `HANDOVER.md` 的 §0 与 §8**，
让下一任不用重新考古。具体要写：做了什么、验证数据是什么、还剩什么。

> 2026-10-11 已按这条把第三轮（① 自定义短语 / ② 候选栏滚动修复 / ③ 清死代码）
> 记进 `HANDOVER.md` §0.1、§2.7、§8.1；2026-10-18 又把第四轮
> （④ GIF / ⑤ `)` 误触 / ⑥ 左括号补全 / ⑦ 豆腐块过滤）记进 §2.9–§2.12。
> **两轮的改动都还没 commit**，别以为文档写了就等于做完了。

---

## 9. 2026-10-18 11:10 停手时的待办（**以本节为准，覆盖 §1 和 §8.3**）

> 用户原话："不论进行到哪，直接停手写接手文档"。状态细节见 `HANDOVER.md §0.2`。

### 9.1 symbols.yaml 的 @ / ~ 改半角（5 分钟，先做）

用户 m00380：「快捷 @ / 快捷 ~ 不要让我选，默认半角」。
`app/src/main/assets/shared/symbols.yaml` 的 `half_shape:` 段：

- 第 61 行 `'~' : [ '~', ～, ˜, … ]` → `'~' : { commit: '~' }`
- 第 63 行 `'@' : [ '@', ©, ®, ℗ ]` → `'@' : { commit: '@' }`

⚠️ 本轮改过一次但**没落盘**（复查时文件仍是原样），重做一遍并 `git status` 确认。

### 9.2 强制重新合并 assets 再编（不然改了也进不了包）

```powershell
cd D:\trime-build
Remove-Item -Recurse -Force app\build\intermediates\assets -ErrorAction SilentlyContinue
Remove-Item app\build\outputs\apk\release\*.apk -ErrorAction SilentlyContinue
$env:BUILD_ABI="arm64-v8a"
.\gradlew.bat :app:assembleRelease
```

编完两件事都必须做：

1. 完整性自检，期望 `510 True True`：
   `python -c "import zipfile,sys;n=zipfile.ZipFile(sys.argv[1]).namelist();print(len(n),'AndroidManifest.xml' in n,'resources.arsc' in n)" app\build\outputs\apk\release\trime-arm64-v8a-release.apk`
2. **确认包内 `assets/shared/symbols.yaml` 第 61/63 行已是 `{ commit: ... }` 形式**（本轮就是这里翻车的）。

### 9.3 装机复验（真机 EAT0220316001355）—— 逐条做，别跳

```powershell
$adb="D:\Android\Sdk\platform-tools\adb.exe"; $d="EAT0220316001355"
& $adb -s $d install -r -t app\build\outputs\apk\release\trime-arm64-v8a-release.apk
& $adb -s $d shell monkey -p com.huawei.notepad -c android.intent.category.LAUNCHER 1
```

- [ ] 豆腐块：打 `a` / `ba` / `zhongguo`，候选栏不得出现空白方块
- [ ] **下标映射**：打 `a`，点屏幕上第 2 个候选，上屏必须是那个字
      （本轮已出现一次"点『啊』上屏『锕』"的疑似串位，**必须复测**再决定要不要改代码）
- [ ] 右滑到底：候选多时右滑能到末端、不弹回
- [ ] bug 5：中文模式连打拼音，中途不得冒出 `（` 或 `（）`
- [ ] @ / ~：按 `@`、`~` 直接上屏半角单字符，不弹候选
- [ ] 顺带回归：候选栏横向滚动、自定义短语（`ceshi` → OpenAI）

### 9.4 bug 3：`ba` 打不出 `BA`

现状：`mydomain.dict.yaml` 有 `BA\tba\t200`，但 `mydomain.schema.yaml` 里
②`mymain` 用 `dictionary: luna_pinyin`，`mydomain` 词典只挂在
③`mydomain_abbrev`(initial_quality 0.6) 和 ④`mydomain_terms`(**0.5**) 上，被 luna_pinyin 的 吧/把/八 压住。

候选方案（代价从小到大，**先跟用户确认再改**）：

- a. `mydomain.custom.yaml` 里把 `"mydomain_terms/initial_quality"` 提到 1.5 以上，并开 `enable_completion: true`
- b. 把高频领域词（BA、蔚蓝档案、白子、阿拜多斯…）权重从 100~200 抬到 2000 量级
- c. 最高优先的词直接写进用户 `custom_phrase.txt`（`initial_quality` 2，比 mymain 的 1.0 高）

改完**必须重部署**（HANDOVER §5.4），并在真机上确认 `ba` 第一页能看到 `BA`。

### 9.5 bug 4：词库整体难用

本轮没动。可查的旋钮：`menu/page_size`=8、`mymain/initial_quality`=1.0、
`mydomain_abbrev/initial_quality`=0.6、`predictor`（`max_candidates` 8 / `max_iterations` 3）、
`speller/algebra` 的三组模糊音补丁（注意：**不要加 `pinyin:/abbreviation`**，会让拉丁编码词条借首字母命中把正常拼音候选顶掉）。

### 9.6 提交（任务 E）

25 个文件逐个点名 `git add --`（清单见 `HANDOVER.md §0.2`），**禁止 `git add -A`**。
排除：两个 typechange、脏子模块 `librime-lua-deps`、`tools/gif_*.py`(30) / `tools/review_*.py`(6) /
`tools/verify-ui/`(234) / `rebuild_predict_db.sh` / `relink_plugin.bat`。

提交前自检：
```powershell
git diff --cached --name-only | Measure-Object -Line        # 期望 25
git diff --cached --name-only | Select-String -Pattern 'gradle-wrapper\.properties|en-US/images/icon\.png|librime-lua-deps|^tools/|^dist/'
```

### 9.7 出包 + 后续

- 产物只放 `D:\trime-build\dist\`，顺手清掉 `dist\_*.png` 那批调试截图（约 20 个）。
- 然后才轮到任务 A（CI 发签名包）→ B（`PLAN-功能扩展.md` 下一项）→ C（真机复验）。

### 9.8 待用户拍板

**中文模式打 `(` 要不要也自动补全角 `（）`？** 现在只有英文模式补。
`resolveAutoPair` 的 `if (!isAsciiMode) return null` 就是那个开关位置。
