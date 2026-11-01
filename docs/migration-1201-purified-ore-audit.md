# IC2R纯净碎矿与新纪元洗粉流程核对（2026-10-23）

用户在游戏中看到“纯净的矿物”，要求核对两个模组的逻辑。本轮为只读审阅安装JAR、工艺图、配方守卫及JEI桥接；没有修改工艺、删除物品、构建或操作游戏。当前安装仍为第29轮。

## 已核实的来源与差异

IC2R 2.10.33-ex120内含七种`ic2:purified_*`：copper、gold、iron、lead、silver、tin、uranium。中文例如`ic2:purified_iron`为“纯净的粉碎铁矿石”，`ic2:iron_dust`为“铁粉”。它们是不同注册项，不是新纪元为净粉又增设的纯度等级。

以铁为例，原生资源定义：

- `ic2:macerator/raw_iron_to_crushed_iron`：粗铁1→IC2粉碎铁矿石2。
- `ic2:ore_washer/crushed_iron_to_purified_iron`：粉碎铁矿石1+水1000mB→纯净碎铁矿1+小撮铁粉2+石粉1。
- `ic2:centrifuge/purified_iron_to_iron_dust`：纯净碎铁矿1→铁粉1+小撮金粉1，最低热量1500。
- 原生另有纯净碎矿直接再粉碎为铁粉或直接熔炼为铁锭，以及未清洗碎矿进入离心机的支路。因此上述链是典型深加工链，并非IC2R唯一可用链。

新纪元当前批准路线则为：原矿/粗矿→含杂质的矿粉→Create水洗→复用IC2普通矿粉→批准熔炼/合金路线。例：粗铁1→`ic2newera:impure_iron_dust`2；洗净后为`ic2:iron_dust`。最终普通铁粉被复用，但前面的中间产物及设备规则不同。

## 当前整合边界

1. 工艺图共19种受控材料；main的14种与campaign/arsenal的5种均未列入铅或铀，也未映射任何`ic2:crushed_*`或`ic2:purified_*`。
2. 铁/金/铜/锡等已受控原料进入IC2粉碎机时，RecipeGuard按批准图替换其冲突入口。第29轮实机日志已记录这些恢复操作完成。不能因JEI看得到纯净碎矿，就宣称其原生入口仍可从相同受控原料执行。
3. IC2原生碎矿/纯净碎矿注册项和后续配方仍存在。新纪元JEI插件当前仅隐藏停用终端，没有给这些矿物提供整合说明或隐藏整理；已实现图同步主要覆盖兼容炉。故玩家仍会看到两套中间产物，界面层尚未整理一致。
4. 铅、铀未入当前图，原生标签和来源确实存在：粗铅/粗铀可经原生粉碎、洗矿、离心处理。此处是未纳入统一流程的范围缺口，不能误称为已批准材料被证明存在绕过路径；尚未通过机器实操复核。
5. 铀必须特别处理：`purified_uranium`离心产生小撮铀235和铀238×6，最低热量3000。直接批量移除纯净矿物/离心配方会影响原生核燃料来源。既有“无普通粉不额外加粉”原则也不能被忽略。
6. 铅已有`ic2:lead_dust`，而1.19.2迁移来的受控清单没有铅；与用户此前“所有已有粉末的矿物按统一流程”目标相比，需要补充审核。铀的燃料支路不能硬套为普通金属粉路线。

## 后续处理方向（尚未实施）

普通受控矿物继续遵守用户批准的杂质粉→Create水洗→净粉流程。整理IC2旧中间产物的中文说明、JEI来源/用途与实际可达性，必要兼容转换仍必须写入同一个ProcessGraph，不增加第二份批准路线表。铅按已有普通粉末原则另审；铀保留必要原生核燃料链并明确标注，不能一刀切删除。任何配方变更都须记录精确ID、输入、输出、原因，并保持早期单倍直烧和存量兼容。

## 依据

- 安装构件：独立1.20.1实例`mods/ic2-forge-2.10.33-ex120.jar`的语言和配方资源。
- 本项目：迁移目录`src/main/resources/data/ic2newera/process_graph/*.json`、`RecipeGuard.java`、`IC2NewEraJeiPlugin.java`、`AdvancedFurnaceJeiSync.java`。
- [IC2R铁洗矿配方](https://github.com/craftorio/IC2R/blob/forge/1.20.1/src/main/resources/data/ic2/recipes/ore_washer/crushed_iron_to_purified_iron.json)。
- [IC2R纯净碎铁矿离心配方](https://github.com/craftorio/IC2R/blob/forge/1.20.1/src/main/resources/data/ic2/recipes/centrifuge/purified_iron_to_iron_dust.json)。
- [IC2R纯净碎铀矿离心配方](https://github.com/craftorio/IC2R/blob/forge/1.20.1/src/main/resources/data/ic2/recipes/centrifuge/purified_uranium_to_small_uranium_235.json)。
