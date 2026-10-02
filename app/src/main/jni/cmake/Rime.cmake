# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

# if you want to add some new plugins, add them to librime_jni/rime_jni.cc too
set(RIME_PLUGINS librime-lua librime-octagram librime-predict)

# Link a plugin directory into librime/plugins/<name>, and make sure the result
# is actually usable.
#
# Background: the previous code was just
#     if(NOT EXISTS <dst>) file(CREATE_LINK <src> <dst> COPY_ON_ERROR SYMBOLIC)
# which breaks two ways:
#   1. On Windows, creating a real symlink needs Developer Mode/admin. Without
#      it CMake either fails or `COPY_ON_ERROR` degrades to a real directory
#      COPY (stale code, and a full duplicate of the tree).
#   2. The `if(NOT EXISTS ...)` guard then sees that directory - empty or stale -
#      and never repairs it. `add_subdirectory()` reports "does not contain a
#      CMakeLists.txt file", the plugin targets (rime-lua-objs, ...) are never
#      created, and the `target_compile_options` calls at the bottom of this file
#      abort the whole configure.
#
# So this helper verifies the result and falls back in order:
#   symlink  ->  Windows junction (works without Developer Mode)  ->  verified copy
# and fails loudly only if none of the three produced a usable directory.
function(rime_link_plugin name src_rel sentinel)
  set(_dst "${CMAKE_SOURCE_DIR}/librime/plugins/${name}")
  set(_src "${CMAKE_SOURCE_DIR}/${src_rel}")

  if(NOT IS_DIRECTORY "${_src}")
    message(FATAL_ERROR
      "Plugin source '${_src}' is missing. "
      "Run: git submodule update --init --recursive")
  endif()

  # Healthy already? (covers junctions, symlinks and plain dirs alike)
  if(EXISTS "${_dst}/${sentinel}")
    return()
  endif()

  # Anything else at _dst is a broken leftover: empty dir, stale copy, dead link.
  if(EXISTS "${_dst}")
    message(STATUS "librime: replacing broken plugin link ${_dst}")
    file(REMOVE_RECURSE "${_dst}")
  endif()

  # A nested target such as librime-lua/thirdparty needs its parent directory to
  # exist first - both for mklink and for the copy fallback.
  get_filename_component(_dst_parent "${_dst}" DIRECTORY)
  file(MAKE_DIRECTORY "${_dst_parent}")

  # Remove leftovers from any previous failed attempt (a staged copy, or a
  # half-created link), so a retry starts clean.
  get_filename_component(_dst_base "${_dst}" NAME)
  file(REMOVE_RECURSE "${_dst_parent}/.stage-${_dst_base}")

  # 1) native symlink. NOTE: on Windows without Developer Mode this is a FATAL
  #    CMake error ("client does not hold the required privilege"), not a soft
  #    failure, and it aborts the whole script before we can fall back. Run it in
  #    a sub-script so the failure is contained.
  file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/rime_link_try.cmake"
       "file(CREATE_LINK \"${_src}\" \"${_dst}\" SYMBOLIC)\n")
  execute_process(
    COMMAND "${CMAKE_COMMAND}" -P "${CMAKE_CURRENT_BINARY_DIR}/rime_link_try.cmake"
    OUTPUT_QUIET ERROR_QUIET RESULT_VARIABLE _symlink_result)
  if(_symlink_result EQUAL 0 AND EXISTS "${_dst}/${sentinel}")
    return()
  endif()
  if(EXISTS "${_dst}")
    file(REMOVE_RECURSE "${_dst}")
  endif()

  # 2) Windows junction - a directory reparse point that CMake traverses and that
  #    does NOT require Developer Mode or elevation. Normal path on a Windows
  #    host, and it correctly handles sources that themselves contain junctions
  #    (e.g. librime-lua/thirdparty), which file(COPY) cannot duplicate.
  #    Gate on CMAKE_HOST_WIN32, not WIN32: the latter describes the Android
  #    target during a cross-compile.
  if(CMAKE_HOST_WIN32)
    if(EXISTS "${_dst}")
      file(REMOVE_RECURSE "${_dst}")
    endif()
    file(TO_NATIVE_PATH "${_src}" _src_native)
    file(TO_NATIVE_PATH "${_dst}" _dst_native)
    execute_process(
      COMMAND cmd /c mklink /J "${_dst_native}" "${_src_native}"
      OUTPUT_VARIABLE _mklink_out ERROR_VARIABLE _mklink_err RESULT_VARIABLE _mklink_result)
    if(_mklink_result EQUAL 0 AND EXISTS "${_dst}/${sentinel}")
      return()
    endif()
    if(_mklink_result EQUAL 0)
      message(STATUS "librime: junction created for ${name} but '${sentinel}' is unreadable")
    else()
      string(STRIP "${_mklink_err}" _mklink_msg)
      message(STATUS "librime: mklink /J failed for ${name}: ${_mklink_msg}")
    endif()
    if(EXISTS "${_dst}")
      file(REMOVE_RECURSE "${_dst}")
    endif()
  endif()

  # 3) Last resort: a real directory copy. `file(COPY)` refuses to duplicate
  #    symlinks/junctions ("cannot duplicate symlink"), and plugin sources such as
  #    librime-lua contain one (thirdparty). So copy via a shell that follows
  #    links, and verify the sentinel afterwards.
  if(EXISTS "${_dst}")
    file(REMOVE_RECURSE "${_dst}")
  endif()
  set(_stage "${_dst_parent}/.stage-${_dst_base}")
  file(REMOVE_RECURSE "${_stage}")
  # IMPORTANT: this is a cross-compile. `WIN32` describes the TARGET (Android,
  # via the NDK toolchain), NOT the host running CMake. Creating junctions is a
  # host capability, so detect the host explicitly.
  if(CMAKE_HOST_WIN32)
    file(TO_NATIVE_PATH "${_src}" _src_native)
    file(TO_NATIVE_PATH "${_stage}" _stage_native)
    # /E all subdirs incl. empty, /B bare, /XJ exclude junction points so we do
    # not recurse into (or fail on) them.
    execute_process(
      COMMAND robocopy "${_src_native}" "${_stage_native}" /E /B /XJ /NFL /NDL /NJH /NJS /NP
      OUTPUT_QUIET ERROR_QUIET RESULT_VARIABLE _robocopy_result)
    # robocopy uses exit codes < 8 for success
    if(_robocopy_result LESS 8 AND EXISTS "${_stage}/${sentinel}")
      file(RENAME "${_stage}" "${_dst}")
    endif()
  else()
    file(COPY "${_src}/" DESTINATION "${_stage}")
    if(EXISTS "${_stage}/${sentinel}")
      file(RENAME "${_stage}" "${_dst}")
    endif()
  endif()
  if(EXISTS "${_dst}/${sentinel}")
    message(STATUS
      "librime: ${name} linked by directory copy (symlink and junction unavailable on this host)")
    return()
  endif()
  file(REMOVE_RECURSE "${_stage}")

  message(FATAL_ERROR
    "Unable to link plugin '${name}'.\n"
    "  from: ${_src}\n"
    "  to:   ${_dst}\n"
    "Tried symlink, Windows junction (mklink /J) and directory copy; none made\n"
    "'${sentinel}' readable through the link. Check that the submodule is present:\n"
    "  git submodule update --init --recursive")
endfunction()

foreach(plugin ${RIME_PLUGINS})
  rime_link_plugin("${plugin}" "${plugin}" CMakeLists.txt)
endforeach()

# librime-lua bundles its Lua dependency tree from librime-lua-deps.
rime_link_plugin("librime-lua/thirdparty" "librime-lua-deps" "lua5.4/lua.h")

option(BUILD_TEST "" OFF)
option(BUILD_STATIC "" ON)
add_subdirectory(librime)
target_compile_options(
  rime-static PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=." "-Wno-error=deprecated-declarations")

target_compile_options(
  rime-lua-objs PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

target_compile_options(
  rime-octagram-objs PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")
