# 模型与贴图目录

2026-10-17 新增普通、中级、高级、核能四档充电宝原创图标：单芯、双芯、晶体散热片、核屏蔽壳四种轮廓。生成入口 `scripts/generate-robot-resources.py`，资源路径与用途见 [充电宝资源索引](../docs/robot-powerbanks-2026-10-17.md)。沿用原版物品显示比例，游戏内观感待玩家确认。

2026-10-13 人形机器人外观研究稿：主线亲自维护`art/sources/humanoid_robot_sculpt.py`，八人网格、原创贴图、可编辑工程与真实模型三视图位于`art/robot-rework/`；[视觉检查与重建入口](robot-rework/visual-review.md)。目前属于外观阶段，未接入实体、动作、构建或安装；旧方块稿已被用户否定，不能作为完成资源。活动游戏资源与机器人研究稿分开记录。

2026-10-14最新为V28，人工验收入口[总览、三视图与V27对照](robot-rework/morning-review.md)，继续点[夜间交接](robot-rework/night-handoff.md)。只重导已保存的可编辑工程可用`py -3 art/sources/humanoid_robot_sculpt.py --editor-only`；资源静态检查入口为`py -3 art/sources/inspect_robot_sculpt.py`。工程数据名含修订版本，Blockbench实际标签仍按文件名显示。目标接近99尚待人工认可。

`art/` 保留当前活动资源范围：矿物材料、合金材料与装备、电子元件、两种处理机和工业网络设备。运行时模型和贴图位于 `src/main/resources/assets/ic2newera/`，本目录维护原创资源生成脚本、预览工具和活动资源索引。

## 目录约定

2026-10-10 材料样式修正：运行 `python art/sources/material_pixel_style.py` 重建全部 13 种新纪元锭（包含旧锭）、12 种粉末、模型、索引和四份预览。锭直接继承原版铁锭模型；读取 `reference/vanilla-iron-ingot-1.19.2.json` 中从本地 1.19.2 客户端提取的 16×16 像素轮廓/明暗矩阵，仅重映射材质色板，大小、透明边缘和显示比例保持原版。原版基准来源与指纹记录在 JSON，不冒称完全原创。粉末为项目绘制的尖顶颗粒堆，含杂质粉附深色夹杂，高碳钢深蓝灰、低碳钢浅银灰。矿物、合金、石英生成器调用同一绘图函数；钢坯已移除。IC2/Create 原有外观不修改。详细约定见 `docs/material-modeling-rules.md`，校准预览为 `preview/vanilla-ingot-proportion-comparison.png`。

预览：`preview/current-ingot-styles.png`、`preview/current-dust-styles.png`、`preview/carbon-steel-four-items.png`。预览对应实际 PNG，游戏光照、持有比例与最终加载效果尚待实机验收。

- `sources/`：活动矿物、合金、电子元件和工业设备资源的生成脚本，以及单独维护的 AI 知识生成脚本。
- `preview/`：模型目录构建和浏览器捕获工具；渲染结果只应对应当前 `model-catalog.json`。
- `references/`：保留参考原件，除非确认已废弃，不在常规清理中改动。
- `model-catalog.json`：活动游戏模型索引，供预览生成器读取。

## 七种设备模型

运行 `py -3 art/sources/machine_visual_style.py` 可重建终端、服务器、非金属处理机、电子材料加工机、适配器、交换机与 EU 电动机七种独立模型、专用贴图和索引。几何与贴图由项目脚本原创绘制；每种设备用机身轮廓与接口结构表达用途。详见 `docs/machine-appearance-2026-10-10.md`。

终端为深蓝黑宽屏赛博操作台，有青紫灯带、键盘台、前部物料口和背部光纤；服务器为黑色机柜，有四层硬盘托架、机架导轨、散热网、后部风扇与布线槽。粉料设备为米黄色敞口料斗，电子设备为白色封闭观察舱；适配器为紧凑天线模块、交换机为低矮多端口机箱、电动机为铜绕组与独立轴承结构。全部几何处于 `0..16` 范围；外壳/接口贴图为 32×32，屏幕/键盘/硬盘面板为 64×32。方块物品直接继承原版方块显示比例。

运行 `py -3 art/sources/render-machine-preview.py` 从实际 JSON/PNG 重建 `preview/machine-design-comparison.png` 与 `preview/terminal-server-detail.png`；使用显式 UV、最近邻贴图采样和深度缓冲。`sources/render-terminal-preview.py` 使用同一渲染器重建终端单独双视角 `preview/terminal-model-preview.png`。离线预览只核对资源外形，游戏光照、持有比例与动态轴尚待实机验收。

