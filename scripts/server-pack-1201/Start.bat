@echo off
chcp 65001 >nul
call "%~dp0Start-Server.bat" %*
exit /b %ERRORLEVEL%
