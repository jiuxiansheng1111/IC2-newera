"""Original character-by-character mesh studies, authored on the main line.

Model units are 1/16 of a Minecraft block, Y points down, north is -Z.
This is an art source and preview tool, not a claim of a running entity renderer.
"""
from __future__ import annotations

import argparse
import base64
import json
import math
import uuid
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
STUDY = ROOT / "art/robot-rework"
RESOLUTION = 1024
TILE = 128
MODEL_REVISION = "V28"

STYLE = {
    "anon": dict(name="千早爱音", hair="#E4AABE", light="#F7D1DD", dark="#B5829A", blue="#376C89", eye="#829AA4", length=12.0),
    "sakiko": dict(name="丰川祥子", hair="#8DAAC7", light="#BDD1E5", dark="#687F9E", blue="#A4A5BC", eye="#B9A376", length=7.0),
    "tomori": dict(name="高松灯", hair="#7E787C", light="#B6ADB3", dark="#5A555E", blue="#427694", eye="#AD7681", length=0.5),
    "soyo": dict(name="长崎爽世", hair="#B19685", light="#E0C6AD", dark="#846C61", blue="#3D6D85", eye="#6F8FA7", length=9.0),
    "taki": dict(name="椎名立希", hair="#453746", light="#806574", dark="#2E2634", blue="#365E7A", eye="#AC699B", length=10.5),
    "rana": dict(name="要乐奈", hair="#D5D8D1", light="#F2F1E7", dark="#A0ABA7", blue="#397796", eye="#8AAAC2", length=0.0),
    "uika": dict(name="三角初华", hair="#EBDFB9", light="#F7EED6", dark="#C1B085", blue="#30313C", eye="#AA84B7", length=0.65),
    "umiri": dict(name="八幡海铃", hair="#5D5D61", light="#93959D", dark="#3B3E44", blue="#623842", eye="#6E9CA0", length=1.35),
}


def rgba(color):
    color = color.lstrip("#")
    return tuple(int(color[i:i+2], 16) for i in (0, 2, 4)) + (255,)


def tint(color, factor):
    return tuple(max(0, min(255, round(v * factor))) for v in rgba(color)[:3]) + (255,)


def materials(identifier):
    s = STYLE[identifier]
    blend=lambda first,second,f: '#'+''.join(f'{round(a*(1-f)+b*f):02X}' for a,b in zip(rgba(first)[:3],rgba(second)[:3]))
    return {
        "skin": "#F3DCCD", "skin_shadow": "#D5B4A6", "white": "#F4F0E6",
        "white_shadow": "#DCDDD8", "blue": s["blue"], "blue_shadow": "#31556C",
        "gold": "#C5AA65", "black": "#30323D", "leather": "#34333D",
        "sole": "#5C6066", "silver": "#8A98A3", "green": "#758267",
        "green_shadow": "#576751", "hair": s["hair"], "hair_light": blend(s["hair"],s["light"],.38),
        "hair_dark": blend(s["hair"],s["dark"],.42),
        "face": "#F3DCCD", "plaid": "#73836B",
        "fishnet": "#EBD1C3", "cream_shoe": "#E5DFD0", "burgundy": "#713D49",
        "denim": "#47758C", "cuff": "#E4DDD2", "navy": "#45435A",
        "hair_inner": "#DABFC2" if identifier=="uika" else s["hair"],
        "hair_flow": s["hair"],
    }


def node(pivot=(0, 0, 0), rotation=(0, 0, 0)):
    return {"pivot": list(pivot), "rotation": list(rotation), "meshes": [], "children": {}}


def mesh(part, name, vertices, faces, material, face_map=False):
    result = {"name": name, "material": material,
              "vertices": [[round(float(v), 5) for v in p] for p in vertices], "faces": []}
    for f in faces:
        indices = list(f)
        if face_map:
            uv = [[(vertices[i][0]+4)/8, (vertices[i][1]+7.5)/8] for i in indices]
        elif len(indices) == 4:
            uv = [[0, 0], [1, 0], [1, 1], [0, 1]]
        else:
            uv = [[.5, .5]] + [[.5+math.cos(k*2*math.pi/(len(indices)-1))*.48,
                                .5+math.sin(k*2*math.pi/(len(indices)-1))*.48] for k in range(len(indices)-1)]
        result["faces"].append({"vertices": indices, "uv": uv})
    part["meshes"].append(result)
    return result


def vertex_normals(vertices, faces):
    """Original area-weighted polygon normals, computed after final proportions."""
    points=np.asarray(vertices,dtype=float); accumulated=np.zeros_like(points)
    for face in faces:
        indices=face["vertices"]; p=points[indices]; normal=np.zeros(3)
        for j in range(1,len(indices)-1):
            normal+=np.cross(p[j]-p[0],p[j+1]-p[0])
        accumulated[indices]+=normal
    lengths=np.linalg.norm(accumulated,axis=1); valid=lengths>1e-8
    accumulated[valid]/=lengths[valid,None]
    accumulated[~valid]=[0,0,-1]
    return np.round(accumulated,7).tolist()


def loft(part, name, rings, material, sides=12, head=False):
    """A tailored radial surface; ring entries are (y, half-width, depth, x, z)."""
    vertices = []
    front_samples=(-1,-.82,-.66,-.50,-.33,-.20,-.12,-.06,0,.06,.12,.20,.33,.50,.66,.82,1)
    front_segments=len(front_samples)-1 if head else 0
    for y, width, depth, x, z in rings:
        if head:
            b = min(1.05, width*.32)
            q=width-b
            profile=[]
            for fraction in front_samples:
                local_x=q*fraction
                # Continuous shallow face relief, in the same surface and UV space.
                nose=.145*math.exp(-((local_x/.36)**2+((y+2.36)/.32)**2))
                bridge=.045*math.exp(-((local_x/.24)**2+((y+2.83)/.60)**2))
                lip=.035*math.exp(-((local_x/.64)**2+((y+1.57)/.22)**2))
                profile.append((local_x,-depth-.26*(1-fraction*fraction)-nose-bridge-lip))
            profile += [(width,-depth+b),(width,depth-b),(width-b,depth),(-width+b,depth),
                        (-width,depth-b),(-width,-depth+b)]
        else:
            profile = [(math.sin(k*2*math.pi/sides)*width, -math.cos(k*2*math.pi/sides)*depth)
                       for k in range(sides)]
        vertices.extend([(x+dx, y, z+dz) for dx, dz in profile])
    n = len(front_samples)+6 if head else sides
    body_faces, front_faces = [], []
    for j in range(len(rings)-1):
        for k in range(n):
            # Outward winding with model-space Y downward.
            f = (j*n+k, (j+1)*n+k, (j+1)*n+(k+1)%n, j*n+(k+1)%n)
            (front_faces if head and k < front_segments else body_faces).append(f)
    body_faces.append(tuple(range(n)))
    body_faces.append(tuple((len(rings)-1)*n+i for i in reversed(range(n))))
    shell=mesh(part, name, vertices, body_faces, material)
    span=max(rings[-1][0]-rings[0][0],.001)
    side_index=0
    for j in range(len(rings)-1):
        for k in range(n):
            if head and k<front_segments: continue
            a,b=k/n,(k+1)/n; t0=(rings[j][0]-rings[0][0])/span; t1=(rings[j+1][0]-rings[0][0])/span
            shell["faces"][side_index]["uv"]=[[a,t0],[a,t1],[b,t1],[b,t0]]
            side_index+=1
    if front_faces:
        mesh(part, name+"_face", vertices, front_faces, "face", face_map=True)


def box(part, name, center, size, material):
    x,y,z = center; w,h,d = size
    vertices = [(x+sx*w/2,y+sy*h/2,z+sz*d/2) for sx,sy,sz in
                ((-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1))]
    return mesh(part, name, vertices, [(0,3,2,1),(5,6,7,4),(4,7,3,0),(1,2,6,5),(0,1,5,4),(3,7,6,2)], material)


def curve(part, name, points, widths, depth, material, sides=6):
    """Tapered, curved polygonal strand or fabric piping, not a solid hair slab."""
    p = np.asarray(points, dtype=float)
    if material.startswith("hair") and len(p)>=3:
        # Interpolate only hair: the accepted body and garment meshes stay exact.
        original=p; sizes=np.asarray(widths,dtype=float); samples=[]; new_sizes=[]
        for j in range(len(original)-1):
            p0=original[max(j-1,0)]; p1=original[j]; p2=original[j+1]; p3=original[min(j+2,len(original)-1)]
            for t in (0,.25,.5,.75):
                samples.append(.5*((2*p1)+(-p0+p2)*t+(2*p0-5*p1+4*p2-p3)*t*t+(-p0+3*p1-3*p2+p3)*t*t*t))
                new_sizes.append(sizes[j]*(1-t)+sizes[j+1]*t)
        p=np.asarray(samples+[original[-1]]); widths=new_sizes+[sizes[-1]]
    vertices = []
    hair=material.startswith("hair")
    largest_width=max(widths) if hair else 1
    previous_right=None
    for i, center in enumerate(p):
        tangent = p[min(i+1,len(p)-1)]-p[max(0,i-1)]
        tangent /= max(np.linalg.norm(tangent), 1e-8)
        right = (previous_right-tangent*np.dot(previous_right,tangent)) if hair and previous_right is not None else np.cross(tangent,[0,0,1])
        if np.linalg.norm(right) < .01: right = np.cross(tangent,[0,1,0])
        right /= np.linalg.norm(right)
        if hair: previous_right=right
        back = np.cross(right, tangent)
        for k in range(sides):
            angle = k*2*math.pi/sides
            thickness=depth*math.sqrt(max(.025,widths[i]/largest_width)) if hair else depth
            vertices.append(center+right*math.cos(angle)*widths[i]/2+back*math.sin(angle)*thickness/2)
    faces = []
    for i in range(len(p)-1):
        for k in range(sides):
            faces.append((i*sides+k,(i+1)*sides+k,(i+1)*sides+(k+1)%sides,i*sides+(k+1)%sides))
    faces += [tuple(range(sides)),tuple((len(p)-1)*sides+k for k in reversed(range(sides)))]
    strand=mesh(part,name,vertices,faces,material)
    for i in range(len(p)-1):
        for k in range(sides):
            strand["faces"][i*sides+k]["uv"]=[[k/sides,i/(len(p)-1)],[k/sides,(i+1)/(len(p)-1)],
                [(k+1)/sides,(i+1)/(len(p)-1)],[(k+1)/sides,i/(len(p)-1)]]
    return strand


