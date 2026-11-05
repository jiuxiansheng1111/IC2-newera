"""Build private r64 releases from pinned r63 archives and the reviewed New Era JAR."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'build-isolated/player-packaging/20261105-r64-ui-text'
OWN = 'ic2newera-0.2.0-migration1201-all.jar'
OWN_SHA = 'E83E7F8A3828D14134D85027A592FB254134F03B3E10F18D0F8014B797A43F94'
PREVIOUS_OWN_SHA = 'F09DE5752410C79075BB817BFBD1A0D0D86D58AA57616BA5F24BA2A1129C5C29'
CLIENT_OLD = 'IC2-New-Era-Windows-1.20.1-r63-Spawn-ZH-Grove-CMM.mrpack'
SERVER_OLD = 'IC2-New-Era-Server-1.20.1-r63-Spawn-ZH-Voice'
CLIENT_NEW = 'IC2-New-Era-Windows-1.20.1-r64-UI-AI-Grove-CMM.mrpack'
SERVER_NEW = 'IC2-New-Era-Server-1.20.1-r64-UI-AI-Voice'


def main():
    spec = importlib.util.spec_from_file_location('archives', ROOT / 'scripts/package-landscape-release-1201.py')
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    paths = []
    for name, digest in [
        (CLIENT_OLD, '59F91743DEC26D116EAF50568FAD989A9948782AC6C692249CD2A1C79AC6F8E9'),
        (SERVER_OLD + '.zip', '1726E8981CA6D1E311AC49AD4B76BC9232220667C27F085492382B8EF7815FCF'),
    ]:
        path = ROOT / 'dist' / name
        if not path.is_file():
            path = WORK / 'release-inputs' / name
        if builder.file_sha(path) != digest:
            raise ValueError('Pinned r63 archive differs: ' + name)
        paths.append(path)
    jar = (ROOT / 'build-isolated/migration-1201/newera/build/libs' / OWN).read_bytes()
    if builder.sha(jar) != OWN_SHA:
        raise ValueError('New Era JAR differs from the reviewed r64 artifact')
    note = ('IC2：新纪元 r64 界面说明与AI知识库更新\n'
            'Minecraft 1.20.1 / Forge 47.4.28 / Java 17。\n'
            '加工机分开显示储能、加工进度及缺料/缺电/出料受阻等原因；'
            '工业控制、机器人、手机、音乐与AI设置补充中文操作说明及长文字悬停提示。\n'
            '内置AI新增界面操作与故障处理指南，并已接入实际检索入口。\n'
            '修复取消采集只停止移动却没有结束订单的问题，保留已收集物品；'
            '此行为修复必须服务器也替换同一新纪元JAR。\n'
            '已有r63玩家退出游戏后，仅替换mods中的' + OWN + '，同模组只保留一个JAR。'
            '现有服务器正常停服后替换同一文件并重启，保留地图与配置；不要用完整包覆盖现有世界。\n'
            '客户端仍含42个模组，完整服务器含31个；原均衡地形、947条Spawn汉化、'
            '无尽贪婪、匠魂与Mantle保持。Grove/CMM材质与ITT光影只在客户端。\n'
            '离线构建和JAR/归档回读完成，未运行自动化测试，未启动游戏验收界面。'
            '本机客户端已安装；本轮未部署线上服务器。实际状态见docs/delivery-r64-ui-ai-2026-11-05.md。\n').encode('utf-8')
    with ZipFile(paths[0]) as source:
        if builder.sha(source.read('overrides/mods/' + OWN)) != PREVIOUS_OWN_SHA:
            raise ValueError('Unexpected client baseline New Era JAR')
        index = json.loads(source.read('modrinth.index.json'))
        index.update(versionId='0.2.0-windows.20261105-r64-ui-ai',
                     name='IC2：新纪元 Windows 完整整合包 r64',
                     summary='42个模组；界面中文说明与AI指南更新，保留Spawn汉化、均衡地形、匠魂与Grove/CMM/ITT。')
        replacements = {'modrinth.index.json': builder.encoded(index),
                        'overrides/mods/' + OWN: jar,
                        'overrides/IC2-NewEra-info/README-zh_CN.txt': note,
                        'overrides/IC2-NewEra-info/UI-AI-r64.zh-CN.txt': note,
                        'overrides/IC2-NewEra-info/PLAYTEST-STATUS.txt': note}
        client = builder.write_archive(ROOT / 'dist' / CLIENT_NEW, source, '', replacements, set())
    builder.SERVER_STEM = SERVER_NEW
    with ZipFile(paths[1]) as source:
        prefix = SERVER_OLD + '/'
        manifest = json.loads(source.read(prefix + 'server-manifest.json'))
        entry = next(m for m in manifest['mods'] if m['modId'] == 'ic2newera')
        if entry['sha256'] != PREVIOUS_OWN_SHA or builder.sha(source.read(prefix + 'mods/' + OWN)) != PREVIOUS_OWN_SHA:
            raise ValueError('Unexpected server baseline New Era JAR')
        entry.update(sizeBytes=len(jar), sha256=OWN_SHA, source='First-party r64 build; see delivery record')
        manifest['package'] = SERVER_NEW
        replacements = {'mods/' + OWN: jar, 'UI-AI-r64.zh-CN.txt': note, 'PLAYTEST-STATUS.txt': note}

        def finish(records):
            manifest['files'] = [dict(r, path=r['path'][len(SERVER_NEW) + 1:]) for r in records]
            data = builder.encoded(manifest)
            audit = {'revision': 'r64', 'serverManifestSha256': builder.sha(data),
                     'mods': len(manifest['mods']), 'newEraSha256': OWN_SHA,
                     'terrainConfigurationAndThirdPartyModsUnchanged': True,
                     'automatedTestsRun': False, 'liveServerUpdated': False,
                     'deploymentRecord': 'docs/delivery-r64-ui-ai-2026-11-05.md'}
            return {'server-manifest.json': data, 'artifact-audit.json': builder.encoded(audit)}

        server = builder.write_archive(ROOT / 'dist' / (SERVER_NEW + '.zip'), source, prefix,
                                       replacements, {'server-manifest.json', 'artifact-audit.json'}, finish)
    with ZipFile(ROOT / 'dist' / CLIENT_NEW) as archive:
        index = json.loads(archive.read('modrinth.index.json'))
        mods = [f['path'] for f in index['files'] if f['path'].startswith('mods/')]
        mods += [n[len('overrides/'):] for n in archive.namelist() if n.startswith('overrides/mods/') and n.endswith('.jar')]
        if len(mods) != 42 or len(set(mods)) != 42:
            raise ValueError('Client must contain 42 unique mod paths')
    receipt = {'revision': 'r64', 'client': client, 'server': server, 'clientMods': 42,
               'serverMods': 31, 'newEraSha256': OWN_SHA, 'liveServerUpdated': False}
    (WORK / 'package-audit.json').write_bytes(builder.encoded(receipt))
    print(json.dumps(receipt, ensure_ascii=True), flush=True)


if __name__ == '__main__':
    main()
