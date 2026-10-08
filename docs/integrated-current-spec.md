# IC2：新纪元 — 当前完整开发规范（交给 Codex）

> 项目名称：**IC2：新纪元 / IC2: New Era**  
> Mod ID：`ic2newera`  
> Minecraft：`1.19.2`  
> Forge：`43.5.0`  
> Java：`17`  
> 核心前置：**IC2 Classic**  
> 核心兼容：**Create / 机械动力**  
> 可选兼容：ProjectE、CC:Tweaked、Advanced Peripherals

---

# 0. Codex 必须先理解的最高原则

本项目不是重新制作一套 IC2，也不是重新制作一套 Create。

最终职责固定为：

```text
Create
=
机械加工、机械动力、机械自动化

IC2 Classic
=
EU、电网、储能、基础加工、已有工业设备、高级能源

IC2：新纪元
=
矿物加工路线最终裁决、少量新增材料、电子科技、工业服务器、局域网、AI自动控制、生产规划
```

最高开发原则：

> **设备兼容，流程统一。**

任何开发前先检查：

1. Minecraft 是否已经有？
2. IC2 Classic 是否已经有？
3. Create 是否已经有？
4. Forge Tag 是否已有兼容材料？

如果已有：

> **优先复用，不重复新增。**

如果已有机器的原配方会绕过新纪元工业流程：

> **保留机器，替换配方。**

---

# 1. 不再做复杂化学系统

目前开发方向已经明确调整。

不要继续开发：

- 复杂化学反应系统
- 强酸生产链
- 大量气体体系
- 催化剂系统
- 温度/压力自由实验系统
- 现实矿物化学提炼
- 铝土矿、闪锌矿、镍黄铁矿等复杂现实矿物
- 大量化工机器
- 大量化学中间体

当前重点是：

```text
IC2 + Create 工业融合
少量核心矿物
矿粉清洗流程
电子元器件
CPU / RAM / GPU / SSD
工业服务器
工业局域网
AI自动化
统一生产规划
```

---

# 2. 核心新增矿物只保留四种

第一阶段仅新增：

```text
Nickel Ore
镍矿

Chromium Ore
铬矿

Titanium Ore
钛矿

Tungsten Ore
钨矿
```

不要继续增加：

```text
Lithium Ore
Cobalt Ore
Molybdenum Ore
Vanadium Ore
Manganese Ore
Magnesium Ore
Silicon Ore
Aluminium Ore
```

除非未来出现不可替代的明确用途，否则不要加。

---

# 3. 已有资源必须复用

Minecraft 已有：

```text
Iron
Gold
Copper
Redstone
Nether Quartz
```

IC2 Classic 已有的材料继续使用，包括但不限于：

```text
Tin
Silver
Uranium
Aluminium
Bronze
Refined Iron
Rare Earth
Magnet
Electronic Circuit
Advanced Circuit
Complex Circuit
Advanced Alloy
Carbon Fiber
Carbon Mesh
Carbon Plate
Energy Crystal
Lapotron Crystal
```

Create 已有：

```text
Zinc
Brass
Precision Mechanism
Electron Tube
Brass Casing
Redstone Link
Cogwheel
Shaft
Mechanical Bearing
```

禁止重复注册同作用材料。

例如不要新增：

```text
ic2newera:copper_ingot
ic2newera:aluminium_ingot
ic2newera:zinc_ingot
ic2newera:brass_ingot
ic2newera:electronic_circuit
```

---

# 4. 下界石英直接作为硅工业来源

不要新增 Silicon Ore。

直接定义：

```text
Nether Quartz
=
硅工业基础资源
```

基础路线：

```text
Nether Quartz
↓
IC2 Macerator / Create Crushing
↓
Quartz Dust
↓
Non-Metal Processor
↓
Silicon
↓
Non-Metal Processor
↓
High Purity Silicon
```

不要增加大量硅化合物中间体。

---

# 5. 新增基础金属

只新增：

```text
Nickel
Chromium
Titanium
Tungsten
```

每种至少需要：

```text
Ingot
Dust
```

如果 Forge Tag 已存在，则必须正确注册：

```text
forge:ingots/nickel
forge:dusts/nickel
...
```

---

# 6. 新增合金保持精简