def panel(part, name, corners, material):
    """Thin two-sided cloth panel, with independent corner positions and UVs."""
    mesh(part,name,corners,[(0,1,2,3),(3,2,1,0)],material)


def join_rear_hair(base, rings, sides, tracks, uv_height):
    """Author a continuous outer/inner hair surface joined to the crown vertices."""
    columns=[k for k in range(sides) if abs(k*2*math.pi/sides-math.pi)<=1.47]
    root_row=[(len(rings)-1)*sides+k for k in columns]

    def lower_path(points, progress):
        points=np.asarray(points,dtype=float)
        for j in range(len(points)-1):
            if points[j,1]<=-1.0<points[j+1,1]:
                fraction=(-1.0-points[j,1])/(points[j+1,1]-points[j,1])
                points=np.vstack((points[j]*(1-fraction)+points[j+1]*fraction,points[j+1:]))
                break
        distances=np.r_[0,np.cumsum(np.linalg.norm(np.diff(points,axis=0),axis=1))]
        position=progress*distances[-1]
        return np.array([np.interp(position,distances,points[:,axis]) for axis in range(3)])

    def position_at(column, progress):
        angle=column*2*math.pi/sides
        slot=max(0,min(len(tracks)-1,(math.pi-angle+1.30)/2.60*(len(tracks)-1)))
        left=max(0,min(len(tracks)-1,int(slot))); right=min(left+1,len(tracks)-1)
        blend=slot-left
        position=lower_path(tracks[left],progress)*(1-blend)+lower_path(tracks[right],progress)*blend
        start=lower_path(tracks[left],0)*(1-blend)+lower_path(tracks[right],0)*blend
        root=np.asarray(base["vertices"][(len(rings)-1)*sides+column])
        position+=(root-start)*(1-progress)**2
        fade=min(1,max(0,(1.30-abs(math.pi-angle))/.15))
        relief=.34*(.5+.5*math.cos(slot*2*math.pi))*fade
        position+=np.array([math.sin(angle),0,-math.cos(angle)])*relief*(1-(1-progress)**2)*(1-progress*.70)
        edge=min(1,abs(math.pi-angle)/1.30)**6
        opening=(1-(1-progress)**4)*(1-progress*.55)
        position[0]+=math.copysign(.60*edge*opening,math.sin(angle))
        position[2]-=.40*edge*opening
        position[2]-=.60*max(0,(abs(math.pi-angle)-1.30)/.17)*(1-(1-progress)**2)
        position[1]+=progress**10*(.08-.12*(.5+.5*math.cos(slot*2*math.pi)))
        return position

    outer_rows=[root_row]; inner_rows=[]
    for step in range(9):
        progress=step/8
        outer=[]; inner=[]
        for i,column in enumerate(columns):
            position=np.asarray(base["vertices"][root_row[i]]) if step==0 else position_at(column,progress)
            angle=column*2*math.pi/sides
            if step:
                outer.append(len(base["vertices"])); base["vertices"].append(position.tolist())
            lining=position-np.array([math.sin(angle),0,-math.cos(angle)])*.28*(1-progress*.65)
            inner.append(len(base["vertices"])); base["vertices"].append(lining.tolist())
        if step: outer_rows.append(outer)
        inner_rows.append(inner)

    def add_face(indices, columns_for_uv):
        uv=[[column/sides,(base["vertices"][index][1]-rings[0][0])/uv_height]
            for index,column in zip(indices,columns_for_uv)]
        base["faces"].append({"vertices":list(indices),"uv":uv})

    for step in range(8):
        for i in range(len(columns)-1):
            k0,k1=columns[i:i+2]
            add_face((outer_rows[step][i],outer_rows[step+1][i],outer_rows[step+1][i+1],outer_rows[step][i+1]),(k0,k0,k1,k1))
            add_face((inner_rows[step][i+1],inner_rows[step+1][i+1],inner_rows[step+1][i],inner_rows[step][i]),(k1,k1,k0,k0))
        for edge in (0,len(columns)-1):
            indices=(outer_rows[step][edge],inner_rows[step][edge],inner_rows[step+1][edge],outer_rows[step+1][edge])
            if edge==0: indices=indices[::-1]
            add_face(indices,(columns[edge],)*4)
    for i in range(len(columns)-1):
        k0,k1=columns[i:i+2]
        add_face((outer_rows[-1][i],inner_rows[-1][i],inner_rows[-1][i+1],outer_rows[-1][i+1]),(k0,k0,k1,k1))
        add_face((outer_rows[0][i+1],inner_rows[0][i+1],inner_rows[0][i],outer_rows[0][i]),(k1,k1,k0,k0))


def sculpt_ear(part, side):
    """A buried root, connected helix and shallow concha, in one original mesh."""
    outline=np.array([(-3.55,-.08),(-3.50,-.24),(-3.33,-.35),(-3.05,-.39),
                      (-2.73,-.32),(-2.42,-.19),(-2.19,-.07),(-2.14,.09),
                      (-2.26,.22),(-2.55,.29),(-2.85,.31),(-3.18,.27),
                      (-3.44,.18),(-3.55,.05)])
    center=np.array([-2.87,-.03]); vertices=[]; count=len(outline)
    for ring,(width,scale) in enumerate(((3.15,.90),(3.60,1.0),(3.64,.74),(3.58,.40))):
        for y,z in outline:
            point=center+(np.array([y,z])-center)*scale
            # The upper edge is slightly broader; the lobe folds into the root.
            lobe=max(0,min(1,(y+2.55)/.41))
            x=width-(.08*lobe if ring in (1,2) else 0)
            vertices.append((side*x,point[0],point[1]))
    faces=[]
    for ring in range(3):
        for k in range(count):
            faces.append((ring*count+k,ring*count+(k+1)%count,
                          (ring+1)*count+(k+1)%count,(ring+1)*count+k))
    faces.extend((tuple(reversed(range(count))),tuple(3*count+k for k in range(count))))
    if side<0: faces=[tuple(reversed(face)) for face in faces]
    mesh(part,"ear_"+str(side),vertices,faces,"skin")


