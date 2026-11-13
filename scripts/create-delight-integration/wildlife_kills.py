"""Scope guard and vanilla hostile-mob jobs for the joint FTB book."""


# These additions specifically depended on Alex's Mobs / Alex's Caves species.
# Their quest IDs are retained here so the merge removes only the intended new
# ecology jobs and leaves every unrelated task and its ID intact.
ECOLOGY_REMOVALS = {
    "0AA4DC0D232CF0E2": ("item", "alexsmobs:animal_dictionary"),
    "0525E335CCAB4AF2": ("advancement", "ic2newera:newera/delight_wildlife_photo"),
    "21AAC2809E78E8BD": ("item", "alexsmobs:rattlesnake_rattle"),
    "0103A1CA65471CB6": ("item", "alexsmobs:roadrunner_feather"),
    "395727EE215E9271": ("item", "alexscaves:trilocaris_tail"),
}
ECOLOGY_REMOVAL_TARGETS = {
    ("item", "alexsmobs:animal_dictionary"),
    ("advancement", "ic2newera:newera/delight_wildlife_photo"),
    ("item", "alexsmobs:rattlesnake_rattle"),
    ("item", "alexsmobs:roadrunner_feather"),
    ("item", "alexscaves:trilocaris_tail"),
}

FIELD_WILDLIFE_REMOVALS = {
    "alexsmobs:rattlesnake_rattle",
    "alexsmobs:roadrunner_feather",
    "alexsmobs:raccoon_tail",
    "alexsmobs:tarantula_hawk_wing",
    "alexsmobs:lost_tentacle",
    "alexsmobs:maggot",
    "alexscaves:trilocaris_tail",
}
FIELD_WILDLIFE_REMOVAL_QUEST_IDS = {
    "alexsmobs:rattlesnake_rattle": "694AD27DD377A0CE",
    "alexsmobs:roadrunner_feather": "694AC955126BF40F",
    "alexsmobs:raccoon_tail": "694A2BBDAB9582EB",
    "alexsmobs:tarantula_hawk_wing": "694A157EA6A1A2D0",
    "alexsmobs:lost_tentacle": "694ACB795E1269B2",
    "alexsmobs:maggot": "694A63EC65AED244",
    "alexscaves:trilocaris_tail": "694A87249F837EB7",
}
ECOLOGY_RETAINED_QUEST_IDS = {
    "796C1715158020D1",  # vanilla cow kill
    "57020FBBB5E5C49D",  # vanilla leather sample
    "0898090629DB364F",  # Spawn angler bucket
    "161320A2AECEA693",  # optional vanilla route card
    "67AF440D9F61A96D",  # industrial phone
}
FIELD_WILDLIFE_RETAINED_QUEST_IDS = {
    "694AC7847E0A7A6B",  # vanilla nautilus shell
    "694A1AA3855D8060",  # vanilla cod bucket
    "694A3CC4F3BBE1BA",  # Spawn angler bucket
    "694ADA69DE1BD8DE",  # Spawn tuna chunk
    "694AEA8570BB148D",  # Spawn seahorse bucket
}

