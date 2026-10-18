# 2026-10-10 材料样式与高低碳钢交付

> 此为当天上午的历史交付。玩家随后要求严格采用原版铁锭比例并将金属合金配料全部改为合金炉；下文工作台/Create 钢粉配方和手工锭轮廓已被后续修正替代。当前状态见 [修正交付](vanilla-ingot-and-alloy-furnace-2026-10-10.md)。

## 本轮要求与源码状态

按玩家两张参考图的外形方向重画新纪元自有资源：13 种锭使用宽斜面像素金属条，12 种粉末使用尖顶颗粒堆。全部实际纹理为原创 16×16 RGBA PNG，脚本手工定义像素几何，不采样或复制参考图片、Minecraft 或其他模组贴图。粉末用颜色区分材料，四种含杂质粉保留深色夹杂。低碳钢浅银灰，高碳钢深蓝灰，并有更明显粗颗粒。

玩家明确选择普通铁粉作为钢源。高低碳钢各有粉与锭，总计四种材料；新增两个粉末注册项，移除两个钢坯注册项，Item 总数净变化 0。钢锭及装备原英文 ID 保留，中文名称统一为高碳钢、低碳钢。Block 新增 0，不增加加工机器。

| 材料 | 碳粉∶普通铁粉 | 一批输出 |
| --- | --- | --- |
| 高碳钢粉 | 1∶2 | 2 份高碳钢粉 |
| 低碳钢粉 | 1∶5 | 5 份低碳钢粉 |

碳粉复用已核实的 `ic2:dust_coal`，铁粉使用 `forge:dusts/iron`。原料分别在已有粉碎路线打粉，工作台 3×3 或无加热 Create 搅拌器配粉，再经普通熔炉、IC2 电炉或原版高炉把一份钢粉变成一锭。IC2 粉碎机和 Create 粉碎轮的回收是一锭一份同等级钢粉，不返还碳粉。没有钢坯、碳包、混合胚、第三台加工机或新的化学路线。

上述数量表为本轮交付快照；活动配方、BOM 与 AI 仍以唯一来源 `process_graph/main.json` 为准。本轮增加 12 条批准路线，当前 4 种受控矿物、66 条路线（58 批准工业、8 原矿直烧旧路线）。此前 54 条路线的输入/输出/设备/条件保持一致。

## 规范要求逐项记录

| 项 | 状态 |
| --- | --- |
| 1 修改文件 | 材料注册、RecipeGuard、科技登记、语言、工艺图、标签、知识库、材料模型/纹理、资源生成器和玩家说明；完整清单见本文末尾。 |
| 2 新增文件 | 两粉的模型/纹理/科技节点、两等级的 Forge 粉/锭标签、统一像素生成器、三份预览和本交付记录。 |
| 3 删除/废弃 | 两钢坯注册、两钢坯模型/纹理/科技节点、两合金炉坯配方及两钢坯直烧配方；生成器不再恢复坯或配料包。 |
| 4 新增 Item | 2 个钢粉，删除 2 个钢坯，净变化 0；两钢锭 ID 保留。 |
| 5 新增 Block | 0。 |
| 6 四种矿物 | 当前源码保留镍/铬/钛/钨，本轮修改外观。 |
| 7 含杂质粉 | 四种全部保留，并统一新外形与夹杂标记。 |
| 8 Washing | 四矿的批准打粉路线仍必须水洗；配制的合金钢粉不属于原矿杂质粉。 |
| 9 Legacy | 四种原矿各有熔炉/高炉单倍路线，共 8 条，未改。 |
| 10 镍/铬炉型 | 正常粉保留熔炉、IC2 电炉及原版高炉。 |
| 11 钛/钨炉型 | 正常粉仍用已核实的 IC2 电力高炉/感应高炉共用配方表，不声称严格分炉。 |
| 12 RecipeGuard | 新增钢粉/钢锭产出须匹配工艺图的批准配粉、熔炼或同级回收；原受控矿物裁决保留。不能表述为覆盖所有模组转换接口。 |
| 13 ProcessGraph | 66 条，新增 12 条钢路线；Gradle 从同一图生成工作台/Create/熔炼/IC2 回收配方。 |
| 14 Silicon | 既有石英→石英粉→硅→高纯硅链保留，石英粉外观更新。 |
| 15 电子硬件 | 既有 CPU/RAM/GPU/SSD 等材料和路线保留。 |
| 16 加工机 | 保留非金属处理机、电子材料加工机两台，没有新增机器。 |
| 17 IC2 电路 | 既有高级/复杂电路融合配方未改。 |
| 18 工业网络 | 既有交换机、适配器、光纤、服务器、终端、电动机/增幅器源码保留；本轮未增网络功能。 |
| 19 服务器硬件 | 原工艺图仍强制 CPU/RAM/SSD。 |
| 20 AI Core | 原工艺图仍强制 GPU。 |
| 21 AI 诊断 | 保留上一阶段设备名单/运行与异常读取；本轮仅更新钢材指南、名称和批准路线资料。 |
| 22 Planner | 读取新增钢路线；铁粉与煤粉作为现有原料需求，不虚构原生能耗或全工厂自动生产。 |
| 23 API Key | 本轮未读取、修改、输出或恢复玩家密钥，没有新增密钥实现。 |
| 24 构建 | `clean build -x test --offline` 成功，耗时 3 分 7 秒；未新增或运行测试。 |
| 25 最终 JAR | `build-isolated/releases/ic2newera-2026-10-10-material-style-carbon-steel.jar`，722560 字节，SHA-256 `FF4B39ADACC4134E9EA6AD10EF92E260FA7F3B4E8CBF813B362A31D9DAE92EEC`。固定交付副本 `releases/ic2newera-0.1.0-dev.jar` 同哈希。 |

