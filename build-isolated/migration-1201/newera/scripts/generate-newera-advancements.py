"""Curated native milestones. Only New Era items; no recipe or gameplay gates."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'src/main/resources'
OUT = RES / 'data/ic2newera/advancements/newera'
OUT.mkdir(parents=True, exist_ok=True)

# Parent links organize the native screen; they do not lock recipes or abilities.
ROWS = [
    ('minerals', 'root', 'nickel_ore', '新的矿藏', '获得镍、铬、钛或钨矿石中的任意一种。', ['nickel_ore', 'chromium_ore', 'titanium_ore', 'tungsten_ore']),
    ('impure_dust', 'minerals', 'impure_nickel_dust', '粉碎的第一步', '获得一种新纪元含杂质矿粉。', ['impure_'+m+'_dust' for m in ('nickel','chromium','titanium','tungsten')]),
    ('washed_dust', 'impure_dust', 'washed_nickel_dust', '洗去杂质', '获得一种新纪元洗后矿粉中间物。', ['washed_'+m+'_dust' for m in ('nickel','chromium','titanium','tungsten')]),
    ('clean_dust', 'impure_dust', 'nickel_dust', '纯净的粉末', '获得镍、铬、钛或钨净粉中的任意一种。', [m+'_dust' for m in ('nickel','chromium','titanium','tungsten')]),
    ('metals', 'clean_dust', 'titanium_ingot', '新金属', '获得镍、铬、钛或钨锭中的任意一种。', [m+'_ingot' for m in ('nickel','chromium','titanium','tungsten')]),
    ('alloy_smelter', 'root', 'alloy_smelter', '合金的起点', '获得一台新纪元合金炉。', None),
    ('alloys', 'alloy_smelter', 'tempered_steel_ingot', '合金时代', '获得低碳钢、高碳钢或铝青铜锭。', ['tempered_steel_ingot','tempered_high_carbon_steel_ingot','aluminium_bronze_ingot']),
    ('advanced_furnace', 'alloys', 'advanced_electric_furnace', '更高的炉温', '获得一台新纪元高级电炉。', None),
    ('eu_motor', 'alloys', 'eu_motor', '电力驱动', '获得一台新纪元 EU 电动机。', None),
    ('non_metal_processor', 'root', 'non_metal_processor', '电子材料的起点', '获得一台非金属处理机。', None),
    ('silicon', 'non_metal_processor', 'silicon', '从石英到硅', '获得硅材料。具体工艺查看 JEI 或询问内置 AI。', None),
    ('high_purity_silicon', 'silicon', 'high_purity_silicon', '纯度提升', '获得高纯硅。', None),
    ('electronic_fabricator', 'high_purity_silicon', 'electronic_fabricator', '精密加工', '获得一台电子材料加工机。', None),
    ('pcb', 'electronic_fabricator', 'pcb', '电路的载体', '获得一块 PCB。', None),
    ('integrated_circuit', 'pcb', 'integrated_circuit', '集成时代', '获得一个集成电路。', None),
    ('computer_components', 'integrated_circuit', 'cpu', '计算的核心', '获得 CPU、RAM、GPU 或工业 SSD 中的任意一种。', ['cpu','ram','gpu','industrial_ssd']),
    ('network_switch', 'root', 'industrial_network_switch', '工业网络', '获得一个工业交换机。', None),
    ('network_adapter', 'network_switch', 'machine_network_adapter', '让机器入网', '获得一个机器网络适配器。', None),
    ('fiber', 'network_switch', 'industrial_fiber', '跨区连接', '获得工业光纤。', None),
    ('control_server', 'network_adapter', 'industrial_control_server', '控制中心', '获得一台工业控制服务器。', None),
    ('ai_core', 'control_server', 'industrial_ai_core', '工业智能', '获得一个工业 AI 核心。', None),
    ('phone', 'network_switch', 'industrial_phone', '随身控制', '获得一部工业手机。', None),
    ('robot_blueprint', 'root', 'robot_blueprint', '伙伴的蓝图', '获得通用机器人图纸。', None),
    ('robot', 'robot_blueprint', 'anon_robot_doll', '新的伙伴', '获得八种机器人娃娃中的任意一种。', [c+'_robot_doll' for c in ('anon','tomori','soyo','taki','rana','sakiko','uika','umiri')]),
    ('robot_battery', 'robot', 'robot_battery', '为伙伴供能', '获得一块机器人电池。', None),
    ('power_bank', 'robot_battery', 'power_bank', '随行电源', '获得任意一款新纪元充电宝。', ['power_bank','intermediate_power_bank','advanced_power_bank','nuclear_power_bank']),
    ('mechanical_cat', 'robot_blueprint', 'mechanical_cat', '机械猫伙伴', '获得一只机械猫。', None),
    ('mechanical_dog', 'robot_blueprint', 'mechanical_dog', '机械狗伙伴', '获得一只机械狗。', None),
    ('wormhole_blueprint', 'root', 'wormhole_blueprint', '空间研究', '获得虫洞图纸。', None),
    ('lunar_crystal', 'wormhole_blueprint', 'lunar_resonance_crystal', '月球共鸣', '获得月球共鸣晶体。', None),
    ('wormhole_device', 'lunar_crystal', 'wormhole_device', '空间锚点', '获得一台虫洞装置。', None),
    ('wormhole_module', 'wormhole_device', 'wormhole_module', '随身跨越', '获得一个虫洞模块。', None),
]

def save(name, value):
    (OUT / (name+'.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

save('root', {
    'display': {'icon': {'item':'ic2newera:industrial_phone'}, 'title':'IC2：新纪元',
        'description':'新纪元自己的材料、设备与伙伴。进度记录里程碑，不限制合成或使用。',
        'background':'minecraft:textures/block/deepslate_tiles.png', 'frame':'task',
        'show_toast':False, 'announce_to_chat':False, 'hidden':False},
    'criteria': {'join': {'trigger':'minecraft:tick'}}, 'requirements':[['join']]
})
for name, parent, icon, title, description, items in ROWS:
    items = items or [icon]
    for item in set(items + [icon]):
        if not (RES / 'assets/ic2newera/models/item' / (item+'.json')).is_file():
            raise ValueError('Missing first-party item model: '+item)
    save(name, {'parent':'ic2newera:newera/'+parent,
        'display': {'icon':{'item':'ic2newera:'+icon}, 'title':title, 'description':description,
            'frame':'task', 'show_toast':True, 'announce_to_chat':False, 'hidden':False},
        'criteria': {'obtain': {'trigger':'minecraft:inventory_changed',
            'conditions': {'items':[{'items':['ic2newera:'+item for item in items]}]}}},
        'requirements':[['obtain']]})
print(f'Generated {len(ROWS)+1} native New Era advancements.')
