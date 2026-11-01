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
            d.rectangle((0,0,4,63),fill=tone(c,-10)); d.rectangle((53,0,63,63),fill=tone(c,-8))
            d.polygon([(10,0),(26,0),(31,18),(27,41),(24,63),(16,63),(18,38),(17,15)],fill=tone(c,7))
            d.polygon([(34,0),(41,0),(44,30),(39,63),(34,63),(38,30)],fill=tone(c,3))
            d.line([(7,0),(11,24),(8,63)],fill=tone(c,-5),width=1)
            d.line([(47,0),(50,30),(46,63)],fill=tone(c,-5),width=1)
        elif kind=='sweep':
            for k,n in [(-17,-7),(1,8),(17,-5),(31,4),(47,-8)]:
                d.polygon([(k,0),(k+6,0),(k+32,63),(k+26,63)],fill=tone(c,n))
        elif kind=='pleat':
            d.rectangle((0,0,8,63),fill=tone(c,-15));d.rectangle((9,0,13,63),fill=tone(c,-7))
            d.rectangle((44,0,48,63),fill=tone(c,5));d.rectangle((49,0,63,63),fill=tone(c,-5))
        elif kind=='cloth':
            d.rectangle((0,0,3,63),fill=tone(c,-9)); d.rectangle((60,0,63,63),fill=tone(c,-7))
            d.rectangle((4,0,59,2),fill=tone(c,5)); d.rectangle((4,61,59,63),fill=tone(c,-7))
        elif kind=='plaid':
            for v in range(0,64,12):
                d.rectangle((v,0,v+2,63),fill=tone(c,-20));d.rectangle((0,v,63,v+2),fill=tone(c,-16))
                d.line((v+6,0,v+6,63),fill=tone(c,15));d.line((0,v+6,63,v+6),fill=tone(c,13))
        elif kind=='shoe':
            d.rectangle((0,49,63,63),fill=tone(c,-9));d.rectangle((10,10,48,13),fill=tone(c,16))
            for v in (23,30,37):d.rectangle((23,v,40,v+1),fill=tone(c,29))
        self.im.paste(t,(x,y)); self.tiles[name]=[x,y,x+64,y+64]; return name
    def part(self,name,pivot,parent=None):
        p={'name':name,'parent':parent,'pivot':list(pivot),'rotation':[0,0,0],'cubes':[]};self.parts.append(p);return p
    def cube(self,p,name,a,b,mat,front=None,continuous_face=False):
        uv={f:list(self.tiles[mat]) for f in FACES}
        tx,ty,_,_=self.tiles[mat]
        def box(u0,v0,u1,v1):
            return [tx+max(0,min(63.9,u0)),ty+max(0,min(63.9,v0)),tx+max(.1,min(64,u1)),ty+max(.1,min(64,v1))]
        if mat in ('hair','hair_shadow','hair_light','fringe'):
            # Continuous world-scale sampling, not a complete hair tile per small lock.
            v0=(31.4-b[1])*3.8;v1=(31.4-a[1])*3.8
            for f in ('north','south'):uv[f]=box((a[0]+4.9)*6.4,v0,(b[0]+4.9)*6.4,v1)
            for f in ('east','west'):uv[f]=box((a[2]+3.5)*8,v0,(b[2]+3.5)*8,v1)
        elif mat in ('skirt','pleat'):
            v0=(17.3-b[1])*4.7;v1=(17.3-a[1])*4.7
            for f in ('north','south'):uv[f]=box((a[0]+4.2)*7.2,v0,(b[0]+4.2)*7.2,v1)
            for f in ('east','west'):uv[f]=box((a[2]+3)*9,v0,(b[2]+3)*9,v1)
        if front: uv['north']=list(self.tiles[front])
        if continuous_face:
            fx,fy,_,_=self.tiles['face']
            # One face chart for the whole head. Cuboids sample their own
            # world-coordinate rectangle; none receives a repeated whole face.
            uv['north']=[fx+(a[0]+3.5)*64/7,fy+(31-b[1])*64/7,
                         fx+(b[0]+3.5)*64/7,fy+(31-a[1])*64/7]
        p['cubes'].append({'name':name,'from':list(a),'to':list(b),'uv':uv,'material':mat})
    def face(self,skin,hair,iris,expression):
        self.tile('face',skin,'flat'); x,y,_,_=self.tiles['face'];t=Image.new('RGB',(64,64),rgb(skin)); d=ImageDraw.Draw(t)
        s=rgb(skin); ink=tone(rgb(hair),-65); ir=rgb(iris)
        d.rectangle((0,0,3,63),fill=tone(s,-8));d.rectangle((60,0,63,63),fill=tone(s,-8))
        d.rectangle((4,60,59,63),fill=tone(s,-4))
        # Pixel contours differ by character; no printed black eye rectangles.
        for left in (True,False):
            a=13 if left else 38
            if expression=='bright':
                lid=[(a,34),(a+1,32),(a+4,31),(a+9,31),(a+12,33)]
                brow=[(a+1,27),(a+4,26),(a+9,26),(a+11,27)]
                bottom=38
            else:
                lid=[(a,34),(a+3,32),(a+8,32),(a+12,33)]
                brow=[(a+1,28),(a+5,27),(a+10,27)]
                bottom=39
            d.polygon(lid+[(a+10,bottom),(a+4,bottom),(a+1,bottom-2)],fill=(255,251,245))
            d.polygon([(a+6,32),(a+9,32),(a+10,34),(a+10,37),(a+8,39),(a+6,38),(a+5,36),(a+5,34)],fill=ir)
            d.line((a+6,33,a+9,33),fill=tone(ir,-29))
            d.rectangle((a+7,34,a+8,36),fill=tone(ir,-72))
            d.line((a+6,38,a+8,38),fill=tone(ir,26))
            d.point((a+6,34),fill=(255,255,249));d.point((a+9,37),fill=tone(ir,40))
            d.line(lid,fill=ink,width=1)
            # Outer lash, short lower eyelid; the entire bottom is not an outline.
            d.point((a-1 if left else a+13,33),fill=ink)
            d.line(brow,fill=tone(rgb(hair),-32),width=1)
        d.point((32,44),fill=tone(s,-29));d.point((33,45),fill=tone(s,-21))
        d.point((31,44),fill=tone(s,7))
        if expression=='bright':
            d.polygon([(28,49),(35,49),(35,50),(34,52),(30,52),(28,50)],fill=(140,76,82))
            d.line((29,49,34,49),fill=(255,240,223));d.line((30,52,34,52),fill=(202,124,128))
        else:
            d.line([(29,50),(30,51),(34,51),(35,50)],fill=(174,116,111),width=1)
            d.line((30,52,34,52),fill=tone(s,-10))
        for a in (11,49):
            d.point((a,44),fill=(244,206,194));d.point((a+2,45),fill=(244,206,194))
        self.im.paste(t,(x,y))
    def finish(self):
        ROOT.mkdir(parents=True,exist_ok=True);path=ROOT/(self.cid+'-atlas.png');self.im.save(path)
        return {'id':self.cid,'name':CHARACTERS[self.cid],'texture_size':[512,512],'texture_path':str(path),'parts':self.parts,'design_revision':'A4'}

