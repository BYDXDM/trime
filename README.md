# trime-build — Trime(同文输入法)fork 活跃主仓库

> 工作区盘点版 README(2026-11-07),替换了上游同名文件。上游原版内容可随时取回:
> `git show HEAD:README.md`,或看 `README_sc.md` / `README_tc.md`(中文版)。
> fork 的功能差异与键盘方案详解见 **README-FORK.md**;开发过程实录见 **HANDOVER.md**。

Trime = Rime 输入法引擎的 Android 前端(Java/Kotlin + JNI C++)。本目录是 osfans/trime 的二次开发主仓库,日常构建、改码、装机都在这里。

## 分支与远程

| 项 | 值 |
|---|---|
| 分支 | `develop`(唯一工作分支) |
| origin | `https://github.com/BYDXDM/trime.git`(自己的 fork,推送目标) |
| upstream | `https://github.com/osfans/trime.git`(上游) |
| 本地 vs origin | **领先 3 个提交未推送**,无落后(2026-11-07 快照,当天有 fetch) |
| 本地 vs upstream | 领先 88 / 落后 15 |

最新提交(36f89c99,2026-11-07):剪贴板建议条两连修、候选面板缺字形过滤、空格长按 iOS 式触摸板+光标放大镜等。

**工作区状态**:4 个源文件已改未提交(候选/键盘 UI)+ 2 个 typechange + 47 个未跟踪文件(多为 `tools/_*.py` 截图工具与构建日志),submodule `librime-lua-deps` 有本地改动。根目录 `_commit_msg.txt`、`rebuild_predict_db.sh`、`relink_plugin.bat` 为工作残留。

## 构建要点

- 命令:`gradlew assembleDebug` / `assembleRelease`(本地构建日志 `build-arm64*.log`、`b-*.log` 即产物)。风格检查 `make spotlessCheck`。
- 原生层:externalNativeBuild → `app/src/main/jni/CMakeLists.txt`,聚合 librime 及其依赖(glog/yaml-cpp/snappy/leveldb/marisa/OpenCC)+ JNI 层 `librime_jni`。NDK `28.0.13004108`、CMake `3.31.6` 版本由 `build-logic/convention/.../Versions.kt` 统一定义。
- submodule 必须先同步:`git submodule update --init`(`.gitmodules` 里 librime、librime-lua、OpenCC、snappy、octagram、trime-predict 等;`librime-predict` 指向 BYDXDM/trime-predict)。
- **Boost 自动下载坑**:`jni/cmake/Boost.cmake` 首次 configure 自动下载 `boost-1.89.0-cmake.tar.xz`(SHA256 校验),但 `file(DOWNLOAD)` 的目标是**相对路径,落点是 cmake 进程的 cwd**,不是源码目录 —— 正常从 gradle 走,tar.xz 落在 `app/boost-1.89.0.tar.xz`(约 102MB,下载缓存);解包才落到 `CMAKE_SOURCE_DIR` 即 `jni/boost/`。若绕过 gradle 从别的目录手动 configure,会在那个 cwd 多出一份 tar.xz(可删)。**`jni/boost/` 是解包产物,不算源码,勿提交。**
- **路径坑**:现路径 `D:\ai\工作` 含中文,历史上中文路径曾导致 ninja 构建失败(故曾迁 ASCII 路径构建,迁移记录在 HANDOVER.md §0.9);如今在中文路径下构建需 `android.overridePathCheck=true`(构建日志有 experimental warning)。若 cmake/ninja 报诡异"文件找不到",优先怀疑中文路径。
- 签名:`keystore.properties` + `release.jks`(含口令,**敏感**)。CI 走 `.github/workflows/build-fork.yml`(可出 release 并发 Release),配置详见 CI-BUILD.md。
- `Makefile` 另有 `patch-apply`(CI 用,给 librime 打补丁,补丁在 `patches/`)、clang-format 等目标。

## 目录要点

- `app/src/main/java/com/osfans/trime/` — 主代码:`ime/`(输入法服务、键盘、候选)、`core/`、`data/`、`ui/`、`provider/` 等;入口 `TrimeApplication.kt`。
- `app/src/main/jni/` — 原生层:7 个 submodule(librime/librime-lua/OpenCC/snappy/octagram/predict/lua-deps)+ `librime_jni/`(JNI 胶水,`rime_jni.cc` 等)+ `cmake/`(Boost.cmake 等查找/下载脚本)。
- `app/src/main/assets/shared/` — **词库方案目录**:luna_pinyin 系列、`mydomain.*`(自建域名词方案)、`pinyin.yaml`、`trime.yaml`(键盘布局)、`predict.db`(联想库)等。
- `app/data/rime/` — 随包 rime 方案子模块(prelude/luna-pinyin/stroke/essay)。
- `docs/`(仅 english-correction.md)、`doc/`(Keyboard.md、trime-schema.json)、`fastlane/`(商店元数据)。
- 构建产物/缓存(**盘点勿进、勿递归**,本仓共 6.6G 主要就是它们):`app/build/`、`.gradle/`、`app/.cxx/`、`jni/boost/`、`build/`、`dist/`(APK 产物)。

## 关联说明

- 工作区根目录的 `_dev_luna.custom.yaml`、`_dev_mydomain.custom.yaml`、`_dev_pinyin.yaml`、`_dev_trime.yaml`、`_built_mydomain.yaml` 是本仓 `assets/shared/` 词库方案的桌面侧配套/构建中间产物,已确认留在根目录,与本仓联动但互不隶属,勿删。
- `_archive/trime-old-clone-03eb40eb/` 是本仓的历史祖先克隆,仅作考古。

## 敏感文件(存在即可,内容勿复述、勿提交)

`keystore.properties`(签名口令)、`release.jks`(签名密钥库)、`dist/release.jks.base64.txt`(等价私钥)。
