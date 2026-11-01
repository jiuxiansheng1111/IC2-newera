"""Project-owned materials/recipes/worldgen; uses existing machines and the shared process graph."""
import json
from pathlib import Path

BASE=Path(__file__).resolve().parents[1]/'src/main/resources/data'
N='ic2newera:'
def write(path,data):
    p=BASE/path;p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def append(path,values):
    p=BASE/path;d=json.loads(p.read_text(encoding='utf-8-sig')) if p.exists() else {'replace':False,'values':[]}
    d['values'] += [v for v in values if v not in d['values']];write(path,d)
def item(name,count=1):return {'item':name,'count':count}
def tag(name,count=1):return {'tag':name,'count':count}
materials=[];routes=[]
machines={'macerator':['ic2:macerator'],'crushing':['create:crushing_wheel'],'splashing':['create:encased_fan'],
 'smelting':['minecraft:furnace','ic2:electric_furnace'],'blasting':['minecraft:blast_furnace'],
 'blast_furnace':['ic2:electric_blast_furnace','ic2:blast_induction_furnace'],
 'pressing':['create:mechanical_press'],'processor':[N+'electronic_fabricator'],'crafting_shaped':['minecraft:crafting_table']}
def route(name,material,fmt,inputs,output,count=1,legacy=False,**extra):
    r={'id':N+name,'material':material,'classification':'LEGACY_PROCESS' if legacy else 'APPROVED_INDUSTRIAL_PROCESS',
       'machines':machines[fmt], 'inputs':inputs,'output':item(output,count),
       'duration_ticks':{'smelting':200,'blasting':100,'crushing':250,'processor':400}.get(fmt,0),
       'eu_per_tick':32 if fmt=='processor' else -1,'priority':10,'format':fmt}
    if fmt=='processor':r['kind']='electronic'
    r.update(extra);routes.append(r);return r

silk={'condition':'minecraft:match_tool','predicate':{'enchantments':[{'enchantment':'minecraft:silk_touch','levels':{'min':1}}]}}
for metal,planet in [('cobalt','moon'),('molybdenum','moon'),('tantalum','mars')]:
    ore=N+metal+'_ore';raw=N+'raw_'+metal;impure=N+'impure_'+metal+'_dust';clean=N+metal+'_dust';ingot=N+metal+'_ingot'
    materials.append({'id':metal,'advanced':True,'stages':{'RAW':ore,'IMPURE_DUST':impure,'CLEAN_DUST':clean,'FINAL_METAL':ingot},'raw_variants':[raw]})
    for source,label in [(ore,'ore'),(raw,'raw')]:
        for fmt in ['macerator','crushing']:route(metal+'_'+label+'_'+fmt,metal,fmt,[item(source)],impure,2)
        for fmt in ['smelting','blasting']:route(metal+'_'+label+'_legacy_'+fmt,metal,fmt,[item(source)],ingot,legacy=True)
    route(metal+'_wash',metal,'splashing',[item(impure)],clean)
    route(metal+'_industrial_smelt',metal,'blast_furnace',[item(clean)],ingot)
    route(metal+'_recycle',metal,'macerator',[item(ingot)],clean,priority=30)
    for kind,value in [('ores',ore),('raw_materials',raw),('dusts',clean),('ingots',ingot)]:
        write(f'forge/tags/items/{kind}/{metal}.json',{'replace':False,'values':[value]})
        append(f'forge/tags/items/{kind}.json',['#forge:'+kind+'/'+metal])
    write(f'forge/tags/blocks/ores/{metal}.json',{'replace':False,'values':[ore]})
    append('forge/tags/blocks/ores.json',[ore]);append('minecraft/tags/blocks/mineable/pickaxe.json',[ore])
    append('forge/tags/blocks/needs_netherite_tool.json' if metal=='tantalum' else 'minecraft/tags/blocks/needs_diamond_tool.json',[ore])
    write(f'ic2newera/worldgen/configured_feature/{metal}_ore.json',{'type':'minecraft:ore','config':{'size':4,'discard_chance_on_air_exposure':0.0,
        'targets':[{'target':{'predicate_type':'minecraft:tag_match','tag':'ad_astra:'+planet+'_ore_replaceables'},'state':{'Name':ore}}]}})
    write(f'ic2newera/worldgen/placed_feature/{metal}_ore.json',{'feature':ore,'placement':[{'type':'minecraft:count','count':3},{'type':'minecraft:in_square'},
        {'type':'minecraft:height_range','height':{'type':'minecraft:uniform','min_inclusive':{'absolute':-48},'max_inclusive':{'absolute':48}}},{'type':'minecraft:biome'}]})
    write(f'ic2newera/forge/biome_modifier/add_{metal}_ore.json',{'type':'forge:add_features','biomes':'#ic2newera:'+planet+'_frontier_ores','features':ore,'step':'underground_ores'})
    write(f'ic2newera/loot_tables/blocks/{metal}_ore.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:alternatives','children':[
        {'type':'minecraft:item','name':ore,'conditions':[silk]}, {'type':'minecraft:item','name':raw,'functions':[{'function':'minecraft:apply_bonus','enchantment':'minecraft:fortune','formula':'minecraft:ore_drops'},{'function':'minecraft:explosion_decay'}]}]}]}]})

