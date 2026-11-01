# 主线依赖、工艺与真实证据集成（2026-10-19）

本记录区分源码与运行验收。当前完成依赖下载/元数据检查、Gradle依赖预解析、源码与数据实现、静态引用核对；尚未由本代理编译、安装或启动游戏/服务器，没有实机兼容、多人与37小时游玩验收。不新增或运行测试，不改玩家存档、LLM配置或密钥。

## 固定外部依赖

8个官方Forge文件已下载到 `build-isolated/campaign-20261019/dependencies/`，未安装。完整URL、SHA256、大小、mods.toml硬依赖和内嵌JAR保存在 `scripts/campaign-dependencies.json`，逐项原始审计在 `build-isolated/campaign-20261019/dependency-audit.json`；可重复下载脚本为 `scripts/get-campaign-dependencies.py`。

| 模组 | 已核实固定文件 | 官方文件入口 |
|---|---|---|
| Ad Astra | 1.19.2 Forge 1.12.7 | https://www.curseforge.com/minecraft/mc-mods/ad-astra/files/4643668 |
| Botarium | 1.19.2 Forge 1.8.2 | https://www.curseforge.com/minecraft/mc-mods/botarium/files/4306551 |
| Resourceful Lib | 1.19.2 Forge 1.1.24 | https://www.curseforge.com/minecraft/mc-mods/resourceful-lib/files/4477753 |
| Resourceful Config | 1.19.2 Forge 1.0.20 | https://www.curseforge.com/minecraft/mc-mods/resourceful-config/files/4444198 |
| Immersive Aircraft | 0.7.11+1.19.2 Forge | https://www.curseforge.com/minecraft/mc-mods/immersive-aircraft/files/6235341 |
| Easy NPC | 1.19.2 Forge 5.7.0 | https://www.curseforge.com/minecraft/mc-mods/easy-npc/files/5806972 |
| YUNG's Better Dungeons | 1.19.2 Forge 3.2.2 | https://www.curseforge.com/minecraft/mc-mods/yungs-better-dungeons/files/4073204 |
| YUNG's API | 1.19.2 Forge 3.8.10 | https://www.curseforge.com/minecraft/mc-mods/yungs-api/files/4876062 |

Ad Astra硬依赖下限：Botarium1.8.2、Resourceful Lib1.1.23、Resourceful Config1.0.20；所选版本满足。Better Dungeons要求YUNG API至少1.19.2-Forge-3.8.0。三库、Easy NPC、Aircraft和YUNG API没有额外第三方硬依赖；未发现Architectury要求。Aircraft内嵌MathParser5.2.1，不重复安装。全部检查为JavaFML/Forge，未混用Fabric版。实际有版本不等于已完成整合运行验证。

`build.gradle`加入固定CurseMaven `runtimeOnly fg.deobf` 坐标，保持源码适配器无外部类编译依赖。已执行仅解析的 `dependencies --configuration runtimeClasspath`、`resolveCampaignRuntime`及相同任务`--offline`，在线/离线均成功解析133个运行产物，外部fg.deobf缓存已准备。`gradle/campaign-dependency-cache.gradle`仅解析依赖，不编译、不测试、不启动游戏。`scripts/runtime-mods.json`追加8项项目文件，未更改新纪元自身哈希。

本阶段排除Terralith（继续旧存档）；采用原创工业怪物，不加入Alex's Mobs/Citadel。TaCZ不强加到非枪械主线，战斗为可选支线。

## 工艺与矿物

`process_graph/main.json`保持原路线；新增 `process_graph/campaign.json` 2种受控材料、67条记录，总16材料/311路线（包括精确阻断记录）。生成器为 `scripts/generate-campaign-integration.py`，实际配方仍由统一Gradle工艺生成器派生。

- 钒仅月球 `lunar_wastelands`，铌仅火星 `martian_wastelands/martian_canyon_creek/martian_polar_caps`。原生标签 `ad_astra:moon_ore_replaceables/mars_ore_replaceables` 限定替换月岩/火星岩；每区块3次生成尝试、矿簇size4、Y=-48..48。实际形成矿簇数受地形/空洞影响。无主世界注入、无retrogen。
- 缺Ad Astra时生物群系条目 `required:false` 形成空tag，矿床目标是软引用tag而不是外部BlockState；输出仅本项目矿块。外部工艺均 `required_mods`，材料本身不含外部物品。基础新纪元应保持可选集成边界，仍待运行验证。
- 两矿正常掉粗原料，保留原生精准采集/时运与爆炸衰减。V用钻石级；Nb用 `forge:needs_netherite_tool`，下界合金或钒钢可采，不要求铌钛。已加入Forge根矿石tag供机器人识别。
- 矿石/粗原料保留单倍早期直烧；工业粉碎2含杂质粉→Create水洗1:1净粉→IC2高级炉1:1锭。净粉不进普通炉，无新化学。
- IC2合金炉：钒净粉1+高碳钢粉3→钒钢粉4；铌净粉1+钛净粉3→铌钛粉4；成粉进IC2高级炉。合金炉实际两个输入槽使用1/3计数，产物4，金属单位守恒。同级锭回粉1:1。原有高级炉即可生产首批，不依赖本合金设备。
- 两合金18件工具/护甲使用标准3×3形状、对应合金与PCB/集成电路；维修材料由装备注册代理定义为对应锭。无额外无用途中间件。
- Desh/Ostrum保留原生raw→直接冶炼→锭→板身份，未声明不存在的粉。真实原生冶炼ID和Create pressing同ID覆盖纳入批准图；原生压缩/锤打、收纳拆分仍可用，不伪称EU路线。
- 精确阻断 `ad_astra:recipes/steel_ingot_from_blasting_iron_ingot`，防止铁锭直接炼航天钢。已有低碳钢锭等量转换成Ad Astra钢锭，保留钢块/粒与原生火箭部件可获取。
- Aircraft发动机真实加入高碳钢、集成电路、Create精密构件；Ad Astra钢/Desh/Ostrum发动机分别加入钛+CPU、钒钢+CPU、铌钛+GPU；氧气分配器保留原生部件并加入CPU。全部用现有电子材料加工机，最多6个输入槽。不改第三方JAR。

