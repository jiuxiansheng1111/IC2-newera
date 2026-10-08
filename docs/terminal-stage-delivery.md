# 2026-10-08 终端机与服务器玩家 AI 交付

工业控制服务器此前已经加入并安装。本阶段依照本聊天新增终端机；用户澄清要求现有 CPU/GPU/SSD/PCB 等进入真实合成配方，并明确要求箱子和 ProjectE 暗物质。

本阶段源码、最终干净构建、PCL安装和包体核对已经完成。游戏内存取、网络拓扑、多人权限、手持比例和真实模型API仍未实机验收。没有启动/关闭游戏、修改存档、读取玩家API Key、上传或发布。

## 交付证据

| 项目 | 实际结果 |
| --- | --- |
| 最终JAR | build-isolated/releases/ic2newera-2026-10-08-terminal-ai.jar |
| 大小 | 660971字节 |
| SHA-256 | 37E94BBB0CBD96D0F28A332F3DFCB44397A4CB135F0C43F90C20EB4AC57EC97F |
| 最终构建 | gradlew.bat -Dnet.minecraftforge.gradle.check.certs=false clean build -x test --offline，BUILD SUCCESSFUL |
| 构建日志 | build-isolated/terminal-stage-build.log |
| 包体审计 | build-isolated/terminal-stage-artifact-audit.json；289个JSON、2个mcmeta可解析 |
| 资源 | 63个物品模型、11个blockstate、124个中英文同集合语言键；终端几何0–16、6张16×16贴图 |
| 随包指南 | data/ic2newera/ai/industrial_stage_guide_zh.md与当前源文件逐字节一致 |
| 安装实例 | C:\Users\罗斌\Desktop\.minecraft\versions\1.19.2-Forge_43.5.0 |
| 安装文件 | mods/ic2newera-0.1.0-dev.jar |
| 安装时间 | 2026-10-08 09:46:44 +08:00 |
| 安装回执 | build/deployment/pcl-installation.json；8个实际模组JAR哈希再次核对一致 |
| 备份 | 安装脚本保留旧JAR；clean前的回执和备份保留于build-isolated/deployment-history |
| 额度 | 开始全部窗口明确可用，短时剩余90%、周68%；交付检查普通可用、短时63%、周64%，未用重置券/购买额度 |

旧文档曾写旧包有11个blockstate，实际逐包核对旧包为10个，本包为11个；仅新增terminal，没有删掉旧blockstate。

## 玩法与限制

终端机为单方块箱体，深色金属、青色状态屏、紫色数据端口及内置无线天线。容量144格即12×12；小窗口界面12列×3行、共4页，所有槽位服务端同步。手工仓储格允许任意物品，每面的9个幽灵白名单不消耗物品、不占仓储容量，全部为空时才允许全部物品。

默认上方/四侧输入、底部输出。六个世界方向可设关闭/输入/输出/双向。留存0–9216按物品类型合计；输出保留最低数量，双向也以此作为主动拉取目标，纯输入不设目标。每面1–64个/秒只限制AI托管主动搬运，既有漏斗/管道的被动速度由其自身决定。直接相邻终端不互相主动拉推，终端之间通过既有物流连接。

放置后自动接入最近有权限的交换机，光纤优先、WiFi16格。服务器装入AI核心并启用控制，终端开启托管后按已保存规则本地搬运相邻公开Forge物品接口。无模型API时本地托管仍可工作；无数据网络时普通被动仓储和面规则仍可用。光纤/WiFi只传数据，物料使用既有物流，不搬运流体。

实际3×3无序配方，九项各1：原版箱子、PCB、集成电路、CPU、GPU、工业SSD、工业光纤、Create黄铜机壳、ProjectE暗物质。暗物质projecte:dark_matter与黄铜机壳create:brass_casing均核对当前安装包资源/原生配方；其余中间体来自现有注册和ProcessGraph。未装ProjectE时配方与批准路线共同跳过，不提供不含暗物质替代。

右键服务器→AI助手，自动使用当前玩家本机模型配置；没有配置/启用时仍由玩家设置，不后台调用。每次发送前获取经权限检查的当前网络摘要：最多16设备状态、8终端、每终端12种物品库存摘要。普通I键助手不自动扫描工厂，摘要也不代表全厂库存。

模型只能生成指定一面的白名单/方向/留存/速率/托管提案。玩家点击“审阅终端提案”查看中文具体改动，再确认。服务端重验所有者/当前队伍/管理员、同网、服务器AI核心与控制启用、加载状态、终端与服务器实体身份、设置版本以及1800游戏tick期限；票据只能用一次。执行/拒绝结果在游戏聊天显示。生产规划仍遵循ProcessGraph；尚未实现整厂生产执行或所有原生机器遥控。

终端菜单取放、快捷移动、翻页与有效性检查均复查权限，其他玩家不能打开仓储菜单；直接拆除也校验权限。公开物品接口用于实体物流，并非领地或运输设施防盗系统。多人操作方法已进入随包助手指南。

## 规定的25项记录

