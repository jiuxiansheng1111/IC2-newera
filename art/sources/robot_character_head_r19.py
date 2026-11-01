"""Prepare role-specific R19 forehead hair edits for Blockbench MCP.

Copies R18 files into a new workspace and emits edit plans. It never edits
.bbmodel files directly; MCP revisions gate each model write.
"""
from __future__ import annotations
import argparse, hashlib, json, math, shutil
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
CHARS=ROOT/"art/robot-mcp-workspace/characters"
OUT=CHARS/"head-r19"
PILOT=("anon","soyo")
REMAINING=("tomori","taki","rana","sakiko","uika","umiri")
ROLES=("anon","tomori","soyo","taki","rana","sakiko","uika","umiri")
# Each lock is (x_root,y_root,x_tip,y_tip,width,depth). Seven ordered locks
# retain individual direction, stagger, and tip lengths.
LOCKS={
 "anon":[(-2.10,27.28,-2.35,24.30,.70,.30),(-1.42,27.35,-1.90,24.12,.72,.30),(-.72,27.38,-1.18,24.02,.70,.30),(-.02,27.40,-.46,24.10,.74,.30),(.70,27.38,.18,24.22,.68,.30),(1.38,27.35,.88,24.38,.68,.30),(2.02,27.28,1.60,24.46,.64,.30)],
 "tomori":[(-1.95,27.28,-1.90,24.38,.62,.28),(-1.30,27.35,-1.28,24.28,.62,.28),(-.65,27.38,-.64,24.20,.62,.28),(0,27.40,0,24.18,.64,.28),(.66,27.38,.65,24.22,.62,.28),(1.32,27.35,1.30,24.31,.62,.28),(1.96,27.28,1.95,24.40,.62,.28)],
 "soyo":[(-2.00,27.28,-2.22,24.42,.56,.28),(-1.48,27.35,-1.80,24.30,.58,.28),(-.92,27.38,-1.32,24.20,.56,.28),(.92,27.38,1.32,24.20,.56,.28),(1.48,27.35,1.80,24.30,.58,.28),(2.00,27.28,2.22,24.42,.56,.28)],
 "taki":[(-2.05,27.28,-2.08,24.20,.62,.30),(-1.37,27.35,-1.38,24.12,.64,.30),(-.69,27.38,-.69,24.06,.64,.30),(0,27.40,0,24.04,.66,.30),(.69,27.38,.69,24.06,.64,.30),(1.37,27.35,1.38,24.12,.64,.30),(2.05,27.28,2.08,24.20,.62,.30)],
 "rana":[(-1.90,27.28,-2.05,24.48,.55,.28),(-1.25,27.35,-1.54,24.14,.58,.28),(-.58,27.38,-.75,24.32,.50,.28),(.05,27.40,.10,24.12,.58,.28),(.67,27.38,.83,24.40,.52,.28),(1.32,27.35,1.62,24.18,.58,.28),(1.94,27.28,2.10,24.46,.54,.28)],
 "sakiko":[(2.05,27.28,2.32,24.42,.60,.30),(1.38,27.35,1.88,24.24,.64,.30),(.70,27.38,1.20,24.08,.68,.30),(0,27.40,.50,24.02,.70,.30),(-.72,27.38,-.18,24.12,.68,.30),(-1.42,27.35,-.86,24.30,.66,.30),(-2.08,27.28,-1.58,24.46,.62,.30)],
 "uika":[(-2.05,27.28,-2.12,24.42,.62,.28),(-1.37,27.35,-1.42,24.26,.64,.28),(-.69,27.38,-.72,24.16,.64,.28),(0,27.40,0,24.14,.66,.28),(.69,27.38,.72,24.16,.64,.28),(1.37,27.35,1.42,24.26,.64,.28),(2.05,27.28,2.12,24.42,.62,.28)],
 "umiri":[(-2.05,27.28,-1.62,24.46,.62,.30),(-1.37,27.35,-.86,24.30,.66,.30),(-.69,27.38,-.18,24.12,.68,.30),(0,27.40,.50,24.02,.70,.30),(.70,27.38,1.20,24.08,.68,.30),(1.38,27.35,1.88,24.24,.64,.30),(2.05,27.28,2.32,24.42,.60,.30)],
}
# Three thin underlay ribbons close the crown-to-forehead profile. Soyo keeps a
# narrow center part; Anon gets a continuous side-swept underlayer.
BASE={
 "anon":[(-1.55,27.30,-1.82,24.12,1.72,.14),(.02,27.40,-.20,24.08,1.72,.14),(1.58,27.30,1.22,24.32,1.70,.14)],
 "soyo":[(-1.34,27.30,-1.55,24.22,1.48,.14),(1.34,27.30,1.55,24.22,1.48,.14)],
 "tomori":[(-1.48,27.62,-1.48,24.58,1.58,.14),(0,27.68,0,24.50,1.62,.14),(1.48,27.62,1.48,24.64,1.58,.14)],
 "taki":[(-1.52,27.62,-1.54,24.12,1.62,.14),(0,27.68,0,24.08,1.64,.14),(1.52,27.62,1.54,24.14,1.62,.14)],
 "rana":[(-1.36,27.62,-1.58,24.55,1.50,.14),(.04,27.68,-.14,24.48,1.56,.14),(1.40,27.62,1.32,24.60,1.48,.14)],
 "sakiko":[(1.48,27.62,1.70,24.28,1.60,.14),(.02,27.68,.30,24.18,1.64,.14),(-1.48,27.62,-1.24,24.36,1.58,.14)],
 "uika":[(-1.54,27.62,-1.54,24.32,1.64,.14),(0,27.68,0,24.26,1.68,.14),(1.54,27.62,1.54,24.34,1.64,.14)],
 "umiri":[(-1.48,27.62,-1.24,24.38,1.58,.14),(.02,27.68,.30,24.18,1.64,.14),(1.48,27.62,1.70,24.28,1.60,.14)],
}
CROWN_STAIRS={
 "taki":[
  ([-1.85,24.52,-2.78],[1.85,24.96,-2.46],"hair"),([-2.05,24.90,-2.57],[2.05,25.34,-2.25],"hair_shade"),
  ([-2.20,25.28,-2.40],[2.20,25.72,-2.08],"hair"),([-2.34,25.66,-2.12],[2.34,26.10,-1.80],"hair_shade"),
  ([-2.45,26.04,-1.82],[2.45,26.48,-1.50],"hair"),([-2.50,26.42,-1.62],[2.50,26.86,-1.30],"hair_shade"),
  ([-2.50,26.80,-1.50],[2.50,27.24,-1.18],"hair"),([-2.45,27.18,-1.38],[2.45,27.58,-1.06],"hair_shade"),
 ],
 "rana":[
  ([-1.75,24.52,-2.78],[1.75,24.96,-2.46],"hair"),([-1.95,24.90,-2.57],[1.95,25.34,-2.25],"hair_shade"),
  ([-2.10,25.28,-2.40],[2.10,25.72,-2.08],"hair"),([-2.24,25.66,-2.12],[2.24,26.10,-1.80],"hair_shade"),
  ([-2.35,26.04,-1.82],[2.35,26.48,-1.50],"hair"),([-2.40,26.42,-1.62],[2.40,26.86,-1.30],"hair_shade"),
  ([-2.40,26.80,-1.50],[2.40,27.24,-1.18],"hair"),([-2.35,27.18,-1.38],[2.35,27.58,-1.06],"hair_shade"),
 ],
 "sakiko":[
  ([-1.85,24.52,-2.78],[1.85,24.96,-2.46],"hair"),([-2.05,24.90,-2.57],[2.05,25.34,-2.25],"hair_shade"),
  ([-2.20,25.28,-2.40],[2.20,25.72,-2.08],"hair"),([-2.34,25.66,-2.12],[2.34,26.10,-1.80],"hair_shade"),
  ([-2.45,26.04,-1.82],[2.45,26.48,-1.50],"hair"),([-2.50,26.42,-1.62],[2.50,26.86,-1.30],"hair_shade"),
  ([-2.50,26.80,-1.50],[2.50,27.24,-1.18],"hair"),([-2.45,27.18,-1.38],[2.45,27.58,-1.06],"hair_shade"),
 ],
 "uika":[
  ([-1.85,24.52,-2.78],[1.85,24.96,-2.46],"hair"),([-2.05,24.90,-2.57],[2.05,25.34,-2.25],"hair_shade"),
  ([-2.20,25.28,-2.40],[2.20,25.72,-2.08],"hair"),([-2.34,25.66,-2.12],[2.34,26.10,-1.80],"hair_shade"),
  ([-2.45,26.04,-1.82],[2.45,26.48,-1.50],"hair"),([-2.50,26.42,-1.62],[2.50,26.86,-1.30],"hair_shade"),
  ([-2.50,26.80,-1.50],[2.50,27.24,-1.18],"hair"),([-2.45,27.18,-1.38],[2.45,27.58,-1.06],"hair_shade"),
 ],
 "tomori":[
  ([-1.85,24.52,-2.78],[1.85,24.96,-2.46],"hair"),
  ([-2.05,24.90,-2.57],[2.05,25.34,-2.25],"hair_shade"),
  ([-2.20,25.28,-2.40],[2.20,25.72,-2.08],"hair"),
  ([-2.34,25.66,-2.12],[2.34,26.10,-1.80],"hair_shade"),
  ([-2.45,26.04,-1.82],[2.45,26.48,-1.50],"hair"),
  ([-2.50,26.42,-1.62],[2.50,26.86,-1.30],"hair_shade"),
  ([-2.50,26.80,-1.50],[2.50,27.24,-1.18],"hair"),
  ([-2.45,27.18,-1.38],[2.45,27.58,-1.06],"hair_shade"),
 ],
 "umiri":[
  ([-1.75,24.52,-2.78],[1.75,24.96,-2.46],"hair"),
  ([-1.95,24.90,-2.57],[1.95,25.34,-2.25],"hair_shade"),
  ([-2.10,25.28,-2.40],[2.10,25.72,-2.08],"hair"),
  ([-2.24,25.66,-2.12],[2.24,26.10,-1.80],"hair_shade"),
  ([-2.35,26.04,-1.82],[2.35,26.48,-1.50],"hair"),
  ([-2.40,26.42,-1.62],[2.40,26.86,-1.30],"hair_shade"),
  ([-2.40,26.80,-1.50],[2.40,27.24,-1.18],"hair"),
  ([-2.35,27.18,-1.38],[2.35,27.58,-1.06],"hair_shade"),
 ],
}

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def descendants(data,name):
 gids={g["uuid"] for g in data.get("groups",[]) if g.get("name")==name}; found=set()
 def walk(nodes,active=False):
  for n in nodes:
   if isinstance(n,str):
    if active: found.add(n)
   else: walk(n.get("children",[]),active or n.get("uuid") in gids)
 walk(data.get("outliner",[])); return found
