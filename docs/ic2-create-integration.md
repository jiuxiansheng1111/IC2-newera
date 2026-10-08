# IC2 Classic 与 Create：从起步到自动化

本文按本项目目标组合编写：Minecraft 1.19.2、Forge 43.5.0、IC2 Classic 2.1.3.4（CurseForge 文件 8545995）与 Create 0.5.1.i（文件 5797604）。它介绍两套模组的基础使用顺序。新纪元工艺数量以服务器同步的 ProcessGraph 为准。整合包数据包、配置或版本更换可能改变配方；进入游戏后请以当前 JEI、方块界面和 Create Ponder 为准。

## 先认清系统边界

- **IC2 Classic** 使用 EU 电力网，机器有各自的配方、供能等级、面向和物流接口。
- **Create** 使用转速与应力网络，动力由转动装置提供，机器通过轴、齿轮、皮带等传递动力。
- 两者不是可直接接线的同一种能源。新纪元没有转速转 EU 或 EU 转转速的设备或比率。
- 新纪元的联动是物品配方：Create 的粗锌/粉碎矿进入 IC2 Macerator；机器产出的物品再进入另一个配方。不要据此推定两套物品管、流体管或机器端口都能直连。

## 如何使用游戏内说明

### Create Ponder

在 JEI 或物品提示中查看 Create 方块的 Ponder 指引（通常可从物品提示进入；具体按键以当前按键设置为准）。Ponder 展示该方块的摆放、连接和操作示例，是搭建轴系、处理设备、移动结构和列车时最直接的逐步说明。不同机器的结构会随 0.5.1.i 的版本说明变化；遇到步骤或按键不一致时以当前实例 Ponder 为准。

### IC2 Classic 手册

IC2 Classic 2.1.3.4 在 JAR 中带有多页游戏内 Wiki。通过 JEI 搜索目标机器/物品，再查看游戏内 Wiki、物品提示和机器 GUI，确认用途、输入面、升级槽、能量等级、物品或流体配方。不要套用其他 IC2 分支或更早版本的教程参数。

**版本差异提示：**此处核对的 IC2 Classic 2.1.3.4 英文方块资源包含 Alloy Smelter、Macerator 和 Centrifugal Extractor；没有 `Ore Washing Plant` 或 `Thermal Centrifuge`。Centrifugal Extractor 是本版本实际存在的另一台设备，不等于 Thermal Centrifuge。把 IndustrialCraft 2 Experimental 的“洗矿机→热离心机”升级线写成 Classic 原生流程是不正确的。Classic 的矿物配方按 JEI 中本版本的实际机器显示操作；本指南不假定不存在的机器或配方。

## IC2 Classic：起步、供能与加工

### 1. 建立第一条 EU 线

先用 JEI 查 Generator、BatBox、线缆、Macerator 与 Electric Furnace 的制作配方。以燃料发电并把 EU 存进 BatBox，再从储能设备的输出侧铺设 IC2 线缆到机器。发电设备和储能设备的输出面、线缆连接、机器可接受的电压等级都要按方块提示和游戏内 Wiki 确认。

不要只看线缆是否接上：匹配电压/能量包等级和传输能力很重要。变压器用于在等级之间变换电力包；其朝向、档位与输入/输出侧应按对应 Wiki 调整。刚起步时将供电与少量机器分开验证，再接入更多负载。

### 2. 从手动加工过渡到用电机器

先用 JEI 检查原矿、粗矿、粉末、锭分别对应哪一种实际配方。Macerator 可执行它在当前配方页列出的粉碎/材料转换；Electric Furnace 用 EU 完成其显示的熔炼；Compressor、Extractor、Centrifugal Extractor、Alloy Smelter 等机器也只处理各自列出的输入。

IC2 Classic 的机器并不都属于矿石前处理，也不是所有矿物都有相同的加工级数。特别是，不能把另一个 IC2 分支的 Ore Washing Plant 或 Thermal Centrifuge 配方套用到这里。需要合金时，可在 JEI 检查 IC2 Alloy Smelter 的配方；新纪元的 Create 锌锭→Create 黄铜路线另见下文。

### 粉碎机界面：I / C / S、有效格子输入与升级槽

在本项目安装的 IC2 Classic 2.1.3.4 中，粉碎机注册 ID 是 `ic2:macerator`。截图左上角的 `I`、`C`、`S` 是三个独立的界面入口，不是一个“ICS”缩写：`I` 是机器访问控制，`C` 是比较器设置，`S` 是格子过滤器信息（英文提示 Slot Filter Info，简中筛选标题为“有效格子输入”）。这三个字母不是机器状态或配方输入。