ProcessGraph/网络快照同步扩展 `crafting_shaped/pressing`，路线界限512、材料仍16、字节界限仍512KiB。实际311条的同算法保守估算72,107字节。主网络协议28→29，quest独立频道版本由后端维护；多人双方必须使用同版。RecipeGuard核对shaped空槽、数量、serializer及unordered原料；精确原生阻断也从图中读取。新材料中文、航空/航天/后期装备分支与AI工艺别名已补齐，未修改LLM参数。

## 任务与证据

`campaign/quests` 共40主线+2可选支线，生成器 `scripts/generate-campaign-quests.py`；前置、步骤、物品图标、展示/交付消耗与奖励均为中文数据。主线不依赖两个支线。

- `05_optional_patrol`：第五章05_03后，黎岚接取，真实巡检机击杀1只。
- `08_optional_sentinel`：第八章08_03后，林舟接取，真实工业守卫击杀1只。
- 两支线均 `side:true`，由后端现有日志远场预览/再次确认入口发起限量遭遇；不是只有文案的挑战入口，不要求枪械。

IC2/Create首版没有添加脆弱Mixin。其加工目标明确为“交付实际物资批次，提供批准工艺指引”，成熟库存可用，不凭物品证明来源，不声称机器产量遥测。水轮/EU入门为设备展示与现场指引，未虚构连接/供电证明；前哨空运明确为建设物资交付，不冒充真实货运。

`quest/integration/CampaignIntegrations` 自动订阅服务器事件，支持：

| 事件 | 真实提交点/含义 |
|---|---|
| produce/process | Processor完成真实扣料、耗能与输出后，调用后端machine；必须绑定该设备、且实际recipe匹配批准图 |
| robot_collect | 工作目标已将世界掉落物插入货物槽、减少/移除世界堆后，按净接受数量向主人记录；手动塞库存不计 |
| robot_mode | 机器人成功改变真实模式或成功派发指定采集；目标robot_follow等 |
| inspect | 本人右键有管理权限、已加载并已连接交换机的实际适配器/服务器；适配器还须识别设备 |
| investigate | 本人在实际已生成结构piece内右键方块；查询StructureManager，实际ID为betterdungeons:small_dungeon、ad_astra:moon_dungeon/mars_temple等 |
| flight | 服务端每5tick测量本人控制飞机、离地净空3格、0.05～25格实际位移，累计米数；瞬移不计 |
| launch | 反射已核实原生Rocket.isFlying且驾驶者随火箭实际向上位移；单次飞行只记一次 |
| arrival | 本次服务器会话10分钟内有真实发射记录后跨维度抵达moon/mars，不能只检查玩家所在维度 |
| oxygen | Ad Astra OxygenUtils.inDistributorBubble真实范围内按秒记录，限定月球/火星；太空服氧气不算房间供氧 |
| place/craft/smelt | 原生玩家放置/合成取出/熔炉取出，均实际服务端事件；本批主线未用其冒充工业机器完成 |

action/story/encounter由后端负责，未注册空的cook/supply等适配器。真正需要工艺产出的硅/PCB/CPU任务必须接取后重做小批，不能库存补记。37小时为活跃游玩调优目标，未实测；旧工厂和多人会缩短。

## 静态核对与待验收

`build-isolated/campaign-20261019/integration-static-audit.json` 记录：40主线/2支线、16材料/311路线；任务无缺失前置或route引用、18件装备shape的原料数量无不符、所用外部物品图标均存在于固定JAR；全部18个Ad Astra/Aircraft原生覆盖ID在目标JAR确实存在。每个任务使用已实现的适配器或明确的物资验收；没有靠“待接入”事件占位完成主线。

310→311新增的一条是 `ad_astra:recipes/steel_ingot_from_blasting_iron_ingot` 的 `BLOCKED_BYPASS` 记录；精确禁用同样从统一图派生，不维护另一份配方判断。按动态科技生成器同一主路线选择方式静态展开135节点，无依赖环；钒钢/铌钛粉选择合金配料为主路线、锭选择高级炉，锭回粉回收不成为主父节点。

尚待根代理统一编译及后续玩家实机验证：外部加载与配方/世界生成兼容、地牢自然发现成本、机器绑定多人归属、飞机/火箭服务端轨迹、供氧边界、遭遇安全和奖励事务。此处静态检查不是这些运行验收，未启动游戏或编辑旧存档。
