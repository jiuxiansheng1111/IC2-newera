# 完整服务端补充交付：r48-Full

2026-10-28。用户要求根目录可见的 JAR、Start 脚本和完整服务器包；随后确认服务器已有 Java 17，Java 作为可选安装项。

## 已交付内容

- `dist/IC2-New-Era-Server-1.20.1-r48-Full.zip`：常规 ZIP 完整包。
- `dist/IC2-New-Era-Server-1.20.1-r48-Full.tar.gz`：同内容 Linux 归档。
- `dist/IC2-New-Era-Server-1.20.1-r48-Full/`：已解压的完整目录，可直接查看或上传。
- 主包不内置 JDK/JRE，使用服务器已有 Java 17；仅新增 `JAVA17-OPTIONAL.txt` 供换机缺 Java 时参考官方安装入口。
- 原 r48 客户端继续配套，本次不修改其模组、手机、宠物或音频。旧服务器归档保留于私有构建检查点，dist 交付入口改为 Full。

## 目录与启动

根目录含 `server.jar`、`Start.sh`、`Start.bat`、平台启动脚本、`server.properties`、`eula.txt`、`user_jvm_args.txt`、面板说明和 manifest。`libraries/` 已含官方 Forge 47.4.28 与 Minecraft 1.20.1 核心，`mods/` 有 25 个锁定的服务端模组；同时保留 ProjectE 受控 EMC 配置、Ultimine 默认64方块配置和示例图纸。不需要再下载 Forge 核心或模组。

推荐 Linux 运行 `chmod +x Start.sh start-server.sh` 后执行 `./Start.sh`，直接由一个 Java 进程启动 Forge。Windows 使用 `Start.bat`。服主自行决定接受 Minecraft EULA 后在 `eula.txt` 设置 `eula=true`；归档默认 false，未代为同意。

根目录 `server.jar` 为本项目轻量启动入口，真实 Forge 核心在 `libraries/`。仅支持 JAR 的面板使用 `java -Xms32M -Xmx128M -jar server.jar`，实际 Forge 子进程堆从 `user_jvm_args.txt` 读取（默认2G/6G）；外层小堆不控制服务器堆。入口传递控制台输入/输出和退出码，关闭入口时尝试向游戏发送 stop，不强杀服务器。正常停止仍由服主输入 stop 并等待保存。

## 构建与核对

启动入口由 Java17 `javac --release 17` 与标准 jar 工具构建，无第三方依赖；类字节码major61、Main-Class、许可证和SHA均核对。没有新增或运行自动化测试。本次没有启动游戏、服务端世界或修改存档。

- 启动入口：6,258 字节，SHA-256 `603D3BC2C0C52C87E0C61D128514FF33D373E50AE5CDE917AEABA90C77292590`。
- `IC2-New-Era-Server-1.20.1-r48-Full.zip`：259,598,725 字节，SHA-256 `2E47B80CE4C4302FC0EF8D37AEB1C9DDD60A6AC0A2BF1FF57FFE7245F1CD795D`。
- `IC2-New-Era-Server-1.20.1-r48-Full.tar.gz`：259,411,192 字节，SHA-256 `65717A905D440A5E6BE2696C0E629360429081B7CA25D863B49283B13CC5DDD0`。
- ZIP/tar均回读全部成员哈希、同一顶层目录和同一文件集；shell脚本LF、无BOM，归档权限0755。
- 实际解压目录：147个文件，294,477,989字节；25个顶层mod JAR、104个libraries文件。逐项与manifest核对哈希，确认server.jar及Start脚本存在。

## 验收边界

这是完整的文件交付，不是完整开服验收。既有25-JAR组合在Windows构建主机的官方 `--initSettings` 初始化停在 EULA 门槛，日志包含已记录的VS兼容扫描警告；本轮没有因此修改或删除上游JAR。Linux实际启动、多玩家连接、AE2供电自动合成、飞行和客户端渲染仍待验收。无需下载核心或模组不代表无需系统Java、无需网络身份验证，或已部署至云服务器。

公开同步只包含本项目启动入口源码、脚本、依赖清单和说明；第三方JAR、Minecraft核心、用户音频、密钥与存档不上传GitHub。具体同步回执保存在本次私有打包检查点。
