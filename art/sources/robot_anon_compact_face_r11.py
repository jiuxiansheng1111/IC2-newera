"""R11 single compact-face revision over read-only R9; real native MCP request generation."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,shutil
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[2]
WORK=ROOT/'art/robot-mcp-workspace/characters/anon-compact-face-r11'
BASE=WORK.parent/'anon-proportion-face-r9/anon-proportion-face-r9.bbmodel'
MODEL='anon-compact-face-r11.bbmodel'
GROUPS={'face_skin_structure','eye_geometry_left','eye_geometry_right','brow_geometry','smile_geometry'}
s=importlib.util.spec_from_file_location('own_mcp',ROOT/'art/sources/robot_layered_head.py');h=importlib.util.module_from_spec(s);s.loader.exec_module(h);h.WORK=WORK
def save(n,v):(WORK/n).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def ids(d):
 names={g['uuid']:g['name'] for g in d['groups']};out=set()
 def walk(ns,on=False):
  for n in ns:
   if isinstance(n,str):
    if on:out.add(n)
   else:walk(n.get('children',[]),on or names[n['uuid']] in GROUPS)
 walk(d['outliner']);return out
def box(n,c,size,tex,uv,parent,rot=None):
 return {'op':'add_cube','name':'r11_'+n,'from':[round(a-b/2,6) for a,b in zip(c,size)],'to':[round(a+b/2,6) for a,b in zip(c,size)],'origin':c,'rotation':rot or [0,0,0],'parent':parent,'texture':tex,'box_uv':False,'faces':{f:{'texture':tex,'uv':uv} for f in ('north','south','east','west','up','down')}}
def geometry(d):
 ops=[]
 def skin(n,c,sz,rot=None):ops.append(box(n,c,sz,2,[16.5,.5,19.5,3.5],'face_skin_structure',rot))
 skin('face_main',[0,23.16,-2.95],[5.3,4.4,1.0]);skin('face_inner',[0,23.3,-.05],[4.85,4,4.85])
 skin('chin_short',[0,20.93,-2.91],[4.10,.44,1.08])
 for sign in (-1,1):skin('jaw_'+str(sign),[sign*2.10,21.12,-2.91],[1.1,.5,1.08],[0,0,sign*10])
 connector=next(e for e in d['elements'] if e['name']=='face_skin_hidden_connector')
 ops.append({'op':'add_cube','name':'r11_hidden_connector','from':connector['from'],'to':connector['to'],'origin':connector['origin'],'parent':'face_skin_structure','box_uv':False,'texture':2,'faces':connector['faces']})
 for side,sign in (('left',1),('right',-1)):
  cx=sign*1.15;p='eye_geometry_'+side
  def eye(n,c,sz,color):
   i={'white':1,'dark':2,'iris':3,'light':4,'pupil':5,'shine':7,'lash':0,'brow':8}[color]
   ops.append(box(n+'_'+side,c,sz,5,[i*4+.5,.5,i*4+3.5,3.5],p if color!='brow' else 'brow_geometry'))
  eye('white_mid',[cx,23.2,-3.469],[1.25,.62,.026],'white');eye('white_up',[cx,23.58,-3.469],[1.03,.14,.026],'white');eye('white_low',[cx,22.84,-3.469],[.92,.1,.026],'white')
  eye('iris',[cx,23.2,-3.493],[.60,.74,.02],'iris');eye('iris_top',[cx,23.47,-3.514],[.60,.21,.018],'dark');eye('iris_low',[cx,22.95,-3.514],[.50,.17,.018],'light')
  eye('pupil',[cx,23.22,-3.534],[.20,.43,.018],'pupil');eye('shine',[cx+.13,23.43,-3.552],[.11,.11,.014],'shine')
  for j,dy in enumerate((0,.05,.08,.05,0)):eye('lid_'+str(j),[cx+sign*(j-2)*.25,23.55+dy,-3.527],[.255,.056,.024],'lash')
  for j,dy in enumerate((0,.012,.020,.012,0)):eye('brow_'+str(j),[cx+sign*(j-2)*.16,23.88+dy,-3.469],[.164,.041,.024],'brow')
 ops.append(box('mouth_single',[0,22.1,-3.464],[.45,.07,.018],7,[4.5,.5,7.5,3.5],'smile_geometry'))
 return ops
def prepare():
 WORK.mkdir(parents=True,exist_ok=True)
 if (WORK/MODEL).exists():raise SystemExit('Existing model is not overwritten.')
 shutil.copy2(BASE,WORK/MODEL);d=json.loads(BASE.read_text(encoding='utf-8'))
 ops=[{'op':'remove_node','target':u} for u in sorted(ids(d))]+geometry(d)+[{'op':'set_model_properties','name':'千早爱音｜R11收小脸与协调五官原型'}]
 save('native-operations.json',ops)
 (WORK/'plan.md').write_text('R11只从R9恢复已保留发型/身形，收小连续肤面至宽5.3/主高4.4，下缘接领口；眼眉嘴中心整体重排，不改任何头发/身体/组/pivot/原8atlas。眼为新连贯少量长方体、纵向蓝灰虹膜，嘴严格1横矩形。一次原生MCP小批修改，self_test=false，不游戏/测试/构建/安装。\n',encoding='utf-8')
 print(json.dumps({'operations':len(ops)}),flush=True)
def frozen():
 a=json.loads(BASE.read_text(encoding='utf-8'));b=json.loads((WORK/MODEL).read_text(encoding='utf-8'))
 def select(d):
  mutable=ids(d);return {'elements':[e for e in d['elements'] if e['uuid'] not in mutable],'groups':d['groups'],'textures':d['textures'],'resolution':d['resolution']}
 same=select(a)==select(b)
 def tree(ns):return [dict(n,children=tree(n.get('children',[]))) for n in ns if isinstance(n,dict)]
 hierarchy=tree(a['outliner'])==tree(b['outliner'])
 v={'identical':same and hierarchy,'frozen_elements':len(select(a)['elements']),'all_groups_pivots_parent_hierarchy_identical':hierarchy and a['groups']==b['groups'],'original_eight_atlases_identical':a['textures']==b['textures'],'model_sha256':hashlib.sha256((WORK/MODEL).read_bytes()).hexdigest(),'baseline_sha256':hashlib.sha256(BASE.read_bytes()).hexdigest()};save('frozen-r9-record.json',v)
 if not v['identical']:raise SystemExit('Frozen R9 fields differ.')
 return v
def frame(label,source):
 file=label+'-face-inspection.bbmodel';shutil.copy2(source,WORK/file);d=json.loads((WORK/file).read_text(encoding='utf-8'));names={g['uuid']:g['name'] for g in d['groups']}
 ops=[{'op':'update_node','target':'head','parent':None},{'op':'remove_node','target':'root'}]
 # Keep the actual R9 upper hair coverage in both copies; only lower hair lengths are omitted.
 def hairids(ns,on=False):
  found=set()
  for n in ns:
   if isinstance(n,str):
    if on:found.add(n)
   else:found|=hairids(n.get('children',[]),on or names[n['uuid']] in {'rear_long_layers','side_long_layers','cheek_and_temple_locks'})
  return found
 hair=hairids(d['outliner']);ops += [{'op':'remove_node','target':e['uuid']} for e in d['elements'] if e['uuid'] in hair and e.get('origin',[0,100,0])[1]<21]
 for j,(lo,hi) in enumerate((([-6.5,19,-5],[-6.48,19.02,-4.98]),([6.48,29.98,5.48],[6.5,30,5.5]))):ops.append({'op':'add_cube','name':'transparent_frame_'+str(j),'from':lo,'to':hi,'parent':'head','texture':3,'box_uv':False,'faces':{f:{'texture':3,'uv':[40.25,40.25,40.75,40.75]} for f in ('north','south','east','west','up','down')}})
 info=h.mcp('bbmodel_info',{'file':file},label+'-face-before');h.mcp('bbmodel_edit',{'file':file,'expected_revision':info['revision'],'operations':ops},label+'-face-frame')
 return file,h.mcp('bbmodel_info',{'file':file},label+'-face-info')['bounds']
def finish():
 f=frozen();info=h.mcp('bbmodel_info',{'file':MODEL},'r11-main-info');static=h.mcp('bbmodel_validate',{'file':MODEL,'self_test':False,'min_thickness':.01},'r11-static');bounds={}
 for label,source in (('r11',WORK/MODEL),('r9',BASE)):
  file,bounds[label]=frame(label,source)
  for view in ('front','three-quarter'):h.render(file,view,f'{label}-face-{view}-studio.png',640,label+'-face-'+view,'studio')
  if label=='r11':
   h.render(MODEL,'front','r11-full-front-studio.png',640,'r11-full-front','studio');h.render(MODEL,'three-quarter','r11-normal-192.png',192,'r11-normal-192')
 bounds['identical']=bounds['r9']==bounds['r11'];save('comparison-camera-record.json',bounds)
 for view in ('front','three-quarter'):
  canvas=Image.new('RGB',(1280,688),'#202329');draw=ImageDraw.Draw(canvas);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
  for x,label,text in ((0,'r9','R9：上一版（同世界边界）'),(640,'r11','R11：收小脸与五官，待人工验收')):
   draw.text((x+12,10),text,font=font,fill='white')
   with Image.open(WORK/f'{label}-face-{view}-studio.png') as im:canvas.paste(im.convert('RGB'),(x,48))
  canvas.save(WORK/f'r9-r11-face-{view}-comparison.png')
 save('facts.json',{'status':'single-pass compact face prototype; root/user review pending','counts':info['counts'],'freeze':f,'static':static['summary'],'self_test':False,'main_sha256':f['model_sha256']})
 (WORK/'README.md').write_text('# 爱音R11：恢复R9发型/身形，单轮收小脸\n\n主工程anon-compact-face-r11.bbmodel；脸主块宽5.3/高4.4，浅颌接领口。双眼中心±1.15/y23.2，轻缓黑眉、纵向蓝灰虹膜，小嘴严格1横矩形位于y22.1。只改脸/眼/眉/嘴，337件R9头发与非head、全部组/父变换/pivot/原8atlas完全冻结。\n\n正脸/3q为r11-face-*-studio.png，共同边界对照r9-r11-face-*-comparison.png（1280×688）；完整人物r11-full-front-studio.png和r11-normal-192.png。实际原生MCP+expected_revision，参数与回执齐，静态self_test=false；没有运行测试/游戏/构建/安装。仅一次集中改，根与用户外观待判断；R9原发型块接缝按回退方向保留。\n\nSHA256：`'+f['model_sha256']+'`\n',encoding='utf-8')
 print(json.dumps({'sha256':f['model_sha256'],'counts':info['counts'],'static':static['summary']}),flush=True)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--quota-checked',action='store_true');p.add_argument('--prepare',action='store_true');p.add_argument('--finish',action='store_true');a=p.parse_args()
 if not a.quota_checked:raise SystemExit('Read actual quota first.')
 if a.prepare:prepare()
 elif a.finish:finish()
