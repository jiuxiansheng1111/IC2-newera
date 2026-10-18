# 机器人电池兼容与四档充电宝

2026-10-18 当前规则：恢复等级限制，普通档更名为初级，机器人货物格自动补电与 24/6 小时续航已接入。最新交付见 [续航记录](robot-power-balance-2026-10-18.md)。下方历史回执保留。

## 电池兼容与悬停说明

实际 IC2 Classic 2.1.3.4 的 `ContainerEnergyStorage -> FilterSlot.createChargeSlot -> ElectricItemFilter -> ElectricItem.MANAGER.charge(..., simulate=true)` 会校验物品最低等级。旧机器人电池为 tier 3，BatBox 为 1、MFE 为 2，因此会拒收；`IDamagelessElectricItem` 本身已正确接入。

按最新要求，电池最低等级恢复为 3（MFSU/HV 及以上），继续使用 IC2 原生 `charge` NBT；容量 4,000,000 EU、传输上限 2048 EU 不变。放入储电设备的充电槽；空电池仍不能用于放电槽。旧电量保持可读。

娃娃悬停仅保留名称和 `耐久度：剩余次数 / 5`，耐久条保留。图纸、控制核心、电池、手机、充电宝不添加长说明，电量条保留。设备槽位用途提示继续显示。

## 充电宝

| 名称 | 注册 ID（前缀 ic2newera:） | 容量 EU | 总输出上限 EU/t | 目标物品最高等级 |
| --- | --- | ---: | ---: | ---: |
| 初级 | `power_bank` | 4,000,000 | 512 | 1 |
| 中级 | `intermediate_power_bank` | 8,000,000 | 2048 | 2 |
| 高级 | `advanced_power_bank` | 16,000,000 | 8192 | 3 |
| 核能 | `nuclear_power_bank` | 32,000,000 | 32768 | 4 |

初级、中级、高级、核能分别要求最低一至四级 IC2 充电设备，实际速度由设备能力限制。低等级物品不能给高等级物品补电。放在副手会自动依次给手机、机器人电池、其他兼容 IC2 物品充电；放在背包（包括主手快捷栏）时只自动给手机充电。无需按住右键，也不需要开关；不会在充电宝之间来回转移。单件目标的传输上限仍有效，一块机器人电池最多接收 2048 EU/t，高级档总吞吐可分配给多块电池。副手移回背包后停止给电池/装备补电，仍可给手机补电；无兼容目标或电量耗尽时不转移电量，也不刷提示。高级或核能充电宝放入机器人货物格会自动给已安装的电池补电，无需取出电池；初级和中级等级不足。

核能档：另一手持 IC2 铀燃料棒，潜行右键装入或替换；另一手为空时潜行右键取出，背包满则落在玩家身边。保留燃料原有耐久，每 20 tick 消耗 1 点；`IReactorPlannerComponent.FUEL_ROD / ENERGY_PRODUCTION` 提供产能，先取整 EU/t 再乘 20，与 IC2 裂变充电板一致。耗尽后通过 `IUsableUranium.createDepletedUraniumRod()` 留下废棒。满电暂停消耗；玩家携带或位于已放置、存活机器人的货物格时生成 EU，不补算离线时间，掉落或放箱子里不生成。本物品使用屏蔽结构，不运行完整反应堆热量模拟。

手机现有物品已接入 IC2 原生电量，容量 200,000 EU、最低充电等级 1、输入上限 512 EU/次，只显示名称和电量条。手机与有电的充电宝同时放在背包即可自动补电，拿在手里也能补电；也可直接放入 IC2 充电设备。打开/刷新状态各消耗 20 EU，每次命令请求消耗 100 EU；耗电在服务端限速后执行。新制手机和旧版无电量数据的手机需要先充电，不凭空赠送电量。

## 批准配方

全部使用已有电子材料加工机，每条六种输入。由 ProcessGraph 唯一来源生成，科技检索/JEI/规划器共用；无成就门禁。新制成品为空电、核能档为空燃料仓，升级不继承旧物品电量。