def nonhead(data):
 ids=descendants(data,"head"); groups={g["uuid"]:g["name"] for g in data.get("groups",[])}
 def tree(nodes,inside=False):
  out=[]
  for n in nodes:
   if isinstance(n,str):
    if not inside and n not in ids: out.append(n)
   else:
    now=inside or groups.get(n.get("uuid"))=="head"
    if not now: out.append({"uuid":n["uuid"],"children":tree(n.get("children",[]),now)})
  return out
 payload={"elements":[e for e in data.get("elements",[]) if e.get("uuid") not in ids],"outliner":tree(data.get("outliner",[])),"animations":data.get("animations",[]),"resolution":data.get("resolution")}
 return hashlib.sha256(json.dumps(payload,sort_keys=True,separators=(",",":")).encode()).hexdigest()

def cube(role,prefix,i,spec,texture,uv,parent):
 xr,yr,xt,yt,width,depth=spec
 # Crown/root end is slightly behind the foreground locks; underlay tip ends
 # at z=-3.04 while visible locks sit at z=-3.22.
 zr,zt=(-1.55,-3.04) if prefix=="underlay" else (-1.42,-3.22)
 dx,dy,dz=xr-xt,yr-yt,zr-zt
 length=math.sqrt(dx*dx+dy*dy+dz*dz)
 rot_x=math.degrees(math.atan2(dz,math.sqrt(dx*dx+dy*dy)))
 rot_z=math.degrees(math.atan2(-dx,dy))
 center=((xr+xt)/2,(yr+yt)/2,(zr+zt)/2)
 faces={f:{"texture":texture,"uv":uv} for f in ("north","south","east","west","up","down")}
 return {"op":"add_cube","name":f"{role}_r19_{prefix}_{i:02d}","parent":parent,"box_uv":False,
  "from":[round(c-s/2,5) for c,s in zip(center,(width,length,depth))],"to":[round(c+s/2,5) for c,s in zip(center,(width,length,depth))],
  "origin":list(center),"rotation":[round(rot_x,4),0,round(rot_z,4)],"texture":texture,"faces":faces}

