# IC2 Refactored 1.20.1 注册 ID 审计

## 2026-10-23 第10轮构建后的进展

下方55项分类是迁移前的审计快照，不是当前替换完成清单。主审已按注册器、原生标签、工具/护甲类型及电缆物品行为核对并应用21项JSON映射，分别记录于 `build-isolated/migration-1201/applied-material-mappings.json` 与 `applied-equipment-material-mappings.json`。其中基础粉/锭沿用目标材料，青铜装备采用Refactored原生属性，不宣称与Classic数值一致。太阳能另在Java规划与执行端完成目标ID、配置出力和原生光照函数适配，不属于上述JSON映射数。

第10轮完整构建通过。build10剩余ID清单 `build-isolated/migration-1201/remaining-classic-ids-build10.json` 是当时的引用快照，不是当前资源的逐项状态。其后已按单独收据将一处机器壳投入映射到目标 `ic2:machine`；当前仍需核验的项及处理边界见下方本轮补充。缺失合金炉/高级电炉与银矿、铝资源等仍未补齐，ProcessGraph继续严格阻塞，不将编译通过当作路线可运行。

## 初始审计快照

审计对象是隔离迁移副本 `build-isolated/migration-1201/newera/src/main/resources` 中出现的全部 `ic2:` 标识，与锁定候选 `build-isolated/migration-1201/dependencies/ic2-forge-2.10.33-ex120.jar` 比较。候选 JAR SHA-256 为 `9C2BAABD51F6E5EECC0E75F9B0DC8474DD6D2A3DD701B1000924253D3ACA110F`。本次仅检查资源和候选 JAR 的注册字节码/资源目录；没有构建、运行游戏、测试或改动迁移副本的 Java/资源。

## 结论

当前资源不能直接作为 IC2 Refactored 的 1.20.1 数据包使用。候选注册器中存在 12 个与资源引用 ID 完全相同的常用项目；另有 27 个引用可找到名称不同的候选 ID，但它们必须逐项由迁移负责人确认材料/机器语义，不能直接批量替换。其余 16 个资源 ID 在候选 IC2 的注册类中没有对应注册。

最严重的流程阻塞是：IC2 Classic 资源要求的 `ic2:alloy_smelter`、`ic2:electric_blast_furnace`、`ic2:blast_induction_furnace` 均未注册。Refactored 注册了 `ic2:blast_furnace` 与 `ic2:induction_furnace`，但注册 ID、机型和配方语义不相同；不能据名字把它们作为等价设备。项目约定的所有合金路线必须保留合金炉约束，高级材料继续要求高级炉。未有经核实的机器映射前，这些 ProcessGraph 工序应标成迁移阻塞，不能改投普通炉、感应炉或删除配方来让图加载通过。

材料方面，Refactored 确实注册银粉/银锭与锡原矿/深层锡矿/粗锡，但没有注册银矿、深层银矿、下界银矿或粗银；也没有铝/铝粉/铝锭/粗铝。其注册类中也没有精炼铁、复杂电路、Glowtronic Crystal、UESC、先进合金板或青铜盾等资源所需条目。旧资源中银和铝的 ProcessGraph 材料定义及其衍生路线因此无法靠可选 Forge tag 处理：路线仍引用缺失物品，不能作为可运行生产路径。

## 分类定义

- `verifiedEquivalent`：候选 JAR 的真实注册器使用同一个 `ic2:` ID；这里只证明注册 ID 存在，不证明某个配方、产量或机器属性相同。
- `differentSemantics`：字节码/资源中找到一个可能相关但 ID 不同、用途/行为尚未证实等价的候选，或者同名 ID 在目标侧已有不同含义。它是人工审阅队列，不是可直接应用的映射。
- `missing`：没有在 IC2 Refactored 的注册器中找到同 ID 或可安全确认的候选。

候选 JAR 的 `ic2.core.ref.Ic2Items` 静态初始化器将名称字符串传给私有 `register(String, Item)`；该方法通过 `IC2.getIdentifier` 生成 `ResourceLocation` 并调用 `EnvProxy.registerItem`。`ic2.core.ref.Ic2Blocks` 以同一形式注册方块；机器候选亦在该类静态初始化器中注册。故名称证据来自注册调用点，不是只依据语言文件、模型文件或英文相似度。JAR 资源目录中的 `assets/ic2/models/item` 和 `assets/ic2/blockstates` 仅作交叉检查，不单独作为注册证明。