建议第一阶段仅保留：

```text
Stainless Steel
Invar
Nichrome
Tool Steel
Titanium Alloy
Tungsten Carbide
```

Create 的 Brass 直接复用。

IC2 的 Bronze / Advanced Alloy 等直接复用。

如果某合金没有明确用途，不要添加。

---

# 7. 全新的矿粉规则：必须先得到“含杂质的 X 粉”

这是强制规则。

所有受 IC2：新纪元管理的矿物，在通过粉碎设备加工后，第一阶段产物统一叫：

```text
含杂质的镍粉
Impure Nickel Dust

含杂质的铬粉
Impure Chromium Dust

含杂质的钛粉
Impure Titanium Dust

含杂质的钨粉
Impure Tungsten Dust
```

ID 建议：

```text
impure_nickel_dust
impure_chromium_dust
impure_titanium_dust
impure_tungsten_dust
```

以后如果其他受控金属进入系统，也遵循：

```text
Impure <Material> Dust
含杂质的<材料>粉
```

不要创建：

```text
Dirty Dust
Washed Dust
Clean Dust
Refined Dust
Purified Dust
```

一堆重复阶段。

只需要两个状态：

```text
含杂质的 X 粉
↓
正常 X 粉
```

---

# 8. IC2 Macerator 与 Create Crushing 产出“含杂质的粉”

例如：

```text
Nickel Ore
↓
IC2 Macerator
↓
Impure Nickel Dust
```

Create 路线：

```text
Nickel Ore
↓
Create Crushing Wheel
↓
Impure Nickel Dust
```

Chromium / Titanium / Tungsten 同理。

如果 Create 的 recipe type 更适合先产生 Crushed Ore，也不要增加额外长期中间体；可以直接让最终粉碎结果统一进入 `Impure X Dust`。

目标是少物品。

---

# 9. 含杂质矿粉绝对不能直接熔炼

强制禁止：

```text
Impure Nickel Dust
→ Furnace
→ Nickel Ingot
```

禁止：

```text
Impure Chromium Dust
→ Blast Furnace
→ Chromium Ingot
```

禁止：

```text
Impure Titanium Dust
→ IC2 Electric Furnace
→ Titanium Ingot
```

禁止：

```text
Impure Tungsten Dust
→ 任意炉子
→ Tungsten Ingot
```

也禁止第三方 Mod 注册的等价捷径。

---

# 10. 必须经过 Create Washing

受控矿粉必须经历 Create 已有的 Washing / Splashing 机械流程。

标准：

```text
Impure Nickel Dust
↓
Create Encased Fan + Water
↓
Nickel Dust
```

```text
Impure Chromium Dust
↓
Create Washing
↓
Chromium Dust
```

```text
Impure Titanium Dust
↓
Create Washing
↓
Titanium Dust
```

```text
Impure Tungsten Dust
↓
Create Washing
↓
Tungsten Dust
```

**洗净后的产物就是正常 Dust。**

不要再创建 `Purified X Dust`。

这意味着：

```text
Impure Dust
=
未完成工业处理

Normal Dust
=
已经洗净，可以进入正常熔炼/合金流程
```

---

# 11. 正常 Dust 必须尽量复用原本已有的 Dust

如果 IC2 / Create / Forge Tag 已经存在该元素的普通 Dust：

洗净后直接输出已有 Dust。

例如未来某材料已有：

```text
forge:dusts/<material>
```

优先输出兼容 Item。

不要为了新纪元再创建另一套“洗净粉”。

对于新纪元自己新增的 Nickel / Chromium / Titanium / Tungsten：

正常：

```text
nickel_dust
chromium_dust
titanium_dust
tungsten_dust
```

就是洗净后的最终粉末。

---

# 12. 矿物加工核心工业流程

标准正式工业路线：

```text
Ore
↓
IC2 Macerator / Create Crushing Wheel
↓
含杂质的 X 粉
↓
Create Washing
↓
正常 X 粉
↓
允许的熔炼设备
↓
Ingot
```

这是 `APPROVED_INDUSTRIAL_PROCESS`。

---

# 13. 原版 Furnace / Blast Furnace 必须保留

不要删除 Furnace。

不要删除 Blast Furnace。

不要完全禁止原矿直接烧制。

