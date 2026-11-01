"""Maintain campaign-owned graph/worldgen resources; does not run Gradle or touch third-party jars."""
import json
from pathlib import Path

BASE = Path(__file__).resolve().parents[1] / 'src/main/resources/data'
N = 'ic2newera:'

def write(path, value):
    target = BASE / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')

def item(name, count=1): return {'item': name, 'count': count}
def tag(name, count=1): return {'tag': name, 'count': count}
routes = []
def route(name, material, fmt, inputs, output, count=1, machines=None, mods=None, legacy=False, **extra):
    default = {'smelting':['minecraft:furnace','ic2:electric_furnace'], 'blasting':['minecraft:blast_furnace'],
        'macerator':['ic2:macerator'], 'crushing':['create:crushing_wheel'], 'splashing':['create:encased_fan'],
        'blast_furnace':['ic2:electric_blast_furnace','ic2:blast_induction_furnace'],
        'mixing_furnace':['ic2:alloy_smelter'], 'crafting':['minecraft:crafting_table'],
        'crafting_shaped':['minecraft:crafting_table'], 'pressing':['create:mechanical_press'],
        'processor':[N+'electronic_fabricator']}
    duration={'smelting':200,'blasting':100,'crushing':250,'processor':600}.get(fmt,0)
    r={'id':name if ':' in name else N+name,'material':material,
       'classification':'LEGACY_PROCESS' if legacy else 'APPROVED_INDUSTRIAL_PROCESS',
       'machines':machines or default[fmt], 'inputs':inputs,'output':item(output,count),
       'duration_ticks':duration,'eu_per_tick':32 if fmt=='processor' else -1,'priority':10,'format':fmt}
    if mods:r['required_mods']=mods
    r.update(extra); routes.append(r); return r

materials=[]
for metal,planet,biomes in [('vanadium','moon',['lunar_wastelands']),('niobium','mars',['martian_wastelands','martian_canyon_creek','martian_polar_caps'])]:
    ore=N+metal+'_ore'; raw=N+'raw_'+metal; impure=N+'impure_'+metal+'_dust'; clean=N+metal+'_dust'; ingot=N+metal+'_ingot'
    materials.append({'id':metal,'advanced':True,'stages':{'RAW':ore,'IMPURE_DUST':impure,'CLEAN_DUST':clean,'FINAL_METAL':ingot},'raw_variants':[raw]})
    for source,suffix in [(ore,'ore'),(raw,'raw')]:
        for fmt in ['macerator','crushing']:
            route(metal+'_'+suffix+'_'+fmt,metal,fmt,[item(source)],impure,2)
        for fmt in ['smelting','blasting']:
            route(metal+'_'+suffix+'_legacy_'+fmt,metal,fmt,[item(source)],ingot,legacy=True)
    route(metal+'_wash',metal,'splashing',[item(impure)],clean)
    route(metal+'_industrial_smelt',metal,'blast_furnace',[item(clean)],ingot)
    # Recycling stays within the final/clean stages, with no multiplicative return.
    route(metal+'_recycle',metal,'macerator',[item(ingot)],clean)
    for kind,values in [('ores',[ore]),('raw_materials',[raw]),('dusts',[clean]),('ingots',[ingot])]:
        write(f'forge/tags/items/{kind}/{metal}.json',{'replace':False,'values':values})
    write(f'forge/tags/blocks/ores/{metal}.json',{'replace':False,'values':[ore]})
    write(f'ic2newera/tags/worldgen/biome/{planet}_frontier_ores.json',{'replace':False,'values':[{'id':'ad_astra:'+b,'required':False} for b in biomes]})
    write(f'ic2newera/worldgen/configured_feature/{metal}_ore.json',{'type':'minecraft:ore','config':{'size':4,'discard_chance_on_air_exposure':0.0,
        'targets':[{'target':{'predicate_type':'minecraft:tag_match','tag':'ad_astra:'+planet+'_ore_replaceables'},'state':{'Name':ore}}]}})
    write(f'ic2newera/worldgen/placed_feature/{metal}_ore.json',{'feature':ore,'placement':[{'type':'minecraft:count','count':3},
        {'type':'minecraft:in_square'},{'type':'minecraft:height_range','height':{'type':'minecraft:uniform','min_inclusive':{'absolute':-48},'max_inclusive':{'absolute':48}}},{'type':'minecraft:biome'}]})
    write(f'ic2newera/forge/biome_modifier/add_{metal}_ore.json',{'type':'forge:add_features','biomes':'#ic2newera:'+planet+'_frontier_ores','features':ore,'step':'underground_ores'})
    silk={'condition':'minecraft:match_tool','predicate':{'enchantments':[{'enchantment':'minecraft:silk_touch','levels':{'min':1}}]}}
    write(f'ic2newera/loot_tables/blocks/{metal}_ore.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:alternatives','children':[
        {'type':'minecraft:item','name':ore,'conditions':[silk]}, {'type':'minecraft:item','name':raw,'functions':[{'function':'minecraft:apply_bonus','enchantment':'minecraft:fortune','formula':'minecraft:ore_drops'},{'function':'minecraft:explosion_decay'}]}]}]}]})

