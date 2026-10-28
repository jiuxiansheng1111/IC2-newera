# r48：AE2、Create 推进与 Linux 专服交付

日期：2026-10-28。本文记录已完成的源码构建、本机客户端安装及双端封包。新增玩法与实际 Linux 部署尚未实机验收。

## 设计与范围

- **AE2 职责**：使用 Applied Energistics 2 原生 ME 网络、ME Terminal、Pattern Provider、Storage Bus，负责仓储、检索和样板自动合成。JEI 15.56.0.205 保留。机器人仍按 ProcessGraph 操作真实 IC2/Create/原版机器；按用户最新要求，手机本轮先不改、AE 对接延期；工业监控和内置 AI 联网保留，手机和 AI 没有提交 AE2 合成请求的入口。
- **旧终端恢复**：新纪元旧终端不再承担生产网络、合成、自动搬运或 AI 托管。既有 `ic2newera:terminal` 注册与兼容方块仅作历史库存取回入口；方块库存只允许提取。带库存 NBT 的旧终端物品会拒绝兑换以防丢失。源码位置：[IndustrialItems.java](../build-isolated/migration-1201/newera/src/main/java/com/ic2newera/industrial/IndustrialItems.java)、[TerminalBlockEntity.java](../build-isolated/migration-1201/newera/src/main/java/com/ic2newera/industrial/TerminalBlockEntity.java)。
- **能源边界**：IC2 Refactored 2.10.33-ex120 自带 AE2 Energy Acceptor 接口；静态代码显示 IC2R 的 `Ae2EnergySink` 向 Energy Acceptor 注入能量，常量 `EU_TO_AE_RATIO=2.0`，即 1 EU 对应 2 AE。该结论仅适用于 IC2R，不是 IC2 Classic 兼容，也未经实机验证。Ad Astra FE 到 AE2 Energy Acceptor 的路径来自 FE API 静态检查，仍待游戏内核实。
- **飞行替代**：采用 Create: Propulsion 0.4.1 + Valkyrien Skies 2.4.11 + Kotlin for Forge 4.11.0，维持 Create 6.0.8 / Forge 47.4.28。此为兼容 1.20.1 Forge 的飞行替代方案，不是 Create Aeronautics 移植版。Propulsion 项目仍将自身描述为 very early alpha；版本标为 release 不等于玩法稳定或验收通过。VS Linux 原生库需要 glibc；Alpine/musl 不在官方支持描述内。
- **资源汉化**：新增 13 个命名空间的自动加载中文增补，共 324 个差异键；只包含上游 `zh_cn` 缺失键和一处 VS `%s` 参数修正，不随包复制第三方完整中文文件。六个图形模组仅客户端；Iris & Oculus Flywheel Compat 2.0.3 为 beta。包中没有光影材质包。语音 bundle 保持原样。
- **其他新增玩法**：FTB Ultimine 目标为按住 `~` 连锁采集，服务端最大连锁方块数 64；Simple Inventory Sorter 目标为中键整理容器。默认配置已入包，实际按键行为待实机验收。

## 目标依赖布局

客户端目标为 31 个 JAR（新纪元本体 1 个 + 第三方依赖 30 个）；Linux 服务端目标为 25 个 JAR（本体 1 个 + 共用第三方 24 个）。新增的 15 个第三方依赖分为 9 个共用项和 6 个客户端项：

| 共用项（9） | 客户端专用项（6） |
|---|---|
| AE2 15.4.11；GuideME 20.1.15 | Embeddium 0.3.31 |
| FTB Ultimine 2001.1.8；FTB Library 2001.2.13；Architectury 9.2.14 | Oculus 1.8.0 |
| Simple Inventory Sorter 23.0.11 | Iris & Oculus Flywheel Compat 2.0.3（beta） |
| Create: Propulsion 0.4.1；Valkyrien Skies 2.4.11；Kotlin for Forge 4.11.0 | Entity Model Features 3.3.12；Entity Texture Features 7.2.6；Fusion 1.3.15b |

JEI、Create、IC2R、Ad Astra 等此前依赖仍按现有锁版本保留。六个客户端专用模组不可放入服务端 `mods`。第三方原始 JAR 可进入私用玩家/服务器包，但不提交公开 GitHub。

## 源码与依赖核对

