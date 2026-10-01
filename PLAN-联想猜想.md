# 联想猜想（输入时预测）—— 交接文档

最后更新：2026-10-01

## 一、需求

> 学习用户的打字习惯；之后**只打首字母**就能联想出最常用的词，**打到一半**也能猜出来。

拆成三句可验收的行为：

1. 打 `qidong` 的前缀（如 `qid`）时，`启动` 排在候选前列；
2. 打 `qd`（首字母）时，`启动` 也能被猜出来；
3. 用户重复上屏某个词后，该词因词频上升而**更靠前**。

## 二、实现分三层

| 层 | 作用 | 落点 |
|---|---|---|
| 召回层 | 简拼/前缀都能命中候选 | `script_translator@mydomain_abbrev` + `enable_completion` |
| **重排层** | 按当前输入把候选分档排序 | **predict 插件 v2（本轮新增）** |
| 学习层 | 上屏的词写进用户词典并提权 | `enable_user_dict` + `mydomain.userdb` |

只有**重排层**是本轮新写的；前两层是 Trime/librime 既有能力，只是原先没接上。

### 重排层做什么

predict.db 的 v1 只存「上文词 → 候选词 + 权重」，没有编码信息，做不了「打一半」。
v2 给每条候选额外存一份**它自己的拼音编码**，运行时按当前输入分三档：

```
tier 0  全拼精确   code == input            （最优先）
tier 1  前缀       input 是 code 的前缀      （「打一半」）
tier 2  首字母     input 是首字母串的前缀     （「只打首字母」）
tier 3  不匹配     （此时插件直接返回 false，不污染候选）
```

同档内先比「命中的输入前缀更长」，再比词频。

## 三、本轮的实质产出

### 1. 修掉了 predict 插件 v2 的三个真实 bug

v2 代码此前**从未真正跑通过**——生成的 predict.db 一直是 v1。三个独立缺陷：

1. **命名空间缺失**：`kPredictFormatV2` 声明在 `namespace predict` 内，但 `PredictDb`
   的成员函数在 `namespace rime` 里裸用，编译期就报 `use of undeclared identifier`。
   已全部限定为 `predict::kPredictFormatV2`。

2. **柔性数组误用**：`CodeBoundaries` 内嵌的是 `::rime::Array<uint32_t> offsets`，
   尾部是柔性数组。原先 `next->bounds = arr;`（`OffsetPtr<CodeBoundaries>` 直接赋
   `Array*`）根本不成立；`offsets = *arr` 那种值拷贝又只会拷到第 1 个元素。
   现在按 `sizeof(CodeBoundaries) + sizeof(uint32_t)*(n-1)` 精确申请，逐个写入。

3. **metadata 落盘竞态**：`MappedFile::Allocate()` 容量不够时会 `Resize()` →
   `Close()` 解除映射再重映射，**旧指针全部失效**。v2 的 format 字段在最后一个
   `Allocate` 之前写，于是写进了已解除映射的页里丢掉——表现为盘上 format 一直是
   上一版的 `1.0`。修法是把 format 挪到**所有分配之后**，重取 `address()` 再写；
   `Save()` 里补一次 `Flush()` 再 `ShrinkToFit()`。

> 排查注意：这三条里第 3 条最坑。由于 ninja 的时间戳比对在 junction 上不可靠，
> 改完源码常常**不重编**，会让你看到「改了却没生效」的假象，反复怀疑逻辑。
> **改完 predict 源码务必先删对象文件再编**（重建脚本已内置这一步）。

### 2. predict.db 的生成路线打通了

关键突破：**Gradle 的原生构建本身就产出了 Android 版 `build_predict` 可执行文件**
（`app/build/intermediates/cxx/Debug/<hash>/obj/x86_64/build_predict`），
推到模拟器里直接跑就行——彻底绕开「宿主没有 C++ 工具链」这个曾经卡住的点。

生成脚本：`D:\trime-build\rebuild_predict_db.sh`

```bash
# 它会：删 predict 对象 → ninja build_predict → 推送 → 设备上生成 → 拉回 → 校验格式
bash D:/trime-build/rebuild_predict_db.sh
```

产物落在 `app/data/rime/myvocab/predict.db`，并通过符号链接
`app/src/main/assets/shared/predict.db` 随包发布。

当前产物：10435 字节，format = `Rime::Predict/2.0`，
107 条训练数据 / 30 个上文键。

### 3. 新增校验器

`tools/check_predict_db.py` —— 只读校验 metadata 与指针范围，
用来快速确认「设备上生成的词库」确实是 v2 且没损坏，不必真跑一轮输入法：

```bash
python tools/check_predict_db.py
```

### 4. 测试

