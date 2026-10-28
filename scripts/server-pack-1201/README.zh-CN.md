# IC2：新纪元 1.20.1 Forge 专用服务器 r48-full

本归档是可迁移的完整 Forge 服务端包，含 Forge 1.20.1-47.4.28 运行所需的 `libraries/`、官方启动参数、锁定的服务端模组和新纪元 `server.jar`。不需要另行下载 Forge 核心或模组。归档默认保留 15 个原始模组、9 个双端增强模组和新纪元模组，共 25 个模组 JAR；以 `server-manifest.json` 锁定信息为准。JEI 保留在服务端包中。

## 推荐启动

需要系统已安装 Java 17。Linux 使用 `JAVA17_HOME`、`JAVA_HOME` 或 PATH 中的 Java；Windows 使用对应环境变量或 PATH。归档不包含 JDK/JRE。建议使用 `./Start.sh`（Linux）或双击 `Start.bat`（Windows）；它们会进入平台启动脚本，由一个 Java 进程直接调用随包 Forge 官方参数文件。Linux 首次解压后运行 `chmod +x Start.sh start-server.sh`。默认 JVM 堆设置在 `user_jvm_args.txt`（`-Xms2G -Xmx6G`），请按服务器内存调整。

`server.jar` 是独立的面板启动入口，面板只支持 `java -jar` 时可使用：

```sh
java -Xms32M -Xmx128M -jar server.jar
```

此小堆仅用于 launcher；Forge 子进程的实际堆仍由 `user_jvm_args.txt` 设置。面板须将工作目录设为服务端根目录，并保留同目录的 `libraries/`、`user_jvm_args.txt` 与 `eula.txt`。launcher 自动附加 `nogui`，也接受其他 Minecraft 参数；不要重复传入 `nogui`。推荐的 Start 脚本启动方式不经过 launcher，使用单 Java 进程。

## EULA 与首次启动

归档内 `eula.txt` 默认明确设置为 `eula=false`。先阅读 [Minecraft EULA](https://www.minecraft.net/eula)，是否接受由服主本人决定；只有本人决定接受后才手动编辑为 `eula=true`。任何启动入口都会在 EULA 未明确接受时退出，不会替服主同意。尚未启动过的世界不会随包提供；首次正常启动会由 Forge/Minecraft 创建服务端数据。

## 默认设置与客户端

- 默认在线身份验证开启（`online-mode=true`），游戏端口 TCP `25565`，视距 8，模拟距离 6，RCON 关闭。
- FTB Ultimine 默认单次最多挖掘 64 个方块。
- 服务端保留 JEI 和 CarbonConfig。玩家客户端也应安装配套 JEI，才能使用客户端配方转移功能。
- Embeddium、Oculus、Oculus Flywheel Compat、Entity Texture Features、Entity Model Features 和 Fusion 仅供客户端使用，不要复制到服务器 `mods/`；其他双端模组按 manifest 对齐版本。
- ProjectE `config/ProjectE/custom_emc.json` 由项目受控中间物黑名单生成，将列出的受控物品 EMC 固定为 0。保留该文件。
- Create 内嵌的 Flywheel、Ponder 等 Jar-in-Jar 库随 Create 一起提供，不要当作独立模组删除。

服务器包不含玩家的 LLM 密钥、语音资源、玩家配置或存档。图纸示例位于 `examples/blueprints/`，需要由玩家自行复制到客户端的 `IC2-NewEra-blueprints/inbox`。语音及其余尚未验收功能不因此视为已在专用服务器验证。

## Linux 兼容依据与验收状态

Forge 参数与依赖已静态核对，完整服务端世界尚未启动或实机验收。Valkyrien Skies 2.4.11 JAR 中包含两个 Krunch JNI ELF64 库：`libKrunchJni64.so` 的 ELF `e_machine=0x3e`（x86-64），`libKrunchJniarm64.so` 的 `e_machine=0xb7`（AArch64）；随包 Netty 也含对应 Linux epoll 构件。配合相同架构的 Java 17 glibc 运行时，x86-64 与 AArch64 有静态依赖支持证据，尚未在 Linux 硬件上实测。Linux 架构需求尚未指定，也不随包提供 Java 运行时。

请将本包解压到与旧版 1.19.2 实例/存档分开的可写目录。远程连接所需的防火墙、安全组和网络设置由服主配置；本包不会开启端口转发或启用 RCON。不要换用 Paper、Spigot 等插件核心。
