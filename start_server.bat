@echo off
echo ============================================================
echo   Smart TV Media Player Server
echo   Starting...
echo ============================================================
echo.

cd /d "%~dp0server"
pip install -r requirements.txt --quiet 2>nul
python app.py

pause