HUNT_JOBS = (
    {"title": "清理主世界僵尸", "entity": "minecraft:zombie", "count": 3, "rank": "E", "branch": "主世界",
     "reward": "minecraft:bread", "reward_count": 2, "text": "击败 3 只僵尸；任意维度击杀均计数，建议在主世界夜间巡查。"},
    {"title": "清理骷髅射手", "entity": "minecraft:skeleton", "count": 2, "rank": "D", "branch": "主世界",
     "reward": "minecraft:torch", "reward_count": 4, "text": "击败 2 只骷髅；任意维度击杀均计数，建议在主世界夜间巡查。"},
    {"title": "巡查蜘蛛巢", "entity": "minecraft:spider", "count": 2, "rank": "D", "branch": "主世界",
     "reward": "minecraft:string", "reward_count": 2, "text": "击败 2 只蜘蛛；任意维度击杀均计数，建议在主世界洞穴或夜间巡查。"},
    {"title": "处理苦力怕威胁", "entity": "minecraft:creeper", "count": 1, "rank": "C", "branch": "主世界",
     "reward": "minecraft:iron_nugget", "reward_count": 6, "text": "击败 1 只苦力怕；任意维度击杀均计数，不要求破坏或采集周边方块。"},
    {"title": "清理溺尸水道", "entity": "minecraft:drowned", "count": 2, "rank": "C", "branch": "海洋",
     "reward": "minecraft:copper_ingot", "reward_count": 2, "text": "击败 2 只溺尸；任意维度击杀均计数，建议在主世界水域巡查。"},
    {"title": "巡查海底守卫", "entity": "minecraft:guardian", "count": 2, "rank": "B", "branch": "海洋",
     "reward": "minecraft:prismarine_shard", "reward_count": 4, "text": "击败 2 只守卫；任意维度击杀均计数，建议在海底神殿巡查，不要求攻击长者守卫。"},
    {"title": "调查女巫营地", "entity": "minecraft:witch", "count": 1, "rank": "C", "branch": "主世界",
     "reward": "minecraft:glass_bottle", "reward_count": 3, "text": "击败 1 名女巫；任意维度击杀均计数，建议在主世界夜间巡查。"},
    {"title": "巡查下界烈焰人", "entity": "minecraft:blaze", "count": 2, "rank": "B", "branch": "下界",
     "reward": "minecraft:gold_nugget", "reward_count": 5, "text": "击败 2 只烈焰人；任意维度击杀均计数，建议在下界要塞巡查。"},
    {"title": "清除恶魂空域", "entity": "minecraft:ghast", "count": 1, "rank": "A", "branch": "下界",
     "reward": "minecraft:arrow", "reward_count": 12, "text": "击败 1 只恶魂；任意维度击杀均计数，建议在下界开阔区域巡查。"},
    {"title": "巡查凋灵骷髅", "entity": "minecraft:wither_skeleton", "count": 2, "rank": "B", "branch": "下界",
     "reward": "minecraft:iron_ingot", "reward_count": 2, "text": "击败 2 只凋灵骷髅；任意维度击杀均计数，建议在下界要塞巡查。"},
    {"title": "清理末地漫游者", "entity": "minecraft:enderman", "count": 2, "rank": "C", "branch": "末地",
     "reward": "minecraft:ender_pearl", "reward_count": 2, "text": "击败 2 只末影人；任意维度击杀均计数，建议在末地巡查，准备安全掩体。"},
    {"title": "回收潜影威胁", "entity": "minecraft:shulker", "count": 1, "rank": "A", "branch": "末地",
     "reward": "minecraft:chorus_fruit", "reward_count": 4, "text": "击败 1 只潜影贝；任意维度击杀均计数，建议在末地城巡查。"},
    {"title": "讨伐凋灵", "entity": "minecraft:wither", "count": 1, "rank": "SS", "branch": "Boss",
     "reward": "ic2:energy_crystal", "reward_count": 1, "text": "击败 1 只凋灵；不要求重复召唤，任意维度击杀均计数。任务不会返还灵魂沙、凋灵骷髅头或下界之星。"},
    {"title": "讨伐末影龙", "entity": "minecraft:ender_dragon", "count": 1, "rank": "SSS", "branch": "Boss",
     "reward": "ic2:advanced_circuit", "reward_count": 3, "text": "击败 1 只末影龙；不要求重复召唤，任意维度击杀均计数。任务不要求或奖励龙蛋。"},
    {"title": "冷海蜘蛛蟹巡护", "entity": "spawn:spider_crab", "count": 1, "rank": "A", "branch": "Spawn 海洋",
     "reward": "minecraft:gold_ingot", "reward_count": 2, "text": "击败 1 只 Spawn 蜘蛛蟹；任意维度击杀均计数，建议到冷海洋巡查。"},
    {"title": "暖海刺棘巡护", "entity": "spawn:barbed", "count": 1, "rank": "A", "branch": "Spawn 海洋",
     "reward": "minecraft:gold_ingot", "reward_count": 2, "text": "击败 1 只 Spawn 刺棘；任意维度击杀均计数，建议到暖海洋水下巡查。"},
    {"title": "处理僵尸猪灵", "entity": "minecraft:zombified_piglin", "count": 1, "rank": "D", "branch": "下界中立生物",
     "reward": "minecraft:iron_nugget", "reward_count": 6, "text": "击败 1 只僵尸猪灵；任意维度击杀均计数，建议在下界巡查。"},
    {"title": "处理铁傀儡", "entity": "minecraft:iron_golem", "count": 1, "rank": "B", "branch": "中立生物",
     "reward": "minecraft:iron_ingot", "reward_count": 3, "text": "击败 1 只铁傀儡；任意维度击杀均计数。可在远离村庄和队友的安全场地召唤备用目标，不要求袭击村庄。"},
    {"title": "登记猪只样本", "entity": "minecraft:pig", "count": 1, "rank": "E", "branch": "家畜样本",
     "reward": "minecraft:bread", "reward_count": 2, "text": "击败 1 只猪；任意维度击杀均计数，建议从自养家畜中选取，不要求屠宰野外动物。"},
    {"title": "登记羊只样本", "entity": "minecraft:sheep", "count": 1, "rank": "D", "branch": "家畜样本",
     "reward": "minecraft:string", "reward_count": 4, "text": "击败 1 只羊；任意维度击杀均计数，建议从自养家畜中选取。"},
    {"title": "登记鸡只样本", "entity": "minecraft:chicken", "count": 1, "rank": "E", "branch": "家畜样本",
     "reward": "minecraft:arrow", "reward_count": 4, "text": "击败 1 只鸡；任意维度击杀均计数，建议从自养家禽中选取。"},
)

