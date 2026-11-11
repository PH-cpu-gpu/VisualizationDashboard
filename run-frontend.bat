@echo off
chcp 65001 >nul
cd /d "%~dp0\bigScreen-web"
call set_env.bat
call npm run dev
