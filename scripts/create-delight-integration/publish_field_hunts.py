"""Publish a validated quest delta without replacing unrelated instance files."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import re
from zipfile import ZipFile

from advisory_lock import serialize_r71_release
from build_joint import W, OUT, digest, read, write
from patch_release_archives import rewrite

PHOTO_JAR_NAME='ic2newera-0.2.0-migration1201-all.jar'
PHOTO_JAR_OLD_SHA256='7dba2477dc2429c4db7fd59465ff178991548f64fac800a042b31223deaed8e5'


def hashbytes(x): return hashlib.sha256(x).hexdigest()


def scope_data():
    audit=W/'collision-quests-20261114'; stage=audit/'staging'
    scope=read(stage/'player-scope.json')
    shape=scope.get('shape',{})
    if (not isinstance(shape,dict) or not isinstance(shape.get('quests'),int)
            or not 250<=shape['quests']<=258 or shape.get('own')!=shape.get('quests')
            or not isinstance(shape.get('chapters'),int) or not 1<=shape['chapters']<=258):
        raise ValueError('Unexpected final quest-set shape')
    changed=scope.get('changed_files'); retired=scope.get('retired_files')
    accepted=scope.get('accepted'); final=scope.get('file_sha256')
    if not all(isinstance(x,list) for x in (changed,retired)) or not isinstance(accepted,dict) or not isinstance(final,dict):
        raise ValueError('Incomplete player-scope.json')
    if len(changed)!=len(set(changed)) or len(retired)!=len(set(retired)) or set(changed)&set(retired):
        raise ValueError('Duplicate or overlapping quest scope paths')
    def checked(rel):
        p=Path(rel)
        allowed=(rel.startswith('config/ftbquests/quests/')
                 or rel=='config/ic2newera/quest_knowledge.json'
                 or rel.startswith('kubejs/data/ic2newera/advancements/photography/')
                 or rel.startswith('kubejs/server_scripts/'))
        if p.is_absolute() or '..' in p.parts or '\\' in rel or ':' in rel or not allowed:
            raise ValueError('Unsafe quest path: '+str(rel))
    for rel in changed+retired: checked(rel)
    if set(changed)!=set(final) or not set(changed+retired).issubset(accepted):
        raise ValueError('Scope hash maps do not match path lists')
    for rel,values in accepted.items():
        checked(rel)
        if not isinstance(values,list) or any(v is not None and (not isinstance(v,str) or len(v)!=64) for v in values):
            raise ValueError('Malformed accepted hashes: '+rel)
    if any(not isinstance(v,str) or len(v)!=64 for v in final.values()): raise ValueError('Malformed final hashes')
    cert=read(stage/'quest-set-published.json')
    cert_rows={r['path']:r for r in cert['paths']}
    for rel in changed:
        data=(stage/rel).read_bytes()
        if hashbytes(data)!=final[rel]: raise ValueError('Final staged hash mismatch: '+rel)
        row=cert_rows.get(rel)
        if row and row.get('after')!=final[rel]: raise ValueError('Staging receipt disagrees: '+rel)
    chapter_files=[stage/rel for rel in changed if rel.startswith('config/ftbquests/quests/chapters/') and rel.endswith('.snbt')]
    from merge_quests import read as read_quest
    quest_ids = [quest['id'] for path in chapter_files for quest in read_quest(path)['quests']]
    if len(chapter_files)!=shape['chapters'] or len(quest_ids)!=shape['quests'] or len(set(quest_ids))!=len(quest_ids):
        raise ValueError('Staged handbook counts or IDs differ from its release receipt')
    return audit,stage,scope


@serialize_r71_release
def install(profile):
    audit,stage,scope=scope_data(); accepted=scope['accepted']; final=scope['file_sha256']
    jobs=[]
    for base,tag in ((profile,'ordinary-profile'),(W/'client-overlay','overlay')):
        for rel in scope['retired_files']:
            dest=base/rel; old=digest(dest) if dest.exists() else None
            if old is None: continue
            if old not in accepted[rel]: raise ValueError('Preserve unknown quest edit: '+str(dest))
            jobs.append(('retire',dest,old,None,audit/'local-history'/tag/'retired'/rel))
        for rel in scope['changed_files']:
            dest=base/rel; data=(stage/rel).read_bytes(); old=digest(dest) if dest.exists() else None
            if old==final[rel]: continue
            if old not in accepted[rel]: raise ValueError('Preserve unknown quest edit: '+str(dest))
            jobs.append(('write',dest,old,data,audit/'local-history'/tag/rel))
    for action,dest,old,data,backup in jobs:
        if (digest(dest) if dest.exists() else None)!=old: raise ValueError('Concurrent change '+str(dest))
        if dest.exists() and not backup.exists():
            backup.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(dest,backup)
        if action=='retire': dest.unlink()
        else:
            dest.parent.mkdir(parents=True,exist_ok=True); tmp=dest.with_name(dest.name+'.hunt.tmp');tmp.write_bytes(data); os.replace(tmp,dest)
            if dest.read_bytes()!=data: raise IOError(str(dest))
    print('Applied quest scope operations:',len(jobs),' at two local destinations')


@serialize_r71_release
def server_zip():
    audit,stage,scope=scope_data(); accepted=scope['accepted']; final=scope['file_sha256']
    s=next(OUT.glob('*-Server.zip')); auditpath=s.with_suffix('.zip.audit.json'); auditrow=read(auditpath)
    if digest(s)!=auditrow['output_sha256']: raise ValueError('The current server archive does not match its audit')
    with ZipFile(s) as z:
        names=set(z.namelist())
        root=next(n.removesuffix('SERVER-BUILD-MANIFEST.json') for n in names if n.endswith('/SERVER-BUILD-MANIFEST.json'))
        mpath=root+'SERVER-BUILD-MANIFEST.json'; m=json.loads(z.read(mpath)); members={}
        from server_heap import heap_arguments
        jvm_member = root + 'user_jvm_args.txt'
        members[jvm_member] = heap_arguments(z.read(jvm_member))
        remove=set()
        for rel in scope['retired_files']:
            key=root+rel
            if key in names:
                prior=hashbytes(z.read(key))
                if prior not in accepted[rel]: raise ValueError('Preserve unknown archive chapter: '+key)
                remove.add(key)
        for rel in scope['changed_files']:
            raw=(stage/rel).read_bytes(); key=root+rel
            if hashbytes(raw)!=final[rel]: raise ValueError(key)
            prior=hashbytes(z.read(key)) if key in names else None
            if prior not in accepted[rel]: raise ValueError('Preserve unknown archive change: '+key)
            members[key]=raw
            if rel not in m['copied_side_audited_files']:m['copied_side_audited_files'].append(rel)
            if rel not in auditrow['copied_side_audited_files']:auditrow['copied_side_audited_files'].append(rel)
        photo_source=W/'photo-assignment-generated/binaries'/PHOTO_JAR_NAME
        if photo_source.is_file():
            selected=[r for r in read(W/'selection.json') if r.get('primary')=='ic2newera']
            if len(selected)!=1 or selected[0].get('name')!=PHOTO_JAR_NAME:
                raise ValueError('Selection must contain exactly the updated New Era JAR')
            photo_jar_sha256=selected[0].get('sha256','').lower()
            if (len(photo_jar_sha256)!=64 or photo_jar_sha256==PHOTO_JAR_OLD_SHA256
                    or digest(photo_source)!=photo_jar_sha256):
                raise ValueError('Photography JAR must match the updated selection SHA-256 and differ from its old hash')
            jar_member=root+'mods/'+PHOTO_JAR_NAME
            if jar_member not in names: raise ValueError('Server archive is missing the locked New Era JAR')
            prior=hashbytes(z.read(jar_member))
            if prior not in {PHOTO_JAR_OLD_SHA256,photo_jar_sha256}:
                raise ValueError('Existing server JAR hash is neither the locked old nor new JAR')
            for manifest_row in (m,auditrow):
                jar_rows=[r for r in manifest_row['mods_server_true'] if r['name']==PHOTO_JAR_NAME]
                if len(jar_rows)!=1 or jar_rows[0]['sha256'] not in {PHOTO_JAR_OLD_SHA256,photo_jar_sha256}:
                    raise ValueError('New Era JAR hash does not match archive audit')
                if jar_rows[0]['sha256']!=prior:
                    raise ValueError('Server manifest JAR hash does not match ZIP bytes')
                jar_rows[0]['sha256']=photo_jar_sha256
            members[jar_member]=photo_source.read_bytes()
        m['copied_side_audited_files']=[r for r in m['copied_side_audited_files'] if r not in scope['retired_files']]
        auditrow['copied_side_audited_files']=[r for r in auditrow['copied_side_audited_files'] if r not in scope['retired_files']]
        ai=hashbytes(members[root+'config/ic2newera/quest_knowledge.json'])
        for row in (m,auditrow):
            for key in ('server_sha256','client_overlay_sha256'):row['quest_knowledge'][key]=ai
        if not remove and all(n in names and z.read(n)==b for n,b in members.items()) and json.loads(z.read(mpath))==m:
            print('Server archive already current');return
    tmp,changed=rewrite(s,remove,{mpath:m},members)
    try:
        if changed!=len(members)+len(remove)+1 or digest(s)!=auditrow['output_sha256']:raise ValueError('Concurrent server archive change')
        with ZipFile(tmp) as z:
            for name,data in members.items():
                if z.read(name)!=data:raise IOError(name)
            if any(root+rel in z.namelist() for rel in scope['retired_files']):raise IOError('Retired quest chapter remains in server archive')
            if PHOTO_JAR_NAME in [Path(n).name for n in members]:
                if hashbytes(z.read(root+'mods/'+PHOTO_JAR_NAME))!=photo_jar_sha256: raise IOError('New Era JAR hash differs after rewrite')
        auditrow.update(output_sha256=digest(tmp),output_bytes=tmp.stat().st_size)
        at=auditpath.with_name(auditpath.name+'.hunt.tmp');write(at,auditrow);os.replace(tmp,s);os.replace(at,auditpath)
    finally:tmp.unlink(missing_ok=True)
    print('Delivered dedicated server archive:',auditrow['output_sha256'])


if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('phase',choices=('install','server'));ap.add_argument('--profile',type=Path)
    a=ap.parse_args()
    if a.phase=='install':
        if not a.profile or not (a.profile/'mods').is_dir():ap.error('Provide the installed profile')
        install(a.profile.resolve())
    else:server_zip()