保留早期低效路线：

```text
Ore
↓
Furnace / Blast Furnace
↓
1× Ingot
```

这个路线定位为：

```text
LEGACY_PROCESS
```

特点：

- 低效率
- 单倍产出
- 适合早期与少量处理
- 不属于正式工业流程
- Production Planner 不推荐
- Industrial AI 不主动使用
- 不提供高效产出

不要为了禁止自动化去破坏 Vanilla Hopper。

允许玩家自己用漏斗做基础熔炉自动化。

但是：

> **想获得真正的高效工业产出和新纪元自动控制，必须走打粉 → 洗净 → 工业熔炼流程。**

---

# 14. 普通矿物与高级矿物的熔炼等级

受控矿物分两档。

## 普通工业金属

```text
Nickel
Chromium
```

洗净后的正常 Dust 可以进入：

```text
Vanilla Furnace
Vanilla Blast Furnace
IC2 Electric Furnace
```

但正式工业自动化优先使用 IC2 电炉体系。

例如：

```text
Nickel Dust
→ IC2 Electric Furnace
→ Nickel Ingot
```

```text
Chromium Dust
→ IC2 Electric Furnace
→ Chromium Ingot
```

---

## 高级工业金属

```text
Titanium
Tungsten
```

正常 Dust 即使已经洗净，也不能用普通 Furnace 直接完成高级冶炼。

必须使用 IC2 的高级炉类设备。

优先复用目标版本 IC2 Classic 已经存在的：

```text
Electric Blast Furnace
Induction Blast Furnace
或目标版本中对应的高级电热/高温炉设备
```

Codex 必须先检查当前 IC2 Classic 1.19.2 API / Registry 中实际可用机器 ID，再绑定 recipe。

不要因为名字不确定就新建一台“New Era Advanced Furnace”。

原则：

```text
Titanium Dust
→ IC2 高级炉
→ Titanium Ingot

Tungsten Dust
→ IC2 更高等级炉/高温路线
→ Tungsten Ingot
```

绝对不要新增专门的钛炉或钨炉。

---

# 15. RecipeGuard 必须实现并更新为三种状态

建立：

```text
RecipeGuard
```

至少区分：

```text
LEGACY_PROCESS
APPROVED_INDUSTRIAL_PROCESS
BLOCKED_BYPASS
```

示例：

## 原矿直接烧

```text
Nickel Ore
→ Furnace
→ Nickel Ingot

LEGACY_PROCESS
```

允许。

## 含杂质粉直接烧

```text
Impure Nickel Dust
→ Furnace
→ Nickel Ingot

BLOCKED_BYPASS
```

必须禁止。

## 洗净粉进入批准炉

```text
Nickel Dust
→ IC2 Electric Furnace
→ Nickel Ingot

APPROVED_INDUSTRIAL_PROCESS
```

允许。

## 高级粉进普通炉

```text
Titanium Dust
→ Furnace
→ Titanium Ingot

BLOCKED_BYPASS
```

必须禁止。

## 高级粉进 IC2 高级炉

```text
Titanium Dust
→ IC2 Advanced Furnace
→ Titanium Ingot

APPROVED_INDUSTRIAL_PROCESS
```

允许。

---

# 16. RecipeGuard 的真正目标

不要暴力删除所有矿物配方。

要做到：

```text
原矿低效直烧
→ 保留

打粉后绕过洗矿
→ 禁止

洗净粉走正确炉子
→ 允许

第三方高效捷径绕过新纪元流程
→ 禁止或替换
```

---

# 17. ProcessGraph 必须成为唯一工业流程数据源

建立：

```text
ProcessGraph
```

所有系统共享：

```text
RecipeGuard
Production Planner
Industrial AI
Industrial Server
BOM Calculator
JEI Integration
Automation Controller
```

ProcessGraph 对矿物节点至少记录：

```text
RAW
IMPURE_DUST
CLEAN_DUST
FINAL_METAL
```

例如：

```text
Nickel Ore
RAW

Impure Nickel Dust
IMPURE_DUST

Nickel Dust
CLEAN_DUST

Nickel Ingot
FINAL_METAL
```

规则：

```text
IMPURE_DUST
不得直接进入 FINAL_METAL
```

必须先：

