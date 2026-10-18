"""Distinct functional silhouettes, authored as Minecraft cuboids and pixel textures."""
from pathlib import Path
import json
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT/'src/main/resources/assets/ic2newera'
DEVICES = ('terminal','industrial_control_server','non_metal_processor','electronic_fabricator',
           'machine_network_adapter','industrial_network_switch','eu_motor')
DESCRIPTIONS = {
    'terminal':'工业储物箱式终端：加固箱体、嵌入式小屏、独立物料口、背部能源与网络接口。',
    'industrial_control_server':'黑色工业机柜：四层机架、硬盘托架、散热风扇、背板端口及布线槽。',
    'non_metal_processor':'米黄色粉料设备：顶部敞口料斗、加工筒体、前置输出抽屉和背部EU口。',
    'electronic_fabricator':'白色精密加工台：封闭观察舱、内部加工头、左右支柱和前置物料托盘。',
    'machine_network_adapter':'紧凑青色数据模块：短机身、凸起光纤插座、立式天线与连接底座。',
    'industrial_network_switch':'低矮深蓝交换机：双排端口、上部散热鳍、两根短天线和光纤接头。',
    'eu_motor':'铜绕组电机：阶梯式电机壳、独立轴承与散热鳍、底脚、背部EU接头；输出轴由Create渲染。'
}
PALETTES = {
    'terminal':('#10171C','#303C42','#879397','#53D5C5','#D2A95E'),
    'industrial_control_server':('#090C10','#171D23','#323D47','#65C69A','#D8AD58'),
    'non_metal_processor':('#383B36','#9B967B','#D9CDA6','#6BB9A0','#E9B75B'),
    'electronic_fabricator':('#293746','#BFCAD0','#F0F3EF','#68DCDD','#BDA462'),
    'machine_network_adapter':('#172E36','#365B64','#789DA3','#66DCE4','#CBB873'),
    'industrial_network_switch':('#131F32','#293B57','#57718E','#5CC6B5','#C2A067'),
    'eu_motor':('#2B3335','#626C69','#A7B2A7','#D69559','#45C5C3')
}


