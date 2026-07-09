@echo off
echo ============================================================
echo   Smart TV Media Player - APK Build
echo ============================================================
echo.

set JAVA_HOME=%~dp0jdk17\jdk-17.0.12
set ANDROID_SDK_ROOT=%~dp0android-sdk
set GRADLE_USER_HOME=%~dp0gradle-cache

:: Dynamically generate local.properties for the portable SDK
set "SDK_PATH=%~dp0android-sdk"
set "SDK_PATH=%SDK_PATH:\=/%"
echo sdk.dir=%SDK_PATH%> "%~dp0android\local.properties"

cd /d "%~dp0android"
"%~dp0gradle-temp\gradle-7.5.1\bin\gradle.bat" assembleDebug --no-daemon

echo.
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    copy /Y "app\build\outputs\apk\debug\app-debug.apk" "%~dp0SmartTVMediaPlayer.apk"
    echo [OK] APK built: SmartTVMediaPlayer.apk
) else (
    echo [ERROR] Build failed!
)