def head(part, identifier):
    s = STYLE[identifier]
    rounded=identifier in ("tomori","rana"); slender=identifier in ("taki","umiri")
    face_rings=[(-7.1,2.75,2.40,0,0),(-6.25,3.35,2.85,0,0),(-3.30,3.36,2.85,0,0),
        (-2.05,3.32 if rounded else (3.12 if slender else 3.24),2.84,0,0),
        (-1.40,3.12 if rounded else (2.88 if slender else 2.98),2.70,0,-.03),
        (-.85,2.78 if rounded else (2.43 if slender else 2.58),2.45,0,.06),
        (-.48,2.08 if rounded else 1.78,2.10,0,.14),(-.27,1.10,1.70,0,.24),(-.16,.48,1.27,0,.32)]
    face_width={"anon":1.0,"sakiko":.97,"tomori":1.02,"soyo":.98,"taki":.95,"rana":1.02,"uika":.97,"umiri":.95}[identifier]
    # Interpolate the existing silhouette; only front depth receives facial relief.
    detailed_rings=[]
    for first,second in zip(face_rings,face_rings[1:]):
        steps=max(1,math.ceil((second[0]-first[0])/.24))
        for step in range(steps):
            amount=step/steps
            detailed_rings.append(tuple(a+(b-a)*amount for a,b in zip(first,second)))
    face_rings=detailed_rings+[face_rings[-1]]
    loft(part,"soft_cheeks_and_short_jaw",[(y,w*face_width,d,x,z) for y,w,d,x,z in face_rings],"skin",head=True)
    loft(part,"neck_above_collar",[(-.70,.78,.71,0,0),(.16,.80,.72,0,0)],"skin",sides=8)
    for side in (-1,1):
        if identifier in ("sakiko","uika"):
            sculpt_ear(part,side)
            box(part,"ear_joint_"+str(side),(side*3.19,-2.6,.27),(.10,.32,.24),"silver")
        else:
            loft(part,"ear_"+str(side),[(-3.45,.21,.25,side*3.32,-.2),(-2.0,.26,.30,side*3.28,-.2)],"skin",sides=6)
            box(part,"ear_joint_"+str(side),(side*3.48,-2.6,.35),(.10,.32,.24),"silver")
    # Rectangular-bevel scalp coverage matches the skull, so rounded caps cannot
    # leave bare corners protruding. The lower front remains open for the eyes.
    scalp=[]
    rings=[(-5.85-2.30*math.cos(a),3.66*math.sin(a)**.75+.14*math.sin(a)**3,
            3.18*math.sin(a)**.80+.14*math.sin(a)**3)
           for a in np.linspace(.10,math.pi/2,12)]
    rings += ([(-4.60,3.78,3.29),(-3.55,3.70,3.27),(-2.65,3.63,3.25),(-1.75,3.56,3.23),(-1.0,3.50,3.22)]
              if identifier in ("sakiko","uika") else [(-4.60,3.78,3.29),(-1.0,3.50,3.22)])
    rings=[(y,w*(1+.015*max(0,(-5.85-y)/2.3)),d*(1+.015*max(0,(-5.85-y)/2.3))) for y,w,d in rings]
    n=144
    rear_count=11 if identifier=="sakiko" else 17
    for y,w,d in rings:
        for k in range(n):
            a=k*2*math.pi/n; sx=math.sin(a); cz=math.cos(a)
            x=math.copysign(abs(sx)**.70,sx)*w; z=-math.copysign(abs(cz)**.70,cz)*d
            if identifier in ("sakiko","uika"):
                tuck=.22*math.exp(-(((y+2.82)/.85)**4+((z+.05)/.80)**4))
                x-=math.copysign(tuck,x)
            rear_angle=math.pi-a
            side_flow=max(0,min(1,(y+2.15)/1.15)); side_flow=side_flow*side_flow*(3-2*side_flow)
            side_fill=.46*math.exp(-((abs(rear_angle)-1.36)/.20)**2)*side_flow
            x+=math.sin(a)*side_fill; z-=.25*side_fill
            if abs(rear_angle)<=1.30:
                slot=(rear_angle+1.30)/2.60*(rear_count-1)
                flow=max(0,min(1,(y+7.35)/3.20)); flow=flow*flow*(3-2*flow)
                fade=min(1,max(0,(1.30-abs(rear_angle))/.15))
                relief=.34*(.5+.5*math.cos(slot*2*math.pi))*flow*fade
                x+=math.sin(a)*relief; z-=math.cos(a)*relief
            scalp.append((x,y,z))
    scalp_faces=[(j*n+k,(j+1)*n+k,(j+1)*n+(k+1)%n,j*n+(k+1)%n)
                  for j in range(len(rings)-1) for k in range(n)
                  if (n/8<=k<n*7/8) or rings[j+1][0]<=-5.84]
    scalp_faces.append(tuple(range(n)))
    base=mesh(part,"continuous_fitted_hair_base",scalp,scalp_faces,"hair_flow")
    uv_height=max(s["length"]+1.0,1.0)-rings[0][0]
    # Continuous scalp UVs avoid restarting the highlight on every height ring.
    for face in base["faces"][:-1]:
        first=face["vertices"][0]; j,k=divmod(first,n)
        t0=(rings[j][0]-rings[0][0])/uv_height
        t1=(rings[j+1][0]-rings[0][0])/uv_height
        face["uv"]=[[k/n,t0],[k/n,t1],[(k+1)/n,t1],[(k+1)/n,t0]]
    # Character-specific sweeping fringes, with rounded rather than dagger tips.
    for side in (-1,1):
        curve(part,f"fitted_temple_lock_{side}",[(side*2.70,-6.70,-2.92),(side*3.03,-5.35,-3.02),
              (side*3.30,-3.60,-2.90)],[.80,.88,.25],.48,"hair",sides=8)
    fringe_count=6 if identifier=="uika" else (5 if identifier in ("anon","taki","umiri") else (4 if identifier in ("sakiko","soyo","tomori") else 7))
    for i in range(fringe_count):
        x=-3.0+i*6/max(1,fringe_count-1)
        if identifier=="anon":
            root_x=(-1.95,-1.30,-.65,0,1.60)[i]
            mid_x=(-2.75,-1.70,-.75,.45,2.30)[i]
            end_x=(-3.15,-1.25,0,1.05,3.0)[i]
            end=(-4.15,-4.60,-4.65,-4.90,-4.30)[i]
            widths=[.90,1.32,.80,.16]
        elif identifier=="sakiko":
            root_x=(-1.6,-.65,.3,1.6)[i]; mid_x=(-2,-.95,.4,2.2)[i]; end_x=(-2.25,-.8,.05,2.45)[i]
            end=(-4.4,-4.45,-4.9,-4.45)[i]
            widths=[1.0,1.35,.38]
        elif identifier=="taki":
            root_x=x*.76; mid_x=x*.9; end_x=x+.16*math.sin(i)
            end=(-4.9,-4.5,-4.35,-4.8,-4.4)[i]
            widths=[1.20,1.45,.20]
        elif identifier=="tomori":
            root_x=(-2.0,-1.0,.3,1.6)[i]; mid_x=(-2.8,-1.35,-.1,2.25)[i]; end_x=(-3.1,-1.8,-.3,2.6)[i]
            end=(-4.0,-4.9,-4.95,-4.3)[i]
            widths=[1.0,1.35,.18]
        elif identifier=="soyo":
            root_x=(-.20,-.12,.12,.20)[i]
            mid_x=(-2.20,-1.30,1.30,2.20)[i]
            end_x=(-3.15,-2.10,2.10,3.15)[i]
            end=(-4.05,-5.65,-5.65,-4.05)[i]
            widths=[.92,1.62,.38]
        elif identifier=="uika":
            root_x=(-2.0,-1.30,-.60,.30,1.0,2.0)[i]
            mid_x=(-2.60,-1.35,-.25,.65,1.60,2.70)[i]
            end_x=(-2.95,-1.75,-.25,.98,2.20,3.15)[i]
            end=(-4.10,-4.75,-4.28,-4.90,-4.25,-3.95)[i]
            widths=[.65,.90,.10]
        elif identifier=="umiri":
            # Left side part: an open forehead with short sweeping inner locks.
            root_x=(-2.10,-1.45,-.65,.30,1.35)[i]
            mid_x=(-3.00,-.65,.35,1.45,2.55)[i]
            end_x=(-3.05,.15,1.15,2.05,3.12)[i]
            end=(-3.75,-5.95,-5.52,-5.10,-4.12)[i]
            widths=[1.35,1.70,.25]
        else:
            root_x=x*.82; mid_x=x; end_x=x+.12*math.sin(i)
            end=-4.8+.18*math.cos(i)
            widths=[.95,1.12,.42]
        fringe_points=[(root_x,-7.5,-2.7),(mid_x,-6.2,-3.25),(end_x,end,-3.32)]
        if identifier=="anon":
            fringe_points=[fringe_points[0],fringe_points[1],
                           (mid_x*.45+end_x*.55,-5.30,-3.40),fringe_points[-1]]
        curve(part,f"sculpted_fringe_{i}",fringe_points,
              widths,.52,"hair_light" if i==1 else "hair",sides=8)
    for i in range(12):
        a=i*2*math.pi/12
        root_x=-.60 if identifier=="anon" else -.25
        curve(part,f"crown_sweep_{i}",[(root_x,-8.05,-.2),
              (math.sin(a)*2.65,-7.65,-math.cos(a)*2.65),
              (math.sin(a)*3.48,-6.55,-math.cos(a)*3.03)],
              [.12,.46,.48],.10,"hair_light" if i in (2,8) else "hair",sides=8)
    length = s["length"]
    # Rear locks follow a curved scalp; every lock has a tapered end.
    rear_tracks=[]
    for i in range(rear_count):
        a = -1.3+i*2.6/(rear_count-1)
        x = math.sin(a)*3.65; z = math.cos(a)*3.0
        tip_y = length + .8*math.cos(i*1.3)
        if identifier=="tomori": tip_y=.15+.20*math.cos(a*2)+.14*math.cos(i*1.3)
        elif identifier=="rana": tip_y=-.05+.12*math.cos(a)+.16*math.cos(i)
        elif identifier=="uika": tip_y=.25+.15*math.cos(a)+.25*math.cos(i)
        elif identifier=="umiri": tip_y=.55+.20*math.cos(a)+.18*math.sin(i)
        bend = .65*math.sin(i*.9) if identifier in ("soyo","sakiko") else .12*math.sin(i)
        rear_points=[(x*.78,-7.0,max(z*.80+.24,1.5)),(x,-3.0,max(z+.60,1.9)),
              (x+bend,tip_y-1.5,max(z+.60,1.85)),(x+bend*.5,tip_y,max(z+.4,1.65))]
        rear_widths=[.82,1.75,1.35,.55 if identifier in ("rana","uika") else .27]
        if identifier=="umiri" and abs(x)>2.2:
            side=1 if x>0 else -1
            rear_points=[rear_points[0],rear_points[1],(x+side*.12,tip_y+.25,max(z+.55,1.85)),
                         (x+side*.90,tip_y-.22,max(z+.40,1.65))]
            rear_widths=[.82,1.75,1.05,.18]
        if identifier=="soyo":
            wave=.46*math.sin(a*1.2)+.20*math.cos(i)
            rear_points=[rear_points[0],rear_points[1],(x+wave,.2,max(z+.70,1.85)),
                (x-wave*.70,4.2,max(z+.55,1.85)),(x+wave*.70,tip_y-1.25,max(z+.65,1.85)),rear_points[-1]]
            rear_widths=[.82,1.75,1.70,1.65,1.10,.27]
        if identifier in ("anon","taki"):
            wave=(.36*math.sin(i*.83)+.10*math.cos(a*1.3)) if identifier=="anon" else .13*math.sin(i*.83)
            rear_points=[rear_points[0],rear_points[1],(x+wave,.8,max(z+.60,1.85)),
                (x-wave*.55,4.0,max(z+.70,1.85)),(x+wave*.75,8.0,max(z+.52,1.80)),
                (x+wave*.40,tip_y-1.10,max(z+.35,1.70)),(x-wave*.18,tip_y,max(z+.23,1.60))]
            rear_widths=[.82,1.72,1.65,1.56,1.45,.80,.16]
        rear_tracks.append(rear_points)
    join_rear_hair(base,rings,n,rear_tracks,uv_height)
    for side in (-1,1):
        side_count=2 if identifier=="sakiko" else 5
        for i in range(side_count):
            x=side*(3.30+i*.16); front=-1.9+i*.46
            if identifier=="uika" and i>=2: front=.95+(i-2)*.40
            long_y=length-1.4+i*.6
            if identifier == "anon": long_y=10.0+i*.75
            if identifier == "sakiko": long_y=2.1+i*.5
            if identifier == "rana": long_y=-.8+i*.35
            if identifier == "tomori": long_y=-.3+i*.22
            if identifier == "uika": long_y=-.2+i*.18
            if identifier == "umiri":
                tip_x=x+side*.65
            else: tip_x=x+side*.18*math.sin(i+1)
            if identifier=="soyo":
                points=[(x*.88,-6.5,front),(x,-3.2,front-.35),(x+side*.55,.25,front-.10),
                        (x-side*.36,3.8,front-.24),(x+side*.60,6.2,front+.05),(tip_x,long_y,front+.15)]
                widths=[.50,1.32,1.26,1.15,.80,.26]
            elif identifier=="anon":
                sway=.12*math.sin(i*.9)
                points=[(x*.88,-6.5,front),(x,-3.2,front-.35),
                        (x+side*sway,.8,front-.14),(x-side*.10,4.0,front-.25),
                        (x+side*.15,long_y-1.8,front+.10),(tip_x,long_y,front+.18)]
                widths=[.50,1.26,1.12,1.06,.75,.16]
            elif identifier=="umiri":
                points=[(x*.88,-6.5,front),(x,-3.2,front-.35),(x+side*.20,-1.0,front-.20),
                        (x+side*.35,long_y+.12,front),(x+side*(.90+i*.10),long_y-.25,front+.18)]
                widths=[.50,1.26,1.10,.70,.15]
            else:
                points=[(x*.88,-6.5,front),(x,-3.2,front-.35),
                        (x+side*.3,long_y-1.5,front-.2),(tip_x,long_y,front+.15)]
                widths=[.50,1.26,1.04,.48 if identifier=="rana" else .26]
            curve(part,f"side_lock_{side}_{i}",points,widths,.68,"hair_inner" if identifier=="uika" and i==1 else ("hair_light" if i==3 else "hair"),sides=8)
    if identifier=="sakiko":
        for side in (-1,1):
            route=np.asarray(facial_bezier([(side*2.70,-6.15,-3.0),(side*4.20,-6.0,-2.45),
                             (side*4.25,-4.20,-.60),(side*3.83,-3.55,.85)],steps=18))
            for strand in range(3):
                points=[]; widths=[]
                for j,center in enumerate(route):
                    t=j/(len(route)-1); tangent=route[min(j+1,len(route)-1)]-route[max(0,j-1)]
                    tangent/=np.linalg.norm(tangent)
                    outward=np.array([side,0.,0.]); outward-=tangent*np.dot(outward,tangent); outward/=np.linalg.norm(outward)
                    transverse=np.cross(tangent,outward); phase=t*math.pi*2*4.5+strand*math.pi*2/3
                    points.append(center+.22*(math.cos(phase)*outward+math.sin(phase)*transverse))
                    widths.append(.26 if .08<t<.92 else .20)
                curve(part,f"woven_braid_{side}_{strand}",points,widths,.26,
                      "hair_light" if strand==1 else "hair",sides=6)
            for j in range(3):
                shift=j*.27; x=side*(3.65+shift)
                curve(part,f"curled_halfup_tail_{side}_{j}",[(x,-3.6,.80+shift),
                      (x+side*.15,-.4,1.10+shift),(x+side*.72,2.1,1.55+shift),
                      (x-side*.18,4.6,1.55+shift),(x+side*.56,6.1+j*.35,1.20+shift),
                      (x+side*.10,7.25+j*.20,.75+shift)],
                      [1.1,1.30,1.24,1.08,.70,.18],.72,"hair_light" if j==1 else "hair",sides=8)
            box(part,f"halfup_ribbon_knot_{side}",(side*3.8,-3.55,.85),(.25,.42,.38),"black")
            for wing in (-1,1):
                curve(part,f"halfup_ribbon_{side}_{wing}",[(side*3.85,-3.55,.80),(side*(3.85+wing*.45),-3.20,.80),
                      (side*3.85,-2.97,.80)],[.18,.36,.12],.16,"black",sides=6)
    if identifier == "anon":
        for j in range(2):
            curve(part,f"paired_front_hair_slide_{j}",[(-3.26,-5.12+j*.34,-2.48),
                  (-3.47,-4.82+j*.34,-2.55),(-3.62,-4.54+j*.34,-2.48)],
                  [.10,.13,.10],.08,"white",sides=6)
    if identifier in ("taki","rana"):
        loft(part,"thin_neck_choker",[(-.48,.835,.76,0,0),(-.24,.835,.76,0,0)],"black",sides=12)
        if identifier=="taki":
            loft(part,"choker_gold_edge",[(-.49,.85,.775,0,0),(-.45,.85,.775,0,0)],"gold",sides=12)
            box(part,"choker_front_clasp",(0,-.31,-.785),(.20,.16,.06),"gold")
    # Reference-based head proportion; Q drawings guide hair, not oversized eyes.
    fixed_neck=("neck_above_collar","thin_neck_choker","choker_gold_edge","choker_front_clasp")
    for m in part["meshes"]:
        if m["name"].startswith(fixed_neck): continue
        for p in m["vertices"]:
            p[0]=round(p[0]*.84,5); p[2]=round(p[2]*.90,5)
            if p[1]<-.30: p[1]=round(-.30+(p[1]+.30)*.91,5)
    shell=next(m for m in part["meshes"] if m["name"]=="soft_cheeks_and_short_jaw")
    face=next(m for m in part["meshes"] if m["name"]=="soft_cheeks_and_short_jaw_face")
    shell_normals=vertex_normals(shell["vertices"],shell["faces"]+face["faces"])
    for m in part["meshes"]:
        if m["name"].startswith(fixed_neck): continue
        if m["material"] in ("skin","face") or m["material"].startswith("hair"):
            m["shading"]="smooth"
            m["normals"]=shell_normals if m is shell or m is face else vertex_normals(m["vertices"],m["faces"])