def write_json(path, value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes((json.dumps(value,ensure_ascii=False,indent=2)+'\n').encode('utf8'))


def surface(name, role):
    dark,body,light,signal,accent=PALETTES[name]
    width,height=(64,32) if role in ('screen','keyboard','bay') else (32,32)
    im=Image.new('RGBA',(width,height),body)
    d=ImageDraw.Draw(im)
    if role=='shell':
        d.rectangle((0,0,width-1,height-1),outline=dark,width=2)
        d.line((2,2,width-3,2),fill=light)
        d.line((2,height-3,width-3,height-3),fill=dark)
        for x,y in ((3,3),(width-4,3),(3,height-4),(width-4,height-4)):
            d.rectangle((x,y,x+1,y+1),fill=light)
        if name=='terminal':
            # Riveted steel crate panels, lid seam and corner guards.
            d.line((2,10,width-3,10),fill=dark,width=2)
            d.line((2,12,width-3,12),fill=light)
            d.rectangle((3,5,5,height-6),fill=light)
            d.rectangle((width-6,5,width-4,height-6),fill=light)
            for x in (8,width-9):
                for y in (7,height-8): d.rectangle((x,y,x+1,y+1),fill=accent)
        else:
            # Fine housing seams; not a repeated central square display.
            d.line((width-8,5,width-8,height-6),fill=dark)
    elif role=='trim':
        d.rectangle((0,0,width-1,height-1),fill=dark)
        if name=='terminal':
            # Thick cast-metal corner armor and latch plates.
            d.rectangle((2,2,8,height-3),fill=body,outline=light)
            d.rectangle((width-9,2,width-3,height-3),fill=body,outline=light)
            for y in (5,height-6):
                d.rectangle((4,y,6,y+2),fill=accent)
                d.rectangle((width-7,y,width-5,y+2),fill=accent)
        else:
            d.rectangle((3,3,width-4,height-4),outline=body,width=2)
            d.rectangle((6,6,width-7,height-7),fill=signal)
    elif role=='vent':
        d.rectangle((0,0,width-1,height-1),fill=dark)
        if name=='industrial_control_server':
            for cy in (9,23):
                d.ellipse((7,cy-6,23,cy+6),outline=light,width=2)
                d.ellipse((13,cy-2,17,cy+2),fill=body)
                for x,y in ((11,cy-3),(18,cy),(13,cy+3)):
                    d.line((15,cy,x,y),fill=light,width=2)
            for y in range(3,30,3):
                for x in (3,27): d.point((x,y),fill=body)
        else:
            for y in range(4,29,4):
                d.rectangle((4,y,27,y+1),fill=body)
                d.line((5,y,26,y),fill=light)
    elif role=='mesh':
        d.rectangle((0,0,31,31),fill=dark)
        for y in range(5,28,4):
            for x in range(5,28,4): d.rectangle((x,y,x+1,y+1),fill=body)
    elif role=='ports':
        d.rectangle((0,0,width-1,height-1),fill=dark)
        if name=='terminal':
            d.rectangle((3,3,28,28),fill=body,outline=light,width=2)
            d.rectangle((7,7,24,22),fill=dark)
            for y in (9,13,17): d.line((9,y,22,y),fill=light)
            d.polygon(((11,23),(21,23),(16,28)),fill=signal)
        else:
            for y in (5,18):
                for x in (4,17):
                    d.rectangle((x,y,x+10,y+8),outline=light)
                    d.rectangle((x+2,y+2,x+8,y+6),fill=body)
                    d.line((x+3,y+5,x+7,y+5),fill=accent)
                    d.point((x+8,y-1),fill=signal)
    elif role=='input' and name=='terminal':
        d.rectangle((0,0,31,31),fill=dark)
        d.rectangle((3,4,28,27),fill=body,outline=light,width=2)
        d.rectangle((7,7,24,19),fill=dark)
        for y in (9,12,15,18): d.line((9,y,22,y),fill=light)
        d.polygon(((11,22),(21,22),(16,28)),fill=signal)
    elif role=='output' and name=='terminal':
        d.rectangle((0,0,31,31),fill=dark)
        d.rectangle((3,4,28,27),fill=body,outline=light,width=2)
        d.rectangle((7,11,24,23),fill=dark)
        d.polygon(((11,10),(21,10),(16,4)),fill=accent)
        d.line((9,25,23,25),fill=signal,width=2)
    elif role=='energy' and name=='terminal':
        d.rectangle((0,0,31,31),fill=dark)
        d.rectangle((3,3,28,28),fill=body,outline=light,width=2)
        d.ellipse((7,7,24,24),fill=dark,outline=accent,width=3)
        d.ellipse((12,12,19,19),fill=accent)
        d.rectangle((14,3,17,7),fill=light)
    elif role=='network' and name=='terminal':
        d.rectangle((0,0,31,31),fill=dark)
        for x in (3,17):
            d.rectangle((x,6,x+11,25),fill=body,outline=light,width=2)
            d.rectangle((x+3,10,x+8,17),fill=dark)
            d.ellipse((x+4,19,x+7,22),fill=accent)
    elif role=='bay':
        d.rectangle((0,0,63,31),fill=dark)
        d.line((0,0,63,0),fill=light)
        for x in (2,22,42):
            d.rectangle((x,4,x+18,27),fill=body,outline=light)
            d.rectangle((x+3,8,x+14,18),fill=dark)
            d.line((x+4,21,x+14,21),fill=light,width=2)
            d.rectangle((x+15,7,x+16,9),fill=signal)
            d.point((x+16,13),fill=accent)
        d.line((0,31,63,31),fill=light)
    elif role=='screen':
        d.rectangle((0,0,63,31),fill='#06141F')
        if name=='terminal':
            d.rectangle((1,1,62,30),outline=light,width=2)
            d.rectangle((4,4,59,27),fill='#10232A')
            d.rectangle((7,7,22,11),fill=signal)
            d.line((8,18,56,18),fill='#31535A')
            d.line((8,23,37,23),fill='#31535A')
            for x,y in ((47,9),(53,9)):
                d.rectangle((x,y,x+3,y+3),fill=accent if x==53 else signal)
        else:
            d.rectangle((1,1,62,30),outline=signal)
            for x in range(5,59,7): d.line((x,5,x,27),fill='#123244')
            for y in range(6,29,5): d.line((4,y,59,y),fill='#123244')
            d.line((4,3,25,3),fill=accent)
            for i,n in enumerate((6,11,8,16,20,14)):
                d.rectangle((6+i*4,27-n,8+i*4,27),fill=signal if i%2==0 else '#247D88')
            d.rectangle((35,7,56,15),outline=accent)
            d.line((38,11,42,11,45,8,48,13,53,10),fill=signal)
            for y,length in ((19,17),(23,12),(27,19)):
                d.line((36,y,36+length,y),fill=signal if y==23 else '#377F9A')
    elif role=='keyboard':
        d.rectangle((0,0,63,31),fill=dark)
        d.rectangle((2,2,44,25),fill=body,outline=light)
        for y in (5,11,17):
            for x in range(5,40,6):
                d.rectangle((x,y,x+3,y+3),fill=dark)
                d.line((x,y,x+3,y),fill=signal)
        d.rectangle((10,23,31,24),fill=signal)
        d.rectangle((49,8,60,24),outline=accent)
        d.line((53,27,59,27),fill=signal)
    elif role=='window':
        d.rectangle((0,0,31,31),fill='#183C4D')
        d.rectangle((2,2,29,29),outline=light)
        d.line((5,5,25,5),fill=signal)
        d.line((6,8,6,22),fill='#57929E')
        d.rectangle((10,17,24,22),fill=body)
        d.line((12,19,22,19),fill=signal)
        d.rectangle((15,6,19,14),fill=light)
        d.line((17,14,17,17),fill=accent)
    elif role=='feed':
        d.rectangle((0,0,31,31),fill=dark)
        d.rectangle((3,7,28,25),outline=light,width=2)
        d.rectangle((7,11,24,21),fill=body)
        d.line((7,22,24,22),fill=accent,width=2)
        d.polygon(((12,3),(19,3),(16,6)),fill=signal)
        d.line((8,14,23,14),fill=dark,width=2)
    elif role=='coil':
        d.rectangle((0,0,31,31),fill=dark)
        for x in range(2,31,4):
            d.rectangle((x,2,x+1,29),fill='#B56939')
            d.line((x,3,x,28),fill='#EDB572')
    target=ASSETS/'textures/block'/f'{name}_{role}.png'
    target.parent.mkdir(parents=True,exist_ok=True)
    im.save(target)
    return f'ic2newera:block/{name}_{role}'


def part(start, end, texture='shell', faces=None, unlit=False):
    mapping={f:texture for f in ('north','south','east','west','up','down')}
    if faces: mapping.update(faces)
    return {'from':list(start),'to':list(end),'shade':not unlit,
            'faces':{f:{'texture':'#'+key,'uv':[0,0,16,16]} for f,key in mapping.items()}}


def device_model(name):
    elements=[]
    add=lambda a,b,t='shell',**kw: elements.append(part(a,b,t,**kw))
    if name=='terminal':
        # Dominant form is a reinforced storage chest; controls are embedded in its front.
        add((1,1,1),(15,12,15))
        add((0.5,10.5,0.5),(15.5,15.5,15.5),faces={'north':'shell','south':'shell','up':'shell'})
        # Small recessed operation screen, set into the upper front lid.
        add((5,7,0.75),(11,11,1.2),faces={'north':'screen'})
        # Distinct lower input chute and output drawer remain separate from the screen.
        add((2,3,0.2),(7,6,1.2),faces={'north':'input'})
        add((9,3,0.2),(14,6,1.2),faces={'north':'output'})
        # Rear service plate: EU socket, paired optical/network sockets and a short aerial.
        add((3,4,14.8),(7,8,15.8),faces={'south':'energy'})
        add((9,4,14.8),(13,8,15.8),faces={'south':'network'})
        add((6,11,13.5),(10,15.5,15.5),faces={'south':'trim'})
        # Raised lid seam, hinge barrels and front latch reinforce the crate silhouette.
        add((1,10,0.5),(15,10.7,1.5),'trim')
        add((2,14.7,14),(5,15.5,16),'trim')
        add((11,14.7,14),(14,15.5,16),'trim')
        add((7,11.5,0.1),(9,12.5,0.8),'trim')
        roles=('shell','screen','trim','input','output','energy','network')
    elif name=='industrial_control_server':
        add((1,0,1),(15,1.5,15))
        add((2,1.5,4),(14,15.5,14),faces={'south':'vent','east':'mesh','west':'mesh'})
        add((0.7,1,2),(2.3,16,4))
        add((13.7,1,2),(15.3,16,4))
        add((2.3,14.5,2),(13.7,16,4),faces={'north':'ports'})
        for y in (2,5,8,11):
            add((2.5,y,2.2),(13.5,y+2.5,5),faces={'north':'bay'})
            add((3,y+0.6,1.7),(3.5,y+1.9,2.2))
            add((12.5,y+0.6,1.7),(13,y+1.9,2.2))
        add((3,4,14),(13,12,14.8),faces={'south':'vent'})
        add((3,12.5,14),(9,15,16),faces={'south':'ports'})
        add((10,2,14),(13,5,16),faces={'south':'ports'})
        # Rear cable guides and retaining bars make the cabinet read as assembled hardware.
        add((1,3,13.5),(2,14,15.5))
        add((14,3,13.5),(15,14,15.5))
        add((1,7,15),(15,8,16))
        roles=('shell','bay','vent','ports','mesh')
    elif name=='non_metal_processor':
        add((0,0,0),(16,3,16))
        add((2,3,2),(14,11.5,14),faces={'north':'feed','south':'ports','east':'vent'})
        add((4,11.5,4),(12,13,12))
        # Open hopper, with four separate walls and a recessed throat.
        add((3,13,3),(13,14,13))
        add((2,14,2),(4,16,14))
        add((12,14,2),(14,16,14))
        add((4,14,2),(12,16,4))
        add((4,14,12),(12,16,14))
        add((4,3,0.2),(12,5.2,2),faces={'north':'feed'})
        add((13.5,5,5),(16,10,11),faces={'east':'ports'})
        roles=('shell','feed','ports','vent')
    elif name=='electronic_fabricator':
        add((0,0,0),(16,3,16))
        add((1,3,3),(3,14,15))
        add((13,3,3),(15,14,15))
        add((3,3,13),(13,14,15),faces={'south':'ports'})
        add((1,14,3),(15,16,15),faces={'up':'vent'})
        add((3,6,3),(13,13.8,13),faces={'north':'window','east':'window','west':'window'})
        add((4,3,0),(12,4.5,5),faces={'up':'feed','north':'feed'})
        add((0.5,8,2.5),(2,12,3),'trim')
        add((14,8,2.5),(15.5,12,3),'trim')
        roles=('shell','window','feed','ports','vent','trim')
    elif name=='machine_network_adapter':
        add((2,0,3),(14,2,13))
        add((3,2,4),(13,9,12),faces={'north':'ports','south':'vent'})
        add((5,4,2),(11,7,4),'ports')
        add((10,9,8),(12,10,11))
        add((10.5,10,8.5),(11.5,16,9.5),'trim')
        add((3,7.5,3.7),(8,8.5,4),'trim')
        roles=('shell','ports','vent','trim')
    elif name=='industrial_network_switch':
        add((0,0,1),(16,2,15))
        add((1,2,2),(15,7,14),faces={'north':'ports','south':'ports','up':'vent'})
        add((2,3,1),(14,6,2),'ports')
        for x in (3,6,9,12): add((x,7,6),(x+0.7,9,13),'shell')
        add((1,7,12),(2,14,13),'trim')
        add((14,7,12),(15,14,13),'trim')
        add((5,2,14),(11,5,16),'ports')
        roles=('shell','ports','vent','trim')
    elif name=='eu_motor':
        # South is the real rotating shaft output; leave the centre 6..10 unobstructed.
        add((1,0,2),(15,2,14))
        add((3,2,3),(13,14,13),faces={'east':'coil','west':'coil'})
        add((2,4,3),(3,12,13),faces={'west':'coil'})
        add((13,4,3),(14,12,13),faces={'east':'coil'})
        add((4,14,3),(12,15,13))
        for z in (3,5,7,9,11):
            add((1.5,4,z),(2,12,z+0.6),'shell')
            add((14,4,z),(14.5,12,z+0.6),'shell')
        for a,b in (((4,4,13),(6,12,15)),((10,4,13),(12,12,15)),
                    ((6,4,13),(10,6,15)),((6,10,13),(10,12,15))): add(a,b,'coil')
        add((5,5,1),(11,11,3),'ports')
        roles=('shell','coil','vent','ports')
    else: raise ValueError(name)
    return {'parent':'minecraft:block/block','textures':{'particle':f'ic2newera:block/{name}_shell',
            **{role:f'ic2newera:block/{name}_{role}' for role in roles}},'elements':elements},roles


def write_device(name):
    model,roles=device_model(name)
    for role in roles: surface(name,role)
    write_json(ASSETS/'models/block'/f'{name}.json',model)
    # Use vanilla block/block display transforms at normal block-item size.
    write_json(ASSETS/'models/item'/f'{name}.json',{'parent':f'ic2newera:block/{name}'})
    catalog_path=ROOT/'art/model-catalog.json'
    catalog=json.loads(catalog_path.read_text(encoding='utf-8-sig'))
    for entry in catalog['models']:
        if entry['id']==name:
            entry['description']=DESCRIPTIONS[name]
            entry['status']='资源源码已实现，待实机显示验收'
    write_json(catalog_path,catalog)


def generate():
    for name in DEVICES: write_device(name)
    print('Generated seven distinct device models, dedicated textures and catalog descriptions.')


if __name__=='__main__': generate()