资源范围包括 `process_graph/main.json`、`campaign.json`、`arsenal.json`、机器人蓝图配方、Forge item tags、technology 节点及 AI 说明。ProcessGraph 是路线的权威来源；Forge tag 中将旧 ID 标为 `required:false` 也不会修复图内缺失的硬引用。

## 机器与流程约束

| 资源 ID | 候选实际注册 | 分类 | 迁移约束 |
|---|---|---|---|
| `ic2:alloy_smelter` | 无 | missing | 所有受控合金路线均保留合金炉要求；等待经验证的兼容设备/专用适配，不可用普通炉或工作台替代。 |
| `ic2:electric_blast_furnace` | `ic2:blast_furnace` | differentSemantics | 不将普通 blast furnace 当作高温电炉。钛、钨及其他高级炉路线保持阻塞。 |
| `ic2:blast_induction_furnace` | `ic2:induction_furnace` | differentSemantics | 不能按名称把普通感应炉当作高温/高效高级炉。 |
| `ic2:electric_furnace` | `ic2:electric_furnace` | verifiedEquivalent | ID 已注册；具体 recipe schema 与能耗仍需迁移侧确认。 |
| `ic2:macerator` | `ic2:macerator` | verifiedEquivalent | ID 已注册；具体掉落及 recipe schema 仍需迁移侧确认。 |
| `ic2:solar_panel_lv` | `ic2:solar_generator` | differentSemantics | 不能假定低压等级或 8 EU/t；在核对输出/电压 API 与昼夜行为前，不以它满足原太阳能规划。 |

Refactored 还注册了 `ic2:induction_furnace`、`ic2:blast_furnace`、`ic2:generator` 等设备，但并未注册上述 Classic 高级机 ID。保留路线阶段与工艺限制；如果所需机型不可用，规划、配方生成和生产应返回明确的不可用/阻塞状态。

## 材料与注册 ID 结论

| Classic 资源引用 | 候选 ID / 结论 | 分类 | 说明 |
|---|---|---|---|
| `dust_iron`, `dust_gold`, `dust_copper`, `dust_coal`, `dust_diamond`, `dust_tin`, `dust_silver`, `dust_bronze` | `iron_dust`, `gold_dust`, `copper_dust`, `coal_dust`, `diamond_dust`, `tin_dust`, `silver_dust`, `bronze_dust` | differentSemantics | 材料名相似但注册 ID 顺序不同；确认物品/配方行为后再逐项迁移。 |
| `ingot_tin`, `ingot_silver`, `ingot_bronze` | `tin_ingot`, `silver_ingot`, `bronze_ingot` | differentSemantics | ID 名称顺序不同，必须显式映射。 |
| `tin_ore`, `deepslate_tin_ore`, `raw_tin` | 相同 ID | verifiedEquivalent | 在目标 item/block 注册初始化器中出现。 |
| `silver_ore`, `deepslate_silver_ore`, `silver_ore_nether`, `raw_silver` | 无 | missing | 银粉/银锭存在不能补出矿石、深层矿、下界矿或粗矿。 |
| `aluminium_ore_nether`, `dust_aluminium`, `ingot_aluminium`, `raw_aluminium` | 无 | missing | 注册类中没有铝资源；不能替换成其他金属。 |
| `ingot_refined_iron` | 无 | missing | 候选没有精炼铁物品注册；钢锭或普通铁不能作为等价替代。 |
| `circuit`, `advanced_circuit`, `carbon_plate`, `industrial_diamond` | 相同 ID | verifiedEquivalent | 候选注册器中均有同名注册。 |
| `complex_circuit`, `glowtronic_crystal`, `uesc`, `plate_advanced_alloy` | 无 | missing | 候选注册器没有这些 ID；不能降级为普通/高级电路、合金或其他晶体。 |
| `lapatron_crystal` | `lapotron_crystal` | differentSemantics | 拼写与 ID 不同；候选有 Lapotron Crystal，需核对等级/充能/配方用途再映射。 |
| `copper_insulated_cable_item` | `insulated_copper_cable` | differentSemantics | 目标注册了绝缘铜缆物品 ID；是否与旧工艺消耗品、放置状态一致需核对。 |
| `machine_block` | `machine` | differentSemantics | 目标有通用机器方块 `machine`，不证明旧机器壳/配方等级一致。 |
| `bronze_block`, `copper_cable`, `nuclear_reactor` | 相同 ID | verifiedEquivalent | 候选方块注册类中存在。 |
| `bronze_dust_block`, `bronze_shield` | 无 | missing | 未找到目标注册。 |
| `tool_axe_bronze`, `tool_hoe_bronze`, `tool_pickaxe_bronze`, `tool_shovel_bronze`, `tool_sword_bronze` | `bronze_axe`, `bronze_hoe`, `bronze_pickaxe`, `bronze_shovel`, `bronze_sword` | differentSemantics | 工具 ID 不同，伤害、耐久、工具等级需迁移核验。 |
| `armor_bronze_boots`, `armor_bronze_chest`, `armor_bronze_helmet`, `armor_bronze_leggings` | `bronze_boots`, `bronze_chestplate`, `bronze_helmet`, `bronze_leggings` | differentSemantics | 装备 ID 命名不同；胸甲部位目标命名为 `chestplate`。 |
| `acquire_refined_iron` | 目标同名 advancement 存在 | differentSemantics | 目标文件内容实际奖励/检查 `ic2:steel_ingot`，标题翻译键也指向 acquire steel；不能视作精炼铁目标。 |