`I` 面板按机器的六个方位设置外部库存访问。`D/U/N/S/W/E` 分别是下、上、北、南、西、东；这里面板里的 `S` 是南方，和按钮 `S` 无关。绿色勾选控制该面是否开放给外部库存，`B` 按钮循环该面的规则：禁止、导入、导出、双向；选中具体库存格后也能设置该格的规则。左下角两个红色叉号分别是“每个格子仅允许放入 1 个物品”和“每种物品最多只能放 1 组”的限制开关；红叉表示当前关闭，点击即可切换。“重置”恢复默认。它们只影响机器的物品自动化接口，不改配方。

`S` 面板显示当前所选机器格允许的物品（有效格子输入），用途是辨认该格接受什么原料；可接受内容取决于机器、槽位和当前已注册规则/配方。它不是又一组输入格，也不是必须配置的全局过滤器。配方和数量仍看 JEI。

`C` 面板配置机器提供给红石比较器的状态信号。选项随机器而异，常见读数来源包括库存填充、EU 缓冲、运行/进度或燃料；可选择最小/最大/平均评估并反转信号。该页控制红石信号，不选择配方、不输送物品也不供电。

粉碎机左侧闪电形指示是 EU 能量/充能显示，旁边箭头表示加工进度。右侧竖排四个格子是 `SlotType.UPGRADES` 升级槽，用于放 IC2 兼容的升级组件，不是额外配方输入。升级是可选的，不放也可正常粉碎；适配的组件可影响速度、能耗、机器储能或物品导入/导出行为，具体按物品提示和本机接受规则判断。不要把四格说成必须填满，也不要假设任意升级在所有机器上都有效。将原料放到 GUI 标示的加工输入位，产物从输出位取；精确原料与数量仍以本版本 JEI 配方为准。

### 3. 供能与机器升级

根据负载增加发电机和储能，再通过 IC2 线缆、变压器与机器等级组织电网。升级前先读机器界面：Overclocker、Energy Storage、Import/Export、Fluid Import/Export 等升级只在相应机器/槽位支持时有效，而且可能改变耗能、速度或缓存。逐个添加并观察机器 GUI 的功率、进度和缓存反馈；不要根据其他版本的固定倍率推算。

用 IC2 扳手或对应专用工具调整机器朝向、执行方块操作。IC2 扳手的操作模式会影响旋转或拆除行为；先读工具提示/游戏内 Wiki，并在正式机器上操作前确认当前模式。机器输入、输出面及红石控制也要按该方块自己的界面配置。

## IC2 Classic：物流与控制

### 物品管

IC2 Tube 可把箱子、机器和物流网连成物品网络。基础 Tube 用于输送；Extraction Tube 从相连库存提取，Insertion Tube 把物品送入库存。需要分流时查看 Filter、Color Filter、Directional、Round Robin、Speed、Stacking、Redstone 等 Tube 的游戏内说明，并以 Tube Configurator 调整本版本支持的连接、颜色、红石或过滤行为。

Provider Tube 与 Request Tube 可接入 IC2 Logistic Grid；先按游戏内 Wiki 搭好网络与请求/供应端，再用少量物品确认物品种类、颜色和请求方向正确。小型工厂优先按单条路线调试；网格过大时使用过滤与分区减少意外交叉。具体连通规则和可配置选项以 Tube Configurator 显示为准。

### 流体管与库存升级

IC2 Fluid Pipe、泵及流体升级属于 IC2 自己的流体系统；Create Fluid Pipe 与 Mechanical Pump 属于 Create 系统。只有游戏明确列出的兼容接口才可直接连接。跨模组搬运时可先用桶等明确可识别的物品容器做人工交接；不要假设 Create 泵、IC2 流体管和另一模组的机器能无条件互通。

## Create：动力、加工和物流

### 1. 建立一条可观察的转速轴系

按 Ponder 先做 Water Wheel 或 Large Water Wheel 等动力源，再用 Shaft、Cogwheel、Gearbox 或 Belt 将动力传给目标设备。Water Wheel 在 0.5.1 中只有一个速度等级；不要套用旧教程中不同水轮的速度档位。使用 Speedometer 观察转速，Stressometer 观察网络负载；如果机器停转，检查动力源方向、传动连接、转速设定和总应力负载。

Hand Crank 可用于早期短时测试。进入中后期后，可按 Ponder 组装 Create Boiler 与 Steam Engine，以蒸汽提供转动动力；锅炉的热源、水路、状态和可承载设备都应按本版本界面逐项确认。蒸汽机输出仍是 Create 转动动力。Clutch、Gearshift、Redstone Link、Sequenced Gearshift 与 Speed Controller 可用于开关或控制传动；具体红石逻辑、设定方式和工作边界按 Ponder 展示，不在本指南中提供未核实的数值。