def skin_limbs(parts, identifier):
    for side,name in ((-1,"right"),(1,"left")):
        arm=parts[name+"_arm"]
        loft(arm,"upper_arm",[(-1.4,.59,.68,0,0),(-.4,.78,.80,0,0),(2.8,.72,.76,0,0)],"skin",sides=10)
        fore=node((0,2.8,0)); arm["children"]["forearm"]=fore
        loft(fore,"forearm",[(0,.74,.78,0,0),(4.2,.70,.72,0,0)],"skin",sides=8)
        loft(fore,"hand",[(4.05,.72,.78,0,-.08),(5.75,.65,.74,0,-.12)],"skin",sides=8)
        box(fore,"wrist_seam",(0,4.0,0),(1.45,.08,1.5),"silver")
        for finger in range(3):
            box(fore,f"finger_seam_{finger}",(-.36+finger*.36,5.2,-.865),(.035,.48,.035),"skin_shadow")
        leg=parts[name+"_leg"]
        loft(leg,"thigh",[(0,1.10,1.05,0,0),(2.2,1.05,1.02,0,0),(5.4,.85,.90,0,-.08)],"skin",sides=8)
        shin=node((0,5.4,-.08)); leg["children"]["shin"]=shin
        loft(shin,"calf",[(0,.85,.90,0,0),(2.2,.90,.96,0,.05),(5.45,.63,.68,0,.02)],"skin",sides=8)
        if identifier == "sakiko":
            loft(shin,"white_knee_sock",[(.15,.90,.95,0,0),(2.2,.94,1.0,0,.05),(5.55,.70,.76,0,.02)],"white",sides=8)
        if identifier == "rana" and name == "left":
            loft(leg,"single_fishnet_stocking",[(.25,1.13,1.08,0,0),(2.2,1.08,1.05,0,0),(5.45,.9,.95,0,-.08)],"fishnet",sides=8)
            loft(shin,"stocking_calf",[(0,.90,.95,0,0),(2.25,.94,1.0,0,.05),(3.0,.79,.85,0,0)],"fishnet",sides=8)
        shoe="cream_shoe" if identifier=="tomori" else "leather"
        boot_start=2.55 if identifier=="rana" else 4.05
        loft(shin,"boot_shaft",[(boot_start,.76,.83,0,0),(5.45,.80,.94,0,-.10)],shoe,sides=8)
        loft(shin,"shaped_toe",[(5.0,.73,1.25,0,-.38),(5.75,.84,1.55,0,-.60),(6.2,.86,1.60,0,-.61)],shoe,sides=8)
        loft(shin,"sole",[(6.15,.90,1.64,0,-.61),(6.42,.89,1.62,0,-.61)],"white_shadow" if identifier=="tomori" else "sole",sides=8)
        box(shin,"heel",(0,6.17,.51),(1.3,.42,.88),"sole")
        for i in range(4):
            box(shin,f"shoe_lace_{i}",(0,5.12+i*.16,-1.47-i*.08),(.65,.055,.055),"white_shadow" if identifier=="tomori" else "silver")


def torso(part, identifier):
    loft(part,"underlying_torso",[(.8,2.7,1.45,0,0),(3.5,2.65,1.48,0,0),
        (6.6,2.15,1.3,0,0),(8.8,2.7,1.45,0,0),(11.2,2.45,1.4,0,0),
        (12.15,2.30,1.32,0,0)],"skin",sides=12)
    loft(part,"neck",[(-.05,.75,.70,0,0),(1.15,.88,.78,0,0)],"skin",sides=8)


def sleeve(arm, name, material, long=False, puff=False):
    end=5.7 if long else 1.8
    rings=[(-1.25,.65,.73,0,0),(-.4,1.08 if puff else .94,1.02 if puff else .90,0,0),
           (.6,1.05 if puff else .92,1.0 if puff else .88,0,0),
           (end-.35,.94 if puff else .85,.93 if puff else .87,0,0),(end,.83,.85,0,0)]
    loft(arm,name,rings,material,sides=12)
    loft(arm,name+"_cuff",[(end-.10,.88,.90,0,0),(end+.15,.85,.87,0,0)],"cuff",sides=12)


def collar(part, material="white"):
    panel(part,"collar_left",[(-.75,.35,-1.52),(-2.05,.75,-1.55),(-1.25,2.2,-1.70),(-.1,1.4,-1.7)],material)
    panel(part,"collar_right",[(.75,.35,-1.52),(2.05,.75,-1.55),(1.25,2.2,-1.70),(.1,1.4,-1.7)],material)


def skirt(part, name, waist, front_hem, back_hem, material, radius=3.5, count=16):
    """Individually pleated, flared cloth panels with a real high-low hem."""
    # Continuous inner cloth fills fold seams without changing the approved outer silhouette.
    lining=[]
    for layer in range(3):
        for i in range(count):
            a=i*2*math.pi/count; rear=(1-math.cos(a))/2
            hem=front_hem+(back_hem-front_hem)*rear
            y=(waist,waist+1.1,hem)[layer]; r=(2.54,2.88,radius-.05)[layer]
            lining.append((math.sin(a)*r,y,-math.cos(a)*r*.68))
    lining_faces=[(j*count+i,(j+1)*count+i,(j+1)*count+(i+1)%count,j*count+(i+1)%count)
                  for j in range(2) for i in range(count)]
    inner=mesh(part,name+"_continuous_lining",lining,lining_faces,material)
    for j,f in enumerate(inner["faces"]):
        f["uv"]=[[(index%count)/count,(lining[index][1]-waist)/max(back_hem-waist,front_hem-waist,.1)] for index in f["vertices"]]
    for i in range(count):
        a0=(i-.48)*2*math.pi/count; a1=(i+.48)*2*math.pi/count
        a=i*2*math.pi/count
        rear=(1-math.cos(a))/2
        hem=front_hem+(back_hem-front_hem)*rear+.14*math.cos(i*1.7)
        r0=2.55; r1=radius+.12*(i%2)
        # three width levels describe the flare, while raised fold crests catch light.
        levels=[(waist,r0),(waist+1.1,r0+.35),(hem,r1)]
        vertices=[]
        for y,r in levels:
            vertices += [(math.sin(a0)*r,y,-math.cos(a0)*r*.68),
                         (math.sin(a)*r*1.035,y,-math.cos(a)*r*.68*1.035),
                         (math.sin(a1)*r,y,-math.cos(a1)*r*.68)]
        pleat=mesh(part,name+f"_pleat_{i}",vertices,[(0,3,4,1),(1,4,5,2),(3,6,7,4),(4,7,8,5)],material)
        for j,f in enumerate(pleat["faces"]):
            f["uv"]=[[(i+(index%3)/2)/count,(vertices[index][1]-waist)/max(hem-waist,.1)] for index in f["vertices"]]


