# IC2 新纪元：工业与电子知识

本指南覆盖四种新增矿物和十种已有粉末的受控矿物、六种新纪元合金锭、九种电子材料、两台材料处理机和工业网络。化学实验、反应链与化工机器已退出当前范围，不能当作可用玩法推荐。

## 唯一配方与数量来源

新纪元配方路线的权威数据是服务端加载的 ProcessGraph。数据包 reload 后由服务器同步到客户端；AI 对具体工业问题附加当前服务器已批准路线，生产规划也读取服务器当前图。配方步骤、物品 ID、输入/输出数量、工艺类型、设备及批次数不得从本静态知识文档推算或另写一份 BOM。

若客户端还未收到服务器工艺图，AI 应明确无法核实具体路线，不得猜测。最终可制作配方以当前 JEI/机器界面为准；指南只说明用途、分工与边界。

## 两个模组与新纪元的分工

- IC2 Classic 提供 EU、电网、储能、基础粉碎/熔炼/合金机器及其原生物流。
- Create 提供转速、机械加工、粉碎轮、风扇水洗、物品物流与机械自动化。
- EU 与 Create 转速网络各自独立；已有新纪元 EU 电动机提供单向 EU→转轴动力，没有反向发电。工业网络只传数据，不承载电力、转速、物品或流体。
- 新纪元新增矿物阶段与合金、电子材料、两台材料处理机、工业网络和生产规划；不重做 IC2/Create 原生设备。

## IC2 Classic 粉碎机界面：I / C / S 按钮、槽位和升级（Macerator / GUI）

玩家截图左上角的 I、C、S 是三个独立的界面按钮，不是合在一起的“ICS”缩写，也不是机器状态灯。IC2 Classic 2.1.3.4 对应的悬停名称分别是：I = 机器访问控制（Machine Access Control），C = 比较器设置（Comparator Settings），S = 格子过滤器信息（Slot Filter Info）。同一套按钮会在提供相应界面组件的 IC2 机器 GUI 中重复出现。

- **I：机器访问控制。** 打开按机器方位配置的面板。D/U/N/S/W/E 分别是下、上、北、南、西、东；这里的 S 是“南”，和左上角的 S 按钮不是一回事。绿色勾选开关控制该面是否可被外部库存访问；旁边标成 B 的按钮循环该面的物品规则：禁止、导入、导出、双向。点选某个库存格时还可针对该格设置访问规则。底部选项限制每格只放 1 个物品、每种物品只放 1 组；红色叉号表示该限制当前关闭，点击可切换，“重置”恢复默认设置。这些选项用于管道/自动化的输入输出，不改变机器配方。
- **C：比较器设置。** 配置机器向红石比较器提供的状态信号。机器支持哪些来源取决于机器本身，常见来源包括库存填充、EU 缓冲、运行/进度或燃料状态；页面可设置最小/最大/平均评估与反转。它控制红石读数，不负责选配方、输入物品或机器供电。
- **S：格子过滤器信息 / 有效格子输入。** 查看所选机器槽位允许的物品。可用条目随机器、槽位和当前已注册配方而变化；它说明“哪些物品能放进这个格子”，不是额外的物品槽，也不是全局配方搜索。把正确原料放进 Macerator 的输入格，确切配方及数量仍以当前 JEI 为准。

粉碎机注册 ID 是 ic2:macerator。左边闪电形条是 EU 能量/充能显示，箭头形条是加工进度。右边竖排四格属于 SlotType.UPGRADES，中文槽位名为“升级”；只能放机器接受的 IC2 升级组件，是可选位，不是四个原料格，留空也能工作。组件可改变速度、耗能、储能或物品导入/导出行为，具体以组件提示与机器校验为准。

## 已有矿物的打粉与水洗

铁、金、铜、煤、钻石、石英、锡、银、铝、锌已有普通粉末，因此补齐含杂质粉中间体。矿石、深层/下界变体、粗金属及旧 Create 碎矿通过 IC2 粉碎机或 Create 粉碎轮先得到含杂质粉，经过 Create 风扇加水清洗才得到已有普通粉末。煤、钻石和石英的固体资源打粉同样需要清洗；它们原有燃料、合成、装备等用途照常使用。钻石粉复用目标版本 IC2 的 ic2:dust_diamond。煤/钻石/石英的路线止于粉末，不虚构熔炼成锭或从粉返还宝石的步骤。

红石粉按玩家要求保持原流程；青金石、绿宝石、远古残骸和铀没有普通粉，不新增粉或路线。已制成金属锭的同级打粉回收直接得到普通粉；清洗后的铁/煤粉仍进入合金炉制作高低碳钢，普通石英粉仍用于制硅。原矿单倍直烧保留低效旧路线；工业规划须读取图里的杂质粉与水洗步骤。

