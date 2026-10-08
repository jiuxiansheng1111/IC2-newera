# IC2：新纪元——核心兼容与流程裁决规范

后续实施以 `integrated-current-spec.md` 的最新整合规范为准；本文件保留兼容原则。原矿 Legacy 单倍直烧、电子中间体与加工设备上限按新规范执行。

2026-10-07 修订：按用户最新要求移除化学体系设计。矿物加工使用既有机械加工、选矿、熔炼和合金流程。

## 一、总体原则

《IC2：新纪元》不是把 IC2 Classic 和 Create 的功能重新做一遍。

目标是：

```text
Create
负责机械加工

IC2 Classic
负责 EU、电力、基础加工、已有工业设备

IC2：新纪元
负责现实工业流程、现代材料、科研、工业网络、自动控制
```

整个 Mod 必须遵循：

**能复用就复用，不重复造轮子。**

---

# 二、矿物加工最终裁决权

必须明确：

```text
IC2：新纪元拥有矿物加工路线的最终裁决权。
```

所有被《IC2：新纪元》纳入管理的矿物、金属、合金和高级材料，都必须遵循新纪元定义的标准工业流程。

例如：

```text
矿石
↓
破碎
↓
选矿 / 洗矿 / 富集
↓
精矿
↓
研磨 / 熔炼
↓
金属
↓
合金 / 板材 / 部件
```

不是所有材料都必须走完整链条。

简单材料可以简化。

复杂材料则使用更完整流程。

重点是：

**不能存在能够直接绕开标准工业路线的捷径。**

---

# 三、Create 的职责

Create 定位为：

```text
机械加工与机械自动化平台
```

应该优先使用现有 Create 设备完成：

```text
破碎
研磨
输送
分拣
机械臂搬运
皮带运输
搅拌
基础机械加工
机械动力
```

例如：

```text
Crushing Wheel
Millstone
Mechanical Mixer
Mechanical Press
Belt
Funnel
Chute
Mechanical Arm
Item Vault
Shaft
Cogwheel
Gearbox
```

都应该尽可能继续发挥作用。

但是：

Create 不拥有受控矿物的最终冶炼决定权。

如果 Create 原始配方存在：

```text
矿石
→ 金属锭
```

或者：

```text
碎矿
→ 洗矿
→ 大量金属粒
```

并且绕过《IC2：新纪元》工业流程，

则：

```text
禁用
或
替换
```

例如：

原流程：

```text
Crushed Zinc Ore
↓
Washing
↓
Zinc Nugget
```

可以替换成：

```text
Crushed Zinc Ore
↓
Washing
↓
Zinc Concentrate
```

然后继续进入新纪元工业流程。

---

# 四、IC2 Classic 的职责

IC2 Classic 定位：

```text
EU
电网
储能
基础机器
已有工业机器
高级能源
核能
UU-Matter
Nano / Quantum
物流系统
```

优先复用：

```text
Macerator
Extractor
Compressor
Electric Furnace
Alloy Smelter
Canner
Rare Earth Extractor
Blast Furnace
Mass Fabricator
```

以及已有：

```text
Cable
Transformer
Battery
Tube
Fluid Pipe
Tank
Logistic Grid
```

不要重新制造同类机器。

但是：

如果 IC2 原始配方能够绕过新纪元标准工业流程，

同样：

```text
禁用
或
替换
```

例如：

```text
某复杂矿粉
→ Electric Furnace
→ 最终金属
```

如果批准工业路线要求：

```text
矿粉
→ 精矿
→ 研磨
→ 熔炼
→ 金属
```

则旧快捷配方必须被移除。

---

# 五、Vanilla 同样受管理

不能只修改 Create 和 IC2。

Minecraft 原版的：

```text
Furnace
Blast Furnace
Smoker
Crafting Table
Stonecutter
```

如果存在能够绕过受控材料工业流程的配方，

也必须：