| 产物 | 六种输入 | tick | EU/t |
| --- | --- | ---: | ---: |
| 初级 | 机器人电池×1、PCB×1、电路×1、高级合金板×2、绝缘铜线×2、碳板×1 | 600 | 64 |
| 中级 | 初级充电宝×1、兰波顿水晶×1、CPU×1、高级电路×2、钛合金锭×2、精密构件×1 | 900 | 96 |
| 高级 | 中级充电宝×1、荧光兰波顿水晶×1、复杂电路×2、GPU×1、碳化钨锭×2、碳板×4 | 1200 | 128 |
| 核能 | 高级充电宝×1、**UESC 能量水晶×1**、IC2 核反应堆×1、CPU×1、复杂电路×2、碳化钨锭×4 | 1600 | 256 |

已核对依赖 JAR 注册表：`ic2:lapatron_crystal` 为 1,000,000 EU / tier 3；`ic2:glowtronic_crystal` 为 7,500,000 EU / tier 3；最高等级的 `ic2:uesc` 能量水晶为 17,500,000 EU / tier 4。更大容量的 `pesd` 是储能设备，不是水晶。核能配方强制 UESC，不以低级水晶代替。

## 资源索引

- `PowerBankItem.java`、`NuclearPowerBankItem.java` 位于 `src/main/java/com/ic2newera/robot/`。
- `scripts/update-process-graph.py` 生成 `src/main/resources/data/ic2newera/process_graph/main.json`，Gradle 再生成四条机器配方。
- `scripts/generate-robot-resources.py` 原创生成三个 16×16 图标及核能 32×32 双层图标、同名模型及中英文名称；图标位于 `src/main/resources/assets/ic2newera/textures/item/`，模型位于同级 `models/item/`，文件名对应上表 ID。
- 初级为单芯灰绿壳，中级为双芯蓝壳，高级为带散热片的紫色晶体壳，核能为深色壳与绿色核标识；核能使用 Forge 自发光图层，均保留生成物品的持有比例。
- 操作说明放在科技检索中，不加物品长描述。

## 2026-10-17 自动补电更新

副手自动充电、背包仅给手机充电、手机电量及服务端请求扣电已接入源码。核能版同时继承自动供电和既有燃料自充。四档配方、容量、图标以及核能装卸燃料方式保持不变。增量离线构建26秒成功，未新增或运行测试。包内543个JSON/mcmeta可解析、766份资源保持不变。已于 2026-10-17T23:39:13.2270856+08:00 更新PCL新纪元JAR，8个实际模组哈希匹配。当前包1,637,156字节，SHA-256 `A401B947523832661FB240418642049D8350F09ABB41AB062D3156E7B9479820`。证据在 `build-isolated/auto-powerbanks-20261017-build.log`、`build-isolated/auto-powerbanks-20261017-package-audit.json` 和 `build/deployment/pcl-installation.json`。未启动游戏，自动充电、手机耗电与燃料联动仍待实机确认。

## 前一版交付状态

电池修复 clean build 36 秒成功，充电宝最终增量构建 25 秒成功（均 `-x test --offline`）。未新增或运行测试。包内 543 个 JSON/mcmeta 可解析，240 条既有工艺路线、69 份机器人资源、8 个语音类保持不变。ProcessGraph 当前共 244 条路线。

最终 JAR `releases/ic2newera-0.1.0-dev.jar`，1,635,807 字节，SHA-256 `A34F8A08C1690E3F765C8E1757F612F174626A8E6AC82E1C1C8E786A3AF4D937`。已于 2026-10-17T23:28:19.9302133+08:00 安装到 PCL 1.19.2-Forge_43.5.0，只替换 1 个 JAR，回读 8 个已安装模组哈希均匹配。安装前游戏进程为 0；未启动/关闭游戏或修改存档、LLM 配置。

证据：`build-isolated/robot-battery-20261017-build.log`、`build-isolated/powerbanks-20261017-final-build.log`、`build-isolated/powerbanks-20261017-package-audit.json`、`build/deployment/pcl-installation.json`。清理前备份在 `build-isolated/deployment-history/robot-battery-20261017-231235/`。

源码/API核对、构建和安装不等于实机验收。游戏内槽位插入、真实充放电、燃料耗尽、存档重载及多人交互待玩家确认。
