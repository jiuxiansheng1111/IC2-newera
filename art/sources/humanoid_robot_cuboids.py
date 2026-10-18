"""Convert original V28 studies to real cuboid elements, never polygon meshes.

This is an original offline art converter. It does not install or start Minecraft.
"""
from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import io
import json
import math
import shutil
import uuid
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

import humanoid_robot_sculpt as preview

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'art/robot-rework/iterations/v28'
OUT=ROOT/'art/robot-cuboids'
REVISION='B2'
STEP=.5
FACE_VERTICES={
    'north':(0,3,2,1),'south':(5,6,7,4),'west':(4,7,3,0),
    'east':(1,2,6,5),'up':(0,1,5,4),'down':(3,7,6,2),
}


def tile(identifier, material):
    index=list(preview.materials(identifier)).index(material)
    return index%8*128,index//8*128


def paint_pixel_face(identifier):
    """Original 64-pixel facial artwork with deliberate, crisp pixel edges."""
    image=Image.new('RGBA',(64,64),preview.rgba('#F3DCCD'));d=ImageDraw.Draw(image)
    style=preview.STYLE[identifier]
    shapes={
        'anon':[(0,.30),(.18,.08),(.48,0),(.82,.10),(1,.32),(.94,.80),(.70,1),(.22,.98),(0,.68)],
        'sakiko':[(0,.20),(.25,.04),(.70,.06),(1,.28),(.90,.83),(.60,1),(.18,.92),(0,.63)],
        'tomori':[(0,.31),(.20,.10),(.48,.02),(.83,.12),(1,.34),(.95,.84),(.66,1),(.20,.95),(0,.66)],
        'soyo':[(0,.30),(.20,.10),(.57,.04),(1,.38),(.96,.79),(.75,.96),(.28,.94),(0,.68)],
        'taki':[(0,.16),(.35,.06),(.85,.16),(1,.29),(.90,.75),(.63,.93),(.20,.90),(0,.62)],
        'rana':[(0,.24),(.30,.07),(.66,.07),(1,.27),(.94,.80),(.66,1),(.22,1),(0,.66)],
        'uika':[(0,.32),(.22,.08),(.50,0),(.85,.12),(1,.36),(.95,.82),(.65,1),(.19,.95),(0,.64)],
        'umiri':[(0,.18),(.28,.08),(.76,.13),(1,.27),(.94,.74),(.66,.95),(.21,.92),(0,.63)],
    }
    widths={'anon':12,'sakiko':11,'tomori':11,'soyo':12,'taki':12,'rana':11,'uika':12,'umiri':11}
    heights={'anon':9,'sakiko':8,'tomori':9,'soyo':8,'taki':7,'rana':9,'uika':9,'umiri':7}
    y=28 if identifier not in ('taki','umiri') else 29;w=widths[identifier];h=heights[identifier]
    for index,cx in enumerate((22,42)):
        x=cx-w/2;flip=lambda u:1-u if index else u
        outline=[(round(x+flip(u)*w),round(y+v*h)) for u,v in shapes[identifier]]
        eye=Image.new('RGBA',(64,64),(0,0,0,0));ed=ImageDraw.Draw(eye)
        mask=Image.new('L',(64,64),0);ImageDraw.Draw(mask).polygon(outline,fill=255)
        ed.polygon(outline,fill=preview.rgba('#FFFAF1'))
        iris='#BDA56D' if identifier=='rana' and index==0 else style['eye']
        iris_width=6 if identifier in ('taki','umiri') else 7
        iris_shape=[(cx-2,y+1),(cx+2,y+1),(cx+iris_width//2,y+3),(cx+iris_width//2,y+h-2),
                    (cx+1,y+h),(cx-1,y+h),(cx-iris_width//2,y+h-2),(cx-iris_width//2,y+3)]
        ed.polygon(iris_shape,fill=preview.tint(iris,.88))
        iris_mask=Image.new('L',(64,64),0);ImageDraw.Draw(iris_mask).polygon(iris_shape,fill=255)
        shades=Image.new('RGBA',(64,64),(0,0,0,0));sd=ImageDraw.Draw(shades)
        sd.rectangle((cx-4,y,cx+4,y+3),fill=preview.tint(iris,.65))
        sd.rectangle((cx-4,y+4,cx+4,y+h),fill=preview.tint(iris,1.02))
        eye.paste(shades,(0,0),iris_mask)
        ed=ImageDraw.Draw(eye);ed.rectangle((cx-1,y+3,cx+1,y+h-2),fill=preview.tint(iris,.36))
        ed.point((cx-2,y+2),fill=preview.rgba('#FFFDF7'));ed.point((cx+2,y+h-1),fill=preview.tint(iris,1.12))
        image.paste(eye,(0,0),mask);d=ImageDraw.Draw(image)
        upper_count=5 if identifier in ('anon','tomori','uika') else 4
        upper=[(round(x+flip(u)*w),round(y+v*h)) for u,v in shapes[identifier][:upper_count]]
        ink='#3B3341' if identifier not in ('taki','umiri') else '#302B38'
        d.line(upper,fill=preview.rgba(ink),width=1)
        outer=round(x) if index==0 else round(x+w);direction=-1 if index==0 else 1
        d.line((outer,y+2,outer+direction,y+1),fill=preview.rgba(ink),width=1)
        d.line((round(x+2),y+h,round(x+w-2),y+h),fill=preview.rgba('#C3A19B'),width=1)
        by=y-5;left=round(x+2);right=round(x+w-2)
        if identifier=='taki':brow=[(left,by-1),(right,by+1)] if index==0 else [(left,by+1),(right,by-1)]
        elif identifier=='umiri':brow=[(left,by),(right,by)]
        elif identifier in ('tomori','soyo'):brow=[(left,by+1),(right,by-1)] if index==0 else [(left,by-1),(right,by+1)]
        else:brow=[(left,by),(cx,by-1),(right,by)]
        d.line(brow,fill=preview.rgba(style['dark']),width=1)
    # Three distinct nose pixels remain small and readable on the square face.
    d.point((32,40),fill=preview.rgba('#FBE6D7'))
    d.point((32,41),fill=preview.rgba('#D3AE9E'))
    d.point((33,42),fill=preview.rgba('#C6A193'))
    mouth='#AD6D7A'
    if identifier=='anon':
        d.polygon([(28,47),(30,48),(35,48),(37,47),(36,51),(34,52),(31,52),(29,50)],fill=preview.rgba('#9F5F73'))
        d.line((30,48,35,48),fill=preview.rgba('#FFF5E6'),width=1)
        d.rectangle((31,50,35,51),fill=preview.rgba('#D397A0'))
    else:
        paths={'sakiko':[(29,47),(32,48),(35,47)],'tomori':[(29,48),(34,48)],
               'soyo':[(28,47),(31,48),(34,48),(36,47)],'taki':[(29,48),(35,48)],
               'rana':[(30,48),(32,47),(35,48)],'uika':[(28,47),(31,49),(34,49),(36,47)],
               'umiri':[(29,48),(35,48)]}
        d.line(paths[identifier],fill=preview.rgba(mouth if identifier!='tomori' else '#B98086'),width=1)
    for x in (15,50):d.line((x,42,x+2,42),fill=preview.rgba('#EBC7BC'),width=1)
    return image.resize((128,128),Image.Resampling.NEAREST)


def face_uv(identifier,material,bounds=None):
    u,v=tile(identifier,material)
    if bounds is None:return [u+2,v+2,u+126,v+126]
    a,b,c,d=bounds
    return [u+1+126*a,v+1+126*b,u+1+126*c,v+1+126*d]


def sample_surface(mesh,step=STEP):
    """Sample emitted triangles onto a shared lattice, retaining source ownership."""
    points=np.asarray(mesh['vertices']);cells=set()
    for face in mesh['faces']:
        ids=face['vertices']
        for j in range(1,len(ids)-1):
            p=points[[ids[0],ids[j],ids[j+1]]]
            longest=max(np.linalg.norm(p[1]-p[0]),np.linalg.norm(p[2]-p[0]),np.linalg.norm(p[2]-p[1]))
            count=max(1,math.ceil(longest/(step*.42)))
            values=np.array([(i/count,k/count) for i in range(count+1) for k in range(count+1-i)])
            sampled=p[0]+values[:,0,None]*(p[1]-p[0])+values[:,1,None]*(p[2]-p[0])
            indices=np.floor((sampled+1e-6)/step).astype(int)
            cells.update(map(tuple,indices.tolist()))
    return cells


def merge_cells(owners):
    """Greedy merge only touching cells with identical original component ownership."""
    remaining=dict(owners);boxes=[]
    for start in sorted(owners,key=lambda p:(p[1],p[2],p[0])):
        if start not in remaining:continue
        owner=remaining[start];x,y,z=start;dx=dy=dz=1
        while remaining.get((x+dx,y,z))==owner:dx+=1
        while all(remaining.get((xx,y+dy,z))==owner for xx in range(x,x+dx)):dy+=1
        while all(remaining.get((xx,yy,z+dz))==owner for xx in range(x,x+dx) for yy in range(y,y+dy)):dz+=1
        for xx in range(x,x+dx):
            for yy in range(y,y+dy):
                for zz in range(z,z+dz):remaining.pop((xx,yy,zz))
        boxes.append((owner,[x*STEP,y*STEP,z*STEP],[(x+dx)*STEP,(y+dy)*STEP,(z+dz)*STEP]))
    return boxes


def component_uvs(identifier,mesh,lo,hi,source_bounds):
    material=mesh['material'];u,v=tile(identifier,material)
    if mesh['name']=='square_head':
        uv={name:face_uv(identifier,'skin') for name in FACE_VERTICES}
        inverse_y=lambda y:(y+.30)/.91-.30 if y<-.30 else y
        a=max(0,min(1,(lo[0]/.84+4)/8));c=max(0,min(1,(hi[0]/.84+4)/8))
        b=max(0,min(1,(inverse_y(lo[1])+7.5)/8));d=max(0,min(1,(inverse_y(hi[1])+7.5)/8))
        uv['north']=face_uv(identifier,'face',(a,b,c,d))
        return uv
    if material.startswith('hair') or material in ('skin','skin_shadow','silver','gold'):
        return {name:[u+62,v+62,u+64,v+64] for name in FACE_VERTICES}
    minimum,maximum=source_bounds;span=np.maximum(maximum-minimum,.001)
    low=np.clip((np.array(lo)-minimum)/span,0,1);high=np.clip((np.array(hi)-minimum)/span,0,1)
    # Continuous planar projections keep plaid, fishnet and cloth readable.
    return {name:face_uv(identifier,material,(low[a],low[b],high[a],high[b]))
            for name,a,b in (('north',0,1),('south',0,1),('east',2,1),('west',2,1),('up',0,2),('down',0,2))}


def convert_part(part,identifier,path):
    result={'pivot':part['pivot'],'rotation':part['rotation'],'cubes':[],'children':{}}
    meshes=list(part['meshes']);source_count=len(meshes);owners={};bounds=[]
    for m in meshes:
        p=np.asarray(m['vertices']);bounds.append((p.min(axis=0),p.max(axis=0)))
    def fill_box(owner,lo,hi):
        low=np.floor((np.array(lo)+1e-6)/STEP).astype(int)
        high=np.ceil((np.array(hi)-1e-6)/STEP).astype(int)
        high=np.maximum(high,low+1)
        for x in range(low[0],high[0]):
            for y in range(low[1],high[1]):
                for z in range(low[2],high[2]):owners[(x,y,z)]=owner
    def explicit_box(name,lo,hi,material):
        owner=len(meshes);meshes.append({'name':name,'material':material,'vertices':[lo,hi]})
        bounds.append((np.array(lo),np.array(hi)));fill_box(owner,lo,hi)
    if path=='head':
        skull=next(m for m in meshes if m['name']=='soft_cheeks_and_short_jaw')
        vertices=np.asarray(skull['vertices']);lo=np.floor(vertices.min(axis=0)/STEP)*STEP;hi=np.ceil(vertices.max(axis=0)/STEP)*STEP
        explicit_box('square_head',lo.tolist(),hi.tolist(),'skin')
        # Square corner coverage for the new square skull, with the front open.
        x0,y0,z0=lo;x1,y1,z1=hi
        for name,a0,b0 in (
            ('square_hair_crown',[x0-.5,y0-.5,z0-.25],[x1+.5,y0,z1+.5]),
            ('square_hair_back',[x0-.5,y0,z1],[x1+.5,y1,z1+.5]),
            ('square_hair_side_l',[x0-.5,y0,z0+1.5],[x0,y1,z1]),
            ('square_hair_side_r',[x1,y0,z0+1.5],[x1+.5,y1,z1])):
            explicit_box(name,a0,b0,'hair')
    for i,m in enumerate(meshes[:source_count]):
        if path=='head' and m['name'] in ('soft_cheeks_and_short_jaw','soft_cheeks_and_short_jaw_face'):continue
        # Thin original trim keeps a proper small cuboid instead of disappearing.
        minimum,maximum=bounds[i]
        if np.min(maximum-minimum)<.18:
            center=(minimum+maximum)/2;size=np.maximum(maximum-minimum,.125)
            lo=(center-size/2).tolist();hi=(center+size/2).tolist()
            fill_box(i,lo,hi)
            continue
        for cell in sample_surface(m):owners[cell]=i
    for j,(owner,lo,hi) in enumerate(merge_cells(owners)):
        m=meshes[owner]
        result['cubes'].append({'name':m['name']+'__block_'+str(j),'from':lo,'to':hi,'material':m['material'],
                                'uv':component_uvs(identifier,m,lo,hi,bounds[owner]),'source_component':m['name']})
    for name,child in part['children'].items():result['children'][name]=convert_part(child,identifier,path+'/'+name)
    return result


def render_meshes(model):
    """Temporary face lists built only from the actual exported cuboid bounds/UVs."""
    def visit(part):
        result={k:part[k] for k in ('pivot','rotation')};result['meshes']=[];result['children']={}
        for c in part['cubes']:
            x0,y0,z0=c['from'];x1,y1,z1=c['to']
            vertices=[[x0,y0,z0],[x1,y0,z0],[x1,y1,z0],[x0,y1,z0],[x0,y0,z1],[x1,y0,z1],[x1,y1,z1],[x0,y1,z1]]
            faces=[]
            for name,ids in FACE_VERTICES.items():
                u0,v0,u1,v1=c['uv'][name]
                faces.append({'vertices':list(ids),'uv':[[u0/1024,v0/1024],[u0/1024,v1/1024],[u1/1024,v1/1024],[u1/1024,v0/1024]]})
            result['meshes'].append({'name':c['name'],'material':c['material'],'vertices':vertices,'faces':faces,'shading':'flat'})
        result['children']={name:visit(child) for name,child in part['children'].items()}
        return result
    return {**model,'parts':{name:visit(part) for name,part in model['parts'].items()}}


def editor_project(identifier,model,texture):
    groups=[];elements=[];outliner=[]
    uid=lambda name:str(uuid.uuid5(uuid.NAMESPACE_URL,'ic2newera:robot-cuboids/'+identifier+'/'+name))
    def visit(part,path,base):
        shift=base+np.array(part['pivot']);group=uid(path);rotation=np.degrees(part['rotation'])
        groups.append({'uuid':group,'name':path.split('/')[-1],'origin':[float(shift[0]),float(24-shift[1]),float(shift[2])],
                       'rotation':[-float(rotation[0]),float(rotation[1]),-float(rotation[2])],'export':True,'visibility':True,'isOpen':True})
        outline={'uuid':group,'isOpen':True,'children':[]}
        for i,c in enumerate(part['cubes']):
            lo=np.array(c['from'])+shift;hi=np.array(c['to'])+shift;eid=uid(path+'/'+str(i))
            elements.append({'type':'cube','uuid':eid,'name':c['name'],'from':[float(lo[0]),float(24-hi[1]),float(lo[2])],
                             'to':[float(hi[0]),float(24-lo[1]),float(hi[2])],'origin':[0,24,0],'rotation':[0,0,0],
                             'box_uv':False,'autouv':0,'shade':True,'visibility':True,'export':True,
                             'faces':{name:{'uv':uv,'texture':0} for name,uv in c['uv'].items()}})
            outline['children'].append(eid)
        for name,child in part['children'].items():outline['children'].append(visit(child,path+'/'+name,shift))
        return outline
    for name,part in model['parts'].items():outliner.append(visit(part,name,np.zeros(3)))
    data=io.BytesIO();texture.save(data,format='PNG')
    return {'meta':{'format_version':'5.0','model_format':'free','box_uv':False},'name':identifier+'_'+REVISION+'_cuboids',
            'model_identifier':'ic2newera:humanoid_robot_'+identifier,'resolution':{'width':1024,'height':1024},
            'elements':elements,'groups':groups,'outliner':outliner,'textures':[{'name':identifier+'.png','uuid':uid('texture'),'id':'0',
            'mode':'bitmap','width':1024,'height':1024,'uv_width':1024,'uv_height':1024,
            'source':'data:image/png;base64,'+base64.b64encode(data.getvalue()).decode('ascii')}]}


def generate(identifiers):
    for folder in ('models','textures','editable','preview'):(OUT/folder).mkdir(parents=True,exist_ok=True)
    records=[]
    for identifier in identifiers:
        old=json.loads((SOURCE/'models'/f'{identifier}.json').read_text(encoding='utf8'))
        model={'format':'ic2newera_rigged_cuboids_v1','revision':REVISION,'units_per_block':16,'coordinates':'x-right_y-down_z-back',
               'name':old['name'],'texture_size':[1024,1024],'lattice_step':STEP,'parts':{name:convert_part(part,identifier,name) for name,part in old['parts'].items()},
               'status':'原生方块元素研究稿，尚未接入游戏','source_revision':'V28'}
        path=OUT/'models'/f'{identifier}.json';path.write_text(json.dumps(model,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
        shutil.copyfile(SOURCE/'textures'/f'{identifier}.png',OUT/'textures'/f'{identifier}.png')
        texture=Image.open(OUT/'textures'/f'{identifier}.png').convert('RGBA')
        texture.paste(paint_pixel_face(identifier),tile(identifier,'face'));texture.save(OUT/'textures'/f'{identifier}.png')
        actual=json.loads(path.read_text(encoding='utf8'))
        project=editor_project(identifier,actual,texture);(OUT/'editable'/f'{identifier}.bbmodel').write_text(json.dumps(project,ensure_ascii=False,separators=(',',':')),encoding='utf8')
        proxy=render_meshes(actual)
        for suffix,orthographic in (('study',False),('orthographic',True)):
            image=preview.sheet(identifier,proxy,texture,orthographic=orthographic);draw=ImageDraw.Draw(image)
            draw.rectangle((0,0,1319,74),fill=(226,227,228,255))
            draw.text((32,23),preview.STYLE[identifier]['name']+' · '+REVISION+'原生长方体实际预览',font=preview.font(26),fill=(44,49,58))
            draw.rectangle((0,905,1319,949),fill=(226,227,228,255))
            draw.text((32,914),'从导出的长方体/骨骼/UV/贴图渲染；工程只有cube元素，无mesh曲面；未接入游戏。',font=preview.font(17),fill=(86,90,99))
            image.save(OUT/'preview'/f'{identifier}-{suffix}.png')
        preview.head_sheet(identifier,proxy,texture).save(OUT/'preview'/f'{identifier}-head-study.png')
        preview.head_sheet(identifier,proxy,texture,orthographic=True).save(OUT/'preview'/f'{identifier}-head-orthographic.png')
        records.append({'id':identifier,'revision':REVISION,'cubes':len(project['elements']),'meshes':0,'bones':len(project['groups']),
                        'source_parts_preserved':False,'source_texture_file_unchanged':False,'texture_change_scope':'face tile only',
                        'cube_project_sha256':hashlib.sha256((OUT/'editable'/f'{identifier}.bbmodel').read_bytes()).hexdigest()})
        print(json.dumps(records[-1],ensure_ascii=False),flush=True)
    (OUT/'generation-record.json').write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n',encoding='utf8')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('characters',nargs='*');args=parser.parse_args()
    if set(args.characters)-set(preview.STYLE):parser.error('Unknown character id')
    generate(args.characters or ['anon'])
