"""Apply the quest-only revision on top of the *published*, translated handbook."""
from pathlib import Path
import shutil
import json
import tempfile

from quest_knowledge import build_catalogue
from merge_quests import read, write

CHANGES = ('Animal_Companions', 'newera_field', 'delight_ecology', 'joint_field_wildlife', 'joint_field_hunts')
REVISION_FILES = ('config/ftbquests/quests/chapter_groups.snbt',
                  *('config/ftbquests/quests/chapters/' + n + '.snbt' for n in CHANGES),
                  'config/ic2newera/quest_knowledge.json')
def expected(quests):
    patrol_count = len(read(quests/'chapters/joint_field_hunts.snbt')['quests'])
    return {'chapters': 62, 'quests': 2664 + patrol_count, 'own': 155 + patrol_count}


def apply_revision(quests: Path, generated: Path):
    """Leave the original 2,509 quests and TaCZ fixes byte-for-byte where possible."""
    chapters = quests / 'chapters'
    src = generated / 'chapters'
    groups = quests / 'chapter_groups.snbt'
    old, new = read(groups), read(generated / groups.name)
    added = [g for g in new['chapter_groups'] if g['id'] == read(src / 'joint_field_hunts.snbt')['group']]
    if len(added) != 1: raise ValueError('Expected one patrol chapter group')
    others = [g for g in old['chapter_groups'] if g['id'] == added[0]['id']]
    if others and others != added: raise ValueError('Conflicting patrol group')
    if not others:
        old['chapter_groups'].append(added[0]); write(groups, old)
    for name in CHANGES:
        incoming = src / (name + '.snbt')
        target = chapters / incoming.name
        candidate = read(incoming)
        if name in ('Animal_Companions', 'newera_field'):
            # These two are original chapters. Only the additional navigation
            # links change; never replace translated tasks/rewards from source.
            original = read(target)
            if original['id'] != candidate['id'] or original['quests'] != candidate['quests']:
                raise ValueError('Underlying quests changed in ' + name)
            original['quest_links'] = candidate['quest_links']
            if original != read(target):
                write(target, original)
        else:
            if target.exists() and read(target) == candidate:
                continue
            shutil.copy2(incoming, target)


def validate_ids(quests: Path):
    from merge_quests import walk_ids
    files = sorted((quests / 'chapters').glob('*.snbt'))
    chapters = [read(p) for p in files]
    ids = walk_ids(chapters)
    if any(int(v, 16) <= 0 or int(v, 16) >= 2**63 for v in ids):
        raise ValueError('Unusable FTB ID')
    by_id = {q['id']: q for c in chapters for q in c['quests']}
    done, visiting = set(), set()
    def visit(i):
        if i in done: return
        if i in visiting: raise ValueError('Circular quest: ' + i)
        visiting.add(i)
        for dep in by_id[i].get('dependencies', []): visit(dep)
        visiting.remove(i); done.add(i)
    for item in by_id: visit(item)
    groups = {g['id'] for g in read(quests/'chapter_groups.snbt')['chapter_groups']}
    for chapter in chapters:
        if chapter.get('group') not in (None, '', '0000000000000000') and chapter['group'] not in groups: raise ValueError('Missing chapter group')
        for link in chapter.get('quest_links', []):
            if link.get('linked_quest') not in by_id: raise ValueError('Unreachable link: ' + str(link))
    return {'chapters': len(files), 'quests': len(by_id), 'own': sum(len(c['quests']) for f,c in zip(files,chapters) if f.stem.startswith(('joint_','newera_','delight_')))}


def compile_index(quests, mod_paths, vanilla, languages, dest, approved_index=None):
    with tempfile.TemporaryDirectory() as tmp:
        preview = Path(tmp)/'index.json'
        actual = build_catalogue(quests,mod_paths,vanilla,languages,preview)
        if actual != validate_ids(quests) or actual != expected(quests): raise ValueError(actual)
        book = json.loads(preview.read_text('utf8'))
    if approved_index and approved_index.exists():
        before = {r['id']: r for r in json.loads(approved_index.read_text('utf-8-sig'))['quests']}
        for i, row in enumerate(book['quests']):
            # Imported quests have no premise changed by this revision. Preserve
            # their translated AI rows too (map links are not prerequisites).
            old = before.get(row['id'])
            if old and not row['own']:
                book['quests'][i] = old
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(book,ensure_ascii=False,separators=(',',':')),encoding='utf8')
    return actual


