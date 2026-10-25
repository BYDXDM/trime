# NEXT-STEPS.md · 下一步交给 agent 的工作单

> 写于 2026-10-11。前置：先通读 **`HANDOVER.md`**（项目现状、根因、坑位全在里面）。
> 本文档只讲「要做什么、怎么算做完、碰到岔路怎么选」。
>
> 上一版（10-10）的 A–E 已**全部完成**（用户确认去边框、首个签名包、真机回归、
> 仓库清理、文档入库），本版是新一轮。

---

## 0. 开工前必须自己复核一遍

```bash
cd D:/ai/工作/trime-build
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

## 9. 2026-10-18 11:10 停手时的待办（**已被 §10 覆盖，仅留作历史**）

> ⚠ **本节及 §9.1–§9.8 已过期。** 其中 9.1（symbols 改半角）、9.2（强制重编 assets）、
> 9.6（提交）、9.7（出包）都已完成，做法与本节的建议**不同**（尤其 9.1 不能直接改
> `symbols.yaml`，会被构建覆盖，正解见 §10）。
> **当前状态以 `HANDOVER.md §0.3` 和本文件 §10 为准。**

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

---

## 10. 2026-10-24 停手（**以本节为准，覆盖 §1、§8、§9**）

> 用户：「算了，别干了，写接收文档把，不用验收，写文档就好」。
> 状态细节、根因、踩过的坑见 `HANDOVER.md §0.3`。

### 10.1 已经做完的（不用再碰）

| 原任务 | 状态 | 实际做法（和原计划不一样的地方） |
|---|---|---|
| G：候选栏豆腐块装机验证 | ✅ | 2026-10-18 那版包上验过，按 `a` 无空白方块；**本版包未复验** |
| E：提交 + 重新出包 | ✅ | commit `7059b57c`，28 文件；包 14227148 字节 / 511 项，已装机 |
| §9.1 `symbols.yaml` 改 @ / ~ 半角 | ✅ | **不能直接改 `symbols.yaml`** —— 构建时被 `syncRimeSharedData` 覆盖。改用新增 `app/src/main/assets/shared/symbols.custom.yaml` 补丁 |
| §9.2 强制重新合并 assets | ✅ | `Remove-Item app\build\intermediates\assets -Recurse -Force` 后再编 |
| §9.8 中文模式补全角括号 | ✅ 已拍板 | 用户 m00994：「中文也要」。已删掉 `isAsciiMode` 守卫并改单测 |
| 空格长按拖光标 | ✅ 代码已改 | `GestureFrame.kt` 加 `slideArmed` |
| 背景 GIF 头太大 | ✅ 已退回 | 素材退回接手时那份；`InputView.kt` 的 `imageMatrix` 代码已全删 |

### 10.2 工作树里还有两个未提交的回滚（**先提交，别丢**）

```
 M app/src/main/assets/shared/backgrounds/mybg/preview.gif   2905549 → 3751952 字节
 M app/src/main/java/com/osfans/trime/ime/core/InputView.kt   -46 行
