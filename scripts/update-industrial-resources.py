"""Maintain names, mining tags and drops for the implemented registries."""
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[1]/'src/main/resources'
def write(path, value):
    path=ROOT/path; path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
names={}
for en,zh in [('iron','铁'),('gold','金'),('copper','铜'),('coal','煤'),('diamond','钻石'),
              ('quartz','石英'),('tin','锡'),('silver','银'),('aluminium','铝'),('zinc','锌')]:
    names['item.ic2newera.impure_'+en+'_dust']='含杂质的'+zh+'粉'
blocks=[]
for en,zh in [('nickel','镍'),('chromium','铬'),('titanium','钛'),('tungsten','钨')]:
    names['block.ic2newera.'+en+'_ore']=zh+'矿石'; blocks.append(en+'_ore')
    for suffix,label in [('ingot','锭'),('dust','粉')]: names['item.ic2newera.'+en+'_'+suffix]=zh+label
    names['item.ic2newera.impure_'+en+'_dust']='含杂质的'+zh+'粉'
    write(Path('assets/ic2newera/blockstates')/(en+'_ore.json'),{'variants':{'':{'model':'ic2newera:block/'+en+'_ore'}}})
for en,zh in {
 'quartz_dust':'石英粉','silicon':'硅','high_purity_silicon':'高纯硅','pcb':'印刷电路板',
 'integrated_circuit':'集成电路','cpu':'中央处理器','ram':'内存模块','gpu':'图形处理器','industrial_ssd':'工业固态存储器',
 'industrial_ai_core':'工业AI核心', 'stainless_steel_ingot':'不锈钢锭','invar_ingot':'因瓦合金锭',
 'nichrome_ingot':'镍铬合金锭','tool_steel_ingot':'工具钢锭','titanium_alloy_ingot':'钛合金锭','tungsten_carbide_ingot':'碳化钨锭',
 'low_carbon_steel_dust':'低碳钢粉','high_carbon_steel_dust':'高碳钢粉',
 'tempered_steel_ingot':'低碳钢锭','tempered_high_carbon_steel_ingot':'高碳钢锭',
}.items(): names['item.ic2newera.'+en]=zh
for en,zh in {'non_metal_processor':'非金属处理机','electronic_fabricator':'电子材料加工机',
    'machine_network_adapter':'机器网络适配器','industrial_fiber':'工业光纤',
    'industrial_network_switch':'工业网络交换机','industrial_control_server':'工业控制服务器','terminal':'终端机'}.items():
    names['block.ic2newera.'+en]=zh; blocks.append(en)
    write(Path('data/ic2newera/loot_tables/blocks')/(en+'.json'),{
      'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'ic2newera:'+en}],
      'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
for lang in ['zh_cn','en_us']:
    path=ROOT/'assets/ic2newera/lang'/(lang+'.json')
    data=json.loads(path.read_text(encoding='utf-8-sig'))
    for retired in ('carbon_steel_billet','high_carbon_steel_billet'):
        data.pop('item.ic2newera.'+retired,None)
    for k,v in names.items(): data[k]=v if lang=='zh_cn' else k.split('.')[-1].replace('_',' ').title()
    write(path.relative_to(ROOT),data)
write(Path('data/minecraft/tags/blocks/mineable/pickaxe.json'),{'replace':False,'values':['ic2newera:'+s for s in blocks]})
write(Path('data/minecraft/tags/blocks/needs_iron_tool.json'),{'replace':False,'values':['ic2newera:'+s for s in blocks if s.endswith('_ore')]})
for a in ('stainless_steel','invar','nichrome','tool_steel','titanium_alloy','tungsten_carbide'):
    write(Path('data/forge/tags/items/ingots')/(a+'.json'),{'replace':False,'values':['ic2newera:'+a+'_ingot']})
for grade,ingot in [('low','tempered_steel_ingot'),('high','tempered_high_carbon_steel_ingot')]:
    write(Path('data/forge/tags/items/dusts')/(grade+'_carbon_steel.json'),
          {'replace':False,'values':['ic2newera:'+grade+'_carbon_steel_dust']})
    write(Path('data/forge/tags/items/ingots')/(grade+'_carbon_steel.json'),
          {'replace':False,'values':['ic2newera:'+ingot]})
for category,suffix in [('ores','_ore'),('ingots','_ingot'),('dusts','_dust')]:
    values=['ic2newera:'+metal+suffix for metal in ('nickel','chromium','titanium','tungsten')]
    if category=='ingots': values+=['ic2newera:'+name+'_ingot' for name in ('stainless_steel','invar','nichrome','tool_steel','titanium_alloy','tungsten_carbide')]
    if category=='dusts': values+=['ic2newera:'+s for s in ('quartz_dust','zinc_dust','low_carbon_steel_dust','high_carbon_steel_dust')]
    if category=='ingots': values+=['ic2newera:tempered_steel_ingot','ic2newera:tempered_high_carbon_steel_ingot','ic2newera:aluminium_bronze_ingot']
    write(Path('data/forge/tags/items')/(category+'.json'),{'replace':False,'values':values})
write(Path('data/forge/tags/blocks/ores.json'),{'replace':False,'values':['ic2newera:'+m+'_ore' for m in ('nickel','chromium','titanium','tungsten')]})
print(f'Updated {len(names)} translations and {len(blocks)} block mining/drop entries')