## 验证层次

- 设计与源码：已实现。科技节点、模型索引、语言与随包知识去掉钢坯路线；两新钢粉与既有钢锭共同构成四种材料。
- 源码资源静态检查：25 个材料图标均为 16×16、0/255 透明度；模型引用、JSON 解析与中英文语言键对应通过。此前 54 条工艺路线逻辑内容未变。
- 构建：授权的 `clean build -x test --offline` 已成功。包内 311 JSON、2 mcmeta 全部可解析，25 张材料 PNG 与源码逐字节一致；12 条钢路线实际配方的输入数量/输出与图一致，两钢粉在打包类中注册，两钢坯在包内没有资源路径或注册。静态回执 `build-isolated/steel-stage-package-audit.json`。清理前安装回执和旧包备份保留在项目内 `build-isolated/deployment-history/steel-stage-20261010-092846`。
- 安装：安装脚本确认 PCL 目标实例未运行后，已向 `1.19.2-Forge_43.5.0` 替换 1 个新纪元 JAR，核对 8 个模组；安装时间 `2026-10-10T09:33:36.4116342+08:00`；安装回执 `build/deployment/pcl-installation.json`，旧包保留在其 `backups/` 中。
- 实机验收：未启动/关闭/强杀游戏，未改存档，尚未验收 JEI、配粉、熔炼、回收、科技树与游戏显示效果。
- 发布：本轮不上传 GitHub，不发布；此前分享 ZIP 对应历史版本。

## 玩家验收

启动已更新实例后，查看 13 种锭、12 种粉外观和高低碳四种材料名称；JEI 应显示高碳 1 煤粉＋2 铁粉→2 钢粉，低碳 1 煤粉＋5 铁粉→5 钢粉。检查工作台与 Create 配粉、钢粉熔炼、两钢锭同级一比一回收，以及科技树/助手不再指向钢坯。四矿的含杂质粉仍需水洗。

实际 PNG 预览：`art/preview/carbon-steel-four-items.png`、`art/preview/current-dust-styles.png`、`art/preview/current-ingot-styles.png`。重建命令：`python art/sources/material_pixel_style.py`。


## 修改、新增和删除文件清单

状态 M 为修改，A 为新增，D 为删除；本地构建/回执/备份在忽略目录，不含于 Git 文件清单。

