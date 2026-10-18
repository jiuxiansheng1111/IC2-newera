"""Generate companion crafting and translations from the shared Java character catalog.

These are ordinary doll assembly recipes, not controlled mineral production routes.
No research prerequisite, custom advancement gate or material-processing machine is added.
"""
import json
import re
import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'src/main/resources'
catalog = (ROOT / 'src/main/java/com/ic2newera/robot/RobotCharacter.java').read_text(encoding='utf-8')
characters = re.findall(r'\("([a-z]+)", "([^"]+)", "([a-z_]+)"\)', catalog)
if len(characters) != 8 or len({dye for _, _, dye in characters}) != 8:
    raise ValueError('Expected eight distinct characters and dyes')

def write(path, value):
    target = RES / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def recipe(name, ingredients, output):
    write(f'data/ic2newera/recipes/robots/{name}.json', {
        'type': 'minecraft:crafting_shapeless', 'ingredients': ingredients,
        'result': {'item': 'ic2newera:' + output, 'count': 1}})
    book_item = 'minecraft:paper' if name == 'blueprint' else 'ic2newera:robot_blueprint'
    write(f'data/ic2newera/advancements/recipes/robots/{name}.json', {
        'criteria': {'has_material': {'trigger': 'minecraft:inventory_changed',
            'conditions': {'items': [{'items': [book_item]}]}}},
        'rewards': {'recipes': ['ic2newera:robots/' + name]}})

def draw_texture(filename, draw, size=16):
    pixels = [[(0, 0, 0, 0) for _ in range(size)] for _ in range(size)]
    def rect(x0, y0, x1, y1, color):
        for y in range(y0, y1):
            for x in range(x0, x1):
                pixels[y][x] = color
    draw(rect)
    import struct
    import zlib
    raw = b''.join(b'\x00' + bytes(channel for pixel in row for channel in pixel) for row in pixels)
    def chunk(kind, payload):
        return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload) & 0xffffffff)
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    target = RES / 'assets/ic2newera/textures/item' / filename
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(png)

def core_icon(rect):
    dark, edge, pin, cyan, light = (35, 43, 60, 255), (106, 123, 141, 255), (198, 206, 204, 255), (24, 178, 186, 255), (137, 246, 230, 255)
    for y in (3, 6, 9, 12): rect(1, y, 4, y + 1, pin); rect(12, y, 15, y + 1, pin)
    for x in (3, 6, 9, 12): rect(x, 1, x + 1, 4, pin); rect(x, 12, x + 1, 15, pin)
    rect(3, 3, 13, 13, dark); rect(4, 4, 12, 12, edge); rect(5, 5, 11, 11, dark)
    rect(6, 6, 10, 10, cyan); rect(7, 7, 9, 9, light); rect(6, 10, 10, 11, (16, 104, 131, 255))

def actuator_icon(rect):
    steel, shadow, brass, gold, hole = (166, 178, 184, 255), (69, 82, 94, 255), (151, 102, 43, 255), (235, 186, 81, 255), (34, 43, 51, 255)
    rect(2, 5, 14, 11, shadow); rect(3, 4, 13, 12, steel); rect(4, 5, 12, 11, brass)
    rect(1, 6, 4, 10, gold); rect(12, 6, 15, 10, gold)
    rect(6, 2, 10, 14, shadow); rect(7, 3, 9, 13, steel); rect(7, 7, 9, 9, hole)
    rect(2, 7, 3, 9, hole); rect(13, 7, 14, 9, hole); rect(8, 4, 9, 6, gold); rect(8, 10, 9, 12, gold)

def battery_icon(rect):
    outline, body, shade = (53, 61, 77, 255), (99, 120, 148, 255), (39, 50, 68, 255)
    rect(6, 2, 10, 4, (208, 188, 102, 255)); rect(5, 4, 11, 14, outline)
    rect(6, 5, 10, 13, body); rect(7, 6, 9, 7, (74, 223, 179, 255))
    rect(7, 8, 9, 9, (70, 194, 176, 255)); rect(7, 10, 9, 12, (72, 162, 186, 255))
    rect(6, 13, 10, 14, shade); rect(3, 7, 5, 12, (177, 190, 203, 255)); rect(11, 7, 13, 12, (177, 190, 203, 255))

