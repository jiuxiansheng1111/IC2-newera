"""Orthographic inspection of the exported textured quads, not an illustration."""
from pathlib import Path
import json, math, struct
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT/'build-isolated/migration-1201/newera/src/main/resources/assets/ic2newera'
OUT = ROOT/'art/robot-mcp-workspace/characters/anon-expression-r21/review'

def mesh(path):
    data = path.read_bytes()
    offset = 32
    parts = []
    for _ in range(9):
        *pivot, count = struct.unpack_from('>3fI', data, offset)
        offset += 16
        vertices = np.frombuffer(data, dtype='>f4', count=count*8, offset=offset).astype(np.float64).reshape(-1,8)
        offset += count*32
        parts.append(vertices)
    return parts

def render(key, smile, yaw):
    size = 420
    pixels = np.full((size,size,3), (231,234,240), dtype=np.uint8)
    depth = np.full((size,size), np.inf)
    tex = np.asarray(Image.open(ASSETS/f'textures/entity/robot/r21/{key}.png').convert('RGBA'))
    a = math.radians(yaw); c,s = math.cos(a),math.sin(a)
    rotation = np.array([[c,0,s],[0,1,0],[-s,0,c]])
    for index, original in enumerate(mesh(ASSETS/f'robot_models/r21/{key}.mesh')):
        if index == (7 if smile else 8): continue
        verts = original.copy()
        verts[:,:3] = verts[:,:3] @ rotation.T
        verts[:,0] = (verts[:,0]+(.12*s)+.245)/.49*size
        verts[:,1] = (verts[:,1]+.19)/.49*size
        for start in range(0,len(verts),4):
            quad = verts[start:start+4]
            for ids in ((0,1,2),(0,2,3)):
                v = quad[list(ids)]
                xmin,xmax = max(0,int(np.floor(v[:,0].min()))),min(size-1,int(np.ceil(v[:,0].max())))
                ymin,ymax = max(0,int(np.floor(v[:,1].min()))),min(size-1,int(np.ceil(v[:,1].max())))
                if xmax<xmin or ymax<ymin: continue
                x,y = np.meshgrid(np.arange(xmin,xmax+1)+.5,np.arange(ymin,ymax+1)+.5)
                den = (v[1,1]-v[2,1])*(v[0,0]-v[2,0])+(v[2,0]-v[1,0])*(v[0,1]-v[2,1])
                if abs(den)<1e-8: continue
                w0 = ((v[1,1]-v[2,1])*(x-v[2,0])+(v[2,0]-v[1,0])*(y-v[2,1]))/den
                w1 = ((v[2,1]-v[0,1])*(x-v[2,0])+(v[0,0]-v[2,0])*(y-v[2,1]))/den
                w2 = 1-w0-w1
                z = w0*v[0,2]+w1*v[1,2]+w2*v[2,2]
                u = np.clip(((w0*v[0,3]+w1*v[1,3]+w2*v[2,3])*tex.shape[1]).astype(int),0,tex.shape[1]-1)
                t = np.clip(((w0*v[0,4]+w1*v[1,4]+w2*v[2,4])*tex.shape[0]).astype(int),0,tex.shape[0]-1)
                samples = tex[t,u]
                area = depth[ymin:ymax+1,xmin:xmax+1]
                mask = (w0>=-1e-7)&(w1>=-1e-7)&(w2>=-1e-7)&(z<area)&(samples[:,:,3]>127)
                normal = original[start+ids[0],5:8] @ rotation.T
                shade = .84+.16*max(0,float(np.dot(normal, np.array([-.25,-.3,-.92]))))
                pixels[ymin:ymax+1,xmin:xmax+1][mask] = np.clip(samples[:,:,:3]*shade,0,255).astype(np.uint8)[mask]
                area[mask] = z[mask]
    return Image.fromarray(pixels)

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    facts = json.loads((ASSETS/'robot_models/r21/manifest.json').read_text(encoding='utf-8'))['expressions']
    board = Image.new('RGB',(5*420,2*460),(38,42,49))
    draw = ImageDraw.Draw(board)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',21)
    labels = dict(soft_smile='日常轻微笑',happy='开心',curious='疑惑',serious='认真',worried='担心')
    for col,fact in enumerate(facts):
        for row,yaw in enumerate((0,25)):
            img = render(fact['character'],fact['smile_part'],yaw)
            img.save(OUT/f"{fact['character']}-{yaw}.png")
            board.paste(img,(col*420,row*460+40))
            draw.text((col*420+15,row*460+8),labels[fact['expression']]+(' · 正面' if row==0 else ' · 斜侧'),font=font,fill='white')
    board.save(OUT/'anon-expression-r21-overview.png')
    print(str(OUT/'anon-expression-r21-overview.png'))

if __name__=='__main__': main()