- `M` `art/README.md`
- `M` `art/model-catalog.json`
- `A` `art/preview/carbon-steel-four-items.png`
- `A` `art/preview/current-dust-styles.png`
- `A` `art/preview/current-ingot-styles.png`
- `M` `art/sources/generate-ai-knowledge.ps1`
- `M` `art/sources/generate-core-alloy-ingots.py`
- `M` `art/sources/generate-core-mineral-assets.py`
- `M` `art/sources/generate-electronic-network-assets.py`
- `M` `art/sources/generate-equipment-assets.ps1`
- `M` `art/sources/generate-high-carbon-steel-assets.ps1`
- `A` `art/sources/material_pixel_style.py`
- `M` `docs/content-inventory.md`
- `M` `docs/development-status.md`
- `M` `docs/integrated-current-spec.md`
- `A` `docs/material-style-and-carbon-steel-2026-10-10.md`
- `M` `docs/new-industrial-stage-guide.md`
- `M` `docs/overnight-development.md`
- `M` `releases/ic2newera-0.1.0-dev.jar`
- `M` `scripts/runtime-mods.json`
- `M` `scripts/update-industrial-resources.py`
- `M` `scripts/update-process-graph.py`
- `M` `src/main/java/com/ic2newera/equipment/ModEquipment.java`
- `M` `src/main/java/com/ic2newera/process/RecipeGuard.java`
- `M` `src/main/java/com/ic2newera/technology/ProductTechnologyCatalog.java`
- `M` `src/main/java/com/ic2newera/technology/TechnologyTree.java`
- `M` `src/main/resources/assets/ic2newera/lang/en_us.json`
- `M` `src/main/resources/assets/ic2newera/lang/zh_cn.json`
- `D` `src/main/resources/assets/ic2newera/models/item/carbon_steel_billet.json`
- `D` `src/main/resources/assets/ic2newera/models/item/high_carbon_steel_billet.json`
- `A` `src/main/resources/assets/ic2newera/models/item/high_carbon_steel_dust.json`
- `A` `src/main/resources/assets/ic2newera/models/item/low_carbon_steel_dust.json`
- `M` `src/main/resources/assets/ic2newera/models/item/tempered_high_carbon_steel_ingot.json`
- `M` `src/main/resources/assets/ic2newera/models/item/zinc_dust.json`
- `M` `src/main/resources/assets/ic2newera/textures/item/aluminium_bronze_ingot.png`
- `D` `src/main/resources/assets/ic2newera/textures/item/carbon_steel_billet.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/chromium_dust.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/chromium_ingot.png`
- `D` `src/main/resources/assets/ic2newera/textures/item/high_carbon_steel_billet.png`
- `A` `src/main/resources/assets/ic2newera/textures/item/high_carbon_steel_dust.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/impure_chromium_dust.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/impure_nickel_dust.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/impure_titanium_dust.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/impure_tungsten_dust.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/invar_ingot.png`
- `A` `src/main/resources/assets/ic2newera/textures/item/low_carbon_steel_dust.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/nichrome_ingot.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/nickel_dust.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/nickel_ingot.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/quartz_dust.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/stainless_steel_ingot.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/tempered_high_carbon_steel_ingot.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/tempered_steel_ingot.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/titanium_alloy_ingot.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/titanium_dust.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/titanium_ingot.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/tool_steel_ingot.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/tungsten_carbide_ingot.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/tungsten_dust.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/tungsten_ingot.png`
- `M` `src/main/resources/assets/ic2newera/textures/item/zinc_dust.png`
- `M` `src/main/resources/data/forge/tags/items/dusts.json`
- `A` `src/main/resources/data/forge/tags/items/dusts/high_carbon_steel.json`
- `A` `src/main/resources/data/forge/tags/items/dusts/low_carbon_steel.json`
- `M` `src/main/resources/data/forge/tags/items/ingots.json`
- `A` `src/main/resources/data/forge/tags/items/ingots/high_carbon_steel.json`
- `A` `src/main/resources/data/forge/tags/items/ingots/low_carbon_steel.json`
- `M` `src/main/resources/data/ic2newera/ai/knowledge_zh.md`
- `D` `src/main/resources/data/ic2newera/ic2_recipes/mixing_furnace/carbon_steel_billet.json`
- `D` `src/main/resources/data/ic2newera/ic2_recipes/mixing_furnace/high_carbon_steel_billet.json`
- `M` `src/main/resources/data/ic2newera/process_graph/main.json`
- `D` `src/main/resources/data/ic2newera/recipes/smelting/high_carbon_steel_ingot_from_billet.json`
- `D` `src/main/resources/data/ic2newera/recipes/smelting/steel_ingot_from_billet.json`
- `D` `src/main/resources/data/ic2newera/technology/high_carbon_steel_billet.json`
- `A` `src/main/resources/data/ic2newera/technology/high_carbon_steel_dust.json`
- `A` `src/main/resources/data/ic2newera/technology/low_carbon_steel_dust.json`
- `M` `src/main/resources/data/ic2newera/technology/refined_iron.json`
- `D` `src/main/resources/data/ic2newera/technology/steel_billet.json`
- `M` `src/main/resources/data/ic2newera/technology/tempered_high_carbon_steel_equipment.json`
- `M` `src/main/resources/data/ic2newera/technology/tempered_high_carbon_steel_ingot.json`
- `M` `src/main/resources/data/ic2newera/technology/tempered_steel_axe.json`
- `M` `src/main/resources/data/ic2newera/technology/tempered_steel_boots.json`
- `M` `src/main/resources/data/ic2newera/technology/tempered_steel_chestplate.json`
- `M` `src/main/resources/data/ic2newera/technology/tempered_steel_equipment.json`
- `M` `src/main/resources/data/ic2newera/technology/tempered_steel_ingot.json`
- `M` `src/main/resources/data/ic2newera/technology/tempered_steel_leggings.json`
