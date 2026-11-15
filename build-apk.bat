@echo off
REM ---------------------------------------------------------------------------
REM friendbook - local signed release APK builder (Windows). No GitHub Actions.
REM Output: friendbook-<version>.apk
REM ---------------------------------------------------------------------------
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo == friendbook local build ==

where java >nul 2>&1
if errorlevel 1 (
  echo [X] Java not found.
  echo     Install Android Studio ^(it bundles JDK 17^): https://developer.android.com/studio
  exit /b 1
)
for /f "tokens=2 delims= " %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JVER=%%v
echo [OK] Java %JVER%

REM --- Android SDK ---
set SDK=%ANDROID_HOME%
if "%SDK%"=="" set SDK=%ANDROID_SDK_ROOT%
if "%SDK%"=="" if exist "%LOCALAPPDATA%\Android\Sdk" set SDK=%LOCALAPPDATA%\Android\Sdk%
if "%SDK%"=="" (
  echo [X] Android SDK not found.
  echo     Open Android Studio -^> SDK Manager and install "Android 16 ^(API 36^)".
  exit /b 1
)
echo [OK] SDK %SDK%
set ANDROID_HOME=%SDK%
set ANDROID_SDK_ROOT=%SDK%
echo sdk.dir=%SDK:\=\\%> local.properties

if not exist "%SDK%\platforms\android-36" (
  echo [!] platform android-36 missing - install it in Android Studio SDK Manager.
  exit /b 1
)

if not exist "app\friendbook-release.p12" (
  echo [X] app\friendbook-release.p12 missing - APK would be unsigned.
  exit /b 1
)
echo [OK] keystore present

for /f "tokens=2 delims= " %%c in ('findstr /r "^ *versionCode " app\build.gradle') do set VCODE=%%c
for /f "tokens=2 delims='" %%n in ('findstr /r "^ *versionName " app\build.gradle') do set VNAME=%%n
echo.
echo Building version %VNAME% (%VCODE%) - first run downloads Gradle, please wait...
echo.

call gradlew.bat :app:assembleRelease --no-daemon --stacktrace
if errorlevel 1 (
  echo.
  echo [X] Build failed - scroll up for the first "error:" line.
  exit /b 1
)

set APK=app\build\outputs\apk\release\app-release.apk
if not exist "%APK%" (
  echo [X] Build finished but %APK% not found.
  exit /b 1
)
copy /y "%APK%" "friendbook-%VNAME%.apk" >nul
echo.
echo [OK] APK ready: %CD%\friendbook-%VNAME%.apk
echo.
echo Copy it to your phone, tap it, choose Update/Install.
echo Do NOT uninstall the old app first - it updates in place and keeps your data.
endlocal