HUNT_LOOT_JOBS = (
    {"title": "僵尸猪灵腐肉样本", "item": "minecraft:rotten_flesh", "rank": "D", "branch": "下界掉落",
     "reward": "minecraft:bread", "reward_count": 1,
     "text": "自愿交付 1 块腐肉。僵尸猪灵可掉落腐肉（数量受掉落与抢夺影响）；本样本任务不作为击杀任务前置。"},
    {"title": "僵尸猪灵金粒样本", "item": "minecraft:gold_nugget", "rank": "C", "branch": "下界掉落",
     "reward": "minecraft:iron_nugget", "reward_count": 4,
     "text": "自愿交付 1 枚金粒。僵尸猪灵可掉落金粒，具体数量受掉落与抢夺影响；无需为此重复召唤或击杀。"},
    {"title": "铁傀儡铁锭样本", "item": "minecraft:iron_ingot", "rank": "C", "branch": "中立生物掉落",
     "reward": "minecraft:copper_ingot", "reward_count": 1,
     "text": "自愿交付 1 块铁锭。铁傀儡被击败时掉落铁锭（常见 3–5 块）；本项不要求攻击村庄。"},
    {"title": "铁傀儡罂粟样本", "item": "minecraft:poppy", "rank": "D", "branch": "中立生物掉落",
     "reward": "minecraft:torch", "reward_count": 2,
     "text": "自愿交付 1 朵罂粟。铁傀儡有概率掉落罂粟；该掉落不保证每次出现，也不作为击杀任务前置。"},
    {"title": "猪排样本", "item": "minecraft:porkchop", "rank": "E", "branch": "家畜样本",
     "reward": "minecraft:bread", "reward_count": 1,
     "text": "自愿交付 1 块生猪排。猪被玩家击败时通常掉落 1–3 块；火焰伤害可能改变掉落物。"},
    {"title": "羊毛样本", "item": "minecraft:white_wool", "rank": "E", "branch": "家畜样本",
     "reward": "minecraft:string", "reward_count": 2,
     "text": "自愿交付 1 块白色羊毛；可剪取白羊羊毛，不必击杀羊。不同颜色羊毛不符合此样本 ID。"},
    {"title": "鸡羽样本", "item": "minecraft:feather", "rank": "E", "branch": "家畜样本",
     "reward": "minecraft:arrow", "reward_count": 2,
     "text": "自愿交付 1 根羽毛。鸡被击败时有概率掉落 0–2 根；也可从已有库存提交，不阻塞击杀任务。"},
    {"title": "下界之星自愿归档", "item": "minecraft:nether_star", "rank": "SS", "branch": "Boss 战利品", "consume": False,
     "reward": "minecraft:gold_ingot", "reward_count": 4,
     "text": "可选交付 1 枚下界之星；击败凋灵后保证掉落。此支线独立开放，不要求重复召唤，不影响凋灵击杀目标。"},
    {"title": "羊肉样本", "item": "minecraft:mutton", "rank": "E", "branch": "家畜样本",
     "reward": "minecraft:bread", "reward_count": 1,
     "text": "自愿交付 1 块生羊肉。羊被击败时会掉落羊肉；火焰伤害可能改为熟羊肉。本项独立开放。"},
    {"title": "凋灵骷髅头颅归档", "item": "minecraft:wither_skeleton_skull", "rank": "S", "branch": "稀有战利品", "consume": False,
     "reward": "minecraft:diamond", "reward_count": 2, "consume": False,
     "text": "可选持有 1 个凋灵骷髅头颅供登记；目标罕见，掉落受战斗与抢夺影响。检查持有后不扣除；无需为击杀任务重复猎取。"},
    {"title": "溺尸三叉戟归档", "item": "minecraft:trident", "rank": "S", "branch": "稀有战利品",
     "reward": "minecraft:gold_ingot", "reward_count": 3, "consume": False,
     "text": "可选持有 1 把溺尸掉落的三叉戟供登记；该武器罕见且有耐久。检查持有后不扣除，不要求与溺尸击杀任务同时完成。"},
    {"title": "兔子脚样本", "item": "minecraft:rabbit_foot", "rank": "B", "branch": "稀有战利品",
     "reward": "minecraft:emerald", "reward_count": 1,
     "text": "自愿交付 1 个兔子脚。兔子可能掉落此物，属于罕见样本；不要求杀兔才能完成任何击杀目标。"},
    {"title": "龙蛋收藏登记", "item": "minecraft:dragon_egg", "rank": "SSS", "branch": "末地珍藏", "consume": False,
     "reward": "ic2:advanced_circuit", "reward_count": 2,
     "text": "持有 1 枚龙蛋即可登记，不会扣除。龙蛋会在原版首次击败末影龙后生成；它不是每次击败都会掉落的物品，也不作为击杀任务前置。"},
    {"title": "铁马铠收藏", "item": "minecraft:iron_horse_armor", "rank": "A", "branch": "宝箱收藏", "consume": False,
     "reward": "ic2:iron_dust", "reward_count": 2,
     "text": "持有 1 件铁马铠即可登记，不会扣除。它是探索宝箱中的稀有收藏，不是击杀马匹所得。"},
    {"title": "金马铠收藏", "item": "minecraft:golden_horse_armor", "rank": "S", "branch": "宝箱收藏", "consume": False,
     "reward": "ic2:gold_plate", "reward_count": 2,
     "text": "持有 1 件金马铠即可登记，不会扣除。它是探索宝箱中的稀有收藏，不是击杀马匹所得。"},
    {"title": "钻石马铠收藏", "item": "minecraft:diamond_horse_armor", "rank": "SS", "branch": "宝箱收藏", "consume": False,
     "reward": "ic2:energy_crystal", "reward_count": 1,
     "text": "持有 1 件钻石马铠即可登记，不会扣除。它是更稀有的探索宝箱收藏，不是击杀马匹所得。"},
)

