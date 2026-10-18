# R16 机器人运行时动作与装备接入

2026-10-17，动画子任务源码与离线预览记录。统一构建、安装和游戏验收由主任务单独记录。没有新增或运行测试，没有启动/关闭 Minecraft，没有修改存档或任何角色原件。

## 已实现的客户端行为

- 行走：根据原版 `limbSwing` / `limbAmount` 让左右腿交替、双臂反向摆动；初华/乐奈腿幅最高 7°，爱音/爽世/海铃最高 9°，灯/立希/祥子最高 12°，臂幅最高 14°。每帧只从两腿的小数组计算脚底修正，使最低鞋底保持在原模型地面高度，避免直接旋转后脚插进地面。
- 待命：双臂约 1° 的缓慢摆动及小幅外展；不同实体使用各自的相位。待命保持站立，未把 `TamableAnimal` 的停留标记强行画成蹲坐。
- 工作：客户端读取服务端同步的 `RobotEntity.isWorking()`；工作臂前举 48°，每次实际挥工具使用原版 swing 进度进一步前举，最高约 87°。待机、寻路和没有有效目标时由实体层把工作状态设为 false。客户端不会自行触发挖掘或凭动画判断工作成功。
- 攻击：读取渲染器已插值的 `attackTime`，使用真实主手/副手方向挥动；持物准备姿态与动作混合。伤害、距离、能量与攻击频率仍由服务端实体行为决定。
- 头转向：脸、五官和全部头发仍属于原 `head` 部位。爱音、爽世、立希、祥子限制 yaw ±12° / pitch ±6°；灯、乐奈、初华、海铃限制 ±25° / ±12°。
- 手持物品：使用原版 `ItemInHandLayer`，保留 `THIRD_PERSON_LEFT_HAND` / `THIRD_PERSON_RIGHT_HAND` 的物品显示变换和模组物品渲染器。握持点在资源加载时从各臂末端真实网格求出，随同一臂枢轴、旋转和地面修正移动，支持主副手。
- 防具：使用原版 `HumanoidArmorLayer` 的槽位选择、染色、附魔、材质和 Forge hook；标准防具几何按 R16 头身及四肢比例适配。`RobotRuntimeModel` 内的 humanoid 节点均无 cube，只是装备姿态接口；本体始终渲染现有 R16 `.mesh`，没有玩家皮肤或替代角色模型。

## 公共实体接口约定

唯一新增依赖为 `public boolean RobotEntity.isWorking()`，应通过实体同步数据提供客户端可见状态。有效工作每次挥动走 `swing(InteractionHand.MAIN_HAND)`；攻击沿用原版 swing。装备使用原版 `getMainHandItem()` / `getOffhandItem()` / `getItemBySlot()`，主手朝向使用 `getMainArm()`。不新增动画网络包，不改实体注册和八个角色 ID。

`RobotAnimationPose` 是独立于 Minecraft 类的纯姿态计算器。客户端与离线导出直接使用同一 Java 源码，没有第二份 Python 动作公式。姿态的旋转次序为 Z、Y、X 的矩阵相乘（顶点实际先 X、后 Y、后 Z），所有部位继续绕 `.mesh` 中原枢轴旋转。

## 可复现离线预览

执行：

```powershell
python art/robot-mcp-workspace/runtime-r16/render_animation_preview.py
```

依赖 Java 17、Python、numpy、Pillow。脚本在临时目录编译纯姿态类和导出器，运行离线姿态导出，随后读取游戏实际 `.mesh` 顶点、法线、UV 和图集栅格化。没有执行测试或启动游戏；临时 Java 类自动清理。八份原 `.bbmodel`、八份网格及图集/采样元数据的哈希逐项与原 manifest 核对。

- [八人动作检查总览](../art/robot-mcp-workspace/runtime-r16/animation/eight-character-animation-overview.png)：步行极值侧视、工作极值侧视、头转向极值斜前视。
- [爱音九种关键姿态](../art/robot-mcp-workspace/runtime-r16/animation/anon-keyframes.png)、[灯](../art/robot-mcp-workspace/runtime-r16/animation/tomori-keyframes.png)、[爽世](../art/robot-mcp-workspace/runtime-r16/animation/soyo-keyframes.png)、[立希](../art/robot-mcp-workspace/runtime-r16/animation/taki-keyframes.png)、[乐奈](../art/robot-mcp-workspace/runtime-r16/animation/rana-keyframes.png)、[祥子](../art/robot-mcp-workspace/runtime-r16/animation/sakiko-keyframes.png)、[初华](../art/robot-mcp-workspace/runtime-r16/animation/uika-keyframes.png)、[海铃](../art/robot-mcp-workspace/runtime-r16/animation/umiri-keyframes.png)。每份有斜前/侧视两排。
- [爱音步行](../art/robot-mcp-workspace/runtime-r16/animation/anon-walk.gif)、[爱音工作](../art/robot-mcp-workspace/runtime-r16/animation/anon-work.gif)、[灯步行](../art/robot-mcp-workspace/runtime-r16/animation/tomori-walk.gif)、[灯工作](../art/robot-mcp-workspace/runtime-r16/animation/tomori-work.gif)。工作 GIF 展示手臂动作，不含物品或装备。
- [导出姿态](../art/robot-mcp-workspace/runtime-r16/animation/keyframes.json)、[来源与哈希回执](../art/robot-mcp-workspace/runtime-r16/animation/provenance.json)。

离线关键帧已人工查看；第一轮完整斜前视检查发现初华和乐奈在 12° 步幅时腿会穿过前裙面，因此分别收紧至 7°，海铃也收至 9° 后重出相关预览。手臂前举和左右步态方向正确。离线材质、光照、相机、GIF 时间不等于 Minecraft 实际画面，也不证明游戏动画同步、手持物品或防具已验收。

## 已知动作与装备限制

现有运行时资源只有 body/head/两臂/两腿/skirt 七个刚性部位，没有肘、膝、手指、分段发束或布料骨骼。动作因此采用小步幅、整臂挥动与有限转头；裙部保持原腰部位置，没有独立裙发物理和碰撞解算。尤其长发转向与肩背、反向摆臂仍可能局部相交；这些限制不能写成“动态完全无穿模”。

标准防具适配是通用比例；长发可能穿过头盔后缘，服装可能盖住部分护腿。Forge hook 返回的自定义防具模型由该模组决定形状，其几何未必遵从此比例；需要实际游戏核对。头槽中的非盔甲方块/头颅、鞘翅、弓弩专用拉弓动作、游泳/乘骑/蹲坐动画不属于本次实现。手持工具的最终握持方向、附魔和各类模组特殊渲染也待实机验收。

## 文件范围

客户端：`RobotRuntimeModel.java`、`RobotMesh.java`、`HumanoidRobotRenderer.java`，新增 `RobotAnimationPose.java`、`RobotArmorModel.java`。离线：`art/sources/ExportRobotAnimationFrames.java`、`art/robot-mcp-workspace/runtime-r16/render_animation_preview.py` 与 `animation/` 输出。本记录和 R16 接入文档的后续说明。没有修改实体、物品、网络协议、控制界面、聊天桥、工业网络或原始美术资源。
