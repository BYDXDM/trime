#!/usr/bin/env bash
# 重新构建 Android 版 build_predict 并在模拟器上生成 predict.db。
#
# 为什么需要这个脚本：
#   1) librime-predict 的源码被 CMake 通过 junction 挂到 librime/plugins/ 下，
#      ninja 的时间戳比对在这种挂载上不可靠，改完源码常常不重编，极易出现
#      「改了却没生效」的假象。这里强制删除 predict 相关 .o 再编。
#   2) 生成的 predict.db 必须由 Android 版工具产出（宿主没有对应 C++ 工具链）。
set -euo pipefail

REPO="D:/ai/工作/trime-build"
BUILD="D:/ai/工作/trime-build"
CXX_DIR="$BUILD/app/.cxx/Debug/5d1v1b3n/x86_64"
NINJA="D:/Android/Sdk/cmake/3.31.6/bin/ninja.exe"
ADB="D:/Android/Sdk/platform-tools/adb.exe"
OBJ_DIR="$CXX_DIR/librime/plugins/librime-predict/CMakeFiles/rime-predict-objs.dir/src"
BIN="$BUILD/app/build/intermediates/cxx/Debug/5d1v1b3n/obj/x86_64/build_predict"

export MSYS_NO_PATHCONV=1
export ANDROID_SDK_ROOT=D:/Android/Sdk

echo "==> 1/5 强制清理 predict 对象文件（避免 stale object）"
rm -f "$OBJ_DIR"/*.o "$OBJ_DIR"/*.o.d

echo "==> 2/5 编译 build_predict"
"$NINJA" -C "$CXX_DIR" -j8 build_predict

echo "==> 3/5 确认二进制确实包含本次改动"
STRINGS="D:/Android/Sdk/ndk/28.0.13004108/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strings.exe"
if ! "$STRINGS" -a "$BIN" | grep -q "Rime::Predict/2.0"; then
  echo "!! 二进制里找不到 v2 格式串，构建可能没生效" >&2
  exit 1
fi

echo "==> 4/5 推送并在设备上生成 predict.db"
"$ADB" push "$BIN" /data/local/tmp/build_predict >/dev/null
"$ADB" push "$REPO/app/data/rime/myvocab/predict.txt" /data/local/tmp/predict.txt >/dev/null
"$ADB" shell "cd /data/local/tmp && rm -f predict.db && chmod 755 build_predict && ./build_predict predict.db < predict.txt" || true

echo "==> 5/5 取回并校验格式"
"$ADB" pull /data/local/tmp/predict.db "$REPO/app/data/rime/myvocab/predict.db" >/dev/null
python - "$REPO/app/data/rime/myvocab/predict.db" <<'PY'
import sys
d = open(sys.argv[1], 'rb').read()
fmt = d[:32].split(b'\0')[0].decode()
print(f"    size   = {len(d)}")
print(f"    format = {fmt!r}")
if not d[:32].startswith(b'Rime::Predict/2.0'):
    sys.exit("!! 不是 v2 格式，联想重排不会生效")
print("    OK")
PY
