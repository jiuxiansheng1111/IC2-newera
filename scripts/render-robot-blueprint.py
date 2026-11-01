"""Render authored compact blueprints using local vanilla block models/textures.

Static geometry preview only; does not start Minecraft or claim build reachability.
Minecraft assets stay in the user's local JAR. No assets are extracted or distributed.
"""
import argparse
import io
import json
import math
import re
import zipfile
from functools import lru_cache
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


def expand(data):
    blocks = {}
    for op in data.get('ops', []):
        a, b = op['from'], op['to']
        lo = [min(a[i], b[i]) for i in range(3)]
        hi = [max(a[i], b[i]) for i in range(3)]
        for x in range(lo[0], hi[0] + 1):
            for y in range(lo[1], hi[1] + 1):
                for z in range(lo[2], hi[2] + 1):
                    p = (x, y, z)
                    if op['type'] == 'clear':
                        blocks.pop(p, None)
                        continue
                    if op.get('hollow', False) and not any(p[i] in (lo[i], hi[i]) for i in range(3)):
                        continue
                    if op['type'] not in ('fill', 'box'):
                        raise ValueError('Preview currently supports fill, box, clear and explicit blocks')
                    blocks[p] = op['palette']
    for block in data.get('blocks', []):
        blocks[tuple(block['pos'])] = block.get('palette', block.get('block'))
    return blocks


def state(value):
    if isinstance(value, dict):
        return value['block'], dict(value.get('properties', {}))
    match = re.fullmatch(r'([^\[]+)(?:\[(.*)\])?', value)
    props = dict(entry.split('=', 1) for entry in match.group(2).split(',')) if match.group(2) else {}
    return match.group(1), props


def face_vertices(side, a, b):
    x, y, z = a
    X, Y, Z = b
    return np.array({
        'down': [(x,y,Z),(x,y,z),(X,y,z),(X,y,Z)],
        'up': [(x,Y,z),(x,Y,Z),(X,Y,Z),(X,Y,z)],
        'north': [(X,y,z),(x,y,z),(x,Y,z),(X,Y,z)],
        'south': [(x,y,Z),(X,y,Z),(X,Y,Z),(x,Y,Z)],
        'west': [(x,y,z),(x,y,Z),(x,Y,Z),(x,Y,z)],
        'east': [(X,y,Z),(X,y,z),(X,Y,z),(X,Y,Z)],
    }[side], dtype=float)


def default_uv(side, a, b):
    x,y,z = a
    X,Y,Z = b
    return {
        'down': [x,16-Z,X,16-z], 'up': [x,z,X,Z],
        'north': [16-X,16-Y,16-x,16-y], 'south': [x,16-Y,X,16-y],
        'west': [z,16-Y,Z,16-y], 'east': [16-Z,16-Y,16-z,16-y]
    }[side]


def rotate(vertices, axis, degrees, origin=(8,8,8)):
    a = math.radians(degrees)
    c,s = math.cos(a), math.sin(a)
    matrix = {'x': [[1,0,0],[0,c,-s],[0,s,c]],
              'y': [[c,0,-s],[0,1,0],[s,0,c]],
              'z': [[c,-s,0],[s,c,0],[0,0,1]]}[axis]
    return (vertices-np.array(origin)) @ np.array(matrix).T + np.array(origin)