def power_bank_icon(rect):
    rect(3, 3, 13, 14, (39, 53, 63, 255)); rect(4, 4, 12, 13, (138, 161, 165, 255))
    rect(5, 2, 8, 4, (202, 211, 205, 255)); rect(9, 2, 11, 4, (202, 211, 205, 255))
    rect(5, 6, 11, 11, (44, 78, 80, 255)); rect(6, 7, 10, 10, (102, 212, 174, 255))
    rect(7, 7, 9, 8, (221, 250, 217, 255)); rect(4, 12, 12, 13, (88, 109, 117, 255))

def intermediate_bank_icon(rect):
    rect(2, 4, 14, 13, (31, 47, 74, 255)); rect(3, 3, 13, 14, (57, 85, 119, 255))
    rect(4, 2, 7, 4, (199, 208, 220, 255)); rect(9, 2, 12, 4, (199, 208, 220, 255))
    rect(4, 5, 7, 12, (83, 182, 220, 255)); rect(9, 5, 12, 12, (83, 182, 220, 255))
    rect(5, 6, 6, 10, (184, 242, 248, 255)); rect(10, 6, 11, 10, (184, 242, 248, 255))
    rect(7, 6, 9, 11, (27, 40, 61, 255)); rect(4, 12, 12, 13, (38, 63, 94, 255))

def advanced_bank_icon(rect):
    rect(2, 4, 14, 12, (37, 31, 53, 255)); rect(3, 3, 13, 13, (84, 74, 112, 255))
    rect(4, 2, 12, 4, (181, 183, 201, 255)); rect(4, 12, 12, 14, (125, 120, 151, 255))
    for y in (5, 8, 11): rect(1, y, 4, y + 1, (178, 144, 73, 255)); rect(12, y, 15, y + 1, (178, 144, 73, 255))
    rect(5, 5, 11, 11, (44, 34, 68, 255)); rect(7, 4, 9, 12, (182, 127, 232, 255))
    rect(6, 6, 10, 10, (182, 127, 232, 255)); rect(7, 6, 9, 8, (239, 204, 255, 255))

def nuclear_bank_icon(rect):
    # A compact graphite shell; the independent trefoil overlay stays bright in darkness.
    rect(9, 2, 23, 5, (106, 128, 124, 255)); rect(12, 2, 20, 3, (183, 206, 195, 255))
    rect(5, 6, 27, 27, (25, 35, 37, 255)); rect(7, 4, 25, 30, (39, 53, 54, 255))
    rect(7, 6, 9, 27, (95, 119, 112, 255)); rect(23, 6, 25, 29, (18, 27, 30, 255))
    rect(9, 8, 23, 27, (21, 34, 34, 255)); rect(9, 28, 23, 30, (17, 25, 29, 255))

def nuclear_bank_emissive_icon(rect):
    # Three separated radial sectors around a bright hub, not a face-like panel.
    from math import atan2, degrees, hypot
    for y in range(7, 28):
        for x in range(6, 27):
            radius = hypot(x - 15.5, y - 17)
            angle = degrees(atan2(y - 17, x - 15.5))
            blade = min(abs((angle - center + 180) % 360 - 180) for center in (-90, 30, 150)) <= 31
            if radius < 1.8:
                rect(x, y, x + 1, y + 1, (185, 255, 203, 255))
            elif 3.6 < radius < 8.6 and blade:
                color = (100, 250, 153, 255) if radius < 7.5 else (55, 208, 112, 255)
                rect(x, y, x + 1, y + 1, color)
            elif 3.3 < radius < 9.5 and blade:
                rect(x, y, x + 1, y + 1, (28, 75, 49, 255))

for filename, painter in [('robot_control_core.png', core_icon), ('robot_actuator.png', actuator_icon),
                          ('robot_battery.png', battery_icon),
                          ('power_bank.png', power_bank_icon), ('intermediate_power_bank.png', intermediate_bank_icon),
                          ('advanced_power_bank.png', advanced_bank_icon), ('nuclear_power_bank.png', nuclear_bank_icon),
                          ('nuclear_power_bank_emissive.png', nuclear_bank_emissive_icon)]:
    draw_texture(filename, painter, 32 if filename.startswith('nuclear_power_bank') else 16)

