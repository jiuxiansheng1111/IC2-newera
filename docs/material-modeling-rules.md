# 材料模型约定

用户于 2026-10-10 要求保存并应用 [中文 Minecraft Wiki 模型教程](https://zh.minecraft.wiki/w/Tutorial:%E5%88%B6%E4%BD%9C%E8%B5%84%E6%BA%90%E5%8C%85/%E6%A8%A1%E5%9E%8B?variant=zh-cn)。已通过浏览器阅读。该页提示部分内容需要更新，因此项目实现以本地 Minecraft 1.19.2 实际资源为准。

教程的模型要点：命名空间决定资源路径；`parent` 继承父模型，子项覆盖同名属性且不得循环；平面物品使用 `generated` 与 `layer0`；`display` 分别控制手持、物品栏、掉落与展示框，变换顺序为缩放、旋转、平移；方块由 `elements` 和各面纹理组成；UV 使用 0～16 的比例坐标，提升纹理分辨率不会扩大模型；方块状态用 variants/multipart 选择或组合模型。不要把教程中的新版字段直接用于 1.19.2。

## 用户明确采用的锭标准

- 先读取目标版本原版铁锭资源，不能凭观感估计比例。
- 全部新纪元锭，包括既有铝青铜、低碳钢、高碳钢及新增矿物/合金锭，采用相同大小、轮廓、像素位置和明暗分区，仅调整材质色板。
- 模型继承 `minecraft:item/iron_ingot`，只覆盖 `textures.layer0`，不声明额外 `elements` 或 `display`。完整继承原版生成模型与第一/第三人称、掉落、展示框变换。
- 贴图固定 16×16 RGBA，原版非透明范围为 `[0,2,16,14)`，每个透明像素的位置均相同；禁止缩放或描边扩大轮廓。
- 原版基准与来源指纹保存在 [基准 JSON](../art/reference/vanilla-iron-ingot-1.19.2.json)。生成器 [material_pixel_style.py](../art/sources/material_pixel_style.py) 读取其像素矩阵，并生成原版/低碳/高碳/旧铝青铜并排预览。
- 12 种新纪元粉末沿用玩家指定的尖顶粉堆外形，按材质色板、杂质颗粒区分。
- 用户随后明确：IC2 和 Create 原有锭/粉外观不用改；本次仅修改新纪元自有资产。原版铁锭只作为校准基准。

本地原版资源位置为 Gradle 缓存的 `minecraft_repo/versions/1.19.2/client.jar`，读取 `assets/minecraft/models/item/iron_ingot.json`、`assets/minecraft/models/item/generated.json` 和 `assets/minecraft/textures/item/iron_ingot.png`。原版资源归 Mojang/Microsoft，按用户明确要求用于校准与材质改色，不将派生轮廓冒称完全原创。

## 合金工艺约定

所有当前金属合金的配料步骤使用 IC2 合金炉。高低碳钢分别用普通铁粉和 IC2 煤粉配料；原生青铜粉工作台配方、Create 黄铜搅拌旁路及其他未批准合金产出由 RecipeGuard 移除。成品粉熔炼、锭回收打粉、块/粒拆分属于已有合金形态转换，只有 ProcessGraph 批准的配方可用。装备制作使用现有工作台。
