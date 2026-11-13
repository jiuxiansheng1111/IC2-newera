"""Generate New Era's handbook with AutoFTBQ and compatible Create pack layouts."""
import argparse
import hashlib
import io
import sys
import tempfile
from pathlib import Path
from zipfile import ZipFile

parser = argparse.ArgumentParser()
parser.add_argument("--autoftbq", required=True, type=Path, help="Bluelgin/AutoFTBQ-v2 checkout")
parser.add_argument("--create-template", required=True, type=Path, help="Above-and-Beyond config/ftbquests/quests")
parser.add_argument("--delight-template", type=Path, help="Player-provided Create-Delight-Remake.zip, for structural reference")
args = parser.parse_args()
sys.path.insert(0, str(args.autoftbq.resolve()))
from quest_writer import write_quest_book, MARKER_CHAPTER_ID
from snbt_parser import parse_snbt, to_snbt, _SNBTByte, _SNBTFloat, _SNBTLong

template = parse_snbt((args.create_template / "chapters/stage_1.snbt").read_text(encoding="utf-8"))
theme = parse_snbt((args.create_template / "data.snbt").read_text(encoding="utf-8"))
cadence = 2.0
if args.delight_template:
    with ZipFile(args.delight_template) as wrapper, ZipFile(io.BytesIO(wrapper.read("modpack.mrpack"))) as reference:
        prefix = "overrides/config/ftbquests/quests/"
        engineer = parse_snbt(reference.read(prefix+"chapters/Junior_Engineer.snbt").decode("utf-8"))
        storage = parse_snbt(reference.read(prefix+"chapters/AE2_Basics.snbt").decode("utf-8"))
        groups = parse_snbt(reference.read(prefix+"chapter_groups.snbt").decode("utf-8"))
        assert len(groups["chapter_groups"]) > 1 and storage["quest_links"] and engineer["quests"]
chapters, all_quests = [], {}
def chapter(key, title, icon):
    chapters.append(dict(key=key, title=title, icon=icon, quests=[], _group="guide"))

def quest(key, title, objective, icon, first, second, gifts, parents=(), lane=0):
    # Descriptions are newly written for this pack. First paragraph tells the current step;
    # the second points to the next station. A tag on a material is validated by an advancement.
    chapter = chapters[-1]
    q = dict(id=key, title=title, icon=icon, x=cadence*len(chapter["quests"]), y=lane*cadence,
             subtitle=first.split("。")[0], description=[first,"",second],
             dependencies=[], shape="gear" if len(gifts)>1 else template["quests"][0].get("shape","square"),
             tasks=[dict(type=typ,target=value,count=count) for typ,value,count in objective],
             rewards=[dict(type="item",target=value,count=count) for value,count in gifts],parents=parents)
    chapter["quests"].append(q)
    all_quests[key]=q

def item(id,count=1): return [("item",id,count)]
def event(id): return [("advancement","ic2newera:newera/"+id,1)]
def one(id): return [(id,1)]
chapter("ground","黄铜工牌 · 矿场起步","ic2:forge_hammer")
quest("welcome","报到领工牌",[("checkmark","",1)],"ic2newera:field_notice_board",
      "点勾确认报到，再点右侧公告栏图标，领走自己的设备。","先找铁和铅来建立低压电源。公告栏每分钟约用 100 EU，通电以后右击即可接委托。",
      one("ic2newera:field_notice_board"))
quest("iron","点亮第一台设备",item("minecraft:iron_ingot",8),"ic2:generator",
      "采铁并烧出八块铁锭，领取 IC2 火力发电机。","放入可燃物，接绝缘铜缆给公告栏供电。背包里的目标材料只作检测，可以继续使用。",
      [("ic2:generator",1),("ic2:insulated_copper_cable",4)],("welcome",))
