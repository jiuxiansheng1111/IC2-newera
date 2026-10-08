# 集成规范第一工业阶段交付报告

交付时间：2026-10-07T21:09:28.1361290+08:00。这是一个可构建、已安装的阶段，完整规范仍有明确待办。未运行游戏或实施测试。

## 规格第 50 节的 25 项报告

| # | 项目 | 实际状态 |
|---:|---|---|
| 1 | 修改文件列表 | 见下方完整文件清单；引导类、JEI、LLM 知识读取、网络协议、科技服务和资源/文档/构建入口已集成。 |
| 2 | 新增文件列表 | 见下方完整清单；包括 process、machine、industrial 等源码与对应资源、生成器、说明。 |
| 3 | 删除/废弃内容 | 本轮没有恢复化学、酸催化、电解、吸收塔、研究分析或旧重复熔炉；旧阶段已删除的体系仍无活动资源。旧低成本 IC2 高级/复杂电路配方由精准守卫替换。 |
| 4 | 新增 Item 数量 | 38 个（含 10 个 BlockItem）：四矿 16、电子材料 9、新合金 6、处理机 2、网络设备及核心 5。总物品模型 62。 |
| 5 | 新增 Block 数量 | 10 个：四矿石、两台处理机、适配器/光纤/交换机/服务器。原有锌矿石保留，blockstate 总数 11。 |
| 6 | 四种矿物完成情况 | 镍/铬/钛/钨均有注册、世界生成、掉落、语言、模型、标签及四阶段路线；源码/构建/安装完成，实际生成与加工待玩家验收。 |
| 7 | Impure Dust 完成情况 | 四种含杂质粉齐全；IC2 粉碎机或 Create 粉碎轮一矿产两粉，洗后得到洁净粉。 |
| 8 | Create Washing 必经步骤 | 批准粉末工业路线必须经 Create Splashing 水洗，RecipeGuard 移除未批准的阶段跳跃。原矿低效直烧保留，因此不能说所有取得金属的途径都必经水洗；实际配方加载待实机确认。 |
| 9 | Legacy 原矿直烧 | 四种新原矿均保留原版 smelting/blasting 单倍产锭，共 8 条；规划器/AI 受控生产方案不选 legacy。 |
| 10 | 镍/铬批准熔炼设备 | 洁净粉使用原版熔炉/高炉配方；IC2 普通电炉读取原版 smelting，高级电炉读取 blasting，复用原生处理。 |
| 11 | 钛/钨批准高级炉 | ic2:electric_blast_furnace 与 ic2:blast_induction_furnace。已核对当前 IC2 Classic 共用 BLAST_FURNACE 配方表；两种机器均允许，不能伪造仅某一炉专用。 |
| 12 | RecipeGuard 阻止捷径 | 受控 RAW/IMPURE/CLEAN 的未批准跳跃、含杂质粉直烧、高级洁净粉进普通炉、未经批准的高产原矿直烧、受控配方冒用 ID/输入/输出/时间，以及旧高级/复杂电路配方。覆盖 RecipeManager 和已接入的 IC2 粉碎/高炉/合金表；不声称覆盖 ProjectE 转化或所有未知第三方存储。 |
| 13 | ProcessGraph 当前路线 | 51 条：43 批准工业 +8 legacy；四矿加工、六种合金、石英/硅、电子模块、设备网络配方和两种原生电路改配方。实际注册配方由图在构建时生成，科技/规划/批准知识读取同图。 |
| 14 | Silicon 路线 | 下界石英 → 石英粉（IC2/Create）→ 硅 → 高纯硅（非金属处理机），不新增硅矿。注册/配方完成，实机待验收。 |
| 15 | CPU/RAM/GPU/SSD | 四模块均由电子材料加工机生产，有名称、独立图标、配方与科技节点；CPU/RAM/SSD 用于服务器，GPU 用于核心。实机待验收。 |
| 16 | 两台加工设备 | 非金属处理机和电子材料加工机已实现并接入 IC2 EU；固定 1/6 输入、1 输出，4096 EU 缓冲、第三级接收上限，正面拒收 EU，输入/输出面及 JEI 和中文悬停已接入。实机尚未确认。 |
| 17 | IC2 两类电路新配方 | 批准图定义新 advanced_circuit、complex_circuit 配方，复用原物品 ID。前者增加 PCB/集成电路，后者增加集成电路/高纯硅/PCB；不另注册 IC2 电路副本。完整数量以 ProcessGraph/JEI 为准。 |
| 18 | 网络状态 | 四种网络方块及 AI 核心已实现：默认网络名、邻机自动识别、Wi-Fi 16 格、光纤优先、加载区块内有界扫描、所有者/当前队伍/管理员权限、状态页及控制页；EU/RPM/物料不互转。实机拓扑和权限待验收。 |
| 19 | 服务器强制 CPU/RAM/SSD | 是，合成配方强制包含三类硬件；不是三个可插拔硬件槽。 |
| 20 | AI 核心强制 GPU | 是，合成强制包含 GPU；服务器模块槽只允许一个核心，无核心不能启动本地自动控制。 |
| 21 | AI 可检测的问题 | 本地服务器读取已接入机器的缺料、输出满、缺电、禁用/错误，并在原生公开接口可读时显示 EU/进度或 Create 转速/过载；未知状态不猜测。仅新处理机可自动暂停/恢复，原生机器只读。外部聊天 LLM 实时工厂诊断与控制提案尚未接入。 |
| 22 | Planner 支持范围 | 批准目标的整数 BOM、批次、余量、工序/设备、当前网络已识别与未识别设备、已知配方 EU；深度/步骤/数量/溢出/循环均设边界。原矿及无批准产路的目标拒绝生成虚构方案；原生耗能/Create 应力未知；没有全工厂库存扫描。 |
| 23 | 硬编码 API Key | 无；未读、输出、恢复玩家密钥，未改模型配置。核心玩法无需外部模型 API，聊天仍使用玩家原配置。 |
| 24 | 构建状态 | clean build -x test --offline 成功，25 秒；未新增/运行测试。包内 JSON、语言键、模型引用/范围、硬件依赖和指南静态核对通过；实际游戏验收未完成。 |
| 25 | 最终 JAR/安装 | build-isolated/releases/ic2newera-2026-10-07-industrial-stage.jar；583033 字节；SHA-256 DE4219324A7CB41DD0A2EBE9BE7B1B38032880AC1FBB11746F4D5AE860664262。已安装至 PCL2 Forge 43.5.0，8 个模组哈希一致，仅替换附属 JAR；回执 build/deployment/pcl-installation.json。 |