def shaped_head(d,head,sak):
    """Connected, solid, hand-authored cuboid cross sections, Y-up.

    A broad cheek transitions into a narrower jaw and a short chin. The
    central face, cheek and temple have progressively recessed front planes.
    Visible skin is shaped in depth as well as in the front silhouette.
    """
    sections=[
        # y low/high, half-width, front, back
        (24.00,24.35,1.22,-2.12,1.42),
        (24.35,24.72,1.76,-2.38,1.70),
        (24.72,25.16,2.22,-2.57,1.93),
        (25.16,25.68,2.62,-2.72,2.12),
        (25.68,26.28,2.93,-2.82,2.28),
        (26.28,27.00,3.13,-2.87,2.40),
        (27.00,27.76,3.24,-2.88,2.48),
        (27.76,28.52,3.22,-2.85,2.51),
        (28.52,29.28,3.12,-2.79,2.48),
        (29.28,30.04,2.98,-2.68,2.37),
        (30.04,30.62,2.74,-2.49,2.16),
        (30.62,31.00,2.32,-2.20,1.89),
    ]
    for row,(yl,yh,width,front,back) in enumerate(sections):
        # Sakiko has a slightly finer lower jaw, Anon a fuller cheek.
        if sak and yl<27:width-=.10 if yl>24.35 else .04
        central=min(width*.76,2.52)
        middle=(width+central)/2
        bands=[(-width,-middle,.49),(-middle,-central,.18),
               (-central,central,0),(central,middle,.18),(middle,width,.49)]
        for col,(xl,xh,recess) in enumerate(bands):
            d.cube(head,f'face_section_{row:02d}_{col}',
                   (xl,yl,front+recess),(xh,yh,back), 'skin',continuous_face=True)
    # A stepped skull cap has volume and a narrower crown, rather than a box lid.
    for i,(yl,yh,width,front,back) in enumerate([
        (29.65,30.15,3.24,-2.96,2.92),
        (30.15,30.68,3.06,-2.81,2.81),
        (30.68,31.12,2.79,-2.53,2.55),
        (31.12,31.38,2.31,-2.18,2.20),
    ]):
        d.cube(head,f'hair_crown_section_{i}',(-width,yl,front),(width,yh,back),'hair')
    # The back and side covering follow the skull profile. Exposed side skin
    # stays a small cheek/ear zone, rather than a tall rectangular skin panel.
    for i,(yl,yh,width,z0,z1) in enumerate([
        (24.75,26.10,3.18,1.87,2.88),
        (26.10,27.60,3.43,2.02,3.05),
        (27.60,29.25,3.47,2.09,3.07),
        (29.25,30.15,3.26,2.00,2.93),
    ]):d.cube(head,f'back_head_section_{i}',(-width,yl,z0),(width,yh,z1),'hair')
    for sign,side in [(-1,'right'),(1,'left')]:
        for i,(yl,yh,inner,outer,z0,z1) in enumerate([
            (24.75,25.70,2.62,3.22,-.62,2.88),
            (25.70,26.65,2.97,3.43,-.92,3.03),
            (26.65,28.10,3.17,3.47,-1.20,3.07),
            (28.10,29.65,3.02,3.39,-2.18,2.99),
        ]):
            xl,xh=(-outer,-inner) if sign<0 else (inner,outer)
            d.cube(head,f'{side}_temple_section_{i}',(xl,yl,z0),(xh,yh,z1),'hair')
        xl,xh=(-3.52,-3.10) if sign<0 else (3.10,3.52)
        d.cube(head,side+'_ear',(xl,26.05,-.73),(xh,27.23,.18),'skin')