quest("lead","在岩层里找铅",event("lead_sample"),"ic2:lead_ore",
      "在主世界地下找铅。通常在 Y=72 以下，Y=-16 以下还有额外一批矿脉。拿到粗铅、铅矿石或深层铅矿石均可。","领打粉机接电，把一份铅矿石或粗铅放入输入槽，留好杂质粉。",
      [("ic2:macerator",1),("minecraft:torch",12)],("iron",))
quest("grind","先把矿石打碎",item("ic2newera:impure_lead_dust",2),"ic2newera:impure_lead_dust",
      "在 IC2 打粉机或 Create 粉碎轮加工一份铅矿石或粗铅，拿到两份含杂质的铅粉。","领取热能离心机。接足供电，等机器升温；下一步可先水洗，也可以直接离心。",
      one("ic2:centrifuge"),("lead",))
quest("wash","多走一道水洗",item("ic2:purified_lead"),"create:encased_fan",
      "让转动的鼓风机吹过水，水流后放入杂质铅粉，每份得到一份洗后铅。","每份洗后铅离心得三份铅粉。没有风扇时，每份杂质粉直接离心得一份，主线也能推进。",
      [("create:shaft",4)],("grind",),1)
quest("spin","离心出粉",item("ic2:lead_dust"),"ic2:centrifuge",
      "向热能离心机供电，放入杂质铅粉或洗后铅，加热完成后取出铅粉。","领电炉，烧铅粉得到铅锭。用洗后路线，一份原矿一共能产六份成品粉。",
      one("ic2:electric_furnace"),("grind",))
quest("metal","第一批成品铅",item("ic2:lead_ingot",2),"ic2:lead_ingot",
      "用电炉或普通炉烧出两块铅锭。","把储电箱接到生产线，稳定低压供电。工业加工章与个人委托现在都能继续。",
      [("ic2:batbox",1),("ic2:wrench",1)],("spin",))
chapter("works","传动工坊 · 合金与电子","create:cogwheel")
quest("kinetic","传动从一根轴开始",item("create:andesite_alloy",2),"create:cogwheel",
      "在工作台合成安山合金。用水车和传动轴带起机械设备。","领取齿轮，搭一套鼓风机水洗装置。水洗量产后再准备高温合金。",
      [("create:cogwheel",2),("create:shaft",4)],("metal",))
quest("smelter","开合金炉",item("ic2newera:alloy_smelter"),"ic2newera:alloy_smelter",
      "按当前配方制作新纪元合金炉，接入 EU。准备普通铁粉和 IC2 煤粉。","按炉内的批准配方配料得到钢粉，再熔炼为钢锭。扳手与电源可以沿用矿场设备。",
      one("ic2:forge_hammer"),("kinetic",))
quest("steel","工坊里的第一炉钢",item("ic2newera:tempered_steel_ingot",2),"ic2newera:tempered_steel_ingot",
      "制得两块低碳钢锭。钢粉合金工序由合金炉完成，再熔炼为成品。","领取低碳钢胸甲和剑，探索下一处矿点。之后可分头做电路板和动力转换。",
      [("ic2newera:tempered_steel_chestplate",1),("ic2newera:tempered_steel_sword",1)],("smelter",))
quest("boards","刻上第一块电路板",item("ic2newera:pcb"),"ic2newera:pcb",
      "从下界石英开始准备硅和高纯硅，按电子材料加工机的配方制得 PCB。","把电路板加工成集成电路，继续制作工业手机及控制硬件。",
      [("ic2:insulated_copper_cable",8)],("steel",))
quest("circuit","接通控制电路",item("ic2newera:integrated_circuit"),"ic2newera:integrated_circuit",
      "取得一枚新纪元集成电路。电子材料加工机的输入、输出与温度在机器界面可查。","制作工业手机并充电。可以去实地取景，也可以给公告栏任务规划交付路线。",
      one("ic2newera:tempered_steel_axe"),("boards",))
quest("motor","让齿轮吃上电",item("ic2newera:eu_motor"),"ic2newera:eu_motor",
      "取得一台初级电动机，将 IC2 供电接入，再将轴面接到 Create 传动设备。","反方向的能源转换用初级机械发电机；高档转换设备需要对应电压和更高级的材料。",
      [("create:cogwheel",4)],("steel",),1)
