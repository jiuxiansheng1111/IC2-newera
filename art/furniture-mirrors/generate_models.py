from __future__ import annotations
import base64, json
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[2]
SRC_DIR = PROJECT / 'art' / 'furniture-mirrors'
DEST = PROJECT / 'build-isolated/migration-1201/newera/src/main/resources/assets/ic2newera'
MC_LOOT = PROJECT / 'build-isolated/migration-1201/newera/src/main/resources/data/ic2newera/loot_tables/blocks'
MC_BLOCKSTATES, MC_BLOCK_MODELS = DEST / 'blockstates', DEST / 'models/block'
MC_ITEM_MODELS, MC_TEXTURES = DEST / 'models/item', DEST / 'textures/block'
for path in (MC_LOOT, MC_BLOCKSTATES, MC_BLOCK_MODELS, MC_ITEM_MODELS, MC_TEXTURES):
    path.mkdir(parents=True, exist_ok=True)

def load_source(name):
    data = json.loads((SRC_DIR / f'{name}.bbmodel').read_text(encoding='utf-8'))
    texture_names = []
    for texture in data['textures']:
        texture_names.append(texture['name'])
        source = texture['source']
        if source.startswith('data:image/png;base64,'):
            (MC_TEXTURES / f'{texture["name"]}.png').write_bytes(base64.b64decode(source.split(',', 1)[1]))
    return data, texture_names

def emit_element(cube, texture_names, y_offset=0, y_clip=None):
    start, end = list(cube['from']), list(cube['to'])
    if y_clip:
        start[1], end[1] = max(start[1], y_clip[0]), min(end[1], y_clip[1])
        if end[1] <= start[1]: return None
    start[1] -= y_offset; end[1] -= y_offset
    indices = {cube['faces'][face].get('texture') for face in ('north','east','south','west','up','down')}
    indices.discard(None)
    key = texture_names[next(iter(indices))] if indices else 'ivory_wood'
    faces = {side: {'uv':[0,0,16,16], 'texture':'#'+key} for side in ('north','east','south','west','up','down')}
    return {'from':start,'to':end,'faces':faces}

def save_model(path, texture_names, cubes, y_offset=0, y_clip=None):
    elements = [element for cube in cubes if (element := emit_element(cube, texture_names, y_offset, y_clip))]
    textures = {name:f'ic2newera:block/{name}' for name in texture_names}
    textures['particle'] = 'ic2newera:block/ivory_wood'
    obj = {'parent':'minecraft:block/block',
           'credit':'Editable cuboids authored with Blockbench MCP; exported from art/furniture-mirrors.',
           'ambientocclusion':True,'textures':textures,'elements':elements}
    if path.stem == 'floor_mirror_inventory':
        # Keep a two-block furniture model at a normal held-item / inventory size.
        obj['display'] = {
            'gui':{'rotation':[30,225,0],'translation':[0,-3,0],'scale':[.3,.3,.3]},
            'ground':{'translation':[0,3,0],'scale':[.2,.2,.2]},
            'fixed':{'translation':[0,-3,0],'scale':[.35,.35,.35]},
            'thirdperson_righthand':{'rotation':[75,45,0],'translation':[0,1,0],'scale':[.2,.2,.2]},
            'thirdperson_lefthand':{'rotation':[75,225,0],'translation':[0,1,0],'scale':[.2,.2,.2]},
            'firstperson_righthand':{'rotation':[0,45,0],'translation':[0,1,0],'scale':[.2,.2,.2]},
            'firstperson_lefthand':{'rotation':[0,225,0],'translation':[0,1,0],'scale':[.2,.2,.2]}}
    path.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

floor, texture_names = load_source('floor_mirror')
save_model(MC_BLOCK_MODELS/'floor_mirror_lower.json', texture_names, floor['elements'], y_clip=(0,16))
save_model(MC_BLOCK_MODELS/'floor_mirror_upper.json', texture_names, floor['elements'], y_offset=16, y_clip=(16,32))
save_model(MC_BLOCK_MODELS/'floor_mirror_inventory.json', texture_names, floor['elements'])
dressing, texture_names = load_source('dressing_mirror')
save_model(MC_BLOCK_MODELS/'dressing_mirror.json', texture_names, dressing['elements'])

floor_states = {}
for half in ('lower','upper'):
    for facing, rotation in (('north',0),('east',90),('south',180),('west',270)):
        floor_states[f'half={half},facing={facing}'] = {'model':f'ic2newera:block/floor_mirror_{half}','y':rotation,'uvlock':True}
(MC_BLOCKSTATES/'floor_mirror.json').write_text(json.dumps({'variants':floor_states},indent=2)+'\n',encoding='utf-8')
dressing_states = {f'facing={facing}':{'model':'ic2newera:block/dressing_mirror','y':rotation,'uvlock':True}
                   for facing,rotation in (('north',0),('east',90),('south',180),('west',270))}
(MC_BLOCKSTATES/'dressing_mirror.json').write_text(json.dumps({'variants':dressing_states},indent=2)+'\n',encoding='utf-8')
(MC_ITEM_MODELS/'floor_mirror.json').write_text(json.dumps({'parent':'ic2newera:block/floor_mirror_inventory'},indent=2)+'\n',encoding='utf-8')
(MC_ITEM_MODELS/'dressing_mirror.json').write_text(json.dumps({'parent':'ic2newera:block/dressing_mirror'},indent=2)+'\n',encoding='utf-8')
floor_loot = {'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'ic2newera:floor_mirror'}],
                 'conditions':[{'condition':'minecraft:block_state_property','block':'ic2newera:floor_mirror','properties':{'half':'lower'}}]}]}
(MC_LOOT/'floor_mirror.json').write_text(json.dumps(floor_loot,indent=2)+'\n',encoding='utf-8')
dressing_loot = {'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'ic2newera:dressing_mirror'}]}]}
(MC_LOOT/'dressing_mirror.json').write_text(json.dumps(dressing_loot,indent=2)+'\n',encoding='utf-8')
print('Generated blockstates/models/items/loot/textures from the Blockbench MCP source files.')
