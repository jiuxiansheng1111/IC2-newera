"""Faithful export and inspection of original hand-authored cuboid designs.

No voxel conversion, character design, installation, or game execution occurs here.
All views rasterize the emitted Blockbench cube coordinates and UV rectangles.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import importlib
import io
import json
import math
from pathlib import Path
import uuid
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'art/robot-block-models'
FACE_IDS = {'north':(0,3,2,1),'south':(5,6,7,4),'west':(4,7,3,0),
            'east':(1,2,6,5),'up':(3,7,6,2),'down':(0,1,5,4)}
EULER_ORDER='ZYX'
ROTATION_SOURCE='https://raw.githubusercontent.com/JannisX11/blockbench/master/js/io/format.ts'

def rotation_matrix(degrees):
    x,y,z=np.radians(degrees);cx,sx=math.cos(x),math.sin(x);cy,sy=math.cos(y),math.sin(y);cz,sz=math.cos(z),math.sin(z)
    rx=np.array([[1,0,0],[0,cx,-sx],[0,sx,cx]])
    ry=np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]])
    rz=np.array([[cz,-sz,0],[sz,cz,0],[0,0,1]])
    return rz@ry@rx

def world_vertices(bb):
    """Original math for native free-format ZYX cube and parent transforms."""
    groups={g['uuid']:g for g in bb['groups']};parents={}
    def walk(items,parent=None):
        for item in items:
            if isinstance(item,str):parents[item]=parent
            else:parents[item['uuid']]=parent;walk(item['children'],item['uuid'])
    walk(bb['outliner']);result={}
    for e in bb['elements']:
        pivot=np.array(e['origin'],float)
        points=(vertices(e)-pivot)@rotation_matrix(e.get('rotation',[0,0,0])).T+pivot
        parent=parents[e['uuid']]
        while parent:
            group=groups[parent];p=np.array(group['origin'],float)
            points=(points-p)@rotation_matrix(group.get('rotation',[0,0,0])).T+p
            parent=parents[parent]
        result[e['uuid']]=points
    return result

def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf8')

def project(model, png):
    uid=lambda x:str(uuid.uuid5(uuid.NAMESPACE_URL,'ic2newera:astra-block/'+model['id']+'/'+x))
    groups=[]; elements=[]; outlines={}
    for part in model['parts']:
        name=part['name']; gid=uid('bone/'+name)
        groups.append({'name':name,'uuid':gid,'origin':part['pivot'],'rotation':part.get('rotation',[0,0,0]),'export':True,'visibility':True})
        outlines[name]={'uuid':gid,'children':[],'isOpen':True}
        for i,cube in enumerate(part['cubes']):
            eid=uid(name+'/'+str(i)+'/'+cube['name'])
            elements.append({'type':'cube','uuid':eid,'name':cube['name'],'from':cube['from'],'to':cube['to'],
                             'origin':cube.get('origin',part['pivot']),'rotation':cube.get('rotation',[0,0,0]),'box_uv':False,'autouv':0,'shade':True,'visibility':True,'export':True,
                             'faces':{face:{'uv':uv,'texture':0} for face,uv in cube['uv'].items()}})
            outlines[name]['children'].append(eid)
    roots=[]
    for part in model['parts']:
        parent=part.get('parent')
        if parent: outlines[parent]['children'].append(outlines[part['name']])
        else: roots.append(outlines[part['name']])
    w,h=model['texture_size']
    return {'meta':{'format_version':'5.0','model_format':'free','box_uv':False},'name':model['id']+'_'+model.get('design_revision','unversioned')+'_manual_cubes',
            'model_identifier':'ic2newera:humanoid_robot_'+model['id'],'resolution':{'width':w,'height':h},
            'elements':elements,'groups':groups,'outliner':roots,'textures':[{'name':model['id']+'.png','uuid':uid('texture'),'id':'0','mode':'bitmap',
            'width':w,'height':h,'uv_width':w,'uv_height':h,'source':'data:image/png;base64,'+base64.b64encode(png).decode()}]}

def inspect(model, bb, png):
    image=Image.open(io.BytesIO(png)).convert('RGBA'); w,h=image.size; errors=[]
    if [w,h]!=model['texture_size']: errors.append('texture dimensions differ')
    ids=[e['uuid'] for e in bb['elements']]+[g['uuid'] for g in bb['groups']]
    if len(ids)!=len(set(ids)): errors.append('duplicate UUID')
    for group in bb['groups']:
        if not np.all(np.isfinite(group['origin'])) or not np.all(np.isfinite(group['rotation'])):
            errors.append('invalid bone pivot/rotation '+group['name'])
    original=[cube for part in model['parts'] for cube in part['cubes']]
    faithful=len(original)==len(bb['elements'])
    for source, emitted in zip(original,bb['elements']):
        faithful &= source['from']==emitted['from'] and source['to']==emitted['to']
        faithful &= source.get('rotation',[0,0,0])==emitted['rotation']
        source_part=next(p for p in model['parts'] if source in p['cubes'])
        faithful &= source.get('origin',source_part['pivot'])==emitted['origin']
        faithful &= all(source['uv'][face]==emitted['faces'][face]['uv'] for face in FACE_IDS)
    if not faithful:errors.append('export changed source coordinates or UVs')
    face_cubes=[c for c in original if c['name'].startswith(('face_section_','face_hero'))]
    chart=model.get('face_chart');face_continuous=None
    if chart:
        u0,v0,u1,v1=chart['uv_rect'];x0,x1=chart['world_x'];y0,y1=chart['world_y']
        face_continuous=bool(face_cubes)
        for cube in face_cubes:
            a,b=cube['from'],cube['to']
            if chart.get('north_camera_convention'):
                expected=[u0+(x1-b[0])*(u1-u0)/(x1-x0),v0+(y1-b[1])*(v1-v0)/(y1-y0),
                          u0+(x1-a[0])*(u1-u0)/(x1-x0),v0+(y1-a[1])*(v1-v0)/(y1-y0)]
            else:
                expected=[u0+(a[0]-x0)*(u1-u0)/(x1-x0),v0+(y1-b[1])*(v1-v0)/(y1-y0),
                          u0+(b[0]-x0)*(u1-u0)/(x1-x0),v0+(y1-a[1])*(v1-v0)/(y1-y0)]
            face_continuous &= bool(np.allclose(cube['uv']['north'],expected,rtol=0,atol=1e-10))
        if not face_continuous:errors.append('face sections do not use the shared world-coordinate chart')
    references=[]
    def walk(items):
        for item in items:
            if isinstance(item,str): references.append(item)
            else: references.append(item['uuid']);walk(item['children'])
    walk(bb['outliner'])
    if sorted(references)!=sorted(ids):errors.append('outliner references do not match elements/groups')
    transparent=[]
    for e in bb['elements']:
        lo=np.array(e['from']);hi=np.array(e['to'])
        if e['type']!='cube' or not np.all(np.isfinite([lo,hi])) or not np.all(hi>lo):errors.append('invalid cube '+e['name'])
        if not np.all(np.isfinite(e['rotation'])) or not np.all(np.isfinite(e['origin'])):errors.append('invalid rotation/origin '+e['name'])
        if set(e['faces'])!=set(FACE_IDS):errors.append('missing face '+e['name'])
        for face,record in e['faces'].items():
            uv=record['uv'];a,b,c,d=uv
            if not np.all(np.isfinite(uv)) or min(a,c)<0 or min(b,d)<0 or max(a,c)>w or max(b,d)>h or a==c or b==d:
                errors.append('invalid UV '+e['name']+'/'+face);continue
            crop=image.crop((math.floor(min(a,c)),math.floor(min(b,d)),math.ceil(max(a,c)),math.ceil(max(b,d))))
            if crop.getchannel('A').getextrema()[0]<255:transparent.append(e['name']+'/'+face)
    embedded=base64.b64decode(bb['textures'][0]['source'].split(',',1)[1])
    if embedded!=png:errors.append('embedded PNG mismatch')
    if transparent:errors.append('UV rectangles sample transparent pixels')
    bounds=np.concatenate(list(world_vertices(bb).values()))
    return {'id':model['id'],'design_revision':model.get('design_revision','unversioned'),
            'coordinates':'Y-up, north camera at -Z, screen-right=-X; source copied unchanged',
            'euler_order':EULER_ORDER,'euler_order_source':ROTATION_SOURCE,
            'rotated_cube_count':sum(any(v!=0 for v in e['rotation']) for e in bb['elements']),
            'bounds':{'from':bounds.min(0).tolist(),'to':bounds.max(0).tolist()},
            'cube_count':len(bb['elements']),'mesh_count':sum(e['type']=='mesh' for e in bb['elements']),'bone_count':len(bb['groups']),
            'source_coordinates_and_uv_preserved':bool(faithful),
            'source_rotations_and_origins_preserved':bool(faithful),
            'face_section_count':len(face_cubes),'face_uv_chart_continuous':face_continuous,
            'static_pass':not errors,'errors':errors,'transparent_uv_faces':transparent,'embedded_png_matches':embedded==png,
            'appearance_acceptance':'pending root visual review and user acceptance','blockbench_import':'pending local UI inspection',
            'animation_game_build_install':'not performed; paused'}

def vertices(e):
    x0,y0,z0=e['from'];x1,y1,z1=e['to']
    return np.array([[x0,y0,z0],[x1,y0,z0],[x1,y1,z0],[x0,y1,z0],[x0,y0,z1],[x1,y0,z1],[x1,y1,z1],[x0,y1,z1]],float)

def render(bb, texture, angle, size=(650,950), bounds=None, albedo=False):
    """Orthographic z-buffer, nearest UV samples, actual outward face normals."""
    camera=np.array([math.sin(angle),0,-math.cos(angle)])
    right=np.array([-math.cos(angle),0,-math.sin(angle)]);up=np.array([0,1,0])
    transformed=world_vertices(bb)
    allv=np.concatenate(list(transformed.values()))
    if bounds is None:
        low=allv.min(axis=0);high=allv.max(axis=0)
        center=(low+high)/2; span=max(high[1]-low[1],1)
    else: center=np.array(bounds[0]);span=bounds[1]
    width,height=size;scale=min((height-80)/span,(width-60)/(span*.75))
    canvas=np.full((height,width,4),(230,233,238,255),np.uint8);depth=np.full((height,width),-np.inf)
    tex=np.asarray(texture.convert('RGBA'));th,tw=tex.shape[:2]
    lights=[(np.array([-.3,.6,-1.0]),.22),(np.array([.50,.30,1.0]),.12)]
    for e in bb['elements']:
        points=transformed[e['uuid']];p=points-center
        screen=np.column_stack((p@right*scale+width/2,height/2-p@up*scale,p@camera))
        for face,inds in FACE_IDS.items():
            q=points[list(inds)];normal=np.cross(q[1]-q[0],q[2]-q[0]);normal/=np.linalg.norm(normal)
            if normal@camera<=0:continue
            shade=.77+sum(max(0,normal@(direction/np.linalg.norm(direction)))*power for direction,power in lights)
            if albedo: shade=1.0
            a,b,c,d=e['faces'][face]['uv']
            if face=='up':uv=np.array([[a,b],[a,d],[c,d],[c,b]],float)
            elif face=='down':uv=np.array([[a,d],[c,d],[c,b],[a,b]],float)
            else:uv=np.array([[c,d],[c,b],[a,b],[a,d]],float)
            # Up/down have different plane axes; UV stays tied to the actual cube face.
            for tri in ((0,1,2),(0,2,3)):
                ss=screen[np.array(inds)[list(tri)]];uu=uv[list(tri)]
                xmin=max(0,int(np.floor(ss[:,0].min())));xmax=min(width-1,int(np.ceil(ss[:,0].max())))
                ymin=max(0,int(np.floor(ss[:,1].min())));ymax=min(height-1,int(np.ceil(ss[:,1].max())))
                if xmin>xmax or ymin>ymax:continue
                xx,yy=np.meshgrid(np.arange(xmin,xmax+1)+.5,np.arange(ymin,ymax+1)+.5)
                x0,y0=ss[0,:2];x1,y1=ss[1,:2];x2,y2=ss[2,:2]
                den=(y1-y2)*(x0-x2)+(x2-x1)*(y0-y2)
                if abs(den)<1e-9:continue
                l0=((y1-y2)*(xx-x2)+(x2-x1)*(yy-y2))/den
                l1=((y2-y0)*(xx-x2)+(x0-x2)*(yy-y2))/den;l2=1-l0-l1
                z=l0*ss[0,2]+l1*ss[1,2]+l2*ss[2,2]
                old=depth[ymin:ymax+1,xmin:xmax+1];mask=(l0>=-1e-8)&(l1>=-1e-8)&(l2>=-1e-8)&(z>old)
                u=l0*uu[0,0]+l1*uu[1,0]+l2*uu[2,0];v=l0*uu[0,1]+l1*uu[1,1]+l2*uu[2,1]
                sampled=tex[np.clip(v.astype(int),0,th-1),np.clip(u.astype(int),0,tw-1)].copy()
                sampled[:,:,:3]=np.clip(sampled[:,:,:3]*shade,0,255).astype(np.uint8)
                mask &= sampled[:,:,3]>0
                target=canvas[ymin:ymax+1,xmin:xmax+1];target[mask]=sampled[mask];old[mask]=z[mask]
    return Image.fromarray(canvas)

def generate(identifiers):
    design=importlib.import_module('robot_character_design')
    for identifier in identifiers:
        model=design.build_character(identifier)
        png=Path(model['texture_path']).read_bytes();bb=project(model,png)
        save_json(Path(model['texture_path']).with_name(identifier+'-design.json'),model)
        save_json(OUT/'models'/f'{identifier}.json',model)
        save_json(OUT/'editable'/f'{identifier}.bbmodel',bb)
        (OUT/'textures').mkdir(parents=True,exist_ok=True);(OUT/'textures'/f'{identifier}.png').write_bytes(png)
        report=inspect(model,bb,png)
        report['design_source_sha256']=hashlib.sha256(Path(design.__file__).read_bytes()).hexdigest()
        report['pipeline_source_sha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        for folder in ('models','editable','textures'):
            path=OUT/folder/(identifier+('.bbmodel' if folder=='editable' else '.png' if folder=='textures' else '.json'))
            report.setdefault('sha256',{})[str(path.relative_to(OUT))]=hashlib.sha256(path.read_bytes()).hexdigest()
        save_json(OUT/'checks'/f'{identifier}.json',report)
        if not report['static_pass']:raise ValueError(report['errors'])
        texture=Image.open(io.BytesIO(png));(OUT/'preview').mkdir(parents=True,exist_ok=True)
        views=[('front',0),('left-front',math.pi/4),('right-front',-math.pi/4),('left-side',math.pi/2),('right-side',-math.pi/2),('back',math.pi)]
        sheet=Image.new('RGBA',(650*3,950*2),(230,233,238,255))
        for index,(label,angle) in enumerate(views):
            view=render(bb,texture,angle);ImageDraw.Draw(view).text((15,15),identifier+' / '+label,fill=(35,39,45))
            view.save(OUT/'preview'/f'{identifier}-{label}.png');sheet.paste(view,(index%3*650,index//3*950))
        sheet.save(OUT/'preview'/f'{identifier}-six-views.png')
        head=[e for e in bb['elements'] if e['from'][1]>=23]
        transformed=world_vertices(bb)
        hv=np.concatenate([transformed[e['uuid']] for e in head]);low=hv.min(0);high=hv.max(0);center=(low+high)/2
        headspan=max(high[1]-low[1],high[0]-low[0],high[2]-low[2])*1.1
        heads=Image.new('RGBA',(2550,1700),(230,233,238,255))
        for index,(label,angle) in enumerate(views):
            view=render(bb,texture,angle,(850,850),(center,headspan))
            view.save(OUT/'preview'/f'{identifier}-head-{label}.png')
            ImageDraw.Draw(view).text((15,15),identifier+' / '+label,fill=(35,39,45))
            heads.paste(view,(index%3*850,index//3*850))
        heads.save(OUT/'preview'/f'{identifier}-head-six-views.png')
        color=render(bb,texture,0,(850,850),(center,headspan),albedo=True)
        ImageDraw.Draw(color).text((15,15),'ALBEDO ONLY / texture color inspection / no lighting',fill=(35,39,45))
        color.save(OUT/'preview'/f'{identifier}-head-front-albedo.png')
        previous=OUT/'iterations/a1/editable'/f'{identifier}.bbmodel'
        if previous.exists() and model.get('design_revision')!='A1':
            old=json.loads(previous.read_text(encoding='utf8'))
            oldtex=Image.open(OUT/'iterations/a1/textures'/f'{identifier}.png')
            compare=Image.new('RGBA',(1950,1900),(230,233,238,255))
            for row,(data,tex,label) in enumerate(((old,oldtex,'A1 not artist accepted'),(bb,texture,model.get('design_revision','current')))):
                for column,(view,angle) in enumerate((('front',0),('side',math.pi/2),('back',math.pi))):
                    panel=render(data,tex,angle);ImageDraw.Draw(panel).text((15,15),label+' / '+view,fill=(35,39,45))
                    compare.paste(panel,(column*650,row*950))
            compare.save(OUT/'preview'/f'{identifier}-a1-current-comparison.png')
        previous=OUT/'iterations/a2/editable'/f'{identifier}.bbmodel'
        if previous.exists() and model.get('design_revision') not in ('A1','A2'):
            old=json.loads(previous.read_text(encoding='utf8'))
            oldtex=Image.open(OUT/'iterations/a2/textures'/f'{identifier}.png')
            compare=Image.new('RGBA',(2550,1700),(230,233,238,255))
            for row,(data,tex,label) in enumerate(((old,oldtex,'A2 rejected square face'),(bb,texture,model.get('design_revision','current')))):
                for column,(view,angle) in enumerate((('front',0),('right-front',-math.pi/4),('right-side',-math.pi/2))):
                    panel=render(data,tex,angle,(850,850),(center,headspan))
                    ImageDraw.Draw(panel).text((15,15),label+' / '+view,fill=(35,39,45))
                    compare.paste(panel,(column*850,row*850))
            compare.save(OUT/'preview'/f'{identifier}-a2-head-comparison.png')
        previous=OUT/'iterations/a4/editable'/f'{identifier}.bbmodel'
        if previous.exists() and model.get('design_revision')=='A5':
            old=json.loads(previous.read_text(encoding='utf8'));oldtex=Image.open(OUT/'iterations/a4/textures'/f'{identifier}.png')
            compare=Image.new('RGBA',(2550,1700),(230,233,238,255))
            for row,(data,tex,label) in enumerate(((old,oldtex,'A4 re-rendered with corrected North camera/UV'),(bb,texture,'A5 original rotated cubes'))):
                for column,(view,angle) in enumerate((('front',0),('right-front',-math.pi/4),('back',math.pi))):
                    panel=render(data,tex,angle,(850,850),(center,headspan));ImageDraw.Draw(panel).text((15,15),label+' / '+view,fill=(35,39,45));compare.paste(panel,(column*850,row*850))
            compare.save(OUT/'preview'/f'{identifier}-a4-head-comparison.png')
        print(json.dumps(report,ensure_ascii=False),flush=True)
    actual=[]
    for cid in identifiers:
        actual.append(json.loads((OUT/'checks'/f'{cid}.json').read_text(encoding='utf8')))
    revisions={r['design_revision'] for r in actual}
    save_json(OUT/'generation-record.json',{'scope':list(identifiers),'uniform_design_revision':len(revisions)==1,
              'design_export_author':'GPT-6.1 Sol','root_role':'guidance, architecture and review',
              'revisions':sorted(revisions),'characters':actual,'scope_note':'Only listed characters exported; never implies eight-character completion'})

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('characters',nargs='+');args=parser.parse_args()
    generate(args.characters)
