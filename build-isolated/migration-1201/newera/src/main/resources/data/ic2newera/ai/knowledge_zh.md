# IC2 新纪元：工业与电子知识

本指南面向 Minecraft 1.20.1 / Forge 47.4.28 的 r49 版本，覆盖受控矿物、合金、电子材料、AE2、材料处理机和工业网络。目标 IC2 实现是 IC2 Refactored 2.10.33-ex120，不是 IC2 Classic。化学实验、反应链与化工机器已退出当前范围，不能当作可用玩法推荐。

## 新纪元进度入口

独立科技树已移除，改为原版「进度」中的「IC2：新纪元」独立分页。68个节点覆盖本模组的重要材料、设备、动力转换、伙伴与装备；其他模组保留各自分页，进度不作为合成或使用门槛。

## 唯一配方与数量来源

新纪元配方路线的权威数据是服务端加载的 ProcessGraph。数据包 reload 后由服务器同步到客户端；AI 对具体工业问题附加当前服务器已批准路线，生产规划也读取服务器当前图。配方步骤、物品 ID、输入/输出数量、工艺类型、设备及批次数不得从本静态知识文档推算或另写一份 BOM。

若客户端还未收到服务器工艺图，AI 应明确无法核实具体路线，不得猜测。最终可制作配方以当前 JEI/机器界面为准；指南只说明用途、分工与边界。

## 1.20.1 当前模组与新纪元的分工

- IC2 Refactored 2.10.33-ex120 提供当前迁移目标中的 EU、电网、储能和原生加工机器；不要把它称为 IC2 Classic，也不要因名称相似假定两者的 ID、配方、界面或 API 相同。
- Create 提供转速、机械加工、粉碎轮、风扇水洗、物品物流与机械自动化。
- EU 与 Create 转速网络各自独立；新纪元电动机将 EU 转为转轴动力，机械发电机消耗 Create 应力和转速产生 EU，均有初级和高级。工业网络只传数据，不承载电力、转速、物品或流体。
- 新纪元新增矿物阶段与合金、电子材料、两台材料处理机、工业网络和生产规划；不重做 IC2/Create 原生设备。

## IC2 Classic 2.1.3.4 / Minecraft 1.19.2 历史界面参考（不适用于1.20.1 IC2R）

以下 I/C/S、槽位和 ID 说明仅记录 IC2 Classic 2.1.3.4 的 1.19.2 界面，不能据此解释 1.20.1 IC2R 截图，也不能据此推断当前注册 ID 或可用升级。1.20.1 请以实际 IC2R 界面、注册表、JEI 和服务器同步 ProcessGraph 为准。

在该历史版本中，玩家截图左上角的 I、C、S 是三个独立界面按钮，不是合在一起的“ICS”缩写，也不是机器状态灯；悬停名称分别为 I = 机器访问控制（Machine Access Control），C = 比较器设置（Comparator Settings），S = 格子过滤器信息（Slot Filter Info）。同一套按钮会在提供相应界面组件的 IC2 Classic 机器 GUI 中重复出现。

- **I：机器访问控制。** 打开按机器方位配置的面板。D/U/N/S/W/E 分别是下、上、北、南、西、东；这里的 S 是“南”，和左上角的 S 按钮不是一回事。绿色勾选开关控制该面是否可被外部库存访问；旁边标成 B 的按钮循环该面的物品规则：禁止、导入、导出、双向。点选某个库存格时还可针对该格设置访问规则。底部选项限制每格只放 1 个物品、每种物品只放 1 组；红色叉号表示该限制当前关闭，点击可切换，“重置”恢复默认设置。这些选项用于管道/自动化的输入输出，不改变机器配方。
- **C：比较器设置。** 配置机器向红石比较器提供的状态信号。机器支持哪些来源取决于机器本身，常见来源包括库存填充、EU 缓冲、运行/进度或燃料状态；页面可设置最小/最大/平均评估与反转。它控制红石读数，不负责选配方、输入物品或机器供电。
- **S：格子过滤器信息 / 有效格子输入。** 查看所选机器槽位允许的物品。可用条目随机器、槽位和当前已注册配方而变化；它说明“哪些物品能放进这个格子”，不是额外的物品槽，也不是全局配方搜索。把正确原料放进 Macerator 的输入格，确切配方及数量仍以当前 JEI 为准。

