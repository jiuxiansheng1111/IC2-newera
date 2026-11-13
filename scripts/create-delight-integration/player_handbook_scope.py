"""Build the player-selected New Era book while keeping persistent FTB IDs intact."""
import hashlib
import json
from pathlib import Path
import os
import shutil
import tempfile
from zipfile import ZipFile, ZIP_DEFLATED

from quest_knowledge import build_catalogue
from merge_quests import read, write
from field_hunt_overlay import validate_ids, validate_player_requests
from wildlife_kills import ECOLOGY_REMOVALS, FIELD_WILDLIFE_REMOVAL_QUEST_IDS, MACHINE_ACCEPTANCE_JOBS

BOOK = 'config/ftbquests/quests'
AI = 'config/ic2newera/quest_knowledge.json'
PHOTO = 'joint_photo_survey'
OWN = ('newera_', 'joint_', 'delight_')
TARGET = 258
CORE_NAME = 'ic2newera-0.2.0-migration1201-all.jar'
CORE_OLD = '7dba2477dc2429c4db7fd59465ff178991548f64fac800a042b31223deaed8e5'
CORE_NEW = '14ffa3775a1121de76350af45069051fd864b5d3d719dcfaa4b05c1457ba30fb'
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def atomic_json(path, value):
    tmp = path.with_suffix('.part')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf8')
    os.replace(tmp, path)
def units(folder):
    return {p.stem: read(p) for p in (folder/'chapters').glob('*.snbt')}
def ids(chapters):
    return {q['id'] for c in chapters.values() for q in c.get('quests', [])}

def sync_phone(work, profile):
    selection=work/'selection.json'
    rows=json.loads(selection.read_text('utf-8-sig'))
    matched=[r for r in rows if r['primary']=='ic2newera']
    binary=work/'photo-assignment-generated/binaries'/CORE_NAME
    if len(matched)!=1 or digest(binary)!=CORE_NEW or matched[0]['name']!=CORE_NAME or matched[0]['sha256'] not in {CORE_OLD,CORE_NEW}:raise ValueError('Unknown phone update')
    row=matched[0]; ref=Path(row['path'])
    backup=work/'collision-quests-20261114/player-handbook-r71/core-before'/CORE_NAME
    prev=backup if backup.is_file() else ref
    if digest(prev)!=CORE_OLD:raise ValueError('Original New Era binary missing')
    with ZipFile(prev) as a, ZipFile(binary) as b:
        if a.namelist()!=b.namelist() or b.testzip() or [k for k in a.namelist() if a.read(k)!=b.read(k)]!=['com/ic2newera/field/EcologyAlbum.class']:
            raise ValueError('Unexpected New Era binary change')
    dests=[ref,profile/'mods'/CORE_NAME]
    if any(not p.is_file() or digest(p) not in {CORE_OLD,CORE_NEW} for p in dests):raise ValueError('Preserve unknown local New Era JAR')
    backup.parent.mkdir(parents=True,exist_ok=True)
    if not backup.is_file():shutil.copy2(prev,backup)
    if not (backup.parent/'selection.json').exists():shutil.copy2(selection,backup.parent/'selection.json')
    for p in dests:
        if digest(p)==CORE_NEW:continue
        temp=p.with_name(p.name+'.newera-part');shutil.copy2(binary,temp)
        if digest(temp)!=CORE_NEW:raise IOError('Core copy mismatch')
        os.replace(temp,p)
    row['sha256']=CORE_NEW
    atomic_json(selection,rows)

