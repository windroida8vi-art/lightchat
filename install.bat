@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
echo ==== LightChat: check tools, install what is missing, build the APK ====
echo.

rem ---------- 1) Java: Android Studio's bundled JDK is enough ----------
if not defined JAVA_HOME (
  for %%D in ("%ProgramFiles%\Android\Android Studio\jbr" "%LOCALAPPDATA%\Programs\Android Studio\jbr") do (
    if exist "%%~D\bin\java.exe" set "JAVA_HOME=%%~D"
  )
)
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
where java >nul 2>nul
if errorlevel 1 (
  echo [!] No JDK found. Installing JDK 17 with winget...
  winget install -e --id EclipseAdoptium.Temurin.17.JDK --accept-source-agreements --accept-package-agreements
  echo.
  echo Close this window, open a NEW terminal and run install.bat again.
  pause
  exit /b 1
)
java -version
echo.

rem ---------- 2) Android SDK ----------
if not defined ANDROID_HOME if defined ANDROID_SDK_ROOT set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_HOME (
  echo [!] Android SDK not found.
  echo     Install Android Studio, open it once and let it download the SDK, then run this again.
  echo     Installing Android Studio with winget:  winget install -e --id Google.AndroidStudio
  pause
  exit /b 1
)
echo Android SDK: %ANDROID_HOME%
>local.properties echo sdk.dir=%ANDROID_HOME:\=\\%

rem ---------- 3) SDK packages: platform 34 + build-tools ----------
set "SDKM="
if exist "%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat" set "SDKM=%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat"
if defined SDKM (
  echo Accepting licenses and installing platform 34, build-tools and platform-tools ...
  (for /l %%i in (1,1,30) do @echo y) | call "%SDKM%" --licenses >nul
  call "%SDKM%" "platforms;android-34" "build-tools;34.0.0" "platform-tools"
) else (
  echo [i] sdkmanager not found. In Android Studio: Settings - Android SDK - SDK Tools - Android SDK Command-line Tools.
  echo     Gradle will still try to download the missing SDK parts by itself.
)
echo.

rem ---------- 4) Build ----------
echo Building the APK. The first run downloads Gradle and libraries, so it needs internet.
echo If it fails with "Could not resolve ..." turn on a VPN and run this file again.
echo.
call gradlew.bat assembleDebug --refresh-dependencies
if errorlevel 1 (
  echo.
  echo [X] Build failed. Read the error above. Network problems: use a VPN.
  pause
  exit /b 1
)
echo.
echo [OK] APK: %~dp0app\build\outputs\apk\debug\app-debug.apk
echo.

rem ---------- 5) Optional: install on a connected phone ----------
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if exist "%ADB%" (
  "%ADB%" devices
  set "ANS="
  set /p ANS=Install on the connected phone now? [y/n] : 
  if /i "!ANS!"=="y" "%ADB%" install -r "app\build\outputs\apk\debug\app-debug.apk"
)
echo.
pause
