# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

find_package(Opencc REQUIRED)

aux_source_directory(. RIME_JNI_SOURCES)
add_library(rime_jni SHARED ${RIME_JNI_SOURCES})
target_link_libraries(rime_jni rime-static ${Opencc_LIBRARY})
target_include_directories(rime_jni PRIVATE
  "${CMAKE_BINARY_DIR}/librime/src"
  "${CMAKE_SOURCE_DIR}/librime/src"
  # predict 插件的头文件：JNI 层要在设备上调用 BuildPredictDbFromText
  # 生成联想词库（见 rime_jni.cc 的 buildPredictDb）
  "${CMAKE_SOURCE_DIR}/librime-predict/src"
  "${Opencc_INCLUDE_PATH}"
)
