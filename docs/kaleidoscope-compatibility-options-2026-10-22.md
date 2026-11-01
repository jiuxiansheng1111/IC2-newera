# 森罗物语：厨房与当前整合包的版本可行性

核对日期：2026-10-22。本文是上游信息与实施路线研究，不代表依赖已迁移、Kaleido 已接入、烹饪动画已保留或实机兼容已经完成。未下载/复制第三方源码或资源到项目，也未安装、升级、改 manifest、构建或运行游戏。

## 结论

Kaleidoscope Cookery（森罗物语：厨房）官方 Forge 发布目标为 Minecraft 1.20.1；另一条官方发布线为 1.21.1 NeoForge。官方 CurseForge、Modrinth 版本页与官方 GitHub 主分支中未发现 1.19.2 Forge 发布或作者认可的 1.19.2 backport。因而目前不能在现有 Minecraft 1.19.2 / Forge 43.5.0 环境中直接依赖官方成品，也不能据此声称其锅具、菜谱或动画已兼容。

官方 IC2 Classic 页面当前的现代 Forge 正式版本仍是 Minecraft 1.19.2 的 `IC2Classic-1.19.2-2.1.3.4`；没有 1.20.1 IC2 Classic 文件。虽然 GitHub 上另有 Minecraft 1.20.1 的 IC2 2.10.33-ex120 发布，但它属于不同的 IC2: Refactored 项目/代码线，不等于 IC2 Classic，也不能假定兼容 IC2 Classic API、存档或本项目既有 IC2 集成。保留 IC2 Classic 是升级方案的主要阻塞。

可行路线按可信度排序：先请 Kaleido 作者提供或认可 1.19.2 Forge backport；否则在作者许可与清晰版本标识下单独维护兼容分支；若改为升级整合包，则必须先决定 IC2 Classic 缺席是否可接受，并对整包所有依赖、IC2 集成与存档做独立迁移评估。现在最小可信的下一步是向作者询问 backport/源码分支支持及资源再分发边界，再做隔离的最小编译验证；不要把“只把模组版本号改到 1.19.2”当成移植。

## 上游版本证据