HUNT_JOB_COUNT = len(HUNT_JOBS)
HUNT_LOOT_COUNT = len(HUNT_LOOT_JOBS)
HUNT_SECTION_COUNT = HUNT_JOB_COUNT + HUNT_LOOT_COUNT
NEW_JOINT_QUEST_COUNT = 187
HUNT_ENTITY_IDS = {job["entity"] for job in HUNT_JOBS}

SPAWN_INDUSTRY_JOBS = (
    {"title": "击败渡渡鸟", "kind": "kill", "entity": "spawn:dodo", "count": 1,
     "rank": "C", "branch": "Spawn 动物", "icon": "spawn:dodo_spawn_egg",
     "text": "击败 1 只 Spawn 渡渡鸟即可记录。队伍共享进度，成员可分别领取一次奖励。",
     "reward": "minecraft:bread", "reward_count": 3},
    {"title": "渡渡鸟肉样本", "kind": "item", "item": "spawn:dodo", "count": 1,
     "rank": "D", "branch": "Spawn 物种样本", "icon": "spawn:dodo",
     "text": "提交并消耗 1 份 Spawn 渡渡鸟肉；该物品来自已注册的 Spawn 渡渡鸟掉落表。此项独立开放。",
     "reward": "minecraft:torch", "reward_count": 4},
    {"title": "击败鲱鱼", "kind": "kill", "entity": "spawn:herring", "count": 1,
     "rank": "B", "branch": "Spawn 海洋动物", "icon": "spawn:herring_spawn_egg",
     "text": "击败 1 条 Spawn 鲱鱼即可记录；该海洋生物有现成的 Forge 生物群系生成数据。队伍共享进度，成员可分别领奖。",
     "reward": "minecraft:copper_ingot", "reward_count": 3},
    {"title": "鲱鱼肉样本", "kind": "item", "item": "spawn:herring", "count": 1,
     "rank": "C", "branch": "Spawn 海洋样本", "icon": "spawn:herring",
     "text": "提交并消耗 1 份 Spawn 鲱鱼肉；其生物掉落表将该物品作为基础肉类掉落。此项不依赖鲱鱼击杀工单。",
     "reward": "minecraft:arrow", "reward_count": 6},
    {"title": "离心工位控制芯片", "kind": "item", "item": "ic2:advanced_circuit", "count": 2,
     "rank": "A", "branch": "IC2 制造", "icon": "ic2:advanced_circuit",
     "text": "提交并消耗 2 个 IC2 高级电路；按 JEI 中当前整合包配方制作。",
     "reward": "ic2:energy_crystal", "reward_count": 1},
    {"title": "高储能晶体交付", "kind": "item", "item": "ic2:energy_crystal", "count": 1,
     "rank": "S", "branch": "IC2 制造", "icon": "ic2:energy_crystal",
     "text": "提交并消耗 1 个 IC2 能量水晶；请先留出生产线所需库存，配方以 JEI 为准。",
     "reward": "ic2:advanced_re_battery", "reward_count": 1},
    {"title": "精密机构批次验收", "kind": "item", "item": "create:precision_mechanism", "count": 2,
     "rank": "A", "branch": "Create 制造", "icon": "create:precision_mechanism",
     "text": "提交并消耗 2 个 Create 精密构件；按 JEI 完成序列装配。",
     "reward": "create:brass_sheet", "reward_count": 4},
    {"title": "黄铜精加工片交付", "kind": "item", "item": "create:brass_sheet", "count": 8,
     "rank": "B", "branch": "Create 制造", "icon": "create:brass_sheet",
     "text": "提交并消耗 8 张 Create 黄铜板；按 JEI 生产并为后续工位留料。",
     "reward": "create:electron_tube", "reward_count": 2},
    {"title": "击败海牛", "kind": "kill", "entity": "spawn:sea_cow", "count": 1,
     "rank": "B", "branch": "Spawn 海洋动物", "icon": "spawn:sea_cow_spawn_egg",
     "text": "记录 1 只 Spawn 海牛；该动物有 Forge 生物群系生成配置。", "reward": "minecraft:gold_ingot", "reward_count": 2},
    {"title": "击败海豹", "kind": "kill", "entity": "spawn:seal", "count": 1,
     "rank": "B", "branch": "Spawn 海洋动物", "icon": "spawn:seal_spawn_egg",
     "text": "记录 1 只 Spawn 海豹；该动物有 Forge 生物群系生成配置。", "reward": "minecraft:iron_ingot", "reward_count": 3},
    {"title": "击败琵琶鱼", "kind": "kill", "entity": "spawn:angler_fish", "count": 1,
     "rank": "A", "branch": "Spawn 海洋动物", "icon": "spawn:angler_fish_spawn_egg",
     "text": "记录 1 只 Spawn 琵琶鱼；水下巡护时留意其专属鱼种。", "reward": "minecraft:gold_ingot", "reward_count": 3},
    {"title": "击败蓝鱼", "kind": "kill", "entity": "spawn:bluefish", "count": 1,
     "rank": "A", "branch": "Spawn 海洋动物", "icon": "spawn:bluefish_spawn_egg",
     "text": "记录 1 只 Spawn 蓝鱼；水下巡护可寻找该海洋生物。", "reward": "minecraft:prismarine_shard", "reward_count": 4},
    {"title": "羽毛样本", "kind": "item", "item": "minecraft:feather", "count": 2,
     "rank": "C", "branch": "Spawn 物种样本", "icon": "minecraft:feather",
     "text": "提交并消耗 2 根羽毛；羽毛可来自多种来源，不要求由特定动物掉落。", "reward": "minecraft:arrow", "reward_count": 4},
    {"title": "琵琶鱼桶样本", "kind": "item", "item": "spawn:angler_fish_bucket", "count": 1,
     "rank": "A", "branch": "Spawn 物种样本", "icon": "spawn:angler_fish_bucket",
     "text": "提交并消耗 1 桶 Spawn 琵琶鱼；水桶可捕获该鱼种。", "reward": "minecraft:gold_ingot", "reward_count": 2},
    {"title": "蓝鱼肉样本", "kind": "item", "item": "spawn:bluefish", "count": 1,
     "rank": "B", "branch": "Spawn 物种样本", "icon": "spawn:bluefish",
     "text": "提交并消耗 1 份 Spawn 蓝鱼；以其生物掉落及烹饪配方为样本来源。", "reward": "minecraft:copper_ingot", "reward_count": 3},
    {"title": "鲱鱼桶样本", "kind": "item", "item": "spawn:herring_bucket", "count": 1,
     "rank": "B", "branch": "Spawn 物种样本", "icon": "spawn:herring_bucket",
     "text": "提交并消耗 1 桶 Spawn 鲱鱼；水桶捕获后交付。", "reward": "minecraft:iron_ingot", "reward_count": 2},
    {"title": "铅洗后粉交付", "kind": "item", "item": "ic2:purified_lead", "count": 2,
     "rank": "C", "branch": "IC2 材料加工", "icon": "ic2:purified_lead",
     "text": "提交并消耗 2 份洗后铅粉；本节点接在含杂质铅粉之后，再进入离心制取净粉。", "reward": "ic2:lead_dust", "reward_count": 1},
    {"title": "铅成品粉交付", "kind": "item", "item": "ic2:lead_dust", "count": 2,
     "rank": "B", "branch": "IC2 材料加工", "icon": "ic2:lead_dust",
     "text": "提交并消耗 2 份铅净粉；按 JEI 使用离心工位加工。", "reward": "ic2:insulated_copper_cable", "reward_count": 2},
    {"title": "含杂质铅粉交付", "kind": "item", "item": "ic2newera:impure_lead_dust", "count": 2,
     "rank": "D", "branch": "IC2 材料加工", "icon": "ic2newera:impure_lead_dust",
     "text": "提交并消耗 2 份含杂质铅粉；它是铅矿粉碎后的中间物，按获批路线继续水洗或离心。", "reward": "ic2:purified_lead", "reward_count": 1},
    {"title": "空燃料棒管坯", "kind": "item", "item": "ic2:fuel_rod", "count": 2,
     "rank": "A", "branch": "IC2 核燃料准备", "icon": "ic2:fuel_rod",
     "text": "提交并消耗 2 根 IC2 空燃料棒管坯；此阶段尚未装入铀燃料。", "reward": "ic2:copper_plate", "reward_count": 1},
    {"title": "铀燃料棒成品", "kind": "item", "item": "ic2:uranium_fuel_rod", "count": 1,
     "rank": "SS", "branch": "IC2 核燃料", "icon": "ic2:uranium_fuel_rod",
     "text": "提交并消耗 1 根 IC2 铀燃料棒成品（ic2:uranium_fuel_rod），不是空棒；按 JEI 原生核燃料路线制作。", "reward": "ic2:advanced_circuit", "reward_count": 1},
    {"title": "合金批次交付", "kind": "item", "item": "ic2:alloy", "count": 2,
     "rank": "S", "branch": "IC2 合金炉", "icon": "ic2:alloy",
     "text": "提交并消耗 2 个 IC2 合金锭；按 JEI 在合金炉生产。", "reward": "ic2:advanced_circuit", "reward_count": 1},
    {"title": "高级可充电电池交付", "kind": "item", "item": "ic2:advanced_re_battery", "count": 1,
     "rank": "SS", "branch": "IC2 储能", "icon": "ic2:advanced_re_battery",
     "text": "提交并消耗 1 个高级可充电电池；按 JEI 配方制造。", "reward": "ic2:energy_crystal", "reward_count": 1},
    {"title": "精炼铜粉交付", "kind": "item", "item": "ic2:copper_dust", "count": 3,
     "rank": "B", "branch": "IC2 材料加工", "icon": "ic2:copper_dust",
     "text": "提交并消耗 3 份铜粉用于后续冶炼。", "reward": "ic2:copper_plate", "reward_count": 2},
    {"title": "铜板绝缘工件", "kind": "item", "item": "ic2:copper_plate", "count": 2,
     "rank": "C", "branch": "IC2 材料制造", "icon": "ic2:copper_plate",
     "text": "提交并消耗 2 张 IC2 铜板；按 JEI 的压板配方制作，作为电力组件前置材料。", "reward": "ic2:insulated_copper_cable", "reward_count": 2},
    {"title": "转子动力轴", "kind": "item", "item": "create:shaft", "count": 8,
     "rank": "D", "branch": "Create 动力", "icon": "create:shaft",
     "text": "提交并消耗 8 根传动轴；按 JEI 备齐动力线材料。", "reward": "create:cogwheel", "reward_count": 4},
    {"title": "机械动力齿轮交付", "kind": "item", "item": "create:cogwheel", "count": 4,
     "rank": "C", "branch": "Create 传动", "icon": "create:cogwheel",
     "text": "提交并消耗 4 个齿轮，用于传动与设备装配。", "reward": "create:shaft", "reward_count": 4},
    {"title": "铜板加工交付", "kind": "item", "item": "create:copper_sheet", "count": 4,
     "rank": "C", "branch": "Create 辊压", "icon": "create:copper_sheet",
     "text": "提交并消耗 4 张铜板；用机械辊压机加工。", "reward": "create:andesite_alloy", "reward_count": 2},
    {"title": "传送带联轴批次", "kind": "item", "item": "create:belt_connector", "count": 2,
     "rank": "B", "branch": "Create 输送", "icon": "create:belt_connector",
     "text": "提交并消耗 2 个传送带连接件，另留备件给输送线。", "reward": "create:shaft", "reward_count": 4},
    {"title": "置物台工位交付", "kind": "item", "item": "create:depot", "count": 1,
     "rank": "B", "branch": "Create 加工", "icon": "create:depot",
     "text": "提交并消耗 1 个置物台；按 JEI 制作并给辊压工位留备件。", "reward": "create:andesite_alloy", "reward_count": 2},
    {"title": "流体缓存罐交付", "kind": "item", "item": "create:fluid_tank", "count": 2,
     "rank": "A", "branch": "Create 流体", "icon": "create:fluid_tank",
     "text": "提交并消耗 2 个流体储罐，为流体加工线准备缓存。", "reward": "create:fluid_pipe", "reward_count": 4},
    {"title": "机械泵站交付", "kind": "item", "item": "create:mechanical_pump", "count": 1,
     "rank": "A", "branch": "Create 流体", "icon": "create:mechanical_pump",
     "text": "提交并消耗 1 台机械泵；装配前核对转轴和管路。", "reward": "create:fluid_pipe", "reward_count": 4},
    {"title": "风扇叶片零件交付", "kind": "item", "item": "create:propeller", "count": 1,
     "rank": "B", "branch": "Create 水洗零件", "icon": "create:propeller",
     "text": "提交并消耗 1 个 Create 原厂风扇叶片零件；这是鼓风机的组成部件，不是整台设备。", "reward": "create:andesite_funnel", "reward_count": 2},
    {"title": "机械手装配工位", "kind": "item", "item": "create:deployer", "count": 1,
     "rank": "A", "branch": "Create 装配", "icon": "create:deployer",
     "text": "提交并消耗 1 台机械手，用于序列装配工位。", "reward": "create:precision_mechanism", "reward_count": 1},
)
SPAWN_INDUSTRY_COUNT = len(SPAWN_INDUSTRY_JOBS)
MACHINE_ACCEPTANCE_JOBS = (
    {"title": "打粉机工位验收", "item": "ic2:macerator", "consume": False, "rank": "B", "branch": "IC2 预处理",
     "text": "持有 1 台 IC2 打粉机即可验收，不会收走机器。它用于粉碎原料；粉末交付另走消耗型工单。",
     "reward": "ic2:insulated_copper_cable", "reward_count": 4},
    {"title": "离心机工位验收", "item": "ic2:centrifuge", "consume": False, "rank": "A", "branch": "IC2 分离",
     "text": "持有 1 台 IC2 离心机即可验收，不会收走机器。先完成对应粉料准备，再按 JEI 操作。",
     "reward": "ic2:lead_dust", "reward_count": 3},
    {"title": "打罐机工位验收", "item": "ic2:canner", "consume": False, "rank": "A", "branch": "IC2 核燃料准备",
     "text": "持有 1 台 IC2 打罐机即可验收，不会收走机器；后续按原生核燃料配方装填燃料棒。",
     "reward": "ic2:fuel_rod", "reward_count": 2},
    {"title": "低压储能工位验收", "item": "ic2:batbox", "consume": False, "rank": "B", "branch": "IC2 储能",
     "text": "持有 1 台 IC2 低压储电箱即可验收，不会收走机器。接线时核对电压与输出方向。",
     "reward": "ic2:tin_plate", "reward_count": 4},
    {"title": "高级电炉工位验收", "item": "ic2newera:advanced_electric_furnace", "consume": False, "rank": "SS", "branch": "新纪元高温加工",
     "text": "持有 1 台新纪元高级电炉即可验收，不会收走机器；按批准路线承担钛、钨等高温加工。",
     "reward": "ic2:purified_lead", "reward_count": 2},
    {"title": "机械压制工位验收", "item": "create:mechanical_press", "consume": False, "rank": "B", "branch": "Create 成形",
     "text": "持有 1 台 Create 机械压机即可验收，不会收走机器；按 JEI 将金属锭或板材送入压制工序。",
     "reward": "ic2:copper_plate", "reward_count": 4},
    {"title": "动力搅拌工位验收", "item": "create:mechanical_mixer", "consume": False, "rank": "A", "branch": "Create 搅拌",
     "text": "持有 1 台 Create 动力搅拌器即可验收，不会收走机器；为配方提供转轴动力。",
     "reward": "create:andesite_alloy", "reward_count": 4},
    {"title": "新纪元合金炉工位验收", "item": "ic2newera:alloy_smelter", "consume": False, "rank": "SS", "branch": "新纪元合金",
     "text": "持有 1 台新纪元合金炉即可验收，不会收走机器；合金配料按批准路线在该设备中生产。",
     "reward": "ic2:advanced_circuit", "reward_count": 2},
)
HUNT_ICONS = {
    "minecraft:zombie": "minecraft:zombie_spawn_egg",
    "minecraft:skeleton": "minecraft:skeleton_spawn_egg",
    "minecraft:spider": "minecraft:spider_spawn_egg",
    "minecraft:creeper": "minecraft:creeper_spawn_egg",
    "minecraft:drowned": "minecraft:drowned_spawn_egg",
    "minecraft:guardian": "minecraft:guardian_spawn_egg",
    "minecraft:witch": "minecraft:witch_spawn_egg",
    "minecraft:blaze": "minecraft:blaze_spawn_egg",
    "minecraft:ghast": "minecraft:ghast_spawn_egg",
    "minecraft:wither_skeleton": "minecraft:wither_skeleton_spawn_egg",
    "minecraft:enderman": "minecraft:enderman_spawn_egg",
    "minecraft:shulker": "minecraft:shulker_spawn_egg",
    "minecraft:wither": "minecraft:nether_star",
    "minecraft:ender_dragon": "minecraft:dragon_head",
    "spawn:spider_crab": "spawn:spider_crab_spawn_egg",
    "spawn:barbed": "spawn:barbed_spawn_egg",
    "minecraft:zombified_piglin": "minecraft:zombified_piglin_spawn_egg",
    "minecraft:iron_golem": "minecraft:iron_golem_spawn_egg",
    "minecraft:pig": "minecraft:pig_spawn_egg",
    "minecraft:sheep": "minecraft:sheep_spawn_egg",
    "minecraft:chicken": "minecraft:chicken_spawn_egg",
}


