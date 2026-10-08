# IC2 Classic 2.1.3.4 API 审计

只读检查本机安装包 `ic2-classic-1.19.2-2.1.3.4.jar` 的 class 签名与字节码。结论仅针对该版本，不读取世界存档，不运行游戏。

## 高级炉结论

IC2 Classic 确有两种 blast furnace 方块：

| Block 字段 | Block registry ID | Tile type / 实现类 | 配方表 |
|---|---|---|---|
| `IC2Blocks.ELECTRIC_BLAST_FURNACE` | `ic2:electric_blast_furnace` | `IC2Tiles.ELECTRIC_BLAST_FURNACE` / `ic2.core.block.machines.tiles.lv.ElectricBlastFurnaceTileEntity` | `RecipeRegistry.BLAST_FURNACE` |
| `IC2Blocks.INDUCTION_BLAST_FURNACE` | `ic2:blast_induction_furnace` | `IC2Tiles.BLAST_FURNACE` / `ic2.core.block.machines.tiles.mv.BlastInductionFurnaceTileEntity` | `RecipeRegistry.BLAST_FURNACE` |

ID 证据：`IC2Blocks` 静态初始化分别以 `electric_blast_furnace`、`blast_induction_furnace` 构造 `BaseMachineBlock`；`IC2ContainerBlock` 构造器将名称传给 Forge `GameData.checkPrefix(name, false)`。因此未带 namespace 的路径采用 IC2 mod namespace。tile 的 `getRecipeList()` 字节码均返回 `GlobalRecipes.blast_furnace`。这证明真实机器和配方入口均存在；不是只存在注册字段。

四种核心金属粉末到锭的专用配方应注册进同一 `RecipeRegistry.BLAST_FURNACE`。单输入可以用：

```java
RecipeRegistry.BLAST_FURNACE.get(isSimulating)
    .addSimpleRecipe(recipeId, ingotStack, dustItem);
```

`IMachineRecipeList` 还公开 `addRecipe(ResourceLocation, IRecipeOutput, IInput...)`、`getAllRecipes()`、`getAllEntries()`、`getRecipe(ResourceLocation)` 与 `removeRecipe(ResourceLocation)`。在服务端用与 IC2 tile `isSimulating()` 相同语义的侧选择取得 recipe list；不要混用客户端与服务端对象。

## 电炉与 smelting

IC2 电炉 `ElectricFurnaceTileEntity.getRecipeList()` 返回 IC2 的 `GlobalRecipes.furnace`。该表是 `CookingMachineRecipeList(RecipeType.SMELTING, 200)`，由 Minecraft smelting 配方构成；高级电炉则使用 `RecipeType.BLASTING` 的专用表。它们不等同于可直接枚举并移除的单独 IC2 dust smelting 表。

要防止粉末直烧捷径：

1. 不添加 dust→ingot 的 Minecraft `smelting` / IC2 `CRAFTING` 普通熔炼配方；这些会被原版熔炉、IC2 电炉等普通 smelting consumer 使用。
2. 新增四金属 dust→ingot 仅进入 `RecipeRegistry.BLAST_FURNACE`。
3. 若要禁止既有 smelting 输入，需按明确的 recipe ID 移除或用数据包覆盖/删除对应普通 smelting recipe。`IMachineRecipeList.removeRecipe` 只管 IC2 自己的 machine recipe list，不能改变 Minecraft `RecipeManager` 中的普通熔炼 recipe。审计未确认当前 JAR 中存在 nickel/chromium/titanium/tungsten 的粉末捷径，因此不要推断有既存四种配方需要删除。

IC2 的另外一套 advanced crafting API `RecipeRegistry.CRAFTING` 提供：`addSmeltingRecipe(ResourceLocation, ItemStack, Object, SmeltingType...)` 与 `removeSmeltingRecipe(ResourceLocation, SmeltingType...)`；`SmeltingType` 有 `FURNACE`、`SMOKER`、`BLASTFURNACE`、`CAMPFIRE`。它覆盖 IC2 自己经该 manager 注册的 recipe，不代表可删除所有 Minecraft 原版或其他 mod 注册的 RecipeManager 条目。

## RecipeList 枚举、删除和 reload

- `RecipeRegistry.MACERATOR` 对应 `GlobalRecipes.macerator`，`MaceratorTileEntity.getRecipeList()` 返回该表；custom folder token 是 `macerator`。
- Macerator GUI 复核：`MaceratorTileEntity.TEXTURE` 指向 `assets/ic2/textures/gui_sprites/blocks/machines/lv/gui_macerator.png`，该背景无 “ICS” 字样。`BasicMachineContainer.CHARGE_BOX=(56,36,14,14)`、`CHARGE_POS=(176,0)` 表示闪电形能量/充能指示；`PROGRESS_BOX=(79,34,24,16)`、`PROGRESS_POS=(176,14)` 是加工进度箭头区域。
- `BasicMachineContainer` 按 Macerator 的 `upgradeSlots=4` 在右侧创建四个 `UpgradeSlot`，索引从 3 起。`SlotType.UPGRADES` 的简中键 `misc.ic2.slots.upgrades` 显示“升级”。`UpgradeSlot.isItemValid` 检查 `IUpgradeItem` 和本机支持的升级类型；`BasicMachineTileEntity.BASIC_MACHINE_UPGRADES` 为全部类型。升级效果依物品类型，不是配方输入，槽位无需填满。
- `RecipeRegistry.BLAST_FURNACE` 对应 `GlobalRecipes.blast_furnace`；folder token 是 `blast_furnace`。
- `RecipeRegistry.MIXING_FURNACE` 对应 `GlobalRecipes.mixingFurnace`（`MultiMachineRecipeList`，构造参数 `maxInputs=2, exact=true`）；folder token 是 `mixing_furnace`。它有真实可放置机器：`IC2Blocks.ALLOY_SMELTER` 的 ID 为 `ic2:alloy_smelter`，tile type `IC2Tiles.ALLOY_SMELTER`，实现类 `ic2.core.block.machines.tiles.lv.AlloySmelterTileEntity`；其 `getRecipeList()` 返回该 `mixingFurnace` 表。因此为多原料合金加热应使用 `RecipeRegistry.MIXING_FURNACE`，不能与两种 blast furnace 配方表混淆。
- `GlobalRecipes` 也公开 furnace、smoker、compressor 等列表；`setRecipes(boolean)` 将它们写入 `RecipeRegistry` 的 sided slots。