- AE2 15.4.11 与必需的 GuideME 20.1.15 来自官方 CurseForge 文件页；`mods.toml` 版本范围匹配 Minecraft 1.20.1 / Forge 47.4.28，AE2 要求 GuideME 20.1.7–20.2.0。JEI 15.x 为可选客户端依赖，现有 JEI 版本落在范围内。AE2 原生能量指南给出 Forge Energy 2 FE = 1 AE。
- 新纪元本体没有新增 AE2 私有存储/合成协议或手机/AI提交请求接口；AE2 的终端、样板与存储总线使用上游注册和逻辑。旧 `terminal` 仅有历史库存提取兼容代码。汉化差异已封入新纪元强制内置中文资源包。
- IC2R 2.10.33-ex120 的 JAR 静态检查找到 `ic2.integration.ae2.Ic2Ae2Plugin` 和 `Ae2EnergySink`，并核对 AE2 15.4.11 中被调用的 Grid / Energy API。该实现属于 IC2R 上游；本阶段未添加新纪元自定义 AE2 能源桥。
- Create: Propulsion 的 `mods.toml` 声明 Create `6.0.6+`、VS `2.4.0+`、Forge `47.3.10+`；VS 2.4.11 声明 Forge `47.2.0+`、Create `6.0.6+`，Forge 1.20.1 另需 Kotlin for Forge 4.11。声明与锁定的 Create 6.0.8 / Forge 47.4.28 相容。官方 VS 安装说明给出 Forge 高于 47.4.0 的支持说明；早期 47.4.2 交互问题案例对应 VS 2.3.0-beta.5，并非本组合的验证结果。
- Propulsion 官方条目列有 Physics Assembler 与 Thruster；静态依赖不要求 Create: Interactive 或 Clockwork。VS 官方兼容表说明 Create 6.0.8+ 的基本兼容不需要这些集成附属。Propulsion/VS/Kotlin 文件只做了 JAR、哈希、元数据和 Linux 原生库静态核对，没有运行游戏。
- VS 2.4.11 JAR 内含 64 位 Linux `libKrunchJni64.so`（ELF x86_64）及 `libKrunchJniarm64.so`（ELF AArch64）。官方兼容页要求 glibc。Linux x64 原生文件存在只证明包内有对应架构，不证明专服运行已验收。
- 9 项共用与 6 项客户端依赖的来源、版本、SHA-256 及环境标签见 [THIRD-PARTY-NOTICES.txt](../scripts/player-pack/THIRD-PARTY-NOTICES.txt) 和 `scripts/player-enhancements-lock-1201.json`。资源翻译键与格式核对记录在 [语言增补目录](../scripts/player-pack/enhancements-zh/assets/)；增补已随本体 JAR 打包。

官方来源：