```text
IMPURE_DUST
→ Create Washing
→ CLEAN_DUST
```

---

# 18. Production Planner 不得推荐低效旧路线

玩家问：

```text
我要自动生产 1024 个镍锭
```

Production Planner 不得建议：

```text
造大量 Furnace + Hopper
```

必须规划：

```text
Nickel Ore
→ Macerator / Crushing Wheel
→ Impure Nickel Dust
→ Create Washing
→ Nickel Dust
→ IC2 Electric Furnace
→ Nickel Ingot
```

高级矿物则规划对应 IC2 高级炉。

---

# 19. 矿物高效产出必须走新纪元正式流程

原矿低效烧制：

```text
1 Ore
→ 1 Ingot
```

正式工业流程可以获得更高等价产率。

实际倍率后续平衡，先设计数据字段，不要写死过高倍率。

例如可以支持：

```text
industrialYieldMultiplier
```

第一版建议保持温和，例如：

```text
1.5x ~ 2.0x equivalent yield
```

不要出现离谱的 5x / 10x。

---

# 20. 只允许最多两个新增加工设备

除网络/服务器设备外，真正用于材料加工的新机器最多只允许两个。

## 设备一：Non-Metal Processor

中文：

```text
非金属处理机
```

主要负责：

```text
Quartz Dust
→ Silicon

Silicon
→ High Purity Silicon
```

以及未来少量真正需要的非金属材料。

不要拆成多台机器。

---

## 设备二：Electronic Fabricator

中文：

```text
电子材料加工机
```

负责：

```text
PCB
Integrated Circuit
CPU
RAM
GPU
Industrial SSD
```

以及必要的电子中间合成。

不要新增：

```text
光刻机
蚀刻机
封装机
晶圆切割机
PCB专用机
CPU专用机
GPU专用机
```

全部合并到这一台设备。

---

# 21. 计算机核心中间合成体必须存在

这是硬性要求。

必须新增：

```text
PCB
Integrated Circuit
CPU
RAM
GPU
Industrial SSD
```

并保留：

```text
Silicon
High Purity Silicon
```

完整核心电子材料：

```text
Silicon
High Purity Silicon
PCB
Integrated Circuit
CPU
RAM
GPU
Industrial SSD
```

不要再增加几十种现实电子零件。

---

# 22. CPU / RAM / GPU / SSD 是游戏中间体，不做电脑模拟器

不要实现：

```text
CPU 核心数
RAM GB
显存
SSD 容量
NVMe 速度
读写速度
缓存层级
```

这是游戏，不需要这些参数。

它们的作用：

```text
CPU
→ 服务器核心组装材料

RAM
→ 服务器核心组装材料

Industrial SSD
→ 服务器必要存储组件

GPU
→ AI Core / 高级 AI 功能必要组件
```

不要做复杂硬件配置页面。

---

# 23. Industrial SSD 必须存在，但不要记录无用历史数据

Industrial SSD 是服务器必要材料。

但不要开发：

- 24小时历史曲线
- 每小时生产统计
- 大型日志数据库
- SSD 容量管理
- 历史记录保留天数

服务器只关注当前游戏有用状态。

例如：

```text
当前哪个设备出问题
缺什么原料
哪个输出满了
哪个网络超载
哪个设备断电
哪个产线停了
```

---

# 24. 电子元件必须融合 IC2 + Create

不要让 CPU/RAM/GPU 全部只吃新纪元自己的材料。

建议方向：

## PCB

使用：

```text
Copper
Silicon
Create Electron Tube
```

实际配方数量后续平衡。

## Integrated Circuit

使用：

```text
PCB
High Purity Silicon
IC2 Electronic Circuit
Create Electron Tube
```

## CPU

建议：

```text
High Purity Silicon
Integrated Circuit
IC2 Advanced Circuit
Gold
Create Precision Mechanism
```

## RAM

建议：

```text
PCB
Integrated Circuit
High Purity Silicon
Redstone
IC2 Electronic Circuit
```

## GPU

建议：

```text
PCB
多个 Integrated Circuit
High Purity Silicon
IC2 Complex Circuit
Create Precision Mechanism
```

## Industrial SSD

建议：

```text
PCB
Integrated Circuit
High Purity Silicon
Gold
IC2 Electronic Circuit
```

