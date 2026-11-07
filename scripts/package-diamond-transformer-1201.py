"""Update the two pinned r64 private archives with the same audited r65 mod."""
from pathlib import Path
from zipfile import ZipFile
import importlib.util
import json

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'build-isolated/player-packaging/20261107-r65-compat'
WORK.mkdir(parents=True, exist_ok=True)
DIST = ROOT / 'dist'
CLIENT_OLD = 'IC2-New-Era-Windows-1.20.1-r64-UI-AI-Grove-CMM.mrpack'
SERVER_OLD = 'IC2-New-Era-Server-1.20.1-r64-UI-AI-Voice'
CLIENT_NEW = 'IC2-New-Era-Windows-1.20.1-r65-Grove-CMM.mrpack'
SERVER_NEW = 'IC2-New-Era-Server-1.20.1-r65-Voice'
spec = importlib.util.spec_from_file_location('archives', ROOT / 'scripts/package-landscape-release-1201.py')
archives = importlib.util.module_from_spec(spec)
spec.loader.exec_module(archives)
own = 'ic2newera-0.2.0-migration1201-all.jar'
previous_sha = 'E83E7F8A3828D14134D85027A592FB254134F03B3E10F18D0F8014B797A43F94'
own_sha = 'C9AABC60B63D1ED6CB5E856FA74479E6A375D8467065BBFC636FB6F1B8E565C2'
jar = (DIST / 'IC2-New-Era-r65-Jars' / own).read_bytes()
assert archives.sha(jar) == own_sha
note = (
    'IC2 新纪元 r65 / Minecraft 1.20.1 / Forge 47.4.28 / Java 17\n'
    '1 个钻石或工业钻石在 IC2 粉碎机或 Create 粉碎轮中产出 1 份普通钻石粉，钻石矿石产出 2 份。'
    '4 份钻石粉和 5 份红石合成 9 份能量水晶粉，压缩后得到 1 个能量水晶。'
    '旧杂质和洗净钻石粉可在热能离心机回收。新纪元普通硅可在 AE2 压印器制作硅板。\n'
    '万能变压器五点面输入，五个单点面输出。手持 IC2 普通扳手、电动扳手或 Create 扳手右击供电面即可转向。'
    '旧机器的五点输入面位于旧主输出面的正对面。使用侧面供电的旧线路请将五点面转向电源，同时检查单点面的下游耐压。\n'
    '客户端和服务器请同步替换 mods/' + own + '，网络协议为 36。已有整合包只需替换这一份 JAR。'
    '新玩家用 PCL 导入完整 mrpack，完整服务器 ZIP 用于新建实例。\n'
    '客户端 42 个模组，完整服务器 31 个。Grove/CMM 和 ITT 在客户端，服务器另含固定语音。'
    '构建和文件核验已完成，玩家操作留待实机验收。部署见 docs/delivery-r65-2026-11-07.md。\n'
).encode('utf-8')


def baseline(name, digest):
    path = DIST / name
    if not path.is_file():
        path = WORK / 'release-inputs' / name
    if archives.file_sha(path) != digest:
        raise ValueError('Baseline differs: ' + name)
    return path


with ZipFile(baseline(CLIENT_OLD, '87ED924835406B34396FBDEAE053CD72A94BDA32C36FBA26B7BA4BADF5E7A19D')) as source:
    assert archives.sha(source.read('overrides/mods/' + own)) == previous_sha
    index = json.loads(source.read('modrinth.index.json'))
    index.update(versionId='0.2.0-windows.20261107-r65', name='IC2：新纪元 Windows 完整整合包 r65',
                 summary='钻石直接打粉、硅板兼容、六面扳手变压器。42 个模组，含 Grove/CMM、ITT 与 Spawn 汉化。')
    client = archives.write_archive(DIST / CLIENT_NEW, source, '', {
        'overrides/mods/' + own: jar, 'modrinth.index.json': archives.encoded(index),
        'overrides/IC2-NewEra-info/README-zh_CN.txt': note,
        'overrides/IC2-NewEra-info/PLAYTEST-STATUS.txt': note}, {'overrides/IC2-NewEra-info/UI-AI-r64.zh-CN.txt'})

archives.SERVER_STEM = SERVER_NEW
with ZipFile(baseline(SERVER_OLD + '.zip', 'EF53A8B7B3CAA3703BFC656FC389385D2664DA21586779E547F6D652758E3B5E')) as source:
    prefix = SERVER_OLD + '/'
    manifest = json.loads(source.read(prefix + 'server-manifest.json'))
    entry = next(m for m in manifest['mods'] if m['modId'] == 'ic2newera')
    assert len(manifest['mods']) == 31 and entry['sha256'] == previous_sha
    assert archives.sha(source.read(prefix + 'mods/' + own)) == previous_sha
    entry.update(sha256=own_sha, sizeBytes=len(jar), source='First-party r65 build')
    manifest['package'] = SERVER_NEW

    def finish(records):
        manifest['files'] = [dict(record, path=record['path'].removeprefix(SERVER_NEW + '/')) for record in records]
        data = archives.encoded(manifest)
        info = dict(revision='r65', serverManifestSha256=archives.sha(data), mods=31, newEraSha256=own_sha,
                    deploymentRecord='docs/delivery-r65-2026-11-07.md')
        return {'server-manifest.json': data, 'artifact-audit.json': archives.encoded(info)}

    server = archives.write_archive(DIST / (SERVER_NEW + '.zip'), source, prefix,
                                    {'mods/' + own: jar, 'PLAYTEST-STATUS.txt': note, 'UPDATE-r65.zh-CN.txt': note},
                                    {'server-manifest.json', 'artifact-audit.json', 'UI-AI-r64.zh-CN.txt'}, finish)

with ZipFile(DIST / CLIENT_NEW) as client_zip:
    paths = [f['path'] for f in index['files'] if f['path'].startswith('mods/')]
    paths += [n.removeprefix('overrides/') for n in client_zip.namelist() if n.startswith('overrides/mods/') and n.endswith('.jar')]
    assert len(paths) == len(set(paths)) == 42
(WORK / 'package-audit.json').write_bytes(archives.encoded(
    dict(revision='r65', client=client, server=server, clientMods=42, serverMods=31, newEraSha256=own_sha)))
