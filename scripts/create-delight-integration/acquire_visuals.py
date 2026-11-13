"""Acquire and SHA-1 verify joint-pack visuals, preserving the four chosen r70 files byte-for-byte."""
import argparse
import hashlib
import json
import shutil
import urllib.request
from pathlib import Path, PurePosixPath


R70_VISUALS = (
    "resourcepacks/Soartex Grove 1.0.4.zip",
    "resourcepacks/CMMRP_Release_1.0_[1.20-1.20.1].zip",
    "resourcepacks/CMM_FIX_EMF_v.1_[1.20-1.20.1].zip",
    "shaderpacks/iterationT 3.2.0.zip",
)


def digest(path, algorithm="sha1"):
    h = hashlib.new(algorithm)
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def fetch(url, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "IC2-New-Era-joint-pack-asset-acquirer/1.0"})
    temp = target.with_suffix(target.suffix + ".partial")
    try:
        with urllib.request.urlopen(req, timeout=90) as response, temp.open("wb") as out:
            shutil.copyfileobj(response, out)
        temp.replace(target)
    finally:
        if temp.exists():
            temp.unlink()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True, type=Path)
    ap.add_argument("--base-index", type=Path)
    ap.add_argument("--profile", required=True, type=Path, help="r70 PCL profile directory")
    args = ap.parse_args()
    work, profile = args.work.resolve(), args.profile.resolve()
    base_index = args.base_index or work / "reference-index.json"
    base_index = base_index.resolve()
    cache = work / "visual-cache"
    plan = []
    index = json.loads(base_index.read_text("utf-8-sig"))
    for row in index["files"]:
        rel = row["path"]
        if not rel.startswith(("resourcepacks/", "shaderpacks/")):
            continue
        posix = PurePosixPath(rel)
        if posix.is_absolute() or ".." in posix.parts or "\\" in rel:
            raise ValueError(f"Unsafe indexed visual path: {rel}")
        src = cache.joinpath(*posix.parts)
        expected = row["hashes"]["sha1"].lower()
        if not src.is_file() or digest(src) != expected:
            url = row.get("downloads", [None])[0]
            if not url:
                raise ValueError(f"No download URL for {rel}")
            fetch(url, src)
        if digest(src) != expected:
            raise ValueError(f"SHA-1 mismatch for {rel}: expected {expected}, got {digest(src)}")
        if row.get("fileSize") is not None and src.stat().st_size != row["fileSize"]:
            raise ValueError(f"Size mismatch for {rel}: expected {row['fileSize']}, got {src.stat().st_size}")
        plan.append({"path": rel, "source": str(src.resolve()), "sha1": expected, "bytes": src.stat().st_size, "origin": "delight-index"})
    for rel in R70_VISUALS:
        src = profile.joinpath(*PurePosixPath(rel).parts)
        if not src.is_file():
            raise FileNotFoundError(f"Locked r70 visual missing: {src}")
        dst = cache.joinpath(*PurePosixPath(rel).parts)
        dst.parent.mkdir(parents=True, exist_ok=True)
        if not dst.is_file() or digest(dst) != digest(src):
            shutil.copy2(src, dst)
        if digest(dst) != digest(src):
            raise IOError(f"r70 visual byte copy mismatch: {rel}")
        plan.append({"path": rel, "source": str(dst.resolve()), "sha1": digest(src), "bytes": src.stat().st_size, "origin": "r70-byte-copy", "sourcePath": str(src)})
    if len({row["path"].casefold() for row in plan}) != len(plan):
        raise ValueError("Duplicate visual destination; resolve the conflict before installation")
    work.mkdir(parents=True, exist_ok=True)
    (work / "visual-plan.json").write_text(json.dumps(plan, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # This compact runtime manifest matches install_joint.py's path/source contract.
    runtime = [{"path": row["path"], "source": row["source"]} for row in plan]
    (work / "visuals.json").write_text(json.dumps(runtime, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"verified": len(plan), "basePack": sum(r["origin"] == "delight-index" for r in plan),
                      "r70BytePreserved": sum(r["origin"] == "r70-byte-copy" for r in plan),
                      "manifest": str(work / "visuals.json")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