def base(cid):
    d=Design(cid);sak=cid=='sakiko'
    hair='8594b9' if sak else 'df9db8'; skin='f6ddcf'
    for name,col,kind in [('skin',skin,'flat'),('hair',hair,'hair'),('hair_shadow','637499' if sak else 'c682a0','hair'),('hair_light','9daacc' if sak else 'ecb2c9','hair'),('dark','262932','cloth'),('white','eeeee6','cloth'),('gold','d6bc63','flat'),('shoe','303038','shoe'),('sock','f3f0e8','cloth'),('top','8996ab' if sak else '326787','cloth'),('skirt','53695e' if sak else 'ece9db','plaid' if sak else 'cloth'),('skirt_shadow','44594f' if sak else 'd6d4c8','cloth'),('tie','303e5c','cloth')]:d.tile(name,col,kind)
    d.face(skin,hair,'bb8b3f' if sak else '638393','soft' if sak else 'bright')
    d.tile('fringe',hair,'sweep'); d.tile('pleat','53695e' if sak else 'ece9db','plaid' if sak else 'pleat')
    body=d.part('body',(0,16,0));neck=d.part('neck',(0,23.1,0),'body');head=d.part('head',(0,24,0),'neck')
    d.cube(neck,'neck',(-1.05,22.6,-.9),(1.05,24.5,1.05),'skin')
    shaped_head(d,head,sak)
    for sign,side in [(-1,'right'),(1,'left')]:
        x=sign*1.35;leg=d.part(side+'_thigh',(x,13.2,0),'body');shin=d.part(side+'_shin',(x,7.4,0),side+'_thigh')
        d.cube(leg,side+'_thigh_skin',(x-1.06,7.4,-1.0),(x+1.06,14.1,1.05),'skin')
        d.cube(shin,side+'_calf',(x-.96,1.0,-.9),(x+.96,7.4,.98),'skin')
        if sak:d.cube(shin,side+'_knee_sock',(x-.99,1.1,-.93),(x+.99,7.1,1.01),'sock')
        else:d.cube(shin,side+'_ankle_sock',(x-1,1.15,-.94),(x+1,3.8,1.02),'dark')
        d.cube(shin,side+'_shoe',(x-1.08,0,-1.65),(x+1.08,1.55,1.12),'shoe')
        if not sak:d.cube(shin,side+'_boot_shaft',(x-1.04,1.5,-1),(x+1.04,3.45,1.1),'dark')
    return d,body,head

