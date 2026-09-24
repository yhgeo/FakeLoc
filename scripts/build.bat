@echo off
REM ===========================================================================
REM  FakeLoc 一键构建（Windows / cmd）
REM
REM  设计要点：源码与工具链解耦 —— 本脚本不假设任何路径，
REM  所有编译期依赖都指向 D:\Android\toolchain，源码可以放在任意位置。
REM
REM  用法:
REM     scripts\build.bat                 构建 release（默认）
REM     scripts\build.bat debug           构建 debug
REM     scripts\build.bat release clean   先 clean 再构建
REM ===========================================================================
setlocal enabledelayedexpansion

REM ---- 项目根目录 = 本脚本所在目录的上一级 ----
set "PROJ=%~dp0.."
for %%I in ("%PROJ%") do set "PROJ=%%~fI"

REM ---- 工具链位置（按需修改这三行即可迁移到别的机器）----
set "JAVA_HOME=D:\Java\Java21"
set "GRADLE_USER_HOME=D:\Android\toolchain\gradle-home"
set "ANDROID_SDK_ROOT=D:\Android\toolchain\sdk"
set "ANDROID_HOME=D:\Android\toolchain\sdk"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [FakeLoc] 错误: 找不到 JDK: %JAVA_HOME%
  exit /b 1
)
if not exist "%ANDROID_SDK_ROOT%\platforms" (
  echo [FakeLoc] 错误: 找不到 Android SDK: %ANDROID_SDK_ROOT%
  exit /b 1
)

cd /d "%PROJ%" || exit /b 1

REM ---- 解析参数：variant / 额外任务 ----
set "VARIANT=release"
set "EXTRA="
for %%A in (%*) do (
  if /I "%%A"=="debug"   set "VARIANT=debug"
  if /I "%%A"=="release" set "VARIANT=release"
  if /I not "%%A"=="debug" if /I not "%%A"=="release" set "EXTRA=!EXTRA! %%A"
)

echo ============================================================
echo  FakeLoc 构建
echo   PROJECT          = %CD%
echo   JAVA_HOME        = %JAVA_HOME%
echo   ANDROID_SDK_ROOT = %ANDROID_SDK_ROOT%
echo   GRADLE_USER_HOME = %GRADLE_USER_HOME%
echo   VARIANT          = %VARIANT%
echo ============================================================

if not "!EXTRA!"=="" (
  call gradlew.bat !EXTRA!
  if errorlevel 1 exit /b 1
)

call gradlew.bat :app:assemble%VARIANT% --stacktrace
if errorlevel 1 (
  echo [FakeLoc] 构建失败
  exit /b 1
)

echo.
echo ============================================================
echo  构建成功，产物：
dir /b /s "app\build\outputs\apk\%VARIANT%\*.apk"
echo ============================================================

endlocal
