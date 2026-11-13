"""Build the cumulative, client-only manual update for existing r71 TaCZ 1.2.0 installs."""
from pathlib import Path
import hashlib
import os
import shutil
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile

from advisory_lock import serialize_r71_release
from build_joint import OUT, W, digest, read
from repair_tacz_lod import PACKS


INSTRUCTIONS = """机械动力：齿轮盛宴 × IC2：新纪元 r71 玩家更新
适用于已经装好 r71、TaCZ 已经是 1.2.0 的玩家。首次安装请用完整 Windows.mrpack。
1. 退出游戏。在 PCL 打开你实际玩的联合包版本的文件夹，确认能看到 mods、config、tacz。
2. 将 mods 里现有的 JEI、ExtendedAE Plus 和 Just Enough Threads 的旧版移到版本文件夹以外，留作备份。旧版通常叫：
   jei-1201-15.56.0.205.jar
   extendedae_plus-1.5.5.jar
   jei_optimize-forge-0.5.0+1.20.1.jar（旧 Just Enough Threads；不能与新版并存）
   文件名若不同，以模组和版本为准。保留 AE2 本体与 ExtendedAE（没有 Plus 的那个）。
3. 把本压缩包里的 mods、tacz、config、resourcepacks、IC2-NewEra-info 目录合并到版本目录，允许覆盖。
   如已装过其中的新版，只保留每个模组的一份 JAR。
4. 正常启动。JEI 应显示 15.74.0.234，ExtendedAE Plus 应显示 1.6.4，Just Enough Threads 应显示 0.14.9。如启用了怪物娘化包，可把随附 NewEra-CMM-Model-Fix.zip 在资源包列表里移到该包上方。
5. mods 中的 newera-ponder-fix-1.20.1-1.0.0.jar 是客户端专用的 Ponder 兼容修正，只放入客户端，不要放进专用服务器。
这是手动覆盖包，不用 PCL 导入，也不要上传到服务器。已累积包含此前启动、远景资源和 Ponder 修正。
"""


@serialize_r71_release
def main():
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
                "resourcepacks/NewEra-CMM-Model-Fix.zip"]:
        files[rel] = W / "client-overlay" / rel
    dest = OUT / "Create-Delight-New-Era-r71-Player-Update.zip"
    temp = dest.with_name(dest.name + f".tmp-{os.getpid()}")
    try:
        with ZipFile(temp, "w", ZIP_DEFLATED) as out:
            out.writestr("先读我-升级步骤.txt", INSTRUCTIONS.encode("utf-8-sig"))
            for rel, path in files.items():
                out.write(path, rel, compress_type=ZIP_STORED if path.suffix in (".jar", ".zip") else ZIP_DEFLATED)
        with ZipFile(temp) as out:
            if out.testzip() or len(out.namelist()) != len(files) + 1:
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
    (OUT / "SHA256SUMS.txt").write_text("\n".join(sums + [f"{digest(dest)}  {dest.name}",
                                        f"{fix['sha256']}  {standalone.name}"]) + "\n", encoding="ascii")
    print(dest.name, dest.stat().st_size, digest(dest))


if __name__ == "__main__":
    main()
