"""Original cuboid expression variants. Preserve every non-mouth/non-brow cube."""
from __future__ import annotations
import copy, json, math
from pathlib import Path
import export_robot_runtime_models as pipeline

ROOT = pipeline.ROOT
SOURCE = ROOT / 'build-isolated/migration-1201/robot-expression-r20/anon-expression-runtime.bbmodel'
WORK = ROOT / 'art/robot-mcp-workspace/characters/anon-expression-r21'
TARGET = ROOT / 'build-isolated/migration-1201/newera/src/main/resources/assets/ic2newera'
PARTS = (*pipeline.PARTS, 'mouth_neutral', 'mouth_smile')

def transform_cube(e, center, scale=(1,1,1), dy=0, rz=0):
    for field in ('from', 'to', 'origin'):
        if field in e:
            e[field] = [center[i] + (e[field][i]-center[i])*scale[i] + (dy if i==1 else 0) for i in range(3)]
    rotation = e.get('rotation', [0,0,0]).copy()
    rotation[2] += rz
    e['rotation'] = rotation

def main():
    base = json.loads(SOURCE.read_text(encoding='utf-8'))
    originals = {e['uuid']: e for e in base['elements']}
    pipeline.PARTS = PARTS
    pipeline.MAGIC = b'NER20M01'  # Same rigid rig / validated decoder; revision is the resource path.
    pipeline.MODEL_DIR = TARGET / 'robot_models/r21'
    pipeline.TEXTURE_DIR = TARGET / 'textures/entity/robot/r21'
    WORK.mkdir(parents=True, exist_ok=True)
    facts = []
    for expression in ('soft_smile', 'happy', 'curious', 'serious', 'worried'):
        model = copy.deepcopy(base)
        for e in model['elements']:
            name = e['name']
            if 'brow_' in name:
                left = '_left_' in name
                center = [-1.3 if left else 1.3, 23.66, -3.34]
                if expression == 'happy': transform_cube(e, center, dy=.055, rz=2 if left else -2)
                if expression == 'curious': transform_cube(e, center, dy=.09 if left else .015, rz=5 if left else 0)
                if expression == 'serious': transform_cube(e, center, dy=-.025, rz=3 if left else -3)
                if expression == 'worried': transform_cube(e, center, dy=.035, rz=11 if left else -11)
            if name.startswith('r20_smile_') and expression == 'happy':
                transform_cube(e, [0,21.295,-3.09], (1.12,1.35,1))
            if name == 'r15_mouth_single_rectangle' and expression == 'curious':
                transform_cube(e, [0,21.295,-3.09], (.55,1.9,1))
            if name.startswith('r20_smile_') and expression == 'worried':
                # A 180-degree rigid Z rotation makes a restrained frown; no inverted faces.
                for field in ('from','to','origin'):
                    if field not in e: continue
                    e[field][0] = -e[field][0]
                    e[field][1] = 2*21.295-e[field][1]
                e['from'], e['to'] = ([min(a,b) for a,b in zip(e['from'],e['to'])],
                                     [max(a,b) for a,b in zip(e['from'],e['to'])])
                # Opposite corners exchange sides while preserving their original slope.
            if not ('brow_' in name or 'mouth' in name or 'smile' in name) and e != originals[e['uuid']]:
                raise ValueError('Unrelated geometry changed')
        source = WORK / f'anon-{expression}.bbmodel'
        source.write_text(json.dumps(model,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
        fact = pipeline.export_character(f'anon_{expression}', source, pipeline.digest(source.read_bytes()))
        fact['expression'] = expression
        fact['smile_part'] = expression in ('soft_smile','happy','worried')
        fact['non_face_elements_unchanged'] = True
        facts.append(fact)
    manifest = dict(revision='r21', format='NER20M01', parts=PARTS,
                    source_sha256=pipeline.digest(SOURCE.read_bytes()), expressions=facts)
    (pipeline.MODEL_DIR/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'variants':len(facts),'non_face_elements_unchanged':True}))

if __name__ == '__main__': main()
