"""Original IC2 New Era industrial creatures. Rebuilds UV PNGs, runtime meshes and MCP edit inputs.

Run from any directory with Python/Pillow; no game, third-party assets or network is used.
Editable bbmodels are authored through the Blockbench file MCP using the emitted operations.
"""
from pathlib import Path
from PIL import Image, ImageDraw
import base64
import hashlib
import json
import random

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / 'art/industrial-hostiles'
ASSETS = ROOT / 'src/main/resources/assets/ic2newera'
COLORS = {'shell': (51, 68, 80), 'plate': (88, 109, 119), 'edge': (153, 174, 180),
          'hazard': (222, 157, 42), 'eye': (255, 193, 65), 'piston': (178, 189, 187),
          'cable': (31, 170, 174), 'tread': (26, 32, 37), 'vent': (26, 43, 51), 'red': (197, 76, 47)}


def build(kind):
    bones, cubes = [], []
    def bone(name, pivot, parent=None):
        bones.append({'name': name, 'pivot': pivot, 'parent': parent})
    def cube(name, parent, start, end, material='shell'):
        cubes.append({'name': name, 'bone': parent, 'from': start, 'to': end, 'material': material})
    if kind == 'patrol_drone':
        bone('body', [0, 12, 0])
        cube('lower_beveled_core', 'body', [-4, 9, -4], [4, 12, 4])
        cube('armored_deck', 'body', [-5, 12, -4], [5, 15, 4], 'plate')
        cube('battery_spine', 'body', [-3, 15, -2], [3, 17, 3])
        cube('top_service_panel', 'body', [-2, 17, -1], [2, 18, 2], 'edge')
        cube('underside_pulse_coil', 'body', [-3, 7, -3], [3, 9, 3], 'cable')
        cube('coil_central_housing', 'body', [-2, 6, -2], [2, 7, 2], 'vent')
        bone('scanner', [0, 13, -4], 'body')
        cube('scanner_outer_frame', 'scanner', [-4, 11, -6], [4, 15, -4], 'tread')
        cube('amber_scanning_lens', 'scanner', [-3, 12, -7], [3, 14, -6], 'eye')
        cube('center_lens', 'scanner', [-1, 11, -8], [1, 15, -7], 'hazard')
        for side in [-1, 1]:
            x = side * 5
            cube(f'side_warning_{side}', 'body', [x-1, 11, -3], [x+1, 13, 3], 'hazard')
            for z in [-6, 6]:
                sx = side * 7
                label = ('left' if side < 0 else 'right') + ('_front' if z < 0 else '_rear')
                cube('support_' + label, 'body', [min(side*3, sx), 12, min(z, z/2)], [max(side*3, sx)+1, 14, max(z, z/2)+1], 'piston')
                bone('rotor_' + label, [sx, 14, z], 'body')
                cube('rotor_hub_' + label, 'rotor_' + label, [sx-1, 13, z-1], [sx+1, 17, z+1], 'edge')
                for bx0, bz0, bx1, bz1, part in [(-3,-1,-1,0,'west'),(1,-1,3,0,'east'),(0,-3,1,-1,'front'),(0,1,1,3,'rear')]:
                    cube('rotor_blade_'+label+'_'+part, 'rotor_'+label, [sx+bx0,15,z+bz0], [sx+bx1,16,z+bz1], 'tread')
                for dx in [-3, 3]:
                    cube(f'guard_x_{label}_{dx}', 'body', [sx+dx, 13, z-3], [sx+dx+1, 14, z+4], 'hazard')
                for dz in [-3, 3]:
                    cube(f'guard_z_{label}_{dz}', 'body', [sx-2, 13, z+dz], [sx+3, 14, z+dz+1], 'shell')
        for x in [-3, 2]:
            cube(f'rear_vent_{x}', 'body', [x, 12, 4], [x+1, 15, 5], 'vent')
            cube(f'landing_prong_{x}', 'body', [x, 4, 1], [x+1, 8, 3], 'piston')
        cube('rear_status_strip', 'body', [-1, 13, 4], [1, 14, 5], 'red')
    else:
        bone('body', [0, 13, 0])
        cube('pelvis_armored_frame', 'body', [-5, 9, -3], [5, 13, 3], 'tread')
        cube('waist_rotary_bearing', 'body', [-3, 12, -2], [3, 14, 2], 'piston')
        cube('armored_torso', 'body', [-6, 14, -4], [6, 21, 4])
        cube('sloped_chest_sill', 'body', [-5, 13, -5], [5, 16, -4], 'plate')
        cube('upper_chest_plate', 'body', [-5, 18, -5], [5, 21, -4], 'plate')
        cube('chest_warning_band', 'body', [-5, 16, -5], [5, 18, -4], 'hazard')
        cube('back_power_unit', 'body', [-4, 14, 4], [4, 21, 7], 'vent')
        for x in [-3, 2]:
            cube(f'back_coolant_{x}', 'body', [x, 15, 7], [x+1, 20, 8], 'cable')
        bone('head', [0, 22, -1], 'body')
        cube('angular_sensor_hood', 'head', [-4, 21, -4], [4, 26, 3], 'plate')
        cube('dark_recessed_visor', 'head', [-3, 22, -5], [3, 25, -4], 'tread')
        cube('single_amber_sensor', 'head', [-2, 23, -6], [2, 24, -5], 'eye')
        cube('right_rangefinder', 'head', [4, 22, -2], [5, 25, 0], 'hazard')
        for side in [-1, 1]:
            name = 'left_leg' if side < 0 else 'right_leg'
            x = side * 3
            bone(name, [x, 11, 0], 'body')
            cube(name+'_upper', name, [x-2, 7, -2], [x+2, 11, 2], 'plate')
            cube(name+'_knee', name, [x-2, 4, -3], [x+2, 7, 3], 'tread')
            cube(name+'_shin', name, [x-2, 1, -2], [x+2, 4, 2], 'shell')
            cube(name+'_foot', name, [x-3, 0, -4], [x+3, 2, 3], 'tread')
            cube(name+'_foot_cap', name, [x-3, 2, -4], [x+3, 3, -2], 'hazard')
            cube(name+'_exposed_piston', name, [x+side*2, 3, 1], [x+side*2+1, 9, 2], 'piston')
        bone('left_arm', [-7, 20, 0], 'body')
        cube('left_shoulder', 'left_arm', [-10, 18, -3], [-6, 22, 3], 'plate')
        cube('left_upper_actuator', 'left_arm', [-9, 14, -2], [-7, 18, 2], 'piston')
        cube('left_forearm_clamp', 'left_arm', [-10, 9, -3], [-6, 14, 3], 'shell')
        cube('left_gripper_a', 'left_arm', [-10, 7, -3], [-9, 10, 2], 'edge')
        cube('left_gripper_b', 'left_arm', [-7, 7, -3], [-6, 10, 2], 'edge')
        bone('hammer_arm', [7, 20, 0], 'body')
        cube('hammer_shoulder', 'hammer_arm', [6, 18, -4], [11, 23, 4], 'hazard')
        cube('hammer_upper_actuator', 'hammer_arm', [7, 14, -2], [10, 18, 2], 'piston')
        cube('hammer_forearm', 'hammer_arm', [7, 8, -3], [11, 14, 3], 'shell')
        cube('hammer_head', 'hammer_arm', [5, 5, -5], [13, 9, 5], 'plate')
        cube('hammer_striking_face', 'hammer_arm', [4, 5, -5], [5, 9, 5], 'edge')
        cube('hammer_warning_face', 'hammer_arm', [13, 5, -5], [14, 9, 5], 'hazard')
        for z in [-3, 2]:
            cube(f'hammer_coolant_{z}', 'hammer_arm', [11, 10, z], [12, 17, z+1], 'cable')
    return {'name': kind, 'texture_size': [256, 256], 'bones': bones, 'cubes': cubes}