- [AE2 15.4.11 发布](https://github.com/AppliedEnergistics/Applied-Energistics-2/releases/tag/forge/v15.4.11)、[AE2 Forge Energy 指南](https://github.com/AppliedEnergistics/Applied-Energistics-2/blob/main/guidebook/ae2-mechanics/energy.md)、[GuideME 20.1.15 发布](https://github.com/AppliedEnergistics/GuideME/releases/tag/v20.1.15)
- [IC2 Refactored 2.10.33-ex120](https://github.com/craftorio/IC2R/releases/tag/2.10.33-ex120)
- [Create: Propulsion 0.4.1](https://modrinth.com/mod/create-propulsion/version/2oBX64AJ)、[Propulsion 依赖表](https://valkyrienskies.org/wiki/create-propulsion)
- [Valkyrien Skies 2.4.11](https://github.com/ValkyrienSkies/Valkyrien-Skies-2/releases/tag/2.4.11)、[VS 安装说明](https://valkyrienskies.org/wiki/How_to_install_addons)、[VS 兼容表与 Linux 要求](https://valkyrienskies.org/wiki/compatibility-list)、[Kotlin for Forge](https://modrinth.com/mod/kotlin-for-forge)
- [FTB Ultimine](https://www.curseforge.com/minecraft/mc-mods/ftb-ultimine-forge/files/7880472)、[FTB Library](https://www.curseforge.com/minecraft/mc-mods/ftb-library-forge/files/8226927)、[Architectury](https://www.curseforge.com/minecraft/mc-mods/architectury-api/files/5137938)、[Simple Inventory Sorter](https://www.curseforge.com/minecraft/mc-mods/inventory-sorter/files/7188685)
- [Embeddium](https://modrinth.com/mod/embeddium/version/UTbfe5d1)、[Oculus](https://modrinth.com/mod/oculus/version/iQ1SwGc3)、[Iris & Oculus Flywheel Compat](https://modrinth.com/mod/oculus-flywheel-compat/version/dE1A45cG)、[EMF](https://modrinth.com/mod/entity-model-features/version/mQR6L8Qe)、[ETF](https://modrinth.com/mod/entitytexturefeatures/version/EAHEZRbp)、[Fusion](https://modrinth.com/mod/fusion/version/EQaOpkjt)

## 交付状态

| 项目 | 状态 |
|---|---|
| 设计边界、来源与候选依赖静态核对 | 已完成；不代表实际玩法验收 |
| Linux Forge 1.20.1 / 47.4.28 官方 installer | 已完成隔离核心安装，生成 `run.sh` 与 Linux `unix_args.txt`；当前为核心准备阶段 |
| 服务端启动状态 | 未完成。原基础与新增25-JAR组合均执行官方 `--initSettings`，退出0，输出要求接受 EULA，`prepared/eula.txt` 仍为 `eula=false`；这不等于完成服务器启动或模组加载。该初始化日志显示 Windows 11 构建主机，尚无实际 Linux 主机运行证据 |
| 新纪元源码构建 | 成功；40 秒，跳过测试 |
| 客户端依赖安装 | 本机独立 1.20.1 实例安装完成，31 JAR；旧本体备份 |
| Linux 服务端 JAR 与核心归档 | 已打包并回读，25 JAR；未部署到云服务器 |
| 客户端 31-JAR 整合包 | 已打包并回读；30 第三方按作者URL下载，本体内置 |
| Linux glibc 专服实际运行与多人验收 | 未实测 |
| AE2 能源、JEI、自动合成、旧终端库存取回、Ultimine、推进器飞行 | 未实测；不得由静态核对推定通过 |
| 自动化测试 | 本阶段未新增或运行 |

`r47` 小屋/亭子多人施工的历史实测只适用于 r47 构件，不外推至 r48 模组组合、玩家包或 Linux 专服。大殿仍是草稿，未交付。Voice-Bundle 未修改、未重打包。

## 最终回执

- 构建：`gradlew.bat -Dnet.minecraftforge.gradle.check.certs=false build -x test --offline --no-daemon`，`BUILD SUCCESSFUL in 40s`。不运行测试、不清理原部署回执。
- 本体：`ic2newera-0.2.0-migration1201-all.jar`，10,496,529 字节，SHA-256 `5C98DDAFD0511AB0CF5D679314916603FE677D34681F4F89F7380B58200218FE`。
- 客户端：`dist/IC2-New-Era-1.20.1-Forge-47.4.28-r48.mrpack`，314,104,518 字节，SHA-256 `D409C9AA135A64C9A88627BBC653D47D175367D723D65FF296619AA24E68044D`。归档回读、492 个便携 Java 文件、30 条外部下载哈希、ProjectE 71 项 EMC 排除与中文默认配置核对完成。
- 服务端：`dist/IC2-New-Era-Server-1.20.1-r48.tar.gz`，259,404,011 字节，SHA-256 `5F21528582D285B9E44A2F2D829462AC878A296987A4D3A95E5B4CCD99038D81`。143 个归档文件回读通过；Forge 两个平台参数文件各58条库引用存在；25个顶层mod、34个JarJar坐标经元数据选型（MixinExtras0.4.1）、60条服务端必需依赖范围核对通过，6个客户端图形mod均排除。
- 安装：2026-10-28T15:48:52.4260934+08:00 更新独立1.20.1实例；31个JAR各自哈希核对，16项新装/更新。未启动游戏或改写存档，未读取玩家密钥。
- 636项原有第一方模型/材质/音效资源与r47逐字节一致；狗的完整音频保留。Voice-Bundle及校验文件未修改；该Win64运行时不能直接用于Linux，Linux AI语音后端未部署。
- 旧r47客户端包及校验文件已移入回收站；构建检查点保留原包，dist仅保留r48双端包、原语音包及说明/校验。
- EULA默认false。25-JAR初始化退出0并停在EULA门槛，没有world；出现VS可选兼容与客户端类探测警告，不能据此宣称完整服务器启动或全部mod初始化已通过。实际Linux运行、多人连接、AE2供电合成、Ultimine、飞行与渲染仍待人工验收。
- AE2新增配方/tag静态复核没有将Certus石英粉或ae2:silicon映射为新纪元受控石英粉/硅；新纪元电子配方使用精确ID。AE2原生科技路线保留，未发现受控流程旁路，不修改ProcessGraph或RecipeGuard。
- 公开源码同步：见本次私有检查点的GitHub回执；第三方原JAR、语音、原参考资产、凭据与存档不进入公开仓库。