具体格子配方由 Codex 在实现时合理设计。

---

# 25. IC2 原有电路必须继续使用

保留：

```text
Electronic Circuit
Advanced Circuit
Complex Circuit
```

不要新增替代品。

基础 `Electronic Circuit` 不要明显加难，避免破坏 IC2 前期。

从 `Advanced Circuit` 开始融合 Create / Silicon / PCB。

建议方向：

```text
PCB
+ IC2 Electronic Circuit
+ Create Electron Tube
+ Gold / Silver
→ IC2 Advanced Circuit
```

更高级：

```text
Integrated Circuit
+ IC2 Advanced Circuit
+ High Purity Silicon
+ Create Precision Mechanism
→ IC2 Complex Circuit
```

---

# 26. 不新增普通电线、物品管、流体管、机械传动

必须复用现有系统。

```text
EU
→ IC2 Cable

机械动力
→ Create Shaft / Cogwheel / Gearbox

物品
→ IC2 Tube / Create Belt / Funnel / Chute / Mechanical Arm

流体
→ IC2 / Create 已有管道

数据
→ Industrial Fiber / WiFi
```

禁止新增：

```text
New Era Power Cable
New Era Item Pipe
New Era Fluid Pipe
New Era Shaft
New Era Belt
```

---

# 27. 工业网络保持极简

只保留：

```text
Machine Network Adapter
Industrial Fiber
Industrial Network Switch
Industrial Control Server
Industrial AI Core
```

这些属于控制/网络设备，不属于材料加工机器，因此不计入“最多两个加工设备”。

---

# 28. Machine Network Adapter 零配置

玩法：

```text
机器旁边放 Adapter
↓
自动识别相邻机器
↓
自动扫描网络
↓
自动联网
```

不要：

```text
IP
Subnet
Gateway
DHCP
Port
Password
Channel
```

右键最多显示：

```text
Machine: Macerator
Network: Factory
Connection: WiFi/Fiber
Status: Online
```

---

# 29. Industrial Network Switch 极简

交换机自带 WiFi。

GUI 只允许：

```text
Network Name:
[Factory]
```

不需要密码。

不需要额外 Wireless Access Point。

Adapter 自动连接最近交换机。

如果同时有光纤：

```text
Fiber > WiFi
```

自动优先有线。

---

# 30. Industrial Fiber 配方复用 IC2 + Create

建议：

```text
IC2 Glass Fibre Cable
+
Create Brass Sheet
+
Glass
→
Industrial Fiber
```

不要增加额外光纤组件。

---

# 31. Machine Network Adapter 配方融合两个 Mod

建议：

```text
IC2 Electronic Circuit
+
Create Precision Mechanism
+
Create Electron Tube
+
Brass Sheet
→
Machine Network Adapter
```

---

# 32. Industrial Network Switch 配方

建议：

```text
IC2 Advanced Circuit
+
Create Precision Mechanism
+
Create Brass Casing
+
Industrial Fiber
+
Create Redstone Link
→
Industrial Network Switch
```

---

# 33. Industrial Control Server 必须使用 CPU / RAM / SSD

服务器不允许再只用一个高级电路直接合成。

建议核心结构：

```text
CPU
RAM
Industrial SSD
IC2 Complex Circuit
IC2 Advanced Machine Block
Create Precision Mechanism
Industrial Fiber
→
Industrial Control Server
```

可以合理加入 Brass Casing。

不要加：

```text
Motherboard
PSU
Network Card
Server Chassis
```

这些没有必要。

---

# 34. Industrial AI Core 必须使用 GPU

AI Core 应明显体现 AI 计算升级。

建议：

```text
GPU
CPU
IC2 Complex Circuit
IC2 Energy Crystal / 合适高级能量部件
Create Precision Mechanism
→
Industrial AI Core
```

插入 Industrial Control Server 后开启自动 AI 工厂功能。

---

# 35. 服务器只显示真正有用的当前信息

默认页面不要塞大量数据。

正常状态示例：

```text
Factory: Normal

Online Devices: 46
Running: 18
Idle: 28

EU: Normal
Mechanical Network: Normal
Materials: Normal

No Problems
```

如果有问题：

