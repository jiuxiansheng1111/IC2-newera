# 机器人工业配方与物品资源（2026-10-17）

本阶段为机器人新增四个物品资源和制造配方，并调整通用图纸与八种娃娃配方。机器人控制核心、执行器、电池与工业手机的工业路线由 `ProcessGraph` 提供；脚本生成的玩家配方不复制这几条路线。当前手机路线已经从旧版 3×3 工作台设计改为电子材料加工机的六种输入、2400 tick 工序，以下数据以当前 `scripts/update-process-graph.py` 和生成后的 `process_graph/main.json` 为准。最终整合构建与安装已完成，当前 JAR 回执和未实机验收的边界见 [综合交付](phone-music-server-delivery-2026-10-18.md)；此前安装/构建记录保留作历史。

## IC2 Classic 物品核对

目标依赖来自 `scripts/runtime-mods.json`：IC2 Classic 1.19.2 版 `2.1.3.4`，缓存 JAR `ic2-classic-242942-8545995.jar`，SHA-256 `F0A99733EF33491ECA418831D681A79902250D341650F652D06F8B88D3627F83`。已从该 JAR 读取物品英文语言文件，并检查 `ic2/core/platform/registries/IC2Items.class` 与 `IC2Recipes.class`。可复用的物品注册名/语言键和实际 recipe 注册代码引用如下：

| IC2 物品 ID | 英文注册名称 | 用途 |
| --- | --- | --- |
| `ic2:advanced_circuit` | Advanced Circuit | 图纸、控制核心、执行器、电池、手机 |
| `ic2:complex_circuit` | Complex Circuit | 高级空电电池 |
| `ic2:lapatron_crystal` | Lapotron Crystal | 控制核心和工业手机的高阶储能材料 |
| `ic2:carbon_plate` | Carbon Plate | 图纸、执行器、电池 |
| `ic2:plate_advanced_alloy` | Advanced Alloy Plate | 执行器、电池 |
| `ic2:circuit` | Electronic Circuit | 电池 |
| `ic2:uesc` | UESC Energy Crystal | 工业手机；物品 ID 与能量等级见 [充电宝记录](robot-powerbanks-2026-10-17.md) |

IC2 的实际 ID 拼写为 `lapatron_crystal`，不是按英文名推测的 `lapotron_crystal`。上述名称在本地 IC2 物品注册及 `items_en_us.json` 中均可找到；`IC2Recipes.class` 引用了这些已注册物品并调用 IC2 高级合成配方管理器。没有核实到可用的 Advanced Machine Block ID，因此路线未使用此猜测名称。

Create 复用已注册的 `create:precision_mechanism`。新纪元电子链复用 `cpu`、`ram`、`gpu`、`industrial_ssd`、`high_purity_silicon`、`titanium_alloy_ingot` 与 `tungsten_carbide_ingot`。这些 ID 在现有共享资源/Java 注册中定义；父任务负责完成四个机器人新 ID 的 Java 注册与运行时行为。

## 图驱动工序

| 产品 | 工序与输入 | 加工时间 | 设定能耗 | 单次 EU |
| --- | --- | ---: | ---: | ---: |
| `robot_control_core` | 电子材料加工机：CPU、RAM、工业 SSD、GPU、IC2 高级电路×2、IC2 兰波顿水晶×1（6 种输入槽） | 1000 tick | 96 EU/t | 96,000 EU |
| `robot_actuator` | 电子材料加工机：IC2 高级合金板×2、碳板、Create 精密构件、钛合金锭×2、碳化钨锭、IC2 高级电路（6 种输入槽） | 800 tick | 64 EU/t | 51,200 EU |
| `robot_battery` | 电子材料加工机：IC2 高级电路、复杂电路、高级合金板×2、碳板、工业 SSD、钛合金锭（6 种输入槽） | 800 tick | 64 EU/t | 51,200 EU |
| `industrial_phone` | 电子材料加工机：控制核心、工业 SSD×2、GPU、IC2 UESC 能量水晶、IC2 复杂电路×2、高级充电宝（六种输入） | 2400 tick | 256 EU/t | 614,400 EU |

电子加工机有六个输入槽。它的 IC2 Sink tier 为 3，并通过 IC2 `EnergyNet.getPowerFromTier(3)` 校验输入电压；以上配方均低于该机器档位。`ProcessorRecipeSerializer` 接受最多六种输入，本阶段没有超过槽位数。4,000,000 EU 容量、最低 tier 3、2048 EU 的充电传输上限（2026-10-18 按用户要求恢复等级限制）属于父任务实现的电池运行数据，不由配方资源模拟。

兰波顿水晶是 IC2 储能物品；控制核心使用 `ic2:lapatron_crystal`，普通工序会消耗整件物品，不能保留其中 EU。工业手机现使用 `ic2:uesc`，不是兰波顿水晶或已充电机器人电池；手机六种输入各占一个加工槽。新机器人电池只使用高阶电子/材料组件，不消耗带电 IC2 储能核心；电池按父任务要求空电出厂。八种娃娃配方不包含电池。

## 图纸与角色娃娃

- 初始通用图纸使用纸、蓝色染料、红石、IC2 高级电路、Create 精密构件和 IC2 碳板合成。配方本身无成就/科研条件。
- 八种娃娃的共用配方输入是通用图纸、机器人控制核心、机器人执行器、羊毛、线，以及各角色对应的发色染料；每个产物都消耗控制核心与执行器，且不附带电池。
- `robot_blueprint` 的 Java crafting remainder 返回同一张图纸。资源生成器保留对应的 recipe-book advancement，它只解锁配方书条目，不充当合成门禁。
- 原八种娃娃贴图不由生成器写入。新加的控制核心、执行器、电池和手机各有独立的 16×16 PNG 与 `generated` 模型，由 `scripts/generate-robot-resources.py` 的标准库像素绘制代码重复生成。

## 验证边界

此前运行了 `scripts/update-process-graph.py` 与 `scripts/generate-robot-resources.py`，生成 14 个 ProcessGraph 材料、240 条图路线，以及图纸/八角色配方、四个新物品模型贴图和中英文名称。手机路线随后更新为电子材料加工机六槽工序；当前静态源数据为 2400 tick、256 EU/t、总计 614,400 EU。资源 JSON、路线输入数、配方输入/输出、纹理尺寸及图纸返还已静态核对。没有新增或运行测试；本轮文档同步不运行 Gradle 构建。

截至本记录，IC2 物品名称已对照目标版本本地 JAR；UESC 的 ID 与等级另见本地核验的充电宝记录。这不代替游戏内 JEI、电子加工机实际能耗/合成、手机充电/耗电、媒体桥服务可用性、机器人交互和多人实机验收。此前安装回执仅代表历史安装；最新整合包以 `build/deployment/pcl-installation.json` 等主任务回执为准，不在此复制旧 JAR 哈希。