```text
禁用
或
替换
```

例如：

如果某种高级矿物属于受控材料，

就不能允许：

```text
Raw Material
→ Furnace
→ Ingot
```

---

# 六、受控材料系统

建立：

```text
ControlledMaterialRegistry
```

其中登记：

```text
iron
copper
tin
zinc
nickel
chromium
aluminium
titanium
tungsten
cobalt
lithium
molybdenum
manganese
rare_earth
...
```

但不要一开始把所有金属全部设为复杂工业流程。

可以分等级：

```text
BASIC
INTERMEDIATE
ADVANCED
SPECIAL
```

例如：

```text
Iron
Copper
Tin
```

保留相对简单路线。

而：

```text
Aluminium
Nickel
Chromium
Titanium
Tungsten
Rare Earth
```

使用更完整工业路线。

---

# 七、Recipe Guard

建立：

```text
RecipeGuard
```

游戏加载配方时自动检查。

检测：

```text
Ore
Raw Ore
Crushed Ore
Ore Dust
Concentrate
```

是否能够通过未授权 Recipe 直接变成：

```text
Ingot
Nugget
Metal Block
Final Alloy
```

如果是：

```text
BLOCK
或
REPLACE
```

日志示例：

```text
[IC2 New Era Recipe Guard]

Bypass recipe detected:

Recipe:
create:example_recipe

Input:
controlled mineral

Output:
controlled metal

Reason:
Bypasses IC2 New Era industrial process.

Action:
BLOCKED
```

---

# 八、不要把 Recipe Guard 写成暴力删除所有配方

必须维护允许列表：

```text
ApprovedProcess
ApprovedRecipe
ApprovedMachine
```

例如：

允许：

```text
IC2 Macerator
矿石 → 矿粉
```

允许：

```text
Create Crushing Wheel
矿石 → 碎矿
```

允许：

```text
Create Washing
碎矿 → 精矿
```

允许：

```text
IC2 Electric Furnace
批准的矿粉 → 金属锭
```

而不是：

“只要输入有矿石就删掉。”

---

# 九、统一 ProcessGraph

必须建立：

```text
ProcessGraph
```

这是整个 Mod 的唯一工业流程真相来源。

所有系统读取同一套数据：

```text
Recipe Guard
Production Planner
AI Assistant
Research System
Automation Controller
BOM Calculator
JEI Integration
Industrial Server
```

例如：

```text
Zinc Ore
↓
Crushing
↓
Crushed Zinc Ore
↓
Washing
↓
Zinc Concentrate
↓
Grinding
↓
Zinc Dust
↓
Smelting
↓
Zinc
```

任何一个系统都不能维护自己的第二套流程。

---

# 十、Production Planner 只能使用批准流程

AI 或 Production Planner 在计算：

```text
“怎么制造 1024 个 Zinc Ingot？”
```

时，

只能使用：

```text
ProcessGraph
```

里的批准路径。

绝对不能因为：

Create 有更便宜路线

IC2 有旧配方

Vanilla 有熔炉配方

就自动选择捷径。

---

# 十一、工业网络配置必须极简

这是游戏。

不要模拟真实企业网络管理员工作。

工业网络正常流程应该是：

```text
放置 Switch
↓
输入网络名称
↓
完成
```

例如：

```text
Factory
MainPlant
SteelWorks
```

除此之外默认不需要配置。

不要：

```text
IP
Subnet
Gateway
DNS
Port
DHCP
Password
WPA
Channel
```

---

# 十二、网络适配器必须零配置

流程：

```text
机器旁放置 Adapter
↓
Adapter 自动识别机器
↓
扫描附近网络
↓
自动加入最近的 ICCN 网络
↓
服务器发现机器
```

玩家不用打开 GUI 配置。

最多右键看到：

```text
Connected Machine:
Macerator

Network:
Factory

Connection:
WiFi

Status:
Online
```

---

