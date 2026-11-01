"""Prepare head-only R18 MCP edits from the reviewed R17 draft.

Copies R17 files to a new workspace and emits thin, crown-overlapping fringe
strips. The script does not edit .bbmodel content; all model edits are applied
through Blockbench MCP with revision checks.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
CHARS = ROOT / "art/robot-mcp-workspace/characters"
OUT = CHARS / "head-r18"
ROLES = ("anon", "tomori", "soyo", "taki", "rana", "sakiko", "uika", "umiri")
CN = {"anon":"爱音","tomori":"灯","soyo":"爽世","taki":"立希","rana":"乐奈","sakiko":"祥子","uika":"初华","umiri":"海铃"}
R17_REV = {"anon":"f6fa3780c7817149","tomori":"fdc33e1a5460adf9","soyo":"09cec4beee508e70","taki":"2d6251bd6d06361e","rana":"63d112d3b6572588","sakiko":"76ad862f08de02f0","uika":"d451fe7c334beaf8","umiri":"f2a09c36c74a365f"}
R18_REV = {"anon":"ad390f9223cc4d82","tomori":"4798b9253f8f6818","soyo":"d686eac28ef8b8b6","taki":"046c5c015ccce7c3","rana":"c3716b1d1a612f4e","sakiko":"1241fdb2455f0784","uika":"d63f8a3e732aa1db","umiri":"4baaeac5d7cf5a7e"}
VIEWS = ("front", "right", "three-quarter")

# Exact image paths returned by this turn's bbmodel_render calls.
RENDER_PATHS = {
"anon":{"r17":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/anon-r17-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/anon-r17-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/anon-r17-three-quarter.png"},"r18":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/anon-r18-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/anon-r18-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/anon-r18-three-quarter.png"}},
"tomori":{"r17":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/tomori-r17-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/tomori-r17-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/tomori-r17-three-quarter.png"},"r18":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/tomori-r18-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/tomori-r18-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/tomori-r18-three-quarter.png"}},
"soyo":{"r17":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/soyo-r17-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/soyo-r17-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/soyo-r17-three-quarter.png"},"r18":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/soyo-r18-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/soyo-r18-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/soyo-r18-three-quarter.png"}},
"taki":{"r17":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/taki-r17-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/taki-r17-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/taki-r17-three-quarter.png"},"r18":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/taki-r18-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/taki-r18-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/taki-r18-three-quarter.png"}},
"rana":{"r17":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/rana-r17-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/rana-r17-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/rana-r17-three-quarter.png"},"r18":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/rana-r18-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/rana-r18-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/rana-r18-three-quarter.png"}},
"sakiko":{"r17":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/sakiko-r17-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/sakiko-r17-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/sakiko-r17-three-quarter.png"},"r18":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/sakiko-r18-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/sakiko-r18-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/sakiko-r18-three-quarter.png"}},
"uika":{"r17":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/uika-r17-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/uika-r17-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/uika-r17-three-quarter.png"},"r18":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/uika-r18-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/uika-r18-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/uika-r18-three-quarter.png"}},
"umiri":{"r17":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/umiri-r17-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/umiri-r17-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/umiri-r17-three-quarter.png"},"r18":{"front":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/umiri-r18-front.png","right":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/umiri-r18-right.png","three-quarter":"C:/Users/罗斌/Desktop/IC2-newera/art/robot-mcp-workspace/characters/head-r18/review/actual/umiri-r18-three-quarter.png"}}}

# (x root, y root, x tip, y tip, width, depth). The crown end sits at
# z=-1.30 and is embedded 0.15 units into the existing crown's front edge.
# Tips vary by role and strand to retain the accepted R17 silhouettes.
LOCKS = {
    "anon":   [(-1.25,27.30,-2.02,24.75,.80,.34),(-.05,27.36,-.88,25.00,.86,.34),(1.12,27.30,.30,25.26,.78,.34)],
    "tomori": [(-1.18,27.26,-1.28,25.20,.78,.32),(-.12,27.34,-.08,25.06,.82,.32),(.98,27.26,1.18,25.20,.78,.32)],
    # Open center part: two outward-moving, shallow pieces, no central V.
    "soyo":   [(-.70,27.32,-1.72,25.18,.78,.32),(.70,27.32,1.72,25.18,.78,.32)],
    "taki":   [(-1.25,27.32,-1.34,25.10,.90,.36),(-.10,27.38,-.03,25.02,.94,.36),(1.06,27.32,1.31,25.10,.90,.36)],
    "rana":   [(-.68,27.24,-1.05,25.35,.58,.30),(.68,27.24,1.05,25.38,.58,.30)],
    "sakiko": [(1.30,27.32,.30,25.26,.76,.34),(.16,27.38,-.86,25.02,.82,.34),(-.92,27.32,-1.92,24.78,.74,.34)],
    "uika":   [(-1.20,27.32,-1.46,25.18,.82,.32),(-.05,27.38,-.05,25.35,.86,.32),(1.15,27.32,1.43,25.18,.82,.32)],
    "umiri":  [(-1.14,27.32,-.42,25.26,.76,.34),(-.02,27.38,.78,25.02,.82,.34),(1.12,27.32,1.88,24.78,.74,.34)],
}

def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def descendants(data: dict, group_name: str) -> set[str]:
    gids = {g["uuid"] for g in data.get("groups", []) if g.get("name") == group_name}
    found: set[str] = set()
    def walk(nodes, active=False):
        for n in nodes:
            if isinstance(n, str):
                if active: found.add(n)
            else:
                walk(n.get("children", []), active or n.get("uuid") in gids)
    walk(data.get("outliner", []))
    return found

def non_head(data: dict) -> str:
    ids = descendants(data, "head")
    groups = {g["uuid"]: g["name"] for g in data.get("groups", [])}
    def tree(nodes, inside=False):
        result=[]
        for n in nodes:
            if isinstance(n, str):
                if not inside and n not in ids: result.append(n)
            else:
                now = inside or groups.get(n.get("uuid")) == "head"
                if not now: result.append({"uuid":n["uuid"],"children":tree(n.get("children",[]),now)})
        return result
    payload={"elements":[e for e in data.get("elements",[]) if e.get("uuid") not in ids],
             "outliner":tree(data.get("outliner",[])),"animations":data.get("animations",[]),
             "resolution":data.get("resolution")}
    return hashlib.sha256(json.dumps(payload,sort_keys=True,separators=(",",":")).encode()).hexdigest()

def make_cube(role, i, spec, texture, uv):
    xr, yr, xt, yt, width, depth = spec
    # From tip to crown root: local +Y points along this vector.
    dx, dy, dz = xr-xt, yr-yt, 2.0
    length = math.sqrt(dx*dx + dy*dy + dz*dz)
    zrot = math.degrees(math.atan2(-dx, dy))
    xrot = math.degrees(math.atan2(dz, math.sqrt(dx*dx + dy*dy)))
    center=((xr+xt)/2,(yr+yt)/2,(-1.30-3.30)/2)
    faces={f:{"texture":texture,"uv":uv} for f in ("north","south","east","west","up","down")}
    return {"op":"add_cube","name":f"{role}_r18_forehead_lock_{i:02d}","parent":"front_fringe_r18",
            "box_uv":False,"from":[round(c-s/2,5) for c,s in zip(center,(width,length,depth))],
            "to":[round(c+s/2,5) for c,s in zip(center,(width,length,depth))],"origin":list(center),
            "rotation":[round(xrot,4),0,round(zrot,4)],"texture":texture,"faces":faces}

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--prepare",action="store_true")
    ap.add_argument("--receipt",action="store_true")
    ap.add_argument("--review",action="store_true",help="write render receipts and head-and-shoulder comparison sheets from returned PNG paths")
    args=ap.parse_args()
    if sum((args.prepare,args.receipt,args.review)) != 1: ap.error("choose exactly one mode")
    OUT.mkdir(parents=True,exist_ok=True)
    plan_path=OUT/"blockbench-edit-plan.json"
    if args.prepare:
        records={}
        for role in ROLES:
            src=CHARS/"head-r17"/role/f"{role}-head-r17.bbmodel"
            dst=OUT/role/f"{role}-head-r18.bbmodel"
            dst.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(src,dst)
            data=json.loads(src.read_text(encoding="utf-8"))
            old=next((e for e in data["elements"] if f"{role}_r17_main_lock_root_" in e.get("name","")),None)
            if old is None: raise RuntimeError(f"R17 hair palette not found for {role}")
            face=old.get("faces",{}).get("north",{})
            texture=old.get("texture",face.get("texture"))
            uv=face.get("uv",[0,0,1,1])
            operations=[{"op":"remove_node","target":"front_fringe_r17"},
                        {"op":"add_group","name":"front_fringe_r18","origin":[0,25.6,-2.3],"parent":"head"}]
            operations += [make_cube(role,i,spec,texture,uv) for i,spec in enumerate(LOCKS[role],1)]
            records[role]={"source_r17":str(src.relative_to(ROOT)),"source_r17_sha256":sha(src),
                "output":str(dst.relative_to(ROOT)),"r17_non_head_fingerprint":non_head(data),
                "texture_index":texture,"uv":uv,"operations":operations,"status":"copied; MCP edits pending"}
        plan_path.write_text(json.dumps(records,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        print(f"Prepared {len(records)} isolated R18 copies and MCP operation plans")
    elif args.receipt:
        records=json.loads(plan_path.read_text(encoding="utf-8"))
        for role,rec in records.items():
            dst=ROOT/rec["output"]
            data=json.loads(dst.read_text(encoding="utf-8"))
            rec["output_sha256"]=sha(dst)
            rec["r18_non_head_fingerprint"]=non_head(data)
            rec["non_head_unchanged"]=rec["r17_non_head_fingerprint"]==rec["r18_non_head_fingerprint"]
            rec["status"]="non-head unchanged" if rec["non_head_unchanged"] else "NON-HEAD CHANGE DETECTED"
        plan_path.write_text(json.dumps(records,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        bad=[r for r,v in records.items() if not v["non_head_unchanged"]]
        print(f"Fingerprints recorded: {len(records)-len(bad)}/{len(records)} unchanged")
        if bad: raise SystemExit("Non-head fingerprint mismatch: "+", ".join(bad))
    else:
        review=OUT/"review"
        receipt={"render_tool":"Blockbench MCP bbmodel_render","source_revision_note":"R17 and R18 revisions from bbmodel_info after final edits","roles":{}}
        for role in ROLES:
            p17=CHARS/"head-r17"/role/f"{role}-head-r17.bbmodel"
            p18=OUT/role/f"{role}-head-r18.bbmodel"
            receipt["roles"][role]={"display_name_zh":CN[role],
                "r17":{"model_path":str(p17.resolve()),"model_revision":R17_REV[role],"model_sha256":sha(p17),"renders":RENDER_PATHS[role]["r17"]},
                "r18":{"model_path":str(p18.resolve()),"model_revision":R18_REV[role],"model_sha256":sha(p18),"renders":RENDER_PATHS[role]["r18"]}}
        (review/"render-receipts.json").write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        font=ImageFont.truetype("C:/Windows/Fonts/msyh.ttc",16)
        labels={"front":"正面","right":"右侧","three-quarter":"三分之四"}
        def head_tile(path):
            im=Image.open(path).convert("RGB")
            # bbmodel_render frames the full avatar; retain the actual returned
            # upper-body crop for readable face/head comparison.
            crop=im.crop((80,0,560,300)).resize((320,200),Image.Resampling.LANCZOS)
            return crop
        for version in ("r18",):
            for view in VIEWS:
                sheet=Image.new("RGB",(1280,472),(32,34,39)); d=ImageDraw.Draw(sheet)
                d.text((12,6),f"R18 {labels[view]} · 本轮 Blockbench MCP 实际渲染",fill=(245,245,245),font=font)
                for ix,role in enumerate(ROLES):
                    x=(ix%4)*320; y=32+(ix//4)*220
                    sheet.paste(head_tile(RENDER_PATHS[role][version][view]),(x,y+20))
                    d.text((x+8,y+2),CN[role],fill=(255,255,255),font=font)
                sheet.save(review/f"r18-{view}-eight.png")
        for view in VIEWS:
            sheet=Image.new("RGB",(1280,1840),(32,34,39)); d=ImageDraw.Draw(sheet)
            d.text((12,6),f"R17 / R18 {labels[view]} · 本轮实际 MCP 渲染",fill=(245,245,245),font=font)
            for ix,role in enumerate(ROLES):
                y=32+ix*224
                d.text((8,y+2),CN[role],fill=(255,255,255),font=font)
                sheet.paste(head_tile(RENDER_PATHS[role]["r17"][view]),(80,y+18))
                sheet.paste(head_tile(RENDER_PATHS[role]["r18"][view]),(560,y+18))
                d.text((120,y+2),"R17",fill=(225,225,225),font=font); d.text((600,y+2),"R18",fill=(225,225,225),font=font)
            sheet.save(review/f"r17-r18-{view}-comparison.png")
        print("Wrote render receipts and six comparison sheets from 48 returned PNG paths")

if __name__=="__main__": main()