def assemble(base, generated, photos, dest):
    """Construct a fresh active chapter directory. Never write to an installed save."""
    if (dest/'chapters').exists(): raise ValueError('Prepare the handbook in a fresh destination')
    shutil.copytree(base, dest, dirs_exist_ok=True, ignore=shutil.ignore_patterns('chapters', 'merge_report.json'))
    data = read(base/'data.snbt')
    data.update(title='IC2：新纪元 · 工业与野外手册', icon='ic2newera:field_notice_board',
                default_reward_team=False, default_autoclaim_rewards='disabled', disable_toast=False)
    write(dest/'data.snbt', data)
    src, canonical = units(generated), units(base)
    current = {n:c for n,c in src.items() if n.startswith(OWN)}
    for n in current:
        if n in canonical and n not in ('delight_ecology','joint_field_wildlife','joint_field_hunts','joint_spawn_industry'):
            c = canonical[n].copy()   # keep the actual r71 signed IDs and TaCZ stacks
            if n=='newera_field':
                if ids({n:c})!=ids({n:current[n]}): raise ValueError('Field task IDs changed')
                c['quest_links']=current[n].get('quest_links', [])
            current[n]=c
    photo = read(photos/BOOK/'chapters'/(PHOTO+'.snbt'))
    if photo['filename']!=PHOTO or len(photo['quests'])!=24: raise ValueError('Expected 24 real photo tasks')
    current[PHOTO]=photo
    existing=ids(current)
    removed_links = []
    for name, chapter in current.items():
        if any(d not in existing for q in chapter['quests'] for d in q.get('dependencies', [])):
            raise ValueError('Unmapped prerequisite in '+name)
        if chapter.get('quest_links'):
            for link in chapter['quest_links']:
                if link['linked_quest'] not in existing: removed_links.append((name,link['linked_quest']))
            chapter['quest_links']=[v for v in chapter['quest_links'] if v['linked_quest'] in existing]
        write(dest/'chapters'/(name+'.snbt'),chapter)
    if set(removed_links)!={('delight_ecology','329597CAA8B8D983'),('joint_field_hunts','4AD0B673F5112388')}:
        raise ValueError(('Check cross-book chapter links',removed_links))
    group_src={g['id']:g for p in (generated,base) for g in read(p/'chapter_groups.snbt')['chapter_groups']}
    active_groups={c['group'] for c in current.values() if c.get('group')}
    if active_groups-group_src.keys():raise ValueError('Missing chapter group')
    # Put the starter path first, followed by departments and then work orders.
    order=[g['id'] for g in read(base/'chapter_groups.snbt')['chapter_groups'] if g['id'] in active_groups]
    order.sort(key=lambda i: (0 if i=='694AC3E117D2CBBA' else 1 if i.startswith('694A') and i not in {'694A80EA0DAF0916','694ADA51E21C1900'} else 2))
    order+=sorted(active_groups-set(order))
    write(dest/'chapter_groups.snbt', {'chapter_groups':[group_src[k] for k in order]})
    prior=ids({n:c for n,c in canonical.items() if n.startswith(OWN)})
    if prior-existing != set(ECOLOGY_REMOVALS)|set(FIELD_WILDLIFE_REMOVAL_QUEST_IDS.values()):
        raise ValueError('Unexpected loss of New Era progress IDs')
    if ids({n:c for n,c in canonical.items() if not n.startswith(OWN)}) & existing:
        raise ValueError('An imported quest is still active')
    shape=validate_ids(dest)
    if shape!={'chapters':len(current),'quests':TARGET,'own':TARGET}:raise ValueError(shape)
    return shape

def checks(quests):
    patrol=validate_player_requests(quests)
    sheets=units(quests)
    by_item={t.get('item'):t for q in sheets['joint_field_hunts']['quests'] for t in q['tasks'] if t['type']=='item'}
    for x in ('dragon_egg','nether_star','wither_skeleton_skull','trident','iron_horse_armor','golden_horse_armor','diamond_horse_armor'):
        if by_item['minecraft:'+x].get('consume_items') is not False: raise ValueError('Collector piece would be consumed: '+x)
    jobs=sheets['joint_spawn_industry']['quests']
    t=[q['tasks'][0] for q in jobs]
    machine_ids={x['item'] for x in MACHINE_ACCEPTANCE_JOBS}
    if {z.get('item') for z in t if z.get('consume_items') is False}!=machine_ids:raise ValueError('Machine possession checks differ')
    supplies={z['item'] for z in t if z.get('consume_items') is True}
    if not {'ic2:fuel_rod','ic2:uranium_fuel_rod','ic2newera:impure_lead_dust','ic2:purified_lead','ic2:lead_dust'}<=supplies:raise ValueError('Missing correct IC2 order')
    if [len(t),sum(z['type']=='kill' for z in t),sum(z.get('consume_items') is True for z in t),len(machine_ids)]!=[42,6,28,8]:raise ValueError('Missing work orders')
    for q in jobs+sheets[PHOTO]['quests']:
        if not q.get('description') or not q.get('rewards') or any(r.get('team_reward',False) for r in q['rewards']):raise ValueError(q['title'])
    proofs=[q['tasks'][0] for q in sheets[PHOTO]['quests']]
    if any(len(q['tasks'])!=1 or q['tasks'][0]['type']!='advancement' for q in sheets[PHOTO]['quests']) or len({z['advancement'] for z in proofs})!=24:raise ValueError('Photo goals are not independent server evidence')
    return {'patrol':patrol, 'spawn_industry':{'kills':6,'delivery':28,'machine_possession':8},'photography':len(proofs)}

def compile_index(quests, mods, vanilla, languages, dest):
    shape=build_catalogue(quests, mods, vanilla, languages, dest)
    if shape!=validate_ids(quests) or shape['quests']!=TARGET or shape['own']!=TARGET:raise ValueError(shape)
    return shape

def non_book_resources(work, photos, source, target):
    from merchant_milestones import REL, remap_merchant_milestones
    expected=source/REL
    (target/REL).parent.mkdir(exist_ok=True,parents=True)
    (target/REL).write_bytes(remap_merchant_milestones(expected.read_bytes()))
    paths=[REL]
    for f in sorted((photos/'kubejs').rglob('*')):
        if not f.is_file(): continue
        rel=f.relative_to(photos)
        if not (rel.as_posix().startswith('kubejs/data/ic2newera/advancements/photography/') or rel.as_posix().startswith('kubejs/server_scripts/')):raise ValueError(rel)
        (target/rel).parent.mkdir(exist_ok=True,parents=True);shutil.copy2(f,target/rel);paths.append(rel.as_posix())
    if sum(rel.endswith('.json') for rel in paths)!=24 or len(paths)<26:raise ValueError(('Missing photo data',len(paths)))
    return paths

