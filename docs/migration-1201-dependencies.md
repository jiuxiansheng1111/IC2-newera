# Minecraft 1.20.1 Forge 迁移依赖矩阵

## 当前锁定（2026-10-23）

隔离目标使用 Minecraft 1.20.1、Forge 47.4.28、Java 17，当前第11轮构建检查点通过；尚未安装到实例，也未进行游戏验收。实际运行时模组、版本、文件名、SHA-256、Forge/Minecraft范围及外部必需依赖核对列在 [runtime-lock-1201.json](../build-isolated/migration-1201/dependencies/runtime-lock-1201.json)。该锁只纳入目标 `newera/build.gradle` 与 `gradle.properties` 当前启用并实际下载的JAR，不含1.19.2模组。

当前锁定包括IC2 Refactored 2.10.33-ex120、Create 0.5.1j、Farmer’s Delight 1.3.4、森罗物语：厨房1.6.0、JEI 15.56.0.205、Curios 5.14.1、The One Probe 10.0.3、ProjectE 1.0.1、Ad Astra 1.15.21及其Botarium、Resourceful Lib、Resourceful Config依赖，以及CarbonConfig 2.0.2.1。全部JAR均在本地依赖目录，锁内SHA-256与文件一致；Ad Astra的3条必需外部依赖和Create嵌入的Flywheel均已核对满足。

CarbonConfig仍由目标 `build.gradle` 无条件声明，因此按当前运行时配置纳入锁。已审阅的IC2 Refactored JAR没有声明CarbonConfig依赖，JAR引用扫描也未发现其配置API引用；这表明构建文件仍携带一项当前IC2R未要求的依赖，但本次不修改构建配置。The One Probe 10.0.3在CurseForge标记为Beta。

| modId | 版本 | 实际JAR | SHA-256 | 目标构建配置 |
|---|---|---|---|---|
| `ad_astra` | `1.15.21` | `adastra-1201-1.15.21.jar` | `D84DC1182B4C570D955DF67B80703DC058CE944C7D9B0702E3577934C96D864C` | runtimeOnly |
| `botarium` | `2.3.4` | `botarium-1201-2.3.4.jar` | `470810A364FF4AD4D4FCD4E55798263938FC893970EACECC58E8B741A392B1CF` | runtimeOnly |
| `carbonconfig` | `2.0.2.1` | `carbonconfig-1201-2.0.2.1-CANDIDATE.jar` | `BC5E7F3E6ECE33FDCF6557EEE598291BE70A474BF8BB1906E8BC021395944C8C` | implementation (unconditional in build.gradle; not required by audited IC2R jar) |
| `create` | `0.5.1j` | `create-1201-0.5.1j.jar` | `A18763BE04F6A7921D2E3BC5E9150B0BB5BC89B5EF45A607E93939972021F932` | implementation |
| `curios` | `5.14.1+1.20.1` | `curios-1201-5.14.1+1.20.1.jar` | `6D77AE8AD532FDF303390F404B0081B3B4AC4F61E7F1F4B4D4A9077E132DAE4F` | implementation |
| `farmersdelight` | `1.20.1-1.3.4` | `farmersdelight-1201-1.3.4.jar` | `34F03EB671EA1CA579B00C98E4541404CB96D1A292014AED9CC5EB45AD8AFBCE` | implementation |
| `ic2` | `2.10.33-ex120` | `ic2-forge-2.10.33-ex120.jar` | `9C2BAABD51F6E5EECC0E75F9B0DC8474DD6D2A3DD701B1000924253D3ACA110F` | implementation |
| `jei` | `15.56.0.205` | `jei-1201-15.56.0.205.jar` | `6B251E60A4719C874DA89D750AF97AA6498F6F4475E3AE79CB1288C7BF0CB65C` | compileOnly + runtimeOnly (enable_jei=true) |
| `kaleidoscope_cookery` | `1.6.0-forge+mc1.20.1` | `kaleidoscope-cookery-1201-1.6.0.jar` | `0EEE5579B36746825D6586C65051D9E68FC84178A19DEC4372FC9D64B8629D62` | implementation |
| `projecte` | `1.0.1` | `projecte-1201-PE1.0.1.jar` | `F51F387E56307E7CDA26915CA0C789760D85E97A8B40B8BFA0988BE66371D018` | runtimeOnly (enable_projecte=true) |
| `resourcefulconfig` | `2.1.3` | `resourcefulconfig-1201-2.1.3.jar` | `C404B9E5CF8595427A72D4CA6EE5730289168762B463F169BE3AE5DFE3727DED` | runtimeOnly |
| `resourcefullib` | `2.1.29` | `resourcefullib-1201-2.1.29.jar` | `B13AFB95231D88E1CAEA5A045967E0FC291B29020B0D26AD39E6F9E9A551FEE8` | runtimeOnly |
| `theoneprobe` | `10.0.3` | `top-1201-10.0.3.jar` | `2A229CA4FD43C450873A09973680F97B2D24D01AB4295685921852EDA722ACD1` | implementation |

