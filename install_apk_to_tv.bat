@echo off
setlocal enabledelayedexpansion

echo ============================================================
echo   Smart TV Media Player - Install APK to TV via ADB
echo ============================================================
echo.

:: Ask for the TV's IP address
set /p TV_IP="Enter your Android TV IP address (e.g. 192.168.1.100): "

if "%TV_IP%"=="" (
    echo Error: TV IP cannot be empty.
    pause
    exit /b
)

:: Define paths
set ADB="%~dp0android-sdk\platform-tools\adb.exe"
set APK="%~dp0SmartTVMediaPlayer.apk"

if not exist %ADB% (
    echo Error: adb.exe not found at %ADB%
    pause
    exit /b
)

if not exist %APK% (
    echo Error: SmartTVMediaPlayer.apk not found at %APK%
    pause
    exit /b
)

echo.
echo 1. Please ensure developer options and ADB debugging are enabled on your TV.
echo 2. Make sure the TV is connected to the same Wi-Fi network.
echo.
echo Connecting to %TV_IP%...
%ADB% disconnect >nul 2>&1
%ADB% connect %TV_IP%

echo.
echo Please look at your TV screen - it might ask to allow USB/ADB debugging.
echo Select "Always allow from this computer" and click OK.
echo.
pause

echo.
echo Checking connection status...
%ADB% devices

echo.
echo Installing APK to %TV_IP%...
%ADB% -s %TV_IP%:5555 install -r %APK%

if %ERRORLEVEL% equ 0 (
    echo.
    echo ============================================================
    echo   [OK] Installation successful!
    echo   You can now launch "Smart TV Media Player" on your TV.
    echo ============================================================
) else (
    echo.
    echo ============================================================
    echo   [ERROR] Installation failed.
    echo   Make sure ADB debugging is enabled on the TV and try again.
    echo ============================================================
)

echo.
pause