| 项 | 本阶段结果 |
| --- | --- |
| 1 修改文件列表 | 见下方源码、构建、数据、文档清单 |
| 2 新增文件列表 | 见下方新建清单 |
| 3 删除/废弃 | 没有删除既有游戏ID；修正生成脚本误放于assets的掉落表，数据掉落表保留；未恢复化学体系 |
| 4 新增Item | 1个BlockItem：terminal；新增电子材料0个 |
| 5 新增Block | 1个：terminal |
| 6 四矿 | 现有镍/铬/钛/钨源码、注册、配方随本包保留，未新增矿种，实机仍待确认 |
| 7 含杂质粉 | 四种现有含杂质粉全部保留 |
| 8 水洗 | 四矿批准粉末路线仍必须Create水洗；本阶段没有改矿物阶段 |
| 9 Legacy原矿 | 四矿普通熔炉/高炉各单倍直烧，共8路线，继续保留 |
| 10 镍/铬炉 | 现有净粉普通熔炉/原版高炉/IC2电炉路径保留 |
| 11 钛/钨炉 | 保留核实过的ic2:electric_blast_furnace与ic2:blast_induction_furnace共用高炉表；不虚报严格分炉等级 |
| 12 RecipeGuard | 原有阶段与高级电路精确裁决保留；新增服务器/AI核心/终端批准硬件配方精确保护。不覆盖ProjectE转化和全部第三方私有接口 |
| 13 ProcessGraph | ProjectE已装时4材料、52路线=44批准工业+8legacy；无ProjectE时51路线。终端仅新增1条条件路线 |
| 14 硅路线 | 石英→石英粉→硅→高纯硅现有路线保留 |
| 15 CPU/RAM/GPU/SSD | 现有中间体及电子加工配方保留；终端直接消耗CPU/GPU/SSD/PCB/集成电路 |
| 16 两台加工机 | 非金属处理机与电子材料加工机保留；终端是物流/控制设备，不做材料加工 |
| 17 原生电路 | IC2高级/复杂电路现有改配方保留，仍由同一图生成 |
| 18 网络 | 自动权限入网、光纤/WiFi、已加载端点扫描；本地终端托管和授权快照/确认提案已接入 |
| 19 服务器硬件 | 实际配方仍强制CPU/RAM/工业SSD；没有新增可插拔硬件参数 |
| 20 AI核心GPU | 实际配方强制GPU；终端配方也直接使用GPU |
| 21 检测/控制 | 现有支持的机器问题读取与两处理机暂停/恢复；新增终端本地搬运与确认面规则。未知原生/第三方控制仍只读 |
| 22 规划器 | 当前批准图整数BOM/工序/设备和已知能耗；可规划终端电子中间体链，暗物质作为外部所需材料。不把库存样本当全厂库存 |
| 23 API Key | 源码默认空，未新增硬编码凭据；未读取玩家配置、恢复密钥或调用玩家API。只在玩家发送时以本机配置认证模型 |
| 24 构建/安装/验收 | 最终clean build -x test --offline成功，未新增/运行测试；PCL安装并8哈希核对。游戏及API未实机验收 |
| 25 最终JAR | 本文证据表路径、大小与SHA-256；与安装包、回执一致 |

## 文件清单

修改的Java文件（目录均为src/main/java/com/ic2newera）：

- industrial/OwnedIndustrialBlockEntity.java
- industrial/IndustrialBlocks.java
- industrial/IndustrialBlockEntities.java
- industrial/IndustrialItems.java
- industrial/IndustrialMenus.java
- industrial/IndustrialNetworkTopology.java
- industrial/IndustrialNetworkPackets.java
- industrial/IndustrialControlServerBlockEntity.java
- client/IndustrialClientEvents.java
- client/ui/IndustrialScreen.java
- client/ui/LLMChatScreen.java
- integration/llm/LLMChatClient.java
- process/ProcessGraph.java
- process/RecipeGuard.java

新增Java文件（同一目录）：

- industrial/TerminalBlock.java
- industrial/TerminalBlockEntity.java
- industrial/TerminalMenu.java
- industrial/TerminalProposal.java
- industrial/TerminalProtectionEvents.java
- industrial/FactoryAiService.java
- integration/llm/FactoryAiContext.java
- client/ui/TerminalScreen.java

修改构建/数据/索引：

- gradle/process-recipes.gradle：从required_mods生成Forge配方条件。
- scripts/update-process-graph.py与src/main/resources/data/ic2newera/process_graph/main.json：同一图新增终端路线。
- scripts/update-industrial-resources.py、src/main/resources/assets/ic2newera/lang/zh_cn.json与en_us.json、data/minecraft/tags/blocks/mineable/pickaxe.json。
- scripts/runtime-mods.json、art/model-catalog.json、art/README.md。

新增游戏资源（均位于src/main/resources）：

- assets/ic2newera/models/block/terminal.json
- assets/ic2newera/models/item/terminal.json
- assets/ic2newera/blockstates/terminal.json
- assets/ic2newera/textures/block/terminal_front.png、terminal_back.png、terminal_side.png、terminal_top.png、terminal_bottom.png、terminal_antenna.png
- data/ic2newera/loot_tables/blocks/terminal.json
- 构建生成data/ic2newera/recipes/terminal.json，由图生成而非独立手写配方。

新增资源脚本/预览/文档：

- art/sources/generate-terminal-assets.py
- art/sources/render-terminal-preview.py
- art/preview/terminal-model-preview.png
- docs/terminal-design-and-guide.md
- docs/terminal-stage-delivery.md（本文件）

更新文档：

- docs/new-industrial-stage-guide.md（随包助手知识）
- docs/ai-assistant-setup.md
- docs/device-slot-reference.md
- docs/models-and-slot-guidelines.md
- docs/morning-acceptance.md
- docs/development-status.md
- docs/overnight-development.md
- docs/integrated-current-spec.md
- IC2_New_Era_Development_Goals_and_Progress_2026-10-08.md

当前目录没有Git元数据；没有创建提交、PR或远程上传。上述清单为本阶段实际编辑/生成记录。所有游戏验收项仍留给玩家记录实际结果，不把构建或哈希通过写成游戏运行通过。

## 后续界面修复

2026-10-08收到玩家界面/中断反馈后新增修复包，终端物品描述按用户要求删除，名称只保留“终端机”。原终端阶段哈希保留历史；最新源码、构建、安装及实机状态见[服务器与AI助手修复交付](server-ai-ui-fix-2026-10-08.md)。