在该历史版本中，粉碎机 ID 为 `ic2:macerator`；左侧闪电形条是 EU 能量/充能显示，箭头形条是加工进度，右侧竖排四格为升级位。上述说明不代表 IC2R 当前使用相同 ID、UI 或槽位规则。

## 已有矿物的打粉与水洗

铁、金、铜、煤、钻石、石英、锡、银、铅、铝、锌等已有普通粉末的矿物纳入统一路线。矿石、深层/下界变体、粗金属及旧 Create 碎矿按批准图先得到含杂质粉；可选择Create风扇水洗得到洗后中间物，再经IC2原生离心机取得较高产量的最终净粉，也可按图把含杂质粉直接离心取得较低产量。图中配方数量与优先级是唯一依据。IC2原生 `crushed_*` 仅作为杂质阶段的旧物品别名，`purified_*` 对应洗后中间物；二者不能与最终净粉混称。煤、钻石和石英的固体资源打粉也遵循图中流程，原有燃料、合成、装备用途照常使用；钻石最终净粉复用目标版本 IC2 的 `ic2:diamond_dust`。煤/钻石/石英路线止于粉末，不虚构熔炼成锭或从粉返还宝石的步骤。

红石粉按玩家要求保持原流程；青金石、绿宝石和远古残骸没有普通粉，不新增粉或路线。铀继续保留IC2原生核燃料来源与副产物路线，不纳入普通净粉流程。已制成金属锭的同级打粉回收直接得到普通粉；最终净铁粉仍进入批准合金路线制作高低碳钢，普通石英净粉仍用于制硅。原矿单倍直烧保留低效旧路线；工业规划须读取图中的阶段、机器和产量。

## 四种矿物路线

镍、铬、钛、钨从矿石经粉碎得到含杂质粉，并可选择Create水洗后用IC2离心机取得较高产量的最终净粉，或直接离心得到较低产量。净粉后续加工仍按服务端批准路线决定。旧矿物的Crushed/Purified物品按图列为兼容别名或洗后阶段。矿石旧直炼是否保留由当前图和RecipeGuard分类决定；不要把阶段物品、原生旧配方或网上攻略当作新纪元批准路线。

## 高低碳钢粉与钢锭

高低碳钢各有粉末和钢锭两个形态。普通铁粉和 IC2 煤粉分别打粉后，在 IC2 合金炉配成对应钢粉，再熔炼成锭；没有钢坯、配料包或额外碳中间体。青铜、黄铜和新纪元其他金属合金的配料也只走合金炉，不能推荐工作台混粉或 Create 搅拌旁路。输入比例、批次数与产量必须读取当前服务器 ProcessGraph，不能由此静态文档另行推算。两种钢锭重新打粉只回收同等级粉末；已制成合金的块/粒拆分和装备制作仅使用批准配方。

## 两台材料处理机

- 非金属处理机：处理新纪元石英粉与硅材料，生成硅和高纯硅。它有配方输入槽与产物槽，输入槽只接受当前配方在该位置要求的物品。
- 电子材料加工机：制作 PCB、集成电路、CPU、RAM、GPU、工业固态硬盘。它的输入按配方位置排列，输出槽用于取出产物。服务端槽位验证是准入权威，悬停说明不能改变验证范围。
- 工业设备的 EU/转速来源各自独立；精确供能、配方和机器条件查看 JEI 与运行时状态。

## 电子材料与原生部件复用

下界石英是硅工业来源，不新增硅矿。九种新纪元电子材料为石英粉、硅、高纯硅、PCB、集成电路、CPU、RAM、GPU 和工业固态硬盘。服务器请求所需产量或查询生产方案时以当前同步工艺图为准。

旧版 IC2 Classic 的电路 ID 与 `ic2:alloy_smelter` 不应作为 1.20.1 的活动配方输入或机器 ID。迁移决定已批准新增新纪元合金兼容炉与高级电炉；二者是当前范围内的新增设备，不因 IC2R 替换而取消。目标版本实际物品 ID、配方和设备条件统一从服务端 ProcessGraph、注册表及 JEI 核对，不在本静态指南里沿用 Classic ID。

## 工业网络和控制

