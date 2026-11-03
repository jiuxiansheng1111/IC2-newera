"""Assemble r61 from the pinned r60 releases and verified upstream landscape lock."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'build-isolated/player-packaging/20261103-r61-landscape'
CLIENT = ROOT / 'dist/IC2-New-Era-Windows-1.20.1-r60-Complete.mrpack'
SERVER = ROOT / 'dist/IC2-New-Era-Server-1.20.1-r60-Voice.zip'
if not CLIENT.is_file():
    CLIENT = WORK / 'release-inputs' / CLIENT.name
if not SERVER.is_file():
    SERVER = WORK / 'release-inputs' / SERVER.name
CLIENT_SHA = 'BE1EFABF2813FB6CDA232BD301CD7C0A2204DB59B5DB865CACC340E94A8AFC73'
SERVER_SHA = '32060AAA5311D58EB922BAC1D0678E5296E95A3036DA90519316039F5FBDEEB6'
OLD_OWN_SHA = '6D56CF2F2E25790B647FDBBD431ACDE7151CCB9F6AFAE87281A9A22BAA31828D'
OWN = 'ic2newera-0.2.0-migration1201-all.jar'
CLIENT_NAME = 'IC2-New-Era-Windows-1.20.1-r61-Grove.mrpack'
SERVER_STEM = 'IC2-New-Era-Server-1.20.1-r61-UltraTerra-Voice'


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def file_sha(path: Path) -> str:
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest().upper()


def encoded(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def write_archive(output: Path, source: ZipFile | None, prefix: str,
                  replacements: dict[str, bytes], removed: set[str],
                  finish=None) -> dict:
    """Copy unchanged payloads in bounded buffers; verify CRC and every payload hash."""
    partial = output.with_name(output.name + '.partial')
    if output.exists() or partial.exists():
        raise FileExistsError(f'Preserve existing release: {output}')
    records = []
    written = set()
    with ZipFile(partial, 'w', ZIP_DEFLATED, compresslevel=1, allowZip64=True) as target:
        def put(name: str, data: bytes):
            rel = PurePosixPath(name)
            if rel.is_absolute() or '..' in rel.parts or '\\' in name or name.casefold() in written:
                raise ValueError('Unsafe or duplicate archive member: ' + name)
            written.add(name.casefold())
            entry = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = ZIP_DEFLATED
            entry.create_system = 3
            entry.external_attr = (0o100755 if name.endswith('.sh') else 0o100644) << 16
            target.writestr(entry, data)
            records.append({'path': name, 'sizeBytes': len(data), 'sha256': sha(data)})
        if source is not None:
            for entry in source.infolist():
                if entry.is_dir():
                    continue
                relative = entry.filename[len(prefix):] if prefix else entry.filename
                if relative in replacements or relative in removed:
                    continue
                if prefix and not entry.filename.startswith(prefix):
                    raise ValueError('Unexpected baseline archive prefix')
                name = (SERVER_STEM + '/' if prefix else '') + relative
                rel = PurePosixPath(name)
                if '..' in rel.parts or rel.is_absolute() or name.casefold() in written:
                    raise ValueError('Unsafe baseline member')
                written.add(name.casefold())
                copied = ZipInfo(name, date_time=entry.date_time)
                copied.compress_type = entry.compress_type
                copied.external_attr = entry.external_attr
                copied.create_system = entry.create_system
                digest = hashlib.sha256()
                with source.open(entry) as src, target.open(copied, 'w', force_zip64=True) as dst:
                    for chunk in iter(lambda: src.read(4 * 1024 * 1024), b''):
                        digest.update(chunk)
                        dst.write(chunk)
                records.append({'path': name, 'sizeBytes': entry.file_size, 'sha256': digest.hexdigest().upper()})
        for name, data in sorted(replacements.items()):
            put((SERVER_STEM + '/' if prefix else '') + name, data)
        if finish:
            for name, data in finish(records).items():
                put(SERVER_STEM + '/' + name, data)
    with ZipFile(partial) as archive:
        if archive.testzip() is not None:
            raise ValueError('Archive CRC failed')
        for record in records:
            with archive.open(record['path']) as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest().upper() != record['sha256']:
                    raise ValueError('Archive payload readback failed: ' + record['path'])
    partial.replace(output)
    digest = file_sha(output)
    output.with_name(output.name + '.sha256').write_text(digest + '  ' + output.name + '\n', 'ascii')
    return {'file': output.name, 'sha256': digest, 'sizeBytes': output.stat().st_size}


def main(jar: Path, expected: str) -> None:
    if file_sha(CLIENT) != CLIENT_SHA or file_sha(SERVER) != SERVER_SHA or file_sha(jar) != expected.upper():
        raise ValueError('Pinned baseline or first-party JAR differs')
    lock = json.loads((ROOT / 'scripts/landscape-lock-1201.json').read_text('utf-8'))
    terrain, grove = lock['terrain'], lock['resourcepack']
    terrain_bytes = (ROOT / 'build-isolated/migration-1201/dependencies' / terrain['file']).read_bytes()
    if sha(terrain_bytes) != terrain['sha256'] or file_sha(WORK / 'sources/Grove.zip') != grove['sha256']:
        raise ValueError('Upstream landscape payload differs from its lock')
    license_bytes = (ROOT / lock['licenseFile']).read_bytes()
    own = jar.read_bytes()
    spec = importlib.util.spec_from_file_location('server_builder', ROOT / 'scripts/package-dedicated-server-1201.py')
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    emc = builder.generated_projecte_config()
    if json.loads(emc)['entries'][-1] != {'item': 'minecraft:quartz', 'emc': 94}:
        raise ValueError('Unexpected quartz EMC override')
    status = ('r61：已构建并完成文件/依赖/归档静态核对。未启动本地Minecraft，未运行自动化测试。\n'
              '服务器实际安装、启动和新地图记录见项目 docs/delivery-r61-landscape-2026-11-03.md；'
              '地形、材质、联机及机器玩法仍由玩家实机验收。\n').encode('utf-8')
    client_notes = ('IC2：新纪元 Windows r61 / Minecraft 1.20.1 / Forge 47.4.28 / Java 17\n\n'
                    '用PCL导入本mrpack；首次导入需要联网下载第三方清单。共38个模组，'
                    '在r60基础上加入UltraTerraForged 1.0.3+1.20.1 Forge。\n'
                    '默认材质为Soartex Grove 1.0.4（舒适细致），沿用ITT 3.2光影。\n'
                    '石英改为94 EMC；直接水洗灵魂沙计入金粒后约5.18%平均收益，配方不变，'
                    '便宜沙子那条额外收益路径按用户要求保留。联机EMC以服务器配置为准。\n'
                    '地形模组是用户选定的后续维护版UltraTerraForged，注册ID不同于ReTerraForged。'
                    '单人新建世界可在Terrain页选择预设；不会自动改变已有地图。\n'
                    '保留YSM2.6.3、小地图、Freecam、输入法修复及原有整合依赖。\n'
                    '本包附带未修改的MIT许可UltraTerraForged官方JAR和完整版权说明；'
                    'Grove原ZIP由作者CDN下载。实际游戏效果仍待玩家验证。\n').encode('utf-8')
    with ZipFile(CLIENT) as source:
        if sha(source.read('overrides/mods/' + OWN)) != OLD_OWN_SHA:
            raise ValueError('Unexpected r60 client New Era JAR')
        index = json.loads(source.read('modrinth.index.json'))
        if len(index['files']) != 37 or any(x['path'].startswith('resourcepacks/') for x in index['files']):
            raise ValueError('Unexpected r60 manifest')
        index['files'].append({'path': 'resourcepacks/' + grove['file'],
                               'hashes': {'sha1': grove['sha1'], 'sha512': grove['sha512']},
                               'env': {'client': 'required', 'server': 'unsupported'},
                               'downloads': [grove['source']], 'fileSize': grove['sizeBytes']})
        index.update(versionId='0.2.0-windows.20261103-r61-landscape', name='IC2：新纪元 Windows 完整整合包 r61',
                     summary='38个模组，含UltraTerraForged新地形、Grove材质、ITT光影和石英94 EMC配置。')
        notes = source.read('overrides/IC2-NewEra-info/THIRD-PARTY-NOTICES.txt')
        notes += ('\n\nr61 additions: Soartex Grove 1.0.4: ' + grove['versionUrl'] + '\n'
                  'UltraTerraForged 1.0.3+1.20.1 Forge: ' + terrain['sourceVersionUrl'] + '\n'
                  'The unmodified upstream UltraTerraForged JAR is redistributed under MIT; '
                  'see LICENSE-UltraTerraForged-MIT.txt for all upstream copyright notices.\n').encode('utf-8')
        replacements = {'modrinth.index.json': encoded(index), 'overrides/mods/' + OWN: own,
                        'overrides/mods/' + terrain['file']: terrain_bytes,
                        'overrides/options.txt': ('lang:zh_cn\nguiScale:0\nresourcePacks:["vanilla","file/' + grove['file'] + '"]\n').encode('utf-8'),
                        'overrides/config/ProjectE/custom_emc.json': emc,
                        'overrides/IC2-NewEra-info/WINDOWS-LANDSCAPE-r61.txt': client_notes,
                        'overrides/IC2-NewEra-info/README-zh_CN.txt': client_notes,
                        'overrides/IC2-NewEra-info/README-en_US.txt': b'IC2: New Era r61, Minecraft 1.20.1 Forge 47.4.28. Import with PCL. 38 mods; Grove 1.0.4, ITT 3.2, UltraTerraForged 1.0.3+1.20.1; quartz 94 EMC. Gameplay acceptance pending.\n',
                        'overrides/IC2-NewEra-info/PLAYTEST-STATUS.txt': status,
                        'overrides/IC2-NewEra-info/THIRD-PARTY-NOTICES.txt': notes,
                        'overrides/IC2-NewEra-info/LICENSE-UltraTerraForged-MIT.txt': license_bytes}
        client_result = write_archive(ROOT / 'dist' / CLIENT_NAME, source, '', replacements,
                                      {'overrides/IC2-NewEra-info/WINDOWS-COMPLETE-r60.txt'})
    server_notes = ('# IC2：新纪元 r61 完整服务器（已内置固定问候语音）\n\n'
                    'Minecraft 1.20.1 / Forge 47.4.28 / Java17，共27个服务端模组。'
                    '新增UltraTerraForged 1.0.3+1.20.1 Forge，含r60已有修复、机器与YSM2.6.3。\n\n'
                    '全新部署：解压本目录，使用已有Java17；自行阅读并接受EULA后运行Start.sh、Start.bat或server.jar。'
                    '固定语音JAR及两行启动参数已保留，不用重复安装。\n\n'
                    '默认新地图为`' + lock['newServerWorld'] + '`，'
                    '`level-type=ultraterraforged:ultraterraforged`。首次启动自动导出地形预设数据包。'
                    '不要把此完整包的默认配置直接覆盖已有服务器；更新前正常停服并完整备份，保留旧地图。\n\n'
                    '石英94 EMC，原水洗配方不变，直接水洗灵魂沙约5.18%平均收益。'
                    '已有服务器需合并该项，保留其他ProjectE自定义条目。\n\n'
                    '客户端使用r61-Grove整合包；Grove及光影只在客户端。新部署仍需玩家验收。\n').encode('utf-8')
    with ZipFile(SERVER) as source:
        prefixes = {n.split('/', 1)[0] for n in source.namelist()}
        if len(prefixes) != 1:
            raise ValueError('Unexpected server layout')
        prefix = prefixes.pop() + '/'
        if sha(source.read(prefix + 'mods/' + OWN)) != OLD_OWN_SHA:
            raise ValueError('Unexpected r60 server New Era JAR')
        manifest = json.loads(source.read(prefix + 'server-manifest.json'))
        for entry in manifest['mods']:
            if entry['modId'] == 'ic2newera':
                entry.update(sha256=expected.upper(), sizeBytes=len(own))
        manifest['mods'].append({**terrain, 'lock': 'landscape',
                                 'containedMods': [{'modId': terrain['modId'], 'metadataVersion': terrain['version']}]})
        for dependency, version_range, selected in [('forge', '[47.1,)', '47.4.28'), ('minecraft', '[1.20.1]', '1.20.1')]:
            manifest['mandatoryServerDependencyClosure'].append({'from': 'ultraterraforged', 'requires': dependency,
                'versionRange': version_range, 'side': 'BOTH', 'jar': terrain['file'], 'selectedVersion': selected,
                'present': True, 'versionRangeSatisfied': True})
        manifest.update(package=SERVER_STEM, landscape=lock,
                        manifestNote='Payloads exclude this manifest and artifact-audit.json; artifact-audit.json hashes this manifest.')
        properties = source.read(prefix + 'server.properties').decode('utf-8-sig')
        if 'level-name=' in properties or 'level-type=' in properties:
            raise ValueError('Unexpected baseline level settings')
        properties += '\nlevel-name=' + lock['newServerWorld'] + '\nlevel-type=ultraterraforged:ultraterraforged\n'
        replacements = {'mods/' + OWN: own, 'mods/' + terrain['file']: terrain_bytes,
                        'config/ProjectE/custom_emc.json': emc, 'server.properties': properties.encode('utf-8'),
                        'README.zh-CN.md': server_notes, 'README.md': server_notes,
                        'FIXED-VOICE-README.zh-CN.txt': server_notes,
                        'LICENSE-UltraTerraForged-MIT.txt': license_bytes, 'PLAYTEST-STATUS.txt': status}
        def finish(records):
            lead = SERVER_STEM + '/'
            manifest['files'] = [{**r, 'path': r['path'][len(lead):]} for r in records]
            data = encoded(manifest)
            audit = {'serverManifestSha256': sha(data), 'newEraSha256': expected.upper(),
                     'terrainSha256': terrain['sha256'], 'freshDeploymentRuntimeVerified': False,
                     'localGameStarted': False, 'automatedTestsRun': False}
            return {'server-manifest.json': data, 'artifact-audit.json': encoded(audit)}
        server_result = write_archive(ROOT / 'dist' / (SERVER_STEM + '.zip'), source, prefix, replacements,
                                      {'server-manifest.json', 'artifact-audit.json'}, finish)
    update_notes = ('r61模组更新附件：正常停服并备份后替换mods内两个JAR，不要保留重复版本。\n'
                    '本附件不覆盖世界、服务器属性或ProjectE配置。Grove只装客户端。\n'
                    '指定服务器已由助手单独配置时不必重复操作；全新开服请用r61-UltraTerra-Voice完整服务器包。\n'
                    '其他现有服务器：把minecraft:quartz合并为94 EMC；若要新地图，保留旧地图并使用新的level-name，'
                    'level-type设为ultraterraforged:ultraterraforged，再正常启动。\n').encode('utf-8')
    update_result = write_archive(ROOT / 'dist/IC2-New-Era-Server-1.20.1-r61-Update.zip', None, '',
        {'mods/' + OWN: own, 'mods/' + terrain['file']: terrain_bytes,
         'LICENSE-UltraTerraForged-MIT.txt': license_bytes, 'UPDATE-README.zh-CN.txt': update_notes}, set())
    audit = {'revision': 'r61', 'newEraSha256': expected.upper(), 'terrainSha256': terrain['sha256'],
             'groveSha256': grove['sha256'], 'clientMods': 38, 'serverMods': 27,
             'quartzEmc': 94, 'archives': [client_result, server_result, update_result],
             'archivePayloadReadbackVerified': True, 'localGameStarted': False, 'automatedTestsRun': False}
    (WORK / 'package-audit.json').write_bytes(encoded(audit))
    print(json.dumps(audit, ensure_ascii=True))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    args = parser.parse_args()
    main(args.jar, args.sha256)
