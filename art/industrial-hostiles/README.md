# 原创工业敌对实体资源

2026-10-19 原始源码/资源已由主线程构建安装；本次普通点射/短抬手增量仍待统一构建安装，战斗平衡和多人表现待人工实机验收。

## 两种轮廓

- `patrol_drone` 失控巡检机：56 个立方体、6 骨骼；四组带护栏旋翼、橙黄扫描眼、底部脉冲线圈、着陆叉。普通点射无橙色激光预警，最远 7.5 格，45 tick 严格射击冷却；碰撞体与视线检测阻断穿墙。
- `industrial_sentinel` 工业守卫：38 个立方体、6 骨骼；装甲胸腹、独立传感头、双机械腿、外露活塞、单侧重锤臂。16 tick 短抬手、固定落点重击，不再显示地面提示圈，36 tick 攻击后硬直；不破坏方块、不爆炸、不点火、不偷取物品。

预览：[巡检机](patrol_drone-animated.png)、[守卫蓄力](industrial_sentinel-animated.png)。预览由已存在的 Blockbench 文件 MCP / bb-render 生成，未开启 Blockbench 桌面或游戏。

## 原创来源与可重现

模型轮廓、骨骼、UV、像素金属色板、警示条、铆钉和动画关键帧均由本项目创建；没有提取第三方模组资产，也没有使用僵尸换皮。

1. 在项目内执行 `python scripts/generate-industrial-hostiles.py`（Python/Pillow）。生成独立 mesh JSON、256×256 RGBA 纹理和 `*.mcp-operations.json`。
2. Blockbench 文件 MCP 允许的根目录是 `art/robot-mcp-workspace`。在其独立 `industrial-hostiles` 子目录创建 `modded_entity`、`box_uv=true`、256×256 的模型，再批量应用 operations；已有文件先读 revision，不覆盖其他人的节点。
3. MCP 执行 outliner / unlisted_nodes / degenerate 几何检查，再读取动画骨骼、渲染 idle 或 heavy_strike 帧；本次两模型上述检查均为 0 错误、0 警告，动画引用骨骼均存在。不运行工具 self_test。
4. 将本次 `.bbmodel` 与预览复制至本目录。模型带嵌入 PNG，可直接在 Blockbench 编辑；本目录是交付源，暂存目录不涉及旧机器人文件。
5. runtime `models/entity/industrialhostile/*.json` 与本目录 `.mesh.json` 内容相同；客户端 `IndustrialHostileModel` 把同一数据转换为原版 `ModelPart`，将 Y-up 坐标反射为 Y-down，X/Z 动画旋转相应反号。纹理与 bbmodel 嵌入 PNG 相同。

脚本重跑会重建 runtime 资源与 MCP 输入；若手工修改 bbmodel，必须同步 mesh/脚本再发布，不能只改一份。运行时不依赖 MCP、Python、Blockbench 或额外动画库。

## 服务端接线

`IndustrialHostiles.register(IEventBus)` 注册实体、属性、创造蛋。允许 ID 仅 `ic2newera:patrol_drone` 和 `ic2newera:industrial_sentinel`；`IndustrialHostiles.type(String)` 返回白名单类型或 null。客户端注册器限定 `Dist.CLIENT` 并自动订阅 MOD bus。

任务后端创建实体后、加入世界前调用 `bindEncounter(UUID campaign, UUID encounter, Set<UUID> participants, BlockPos origin)`；成员 1～64 人，半径 24 格、高差 12 格、最长 12000 tick。任务后端负责召唤点安全检查、在场人数/总量上限和玩家主动确认。`updateParticipants` 可撤销成员。

`campaignId()`、`encounterId()`、`participantIds()`、`encounterOrigin()`、`isEncounterBound()` 供服务端真实 `LivingDeathEvent` 判定；不接受客户端击杀声明。怪物仅选本次参与且在线的生存玩家，或主人满足同样条件的机器人。非参与者伤害被拒绝；环境击杀掉落与有效任务击杀独立，任务后端还须核对真实击杀者。未绑定创造蛋样本被动，允许创造玩家清理。

出生区域越界、维度变化、寿命结束直接 discard；参与者离开/登出后立即停战，100 tick 无有效参与者则 discard。已保存绑定实体在加载后首 tick discard，不自动重启旧战斗，不触发击杀完成；任务控制器应恢复为待重试。没有自然生成注册，不会自动刷入旧工厂。

## 初始平衡与限制

巡检机 80 HP / 8 护甲 / 10 脉冲；守卫 240 HP / 14 护甲 / 18 重击。使用原版伤害、耐久与无敌帧，未做瞬杀、百分比免伤或全伤害吸收。死亡只掉少量铁粒/红石或铁锭/铜锭，无 CPU、高级电池或科技捷径，经验为 0。

已完成源码静态审阅、MCP 几何检查和资源一致性核查；尚未验证 Forge 编译、实体导航/碰撞、机器人参战、多端动画同步、帧率或数值体验。实体消失不算胜利，后端限额/恢复接线和真实服务器验收仍必须完成。