class Assets:
    def __init__(self, path):
        self.jar = zipfile.ZipFile(path)

    @lru_cache(None)
    def document(self, name):
        return json.loads(self.jar.read(name))

    @lru_cache(None)
    def model(self, name):
        namespace, tail = name.split(':', 1) if ':' in name else ('minecraft', name)
        obj = self.document(f'assets/{namespace}/models/{tail}.json')
        base = self.model(obj['parent']) if 'parent' in obj and not obj['parent'].startswith('builtin/') else {}
        result = dict(base)
        result.update(obj)
        result['textures'] = dict(base.get('textures', {})) | obj.get('textures', {})
        return result

    @lru_cache(None)
    def texture(self, name):
        namespace, tail = name.split(':', 1) if ':' in name else ('minecraft', name)
        img = Image.open(io.BytesIO(self.jar.read(f'assets/{namespace}/textures/{tail}.png'))).convert('RGBA')
        if img.height != img.width:
            img = img.crop((0,0,img.width,img.width))
        return np.array(img)

    @lru_cache(None)
    def meshes(self, block_id, prop_tuple):
        namespace, tail = block_id.split(':', 1)
        doc = self.document(f'assets/{namespace}/blockstates/{tail}.json')
        if 'variants' not in doc:
            raise ValueError(f'Multipart block needs explicit neighbour support: {block_id}')
        props = {'facing':'north','half':'bottom','shape':'straight','waterlogged':'false',
                 'axis':'y','type':'bottom','rotation':'0'} | dict(prop_tuple)
        choices = []
        for key, value in doc['variants'].items():
            required = dict(pair.split('=',1) for pair in key.split(',')) if key else {}
            if all(props.get(k) in v.split('|') for k,v in required.items()):
                choices.append(value)
        if len(choices) != 1:
            raise ValueError(f'Unresolved blockstate {block_id}: {props}')
        choice = choices[0][0] if isinstance(choices[0], list) else choices[0]
        model = self.model(choice['model'])
        faces = []
        for elem in model['elements']:
            a,b = elem['from'],elem['to']
            for side, face in elem['faces'].items():
                vertices = face_vertices(side,a,b)
                if 'rotation' in elem:
                    r=elem['rotation']
                    vertices=rotate(vertices,r['axis'],r['angle'],r['origin'])
                vertices=rotate(vertices,'x',choice.get('x',0))
                vertices=rotate(vertices,'y',choice.get('y',0))
                normal=np.cross(vertices[1]-vertices[0],vertices[2]-vertices[0])
                normal/=np.linalg.norm(normal)
                tex=face['texture']
                seen=set()
                while tex.startswith('#'):
                    if tex in seen:
                        raise ValueError('Texture reference cycle')
                    seen.add(tex)
                    tex=model['textures'][tex[1:]]
                u,v,U,V=face.get('uv',default_uv(side,a,b))
                uv=np.array([(u,V),(U,V),(U,v),(u,v)],dtype=float)/16
                uv=np.roll(uv,face.get('rotation',0)//90,axis=0)
                faces.append((vertices/16,normal,uv,self.texture(tex)))
        return faces


class Renderer:
    def __init__(self, width, height, camera, bounds, positions):
        self.width,self.height=width,height
        self.rgb=np.full((height,width,3),(237,234,224),dtype=np.uint8)
        self.depth=np.full((height,width),-np.inf,dtype=np.float32)
        self.camera=np.array(camera,dtype=float)
        self.camera/=np.linalg.norm(self.camera)
        self.right=np.cross([0,1,0],self.camera)
        self.right/=np.linalg.norm(self.right)
        self.up=np.cross(self.camera,self.right)
        self.light=np.array([-.45,.85,.35])
        self.light/=np.linalg.norm(self.light)
        corners=np.array([[x,y,z] for x in [0,1] for y in [0,1] for z in [0,1]])
        points=(positions[:,None,:]+corners[None,:,:]).reshape(-1,3)
        self.center=(points.min(0)+points.max(0))/2
        raw=self.raw(points)
        self.scale=min(width*.82/np.ptp(raw[:,0]),height*.79/np.ptp(raw[:,1]))
        self.offset=np.array([width/2,height*.55])-(raw[:,:2].max(0)+raw[:,:2].min(0))/2*self.scale

    def raw(self, points):
        p=points-self.center
        return np.column_stack((p@self.right,-p@self.up,p@self.camera))

    def project(self, points):
        out=self.raw(points)
        out[:,:2]=out[:,:2]*self.scale+self.offset
        return out

    def triangle(self, vertices, uv, tex, shade=1):
        p=self.project(vertices)
        lo=np.maximum(np.floor(p[:,:2].min(0)).astype(int),[0,0])
        hi=np.minimum(np.ceil(p[:,:2].max(0)).astype(int),[self.width-1,self.height-1])
        if any(lo>hi): return
        xx,yy=np.meshgrid(np.arange(lo[0],hi[0]+1)+.5,np.arange(lo[1],hi[1]+1)+.5)
        a,b,c=p
        denominator=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
        if abs(denominator)<1e-8:return
        w0=((b[1]-c[1])*(xx-c[0])+(c[0]-b[0])*(yy-c[1]))/denominator
        w1=((c[1]-a[1])*(xx-c[0])+(a[0]-c[0])*(yy-c[1]))/denominator
        w2=1-w0-w1
        zz=w0*a[2]+w1*b[2]+w2*c[2]
        target=self.depth[lo[1]:hi[1]+1,lo[0]:hi[0]+1]
        mask=(w0>=-1e-6)&(w1>=-1e-6)&(w2>=-1e-6)&(zz>target+1e-5)
        if not mask.any(): return
        u=w0*uv[0,0]+w1*uv[1,0]+w2*uv[2,0]
        v=w0*uv[0,1]+w1*uv[1,1]+w2*uv[2,1]
        tx=np.clip((u*tex.shape[1]).astype(int),0,tex.shape[1]-1)
        ty=np.clip((v*tex.shape[0]).astype(int),0,tex.shape[0]-1)
        sample=tex[ty,tx]
        mask&=sample[:,:,3]>=128
        dest=self.rgb[lo[1]:hi[1]+1,lo[0]:hi[0]+1]
        dest[mask]=np.clip(sample[:,:,:3][mask]*shade,0,255).astype(np.uint8)
        target[mask]=zz[mask]

    def quad(self, vertices, normal, uv, tex, shade=None):
        if np.dot(normal,self.camera)<=.0001:return
        if shade is None:
            shade=.57+.43*max(0,np.dot(normal,self.light))
        for idx in ([0,1,2],[0,2,3]):
            self.triangle(vertices[idx],uv[idx],tex,shade)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('blueprint',type=Path)
    parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--camera',type=float,nargs=3,default=[1,.72,1.35])
    parser.add_argument('--width',type=int,default=1800)
    parser.add_argument('--height',type=int,default=1400)
    args=parser.parse_args()
    data=json.loads(args.blueprint.read_text(encoding='utf-8'))
    blocks=expand(data)
    positions=np.array(list(blocks))
    bounds=(positions.min(0),positions.max(0))
    render=Renderer(args.width,args.height,args.camera,bounds,positions)
    assets=Assets(args.jar)
    ground=np.array([[[220,216,200,255]]],dtype=np.uint8)
    lo,hi=bounds
    y=lo[1]-.02
    surface=np.array([[lo[0]-6,y,lo[2]-6],[lo[0]-6,y,hi[2]+7],
                      [hi[0]+7,y,hi[2]+7],[hi[0]+7,y,lo[2]-6]],dtype=float)
    uv=np.array([[0,0],[0,1],[1,1],[1,0]])
    render.quad(surface,np.array([0,1,0]),uv,ground,1)
    # Ground-only cast silhouettes, computed from the blueprint's real block bounds.
    shadow=np.array([[[195,191,176,255]]],dtype=np.uint8)
    for p in blocks:
        top=face_vertices('up',np.array(p),np.array(p)+1)
        drop=top[:,1]-y
        top[:,0]+=drop*.38
        top[:,2]-=drop*.3
        top[:,1]=y+.002
        render.quad(top,np.array([0,1,0]),uv,shadow,1)
    face_count=0
    for p,value in blocks.items():
        block_id,props=state(data['palette'].get(value,value))
        for vertices,normal,face_uv,tex in assets.meshes(block_id,tuple(sorted(props.items()))):
            if np.dot(normal,render.camera)>.0001:
                render.quad(vertices+np.array(p),normal,face_uv,tex)
                face_count+=1
    image=Image.fromarray(render.rgb)
    draw=ImageDraw.Draw(image)
    font_path=Path('C:/Windows/Fonts/msyh.ttc')
    font=ImageFont.truetype(str(font_path),36)
    small=ImageFont.truetype(str(font_path),23)
    draw.text((68,45),data.get('title','建筑图纸预览'),font=font,fill=(43,48,43))
    dims=hi-lo+1
    draw.text((70,99),f'{dims[0]} × {dims[2]} × {dims[1]} 格  ·  {len(blocks):,} 个方块  ·  原版方块模型与材质',font=small,fill=(91,95,82))
    draw.text((70,args.height-61),'静态图纸预览，非游戏截图；施工通路与整体建造尚未实机验收。',font=small,fill=(91,95,82))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    image.save(args.output)
    print(json.dumps({'parts':len(blocks),'drawnFaces':face_count,'size':dims.tolist(),
                      'output':str(args.output),'source':'local vanilla 1.20.1 models and textures',
                      'gameExecuted':False},ensure_ascii=False))


if __name__=='__main__':
    main()