## 四种矿物路线

镍、铬、钛、钨从矿石经粉碎得到含杂质粉，再由 Create 风扇水洗得到洁净粉，最后按服务端批准路线精炼。镍、铬可用普通熔炼路线；钛、钨洁净粉走 IC2 电力高炉或感应高炉。矿石旧直炼是否保留由当前图和 RecipeGuard 分类决定；不要把杂质粉直接熔炼或使用旧攻略覆盖当前裁决。

## 高低碳钢粉与钢锭

高低碳钢各有粉末和钢锭两个形态。普通铁粉和 IC2 煤粉分别打粉后，在 IC2 合金炉配成对应钢粉，再熔炼成锭；没有钢坯、配料包或额外碳中间体。青铜、黄铜和新纪元其他金属合金的配料也只走合金炉，不能推荐工作台混粉或 Create 搅拌旁路。输入比例、批次数与产量必须读取当前服务器 ProcessGraph，不能由此静态文档另行推算。两种钢锭重新打粉只回收同等级粉末；已制成合金的块/粒拆分和装备制作仅使用批准配方。

## 两台材料处理机

- 非金属处理机：处理新纪元石英粉与硅材料，生成硅和高纯硅。它有配方输入槽与产物槽，输入槽只接受当前配方在该位置要求的物品。
- 电子材料加工机：制作 PCB、集成电路、CPU、RAM、GPU、工业固态硬盘。它的输入按配方位置排列，输出槽用于取出产物。服务端槽位验证是准入权威，悬停说明不能改变验证范围。
- 工业设备的 EU/转速来源各自独立；精确供能、配方和机器条件查看 JEI 与运行时状态。

## 电子材料与原生部件复用

下界石英是硅工业来源，不新增硅矿。九种新纪元电子材料为石英粉、硅、高纯硅、PCB、集成电路、CPU、RAM、GPU 和工业固态硬盘。服务器请求所需产量或查询生产方案时以当前同步工艺图为准。

复用 IC2 Classic 2.1.3.4 电路 ic2:circuit、ic2:advanced_circuit、ic2:complex_circuit，复用 Create 电子管与精密构件；不注册同用途替代品。IC2 的合金熔炼机 ic2:alloy_smelter 用于图中批准的多输入合金路线。

## 工业网络和控制

工业交换机提供 Wi-Fi 与光纤网络。交换机网络名需匹配；适配器自动关联符合权限的最近交换机，优先采用相邻工业光纤链路。将适配器贴近支持的方块实体，可查看设备状态；自动控制只支持实现本项目控制接口的机器。IC2/Create 公共状态读取不代表所有第三方机器都可远程控制。

工业控制服务器通过网络查看状态、按物品 ID 请求服务器端生产规划，并可在授权后启用支持设备的自动暂停/恢复。工业 AI 核心必须安装在服务器专用槽内。规划结果不是库存盘点，也不会自动保证机器或材料齐全；网络不要求玩家配置 IP、端口、网关、DNS 或硬件参数。

## 资料与版本边界

IC2 Classic 与 Create 的具体操作查目标实例 JEI、IC2 游戏内 Wiki 和 Create Ponder。静态指南、旧版本教程或旧安装包不能覆盖当前服务器工艺图。PCL2 历史安装记录对应先前交付版本，不代表本次新工业阶段已安装或实机验收。

## 本模组注册物品与方块索引
- 下列名称由当前简体中文语言文件自动生成；精确配方步骤和数量见当前服务器同步 ProcessGraph 与 JEI。
- 铬矿石（方块，`ic2newera:chromium_ore`）
- 电子材料加工机（方块，`ic2newera:electronic_fabricator`）
- EU电动机（方块，`ic2newera:eu_motor`）
- 工业控制服务器（方块，`ic2newera:industrial_control_server`）
- 工业光纤（方块，`ic2newera:industrial_fiber`）
- 工业网络交换机（方块，`ic2newera:industrial_network_switch`）
- 机器网络适配器（方块，`ic2newera:machine_network_adapter`）
- 镍矿石（方块，`ic2newera:nickel_ore`）
- 非金属处理机（方块，`ic2newera:non_metal_processor`）
- 终端机（方块，`ic2newera:terminal`）
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
- IC2 Classic 机器与 1.19 数据包：<https://github.com/TinyModularThings/IC2Classic/wiki/Machines>、<https://github.com/TinyModularThings/IC2Classic/wiki/1.19.x-Datapacks>
- Create 动力、压机与搅拌器：<https://github.com/Creators-of-Create/Create/wiki/Stress-Units,-Capacity-and-Impact>、<https://github.com/Creators-of-Create/Create/wiki/Mechanical-Press>、<https://github.com/Creators-of-Create/Create/wiki/Mechanical-Mixer>