def waistband(part, y, material="gold"):
    loft(part,"waist_band",[(y,2.62,1.74,0,0),(y+.40,2.62,1.74,0,0)],material,sides=12)
    box(part,"belt_buckle",(0,y+.2,-1.80),(.52,.35,.14),"silver")


def hem_border(part,name,front,back,radius,material,count=24):
    vertices=[]
    for i in range(count):
        a=i*2*math.pi/count; y=front+(back-front)*(1-math.cos(a))/2
        x=math.sin(a)*radius; z=-math.cos(a)*radius*.68
        vertices.extend([(x,y-.14,z),(x,y+.02,z)])
    faces=[(i*2,i*2+1,((i+1)%count)*2+1,((i+1)%count)*2) for i in range(count)]
    mesh(part,name,vertices,faces,material)


def closed_shorts(part,material):
    loft(part,"closed_shorts_hip",[(7.5,2.88,1.64,0,0),(9.5,2.88,1.64,0,0)],material,sides=12)
    panel(part,"shorts_front_crotch_seam",[(-.70,9.3,-1.65),(.70,9.3,-1.65),
          (.45,12.1,-1.47),(-.45,12.1,-1.47)],material)
    panel(part,"shorts_back_crotch_seam",[(.70,9.3,1.65),(-.70,9.3,1.65),
          (-.45,12.1,1.47),(.45,12.1,1.47)],material)


def buttons(part, positions, material="gold"):
    for i,(x,y,z) in enumerate(positions):
        box(part,f"button_{i}",(x,y,z),(.16,.16,.10),material)


def clothing(parts, identifier):
    b=parts["body"]
    if identifier=="anon":
        loft(b,"cropped_denim_top",[(.6,2.80,1.55,0,0),(2.5,2.86,1.7,0,0),(5.25,2.34,1.48,0,0)],"blue",sides=12)
        collar(b,"blue")
        for side,name in ((-1,"right"),(1,"left")): sleeve(parts[name+"_arm"],"short_denim_sleeve","blue")
        waistband(b,7.0,"white")
        skirt(b,"white_high_low_skirt",7.4,10.7,20.0,"white",radius=3.7)
        skirt(b,"short_ruffle_overlay",7.6,10.6,12.3,"white_shadow",radius=3.8)
        buttons(b,[(x,y,-1.75) for x in (-1.45,1.45) for y in (2.6,4.25)],"silver")
        curve(b,"yellow_necktie",[(.25,1.35,-1.92),(.45,2.4,-1.90),(.18,3.2,-1.83)],[.48,.35,.06],.12,"gold",sides=4)
        for s in (-1,1): curve(b,f"denim_seam_{s}",[(s*2.2,1.2,-1.05),(s*2.0,3.5,-1.13),(s*1.9,5.1,-1.0)],[.06]*3,.06,"blue_shadow",4)
    elif identifier=="sakiko":
        loft(b,"school_cardigan",[(.65,2.78,1.52,0,0),(3.0,2.80,1.55,0,0),(6.75,2.45,1.46,0,0)],"blue",sides=12)
        panel(b,"white_blouse_insert",[(-.85,.65,-1.6),(.85,.65,-1.6),(.62,6.7,-1.6),(-.62,6.7,-1.6)],"white")
        collar(b,"white")
        for name in ("right","left"): sleeve(parts[name+"_arm"],"school_cardigan_sleeve","blue",long=True)
        curve(b,"small_school_tie",[(0,1.3,-1.87),(0,2.9,-1.79),(.1,3.4,-1.75)],[.44,.30,.02],.12,"navy",4)
        buttons(b,[(.96,y,-1.6) for y in (3.0,4.5,5.9)],"white_shadow")
        skirt(b,"green_plaid_pleated_skirt",7.0,11.0,11.0,"plaid",radius=3.45)
        waistband(b,6.8,"green_shadow")
    elif identifier=="tomori":
        loft(b,"white_inner_shirt",[(.6,2.6,1.5,0,0),(6.8,2.25,1.44,0,0)],"white",sides=12)
        for s in (-1,1):
            panel(b,f"blue_open_jacket_front_{s}",[(s*.85,.6,-1.65),(s*2.8,.75,-1.35),(s*2.5,6.9,-1.30),(s*.8,6.9,-1.68)],"blue")
        loft(b,"blue_jacket_back",[(.75,2.8,1.65,0,.32),(6.8,2.45,1.5,0,.32)],"blue",sides=12)
        panel(b,"visible_inner_shirt",[(-.75,.72,-1.82),(.75,.72,-1.82),(.70,6.8,-1.78),(-.70,6.8,-1.78)],"white")
        for name in ("right","left"): sleeve(parts[name+"_arm"],"white_baseball_sleeve","white",long=True,puff=True)
        waistband(b,7.2)
        closed_shorts(b,"green")
        for s in (-1,1): loft(b,f"high_waist_short_leg_{s}",[(7.6,1.38,1.55,s*1.4,0),(11.0,1.45,1.50,s*1.4,0)],"green",sides=8)
        buttons(b,[(1.0,y,-1.74) for y in (1.8,3.25,4.7,6.1)],"silver")
    elif identifier=="soyo":
        loft(b,"blue_ruffled_dress_bodice",[(.8,2.8,1.6,0,0),(3.1,2.88,1.7,0,0),(6.4,2.3,1.5,0,0)],"blue",sides=12)
        collar(b,"blue")
        for name in ("right","left"):
            sleeve(parts[name+"_arm"],"flutter_sleeve","blue",puff=True)
            loft(parts[name+"_arm"],"fine_gold_sleeve_edge",[(1.9,1.13,1.12,0,0),(2.08,1.10,1.10,0,0)],"gold",sides=12)
        waistband(b,6.55)
        skirt(b,"white_long_underskirt",7.0,11.1,20.0,"white",radius=3.8)
        skirt(b,"blue_asymmetric_overskirt",6.95,9.8,12.1,"blue",radius=3.75,count=12)
        hem_border(b,"gold_hem_edge",9.8,12.1,3.88,"gold")
    elif identifier=="taki":
        loft(b,"white_blouse",[(.75,2.75,1.6,0,0),(6.9,2.3,1.45,0,0)],"white",sides=12)
        for name in ("right","left"): sleeve(parts[name+"_arm"],"white_puffed_sleeve","white",long=True,puff=True)
        loft(b,"blue_fitted_bodice",[(2.7,2.88,1.74,0,0),(4.0,2.64,1.65,0,0),(6.9,2.32,1.5,0,0)],"blue",sides=12)
        skirt(b,"blue_flared_skirt",7.4,11.6,11.8,"blue",radius=3.65)
        skirt(b,"white_ruffled_hem",11.35,12.5,12.7,"white",radius=3.82,count=20)
        waistband(b,7.0,"black")
        curve(b,"fine_neck_ribbon",[(-.7,.9,-.82),(0,1.1,-.94),(.7,.9,-.82)],[.09]*3,.09,"gold",4)
    elif identifier=="rana":
        loft(b,"loose_white_tshirt",[(.75,2.78,1.58,0,0),(6.7,2.35,1.5,0,0)],"white",sides=12)
        sleeve(parts["left_arm"],"white_short_sleeve","white")
        sleeve(parts["right_arm"],"blue_coat_sleeve","blue",long=True)
        panel(b,"asymmetric_coat_front",[(-3.1,.65,-1.90),(-1.35,.7,-1.93),(-1.8,16.5,-2.15),(-4.1,15.3,-1.98)],"blue")
        panel(b,"asymmetric_coat_back",[(-3.1,.65,1.90),(1.1,.7,1.90),(.7,16.0,2.1),(-4.1,15.3,1.98)],"blue")
        curve(b,"white_lapel_edge",[(-1.35,.8,-1.99),(-1.62,8.2,-2.08),(-1.8,16.5,-2.22)],[.10]*3,.10,"white",4)
        closed_shorts(b,"black")
        for s in (-1,1): loft(b,f"black_shorts_{s}",[(7.2,1.42,1.56,s*1.4,0),(10.7,1.42,1.56,s*1.4,0)],"black",sides=8)
        waistband(b,6.95,"black")
    elif identifier=="uika":
        loft(b,"black_dress_bodice",[(.8,2.75,1.55,0,0),(3.5,2.78,1.63,0,0),(6.8,2.3,1.5,0,0)],"black",sides=12)
        skirt(b,"black_gold_dress",7.0,18.0,18.2,"black",radius=3.4)
        waistband(b,6.8)
        collar(b,"white")
        for name in ("right","left"): sleeve(parts[name+"_arm"],"black_short_sleeve","black")
        for s in (-1,1): curve(b,f"long_gold_edge_{s}",[(s*2.5,7.0,-1.7),(s*2.9,12.0,-1.9),(s*3.1,17.8,-2.0)],[.08]*3,.08,"gold",4)
    elif identifier=="umiri":
        loft(b,"black_dress_bodice",[(.8,2.7,1.55,0,0),(3.5,2.78,1.63,0,0),(6.8,2.3,1.5,0,0)],"black",sides=12)
        skirt(b,"black_dress",7.0,15.0,15.0,"black",radius=3.5)
        for s in (-1,1):
            panel(b,f"burgundy_shawl_{s}",[(0,.7,-1.65),(s*3.7,.85,-1.5),(s*3.3,4.2,-1.80),(s*.8,2.5,-1.94)],"burgundy")
        for name in ("right","left"): sleeve(parts[name+"_arm"],"burgundy_cap_sleeve","burgundy")


def make_model(identifier):
    parts={"head":node(),"body":node(),"right_arm":node((-3.6,2.0,0)),"left_arm":node((3.6,2.0,0)),
           "right_leg":node((-1.25,12.0,0)),"left_leg":node((1.25,12.0,0))}
    head(parts["head"],identifier); torso(parts["body"],identifier)
    skin_limbs(parts,identifier); clothing(parts,identifier)
    return {"format":"ic2newera_rigged_mesh_v2","revision":MODEL_REVISION,"units_per_block":16,"coordinates":"x-right_y-down_z-back",
            "name":STYLE[identifier]["name"],"texture_size":[RESOLUTION,RESOLUTION],"parts":parts,
            "status":"主线亲自重建的外观研究稿；尚未接入实体渲染或游戏"}


