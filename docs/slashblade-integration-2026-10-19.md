# 科技拔刀与武装材料集成记录

2026-10-19，GPT-6.1 Sol。此记录为依赖核实、数据/源码和静态审计；本代理没有构建、安装、运行测试、启动游戏/服务器、修改存档或LLM配置。战斗和美术由独立代理实现，实机表现另验收。

## 固定原版依赖

作者flammpfeil的[官方1.19.2 Forge文件](https://www.curseforge.com/minecraft/mc-mods/slashblade/files/4346941)：`SlashBlade-1.19.2-0.0.8.jar`，CF241596/4346941，2023-01-17上传的Beta。不是仅用于新版Minecraft的Resharped/Reborn。

- 本地 `build-isolated/slashblade-20261019/dependencies/SlashBlade-1.19.2-0.0.8.jar`。
- 3,030,466字节，SHA256 `0BE453ADA11D57ECB0491416571AD1C46B19FA28743CC772529C14E39A71FEA0`。
- JavaFML `[43,)`；模组ID `slashblade`；Manifest实际版本 `0.0.8`。
- mods.toml硬依赖仅Forge `[40,)`、Minecraft `[1.19.2,1.20)`；无额外第三方硬依赖或内嵌JAR。当前Forge43.5.0满足这些声明，仍不等于实机兼容已验证。
- 官方CurseMaven坐标 `curse.maven:slashblade-241596:4346941`。`build.gradle`使用 `implementation fg.deobf` 支持子类编译与运行；仅依赖解析的在线/离线 `resolveCampaignRuntime` 都成功，134个运行产物已在Gradle缓存。
- 根代理最终决定本版本必须安装SlashBlade，项目mods.toml为 `mandatory=true`、`[0.0.8,0.0.9)`、`AFTER/BOTH`，安装清单追加该固定JAR，没有更新新纪元自身hash。
- 下载与元数据可复现脚本 `scripts/get-slashblade-dependency.py`；公开锁定信息 `scripts/slashblade-dependency.json`，完整回执位于本阶段build-isolated目录。只下载官方JAR，不复制第三方实现或资产。

## 实际0.0.8 API边界

依据上述固定JAR的javap与字节码，证据保存在本阶段 `api-public.txt` 和 `*.bytecode.txt`：

- `mods.flammpfeil.slashblade.item.ItemSlashBlade(Tier,int,float,Item.Properties)` 为公开构造，可真实继承。`CapabilityAttachHandler.AttachCapabilities_ItemStack`用 `instanceof ItemSlashBlade` 自动附 `slashblade:bladestate`，子类不应重复附加。
- `ItemSlashBlade.BLADESTATE`公开。`ISlashBladeState`提供模型/贴图、基础攻击修正、地面/空中连段根、SA、特效颜色、损坏、杀敌计数等公开setter。优先调用能力API，不能把普通Item改名后声称拔刀剑。
- `ComboState`有公开构造、`setClickAction/addTickAction/addHitEffect`等；`SlashArts(String,Function<LivingEntity,ComboState>)`支持SA。其 `RegistryBase`固定 `slashblade` namespace并拼path，名字应使用 `ic2newera_*` 普通唯一字符串，不能传带冒号的资源ID；能力里使用注册对象 `getName()`。
- `InputCommandEvent`为服务端玩家输入变化事件；`RenderOverrideEvent`可改已有模型/部件/贴图。它不是独立双手攻击或副手渲染接口。
- `LayerMainBlade.render`、`BladeFirstPersonRender.render`及原生攻击路径均主要读取MAIN_HAND。不能假定装备两把便自动独立连击；副手显示与受限服务器技能由战斗代理单独适配。
- 原生模型烘焙只为 `SBItems.slashblade#inventory` 包装 `BladeModel`。新增注册ID需自有客户端烘焙回调包装模型；直接换Item类不保证第一人称和原生TEISR成立。
- 原生配方支持Forge结果NBT和 `AnvilCraftingRecipe`，但本项目两把刀采用各自真实注册ID及子类能力初始化，工艺图不嵌任意复制的第三方刀NBT。电子机reload时 `new ItemStack`、`copy`和JEI结果路径也必须被初始化覆盖，已交战斗代理核实。
- 原生 `slashblade:slashblade` 覆盖多种有NBT的名刀。根代理最终取消消耗整刀，改用实际 `slashblade:proudsoul_ingot` 耀魂锭；JAR原生 `data/slashblade/recipes/material/ingot.json` 为耀魂2+铁锭1→耀魂锭1，因此不会吞掉玩家珍藏刀的附魔/杀敌状态。

## 四种资源与闭环

检查本阶段固定Ad Astra/库/Aircraft/NPC/YUNG、现有IC2/Create依赖的对应物品tag和资源，未发现可复用的钴/钼/钽同名金属资源；没有再造已有铱。`arsenal/ArsenalMaterials`与资产由资源代理拥有。

- 月球钴、钼；火星钽。三金属各有矿块/粗原料/含杂质粉/净粉/锭。正常矿物掉粗原料，精准采集和时运保留。普通早期原矿/粗原料直烧单倍；工业粉碎2杂质粉→水洗1:1→IC2已有高级炉净粉1:1成锭，保留锭回净粉1:1。无化学、无新设备、无电力互转。
- 对应Ad Astra生物群系软tag、月岩/火星岩replaceable tag；3次/区块、size4、Y=-48..48，仅新生成区块。钴/钼钻石级，钽下界合金级，不需要本次不存在的新采集工具；旧钒钢/铌钛工具保留。
- 火星共振簇复用原版AmethystClusterBlock机制，地表heightmap、空气与原生would_survive过滤后生成朝上簇，避免用矿石替换造成无支撑悬空。每区块3次尝试，不生长、不回填、不注入旧主世界；正常掉2碎晶，精准采集得簇，时运/爆炸衰减走原生规则。
- 共振碎晶2→共振核心1，仅已有非金属处理机，400tick×32EU/t；没有共振粉末或金属锭。外部生物群系引用延用 `required:false`，配置不直接引用外部BlockState。

六个中间件都进入真实后续消耗：

| 元件 | 批准制作 | 直接消费用途 |
|---|---|---|
| 磁线圈 | 电子机：钴锭2+铜锭2+PCB1→2 | 夜弧、两件磁导甲、稳定器 |
| 耐热板 | Create冲压：钼锭1→1 | 两套护腿、稳定器 |
| 脉冲电容 | 电子机：钽锭2+高纯硅1+PCB1→2 | 两套胸甲、稳定器 |
| 共振核心 | 非金属机：共振碎晶2→1 | 星脉、两件共振甲 |
| 刀稳定器 | 电子机：磁线圈2+耐热板2+电容2+IC1→1 | 夜弧、星脉 |
| 伺服装甲关节 | 电子机：钴/钼/钽锭各1+Create精密构件1+IC1→2 | 两刀、两套胸甲 |

双刀各用电子机6槽：耀魂锭1、稳定器1、磁线圈/共振核心2、钒钢/铌钛锭4、CPU1、伺服关节1，1200tick×64EU/t。两套护甲使用常规3×3形状，金属与上述元件实际消耗；维修分别用钴锭/钽锭，由注册代理实现。此次不新增采集工具。

## 统一图、指引与支线

数据源 `process_graph/arsenal.json` 新增3材料/49路线，脚本 `scripts/generate-arsenal-integration.py` 可重生成。总19材料/360路线，仍低于512路线界限；材料边界扩大到24并同步网络解析，主网络协议29→30。512KiB限制保持；当前估算84,105字节。RecipeGuard精确保护中间件、刀与护甲批准数量/类型，没有修改第三方JAR或全量删除外部配方。

动态科技新增异星材料、科技武装中间件、科技拔刀与双刃战技、后期科技护甲分支，钴/钼/钽中文名称及AI材料、元件、刀、甲别名已接入同一工艺图；不改模型参数。

新增 `08_optional_blades`，第八章08_01之后由岑工接取，`side:true`。接取并绑定电子机后真实制造两刀各1，展示两套胸甲，不收走成品；技能表现不冒充已验证的战斗证据。现有40主线与两个遭遇支线不变，合计40主线+3支线，主线不以本支线为前置。

## 验证界限

`build-isolated/slashblade-20261019/integration-static-audit.json`：19材料/360路线、84,105字节；163个动态科技节点无环；任务前置与route引用无缺失，护甲shape原料数量一致，6中间件都有实际消费者。原生耀魂锭ID与版本/能力签名来自固定JAR，不从新版源码推断。

在线和离线仅依赖解析已成功；此阶段尚未进行统一编译、安装和游戏验收。待根代理统一构建，再由玩家验证加载、晶簇生存条件、矿物产量、JEI/能力初始化、第一/第三人称双持、服务器战斗边界与实际手感。
