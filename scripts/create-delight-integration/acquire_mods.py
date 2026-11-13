"""Acquire the mod JARs actually declared in the user-provided MRPack and verify their bytes."""
from collections import Counter
from concurrent.futures import ThreadPoolExecutor,as_completed
from contextlib import closing
from pathlib import Path, PurePosixPath
from urllib.parse import quote,unquote,urlsplit,urlunsplit
from urllib.request import Request,urlopen
from zipfile import ZipFile
import argparse,hashlib,io,json,os,shutil,time

root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base',type=Path,default=Path.home()/'Downloads/Create-Delight-Remake.zip')
parser.add_argument('--work',type=Path,default=root/'build-isolated/player-packaging/20261113-delight-joint')
parser.add_argument('--profile',type=Path)
args=parser.parse_args()
work=args.work.resolve()
stage=work/'incoming-mods'
stage.mkdir(parents=True,exist_ok=True)
profile=args.profile or Path(json.loads((root/'build-isolated/migration-1201/newera/build/deployment/pcl-installation-1201.json').read_text('utf-8-sig'))['versionDirectory'])
outer=args.base.resolve()
base_dir=work/'quests-work'
base_dir.mkdir(parents=True,exist_ok=True)
with ZipFile(outer) as source:
    (base_dir/'base.mrpack').write_bytes(source.read('modpack.mrpack'))
with ZipFile(base_dir/'base.mrpack') as source:
    (work/'reference-index.json').write_bytes(source.read('modrinth.index.json'))
    prefix='overrides/config/ftbquests/quests/'
    for entry in source.infolist():
        if not entry.filename.startswith(prefix) or entry.is_dir(): continue
        rel=PurePosixPath(entry.filename[len(prefix):])
        if rel.is_absolute() or '..' in rel.parts or '\\' in str(rel) or ':' in str(rel): raise ValueError(rel)
        dest=base_dir/'base-extracted'/Path(*rel.parts)
        dest.parent.mkdir(parents=True,exist_ok=True)
        with source.open(entry) as src, dest.open('wb') as out: shutil.copyfileobj(src,out)
def digest(path,alg):
    with path.open('rb') as f:return hashlib.file_digest(f,alg).hexdigest()
def acquire_locked_hotfix(patch,api_template):
    name=patch['filename'];target=stage/name
    def checked(path):
        return path.is_file() and path.stat().st_size==patch['fileSize'] and digest(path,'sha512')==patch['sha512'].lower()
    if checked(target):return target
    api_url=api_template.format(versionId=patch['versionId'])
    req=Request(api_url,headers={'User-Agent':'NewEra-Modpack-Installer/1.0','Accept':'application/json'})
    with closing(urlopen(req,timeout=30)) as resp:version=json.load(resp)
    if version.get('id')!=patch['versionId']:raise ValueError('Modrinth version ID mismatch: '+name)
    files=[row for row in version.get('files',[]) if row.get('filename')==name]
    if len(files)!=1:raise ValueError('Locked filename is not unique in Modrinth version: '+name)
    published=files[0]
    if published.get('size')!=patch['fileSize'] or published.get('hashes',{}).get('sha512','').lower()!=patch['sha512'].lower():
        raise ValueError('Modrinth published size/SHA-512 differs from release lock: '+name)
    part=target.with_name(target.name+'.partial')
    req=Request(published['url'],headers={'User-Agent':'NewEra-Modpack-Installer/1.0','Accept':'*/*'})
    with closing(urlopen(req,timeout=45)) as resp,part.open('wb') as out:
        count=0
        while chunk:=resp.read(256*1024):
            count+=len(chunk)
            if count>patch['fileSize']:raise ValueError('Locked file exceeds expected size: '+name)
            out.write(chunk)
    if not checked(part):
        part.unlink(missing_ok=True)
        raise ValueError('Downloaded hotfix failed locked size/SHA-512: '+name)
    part.replace(target)
    return target
index=json.loads((work/'reference-index.json').read_text('utf-8'))
rows=[row for row in index['files'] if row['path'].startswith('mods/') and row['path'].endswith('.jar')]
candidates={}
for folder in [profile/'mods',root/'build-isolated/migration-1201/dependencies']:
    for p in folder.glob('*.jar'):candidates.setdefault(p.stat().st_size,[]).append(p)
