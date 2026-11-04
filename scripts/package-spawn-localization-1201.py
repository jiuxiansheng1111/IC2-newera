"""Private r63 packages: replace only Spawn with the language-only localized JAR."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'build-isolated/player-packaging/20261105-r63-spawn-zh'
SPAWN = 'spawn-4.0.8-1.20.1.jar'
CLIENT_OLD = 'IC2-New-Era-Windows-1.20.1-r62-Content-Grove-CMM.mrpack'
SERVER_OLD = 'IC2-New-Era-Server-1.20.1-r62-Content-Voice'
CLIENT_NEW = 'IC2-New-Era-Windows-1.20.1-r63-Spawn-ZH-Grove-CMM.mrpack'
SERVER_NEW = 'IC2-New-Era-Server-1.20.1-r63-Spawn-ZH-Voice'


def main():
    spec = importlib.util.spec_from_file_location('archives', ROOT / 'scripts/package-landscape-release-1201.py')
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    paths = []
    for name, digest in [(CLIENT_OLD, 'FE455B3A83F431B4210D02B669E1449443DB9BBC384CE68041A16B6E48823B8F'),
                         (SERVER_OLD + '.zip', '1F4B67C982C8BB0C2DCF33731A3A298F141DEF4553F5FE5B83AD0FC13F02C612')]:
        path = ROOT / 'dist' / name
        if not path.exists():
            path = WORK / 'release-inputs' / name
        if builder.file_sha(path) != digest:
            raise ValueError('Pinned r62 baseline differs: ' + name)
        paths.append(path)
    lock = json.loads((ROOT / 'scripts/player-enhancements-lock-1201.json').read_text('utf-8'))
    spawn = next(m for m in lock['mods'] if m['modId'] == 'spawn')
    override = spawn['localOverride']
    jar = (ROOT / 'build-isolated/migration-1201/dependencies' / override['relativePath']).read_bytes()
    if builder.sha(jar) != override['sha256'] or len(jar) != override['sizeBytes']:
        raise ValueError('Localized Spawn differs from lock')
    note = ('r63：Spawn 生机遍布简体中文补全\n'
            'Minecraft 1.20.1 / Forge 47.4.28；原有模组与世界设置沿用r62。\n'
            'Spawn 4.0.8 新增947条简体中文：生物、物品、方块、进度、字幕、图鉴与说明。'
            '原5010项JAR内容保持不变；代码、配方、协议、注册表不变。\n'
            '已有玩家退出游戏后，在mods中替换spawn-4.0.8-1.20.1.jar，同一模组只保留一个JAR。'
            '游戏语言选择简体中文。不需要额外启用资源包。仅服务器安装汉化不会替代客户端语言资源。\n'
            '完整客户端仍含42个模组及Grove/CMM/ITT；服务器包含31个模组，'
            '指定线上服另保留Cloth Config与Xaero。服务器包用于全新部署；更新现有服只替换Spawn，正常重启，保留现有地图。\n'
            '已静态核对语言键、格式占位符、全部原始JAR成员和归档内容；'
            '实际重载记录见docs/delivery-r63-spawn-zh-2026-11-05.md，不代表全部玩法验收。\n').encode('utf-8')
    notices = ('\nr63 local customization: Spawn 4.0.8 by Ninni (All Rights Reserved).\n'
               'The official binary is retained except for the added assets/spawn/lang/zh_cn.json.\n'
               'Upstream download/hash remains in the source lock; localized output has a separate hash.\n'
               'These local archives are for this private installation; do not publish restricted third-party binaries.\n'
               'Public source sync includes the translation and builder only.\n').encode('utf-8')
    with ZipFile(paths[0]) as source:
        index = json.loads(source.read('modrinth.index.json'))
        entries = [f for f in index['files'] if f['path'] == 'mods/' + SPAWN]
        if len(entries) != 1 or entries[0]['hashes']['sha1'] != spawn['sha1']:
            raise ValueError('Unexpected baseline Spawn manifest entry')
        index['files'] = [f for f in index['files'] if f['path'] != 'mods/' + SPAWN]
        index.update(versionId='0.2.0-windows.20261105-r63-spawn-zh',
                     name='IC2：新纪元 Windows 完整整合包 r63',
                     summary='42个模组，Spawn完整简体汉化；均衡地形、无尽贪婪、匠魂与Grove/CMM/ITT沿用。')
        replacements = {'modrinth.index.json': builder.encoded(index),
                        'overrides/mods/' + SPAWN: jar,
                        'overrides/IC2-NewEra-info/README-zh_CN.txt': note,
                        'overrides/IC2-NewEra-info/SPAWN-r63.zh-CN.txt': note,
                        'overrides/IC2-NewEra-info/PLAYTEST-STATUS.txt': note,
                        'overrides/IC2-NewEra-info/THIRD-PARTY-NOTICES.txt': source.read('overrides/IC2-NewEra-info/THIRD-PARTY-NOTICES.txt') + notices}
        client = builder.write_archive(ROOT / 'dist' / CLIENT_NEW, source, '', replacements, set())
    builder.SERVER_STEM = SERVER_NEW
    with ZipFile(paths[1]) as source:
        prefix = SERVER_OLD + '/'
        manifest = json.loads(source.read(prefix + 'server-manifest.json'))
        manifest['package'] = SERVER_NEW
        entry = next(m for m in manifest['mods'] if m['modId'] == 'spawn')
        entry['upstream'] = {k: entry.pop(k) for k in ('source', 'sizeBytes', 'sha256', 'sha1', 'sha512')}
        entry.update(source='Local language-only patch; see upstream for official download',
                     sizeBytes=len(jar), sha256=builder.sha(jar),
                     localization={'language': 'zh_cn', 'keys': 947, 'addedResource': 'assets/spawn/lang/zh_cn.json',
                                   'originalPayloadsUnchanged': True})
        replacements = {'mods/' + SPAWN: jar, 'SPAWN-r63.zh-CN.txt': note, 'PLAYTEST-STATUS.txt': note,
                        'THIRD-PARTY-r63.txt': notices}
        def finish(records):
            manifest['files'] = [dict(r, path=r['path'][len(SERVER_NEW) + 1:]) for r in records]
            data = builder.encoded(manifest)
            audit = {'revision': 'r63', 'serverManifestSha256': builder.sha(data), 'mods': len(manifest['mods']),
                     'spawnSha256': builder.sha(jar), 'baselineWorldConfigurationUnchanged': True,
                     'automatedTestsRun': False, 'deploymentRecord': 'docs/delivery-r63-spawn-zh-2026-11-05.md'}
            return {'server-manifest.json': data, 'artifact-audit.json': builder.encoded(audit)}
        server = builder.write_archive(ROOT / 'dist' / (SERVER_NEW + '.zip'), source, prefix,
                                       replacements, {'server-manifest.json', 'artifact-audit.json'}, finish)
    with ZipFile(ROOT / 'dist' / CLIENT_NEW) as z:
        index = json.loads(z.read('modrinth.index.json'))
        mods = [f['path'] for f in index['files'] if f['path'].startswith('mods/')]
        mods += [n[len('overrides/'):] for n in z.namelist() if n.startswith('overrides/mods/') and n.endswith('.jar')]
        if len(mods) != 42 or len(set(mods)) != 42:
            raise ValueError('Client must contain exactly 42 unique mod paths')
    receipt = {'revision': 'r63', 'client': client, 'server': server, 'clientMods': 42, 'serverMods': 31,
               'spawnSha256': builder.sha(jar), 'originalGameplayUnchanged': True}
    (WORK / 'package-audit.json').write_bytes(builder.encoded(receipt))
    print(json.dumps(receipt, ensure_ascii=True), flush=True)


if __name__ == '__main__':
    main()
