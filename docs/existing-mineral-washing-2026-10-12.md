# 2026-10-12 已有矿物打粉与水洗交付

用户要求所有有普通粉末的矿物遵守自己的流程，随后确认：没有现成普通粉末的不加，红石粉不需要调整。本轮据此补齐中间体，不新增矿石、普通粉副本或加工机。

## 范围与依赖核实

直接核对固定版本 IC2 Classic 2.1.3.4 注册类字节码和 Create 0.5.1.i 资源；已确认 `ic2:dust_diamond` 存在。普通粉末复用如下：

| 材料 | 水洗后普通粉 | 后续阶段 |
| --- | --- | --- |
| 铁、金、铜 | `ic2:dust_iron`、`ic2:dust_gold`、`ic2:dust_copper` | 批准普通熔炼或原有粉末用途 |
| 煤、钻石 | `ic2:dust_coal`、`ic2:dust_diamond` | 原有粉末用途，无自制成锭/返还宝石配方 |
| 石英 | `ic2newera:quartz_dust` | 既有非金属处理机制硅 |
| 锡、银、铝 | `ic2:dust_tin`、`ic2:dust_silver`、`ic2:dust_aluminium` | 批准普通熔炼或原有粉末用途 |
| 锌 | `ic2newera:zinc_dust` | 批准普通熔炼成 Create 锌锭 |

新增 Item **10**：上述材料各一个 `impure_<material>_dust`；新增 Block **0**。四种新增矿石镍/铬/钛/钨原有路线继续使用，共14种受控材料。红石完全保持原流程。青金石、绿宝石、远古残骸和铀没有对应普通粉末，未添加粉末或工业路线。

矿石及其已有深层/下界变体、粗金属先双倍打成杂质粉；旧 Create 碎矿与煤/钻石/石英固体原料一比一打成杂质粉；Create 水洗一比一成为普通粉。成品金属锭一比一回收普通粉，不重复增产。原矿低效单倍直烧保留；固体煤/钻石/石英其他原生用途仍可用。原来的高低碳钢合金炉、石英制硅、电子材料和网络/设备要求保留。

## 源码与资源

- `CoreMaterials.java` 注册10个杂质粉；普通粉不重复注册。
- `scripts/update-process-graph.py` 与 `process_graph/main.json` 共用数量/路线，Gradle生成实际配方。旧锌原料及7个Create碎矿直出普通粉的8个独立粉碎配方和旧锌粉熔炼配方移除；替代路线均从图生成。
- `ProcessGraph.java` 支持原矿变体和非金属可选的成锭阶段，校验矿石/杂质粉转换；煤/钻石/石英固体资源仅打粉转换受清洗规则约束。
- `RecipeGuard.java` 精确拦截 IC2 粉碎机、炉表以及 RecipeManager 中的原矿/碎矿直出净粉、杂质粉直烧等未批准转换；不把普通粉的原生下游用途一并封禁。原有合金炉、高级电路和设备配方裁决保留。未覆盖的其他模组非公开转化接口、ProjectE EMC途径不宣称已全面拦截。
- `ProductionPlanner.java` 解析受控材料的普通粉标签，铁/煤粉和合金规划包含清洗；`ProcessTechnologyCatalog.java`、`ApprovedProcessKnowledge.java` 从同一图提供全部14种材料节点与中文路线。
- `ProcessGraphPacket.java` 同步可选成锭阶段、原矿变体及固体打粉输入；`NetworkHandler.java` 协议版本28，客户端/服务端必须更新到相同版本。记录上限沿用16材料、256路线、512KiB。
- 美术新增10套原创16×16粉堆贴图与item模型、两个语言文件各10键、模型索引、专用可重复生成器和实际资源预览。杂质粉不加入任何普通 `forge:dusts/<material>` 标签；石英普通粉补齐兼容标签。
- README、活动规范、玩家指南、IC2/Create说明、锌科技节点及随包助手知识和生成器已同步。

当前工艺图14种材料、236条路线：179条批准工业、57条旧单倍直烧、14条水洗（包含于批准路线）。与上一安装JAR对照，旧75条图路线中73条数据完整保留，只替换2条石英直接产净粉路线。没有新增或运行测试，没有启动/关闭 Minecraft，没有修改存档或读取玩家模型配置/API Key，没有上传或发布。实机验收尚未执行。

## 构建与安装证据

- 干净构建命令：`gradlew.bat -Dnet.minecraftforge.gradle.check.certs=false clean build -x test --offline`；`BUILD SUCCESSFUL in 30s`。29条弃用API警告，未阻断构建，测试任务未执行。
- 最终JAR：`build-isolated/releases/ic2newera-2026-10-12-mineral-washing.jar`；同时更新本地 `releases/ic2newera-0.1.0-dev.jar`。大小812028字节；SHA-256 `E761E0173CEBA8E00D2E00E9B5983C3E66771B8DC569B427DB1E8BE311EC32E9`。
- JAR中481 JSON、2 mcmeta均可解析；236个图生成配方存在，14条水洗对应正确普通粉，新10张PNG均16×16、模型引用与中文/英文名称齐全；无新红石资源和旧直出净粉桥接配方；随包两份玩家指南与项目源字节相同。
- 清理前核实 `build` 为项目内普通目录，完整保留 `build/deployment` 到 `build-isolated/deployment-history/mineral-washing-20261012-093836/deployment/`，上一发布JAR另存同目录 `previous-addon.jar`。静态源/包核对回执与构建日志在 `build-isolated/inspection/mineral-*.json`、`mineral-washing-build.log`。
- 目标PCL实例未运行时用现有安装脚本更新。时间 `2026-10-12T09:41:12.5174163+08:00`，只替换1个新纪元JAR，8个安装模组实际SHA-256与新回执一致；旧安装包由脚本自动备份。回执 `build/deployment/pcl-installation.json`。
- 活动源码、构建、实际安装已完成；游戏内JEI、机器运行、多人和模型API没有实机验收。本轮未更新 `dist` 分享ZIP，也未上传GitHub。