def palette_uv(role, data, swatch):
 if role=="anon":
  x={"hair":4.5,"hair_shade":12.5}[swatch]; return [x,.5,x+3,3.5]
 pal=json.loads((CHARS/f"{role}-r16-standard/palette.json").read_text(encoding="utf-8"))
 slot=list(pal).index(swatch); return [slot%16*4+.5,slot//16*4+.5,slot%16*4+3.5,slot//16*4+3.5]

def segment_op(role,name,a,b,width,depth,texture,uv,parent):
 dx,dy,dz=a[0]-b[0],a[1]-b[1],a[2]-b[2]
 length=math.sqrt(dx*dx+dy*dy+dz*dz)
 rot_x=math.degrees(math.atan2(dz,math.sqrt(dx*dx+dy*dy)))
 rot_z=math.degrees(math.atan2(-dx,dy))
 center=[(a[i]+b[i])/2 for i in range(3)]
 faces={f:{"texture":texture,"uv":uv} for f in ("north","south","east","west","up","down")}
 return {"op":"add_cube","name":name,"parent":parent,"box_uv":False,
  "from":[round(c-s/2,5) for c,s in zip(center,(width,length,depth))],
  "to":[round(c+s/2,5) for c,s in zip(center,(width,length,depth))],"origin":center,
  "rotation":[round(rot_x,4),0,round(rot_z,4)],"texture":texture,"faces":faces}

def box_op(name,parent,box,texture,uv):
 lo,hi=box
 faces={f:{"texture":texture,"uv":uv} for f in ("north","south","east","west","up","down")}
 return {"op":"add_cube","name":name,"parent":parent,"from":lo,"to":hi,"origin":[(lo[i]+hi[i])/2 for i in range(3)],"rotation":[0,0,0],"box_uv":False,"texture":texture,"faces":faces}

def lerp(a,b,t): return tuple(a[i]+(b[i]-a[i])*t for i in range(3))

def refine_ops(role,data):
 old=next(e for e in data["elements"] if f"{role}_r19_lock_" in e.get("name",""))
 texture=old["faces"]["north"]["texture"]
 main_uv=palette_uv(role,data,"hair"); shade_uv=palette_uv(role,data,"hair_shade")
 ops=[]
 if role in REMAINING:
  ops += [{"op":"remove_node","target":"forehead_underlay_r19"},{"op":"add_group","name":"forehead_underlay_r19","origin":[0,25.7,-2.2],"parent":"head"}]
  ops += [cube(role,"underlay",i,s,texture,main_uv,"forehead_underlay_r19") for i,s in enumerate(BASE[role],1)]
 if role in CROWN_STAIRS:
  if any(g.get("name")=="r19_crown_underfill" for g in data.get("groups",[])):
   ops.append({"op":"remove_node","target":"r19_crown_underfill"})
  ops.append({"op":"add_group","name":"r19_crown_underfill","origin":[0,25.7,-2.2],"parent":"head"})
  for i,(lo,hi,swatch) in enumerate(CROWN_STAIRS[role],1):
   ops.append(box_op(f"{role}_r19_crown_underfill_{i:02d}","r19_crown_underfill",(lo,hi),texture,palette_uv(role,data,swatch)))
 ops += [{"op":"remove_node","target":"front_fringe_r19"},{"op":"add_group","name":"front_fringe_r19","origin":[0,25.6,-2.3],"parent":"head"}]
 if role=="anon":
  roots=[-2.05,-1.38,-.71,-.04,.64,1.31,1.98]
  sweeps=[.34,.55,.75,.93,1.08,1.20,1.28]
  tips=[24.16,24.03,24.24,24.08,24.20,24.36,24.44]
  offsets=[0,-.10,-.16,-.08,-.14,-.08,-.16]
  strands=[]
  for i,(x,y,off,sweep) in enumerate(zip(roots,tips,offsets,sweeps)):
   strands.append([(x,27.30,-1.42+off),(x-sweep*.18,26.20,-2.05+off),(x-sweep*.56,25.16,-2.72+off),(x-sweep,y,-3.22+off)])
 elif role=="soyo":
  # Three curved, overlapping locks on each curtain; inner ends keep a narrow part.
  strands=[]
  for side in (-1,1):
   for j,(root,tip,y,off) in enumerate(((.36,1.02,24.08,-.08),(.92,1.62,24.28,-.16),(1.50,2.22,24.44,-.08))):
    sweep=tip-root
    strands.append([(side*root,27.30,-1.42+off),(side*(root+sweep*.16),26.20,-2.05+off),(side*(root+sweep*.55),25.15,-2.72+off),(side*tip,y,-3.22+off)])
 elif role in REMAINING:
  strands=[]
  profiles={
   # Short, uneven fringe over Tomori's compact bob.
   "tomori":[(-1.88,-2.02,24.46,-.08),(-1.22,-1.42,24.38,-.16),(-.56,-.70,24.44,-.08),(.14,.02,24.34,-.14),(.80,.94,24.48,-.08),(1.46,1.64,24.40,-.16)],
   # Taki stays orderly and nearly level, with a restrained stepped edge.
   "taki":[(-2.02,-2.02,24.22,-.08),(-1.36,-1.34,24.12,-.16),(-.70,-.68,24.08,-.08),(0,0,24.06,-.14),(.70,.68,24.08,-.08),(1.36,1.34,24.12,-.16),(2.02,2.02,24.22,-.08)],
   # Rana has short, fine, slightly parted locks rather than a solid cap.
   "rana":[(-1.90,-2.08,24.62,-.08),(-1.24,-1.60,24.44,-.16),(-.58,-.82,24.58,-.08),(.10,.04,24.42,-.14),(.78,1.02,24.64,-.08),(1.42,1.82,24.50,-.16)],
   # Sakiko sweeps from her right side across the forehead.
   "sakiko":[(2.05,2.30,24.42,-.08),(1.38,1.88,24.24,-.16),(.70,1.20,24.08,-.08),(0,.50,24.02,-.14),(-.72,-.18,24.12,-.08),(-1.42,-.86,24.30,-.16),(-2.08,-1.58,24.46,-.08)],
   # Uika has a clean blunt fringe broken into close, subtly unequal pieces.
   "uika":[(-2.02,-2.06,24.40,-.08),(-1.36,-1.40,24.30,-.16),(-.70,-.72,24.24,-.08),(0,0,24.22,-.14),(.70,.72,24.24,-.08),(1.36,1.40,24.30,-.16),(2.02,2.06,24.40,-.08)],
   # Umiri's side part falls in the opposite direction to Sakiko's.
   "umiri":[(-2.02,-2.32,24.46,-.08),(-1.36,-1.88,24.30,-.16),(-.70,-1.28,24.12,-.08),(0,-.50,24.04,-.14),(.70,.10,24.10,-.08),(1.36,.78,24.26,-.16),(2.02,1.54,24.44,-.08)],
  }
  # End heights and width/depth vary by hairstyle while retaining connected
  # ribbons over the continuous underlayer.
  for i,(root,tip,y,off) in enumerate(profiles[role]):
   strands.append([(root,27.62,-1.54+off),(root+(tip-root)*.18,26.20,-2.05+off),(root+(tip-root)*.56,25.15,-2.72+off),(tip,y,-3.22+off)])
 else: raise ValueError(role)
 for si,points in enumerate(strands,1):
  # Adjacent lengths have uneven tips; each strand is a connected three-piece
  # strip with small overlaps, alternating its existing hair/shade atlas swatches.
  widths=(.72,.64,.53) if role=="anon" else ((.58,.56,.48) if role=="soyo" else {"tomori":(.50,.43,.36),"taki":(.52,.46,.40),"rana":(.46,.42,.35),"sakiko":(.50,.44,.38),"uika":(.48,.44,.38),"umiri":(.50,.44,.38)}[role])
  for seg in range(3):
   a=points[seg]; b=points[seg+1]
   # Extend both ends by 0.06 along the centerline to hide segment seams.
   unit=tuple((b[k]-a[k])/math.dist(a,b) for k in range(3))
   a2=tuple(a[k]-unit[k]*.06 for k in range(3)) if seg else a
   b2=tuple(b[k]+unit[k]*.06 for k in range(3)) if seg<2 else b
   uv=main_uv if (si+seg)%3 else shade_uv
   ops.append(segment_op(role,f"{role}_r19_lock_{si:02d}_segment_{seg+1}",a2,b2,widths[seg],.24,texture,uv,"front_fringe_r19"))
 return ops

def main():
 ap=argparse.ArgumentParser(); ap.add_argument("--prepare",action="store_true"); ap.add_argument("--prepare-remaining",action="store_true"); ap.add_argument("--refine",action="store_true"); ap.add_argument("--refine-remaining",action="store_true"); ap.add_argument("--refine-pair",action="store_true"); ap.add_argument("--refine-crown-four",action="store_true"); ap.add_argument("--receipt",action="store_true"); a=ap.parse_args()
 if sum((a.prepare,a.prepare_remaining,a.refine,a.refine_remaining,a.refine_pair,a.refine_crown_four,a.receipt))!=1: ap.error("choose one mode")
 OUT.mkdir(parents=True,exist_ok=True); pp=OUT/"blockbench-edit-plan.json"
 if a.prepare:
  rec={}
  for role in PILOT:
   src=CHARS/"head-r18"/role/f"{role}-head-r18.bbmodel"; dst=OUT/role/f"{role}-head-r19.bbmodel"
   dst.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(src,dst); data=json.loads(src.read_text(encoding="utf-8"))
   old=next(e for e in data["elements"] if f"{role}_r18_forehead_lock_" in e.get("name","")); face=old["faces"]["north"]
   tex=old.get("texture",face.get("texture")); uv=face["uv"]
   ops=[{"op":"remove_node","target":"front_fringe_r18"},{"op":"add_group","name":"forehead_underlay_r19","origin":[0,25.7,-2.2],"parent":"head"},{"op":"add_group","name":"front_fringe_r19","origin":[0,25.7,-2.3],"parent":"head"}]
   ops += [cube(role,"underlay",i,s,tex,uv,"forehead_underlay_r19") for i,s in enumerate(BASE[role],1)]
   ops += [cube(role,"lock",i,s,tex,uv,"front_fringe_r19") for i,s in enumerate(LOCKS[role],1)]
   rec[role]={"source":str(src.relative_to(ROOT)),"source_sha256":sha(src),"source_non_head_fingerprint":nonhead(data),"output":str(dst.relative_to(ROOT)),"texture_index":tex,"uv":uv,"operations":ops,"status":"copied; MCP edit pending"}
  pp.write_text(json.dumps(rec,ensure_ascii=False,indent=2)+"\n",encoding="utf-8"); print(f"Prepared {len(rec)} pilot models")
 elif a.prepare_remaining:
  rec={}
  for role in REMAINING:
   src=CHARS/"head-r18"/role/f"{role}-head-r18.bbmodel"; dst=OUT/role/f"{role}-head-r19.bbmodel"
   dst.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(src,dst); data=json.loads(src.read_text(encoding="utf-8"))
   old=next(e for e in data["elements"] if f"{role}_r18_forehead_lock_" in e.get("name","")); face=old["faces"]["north"]; tex=old.get("texture",face.get("texture")); uv=face["uv"]
   ops=[{"op":"remove_node","target":"front_fringe_r18"},{"op":"add_group","name":"forehead_underlay_r19","origin":[0,25.7,-2.2],"parent":"head"},{"op":"add_group","name":"front_fringe_r19","origin":[0,25.7,-2.3],"parent":"head"}]
   ops += [cube(role,"underlay",i,s,tex,uv,"forehead_underlay_r19") for i,s in enumerate(BASE[role],1)]
   ops += [cube(role,"lock",i,s,tex,uv,"front_fringe_r19") for i,s in enumerate(LOCKS[role],1)]
   rec[role]={"source":str(src.relative_to(ROOT)),"source_sha256":sha(src),"source_non_head_fingerprint":nonhead(data),"output":str(dst.relative_to(ROOT)),"texture_index":tex,"uv":uv,"operations":ops,"status":"copied; MCP edit pending"}
  (OUT/"remaining-edit-plan.json").write_text(json.dumps(rec,ensure_ascii=False,indent=2)+"\n",encoding="utf-8"); print(f"Prepared {len(rec)} remaining candidate models")
 elif a.refine or a.refine_remaining or a.refine_pair or a.refine_crown_four:
  roles=("tomori","umiri") if a.refine_pair else (("taki","rana","sakiko","uika") if a.refine_crown_four else (REMAINING if a.refine_remaining else PILOT))
  rec={}
  for role in roles:
   src=OUT/role/f"{role}-head-r19.bbmodel"; data=json.loads(src.read_text(encoding="utf-8"))
   rec[role]={"source":str(src.relative_to(ROOT)),"source_sha256":sha(src),"source_non_head_fingerprint":nonhead(data),"operations":refine_ops(role,data),"expected_revision":"Read fresh using bbmodel_info immediately before MCP edit; never infer from SHA or prior run."}
  target=OUT/("refine-closure-pair-edit-plan.json" if a.refine_pair else ("refine-crown-four-edit-plan.json" if a.refine_crown_four else ("refine-remaining-edit-plan.json" if a.refine_remaining else "refine-edit-plan.json")))
  target.write_text(json.dumps(rec,ensure_ascii=False,indent=2)+"\n",encoding="utf-8"); print(f"Prepared {len(rec)} role-specific outer-strand refine operations with updated role-specific underlays")
 else:
  rec={}; bad=[]
  for role in ROLES:
   src=CHARS/"head-r18"/role/f"{role}-head-r18.bbmodel"; dst=OUT/role/f"{role}-head-r19.bbmodel"
   sd=json.loads(src.read_text(encoding="utf-8")); dd=json.loads(dst.read_text(encoding="utf-8")); unchanged=nonhead(sd)==nonhead(dd)
   rec[role]={"source":str(src.relative_to(ROOT)),"source_sha256":sha(src),"output":str(dst.relative_to(ROOT)),"output_sha256":sha(dst),"source_non_head_fingerprint":nonhead(sd),"output_non_head_fingerprint":nonhead(dd),"non_head_unchanged":unchanged,"status":"non-head unchanged" if unchanged else "NON-HEAD MISMATCH"}
   if not unchanged: bad.append(role)
  target=OUT/"model-fingerprints.json"; target.write_text(json.dumps(rec,ensure_ascii=False,indent=2)+"\n",encoding="utf-8"); print(f"Non-head fingerprints unchanged: {len(rec)-len(bad)}/{len(rec)}")
  if bad: raise SystemExit("Mismatch: "+", ".join(bad))
if __name__=="__main__": main()