chapter("field","野外工单 · 同伴与拍摄","ic2newera:industrial_phone")
quest("boardjob","第一次交班",event("contract_completed"),"ic2newera:field_notice_board",
      "在有电的公告栏接取委托，查看材料或指定生物，完成后回原公告栏点击领取。","新委托每半小时轮换，已接委托可继续。领取机械狗，把它带到矿区。",
      one("ic2newera:mechanical_dog"),("iron",))
quest("phone","口袋里的调度室",item("ic2newera:industrial_phone"),"ic2newera:industrial_phone",
      "制作一部工业手机并给它充电。手机里的远征手记、图库和设备清单有各自入口。","领取一台剩余 1/5 耐久的机器人娃娃，放置时留出空间；为机器人准备电池。最后一次损坏后将无法再部署。",
      [("ic2newera:anon_robot_doll",1),("ic2newera:robot_battery",1)],("circuit",))
quest("shutter","快门落下",event("phone_photo"),"ic2newera:industrial_phone",
      "打开手机相机，选择适合的原生视角，拍照并将一张照片保存到服务器图库。","图库会记录坐标。把目的地加入任务路线，出发后可在导航地图查看每一步。",
      one("ic2newera:mechanical_cat"),("phone",))
quest("arrival","重回取景地",event("route_arrival"),"minecraft:compass",
      "打开生态相册或手机图库，为一处拍摄点开启导航，沿路线抵达。","用任务路线安排多站。未加载的地形会在行进中补算，跨维度时先前往对应世界。",
      [("minecraft:golden_carrot",6)],("shutter",))
quest("museum","观察员的原片",event("album_published"),"exposure:photograph",
      "拍摄生机遍布的鮟鱇鱼、蛤蜊或金枪鱼。在符合条件的相片中选一张公开收录。","同伴可以查看原片，用拍摄点或物种发现点重新造访这片海域。",
      [("minecraft:nautilus_shell",2)],("shutter",),1)
chapter("space","远征地图 · 月球与火星","ad_astra:tier_1_rocket")
quest("prep","预备起飞",item("ad_astra:tier_1_rocket"),"ad_astra:tier_1_rocket",
      "准备 Ad Astra 一级火箭、燃料、能用的氧气和完整太空服。用工业手机登记地球备货箱，核对清单。","地球发射后选择月球，控制着陆器触地，再打开远征手记确认落点。",
      [("minecraft:baked_potato",12),("minecraft:torch",16)],("circuit",))
quest("landing","落点已标记",event("moon_landing"),"ad_astra:space_suit",
      "真实乘坐火箭前往月球并着陆，在手机远征手记中确认本队落点。","到月面荒原的地下 Y=-48～48 寻找钒、钴和钼，记下补给箱与返程地点。",
      [("minecraft:golden_carrot",8)],("prep",))
quest("lunar_metals","月下的三种金属",event("lunar_samples"),"ic2newera:vanadium_ore",
      "分别取得月球荒原中的钒、钴、钼样本。每种可用原矿或粗矿，已记录的样本无需同时留在背包里。","把材料运回工坊，制出钒锭，再准备钒钢及更远航程所需的二级火箭。",
      [("ic2newera:vanadium_steel_pickaxe",1),("ic2newera:vanadium_steel_boots",1)],("landing",))
quest("mars","去火星找铌和钽",event("martian_samples"),"ic2newera:niobium_ore",
      "用 Ad Astra 二级或更高火箭抵达火星。在火星荒原、峡谷或极地区地下 Y=-48～48，分别取得铌、钽矿石或粗矿。","返航加工矿物，继续做铌钛合金。同时留意能在火星地表找到的共振矿簇。",
      [("ic2newera:niobium_titanium_helmet",1)],("lunar_metals",))