## 验收与后续边界

按 [玩家指南](new-industrial-stage-guide.md) 和 [实机验收步骤](morning-acceptance.md) 操作。槽位说明见 [槽位参考](device-slot-reference.md)，模型规则见 [模型与贴图规范](models-and-slot-guidelines.md)，全部资源索引位于 `art/model-catalog.json`。

未完成：外部聊天 LLM 的实时工厂快照及受确认命令提案、原生机器安全控制适配、完整科研解锁、全工厂库存盘点、任意第三方转换/隐藏配方接口覆盖。不得把本地控制器等同于外部 LLM 已能操作全部设备。

原生 IC2 两类高炉共用配方表；固定“钛用一炉、钨只用另一炉”的严格等级差尚无法由已验证公开表表达。模型尺度和清晰度、原生槽位提示位置、资源重载/世界加载仍待实机结果。

## 修改文件列表

- `AGENTS.md`
- `README.md`
- `art/README.md`
- `art/model-catalog.json`
- `art/sources/generate-ai-knowledge.ps1`
- `build.gradle`
- `docs/content-inventory.md`
- `docs/core-compatibility-and-process-rules.md`
- `docs/development-status.md`
- `docs/ic2-create-integration.md`
- `docs/morning-acceptance.md`
- `docs/overnight-development.md`
- `scripts/runtime-mods.json`
- `src/main/java/com/ic2newera/IC2NewEra.java`
- `src/main/java/com/ic2newera/integration/jei/IC2NewEraJeiPlugin.java`
- `src/main/java/com/ic2newera/integration/llm/LLMChatClient.java`
- `src/main/java/com/ic2newera/integration/llm/LLMKnowledgeBase.java`
- `src/main/java/com/ic2newera/network/NetworkHandler.java`
- `src/main/java/com/ic2newera/technology/TechnologyService.java`
- `src/main/resources/assets/ic2newera/lang/en_us.json`
- `src/main/resources/assets/ic2newera/lang/zh_cn.json`
- `src/main/resources/data/forge/tags/items/dusts.json`
- `src/main/resources/data/forge/tags/items/ingots.json`
- `src/main/resources/data/forge/tags/items/ores.json`
- `src/main/resources/data/ic2newera/ai/knowledge_zh.md`

