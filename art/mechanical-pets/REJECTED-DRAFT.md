# 已否决的方盒草稿

本目录原 `geometry-source.json`、`geometry-index.json`、`mechanical-cat-poses.png`、`mechanical-dog-poses.png` 属于被用户否决的早期外观，保留仅作修改记录。不能再将它们当作活动模型、生成依据或最终预览。

随后误做的 `organic/` 圆面动物也被用户明确否决。活动源为 `voxel/cat/cat.bbmodel` 与 `voxel/dog/dog.bbmodel`。`scripts/generate-mechanical-pets.py` 是新 vanilla cuboid 导出入口，不能重新生成旧方盒 Java、金属贴图或圆球动物。

新的物品图标直接由对应真实 Blockbench 模型渲染，来源模型指纹和渲染参数记录在 `voxel/item-render-receipt.json`。没有图标或源已变化时，生成入口拒绝使用过期图标。
