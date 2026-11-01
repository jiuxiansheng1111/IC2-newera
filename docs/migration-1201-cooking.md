# 1.20.1 烹饪迁移与双锅实施记录

第17轮已加入森罗炒锅独立原生交互、翻炒持久计数与实际品质成品暂存，构建通过，未安装/实机。此前汤锅限定互通保持；当前依据见[交付记录](migration-1201-build17.md)。以下是此前阶段的详细证据。

## 第15轮当前结果：限定双向互通已构建，实机未做

已完成FD→森罗原生桥复用，以及森罗静态水底/默认碗/1～5食材/1～9份结果→FD锅的虚拟反向配方。FD中额外消耗真实水桶，按成品份数消耗碗；仅回收原锅本批生成的真实余桶，机器人选择锅侧可达可见站位。成品从原锅实际减少并进入暂存/货物后才计订单。反向不入全局RecipeManager，避免森罗再次反向转换；JEI可展示其原料及容器。

第15轮33秒构建通过、804JSON资源核对，详情见[交付记录](migration-1201-build14.md)。缺工艺设备/材料仍阻塞新世界验收。下面的“未实现反向”是较早阶段记录；炒锅、茶壶、蒸笼依然未接入，动态/特殊汤底菜谱也不支持反向。暂存与锅/实体分开保存，不能宣称硬崩溃原子事务；异常转移冻结供人工核对，不补造物品。


## 2026-10-23 当前状态

隔离副本已实现独立 `KaleidoStockpotCookingAdapter`，连同FD适配器在第10轮完整构建中通过（29秒、退出码0、未运行测试），包与源码检查点见 [第10轮交付记录](migration-1201-build10.md)。没有安装、启动游戏或实机验收。旧1.19.2源码不包含这次森罗迁移。

汤锅流程使用真实汤底、九格食材、锅盖、加热计时和餐具。按同一组输入重新调用原生配方匹配，遵循普通、flex、FD桥接的优先顺序；每次投入/开盖/取出重检权限。实际FakePlayer手持和库存返还物转入持久暂存状态；64格额外暂存预留足够空间接住一次完整原生库存返回，满货物槽保留余量，批次完全实收后才计数。取消仅返还未入锅的实际暂存物；锅内材料留在原锅。无法确认的操作中断会锁定，不能自动生成预期输出。

已审阅1.6.0的FD→森罗汤锅桥接与真实取出契约，复用原生桥接而不重复注册；只有默认餐具可表达的FD配方会采用。森罗→FD尚未实现，炒锅、茶壶、蒸笼也未接入机器人。烹饪动作共用COOKING状态，视觉效果仍需游戏验收。

第11轮另补已保存任务的后端模组可用性保护：只有另一种锅具模组存在时，不继续调用缺失后端；取消也先验证森罗是否存在。27秒完整构建成功，包SHA与源码记录见上述交付记录。

## 反向互通的下一步实施依据（未实现）

实际KCC资源和FD接口表明，不能把所有汤锅配方直接换类型。KCC静态汤锅77条资源配方中53条食材数不超过FD六槽，但这些仍需汤底、餐具和份数检查；另28条flex汤锅配方包含动态结果/品质，暂不转换。KCC原生按结果栈数量设置待取份数，上限9，每次取出一份并消耗匹配餐具。FD有六个食材槽、固定容器和结果栈，没有独立汤底字段。

主审从锁定JAR再次读到三个具体候选：`kaleidoscope_cookery:stockpot/rice_1` 为1份 `forge:grain/rice`、水汤底、碗、100tick，产KCC米饭1份；`stockpot/rice_2` 为2份同标签米、200tick、结果2份；`stockpot/beef_meatball_soup` 为2个KCC生肉丸和3份 `forge:crops/lettuce`、水汤底、碗、300tick、结果1份。这些产物ID与FD米饭/汤品并不相同，不能通过名称相似直接当作FD原配方。

最小后续方案应从共享订单选择具体配方和后端，在持久状态中处理真实汤底扣除及桶返还，并只桥接可完整表达的静态配方。不能把水汤底省略，也不能注入全局配方后让玩家绕过汤底成本；超过六槽、多替代餐具、特殊汤底/返还语义、动态品质或超过原锅9份上限均需明确拒绝。当前没有新增反向配方，没有宣称两锅互通已经全部完成。

## 2026-10-22 历史初始迁移记录

以下保留初始审阅过程；其中“未实现/未编译”描述该时点，不代表上面的最新状态。工作副本为 `build-isolated/migration-1201/newera/`；初次仅迁移四个烹饪源码的1.20.1 API，未构建或游戏验收。

## Farmer’s Delight 原生锅

依据本地 `build-isolated/migration-1201/dependencies/farmersdelight-1201-1.3.4.jar`，`CookingPotRecipe` 实现 `Recipe<RecipeWrapper>`，暴露配方 ingredients、带 `RegistryAccess` 的 `getResultItem`、`getOutputContainer`、烹饪时间和匹配方法。`CookingPotBlockEntity` 的 JAR API 提供 `isHeated()`、`getInventory()`、`getCapability()` 与烹饪 tick；库存为 9 槽，其中六个输入位、容器位 7、输出位 8（餐食展示位 6）。成品和额外容器都是物品栈；该配方接口没有液体输入字段。加热使用 FD 自己认可的锅底热源。