def prepare(work, profile):
    audit=work/'collision-quests-20261114'; base=audit/'published-baseline'
    generated=work/'quests-work/merged-r71-ecology-prune'; photos=work/'photo-assignment-generated'
    overlay=work/'client-overlay'; canonical=work.parents[1]/'.r71-tacz12-pre-overlay'
    stage=audit/'staging'; edition=audit/'player-handbook-r71'; edition.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=edition) as directory:
        tmp=Path(directory); quests=tmp/BOOK
        shape=assemble(base/BOOK,generated,photos,quests)
        stats=checks(quests)
        assets=profile.parents[1]/'assets'; index=json.loads((profile/(profile.name+'.json')).read_text('utf-8-sig'))['assetIndex']['id']
        zh=json.loads((assets/'indexes'/(index+'.json')).read_text('utf-8-sig'))['objects']['minecraft/lang/zh_cn.json']['hash']
        compile_index(quests, sorted((profile/'mods').glob('*.jar')), profile/(profile.name+'.jar'),
                      [assets/'objects'/zh[:2]/zh,*sorted((overlay/'kubejs/assets').glob('*/lang/zh_cn.json'))], tmp/AI)
        # Cumulative player patches recognize only versions from our approved
        # pack snapshots. Never bless arbitrary edits in a player's live instance.
        changed=[f.relative_to(tmp).as_posix() for f in sorted((quests/'chapters').glob('*.snbt'))]
        changed += [BOOK+'/chapter_groups.snbt', BOOK+'/data.snbt', AI]
        from merchant_milestones import REL
        source=canonical if (canonical/REL).is_file() else overlay
        changed+=non_book_resources(work,photos,source,tmp)
        retired=[BOOK+'/chapters/'+p.name for p in sorted((work/'quests-work/base-extracted/chapters').glob('*.snbt'))]
        if len(retired)!=41 or len(ids(units(work/'quests-work/base-extracted')))!=2509:raise ValueError('The originally imported book changed')
        snapshots=[base,canonical,overlay,audit/'online-before',audit/'online-readback']
        known={}
        for rel in changed+retired:
            vals={digest(src/rel) for src in snapshots if (src/rel).is_file()}
            if rel in changed:vals.add(digest(tmp/rel))
            alternate=work/'quests-work/base-extracted'/rel.removeprefix(BOOK+'/')
            if rel in retired and alternate.is_file():vals.add(digest(alternate))
            known[rel]=sorted(vals)+[None]
        live=ids({n:c for n,c in units(overlay/BOOK).items() if n.startswith(OWN)})
        if live-ids(units(quests)):raise ValueError('Current own progress lost')
        final={rel:digest(tmp/rel) for rel in changed}
        scope=dict(shape=shape,changed_files=changed,retired_files=retired,accepted=known,file_sha256=final)
        cert=dict(shape=shape,**stats,retired_original=2509,paths=[dict(path=r,after=final[r]) for r in changed])
        out=edition/'newera-handbook-live.zip'; pending=out.with_suffix('.part')
        package=dict(final)
        binary=photos/'binaries'/CORE_NAME
        if digest(binary)!=CORE_NEW:raise ValueError('Missing final phone mod')
        package['mods/'+CORE_NAME]=CORE_NEW
        with ZipFile(pending,'w',ZIP_DEFLATED) as z:
            for rel in changed:z.write(tmp/rel,rel)
            z.write(binary,'mods/'+CORE_NAME)
        with ZipFile(pending) as z:
            if z.testzip() or set(z.namelist())!=set(package) or any(hashlib.sha256(z.read(r)).hexdigest()!=h for r,h in package.items()):raise IOError('Incomplete server payload')
        os.replace(pending,out)
        # Replace the previous *staging directory* by rename, never a running
        # installation. The on-disk old receipt and generated chapters survive.
        old=edition/'previous-staging'
        if stage.exists(): 
            suffix=0
            while old.exists():
                suffix+=1;old=edition/('previous-staging-'+str(suffix))
            os.replace(stage,old)
        stage.mkdir()
        shutil.copytree(tmp,stage,dirs_exist_ok=True)
        atomic_json(stage/'quest-set-published.json',cert)
        atomic_json(stage/'player-scope.json',scope)
    return shape

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--profile',required=True,type=Path)
    p.add_argument('--sync-phone',action='store_true')
    p.add_argument('--work',type=Path,default=Path('build-isolated/player-packaging/20261113-delight-joint'))
    args=p.parse_args()
    if args.sync_phone:
        sync_phone(args.work.resolve(),args.profile.resolve());print('Installed verified New Era photo update')
    else:print(prepare(args.work.resolve(),args.profile.resolve()))