工业交换机提供 Wi-Fi 与光纤网络。交换机网络名需匹配；适配器自动关联符合权限的最近交换机，优先采用相邻工业光纤链路。将适配器贴近支持的方块实体，可查看设备状态；自动控制只支持实现本项目控制接口的机器。IC2/Create 公共状态读取不代表所有第三方机器都可远程控制。

工业控制服务器通过网络查看状态、按物品 ID 请求服务器端生产规划，并可在授权后启用支持设备的自动暂停/恢复。工业 AI 核心必须安装在服务器专用槽内。规划结果不是库存盘点，也不会自动保证机器或材料齐全；网络不要求玩家配置 IP、端口、网关、DNS 或硬件参数。

## 资料与版本边界

1.20.1 的 IC2R 与 Create 操作查目标实例 JEI、IC2R 当前手册/界面和 Create Ponder；IC2 Classic 及其 1.19 文档只作历史参考。静态指南、旧版本教程或旧安装包不能覆盖当前服务器 ProcessGraph。PCL2 历史安装记录对应先前交付版本，不代表本次新工业阶段已实机验收。

## 终端移除与网络保留

旧新纪元终端不再承担新仓储、合成、远程托管或生产中转。其方块/物品/方块实体/菜单注册仍保留用于从旧存档取回库存；终端不进入工业网络，普通创造物品栏与 JEI 中隐藏。不要移除这些兼容注册或恢复菜单，否则旧终端中的物品可能无法取回。

## AE2：仓储、终端与自动合成

Applied Energistics 2（AE2）是本整合包唯一负责 ME 网络仓储、终端检索及样板自动合成的系统。玩家使用 AE2 原生 ME Terminal、Pattern Provider（样板供应器）和 Storage Bus（存储总线）搭建网络；新纪元不另造 ME 库存、终端、样板队列或合成提交系统。AE2 终端显示的是接入该 ME 网络且可访问的存储，不等于世界全部物品清单。