class FaceDraw:
    """Draw original facial strokes in logical atlas coordinates at 4x scale."""
    def __init__(self, image, scale=4):
        self.draw=ImageDraw.Draw(image); self.scale=scale

    def coordinates(self, points):
        if isinstance(points[0],(int,float)):
            return tuple(float(v)*self.scale for v in points)
        return [(float(x)*self.scale,float(y)*self.scale) for x,y in points]

    def line(self, points, fill, width=1):
        self.draw.line(self.coordinates(points),fill=fill,width=max(1,round(width*self.scale)),joint="curve")

    def polygon(self, points, fill):
        self.draw.polygon(self.coordinates(points),fill=fill)

    def ellipse(self, bounds, fill):
        self.draw.ellipse(self.coordinates(bounds),fill=fill)

    def point(self, point, fill):
        x,y=point; self.ellipse((x-.4,y-.4,x+.4,y+.4),fill)


def facial_bezier(control, steps=16):
    p=np.asarray(control,dtype=float)
    return [tuple((1-t)**3*p[0]+3*(1-t)**2*t*p[1]+3*(1-t)*t*t*p[2]+t**3*p[3])
            for t in np.linspace(0,1,steps+1)]


def paint_face(identifier):
    """Pixel-drawn character eyes; never generated from a third-party face crop."""
    img=Image.new("RGBA",(TILE,TILE),rgba("#F3DCCD"))
    for yy in range(TILE):
        for xx in range(TILE):
            edge=abs((xx-63.5)/64)**4
            factor=1.012-.050*edge-.016*(yy/127)**3
            img.putpixel((xx,yy),tint("#F3DCCD",factor))
    scale=4; img=img.resize((TILE*scale,TILE*scale),Image.Resampling.BICUBIC)
    d=FaceDraw(img,scale)
    s=STYLE[identifier]
    expressions={"anon":(55,23,17),"sakiko":(56,22,16),"tomori":(56,22,17),"soyo":(56,23,16),
                 "taki":(57,23,16),"rana":(55,22,17),"uika":(55,23,18),"umiri":(57,22,16)}
    shapes={
        "anon":[(0,.34),(.20,.10),(.48,0),(.84,.10),(1,.34),(.94,.78),(.70,1),(.22,.97),(.04,.70)],
        "sakiko":[(0,.22),(.22,.06),(.68,.03),(1,.26),(.92,.82),(.60,.99),(.18,.92),(.01,.63)],
        "tomori":[(0,.32),(.20,.10),(.48,.02),(.83,.12),(1,.36),(.94,.82),(.66,1),(.20,.95),(.02,.66)],
        "soyo":[(0,.32),(.20,.12),(.57,.05),(1,.38),(.96,.78),(.75,.96),(.28,.94),(.02,.70)],
        "taki":[(0,.16),(.35,.08),(.85,.16),(1,.28),(.92,.76),(.65,.95),(.20,.91),(.05,.64)],
        "rana":[(0,.24),(.30,.07),(.66,.07),(1,.26),(.95,.77),(.67,.99),(.22,.99),(.02,.67)],
        "uika":[(0,.34),(.22,.10),(.50,0),(.85,.12),(1,.36),(.95,.80),(.65,1),(.19,.95),(.01,.65)],
        "umiri":[(0,.18),(.28,.09),(.76,.14),(1,.26),(.94,.75),(.66,.96),(.21,.92),(.03,.64)],
    }
    y,w,h=expressions[identifier]
    for eye_index,center_x in enumerate((43.5,84.5)):
        x=center_x-w/2
        mirrored=lambda u:1-u if eye_index else u
        shape=shapes[identifier]
        outline=[(x+mirrored(u)*w,y+v*h) for u,v in shape]
        eye=Image.new("RGBA",img.size,(0,0,0,0)); ed=FaceDraw(eye,scale)
        mask=Image.new("L",img.size,0); FaceDraw(mask,scale).polygon(outline,fill=255)
        ed.polygon(outline,fill=rgba("#FEFCF6"))
        iris = "#BDA56D" if identifier=="rana" and eye_index==0 else s["eye"]
        iris_fraction={"anon":.55,"sakiko":.58,"tomori":.55,"soyo":.56,
                       "taki":.53,"rana":.55,"uika":.57,"umiri":.53}[identifier]
        iris_width=w*iris_fraction; cx=center_x; cy=y+h/2
        # Oval iris with a shaded upper edge, rather than a rectangular blue block.
        for sample in range(h*scale):
            yy=y+(sample+.5)/scale
            normalized=(yy-cy)/(h/2)
            half=iris_width/2*math.sqrt(max(0,1-normalized*normalized))
            factor=.62+.46*(yy-y)/max(h,1)
            ed.line((cx-half,yy,cx+half,yy),fill=tint(iris,factor),width=1/scale)
        pupil_width=iris_width*.35; pupil_top=y+h*.29; pupil_bottom=y+h*.77
        ed.ellipse((cx-pupil_width/2,pupil_top,cx+pupil_width/2,pupil_bottom),fill=tint(iris,.38))
        ed.ellipse((cx-iris_width*.31,y+2.1,cx-iris_width*.12,y+4.3),fill=rgba("#FFFFFF"))
        ed.ellipse((cx+iris_width*.20,y+h-3.4,cx+iris_width*.30,y+h-2.4),fill=tint(iris,1.17))
        img.paste(eye,(0,0),mask)
        eyeliner="#45414C" if identifier not in ("taki","umiri") else "#302E3C"
        upper_count=5 if identifier in ("anon","tomori","uika") else 4
        upper=[(x+mirrored(u)*w,y+v*h) for u,v in shape[:upper_count]]
        d.line(upper,fill=rgba(eyeliner),width=2)
        outer=x if eye_index==0 else x+w; direction=-1 if eye_index==0 else 1
        d.line([(outer,y+5),(outer+direction*2,y+2)],fill=rgba(eyeliner),width=1)
        d.line([(x+3,y+h-1),(x+w-5,y+h)],fill=rgba("#C2A09C"),width=1)
        brow_y=y-7+(1 if identifier in ("sakiko","umiri") else 0)
        brow=[(x+3,brow_y),(x+11,brow_y-2),(x+w-4,brow_y)]
        if identifier=="taki":
            brow=[(x+3,brow_y-1),(x+w-4,brow_y+2)] if eye_index==0 else [(x+3,brow_y+2),(x+w-4,brow_y-1)]
        elif identifier=="sakiko":
            brow=facial_bezier([(x+mirrored(u)*w,brow_y+v) for u,v in ((.13,-.4),(.35,-1.9),(.70,-1.3),(.87,-.2))])
        elif identifier in ("tomori","soyo"):
            brow=[(x+3,brow_y+1),(x+w-5,brow_y-2)] if eye_index==0 else [(x+3,brow_y-2),(x+w-5,brow_y+1)]
        elif identifier=="umiri": brow=[(x+3,brow_y),(x+w-4,brow_y)]
        d.line(brow,fill=rgba(s["dark"]),width=1.3 if identifier=="sakiko" else 1)
    # Subtle nose and mouth marks follow the original portrait proportions.
    d.line([(64,82),(65,83)],fill=rgba("#DDBEB2"),width=1)
    d.point((64,81),fill=rgba("#F8E7DC"))
    if identifier=="anon":
        upper=facial_bezier([(58.5,93),(62,94.5),(68,94),(71,92.5)])
        lower=facial_bezier([(71,92.5),(70,100),(61,100),(58.5,93)])
        d.polygon(upper+lower,fill=rgba("#A66C78"))
        d.ellipse((61,95,68.5,98.7),fill=rgba("#D89A9E"))
        d.line(facial_bezier([(60,93.4),(63,94.3),(67,94),(69.7,93.2)]),fill=rgba("#FFF4E8"),width=.7)
    elif identifier=="taki":
        d.line(facial_bezier([(59,94),(62,94.1),(66,94),(69,94.3)]),fill=rgba("#B5777B"),width=1)
    elif identifier=="umiri":
        d.line(facial_bezier([(59,94),(62,94.4),(66,94.4),(69,94)]),fill=rgba("#B5777B"),width=1)
    elif identifier=="rana":
        d.line(facial_bezier([(61,95),(63,94.1),(65,94.1),(67,95)]),fill=rgba("#B6767A"),width=1)
    elif identifier=="tomori":
        d.line(facial_bezier([(61,95),(62,94.7),(64,94.8),(66,95.1)]),fill=rgba("#BA8587"),width=1)
    elif identifier=="sakiko":
        d.line(facial_bezier([(60,94),(62,94.8),(66,94.8),(69,93.8)]),fill=rgba("#B6767A"),width=1)
    elif identifier=="uika":
        d.line(facial_bezier([(57,94),(61,96.5),(67,97),(72,93)]),fill=rgba("#B6767A"),width=1)
    else:
        d.line(facial_bezier([(58,94),(62,96.2),(67,96.1),(71,94)]),fill=rgba("#B6767A"),width=1)
    blush=Image.new("RGBA",img.size,(0,0,0,0)); bd=FaceDraw(blush,scale)
    for x in (27,100):
        for radius in (4.5,3.8,3.0,2.2):
            bd.ellipse((x-radius,83-radius*.40,x+radius,83+radius*.40),fill=(228,154,151,20))
    img=Image.alpha_composite(img,blush)
    return img.resize((TILE,TILE),Image.Resampling.BOX)


