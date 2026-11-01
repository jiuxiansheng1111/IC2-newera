#!/usr/bin/env bash
set -eu
cd -- "$(dirname -- "$(readlink -f -- "$0")")"

if [[ -n "${JAVA17_HOME:-}" && -x "$JAVA17_HOME/bin/java" ]]; then
  JAVA_BIN="$JAVA17_HOME/bin/java"
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
else
  echo '未找到 Java。请安装 Java 17，并设置 JAVA17_HOME 或 JAVA_HOME。' >&2
  echo 'Java was not found. Install Java 17 and set JAVA17_HOME or JAVA_HOME.' >&2
  exit 1
fi

JAVA_VERSION="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2; exit}')"
if [[ "$JAVA_VERSION" != "17" ]]; then
  echo "检测到 Java 版本 '${JAVA_VERSION:-未知}'，本服务端严格要求 Java 17。" >&2
  echo "Detected Java version '${JAVA_VERSION:-unknown}'. This server requires Java 17 exactly." >&2
  exit 1
fi

if [[ ! -f eula.txt ]]; then
  echo '尚未找到 eula.txt。请阅读并自行同意 Minecraft EULA 后，在文件中设置 eula=true：' >&2
  echo 'https://www.minecraft.net/eula' >&2
  echo 'eula.txt was not found. Read and accept the Minecraft EULA yourself, then set eula=true:' >&2
  echo 'https://www.minecraft.net/eula' >&2
  exit 1
fi
if ! grep -Eiq '^[[:space:]]*eula=true[[:space:]]*$' eula.txt; then
  echo 'EULA 尚未由服主明确同意。请阅读 https://www.minecraft.net/eula ，然后自行编辑 eula.txt 设置 eula=true。' >&2
  echo 'The EULA has not been accepted by the server owner. Read https://www.minecraft.net/eula and edit eula.txt yourself to set eula=true.' >&2
  exit 1
fi

FORGE_ARGS='libraries/net/minecraftforge/forge/1.20.1-47.4.28/unix_args.txt'
if [[ ! -f "$FORGE_ARGS" ]]; then
  echo "缺少 Forge 官方启动参数文件：$FORGE_ARGS" >&2
  echo "Missing Forge official argument file: $FORGE_ARGS" >&2
  echo '请先在此独立服务端目录完成 Forge 1.20.1-47.4.28 服务端安装。' >&2
  echo 'Install the Forge 1.20.1-47.4.28 server into this standalone directory first.' >&2
  exit 1
fi

exec "$JAVA_BIN" @user_jvm_args.txt "@$FORGE_ARGS" nogui "$@"