def checked(row,path):
    return path.is_file() and path.stat().st_size==row['fileSize'] and all(digest(path,alg)==row['hashes'][alg].lower() for alg in ('sha512','sha1') if alg in row['hashes'])
def aliases(url):
    u=urlsplit(url);result=[u.hostname]
    if u.hostname in {'mediafilez.forgecdn.net','media.forgecdn.net','edge.forgecdn.net'}:
        result.extend(['mediafilez.forgecdn.net','edge.forgecdn.net','media.forgecdn.net'])
    if u.scheme!='https':raise ValueError("source requires HTTPS")
    return [urlunsplit(('https',host,quote(unquote(u.path),safe="/@-_.~+"),u.query,"")) for host in dict.fromkeys(result)]
def fetch(row):
    name=PurePosixPath(row['path']).name
    assert row['path']=='mods/'+name and Path(name).name==name and ':' not in name
    target=stage/name
    if checked(row,target):return name,'verified-stage'
    for p in candidates.get(row['fileSize'],[]):
        if checked(row,p):return name,str(p)
    errors=[];urls=list(dict.fromkeys(u for entry in row['downloads'] for u in aliases(entry)))
    part=target.with_name(target.name+'.partial')
    for attempt in range(3):
        for url in urls:
            try:
                req=Request(url,headers={'User-Agent':'Mozilla/5.0 NewEra-Modpack-Installer/1.0','Accept':'*/*'})
                with closing(urlopen(req,timeout=24)) as resp,part.open('wb') as file:
                    count=0
                    while chunk:=resp.read(256*1024):
                        count+=len(chunk)
                        if count>row['fileSize']:raise ValueError("response exceeds manifest size")
                        file.write(chunk)
                if not checked(row,part):raise ValueError("size or published digest mismatch")
                part.replace(target);return name,'download'
            except Exception as exc:
                errors.append(type(exc).__name__+': '+str(exc));part.unlink(missing_ok=True)
        time.sleep(0.6*(attempt+1))
    return name,{'failed':errors[-4:]}
with ZipFile(outer) as z,ZipFile(io.BytesIO(z.read('modpack.mrpack'))) as nested:
    embedded={}
    for info in nested.infolist():
        parts=PurePosixPath(info.filename).parts
        if len(parts)==3 and parts[0]=='overrides' and parts[1]=='mods' and parts[2].endswith('.jar'):
            target=stage/parts[2];raw=nested.read(info);target.write_bytes(raw);embedded[target.name]={'from':'embedded','sha256':hashlib.sha256(raw).hexdigest()}
results={}
with ThreadPoolExecutor(max_workers=12) as pool:
    futures={pool.submit(fetch,r):r for r in rows}
    for f in as_completed(futures):
        n,status=f.result();results[n]=status
        if len(results)%10==0 or isinstance(status,dict):
            print(len(results),'/',len(rows),'failed',sum(isinstance(v,dict) for v in results.values()),flush=True)
hotfix_lock=json.loads((Path(__file__).with_name('hotfix-lock-r71.json')).read_text('utf-8-sig'))
if hotfix_lock.get('schema')!=1:raise ValueError('Unsupported r71 hotfix lock schema')
for patch in hotfix_lock['patches']:
    if patch['replaces'] not in results and patch['filename'] not in results:
        raise ValueError('Base acquisition does not contain the superseded mod: '+patch['replaces'])
    acquire_locked_hotfix(patch,hotfix_lock['versionApi'])
    results.pop(patch['replaces'],None)
    results[patch['filename']]='verified-stage'
record={'manifest':len(rows),'embedded':embedded,'rows':results,
        'lockedHotfixes':[{'versionId':p['versionId'],'filename':p['filename'],'sha512':p['sha512']} for p in hotfix_lock['patches']]}
record_path=work/'acquisition.json';record_temp=record_path.with_name(record_path.name+f'.tmp-{os.getpid()}')
record_temp.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
os.replace(record_temp,record_path)
print('ACQUISITION',dict(Counter('failed' if isinstance(v,dict) else 'found' if v not in {'verified-stage','download'} else v for v in results.values())),len(embedded),flush=True)