## 新增文件列表

- `art/sources/generate-core-alloy-ingots.py`
- `art/sources/generate-core-mineral-assets.py`
- `art/sources/generate-electronic-network-assets.py`
- `docs/device-slot-reference.md`
- `docs/ic2-api-audit-2026-10-07.md`
- `docs/integrated-current-spec.md`
- `docs/integrated-stage-delivery.md`
- `docs/models-and-slot-guidelines.md`
- `docs/new-industrial-stage-guide.md`
- `gradle/process-recipes.gradle`
- `scripts/update-industrial-resources.py`
- `scripts/update-process-graph.py`
- `src/main/java/com/ic2newera/block/ModMineralBlocks.java`
- `src/main/java/com/ic2newera/block/ModProcessorBlocks.java`
- `src/main/java/com/ic2newera/client/IndustrialClientEvents.java`
- `src/main/java/com/ic2newera/client/NativeSlotHelp.java`
- `src/main/java/com/ic2newera/client/ProcessorClientEvents.java`
- `src/main/java/com/ic2newera/client/ui/IndustrialScreen.java`
- `src/main/java/com/ic2newera/client/ui/ProcessorScreen.java`
- `src/main/java/com/ic2newera/industrial/ControllableMachine.java`
- `src/main/java/com/ic2newera/industrial/IndustrialBlockEntities.java`
- `src/main/java/com/ic2newera/industrial/IndustrialBlocks.java`
- `src/main/java/com/ic2newera/industrial/IndustrialControlServerBlockEntity.java`
- `src/main/java/com/ic2newera/industrial/IndustrialFiberBlockEntity.java`
- `src/main/java/com/ic2newera/industrial/IndustrialItems.java`
- `src/main/java/com/ic2newera/industrial/IndustrialMachineInspector.java`
- `src/main/java/com/ic2newera/industrial/IndustrialMenu.java`
- `src/main/java/com/ic2newera/industrial/IndustrialMenus.java`
- `src/main/java/com/ic2newera/industrial/IndustrialNetworkIndex.java`
- `src/main/java/com/ic2newera/industrial/IndustrialNetworkPackets.java`
- `src/main/java/com/ic2newera/industrial/IndustrialNetworkSwitchBlockEntity.java`
- `src/main/java/com/ic2newera/industrial/IndustrialNetworkTopology.java`
- `src/main/java/com/ic2newera/industrial/MachineNetworkAdapterBlockEntity.java`
- `src/main/java/com/ic2newera/industrial/OwnedIndustrialBlockEntity.java`
- `src/main/java/com/ic2newera/integration/jei/ProcessorJeiCategory.java`
- `src/main/java/com/ic2newera/item/AlloyMaterials.java`
- `src/main/java/com/ic2newera/item/CoreMaterials.java`
- `src/main/java/com/ic2newera/item/ElectronicMaterials.java`
- `src/main/java/com/ic2newera/machine/ProcessorBlock.java`
- `src/main/java/com/ic2newera/machine/ProcessorBlockEntities.java`
- `src/main/java/com/ic2newera/machine/ProcessorBlockEntity.java`
- `src/main/java/com/ic2newera/machine/ProcessorKind.java`
- `src/main/java/com/ic2newera/machine/ProcessorSlotRoles.java`
- `src/main/java/com/ic2newera/menu/ProcessorMenu.java`
- `src/main/java/com/ic2newera/network/ProcessGraphPacket.java`
- `src/main/java/com/ic2newera/process/ApprovedProcessKnowledge.java`
- `src/main/java/com/ic2newera/process/ProcessGraph.java`
- `src/main/java/com/ic2newera/process/ProcessGraphClientState.java`
- `src/main/java/com/ic2newera/process/ProcessGraphSync.java`
- `src/main/java/com/ic2newera/process/ProductionPlanner.java`
- `src/main/java/com/ic2newera/process/RecipeGuard.java`
- `src/main/java/com/ic2newera/recipe/ModProcessorRecipes.java`
- `src/main/java/com/ic2newera/recipe/ProcessorRecipe.java`
- `src/main/java/com/ic2newera/recipe/ProcessorRecipeSerializer.java`
- `src/main/java/com/ic2newera/technology/ProcessTechnologyCatalog.java`
- `src/main/java/com/ic2newera/worldgen/MineralWorldgen.java`
- `src/main/resources/assets/ic2newera/blockstates/chromium_ore.json`
- `src/main/resources/assets/ic2newera/blockstates/electronic_fabricator.json`
- `src/main/resources/assets/ic2newera/blockstates/industrial_control_server.json`
- `src/main/resources/assets/ic2newera/blockstates/industrial_fiber.json`
- `src/main/resources/assets/ic2newera/blockstates/industrial_network_switch.json`
- `src/main/resources/assets/ic2newera/blockstates/machine_network_adapter.json`
- `src/main/resources/assets/ic2newera/blockstates/nickel_ore.json`
- `src/main/resources/assets/ic2newera/blockstates/non_metal_processor.json`
- `src/main/resources/assets/ic2newera/blockstates/titanium_ore.json`
- `src/main/resources/assets/ic2newera/blockstates/tungsten_ore.json`
- `src/main/resources/assets/ic2newera/models/block/chromium_ore.json`
- `src/main/resources/assets/ic2newera/models/block/electronic_fabricator.json`
- `src/main/resources/assets/ic2newera/models/block/industrial_control_server.json`
- `src/main/resources/assets/ic2newera/models/block/industrial_fiber.json`
- `src/main/resources/assets/ic2newera/models/block/industrial_network_switch.json`
- `src/main/resources/assets/ic2newera/models/block/machine_network_adapter.json`
- `src/main/resources/assets/ic2newera/models/block/nickel_ore.json`
- `src/main/resources/assets/ic2newera/models/block/non_metal_processor.json`
- `src/main/resources/assets/ic2newera/models/block/titanium_ore.json`
- `src/main/resources/assets/ic2newera/models/block/tungsten_ore.json`
- `src/main/resources/assets/ic2newera/models/item/chromium_dust.json`
- `src/main/resources/assets/ic2newera/models/item/chromium_ingot.json`
- `src/main/resources/assets/ic2newera/models/item/chromium_ore.json`
- `src/main/resources/assets/ic2newera/models/item/cpu.json`
- `src/main/resources/assets/ic2newera/models/item/electronic_fabricator.json`
- `src/main/resources/assets/ic2newera/models/item/gpu.json`
- `src/main/resources/assets/ic2newera/models/item/high_purity_silicon.json`
- `src/main/resources/assets/ic2newera/models/item/impure_chromium_dust.json`
- `src/main/resources/assets/ic2newera/models/item/impure_nickel_dust.json`
- `src/main/resources/assets/ic2newera/models/item/impure_titanium_dust.json`
- `src/main/resources/assets/ic2newera/models/item/impure_tungsten_dust.json`
- `src/main/resources/assets/ic2newera/models/item/industrial_ai_core.json`
- `src/main/resources/assets/ic2newera/models/item/industrial_control_server.json`
- `src/main/resources/assets/ic2newera/models/item/industrial_fiber.json`
- `src/main/resources/assets/ic2newera/models/item/industrial_network_switch.json`
- `src/main/resources/assets/ic2newera/models/item/industrial_ssd.json`
- `src/main/resources/assets/ic2newera/models/item/integrated_circuit.json`
- `src/main/resources/assets/ic2newera/models/item/invar_ingot.json`
- `src/main/resources/assets/ic2newera/models/item/machine_network_adapter.json`
- `src/main/resources/assets/ic2newera/models/item/nichrome_ingot.json`
- `src/main/resources/assets/ic2newera/models/item/nickel_dust.json`
- `src/main/resources/assets/ic2newera/models/item/nickel_ingot.json`
- `src/main/resources/assets/ic2newera/models/item/nickel_ore.json`
- `src/main/resources/assets/ic2newera/models/item/non_metal_processor.json`
- `src/main/resources/assets/ic2newera/models/item/pcb.json`
- `src/main/resources/assets/ic2newera/models/item/quartz_dust.json`
- `src/main/resources/assets/ic2newera/models/item/ram.json`
- `src/main/resources/assets/ic2newera/models/item/silicon.json`
- `src/main/resources/assets/ic2newera/models/item/stainless_steel_ingot.json`
- `src/main/resources/assets/ic2newera/models/item/titanium_alloy_ingot.json`
- `src/main/resources/assets/ic2newera/models/item/titanium_dust.json`
- `src/main/resources/assets/ic2newera/models/item/titanium_ingot.json`
- `src/main/resources/assets/ic2newera/models/item/titanium_ore.json`
- `src/main/resources/assets/ic2newera/models/item/tool_steel_ingot.json`
- `src/main/resources/assets/ic2newera/models/item/tungsten_carbide_ingot.json`
- `src/main/resources/assets/ic2newera/models/item/tungsten_dust.json`
- `src/main/resources/assets/ic2newera/models/item/tungsten_ingot.json`
- `src/main/resources/assets/ic2newera/models/item/tungsten_ore.json`
- `src/main/resources/assets/ic2newera/textures/block/chromium_ore.png`
- `src/main/resources/assets/ic2newera/textures/block/electronic_fabricator_front.png`
- `src/main/resources/assets/ic2newera/textures/block/electronic_fabricator_side.png`
- `src/main/resources/assets/ic2newera/textures/block/electronic_fabricator_top.png`
- `src/main/resources/assets/ic2newera/textures/block/industrial_control_server_front.png`
- `src/main/resources/assets/ic2newera/textures/block/industrial_control_server_side.png`
- `src/main/resources/assets/ic2newera/textures/block/industrial_control_server_top.png`
- `src/main/resources/assets/ic2newera/textures/block/industrial_fiber.png`
- `src/main/resources/assets/ic2newera/textures/block/industrial_network_switch_front.png`
- `src/main/resources/assets/ic2newera/textures/block/industrial_network_switch_side.png`
- `src/main/resources/assets/ic2newera/textures/block/industrial_network_switch_top.png`
- `src/main/resources/assets/ic2newera/textures/block/machine_network_adapter_front.png`
- `src/main/resources/assets/ic2newera/textures/block/machine_network_adapter_side.png`
- `src/main/resources/assets/ic2newera/textures/block/machine_network_adapter_top.png`
- `src/main/resources/assets/ic2newera/textures/block/nickel_ore.png`
- `src/main/resources/assets/ic2newera/textures/block/non_metal_processor_front.png`
- `src/main/resources/assets/ic2newera/textures/block/non_metal_processor_side.png`
- `src/main/resources/assets/ic2newera/textures/block/non_metal_processor_top.png`
- `src/main/resources/assets/ic2newera/textures/block/titanium_ore.png`
- `src/main/resources/assets/ic2newera/textures/block/tungsten_ore.png`
- `src/main/resources/assets/ic2newera/textures/item/chromium_dust.png`
- `src/main/resources/assets/ic2newera/textures/item/chromium_ingot.png`
- `src/main/resources/assets/ic2newera/textures/item/cpu.png`
- `src/main/resources/assets/ic2newera/textures/item/gpu.png`
- `src/main/resources/assets/ic2newera/textures/item/high_purity_silicon.png`
- `src/main/resources/assets/ic2newera/textures/item/impure_chromium_dust.png`
- `src/main/resources/assets/ic2newera/textures/item/impure_nickel_dust.png`
- `src/main/resources/assets/ic2newera/textures/item/impure_titanium_dust.png`
- `src/main/resources/assets/ic2newera/textures/item/impure_tungsten_dust.png`
- `src/main/resources/assets/ic2newera/textures/item/industrial_ai_core.png`
- `src/main/resources/assets/ic2newera/textures/item/industrial_ssd.png`
- `src/main/resources/assets/ic2newera/textures/item/integrated_circuit.png`
- `src/main/resources/assets/ic2newera/textures/item/invar_ingot.png`
- `src/main/resources/assets/ic2newera/textures/item/nichrome_ingot.png`
- `src/main/resources/assets/ic2newera/textures/item/nickel_dust.png`
- `src/main/resources/assets/ic2newera/textures/item/nickel_ingot.png`
- `src/main/resources/assets/ic2newera/textures/item/pcb.png`
- `src/main/resources/assets/ic2newera/textures/item/quartz_dust.png`
- `src/main/resources/assets/ic2newera/textures/item/ram.png`
- `src/main/resources/assets/ic2newera/textures/item/silicon.png`
- `src/main/resources/assets/ic2newera/textures/item/stainless_steel_ingot.png`
- `src/main/resources/assets/ic2newera/textures/item/titanium_alloy_ingot.png`
- `src/main/resources/assets/ic2newera/textures/item/titanium_dust.png`
- `src/main/resources/assets/ic2newera/textures/item/titanium_ingot.png`
- `src/main/resources/assets/ic2newera/textures/item/tool_steel_ingot.png`
- `src/main/resources/assets/ic2newera/textures/item/tungsten_carbide_ingot.png`
- `src/main/resources/assets/ic2newera/textures/item/tungsten_dust.png`
- `src/main/resources/assets/ic2newera/textures/item/tungsten_ingot.png`
- `src/main/resources/data/forge/biome_modifier/add_chromium_ore.json`
- `src/main/resources/data/forge/biome_modifier/add_nickel_ore.json`
- `src/main/resources/data/forge/biome_modifier/add_titanium_ore.json`
- `src/main/resources/data/forge/biome_modifier/add_tungsten_ore.json`
- `src/main/resources/data/forge/tags/blocks/ores.json`
- `src/main/resources/data/forge/tags/blocks/ores/chromium.json`
- `src/main/resources/data/forge/tags/blocks/ores/nickel.json`
- `src/main/resources/data/forge/tags/blocks/ores/titanium.json`
- `src/main/resources/data/forge/tags/blocks/ores/tungsten.json`
- `src/main/resources/data/forge/tags/items/dusts/chromium.json`
- `src/main/resources/data/forge/tags/items/dusts/impure_chromium.json`
- `src/main/resources/data/forge/tags/items/dusts/impure_nickel.json`
- `src/main/resources/data/forge/tags/items/dusts/impure_titanium.json`
- `src/main/resources/data/forge/tags/items/dusts/impure_tungsten.json`
- `src/main/resources/data/forge/tags/items/dusts/nickel.json`
- `src/main/resources/data/forge/tags/items/dusts/titanium.json`
- `src/main/resources/data/forge/tags/items/dusts/tungsten.json`
- `src/main/resources/data/forge/tags/items/ingots/chromium.json`
- `src/main/resources/data/forge/tags/items/ingots/invar.json`
- `src/main/resources/data/forge/tags/items/ingots/nichrome.json`
- `src/main/resources/data/forge/tags/items/ingots/nickel.json`
- `src/main/resources/data/forge/tags/items/ingots/stainless_steel.json`
- `src/main/resources/data/forge/tags/items/ingots/titanium.json`
- `src/main/resources/data/forge/tags/items/ingots/titanium_alloy.json`
- `src/main/resources/data/forge/tags/items/ingots/tool_steel.json`
- `src/main/resources/data/forge/tags/items/ingots/tungsten.json`
- `src/main/resources/data/forge/tags/items/ingots/tungsten_carbide.json`
- `src/main/resources/data/forge/tags/items/ores/chromium.json`
- `src/main/resources/data/forge/tags/items/ores/nickel.json`
- `src/main/resources/data/forge/tags/items/ores/titanium.json`
- `src/main/resources/data/forge/tags/items/ores/tungsten.json`
- `src/main/resources/data/ic2newera/loot_tables/blocks/chromium_ore.json`
- `src/main/resources/data/ic2newera/loot_tables/blocks/electronic_fabricator.json`
- `src/main/resources/data/ic2newera/loot_tables/blocks/industrial_control_server.json`
- `src/main/resources/data/ic2newera/loot_tables/blocks/industrial_fiber.json`
- `src/main/resources/data/ic2newera/loot_tables/blocks/industrial_network_switch.json`
- `src/main/resources/data/ic2newera/loot_tables/blocks/machine_network_adapter.json`
- `src/main/resources/data/ic2newera/loot_tables/blocks/nickel_ore.json`
- `src/main/resources/data/ic2newera/loot_tables/blocks/non_metal_processor.json`
- `src/main/resources/data/ic2newera/loot_tables/blocks/titanium_ore.json`
- `src/main/resources/data/ic2newera/loot_tables/blocks/tungsten_ore.json`
- `src/main/resources/data/ic2newera/process_graph/main.json`
- `src/main/resources/data/ic2newera/worldgen/configured_feature/chromium_ore.json`
- `src/main/resources/data/ic2newera/worldgen/configured_feature/nickel_ore.json`
- `src/main/resources/data/ic2newera/worldgen/configured_feature/titanium_ore.json`
- `src/main/resources/data/ic2newera/worldgen/configured_feature/tungsten_ore.json`
- `src/main/resources/data/ic2newera/worldgen/placed_feature/chromium_ore.json`
- `src/main/resources/data/ic2newera/worldgen/placed_feature/nickel_ore.json`
- `src/main/resources/data/ic2newera/worldgen/placed_feature/titanium_ore.json`
- `src/main/resources/data/ic2newera/worldgen/placed_feature/tungsten_ore.json`
- `src/main/resources/data/minecraft/tags/blocks/mineable/pickaxe.json`
- `src/main/resources/data/minecraft/tags/blocks/needs_iron_tool.json`

