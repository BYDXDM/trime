# 用 GitHub Actions 编译（本地零占用）

> 目标：把 `assembleDebug` / `assembleRelease` / 单元测试 / 代码风格检查全部搬到 GitHub 上跑，
> 本机只负责写代码和下载产物。

---

## 1. 现状：工作流本来就有，这次补了什么

仓库里原本就有 `.github/workflows/build-fork.yml`（上游加的），它能：
预装 NDK/SDK → 同步 submodule → `make patch-apply` → `spotlessCheck` → `testDebugUnitTest`
→ `assembleDebug` → 上传 artifact → 建 GitHub Release。

**它只编 debug 包**，而 debug 包的包名带 `applicationIdSuffix = ".debug"`
（`com.osfans.trime.debug`），不适合当正式包用。

本次**新增/修改**：

| 改动 | 说明 |
|---|---|
| 新增 `workflow_dispatch` 输入 `variant`（debug / release / both） | 手动触发时可选择编哪种 |
| 新增 `workflow_dispatch` 输入 `publish_release`（默认 true） | 是否顺便建 GitHub Release |
| 新增步骤 **Configure release signing** | 从 Secrets 还原签名密钥库（走 `keyBase64`，见下） |
| 新增步骤 **Build release APK** | 编**已签名**的 release 包 |
| 新增步骤 **Verify release signature** | CI 里直接 `apksigner verify --print-certs`，签名没生效当场发现 |
| 改造 **Locate APKs / Upload APK artifact** | 同时索引 debug 与 release 产物 |
| 新增 job **publish** | 只在手动触发且勾选时才发 Release（push 的日常构建不污染 Releases） |

**push 触发仍只编 debug**：debug 与 release 用两套 native 构建配置
（CMake `Debug` vs `RelWithDebInfo`），两个都编等于把 C++ 全量编译跑两遍，CI 时间翻倍。

---

## 2. 需要新增的配置：4 个 Repository Secret

路径：仓库 → **Settings → Secrets and variables → Actions → New repository secret**

| Secret 名 | 值 | 从哪来 |
|---|---|---|
| `KEYSTORE_BASE64` | 密钥库文件的 base64（单行，无换行） | `D:\trime-build\dist\release.jks.base64.txt` 的**全部内容** |
| `KEYSTORE_STORE_PASSWORD` | 密钥库口令 | `D:\trime-build\keystore.properties` 的 `storePassword` |
| `KEYSTORE_KEY_ALIAS` | 别名 | `trime` |
| `KEYSTORE_KEY_PASSWORD` | 密钥口令 | 同 `storePassword` |

**为什么用 `keyBase64` 而不是把 `.jks` 传上去**：`build-logic/.../ProjectExtensions.kt`
的 `signKeyFile` 优先读 `keystore.properties` 里的 `storeFile`，读不到就回退到
`keyBase64` —— 后者会把 base64 解码到构建目录的临时文件，密钥库本体因此不必进仓库、
也不必出现在 runner 的工作区里。工作流写入的 `keystore.properties` 已被 `.gitignore` 忽略。

> ⚠ **未配置 Secret 时**：工作流会打一条 `::warning::`，release 包**不带签名**（装不上，
> 只用于验证编译是否通过）。debug 包不受影响。

> ⚠ **`release.jks` 与口令必须自己备份好**：它是这个应用的签名身份，丢了就只能卸载重装
> （数据全失）。`dist/release.jks.base64.txt` 是私钥的等价物，**不要提交进仓库、不要贴到聊天里**。

---

## 3. 怎么触发

| 方式 | 结果 |
|---|---|
| `git push` 到 `main` / `master` / `develop` | 自动编 **debug**，跑单测与风格检查，留 artifact |
| Actions 页 → **Build Fork APK** → **Run workflow** | 可选 `variant`（debug/release/both）与 `publish_release` |

> **CI 编的是「推上去的代码」，不是你本地的未提交改动。** 想让 CI 产出带修复的包，
> 必须先把改动 `commit` + `push`。

---

## 4. 怎么拿产物

### 4.1 临时产物（保留 30 天）

1. 打开仓库 **Actions** 页 → 点进这次 run
2. 页面底部 **Artifacts** → 下载 `myime-apk-<variant>.zip`
3. 解压得到每个 ABI 一个 APK

### 4.2 长期可下载（推荐）

手动触发时勾上 `publish_release`（默认已勾），构建成功后会创建
`v0.1.<run_number>` 的 Release，APK 直接挂在 **Releases** 页面上，
有固定链接、不设过期时间：

```
https://github.com/<你的用户名>/trime/releases
```

手机直接用浏览器打开这个页面下载安装即可。

### 4.3 该装哪个

| 文件名 | 适用设备 |
|---|---|
| `*-arm64-v8a-*.apk` | **绝大多数手机**（近几年的 Android 机型） |
| `*-armeabi-v7a-*.apk` | 老旧 32 位设备 |

- `-debug` 后缀 → 包名 `com.osfans.trime.debug`（与正式包**并存**，可同时装）
- `-release` 后缀 → 包名 `com.osfans.trime`，已签名，适合长期使用

---

## 5. 想省 CI 时间 / 改 ABI 范围

工作流顶部的 `env.BUILD_ABI` 控制编哪些 ABI（默认只编真机在用的两个）：

```yaml
BUILD_ABI: arm64-v8a,armeabi-v7a
```

`build-logic/.../NativeBaseConventionPlugin.kt` 读它做 `splits.abi { include(...) }`；
不设置则回退到 `Versions.supportedAbis`（全部 4 个，含 x86/x86_64 模拟器版）。
CI 上少编两个 ABI 能把 native 编译时间和产物体积都砍掉约一半。

---

## 6. 常见失败与排查

| 现象 | 原因 / 处理 |
|---|---|
| `lua5.4/lua.h: MISSING` | submodule 没取全。工作流用的是不带 `--depth` 的 `git submodule update --init --recursive`，别改成浅克隆 |
| `call to undeclared function 'fseeko'` | 没跑 `make patch-apply`（工作流里是独立一步，别删） |
| `Failed to install ... ndk;28.0.13004108` | NDK 没预装。必须像工作流那样用 `sdkmanager --install` 预装，交给 AGP 在 daemon 里自动下载会 `Connection refused` |
| `apksigner verify` 报 `NOT signed` | 4 个 Secret 没配全，或 `KEYSTORE_BASE64` 里混进了换行 |
| 单测 `ClassNotFoundException` | 本地沙箱拦截了 `app/build/intermediates/.../*.class` 的读取；CI 上不会出现（本地跑时给命令加沙箱豁免即可） |
