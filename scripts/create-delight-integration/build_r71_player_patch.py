"""Build the cumulative, client-only manual update for existing r71 TaCZ 1.2.0 installs."""
from pathlib import Path
import hashlib
import os
import shutil
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile

from advisory_lock import serialize_r71_release
from build_joint import OUT, W, digest, read
from repair_tacz_lod import PACKS
from publish_field_hunts import scope_data


INSTRUCTIONS = """机械动力：齿轮盛宴 × IC2：新纪元 r71 玩家更新
适用于已经装好 r71、TaCZ 已经是 1.2.0 的玩家。首次安装请用完整 Windows.mrpack。
1. 退出游戏。在 PCL 打开你实际玩的联合包版本的文件夹，确认能看到 mods、config、tacz。
2. 将 mods 里现有的 JEI、ExtendedAE Plus 和 Just Enough Threads 的旧版移到版本文件夹以外，留作备份。旧版通常叫：
   jei-1201-15.56.0.205.jar
   extendedae_plus-1.5.5.jar
   jei_optimize-forge-0.5.0+1.20.1.jar（旧 Just Enough Threads；不能与新版并存）
   文件名若不同，以模组和版本为准。保留 AE2 本体与 ExtendedAE（没有 Plus 的那个）。
3. 把本压缩包里的 mods、tacz、config、kubejs、resourcepacks、IC2-NewEra-info 目录合并到版本目录，允许覆盖；白名单更新需覆盖 `kubejs/server_scripts/Lightmans Currency/white_list.js`。
   如已装过其中的新版，只保留每个模组的一份 JAR。
4. 正常启动。JEI 应显示 15.74.0.234，ExtendedAE Plus 应显示 1.6.4，Just Enough Threads 应显示 0.14.9。如启用了怪物娘化包，可把随附 NewEra-CMM-Model-Fix.zip 在资源包列表里移到该包上方。
5. mods 中的 newera-ponder-fix-1.20.1-1.0.0.jar 是客户端专用的 Ponder 兼容修正，只放入客户端，不要放进专用服务器。
6. 本更新包含讨伐任务和助手的新任务索引。已有玩家还需将 mods/jei_plus_plus-1.0.6-1.20.1.jar 移出 mods，换为作者的 JEI++ 1.0.9a（1.20.1 Forge）：https://www.curseforge.com/minecraft/mc-mods/jei-plus-plus/files/9509594 。该文件由作者分发，完整 PCL 包会自动下载。请不要删掉 JEI 本体。服务器不需要这个客户端文件。
7. 为移除底包的旧任务章节，请在关闭游戏后运行随包的 `remove-retired-quests.ps1`，输入当前联合包版本目录。脚本仅移动 scope 清单中、哈希匹配的 41 个旧章节到同目录 `.newera-quest-prune-backup`，保留其他任务文件和进度；遇到未知改动会停止。
这是手动覆盖包，不用 PCL 导入，也不要上传到服务器。保留现有存档与任务进度。
"""


def retired_cleanup_script(scope):
    retired=scope['retired_files']; accepted=scope['accepted']
    rows=[{'path':p,'hashes':[v for v in accepted[p] if v is not None]} for p in retired]
    payload=__import__('json').dumps(rows,ensure_ascii=False,indent=2)
    return f'''$ErrorActionPreference = 'Stop'
$profile = Read-Host '输入当前 r71 联合包版本目录完整路径'
if (-not (Test-Path -LiteralPath (Join-Path $profile 'mods') -PathType Container) -or
    -not (Test-Path -LiteralPath (Join-Path $profile 'config/ftbquests/quests/data.snbt') -PathType Leaf)) {{
  throw '所选路径不像已安装的 r71 Forge 版本目录；未修改任何文件。'
}}
$scope = @'
{payload}
'@ | ConvertFrom-Json
$backupRoot = Join-Path $profile '.newera-quest-prune-backup'
foreach ($row in $scope) {{
  $path = Join-Path $profile ($row.path -replace '/', '\\')
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {{ continue }}
  $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($row.hashes -notcontains $hash) {{ throw "未知任务文件内容，停止且保留原件：$($row.path)" }}
}}
foreach ($row in $scope) {{
  $path = Join-Path $profile ($row.path -replace '/', '\\')
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {{ continue }}
  $dest = Join-Path $backupRoot ($row.path -replace '/', '\\')
  if (Test-Path -LiteralPath $dest) {{ throw "备份目标已存在，停止：$dest" }}
  New-Item -ItemType Directory -Path (Split-Path -Parent $dest) -Force | Out-Null
  Move-Item -LiteralPath $path -Destination $dest
}}
Write-Host '已归档清单中的旧任务章节。'
'''


