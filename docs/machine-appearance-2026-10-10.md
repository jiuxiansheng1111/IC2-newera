# 2026-10-10 新纪元设备外形重做

## 设计与源码

玩家指出五种设备共享完整方盒外形，并要求终端有赛博科技气息、服务器为黑色且具有现实制造物的结构。当前七种新纪元设备已经各自重建几何与专用像素贴图，IC2/Create 原有外观不修改。

| 设备 | 当前外形 |
| --- | --- |
| 终端机 | 深蓝黑分体宽屏、青色状态图表、青紫边框灯带、键盘操作台、前部物料口、背部端口和无线结构 |
| 工业控制服务器 | 黑色机柜、四层硬盘托架、十二个硬盘面板、机架导轨、侧面通风网、后部双风扇、数据端口与横向布线槽 |
| 非金属处理机 | 米黄色筒体、顶部敞口料斗、前部输出抽屉、凸起 EU 接头 |
| 电子材料加工机 | 白色精密机架、封闭蓝色观察窗与加工头图案、前部物料托盘 |
| 机器网络适配器 | 青色紧凑模块、凸起光纤插座、立式天线和安装底座 |
| 工业网络交换机 | 深蓝低矮端口机箱、双排数据插口、四条顶部散热鳍、双天线与后部光纤口 |
| EU 电动机 | 灰色阶梯电机壳、铜色绕组、分体散热鳍、轴承环、底脚与背部 EU 接头 |

服务器托架和风扇为外观结构；图表与加工头为静态贴图，不代表新增运行功能、动态屏幕或硬盘槽。当前设备仍使用已实现的操作界面和槽位。

七种模型均在标准 `0..16` 单方块空间内，方块物品继承原版 `minecraft:block/block` 显示变换，不额外放大手持比例。终端与加工机保持北面正面及原朝向 state；电动机保持南面（+Z）出轴，轴承中心为 Create 动态轴留空。网络模块没有新增朝向 state。详细模型不使用整块遮挡，避免相邻设备被当作完整方块隐藏面。

## 资源与重建

- 共享生成源：`art/sources/machine_visual_style.py`；电子网络、终端、电机原生成器均在最后调用该源，重新生成不会回到旧方盒。
- 游戏模型：`src/main/resources/assets/ic2newera/models/block/` 与同名 item model。
- 专用贴图：`src/main/resources/assets/ic2newera/textures/block/`，32×32 外壳/接口等、64×32 屏幕/键盘/硬盘托架；全部由项目几何和像素绘图生成。
- 索引：`art/model-catalog.json`；对照预览：`art/preview/machine-design-comparison.png`；终端和服务器正后细节：`art/preview/terminal-server-detail.png`。
- 离线预览源：`art/sources/render-machine-preview.py`，读取实际游戏 JSON/PNG，显式 UV、最近邻采样、深度缓冲渲染。运行 `py -3 art/sources/render-machine-preview.py` 可重复生成；终端单独预览入口继续保留。

## 验证与交付

源码和资源已实现，实际 JSON/PNG 的七设备对照与终端/服务器四视角预览已查看；模型轮廓可区分，未发现裁切或明显表面重叠闪烁。`clean build -x test --offline` 成功；最终包 318 JSON、2 mcmeta 可解析，七种不同几何、32 张被实际引用的专用 PNG、模型范围/显式 UV/贴图路径/原朝向及电机轴孔静态核对通过。13 锭、12 粉及 75 条流程路线的包核对同样通过；没有 IC2/Create/原版外观覆盖。没有新增或运行测试；游戏光照、相邻放置、六向电机动态轴和手持显示仍待实机验收。

## 本机交付记录

- JAR：`build-isolated/releases/ic2newera-2026-10-10-distinct-machines.jar` 与安装源 `releases/ic2newera-0.1.0-dev.jar`，742881 字节。
- SHA-256：`3109461487D399149C0EA10AD55E32C434D5C95A3AD79ED6ACF0E647A3F841D5`；`scripts/runtime-mods.json` 已同步。
- 玩家先前确认保存关闭，安装前再次静态确认该实例未运行；原 PCL 脚本于 `2026-10-10T14:52:58.6712265+08:00` 替换新纪元 1 个 JAR，核对清单内 8 个模组哈希。实例：`C:/Users/罗斌/Desktop/.minecraft/versions/1.19.2-Forge_43.5.0`；回执 `build/deployment/pcl-installation.json`。
- 清理构建前保留上次安装回执、备份及发布 JAR到 `build-isolated/deployment-history/distinct-machines-20261010-145138/`；本次被替换 JAR由安装脚本备份在 `build/deployment/backups/`。
- 核对回执：`build-isolated/vanilla-alloy-package-audit.json`、`build-isolated/machine-model-package-audit.json`，均无错误；构建保留原有 27 条废弃 API 警告。
- 普通使用可用，交付前全部适用额度可读且大于 1%：短时剩余 74%、周剩余 28%。没有新增/运行测试，没有启动/关闭游戏、修改存档、读取 API Key或上传/发布；分享 ZIP 仍为此前版本。

实机待核对：终端正背接口、服务器黑色层次、相邻放置的遮挡、处理机四向与电机六向/动态轴、GUI/地面/第一及第三人称显示，以及 JEI/合金炉与工作台旁路拦截。离线资源预览不代表这些游戏结果已通过。
