# 增强模组与职责边界

本文件包含当前增强模组的运行知识；配方和受控工艺仍以当前服务端 ProcessGraph 及 JEI 为准。具体版本、构建和安装见交付回执。尚未实机验收的功能不应声称已在目标服务器运行通过。

## 是，史蒂夫模型（YSM）与玩家模型

Windows 客户端 r51 新增 Yes Steve Model（是，史蒂夫模型）2.6.5 Forge 版，适用于 Minecraft 1.20.1。它用于切换玩家模型和动画，不是新纪元机器人或机械宠物的模型系统。Forge 默认用 Alt+Y 打开模型选择界面；如无响应，先检查控制设置中的键位冲突。模型的具体外观和授权以实际安装的模型为准。

根据模组作者说明，完整多人功能需要客户端和服务器都安装 YSM；服务器模型由服主管理和同步，客户端单独安装不等于多人完整同步已启用。玩家可在单人游戏加载模型后于未安装服务端的服务器使用，但具体能力有限。加载、渲染和多人同步均须在实际环境确认，不要声称已经验收。更多说明见 https://ysm.cfpa.team/wiki/install/ 和 https://ysm.cfpa.team/wiki/faq/ 。

## AE2：ME 仓储、终端与自动合成

Applied Energistics 2 15.4.11 与必需依赖 GuideME 20.1.15 负责 ME 网络仓储、终端检索和样板自动合成。玩家使用 AE2 原生 ME Terminal、Pattern Provider（样板供应器）和 Storage Bus（存储总线）；新纪元不另造 ME 库存、终端、样板队列或合成提交系统。ME Terminal 只能看到接入当前 ME 网络且有权限访问的存储，不等于世界全部物品清单。

旧新纪元 Terminal 注册仍保留用于取回旧存档库存，但已从创造物品栏和 JEI 隐藏，不承担新仓储、合成、远程托管或生产中转；不要删除其方块/物品/方块实体/菜单和恢复路径。

AE2 和 IC2 Refactored 2.10.33-ex120 的能量桥接在 IC2R 一侧实现：`ae2:energy_acceptor` 注册为 IC2R EnergyNet sink，代码常量 `EU_TO_AE_RATIO=2.0` 对应实现 1 EU 注入 2 AE。该结论来自发行 JAR/API 静态检查，未实机测量；这是 IC2R 互操作，不可推及 IC2 Classic。AE2 Energy Acceptor 对 Forge Energy 的官方换算为 2 FE = 1 AE。Ad Astra 设备通过 Botarium/Forge Energy 接入 AE2 的路径仅为静态兼容判断，发电机、电缆方向和实传输均未实测。

机器人仍按 ProcessGraph 批准路线使用 IC2/Create/原版机器加工真实物品，并使用进料箱、周转箱和成品箱；这不是 AE2 样板合成。工业控制服务器的生产规划报告不等于库存盘点或自动取料。

本阶段不开发手机到 AE2 的对接。玩家在 AE2 原生终端操作 ME 网络；新纪元手机保持现有机器人联系、状态、授权控制等功能，内置 AI 保留互联网与知识问答，不增加手机合成提交，也不让 AI 代玩家提交 AE2 合成请求。

## 连锁采集和容器排序

FTB Ultimine 2001.1.8 与 FTB Library 2001.2.13、Architectury API 9.2.14 提供客户端/服务器连锁采集。目标操作为按住 `~` 触发，服务器最大连锁数量设为 64；服主配置为权威值，避免点按或过大的范围误采建筑。实际键位可按客户端控制设置调整。

Simple Inventory Sorter 23.0.11 提供容器真实中键排序，不等同于 Mouse Tweaks 的拖拽物品功能。包内为 FTB Ultimine、FTB Library、Inventory Sorter 补充 zh_cn 翻译资源。客户端/服务端联机同步和操作仍待验收。

## Create 推进与客户端图形前置

Create: Propulsion 0.4.1、Valkyrien Skies 2.4.11 和 Kotlin for Forge 4.11.0 是 r48 选定的 Create 飞行/推进组合；不另加 Clockwork。Propulsion 项目将该版本描述为 very early alpha，游戏行为尚未验收。Valkyrien Skies 包含 Linux x86_64 与 AArch64 原生库，运行支持要求 glibc；Alpine/musl 不在其官方兼容说明内。

六个图形/贴图模组仅供客户端：Embeddium 0.3.31、Oculus 1.8.0、Iris & Oculus Flywheel Compat 2.0.3、Entity Model Features 3.3.12、Entity Texture Features 7.2.6、Fusion 1.3.15b。EMF 需要 ETF。Linux 服务端不要安装这六项。r48 包没有附带实际光影材质包；客户端启用光影需自行安装兼容资源。渲染兼容尚未实机验收。
