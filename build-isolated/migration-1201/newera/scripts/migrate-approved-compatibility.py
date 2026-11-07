"""Apply the approved 1.20.1 compatibility design to the authoritative process graph.

Idempotent authoring operation; does not build, run tests, alter an instance or save.
Existing quantities, washing stages and recipes survive namespace migration.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / 'src/main/resources'
NAMES = ('silver_ore', 'deepslate_silver_ore', 'silver_ore_nether', 'raw_silver',
         'aluminium_ore_nether', 'raw_aluminium', 'dust_aluminium', 'ingot_aluminium',
         'bronze_dust_block', 'complex_circuit', 'glowtronic_crystal', 'uesc', 'plate_advanced_alloy')
MAPPING = {'ic2:' + name: 'ic2newera:' + name for name in NAMES}
MAPPING['ic2:dust_diamond'] = 'ic2:diamond_dust'
MAPPING.update({'ic2:alloy_smelter': 'ic2newera:alloy_smelter',
                'ic2:electric_blast_furnace': 'ic2newera:advanced_electric_furnace',
                'ic2:blast_induction_furnace': 'ic2newera:advanced_electric_furnace',
                'ad_astra:engine_fan': 'ad_astra:fan'})


def migrate_space_route(r):
    """Align the campaign with the locked Ad Astra 1.15.19 registry/recipe files."""
    if r['id'].startswith('ad_astra:recipes/'):
        name = r['id'].removeprefix('ad_astra:recipes/')
        folder = r['format'] + '/' if r['format'] in ('smelting', 'blasting') else ''
        r['id'] = 'ad_astra:' + folder + name
    if r['id'] == 'ad_astra:oxygen_distributor':
        for ingredient in r['inputs']:
            if ingredient.get('item') == 'ad_astra:oxygen_tank':
                # The target device uses one large tank, made from two gas tanks plus steel.
                ingredient.update(item='ad_astra:large_gas_tank', count=1)
    old_bypass = 'ad_astra:blasting/steel_ingot_from_blasting_iron_ingot'
    bypass = 'ad_astra:alloying/steel_ingot_from_alloying_iron_ingot_and_coals'
    if r['id'] in (old_bypass, bypass):
        r.update(id=bypass, machines=['ad_astra:etrionic_blast_furnace'],
                 inputs=[item('minecraft:iron_ingot'), dict(tag='minecraft:coals', count=1)],
                 format='alloying', duration_ticks=100, eu_per_tick=-1,
                 native_energy_per_tick=20, native_energy_unit='FE',
                 reason='钢必须先经批准的新纪元合金兼容炉配碳；低碳钢锭可等量转换为原生航天钢锭。')


def remap(value):
    if isinstance(value, str):
        return MAPPING.get(value, value)
    if isinstance(value, list):
        return [remap(x) for x in value]
    if isinstance(value, dict):
        return {key: remap(v) for key, v in value.items()}
    return value


def item(name, count=1):
    return dict(item=name, count=count)


def route(name, inputs, output=None, count=1, fmt='processor', machine='electronic_fabricator',
          ticks=400, eu=32):
    result = dict(id='ic2newera:' + name, material='compatibility_hardware',
                  classification='APPROVED_INDUSTRIAL_PROCESS',
                  machines=[('minecraft:' if machine == 'crafting_table' else 'ic2newera:') + machine],
                  inputs=inputs, output=dict(item=output or 'ic2newera:' + name, count=count),
                  duration_ticks=ticks, eu_per_tick=eu, priority=10, format=fmt)
    if fmt == 'processor':
        result['kind'] = 'electronic'
    return result


def main():
    changed = []
    # Forge dynamic registries are data/<namespace>/forge/biome_modifier in 1.20.1.
    # Repair the four copied legacy paths without replacing later authored files.
    for ore in ('chromium', 'nickel', 'titanium', 'tungsten'):
        old = RESOURCES / f'data/forge/biome_modifier/add_{ore}_ore.json'
        new = RESOURCES / f'data/ic2newera/forge/biome_modifier/add_{ore}_ore.json'
        if old.exists():
            if new.exists():
                raise ValueError('Both biome modifier paths exist: ' + ore)
            new.parent.mkdir(parents=True, exist_ok=True)
            old.rename(new)
            changed.append(str(new.relative_to(ROOT)))
    for file in sorted((RESOURCES / 'data').rglob('*.json')):
        original = json.loads(file.read_text(encoding='utf-8'))
        document = remap(original)
        if file.parent.name == 'process_graph':
            for r in document.get('routes', []):
                migrate_space_route(r)
                r['machines'] = list(dict.fromkeys(r['machines']))
                if r['format'] in ('mixing_furnace', 'blast_furnace'):
                    advanced = r['format'] == 'blast_furnace'
                    # Explicit migration balance, not claimed native Classic equivalence.
                    r['duration_ticks'] = r['duration_ticks'] or (400 if advanced else 200)
                    r['eu_per_tick'] = 32 if advanced else 16
            if file.name == 'main.json':
                additions = [
                    # Bootstrap alloy equipment without requiring an alloy from that same furnace.
                    route('alloy_smelter', [item('minecraft:iron_ingot', 6), item('minecraft:furnace'),
                          item('ic2:circuit'), item('ic2:insulated_copper_cable')],
                          fmt='crafting', machine='crafting_table', ticks=0, eu=0),
                    route('advanced_electric_furnace', [item('ic2newera:invar_ingot', 2),
                          item('ic2newera:nichrome_ingot', 2), item('ic2:electric_furnace'),
                          item('ic2:advanced_circuit'), item('minecraft:bricks', 3)],
                          fmt='crafting', machine='crafting_table', ticks=0, eu=0),
                    # Layered structural component, not a power-storage item.
                    route('plate_advanced_alloy', [item('ic2newera:stainless_steel_ingot', 2),
                          item('ic2:bronze_ingot'), item('ic2:carbon_plate')], count=2, ticks=400, eu=32),
                    route('glowtronic_crystal', [item('ic2:lapotron_crystal'), item('minecraft:glowstone_dust', 4),
                          item('ic2newera:integrated_circuit', 2), item('ic2newera:high_purity_silicon')],
                          ticks=1000, eu=128),
                    route('uesc', [item('ic2newera:glowtronic_crystal', 2), item('ic2:carbon_plate', 2),
                          item('ic2newera:complex_circuit', 2), item('ic2newera:tungsten_carbide_ingot')],
                          ticks=1600, eu=256),
                    # Same-stage interoperability, after the approved steel alloying route.
                    # No raw iron -> steel shortcut and no reverse conversion.
                    route('low_carbon_steel_to_ic2_steel', [item('ic2newera:tempered_steel_ingot')],
                          output='ic2:steel_ingot', fmt='crafting', machine='crafting_table', ticks=0, eu=0),
                ]
                # Create 0.5.1j detects Classic and Refactored by the same mod ID.
                # Preserve its single-yield early smelting using real target items.
                exclusions = []
                for metal, crushed, output in (
                    ('silver', 'silver', 'ic2:silver_ingot'),
                    ('tin', 'tin', 'ic2:tin_ingot'),
                    ('aluminium', 'aluminum', 'ic2newera:ingot_aluminium'),
                    ('uranium', 'uranium', 'ic2:uranium_ingot'),
                ):
                    for fmt, machine, ticks in (('smelting', 'minecraft:furnace', 200),
                                                ('blasting', 'minecraft:blast_furnace', 100)):
                        additions.append(dict(
                            id=f'create:{fmt}/ingot_{metal}_compat_ic2', material=metal,
                            classification='LEGACY_PROCESS', machines=[machine],
                            inputs=[item(f'create:crushed_raw_{crushed}')],
                            output=item(output), duration_ticks=ticks, eu_per_tick=0,
                            priority=100, format=fmt, experience=0.1, required_mods=['create', 'ic2'],
                            reason='保留原生单倍早期熔炼，修正Create对IC2 Classic物品ID的假定；不用于批准工业规划。'))
                    exclusions.append(dict(
                        id=f'create:splashing/ic2/crushed_raw_{crushed}',
                        required_mods=['create', 'ic2'],
                        inputs=[item(f'create:crushed_raw_{crushed}')],
                        original_output=item(f'ic2:nugget_{metal}', 9),
                        reason='Create的Classic兼容金属粒在锁定IC2R中无对应物品；不伪造等价产物，受控矿物仍按批准粉碎和清洗路线加工。'))
                previous = document.get('native_recipe_exclusions', [])
                indexed = {r['id']: r for r in previous}
                for exclusion in exclusions:
                    if exclusion['id'] in indexed and indexed[exclusion['id']] != exclusion:
                        raise ValueError('Existing native recipe exclusion differs: ' + exclusion['id'])
                    indexed[exclusion['id']] = exclusion
                document['native_recipe_exclusions'] = list(indexed.values())
                by_id = {r['id']: r for r in document['routes']}
                for r in additions:
                    # An authored migration route must not silently overwrite a later manual edit.
                    if r['id'] in by_id and by_id[r['id']] != r:
                        raise ValueError('Existing migration route differs: ' + r['id'])
                    if r['id'] not in by_id:
                        document['routes'].append(r)
        if document != original:
            file.write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            changed.append(str(file.relative_to(ROOT)))
    print(json.dumps({'changed': changed, 'mapping': MAPPING}, ensure_ascii=True))


if __name__ == '__main__':
    main()
