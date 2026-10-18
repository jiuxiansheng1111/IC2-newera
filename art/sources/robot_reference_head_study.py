"""User-authorized source-pixel face study. No AI face painting or A6 face call."""
from pathlib import Path
import argparse, copy, hashlib, json, math
import numpy as np
from PIL import Image, ImageDraw
import robot_cube_pipeline as pipeline

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'art/robot-reference-head-prototype'
SOURCE=ROOT/'art/robot-image-reference/stage-reference-lossless.png'
LANDMARKS={'viewer_left_eye':[104.5,161.0], 'viewer_right_eye':[123.0,153.5],
           'nose':[116.0,163.0], 'mouth':[119.0,171.0], 'chin':[118.6,178.0]}
FACE_POLYGON=[(104,150),(111,152),(120,147),(129,147),(133,157),(130,167),
              (125,175),(119,178),(111,176),(105,169),(99,165),(99,158)]
FEATURE_POLYGONS=[[(98,158),(102,156),(108,156),(111,159),(111,163),(108,166),(102,165),(98,162)],
                  [(115,150),(119,149),(126,149),(129,151),(128,155),(125,158),(119,157),(116,155)],
                  [(111,160),(119,160),(120,166),(111,166)],
                  [(111,171),(115,168),(121,167),(123,169),(123,172),(119,175),(114,174),(111,173)]]

def save_json(path,obj):
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf8')

def face_study():
    src=Image.open(SOURCE).convert('RGB');pixels=np.asarray(src);w,h=src.size
    left=np.array(LANDMARKS['viewer_left_eye']);right=np.array(LANDMARKS['viewer_right_eye'])
    delta=right-left;theta=-math.atan2(delta[1],delta[0]);scale=24/np.linalg.norm(delta)
    c,s=math.cos(theta),math.sin(theta);rotation=np.array([[c,-s],[s,c]])
    transform=scale*rotation;origin=np.array([20.,26.]);inverse=np.linalg.inv(transform)
    forward=lambda point:transform@(np.array(point)-left)+origin
    # This row shift recentres the visible lower-face midline; it changes only
    # source projection, not the sampled pixels or the mouth expression.
    mouth_uv=forward(LANDMARKS['mouth']);chin_uv=forward(LANDMARKS['chin'])
    knots=[(0,0),(26,0),(float(mouth_uv[1]),float(32-mouth_uv[0])),
           (float(chin_uv[1]),float(32-chin_uv[0])),(64,float(32-chin_uv[0]))]
    offset=lambda y:float(np.interp(y,[a for a,b in knots],[b for a,b in knots]))
    face_mask=Image.new('L',src.size,0);ImageDraw.Draw(face_mask).polygon(FACE_POLYGON,fill=255)
    masks=[]
    for polygon in FEATURE_POLYGONS:
        mask=Image.new('L',src.size,0);ImageDraw.Draw(mask).polygon(polygon,fill=255);masks.append(np.asarray(mask)>0)
    fm=np.asarray(face_mask)>0
    skin_values=pixels[158:164,112:118].reshape(-1,3)
    skin=tuple(np.median(skin_values,axis=0).astype(np.uint8).tolist())
    corrected=np.full((64,64,3),255,np.uint8);chart=np.empty((64,64,3),np.uint8);chart[:]=skin
    sample_mask=np.zeros((64,64),np.uint8);source_map=np.full((64,64,2),-1,np.int16)
    for y in range(64):
        for x in range(64):
            position=np.array([x+.5-offset(y+.5),y+.5])
            for uc,sx_scale,sy_scale in ((20,1.05,1.02),(44,.96,.98)):
                if abs(position[0]-uc)<9 and abs(position[1]-26)<8:
                    position=np.array([uc+(position[0]-uc)/sx_scale,26+(position[1]-26)/sy_scale]);break
            raw=inverse@(position-origin)+left
            sx,sy=np.rint(raw).astype(int)
            if not (0<=sx<w and 0<=sy<h):continue
            color=pixels[sy,sx];corrected[y,x]=color
            # Pink hair is excluded, but dark eye pixels and blue-grey iris remain.
            pink=int(color[2])>int(color[1])+3 and int(color[0])>int(color[2])+10
            keep=((masks[0][sy,sx] or masks[1][sy,sx]) and not pink) or masks[3][sy,sx]
            if keep:chart[y,x]=color;sample_mask[y,x]=255;source_map[y,x]=[sx,sy]
    folder=OUT/'uv-study';folder.mkdir(parents=True,exist_ok=True)
    Image.fromarray(corrected).save(folder/'anon-projection-corrected-64.png')
    Image.fromarray(chart).save(folder/'anon-source-face-uv-64.png')
    Image.fromarray(chart).resize((512,512),Image.Resampling.NEAREST).save(folder/'anon-source-face-uv-grid-512.png')
    Image.fromarray(sample_mask).save(folder/'anon-source-sampling-mask.png')
    np.save(folder/'anon-uv-source-coordinate-map.npy',source_map)
    marked=src.crop((90,140,138,185)).resize((576,540),Image.Resampling.NEAREST);draw=ImageDraw.Draw(marked)
    for name,point in LANDMARKS.items():
        x,y=(point[0]-90)*12,(point[1]-140)*12;draw.ellipse((x-5,y-5,x+5,y+5),outline=(220,20,30),width=2)
        draw.text((x+8,y-10),name,fill=(10,20,30))
    marked.save(folder/'anon-source-landmarks.png')
    comparison=Image.new('RGB',(1536,552),(237,239,242))
    crop=src.crop((90,140,138,185)).resize((512,512),Image.Resampling.NEAREST)
    for i,(im,label) in enumerate(((crop,'Original user pixels'),(Image.fromarray(corrected).resize((512,512),Image.Resampling.NEAREST),'Roll and visible-midline correction'),(Image.fromarray(chart).resize((512,512),Image.Resampling.NEAREST),'Sampled features / skin only UV'))):
        comparison.paste(im,(i*512,32));ImageDraw.Draw(comparison).text((i*512+8,9),label,fill=(25,31,40))
    comparison.save(folder/'anon-source-to-uv-comparison.png')
    record={'source':str(SOURCE.relative_to(ROOT)),'source_sha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
            'source_size':list(src.size),'landmarks_source_xy':LANDMARKS,'output_logical_pixels':[64,64],
            'eye_roll_correction_degrees':math.degrees(theta),'source_to_uv_matrix':transform.tolist(),
            'source_anchor':left.tolist(),'uv_anchor':origin.tolist(),'visible_midline_row_offsets':knots,
            'face_polygon_source_xy':FACE_POLYGON,'feature_polygons_source_xy':FEATURE_POLYGONS,
            'skin_fill_rgb':skin,'sampling':'nearest source pixel; integer source map retained',
            'mask_rule':'Tight source eye and mouth polygons only; all other pixels use median source skin. No nose line or cheek/hair-shadow painting.',
            'local_eye_projection_scales':{'viewer_left':[1.05,1.02],'viewer_right':[.96,.98]},
            'information_limit':'Source face is only tens of pixels; no invented high-resolution face detail.',
            'a6_face_drawing_called':False,'sampled_uv_pixels':int(np.count_nonzero(sample_mask))}
    save_json(folder/'sampling-record.json',record)
    print(json.dumps({'uv_comparison':str(folder/'anon-source-to-uv-comparison.png'),'roll_degrees':math.degrees(theta),'sampled_pixels':record['sampled_uv_pixels'],'skin_fill':skin},ensure_ascii=False),flush=True)
    return Image.fromarray(chart),skin,record

