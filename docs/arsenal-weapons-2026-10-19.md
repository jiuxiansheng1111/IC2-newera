# 原创科技双刀与军械材料源码交接

2026-10-19 本轮增量：注册/美术资源已实现，尚待主线程统一构建安装；未测试、未启动游戏，数值是初值。

`ArsenalMaterials.register(IEventBus)` 提供独立注册 facade，新增 4 种方块及 31 个对应方块物品/材料/护甲物品。原有 frontier 的镐、斧、铲、锄与剑保持其注册身份；本轮没有增加工具。

| 内容 | ID / 形态 |
| --- | --- |
| 月球钴 | `cobalt_ore`、`raw_cobalt`、`impure_cobalt_dust`、`cobalt_dust`、`cobalt_ingot` |
| 月球深层钼 | `molybdenum_ore`、`raw_molybdenum`、`impure_molybdenum_dust`、`molybdenum_dust`、`molybdenum_ingot` |
| 火星钽 | `tantalum_ore`、`raw_tantalum`、`impure_tantalum_dust`、`tantalum_dust`、`tantalum_ingot` |
| 原创共振晶 | `resonance_cluster`、`resonance_shard`，不强造粉/锭 |
| 真实配方中间件 | `magnetic_coil` 磁约束线圈、`heat_resistant_plate` 耐热护板、`pulse_capacitor` 脉冲电容、`resonance_core` 共振核心、`blade_stabilizer` 刀身稳定器、`servo_armor_joint` 装甲伺服关节 |
| 新护甲 | `magnetic_armor_` / `resonance_armor_` + `helmet/chestplate/leggings/boots` |
| 双刀（blade 模块注册） | `night_arc` 夜弧、`star_pulse` 星脉 |

三金属按经核实 ProcessGraph 四阶段工艺接线；晶体采用碎晶→共振核心的批准精制路线，不恢复化学或另设发电系统。矿物生成/采掘门槛/loot、金属精制设备、6 中间件被武器和护甲真实消费的配方由数据代理落地；此注册器不单独宣称路线已经可玩。

| 初始护甲属性 | 磁约束甲 | 共振甲 |
| --- | --- | --- |
| 头 / 胸 / 腿 / 靴防御 | 4 / 9 / 7 / 4，总 24 | 5 / 9 / 8 / 4，总 26 |
| 每件韧性 | 4 | 4.5 |
| 每件 / 全套击退抗性 | 0.1 / 0.4 | 0.125 / 0.5 |
| 原版耐久倍率 | 50 | 60 |
| 头 / 胸 / 腿 / 靴耐久 | 550 / 800 / 750 / 650 | 660 / 960 / 900 / 780 |
| 铁砧维修材料 | 钴锭 | 钽锭 |

普通 ArmorItem、原版护甲/耐久/附魔机制，没有额外无敌、100% 减伤或新电力。外观见 `art/arsenal/catalog.png` / `armor-uv.png`。三锭继承原版 `minecraft:item/iron_ingot`；原创像素/模型来源与复现见 `art/arsenal/README.md`。

双刀资源按核实的原生 SlashBlade OBJ 组名与单位交付，原创黑青曲刃、银白蓝紫直刃及独立刀鞘。原生刀状态指定完整 `ic2newera:models/blade/<id>.obj` 与 `ic2newera:textures/blade/<id>.png`；功能、动作、副手、技能、耐久/断刀与配方由 blade 模块接线。资源检查不等于原生渲染或高速二刀流实机验收。

## 怪物攻击表现增量

巡检机普通点射取消橙色激光预警/蓄能阶段，直接射击；使用服务端 gameTime 截止值保证两次射击至少相隔 45 tick，即使中间丢失目标也不能绕过冷却。保留最远 7.5 格、碰撞裁切与视线检测，不穿墙，80 HP/8 护甲/10 伤害不变。

工业守卫抬手缩短至 16 tick，取消地面橙色提示圈；固定落点近战重击、36 tick 硬直、240 HP/14 护甲/18 伤害不变。客户端直接引用服务端时长常量；可编辑模型的 heavy_strike 时间线更新为 2.6 秒（0.8 秒抬手 + 1.8 秒恢复）。参与者/机器人主人、维度、范围、TTL 与地形保护边界未放宽。

本轮未构建或安装；未运行测试或真实存档/游戏/API 验收。旧版已安装交付记录仍保留，不代表以上新增内容已经安装。
