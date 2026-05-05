@echo off
setlocal

set "PROJECT_DIR=%~dp0"

if exist "%PROJECT_DIR%.env" (
  echo Please use start-builder.sh on Linux/VPS for .env loading.
)

if "%ANDROID_SDK_ROOT%"=="" if not "%ANDROID_HOME%"=="" set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if "%ANDROID_SDK_ROOT%"=="" set "ANDROID_SDK_ROOT=%USERPROFILE%\android-sdk"
if "%ANDROID_HOME%"=="" set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if "%BUILDER_TEMPLATE_DIR%"=="" set "BUILDER_TEMPLATE_DIR=%PROJECT_DIR%template"
if "%BUILDER_DATA_DIR%"=="" set "BUILDER_DATA_DIR=%PROJECT_DIR%builder-data"
if "%PORT%"=="" set "PORT=8080"
if "%BUILDER_PORT%"=="" set "BUILDER_PORT=%PORT%"
set "PATH=%ANDROID_SDK_ROOT%\cmdline-tools\latest\bin;%ANDROID_SDK_ROOT%\platform-tools;%PATH%"

if not exist "%ANDROID_SDK_ROOT%" (
  echo Android SDK not found at: %ANDROID_SDK_ROOT%
  echo Set ANDROID_SDK_ROOT before running this script.
  exit /b 1
)

cd /d "%PROJECT_DIR%"
call gradlew.bat installDist || exit /b 1
call build\install\buildersrc\bin\buildersrc.bat
