@echo off
chcp 65001 >nul
set "TARGET=D:\ai\工作\trime-build\app\src\main\jni\librime\plugins\librime-predict"
set "SOURCE=D:\ai\工作\trime-build\app\src\main\jni\librime-predict"
if exist "%TARGET%" rmdir /s /q "%TARGET%"
mklink /J "%TARGET%" "%SOURCE%"
if errorlevel 1 (
  echo RELINK_FAILED
  exit /b 1
)
echo RELINK_OK
dir /b "%TARGET%\src"