计划随 r48 固定为 Forge 1.20.1 的 AE2 15.4.11 与必需的 GuideME 20.1.15；AE2 声明 GuideME 为必需依赖，并声明 JEI、The One Probe、Jade 为可选集成。AE2 版本页：[CurseForge 15.4.11](https://www.curseforge.com/minecraft/mc-mods/applied-energistics-2/files/8991271)，[官方发布说明](https://github.com/AppliedEnergistics/Applied-Energistics-2/releases/tag/forge/v15.4.11)。r48 集成、首次启动、AE2网络和服务端互操作仍未实机验收。

AE2 15.4.11 与 IC2 Refactored 2.10.33-ex120 的配对包含 IC2R 一侧的原生 AE2 能量接收器桥接：`ae2:energy_acceptor` 注册为 IC2R EnergyNet sink，代码常量为 `EU_TO_AE_RATIO=2.0`，即实现按 1 EU 注入 2 AE。此结论来自双方归档与 API 的静态检查，未实机测量；这是 IC2 Refactored 的互操作，不适用于把 IC2 Classic 当成同一实现。AE2 能量指南另说明 Forge Energy 通过 Energy Acceptor 转换时为 2 FE = 1 AE。Ad Astra 使用 Botarium/Forge Energy 接口，因此其发电设备可尝试通过 AE2 Energy Acceptor 供能；具体发电机、电缆连接方向与传输量尚未实测，不应宣称已验收。

机器人仍负责按 ProcessGraph 批准路线操作 IC2/Create/原版机器及实体进料、周转和成品箱；这些加工路线与 AE2 样板自动合成不是同一功能。工业控制服务器的 ProductionPlanner 只生成流程/机器/原料规划，不盘点全厂库存，也不自动取料。手机继续承担机器人联系、状态与授权机器控制；内置 AI 继续互联网/知识问答和工业状态解读。手机界面没有 AE2 合成提交入口，AI 不会替玩家向 ME 网络提交合成请求。

## r48 新增便利与客户端图形模组

以下为计划固定入 r48 的 Forge 1.20.1 版本；相关游戏功能与服务器兼容性仍未实机验收：

- FTB Ultimine 2001.1.8（客户端与服务器）提供按住 `~` 触发的连锁采集；必需 FTB Library 2001.2.13 与 Architectury API 9.2.14。r48 目标最大方块数为 64，以服务器配置为准；整合包会内置 Ultimine、FTB Library 和 Inventory Sorter 的 zh_cn 翻译补丁，实际操作仍需验收。
- Simple Inventory Sorter 23.0.11 提供真正的容器中键整理，不等同于 Mouse Tweaks 的拖拽搬运。其 JAR 未声明 Forge/MC 模组依赖；客户端/服务器安装与同步要求仍需联机验收。
- Create: Propulsion 0.4.1 是 Create 6 系列的飞行/推进扩展，作为本包的推进路线选择，不另加 Clockwork。它依赖 Create 至少 6.0.6 和 Valkyrien Skies 至少 2.4.0；固定的 VS 2.4.11 还需要 Kotlin for Forge 4.11.0。项目说明标注为很早期 alpha，游戏功能仍待实测。VS 2.4.11 含 Linux x86_64 与 AArch64 原生库；官方兼容说明要求 glibc，Alpine/musl 不在其支持说明内。
- r48 客户端固定六个渲染/贴图相关模组：Embeddium 0.3.31、Oculus 1.8.0、Iris & Oculus Flywheel Compat 2.0.3、Entity Model Features 3.3.12、Entity Texture Features 7.2.6、Fusion 1.3.15b；EMF 需要 ETF。六者均为 client-only，不能放进 Linux 服务端 mods。整合包没有包含任何实际光影材质包；Oculus-Flywheel-Compat 为 beta，渲染兼容仍待验收。

## 本模组注册物品与方块索引
- 下列名称由当前简体中文语言文件自动生成；精确配方步骤和数量见当前服务器同步 ProcessGraph 与 JEI。
- 铬矿石（方块，`ic2newera:chromium_ore`）
- 电子材料加工机（方块，`ic2newera:electronic_fabricator`）
- 初级EU电动机（方块，`ic2newera:eu_motor`）
- 高级EU电动机（方块，`ic2newera:advanced_eu_motor`）
- 初级机械发电机（方块，`ic2newera:kinetic_generator`）
- 高级机械发电机（方块，`ic2newera:advanced_kinetic_generator`）
- 万能变压器（方块，`ic2newera:universal_transformer`）
- 工业控制服务器（方块，`ic2newera:industrial_control_server`）
- 工业光纤（方块，`ic2newera:industrial_fiber`）
- 工业网络交换机（方块，`ic2newera:industrial_network_switch`）
- 机器网络适配器（方块，`ic2newera:machine_network_adapter`）
- 镍矿石（方块，`ic2newera:nickel_ore`）
- 非金属处理机（方块，`ic2newera:non_metal_processor`）
- 钛矿石（方块，`ic2newera:titanium_ore`）
- 钨矿石（方块，`ic2newera:tungsten_ore`）
- 铝青铜斧（物品，`ic2newera:aluminium_bronze_axe`）
- 铝青铜靴（物品，`ic2newera:aluminium_bronze_boots`）
- 铝青铜胸甲（物品，`ic2newera:aluminium_bronze_chestplate`）
- 铝青铜头盔（物品，`ic2newera:aluminium_bronze_helmet`）
- 铝青铜锭（物品，`ic2newera:aluminium_bronze_ingot`）
- 铝青铜护腿（物品，`ic2newera:aluminium_bronze_leggings`）
- 铝青铜剑（物品，`ic2newera:aluminium_bronze_sword`）
- 铬粉（物品，`ic2newera:chromium_dust`）
- 铬锭（物品，`ic2newera:chromium_ingot`）
- 中央处理器（物品，`ic2newera:cpu`）
- 图形处理器（物品，`ic2newera:gpu`）
- 高碳钢粉（物品，`ic2newera:high_carbon_steel_dust`）
- 高纯硅（物品，`ic2newera:high_purity_silicon`）
- 含杂质的铝粉（物品，`ic2newera:impure_aluminium_dust`）
- 含杂质的铬粉（物品，`ic2newera:impure_chromium_dust`）
- 含杂质的煤粉（物品，`ic2newera:impure_coal_dust`）
- 含杂质的铜粉（物品，`ic2newera:impure_copper_dust`）
- 含杂质的钻石粉（物品，`ic2newera:impure_diamond_dust`）
- 含杂质的金粉（物品，`ic2newera:impure_gold_dust`）
- 含杂质的铁粉（物品，`ic2newera:impure_iron_dust`）
- 含杂质的镍粉（物品，`ic2newera:impure_nickel_dust`）
- 含杂质的石英粉（物品，`ic2newera:impure_quartz_dust`）
- 含杂质的银粉（物品，`ic2newera:impure_silver_dust`）
- 含杂质的锡粉（物品，`ic2newera:impure_tin_dust`）
- 含杂质的钛粉（物品，`ic2newera:impure_titanium_dust`）
- 含杂质的钨粉（物品，`ic2newera:impure_tungsten_dust`）
- 含杂质的锌粉（物品，`ic2newera:impure_zinc_dust`）
- 工业AI核心（物品，`ic2newera:industrial_ai_core`）
- 工业固态存储器（物品，`ic2newera:industrial_ssd`）
- 集成电路（物品，`ic2newera:integrated_circuit`）
- 因瓦合金锭（物品，`ic2newera:invar_ingot`）
- 低碳钢粉（物品，`ic2newera:low_carbon_steel_dust`）
- 镍铬合金锭（物品，`ic2newera:nichrome_ingot`）
- 镍粉（物品，`ic2newera:nickel_dust`）
- 镍锭（物品，`ic2newera:nickel_ingot`）
- 印刷电路板（物品，`ic2newera:pcb`）
- 石英粉（物品，`ic2newera:quartz_dust`）
- 内存模块（物品，`ic2newera:ram`）
- 硅（物品，`ic2newera:silicon`）
- 不锈钢锭（物品，`ic2newera:stainless_steel_ingot`）
- 高碳钢斧（物品，`ic2newera:tempered_high_carbon_steel_axe`）
- 高碳钢靴子（物品，`ic2newera:tempered_high_carbon_steel_boots`）
- 高碳钢胸甲（物品，`ic2newera:tempered_high_carbon_steel_chestplate`）
- 高碳钢头盔（物品，`ic2newera:tempered_high_carbon_steel_helmet`）
- 高碳钢锭（物品，`ic2newera:tempered_high_carbon_steel_ingot`）
- 高碳钢护腿（物品，`ic2newera:tempered_high_carbon_steel_leggings`）
- 高碳钢剑（物品，`ic2newera:tempered_high_carbon_steel_sword`）
- 低碳钢斧（物品，`ic2newera:tempered_steel_axe`）
- 低碳钢靴（物品，`ic2newera:tempered_steel_boots`）
- 低碳钢胸甲（物品，`ic2newera:tempered_steel_chestplate`）
- 低碳钢头盔（物品，`ic2newera:tempered_steel_helmet`）
- 低碳钢锭（物品，`ic2newera:tempered_steel_ingot`）
- 低碳钢护腿（物品，`ic2newera:tempered_steel_leggings`）
- 低碳钢剑（物品，`ic2newera:tempered_steel_sword`）
- 钛合金锭（物品，`ic2newera:titanium_alloy_ingot`）
- 钛粉（物品，`ic2newera:titanium_dust`）
- 钛锭（物品，`ic2newera:titanium_ingot`）
- 工具钢锭（物品，`ic2newera:tool_steel_ingot`）
- 碳化钨锭（物品，`ic2newera:tungsten_carbide_ingot`）
- 钨粉（物品，`ic2newera:tungsten_dust`）
- 钨锭（物品，`ic2newera:tungsten_ingot`）
- WiFi增幅器（物品，`ic2newera:wifi_amplifier`）
- 锌粉（物品，`ic2newera:zinc_dust`）

## 官方参考
- IC2 Classic 机器与 1.19 数据包（仅历史版本参考，不是 1.20.1 IC2R 文档）：<https://github.com/TinyModularThings/IC2Classic/wiki/Machines>、<https://github.com/TinyModularThings/IC2Classic/wiki/1.19.x-Datapacks>
- Create 动力、压机与搅拌器：<https://github.com/Creators-of-Create/Create/wiki/Stress-Units,-Capacity-and-Impact>、<https://github.com/Creators-of-Create/Create/wiki/Mechanical-Press>、<https://github.com/Creators-of-Create/Create/wiki/Mechanical-Mixer>
