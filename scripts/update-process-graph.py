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

route('quartz_macerate',own('quartz_dust'),[item('minecraft:quartz')],'macerator',['ic2:macerator'],ticks=0)
route('quartz_crush',own('quartz_dust'),[item('minecraft:quartz')],'crushing',['create:crushing_wheel'],ticks=200)
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
path.write_text(json.dumps(dict(materials=materials,routes=routes),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(f'Wrote {len(materials)} materials / {len(routes)} authoritative routes')
