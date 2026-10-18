# 八名角色 R16 模型的游戏渲染接入

> 2026-10-17 后续功能已更新：[机器人电池、寿命、采集、手机、动画与可选语音](robot-industrial-control-2026-10-17.md)。本页后文保留前一阶段交付记录；旧便宜配方、无限死亡返还、只有头转动或没有语音源码的说明已由新阶段覆盖。

后续更新（2026-10-17）：运行时行走、待命、工作、攻击、有限头转向，以及原版手持物品/防具层已补充源码，详见 [R16 动作与装备记录](robot-r16-animation-2026-10-17.md)。下文“四肢静态、没有装备”描述保留首次接入时的历史状态；本次动画的构建/安装由统一主任务记录，离线预览不代表实机验收。

2026-10-17。此记录只覆盖用户认可的八份实际外观模型、离线转换和客户端渲染。共同实体、角色娃娃、控制界面、网络和配方由“添加8种机器人图纸与棉花娃娃”任务维护；协调入口为 `docs/robot-integration-coordination-2026-10-17.md`。本阶段不另建第二套机器人实体或角色生成蛋。

## 原件与运行时资源

爱音使用 `art/robot-mcp-workspace/characters/anon-body-balance-r16/anon-body-balance-r16.bbmodel`；其余七人使用 `art/robot-mcp-workspace/characters/<id>-r16-standard/<id>-r16-standard.bbmodel`。外观索引为 [七人及爱音总览](../art/robot-mcp-workspace/characters/seven-r16-standard/README.md)。原件和贴图未修改，八份 SHA-256 固定在转换器与运行时清单中；源哈希不同会停止导出。

| 角色 | 资源 ID | 原件 cube | 导出 quad | 原贴图数 | 游戏图集尺寸 |
| --- | --- | ---: | ---: | ---: | --- |
| 千早爱音 | anon | 615 | 3690 | 9 | 128×1024 |
| 高松灯 | tomori | 578 | 3468 | 1 | 64×64 |
| 长崎爽世 | soyo | 541 | 3246 | 1 | 64×64 |
| 椎名立希 | taki | 613 | 3678 | 1 | 64×64 |
| 要乐奈 | rana | 586 | 3516 | 1 | 64×64 |
| 丰川祥子 | sakiko | 584 | 3504 | 1 | 64×64 |
| 三角初华 | uika | 586 | 3516 | 1 | 64×64 |
| 八幡海铃 | umiri | 569 | 3414 | 1 | 64×64 |

合计 4672 cube、28032 quad、112128 vertex。每个 cube 的六面均进入运行时资源；没有以旧占位稿、原版玩家皮肤或部分头部替换。静态源中没有动画。

转换入口：

```powershell
python art/sources/export_robot_runtime_models.py
```

依赖 Python 与 Pillow。它只读已批准的 `.bbmodel`，写本模组的专用资源；不调用模型生成服务、Minecraft 或 Blockbench，不修改存档。

- 网格：`src/main/resources/assets/ic2newera/robot_models/r16/<id>.mesh`
- 图集：`src/main/resources/assets/ic2newera/textures/entity/robot/r16/<id>.png`
- 采样元数据：同目录 `<id>.png.mcmeta`
- [完整来源、尺寸、部位、误差与资源 SHA-256 清单](../src/main/resources/assets/ic2newera/robot_models/r16/manifest.json)

`.mesh` 是专用实体网格，不能当作普通 Java block/item JSON。格式标记 `NER16M01`，大端浮点数；头部保存六个包围盒坐标，随后依固定顺序保存 body、head、arm_left、arm_right、leg_left、leg_right、skirt 七个刚性部位。每部位为三个枢轴坐标、vertex 数量和交错的 `x,y,z,u,v,nx,ny,nz` 数据。四个连续 vertex 构成一个 quad。

## 转换与显示约定

