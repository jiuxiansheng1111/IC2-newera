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
    ('eu_motor', 'root', 'eu_motor', '电力驱动', '获得一台新纪元 EU 电动机。', None),
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
    ('basic_generator', 'eu_motor', 'kinetic_generator', '机械生电', '获得一台初级机械发电机。', None),
    ('advanced_motor', 'eu_motor', 'advanced_eu_motor', '更强的驱动', '获得一台高级 EU 电动机。', None),
    ('advanced_generator', 'basic_generator', 'advanced_kinetic_generator', '高速发电', '获得一台高级机械发电机。', None),
    ('universal_transformer', 'advanced_bank', 'universal_transformer', '电压自由转换', '获得一台万能变压器。', None),
    ('intermediate_bank', 'power_bank', 'intermediate_power_bank', '储能进阶', '获得中级充电宝。', None),
    ('advanced_bank', 'intermediate_bank', 'advanced_power_bank', '大容量储能', '获得高级充电宝。', None),
    ('nuclear_bank', 'advanced_bank', 'nuclear_power_bank', '核能随行', '获得核能充电宝。', None),
    ('vanadium_ore', 'minerals', 'vanadium_ore', '钒矿新发现', '获得钒矿石或粗钒。', ['vanadium_ore','raw_vanadium']),
    ('vanadium_ingot', 'vanadium_ore', 'vanadium_ingot', '提炼钒', '获得钒锭。', None),
    ('niobium_ore', 'minerals', 'niobium_ore', '寻找铌矿', '获得铌矿石或粗铌。', ['niobium_ore','raw_niobium']),
    ('niobium_ingot', 'niobium_ore', 'niobium_ingot', '提炼铌', '获得铌锭。', None),
    ('cobalt_ore', 'minerals', 'cobalt_ore', '钴蓝矿脉', '获得钴矿石或粗钴。', ['cobalt_ore','raw_cobalt']),
    ('cobalt_ingot', 'cobalt_ore', 'cobalt_ingot', '提炼钴', '获得钴锭。', None),
    ('molybdenum_ore', 'minerals', 'molybdenum_ore', '耐热矿脉', '获得钼矿石或粗钼。', ['molybdenum_ore','raw_molybdenum']),
    ('molybdenum_ingot', 'molybdenum_ore', 'molybdenum_ingot', '提炼钼', '获得钼锭。', None),
    ('tantalum_ore', 'minerals', 'tantalum_ore', '稀有钽矿', '获得钽矿石或粗钽。', ['tantalum_ore','raw_tantalum']),
    ('tantalum_ingot', 'tantalum_ore', 'tantalum_ingot', '提炼钽', '获得钽锭。', None),
    ('vanadium_steel', 'vanadium_ore', 'vanadium_steel_ingot', '钒钢冶炼', '获得钒钢锭。', None),
    ('niobium_titanium', 'niobium_ore', 'niobium_titanium_ingot', '铌钛合金', '获得铌钛合金锭。', None),
    ('magnetic_coil', 'cobalt_ingot', 'magnetic_coil', '约束磁场', '获得磁约束线圈。', None),
    ('heat_plate', 'molybdenum_ingot', 'heat_resistant_plate', '抵御高温', '获得耐热护板。', None),
    ('pulse_capacitor', 'tantalum_ingot', 'pulse_capacitor', '脉冲储能', '获得脉冲电容。', None),
    ('servo_joint', 'cobalt_ingot', 'servo_armor_joint', '动力关节', '获得装甲伺服关节。', None),
    ('resonance_shard', 'minerals', 'resonance_shard', '共振碎晶', '获得共振碎晶。', None),
    ('resonance_cluster', 'resonance_shard', 'resonance_cluster', '共振矿簇', '获得共振矿簇。', None),
    ('resonance_core', 'resonance_shard', 'resonance_core', '共振核心', '获得共振核心。', None),
    ('vanadium_tool', 'vanadium_steel', 'vanadium_steel_pickaxe', '钒钢工具', '获得任意一件钒钢工具。', ['vanadium_steel_pickaxe','vanadium_steel_axe','vanadium_steel_shovel','vanadium_steel_hoe']),
    ('niobium_tool', 'niobium_titanium', 'niobium_titanium_pickaxe', '铌钛工具', '获得任意一件铌钛合金工具。', ['niobium_titanium_pickaxe','niobium_titanium_axe','niobium_titanium_shovel','niobium_titanium_hoe']),
]

# Every piece can be acquired at a different time; the native criteria retain progress.
ARMOR_SETS = [
    ('bronze_armor', 'alloys', 'aluminium_bronze', '铝青铜护具'),
    ('steel_armor', 'alloys', 'tempered_steel', '低碳钢护具'),
    ('high_carbon_armor', 'alloys', 'tempered_high_carbon_steel', '高碳钢护具'),
    ('vanadium_armor', 'vanadium_steel', 'vanadium_steel', '钒钢护具'),
    ('niobium_armor', 'niobium_titanium', 'niobium_titanium', '铌钛护具'),
    ('magnetic_armor', 'servo_joint', 'magnetic_armor', '磁约束护具'),
    ('resonance_armor', 'tantalum_ingot', 'resonance_armor', '共振护具'),
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
for name, parent, prefix, title in ARMOR_SETS:
    parts = ['helmet', 'chestplate', 'leggings', 'boots']
    for part in parts:
        if not (RES / 'assets/ic2newera/models/item' / (prefix+'_'+part+'.json')).is_file():
            raise ValueError('Missing armor item model: '+prefix+'_'+part)
    criteria = {part: {'trigger':'minecraft:inventory_changed', 'conditions': {'items':[
        {'items':['ic2newera:'+prefix+'_'+part]}]}} for part in parts}
    save(name, {'parent':'ic2newera:newera/'+parent,
        'display': {'icon':{'item':'ic2newera:'+prefix+'_chestplate'}, 'title':title,
            'description':'分别获得头盔、胸甲、护腿和靴子；无需同时携带。',
            'frame':'goal', 'show_toast':True, 'announce_to_chat':False, 'hidden':False},
        'criteria':criteria, 'requirements':[[part] for part in parts]})
print(f'Generated {len(ROWS)+len(ARMOR_SETS)+1} native New Era advancements.')