quest("signal","火星发回的回声",item("ic2newera:resonance_shard"),"ic2newera:resonance_shard",
      "探索火星可生成共振矿簇的地表，取得共振碎晶。","连同铌钛材料运回工坊。可以用低压公告栏接取高阶委托，再把航点分享给下一队。",
      [("ic2newera:niobium_titanium_sword",1)],("mars",))
chapter("hangar","装配间 · 飞行与装备","immersive_aircraft:gyrodyne")
quest("bench","打开装备工位",item("tacz:gun_smith_table"),"tacz:gun_smith_table",
      "制作 TaCZ 装备工位，打开工位查看新纪元添加的两项勘探装备配方。","也可以在 JEI 查看 Create 序列组装配方，用已有传动线与工业部件加工同款装备。",
      [("create:andesite_alloy",3)],("circuit",))
quest("loadout","领用勘探装备",event("field_equipment"),"tacz:gun_smith_table",
      "在 TaCZ 工位或 Create 序列组装线上制得 MK18 或 AI AWP，取到背包即可登记。","领取返回营地用的补给，把出发位置加入手机路线。两种装备首次制成时均为空仓。",
      [("minecraft:cooked_beef",4)],("bench",))
quest("plane","测绘机出库",event("survey_aircraft"),"immersive_aircraft:gyrodyne",
      "按 JEI 中的序列组装配方从飞机机身开始，加工成一架固定旋翼机，再将成品收入背包。","将起降点存到手机，再去未勘探地区拍摄。前往月球仍需 Ad Astra 火箭。",
      [("ic2newera:tempered_steel_boots",1),("minecraft:compass",1)],("kinetic",),1)

chapter("foundry","铸造车间 · 从火泥到工位","tconstruct:seared_melter")
quest("grout","炉砖从河岸开始",item("tconstruct:grout",4),"tconstruct:grout",
      "收集沙子、沙砾和黏土，按 JEI 组合为至少四份匠魂火泥。","把火泥烧成焦黑砖。工坊里已有的电炉也能承担这道烧制工序。",
      [("minecraft:clay_ball",4)],("iron",))
quest("melter","一座能出料的小炉",item("tconstruct:seared_heater")+item("tconstruct:seared_melter"),"tconstruct:seared_melter",
      "备齐焦黑加热器和焦黑熔化炉，把熔化炉搭在加热器上。","侧面安装浇注口，下方留一格放铸造台。初期就能试浇，再决定何时扩为大冶炼炉。",
      one("tconstruct:seared_faucet"),("grout",))
quest("cast","铸台接上第一炉",item("tconstruct:seared_table")+item("tconstruct:ingot_cast"),"tconstruct:ingot_cast",
      "取得铸造台和可重复使用的金锭模具，留在浇注口下方试浇一锭。","把工位旁的成品箱登记成手机路标，供机械工坊集中取料。",
      [("minecraft:gold_ingot",2)],("melter",))
quest("toolwork","从木板模到工具台",item("tconstruct:part_builder")+item("tconstruct:tinker_station"),"tconstruct:tinker_station",
      "备齐匠魂部件制造台和工具装配台。用模具制部件，再在工具台装配。","检查你的手斧是否匹配农夫乐事砧板上显示的工具条件，再交给机器人复做。",
      [("tconstruct:pattern",4)],("grout",),1)
quest("shared_shop","铸造与传动交班",item("create:mechanical_press")+item("tconstruct:seared_basin"),"create:mechanical_press",
      "备齐机械动力辊压机和匠魂浇铸盆，预留独立的进料和成品位置。","把两条线的成品送进库存，给下一章的 AE2 存储准备空间。",
      [("create:depot",1),("create:shaft",3)],("cast","kinetic"))

chapter("storage","仓库调度 · AE2 与工业工坊","ae2:terminal")
quest("certus","给水晶留一个生长面",item("ae2:certus_quartz_crystal",4),"ae2:certus_quartz_crystal",
      "在陨石中找到可以结晶的赛特斯石英母岩，收集四枚水晶，保留母岩与周围生长空间。","准备充能器，通电后尝试让普通赛特斯石英水晶充能。",
      [("minecraft:redstone",8)],("kinetic",))
