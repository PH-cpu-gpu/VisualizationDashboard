@echo off
chcp 65001 >nul
echo Building bigScreen frontend...

:: Auto increment version in package.json
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bump-version-frontend.ps1"

cd /d "%~dp0bigScreen-web"
call set_env.bat
hui build --filename-hashing --see

pause