```

两个都是**故意改回去的**，`InputView.kt` 用 `git diff 7df96714 -- <该文件>` 验证过输出为空。
排除项照旧：两个 typechange + 脏子模块 `librime-lua-deps`。

### 10.3 下一步（按优先级）

1. **背景 GIF 循环衔接不流畅** —— 用户 m01244 唯一的明确诉求。
   两份素材的客观数据：接手时那份（现用）循环缝 **15.69**（相邻帧均值 5.06，比值 **3.10×**，明显跳帧）；
   第四轮换的那份（已弃）循环缝 **3.45**（比值 **0.88×**，无感循环）但构图是近景、头占比大。
   **正确方向：以接手时那份的构图为准，重新生成循环缝达标的素材**，工具链是 `tools/gif_*.py`（30 个），
   原件在 `tools/verify-ui/_orig_preview.gif`。代码层面无解，见 `HANDOVER.md §0.3` 的专节。
2. **任务 A：推送 `develop` 触发 CI 发签名包**（还没做）
3. 装机复验：右滑到底、中文补全角括号、空格长按拖光标、`@` / `~` 半角、候选下标映射
4. bug 3（`ba` 打不出 `BA`）、bug 4（词库整体难用）—— **一直没动**
5. 任务 B：按 `PLAN-功能扩展.md` 做下一项

### 10.4 待用户拍板

**无。** §9.8 那条已在 m00994 拍板为「中文也要」，且已实现。

---

## 11. 2026-10-24 第六轮（**只调研、未改代码**，换 agent 时停在这里）

> 覆盖 §10 的三条结论：GIF 有现成解法、字体根因已查明、BA 根因已查明。
> 代码与工作树**零改动**，`HEAD` 仍是 `7059b57c`。

### 11.1 优先级（按用户 m02529 的三个诉求排）

| # | 事项 | 状态 | 工作量 |
|---|---|---|---|
| 1 | 背景 GIF 循环衔接 | **解法已就位**，只需换文件 + 出包装机 | 小（10 分钟 + 一次构建） |
| 2 | 字体难看 | 根因已查明，方案待用户拍板 APK 体积 | 中（改一行 + 选字体 + 构建） |
| 3 | `ba` 打不出 `BA` / 词库难用 | 根因已查明，方案未定 | 中（改 schema + 重部署 + 真机验） |

### 11.2 GIF：**别再去生成素材了**

`tools/verify-ui/_install_candidate.gif`（3471816 字节、38 帧、SHA256 `c6b7ad55c429f657`）
就是成品，**构图与现用素材完全一致**（远景、头占比小），循环缝 `2.04` vs 现用 `15.69`，
比值 `0.39×`（无感循环）vs 现用 `3.10×`（明显跳帧）。详见 `HANDOVER.md §0.4` 的指标表。

执行就两步（**第 1 步本轮已做完**，`preview.gif` 现在就是 3471816 字节）：

1. ~~复制文件~~ **已完成**：`Copy-Item tools\verify-ui\_install_candidate.gif app\src\main\assets\shared\backgrounds\mybg\preview.gif -Force`
2. 构建 + 装机：
   ```powershell
   Remove-Item app\build\intermediates\assets -Recurse -Force -ErrorAction SilentlyContinue
   Remove-Item app\build\outputs\apk\release\*.apk -Force -ErrorAction SilentlyContinue
   $env:BUILD_ABI="arm64-v8a"
   .\gradlew.bat :app:assembleRelease
   ```
   然后 `adb -s EAT0220316001355 install -r -t dist\trime-arm64-v8a-release.apk`，看键盘背景循环是否顺。

背景图**不在** `RimeDataSyncPlugin.MIRRORED_DIRS` 里，不会被构建冲掉；
`DataManager.kt:58` 的 `PACKAGED_MIRROR_DIRS` 含 `backgrounds`，尺寸变化会自动覆盖设备上的旧图。

背景图**不在** `RimeDataSyncPlugin.MIRRORED_DIRS` 里，不会被构建冲掉；
`DataManager.kt:58` 的 `PACKAGED_MIRROR_DIRS` 含 `backgrounds`，尺寸变化会自动覆盖设备上的旧图。

### 11.3 字体：根因是**一个字体文件都没有**

`app/src/main/assets/shared/trime.yaml:14-22` 的 9 个字体名全部落空，
`FontManager.kt:33` 只从 `<userDataDir>/fonts` 读、不从 assets 读，
真机上那个目录**根本不存在** → 全部落到系统 `sans-serif`。

**要用户先拍板**：加中文字体会让 APK 从 14.2MB 涨到 30MB+（`Deng.ttf` 16.3MB / `NotoSansSC-VF.ttf` 17.8MB / `simhei.ttf` 9.8MB）。
折中方案是只放一个 `latin.ttf` 覆盖 key/label/preview/text 四个槽，中文继续靠系统 fallback。

### 11.4 BA：词条在，但主翻译器看不到

`mydomain.dict.yaml:285` 有 `BA<TAB>ba<TAB>200`。问题在 `mydomain.schema.yaml`：
- `mymain` 的 `dictionary: luna_pinyin`（**底库不是 mydomain，看不到 BA**），`initial_quality: 1.0`
- `mydomain_terms` 用 `dictionary: mydomain` 但 `initial_quality: 0.5`（权重砍半）
- `mydomain_abbrev` 用 `dictionary: mydomain`、`initial_quality: 1.0`，但只管简拼

所以 `ba` 第一页（`menu/page_size: 9`）被 luna_pinyin 的吧/把/八占满。
可选：抬 `mydomain_terms/initial_quality` 到 1.5~2.0，或把 BA 等词条权重抬到 2000 量级。

**改完必须重新部署**（主界面 `部署` 按钮，坐标 `(744, 225)`），再真机确认 `ba` 第一页有 `BA`。

### 11.5 未改动的既有待办

- 装机复验：右滑到底、中文补全角括号、空格长按拖光标、`@` / `~` 半角、候选下标映射
- 任务 A：推送 `develop` 触发 CI 发签名包
- 任务 B：按 `PLAN-功能扩展.md` 做下一项
- 两个回滚（`InputView.kt`、`preview.gif`）**仍未提交**

---

## 12. 2026-10-24 第七轮（**已完成并装机验证**，覆盖 §11 的三个诉求）

> 三个诉求全部落地，已提交并出包装机。细节与踩坑见 `HANDOVER.md` §0.5（以它为准），
> 这里只留结论与下一步。

### 12.1 结果

| # | 诉求 | 结果 |
|---|---|---|
| 1 | 背景 GIF 循环 | `_install_candidate.gif` 已装机，背景确认在动；**循环顺不顺等用户过目** |
| 2 | 字体难看 | 真根因是 `GeneralStyle.stringList()` 只认 YAML 序列、标量字体名全部丢空（2bcdf382 的错误设计）；已修 + Varela Round 换 **Quicksand** + `key_text_size` 22→20（用户要求「更小更圆」）+ `PACKAGED_MIRROR_DIRS` 加 fonts |
| 3 | ba 词库 | 用户澄清要的是**蔚蓝档案词库**（不是字面 BA 词条）；根因是整库权重比 essay 词频刻度低 10 倍（白字=557 vs 白子=285）；蔚蓝档案段 ×10 → 2500~3400，BA=2000。真机：白子首页第 5、蔚蓝档案第 1 |

### 12.2 下一步（按优先级）

1. **词库其他段（科技/原神/崩铁 ≤200）是否也对齐 essay 刻度** —— 这是 bug 4
   「词库难用」的真正根因；抬权会霸榜，**必须用户拍板**后再动。
2. GIF 循环观感（用户确认）+ 装机复验清单（右滑/括号/空格拖光标/@~）。
3. 任务 A：push（本地已 ahead 2）触发 CI；任务 B：PLAN-功能扩展.md。
4. 手机电上还装着含诊断代码的中间包？——否，20:12 的正式包（诊断已移除）已装机。



---

## 13. 2026-10-24 第八轮（搜狗风面板 + 删除键统一 + 字数图表，已完成）

| # | 诉求 | 结果 |
|---|---|---|
| 1 | 符号界面难看繁琐、表情删页、数字照搜狗图 | 符号面板 4 列大键 + ↑/↓ 分组循环 + 数字页签；数字面板照搜狗；表情/颜文字页已删（commit b7c6b761） |
| 2 | 删除键别总换位置 | 统一到右缘拇指位：主键盘 Z 行末（锚点）、数字/符号面板右列底部（commit 50add28c） |
| 3 | 打字字数图表化 | 主界面「今日已输入」下方新增 14 天 Canvas 柱状图（commit e9b0c5a1） |

- 搜狗本地词库：手机端 Android/data 可进但词库本体在 /data/data（无 root 不可达），
  PC 未装搜狗 —— 结论「挖不到」，替代路径见 HANDOVER §0.6.4。
- 下一步照旧：词库其他段刻度对齐（待拍板）、装机复验清单、任务 A/B。


---

## 14. 2026-10-24 第九轮（候选两 bug + 科技段 + 返回键，已完成装机验证）

| # | 诉求 | 结果 |
|---|---|---|
| 1 | 重大 bug：候选右滑不能到底 + 无法展开 | 根因 = JNI bulk 截断 16 + total=-1 判空恒真；limit→64 + 判空修正（24d0910b） |
| 2 | 科技大于崩铁，原神可删 | 科技 ×10→750~1000；原神段删除；gacha 通用词挪崩铁；predict 原神链清掉（fc1f8b06） |
| 3 | 返回键定死左下角 | 数字面板行首 + 符号面板页签行首（26eeeae3） |

- 词库层级：蔚蓝档案 2500~3400 > 科技 750~1000 > 崩铁 ≤200（待拍板是否对齐）。
- 本轮起所有提交已同步推远端。


---

## 15. 2026-10-25 第十轮（符号翻页/Shift 箭头/数字键去快捷，已完成装机验证）

| # | 诉求 | 结果 |
|---|---|---|
| 1 | 符号页面不能下滑 | 语义 = 组内翻更多符号：每组扩两页（39990dad）+ 全键滑动翻页（36480c47） |
| 2 | 数字键不要圈号快捷 | long_click/swipe_up 全删（39990dad） |
| 3 | Shift 改搜狗箭头 | label → ⇧（39990dad） |

键盘总数 17（7 组 × 2 页 + 拼音/英文/数字）。全部已推送。
翻页流畅化（16455902）：双页滑动动画 180ms + 左右滑手势，连滑跨分组稳定。