```text
Problems: 3

• Nickel shortage
• Macerator #3 output full
• Mechanical Network #2 overstressed
```

点击后再看具体机器。

---

# 36. AI / Server 的核心任务是找问题并处理问题

必须能发现：

```text
WAITING_INPUT
OUTPUT_FULL
NO_POWER
LOW_POWER
OVERSTRESSED
NO_NETWORK
DISABLED
MANUAL_OVERRIDE
ERROR
```

例如：

```text
Stainless Steel Line

Problem:
Nickel shortage

Required: 16
Available: 3
```

不要默认显示大量无关趋势图。

---

# 37. AI 必须能接管所有已支持联网设备

在玩家允许的情况下：

AI 可以：

- 启动设备
- 停止设备
- 根据库存启动生产
- 根据缺料暂停
- 根据输出满暂停
- 根据 EU 不足暂停低优先级设备
- EU 恢复后自动恢复
- 根据 Create Overstressed 提示或停止相关负载
- 控制生产目标

未知第三方 Mod 只能在 Adapter 支持能力范围内控制，不要虚假声称完全支持所有 Mod。

---

# 38. LLM 不能直接控制机器

必须：

```text
Player Text
↓
LLM
↓
Structured Command
↓
Validation
↓
Factory Controller
↓
Device Adapter
↓
Machine
```

没有 API Key 时，本地自动化仍必须全部可用。

---

# 39. AI 模型由玩家自行配置

强制：

```text
默认关闭
```

玩家自行填写：

```text
Provider
Base URL
API Key
Model
```

开发者：

- 不提供公共 API Key
- 不承担调用费用
- 不硬编码 Key
- 不在日志中输出完整 Key
- 不配置模型也能完整玩核心内容

---

# 40. Production Planner 必须实现

玩家可以请求：

```text
我要自动生产 1024 Nickel Ingot
```

系统自动给出：

- 需要的原料
- 需要的 IC2 机器
- 需要的 Create 设备
- 缺失设备
- 需要的电力
- 需要的机械动力
- 需要的网络适配器
- 所需材料清单 BOM

但不要显示过量工程数据。

重点是：

```text
我缺什么？
我要造什么？
哪里有问题？
```

---

# 41. Production Planner 必须遵守矿粉清洗流程

绝对不能规划：

```text
Ore
→ Macerator
→ Dust
→ Furnace
```

必须：

```text
Ore
→ Macerator / Crushing
→ Impure Dust
→ Create Washing
→ Normal Dust
→ Approved Furnace
→ Ingot
```

高级矿：

```text
Titanium / Tungsten
```

必须走 IC2 高级炉。

---

# 42. BOM 必须优先使用 IC2 / Create 已有部件

例如服务器自动化网络可能使用：

```text
IC2 Electronic Circuit
IC2 Advanced Circuit
IC2 Complex Circuit
IC2 Cable
IC2 Machine Block
IC2 Advanced Machine Block
Create Precision Mechanism
Create Electron Tube
Create Brass Casing
Create Redstone Link
Industrial Fiber
Machine Network Adapter
CPU
RAM
GPU
Industrial SSD
```

不要重新创造同类零件。

---

# 43. Create 与 IC2 的结合应体现在配方，而不是新增大量重复东西

设计原则：

```text
电子、电力、能源部件
→ IC2

机械结构、精密机构
→ Create

新材料、硅、CPU/RAM/GPU/SSD、流程裁决、AI
→ IC2：新纪元
```

例如服务器：

```text
IC2 电子
+
Create Precision Mechanism
+
New Era CPU/RAM/SSD
```

这才是主要融合方式。

---

# 44. 关于 Create ↔ IC2 能量转换

如果当前项目已经实现或计划实现 Create Rotation ↔ IC2 EU 转换，则保留该系统。

推荐基准：

```text
128 当前 SU ≈ 1 EU/t
```

双向转换存在效率损耗，默认建议：

```text
Create → EU = 80%
EU → Create = 80%
```

禁止无损往返循环。

如果该功能尚未开发，不要让它阻塞当前核心开发任务。

---

# 45. ProjectE 仍然只能作为可选减负兼容

ProjectE 不是必需前置。

不得让 ProjectE 绕过核心科技流程。