三种列表都实现 `IMachineRecipeList`，可枚举 recipe IDs/entries 并按 ResourceLocation 删除。普通 `MachineRecipeList.reload()` 清掉 recipe map 和 item-index map、将 mapped 标志复位，然后逐一调用 listeners；`MultiMachineRecipeList` 也提供 `reload()` 和 entries/ID 枚举/删除。`GlobalRecipes.reloadRecipes()` 会逐表 reload，包括 macerator、blast furnace 和 mixing furnace。`registerListener(Consumer<IMachineRecipeList>)` 只追加 listener，不会立即执行。

**RecipeGuard 建议：**在 IC2 recipe tables 可用后为三张表分别注册 guard listener；对可识别的旧 recipe ID 执行 `removeRecipe(id)`，然后由下次 lazy map 建立从剩余 entries 重建查找索引。由于 IC2 全局 reload 会清空并由既有 listeners 重新填充 recipes，guard 必须在 reload 后重跑（listener 后注册可使其在已有添加 listener 后执行）。只依赖一次性的启动时删除不够。新增受保护 recipe 使用稳定、项目自有 namespace 的 ID。对于普通 `RecipeManager` smelting 路径，另用 recipe 数据覆盖策略；不要把 RecipeGuard 的 IC2 list 删除当成全局拦截。

## EU Sink 与注册/卸载

准确公开签名：

```java
interface IEnergySink extends IEnergyAcceptor {
    int getSinkTier();
    int getRequestedEnergy();
    int acceptEnergy(Direction direction, int amount, int voltage);
}

interface IEnergyNet {
    void addTile(IEnergyTile tile);
    void removeTile(IEnergyTile tile);
    void updateTile(IEnergyTile tile);
    // 另有 getTile/getSubTile/getTiles、getPowerFromTier/getTierFromPower 等查询。
}
```

该 JAR 的 `ic2.api.events` 和 `ic2.api.energy` 中没有 `EnergyTileLoadEvent` / `EnergyTileUnloadEvent`。IC2 自身以 tile 生命周期完成登记：`BaseElectricTileEntity.onLoaded()` 在 simulation/server 侧调用 `EnergyNet.INSTANCE.addTile(this)`；`onUnloaded(boolean)` 在已登记时调用 `removeTile(this)`；朝向改变调用 `updateTile(this)`。新机器应继承 `BaseElectricTileEntity`（或其机器子类），实现 sink 方法，让这套生命周期接管电网登记；不要依赖不存在的 load/unload API event。

## 原生机器 inventory/slot API

`IInventoryMachine` 是 IC2 内部公开接口，提供：

```java
IHasInventory getInputInventory();
IHasInventory getOutputInventory();
```

`IHasInventory` 提供 `getSlotCount()`、`getStackInSlot(int)`、`setStackInSlot(int, ItemStack)`、`getMaxStackSize(int)`、`canInsert(int, ItemStack)`、`canExtract(int, ItemStack)`。IC2 BasicMachine tile 的输入/输出 inventory 是独立视图；调用方应分别遍历视图内的 0-based slot index，不能把它们当成一个连续 inventory，也不应硬编码普通 tile 的内部 `inventory` 物理下标。外部 Forge 自动化可使用 `BaseMachineTileEntity.getConnectedInventory(Direction)` 返回的 `IItemHandler`，或 Forge item handler capability。若 processor 需要指定 input/output slot，优先实现 `IInventoryMachine` 并返回合适 `IHasInventory`，再明确每个槽的 insert/extract 策略。

## 审计边界与证据位置

使用本地 JAR 的 `javap -p` / `javap -p -c` 对以下类型核对：`IC2Blocks`、`IC2Tiles`、`IC2ContainerBlock`、两种 blast tile、`AlloySmelterTileEntity`、`ElectricFurnaceTileEntity`、`MaceratorTileEntity`、`RecipeRegistry`、`GlobalRecipes`、`MachineRecipeList`、`MultiMachineRecipeList`、`CookingMachineRecipeList`、`IMachineRecipeList`、`IAdvancedCraftingManager`、`IEnergySink`、`IEnergyNet`、`BaseElectricTileEntity`、`IInventoryMachine`、`IHasInventory`。`IC2Items` 字段中没有 quartz dust；本模组 `ElectronicMaterials.QUARTZ_DUST` 是新注册物品。没有复制第三方方法体。未核实四矿配方在 datapack reload 中的精确磁盘路径约定；上述 folder 字段值来自 IC2 `GlobalRecipes` 构造字节码。