def prepare(work, profile):
    from zipfile import ZipFile, ZIP_DEFLATED
    from hashlib import sha256
    from wildlife_kills import ECOLOGY_REMOVALS, FIELD_WILDLIFE_REMOVAL_QUEST_IDS
    audit = work/'collision-quests-20261114'; stage = audit/'staging'; overlay = work/'client-overlay'
    rel = 'config/ftbquests/quests'; ai = 'config/ic2newera/quest_knowledge.json'
    pre = audit/'published-baseline'
    if not (pre/rel).exists():
        shutil.copytree(overlay/rel,pre/rel)
        (pre/ai).parent.mkdir(parents=True,exist_ok=True); shutil.copy2(overlay/ai,pre/ai)
    shutil.copytree(pre/rel,stage/rel,dirs_exist_ok=True)
    apply_revision(stage/rel, work/'quests-work/merged-r71-ecology-prune')
    assets=profile.parents[1]/'assets'
    idx=json.loads((profile/(profile.name+'.json')).read_text('utf-8-sig'))['assetIndex']['id']
    zh=json.loads((assets/'indexes'/(idx+'.json')).read_text('utf-8-sig'))['objects']['minecraft/lang/zh_cn.json']['hash']
    counts=compile_index(stage/rel, sorted((profile/'mods').glob('*.jar')), profile/(profile.name+'.jar'),
                         [assets/'objects'/zh[:2]/zh, *sorted((overlay/'kubejs/assets').glob('*/lang/zh_cn.json'))],stage/ai,pre/ai)
    def rows(base): return {r['id']:r for r in json.loads((base/ai).read_text('utf8'))['quests']}
    before, after=rows(pre), rows(stage)
    added, removed=sorted(after.keys()-before.keys()), sorted(before.keys()-after.keys())
    hunt=read(stage/rel/'chapters/joint_field_hunts.snbt'); patrol={q['id']:q for q in hunt['quests']}
    if set(removed)!=(set(ECOLOGY_REMOVALS)|set(FIELD_WILDLIFE_REMOVAL_QUEST_IDS.values())) or set(added)!=set(patrol): raise ValueError('Unexpected change in existing quest IDs')
    if any(after[k]!=r for k,r in before.items() if not r['own']) or sum(not v['own'] for v in after.values())!=2509: raise ValueError('Base task changed')
    for key in added:
        v=after[key]; q=patrol[key]; kinds=[t['type'] for t in q['tasks']]
        if not kinds or not set(kinds)<= {'kill','item'} or not v['own'] or len(v['tasks'])!=len(kinds) or not all('每人领取' in r for r in v['rewards']): raise ValueError(v)
        if 'kill' in kinds and (kinds!=['kill'] or not v['tasks'][0].startswith('击败 ')): raise ValueError('Kill credit must be independent of loot: '+key)
        if any(s in str(q) for s in ('alexsmobs:', 'alexscaves:', 'createdelight:')): raise ValueError('Unexpected pack-only creature: '+key)
    def digest(p): return sha256(p.read_bytes()).hexdigest()
    files=[{'path':key,'before':digest(pre/key) if (pre/key).is_file() else None,'after':digest(stage/key)} for key in REVISION_FILES]
    for src in (pre/rel).rglob('*'):
        if src.is_file() and src.relative_to(pre).as_posix() not in REVISION_FILES and src.read_bytes()!=(stage/src.relative_to(pre)).read_bytes(): raise ValueError('Unrelated game file differs: '+src.name)
    patch=audit/'r71-quests-ai-update.zip'
    with ZipFile(patch,'w',ZIP_DEFLATED) as z:
        for f in files: z.write(stage/f['path'], f['path'])
    with ZipFile(patch) as z:
        if z.testzip() or len(z.namelist())!=len(files): raise ValueError('Damaged quest patch')
    certificate={'shape':counts, 'patrol':validate_player_requests(stage/rel), 'removed':removed,'added':added,'paths':files,'zipsha256':digest(patch)}
    (stage/'quest-set-published.json').write_text(json.dumps(certificate,ensure_ascii=False,indent=2),encoding='utf8')
    return certificate