def arms(d,body,sak):
    for sign,side in [(-1,'right'),(1,'left')]:
        x=sign*4.25;p=d.part(side+'_upper_arm',(x,22.25,0),'body');q=d.part(side+'_forearm',(x,18.5,0),p['name'])
        d.cube(p,side+'_upper_arm',(x-1.05,18.5,-1.2),(x+1.05,22.35,1.3),'top')
        if not sak:
            d.cube(p,side+'_rolled_sleeve',(x-1.12,18.5,-1.3),(x+1.12,19.2,1.4),'top')
            d.cube(p,side+'_sleeve_edge',(x-1.13,18.55,-1.35),(x+1.13,18.72,-1.28),'gold')
        d.cube(q,side+'_forearm',(x-.91,15.0,-1.02),(x+.91,18.5,1.12),'top' if sak else 'skin')
        if sak:d.cube(q,side+'_cuff',(x-.96,15.0,-1.07),(x+.96,15.65,1.17),'top')
        d.cube(q,side+'_hand',(x-.87,13.5,-1),(x+.87,15.05,1.07),'skin')

def anon():
    d,b,h=base('anon');arms(d,b,False)
    d.cube(b,'midriff',(-2.85,15.5,-1.45),(2.85,19,1.4),'skin')
    d.cube(b,'cropped_blue_jacket',(-3.15,18.45,-1.6),(3.15,22.5,1.55),'top')
    d.cube(b,'left_stand_collar',(-1.18,22.25,-1.76),(-.23,23.1,-1.48),'top')
    d.cube(b,'right_stand_collar',(.23,22.25,-1.76),(1.18,23.1,-1.48),'top')
    d.cube(b,'collar_gold_edge',(.97,22.45,-1.84),(1.14,23.04,-1.75),'gold')
    d.cube(b,'left_chest_seam',(-2.65,19.05,-1.68),(-2.54,21.95,-1.6),'dark')
    d.cube(b,'chest_pocket',(-2.45,20.5,-1.71),(-.72,21.34,-1.6),'top')
    d.cube(b,'pocket_flap',(-2.51,21.18,-1.81),(-.68,21.43,-1.7),'top')
    d.cube(b,'pocket_button',(-1.62,21.2,-1.87),(-1.46,21.36,-1.8),'gold')
    d.cube(b,'cropped_hem',(-3.18,18.45,-1.69),(3.18,18.8,1.6),'top')
    d.cube(b,'jacket_seam',(-.08,18.5,-1.7),(.08,22.15,-1.61),'skirt_shadow')
    for yy in (19.1,20.3,21.4):d.cube(b,'jacket_button_'+str(yy),(.31,yy,-1.8),(.48,yy+.18,-1.69),'gold')
    for i,(x0,x1,y0,y1) in enumerate([(1.55,2.22,22.12,22.45),(1.28,1.95,21.6,22.12),(1.06,1.66,20.95,21.6),(1.01,1.42,20.25,20.95)]):
        d.cube(b,'gold_lapel_'+str(i),(x0,y0,-1.82),(x1,y1,-1.61),'gold')
    d.cube(b,'white_waistband',(-3.04,16.8,-1.65),(3.04,17.3,1.67),'white')
    # Vertical skirt gores: full-length fronts, staggered depth, no horizontal tier seam.
    for i,(x0,x1,y0,z) in enumerate([(-3.15,-2.05,9.8,-2.15),(-2.05,-.8,10.25,-2.27),(-.8,.45,10.5,-2.35),(.45,1.7,10.2,-2.25),(1.7,3.15,9.75,-2.15)]):
        d.cube(b,'front_skirt_gore_'+str(i),(x0,y0,z),(x1,16.8,1.9),'pleat')
    for sign in (-1,1):
        x0,x1=(-3.7,-3.15) if sign<0 else (3.15,3.7)
        d.cube(b,'outer_skirt_gore_'+str(sign),(x0,8.4,-1.8),(x1,14.5,2.5),'pleat')
    for i,(x0,x1,y0,z) in enumerate([(-3.15,-1.95,6.2,2.5),(-1.95,-.65,4.9,2.7),(-.65,.65,4.5,2.85),(.65,1.95,5.1,2.7),(1.95,3.15,6.4,2.5)]):
        d.cube(b,'rear_train_gore_'+str(i),(x0,y0,1.85),(x1,16.8,z),'pleat')
    # Side-sweep is an asymmetric continuous staircase, not repeated vertical teeth.
    for i,(x0,x1,y0,y1,z) in enumerate([(-3.55,-2.65,28.7,30.8,-3.17),(-2.65,-1.8,28.95,30.8,-3.22),(-1.8,-.9,29.2,30.8,-3.26),(-.9,.1,29.5,30.8,-3.25),(.1,1.35,29.75,30.8,-3.21),(1.35,2.5,29.1,30.8,-3.15),(2.5,3.52,28.2,30.8,-3.14)]):
        d.cube(h,'swept_fringe_'+str(i),(x0,y0,z),(x1,y1,-2.86),'fringe')
    for sign,side in [(-1,'right'),(1,'left')]:
        x0,x1=(-4.03,-3.45) if sign<0 else (3.45,4.03)
        d.cube(h,side+'_face_frame',(x0,23.1,-2.35),(x1,29.5,-1.2),'hair_light')
        x0,x1=(-4.03,-3.35) if sign<0 else (3.35,4.03)
        d.cube(h,side+'_long_lock_top',(x0,19.6,.65),(x1,27.2,2.85),'hair')
        x0,x1=(-3.85,-3.13) if sign<0 else (3.13,3.85)
        d.cube(h,side+'_long_lock_end',(x0,16.1,1.0),(x1,19.6,2.75),'hair_light')
    for name,x0,x1,y,z in [('right',-3.5,-1.12,16.6,3.42),('middle',-1.12,1.23,15.6,3.58),('left',1.23,3.5,16.1,3.38)]:
        d.cube(h,'back_long_mass_'+name,(x0,y,2.65),(x1,24.7,z),'hair')
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
    for i,(x0,x1,z) in enumerate([(-3.2,-2.25,-2.1),(-2.25,-1.3,-2.23),(-1.3,-.4,-2.08),(-.4,.55,-2.25),(.55,1.5,-2.08),(1.5,2.4,-2.23),(2.4,3.2,-2.1)]):
        d.cube(b,'green_vertical_pleat_'+str(i),(x0,9.8,z),(x1,15.7,-z),'pleat')
    for sign in (-1,1):
        x0,x1=(-3.65,-3.2) if sign<0 else (3.2,3.65)
        d.cube(b,'green_outer_pleat_'+str(sign),(x0,9.9,-1.95),(x1,13.5,1.95),'pleat')
        x0,x1=(-3.98,-3.65) if sign<0 else (3.65,3.98)
        d.cube(b,'green_flared_hem_'+str(sign),(x0,9.9,-1.88),(x1,11.5,1.88),'pleat')
    for i,(x0,x1,y0) in enumerate([(-3.5,-2.2,28.5),(-2.2,-.8,28.85),(-.8,.6,29.3),(.6,2.0,29.0),(2.0,3.5,28.5)]):
        d.cube(h,'soft_fringe_'+str(i),(x0,y0,-3.18),(x1,30.8,-2.86),'fringe')
    # Paired braids use four connected broad alternating lobes at the side of the head.
    for sign,side in [(-1,'right'),(1,'left')]:
        for i in range(4):
            c=sign*(3.75+(0.14 if i%2 else 0)); y=28.35-i*.72
            d.cube(h,side+'_braid_'+str(i),(c-.42,y-.72,-1.2-(.035 if i%2 else 0)),(c+.42,y,.35+(.035 if i%2 else 0)),'hair_light' if i%2 else 'hair')
        c=sign*3.95
        d.cube(h,side+'_black_knot',(c-.42,24.6,-.75),(c+.42,25.25,.5),'dark')
        d.cube(h,side+'_ribbon_upper',(c-.7,24.95,-.55),(c+.7,25.4,.24),'dark')
        d.cube(h,side+'_ribbon_tail',(c-.2,23.85,-.45),(c+.2,24.65,.02),'dark')
        # Deliberate inward/outward change supplies a squared S curl, all connected.
        for i,(cx,y0,y1,z0,z1,w) in enumerate([(3.82,22.0,24.7,.3,1.75,1.16),(4.13,20.5,22.0,.35,1.85,1.5),(4.02,19.4,20.5,.5,2.02,1.42),(3.4,18.7,19.4,.7,2.1,1.35),(3.12,18.7,19.8,.9,2.08,.6)]):
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
