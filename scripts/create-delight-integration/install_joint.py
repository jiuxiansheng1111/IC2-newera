"""Prepare an isolated PCL version from a completed joint-pack work directory.

This script intentionally does not launch Minecraft. A completed destination is
immutable: repeat invocations only verify its receipt and installed bytes.
"""
import argparse
import hashlib
import json
import os
import shutil
import tempfile
from pathlib import Path, PurePosixPath


def sha(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def read(path):
    return json.loads(path.read_text("utf-8-sig"))


def safe_target(root, rel):
    p = PurePosixPath(rel)
    if p.is_absolute() or ".." in p.parts or "\\" in rel or ":" in rel:
        raise ValueError(f"Unsafe relative path: {rel}")
    out = root.joinpath(*p.parts)
    if not out.resolve().is_relative_to(root.resolve()):
        raise ValueError(f"Path escapes destination: {rel}")
    return out


def require_file(path, label):
    if not path.is_file():
        raise FileNotFoundError(f"{label}: {path}")
    return path


def source_rows(work):
    selection = read(work / "selection.json")
    if not isinstance(selection, list):
        raise ValueError("selection.json must be a list")
    mods = []
    for row in selection:
        if not isinstance(row, dict) or not isinstance(row.get("path"), str) or not isinstance(row.get("name"), str) or not isinstance(row.get("server"), bool):
            raise ValueError("Each selection row needs path, name, and boolean server")
        src = require_file(Path(row["path"]), "selected mod jar")
        if src.suffix.lower() != ".jar" or Path(row["name"]).name != row["name"] or not row["name"].lower().endswith(".jar"):
            raise ValueError(f"Invalid mod filename: {row['name']}")
        mods.append((src, "mods/" + row["name"], "mod", row["server"]))
    visuals = read(work / "visuals.json")
    if not isinstance(visuals, list):
        raise ValueError("visuals.json must be a list")
    seen = set()
    for row in visuals:
        if not isinstance(row, dict) or not isinstance(row.get("path"), str) or not isinstance(row.get("source"), str):
            raise ValueError("Each visual needs path and absolute source")
        rel = row["path"]
        if not rel.startswith(("resourcepacks/", "shaderpacks/")):
            raise ValueError(f"Visual must be a resourcepack or shaderpack: {rel}")
        if not Path(row["source"]).is_absolute():
            raise ValueError(f"Visual source must be absolute: {row['source']}")
        target_name = PurePosixPath(rel).name
        if not target_name or target_name in (".", ".."):
            raise ValueError(f"Invalid visual path: {rel}")
        if ".." in PurePosixPath(rel).parts or "\\" in rel or ":" in rel:
            raise ValueError(f"Unsafe visual path: {rel}")
        key = rel.casefold()
        if key in seen:
            raise ValueError(f"Duplicate visual path: {rel}")
        seen.add(key)
        src = require_file(Path(row["source"]), "visual source")
        mods.append((src, rel, "visual", False))
    return mods


def profile_files(profile):
    version_json = require_file(profile / f"{profile.name}.json", "profile version JSON")
    version_jar = require_file(profile / f"{profile.name}.jar", "profile version JAR")
    data = read(version_json)
    if data.get("id") != profile.name:
        raise ValueError("Profile JSON id does not match profile directory")
    # PCL stores versions under .minecraft/versions; libraries/assets are shared
    # one level above the versions directory.
    mcroot = profile.parent.parent if profile.parent.name.lower() == "versions" else profile.parent
    if not (mcroot / "libraries").is_dir():
        raise FileNotFoundError(f"Shared libraries directory missing: {mcroot / 'libraries'}")
    client = data.get("downloads", {}).get("client", {})
    if client.get("sha1"):
        h = hashlib.sha1(version_jar.read_bytes()).hexdigest()
        if h.lower() != client["sha1"].lower():
            raise ValueError("Profile vanilla client jar does not match version JSON SHA-1")
    asset_index = data.get("assetIndex", {})
    if asset_index.get("id"):
        index_path = mcroot / "assets" / "indexes" / f"{asset_index['id']}.json"
        require_file(index_path, "shared Minecraft asset index")
        if asset_index.get("sha1") and hashlib.sha1(index_path.read_bytes()).hexdigest().lower() != asset_index["sha1"].lower():
            raise ValueError("Shared Minecraft asset index does not match version JSON SHA-1")
    missing = []
    for lib in data.get("libraries", []):
        rules = lib.get("rules", [])
        if rules:
            allowed = False
            for rule in rules:
                if rule.get("os", {}).get("name", "windows") == "windows":
                    allowed = rule.get("action") == "allow"
            if not allowed:
                continue
        artifact = lib.get("downloads", {}).get("artifact", {})
        rel = artifact.get("path")
        if rel and not (mcroot / "libraries" / Path(*PurePosixPath(rel).parts)).is_file():
            missing.append(rel)
    if missing:
        raise FileNotFoundError("Missing shared libraries: " + ", ".join(missing[:8]))
    return version_json, version_jar, data


def verify_receipt(dest, expected_input):
    receipt_path = dest / "installed.json"
    if not receipt_path.is_file():
        raise FileExistsError(f"Destination exists without installer receipt; left unchanged: {dest}")
    receipt = read(receipt_path)
    if receipt.get("inputFingerprint") != expected_input:
        raise FileExistsError(f"Destination input differs; left unchanged: {dest}")
    for row in receipt.get("installedFiles", []):
        path = safe_target(dest, row["path"])
        if not path.is_file() or sha(path) != row["sha256"]:
            raise FileExistsError(f"Destination bytes differ; left unchanged: {path}")
    return receipt


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True, type=Path)
    ap.add_argument("--profile", required=True, type=Path)
    ap.add_argument("--name", required=True)
    args = ap.parse_args()
    work, profile, name = args.work.resolve(), args.profile.resolve(), args.name
    if Path(name).name != name or name in (".", ".."):
        raise ValueError("--name must be a single directory/version id")
    overlay = work / "client-overlay"
    if not overlay.is_dir():
        raise FileNotFoundError(f"client-overlay missing: {overlay}")
    version_json, version_jar, profile_data = profile_files(profile)
    files = source_rows(work)
    versions_root = profile.parent
    mcroot = versions_root.parent if versions_root.name.lower() == "versions" else versions_root
    dest = versions_root / name
    fingerprint_data = {
        "sourceProfile": profile.name,
        "profileJsonSha256": sha(version_json),
        "profileJarSha256": sha(version_jar),
        "selection": sorted((dst, sha(src), server) for src, dst, kind, server in files if kind == "mod"),
        "visuals": sorted((dst, sha(src)) for src, dst, kind, _ in files if kind == "visual"),
        "overlay": sorted((p.relative_to(overlay).as_posix(), sha(p)) for p in overlay.rglob("*") if p.is_file()),
    }
    fingerprint = hashlib.sha256(json.dumps(fingerprint_data, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    if dest.exists():
        receipt = verify_receipt(dest, fingerprint)
        print(json.dumps({"status": "verified-existing", "destination": str(dest), "files": len(receipt["installedFiles"])}, ensure_ascii=False))
        return
    staging = Path(tempfile.mkdtemp(prefix=f".{name}.stage-", dir=versions_root))
    try:
        shutil.copytree(overlay, staging, dirs_exist_ok=True, ignore=shutil.ignore_patterns("saves", "logs", "crash-reports", "screenshots", "servers.dat", "usercache.json", "usernamecache.json"))
        new_json = json.loads(version_json.read_text("utf-8-sig"))
        new_json["id"] = name
        (staging / f"{name}.json").write_text(json.dumps(new_json, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        shutil.copy2(version_jar, staging / f"{name}.jar")
        installed = [{"path": rel, "sha256": checksum, "kind": "pack-file"}
                     for rel, checksum in fingerprint_data["overlay"]]
        for src, rel, kind, server in files:
            dst = safe_target(staging, rel)
            dst.parent.mkdir(parents=True, exist_ok=True)
            if dst.exists():
                if sha(dst) != sha(src):
                    raise ValueError(f"Overlay conflicts with selected input: {rel}")
            else:
                shutil.copy2(src, dst)
            installed.append({"path": rel, "sha256": sha(dst), "kind": kind, **({"server": server} if kind == "mod" else {})})
        installed.extend([
            {"path": f"{name}.json", "sha256": sha(staging / f"{name}.json"), "kind": "version-json"},
            {"path": f"{name}.jar", "sha256": sha(staging / f"{name}.jar"), "kind": "vanilla-client-jar"},
        ])
        receipt = {"format": 1, "versionId": name, "isolatedGameDirectory": True,
                   "sourceProfile": profile.name, "sourceProfileRoot": str(profile),
                   "sharedLibrariesRoot": str(mcroot / "libraries"), "inputFingerprint": fingerprint,
                   "inputFingerprintData": fingerprint_data, "installedFiles": installed}
        receipt_text = json.dumps(receipt, ensure_ascii=False, indent=2) + "\n"
        (staging / "installed.json").write_text(receipt_text, encoding="utf-8")
        # Verify every installed payload from disk before making the destination visible.
        for row in installed:
            if sha(safe_target(staging, row["path"])) != row["sha256"]:
                raise IOError(f"Staged byte verification failed: {row['path']}")
        staging.rename(dest)
        (work / "installed.json").write_text(receipt_text, encoding="utf-8")
        print(json.dumps({"status": "installed", "destination": str(dest), "files": len(installed)}, ensure_ascii=False))
    except Exception:
        # Keep the uniquely named staging folder for inspection; never touch other versions.
        raise


if __name__ == "__main__":
    main()