for name,inputs in [('vanadium_steel',[item(N+'vanadium_dust'),item(N+'high_carbon_steel_dust',3)]),('niobium_titanium',[item(N+'niobium_dust'),item(N+'titanium_dust',3)])]:
    route(name+'_alloy',name,'mixing_furnace',inputs,N+name+'_dust',4)
    route(name+'_smelt',name,'blast_furnace',[item(N+name+'_dust')],N+name+'_ingot')
    route(name+'_recycle',name,'macerator',[item(N+name+'_ingot')],N+name+'_dust')
    for kind in ['dusts','ingots']:write(f'forge/tags/items/{kind}/{name}.json',{'replace':False,'values':[N+name+('_dust' if kind=='dusts' else '_ingot')]})
    keys={'I':item(N+name+'_ingot'),'S':item('minecraft:stick'),'C':item(N+('pcb' if name=='vanadium_steel' else 'integrated_circuit'))}
    patterns={'sword':[' I ',' I ',' C '], 'pickaxe':['III',' C ',' S '], 'axe':['II ','IC ',' S '], 'shovel':[' I ',' C ',' S '],
        'hoe':['II ',' C ',' S '], 'helmet':['III','ICI'], 'chestplate':['ICI','III','III'], 'leggings':['III','ICI','I I'], 'boots':['I I','ICI']}
    for gear,pattern in patterns.items():
        counts={c:sum(row.count(c) for row in pattern) for c in keys}
        used={c:keys[c] for c in keys if counts[c]}
        route(name+'_'+gear,'frontier_equipment','crafting_shaped',[dict(keys[c],count=counts[c]) for c in used],N+name+'_'+gear,pattern=pattern,keys=used)

# Native extraterrestrial materials keep their real direct-smelting/plate identity; no invented dust stage.
for metal,planet in [('desh','moon'),('ostrum','mars')]:
    for source in ['raw_'+metal,planet+'_'+metal+'_ore','deepslate_'+metal+'_ore']:
        for fmt in ['smelting','blasting']:
            route(f'ad_astra:recipes/{metal}_ingot_from_{fmt}_{source}','extraterrestrial_'+metal,fmt,[item('ad_astra:'+source)],'ad_astra:'+metal+'_ingot',mods=['ad_astra'])
    route(f'create:pressing/{metal}_ingot','extraterrestrial_'+metal,'pressing',[tag('ad_astra_platform:'+metal+'_ingots')],'ad_astra:'+metal+'_plate',mods=['ad_astra','create'])

# Equivalent finished steel conversion preserves every native steel part without restoring iron -> steel.
route('campaign_steel_exchange','campaign_steel','crafting',[item(N+'tempered_steel_ingot')],'ad_astra:steel_ingot',mods=['ad_astra'])
for metal,reinforcement,electronic in [('steel','titanium_ingot','cpu'),('desh','vanadium_steel_ingot','cpu'),('ostrum','niobium_titanium_ingot','gpu')]:
    route('ad_astra:recipes/'+metal+'_engine','campaign_hardware','processor',[item('ad_astra:'+metal+'_plate',3),item('ad_astra:engine_frame'),item('ad_astra:engine_fan'),item(N+reinforcement,2),item(N+electronic)],'ad_astra:'+metal+'_engine',mods=['ad_astra'],kind='electronic')
route('ad_astra:recipes/oxygen_distributor','campaign_hardware','processor',[item('ad_astra:engine_fan',3),item('ad_astra:oxygen_tank',2),item('ad_astra:desh_plate',2),item('ad_astra:oxygen_gear'),item('ad_astra:oxygen_loader'),item(N+'cpu')],'ad_astra:oxygen_distributor',mods=['ad_astra'],kind='electronic')

blocked=route('ad_astra:recipes/steel_ingot_from_blasting_iron_ingot','campaign_steel','blasting',[item('minecraft:iron_ingot')],'ad_astra:steel_ingot',mods=['ad_astra'],
    disable_native=True,reason='钢必须先经批准的IC2合金炉配碳；现有低碳钢锭可等量转换为原生航天钢锭。')
blocked['classification']='BLOCKED_BYPASS'
write('ic2newera/process_graph/campaign.json',{'materials':materials,'routes':routes})

def append_tag(path, values):
    file=BASE/path
    data=json.loads(file.read_text(encoding='utf-8')) if file.exists() else {'replace':False,'values':[]}
    data['values'] += [v for v in values if v not in data['values']]
    write(path,data)
append_tag('minecraft/tags/blocks/mineable/pickaxe.json',[N+'vanadium_ore',N+'niobium_ore'])
append_tag('minecraft/tags/blocks/needs_diamond_tool.json',[N+'vanadium_ore'])
append_tag('forge/tags/blocks/needs_netherite_tool.json',[N+'niobium_ore'])
append_tag('forge/tags/blocks/ores.json',[N+'vanadium_ore',N+'niobium_ore'])
append_tag('forge/tags/items/ores.json',[N+'vanadium_ore',N+'niobium_ore'])
for kind in ['raw_materials','dusts','ingots']:
    names=['vanadium','niobium']+(['vanadium_steel','niobium_titanium'] if kind!='raw_materials' else [])
    append_tag(f'forge/tags/items/{kind}.json',['#forge:'+kind+'/'+name for name in names])
for tier in ['vanadium_steel','niobium_titanium']:
    write(f'ic2newera/tags/blocks/needs_{tier}_tool.json',{'replace':False,'values':[]})
print(f'Wrote campaign integration: {len(materials)} materials, {len(routes)} approved/legacy routes; original graph unchanged.')

