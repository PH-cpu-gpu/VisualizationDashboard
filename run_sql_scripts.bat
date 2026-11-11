@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM  SQL脚本批量执行工具 (Oracle 11g 兼容)
REM 连接信息: 127.0.0.1:1521:XE
REM 用户名 = bigScreen, 密码 = 123
REM ============================================================

set DB_HOST=127.0.0.1
set DB_PORT=1521
set DB_SERVICE=XE
set SQL_BASE_DIR=%~dp0_sql

REM ---------- 自动检测 ORACLE_HOME ----------
REM 优先级: 环境变量 > 注册表 > PATH中查找 > 全盘搜索

if defined ORACLE_HOME (
    if exist "%ORACLE_HOME%\bin\sqlplus.exe" goto :found_oracle_home
    echo [警告] ORACLE_HOME 已设置但路径无效: %ORACLE_HOME%
    set "ORACLE_HOME="
)

REM 1. 从注册表获取（兼容32/64位）
for /f "tokens=2*" %%a in ('reg query "HKLM\SOFTWARE\Oracle" /v ORACLE_HOME 2^>nul ^| findstr REG_SZ') do set "ORACLE_HOME=%%b"
if defined ORACLE_HOME if exist "%ORACLE_HOME%\bin\sqlplus.exe" goto :found_oracle_home
set "ORACLE_HOME="

for /f "tokens=2*" %%a in ('reg query "HKLM\SOFTWARE\WOW6432Node\Oracle" /v ORACLE_HOME 2^>nul ^| findstr REG_SZ') do set "ORACLE_HOME=%%b"
if defined ORACLE_HOME if exist "%ORACLE_HOME%\bin\sqlplus.exe" goto :found_oracle_home
set "ORACLE_HOME="

REM 2. 从 PATH 中查找 sqlplus
for /f "delims=" %%p in ('where sqlplus 2^>nul') do (
    for %%q in ("%%~dpp..") do set "ORACLE_HOME=%%~fq"
)
if defined ORACLE_HOME if exist "%ORACLE_HOME%\bin\sqlplus.exe" goto :found_oracle_home
set "ORACLE_HOME="

REM 3. 全盘搜索 sqlplus.exe（遍历所有盘符）
echo [信息] 正在搜索 Oracle 客户端安装路径，请稍候...
for /f "skip=1" %%d in ('wmic logicaldisk get name 2^>nul ^| findstr ":"') do (
    if not defined ORACLE_HOME (
        for /f "delims=" %%p in ('dir /s /b "%%d\sqlplus.exe" 2^>nul') do (
            if not defined ORACLE_HOME (
                for %%q in ("%%~dpp..") do set "ORACLE_HOME=%%~fq"
            )
        )
    )
)
if defined ORACLE_HOME if exist "%ORACLE_HOME%\bin\sqlplus.exe" goto :found_oracle_home
set "ORACLE_HOME="

echo [错误] 未找到 Oracle 客户端，请确认已安装。
echo        或手动设置后重新运行:
echo        set ORACLE_HOME=你的Oracle客户端安装路径
pause
exit /b 1

:found_oracle_home
echo [信息] ORACLE_HOME = %ORACLE_HOME%

REM 确保 sqlplus 在 PATH 中
set "PATH=%ORACLE_HOME%\bin;%PATH%"

REM 设置 NLS_LANG 避免中文乱码
set "NLS_LANG=SIMPLIFIED CHINESE_CHINA.ZHS16GBK"

REM ---------- 密码 ----------
set "DB_USER=bigScreen"
set "DB_PWD=123"

REM ---------- 临时SQL文件 ----------
set "TMP_SQL=%TEMP%\run_sql_exit.sql"

REM ---------- 统计 ----------
set TOTAL_OK=0
set TOTAL_FAIL=0

echo ============================================================
echo  SQL脚本批量提交工具 (Oracle 11g)
echo  目标数据库: %DB_HOST%:%DB_PORT%/%DB_SERVICE%
echo  用户: %DB_USER%
echo  SQL目录  : %SQL_BASE_DIR%
echo ============================================================
echo.

REM ---------- 按依赖顺序执行顶层初始化脚本 ----------
REM 说明: 仅执行下面列出的脚本（建表 -> 指标定义 -> 基线/示例数据）
REM       migration\ 为存量库升级脚本、dev\ 为调试脚本，均不参与批量执行
set "SQL_FILES=monitor_tables.sql alert_tables.sql init_nginx_metrics.sql init_tomcat_metrics.sql init_node_layout.sql insert_sample_rules.sql"

for %%N in (%SQL_FILES%) do (
    set "SQL_NAME=%%N"
    set "SQL_FILE=%SQL_BASE_DIR%\%%N"
    set "LOG_FILE=%SQL_BASE_DIR%\!SQL_NAME!.log"
    if not exist "!SQL_FILE!" (
        echo.
        echo   [跳过] !SQL_NAME!  文件不存在
    ) else (
        echo.
        echo   [执行] !SQL_NAME! ...

        REM 生成临时SQL：先执行脚本，再退出
        echo @!SQL_FILE! > "!TMP_SQL!"
        echo exit; >> "!TMP_SQL!"

        REM 使用 sqlplus 执行
        "%ORACLE_HOME%\bin\sqlplus.exe" -S "%DB_USER%/%DB_PWD%@%DB_HOST%:%DB_PORT%/%DB_SERVICE%" @"!TMP_SQL!" > "!LOG_FILE!" 2>&1

        REM 检查日志中是否包含 ORA- 错误
        findstr /C:"ORA-" "!LOG_FILE!" >nul 2>&1
        if errorlevel 1 (
            echo   [成功] !SQL_NAME!
            set /a TOTAL_OK+=1
        ) else (
            echo   [失败] !SQL_NAME!  请查看日志: !LOG_FILE!
            set /a TOTAL_FAIL+=1
        )
    )
)

REM 清理临时文件
if exist "!TMP_SQL!" del "!TMP_SQL!"

echo ============================================================
echo  执行完毕  成功: %TOTAL_OK%  失败: %TOTAL_FAIL%
echo ============================================================
echo.
pause