现有适配器仍只找空锅、模拟完整物料分配、要求可回收的余物与输出空间，记录投入快照/待投 escrow，调用锅的原生计时，完成后模拟提取再入机器人货物槽。指定菜品只比较配方真实输出物品，完成数量只在成品成功入货物槽后累计；取消仍先核对锅和 escrow，再返还尚未投入的材料。对 MC 1.20.1 的源码适配将所有 FD 配方结果读取改为 `getResultItem(level.registryAccess())`，把实体世界读取改为 `level()`，订单物品索引改用 `BuiltInRegistries.ITEM`。

## Kaleidoscope Cookery（森罗物语：厨房）

依据本地官方 Modrinth 文件 `build-isolated/migration-1201/dependencies/kaleidoscope-cookery-1201-1.6.0.jar` 静态查看的真实类型如下。这个 JAR 没有声明 FD 必需依赖。后续主代理进一步检查发现 `compat/farmersdelight/CookingPotCompat`，已内置将 FD CookingPotRecipe 转成森罗 StockpotRecipe 的兼容，原先声称没有桥接类的判断已纠正；它不是机器人控制器，也不能据此宣称双向、所有锅通用。

| 设备/配方类型 | JAR 中核实的表达 | 执行差异 |
|---|---|---|
| 森罗炒锅 | `PotRecipe`、`FlexPotRecipe`：食材列表、carrier 餐具 Ingredient、成品、时间、翻炒次数 | `PotBlockEntity` 保存输入、carrier、结果与阶段；实体 API 有添加食材、上油、翻炒阶段、取出成品等独立交互。单纯提交 FD 式库存槽不能表达操作阶段。 |
| 森罗汤锅 | `StockpotRecipe`、`FlexStockpotRecipe`：食材、`soupBase` 注册 ID、carrier、成品、时间；容器另含汤底 ID | `StockpotBlockEntity` 需先加/移除汤底，再处理食材；还维护锅盖、输出次数及实际汤底。汤底是独立注册对象，不可用 FD 的普通输入位代替。 |
| 森罗茶壶 | `TeapotRecipe`：茶液体 ID、食材、食材数量、时间、成品；输入容器另带 `teaFluid` | 需验证并设置实际茶液体状态，单用 ItemStack ingredients 不能表示液体量/状态。 |
| 蒸笼 | `SteamerRecipe`：单个 Ingredient、结果与 `cookTick` | 使用独立蒸笼设备与单输入配方类型，不是锅配方。 |
| 其他厨具 | 另有砧板、石磨、竹屉等各自的 recipe/entity 类型 | 各自有独立处理器与输出语义，不属于烹饪锅桥接。 |

因此不能将森罗配方直接塞进现有 FD adapter：虽然配方都实现 Minecraft `Recipe` 基础接口，但其 RecipeType、容器泛型和执行状态完全不同。要支持两种锅，应增加独立的 Kaleido adapter，并按炒锅/汤锅/茶壶分别检查配方、燃料/加热、餐具或汤底/茶液体、操作阶段、真实输出与安全取消；蒸笼需另一个独立设备流程。两边仍可共用订单目标、批次实际入库计数和机器人权限/路径框架，不应共用伪造的统一锅库存模型。

Kaleido 的这些配方类由各自 serializer / `RecipeType` 注册到 Minecraft 配方管理器；配方数据随正常服务端配方重载变化。实体在方块实体 NBT 保存自己的锅内状态。因此未来 adapter 必须每次使用当前 `RecipeManager` 重新解析已绑定 recipe ID，并对方块实体快照做核验，不能缓存上次加载的配方或把 NBT 阶段误当成外部配方仍有效。此次没有实现 Kaleido adapter，也没有声称现有机器人支持森罗锅。

## 本次源码变更边界

- `build-isolated/migration-1201/newera/src/main/java/com/ic2newera/robot/cooking/FarmerDelightCookingAdapter.java`
- `build-isolated/migration-1201/newera/src/main/java/com/ic2newera/robot/cooking/RobotCookingGoal.java`
- `build-isolated/migration-1201/newera/src/main/java/com/ic2newera/robot/cooking/RobotCookingOrder.java`
- `build-isolated/migration-1201/newera/src/main/java/com/ic2newera/robot/cooking/RobotCookingState.java`（该记录类的 MC 1.20.1 NBT/ItemStack API 已逐项检查，无需变更）

迁移目录中的 FD/CookingPot API 依据是上述下载 JAR 的 `javap` 静态签名与常量；`RobotCookingOrder` 对应 MC 1.20.1 的 `BuiltInRegistries.ITEM` 注册访问，配方结果读取使用 `RegistryAccess` 参数。工作未编译，待主代理统一处理整个迁移工程的映射/API 差异并编译。


## 主代理补充核验：已有 FD → 森罗汤锅桥接

锁定1.6.0 JAR内的 `CookingPotCompat` 公开/包内API及字节码已读取，证据在 `build-isolated/migration-1201/kaleido-fd-compat-api.txt`。其 `afterStockpotRecipeMatch` 在森罗原生匹配为空时查询真实FD料理锅配方，并将其食材、成品、时间、餐具转换为汤锅结果；JEI同样列出转换配方。后续复用此原生路径，避免重复注册同一方向配方。尚需核实事件订阅启用条件、汤底、锅盖、成品份数与取出逻辑；不把类存在视为实机通过。反向森罗→FD只考虑FD能表达的食材/加热/餐具配方，不能跳过上油、汤底、翻炒等操作语义。
