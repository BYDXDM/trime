# verify-ui — 候选栏/键盘的像素级验收工具

这台机器上视觉模型通道不可用（modlens 走 generativelanguage.googleapis.com，
ECONNREFUSED / 503 / ECONNRESET），所以候选栏「到底画没画出来」改用纯像素分析确认。
工具只用标准库（zlib + struct），无第三方依赖，Python 3 直接跑。

## 为什么需要它

候选栏曾经**整行零墨迹**——不是字小，是根本没画。肉眼看缩略图容易误判成「字太小」，
而行投影能给出确定的 0/非 0 判据。

## 用法

### 1. 取图（必须走设备端存盘再 pull）

```powershell
$adb = "D:\Android\Sdk\platform-tools\adb.exe"
& $adb shell "screencap -p /sdcard/s.png"
& $adb pull /sdcard/s.png .\s.png
```

⚠ **不要**用 `adb exec-out screencap -p > s.png`：PowerShell 会把重定向写成 UTF-16，
PNG 损坏（表现为 `Unsupported or malformed image data`）。

### 2. 行投影：找内容在哪一行

```powershell
python tools\verify-ui\_analyze_bar.py .\s.png 1440 1620
```

每行输出 `y=.. bg=.. min=.. inkpx=.. runs=..`：

| 字段 | 含义 |
|---|---|
| `bg` | 该行最大亮度，近似当作背景 |
| `min` | 该行最小亮度 |
| `inkpx` | 比背景暗 60 以上的像素数 —— **>0 就是有内容** |
| `runs` | 暗区段数，可用来数字形个数 |

候选栏那一带如果 `inkpx` 全为 0，就是「整行空白」这个缺陷复现了。

### 3. 裁剪：把某一行带出来看

```powershell
python tools\verify-ui\_crop_png.py .\s.png .\bar.png 0 1470 1080 1600
```

参数为 `<源> <目标> <x0> <y0> <x1> <y1>`（像素，原图坐标）。

## 这台机器上的实测基准

真机 HUAWEI ANA-TN00 / 1080x2340 / density 480（3.0）：

| 元素 | 位置 | 测量值 |
|---|---|---|
| 候选栏横带 | y ≈ 1470–1600 | 候选字形墨迹 48px 高 |
| 数字行 | y ≈ 1600 | — |
| QWERTY 字母行 | y ≈ 1709 | 字母墨迹约 48px 高 |
| ASDF 行 | y ≈ 1882 | — |
| ZXCV 行 | y ≈ 2053 | — |

键盘按键的横坐标：数字/字母行起点约 x=77，键距约 108px
（`x = 77 + 108 * n`，n 从 0 起）。

## 模拟输入

通知栏/输入法都没法用 `adb shell input text` 触发候选（那是绕过输入法直接塞给应用）。
必须点击输入法自己的键盘：

```powershell
& $adb shell "input tap 757 2053"   # n
```
