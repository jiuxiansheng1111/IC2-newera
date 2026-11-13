"""Eight first-party production routes for the joint FTB handbook."""
from hashlib import sha256

from extra_routes_b import ROUTES as EXPEDITION_ROUTES


# Each row gives a real task stack, whether it is handed in, a personal reward
# and a workshop instruction. Expensive equipment is inspected in inventory.
ROUTES = [
    ("assay", "矿山化验室", [
        ("给矿车装第一筐铜", "minecraft:raw_copper", 6, True, "minecraft:torch", 8, "粗铜留作进厂样本。领取照明，继续找同层的铁和更深处的铅。"),
        ("抽检粗铁", "minecraft:raw_iron", 4, True, "create:andesite_alloy", 1, "选未经打粉的粗铁装箱。合金可留作风扇传动件。"),
        ("给铅矿编批次", "ic2:raw_lead", 3, True, "ic2:insulated_copper_cable", 2, "把粗铅分两份，一份送检，一份留给打粉机。电缆可接离心工位。"),
        ("留一袋含杂质铅粉", "ic2newera:impure_lead_dust", 2, True, "create:shaft", 2, "一份原矿打成两份含杂质粉。另开一批给风扇水洗，别把全部样品上交。"),
        ("风道取样口", "ic2:purified_lead", 2, True, "minecraft:iron_ingot", 2, "让鼓风机吹过水，再把杂质粉放进风流。这里验收洗后中间物，留出一批进热能离心机。"),
        ("校对离心产量", "ic2:lead_dust", 4, True, "minecraft:coal", 3, "洗后一份可离心成三份成品粉。也可把杂质粉直接离心，一份只得一份。留点煤给熔炼工位。"),
        ("压出屏蔽铅板", "ic2:lead_plate", 3, True, "ic2:tin_plate", 1, "先熔出铅锭，再依 JEI 制板。薄板走周转箱，成品粉走另一条线。"),
        ("镍矿换班样", "ic2newera:nickel_dust", 3, True, "ic2:iron_dust", 2, "镍矿粉碎、按需水洗后离心，留下净镍粉作为合金原料。"),
        ("铬粉的去向", "ic2newera:chromium_dust", 3, True, "ic2newera:nickel_ingot", 1, "离心后分储铬粉。准备镍和铬，在新纪元合金炉的 JEI 页查看配料。"),
        ("交付镍铬合金试条", "ic2newera:nichrome_ingot", 2, True, "ic2:insulated_gold_cable", 2, "给合金炉接电，按批准配方投料。留存其余镍铬合金供设备生产。"),
        ("钛料高温炉检", "ic2newera:titanium_ingot", 2, True, "ic2newera:heat_resistant_plate", 1, "钛净粉进入新纪元高级电炉。普通炉不能完成这道高温工序。"),
        ("钨锭随车出厂", "ic2newera:tungsten_ingot", 2, True, "ic2newera:titanium_ingot", 1, "把钨净粉放进高级电炉，给后续精密工装留一批钨。"),
    ]),
    ("switchyard", "动力车间与变电站", [
        ("低压配电备线", "ic2:insulated_copper_cable", 6, True, "minecraft:redstone", 3, "在热源与储电箱间铺设低压供电，另做一卷绝缘铜缆交仓。"),
        ("检查低压储能箱", "ic2:batbox", 1, False, "ic2:tin_plate", 2, "把储电箱拿在背包里验收后再放置。接线前看清输出面。"),
        ("传动侧备轴", "create:shaft", 8, True, "create:cogwheel", 2, "给两条试验线分别留出输出轴。齿轮可把动力送到相邻工位。"),
        ("初级电动机入列", "ic2newera:eu_motor", 1, False, "ic2:insulated_copper_cable", 4, "用初级电动机把 EU 转为轴动力，分别接电缆与传动轴。"),
        ("反向发电试车", "ic2newera:kinetic_generator", 1, False, "ic2:copper_plate", 2, "把外部转轴接到初级机械发电机，将产生的 EU 送入独立储电箱。"),
        ("给检修工留扳手", "ic2:wrench", 1, True, "ic2:insulated_gold_cable", 2, "做一把备用扳手交给检修组；正在使用的那把可先放进箱子。"),
        ("给万能变压器定向", "ic2newera:universal_transformer", 1, False, "minecraft:redstone", 5, "验收后用扳手调整方向。五点面接输入，单点面接输出；升压未送出的 EU 暂存在机器里。"),
        ("低压升档工位", "ic2:lv_transformer", 1, False, "ic2:double_insulated_gold_cable", 2, "登记原版低压变压器，把不同电压的支路分开，在接负载前核对电压等级。"),
        ("中压母线接头", "ic2:double_insulated_gold_cable", 6, True, "ic2:circuit", 1, "为新支路备绝缘金缆，保留线上的现用线材。"),
        ("第一枚能量水晶", "ic2:energy_crystal", 1, False, "ic2:gold_plate", 2, "按游戏内配方使用 IC2 普通钻石粉。水晶验收后仍可装入设备。"),
        ("高级电动机联调", "ic2newera:advanced_eu_motor", 1, False, "ic2:advanced_circuit", 1, "三级、四级电压的转化任务交给高级机。先检查电缆与电源的等级。"),
        ("高级机械发电机交班", "ic2newera:advanced_kinetic_generator", 1, False, "ic2newera:industrial_fiber", 4, "为高级发电机配外部机械动力与能容纳输出的电网，再用光纤接监控站。"),
    ]),
    ("conveyor", "机械动力分拣线", [
        ("给装配工备安山合金", "create:andesite_alloy", 6, True, "minecraft:dried_kelp", 4, "给传动件预留安山合金，备好海带做传送带。"),
        ("传送带组开箱", "create:belt_connector", 3, True, "create:shaft", 4, "平行放置传动轴并连接，最终交库的是备用传送带。"),
        ("装卸月台", "create:depot", 1, False, "minecraft:iron_ingot", 3, "把置物台留在检修通道旁，供辊压或注液取放工件。"),
        ("片材工位", "create:mechanical_press", 1, False, "minecraft:copper_ingot", 4, "让辊压机位于置物台或传送带正上方，并接入转轴。"),
        ("收第一批铜板", "create:copper_sheet", 4, True, "minecraft:glass", 4, "给装配线准备机械动力的铜板；交付图标指定的是 Create 的那一叠。"),
        ("封装流体储罐", "create:fluid_tank", 2, False, "create:fluid_pipe", 2, "把备用储罐验收后放到注液线上，管道留给输送端。"),
        ("泵站验货", "create:mechanical_pump", 1, False, "minecraft:iron_nugget", 6, "动力泵装在管路上，用扳手核对流向，再接外部动力。"),
        ("风选线的出料口", "create:encased_fan", 1, False, "create:andesite_funnel", 1, "风扇吹过水可以水洗；在作业区外放漏斗，避免材料还没洗完就被收走。"),
        ("黄铜分流", "create:brass_funnel", 2, False, "create:electron_tube", 1, "把黄铜漏斗接入缓存箱，设置指定输出物品。奖励留作精密装配零件。"),
        ("扳手换班", "create:wrench", 1, True, "create:brass_sheet", 2, "留一把在现场，用备用的机械动力扳手交货。之后用黄铜板补建分拣设备。"),
        ("机械手接班", "create:deployer", 1, False, "create:cogwheel", 4, "为机械手提供装配材料和转速，给重复工序留出回流段。"),
        ("精密构件成批出线", "create:precision_mechanism", 2, True, "ic2:circuit", 2, "用序列装配完成多轮工序，只提交成品。留好基础电路给仓储分站。"),
    ]),
    ("storage", "ME 仓库和工业网络", [
        ("赛特斯样晶", "ae2:certus_quartz_crystal", 4, True, "minecraft:redstone", 4, "找 AE2 石英，分出一批给充能站，普通下界石英不能替代此样品。"),
        ("充能器上架", "ae2:charger", 1, False, "minecraft:copper_ingot", 4, "给充能器供电并装入普通赛特斯石英，领取下一批导电材料。"),
        ("交带电晶样", "ae2:charged_certus_quartz_crystal", 2, True, "minecraft:quartz", 3, "留好额外带电晶体与下界石英、红石给福鲁伊克斯工序。"),
        ("福鲁伊克斯晶体批次", "ae2:fluix_crystal", 4, True, "minecraft:gold_ingot", 2, "按本包 JEI 的福鲁伊克斯配方加工；预留一批晶体制作线缆。"),
        ("压印台入库", "ae2:inscriber", 1, False, "minecraft:redstone", 6, "给压印器供电，准备对应模板，先压印电路和硅板。"),
        ("逻辑处理器抽样", "ae2:logic_processor", 2, True, "ae2:fluix_glass_cable", 2, "在压印器合装逻辑处理器；奖励线缆给存储分支。"),
        ("运算处理器抽样", "ae2:calculation_processor", 2, True, "minecraft:gold_ingot", 2, "给运算电路安排独立的模板，避免与逻辑电路混料。"),
        ("工程处理器抽样", "ae2:engineering_processor", 1, True, "minecraft:quartz", 4, "钻石级样品交一枚即可；其他工程处理器留作控制部件。"),
        ("第一批 1K 核心", "ae2:cell_component_1k", 3, True, "minecraft:iron_ingot", 4, "另留核心装外壳，再放入 ME 驱动器，单个核心本身不能接入网络。"),
        ("ME 驱动器上架", "ae2:drive", 1, False, "ae2:fluix_glass_cable", 4, "把驱动器接入有电的 ME 线缆，在插槽内放成品存储元件。"),
        ("合成终端登记", "ae2:crafting_terminal", 1, False, "ic2newera:industrial_fiber", 4, "合成终端安装在 ME 网络。工业光纤留给新纪元交换机。"),
        ("两个网络分别接线", "ic2newera:industrial_network_switch", 1, False, "ic2newera:machine_network_adapter", 1, "给交换机命名，把适配器放在工业机器旁；ME 管物品，工业网络读取设备状态。"),
    ]),
]