# A real up-facing amethyst-style cluster needs support. Heightmap + survival/air predicates prevent floating ore replacement.
cluster={'Name':N+'resonance_cluster','Properties':{'facing':'up','waterlogged':'false'}}
write('ic2newera/worldgen/configured_feature/resonance_cluster.json',{'type':'minecraft:simple_block','config':{'to_place':{'type':'minecraft:simple_state_provider','state':cluster}}})
write('ic2newera/worldgen/placed_feature/resonance_cluster.json',{'feature':N+'resonance_cluster','placement':[{'type':'minecraft:count','count':3},
    {'type':'minecraft:in_square'},{'type':'minecraft:heightmap','heightmap':'OCEAN_FLOOR_WG'},
    {'type':'minecraft:block_predicate_filter','predicate':{'type':'minecraft:all_of','predicates':[
        {'type':'minecraft:matching_blocks','blocks':['minecraft:air']},{'type':'minecraft:would_survive','state':cluster}]}},{'type':'minecraft:biome'}]})
write('ic2newera/forge/biome_modifier/add_resonance_cluster.json',{'type':'forge:add_features','biomes':'#ic2newera:mars_frontier_ores','features':N+'resonance_cluster','step':'vegetal_decoration'})
write('ic2newera/loot_tables/blocks/resonance_cluster.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:alternatives','children':[
    {'type':'minecraft:item','name':N+'resonance_cluster','conditions':[silk]},
    {'type':'minecraft:item','name':N+'resonance_shard','functions':[{'function':'minecraft:set_count','count':2},{'function':'minecraft:apply_bonus','enchantment':'minecraft:fortune','formula':'minecraft:ore_drops'},{'function':'minecraft:explosion_decay'}]}]}]}]})
append('minecraft/tags/blocks/mineable/pickaxe.json',[N+'resonance_cluster']);append('minecraft/tags/blocks/needs_diamond_tool.json',[N+'resonance_cluster'])
append('forge/tags/blocks/ores.json',[N+'resonance_cluster']);append('forge/tags/items/ores.json',[N+'resonance_cluster'])
write('forge/tags/items/gems/resonance.json',{'replace':False,'values':[N+'resonance_shard']});append('forge/tags/items/gems.json',['#forge:gems/resonance'])

route('magnetic_coil','arsenal_components','processor',[item(N+'cobalt_ingot',2),tag('forge:ingots/copper',2),item(N+'pcb')],N+'magnetic_coil',2)
route('heat_resistant_plate','arsenal_components','pressing',[item(N+'molybdenum_ingot')],N+'heat_resistant_plate')
route('pulse_capacitor','arsenal_components','processor',[item(N+'tantalum_ingot',2),item(N+'high_purity_silicon'),item(N+'pcb')],N+'pulse_capacitor',2)
route('resonance_core','arsenal_components','processor',[item(N+'resonance_shard',2)],N+'resonance_core',kind='non_metal',machines=[N+'non_metal_processor'])
route('blade_stabilizer','arsenal_components','processor',[item(N+'magnetic_coil',2),item(N+'heat_resistant_plate',2),item(N+'pulse_capacitor',2),item(N+'integrated_circuit')],N+'blade_stabilizer')
route('servo_armor_joint','arsenal_components','processor',[item(N+'cobalt_ingot'),item(N+'molybdenum_ingot'),item(N+'tantalum_ingot'),item('create:precision_mechanism'),item(N+'integrated_circuit')],N+'servo_armor_joint',2)
for family,metal,core in [('magnetic_armor','cobalt_ingot','magnetic_coil'),('resonance_armor','tantalum_ingot','resonance_core')]:
    keys={'M':item(N+metal),'C':item(N+core),'J':item(N+'servo_armor_joint'),'P':item(N+'pulse_capacitor'),'H':item(N+'heat_resistant_plate'),'S':item(N+'blade_stabilizer')}
    patterns={'helmet':['MMM','MCM'],'chestplate':['MPM','SJS','MMM'],'leggings':['MMM','MHM','M M'],'boots':['M M','MCM']}
    for part,pattern in patterns.items():
        counts={k:sum(row.count(k) for row in pattern) for k in keys};used={k:keys[k] for k in keys if counts[k]}
        route(family+'_'+part,'arsenal_armor','crafting_shaped',[dict(keys[k],count=counts[k]) for k in used],N+family+'_'+part,pattern=pattern,keys=used)
write('ic2newera/process_graph/arsenal.json',{'materials':materials,'routes':routes})
print(f'Generated arsenal: {len(materials)} controlled metals, {len(routes)} routes; crystal is deliberately not a metal stage.')
