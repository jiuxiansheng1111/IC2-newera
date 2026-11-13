"""Remove only the locked legacy override from existing r71 release archives."""
import argparse
import hashlib
import json
import os
import shutil
from pathlib import Path, PurePosixPath
from zipfile import ZipFile

from advisory_lock import serialize_r71_release
from build_joint import HOTFIX_LOCK, OUT, digest, read, write


def rewrite(source: Path, remove: set[str], replace_json: dict[str, object] | None = None,
            replace_members: dict[str, bytes] | None = None) -> tuple[Path, int]:
    temporary = source.with_name(source.name + f".tmp-{os.getpid()}")
    changed = 0
    try:
        with ZipFile(source, "r") as old, ZipFile(temporary, "w", allowZip64=True) as new:
            if len(old.namelist()) != len(set(old.namelist())):
                raise ValueError(f"Duplicate members in source archive: {source}")
            new.comment = old.comment
            for info in old.infolist():
                if info.filename in remove:
                    changed += 1
                    continue
                clone = info.__class__(info.filename, info.date_time)
                for attr in ("compress_type", "comment", "extra", "create_system", "create_version",
                             "extract_version", "flag_bits", "internal_attr", "external_attr"):
                    setattr(clone, attr, getattr(info, attr))
                if replace_json and info.filename in replace_json:
                    raw = json.dumps(replace_json[info.filename], ensure_ascii=False, indent=2).encode("utf-8") + b"\n"
                    new.writestr(clone, raw)
                    changed += 1
                elif replace_members and info.filename in replace_members:
                    new.writestr(clone, replace_members[info.filename])
                    changed += 1
                else:
                    with old.open(info, "r") as reader, new.open(clone, "w") as writer:
                        shutil.copyfileobj(reader, writer, 1024 * 1024)
            present = set(old.namelist())
            missing = remove - present
            if missing:
                raise FileNotFoundError(f"Locked archive members missing from {source}: {sorted(missing)}")
        with ZipFile(temporary, "r") as check:
            if check.testzip() is not None:
                raise IOError(f"Rewritten archive failed CRC verification: {temporary}")
            names = set(check.namelist())
            if names & remove:
                raise IOError(f"Excluded member remains in rewritten archive: {source}")
        return temporary, changed
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


def sync_live_questlinks(work: Path, out: Path):
    live = work / "live-r71"
    archive = live / "r71-ftb-questlinks.zip"
    manifest = read(live / "r71-ftb-questlinks-manifest.json")
    client = next(out.glob("*Windows.mrpack"))
    server = next(out.glob("*Server.zip"))
    prior = read(work / "verified-release.json")
    for artifact in (client, server):
        if digest(artifact) != prior["artifacts"][artifact.name]["sha256"]:
            raise ValueError(f"Release archive changed since the previous verified manifest: {artifact}")
    initial_client_sha, initial_server_sha = digest(client), digest(server)
    files: dict[str, bytes] = {}
    for row in manifest["files"]:
        rel = PurePosixPath(row["path"])
        if rel.is_absolute() or ".." in rel.parts or "\\" in str(rel) or ":" in str(rel):
            raise ValueError(f"Unsafe live quest patch path: {row['path']}")
        with ZipFile(archive) as patch_zip:
            if patch_zip.testzip(): raise ValueError("Live quest patch archive failed CRC verification")
            raw = patch_zip.read(rel.as_posix())
        if len(raw) != row["bytes"] or hashlib.sha256(raw).hexdigest() != row["sha256"]:
            raise ValueError(f"Live quest patch manifest mismatch: {rel}")
        overlay_file = work / "client-overlay" / Path(*rel.parts)
        if not overlay_file.is_file() or overlay_file.read_bytes() != raw:
            raise ValueError(f"Live quest file differs from the prepared client overlay: {rel}")
        files[rel.as_posix()] = raw
    if len(files) != 4 or manifest.get("counts", {}).get("chapters") != 61:
        raise ValueError("Unexpected live quest patch file/count set")
    ai_rel = "config/ic2newera/quest_knowledge.json"
    ai_file = work / "client-overlay" / ai_rel
    ai_bytes = ai_file.read_bytes()
    ai_sha = hashlib.sha256(ai_bytes).hexdigest()
    files[ai_rel] = ai_bytes
    sync_manifest = {"release": "r71", "sourceArchive": str(archive), "sourceManifest": manifest,
                     "files": [{"path": path, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
                               for path, raw in sorted(files.items())], "aiIndexSha256": ai_sha}
    server_audit_path = server.with_suffix(server.suffix + ".audit.json")
    audit = read(server_audit_path)
    if audit.get("output_sha256") != initial_server_sha:
        raise ValueError("Server audit changed before live quest sync")
    with ZipFile(server) as source:
        roots = {n.split("/", 1)[0] for n in source.namelist() if "/" in n}
        if len(roots) != 1: raise ValueError("Expected one server archive root")
        root = next(iter(roots)) + "/"
        server_manifest_path = root + "SERVER-BUILD-MANIFEST.json"
        server_manifest = json.loads(source.read(server_manifest_path))
    server_members = {root + rel: raw for rel, raw in files.items()}
    for key in ("client_overlay_sha256", "server_sha256"):
        server_manifest["quest_knowledge"][key] = ai_sha
        audit["quest_knowledge"][key] = ai_sha
    server_temp, server_changed = rewrite(server, set(), {server_manifest_path: server_manifest}, server_members)
    if server_changed != len(files) + 1:
        server_temp.unlink(missing_ok=True)
        raise ValueError("Unexpected server quest sync member count")
    audit["output_sha256"] = digest(server_temp)
    audit["output_bytes"] = server_temp.stat().st_size
    server_audit_temp = server_audit_path.with_name(server_audit_path.name + f".tmp-{os.getpid()}")
    write(server_audit_temp, audit)
    if digest(server) != initial_server_sha:
        server_temp.unlink(missing_ok=True)
        server_audit_temp.unlink(missing_ok=True)
        raise FileExistsError("Server archive changed while quest sync was prepared")
    os.replace(server_temp, server)
    os.replace(server_audit_temp, server_audit_path)
    client_members = {"overrides/" + rel: raw for rel, raw in files.items()}
    client_temp, client_changed = rewrite(client, set(), replace_members=client_members)
    if client_changed != len(files):
        client_temp.unlink(missing_ok=True)
        raise ValueError("Unexpected client quest sync member count")
    if digest(client) != initial_client_sha:
        client_temp.unlink(missing_ok=True)
        raise FileExistsError("Client archive changed while quest sync was prepared")
    os.replace(client_temp, client)
    write(live / "r71-questlink-sync-manifest.json", sync_manifest)
    lines = [f"{digest(path)}  {path.name}" for path in (client, server)]
    (out / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"clientSha256": digest(client), "serverSha256": digest(server),
                      "aiIndexSha256": ai_sha, "syncedFiles": len(files)}, ensure_ascii=False))