def prototype(chart,skin,record):
    prior=ROOT/'art/robot-block-models/iterations/a6-first-complete'
    old=json.loads((prior/'models/anon.json').read_text(encoding='utf8'))
    parts=copy.deepcopy([p for p in old['parts'] if p['name'] not in ('head','neck') and not p['name'].startswith('head_')])
    atlas=Image.open(prior/'textures/anon.png').convert('RGB');atlas.paste(chart,(0,256))
    colors={'skin':skin,'hair':(235,186,205),'inner':(216,165,186),'clip':(47,43,52)}
    swatches={name:(144+index*8,256) for index,name in enumerate(colors)}
    ap=ImageDraw.Draw(atlas)
    for name,(x,y) in swatches.items():ap.rectangle((x,y,x+3,y+3),fill=colors[name])
    def part(name,pivot,parent):
        value={'name':name,'parent':parent,'pivot':list(pivot),'rotation':[0,0,0],'cubes':[]};parts.append(value);return value
    neck=part('neck',(0,23.2,.4),'body');head=part('head',(0,24,.2),'neck')
    def box(p,name,center,size,mat='skin',rot=(0,0,0),face=False):
        x,y=swatches[mat];a=[c-t/2 for c,t in zip(center,size)];b=[c+t/2 for c,t in zip(center,size)]
        uv={f:[x,y,x+1,y+1] for f in pipeline.FACE_IDS}
        if face:uv['north']=[(2.7-b[0])*64/5.4,256+(28.35-b[1])*64/5.4,(2.7-a[0])*64/5.4,256+(28.35-a[1])*64/5.4]
        p['cubes'].append({'name':name,'from':a,'to':b,'origin':list(center),'rotation':list(rot),'uv':uv,'material':mat})
    box(neck,'short_recessed_neck',(0,23.50,.56),(.92,1.50,.82))
    box(head,'face_hero_sampled',(0,26.10,-1.935),(4.06,4.40,.43),face=True)
    box(head,'short_integrated_chin',(0,23.81,-1.93),(1.76,.38,.43))
    for sign in (-1,1):box(head,'shallow_edge_'+str(sign),(sign*1.42,23.82,-1.93),(1.10,.35,.43),rot=(0,0,sign*5))
    box(head,'continuous_back_support',(0,26,.12),(3.90,4.60,3.95))
    box(head,'jaw_neck_connection',(0,24.43,-.15),(3.8,1.23,3.30))
    hair=part('head_hair',(0,28,.2),'head')
    for name,c,z,r in [
      ('covered_crown',(0,28.32,.1),(4.9,1.45,4.40),(0,0,0)),
      ('upper_crown',(0,29.26,.05),(3.20,.75,3.35),(0,0,0)),
      ('left_top',(1.84,29.0,.05),(1.28,.68,3.40),(0,0,-18)),
      ('right_top',(-1.84,29.0,.05),(1.28,.68,3.40),(0,0,18)),
      ('fringe_root',(0,28.54,-2.03),(4.86,1.17,.75),(0,0,0)),
      ('source_side_sweep',(.05,28.60,-2.22),(3.60,1.35,.40),(0,0,-12)),
      ('clip_side_fringe',(-1.96,28.25,-2.20),(.92,1.75,.46),(0,0,-6)),
    ]:box(hair,name,c,z,'hair',r)
    for sign in (-1,1):
        box(hair,'side_cover_'+str(sign),(sign*2.26,26.87,.20),(.92,4.75,3.66),'inner',(0,-sign*10,0))
        box(hair,'front_side_lock_'+str(sign),(sign*2.18,25.91,-1.89),(.61,4.63,.76),'hair',(0,sign*5,-sign*3))
        box(hair,'long_side_'+str(sign),(sign*2.67,22.20,.70),(.94,10.70,1.81),'hair',(0,-sign*17,-sign*4))
    for k,(x,y,z,w,length,r) in enumerate([(-1.68,22.48,2.21,1.46,12.78,(-2,-13,-3)),(-.34,21.98,2.71,1.43,13.6,(2,-3,1)),(1.03,22.29,2.56,1.44,13.02,(4,10,4)),(2.08,22.75,1.89,1.1,11.85,(3,22,5))]):
        box(hair,'rear_mass_'+str(k),(x,y,z),(w,length,1.27),'hair',r)
    for k,yy in enumerate((28.65,28.25)):box(hair,'double_clip_'+str(k),(-1.99,yy,-2.54),(.64,.10,.20),'clip',(0,0,-6))
    texture=OUT/'textures/anon.png';texture.parent.mkdir(parents=True,exist_ok=True);atlas.save(texture)
    model={'id':'anon','name':'原图像素映射爱音研究原型','texture_size':[512,512],'texture_path':str(texture),'parts':parts,'design_revision':'REFERENCE_UV_R1',
      'face_chart':{'uv_rect':[0,256,64,320],'world_x':[-2.7,2.7],'world_y':[22.95,28.35],'north_camera_convention':True},'sampling_record':record}
    png=texture.read_bytes();bb=pipeline.project(model,png);check=pipeline.inspect(model,bb,png)
    check['body_parts_preserved']=[p for p in parts if p['name'] not in ('head','neck') and not p['name'].startswith('head_')]==[p for p in old['parts'] if p['name'] not in ('head','neck') and not p['name'].startswith('head_')]
    for folder,value,name in [('models',model,'anon.json'),('editable',bb,'anon.bbmodel'),('checks',check,'anon.json')]:save_json(OUT/folder/name,value)
    transformed=pipeline.world_vertices(bb);selected=[e for e in bb['elements'] if e['from'][1]>=23];v=np.concatenate([transformed[e['uuid']] for e in selected]);low=v.min(0);high=v.max(0);center=(low+high)/2;span=float(max(high-low)*1.1)
    preview=OUT/'preview';preview.mkdir(exist_ok=True);sheet=Image.new('RGBA',(2550,1700),(230,233,238,255))
    for k,(name,angle) in enumerate((('front',0),('left-front',math.pi/4),('right-front',-math.pi/4),('left-side',math.pi/2),('right-side',-math.pi/2),('back',math.pi))):
        im=pipeline.render(bb,atlas,angle,(850,850),(center,span));im.save(preview/f'anon-head-{name}.png');sheet.paste(im,(k%3*850,k//3*850))
    sheet.save(preview/'anon-head-six-views.png');pipeline.render(bb,atlas,0,(220,260),(center,span)).save(preview/'anon-normal-size.png')
    save_json(OUT/'generation-record.json',{'scope':['anon'],'revision':'REFERENCE_UV_R1','source_module':str(Path(__file__).relative_to(ROOT)),'source_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'a6_face_drawing_called':False,'check':check})
    print(json.dumps(check,ensure_ascii=False),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--uv-only',action='store_true');args=parser.parse_args()
    chart,skin,record=face_study()
    if not args.uv_only:prototype(chart,skin,record)