def texture_and_uv(model):
    image = Image.new('RGBA', (256, 256), (13, 22, 28, 255))
    draw = ImageDraw.Draw(image)
    u = v = row_height = 0
    for box in model['cubes']:
        dx, dy, dz = [int(box['to'][i] - box['from'][i]) for i in range(3)]
        width, height = 2*(dx+dz), dy+dz
        if u + width + 2 > 256:
            u = 0; v += row_height + 2; row_height = 0
        if v + height + 2 > 256:
            raise ValueError('UV atlas exhausted')
        box['uv'] = [u, v]
        base = COLORS[box['material']]
        seed = int.from_bytes(hashlib.sha256(box['name'].encode()).digest()[:4], 'big')
        rng = random.Random(seed)
        for yy in range(v, v+height):
            for xx in range(u, u+width):
                noise = rng.randint(-7, 7)
                # Industrial edges, rivets and warning stripes are authored here, not copied from another texture.
                shade = -20 if xx in (u, u+width-1) or yy in (v, v+height-1) else 0
                color = tuple(max(0, min(255, c+noise+shade)) for c in base)
                if box['material'] == 'hazard' and ((xx-u)+(yy-v)) % 7 < 2:
                    color = (40, 43, 39)
                if box['material'] == 'eye':
                    color = (255, min(255, 170 + (yy-v)*14), 43)
                image.putpixel((xx, yy), (*color, 255))
        if width > 8 and height > 5 and box['material'] in ('shell', 'plate'):
            for px in (u+2, u+width-3):
                for py in (v+2, v+height-3):
                    draw.point((px, py), fill=(192, 201, 198, 255))
        u += width + 2; row_height = max(row_height, height)
    return image