## 原设备生成入口

`generate-electronic-network-assets.py` 继续生成九种电子图标、工业 AI 核心、工业光纤、两种处理机及网络方块的 blockstates/模型和索引；终端由 `generate-terminal-assets.py` 维护四向 blockstates 与掉落表，电机由 `generate-eu-motor-assets.py` 维护六向 blockstates 与掉落表。这三个生成器最后均调用共享设备外形源，保证重新生成采用当前模型。

终端/处理机以北面为正面；网络模块不增加朝向 state。EU 电动机南面（+Z）为 Create 动态出轴接口，中心 6..10 区域留作轴孔，北面是 EU 电缆端口；模型不包含静态轴。`facing=south` 无旋转，其余方向通过原 blockstates 旋转。工业光纤保留透明线缆模型。

退役预览、试制贴图和旧归档位于被 `.gitignore` 忽略的 `build-isolated/retired-art/`，不会进入活动模型索引。AI 知识生成脚本保留在 `sources/`。

## Wi-Fi 增幅器

运行 `py -3 art/sources/generate-wifi-amplifier-assets.py` 可重建 `ic2newera:wifi_amplifier` 的 16×16 物品图标、`minecraft:item/generated` 模型，并更新 `model-catalog.json`。图标由 Pillow 用原创像素几何绘制：深绿色电路板、铜色天线、青色信号弧与金色插槽触点；不复用电子材料图标或外部资产。它表示可安装到交换机升级槽的模块，不是独立网络方块。

## 硅材料外形（2026-10-10）

普通硅为深灰碎晶配银灰金属断面；高纯硅为银灰完整晶体，以轮廓和较亮晶面区分阶段。两张16×16透明贴图使用中性灰色板。运行 `py -3 art/sources/generate-electronic-network-assets.py --silicon-only` 可只重建这两种贴图及索引；完整生成入口使用相同绘图函数。运行 `py -3 art/sources/render-silicon-preview.py` 从实际PNG重建 `preview/silicon-material-styles.png`。

## 2026-10-18 充电宝和终端更新

四档充电宝由 `scripts/generate-robot-resources.py` 生成；核能使用 32×32 深色壳和绿色核标识两层 PNG，模型 `forge:item_layers` 的第二层自发光。实际贴图预览由 `art/sources/render-powerbank-preview.py` 生成到 `art/preview/powerbanks-20261018.png`。

终端现改为加固工业箱体，嵌入小屏、独立输入槽与输出抽屉，背面有 EU 和光纤接口；替代上文历史宽屏电脑外观。生成源 `art/sources/machine_visual_style.py` 的 terminal 分支和 `generate-terminal-assets.py`，预览 `art/preview/terminal-model-preview.png`；其他设备外形未改。

## 2026-10-18 手机立体外观

当前八个机器人最终可编辑工程统一保存在 `robot-models/current/`，批准哈希不变；`sources/export_robot_runtime_models.py`直接读取此目录，不依赖旧工作区。历史稿删除请求被自动审批拦截，旧工作区仍在本机但不纳入公开包。

手机注册ID保持 `industrial_phone`，游戏名称统一“手机”。`py -3 art/sources/generate-phone-assets.py` 只重建这件物品的独立 cuboid 模型、128×128 专属图集和 `preview/phone-model-preview.png`；原机器人资源生成入口最后调用同一专属生成器，避免覆盖回旧平面图标。

原创外观采用薄金属机身、黑边冷色屏、居中前摄岛、哑光机背、三枚独立凸起镜头和实体侧键，参考现代 iPhone 的外形语言，没有使用品牌标识或下载第三方贴图。正背面板与材质/镜头色块在图集中隔离，所有面显式UV。模型处于0..16范围，独立elements模型不继承 `builtin/generated` 路径，避免Minecraft 1.19.2的ItemModelGenerator忽略立体几何；display保留目标版本原版物品比例/平移，GUI转向正面，第一人称朝向调整为屏幕面对玩家。

预览直接读取当前Minecraft JSON/PNG，复用项目的三角形/UV/深度渲染器；包含正、背、侧视。离线预览不能代替游戏手持显示验收。外观参考：[Apple iPhone 18 Pro](https://www.apple.com/iphone-18-pro/)。

同日最终A60E…构建已在专用验收世界实际加载：手机名称正确，独立立体模型生效，第一人称正面屏幕朝向玩家。实机截图与远程语音/指定采集验收见 [手机交付](../docs/phone-remote-and-model-2026-10-18.md)，视觉喜好由用户确认。

