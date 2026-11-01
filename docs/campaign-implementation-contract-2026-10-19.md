# 主线实现契约（2026-10-19）

实施者：GPT-6.1 Sol。本文固定任务数据、服务端证据及交互边界；实现、编译与实机状态另记，不能把契约写成验收通过。

## 数据

目录 `data/ic2newera/campaign/quests/*.json`，文件名决定稳定任务ID（例如 `ic2newera:01_01`）。只接受本mod命名空间；40项任务按chapter/order排列。示例：

```json
{
  "version": 1,
  "chapter": 1,
  "order": 1,
  "npc": "stationmaster",
  "title": "营地报到",
  "description": "向站长了解任务并追踪记录。",
  "icon": "minecraft:compass",
  "prerequisites": [],
  "requires_mods": [],
  "steps": ["打开任务日志，确认接取。", "点击追踪，再回站长提交。"],
  "danger": "营地不会替你放建筑或改变旧基地。",
  "objectives": [{"type":"event","event":"action","target":"ic2newera:track","count":1,"scope":"shared"}],
  "rewards": [{"item":"minecraft:bread","count":4}],
  "legacy_credit": false
}
```

NPC角色仅 `stationmaster` 林舟、`engineer` 岑工、`surveyor` 黎岚、`astronaut` 乔宁、`communicator` 许澄。

objective类型：`show_item`（提交时实物展示不扣）、`consume_item`（首次共享提交与奖励容量预检同一事务才扣）、`event`（真实服务端证据）。物品目标用`target`；事件额外有`event`。`count`为正整数；scope为`shared`或`personal`，默认shared。可选`route`引用批准ProcessGraph ID，不在任务内写第二套配方。可选`technology`引用服务端科技ID，仅入门认可使用，不能代替专题产出。`legacy_credit:true`只允许没有生产/入库/驾驶/发射等实作事件的基础任务，并由明确毕业验收动作重验现有库存，不伪造事件。

## 事件API（集成代理拥有 quest/integration/** 与原生完成点接线）

`QuestEvidence.register(String event, String description)`注册已真实接入的事件；未注册事件目标显示“待接入验收”，不能完成。

`QuestEvidence.emit(ServerPlayer actor, String event, ResourceLocation target, int count)`：在真实动作提交后调用；后端只向actor当前campaign中已接取且前置满足的目标累计。只用于服务器线程；不接受客户端上报完成数。用于`action/place/visit/investigate/produce/robot_collect/robot_mode/cook/ride/flight/launch/oxygen/supply/story`等，具体支持由适配器注册，不能仅为消除不可用提示注册空实现。

`QuestEvidence.machine(ServerLevel level, BlockPos pos, ResourceLocation recipe, ItemStack output)`：真实加工完成后调用。只向成员主动绑定的机器归属提供证据，且recipe须在ProcessGraph为批准路线；按route ID与实际output分别派发`process`及`produce`，不把转速/活动当产出。任务GUI提供“绑定我上次检查的设备”操作；玩家实际右键设备时记录候选，服务端验证已加载、距离与已知设施权限，不能由客户端填任意坐标。

机器人事件先核实实际入货增量，再向主人emit；机器人任务本身的重放去重由持久任务身份/实际发生的入货保证。无需改成可以任意客户端发事件的接口。

## 交互及存档

独立quest频道协议1：客户端动作只含menuId、随机session票据、快照revision、action、稳定任务ID/必要邀请玩家名，不含奖励或完成数。菜单绑定终端或NPC，接受/提交/领奖仅服务器核验；任务日志可随时看，NPC交付需同维度、已加载存活且8格内，角色匹配。

任务日志独立快捷键（J）无需物品/LLM。玩家可明确选择“在此联络营地”，先预览五个NPC占地，再确认；服务端检查加载、碰撞、世界边界、合法交互/权限，绝不自动登录spawn或放建筑。潜行使用自己有权限的既有终端同样可进入；普通终端存储不变。

Easy NPC 5.7.0优先提供显示实体；使用实际注册EntityType及项目持久角色标签，只拦截新纪元创建并绑定的角色，不接管全村民，不使用NPC任意命令发奖。角色UUID及最后位置存campaign；死亡可明确恢复，卸载只提示待加载，不重复生成、不强载区块。

世界SavedData保存稳定world UUID、campaign UUID、队长、明确成员/邀请、共享任务状态、贡献/调查及NPC角色身份。加入必须队长邀请且本人确认；个人教程和奖励回执放玩家持久NBT。领取键为world UUID+稳定任务ID+玩家UUID，不含campaign/定义version；换营地、离队重进、升级定义均不重复领奖。历史已提交任务不自动赋予新入队者全部奖励，接受/参与时记录资格。

首次提交：重验共享证据、个人条件、当前背包与全部奖励容量；先在背包副本模拟扣料/发奖。扣料前通过原生SavedData写出本模组营地文件并回读确认稳定world/campaign身份，再原子持久化本模组独立pending事务账本（任务、贡献者、事务token、定义版本、参与资格和进度）。随后提交库存及个人回执，通过原生PlayerList保存玩家并仅回读自有token确认。无法确认时保留pending并阻止其他成员再次交付；其他成员先读原贡献者已存回执恢复共享状态，不依赖原贡献者先登录。共享提交确认后保留committed账本，恢复只补回执而不重发奖励。未手写替换原生playerdata。后续有资格成员只领个人奖励，不再次消耗共享交付。重复包检查票据/revision/状态/回执，不重复扣物或发奖。此为源码中的恢复设计，尚未实机验证崩溃恢复。

40项主线之外增加两项正式可选遭遇支线，`side:true`：第五章巡检无人机、第八章工业守卫，各一次真实击杀，不作为主线前置。界面独立`has_encounter`字段控制双确认入口，不解析显示文本。和平难度和无合格生存/冒险参与者明确拒绝；按遭遇原维度管理实体，离队/退出撤销既有参与者，卸载/超时/读档回收不算击杀。

NPC交谈在详情显示该任务真实说明后记录对应story证据；自有标签角色同时处理标准和精确实体交互，尊重已取消保护事件。服务器停止时显式释放事务实例及交互节流/设备检查缓存，不保留旧世界服务器引用。

目标1.19.2的`PlayerList.save(ServerPlayer)`为protected，使用已核实的公开`saveAll()`原生循环保存，再回读当前贡献者自有token。代价是一次真实领奖（或尚待确认的在场贡献者恢复）会保存全部在线玩家；普通查看和刷新不会主动保存玩家。未使用反射、访问放宽或自写playerdata替换逻辑。

原创怪物接口由实体代理提供 `industrialhostile.IndustrialHostiles.register(IEventBus)`与`EncounterHostile.bindEncounter(campaign, encounter, participants, origin)`。任务后端仅在成员主动确认远离营地/工业设施的安全遭遇点后限量生成，不自然刷怪。真实死亡事件按campaign/encounter/参与者验证，读档失效遭遇回到待重试，不凭旧“怪物不在”判成功。

## 本次协作所有权

- music_gui_notice_sol：quest/**（排除integration）、client/quest/**、IC2NewEra最终注册，本文。
- tech_preview_sol：quest/integration/**、40任务JSON、外部模组依赖与真实事件完成点接线；可修改Robot/Processor/料理原文件的必要事件调用，不修改后端/menu/UI。
- tech_tree_visuals_sol：原创工业怪物实体/模型/资源与facade，提供实际API供后端注册。

只做本地实现；不新增/运行测试、不启动/关闭游戏、不编辑存档、不读模型私密配置、不制作分享包/上传发布。缺依赖/未支持API必须可见失败，不以占位或自动完成掩盖。