quest("fluix","让三种材料结晶",item("ae2:charger")+item("ae2:fluix_crystal",2),"ae2:fluix_crystal",
      "备好充能器，按 AE2 的水中反应配方取得两枚福鲁伊克斯水晶。","检查 JEI 中福鲁伊克斯与处理器的用途，备料时从现有石英线取料。",
      [("minecraft:quartz",4)],("certus",))
quest("processors","压印一枚真正的处理器",item("ae2:inscriber")+item("ae2:logic_processor"),"ae2:logic_processor",
      "取得压印器和一枚逻辑处理器。模具与印好的电路分别放入配方所示槽位。","将处理器用于存储元件。在物流仓库留空位以便接入驱动器和终端。",
      [("minecraft:gold_ingot",3)],("fluix",))
quest("warehouse","让终端读到第一格货",item("ae2:drive")+item("ae2:item_storage_cell_1k")+item("ae2:terminal"),"ae2:drive",
      "备齐 ME 驱动器、1k 物品存储元件和 ME 终端。连上 AE2 可用的电源与线缆，在终端存取一批工坊材料。","给新纪元生产线保留真实进料箱，再决定哪些成品交给 ME 仓库保存。",
      [("create:andesite_alloy",4),("minecraft:chest",2)],("processors",))
quest("pattern","一个样板对一台装配器",item("ae2:blank_pattern")+item("ae2:pattern_provider")+item("ae2:molecular_assembler"),"ae2:pattern_provider",
      "备齐空白样板、样板供应器和分子装配室，选择一条简单工作台配方编码。","把样板插入供应器，配齐合成 CPU 和原料后从终端下单。工厂原有矿物加工线继续按机器配方运转。",
      [("ae2:blank_pattern",2)],("warehouse","boards"),1)

chapter("kitchen","食堂与后勤 · 两套厨房一条补给线","farmersdelight:cooking_pot")
quest("prep_table","砧板上的第一份备料",item("farmersdelight:cutting_board")+item("farmersdelight:iron_knife"),"farmersdelight:cutting_board",
      "制作农夫乐事砧板与铁刀。在 JEI 查一份实际切配配方，给砧板旁留一个收料位置。","机器人也可使用符合该配方的手持工具；留出放成品与副产物的空间。",
      [("minecraft:bowl",4)],("welcome",))
quest("hotpot","把饱食度带出基地",item("farmersdelight:cooking_pot")+item("farmersdelight:vegetable_soup"),"farmersdelight:cooking_pot",
      "在热源上放置农夫乐事烹饪锅，按配方取一碗蔬菜汤。","将食物和空碗分别存放，准备一箱品种不同的出勤口粮。",
      [("farmersdelight:rice",4),("minecraft:bowl",4)],("prep_table",))
quest("wok","换一个工位翻炒",item("kaleidoscope_cookery:pot")+item("kaleidoscope_cookery:kitchen_shovel"),"kaleidoscope_cookery:kitchen_shovel",
      "制作森罗厨房的锅和锅铲，配合合适的热源，准备进行一轮翻炒。","先按游戏配方做一份蛋炒饭，再让工坊机器人复做已支持的锅灶操作。",
      [("minecraft:egg",3)],("prep_table",),1)
quest("ration","为远征箱配两道菜",item("kaleidoscope_cookery:egg_fried_rice")+item("farmersdelight:beef_stew"),"kaleidoscope_cookery:egg_fried_rice",
      "从两套厨房各备一份熟食，取得一份蛋炒饭与一份炖牛肉。","把口粮放入地面备货箱，用手记记录实际数量。本任务只检测，口粮可以直接带走。",
      [("minecraft:golden_carrot",4)],("wok","hotpot"))

