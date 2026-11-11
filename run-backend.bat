@echo off
chcp 65001 >nul
set JAVA_HOME=D:\Java\jdk8
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d "%~dp0"
call mvn install -pl bigScreen-api -am -DskipTests
cd /d "%~dp0\bigScreen-server"
call mvn clean spring-boot:run -Dserver.port=9090 -Dspring.profiles.active=dev
