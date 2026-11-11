@echo off

echo        ---------- set Node JS environment ---------- & echo.

set node_home=C:\Users\28970\AppData\Local\nvm\v16.20.2
set path=%node_home%;%node_home%\node_modules;%path%
set node_path=%node_home%\node_modules

rem hui CLI 内部会调用 npm 安装 @hui/bundler 等私有包，必须指向内网私服
set npm_config_registry=http://172.24.2.228:8081/repository/npm-public/
