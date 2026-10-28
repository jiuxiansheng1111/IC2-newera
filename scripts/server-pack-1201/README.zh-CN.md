# IC2：新纪元 1.20.1 Forge 专用服务器

本目录提供 Linux/云服务器、Windows 和控制面板启动模板，配套 Minecraft 1.20.1、Forge 47.4.28 与新纪元 r48 客户端。服务器归档包含 Forge 安装器已生成的完整 `libraries/`、15 个原始运行模组、锁定清单中的 9 个双端增强模组以及新纪元 JAR；最终模组数量和版本以归档内 `server-manifest.json` 为准。模板使用 Forge 安装器生成的官方参数文件，不会下载或修改系统 Java，也不会自动重启服务端。

## Linux/云服务器快速开始

1. 解压服务器归档到独立目录。归档已包含完整 Forge `libraries/`、`mods/`、官方启动参数、默认配置和启动模板，无需再次安装 Forge。
2. 确认已安装 Java 17，并设置 `JAVA17_HOME` 或 `JAVA_HOME`；启动脚本会按 `JAVA17_HOME`、`JAVA_HOME`、PATH 的顺序选择 Java，且严格拒绝非 Java 17。默认 JVM 参数为 `-Xms2G -Xmx6G`，可按机器内存编辑 `user_jvm_args.txt`。
3. 阅读 [Minecraft EULA](https://www.minecraft.net/eula)。归档内 `eula.txt` 默认是 `eula=false`；只有你本人决定接受后，才手动改为 `eula=true`。脚本不会代替你同意 EULA。
4. 在服务端根目录运行 `chmod +x start-server.sh`，再运行 `./start-server.sh`。启动脚本会把命令末尾的额外参数原样传给 Minecraft；EULA 检查始终生效，本包不会自动同意 EULA。
5. 默认监听端口为 TCP 25565。需要远程连接时，由服主自行配置防火墙和云安全组放行 TCP 25565；本包不会自动配置端口转发或公网部署。

服务启动后保持控制台运行。关闭时输入 `stop`，等待存档保存并正常退出。

## 准备独立服务器目录

1. 将服务器归档完整解压到一个独立、可写的目录。归档中已包含 Forge 1.20.1-47.4.28 的 `libraries/` 与 Windows/Linux 参数文件。
2. 使用 Java 17；启动脚本会依次选择 `JAVA17_HOME`、`JAVA_HOME`、PATH 中的 Java，并严格拒绝非 Java 17。`user_jvm_args.txt` 默认分配 `-Xms2G -Xmx6G`，请按机器内存自行调整。

这是完整但尚未启动过的独立 1.20.1 服务端目录。Forge 参数与依赖仅作静态核对。Valkyrien Skies 2.4.11 JAR 内有两个实际 Krunch JNI ELF64 共享库：`libKrunchJni64.so` 的 ELF `e_machine=0x3e`（x86-64），`libKrunchJniarm64.so` 的 `e_machine=0xb7`（AArch64）；随包 Netty 也包含对应 Linux epoll 构件。配合对应架构的 Java 17 glibc 运行时，Linux x86-64/AArch64 是有静态依赖证据的兼容目标，尚未在 Linux 硬件上实测。不要将服务器文件与旧版 1.19.2 实例或存档混放。

## Windows 与控制面板启动

先阅读 [Minecraft EULA](https://www.minecraft.net/eula)。只有服主本人决定接受后，才在服务器根目录的 `eula.txt` 中手动设置 `eula=true`。归档默认提供 `eula=false`；启动模板发现 EULA 文件缺失或没有 `eula=true` 时会显示中英文提示并退出，不会代替服主同意。

- Windows：双击 `Start-Server.bat`。
- Linux 控制面板：按 `Panel-Startup.txt` 设置工作目录和 Java 17 启动命令。面板须将服务端根目录设为工作目录。

启动成功后保持控制台运行。关闭时在控制台输入 `stop`，等待存档保存并正常退出；不要直接关闭窗口或强制结束进程。

## 客户端连接

- 同一台电脑游玩：Minecraft 多人游戏地址填 `localhost`。
- 其他电脑游玩：填服务器的局域网或公网地址，例如 `serverIP:25565`。默认游戏端口为 TCP 25565。
- 服务端不会自动配置路由器端口转发或公网部署。需要远程连接时，由服主自行配置网络，并在服务器防火墙放行 TCP 25565；云主机还需在云安全组放行 TCP 25565。
- 使用配套的新纪元 r48 Minecraft 1.20.1 Forge 客户端连接。服务端保留 JEI，尽管 JEI 在专用服务器端是可选模组；玩家客户端也须保留 JEI，才能使用 `[+]` 配方转移等客户端功能。Embeddium、Oculus、Oculus Flywheel Compat、Entity Texture Features、Entity Model Features 和 Fusion 只装在客户端，不要复制到服务器 `mods/`；其他双端模组按锁定清单保持版本一致。

## 模组与玩家数据

服务器包按配套锁定清单保留 **15 个原始模组 + 9 个双端增强模组 + 新纪元 JAR，共 25 个模组 JAR**；实际数量以 manifest 为准。JEI 和 CarbonConfig 均保留。Create 内嵌的 Flywheel、Ponder 等 Jar-in-Jar 库随 Create 一起提供，不要当作独立模组删除。ProjectE 的 `config/ProjectE/custom_emc.json` 由项目内受控物品黑名单生成，只将列出的受控中间物品 EMC 固定为 0；不要删掉这项配置或以忽略整个 `config/` 为由移除它。Ultimine 的默认单次方块上限是 64。

图纸示例应由每位玩家复制到自己客户端的 `IC2-NewEra-blueprints/inbox`。服务器不会自动读取这个客户端目录。玩家的 LLM 密钥及语音资源属于客户端个人数据，不随服务器包打包；不要复制玩家配置或凭据到服务器。

默认 `online-mode=true`，请保持正版身份验证开启。RCON 默认关闭；不要在未明确配置安全措施时启用。不要换用 Paper、Spigot 等插件核心；本包是 Forge 服务端，不包含 Bukkit 插件桥。

## 故障检查

- 脚本提示 Java 版本错误：检查 `JAVA17_HOME`、`JAVA_HOME` 和 PATH，确保选中的 `java` 是 Java 17。
- 提示缺少参数文件：确认归档已完整解压到服务器根目录，并存在对应的 `libraries/.../win_args.txt` 或 `unix_args.txt`。
- 玩家无法连接：确认客户端版本及模组配套，并由服主检查服务端防火墙与云安全组 TCP 25565 规则。模板不会自动开放端口或配置公网网络。
