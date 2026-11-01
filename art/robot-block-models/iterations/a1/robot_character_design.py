"""Hand-authored native cuboid character design. No old geometry or textures are read."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'robot-block-design'
CHARACTERS = {'anon':'千早爱音', 'sakiko':'丰川祥子'}
FACES = ('north','south','east','west','up','down')

def rgb(h): return tuple(bytes.fromhex(h.lstrip('#')))
def tone(c,n): return tuple(max(0,min(255,x+n)) for x in c)

class Design:
    def __init__(self, cid):
        self.cid=cid; self.im=Image.new('RGB',(512,512),(240,231,219)); self.tiles={}; self.parts=[]
    def tile(self,name,color,kind='cloth'):
        index=len(self.tiles); x=(index%8)*64; y=(index//8)*64
        c=rgb(color); t=Image.new('RGB',(64,64),c); d=ImageDraw.Draw(t)
        if kind=='hair':
            d.rectangle((0,0,5,63),fill=tone(c,-8)); d.rectangle((48,0,63,63),fill=tone(c,-11))
            for a,b in [(14,17),(26,27),(39,41)]: d.rectangle((a,4,b,58),fill=tone(c,5))
            d.rectangle((6,0,47,5),fill=tone(c,7))
        elif kind=='cloth':
            d.rectangle((0,0,3,63),fill=tone(c,-9)); d.rectangle((60,0,63,63),fill=tone(c,-7))
            d.rectangle((4,0,59,2),fill=tone(c,5)); d.rectangle((4,61,59,63),fill=tone(c,-7))
        elif kind=='plaid':
            for v in (6,30,54):
                d.rectangle((v,0,v+5,63),fill=tone(c,-22));d.rectangle((0,v,63,v+5),fill=tone(c,-18))
                d.line((v+8,0,v+8,63),fill=tone(c,22));d.line((0,v+8,63,v+8),fill=tone(c,19))
        elif kind=='shoe':
            d.rectangle((0,49,63,63),fill=tone(c,-9));d.rectangle((10,10,48,13),fill=tone(c,16))
            for v in (23,30,37):d.rectangle((23,v,40,v+1),fill=tone(c,29))
        self.im.paste(t,(x,y)); self.tiles[name]=[x,y,x+64,y+64]; return name
    def part(self,name,pivot,parent=None):
        p={'name':name,'parent':parent,'pivot':list(pivot),'rotation':[0,0,0],'cubes':[]};self.parts.append(p);return p
    def cube(self,p,name,a,b,mat,front=None):
        uv={f:list(self.tiles[mat]) for f in FACES}
        if front: uv['north']=list(self.tiles[front])
        p['cubes'].append({'name':name,'from':list(a),'to':list(b),'uv':uv,'material':mat})
    def face(self,skin,hair,iris,expression):
        self.tile('face',skin,'flat'); x,y,_,_=self.tiles['face'];t=Image.new('RGB',(64,64),rgb(skin)); d=ImageDraw.Draw(t)
        s=rgb(skin); ink=tone(rgb(hair),-65); ir=rgb(iris)
        d.rectangle((0,0,3,63),fill=tone(s,-8));d.rectangle((60,0,63,63),fill=tone(s,-8))
        d.rectangle((4,60,59,63),fill=tone(s,-4))
        # Eyes are pixel-painted individually; eyebrow trajectories and mouths differ.
        for left in (True,False):
            a=13 if left else 38
            if expression=='bright':
                lid=[(a,33),(a+2,31),(a+10,31),(a+13,33)]
                brow=[(a,26),(a+3,25),(a+10,26)]
                bottom=40
            else:
                lid=[(a,33),(a+3,32),(a+10,32),(a+13,34)]
                brow=[(a,27),(a+4,26),(a+11,27)]
                bottom=40
            d.polygon(lid+[(a+12,bottom),(a+3,bottom)],fill=(255,249,244))
            d.rectangle((a+5,33,a+10,bottom-1),fill=ir)
            d.rectangle((a+5,33,a+10,35),fill=tone(ir,-30))
            d.rectangle((a+7,34,a+8,38),fill=tone(ir,-75))
            d.rectangle((a+5,38,a+10,39),fill=tone(ir,19))
            d.point((a+6,34),fill=(255,255,249));d.point((a+9,38),fill=tone(ir,45))
            d.line(lid,fill=ink,width=1);d.line([(a+2,bottom),(a+10,bottom)],fill=tone(s,-31),width=1)
            d.line(brow,fill=tone(rgb(hair),-32),width=1)
        d.line([(32,43),(32,45),(33,45)],fill=tone(s,-37),width=1)
        d.point((31,44),fill=tone(s,9))
        if expression=='bright':
            d.polygon([(27,49),(29,50),(36,49),(35,53),(31,55),(28,53)],fill=(118,62,70))
            d.line((29,50,34,50),fill=(255,244,227));d.line((30,53,33,53),fill=(221,129,137))
        else:
            d.line([(28,50),(30,51),(34,51),(36,50)],fill=(161,100,98),width=1)
            d.line((30,52,34,52),fill=tone(s,-10))
        for a in (10,49):d.rectangle((a,44,a+4,45),fill=(245,196,185))
        self.im.paste(t,(x,y))
    def finish(self):
        ROOT.mkdir(parents=True,exist_ok=True);path=ROOT/(self.cid+'-atlas.png');self.im.save(path)
        return {'id':self.cid,'name':CHARACTERS[self.cid],'texture_size':[512,512],'texture_path':str(path),'parts':self.parts,'design_revision':'A1'}

def base(cid):
    d=Design(cid);sak=cid=='sakiko'
    hair='8594b9' if sak else 'df9db8'; skin='f6ddcf'
    for name,col,kind in [('skin',skin,'flat'),('hair',hair,'hair'),('hair_shadow','637499' if sak else 'c682a0','hair'),('hair_light','9daacc' if sak else 'ecb2c9','hair'),('dark','262932','cloth'),('white','eeeee6','cloth'),('gold','d6bc63','flat'),('shoe','303038','shoe'),('sock','f3f0e8','cloth'),('top','8996ab' if sak else '326787','cloth'),('skirt','53695e' if sak else 'ece9db','plaid' if sak else 'cloth'),('skirt_shadow','44594f' if sak else 'd6d4c8','cloth'),('tie','303e5c','cloth')]:d.tile(name,col,kind)
    d.face(skin,hair,'bb8b3f' if sak else '638393','soft' if sak else 'bright')
    body=d.part('body',(0,16,0));neck=d.part('neck',(0,23.1,0),'body');head=d.part('head',(0,24,0),'neck')
    d.cube(neck,'neck',(-1.05,22.6,-.9),(1.05,24.5,1.05),'skin')
    d.cube(head,'square_head',(-3.5,24,-2.85),(3.5,31,2.85),'skin','face')
    # One connected top cap; side/back sheets have intentionally different heights.
    d.cube(head,'hair_crown',(-3.72,30.55,-3.05),(3.72,31.55,3.12),'hair')
    d.cube(head,'back_head',(-3.7,24.7,2.85),(3.7,30.55,3.35),'hair')
    for sign,side in [(-1,'right'),(1,'left')]:
        a,b=(-3.78,-3.5) if sign<0 else (3.5,3.78)
        d.cube(head,side+'_temple',(a,27.1,-2.85),(b,30.55,2.86),'hair')
        a,b=(-3.85,-3.49) if sign<0 else (3.49,3.85)
        d.cube(head,side+'_ear',(a,25.65,-.85),(b,27.3,.4),'skin')
    for sign,side in [(-1,'right'),(1,'left')]:
        x=sign*1.35;leg=d.part(side+'_thigh',(x,13.2,0),'body');shin=d.part(side+'_shin',(x,7.4,0),side+'_thigh')
        d.cube(leg,side+'_thigh_skin',(x-1.06,7.4,-1.0),(x+1.06,14.1,1.05),'skin')
        d.cube(shin,side+'_calf',(x-.96,1.0,-.9),(x+.96,7.4,.98),'skin')
        if sak:d.cube(shin,side+'_knee_sock',(x-.99,1.1,-.93),(x+.99,7.1,1.01),'sock')
        else:d.cube(shin,side+'_ankle_sock',(x-1,1.15,-.94),(x+1,3.8,1.02),'dark')
        d.cube(shin,side+'_shoe',(x-1.08,0,-1.65),(x+1.08,1.55,1.12),'shoe')
        if not sak:d.cube(shin,side+'_boot_shaft',(x-1.04,1.5,-1),(x+1.04,3.45,1.1),'shoe')
    return d,body,head

def arms(d,body,sak):
    for sign,side in [(-1,'right'),(1,'left')]:
        x=sign*4.25;p=d.part(side+'_upper_arm',(x,22.25,0),'body');q=d.part(side+'_forearm',(x,18.5,0),p['name'])
        d.cube(p,side+'_upper_arm',(x-1.05,18.5,-1.2),(x+1.05,22.35,1.3),'top')
        d.cube(q,side+'_forearm',(x-.91,15.0,-1.02),(x+.91,18.5,1.12),'top' if sak else 'skin')
        if sak:d.cube(q,side+'_cuff',(x-.96,15.0,-1.07),(x+.96,15.65,1.17),'top')
        d.cube(q,side+'_hand',(x-.87,13.5,-1),(x+.87,15.05,1.07),'skin')

def anon():
    d,b,h=base('anon');arms(d,b,False)
    d.cube(b,'midriff',(-2.85,15.5,-1.45),(2.85,19,1.4),'skin')
    d.cube(b,'cropped_blue_jacket',(-3.15,18.45,-1.6),(3.15,22.5,1.55),'top')
    d.cube(b,'white_neck_insert',(-1.0,21.35,-1.72),(1.0,23,-1.5),'white')
    d.cube(b,'short_stand_collar',(-1.2,22.25,-1.75),(1.2,23.15,-1.48),'top')
    d.cube(b,'jacket_seam',(-.08,18.5,-1.7),(.08,22.15,-1.61),'skirt_shadow')
    for yy in (19.1,20.3,21.4):d.cube(b,'jacket_button_'+str(yy),(.31,yy,-1.8),(.48,yy+.18,-1.69),'gold')
    d.cube(b,'yellow_tie_upper',(1.6,21.2,-1.82),(2.05,22.7,-1.61),'gold')
    d.cube(b,'yellow_tie_lower',(1.28,20.0,-1.83),(1.9,21.5,-1.61),'gold')
    d.cube(b,'white_waistband',(-3.04,15.5,-1.6),(3.04,16.1,1.62),'white')
    # Short front and three deliberately broad, cascading rear tiers.
    for i,(y0,y1,w,z0,z1) in enumerate([(13.4,15.5,3.35,-1.9,1.95),(11.2,13.4,3.75,-2.15,2.25),(9.5,11.2,4.1,-2.35,2.6)]):
        d.cube(b,'front_skirt_tier_'+str(i),(-w,y0,z0),(w,y1,z1),'skirt')
    for i,(y0,y1,w,z0,z1) in enumerate([(7.3,9.5,4.2,.8,2.95),(5.5,7.3,3.95,1.0,3.1),(4.4,5.5,3.3,1.3,3.05)]):
        d.cube(b,'rear_train_'+str(i),(-w,y0,z0),(w,y1,z1),'skirt')
    for x in (-2.8,-1.4,1.3,2.75):d.cube(b,'front_fold_'+str(x),(x,9.6,-2.43),(x+.19,11.15,-2.34),'skirt_shadow')
    # Side-sweep is an asymmetric continuous staircase, not repeated vertical teeth.
    for i,(x0,x1,y0,y1,z) in enumerate([(-3.55,-1.8,28.75,30.55,-3.17),(-1.8,-.1,29.05,30.55,-3.22),(-.1,1.35,29.55,30.55,-3.2),(1.35,2.45,29.1,30.55,-3.15),(2.45,3.52,28.2,30.55,-3.14)]):
        d.cube(h,'swept_fringe_'+str(i),(x0,y0,z),(x1,y1,-2.86),'hair')
    for sign,side in [(-1,'right'),(1,'left')]:
        x0,x1=(-4.03,-3.45) if sign<0 else (3.45,4.03)
        d.cube(h,side+'_face_frame',(x0,23.1,-2.35),(x1,29.5,-1.2),'hair_light')
        x0,x1=(-4.03,-3.35) if sign<0 else (3.35,4.03)
        d.cube(h,side+'_long_lock_top',(x0,19.6,.65),(x1,27.2,2.85),'hair')
        x0,x1=(-3.85,-3.13) if sign<0 else (3.13,3.85)
        d.cube(h,side+'_long_lock_end',(x0,16.1,1.0),(x1,19.6,2.75),'hair_light')
    d.cube(h,'back_length_upper',(-3.55,20.1,2.5),(3.55,24.7,3.35),'hair')
    d.cube(h,'back_length_middle',(-3.32,17.1,2.55),(3.32,20.1,3.45),'hair')
    d.cube(h,'back_length_tip',(-2.85,15.6,2.55),(2.85,17.1,3.35),'hair')
    for yy in (28.7,29.15):d.cube(h,'double_hair_clip_'+str(yy),(2.52,yy,-3.28),(3.34,yy+.16,-3.13),'dark')
    return d.finish()

def sakiko():
    d,b,h=base('sakiko');arms(d,b,True)
    d.cube(b,'cardigan_torso',(-3.13,15.9,-1.61),(3.13,22.5,1.52),'top')
    d.cube(b,'shirt_insert',(-1.45,20.2,-1.76),(1.45,22.7,-1.6),'white')
    for sign in (-1,1):
        a,c=(-1.4,-.35) if sign<0 else (.35,1.4)
        d.cube(b,'white_collar_'+str(sign),(a,22.0,-1.85),(c,22.7,-1.74),'white')
    d.cube(b,'tie_knot',(-.3,21.7,-1.96),(.3,22.3,-1.76),'tie')
    d.cube(b,'tie_length',(-.27,19.9,-1.91),(.27,21.75,-1.75),'tie')
    for yy in (16.65,17.8,18.95):d.cube(b,'cardigan_button_'+str(yy),(-.12,yy,-1.76),(.12,yy+.23,-1.61),'dark')
    d.cube(b,'cardigan_hem',(-3.17,15.7,-1.67),(3.17,16.25,1.57),'top')
    for i,(y0,y1,w,depth) in enumerate([(13.3,15.7,3.35,1.85),(11.0,13.3,3.65,2.1),(9.6,11,3.95,2.3)]):
        d.cube(b,'green_plaid_skirt_'+str(i),(-w,y0,-depth),(w,y1,depth),'skirt')
    for x in (-3,-1.55,1.45,2.9):d.cube(b,'pleat_'+str(x),(x,9.65,-2.38),(x+.17,10.98,-2.29),'skirt_shadow')
    for i,(x0,x1,y0) in enumerate([(-3.5,-2.2,28.5),(-2.2,-.8,28.85),(-.8,.6,29.3),(.6,2.0,29.0),(2.0,3.5,28.5)]):
        d.cube(h,'soft_fringe_'+str(i),(x0,y0,-3.18),(x1,30.55,-2.86),'hair')
    # Paired braids use four connected broad alternating lobes at the side of the head.
    for sign,side in [(-1,'right'),(1,'left')]:
        for i in range(4):
            c=sign*(3.75+(0.14 if i%2 else 0)); y=28.35-i*.72
            d.cube(h,side+'_braid_'+str(i),(c-.42,y-.72,-1.2),(c+.42,y+.12,.35),'hair_light' if i%2 else 'hair')
        c=sign*3.95
        d.cube(h,side+'_black_knot',(c-.42,24.6,-.75),(c+.42,25.25,.5),'dark')
        d.cube(h,side+'_ribbon_upper',(c-.7,24.95,-.55),(c+.7,25.4,.24),'dark')
        d.cube(h,side+'_ribbon_tail',(c-.2,23.85,-.45),(c+.2,24.65,.02),'dark')
        # Deliberate inward/outward change supplies a squared S curl, all connected.
        for i,(cx,y0,y1,z0,z1,w) in enumerate([(3.9,21.5,24.7,.4,1.7,.8),(4.12,19.0,21.5,.7,2.0,.85),(3.73,17.3,19.0,.95,2.05,1.0),(3.35,16.8,17.65,1.1,2.15,.85)]):
            c=sign*cx;d.cube(h,side+'_curl_'+str(i),(c-w/2,y0,z0),(c+w/2,y1,z1),'hair')
    d.cube(h,'back_hair_upper',(-3.45,22.7,2.7),(3.45,24.7,3.32),'hair')
    d.cube(h,'back_hair_lower',(-3.2,21.4,2.75),(3.2,22.7,3.35),'hair')
    return d.finish()

def build_character(cid):
    if cid=='anon':return anon()
    if cid=='sakiko':return sakiko()
    raise ValueError('Character has not yet been individually designed: '+cid)

if __name__=='__main__':
    import json
    for cid in CHARACTERS:
        result=build_character(cid)
        (ROOT/(cid+'-design.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print(cid, sum(len(p['cubes']) for p in result['parts']), result['texture_path'])