### 2. 用机械设备处理物品

Create 的常见加工设备包括 Crushing Wheels、Mechanical Press、Mechanical Mixer、Mechanical Saw、Mechanical Drill、Mechanical Harvester、Mechanical Crafter 与 Encased Fan。先用 JEI 查看当前物品对应的 Create 配方/处理类型，再按 Ponder 搭设备：

- Crushing Wheels 执行其列出的破碎配方。
- Mechanical Press、Mixer 和 Basin 组合可完成 JEI 中列出的压制、搅拌或加热混合配方；需要加热时按 Ponder 为 Basin 配置 Create 认可的热源。
- Mechanical Saw、Drill 与 Harvester 用于它们各自允许的切割、采掘或收割用途。
- Encased Fan 的处理取决于风扇前方气流、处理媒介和物品配方；按 Ponder/JEI 验证具体处理，不能把它当成任意粉碎设备。

Mechanical Crafter 可拼接配方，Deployer 可按过滤配置对目标执行放置或交互；两者都需先按 Ponder 设置工作方向和物品输入。Sequenced Assembly 则用 Deployer、Press 等处理步骤对指定输入逐步加工，JEI 显示该序列实际需要的步骤和产物。处理链以 Depot、Belt、Chute、Funnel、Vault 或箱子作为缓冲点。部署前先检查输出是否会回到输入、漏斗/漏斗口方向、过滤器和库存容量；分段启动可以更容易定位堵塞。

### 3. Create 物流与流体

Belts 适合可见的连续物品线，Funnels/Brass Funnels 与 Chutes 用于装卸或落料，Brass Tunnels 可按过滤与分配模式处理分支。Depot、箱子、Barrel、Vault 提供工序之间的暂存；Filter、Attribute Filter、Threshold Switch、Smart Observer 和 Redstone Link 用于分类、库存检测或信号控制。实际过滤选项以该方块 GUI 和 Ponder 为准。

Create Mechanical Pump、Fluid Pipe、Fluid Tank、Valve 与 Spout 可组成 Create 内部的流体管线。确认泵的动力方向、流体源/目的端及管网连续性，再逐段扩展。项目未实现 IC2 EU/转速转换或任意 IC2/Create 流体接口桥接。

## Create：移动结构、控制与列车

### 可移动结构

Mechanical Bearing 可旋转可移动结构；Mechanical Piston、Gantry、Pulley 与 Elevator Pulley 可按各自 Ponder 组装直线或升降结构。先识别结构边界、粘合/连接方式、控制方块和停止条件，再用低速、小结构测试。Contraption Controls 可对特定已组装结构提供控制；不是所有方块都能成为可移动结构。

移动结构上的箱子、Vault 或其他库存可按 Create 的 Portable Storage Interface 等 Ponder 演示与静止库存交接物品。不要假设 IC2 机器或方块实体可以被装配、在移动时继续工作，或能通过 Portable Storage Interface 与 IC2 管路保持原有功能；逐块以 Ponder/实际方块支持情况为准。Create 0.5.1.i 版本说明特别提到修复移动单方块储罐/Vault 的问题，具体可移动性仍看本版本行为。

### 列车

铺设并连接 Track，放置 Train Station 后依照 Ponder 创建车头/车厢编组并组装列车。Station 用于命名、停靠与编排；Train Schedule 设定站点顺序及等待条件；Train Signal 用于将线路分段并管理交汇，避免列车占用同一冲突区段。调度前先在低复杂度环线上运行一班车，检查轨道连通、站点名称、信号方向与等待条件，再增加分支和车队。

列车适合连接矿点、农场、加工厂和仓库等 Create 物流节点。车厢库存通过 Create 的站台装卸/Portable Storage Interface 等受支持交接方式传输。列车不会把 EU 送进 IC2 电网；若货物最终送进 IC2 机器，先卸到静态箱子，再由 IC2 Tube 或明确兼容的库存接口送入机器。

## IC2 Classic：农业、核能、UU 与装备

### 作物与种子

IC2 Crops 是独立于原版随机刻的作物系统，使用 Crop Sticks 与合适的 Farmland/SubSoil 环境生长。先用基础作物和种子开始，查看 JEI/游戏内 Wiki 对该作物的环境要求；采集种子后用 Crop Analyzer 查看种类与生长、产量、抗性等属性。把两种作物布置在可交配的位置以尝试杂交，使用 Crop Analyzer 预览候选结果；繁育结果仍受系统规则影响，不保证每次得到目标种子。