1. Generic/free 的 ZYX 欧拉顺序按 `Rz × Ry × Rx` 处理。先绕 cube 原 `origin` 旋转，再由内到外应用父组旋转；不是把三轴角度强塞进只支持单轴角度的原版方块模型。
2. 保留逐面 UV、镜向 UV 范围与面内 90° 旋转规则；north/south/east/west/up/down 分别按源面方向建立几何。当前八份源的 UV 均在各自图集内，没有 UV 包裹依赖。
3. 实体模型坐标为 `(x / 16, (24 - y) / 16, z / 16)`。保留原头身比例，脚底对应 LivingEntityRenderer 的地面约定，整人实际高度约 1.745 格。Y 轴反射后反转 quad 绕序，并从转换后的顶点重新计算单位法线；原 front 保持模型的 -Z 方向。
4. 爱音九张 PNG 按整数像素位置拼入一张图；包括目前未被面引用的早期图集。逐张裁回后与原 RGBA 字节比对一致，没有缩放、调色或重画。其余七张 PNG 直接复制原文件字节。全部原 alpha 只有 0/255。
5. 图集使用 `blur=false`、`clamp=true`，按最近像素采样，不混合相邻图集边缘。材质使用 `entityCutoutNoCull`：保留透明区域、双面可见、深度写入、原版实体明暗、受伤覆盖与发光轮廓。未把所有面强制全亮。
6. 所有数据写为 float32。当前最大单分量量化误差小于 `6e-8`（实体格或归一化 UV 单位），未改变原件。