@serialize_r71_release
def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--work", type=Path, default=Path("build-isolated/player-packaging/20261113-delight-joint"))
    ap.add_argument("--out", type=Path, default=OUT)
    ap.add_argument("--sync-live-questlinks", action="store_true")
    args = ap.parse_args()
    work, out = args.work.resolve(), args.out.resolve()
    if args.sync_live_questlinks:
        sync_live_questlinks(work, out)
        return
    patches = read(HOTFIX_LOCK)["patches"]
    excluded = {rel for patch in patches for rel in patch.get("excludeOverlayFiles", [])}
    if not excluded:
        raise ValueError("The r71 lock contains no archive exclusions")
    client = next(out.glob("*Windows.mrpack"))
    server = next(out.glob("*Server.zip"))
    sums = read(work / "verified-release.json")
    for artifact in (client, server):
        expected = sums["artifacts"][artifact.name]["sha256"]
        if digest(artifact) != expected:
            raise ValueError(f"Existing r71 archive differs from its verified release manifest: {artifact}")
    initial_client_sha, initial_server_sha = digest(client), digest(server)
    client_remove = {"overrides/" + rel for rel in excluded}
    # The server archive is replaced first so its old bytes are released before
    # rewriting the smaller client MRPack. Each individual replacement is atomic.
    with ZipFile(server, "r") as source:
        roots = {n.split("/", 1)[0] for n in source.namelist() if "/" in n}
        if len(roots) != 1:
            raise ValueError("Expected one dedicated-server archive root")
        root = next(iter(roots)) + "/"
        server_remove = {root + rel for rel in excluded}
        manifest_path = root + "SERVER-BUILD-MANIFEST.json"
        manifest = json.loads(source.read(manifest_path))
    audit_path = server.with_suffix(server.suffix + ".audit.json")
    audit = read(audit_path)
    if audit.get("builder") != "build_server.py" or Path(audit.get("output_path", "")).resolve() != server:
        raise ValueError("Existing server archive audit does not belong to build_server.py")
    if audit.get("output_sha256") != digest(server):
        raise ValueError("Existing server archive differs from its build audit")
    for rel in excluded:
        if rel not in manifest.get("copied_side_audited_files", []) or rel not in audit.get("copied_side_audited_files", []):
            raise ValueError(f"Locked exclusion is absent from server audit trail: {rel}")
        manifest["copied_side_audited_files"].remove(rel)
        audit["copied_side_audited_files"].remove(rel)
    manifest.setdefault("excluded", []).extend("locked incompatible legacy override: " + rel for rel in sorted(excluded))
    temp, removed = rewrite(server, server_remove, {manifest_path: manifest})
    if removed != len(server_remove) + 1:
        temp.unlink(missing_ok=True)
        raise ValueError("Unexpected number of server archive changes")
    audit["output_sha256"] = digest(temp)
    audit["output_bytes"] = temp.stat().st_size
    temp_audit = audit_path.with_name(audit_path.name + f".tmp-{os.getpid()}")
    write(temp_audit, audit)
    if digest(server) != initial_server_sha:
        temp.unlink(missing_ok=True)
        temp_audit.unlink(missing_ok=True)
        raise FileExistsError("Server archive changed during patch preparation; left the newer archive untouched")
    os.replace(temp, server)
    os.replace(temp_audit, audit_path)
    temp, removed = rewrite(client, client_remove)
    if removed != len(client_remove):
        temp.unlink(missing_ok=True)
        raise ValueError("Unexpected number of client archive changes")
    if digest(client) != initial_client_sha:
        temp.unlink(missing_ok=True)
        raise FileExistsError("Client archive changed during patch preparation; left the newer archive untouched")
    os.replace(temp, client)
    lines = [f"{digest(path)}  {path.name}" for path in (client, server)]
    (out / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"client": str(client), "server": str(server), "excluded": sorted(excluded),
                      "clientSha256": digest(client), "serverSha256": digest(server)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