Production Planner / RecipeGuard 的标准工业路径仍由新纪元决定。

不要因为存在 EMC 就跳过：

```text
Impure Dust
→ Washing
→ Normal Dust
```

这条工业规则。

---

# 46. 当前仍缺失、需要 Codex 优先实现的内容

Codex 接下来优先检查并补齐以下部分。

## A. Registry

确认/新增：

```text
Nickel Ore
Chromium Ore
Titanium Ore
Tungsten Ore

Nickel Ingot / Dust
Chromium Ingot / Dust
Titanium Ingot / Dust
Tungsten Ingot / Dust

Impure Nickel Dust
Impure Chromium Dust
Impure Titanium Dust
Impure Tungsten Dust

Stainless Steel
Invar
Nichrome
Tool Steel
Titanium Alloy
Tungsten Carbide

Silicon
High Purity Silicon
PCB
Integrated Circuit
CPU
RAM
GPU
Industrial SSD
```

不要顺手增加其他无关材料。

---

## B. 两台加工设备

只允许：

```text
Non-Metal Processor
Electronic Fabricator
```

不要新增其他材料加工机器。

---

## C. 矿物 Recipe

实现：

```text
Ore
→ Macerator / Create Crushing
→ Impure Dust
```

然后：

```text
Impure Dust
→ Create Washing
→ Normal Dust
```

然后按材料等级进入正确熔炼设备。

---

## D. 普通 / 高级矿物熔炼限制

普通：

```text
Nickel
Chromium
```

高级：

```text
Titanium
Tungsten
```

高级矿正常 Dust 不能进 Vanilla Furnace 直接变锭。

必须绑定 IC2 已有高级炉。

Codex 必须先审计 IC2 1.19.2 当前实际机器和 Recipe Registry，再实现，不允许凭空新造炉子。

---

## E. RecipeGuard

实现三类：

```text
LEGACY_PROCESS
APPROVED_INDUSTRIAL_PROCESS
BLOCKED_BYPASS
```

重点阻止：

```text
Impure Dust → Ingot
Titanium/Tungsten Dust → Vanilla Furnace → Ingot
第三方高效捷径 → 直接绕流程
```

---

## F. ProcessGraph

至少支持：

```text
RAW
IMPURE_DUST
CLEAN_DUST
FINAL_METAL
```

以及：

```text
requiredMachine
allowedMachines
isLegacy
isIndustrial
```

---

## G. 电子系统

完成：

```text
Nether Quartz
→ Quartz Dust
→ Silicon
→ High Purity Silicon
```

并使用 Electronic Fabricator 制作：

```text
PCB
Integrated Circuit
CPU
RAM
GPU
Industrial SSD
```

---

## H. IC2 电路改配方

保持基础 Electronic Circuit 基本不变。

重新设计：

```text
Advanced Circuit
Complex Circuit
```

使其结合：

```text
IC2 原电路
Create Electron Tube
Create Precision Mechanism
Silicon / PCB / Integrated Circuit
```

不要新增 New Era Advanced Circuit。

---

## I. 网络系统

完成：

```text
Machine Network Adapter
Industrial Fiber
Industrial Network Switch
Industrial Control Server
Industrial AI Core
```

Adapter 零配置。

Switch 只允许网络名称。

无密码。

自动 WiFi。

Fiber 优先。

---

## J. 服务器硬件链

服务器必须使用：

```text
CPU
RAM
Industrial SSD
```

AI Core 必须使用：

```text
GPU
```

不要做复杂硬件参数系统。

---

## K. 工厂问题提示

服务器 / AI 必须先实现最有用的功能：

```text
哪个设备有问题
问题是什么
缺什么
需要多少
当前有多少
```

而不是先做历史曲线和报表。

---

## L. Production Planner

必须能根据 ProcessGraph 回答：

```text
为了自动生产 X，需要哪些机器？
需要哪些材料？
现在缺哪些东西？
需要多少 EU / SU？
```

并且只允许批准工业流程。

---

# 47. 第一阶段明确禁止继续增加的内容

Codex 不要自行发挥加入：

```text
更多矿石
更多化工设备
更多炉子
更多管道
更多电缆
更多传送带
更多服务器模块
CPU等级
RAM容量
SSD容量
复杂日志
历史趋势图
网络密码
IP系统
DHCP系统
路由系统
复杂现实芯片制造线
复杂酸/气体系统
```