## 从 ProcessGraph 派生的配方资源

以下 51 个注册资源由 `gradle/process-recipes.gradle` 生成，位于 `build/generated-process-resources/`，不在源码中复制维护 BOM。

- `data/ic2newera/ic2_recipes/macerator/nickel_macerate.json`
- `data/ic2newera/recipes/nickel_crush.json`
- `data/ic2newera/recipes/nickel_wash.json`
- `data/ic2newera/recipes/nickel_legacy_smelting.json`
- `data/ic2newera/recipes/nickel_clean_smelting.json`
- `data/ic2newera/recipes/nickel_legacy_blasting.json`
- `data/ic2newera/recipes/nickel_clean_blasting.json`
- `data/ic2newera/ic2_recipes/macerator/chromium_macerate.json`
- `data/ic2newera/recipes/chromium_crush.json`
- `data/ic2newera/recipes/chromium_wash.json`
- `data/ic2newera/recipes/chromium_legacy_smelting.json`
- `data/ic2newera/recipes/chromium_clean_smelting.json`
- `data/ic2newera/recipes/chromium_legacy_blasting.json`
- `data/ic2newera/recipes/chromium_clean_blasting.json`
- `data/ic2newera/ic2_recipes/macerator/titanium_macerate.json`
- `data/ic2newera/recipes/titanium_crush.json`
- `data/ic2newera/recipes/titanium_wash.json`
- `data/ic2newera/recipes/titanium_legacy_smelting.json`
- `data/ic2newera/recipes/titanium_legacy_blasting.json`
- `data/ic2newera/ic2_recipes/blast_furnace/titanium_industrial_smelt.json`
- `data/ic2newera/ic2_recipes/macerator/tungsten_macerate.json`
- `data/ic2newera/recipes/tungsten_crush.json`
- `data/ic2newera/recipes/tungsten_wash.json`
- `data/ic2newera/recipes/tungsten_legacy_smelting.json`
- `data/ic2newera/recipes/tungsten_legacy_blasting.json`
- `data/ic2newera/ic2_recipes/blast_furnace/tungsten_industrial_smelt.json`
- `data/ic2newera/ic2_recipes/macerator/quartz_macerate.json`
- `data/ic2newera/recipes/quartz_crush.json`
- `data/ic2newera/recipes/silicon.json`
- `data/ic2newera/recipes/high_purity_silicon.json`
- `data/ic2newera/recipes/pcb.json`
- `data/ic2newera/recipes/integrated_circuit.json`
- `data/ic2newera/recipes/cpu.json`
- `data/ic2newera/recipes/ram.json`
- `data/ic2newera/recipes/gpu.json`
- `data/ic2newera/recipes/industrial_ssd.json`
- `data/ic2newera/ic2_recipes/mixing_furnace/stainless_steel.json`
- `data/ic2newera/ic2_recipes/mixing_furnace/invar.json`
- `data/ic2newera/ic2_recipes/mixing_furnace/nichrome.json`
- `data/ic2newera/ic2_recipes/mixing_furnace/tool_steel.json`
- `data/ic2newera/ic2_recipes/mixing_furnace/titanium_alloy.json`
- `data/ic2newera/ic2_recipes/mixing_furnace/tungsten_carbide.json`
- `data/ic2newera/recipes/non_metal_processor.json`
- `data/ic2newera/recipes/electronic_fabricator.json`
- `data/ic2newera/recipes/machine_network_adapter.json`
- `data/ic2newera/recipes/industrial_fiber.json`
- `data/ic2newera/recipes/industrial_network_switch.json`
- `data/ic2newera/recipes/industrial_control_server.json`
- `data/ic2newera/recipes/industrial_ai_core.json`
- `data/ic2newera/recipes/advanced_circuit.json`
- `data/ic2newera/recipes/complex_circuit.json`

## 可追溯证据

- 构建日志：`build-isolated/industrial-stage-build.log`。
- 包体静态核对：`build-isolated/industrial-stage-resource-audit.json`。
- 安装哈希核对：`build-isolated/industrial-stage-installed-audit.json`。
- 安装回执及备份：`build/deployment/`。
- 文件差异依据：`build-isolated/industrial-stage-file-delta.json`；没有 Git 元数据，Java 清单对照旧包对应顶层类，资源逐字节对照；文档与生成脚本按本轮集成记录补充。
