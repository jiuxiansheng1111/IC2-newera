"""Apply only the approved r72 compatibility files to the existing ordinary PCL instance."""
import argparse
import json
import os
import shutil
import tempfile
from pathlib import Path
from uuid import uuid4

from client_assets import (FIX_NAME, FALLBACK_NAME, OLD_FIX, add_fallback_to_options,
                           build_client_assets, digest)
from install_local import WORK, REPO, OLD, game_closed


def atomic_copy(src, dst):
    fd, filename = tempfile.mkstemp(dir=dst.parent, prefix=dst.name + ".", suffix=".tmp")
    os.close(fd)
    tmp = Path(filename)
    try:
        shutil.copyfile(src, tmp)
        if digest(tmp) != digest(src):
            raise ValueError(f"Copy verification failed for {dst.name}")
        os.replace(tmp, dst)
    finally:
        tmp.unlink(missing_ok=True)


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("--apply", action="store_true")
    args.add_argument("--textures-only", action="store_true", help="Keep the active VS JAR as it is")
    parsed = args.parse_args()
    apply = parsed.apply
    profile = (REPO.parent / ".minecraft/versions/1.20.1-Forge_47.4.28").resolve(strict=True)
    if apply:
        game_closed()
    history = WORK / "local-install"
    oldhashes = {FIX_NAME:{OLD_FIX}, FALLBACK_NAME:set(), "valkyrienskies-120-2.4.11.jar":set()}
    for note in history.glob("resource-fix-*/receipt.json"):
        data = json.loads(note.read_bytes())
        if data.get("profile") == str(profile) and data.get("state") == "installed":
            for k, v in data["installed"].items():
                if Path(k).name in oldhashes:
                    oldhashes[Path(k).name].add(v)
    layer = WORK / "pack/overrides/resourcepacks"
    art = build_client_assets(WORK/"pack/inputs/client/mods", OLD/"client-overlay/resourcepacks"/FIX_NAME, layer)
    vsdir = WORK / "vs-compat"
    vsname = "valkyrienskies-120-2.4.11.jar"
    old_vs = "F99F24DE62015451A047F90484CF9D25970CAC350105B581C22B2D5247EBFD53"
    changes = {f"resourcepacks/{name}":layer/name for name in art}
    if not parsed.textures_only:
        manifests = [json.loads(n.read_bytes()) for n in vsdir.glob("*.manifest.json")]
        verified = [m for m in manifests if m.get("method") == "forge_sculk_caller_wrappers" and
                    str(m.get("source_sha256","")).upper() == old_vs and str(m.get("sha256","")).upper()
                    != "0CA11689706524FA6D80A28C91BD4EBB53A773AFBE6F42597643C8BEA5E36D9D"]
        if len(verified) != 1:
            raise ValueError("A final Forge caller-wrapper receipt is required")
        manifest = verified[0]
        source = vsdir / Path(manifest["artifact"]).name
        if digest(source) != str(manifest["sha256"]).upper():
            raise ValueError("VS receipt did not match its replacement")
        if len(list((profile/"mods").glob("valkyrienskies*.jar"))) != 1:
            raise ValueError("Expected one installed VS JAR")
        oldhashes[vsname].add(old_vs)
        changes[f"mods/{vsname}"] = source
    for k, src in changes.items():
        p = profile/k
        if p.is_symlink() or p.parent.is_symlink():
            raise ValueError(f"Unexpected local link: {k}")
        if p.is_file() and digest(p) not in oldhashes[p.name] | {digest(src)}:
            raise ValueError(f"Unrecognized local file, retained: {k}")
    opts = profile/"options.txt"
    before = opts.read_bytes()
    after = add_fallback_to_options(before)
    changed = {k:s for k,s in changes.items() if not (profile/k).is_file() or digest(profile/k) != digest(s)}
    print("Changed:", sorted(changed), "resource pack order:", before != after)
    if not apply or not (changed or before != after):
        return
    dest = history / ("resource-fix-" + uuid4().hex)
    dest.mkdir(parents=True)
    if before != after:
        if opts.is_symlink():
            raise ValueError("Unexpected linked local options")
        (dest/"new-options.txt").write_bytes(after)
        changed["options.txt"] = dest/"new-options.txt"
    for k in changed:
        p = profile/k
        if p.exists():
            b = dest/"previous"/k
            b.parent.mkdir(parents=True, exist_ok=True)
            try:
                os.link(p,b)
            except OSError:
                shutil.copy2(p,b)
    note = {"state":"installing","profile":str(profile),"installed":{k:digest(s) for k,s in changed.items()}}
    (dest/"receipt.json").write_text(json.dumps(note,indent=2,ensure_ascii=False),encoding="utf-8")
    done=[]
    try:
        for k, src in changed.items():
            atomic_copy(src, profile/k)
            done.append(k)
    except BaseException:
        for k in reversed(done):
            b=dest/"previous"/k
            if b.is_file():
                atomic_copy(b,profile/k)
            else:
                (profile/k).unlink()
        note["state"]="restored"
        raise
    else:
        note["state"]="installed"
    finally:
        (dest/"receipt.json").write_text(json.dumps(note,indent=2,ensure_ascii=False),encoding="utf-8")
    print("Verified and installed:", dest)


if __name__ == "__main__":
    main()