| 项目 | 官方页面所示版本 | 对迁移的意义 |
|---|---|---|
| Kaleidoscope Cookery | CurseForge 最新 Forge 文件为 1.20.1；Modrinth 列 1.20.1 和 1.21.1；GitHub `main` 的 `gradle.properties` 锁定 MC 1.20.1、Forge 47.3.0，版本范围 `[1.20.1,1.21)`。 | 无官方 1.19.2 二进制；源代码目标也不是当前 Forge 43 环境。见[官方 CurseForge 项目页](https://www.curseforge.com/minecraft/mc-mods/kaleidoscope-cookery)、[Modrinth 版本页](https://modrinth.com/mod/kaleidoscope-cookery/versions)、[官方 gradle.properties](https://github.com/KaleidoscopeMods/KaleidoscopeCookery/blob/main/gradle.properties)。 |
| IC2 Classic | CurseForge 现代 Forge 主文件 `IC2Classic-1.19.2-2.1.3.4`。页面说明该模组由 Playersf 代发布，开发者按自己选择的 MC 版本移植；未列 1.20.1 文件。 | 1.20.1 没有可验证的 IC2 Classic 正式版。见[IC2 Classic 官方 CurseForge 页面](https://www.curseforge.com/minecraft/mc-mods/ic2-classic)。 |
| IC2 2.10.33-ex120 | `craftorio/ic2` GitHub Releases 显示 Minecraft 1.20.1。 | 这是 IC2: Refactored 代码线，不是 IC2 Classic 2.1.3.4 的续版替换保证。见[该仓库发布页](https://github.com/craftorio/ic2/releases)。 |
| Create | 官方 CurseForge 存在 1.20.1 Forge `0.5.1.j`，也有较新的 6.0.x；1.19.2 当前是 0.5.1.i。 | 有正式 1.20.1 版本，但跨版本 Create 配方/集成仍需按目标分支核验。见[官方 1.20.1 文件列表](https://www.curseforge.com/minecraft/mc-mods/create/files/all?version=1.20.1)。 |
| Ad Astra | 官方 CurseForge 有 Forge `1.20.1-1.15.21`；另有 1.19.4，而非当前 1.19.2。官方 README 对 1.20.1 及更旧版本列出对应 Maven artifact 命名规则。 | 1.20.1 有正式版；升级时需同步切换 Botarium、Resourceful Lib/Config 等实际要求版本。见[官方项目页](https://www.curseforge.com/minecraft/mc-mods/ad-astra)、[官方源码 README](https://github.com/terrarium-earth/Ad-Astra)。 |

## 当前依赖迁移清单

本地 `scripts/runtime-mods.json` 与 2026-10-22 安装回执各有 13 个模组条目，其中 1 个是本项目本身，实际需审查的第三方模组共 **12 个**。以下“有 1.20.1”表示上游发布页有相应版本，不表示和其他升级后的模组组合已通过联机/存档验证。迁移时应重新选择彼此满足依赖范围的 Forge 文件，不应沿用 1.19.2 jar。

| 第三方模组（当前 ID） | 1.20.1 Forge 上游证据 | 迁移评估 |
|---|---|---|
| IC2 Classic (`ic2`) | 未找到官方 1.20.1 IC2 Classic 发布；官方 CurseForge 当前现代版仍为 1.19.2。 | **阻塞项**：保留 IC2 Classic 的整包升级路线暂不成立。另有 IC2: Refactored 1.20.1，但要作为替换项目重新核验机器、EU API、配方、注册 ID、存档和本项目代码，不可算作版本升级。 |
| Create (`create`) | 有官方 1.20.1 Forge 0.5.1.j 和 6.0.x；当前锁定 1.19.2 0.5.1.i。 | 选择明确的 Create 版本系列，并重核 Ponder/Flywheel、附属模组、配方与 API。项目目标中不应无意从 0.5.1 升到 6.x。 |
| Carbon Config (`carbonconfig`) | 官方文件列表有 Forge 1.20.1，例如 `CarbonConfig-1.20-2.0.2.1`。 | 可迁移；注意当前版本较 1.19.2 的 2.0.2 已更新，核对 IC2 Classic 替代方案实际依赖。 |
| Curios (`curios`) | 官方有 Forge `curios-forge-5.3.1+1.20.1`，后续 1.20.1 文件亦存在。 | 可迁移；重核依赖它的盔甲/饰品或本项目接口代码。 |
| The One Probe (`theoneprobe`) | 官方文件列表有 Forge 1.20.1 `10.0.4` 等版本。 | 可迁移；重新核对 TOP API 集成与客户端/服务端版本配对。 |
| JEI (`jei`) | 官方有 Forge 1.20.1，版本线为 15.x（页面显示 15.74.0.228）。 | 可迁移；项目的 JEI 插件接口和配方显示须重编译核验。 |
| ProjectE (`projecte`) | 官方有 Forge 1.20.1 `PE1.0.1`。 | 可迁移；重审 EMC 配置、配方/物品注册变化及本地改动。 |
| Farmer’s Delight (`farmersdelight`) | 官方有 1.20.1 Forge 发布，页面当前列到 1.3.4；当前锁定 1.19.2 1.2.4。 | 可迁移；代码集成需针对对应 FD API 重编译，保存旧世界前核对方块/物品 ID 与迁移。 |
| Ad Astra (`ad_astra`) | 官方有 Forge 1.20.1 `1.15.21`。 | 可迁移，但存在强依赖版本链，不能只替换主 jar。 |
| Botarium (`botarium`) | 官方 Forge 1.20.1 文件可用（1.20.x 版本线；页面列有 2.1.0/2.1.1）。 | 可迁移；选用 Ad Astra 目标版本明确要求的 Botarium 版本，不能用 1.19.2 的 1.8.2。 |
| Resourceful Lib (`resourcefullib`) | 官方有 Forge 1.20.1，例如 2.1.29。 | 可迁移；与 Ad Astra 及其他调用者统一满足版本范围。 |
| Resourceful Config (`resourcefulconfig`) | 官方有 Forge 1.20.1，例如 2.1.3。 | 可迁移；与 Ad Astra 目标版本统一满足版本范围。 |

因此，其他 11 个外部依赖（排除 IC2 Classic）有 1.20.1 Forge 文件可供评估；这只是“版本存在”，并非完整兼容证明。当前 Ad Astra 依赖 Botarium、Resourceful Lib 与 Resourceful Config；现有依赖清单中的三者都必须一起升级并按当期官方依赖范围锁定。Create 生态和项目自身引用的 IC2/Create/JEI/FD API 也都要重新编译与验证。Modpack/世界迁移还需考虑 Forge 主版本从 43.x 到 47.x、方块/物品注册变化、配置格式、数据包与配方行为；Minecraft 与 Forge 不能用旧实例回退来验证迁移。

## Kaleido 1.20.1 源码对 backport 的影响

官方 `gradle.properties` 指向 MC 1.20.1、Forge 47.3.0 与 Parchment `2023.08.20-1.20.1`；当前项目是 MC 1.19.2、Forge 43.5.0。Java 17 可以保持，但 Minecraft/Forge/映射、ForgeGradle 依赖解析、注册与事件 API、数据包资源版本、模型/渲染与动画资源、菜单/网络同步都需要针对 1.19.2 实际 API 做编译和运行核对。官方 `build.gradle` 还列有 JEI 15、Cloth Config、Architectury、REI API、EMI、Jade、KubeJS/Rhino、Farmer’s Delight、背包及其余兼容目标；其中不少是开发期/可选集成依赖，不等价于玩家端硬前置，移植时须先从 `mods.toml`、条件加载和代码路径区分 required、compileOnly、optional、runtime，而不能把整个 Gradle 清单当成必装清单。见[官方 build.gradle](https://github.com/KaleidoscopeMods/KaleidoscopeCookery/blob/main/build.gradle)、[官方 mods.toml](https://github.com/KaleidoscopeMods/KaleidoscopeCookery/blob/main/src/main/resources/META-INF/mods.toml)。

这不是一个改版本常量就能结束的工作：要逐个找出所有 1.20.1-only 符号与调用约定；将注册、方块实体、菜单/网络、配方序列化、资源 pack 格式和客户端渲染路径映射至 Forge 43；保留原版核心行为和动画；然后在服务端/客户端、多玩家同步、锅具状态恢复、保存/重载和真实食谱消耗场景验证。官方源码中还有多个兼容集成与资源，目标依赖如果不做 1.19.2 backport，则必须将相应适配改为反射/可选加载或移除；否则不仅主模组，依赖集成也会阻断构建或启动。Kaleido 本体若仍保持为安装的 1.20.1 jar，则它自身不会运行在 MC 1.19.2 上。

这里的 API 差异结论是“构建目标与依赖面”的可行性判断，不是逐个 Java 方法的完整 diff：官方公开主分支锁定 1.20.1/Forge 47.3.0，发布页未提供 1.19.2 源码/成品对照分支。正式移植前仍须在隔离分支对源码的注册、网络、渲染、配方等调用逐文件比对 Forge 43/Minecraft 1.19.2 API，并以编译错误和运行行为逐项确认；目前不宜臆列未验证的具体方法名或宣称已经盘完所有符号差异。

## 许可与分发边界

官方仓库将代码与资源明确分开：[`LICENSE-CODE`](https://github.com/KaleidoscopeMods/KaleidoscopeCookery/blob/main/LICENSE-CODE) 为 BSD 3-Clause；[`LICENSE-ASSETS`](https://github.com/KaleidoscopeMods/KaleidoscopeCookery/blob/main/LICENSE-ASSETS) 为 CC BY-NC-SA 4.0。两者不能混为一个许可：

- 如 backport 复用或修改 BSD 授权代码，源代码再分发须保留版权声明、条件与免责声明；二进制分发须在文档或其他材料中附带相同声明/条件/免责声明；不能用作者或贡献者名义为衍生项目背书。标清改动来源与独立维护关系有助于避免误认官方支持。
- 资源许可允许分享与改编，但要求适当署名、链接许可并说明改动；禁止商业使用；如改编资源，衍生资源须按相同 CC BY-NC-SA 4.0 分发，且不能额外设置限制。该限制会影响付费整合包、商业推广或含改编资源的商业发行；具体商业边界若不清楚，应先取得权利人书面确认。
- 最低风险做法是先取得作者对 1.19.2 backport 的认可和资源再分发确认；若不能获得，使用新绘制/新建的独立资源，不打包原资源，并严格遵守代码许可证。不能从官方 jar 或源包抽取并直接放进本项目，也不能暗示作者认可本项目。

以上是对仓库中公开许可证条款的工程性归纳，不是法律意见。

## 官方 backport / 作者认可情况

截至核对日期，官方 CurseForge 版本列表只列 1.20.1 Forge 与 1.21.1 NeoForge；Modrinth compatibility 同样只列 1.20.1、1.21–1.21.1；官方 GitHub 主分支的 build 目标为 1.20.1。针对官方仓库 issue 搜索“1.19.2”未检索到可确认作者承诺或认可 backport 的官方记录。因此结论应表述为“在检查的官方发布页、主仓库和公开 issue 记录中未找到”，不把搜索缺失扩大为作者绝对没有私下计划。

## 最小可信实施路线与风险

1. **优先询问作者**：请求明确答复是否接受 1.19.2 Forge backport、是否有旧分支/未发布修复、资源许可证是否适用于目标分发渠道，以及能否提供兼容版本/依赖版本范围。等待期间只做 API 可行性盘点。
2. **隔离技术探针**：不改当前整合包 manifest、不把第三方源/资源复制到本项目。若作者确认可行，在单独的临时分支或独立 fork 做最小源码 backport，先核实构建、加载、核心锅具与一个真实配方，再覆盖同步/动画/存档加载；逐一处理可选依赖。保留 LICENSE-CODE/ASSETS 所需通知，产物明确标注 unofficial 1.19.2 backport、来源、修改与兼容范围。
3. **做核心验收后再决定整合**：在隔离 1.19.2 Forge 43.5.0 客户端与专用服务端验证锅具操作和动画、多人状态同步、材料/容器/燃料/成品一致性、容器归还、防重复产出、存档重载及依赖缺失时启动行为。通过后才讨论接入当前整合包；不得把“编译成功”当成完整验收。
4. **若坚持升级整包**：先确认是否接受暂时移除/替换 IC2 Classic。替换为 IC2: Refactored 是一次架构迁移，需要另行盘点其 EU/机器/注册/配方接口与世界数据；它不是保留 IC2 Classic 的升级捷径。之后再锁定 1.20.1 Forge 47.x 与 12 个第三方依赖的互相兼容版本，并单独做存档备份和世界迁移验证。

主要风险是作者不维护 backport、源码分支 API 改动范围大、资源 NC-SA 限制目标分发、可选集成依赖的版本/许可证边界、锅具/动画客户端服务端同步缺陷，以及若升级则 IC2 Classic 缺位带来的核心玩法/存档风险。当前结论是：**源代码 backport 技术上值得先做小范围探针，但完整 1.19.2 兼容未获证明；整包升级无法在保留 IC2 Classic 的前提下作为已确认方案。**
