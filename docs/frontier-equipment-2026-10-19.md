# 外星资源与工业装备实现交接

2026-10-19。本文件记录注册/资源源码与初始数值，不代表已构建、安装或实机平衡。主线、世界生成、配方、ProcessGraph 和任务遭遇由对应模块接线，不在装备注册器另造一套规则。

## 注册与资源

`FrontierContent.register(IEventBus)` 注册两种矿石方块及对应物品、12 种材料、两套各 9 件装备。统一命名空间 `ic2newera`。

| 类别 | 注册 ID |
| --- | --- |
| 月球钒 | `vanadium_ore`、`raw_vanadium`、`impure_vanadium_dust`、`vanadium_dust`、`vanadium_ingot` |
| 火星铌 | `niobium_ore`、`raw_niobium`、`impure_niobium_dust`、`niobium_dust`、`niobium_ingot` |
| 钒钢 | `vanadium_steel_dust`、`vanadium_steel_ingot` |
| 铌钛合金 | `niobium_titanium_dust`、`niobium_titanium_ingot` |
| 工具/护甲 | `vanadium_steel_` 或 `niobium_titanium_` + `sword/pickaxe/axe/shovel/hoe/helmet/chestplate/leggings/boots` |

方块/物品模型、blockstate、32 张 16×16 材质和 4 张 64×32 护甲穿戴贴图齐备；中英文各追加本模块 36 个键（含两怪物/创造蛋），没有物品冗长 lore。矿石强度分别 4.5/5.5，要求正确工具；掉落和生成由数据模块定义。

## 初始属性

剑/斧总攻击以下按未附魔、原版玩家基础攻击 1 计算；剑攻速 -2.4、斧 -3。工具为标准 SwordItem/PickaxeItem/AxeItem/ShovelItem/HoeItem，不另造耗电或自动采矿系统。

| 项目 | 钒钢 | 铌钛合金 |
| --- | --- | --- |
| 工具耐久 / 速度 / 数字等级 | 2400 / 10 / 4 | 3200 / 12 / 5 |
| 剑 / 斧总攻击 | 9 / 11 | 10 / 12 |
| 护甲头/胸/腿/靴 | 4 / 8 / 6 / 4（共 22） | 4 / 9 / 7 / 4（共 24） |
| 每件韧性 / 击退抗性 | 3.5 / 0.1 | 4 / 0.125 |
| 护甲耐久倍率 | 40 | 50 |
| 头/胸/腿/靴耐久 | 440 / 640 / 600 / 520 | 550 / 800 / 750 / 650 |

修复分别使用自身合金锭，普通附魔/耐久体系；没有额外无敌或 100% 减伤。使用原版护甲公式，实际高护甲效果仍须实机调优。

Forge `TierSortingRegistry` 排序为 NETHERITE → vanadium_steel → niobium_titanium，分别关联 `ic2newera:needs_vanadium_steel_tool`、`ic2newera:needs_niobium_titanium_tool`。采掘判定不依赖单纯数字等级。数据代理已对齐：钒矿 needs_diamond_tool，铌矿 `forge:needs_netherite_tool`；后者已从本地 Forge43.5.0 源码确认 Tiers.NETHERITE 对应此标签。

机器人现有 `RobotHarvesting` 调用 `ItemStack.isCorrectToolForDrops` 与 `BlockState.canHarvestBlock`，标准镐会走相同 Forge 判定；未实测机器人持新工具，不能宣称已验收。

## 视觉与可重现

执行 `python scripts/generate-frontier-assets.py`（Python/Pillow）重建本模块资源及 `art/frontier-equipment/catalog.png`、`armor-uv.png`。

所有新锭读取项目 `art/reference/vanilla-iron-ingot-1.19.2.json` 的逐像素轮廓和明暗分区，仅换材质色板；模型继承 `minecraft:item/iron_ingot`，无 display 改动。基准来自 Mojang/Microsoft 原版资源，不冒称原版轮廓为原创。

粉末复用本项目 `material_pixel_style.py` 尖顶粉堆规则，含杂质粉带明显暗颗粒。装备是本次原创 16×16 工业护板、金属边缘、铆钉/黄铜扣；护甲使用原版装备层布局。月球灰色基岩和火星红褐基岩上的矿脉均自行像素绘制，未提取第三方模组纹理。

不修改 IC2/Create 外观，不给 ProjectE/转化台/拟态复制默认旁路；有关标签、EMC 和批准配方审计留给流程集成。金属配料仍走经核实 IC2 合金炉，EU/Create 转速/航天能量不自行互通。

## 尚需集成验收

- 主类接 `FrontierContent.register(modBus)`；工业敌对实体另接 `IndustrialHostiles.register(modBus)`，客户端实体注册已由 Dist.CLIENT mod-event 订阅。
- 数据模块为实际月球/火星维度、矿石 loot/tag、洗粉、冶炼、合金、装备配方和 ProcessGraph 提供一致路线。注册器不声称配方/世界生成已生效，不编辑既有存档。
- 本阶段只完成资源生成、原版轮廓/模型/嵌入 PNG 一致性和源码静态审阅；未执行构建/测试/安装，未启动游戏。真实掉落、JEI、机器人采掘、护甲穿戴、附魔、修理、服务器兼容与战斗平衡由统一集成后人工验收。