# 十三、交换机自带 WiFi

不要单独增加：

```text
Router
Wireless Access Point
Repeater
```

Industrial Network Switch 本身拥有：

```text
Fiber
+
WiFi
```

无线 Adapter 自动连接。

大型基地需要跨区域时：

```text
Switch
↓
Fiber
↓
Switch
```

即可。

---

# 十四、网络不需要密码

不加入密码系统。

原因：

没有有效玩法价值。

只增加配置负担。

多人环境下：

使用：

```text
Owner UUID
Team
Permission
```

解决访问权限即可。

不是用 WiFi 密码解决。

---

# 十五、物品、电力、流体全部复用已有系统

最终职责：

```text
EU
→ IC2 Cable

机械动力
→ Create Shaft / Cogwheel

物品
→ IC2 Tube / Create Belt / Funnel / Chute

流体
→ IC2 / Create 已有 Fluid Pipe

数据
→ ICCN Fiber / WiFi
```

不要再添加：

```text
New Era Power Cable
New Era Item Pipe
New Era Fluid Pipe
New Era Mechanical Shaft
```

---

# 十六、设备配方要融合 IC2 + Create

ICCN 和现代工业设备配方应优先结合：

```text
IC2电子部件
+
Create机械部件
+
新纪元材料
```

例如：

Machine Network Adapter：

```text
IC2 Electronic Circuit
+
Create Precision Mechanism
+
Create Brass Sheet
+
Redstone
```

Industrial Fiber：

```text
IC2 Glass Fibre Cable
+
Create Brass Sheet
+
Glass
```

Industrial Network Switch：

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
```

Industrial Control Server：

```text
IC2 Advanced Machine Block
+
IC2 Complex Circuit
+
Create Precision Mechanism
+
Create Brass Casing
+
Industrial Fiber
```

不要为了服务器再新增：

```text
CPU
RAM
SSD
Motherboard
Router Chip
WiFi Chip
```

IC2 已经有足够的电子科技物品承担这些抽象。

---

# 十七、少物品原则

每次准备新增 Item / Block 时必须先检查：

```text
Minecraft
IC2 Classic
Create
```

是否已经有可以承担该功能的东西。

如果已有：

直接使用。

只有满足下面条件才新增：

```text
现有模组没有这个功能
并且
这个功能对于新纪元科技树确实必要
```

---

# 十八、机器同样遵循少添加原则

如果 IC2 已有：

```text
Macerator
```

就不要做：

```text
New Era Crusher
```

如果 Create Crushing Wheel 已经适合：

就优先用 Crushing Wheel。

只有在现有机器无法承担真实工业步骤时，

才新增：

```text
Vacuum Furnace
```

---

# 十九、最终定位

最终三者关系必须保持：

```text
Create
=
机械工业平台

IC2 Classic
=
电气工业平台

IC2：新纪元
=
现代工业科学与控制层
```

进一步细分：

```text
Create：
机械加工
机械自动化
轴
齿轮
皮带
机械臂
机械运输

IC2：
EU
发电
储能
电缆
基础机器
高级机器
核能
UU
Nano
Quantum

IC2：新纪元：
矿物工艺裁决
现代冶金
先进材料
科研系统
半导体
工业计算
服务器
AI控制
ProcessGraph
ProductionPlanner
RecipeGuard
```

---

# 二十、最高原则

任何开发决策都先问：

```text
这是不是已经被 Minecraft、IC2 或 Create 做过了？
```

如果是：

```text
复用。
```

再问：

```text
现有实现是否会绕过 IC2：新纪元的核心工业流程？
```

如果是：

```text
保留设备，
替换配方。
```

而不是删除整个设备。

最终目标是：

**设备兼容，流程统一。**

即：

```text
允许玩家使用 Create 的机器。

允许玩家使用 IC2 的机器。

但是这些机器最终都服务于
IC2：新纪元定义的工业流程。
```
