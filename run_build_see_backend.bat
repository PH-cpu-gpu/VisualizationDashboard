@echo off
chcp 65001 >nul
echo Building bigScreen backend...
set JAVA_HOME=D:\My\MyDownload\ProgrammingRelated\JDKList\jdk1.8\JDK8-64
set PATH=%JAVA_HOME%\bin;%PATH%

:: Auto increment version in deploy.xml
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bump-version-backend.ps1"

call mvn package -f pom.xml
pause
