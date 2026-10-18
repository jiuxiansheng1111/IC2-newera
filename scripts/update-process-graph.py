"""Author the graph. Concrete recipes are derived by Gradle, not duplicated here."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
materials, routes = [], []
def own(name): return 'ic2newera:' + name
def item(name, n=1): return {'item': name, 'count': n}
def tag(name, n=1): return {'tag': 'forge:' + name, 'count': n}
def route(name, output, inputs, fmt, machines, count=1, ticks=200, eu=-1, priority=10, material='', legacy=False, **extra):
    routes.append(dict(id=own(name), material=material,
        classification='LEGACY_PROCESS' if legacy else 'APPROVED_INDUSTRIAL_PROCESS',
        machines=machines, inputs=inputs, output=dict(item=output,count=count),
        duration_ticks=ticks, eu_per_tick=eu, priority=priority, format=fmt, **extra))

for metal in ('nickel','chromium','titanium','tungsten'):
    ore, impure, dust, ingot = (own(metal+'_ore'),own('impure_'+metal+'_dust'),own(metal+'_dust'),own(metal+'_ingot'))
    advanced = metal in ('titanium','tungsten')
    materials.append(dict(id=metal,advanced=advanced,stages=dict(RAW=ore,IMPURE_DUST=impure,CLEAN_DUST=dust,FINAL_METAL=ingot)))
    route(metal+'_macerate',impure,[item(ore)],'macerator',['ic2:macerator'],2,material=metal,priority=5,ticks=0)
    route(metal+'_crush',impure,[item(ore)],'crushing',['create:crushing_wheel'],2,material=metal,ticks=250)
    route(metal+'_wash',dust,[item(impure)],'splashing',['create:encased_fan'],material=metal,ticks=0)
    for fmt,ticks,machines in [('smelting',200,['minecraft:furnace','ic2:electric_furnace']),('blasting',100,['minecraft:blast_furnace'])]:
        route(metal+'_legacy_'+fmt,ingot,[item(ore)],fmt,machines,ticks=ticks,material=metal,legacy=True)
        if not advanced:
            route(metal+'_clean_'+fmt,ingot,[item(dust)],fmt,machines,ticks=ticks,material=metal)
    if advanced:
        route(metal+'_industrial_smelt',ingot,[item(dust)],'blast_furnace',
            ['ic2:electric_blast_furnace','ic2:blast_induction_furnace'],material=metal,ticks=0)

# Existing normal powders are reused; never invent dust for a mineral without one.
# Redstone is explicitly excluded by the player. Lapis/emerald/ancient debris and
# uranium have no ordinary powder in the installed dependency registries.
existing_powders = {
 'iron': ('ic2:dust_iron','minecraft:iron_ingot',
          ['minecraft:iron_ore','minecraft:deepslate_iron_ore'],['minecraft:raw_iron'],'create:crushed_raw_iron',[]),
 'gold': ('ic2:dust_gold','minecraft:gold_ingot',
          ['minecraft:gold_ore','minecraft:deepslate_gold_ore','minecraft:nether_gold_ore'],['minecraft:raw_gold'],'create:crushed_raw_gold',[]),
 'copper': ('ic2:dust_copper','minecraft:copper_ingot',
            ['minecraft:copper_ore','minecraft:deepslate_copper_ore'],['minecraft:raw_copper'],'create:crushed_raw_copper',[]),
 'coal': ('ic2:dust_coal',None,
          ['minecraft:coal_ore','minecraft:deepslate_coal_ore'],[],None,['minecraft:coal']),
 'diamond': ('ic2:dust_diamond',None,
             ['minecraft:diamond_ore','minecraft:deepslate_diamond_ore'],[],None,['minecraft:diamond','ic2:industrial_diamond']),
 'quartz': (own('quartz_dust'),None,['minecraft:nether_quartz_ore'],[],None,['minecraft:quartz']),
 'tin': ('ic2:dust_tin','ic2:ingot_tin',
         ['ic2:tin_ore','ic2:deepslate_tin_ore'],['ic2:raw_tin'],'create:crushed_raw_tin',[]),
 'silver': ('ic2:dust_silver','ic2:ingot_silver',
            ['ic2:silver_ore','ic2:deepslate_silver_ore','ic2:silver_ore_nether'],['ic2:raw_silver'],'create:crushed_raw_silver',[]),
 'aluminium': ('ic2:dust_aluminium','ic2:ingot_aluminium',
               ['ic2:aluminium_ore_nether'],['ic2:raw_aluminium'],'create:crushed_raw_aluminum',[]),
 'zinc': (own('zinc_dust'),'create:zinc_ingot',
          ['create:zinc_ore','create:deepslate_zinc_ore'],['create:raw_zinc'],'create:crushed_raw_zinc',[]),
}
for mineral,(dust,ingot,ores,raw,crushed,feedstocks) in existing_powders.items():
    impure=own('impure_'+mineral+'_dust')
    variants=ores[1:]+raw+([crushed] if crushed else [])
    stages=dict(RAW=ores[0],IMPURE_DUST=impure,CLEAN_DUST=dust)
    if ingot: stages['FINAL_METAL']=ingot
    materials.append(dict(id=mineral,advanced=False,stages=stages,
                          raw_variants=variants,grinding_inputs=feedstocks))
    for source in ores+raw+([crushed] if crushed else [])+feedstocks:
        count=1 if source==crushed or source in feedstocks else 2
        path=source.split(':')[1]
        for fmt,machine,ticks in [('macerator','ic2:macerator',0),('crushing','create:crushing_wheel',250)]:
            route(mineral+'_'+path+'_'+fmt,impure,[item(source)],fmt,[machine],count,
                  ticks=ticks,material=mineral,priority=5 if fmt=='macerator' else 10)
    route(mineral+'_wash',dust,[item(impure)],'splashing',['create:encased_fan'],
          ticks=0,material=mineral,priority=5)
    legacy_output=ingot or feedstocks[0]
    for source in ores+raw:
        for fmt,ticks,machines in [('smelting',200,['minecraft:furnace','ic2:electric_furnace']),
                                   ('blasting',100,['minecraft:blast_furnace'])]:
            # Nonmetal ores have only their existing single-output furnace route.
            if not ingot and fmt=='blasting': continue
            route(mineral+'_'+source.split(':')[1]+'_legacy_'+fmt,legacy_output,[item(source)],fmt,
                  machines,ticks=ticks,material=mineral,legacy=True)
    if ingot:
        for fmt,ticks,machines in [('smelting',200,['minecraft:furnace','ic2:electric_furnace']),
                                   ('blasting',100,['minecraft:blast_furnace'])]:
            route(mineral+'_clean_'+fmt,ingot,[item(dust)],fmt,machines,ticks=ticks,material=mineral)
        # Already-made ingots are clean recovery feedstock, not freshly mined ore.
        for fmt,machine,ticks in [('macerator','ic2:macerator',0),('crushing','create:crushing_wheel',150)]:
            route(mineral+'_ingot_recovery_'+fmt,dust,[item(ingot)],fmt,[machine],
                  ticks=ticks,material=mineral,priority=30)
def processor(name, inputs, kind='electronic', ticks=300, eu=16, count=1):
    route(name,own(name),inputs,'processor',[own('non_metal_processor' if kind=='non_metal' else 'electronic_fabricator')],
          kind=kind,ticks=ticks,eu=eu,count=count)
processor('silicon',[item(own('quartz_dust'),2)],'non_metal',200,8)
processor('high_purity_silicon',[item(own('silicon'),2)],'non_metal',400,16)
processor('pcb',[tag('ingots/copper',2),item(own('silicon')),item('create:electron_tube')])
processor('integrated_circuit',[item(own('high_purity_silicon')),item(own('pcb')),item('minecraft:redstone',2)])
processor('cpu',[item(own('integrated_circuit'),2),item(own('pcb')),tag('ingots/gold'),item('ic2:circuit')],ticks=600,eu=32)
processor('ram',[item(own('integrated_circuit'),2),item(own('pcb')),tag('ingots/copper')],ticks=400,eu=24)
processor('gpu',[item(own('integrated_circuit'),4),item(own('pcb'),2),tag('ingots/gold',2),item('create:precision_mechanism')],ticks=800,eu=64)
processor('industrial_ssd',[item(own('integrated_circuit'),2),item(own('high_purity_silicon'),2),item(own('pcb')),tag('ingots/iron')],ticks=500,eu=32)

# IC2's existing two-input alloy smelter is reused. New alloys each have a concrete device use.
alloys = {
 'stainless_steel':([tag('ingots/iron',3),item(own('chromium_ingot'))],4),
 'invar':([tag('ingots/iron',2),item(own('nickel_ingot'))],3),
 'nichrome':([item(own('nickel_ingot'),4),item(own('chromium_ingot'))],5),
 'tool_steel':([item(own('tempered_steel_ingot')),item(own('tungsten_ingot'))],2),
 'titanium_alloy':([item(own('titanium_ingot'),3),tag('ingots/aluminium')],4),
 'tungsten_carbide':([item(own('tungsten_ingot')),item('minecraft:coal')],1),
}
for alloy,(inputs,n) in alloys.items():
    route(alloy,own(alloy+'_ingot'),inputs,'mixing_furnace',['ic2:alloy_smelter'],n,ticks=0)

# Existing bronze/brass and the older aluminium bronze follow the same graph policy.
route('bronze_dust','ic2:dust_bronze',[tag('dusts/copper',3),tag('dusts/tin')],
      'mixing_furnace',['ic2:alloy_smelter'],4,ticks=0,priority=5)
route('bronze_ingot','ic2:ingot_bronze',[tag('ingots/copper',3),tag('ingots/tin')],
      'mixing_furnace',['ic2:alloy_smelter'],4,ticks=0,priority=5)
for fmt,ticks,machines in [('smelting',200,['minecraft:furnace','ic2:electric_furnace']),
                           ('blasting',100,['minecraft:blast_furnace'])]:
    route('bronze_'+fmt,'ic2:ingot_bronze',[item('ic2:dust_bronze')],fmt,machines,ticks=ticks)
route('bronze_macerate','ic2:dust_bronze',[item('ic2:ingot_bronze')],
      'macerator',['ic2:macerator'],ticks=0,priority=30)
# Recovering already-made alloy from storage does not form a new alloy.
route('bronze_ingot_from_block','ic2:ingot_bronze',[item('ic2:bronze_block')],
      'crafting',['minecraft:crafting_table'],9,ticks=0,eu=0,priority=40)
route('bronze_dust_from_block','ic2:dust_bronze',[item('ic2:bronze_dust_block')],
      'crafting',['minecraft:crafting_table'],9,ticks=0,eu=0,priority=40)
route('brass_ingot','create:brass_ingot',[tag('ingots/copper',7),item('create:zinc_ingot',3)],
      'mixing_furnace',['ic2:alloy_smelter'],10,ticks=0,priority=5)
route('brass_ingot_from_block','create:brass_ingot',[item('create:brass_block')],
      'crafting',['minecraft:crafting_table'],9,ticks=0,eu=0,priority=40)
route('brass_ingot_from_nuggets','create:brass_ingot',[item('create:brass_nugget',9)],
      'crafting',['minecraft:crafting_table'],ticks=0,eu=0,priority=40)
route('aluminium_bronze_ingot',own('aluminium_bronze_ingot'),[tag('dusts/copper',9),tag('dusts/aluminium')],
      'mixing_furnace',['ic2:alloy_smelter'],10,ticks=0)

# Grind iron/coal separately in existing machines. No billet or extra carbon item.
# A batch preserves iron yield: 1 coal dust + N iron dust -> N grade-specific dust.
for grade,iron_count,ingot_name in [('low',5,'tempered_steel_ingot'),('high',2,'tempered_high_carbon_steel_ingot')]:
    dust=own(grade+'_carbon_steel_dust'); ingot=own(ingot_name)
    inputs=[item('ic2:dust_coal'),tag('dusts/iron',iron_count)]
    route(grade+'_carbon_steel_dust',dust,inputs,'mixing_furnace',['ic2:alloy_smelter'],
          count=iron_count,ticks=0,material=grade+'_carbon_steel',priority=5)
    for fmt,ticks,machines in [('smelting',200,['minecraft:furnace','ic2:electric_furnace']),
                               ('blasting',100,['minecraft:blast_furnace'])]:
        route(grade+'_carbon_steel_'+fmt,ingot,[item(dust)],fmt,machines,
              ticks=ticks,material=grade+'_carbon_steel')
    route(grade+'_carbon_steel_macerate',dust,[item(ingot)],'macerator',['ic2:macerator'],
          ticks=0,material=grade+'_carbon_steel',priority=30)
    route(grade+'_carbon_steel_crush',dust,[item(ingot)],'crushing',['create:crushing_wheel'],
          ticks=150,material=grade+'_carbon_steel',priority=40)

def craft(name, inputs, count=1):
    route(name,own(name),inputs,'crafting',['minecraft:crafting_table'],count=count,ticks=0,eu=0)
craft('non_metal_processor',[tag('ingots/iron',2),item(own('nichrome_ingot')),item('ic2:circuit'),item('create:andesite_alloy',2),item('minecraft:quartz',2)])
craft('electronic_fabricator',[item(own('stainless_steel_ingot'),2),item(own('tungsten_carbide_ingot')),item(own('tool_steel_ingot')),item('create:electron_tube'),item('ic2:circuit'),item('create:precision_mechanism'),tag('ingots/copper',2)])
craft('machine_network_adapter',[item(own('pcb')),item('ic2:circuit'),item(own('invar_ingot')),item('create:electron_tube')])
craft('eu_motor',[item('create:andesite_alloy',4),item('ic2:copper_insulated_cable_item',2),
                  item('ic2:circuit'),item('create:andesite_casing'),item('create:shaft')])
craft('wifi_amplifier',[item(own('pcb')),item('ic2:circuit'),item('ic2:copper_insulated_cable_item',2),
                        item('create:electron_tube',2),item('create:andesite_alloy',3)])
craft('industrial_fiber',[item('minecraft:glass',2),item('minecraft:quartz'),item('minecraft:redstone')],8)
craft('industrial_network_switch',[item(own('pcb'),2),item(own('integrated_circuit')),item(own('industrial_fiber'),2),item(own('stainless_steel_ingot'))])
craft('industrial_control_server',[item(own('cpu')),item(own('ram'),2),item(own('industrial_ssd')),item(own('industrial_network_switch')),item(own('titanium_alloy_ingot'),2),item('ic2:machine_block')])
craft('industrial_ai_core',[item(own('gpu')),item(own('integrated_circuit'),2),item(own('ram')),item(own('industrial_ssd')),item(own('pcb'))])
# Robot components reuse late-game IC2/Create parts and the established electronic chain.
# Every fabricator recipe stays within its six physical input slots.
processor('robot_control_core',[
    item(own('cpu')), item(own('ram')), item(own('industrial_ssd')), item(own('gpu')),
    item('ic2:advanced_circuit',2), item('ic2:lapatron_crystal')],
    ticks=1000,eu=96)
processor('robot_actuator',[
    item('ic2:plate_advanced_alloy',2), item('ic2:carbon_plate'), item('create:precision_mechanism'),
    item(own('titanium_alloy_ingot'),2), item(own('tungsten_carbide_ingot')), item('ic2:advanced_circuit')],
    ticks=800,eu=64)
processor('robot_battery',[
    item('ic2:advanced_circuit'), item('ic2:complex_circuit'), item('ic2:plate_advanced_alloy',2),
    item('ic2:carbon_plate'), item(own('industrial_ssd')), item(own('titanium_alloy_ingot'))],
    ticks=800,eu=64)
processor('power_bank',[
    item(own('robot_battery')), item(own('pcb')), item('ic2:circuit'),
    item('ic2:plate_advanced_alloy',2), item('ic2:copper_insulated_cable_item',2), item('ic2:carbon_plate')],
    ticks=600,eu=64)
processor('intermediate_power_bank',[
    item(own('power_bank')), item('ic2:lapatron_crystal'), item(own('cpu')),
    item('ic2:advanced_circuit',2), item(own('titanium_alloy_ingot'),2), item('create:precision_mechanism')],
    ticks=900,eu=96)
processor('advanced_power_bank',[
    item(own('intermediate_power_bank')), item('ic2:glowtronic_crystal'), item('ic2:complex_circuit',2),
    item(own('gpu')), item(own('tungsten_carbide_ingot'),2), item('ic2:carbon_plate',4)],
    ticks=1200,eu=128)
# UESC is IC2 Classic's highest-tier energy crystal. Fuel is loaded separately, never created by crafting.
processor('nuclear_power_bank',[
    item(own('advanced_power_bank')), item('ic2:uesc'), item('ic2:nuclear_reactor'),
    item(own('cpu')), item('ic2:complex_circuit',2), item(own('tungsten_carbide_ingot'),4)],
    ticks=1600,eu=256)
# Late-game handset: six physical fabricator slots, no achievement unlock gate.
processor('industrial_phone',[
    item(own('robot_control_core')), item(own('industrial_ssd'),2), item(own('gpu')),
    item('ic2:uesc'), item('ic2:complex_circuit',2), item(own('advanced_power_bank'))],
    ticks=2400,eu=256)
route('terminal', own('terminal'), [item('minecraft:chest'),item(own('pcb')),item(own('integrated_circuit')),
      item(own('cpu')),item(own('gpu')),item(own('industrial_ssd')),item(own('industrial_fiber')),
      item('create:brass_casing'),item('projecte:dark_matter')],
      'crafting',['minecraft:crafting_table'],ticks=0,eu=0,required_mods=['projecte'])
route('advanced_circuit', 'ic2:advanced_circuit',[item(own('pcb')),item(own('integrated_circuit')),item('ic2:circuit'),item('minecraft:redstone',2)],
      'crafting',['minecraft:crafting_table'],ticks=0,eu=0)
route('complex_circuit', 'ic2:complex_circuit',[item('ic2:advanced_circuit'),item(own('integrated_circuit'),2),item(own('high_purity_silicon')),item(own('pcb'))],
      'crafting',['minecraft:crafting_table'],ticks=0,eu=0)

path=ROOT/'src/main/resources/data/ic2newera/process_graph/main.json'
path.parent.mkdir(parents=True,exist_ok=True)
path.write_bytes((json.dumps(dict(materials=materials,routes=routes),ensure_ascii=False,indent=2)+'\n').encode('utf-8'))
print(f'Wrote {len(materials)} materials / {len(routes)} authoritative routes')