recipe('blueprint', [{'item': 'minecraft:paper'}, {'item': 'minecraft:blue_dye'},
                     {'item': 'minecraft:redstone'}, {'item': 'ic2:advanced_circuit'},
                     {'item': 'create:precision_mechanism'}, {'item': 'ic2:carbon_plate'}], 'robot_blueprint')
write('assets/ic2newera/models/item/robot_blueprint.json', {
    'parent': 'minecraft:item/generated', 'textures': {'layer0': 'ic2newera:item/robot_blueprint'}})
for identifier in ('robot_control_core', 'robot_actuator', 'robot_battery',
                   'power_bank', 'intermediate_power_bank', 'advanced_power_bank', 'nuclear_power_bank'):
    write('assets/ic2newera/models/item/' + identifier + '.json', {
        'parent': 'minecraft:item/generated', 'textures': {'layer0': 'ic2newera:item/' + identifier}})
write('assets/ic2newera/models/item/nuclear_power_bank.json', {
    'parent': 'minecraft:item/generated', 'loader': 'forge:item_layers',
    'textures': {'layer0': 'ic2newera:item/nuclear_power_bank',
                 'layer1': 'ic2newera:item/nuclear_power_bank_emissive'},
    'emissive_layers': [1]})
for identifier, name, dye in characters:
    output = identifier + '_robot_doll'
    recipe(identifier, [{'item': 'ic2newera:robot_blueprint'},
        {'item': 'ic2newera:robot_control_core'}, {'item': 'ic2newera:robot_actuator'},
        {'tag': 'minecraft:wool'}, {'item': 'minecraft:string'},
        {'item': 'minecraft:' + dye + '_dye'}], output)
    write('assets/ic2newera/models/item/' + output + '.json', {
        'parent': 'minecraft:item/generated', 'textures': {'layer0': 'ic2newera:item/' + output}})

english_names = {'anon': 'Chihaya Anon', 'tomori': 'Takamatsu Tomori', 'soyo': 'Nagasaki Soyo',
                 'taki': 'Shiina Taki', 'rana': 'Kaname Rana', 'sakiko': 'Togawa Sakiko',
                 'uika': 'Misumi Uika', 'umiri': 'Yahata Umiri'}
for locale in ('zh_cn', 'en_us'):
    path = RES / f'assets/ic2newera/lang/{locale}.json'
    values = json.loads(path.read_text(encoding='utf-8-sig'))
    zh = locale == 'zh_cn'
    values['entity.ic2newera.companion_robot'] = '陪伴机器人' if zh else 'Companion Robot'
    values['item.ic2newera.robot_blueprint'] = '通用机器人图纸' if zh else 'Universal Robot Blueprint'
    # Item hovers only show names and robot durability; instructions live in the guide.
    for key in list(values):
        if key.startswith('tooltip.ic2newera.robot_') or key == 'tooltip.ic2newera.industrial_phone':
            del values[key]
    values['tooltip.ic2newera.robot_durability'] = '耐久度：%s / %s' if zh else 'Durability: %s / %s'
    item_names = {'robot_control_core': ('机器人控制核心', 'Robot Control Core'),
        'robot_actuator': ('机器人执行器', 'Robot Actuator'),
        'robot_battery': ('机器人可替换电池', 'Replaceable Robot Battery'),
        'power_bank': ('初级充电宝', 'Basic Power Bank'),
        'intermediate_power_bank': ('中级充电宝', 'Intermediate Power Bank'),
        'advanced_power_bank': ('高级充电宝', 'Advanced Power Bank'),
        'nuclear_power_bank': ('核能充电宝', 'Nuclear Power Bank'),
        'industrial_phone': ('手机', 'Phone')}
    for identifier, labels in item_names.items(): values['item.ic2newera.' + identifier] = labels[0] if zh else labels[1]
    for identifier, name, dye in characters:
        values['robot.ic2newera.' + identifier] = name if zh else english_names[identifier]
        values['item.ic2newera.' + identifier + '_robot_doll'] = name + '机器人娃娃' if zh else english_names[identifier] + ' Robot Doll'
    path.write_text(json.dumps(values, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
phone_assets = runpy.run_path(str(ROOT / 'art/sources/generate-phone-assets.py'))
phone_assets['main']()
print('Generated 1 blueprint + 8 doll recipes, models, recipe-book entries, phone assets and bilingual names.')