- `PredictiveTypingTest`（7 项）钉死三层连线：学习层三开关、召回层 translator 类型与
  补全、重排层 predictor 接线与档位、predict.txt 四列格式、首字母推导（原神→ys）、
  随包发布与镜像、默认方案列表。
- 全量单测 **339/339 通过**。
- 四个 ABI 的 native 构建全部链接通过（`librime_jni.so` 不会因 JNI 改动而挂）。

## 四、关键坑位（下一位接手请先读这段）

### 4.1 构建必须在 ASCII 路径下

仓库路径 `D:\ai\工作\trime` 含中文，ninja 会用 GBK 误解码而失败。
**必须在 `D:\trime-build`（ASCII 镜像）里构建。**

### 4.2 插件的 stale-copy 陷阱 —— 本轮已用 junction 根治

`cmake/Rime.cmake` 里是这么写的：

```cmake
foreach(plugin ${RIME_PLUGINS})
  if(NOT EXISTS "${CMAKE_SOURCE_DIR}/librime/plugins/${plugin}")
    file(CREATE_LINK "${CMAKE_SOURCE_DIR}/${plugin}"
         "${CMAKE_SOURCE_DIR}/librime/plugins/${plugin}" COPY_ON_ERROR SYMBOLIC)
  endif()
endforeach()
```

`if(NOT EXISTS ...)` 只会执行一次。在镜像里它退化成了**真实目录拷贝**，
于是 `librime/plugins/librime-predict/` 里一直是上游的旧代码，
你在 `app/src/main/jni/librime-predict/` 改什么都不进构建。

**已改成 Windows junction（目录联接）**，指向真实源码：

```powershell
# 若日后又被覆盖成实体目录，重建即可
$t='D:\trime-build\app\src\main\jni\librime\plugins\librime-predict'
$s='D:\ai\工作\trime\app\src\main\jni\librime-predict'
if (Test-Path $t) { Remove-Item -Recurse -Force $t }
cmd /c mklink /J "$t" "$s"
```

> 注意：`mklink` 走 Git Bash/`cmd` 传中文路径会被编码搞坏。
> 用 PowerShell 包一层（如上），或写成 `.bat` 用 `chcp 65001`。

### 4.3 镜像里的 `assets/shared` 不会自动同步

镜像的 `app/src/main/assets/shared/` 是**实体目录拷贝**（符号链接在拷贝时被解引用）。
改了 `app/data/rime/myvocab/` 下的字典或 predict.* 之后，**必须手动拷进镜像**，
否则 APK 里带的还是旧数据 —— 本轮就因此让模拟器跑了一轮「词典里查不到『启动』」的冤案。

```bash
SRC="D:/ai/工作/trime/app/data/rime/myvocab"
DST="D:/trime-build/app/src/main/assets/shared"
cp "$SRC/mydomain.dict.yaml" "$SRC/mydomain.custom.yaml" \
   "$SRC/predict.txt" "$SRC/predict.db" "$DST/"
```

### 4.4 Mimosa 安全钩子

- 用 Bash 直接写源码/安全配置会被拦（报「绕过 Write/Edit 安全扫描」）→ 一律用 Write/Edit。
- 新建 `.py` 里出现 `open(<模块常量>, 'w')` 会被判「高危·路径穿越」并拦截。
  本轮的 `tools/gen_predict.py` 因此直接放弃，predict.txt 改为一次性生成后作为数据提交。
  但 `tools/check_predict_db.py`（只读，`open(path, "rb")`）可以正常通过。

### 4.5 adb 在 Git Bash 下的路径

```bash
export MSYS_NO_PATHCONV=1   # 否则 /data/local/tmp 会被改写成 C:/Program Files/Git/...
export ANDROID_SDK_ROOT=D:/Android/Sdk
ADB=D:/Android/Sdk/platform-tools/adb.exe
```

## 五、当前状态与未完成项

### 已完成

- [x] predict 插件 v2 三个 bug 修复，编译通过
- [x] `rebuild_predict_db.sh` 生成真实 v2 `predict.db`（10435 字节）
- [x] `predict.db` 随包发布（APK 内 `assets/shared/predict.db` 已确认存在）
- [x] 运行时部署落盘验证：设备 `rime/predict.db` 为 `Rime::Predict/2.`，
      并生成 `predict.db.version` 标记（DataManager 的镜像逻辑生效）
- [x] `tools/check_predict_db.py` 校验器
- [x] 全量单测 339/339；四 ABI native 链接通过

### 未完成（模拟器功能验收）

**结论：尚未在模拟器上看到联想候选真正弹出。** 卡点不在本轮的预测代码，
而在模拟器环境本身：

- 模拟器每次重启后 `default_input_method` 会被重置回 `LatinIME`，
  需要在每次测试前重新 `ime set`；