森罗与农夫乐事的当前机器人实现范围见 [烹饪实施记录](migration-1201-cooking.md)：复用森罗内置的FD→森罗汤锅兼容，FD→森罗汤锅单向已有源码并通过构建，尚未实机验收；森罗→FD反向桥接、森罗炒锅、茶壶和蒸笼尚未实现。IC2R缺失机器和材料导致的工艺阻塞见 [注册ID审计](migration-1201-registry-audit.md)。

## 2026-10-22 历史候选矩阵

下文保留IC2目标裁决前的候选调研，不代表当前依赖锁。旧内容中关于IC2待裁决、CarbonConfig仅候选以及尚未下载的状态均为历史记录；当前依据以runtime-lock-1201.json为准。

## 目标基线

| 项目 | 预选 | 依据 |
|---|---|---|
| Minecraft | 1.20.1 | 用户最新迁移目标 |
| Forge | 1.20.1-47.4.28 | 官方 Forge 页面列为 Latest；根代理已核对官方 MDK SHA1 |
| Java / Gradle / ForgeGradle | Java 17 / Gradle 8.8 / ForgeGradle 6 | 当前迁移工作预选，由根代理负责实现与编译 |
| Create | 0.5.1j | 按用户要求优先 0.5.1 API 线；0.5.1j 官方 Forge 文件可用。0.5.1j 与当前 1.19.2 的 0.5.1.i 属同一 API 系列，但源码迁移仍须逐项核对调用差异。 |