def remove_ecology_jobs(chapter):
    """Remove only the five exact gear-pack species tasks; keep other IDs."""
    found = {}
    for quest in chapter.get("quests", []):
        tasks = quest.get("tasks", [])
        if len(tasks) != 1:
            continue
        task = tasks[0]
        kind = task.get("type")
        value = task.get("item") if kind == "item" else task.get("advancement") if kind == "advancement" else None
        target = (kind, value)
        if target not in ECOLOGY_REMOVAL_TARGETS:
            continue
        if target in found:
            raise ValueError(f"Duplicate ecology removal target: {target}")
        actual = task.get("item") if kind == "item" else task.get("advancement")
        if actual != value:
            raise ValueError(f"Ecology removal target changed: {quest.get('id')} {task}")
        found[target] = quest
    if set(found) != ECOLOGY_REMOVAL_TARGETS:
        raise ValueError(f"Expected exact ecology removal set; found {sorted(found)}")
    removed_ids = {quest["id"] for quest in found.values()}
    chapter["quests"] = [q for q in chapter["quests"] if q.get("id") not in removed_ids]
    # Preserve surviving task/object IDs; only repair the chain around removed
    # nodes so the cow, Spawn sample, paper and phone jobs remain reachable.
    for quest in chapter["quests"]:
        quest.pop("dependencies", None)
    for current, previous in zip(chapter["quests"][1:], chapter["quests"][:-1]):
        current["dependencies"] = [previous["id"]]
    return sorted(removed_ids)


def filtered_field_wildlife(rows):
    """Return retained route entries with original positions, guarding scope."""
    if len(rows) != 12:
        raise ValueError("Expected the original twelve field-wildlife delivery jobs")
    items = [row[1] for row in rows]
    found = {item for item in items if item in FIELD_WILDLIFE_REMOVALS}
    if found != FIELD_WILDLIFE_REMOVALS:
        raise ValueError(f"Unexpected field-wildlife removal set: {sorted(found)}")
    kept = [(index, row) for index, row in enumerate(rows)
            if row[1] not in FIELD_WILDLIFE_REMOVALS]
    expected_kept = {"minecraft:nautilus_shell", "minecraft:cod_bucket",
                     "spawn:angler_fish_bucket", "spawn:tuna_chunk", "spawn:seahorse_bucket"}
    if {row[1] for _, row in kept} != expected_kept:
        raise ValueError("Field wildlife must retain only the existing vanilla and Spawn samples")
    return kept


# Original field-wildlife route indices form a simple two-branch chain.
# Keep the surviving item/reward IDs while reconnecting vanilla/Spawn tasks.
FIELD_WILDLIFE_PARENTS = {7: (), 8: (), 9: (7, 8), 10: (9,), 11: (10,)}