Simple Crop Library 与 Crop Library 可保存种子/突变信息；Cropmatron、Crop Harvester 等农业设备可帮助维护或收获。它们的输入、覆盖范围、供能和种类支持以各自 GUI、JEI 与 Wiki 为准。Create 的 Mechanical Harvester 则适合在 Ponder 指引下装到可动结构上处理其支持的作物；它和 IC2 Crop Harvester 是不同设备，两套农场也不自动共用种植区/物流接口。Create Goggles 可显示转速/应力网络信息，Create Backtank 与潜水头盔、潜水靴是水下作业装备；具体效果和操作限制按物品说明及 Ponder 确认。

### 核反应堆与蒸汽路线

IC2 Nuclear Reactor 的燃料组件、热管理、冷却/输出方案共同决定产能与风险。用 Reactor Planner 和游戏内 Reactor Wiki 逐格检查方案、组件相互作用与热量，再在安全隔离处小规模验证；过热会造成严重事故。不要直接复制其他版本或整合包的摆法，因为组件、配方、配置及输出可能不同。

IC2 还提供 Steam Reactor、Thermonuclear Reactor 和 Steam Turbine 等高阶系统，但它们的结构、冷却和流体/蒸汽端口与普通 EU 发电机不同。按本版本 Wiki 逐步搭建和确认端口，先处理安全停机、蒸汽输出与储存，再接入下游负载。Create 的 Steam Engine 属于转动网络；无新纪元能源转换器时，它不会向 IC2 电网输出 EU。

### UU-Matter 与后期资源

Mass Fabricator 将大量 EU 转为 UU-Matter；IC2 Classic 游戏内 Wiki 指出 Scrap 可作为可选辅助材料降低该过程的能耗。它是后期高耗能路线，应先确认稳定的 EU 供给，再查看 JEI 中当前可用的 UU-Matter 配方和相关研究/解锁。UU-Matter 可用于本版本支持的材料合成、后期物品或相关作物库用途；具体输出、成本、配方隐藏状态以 JEI 和物品 Wiki 为准，不假设可复制任意物品。

### 装备与工具

IC2 的装备横跨普通工具、充电工具、防护服、喷气背包与高阶能量装备。通过 JEI 查制作路线，并按装备提示使用充电设备/兼容储能设备；工具的电量、工作模式、耐久或防护能力按其自身 Wiki 和 GUI 确认。IC2 Wrench、Electric Wrench、Precision Wrench、Tube Configurator 各有不同用途，不可互相代替。

当前阶段包括四种矿物、六种合金、九种电子材料、非金属处理机、电子材料加工机和工业网络。IC2 Classic 电路、Create 电子管与精密构件继续复用原模组物品；化学实验与化学加工线已退役。

## 新纪元材料与工业网络`r`n`r`n非金属处理机加工石英粉与硅材料；电子材料加工机按配方位置使用多输入槽生产 PCB、集成电路、CPU、RAM、GPU 和工业固态硬盘。物品过滤由服务端已登记配方决定，槽位提示和自动化方向以游戏内实现为准。`r`n`r`n工业网络由工业光纤、网络交换机和工业控制服务器组成。服务器可安装工业 AI 核心并控制已接入设备；权限、拓扑与操作方式依当前游戏界面确认。Create 转速与 IC2 EU 仍是独立能源体系。`r`n`r`n工序和材料数量唯一来自服务器同步的 ProcessGraph，生产规划器与 AI 读取当前同步数据；本文不维护独立材料清单。JEI 显示当前实例实际加载配方，若与图数据不一致，应排查资源加载或同步状态。`r`n`r`n## 配方与版本依据

- IC2 Classic 配方与机器类型：在目标实例 JEI 查询。IC2 官方 [1.19.x DataPacks 文档](https://github.com/TinyModularThings/IC2Classic/wiki/1.19.x-DataPacks)说明其数据包机器配方类别；本文涉及的 IC2 Classic 英文方块/游戏内 Wiki 资源核对自 CurseForge 文件 8545995 对应 JAR。
- Create 设备操作：优先看目标实例方块 Ponder，再看[官方 Create Wiki 用户文档](https://wiki.createmod.net/users/)与[0.5.1 版本说明](https://wiki.createmod.net/users/changelogs/0.5.1)。0.5.1.i 面向 Minecraft 1.19.2；其版本修正见[0.5.1i 说明](https://wiki.createmod.net/users/changelogs/0.5.1i)。
- IC2 Classic 版本文件：[CurseForge 文件 8545995](https://www.curseforge.com/minecraft/mc-mods/ic2-classic/files/8545995)。Create 版本文件：[Create 0.5.1.i 文件 5797604](https://www.curseforge.com/minecraft/mc-mods/create/files/5797604)。
- 新纪元工序与数量以服务器同步 ProcessGraph、当前资源和游戏内 JEI 为准。整合包数据包覆盖、配置差异或换版都可能改变最终可见结果。