如果确实认为某项不可缺少，先在最终汇报中说明原因，不要直接添加。

---

# 48. Codex 当前执行顺序

严格按以下顺序工作：

1. 扫描当前仓库结构和已实现内容。
2. 列出与本规范冲突的旧设计。
3. 不删除可复用代码，优先重构。
4. 补齐 4 个核心矿物 Registry。
5. 补齐 `Impure X Dust`。
6. 补齐正常 Dust。
7. 实现 Macerator / Create Crushing → Impure Dust。
8. 实现 Create Washing → Normal Dust。
9. 实现普通/高级矿物熔炼限制。
10. 实现 RecipeGuard。
11. 实现 ProcessGraph。
12. 实现 Silicon / High Purity Silicon。
13. 实现 Non-Metal Processor。
14. 实现 PCB / Integrated Circuit / CPU / RAM / GPU / Industrial SSD。
15. 实现 Electronic Fabricator。
16. 修改 IC2 Advanced Circuit / Complex Circuit 配方。
17. 实现最小网络系统。
18. 实现服务器 CPU/RAM/SSD 要求。
19. 实现 AI Core GPU 要求。
20. 实现当前设备故障/缺料提示。
21. 实现 Production Planner 最小版本。
22. 运行完整构建。

---

# 49. 构建要求

每完成阶段必须运行：

```powershell
.\gradlew.bat clean build
```

如果失败：

1. 读取完整错误日志。
2. 定位问题。
3. 直接修复。
4. 再次构建。
5. 重复直到：

```text
BUILD SUCCESSFUL
```

不要只告诉用户怎么修。

直接修改项目。

不要通过更换：

```text
Minecraft
Forge
Java
IC2版本
```

来逃避编译错误。

---

# 50. 完成后必须汇报

Codex 最终输出：

1. 修改文件列表。
2. 新增文件列表。
3. 删除/废弃内容。
4. 新增 Item 数量。
5. 新增 Block 数量。
6. 4 种矿物是否全部完成。
7. `Impure X Dust` 是否全部完成。
8. Create Washing 是否真正成为必经工业步骤。
9. 哪些原矿仍可 Legacy Furnace 直烧。
10. Nickel / Chromium 的批准熔炼设备。
11. Titanium / Tungsten 的批准 IC2 高级炉。
12. RecipeGuard 阻止了哪些捷径。
13. ProcessGraph 当前路线。
14. Silicon 路线是否完成。
15. CPU / RAM / GPU / Industrial SSD 是否完成。
16. 两台新加工设备是否完成。
17. IC2 Advanced Circuit / Complex Circuit 新配方。
18. 网络系统当前状态。
19. Industrial Control Server 是否强制 CPU/RAM/SSD。
20. Industrial AI Core 是否强制 GPU。
21. 当前 AI 能检测哪些设备问题。
22. Production Planner 当前支持什么。
23. 是否存在硬编码 API Key。
24. `build` 是否成功。
25. 最终 JAR 路径。

未完成的内容必须明确标记为未完成。

不要虚报。

---

# 51. 最终一句话原则

> **少物品、少机器、少配置；IC2 管电，Create 管机械，新纪元管流程、电子和智能控制。**

> **原矿可以低效直接烧；一旦打成粉，就必须先得到“含杂质的 X 粉”，经过 Create 洗净成为正常 X 粉后，才允许进入正式熔炼。高级矿物必须使用 IC2 已有高级炉完成。**


## 2026-10-08 用户最新增量：终端机

本聊天明确新增终端机作为仓储/物流和数据控制端点（ic2newera:terminal），容量144格，即12×12；不承担材料加工，不改变两台加工机上限。配方复用现有CPU/GPU/SSD/PCB等中间体，强制原版箱子和ProjectE暗物质；数量以ProcessGraph为准。服务器自动关联打开AI界面的当前玩家本机配置，WiFi/光纤沿用权限网络，密钥不传游戏服务器。多人操作与终端面规则说明随游戏内助手。已实现/构建/安装状态见terminal-stage-delivery.md，实机待验收。此前网络列表的“只保留”不排除本次新增终端机。