chapter("dispatch","工务处 · 轮班和每周联合订单","ic2newera:field_notice_board")
quest("dispatch_intro","读两份不同的工单",item("ic2newera:field_notice_board"),"ic2newera:field_notice_board",
      "领取个人公告栏，接通一级低压电。半小时委托与每周联合工单共用这一台屏幕。","在每周页查看两种或以上材料和附加目标。每周一北京时间零点轮换，自己的已接进度保存在当前世界。",
      [("minecraft:paper",4)],("welcome",))
quest("weekly_sign","在周单上签名",event("weekly_accepted"),"minecraft:writable_book",
      "到已通电的公告栏查看本周主题与整份报酬，再接个人联合工单。","先在原公告栏分批交付一项材料。物资、日常委托或照片目标以这张工单面板为准。",
      [("minecraft:torch",12)],("dispatch_intro",))
quest("weekly_close","完成第一次跨车间交付",event("weekly_claimed"),"minecraft:clock",
      "在原公告栏完成周单全部条件并亲自领取报酬。背包里留出所有奖励所需的空间。","下一周会有新的主题。牵涉多个工位时，将材料箱、拍摄点和交付公告栏排成一条手机任务路线。",
      [("create:brass_sheet",2)],("weekly_sign","boardjob"))

chapter("endgame","终局仓储 · 等价交换与无尽贪婪","avaritia:crystal_matrix_ingot")
quest("equivalence","把仓库的一份余料登记在案",item("projecte:philosophers_stone")+item("projecte:transmutation_table"),"projecte:transmutation_table",
      "取得贤者之石和转化桌。打开转化桌，只选择本包实际标有 EMC 的余料登记。","给加工线保留最低库存。高阶材料的 EMC 是否存在以本服务器显示为准。",
      [("minecraft:glowstone_dust",8)],("warehouse",))
quest("matrix","把水晶矩阵单独建账",item("avaritia:crystal_matrix_ingot"),"avaritia:crystal_matrix_ingot",
      "按已安装的无尽贪婪配方取得一块水晶矩阵锭。用 ME 终端盘点下一批所需的高价值材料。","准备中子收集器，将其持续产物计入长期库存。手册奖励保留生产目标的成本。",
      [("ae2:blank_pattern",2)],("equivalence","steel"))
quest("neutrons","等一份真正的长期产出",item("avaritia:neutron_collector")+item("avaritia:neutron_pile"),"avaritia:neutron_collector",
      "取得中子收集器和一份中子素尘埃。根据当前 JEI 的合并关系记录累积所需的份数。","准备持久存储，再制作一锭中子素。将材料留给终局配方。",
      [("minecraft:diamond",2)],("matrix",))
quest("capstone","两座星球和一台工厂的账本",item("avaritia:neutron_ingot")+item("ic2newera:industrial_ssd"),"ic2newera:industrial_ssd",
      "在完成月球采样后，备齐一锭中子素和工业 SSD。把长期收集与工业电子生产的成果放到同一张清单。","把余下物资分配到下一次远征或无尽合成。终局装备本体仍由玩家的小队生产。",
      [("minecraft:nether_star",1)],("neutrons","lunar_metals"),1)

groups_by_key = {"guide":"新纪元工业手册","engineering":"工程师 · 制造与仓储","explore":"勘探队 · 现场与远征","supply":"后勤组 · 委托与生活"}
chapter_groups={"ground":"guide","works":"engineering","foundry":"engineering","storage":"engineering","endgame":"engineering",
                "field":"explore","space":"explore","hangar":"explore","kitchen":"supply","dispatch":"supply"}
positions={
    "ground":[(0,0),(2,0),(4,0),(6,0),(8,2),(10,0),(12,0)],
    "works":[(0,0),(2,0),(4,0),(6,0),(8,0),(6,2)],
    "field":[(0,2),(0,0),(2,0),(4,0),(4,2)],
    "space":[(0,0),(2,0),(4,0),(6,0),(8,0)],"hangar":[(0,0),(2,0),(0,2)],
    "foundry":[(0,0),(2,0),(4,0),(2,2),(6,0)],"storage":[(0,0),(2,0),(4,0),(6,0),(8,0)],
    "kitchen":[(0,0),(2,-1),(2,1),(4,0)],"dispatch":[(0,0),(2,0),(4,0)],
    "endgame":[(0,0),(2,0),(4,0),(6,0)]}