Forge 官方下载页：[1.20.1 Forge](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html)。Create 官方文件：[0.5.1j](https://www.curseforge.com/minecraft/mc-mods/create/files/5838779)。Create 项目列出 1.20.1 的 6.0.8 为更新分支；本表按 0.5.1j 作为 API 保守候选，不表示其比 6.x 更新。

## 历史：原 1.19.2 清单与 1.20.1 候选对照

本节是旧清单快照；“候选”“待核实”等状态反映当时调研进度，不应覆盖上方已锁定的本地JAR、元数据与依赖结论。

| runtime modId | 当前 1.19.2 文件 | 1.20.1 Forge 候选 | 必需依赖 / 版本约束 | 官方页面与构建坐标 | 状态 |
|---|---|---|---|---|---|
| `ic2newera` | `ic2newera-0.1.0-dev.jar` | 本项目迁移产物，版本待定 | Minecraft 1.20.1 / Forge 47.x；需随 IC2/Create API 迁移 | [本项目发布目录](../build-isolated/migration-1201/) | 内部源码迁移，不是外部下载项 |
| `ic2` | `ic2-classic-1.19.2-2.1.3.4.jar` | **待另一代理裁决**，不在此指定版本 | 目标版本、依赖（含 Carbon Config）与 API 兼容性均待 IC2 专项核实 | [IC2 Classic CurseForge](https://www.curseforge.com/minecraft/mc-mods/ic2-classic) | 暂不纳入锁定方案 |
| `create` | `create-1.19.2-0.5.1.i.jar` | `create-1.20.1-0.5.1.j.jar`（0.5.1j） | CurseForge 文件关系列出 0 个依赖；按 0.5.1j 的 Forge 支持使用 | [官方文件](https://www.curseforge.com/minecraft/mc-mods/create/files/5838779)；`curse.maven:create-328085:5838779` | 推荐 API 保守候选；不选 6.0.8 作为本轮基线 |
| `carbonconfig` | `carbonconfig-1.19.2-2.0.2.jar` | `CarbonConfig-1.20-2.0.2.1.jar` | Forge；IC2 是否接受此版本须由 IC2 代理确认 | [官方文件](https://www.curseforge.com/minecraft/mc-mods/carbon-config/files/8119981)；`curse.maven:carbon-config-898104:8119981` | 版本存在；IC2 约束未定 |
| `curios` | `curios-forge-1.19.2-5.1.6.4.jar` | `curios-forge-5.14.1+1.20.1.jar` | Forge 版，兼容 MC 1.20–1.20.1；官方 Curios Maven 可用 | [Curios 官方 Maven](https://maven.theillusivec4.top/top/theillusivec4/curios/curios-forge/5.14.1%2B1.20.1/)；坐标 `top.theillusivec4.curios:curios-forge:5.14.1+1.20.1` | 可用候选；项目依赖页无必需依赖 |
| `theoneprobe` | `theoneprobe-1.19-6.2.2.jar` | `theoneprobe-1.20.1-10.0.3.jar` | Forge；10.0.3 为公开且下载量较高的 1.20.1 Beta；较新的 10.0.4 仍标 Beta | [官方文件](https://www.curseforge.com/minecraft/mc-mods/the-one-probe/files/6106996)；`curse.maven:the-one-probe-245211:6106996` | 建议先固定 10.0.3；升级 10.0.4 需另审 |
| `jei` | `jei-1.19.2-forge-11.8.1.1034.jar` | `jei-1.20.1-forge-15.56.0.205.jar` | Forge；CurseForge 文件关系列出无必需依赖 | [官方文件](https://www.curseforge.com/minecraft/mc-mods/jei/files/8778011)；`curse.maven:jei-238222:8778011` | 选已标 Release 的稳定文件；更高 1.20.1 文件有 Beta 标记 |
| `projecte` | `ProjectE-1.19.2-PE1.1.0.jar` | `ProjectE-1.20.1-PE1.0.1.jar` | Forge；官方项目依赖页列 0 个依赖 | [官方文件](https://www.curseforge.com/minecraft/mc-mods/projecte/files/4901949)；`curse.maven:projecte-226410:4901949` | 存在正式版候选 |
| `farmersdelight` | `FarmersDelight-1.19.2-1.2.4.jar` | `FarmersDelight-1.20.1-1.3.4.jar` | Forge；项目依赖页无必需依赖 | [官方文件](https://www.curseforge.com/minecraft/mc-mods/farmers-delight/files/8765179)；`curse.maven:farmers-delight-398521:8765179` | 存在正式版候选 |
| `ad_astra` | `ad_astra-forge-1.19.2-1.12.7.jar` | `ad_astra-forge-1.20.1-1.15.21.jar` | 项目级必需关系包括 Resourceful Config、Resourceful Lib；不同 MC/loader 版本的依赖关系可能不同。当前 1.20.1 Forge 文件页面未暴露逐文件依赖，Botarium 是否仍为该文件所需需核 `mods.toml`/官方发布说明；不要据当前 1.19.2 直接沿用。 | [官方文件](https://www.curseforge.com/minecraft/mc-mods/ad-astra/files/8833730)；`curse.maven:ad-astra-635042:8833730`；[项目依赖页](https://www.curseforge.com/minecraft/mc-mods/ad-astra/relations/dependencies) | 版本候选明确，依赖需逐文件确认 |
| `botarium` | `botarium-forge-1.19.2-1.8.2.jar` | `botarium-forge-1.20.1-2.3.4.jar` | 1.20.1 Forge/NeoForge 文件；文件依赖页没有依赖项 | [官方文件](https://www.curseforge.com/minecraft/mc-mods/botarium/files/5486070)；`curse.maven:botarium-704113:5486070` | 候选存在；保留与否依 Ad Astra/IC2 最终依赖核验 |
| `resourcefullib` | `resourcefullib-forge-1.19.2-1.1.24.jar` | `resourcefullib-forge-1.20.1-2.1.29.jar` | Forge；Ad Astra 项目声明所需 | [官方文件](https://www.curseforge.com/minecraft/mc-mods/resourceful-lib/files/5659871)；`curse.maven:resourceful-lib-570073:5659871` | 候选存在 |
| `resourcefulconfig` | `resourcefulconfig-forge-1.19.2-1.0.20.jar` | `resourcefulconfig-forge-1.20.1-2.1.3.jar` | Forge；Ad Astra 项目声明所需 | [官方文件](https://www.curseforge.com/minecraft/mc-mods/resourceful-config/files/6231176)；`curse.maven:resourceful-config-714059:6231176` | 候选存在 |

## 森罗物语：厨房与 Farmer’s Delight 锅具互通

“森罗”对应官方项目 **Kaleidoscope Cookery 森罗物语：厨房**。官方 Modrinth 提供 Forge 1.20.1 `1.6.0-forge+mc1.20.1`：[版本页](https://modrinth.com/mod/kaleidoscope-cookery/version/1.6.0-forge%2Bmc1.20.1)，Maven 坐标 `maven.modrinth:v17FatAc:Ghp0qCKY`。该版本页标注 1.20.1 Forge、客户端与服务端；项目简介为独立烹饪系统。

现有官方变更记录能确认一定程度的内容/标签互操作：森罗早期更新提到认识 Farmer’s Delight 大米标签，且将自家炉具加入 FD 的 `#farmersdelight:heat_sources`。这不等于 FD 锅与森罗锅共享配方或能互相直接烹煮；当前官方版本记录没有证明这种原生双向锅具兼容。若目标是让同一台 Cooking for Blockheads 厨房自动使用两种锅的配方，独立的 [Code’s Mod Unified](https://modrinth.com/mod/codes-mod-unified/) 页面明确宣称提供 Farmer’s Delight / Kaleidoscope Cookery 的桥接，但它不在当前 13-mod 清单内，应作为新增可选候选另行核依赖和授权，不默认纳入。

因此烹饪订单接口必须按配方类型/目标锅分别发现和执行；不能把“森罗能识别 FD 标签或加热源”当作 FD 原生锅配方已可被森罗锅使用。这里仅作官方页面级核对，未验证游戏内交互。

## 历史：迁移门槛与未决项（2026-10-22）

1. IC2 Classic 是主要阻塞项：必须由 IC2 专项代理确认 Forge 1.20.1 文件、API/许可可用性及 Carbon Config 版本，再冻结新纪元的依赖基线。
2. Ad Astra 的 1.20.1 Forge 文件需要逐文件核 `mods.toml` 或作者官方发行说明，尤其确认 Botarium 与 Resourceful 版本范围；项目总依赖页会混合多个 MC/loader 版本。
3. Forge 文件标记 `Forge+ 1` 表示 CurseForge 页面也声明其他 loader；本方案仍只使用 Forge classifier/file。
4. 本表没有安装模组或读取本地存档；文末“独立 JAR 元数据审计”说明了本地下载和 ZIP 元数据读取范围。此表是迁移候选矩阵，不是已构建或实机兼容结论。

## 独立 JAR 元数据审计

随后从官方 Forge CDN（`edge.forgecdn.net`）、官方 Modrinth CDN 和 Curios 作者 Maven 下载候选 JAR 到 `build-isolated/migration-1201/dependencies/`，只用 ZIP 读取 `META-INF/mods.toml` 与 `META-INF/jarjar/metadata.json`，没有执行 JAR。CurseForge Maven 只用于初始字节对照；最终文件重新通过官方 Forge CDN 下载，SHA-256 与官方文件对应值一致。逐文件版本范围和 SHA-256 锁定见同目录 [lock-candidate.json](../build-isolated/migration-1201/dependencies/lock-candidate.json)。该目录已有的 IC2 专项 JAR 保留原名，未覆盖。

JAR 元数据确认 Ad Astra Forge 1.15.21 的必需外部依赖为 Botarium `>=2.3.0`、Resourceful Lib `>=2.1.23`、Resourceful Config `>=2.1.0`；候选 2.3.4、2.1.29、2.1.3 分别满足约束。Forge 范围为 `>=47`，Minecraft 范围为 `>=1.20.1`。聚合 CurseForge 关系造成的 Common Storage Lib 疑问对这个具体 Forge JAR 不成立：其 `mods.toml` 不声明该依赖。

Create 0.5.1j 的 `mods.toml` 要求 Forge `>=47.1.3`、Minecraft `[1.20,1.21)` 和 Flywheel `[0.6.11,0.6.12)`；`jarjar/metadata.json` 显示 Create 内已嵌入 Flywheel 0.6.11-13 与 Registrate MC1.20-1.3.3，无须单独添加 Flywheel。其余资源库 Jar-in-Jar 项也留在各自父 JAR 内。Farmer’s Delight 1.3.4 要求 Forge `>=47.1.0` 和 Minecraft `[1.20,1.20.1]`，CraftTweaker 14.0.6+ 为可选依赖。Kaleidoscope Cookery 1.6.0 的 JAR 仅要求 Forge `>=47`、Minecraft `[1.20.1,1.21)`，没有 Farmer’s Delight 的强制依赖项。JEI、Curios、ProjectE、TOP 的 Forge/MC 范围与锁文件一致；TOP 10.0.3 仍是 CurseForge 标记的 Beta。

`CarbonConfig-1.20-2.0.2.1` 当时作为候选记录。当前隔离构建仍无条件声明此依赖，因此它已列入运行时锁；IC2R元数据和本地符号扫描未发现其必要性。完整下载与ZIP读取仍不等于单独的构建、安装或游戏兼容验证。
