# myime — Trime 二次开发输入法

Android 中文输入法，基于 [Trime](https://github.com/osfans/trime)（Rime 引擎）二次开发。
只保留 **26 键拼音** 和 **英文** 两种输入模式。

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

### 3. 退格上滑清空

```yaml
backspace_clear:
  send: BackSpace
  repeatable: true
  swipe_up: Clear                   # 全选并删除
  swipe_left: BackToPreviousSyllable
  swipe_down: Redo                  # 恢复误删
```

### 4. 剪贴板首候选

复制文字后首次唤起输入法，剪贴板内容作为**第一个候选词**展示，点一下上屏。

见 `ime/candidates/ClipboardCandidateInjector.kt`。

### 5. 关键词联想

输入 `youxiang` 会同时给出 `邮箱` / `qq.com` / `163.com` / `gmail.com` /
`outlook.com`；输入 `gmail` 直接出 `gmail.com`。

词表维护在 `app/data/rime/myvocab/keywords.tsv`，用脚本合并进主词库。

### 6. GIF 背景真正动起来

Trime 原生已支持图片背景（`ColorTable.Value.Image` → `ColorManager.imageDrawable()`），
但用的是 `BitmapFactory.decodeFile()`，**GIF 只会解出第一帧**。

`data/theme/KeyboardBackground.kt` 补上这段：Android 9+ 用
`AnimatedImageDrawable` 播放动图，并给出自动对比度蒙层方案。

### 7. 自动学习 + 词库导入导出

学习不需要写代码，靠 Rime 原生机制（`mydomain.schema.yaml`）：

```yaml
translator:
  enable_user_dict: true
  user_dict: mydomain.user
  db_class: userdb
```

用户词库落在 `/data/data/com.osfans.trime/files/rime/mydomain.userdb`，
**升级不覆盖**（APK 的 assets 才被覆盖）。
导入导出沿用 Trime 自带的 `UserDictManager`（设置 → 用户词典）。

## 内置词库

`app/data/rime/myvocab/mydomain.dict.yaml`，349 词条：

- **科技/AI/代理** — 科技、AI、Clash、V2Ray、Cloudflare、GitHub、Docker…
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
| `check_dict.py` | 编码合法性、(词,编码) 完全重复、简拼可达性 |
| `test_input_sim.py` | 模拟 librime 排序，验证全拼/简拼/用户学习 |
| `check_keywords.py` | 关键词联想可达性 |

### 三个反直觉的坑

1. **`columns` 必须设 `-1`**。Trime 换行条件是
   `column >= maxColumns || x + widthPx > allowedWidth`。
   含半键占位（`{width: 5}`）的行键个数 > 10，设 `columns: 10` 会被提前换行。

2. **每行权重和必须严格等于 100**。Z 行 8 个字母键要用 `width: 8.75` 而非 10，
   否则差 5 导致错位。

3. **简拼 `initial_quality` 不能设 0.6**。会把「崩铁」(2000) 压到 1200，
   排在所有全拼候选之后。设 1.0 与全拼平权。

## 构建

### GitHub Actions（推荐）

push 到 `main` 触发 `.github/workflows/build-fork.yml`，
产物在 Actions 的 Artifacts 里下载。

CI 会 `submodules: recursive` 拉取 librime 等 11 个 C++ 依赖，
并安装 `platforms;android-36` / `build-tools;36.0.0` / `ndk;27.2.12479018`。

### 本地

```sh
git clone --recursive https://github.com/<you>/myime.git
cd myime
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
- `app/data/rime/myvocab/` （新增，不会被上游覆盖）
- `app/src/main/assets/shared/{trime.yaml,panels.yaml}`
- `app/src/main/java/com/osfans/trime/{ime/candidates,data/theme,ui/main/settings}/`
- `tools/`

## License

GPL-3.0-or-later（继承自 Trime）