ANCHORS = {
    "assay": "newera_ground", "switchyard": "newera_works", "conveyor": "newera_ground",
    "storage": "newera_storage", "field_supply": "newera_kitchen", "field_wildlife": "newera_field",
    "precision_workshop": "newera_works", "space_provisions": "newera_space",
}
# Columns split into two independent jobs before each workshop checkpoint.
PARENTS = [(), (0,), (0,), (1, 2), (3,), (3,), (4, 5), (6,), (6,), (7, 8), (9,), (10,)]
GRID = [(0, 0), (2, -1), (2, 1), (4, 0), (6, -1), (6, 1),
        (8, 0), (10, -1), (10, 1), (12, 0), (14, 0), (16, 0)]


def build_extra_chapters(base_chapters, newera_chapters, used_ids):
    def identify(key):
        value = "694A" + sha256(("newera-delight-production/" + key).encode()).hexdigest()[:12].upper()
        if value in used_ids:
            raise ValueError(f"Quest object ID collision for {key}")
        used_ids.add(value)
        return value

    anchors = {chapter["filename"]: chapter["quests"][0]["id"] for chapter in newera_chapters}
    group = {"id": identify("production"), "title": "联合工单 · 生产与远征"}
    chapters = []
    routes = ROUTES + EXPEDITION_ROUTES
    if len(routes) != 8 or any(len(rows) != 12 for _, _, rows in routes):
        raise ValueError("Expected eight twelve-job routes")
    for index, (key, title, rows) in enumerate(routes):
        anchor = anchors[ANCHORS[key]]
        quests = []
        for number, (name, item, count, consume, gift, amount, instruction) in enumerate(rows):
            object_key = f"{key}/{item}"
            x, y = GRID[number]
            tasks = [{"id": identify(object_key + "/task"), "type": "item", "item": item,
                      "count": count, "consume_items": consume}]
            # Tinkers' Construct parts and tools carry real material/durability
            # NBT. FTB Quests 2001.4.22 reads `match_nbt: false` as the explicit
            # no-NBT-match setting, so accept any legitimately assembled item
            # with the exact registered item ID.
            if item.startswith("tconstruct:"):
                tasks[0]["match_nbt"] = False
            rewards = [{"id": identify(object_key + "/reward"), "type": "item", "item": gift,
                        "count": amount, "team_reward": False}]
            parents = [quests[i]["id"] for i in PARENTS[number]] or [anchor]
            terms = f"在任务页提交 {count} 件目标物品，提交时会扣除。" if consume else f"背包中备齐 {count} 件目标设备即可登记，验收后可继续使用。"
            quests.append({"id": identify(object_key), "title": name, "icon": item, "x": float(x),
                           "y": float(y), "shape": "gear" if number in (3, 6, 9, 11) else "square",
                           "dependencies": parents, "subtitle": "交付备品" if consume else "设备验收",
                           "description": [instruction, "", terms + " 奖励由队员分别领取。"],
                           "tasks": tasks, "rewards": rewards})
        chapters.append({"id": identify(key), "filename": "joint_" + key, "title": title,
                         "icon": quests[0]["icon"], "group": group["id"], "order_index": index,
                         "default_quest_shape": "square", "quests": quests, "quest_links": []})
    return [group], chapters