语义核对依据为 Blockbench 原作者的 [默认欧拉格式定义](https://raw.githubusercontent.com/JannisX11/blockbench/master/js/io/format.ts) 与 [CubeFace 的 UV/面方向定义](https://raw.githubusercontent.com/JannisX11/blockbench/master/js/outliner/types/cube.js)。Minecraft/Forge 接口按本机 Forge 1.19.2-43.5.0 official-mapped sources 核对；仅使用接口与格式信息，没有复制第三方实现或资产。

## 共用实体与缓存

客户端注册 `RobotRegistry.ROBOT`，实际实体 ID 为 `ic2newera:companion_robot`，通过 `RobotEntity.character()` / `RobotCharacter.id` 选择八人资源。角色的 NBT 字段是 `RobotCharacter`。创造模式使用另一任务提供的对应角色娃娃；有权限的命令调试入口为：

```mcfunction
/summon ic2newera:companion_robot ~ ~ ~ {RobotCharacter:"anon"}
/summon ic2newera:companion_robot ~ ~ ~ {RobotCharacter:"tomori"}
```

可将角色 ID 替换为上表其余六个值。命令生成的实体与娃娃放置的所有权/交互行为不同，由共用实体任务负责；这里不宣称已实机核验。

`RobotClientEvents` 通过 `Dist.CLIENT` 的 MOD bus 注册 renderer 与客户端资源重载监听器。`RobotModelResources` 在加载资源或 F3+T 时读八份二进制资源，并发布不可修改的角色映射；逐帧只选择已缓存的数组、施加姿态和提交顶点，不读 JSON、不解析 `.bbmodel`、不重建 cube/quad。

加载器验证格式标记、部位数量、顶点数上限、有限浮点数、边界和单位法线。单个角色资源缺失/损坏时，日志包含该角色 ID、网格和贴图路径，只为该角色显示醒目的缺失资源标记，其余角色继续加载。无客户端 renderer/纹理/网格类被公共实体注册引用。

## 姿态与验证边界

完整 head 部位（脸、五官、全部长短发）绕源 `head` 枢轴跟随实体头转向，yaw 限 ±25°、pitch 限 ±12°，先保守展示再按实机校准。四肢和裙摆保持源静态姿态；没有步行、坐下、挥手、头发物理、装备或手持道具渲染层，也没有源动画导入。跟随时可能呈现四肢不摆动；长发转头仍可能与肩背相交，不能描述成动态无穿插。

已完成：八份源哈希核对、4672 cube 完整导出、逐 atlas 原 RGBA 一致性、二进制写出和量化检查；另从游戏实际 `.mesh` 和 PNG 直接栅格化八人的正/侧/背图，并亲自核对完整头身、长发和服装方向。[24 视图、可复现渲染脚本与来源](../art/robot-mcp-workspace/runtime-r16/README.md) 已保存。离线图不是 Minecraft 截图，光照也不等同游戏。

`compileJava`、最终构建与安装状态以本次交接末尾的记录为准。初次共享工作区编译时，另一任务仍在写入公共实体，曾出现其 `mobInteract` 可见性和待生成 `RobotPackets` 的编译错误；不把这次中间失败称为模型资源错误，也不据此修改其他任务代码。

未新增或运行测试；未启动、关闭或强杀 Minecraft；未修改存档；未上传游戏仓库。资源重载、服务端实际启动、多人角色同步、生成/卸载/重载、轮廓/受伤效果、远近剔除、明暗/透明和性能仍需实际游戏验收。最终 clean build、PCL 安装与回执由主智能体处理。

## 本接入负责的文件

- `art/sources/export_robot_runtime_models.py`
- `src/main/java/com/ic2newera/client/robot/RobotMesh.java`
- `src/main/java/com/ic2newera/client/robot/RobotModelResources.java`
- `src/main/java/com/ic2newera/client/robot/RobotRuntimeModel.java`
- `src/main/java/com/ic2newera/client/robot/HumanoidRobotRenderer.java`
- `src/main/java/com/ic2newera/client/robot/RobotClientEvents.java`
- 八个 `.mesh`、八张 PNG、八份 PNG 采样元数据及 `manifest.json`
- 本文
- `art/robot-mcp-workspace/runtime-r16/` 的离线网格预览、渲染脚本与来源记录

共用实体、娃娃、注册入口、语言、配方与控制/网络文件由另一任务负责。此前本接入补充的最小实体/角色枚举/生成蛋及自己的入口注册和语言键已精确撤回，没有撤回其他任务的改动。旧 `robot_models/*.json` 保留供历史来源追踪，新的 renderer 不读取它们。

## 主智能体最终集成结果

共享实体的 `mobInteract` 可见性已由所属任务修复。2026-10-17 统一 `clean build -x test --offline` 已成功（37 秒），并于北京时间 20:31 更新本地 PCL Forge 1.19.2 隔离版本。JAR SHA-256 为 `e33c9bfa72b1fc9d17a91553665083595b4912d74b092e8d3a0867d1fef3d768`；构建/release/安装文件一致，8 个安装模组哈希核对通过。详细回执见 [共同交付记录](robot-models-game-and-plugin-2026-10-17.md)。前面的失败仅为并发开发中的历史记录，当前构建已通过；仍未进行真实游戏显示/交互验收。

## 本接入最终检查回执

最后执行 `.\gradlew.bat '-Dnet.minecraftforge.gradle.check.certs=false' compileJava -x test --offline`，返回 `BUILD FAILED`：共享任务的 `src/main/java/com/ic2newera/robot/RobotEntity.java:69` 仍以 `protected mobInteract` 覆盖父类 `Animal` 的 `public` 方法。该次仅剩这一个编译错误；编译器没有报告本接入五个客户端类的错误，但这不等于整个项目编译成功。按协作边界交由公共实体任务/主智能体修复并统一 clean build，此处没有擅自修改它的实现。

八份源与 24 份运行时资源再次逐文件核对 SHA-256 一致。运行时清单（包含每个 `.mesh`、PNG、采样元数据的独立哈希）SHA-256 为 `612d3926a5a0c38dce496383158250c932cada80a1c9b2afc85b3ce066e24e8f`。离线 24 视图 SHA-256 为 `1f2a784d9b53d6e8e8d48bb73f11e0514cd60c2240063ea23a2a45986f075067`。最终 JAR、安装和实机验收未由本接入执行。
