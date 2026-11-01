#!/usr/bin/env python3
"""Export authored vanilla-style cuboid pets and model-rendered item icons."""
from pathlib import Path
import hashlib
import json
import runpy

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / 'art/mechanical-pets/voxel'
ASSETS = ROOT / 'build-isolated/migration-1201/newera/src/main/resources/assets/ic2newera'


def main():
    runpy.run_path(str(ROOT / 'scripts/export-mechanical-pet-mesh.py'), run_name='__main__')
    receipt = json.loads((ART / 'item-render-receipt.json').read_text(encoding='utf-8'))
    for row in receipt['items']:
        source = ROOT / row['source']
        icon = ROOT / row['icon']
        if hashlib.sha256(source.read_bytes()).hexdigest() != row['source_sha256'].lower():
            raise ValueError('Re-render the item from the updated model: ' + row['name'])
        if hashlib.sha256(icon.read_bytes()).hexdigest() != row['icon_sha256'].lower():
            raise ValueError('Item render fingerprint differs: ' + row['name'])
        (ASSETS / 'textures/item' / (row['name'] + '.png')).write_bytes(icon.read_bytes())
        model = {'parent': 'minecraft:item/generated',
                 'textures': {'layer0': 'ic2newera:item/' + row['name']}}
        (ASSETS / 'models/item' / (row['name'] + '.json')).write_text(
            json.dumps(model, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Exported vanilla-style cuboid pets and actual model-rendered item icons.')


if __name__ == '__main__':
    main()