- Trime 首次启动要走 Setup 向导 + 通知权限，期间会**重新编译
  luna_pinyin 的 13MB 词典**，耗时较长；
- 在编译尚未完成时会看到 `StatusProto(schemaId=)` 为空 —— 这是**会话没起来**，
  不是词库有问题。等部署跑完再测即可。

**下一步验收步骤**（环境就绪后）：

```bash
export MSYS_NO_PATHCONV=1; export ANDROID_SDK_ROOT=D:/Android/Sdk
ADB=D:/Android/Sdk/platform-tools/adb.exe
PKG=com.osfans.trime.debug
IME=$PKG/com.osfans.trime.ime.core.TrimeInputMethodService

$ADB shell "ime set $IME"
$ADB shell "am start -a android.intent.action.VIEW -d 'https://example.com'"
sleep 5 && $ADB shell input tap 540 300        # 点地址栏唤起键盘
sleep 3 && $ADB logcat -c
$ADB shell input text "qd"                      # 首字母
$ADB logcat -d | grep "Bulk(total"
# 期望：total > 0，且候选首个是「启动」

$ADB shell input text "qid"                     # 打一半
$ADB logcat -d | grep "Bulk(total"
# 期望：total > 0，启动仍在前列
```

判断标准：`Bulk(total=N)` 的 `N > 0`。若仍为 0，先看
`StatusProto(schemaId=...)` 是否为空来区分「会话未起」与「词库没命中」。

## 六、文件清单

### 本轮改动

| 文件 | 说明 |
|---|---|
| `app/src/main/jni/librime-predict/src/predict_db.cc` | 三个 bug 修复：命名空间、柔性数组、format 落盘 + `Save()` 补 Flush |
| `app/src/main/jni/librime-predict/src/predict_db.h` | v2 结构定义（`kPredictFormatV2`/`EntryV2`/`CodeBoundaries`） |
| `app/src/main/jni/librime-predict/src/predict_engine.cc` | `has_codes()` → `LookupRanked()` 接线 |
| `app/src/main/jni/librime-predict/src/predict_translator.cc` | 候选数上限的 off-by-one 修复 |
| `app/src/main/jni/librime-predict/tools/build_predict.cc` | 复用 `BuildPredictDbFromText`，与 JNI 路径共用解析 |
| `app/data/rime/myvocab/predict.txt` | 107 条训练数据（TSV，第 4 列为拼音编码） |
| `app/data/rime/myvocab/predict.db` | **v2 产物**，10435 字节 |
| `app/data/rime/myvocab/mydomain.dict.yaml` | 追加「五、联想搭配常用词」68 条 |
| `app/data/rime/myvocab/mydomain.custom.yaml` | predictor / predict_translator 接线 + prediction 开关 |
| `app/src/main/assets/shared/predict.db` | 符号链接 → `data/rime/myvocab/predict.db` |
| `app/src/test/.../PredictiveTypingTest.kt` | 7 项契约测试 |
| `tools/check_predict_db.py` | v2 词库只读校验器 |
| `D:\trime-build\rebuild_predict_db.sh` | 一键重编 + 生成 + 校验 |

### 镜像侧（`D:\trime-build`，非仓库内容）

| 路径 | 说明 |
|---|---|
| `relink_plugin.bat` | 重建 junction 的辅助脚本 |
| `rebuild_predict_db.sh` | 生成 predict.db 的主脚本 |
| `app/src/main/jni/librime/plugins/librime-predict` | **junction** → 主仓真实源码 |

## 七、待决问题

1. **JNI `buildPredictDb` 要不要留？**
   `rime_jni.cc` / `Rime.kt` / `DataManager.kt` 里加了一套「在设备上把
   predict.txt 编译成 predict.db」的 JNI 路径。四 ABI 链接已通过，不会挂。
   但现在 `predict.db` 已随包发布，这套运行时重编在功能上是**冗余**的。
   留着的好处是：数据更新时用户无需升级 APK；坏处是多一条维护路径
   （且依赖 plugin 的 junction 不被覆盖）。
   **建议：保留**，它是数据热更新的唯一途径；但需在 `Rime.cmake` 上加一道
   保险，避免 junction 被实体目录覆盖时静默链接失败。

2. **`PredictiveTypingTest` 里的首字母推导是 Kotlin 侧复刻的**，
   与 C++ 的 `SplitCodeIntoBoundaries` 是两份独立实现。若两边规则漂移，
   测试会「绿着但没意义」。可考虑把边界切分也做成纯数据（写进 predict.txt），
   或在 CI 里跑一次真实 DB 的往返校验。

3. 模拟器功能验收尚未跑通，见第五节「未完成」小节。