旧资源 ID 共 55 个（含 advancement ID）。完整逐 ID、目标候选和分类见机读清单。

## 建议的迁移顺序

1. 先将图中 ID 按此清单分为“可按同 ID 保留”“待逐项映射”“无可用候选”。不要在加载失败时删掉工序、材料阶段或机器要求。
2. 对候选机器/材料由迁移负责人确认实际配方接口、物品行为、原生标签和输出后，单独形成明确映射表。机器的 ID 映射还需要验证加工温度、输入槽、能耗和产出，名称相似不足以通过。
3. 对缺失资源继续保留 ProcessGraph 的阶段与审批边界，并让受影响配方/规划返回“依赖 IC2 Refactored 中缺失注册/机器”这一明确阻塞。只有在用户批准并且目标侧真实注册/行为可证实后，才能选择实现兼容适配或重新设计路线。
4. 迁移后分别校验主图、campaign、arsenal、Forge tags、科技树与蓝图的所有引用；可选 tag 项不能掩盖图和配方中的必需缺失引用。

本报告是静态注册审计，不代表资源包已加载、模组已构建、已安装或经过实机验证。


## 2026-10-23 后续复核：机器壳与储能候选

本轮只读本地IC2R 2.10.33-ex120 JAR的注册器字节码和配方资源，并调整一处已证实的配方材料ID。可机读证据见 `build-isolated/migration-1201/verified-equivalent-machine-shell-mapping.json`。

`ic2:machine_block` 在build10清单中的唯一资源用途是工业控制服务器合成所需的一件通用机器壳。目标JAR的 `Ic2Blocks.<clinit>` 按 `machine` 注册方块，且 `data/ic2/recipes/shaped/machine.json` 明确用6个 `forge:plates/iron` 制作一个 `ic2:machine` 中间件；因此只将该ProcessGraph输入从 `ic2:machine_block` 改为 `ic2:machine`。这仅证明通用壳料用途，不映射Classic加工机器ID或等级。

目标JAR将 `ic2:lapotron_crystal` 注册为 `ItemBattery`，构造参数含10,000,000 EU容量、8,092 EU/t传输参数和Tier 4，并提供自身合成、充放电配方。迁移资源的 `ic2:lapatron_crystal` 仍未改名，因为本地证据没有证明Classic原物品的容量、传输等级和配方成本与目标相同。目标 `ic2:energy_crystal` 也确有储能注册与原生配方（1,000,000 EU、2,048 EU/t参数、Tier 3），但当前迁移资源没有引用它，不能拿来替换Lapotron。

本轮不修改剩余机器/材料阻塞项，不新增或删减路线，不放宽ProcessGraph。JSON静态解析通过；未构建、测试或启动游戏。旧 `remaining-classic-ids-build10.json` 保留作为build10快照，仍可能显示已处理的旧壳料ID。

## 2026-10-23 主代理后续材料用途映射

补核本机旧Classic构件后，两处制作材料 `ic2:lapatron_crystal` 已映射到目标 `ic2:lapotron_crystal`（控制核心/中级移动电源各1）。这是制作材料用途映射，目标原生容量1000万EU/四级，不等于旧100万EU/三级；不迁移电池NBT，也不改变新纪元成品参数。上方未映射说明为该核实前历史结论。新收据已更新，源码尚待第12轮后的下一次构建。