@serialize_r71_release
def main():
    audit,stage,scope=scope_data()
    receipt = read(OUT / "r71-release-manifest.json")
    if (receipt["runtimeFixes"].get("extendedAeOfficialVersion") != "1.6.4"
            or receipt["runtimeFixes"].get("justEnoughThreads") != "0.14.9"
            or receipt["runtimeFixes"].get("ponderFix", {}).get("modId") != "newera_ponder_fix"
            or receipt["runtimeFixes"].get("ponderFix", {}).get("version") != "1.0.0"
            or receipt["forgeDependencyErrors"]):
        raise ValueError("Verify the matching JEI, optimizer, AE and client-only Ponder fix before publishing the player update")
    by_id = {r["primary"]: r for r in read(W / "selection.json")}
    files = {}
    for mod_id in ("ic2newera", "jei", "extendedae_plus", "justenoughthreads", "newera_ponder_fix"):
        r = by_id[mod_id]
        if mod_id == "newera_ponder_fix" and (r["name"] != "newera-ponder-fix-1.20.1-1.0.0.jar"
                                                or r["ids"] != ["newera_ponder_fix"] or r["server"]):
            raise ValueError("The Ponder compatibility JAR must remain an exact client-only artifact")
        src = Path(r["path"])
        if digest(src) != r["sha256"]:
            raise ValueError(f"Source JAR changed: {src.name}")
        files["mods/" + r["name"]] = src
    for rel in [*("tacz/" + n for n in PACKS), "config/crash_assistant/modlist.json",
                "config/modpack_defaults/config/crash_assistant/modlist.json", "IC2-NewEra-info/THIRD-PARTY-NOTICES.txt",
                "resourcepacks/NewEra-CMM-Model-Fix.zip", *scope['changed_files']]:
        files[rel] = W / "client-overlay" / rel
    cleanup=retired_cleanup_script(scope)
    dest = OUT / "Create-Delight-New-Era-r71-Player-Update.zip"
    temp = dest.with_name(dest.name + f".tmp-{os.getpid()}")
    try:
        with ZipFile(temp, "w", ZIP_DEFLATED) as out:
            out.writestr("先读我-升级步骤.txt", INSTRUCTIONS.encode("utf-8-sig"))
            out.writestr("remove-retired-quests.ps1", cleanup.encode("utf-8-sig"))
            for rel, path in files.items():
                out.write(path, rel, compress_type=ZIP_STORED if path.suffix in (".jar", ".zip") else ZIP_DEFLATED)
        with ZipFile(temp) as out:
            if out.testzip() or len(out.namelist()) != len(files) + 2:
                raise ValueError("Broken player patch archive")
            for rel, path in files.items():
                if hashlib.sha256(out.read(rel)).hexdigest() != digest(path):
                    raise ValueError(rel)
        os.replace(temp, dest)
    finally:
        temp.unlink(missing_ok=True)
    sums = [f"{v['sha256']}  {n}" for n, v in receipt["artifacts"].items()]
    fix = by_id["newera_ponder_fix"]
    standalone = OUT / fix["name"]
    source = Path(fix["path"])
    if not standalone.is_file() or digest(standalone) != fix["sha256"]:
        stage = standalone.with_name(standalone.name + f".tmp-{os.getpid()}")
        try:
            shutil.copyfile(source, stage)
            if digest(stage) != fix["sha256"]:
                raise ValueError("Standalone Ponder JAR changed during publication")
            os.replace(stage, standalone)
        finally:
            stage.unlink(missing_ok=True)
    core = by_id["ic2newera"]
    core_file = OUT / core["name"]
    if not core_file.is_file() or digest(core_file) != core["sha256"]:
        temp_core = core_file.with_name(core_file.name + ".tmp")
        shutil.copy2(core["path"], temp_core)
        if digest(temp_core) != core["sha256"]:
            raise ValueError("The standalone New Era JAR changed while copying")
        os.replace(temp_core, core_file)
    (OUT / "SHA256SUMS.txt").write_text("\n".join(sums + [f"{digest(dest)}  {dest.name}",
                                        f"{fix['sha256']}  {standalone.name}", f"{core['sha256']}  {core_file.name}"]) + "\n", encoding="ascii")
    print(dest.name, dest.stat().st_size, digest(dest))


if __name__ == "__main__":
    main()