## 文件记录与25项范围确认

修改文件：8个Java文件为 `item/CoreMaterials.java`、`process/ProcessGraph.java`、`process/RecipeGuard.java`、`process/ProductionPlanner.java`、`process/ApprovedProcessKnowledge.java`、`technology/ProcessTechnologyCatalog.java`、`network/ProcessGraphPacket.java`、`network/NetworkHandler.java`；脚本为 `scripts/update-process-graph.py`、`scripts/update-industrial-resources.py`、`scripts/runtime-mods.json`、`art/sources/generate-ai-knowledge.ps1`；资源为 `data/ic2newera/process_graph/main.json`、`data/ic2newera/technology/zinc_dust.json`、`data/ic2newera/ai/knowledge_zh.md`、两语言JSON、`art/model-catalog.json`；活动文档为AGENTS、README、整合规范、玩家指南、IC2/Create说明、开发状态及本交接文档。现有未提交改动保留，未提交/推送。

新增文件：10个 `assets/ic2newera/models/item/impure_<material>_dust.json` 与10张对应PNG（材料列表见上表）；`data/forge/tags/items/dusts/quartz.json`；`art/sources/generate-existing-mineral-powders.py`；`art/preview/existing-mineral-impure-powders.png`；本交付文档。构建生成的236个配方不另维护副本。

移除文件：旧 `ic2_recipes/macerator/zinc_dust.json`、`crushed_zinc_to_dust.json`、`crushed_tin_to_ic2_dust.json`、`crushed_silver_to_ic2_dust.json`、`crushed_iron_to_ic2_dust.json`、`crushed_gold_to_ic2_dust.json`、`crushed_copper_to_ic2_dust.json`、`crushed_aluminum_to_ic2_dust.json` 和 `recipes/smelting/zinc_ingot_from_dust.json`。图中旧 `quartz_macerate`、`quartz_crush` 已由杂质粉路线接替。

| 规范报告项 | 本轮结果 |
| --- | --- |
| 1 修改文件 | 上述Java、图生成器、资源、指南及安装清单 |
| 2 新增文件 | 20个PNG/模型、净石英粉标签、生成器、预览、交付文档 |
| 3 删除/废弃 | 9个旧独立配方及2条石英直出净粉图路线 |
| 4 新增Item | 10个杂质粉，无普通粉副本 |
| 5 新增Block | 0 |
| 6 四种新矿 | 既有实现保留，4种新增矿石不扩增 |
| 7 杂质粉 | 14种受控材料均有，10种本轮补齐 |
| 8 Create清洗 | 图和生成配方定义为必经阶段；实机待验收 |
| 9 Legacy直烧 | 图含57条单倍旧路线：镍/铬/钛/钨原矿、铁/金/铜/锡/银/铝/锌原矿及粗金属、煤/钻石/石英矿石单倍熔炉路线 |
| 10 镍/铬炉型 | 原版熔炉/高炉及IC2电炉，原有路线未变 |
| 11 钛/钨炉型 | 复用IC2电力高炉/感应高炉共用高炉表，原有路线未变 |
| 12 RecipeGuard | 精确拦截原矿/碎矿直出净粉、杂质粉直烧、固体资源直出净粉；维持高级粉、合金和电子硬件裁决 |
| 13 ProcessGraph | 14材料、236路线，179批准工业/57旧路线 |
| 14 Silicon | 既有普通石英粉制硅/高纯硅保留，前段增加水洗 |
| 15 CPU/RAM/GPU/SSD | 既有电子中间体和配方未变 |
| 16 两台处理机 | 非金属/电子材料处理机保留，无第三台 |
| 17 IC2高级电路 | 原有图配方及中间体要求保留，未修改 |
| 18 网络 | 原有数据网络保留；流程同步协议升为28 |
| 19 服务器CPU/RAM/SSD | 图中的强制材料要求未变 |
| 20 AI核心GPU | 图中的强制GPU要求未变 |
| 21 AI设备诊断 | 原有有界只读快照能力未改动，本轮只扩充批准工艺知识 |
| 22 规划器 | 普通粉标签也展开图路线，铁/煤/铜等BOM包含清洗；未宣称启用全厂自动生产 |
| 23 API Key | 本轮未读取/更改玩家模型配置，未新增密钥或模型调用 |
| 24 Build | 干净离线构建成功，未新增/运行测试；源码/JAR静态核对完成 |
| 25 最终JAR | 上述812028字节JAR，已安装到PCL隔离实例；实机待验收 |

## 玩家验收

在更新版本后查看 JEI：铁/金/铜的普通与深层矿、下界金矿、粗金属，煤/钻石/下界石英及锡/银/铝/锌均先输出杂质粉；杂质粉只在 Create 风扇加水下成为对应现有普通粉。旧 Create 碎矿不能直接烧制或一份再次双倍打成净粉。用净铁粉/煤粉做两种钢、净石英粉制硅；普通粉原生用途、金属锭回收及单倍原矿直烧应可用。红石、青金石、绿宝石和远古残骸继续原有玩法。
