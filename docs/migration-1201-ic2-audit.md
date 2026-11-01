# IC2 迁移到 Minecraft 1.20.1：只读兼容性审计

审计日期：2026-10-22  
项目当前配置：Minecraft 1.19.2 / Forge 43.5.0 / IC2 Classic 2.1.3.4。项目使用 Curse Maven 坐标 `curse.maven:ic2-classic-242942:8545995`。本审计按用户新目标评估 1.20.1，不更改主源码、依赖或实例。

## 结论

截至本次核查，没有找到 IC2 Classic 官方发布的 Minecraft 1.20.1 构建，也没有找到官方 1.20.1 完整源码分支。官方 CurseForge 页面列出的最新文件仍是 `IC2Classic-1.19.2-2.1.3.4`；官方上游仓库当前分支为 `1.19.x`，README 明确称其为 1.19.2 API。进一步检查官方仓库 `src/main` 目录的 GitHub 页面树，Java 目录只有 `ic2/api`；资源树只见 `assets/ic2/lang`。没有 `ic2/core`、机器/能网实现、完整注册代码或机器资源，因此该仓库是 API 源码交付，不是完整 IC2 Classic 模组源码。它不能作为 1.20.1 Classic 的完整源码编译输入。构建脚本映射目标仍为 1.19.2，且本次没有运行构建；是否能独立编译其 API source set 也未实测。[IC2 Classic 官方发布页](https://www.curseforge.com/minecraft/mc-mods/ic2-classic)；[IC2 Classic 官方上游源码树与 API 说明](https://github.com/TinyModularThings/IC2Classic/tree/1.19.x)；[官方 1.19.x 构建脚本](https://github.com/TinyModularThings/IC2Classic/blob/1.19.x/build.gradle)

可见的 Forge 1.20.1 候选是 `craftorio/ic2` 的 **IndustrialCraft 2: Refactored**。仓库发布页显示 `2.10.33-ex120`（MC 1.20.1）；当前 `forge/1.20.1` 分支 Gradle 属性写为 2.10.35-ex120、Forge 47.4.20、Java 17。仓库 README 明确称其从官方 IC2 Experimental `2.9.40-ex119` 反编译，并从 `2.8.222-ex112` 回移部分缺失/损坏功能；这不是 IC2 Classic 的移植版本。[Refactored 发布页](https://github.com/craftorio/ic2/releases)；[Refactored 1.20.1 分支说明](https://github.com/craftorio/ic2/blob/forge/1.20.1/README_EN.md)；[分支构建属性](https://github.com/craftorio/ic2/blob/forge/1.20.1/gradle.properties)

该候选当前声明 `All Rights Reserved / No License`，并说明仓库持有人不拥有原 IC2 源码和资源的版权，不能单方面授权整个项目的分发/商用。它也声明删除物品形态 UU Matter 及其下游机器、删除 Classic 的 `refined_iron_ingot`、调整锡/铅/铀矿生成，并改变充电板行为。候选发布说明还列出电表无显示、没有管道系统，以及未来计划将电网从 EU 发包改为电流模型。由此，不能把它作为 IC2 Classic 的二进制/API/行为兼容替身；授权是接纳它作为迁移依赖之前的硬阻塞项。[候选差异和版权声明](https://github.com/craftorio/ic2/blob/forge/1.20.1/README_EN.md)；[候选已知问题和电网计划](https://github.com/craftorio/ic2/blob/forge/1.20.1/release.md)

为履行候选构建核查，本次从该官方 GitHub Release 下载了公开 jar 到隔离目录 `build-isolated/migration-1201/dependencies/ic2-forge-2.10.33-ex120.jar`（SHA-256 `9C2BAABD51F6E5EECC0E75F9B0DC8474DD6D2A3DD701B1000924253D3ACA110F`），只读 `META-INF/mods.toml`、jar 路径和 `javap` 签名，没有安装或运行 jar。元数据是 `modId=ic2`、`version=2.10.33-ex120`、`license=All Rights Reserved`。候选里能观察到 `macerator`、`electric_furnace`、`induction_furnace`、`blast_furnace`、`generator`、`solar_generator` 和多类电缆资源；未观察到与 Classic `alloy_smelter`、`electric_blast_furnace`、`blast_induction_furnace` 同名的机器 loot-table 资源。后者是 jar 资源路径证据，不能单独证明全体注册表绝不存在同名注册项；但足以说明机器映射未经核实且不可按名字直替。

候选保留了一部分 `ic2.api.energy.tile` 类名，但方法契约不兼容：候选 `EnergyNet.instance` 对应 Classic `EnergyNet.INSTANCE`；候选能量源用 `double getOfferedEnergy()/drawEnergy(double)`，Classic 用 `int getProvidedEnergy()/consumeEnergy(int)`；候选 emitter/acceptor 是 `emitsEnergyTo/acceptsEnergyFrom`，Classic 是 `canEmitEnergy/canAcceptEnergy`；候选 storage 是 `ic2.api.tile.IEnergyStorage`，Classic 是 `ic2.api.tiles.readers.IEUStorage`。配方 API 也整体不同：候选 `ic2.api.recipe.Recipes` / `IMachineRecipeManager`，Classic `ic2.api.recipes.RecipeRegistry` / `IMachineRecipeList` 及其 `ingridients` 类型。当前源代码不能直接与该 jar 编译链接，电网和配方行为也需适配审查。迁移仍需先解决依赖许可，再逐项核对实际注册和行为。

## 本地项目直接依赖面

清单 `build-isolated/migration-1201/ic2-dependency-manifest.json` 逐项记录了源码 import、字符串形式的 IC2 ID、当前构建坐标和 ProcessGraph 机器引用。扫描结果为 58 条不同的 IC2 Java 类型 import，以及 11 个源码中字符串出现的 `ic2:` ID。

直接依赖分成以下功能面：

- EU 网格与能量 tile：`EnergyNet`、`IEnergyTile`、`IEnergySource`、`IEnergySink`、`IEnergyEmitter`、`IEnergyAcceptor`、`IEnergyConductor`、`IEUStorage`；用于电机、新纪元加工机、发电统计/电网拓扑和生产诊断。
- IC2 机器与内部实现：`BaseGeneratorTileEntity`、`SolarPanelTileEntity`、`BaseElectricTileEntity`、`MaceratorTileEntity`；代码直接访问发电机/太阳能板/机器类型，而非只通过 `Block` 通用接口。
- 配方：`RecipeRegistry`、`IMachineRecipeList`、`IInput`、`ItemInput`、`IRecipeOutput`、`IRecipeOutputChance`、`SimpleRecipeOutput`、`ItemWithNBTGenerator`；RecipeGuard 和机器人原生生产 IO 直接依赖 Classic 配方注册 API。
- 库存槽与原生 IO：`IHasInventory`、`IHasInventoryHandler`、`InventoryHandler`、`AccessRule`、`SlotType`、`SlotBase`。
- 线缆与建造改线：`CableBlock`、`CableTileEntity`、`CamouflageStorage`。
- 电池、工具与反应堆：`ElectricItem.MANAGER`、`IElectricItem`、`IDamagelessElectricItem`、`IMiningDrill`、`ChainsawTool`、`AdvancedDrill`、`AdvancedChainsaw`、`IReactorPlannerComponent`、`IUsableUranium`。
- Reader/运行状态：`IActivityProvider`、`IProgressMachine`、`ReaderProvider`。

这些 import 横跨公开 API 和 IC2 Classic 的 `ic2.core...` 内部实现。Refactored 即便在部分方法上有相似概念，也需要用目标发布物的实际包名、可见性、签名、线程/服务端行为逐项建立适配；只保留 mod ID 不能满足兼容。

## ProcessGraph 机器 ID

三个当前 ProcessGraph 文件共有 356 条路线（main 243、campaign 66、arsenal 47）。IC2 机器 ID 及路线引用次数如下：

| ID | main | campaign | arsenal | 迁移含义 |
|---|---:|---:|---:|---|
| `ic2:macerator` | 52 | 8 | 9 | 主矿物打粉及其他路线的核心机器；配方 ID、输入输出槽和配方 API 都要核实。 |
| `ic2:electric_furnace` | 43 | 10 | 6 | 普通电炉路线；当前 ProcessGraph 依赖该机器资源 ID 和配方可用性。 |
| `ic2:alloy_smelter` | 12 | 2 | 0 | 合金阶段关键设备；不能以普通炉或新纪元设备的近似功能替代。 |
| `ic2:electric_blast_furnace` | 2 | 4 | 3 | 钛/钨等高级炉流程之一；需核实候选中注册表和工序行为。 |
| `ic2:blast_induction_furnace` | 2 | 4 | 3 | 高级炉备选 ID；需核实配方、热量/能源机制与槽位语义。 |

源码其他位置还直接提及 `ic2:induction_furnace`、`ic2:generator`、`ic2:solar_panel_lv`、`ic2:copper_cable`、`ic2:advanced_circuit`、`ic2:complex_circuit`、`ic2:mixing_furnace`。其中发电机/低压太阳能用于生产方案，`induction_furnace` 用于 IC2 原生 IO，线缆 ID 用于建造/改线，材料 ID 用于工艺或技术路径。这些值也必须对选定目标依赖进行实际注册表核对。

## 无法直接替代或尚未核实的部分

- **高级炉**：当前图中 `electric_blast_furnace` 与 `blast_induction_furnace` 都是批准路径机器。候选来源是 IC2 Experimental 系，而非 Classic；本次没有检查候选 jar 注册表，故不能断言其有/无对应 ID。路线进入 1.20.1 前必须确认实际机器 ID、机器结构/配方、温度或热量条件、能量消耗及产物。
- **合金炉**：`ic2:alloy_smelter` 是项目规定的合金唯一设备。不能按名字相似度把它换成候选的 `induction_furnace`、`mixing_furnace` 或其他机器。需要确认真实机器注册 ID、两输入行为、输出槽、每配方能耗和并行语义；若没有等价机器，应先处理工艺模型，而不是静默改图。
- **EU**：电机、设备、储能和供电预估都调用 Classic 的 EnergyNet/Emitter/Acceptor/Source/Sink/Conductor/EUStorage API。候选发布说明提到电表问题，并计划从 EU 发包改为电流概念；当前实际发布版行为/计划是否已实现必须以目标构建核查。其 API、方向判定、包大小、损耗、缓存和读写时序均不能直接假定等价。
- **电缆及网络**：建造器直接使用 Classic `CableBlock`、`CableTileEntity`、伪装存储类，并以 ICCN 独立实现数据网络。Refactored 发布说明称暂无管道系统，未证明具有 Classic 电缆 API/模型/连接行为。ICCN 数据网络也不是电力网，迁移不能混淆两者。
- **注册 ID 与配方注册**：目标都可能使用 `ic2` 命名空间，但候选已明确删改部分材料/功能。机器 ID、物品/矿石 ID、RecipeRegistry 的机器列表、RecipeGuard 对已有配方的判定，均需从目标版本真实数据重新验证。保留命名空间并不能证明保留注册项。

## 建议交接顺序

1. 由项目负责人决定是否接受 Refactored 的来源与许可风险；本审计不建议将该候选直接纳入依赖。
2. 对 Minecraft/Forge/Create/Farmer's Delight 等整个 1.20.1 目标栈建立锁定版本清单。
3. 若仍选择该候选，先在隔离审查中用实际已发布构件核实 mod 元数据、Registry、机器配方和 API；不要把当前分支版本等同于已发布 jar。
4. 将 Classic 直连拆成可替换适配层，逐项迁移 EU、发电/储能、配方、库存、电缆、工具/物品与 IC2 内部 tile 接入。
5. 在ProcessGraph机器与物料映射审查通过之前，不转换任何批准路线或直接替换机器 ID。

本次只生成审计和机读清单；没有下载或复制第三方源码，只把官方发布页的候选 jar 放在指定隔离审计目录供只读检查；没有修改主源码和构建配置，没有执行构建、测试、游戏或安装。
