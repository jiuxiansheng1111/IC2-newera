@echo off
chcp 65001 >nul
setlocal
pushd "%~dp0"

set "JAVA_EXE="
if defined JAVA17_HOME if exist "%JAVA17_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA17_HOME%\bin\java.exe"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for %%J in (java.exe) do set "JAVA_EXE=%%~$PATH:J"

if not defined JAVA_EXE (
  echo 未找到 Java。请安装 64 位 Java 17，并设置 JAVA17_HOME 或 JAVA_HOME。
  echo Java was not found. Install 64-bit Java 17 and set JAVA17_HOME or JAVA_HOME.
  popd
  exit /b 1
)

set "JAVA_VERSION="
for /f "tokens=3" %%V in ('""%JAVA_EXE%" -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.specification.version""') do set "JAVA_VERSION=%%V"
if not "%JAVA_VERSION%"=="17" (
  echo 检测到 Java 版本 "%JAVA_VERSION%"，本服务端严格要求 Java 17。
  echo Detected Java version "%JAVA_VERSION%". This server requires Java 17 exactly.
  popd
  exit /b 1
)

if not exist "eula.txt" (
  echo 尚未找到 eula.txt。请阅读并自行同意 Minecraft EULA 后，在文件中设置 eula=true：
  echo https://www.minecraft.net/eula
  echo eula.txt was not found. Read and accept the Minecraft EULA yourself, then set eula=true:
  echo https://www.minecraft.net/eula
  popd
  exit /b 1
)
findstr /i /r /c:"^eula=true$" "eula.txt" >nul
if errorlevel 1 (
  echo EULA 尚未由服主明确同意。请阅读 https://www.minecraft.net/eula ，然后自行编辑 eula.txt 设置 eula=true。
  echo The EULA has not been accepted by the server owner. Read https://www.minecraft.net/eula and edit eula.txt yourself to set eula=true.
  popd
  exit /b 1
)

set "FORGE_ARGS=libraries\net\minecraftforge\forge\1.20.1-47.4.28\win_args.txt"
if not exist "%FORGE_ARGS%" (
  echo 缺少 Forge 官方启动参数文件：%FORGE_ARGS%
  echo Missing Forge official argument file: %FORGE_ARGS%
  echo 请先在此独立服务端目录完成 Forge 1.20.1-47.4.28 服务端安装。
  echo Install the Forge 1.20.1-47.4.28 server into this standalone directory first.
  popd
  exit /b 1
)

"%JAVA_EXE%" @user_jvm_args.txt @"%FORGE_ARGS%" nogui
set "SERVER_EXIT=%ERRORLEVEL%"
popd
exit /b %SERVER_EXIT%