def texture_atlas(identifier):
    palette=materials(identifier); atlas=Image.new("RGBA",(RESOLUTION,RESOLUTION),(0,0,0,0)); slots={}
    for i,(name,color) in enumerate(palette.items()):
        u=(i%8)*TILE; v=(i//8)*TILE; slots[name]=(u,v)
        tile=paint_face(identifier) if name=="face" else Image.new("RGBA",(TILE,TILE),rgba(color))
        if name!="face":
            d=ImageDraw.Draw(tile)
            # Restrained value steps give fabric and hair readable volume.
            if name=="hair_flow":
                rear_count=11 if identifier=="sakiko" else 17
                root_y=-5.85-2.30*math.cos(.10)
                uv_height=max(STYLE[identifier]["length"]+1.0,1.0)-root_y
                for yy in range(TILE):
                    height=root_y+yy/(TILE-1)*uv_height
                    strength=max(0,min(1,(height+7.35)/3.20))
                    strength=strength*strength*(3-2*strength)
                    for xx in range(TILE):
                        slot=(math.pi-xx/(TILE-1)*2*math.pi+1.30)/2.60*(rear_count-1)
                        phase=slot*2*math.pi
                        factor=.97+strength*(.045*math.cos(phase)+.012*math.cos(phase*2.2+yy*.013))
                        d.point((xx,yy),fill=tint(color,factor))
            elif name.startswith("hair"):
                for xx in range(TILE):
                    factor=.94+.105*math.exp(-((xx-45)/25)**2)
                    d.line((xx,0,xx,127),fill=tint(color,factor),width=1)
                for xx in (16,91):
                    d.line([(xx,0),(xx+4,58),(xx+7,127)],fill=tint(color,.94),width=1)
            elif name=="plaid":
                for x in range(0,128,24):
                    d.rectangle((x,0,x+7,127),fill=rgba("#A1AB8A"))
                    d.line((x+10,0,x+10,127),fill=rgba("#465B50"),width=2)
                for y in range(0,128,24):
                    d.rectangle((0,y,127,y+5),fill=rgba("#879578"))
                    d.line((0,y+9,127,y+9),fill=rgba("#BCC4AA"),width=1)
            elif name=="fishnet":
                for k in range(-128,256,16):
                    d.line((k,0,k+128,128),fill=rgba("#726773"),width=1)
                    d.line((k,0,k-128,128),fill=rgba("#726773"),width=1)
            elif name in ("blue","denim","white","white_shadow","green","black","burgundy","navy"):
                d.rectangle((0,0,9,127),fill=tint(color,.91))
                d.rectangle((10,0,23,127),fill=tint(color,.96))
                d.rectangle((104,0,127,127),fill=tint(color,.94))
                d.line([(58,0),(52,42),(62,89),(57,127)],fill=tint(color,1.05),width=2)
                d.line([(83,0),(88,54),(80,127)],fill=tint(color,.93),width=1)
            elif name=="leather":
                d.rectangle((8,0,17,127),fill=tint(color,1.30))
                d.line((23,0,23,127),fill=tint(color,1.16),width=1)
            elif name=="skin":
                pass  # Geometry lighting supplies shading, without repeating painted joint bands.
        atlas.paste(tile,(u,v))
    return atlas,slots


def assign_atlas(model,slots):
    def visit(part):
        for m in part["meshes"]:
            u,v=slots[m["material"]]
            for face in m["faces"]:
                face["uv"]=[[(u+1+max(0,min(1,s))*(TILE-2))/RESOLUTION,
                              (v+1+max(0,min(1,t))*(TILE-2))/RESOLUTION] for s,t in face["uv"]]
        for child in part["children"].values(): visit(child)
    for part in model["parts"].values(): visit(part)


def rotation_matrix(rotation):
    rx,ry,rz=rotation; cx,sx=math.cos(rx),math.sin(rx); cy,sy=math.cos(ry),math.sin(ry); cz,sz=math.cos(rz),math.sin(rz)
    return np.array([[cz,-sz,0],[sz,cz,0],[0,0,1]]) @ np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]]) @ np.array([[1,0,0],[0,cx,-sx],[0,sx,cx]])


def geometry(model):
    result=[]
    def visit(part,mat,translation,path):
        shift=translation+mat@np.array(part["pivot"]); current=mat@rotation_matrix(part["rotation"])
        for m in part["meshes"]:
            points=np.array(m["vertices"])@current.T+shift
            result.append((path,m,points))
        for name,child in part["children"].items(): visit(child,current,shift,path+"/"+name)
    for name,part in model["parts"].items(): visit(part,np.eye(3),np.zeros(3),name)
    return result


def render(model,texture,direction,width=380,height=720,frame=None):
    toward=np.array(direction,dtype=float); toward/=np.linalg.norm(toward)
    right=np.cross([0,-1,0],toward); right/=np.linalg.norm(right); up=np.cross(toward,right)
    objects=geometry(model); allpoints=np.concatenate([p for _,_,p in objects])
    xx=allpoints@right; yy=allpoints@up
    xmin,xmax,ymin,ymax=(xx.min(),xx.max(),yy.min(),yy.max()) if frame is None else frame
    scale=min((width-34)/(xmax-xmin),(height-38)/(ymax-ymin))
    offset=np.array([width/2-(xmax+xmin)/2*scale,height/2+(ymax+ymin)/2*scale])
    canvas=np.zeros((height,width,4),dtype=np.uint8); zbuf=np.full((height,width),-np.inf); tex=np.array(texture)
    light=np.array([-.3,-.6,-1.0]); light/=np.linalg.norm(light)
    fill_light=np.array([.50,-.30,1.0]); fill_light/=np.linalg.norm(fill_light)
    for path,m,points in objects:
        smooth=m.get("shading")=="smooth" and "normals" in m
        normals=None
        if smooth:
            names=path.split("/"); part=model["parts"][names[0]]; transform=rotation_matrix(part["rotation"])
            for name in names[1:]:
                part=part["children"][name]; transform=transform@rotation_matrix(part["rotation"])
            normals=np.asarray(m["normals"])@transform.T
        projected=np.column_stack((points@right*scale+offset[0],-points@up*scale+offset[1],points@toward))
        for face in m["faces"]:
            indices=face["vertices"]; uv=np.array(face["uv"])*RESOLUTION
            for j in range(1,len(indices)-1):
                tri=(0,j,j+1); p=projected[[indices[k] for k in tri]]; tuv=uv[list(tri)]
                p0,p1,p2=points[[indices[k] for k in tri]]
                normal=np.cross(p1-p0,p2-p0); length=np.linalg.norm(normal)
                if length<1e-8: continue
                normal/=length
                # A warped quad can contain both a back-facing and front-facing
                # triangle. Culling the entire polygon by its first triangle
                # incorrectly removes real coverage from the second triangle.
                if normal@toward<-.001: continue
                illumination=.77+.22*max(0,float(normal@light))+.12*max(0,float(normal@fill_light))
                x0=max(0,int(np.floor(p[:,0].min()))); x1=min(width-1,int(np.ceil(p[:,0].max())))
                y0=max(0,int(np.floor(p[:,1].min()))); y1=min(height-1,int(np.ceil(p[:,1].max())))
                if x0>x1 or y0>y1: continue
                gx,gy=np.meshgrid(np.arange(x0,x1+1)+.5,np.arange(y0,y1+1)+.5)
                den=(p[1,1]-p[2,1])*(p[0,0]-p[2,0])+(p[2,0]-p[1,0])*(p[0,1]-p[2,1])
                if abs(den)<1e-8: continue
                a=((p[1,1]-p[2,1])*(gx-p[2,0])+(p[2,0]-p[1,0])*(gy-p[2,1]))/den
                b=((p[2,1]-p[0,1])*(gx-p[2,0])+(p[0,0]-p[2,0])*(gy-p[2,1]))/den; c=1-a-b
                z=a*p[0,2]+b*p[1,2]+c*p[2,2]
                old=zbuf[y0:y1+1,x0:x1+1]; mask=(a>=-1e-6)&(b>=-1e-6)&(c>=-1e-6)&(z>=old)
                tx=np.clip((a*tuv[0,0]+b*tuv[1,0]+c*tuv[2,0]).astype(int),0,RESOLUTION-1)
                ty=np.clip((a*tuv[0,1]+b*tuv[1,1]+c*tuv[2,1]).astype(int),0,RESOLUTION-1)
                colors=tex[ty,tx].copy(); mask &= colors[:,:,3]>0
                if smooth:
                    ni=normals[[indices[k] for k in tri]]
                    pixel_normal=a[:,:,None]*ni[0]+b[:,:,None]*ni[1]+c[:,:,None]*ni[2]
                    pixel_normal/=np.maximum(np.linalg.norm(pixel_normal,axis=2,keepdims=True),1e-8)
                    pixel_light=.77+.22*np.maximum(0,pixel_normal@light)+.12*np.maximum(0,pixel_normal@fill_light)
                    colors[:,:,:3]=np.minimum(255,colors[:,:,:3]*pixel_light[:,:,None]).astype(np.uint8)
                else:
                    colors[:,:,:3]=np.minimum(255,colors[:,:,:3]*illumination).astype(np.uint8)
                region=canvas[y0:y1+1,x0:x1+1]; region[mask]=colors[mask]; old[mask]=z[mask]
    return Image.fromarray(canvas)


def font(size):
    return ImageFont.truetype("C:/Windows/Fonts/msyh.ttc",size)


def sheet(identifier,model,texture,orthographic=False):
    image=Image.new("RGBA",(1320,950),(226,227,228,255)); d=ImageDraw.Draw(image)
    d.text((32,23),STYLE[identifier]["name"]+" · 主线重建 / 实际网格预览",font=font(26),fill=(44,49,58))
    specs=([("正面",(0,0,-1)),("侧面",(1,0,0)),("背面",(0,0,1))] if orthographic else
           [("正面",(0,0,-1)),("前侧",(-.65,-.10,-1)),("背面",(.25,-.05,1))])
    for i,(label,direction) in enumerate(specs):
        d.rounded_rectangle((20+i*430,76,430+i*430,895),radius=15,fill=(240,240,239),outline=(196,198,203),width=1)
        img=render(model,texture,direction,380,760)
        image.alpha_composite(img,(35+i*430,96))
        d.text((40+i*430,852),label,font=font(21),fill=(65,72,82))
    d.text((32,912),"从同一份网格/骨骼/UV/贴图渲染；外观研究稿，尚未接入游戏。",font=font(17),fill=(86,90,99))
    return image


def head_sheet(identifier,model,texture,orthographic=False,complete=False):
    image=Image.new("RGBA",(1200,450),(226,227,228,255)); d=ImageDraw.Draw(image)
    suffix="头部实际几何 · 完整发梢" if complete else "头部实际几何"
    d.text((22,15),STYLE[identifier]["name"]+" · "+model.get("revision","研究稿")+suffix,font=font(21),fill=(44,49,58))
    only={**model,"parts":{"head":model["parts"]["head"]}}
    specs=((("正面",(0,0,-1)),("侧面",(1,0,0)),("背面",(0,0,1))) if orthographic else
           (("正面",(0,0,-1)),("前侧",(-.85,0,-1)),("背面",(.25,0,1))))
    frame=(-6.1,6.1,-3.2,8.8) if complete else (-5.7,5.7,-1.5,8.8)
    for i,(label,direction) in enumerate(specs):
        image.alpha_composite(render(only,texture,direction,390,350,frame=frame),(i*400+5,48))
        d.text((i*400+25,410),label,font=font(18),fill=(65,72,82))
    return image


