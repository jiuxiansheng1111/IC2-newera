# 模型与贴图目录

`art/` 保留当前活动资源范围：矿物材料、合金材料与装备、电子元件、两种处理机和工业网络设备。运行时模型和贴图位于 `src/main/resources/assets/ic2newera/`，本目录维护原创资源生成脚本、预览工具和活动资源索引。

## 目录约定

- `sources/`：活动矿物、合金、电子元件和工业设备资源的生成脚本，以及单独维护的 AI 知识生成脚本。
- `preview/`：模型目录构建和浏览器捕获工具；渲染结果只应对应当前 `model-catalog.json`。
- `references/`：保留参考原件，除非确认已废弃，不在常规清理中改动。
- `model-catalog.json`：活动游戏模型索引，供预览生成器读取。

## 电子与工业网络资源

运行 `py -3 art/sources/generate-electronic-network-assets.py` 可重建 9 种电子材料图标、工业 AI 核心图标，以及两种处理机和四种网络方块的模型、blockstates 与 16×16 原创贴图。脚本同时把新模型登记到 `model-catalog.json`。非金属处理机用宽大的粉料入口和底部托盘，电子材料加工机用独立芯片观察窗；适配器、光纤、交换机和服务器分别用无线信号、透明线缆、多端口灯和机架托盘表达用途。

所有方块几何限制在标准 16×16×16 单方块空间内；处理机朝向按 `facing` state 旋转，网络方块没有额外朝向 state。电子物品和工业 AI 核心使用 `minecraft:item/generated` 图标模型；方块物品引用自己的 block model。脚本仅从项目内手绘色板和像素几何生成，不读取或改写其他模组资产。

退役预览、试制贴图和旧归档已移至被 `.gitignore` 忽略的 `build-isolated/retired-art/`，不会进入活动模型索引。AI 知识脚本保留在 `sources/`。

## 终端机

离线双视角预览为 preview/terminal-model-preview.png；sources/render-terminal-preview.py 从实际方块JSON与项目贴图重复生成。它展示模型设计，不代表游戏手持比例已实机验收。

运行 `py -3 art/sources/generate-terminal-assets.py` 可重建 `ic2newera:terminal` 的方块/物品模型、四向朝向方块状态、掉落表与六张 16×16 贴图，并更新 `model-catalog.json`。贴图由脚本使用 Pillow 像素绘图原创生成，没有引入外部图像或第三方资产。深色箱式金属外壳以青色屏幕/物流标记和紫色数据端口区分接口：北面显示屏及物料槽，南面光纤插口与内置天线，侧面、顶面和底面各有独立物流/服务图案；机身与全部附加几何均限制在一个标准方块内。物品模型显式沿用常见方块物品的 GUI、地面和手持缩放比例。