def server_files(work):
    """Patch navigation against the server's own saved ordering and defaults."""
    from hashlib import sha256
    from zipfile import ZipFile, ZIP_DEFLATED
    audit=work/'collision-quests-20261114'; pre=audit/'online-before'; stage=audit/'staging'; live=audit/'server-payload'
    shutil.copytree(stage/'config',live/'config',dirs_exist_ok=True)
    root='config/ftbquests/quests/'
    for name in ('Animal_Companions','newera_field'):
        rel=root+'chapters/'+name+'.snbt'; original=read(pre/rel); incoming=read(stage/rel)
        if original['quests']!=incoming['quests'] or original['id']!=incoming['id']:raise ValueError('Existing player-edited quest changed: '+name)
        old_ids={k['id'] for k in original.get('quest_links',[])}; base=read(audit/'published-baseline'/rel)
        expected_old={k['id'] for k in base.get('quest_links',[])}
        if old_ids!=expected_old:raise ValueError('Unexpected online navigation edit: '+name)
        original['quest_links']=incoming['quest_links']; write(live/rel,original)
    rel=root+'chapter_groups.snbt'; online=read(pre/rel); new=read(stage/rel); base=read(audit/'published-baseline'/rel)
    base_ids={g['id'] for g in base['chapter_groups']}; add=[q for q in new['chapter_groups'] if q['id'] not in base_ids]
    if len(add)!=1 or online['chapter_groups']!=[g for g in online['chapter_groups'] if g['id'] in base_ids]:raise ValueError('Unexpected online group change')
    online['chapter_groups']+=add; write(live/rel,online)
    # Existing unmodified quest IDs (including partly normalized SNBT on the
    # server) must all survive outside the exact twelve discontinued objectives.
    for name in ('delight_ecology','joint_field_wildlife'):
        rel=root+'chapters/'+name+'.snbt'; a=read(pre/rel); b=read(audit/'published-baseline'/rel)
        if a['id']!=b['id'] or {q['id'] for q in a['quests']}!={q['id'] for q in b['quests']}:raise ValueError('Unexpected extra live quests: '+name)
    zpath=audit/'r71-quests-ai-live-update.zip'; hs={}
    with ZipFile(zpath,'w',ZIP_DEFLATED) as z:
        for rel in REVISION_FILES:
            data=(live/rel).read_bytes(); hs[rel]=sha256(data).hexdigest(); z.writestr(rel,data)
    with ZipFile(zpath) as z:
        if z.testzip() or any(sha256(z.read(r)).hexdigest()!=h for r,h in hs.items()):raise IOError('Remote distribution checksum mismatch')
    (audit/'server-payload-digests.json').write_text(json.dumps(hs,indent=2),encoding='utf8')
    return zpath


def validate_player_requests(quests):
    """Check the agreed optional kills, rare collections and per-member rewards."""
    q=read(quests/'chapters/joint_field_hunts.snbt')['quests']; kills={}; materials={}; grades=set()
    import re
    for node in q:
        matches=re.findall(r'(?<![A-Z])(?:SSS|SS|[SABCDE])(?![A-Z])',str(node.get('subtitle',''))+' '+str(node.get('description','')))
        grades.update(matches)
        if not matches or not node.get('rewards') or any(r.get('team_reward',False) for r in node['rewards']):raise ValueError('Missing tier or individual reward: '+node['title'])
        for task in node['tasks']:
            if task['type']=='kill':
                if len(node['tasks'])!=1 or node.get('dependencies') or int(task.get('value',1))<=0:raise ValueError('Kill credit is gated: '+node['title'])
                kills[task['entity']]=task
            elif task['type']=='item': materials[task['item']]=task
    if not {'minecraft:wither','minecraft:ender_dragon','minecraft:zombified_piglin','minecraft:iron_golem'}<=kills.keys():raise ValueError('Missing named boss or neutral target')
    if len(kills.keys() & {'minecraft:pig','minecraft:chicken','minecraft:sheep','minecraft:rabbit','minecraft:cow'})<2:raise ValueError('Missing farm animals')
    if not any(k.split(':')[0] in {'spawn','ad_astra'} for k in kills):raise ValueError('No original mod wildlife represented')
    if 'minecraft:wither_skeleton_skull' not in materials:raise ValueError('Missing the requested rare skull')
    if grades!={'E','D','C','B','A','S','SS','SSS'}:raise ValueError(('Missing grades',grades))
    return {'kills':len(kills),'loot':len(materials),'tiers':sorted(grades)}


if __name__=='__main__':
    import argparse
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--profile',required=True,type=Path)
    ap.add_argument('--online',action='store_true',help='Also prepare a deployment that preserves the captured server settings')
    ap.add_argument('--work',type=Path,default=Path('build-isolated/player-packaging/20261113-delight-joint'))
    args=ap.parse_args(); print(prepare(args.work.resolve(),args.profile.resolve())['shape'])
    if args.online: print(server_files(args.work.resolve()))