for c in chapters:
    for row,(x,y) in zip(c["quests"],positions[c["key"]],strict=True):row.update(x=float(x),y=float(y))

def stable(name):
    return "694A" + hashlib.sha256(("newera-guide/"+name).encode()).hexdigest()[:12].upper()

out=Path(__file__).resolve().parent/"config/ftbquests/quests"
(out/"chapters").mkdir(parents=True,exist_ok=True)
def save(name,data):
    (out/name).write_text(to_snbt(data)+"\n",encoding="utf-8")
with tempfile.TemporaryDirectory() as scratch:
    scratch=Path(scratch)
    serial=iter(range(100000))
    write_quest_book(str(scratch),{"chapters":chapters,"title":"新纪元工业手册"},uid=lambda:stable(str(next(serial))),
        normalize_item=lambda s:s, item_count=lambda row,key:row.get("count",1), to_snbt=to_snbt,
        double_value=_SNBTFloat,long_value=_SNBTLong,group_config={"guide":{"title":"新纪元工业手册"}})
    manifest=[]
    for file in (scratch/"chapters").glob("*.snbt"):
        if file.stem==MARKER_CHAPTER_ID: continue
        ch=parse_snbt(file.read_text(encoding="utf-8")); source=next(c for c in chapters if c["title"]==ch["title"])
        category=chapter_groups[source["key"]]
        ch.update(id=stable("chapter/"+source["key"]),filename="newera_"+source["key"],group=stable("group" if category=="guide" else "group/"+category),
                  default_quest_shape=theme["default_quest_shape"],default_hide_dependency_lines=False)
        ch["order_index"]=chapters.index(source)
        local={q["id"] for q in source["quests"]}
        anchors=dict.fromkeys(parent for q in source["quests"] for parent in q["parents"] if parent not in local)
        ch["quest_links"]=[{"id":stable("link/"+source["key"]+"/"+parent),"linked_quest":stable("quest/"+parent),
                            "x":_SNBTFloat(-3),"y":_SNBTFloat(i*2)} for i,parent in enumerate(anchors)]
        for row,spec in zip(ch["quests"],source["quests"],strict=True):
            key=spec["id"];row["id"]=stable("quest/"+key);row["dependencies"]=[stable("quest/"+k) for k in spec["parents"]]
            for i,t in enumerate(row['tasks']): t['id']=stable(key+'/task/'+str(i))
            for i,r in enumerate(row['rewards']):
                r['id']=stable(key+'/reward/'+spec['rewards'][i]['target']);r['team_reward']=False
                if r['item']=='ic2newera:anon_robot_doll':
                    r['item']={'id':r['item'],'Count':_SNBTByte(1),'tag':{'RobotState':{'Deaths':4}}}
        save("chapters/"+ch["filename"]+".snbt",ch)
        manifest.append(ch["filename"])
    data=parse_snbt((scratch/'data.snbt').read_text(encoding='utf-8'))
    data.update(default_reward_team=False,default_consume_items=False,default_quest_disable_jei=True,
                default_quest_shape=theme["default_quest_shape"],grid_scale=_SNBTFloat(1),
                disable_toast=False,icon="ic2newera:field_notice_board",title="新纪元工业手册",drop_book_on_death=False)
    save("data.snbt",data);save("chapter_groups.snbt",{"chapter_groups":[
        {"id":stable("group" if key=="guide" else "group/"+key),"title":title} for key,title in groups_by_key.items()]})
print(f"AutoFTBQ generated {len(chapters)} chapters and {len(all_quests)} quests from the Create chapter layout.")
