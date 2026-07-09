@echo off
echo ============================================================
echo   Smart TV Media Player Server
echo   Starting...
echo ============================================================
echo.

cd /d "%~dp0server"

:: Try to install python dependencies offline if wheels folder is present
if exist "%~dp0server\wheels" (
    echo Installing dependencies from local offline cache...
    pip install --no-index --find-links="%~dp0server\wheels" -r requirements.txt --quiet 2>nul
    if %ERRORLEVEL% equ 0 (
        echo [OK] Dependencies installed offline successfully.
        goto :start_app
    )
    echo [WARNING] Offline installation failed, attempting online installation...
)

echo Installing dependencies from online repository...
pip install -r requirements.txt --quiet 2>nul

:start_app
echo Starting Flask application...
python app.py

pause