def editable_project(identifier,model,texture):
    """Blockbench 5.0 Generic Model, preserving polygon geometry and bone groups."""
    groups=[]; outliner=[]; elements=[]
    uid=lambda value:str(uuid.uuid5(uuid.NAMESPACE_URL,"ic2newera:robot-sculpt/"+identifier+"/"+value))
    def visit(part,path,base):
        shift=base+np.array(part["pivot"]); gid=uid(path)
        rotation=[math.degrees(v) for v in part["rotation"]]
        groups.append({"name":path.split("/")[-1],"uuid":gid,"origin":[float(shift[0]),float(24-shift[1]),float(shift[2])],
                       "rotation":[-rotation[0],rotation[1],-rotation[2]],"export":True,"isOpen":True,"visibility":True})
        entry={"uuid":gid,"isOpen":True,"children":[]}
        for i,m in enumerate(part["meshes"]):
            eid=uid(path+"/"+m["name"]+"/"+str(i)); vertices={}
            for k,p in enumerate(m["vertices"]):
                xyz=np.array(p)+shift; vertices["v"+str(k)]=[float(xyz[0]),float(24-xyz[1]),float(xyz[2])]
            faces={}
            for k,f in enumerate(m["faces"]):
                indices=list(reversed(f["vertices"]))
                uv={"v"+str(j):[u*RESOLUTION,v*RESOLUTION] for j,(u,v) in zip(f["vertices"],f["uv"])}
                faces["f"+str(k)]={"vertices":["v"+str(j) for j in indices],"uv":uv,"texture":0}
            elements.append({"name":m["name"],"type":"mesh","uuid":eid,"origin":[0,0,0],"rotation":[0,0,0],
                             "vertices":vertices,"faces":faces,"visibility":True,"export":True,
                             "shading":m.get("shading","flat")})
            entry["children"].append(eid)
        for name,child in part["children"].items(): entry["children"].append(visit(child,path+"/"+name,shift))
        return entry
    for name,part in model["parts"].items(): outliner.append(visit(part,name,np.zeros(3)))
    import io
    stream=io.BytesIO(); texture.save(stream,format="PNG")
    return {"meta":{"format_version":"5.0","model_format":"free","box_uv":False},"name":identifier+"_"+model.get("revision",MODEL_REVISION),
            "model_identifier":"ic2newera:humanoid_robot_"+identifier,"resolution":{"width":RESOLUTION,"height":RESOLUTION},
            "elements":elements,"groups":groups,"outliner":outliner,
            "textures":[{"name":identifier+".png","uuid":uid("texture"),"id":"0","mode":"bitmap",
                         "uv_width":RESOLUTION,"uv_height":RESOLUTION,"width":RESOLUTION,"height":RESOLUTION,
                         "source":"data:image/png;base64,"+base64.b64encode(stream.getvalue()).decode("ascii")}]}


def generate(identifiers):
    for folder in ("models","textures","editable","preview"): (STUDY/folder).mkdir(parents=True,exist_ok=True)
    index_path=STUDY/"study-index.json"
    previous=json.loads(index_path.read_text(encoding="utf-8")) if index_path.exists() else []
    records={entry["id"]:entry for entry in previous if entry.get("id") in STYLE}
    for identifier in identifiers:
        model=make_model(identifier); texture,slots=texture_atlas(identifier); assign_atlas(model,slots)
        model_path=STUDY/"models"/(identifier+".json"); tex_path=STUDY/"textures"/(identifier+".png")
        model_path.write_text(json.dumps(model,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        texture.save(tex_path)
        # Preview reads the emitted resource rather than a substitute illustration.
        saved=json.loads(model_path.read_text(encoding="utf-8")); actual_texture=Image.open(tex_path).convert("RGBA")
        sheet(identifier,saved,actual_texture).save(STUDY/"preview"/(identifier+"-study.png"))
        head_sheet(identifier,saved,actual_texture).save(STUDY/"preview"/(identifier+"-head-study.png"))
        sheet(identifier,saved,actual_texture,orthographic=True).save(STUDY/"preview"/(identifier+"-orthographic.png"))
        head_sheet(identifier,saved,actual_texture,orthographic=True).save(STUDY/"preview"/(identifier+"-head-orthographic.png"))
        if identifier=="umiri":
            head_sheet(identifier,saved,actual_texture,orthographic=True,complete=True).save(STUDY/"preview/umiri-head-complete.png")
        project=editable_project(identifier,saved,actual_texture)
        (STUDY/"editable"/(identifier+".bbmodel")).write_text(json.dumps(project,ensure_ascii=False,separators=(",",":")),encoding="utf-8")
        polygons=sum(len(m["faces"]) for _,m,_ in geometry(saved))
        records[identifier]={"id":identifier,"name":STYLE[identifier]["name"],"revision":MODEL_REVISION,"polygons":polygons,"status":"待玩家视觉验收，未接入游戏"}
        print(identifier+": "+str(polygons)+" polygons; saved real-mesh preview and editable project",flush=True)
    index_path.write_text(json.dumps([records[k] for k in STYLE if k in records],ensure_ascii=False,indent=2)+"\n",encoding="utf-8")


def refresh_editable_projects(identifiers):
    """Re-export emitted mesh resources without regenerating geometry."""
    for identifier in identifiers:
        model=json.loads((STUDY/"models"/(identifier+".json")).read_text(encoding="utf-8"))
        texture=Image.open(STUDY/"textures"/(identifier+".png")).convert("RGBA")
        project=editable_project(identifier,model,texture)
        (STUDY/"editable"/(identifier+".bbmodel")).write_text(json.dumps(project,ensure_ascii=False,separators=(",",":")),encoding="utf-8")
    print("Refreshed saved editable projects: "+", ".join(identifiers),flush=True)


def recognition_gallery():
    order=("rana","anon","sakiko","umiri","tomori","uika","taki","soyo")
    full=Image.new("RGBA",(1680,1250),(224,226,229,255)); heads=Image.new("RGBA",(1680,950),(224,226,229,255))
    fd=ImageDraw.Draw(full); hd=ImageDraw.Draw(heads); mapping={}
    fd.text((25,16),"无姓名角色辨识检查 · 实际网格",font=font(23),fill=(54,59,68))
    hd.text((25,16),"无姓名头部局部检查 · 实际网格",font=font(23),fill=(54,59,68))
    for i,identifier in enumerate(order):
        path=STUDY/"models"/(identifier+".json")
        if not path.exists(): continue
        model=json.loads(path.read_text(encoding="utf-8")); texture=Image.open(STUDY/"textures"/(identifier+".png")).convert("RGBA")
        head_sheet(identifier,model,texture).save(STUDY/"preview"/(identifier+"-head-study.png"))
        sheet(identifier,model,texture,orthographic=True).save(STUDY/"preview"/(identifier+"-orthographic.png"))
        head_sheet(identifier,model,texture,orthographic=True).save(STUDY/"preview"/(identifier+"-head-orthographic.png"))
        if identifier=="umiri":
            head_sheet(identifier,model,texture,orthographic=True,complete=True).save(STUDY/"preview/umiri-head-complete.png")
        x=(i%4)*420; y=(i//4)*600+55; hy=(i//4)*445+55
        fd.rounded_rectangle((x+8,y,x+412,y+587),radius=12,fill=(242,241,239),outline=(196,198,204))
        hd.rounded_rectangle((x+8,hy,x+412,hy+432),radius=12,fill=(242,241,239),outline=(196,198,204))
        full.alpha_composite(render(model,texture,(0,0,-1),380,550),(x+20,y+12))
        head_model={**model,"parts":{"head":model["parts"]["head"]}}
        heads.alpha_composite(render(head_model,texture,(0,0,-1),380,385,frame=(-5.7,5.7,-1.5,8.8)),(x+20,hy+16))
        tag=chr(65+i); fd.text((x+22,y+558),tag,font=font(21),fill=(63,69,80)); hd.text((x+22,hy+401),tag,font=font(21),fill=(63,69,80)); mapping[tag]=identifier
    full.save(STUDY/"preview/recognition-gallery.png"); heads.save(STUDY/"preview/head-recognition.png")
    (STUDY/"recognition-map.json").write_text(json.dumps(mapping,indent=2)+"\n",encoding="utf-8")
    head_comparison("v10","head-revision-comparison.png","左V10比例修正前 / 中当前 / 右当前前侧")
    head_comparison("v27","head-last-iteration-comparison.png","左V27 / 中当前 / 右当前前侧")
    head_comparison("v27","head-back-iteration-comparison.png","左V27背面 / 中当前背面 / 右当前侧面",back=True)


def head_comparison(baseline_revision,filename,title,back=False):
    comparison=Image.new("RGBA",(1520,1680),(224,226,229,255)); cd=ImageDraw.Draw(comparison)
    cd.text((22,14),"头部实际资源对照 · "+title,font=font(23),fill=(54,59,68))
    for i,identifier in enumerate(STYLE):
        cx=(i%2)*760; cy=(i//2)*405+50
        model=json.loads((STUDY/"models"/(identifier+".json")).read_text(encoding="utf-8"))
        tex=Image.open(STUDY/"textures"/(identifier+".png")).convert("RGBA")
        baseline=STUDY/"iterations"/baseline_revision
        old_path=baseline/"models"/(identifier+".json")
        if not old_path.exists(): continue
        old=json.loads(old_path.read_text(encoding="utf-8")); old_tex=Image.open(baseline/"textures"/(identifier+".png")).convert("RGBA")
        angles=((0,0,1),(0,0,1),(1,0,0)) if back else ((0,0,-1),(0,0,-1),(.65,0,-1))
        for j,(source,atlas,direction) in enumerate(((old,old_tex,angles[0]),(model,tex,angles[1]),(model,tex,angles[2]))):
            only={**source,"parts":{"head":source["parts"]["head"]}}
            comparison.alpha_composite(render(only,atlas,direction,248,350,frame=(-5.7,5.7,-1.5,8.8)),(cx+j*250,cy+20))
        cd.text((cx+16,cy+363),STYLE[identifier]["name"],font=font(19),fill=(54,59,68))
    comparison.save(STUDY/"preview"/filename)


if __name__=="__main__":
    parser=argparse.ArgumentParser(); parser.add_argument("characters",nargs="*",metavar="CHARACTER")
    modes=parser.add_mutually_exclusive_group(); modes.add_argument("--gallery-only",action="store_true"); modes.add_argument("--editor-only",action="store_true")
    args=parser.parse_args()
    if set(args.characters)-set(STYLE): parser.error("Unknown character id")
    if args.gallery_only: recognition_gallery()
    elif args.editor_only: refresh_editable_projects(args.characters or list(STYLE))
    else: generate(args.characters or ["anon","sakiko"])

