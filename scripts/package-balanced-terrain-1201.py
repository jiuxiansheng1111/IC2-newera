"""Package the balanced world, Spawn, Re:Avaritia, Tinkers and client CMM for r62."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'build-isolated/player-packaging/20261103-r62-balanced-terrain'
CLIENT = ROOT / 'dist/IC2-New-Era-Windows-1.20.1-r61-Grove.mrpack'
SERVER = ROOT / 'dist/IC2-New-Era-Server-1.20.1-r61-UltraTerra-Voice.zip'
if not CLIENT.is_file():
    CLIENT = WORK / 'release-inputs' / CLIENT.name
if not SERVER.is_file():
    SERVER = WORK / 'release-inputs' / SERVER.name
WORLD = 'world-ultraterra-balanced-r62-20261103'
PRESET_NAME = 'ic2-new-era-balanced-1201'
OWN = 'ic2newera-0.2.0-migration1201-all.jar'


def main() -> None:
    spec = importlib.util.spec_from_file_location('r61', ROOT / 'scripts/package-landscape-release-1201.py')
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    for path, digest in [(CLIENT, '98E6FAAA34FC3700643843606653668A358E6A67138321C26D460BE2E16754C6'),
                         (SERVER, 'D87DE8595233D6523C5C55D1531FB58AD74BF2C3BF61A37933A902C0CC179A3B')]:
        if builder.file_sha(path) != digest:
            raise ValueError('Pinned r61 package differs: ' + str(path))
    content = json.loads((ROOT / 'scripts/world-content-lock-1201.json').read_text('utf-8'))
    mod_files, resource_files = {}, {}
    for group, target in [('mods', mod_files), ('resourcepacks', resource_files)]:
        for item in content[group]:
            data = (WORK / 'sources' / item['file']).read_bytes()
            if builder.sha(data) != item['sha256'] or len(data) != item['sizeBytes']:
                raise ValueError('Upstream file differs: ' + item['file'])
            target[group + '/' + item['file']] = data
    own = (ROOT / 'build-isolated/migration-1201/newera/build/libs' / OWN).read_bytes()
    if builder.sha(own) != 'F09DE5752410C79075BB817BFBD1A0D0D86D58AA57616BA5F24BA2A1129C5C29':
        raise ValueError('Unexpected r62 New Era JAR')
    mod_files['mods/' + OWN] = own
    preset = (ROOT / 'scripts/presets' / (PRESET_NAME + '.json')).read_bytes()
    settings = json.loads(preset)
    features = [v for c in ('landforms', 'coasts', 'caveFeatures', 'oceans')
                for v in settings[c].values() if isinstance(v, dict) and 'enabled' in v]
    if len(features) != 40 or not all(v['enabled'] for v in features):
        raise ValueError('All 40 landscape features must remain enabled')
    if any(settings['terrain'][t]['weight'] <= 0 for t in
           ('steppe', 'plains', 'hills', 'dales', 'plateau', 'badlands', 'torridonian', 'mountains', 'volcano')):
        raise ValueError('All nine base terrain types need nonzero weight')
    config = {
        'config/ultraterraforged/modpack-presets/' + PRESET_NAME + '.json': preset,
        'config/ultraterraforged/modpack.json': builder.encoded({
            'defaultPreset': PRESET_NAME, 'showBuiltinPresets': True, 'useAsDefaultWorldType': True}),
    }
    notes = ('r62：新纪元·全地形均衡、Spawn、无尽贪婪、匠魂与怪物娘化\n\n'
             '9类基础地形均保留生成权重，40项特色地貌全部开启。平原与草原增加，火山和崎岖山地适当减少；'
             '大陆尺度2400、地形区域900、生物群系尺度200，冷热干湿范围完整保留。权重不等于实测面积占比，'
             '不能保证所有地貌都在出生点附近。\n\n'
             '客户端：将config目录放到1.20.1 Forge实例根目录。创建世界时可选“新纪元·全地形均衡”，'
             '也可继续选其他预设并编辑。已有r61客户端需要更新新增模组及新纪元JAR，再连接服务器。\n'
             '新增Spawn 4.0.8、Re:Avaritia 1.4.2、匠魂3.11.2.166及Mantle 1.11.104，均为1.20.1 Forge正式版。'
             '这些内容模组客户端与服务器都需要安装，匠魂前置已包含。客户端共42个模组，完整服务器包31个。\n'
             '娘化使用CMMRP 1.0与同版EMF修复包，仅安装客户端。Grove保留，材质优先级从低到高为'
             '原版、Grove、CMM主包、CMM EMF修复包；使用已安装的EMF/ETF，不需要OptiFine。'
             '娘化只改支持的原版生物外观，不新增可交互生物，不保证覆盖Spawn生物；末影龙娘化不在本版EMF支持范围内。\n'
             '服务器：正常停服并备份，将server-preset.json放到config/ultraterraforged/。'
             '首次生成新地图前，将server.properties的level-type设为ultraterraforged:ultraterraforged，'
             'level-name改为一个尚不存在的新目录名。可复现种子为2026110362。保留旧世界即可回退。'
             '已存在level.dat的世界不会重新读取此JSON并重生成地形。\n\n'
             '新纪元JAR更新内置AI，并修复Spawn群系在UTF新世界预设导出时尚未注册而导致的启动失败；'
             '修复仅作用于导出的临时原版查找表，正常世界仍加载Spawn群系。协议和ProcessGraph沿用r61。新增模组使用其原生玩法与配方，'
             '未新增机器人自动使用匠魂或无尽机器的能力。Grove、CMM与ITT仅在客户端，'
             '石英94 EMC沿用。未启动本地游戏，实际地形观感由玩家验收；指定服务器部署状态见项目交付记录。\n')
    note_bytes = notes.encode('utf-8')
    validation = ('r62验收范围：已核对来源哈希、模组依赖和打包文件。新纪元已离线构建，未运行自动化测试。\n'
                  '含Spawn与UltraTerraForged的临时预设导出注册表兼容修复。指定服务器实际启动结果见'
                  'docs/delivery-r62-balanced-content-2026-11-03.md。\n'
                  '没有启动本地Minecraft；客户端模型、光影、地形观感及完整玩法仍待玩家实机验收。\n').encode('utf-8')
    notices = ('r62 additions (unmodified upstream files):\n'
               'Spawn by Ninni: All Rights Reserved; official distribution: https://modrinth.com/mod/spawn-mod/version/Iw5SeMQX\n'
               'Re:Avaritia by Nova Committee: code MIT, assets CC BY-NC-SA 4.0; https://github.com/Nova-Committee/Re-Avaritia\n'
               "Tinkers' Construct and Mantle by Slime Knights: MIT; https://modrinth.com/mod/tinkers-construct and https://modrinth.com/mod/mantle\n"
               'Cute Mob Models Resource Pack by Emmie_4 and team: GPL-3.0-only; https://modrinth.com/resourcepack/cute-mob-models-resource-pack/version/edCc04l8\n'
               'Windows mrpack downloads third-party additions from the original author CDN. The local server archive is for this private server deployment; do not republish restricted binaries.\n').encode('utf-8')
    with ZipFile(CLIENT) as source:
        index = json.loads(source.read('modrinth.index.json'))
        for group in ('mods', 'resourcepacks'):
            for item in content[group]:
                index['files'].append({'path': group + '/' + item['file'],
                    'hashes': {'sha1': item['sha1'], 'sha512': item['sha512']},
                    'env': {'client': 'required', 'server': 'required' if group == 'mods' else 'unsupported'},
                    'downloads': [item['source']], 'fileSize': item['sizeBytes']})
        if len({f['path'] for f in index['files']}) != len(index['files']):
            raise ValueError('Duplicate manifest path')
        index.update(versionId='0.2.0-windows.20261103-r62-content', name='IC2：新纪元 Windows 完整整合包 r62',
                     summary='42个模组：均衡地形、Spawn、无尽贪婪、匠魂；Grove叠加怪物娘化，保留ITT和石英94 EMC。')
        replacements = {'overrides/' + k: v for k, v in config.items()}
        active_packs = ['vanilla', 'file/Soartex Grove 1.0.4.zip'] + ['file/' + p['file'] for p in content['resourcepacks']]
        replacements.update({'modrinth.index.json': builder.encoded(index), 'overrides/TERRAIN-r62.zh-CN.txt': note_bytes,
            'overrides/mods/' + OWN: own,
            'overrides/options.txt': ('lang:zh_cn\nguiScale:0\nresourcePacks:' + json.dumps(active_packs, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8'),
            'overrides/IC2-NewEra-info/README-zh_CN.txt': note_bytes,
            'overrides/IC2-NewEra-info/PLAYTEST-STATUS.txt': validation,
            'overrides/IC2-NewEra-info/README-en_US.txt': b'IC2 New Era r62: MC 1.20.1 Forge 47.4.28, 42 mods. Spawn, Re:Avaritia, Tinkers Construct + Mantle, balanced UltraTerraForged. Grove + CMM + CMM EMF fix are client only. Import with PCL; initial official CDN downloads require Internet. Client gameplay acceptance pending.\n',
            'overrides/IC2-NewEra-info/THIRD-PARTY-NOTICES.txt': source.read('overrides/IC2-NewEra-info/THIRD-PARTY-NOTICES.txt') + b'\n' + notices})
        client = builder.write_archive(ROOT / 'dist/IC2-New-Era-Windows-1.20.1-r62-Content-Grove-CMM.mrpack',
                                       source, '', replacements, {'overrides/IC2-NewEra-info/WINDOWS-LANDSCAPE-r61.txt'})
    builder.SERVER_STEM = 'IC2-New-Era-Server-1.20.1-r62-Content-Voice'
    with ZipFile(SERVER) as source:
        prefix = 'IC2-New-Era-Server-1.20.1-r61-UltraTerra-Voice/'
        manifest = json.loads(source.read(prefix + 'server-manifest.json'))
        manifest.update(package=builder.SERVER_STEM)
        for entry in manifest['mods']:
            if entry['modId'] == 'ic2newera':
                entry.update(sha256=builder.sha(own), sizeBytes=len(own))
        manifest['mods'].extend({**item, 'lock': 'world-content',
            'containedMods': [{'modId': item['modId'], 'metadataVersion': item['version']}]} for item in content['mods'])
        selected = {'forge': '47.4.28', 'minecraft': '1.20.1', 'mantle': '1.11.104'}
        for dep in content['mandatoryDependencies']:
            manifest['mandatoryServerDependencyClosure'].append({'from': dep['from'], 'requires': dep['modId'],
                'versionRange': dep['versionRange'], 'side': dep['side'], 'selectedVersion': selected[dep['modId']],
                'present': True, 'versionRangeSatisfied': True})
        manifest['landscape'].update(revision='r62', newServerWorld=WORLD,
                                     balancedPreset={'file': PRESET_NAME + '.json', 'sha256': builder.sha(preset)})
        properties = source.read(prefix + 'server.properties').decode('utf-8').replace(
            'level-name=world-ultraterra-r61-20261103', 'level-name=' + WORLD)
        properties += 'level-seed=2026110362\n'
        replacements = {**config, **mod_files, 'config/ultraterraforged/server-preset.json': preset,
                        'PLAYTEST-STATUS.txt': validation,
                        'THIRD-PARTY-r62.txt': notices,
                        'server.properties': properties.encode('utf-8'), 'TERRAIN-r62.zh-CN.txt': note_bytes}
        for readme in ('README.md', 'README.zh-CN.md', 'FIXED-VOICE-README.zh-CN.txt'):
            prior = source.read(prefix + readme).decode('utf-8-sig')
            replacements[readme] = (notes + '\n原基础包说明（模组及语音部分继续适用）：\n' + prior).encode('utf-8')
        def finish(records):
            lead = builder.SERVER_STEM + '/'
            manifest['files'] = [{**r, 'path': r['path'][len(lead):]} for r in records]
            data = builder.encoded(manifest)
            return {'server-manifest.json': data, 'artifact-audit.json': builder.encoded({
                'serverManifestSha256': builder.sha(data), 'presetSha256': builder.sha(preset),
                'revision': 'r62', 'modPayloadsUnchangedFromR61': False, 'newEraSha256': builder.sha(own),
                'localGameStarted': False, 'automatedTestsRun': False})}
        server = builder.write_archive(ROOT / 'dist' / (builder.SERVER_STEM + '.zip'), source, prefix,
                                       replacements, {'server-manifest.json', 'artifact-audit.json'}, finish)
    client_patch = builder.write_archive(ROOT / 'dist/IC2-New-Era-Client-r62-Update.zip', None, '',
                                   {**config, **mod_files, **resource_files, 'THIRD-PARTY-r62.txt': notices,
                                    'README.zh-CN.txt': note_bytes + '老客户端先退出游戏，解压到隔离实例根目录；替换同名新纪元JAR。保留自己的options.txt，进入资源包设置按上方顺序启用娘化。全新玩家请导入完整mrpack。\n'.encode('utf-8')}, set())
    server_patch = builder.write_archive(ROOT / 'dist/IC2-New-Era-Server-r62-Update.zip', None, '',
                                   {**mod_files, 'config/ultraterraforged/server-preset.json': preset,
                                    'THIRD-PARTY-r62.txt': notices, 'README.zh-CN.txt': note_bytes}, set())
    receipt = {'revision': 'r62', 'archives': [client, server, client_patch, server_patch], 'presetSha256': builder.sha(preset),
               'newEraSha256': builder.sha(own), 'clientMods': 42, 'serverMods': 31,
               'archivePayloadReadbackVerified': True, 'modsUnchanged': False,
               'localGameStarted': False, 'automatedTestsRun': False}
    WORK.mkdir(parents=True, exist_ok=True)
    (WORK / 'package-audit.json').write_bytes(builder.encoded(receipt))
    print(json.dumps(receipt, ensure_ascii=True))


if __name__ == '__main__':
    main()