def operations(model, png):
    ops = [{'op': 'add_texture', 'name': model['name'], 'source': 'data:image/png;base64,'+base64.b64encode(png.read_bytes()).decode(), 'width': 256, 'height': 256}]
    for bone in model['bones']:
        op = {'op': 'add_group', 'name': bone['name'], 'origin': bone['pivot']}
        if bone['parent']: op['parent'] = bone['parent']
        ops.append(op)
    for cube in model['cubes']:
        ops.append({'op': 'add_cube', 'name': cube['name'], 'from': cube['from'], 'to': cube['to'], 'parent': cube['bone'], 'box_uv': True, 'uv_offset': cube['uv'], 'texture': model['name']})
    ops.append({'op': 'add_animation', 'name': 'idle', 'length': 2, 'loop': 'loop', 'snapping': 20})
    if model['name'] == 'patrol_drone':
        for t, y in [(0, 0), (0.5, 0.7), (1, 0), (1.5, -0.7), (2, 0)]:
            ops.append({'op': 'set_keyframe', 'animation': 'idle', 'bone': 'body', 'channel': 'position', 'time': t, 'value': [0, y, 0], 'interpolation': 'catmullrom'})
        for bone in model['bones']:
            if bone['name'].startswith('rotor_'):
                for t, angle in [(0, 0), (0.5, 180), (1, 360), (1.5, 540), (2, 720)]:
                    ops.append({'op': 'set_keyframe', 'animation': 'idle', 'bone': bone['name'], 'channel': 'rotation', 'time': t, 'value': [0, angle, 0], 'interpolation': 'linear'})
    else:
        ops.append({'op': 'add_animation', 'name': 'walk', 'length': 1, 'loop': 'loop', 'snapping': 20})
        for bone, sign in [('left_leg', 1), ('right_leg', -1), ('left_arm', -0.5)]:
            for t, angle in [(0, -22), (0.25, 0), (0.5, 22), (0.75, 0), (1, -22)]:
                ops.append({'op': 'set_keyframe', 'animation': 'walk', 'bone': bone, 'channel': 'rotation', 'time': t, 'value': [angle*sign, 0, 0], 'interpolation': 'catmullrom'})
        ops.append({'op': 'add_animation', 'name': 'heavy_strike', 'length': 2.6, 'loop': 'once', 'snapping': 20})
        for t, angle in [(0, 0), (0.6, -100), (0.75, -125), (0.8, 40), (1.6, 25), (2.6, 0)]:
            ops.append({'op': 'set_keyframe', 'animation': 'heavy_strike', 'bone': 'hammer_arm', 'channel': 'rotation', 'time': t, 'value': [angle, 0, 0], 'interpolation': 'linear'})
    return ops


def main():
    ART.mkdir(parents=True, exist_ok=True)
    for directory in (ASSETS/'textures/entity/industrialhostile', ASSETS/'models/entity/industrialhostile'):
        directory.mkdir(parents=True, exist_ok=True)
    for kind in ('patrol_drone', 'industrial_sentinel'):
        model = build(kind)
        png = ART/(kind+'.png')
        texture_and_uv(model).save(png)
        (ASSETS/'textures/entity/industrialhostile'/(kind+'.png')).write_bytes(png.read_bytes())
        encoded = json.dumps(model, ensure_ascii=False, indent=2)+'\n'
        (ART/(kind+'.mesh.json')).write_text(encoded, encoding='utf-8')
        (ASSETS/'models/entity/industrialhostile'/(kind+'.json')).write_text(encoded, encoding='utf-8')
        (ART/(kind+'.mcp-operations.json')).write_text(json.dumps(operations(model, png), ensure_ascii=False), encoding='utf-8')
        print(kind, len(model['bones']), 'bones,', len(model['cubes']), 'original cubes')


if __name__ == '__main__':
    main()
