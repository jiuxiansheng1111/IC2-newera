"""Safely add the verified r71 client payload to an existing Forge instance.

The default invocation only writes a dry-run plan under build-isolated. Applying
requires that plan's fingerprint and an explicit assertion that the instance is
closed. Personal worlds, launcher state, options, screenshots, account data and
AI configuration are never copied from the pack or inspected in the target.
"""
import argparse
import fnmatch
import hashlib
import json
import os
import re
import shutil
import tempfile
import tomllib
import uuid
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_WORK = ROOT / "build-isolated/player-packaging/20261113-delight-joint"
DEFAULT_RELEASE = ROOT / "dist/Create-Delight-New-Era-r71"
PLAN_DIR = ROOT / "build-isolated/player-packaging/20261113-delight-joint/existing-forge-plans"
BACKUP_ROOT = ROOT / "build-isolated/player-packaging/20261113-delight-joint/existing-forge-backups"
CONFIG_POLICY = Path(__file__).with_name("server-config-policy.json")
HOTFIX_LOCK = Path(__file__).with_name("hotfix-lock-r71.json")

PRIVATE_EXACT = {
    "options.txt", "servers.dat", "servers.dat_old", "usercache.json", "usernamecache.json",
    "launcher_profiles.json", "launcher_accounts.json", "realms_persistence.json",
    "config/ic2newera-robot-voice.json", "PCL/Setup.ini", "PCL/config.json",
}
PRIVATE_PREFIXES = (
    "PCL/", "saves/", "screenshots/", "logs/", "crash-reports/", "resourcepacks/",
    "shaderpacks/", "config/ic2newera/", "config/voicechat/", "config/ias/",
)
PRIVATE_NAME_PARTS = ("apikey", "api_key", "secret", "credential", "password", "token", "account", "auth")
ALWAYS_AUTHORITATIVE = (
    "kubejs/", "ldlib/", "tacz/", "hotai/", "assets/mbd2/", "data/", "scripts/",
    "config/ftbquests/quests/", "config/ic2newera/quest_knowledge.json",
    "config/ProjectE/custom_emc.json", "config/ftbxmodcompat.snbt",
)
REMOVED_CONFLICT_IDS = {"irisflw"}


def read_json(path):
    return json.loads(path.read_text("utf-8-sig"))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def sha512(path):
    h = hashlib.sha512()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def plan_hash(data):
    encoded = json.dumps(data, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def safe_rel(value):
    if not isinstance(value, str) or not value or "\\" in value or ":" in value:
        raise ValueError(f"Unsafe relative path: {value!r}")
    rel = PurePosixPath(value)
    if rel.is_absolute() or any(part in ("", ".", "..") for part in rel.parts):
        raise ValueError(f"Unsafe relative path: {value!r}")
    return rel.as_posix()


def safe_target(root, relative):
    rel = safe_rel(relative)
    target = root.joinpath(*PurePosixPath(rel).parts)
    if not target.resolve(strict=False).is_relative_to(root.resolve()):
        raise ValueError(f"Target escapes instance: {relative}")
    return target


def private_path(rel):
    folded = rel.casefold()
    if folded == "config/ic2newera/quest_knowledge.json":
        return False
    if folded in {p.casefold() for p in PRIVATE_EXACT}:
        return True
    if any(folded.startswith(p.casefold()) for p in PRIVATE_PREFIXES):
        return True
    return any(part in folded for part in PRIVATE_NAME_PARTS)


def mod_ids(path):
    """Read Forge mod identities only; never inspect configs or player files."""
    ids = set()
    with ZipFile(path) as archive:
        names = set(archive.namelist())
        metadata = next((name for name in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml") if name in names), None)
        if metadata:
            parsed = tomllib.loads(archive.read(metadata).decode("utf-8-sig"))
            for row in parsed.get("mods", []):
                if isinstance(row, dict) and isinstance(row.get("modId"), str):
                    ids.add(row["modId"].lower())
        manifest = "META-INF/MANIFEST.MF"
        if "kotlinforforge" in path.name.lower() and manifest in names:
            ids.add("kotlinforforge")
    return ids


def zip_member_hash(archive, member, algorithm="sha512"):
    digest = hashlib.new(algorithm)
    size = 0
    with archive.open(member) as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            size += len(chunk)
            digest.update(chunk)
    return size, digest.hexdigest()


def selected_rows(work, release_dir, target_profile, candidate_sha256=None):
    selection_path = work / "selection.json"
    rows = read_json(selection_path)
    if not isinstance(rows, list):
        raise ValueError("selection.json must contain an array")

    release_zip = release_dir / "Create-Delight-New-Era-r71-Windows.mrpack"
    if not release_zip.is_file():
        matches = sorted(release_dir.glob("*Windows*.mrpack"))
        if len(matches) != 1:
            raise FileNotFoundError(f"Expected one published Windows MRPack in {release_dir}")
        release_zip = matches[0]
    if candidate_sha256:
        if not re.fullmatch(r"[0-9a-fA-F]{64}", candidate_sha256):
            raise ValueError("--candidate-sha256 must be a 64-digit SHA-256")
        release_sha256 = sha256(release_zip).lower()
        if release_sha256 != candidate_sha256.lower():
            raise ValueError("Candidate Windows MRPack does not match --candidate-sha256")
    else:
        manifest_path = release_dir / "r71-release-manifest.json"
        manifest = read_json(manifest_path)
        if manifest.get("release") != "r71":
            raise ValueError("Release manifest is not for r71")
        artifact = manifest.get("artifacts", {}).get(release_zip.name)
        if not isinstance(artifact, dict) or not re.fullmatch(r"[0-9a-fA-F]{64}", str(artifact.get("sha256", ""))):
            raise ValueError("Final release manifest does not lock the selected Windows MRPack")
        if release_zip.stat().st_size != artifact.get("bytes") or sha256(release_zip).lower() != artifact["sha256"].lower():
            raise ValueError("Windows MRPack does not match the final r71 release manifest")
        release_sha256 = artifact["sha256"].lower()
    with ZipFile(release_zip) as archive:
        index = json.loads(archive.read("modrinth.index.json").decode("utf-8-sig"))
        published, embedded = {}, {}
        seen_members = set()
        for row in index.get("files", []):
            rel = safe_rel(row.get("path"))
            if rel.startswith("mods/"):
                if PurePosixPath(rel).parent.as_posix() != "mods" or not rel.lower().endswith(".jar"):
                    raise ValueError(f"Unexpected non-root or non-JAR mods entry in MRPack index: {rel}")
                key = PurePosixPath(rel).name.casefold()
                if key in seen_members:
                    raise ValueError(f"Duplicate/case-colliding MRPack mod index path: {rel}")
                seen_members.add(key)
                published[PurePosixPath(rel).name] = row
        archive_names = archive.namelist()
        if len(archive_names) != len(set(archive_names)):
            raise ValueError("Duplicate ZIP entries in candidate MRPack")
        for member in archive_names:
            if member.startswith("overrides/mods/") and member.lower().endswith(".jar"):
                rel = safe_rel(member[len("overrides/"):])
                if PurePosixPath(rel).parent.as_posix() != "mods":
                    raise ValueError(f"Unexpected nested embedded mod path: {member}")
                name = PurePosixPath(rel).name
                key = name.casefold()
                if key in seen_members:
                    raise ValueError(f"Mod appears more than once in MRPack index/overrides: {name}")
                seen_members.add(key)
                size, digest = zip_member_hash(archive, member)
                embedded[name] = {"member": member, "fileSize": size, "sha512": digest}

    selected_names = set()
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("name"), str):
            raise ValueError("Invalid selection row")
        name = row["name"]
        if name.casefold() in {previous.casefold() for previous in selected_names}:
            raise ValueError(f"Duplicate/case-colliding filename in selection.json: {name}")
        selected_names.add(name)
    package_names = set(published) | set(embedded)
    missing_names = selected_names - package_names
    extra_names = package_names - selected_names
    if missing_names or extra_names:
        raise ValueError("Selection/MRPack mod file mismatch; missing=" + repr(sorted(missing_names))
                         + ", extra=" + repr(sorted(extra_names)))

    result, seen_names, seen_pair, ids_by_hash = [], set(), set(), {}
    incoming = work / "incoming-mods"
    incoming_root = incoming.resolve()
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("Invalid selection row")
        name, expected, ids = row.get("name"), row.get("sha256"), row.get("ids")
        if not isinstance(name, str) or PurePosixPath(name).name != name or not name.lower().endswith(".jar"):
            raise ValueError(f"Invalid selected jar filename: {name!r}")
        if not re.fullmatch(r"[0-9a-fA-F]{64}", str(expected)) or not isinstance(ids, list) or not ids:
            raise ValueError(f"Missing selected SHA-256 or mod IDs for {name}")
        if name.casefold() in seen_names:
            raise ValueError(f"Duplicate published filename in selection.json: {name}")
        seen_names.add(name.casefold())

        published_row = published.get(name)
        if published_row is not None:
            published_hash = (published_row.get("hashes") or {}).get("sha512")
            published_size = published_row.get("fileSize")
            if not re.fullmatch(r"[0-9a-fA-F]{128}", str(published_hash or "")):
                raise ValueError(f"MRPack official index does not lock full SHA-512 for {name}")
        else:
            published_hash = embedded[name]["sha512"]
            published_size = embedded[name]["fileSize"]

        candidates = [incoming / name]
        original = Path(row.get("path", ""))
        if original.is_absolute():
            original = original.resolve()
            if not original.is_relative_to(target_profile.resolve()):
                candidates.append(original)
        source = next((p for p in candidates if p.is_file()
                       and (not p.is_relative_to(incoming) or p.resolve().is_relative_to(incoming_root))
                       and sha256(p).lower() == expected.lower()
                       and sha512(p).lower() == published_hash.lower()), None)
        if source is None:
            raise FileNotFoundError(f"No cached source matches selection and published SHA-256/SHA-512: {name}")
        if published_size is not None and source.stat().st_size != published_size:
            raise ValueError(f"Cached source byte size differs from published MRPack entry: {name}")
        actual_ids = mod_ids(source)
        declared_ids = {str(value).lower() for value in ids}
        if actual_ids and actual_ids != declared_ids:
            raise ValueError(f"Selection mod IDs differ from jar metadata for {name}: {sorted(actual_ids)} != {sorted(declared_ids)}")
        if not actual_ids:
            raise ValueError(f"Cannot confirm Forge mod IDs in selected jar: {name}")
        digest = expected.lower()
        key = (tuple(sorted(declared_ids)), digest)
        if key in seen_pair:
            raise ValueError(f"Duplicate selected mod ID + SHA-256 entry: {name}")
        seen_pair.add(key)
        previous_ids = ids_by_hash.setdefault(digest, declared_ids)
        if previous_ids != declared_ids:
            raise ValueError(f"Same-byte aliases disagree on mod identity: {name}")
        result.append({"name": name, "source": str(source.resolve()), "sha256": digest,
                       "sha512": published_hash.lower(), "ids": sorted(declared_ids),
                       "server": row.get("server") is True,
                       "replacesModId": row.get("replacesModId")})
    return result, release_sha256


def validate_profile(profile, version_id):
    if Path(version_id).name != version_id or version_id in (".", ".."):
        raise ValueError("--version-id must be one PCL version ID")
    profile = profile.resolve()
    if profile.name != version_id:
        raise ValueError("PCL profile directory must match --version-id")
    version_json = profile / f"{version_id}.json"
    if not version_json.is_file():
        raise FileNotFoundError(f"Missing PCL version descriptor: {version_json}")
    data = read_json(version_json)
    if data.get("id") != version_id:
        raise ValueError("PCL version JSON id does not match --version-id")
    forge = [lib.get("name", "") for lib in data.get("libraries", []) if isinstance(lib, dict)]
    forge_ids = [name for name in forge if name.startswith("net.minecraftforge:forge:")]
    game_args = data.get("arguments", {}).get("game", [])
    merged_args = {}
    if isinstance(game_args, list):
        for index, value in enumerate(game_args[:-1]):
            if isinstance(value, str) and value in ("--launchTarget", "--fml.forgeVersion",
                                                     "--fml.mcVersion", "--fml.forgeGroup"):
                merged_args.setdefault(value, []).append(game_args[index + 1])
    merged_pcl = (
        data.get("clientVersion") == "1.20.1"
        and data.get("mainClass") == "cpw.mods.bootstraplauncher.BootstrapLauncher"
        and merged_args.get("--launchTarget") == ["forgeclient"]
        and merged_args.get("--fml.forgeVersion") == ["47.4.28"]
        and merged_args.get("--fml.mcVersion") == ["1.20.1"]
        and merged_args.get("--fml.forgeGroup") == ["net.minecraftforge"]
    )
    standard_forge = (
        data.get("inheritsFrom") == "1.20.1"
        and "net.minecraftforge:forge:1.20.1-47.4.28" in forge_ids
    )
    if not (merged_pcl or standard_forge):
        raise ValueError("Target must be Minecraft 1.20.1 with Forge 47.4.28 (standard or verified PCL merged descriptor)")
    if not (profile / f"{version_id}.jar").is_file():
        raise FileNotFoundError("PCL version client jar is missing")
    return {"versionId": version_id, "versionJsonSha256": sha256(version_json),
            "versionJarSha256": sha256(profile / f"{version_id}.jar"),
            "forgeVersion": "47.4.28", "descriptorStyle": "pcl-merged" if merged_pcl else "inherited",
            "forgeLibrary": "net.minecraftforge:forge:1.20.1-47.4.28" if standard_forge else None}


def ids_from_field(value):
    if isinstance(value, str):
        return {value.lower()}
    if isinstance(value, list):
        return {str(item).lower() for item in value if isinstance(item, str)}
    return set()


def load_retired_ids():
    lock = read_json(HOTFIX_LOCK)
    disabled, replacements = set(), {}
    for row in lock.get("disabledMods", []):
        if not isinstance(row, dict):
            continue
        disabled.update(ids_from_field(row.get("modId")))
        old_ids = ids_from_field(row.get("replacesModId"))
        new_ids = ids_from_field(row.get("modId"))
        for old_id in old_ids:
            replacements.setdefault(old_id, set()).update(new_ids)
    for row in lock.get("patches", []):
        if not isinstance(row, dict):
            continue
        new_ids = ids_from_field(row.get("modId"))
        old_ids = ids_from_field(row.get("replacesModId"))
        for old_id in old_ids:
            replacements.setdefault(old_id, set()).update(new_ids)
    return disabled, replacements


def read_policy():
    policy = read_json(CONFIG_POLICY)
    return policy


def matches_any(path, patterns):
    return any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns)


def overlay_kind(rel, policy):
    # Only these named embedded visual resources belong to this release.
    # They are optional and never displace or enable a player's resource pack.
    if rel in ("resourcepacks/LCVP_CreateStyle_v1.7.zip", "resourcepacks/Squareful-Create_Delight_Remake.zip"):
        return "add-if-missing"
    if private_path(rel):
        return None
    if rel in ("options.txt", "PCL/config.json", "PCL/Setup.ini") or rel.startswith("PCL/"):
        return None
    if rel.startswith("config/"):
        if rel in ("config/crash_assistant/modlist.json", "config/modpack_defaults/config/crash_assistant/modlist.json"):
            return "authoritative"
        if rel == "config/ic2newera/quest_knowledge.json":
            return "authoritative"
        for prefix in policy.get("excludedTopLevelDirectories", []):
            if rel.startswith("config/" + prefix.strip("/") + "/"):
                return None
        for pattern in policy.get("excludedPathPatterns", []) + policy.get("excludedRuntimeFiles", []):
            if matches_any(rel, [pattern]):
                return None
        for pattern in policy.get("excludedFilenamePatterns", []):
            if matches_any(PurePosixPath(rel).name, [pattern]):
                return None
        if rel == "config/ProjectE/custom_emc.json" or rel.startswith("config/ftbquests/quests/"):
            return "authoritative"
        if matches_any(rel, policy.get("gameplayExamplesIncluded", [])):
            return "add-if-missing"
        # Install the pack's shipped defaults for new mods (including biomes and loot);
        # keep all existing player choices and do not inspect private/excluded files.
        return "add-if-missing"
    if rel.startswith(("defaultconfigs/", "IC2-NewEra-blueprints/", "IC2-NewEra-info/")) or rel == "LICENSE":
        return "add-if-missing"
    if rel.startswith(ALWAYS_AUTHORITATIVE):
        return "authoritative"
    # Published schematics are optional assets: add missing files without replacing a local design.
    if rel.startswith("schematics/"):
        return "add-if-missing"
    return None


def existing_mods(profile):
    rows = []
    mods_dir = profile / "mods"
    if not mods_dir.exists():
        return rows
    if not mods_dir.is_dir():
        raise ValueError(f"Target mods path is not a directory: {mods_dir}")
    for path in sorted(mods_dir.glob("*.jar"), key=lambda p: p.name.casefold()):
        ids = mod_ids(path)
        rows.append({"name": path.name, "path": path, "sha256": sha256(path), "ids": ids})
    return rows


def collect_plan(work, release_dir, profile, version_id, candidate_sha256=None):
    identity = validate_profile(profile, version_id)
    selected, release_sha256 = selected_rows(work, release_dir, profile, candidate_sha256)
    policy = read_policy()
    disabled_ids, replaces_mod_ids = load_retired_ids()
    chosen_ids = {mod_id for row in selected for mod_id in row["ids"]}
    if chosen_ids & disabled_ids:
        raise ValueError(f"Final selection includes disabled mod IDs: {sorted(chosen_ids & disabled_ids)}")
    selected_by_id = {}
    for row in selected:
        for mod_id in row["ids"]:
            selected_by_id.setdefault(mod_id, []).append(row)
    ambiguous = {mod_id: rows for mod_id, rows in selected_by_id.items()
                 if len({row["sha256"] for row in rows}) > 1}
    if ambiguous:
        raise ValueError("Final release selection has different bytes for the same mod ID: "
                         + ", ".join(sorted(ambiguous)))
    for row in selected:
        retired = ids_from_field(row.get("replacesModId"))
        for old_id in retired:
            replaces_mod_ids.setdefault(old_id, set()).update(row["ids"])
    for old_id, successors in replaces_mod_ids.items():
        if old_id in chosen_ids and successors & chosen_ids:
            raise ValueError(f"Final selection keeps retired mod ID {old_id} alongside its replacement")
    current = existing_mods(profile)
    actions, protected = [], {"options.txt", "PCL/**", "saves/**", "screenshots/**", "logs/**",
                             "account/credentials/API-key files", "local client settings"}
    handled_current = set()

    def add_action(action, rel, source=None, expected_old=None, expected_new=None, reason=""):
        actions.append({"action": action, "path": safe_rel(rel), "source": source,
                        "expectedOldSha256": expected_old, "expectedNewSha256": expected_new,
                        "reason": reason})

    # Explicitly retire the known shader conflict and any disabled IDs from the release lock.
    for row in current:
        conflict = bool(row["ids"] & REMOVED_CONFLICT_IDS and "colorwheel" in selected_by_id)
        disabled = bool(row["ids"] & disabled_ids)
        replaced_ids = {old_id for old_id, new_ids in replaces_mod_ids.items()
                        if old_id in row["ids"] and new_ids & chosen_ids}
        replaced = bool(replaced_ids)
        if conflict or disabled or replaced:
            retained_by_new_jars = row["ids"] & chosen_ids
            retired_ids = REMOVED_CONFLICT_IDS | disabled_ids | {
                old_id for old_id, new_ids in replaces_mod_ids.items() if new_ids & chosen_ids
            }
            retained_ids = row["ids"] - retained_by_new_jars - retired_ids
            if retained_ids:
                raise ValueError(f"Removing {row['name']} would remove unrelated mod IDs: {sorted(retained_ids)}")
            if replaced and not conflict and not disabled:
                reason = "retired old mod ID(s): " + ",".join(sorted(replaced_ids))
            elif conflict:
                reason = "r71 colorwheel replaces irisflw"
            else:
                reason = "disabled by hotfix-lock-r71"
            add_action("remove-to-backup", "mods/" + row["name"], expected_old=row["sha256"],
                       reason=reason)
            handled_current.add(row["name"].casefold())

    # Install one exact release jar per selected mod identity. All displaced files are backed up.
    for row in selected:
        target_rel = "mods/" + row["name"]
        target = safe_target(profile, target_rel)
        collision_rows = [old for old in current if old["name"].casefold() not in handled_current
                          and old["ids"] & set(row["ids"])]
        for old in collision_rows:
            if old["name"] == row["name"] and old["sha256"].lower() == row["sha256"]:
                continue
            unknown_ids = old["ids"] - chosen_ids
            if unknown_ids:
                raise ValueError(f"Replacing {old['name']} would remove unrelated mod IDs: {sorted(unknown_ids)}")
            add_action("remove-to-backup", "mods/" + old["name"], expected_old=old["sha256"],
                       reason="replace conflicting mod ID or filename alias with selected r71 jar")
            handled_current.add(old["name"].casefold())
        canonical_present = any(old["name"] == row["name"] and old["sha256"].lower() == row["sha256"]
                                for old in current)
        if target.is_file() and canonical_present and sha256(target).lower() == row["sha256"]:
            # Keep exact bytes under the canonical published filename.
            add_action("keep", target_rel, expected_old=row["sha256"], expected_new=row["sha256"],
                       reason="already exact published jar")
        else:
            add_action("install", target_rel, source=row["source"], expected_new=row["sha256"],
                       reason="selected r71 jar: " + ",".join(row["ids"]))

        # Remove same-byte aliases so the target uses the exact filename from the published MRPack.
        for old in current:
            if old["name"].casefold() == row["name"].casefold() or old["name"].casefold() in handled_current:
                continue
            if old["sha256"].lower() == row["sha256"] and old["ids"] == set(row["ids"]):
                add_action("remove-to-backup", "mods/" + old["name"], expected_old=old["sha256"],
                           reason="unify same-byte alias to published filename")
                handled_current.add(old["name"].casefold())

    # Avoid replacing a selected filename when it is currently occupied by an unrelated jar.
    for row in selected:
        rel = "mods/" + row["name"]
        target = safe_target(profile, rel)
        if target.exists() and not target.is_file():
            raise ValueError(f"Selected jar path is occupied by a non-file: {rel}")
        if target.is_file() and sha256(target).lower() != row["sha256"]:
            old = next((item for item in current if item["name"].casefold() == row["name"].casefold()), None)
            if old is None or old["name"].casefold() not in handled_current:
                raise ValueError(f"Selected filename collides with an unclassified file: {rel}")

    # Copy only published gameplay/data files and policy-approved shared configs.
    overlay = work / "client-overlay"
    if not overlay.is_dir():
        raise FileNotFoundError(f"client-overlay missing: {overlay}")
    overlay_rows = []
    for source in overlay.rglob("*"):
        if not source.is_file():
            continue
        if not source.resolve().is_relative_to(overlay.resolve()):
            raise ValueError(f"Overlay source symlink escapes client-overlay: {source}")
        rel = safe_rel(source.relative_to(overlay).as_posix())
        kind = overlay_kind(rel, policy)
        if kind is None:
            continue
        overlay_rows.append((rel, source, kind))

    required = {"config/ic2newera/quest_knowledge.json", "config/ProjectE/custom_emc.json"}
    found = {rel for rel, _, _ in overlay_rows}
    missing = required - found
    if missing:
        raise FileNotFoundError("Required published gameplay files are missing: " + ", ".join(sorted(missing)))

    for rel, source, kind in sorted(overlay_rows):
        target = safe_target(profile, rel)
        if kind == "add-if-missing" and target.is_file():
            add_action("keep", rel, reason="preserve existing player file")
            continue
        source_hash = sha256(source)
        if rel == "config/ProjectE/custom_emc.json" and target.is_file():
            # Merge keyed entries: release entries win for release-owned item IDs, local extras survive.
            source_data, target_data = read_json(source), read_json(target)
            release_entries = source_data.get("entries", [])
            local_entries = target_data.get("entries", [])
            if not isinstance(release_entries, list) or not isinstance(local_entries, list):
                raise ValueError("ProjectE custom EMC entries must be arrays")
            release_keys = {entry.get("item") for entry in release_entries if isinstance(entry, dict)}
            merged = [entry for entry in local_entries if not isinstance(entry, dict) or entry.get("item") not in release_keys]
            merged.extend(release_entries)
            target_data["entries"] = merged
            merged_bytes = (json.dumps(target_data, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
            merged_hash = hashlib.sha256(merged_bytes).hexdigest()
            if sha256(target) == merged_hash:
                add_action("keep", rel, expected_old=sha256(target), expected_new=merged_hash,
                           reason="ProjectE EMC merge already present")
            else:
                add_action("write-merged-json", rel, source=str(source.resolve()),
                           expected_old=sha256(target), expected_new=merged_hash,
                           reason="merge published ProjectE entries; preserve local extras")
        elif target.is_file():
            old_hash = sha256(target)
            if old_hash == source_hash:
                add_action("keep", rel, expected_old=old_hash, expected_new=old_hash, reason="already exact overlay file")
            elif kind == "add-if-missing":
                add_action("keep", rel, expected_old=old_hash, expected_new=old_hash, reason="preserve player-adjustable config")
            else:
                add_action("replace-to-backup", rel, source=str(source.resolve()), expected_old=old_hash,
                           expected_new=source_hash, reason="authoritative r71 gameplay data")
        else:
            add_action("install", rel, source=str(source.resolve()), expected_new=source_hash,
                       reason="published gameplay data")

    # This named client-only model fix is shipped separately from the visual selection list.
    cmm_rel = "resourcepacks/NewEra-CMM-Model-Fix.zip"
    cmm_source = overlay / cmm_rel
    cmm_fix_present = cmm_source.is_file()
    if cmm_fix_present:
        if not cmm_source.resolve().is_relative_to(overlay.resolve()):
            raise ValueError("CMM model fix source escapes client-overlay")
        cmm_target = safe_target(profile, cmm_rel)
        if cmm_target.exists():
            add_action("keep", cmm_rel, expected_new=None, reason="preserve existing local CMM override")
        else:
            add_action("install", cmm_rel, source=str(cmm_source.resolve()), expected_new=sha256(cmm_source),
                       reason="published CMM model fix; not auto-selected")

    visuals_path = work / "visuals.json"
    visuals = read_json(visuals_path)
    if not isinstance(visuals, list):
        raise ValueError("visuals.json must contain an array")
    visual_seen = set()
    for row in visuals:
        rel = safe_rel(row.get("path"))
        if not rel.startswith(("resourcepacks/", "shaderpacks/")):
            raise ValueError(f"Invalid visual destination: {rel}")
        if rel.casefold() in visual_seen:
            raise ValueError(f"Duplicate visual destination: {rel}")
        visual_seen.add(rel.casefold())
        source = Path(row.get("source", ""))
        if not source.is_absolute() or not source.is_file() or not source.resolve().is_relative_to(work.resolve()):
            raise FileNotFoundError(f"Published visual source missing: {source}")
        source_hash = sha256(source)
        target = safe_target(profile, rel)
        if target.exists():
            # Do not read/hash an existing local visual: preserve the user's selection untouched.
            add_action("keep", rel, expected_new=None, reason="preserve existing local visual")
        else:
            add_action("install", rel, source=str(source.resolve()), expected_new=source_hash,
                       reason="published visual asset; not auto-selected")

    source_fingerprint = {
        "selectionSha256": sha256(work / "selection.json"),
        "mrpackSha256": release_sha256,
        "overlayFiles": sorted((rel, sha256(source)) for rel, source, _ in overlay_rows),
        "visualsSha256": sha256(visuals_path),
    }
    for action_index, action in enumerate(actions):
        if action["action"] == "keep":
            continue
        target = safe_target(profile, action["path"])
        if action["action"] in ("remove-to-backup", "replace-to-backup", "write-merged-json"):
            # Check path/hash only for files explicitly selected for modification.
            if not target.is_file() or sha256(target) != action["expectedOldSha256"]:
                raise ValueError(f"Plan target changed during planning: {action['path']}")
        elif action["action"] == "install" and target.exists():
            removed_before = any(previous["action"] == "remove-to-backup"
                                 and previous["path"].casefold() == action["path"].casefold()
                                 for previous in actions[:action_index])
            if not removed_before:
                raise ValueError(f"Plan install path unexpectedly exists: {action['path']}")
    return {
        "schema": 1, "release": "r71", "profile": str(profile), "profileIdentity": identity,
        "sourceRoot": str(work.resolve()), "releaseRoot": str(release_dir.resolve()),
        "sourceFingerprint": source_fingerprint, "selectedJarCount": len(selected),
        "cmmModelFixSourcePresent": cmm_fix_present,
        "selectedModIds": sorted(chosen_ids), "disabledModIds": sorted(disabled_ids),
        "replacedModIds": {old: sorted(new) for old, new in sorted(replaces_mod_ids.items())},
        "candidateSha256": candidate_sha256.lower() if candidate_sha256 else None,
        "actions": actions, "protectedPersonalPaths": sorted(protected),
    }


def write_plan(plan, plan_out):
    if plan_out.exists():
        raise FileExistsError(f"Refusing to overwrite an existing dry-run plan: {plan_out}")
    plan_out.parent.mkdir(parents=True, exist_ok=True)
    fingerprint = plan_hash(plan)
    envelope = {"planSha256": fingerprint, "plan": plan}
    write_json(plan_out, envelope)
    return fingerprint


def preflight(plan):
    profile = Path(plan["profile"])
    for index, action in enumerate(plan["actions"]):
        target = safe_target(profile, action["path"])
        kind = action["action"]
        if kind == "keep":
            if action.get("expectedOldSha256"):
                if not target.is_file() or sha256(target) != action["expectedOldSha256"]:
                    raise ValueError(f"Kept file changed since plan: {action['path']}")
            elif not target.exists():
                raise ValueError(f"Preserved visual changed since plan: {action['path']}")
        elif kind in ("remove-to-backup", "replace-to-backup", "write-merged-json"):
            if not target.is_file() or sha256(target) != action["expectedOldSha256"]:
                raise ValueError(f"Target changed since plan: {action['path']}")
        elif kind == "install" and target.exists():
            removed_before = any(previous["action"] == "remove-to-backup"
                                 and previous["path"].casefold() == action["path"].casefold()
                                 for previous in plan["actions"][:index])
            if not removed_before:
                raise ValueError(f"Install target appeared since plan: {action['path']}")
        if action.get("source"):
            source = Path(action["source"])
            if not source.is_file():
                raise FileNotFoundError(f"Install source disappeared: {source}")
            expected = action.get("expectedNewSha256")
            if expected and sha256(source) != expected:
                # The EMC merged value is generated rather than byte-identical to its source.
                if action["action"] != "write-merged-json":
                    raise ValueError(f"Install source changed since plan: {source}")


def make_emc_merge(action, profile):
    source_data = read_json(Path(action["source"]))
    target = safe_target(profile, action["path"])
    target_data = read_json(target)
    release_entries = source_data.get("entries", [])
    local_entries = target_data.get("entries", [])
    release_keys = {entry.get("item") for entry in release_entries if isinstance(entry, dict)}
    merged = [entry for entry in local_entries if not isinstance(entry, dict) or entry.get("item") not in release_keys]
    merged.extend(release_entries)
    target_data["entries"] = merged
    return (json.dumps(target_data, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def atomic_install(target, source=None, content=None):
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix=".r71-install-", dir=target.parent)
    os.close(fd)
    temp = Path(temp_name)
    try:
        if content is not None:
            temp.write_bytes(content)
        elif source is not None:
            shutil.copy2(source, temp)
        else:
            raise ValueError("Install needs source or content")
        os.replace(temp, target)
    finally:
        if temp.exists():
            temp.unlink()


def apply_plan(plan, backup_root):
    preflight(plan)
    transaction_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:10]
    backup_root = backup_root.resolve()
    safe_base = (ROOT / "build-isolated").resolve()
    if not backup_root.is_relative_to(safe_base):
        raise ValueError("Backup root must stay inside build-isolated")
    transaction = backup_root / plan["profileIdentity"]["versionId"] / transaction_id
    transaction.mkdir(parents=True, exist_ok=False)
    profile = Path(plan["profile"])
    receipt = {"schema": 1, "status": "applying", "release": "r71", "transactionId": transaction_id,
               "profile": plan["profile"], "profileIdentity": plan["profileIdentity"],
               "planSha256": plan_hash(plan), "backupDirectory": str(transaction),
               "protectedPersonalPaths": plan["protectedPersonalPaths"], "operations": []}
    receipt_path = transaction / "install-receipt.json"
    applied = []
    try:
        # Preserve every replaced/removed file before changing any target.
        for index, action in enumerate(plan["actions"]):
            if action["action"] not in ("remove-to-backup", "replace-to-backup", "write-merged-json"):
                continue
            target = safe_target(profile, action["path"])
            backup_rel = f"files/{index:05d}/{PurePosixPath(action['path']).name}"
            backup_path = safe_target(transaction, backup_rel)
            backup_path.parent.mkdir(parents=True, exist_ok=True)
            if backup_path.exists():
                raise FileExistsError(backup_path)
            shutil.copy2(target, backup_path)
            if sha256(backup_path) != action["expectedOldSha256"]:
                raise IOError(f"Backup byte verification failed: {action['path']}")
            action["_backup"] = backup_rel

        for action in plan["actions"]:
            kind = action["action"]
            target = safe_target(profile, action["path"])
            if kind == "keep":
                continue
            if kind == "remove-to-backup":
                target.unlink()
                actual = None
                applied.append((action, target))
            elif kind == "replace-to-backup":
                atomic_install(target, source=Path(action["source"]))
                applied.append((action, target))
                actual = sha256(target)
            elif kind == "write-merged-json":
                content = make_emc_merge(action, profile)
                if hashlib.sha256(content).hexdigest() != action["expectedNewSha256"]:
                    raise ValueError("ProjectE EMC merge no longer matches the approved plan")
                atomic_install(target, content=content)
                applied.append((action, target))
                actual = sha256(target)
            elif kind == "install":
                if target.exists():
                    raise ValueError(f"Install target appeared during transaction: {action['path']}")
                atomic_install(target, source=Path(action["source"]))
                applied.append((action, target))
                actual = sha256(target)
            else:
                raise ValueError(f"Unknown plan operation: {kind}")
            expected_after = action.get("expectedNewSha256")
            if expected_after != actual:
                raise IOError(f"Installed bytes differ from plan: {action['path']}")
            operation = {key: value for key, value in action.items() if not key.startswith("_")}
            operation["backup"] = action.get("_backup")
            operation["actualNewSha256"] = actual
            receipt["operations"].append(operation)

        receipt["status"] = "installed"
        receipt["installedCount"] = sum(1 for op in receipt["operations"] if op["action"] in ("install", "replace-to-backup", "write-merged-json"))
        receipt["removedCount"] = sum(1 for op in receipt["operations"] if op["action"] == "remove-to-backup")
        receipt["keptCount"] = sum(1 for op in plan["actions"] if op["action"] == "keep")
        write_json(receipt_path, receipt)
        return receipt
    except Exception as error:
        # Restore changed targets only from this transaction's verified backups.
        for action, target in reversed(applied):
            backup_rel = action.get("_backup")
            if backup_rel:
                backup = safe_target(transaction, backup_rel)
                if backup.is_file() and sha256(backup) == action["expectedOldSha256"]:
                    atomic_install(target, source=backup)
            elif target.exists():
                target.unlink()
        receipt["status"] = "failed-rolled-back"
        receipt["error"] = str(error)
        write_json(receipt_path, receipt)
        raise


def rollback(receipt_path):
    receipt_path = receipt_path.resolve()
    if not receipt_path.is_file():
        raise FileNotFoundError(receipt_path)
    safe_base = (ROOT / "build-isolated").resolve()
    if not receipt_path.is_relative_to(safe_base):
        raise ValueError("Rollback receipt must be inside build-isolated")
    receipt = read_json(receipt_path)
    if receipt.get("status") != "installed":
        raise ValueError("Only a completed install receipt can be rolled back")
    transaction = Path(receipt["backupDirectory"]).resolve()
    if not transaction.is_relative_to(safe_base):
        raise ValueError("Receipt backup path is outside build-isolated")
    profile = Path(receipt["profile"]).resolve()
    # Verify final live state per path before restoring any file. A selected jar can
    # intentionally replace a conflicting file at the same filename in two steps.
    final_by_path = {}
    for operation in receipt["operations"]:
        final_by_path[operation["path"].casefold()] = operation
    for operation in final_by_path.values():
        target = safe_target(profile, operation["path"])
        expected = operation.get("actualNewSha256")
        if expected is None:
            if target.exists():
                raise ValueError(f"Removed path has since been recreated; rollback refused: {operation['path']}")
        elif not target.is_file() or sha256(target) != expected:
            raise ValueError(f"Installed file changed since receipt; rollback refused: {operation['path']}")
    for operation in receipt["operations"]:
        backup_rel = operation.get("backup")
        if backup_rel:
            backup = safe_target(transaction, backup_rel)
            if not backup.is_file() or sha256(backup) != operation["expectedOldSha256"]:
                raise ValueError(f"Backup missing or changed; rollback refused: {backup_rel}")

    for operation in reversed(receipt["operations"]):
        target = safe_target(profile, operation["path"])
        backup_rel = operation.get("backup")
        if backup_rel:
            atomic_install(target, source=safe_target(transaction, backup_rel))
        elif target.exists():
            target.unlink()
    receipt["status"] = "rolled-back"
    receipt["rolledBackUtc"] = datetime.now(timezone.utc).isoformat()
    write_json(receipt_path, receipt)
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, default=DEFAULT_WORK)
    parser.add_argument("--release-dir", type=Path, default=DEFAULT_RELEASE)
    parser.add_argument("--candidate-sha256", help="Independent pin for a freshly exported MRPack before verify_release publishes a manifest")
    parser.add_argument("--profile", type=Path, required=True, help="Existing PCL version directory")
    parser.add_argument("--version-id", required=True, help="Must match the directory and its PCL JSON id")
    parser.add_argument("--plan-out", type=Path, help="Dry-run plan destination (defaults under build-isolated)")
    parser.add_argument("--apply", action="store_true", help="Apply after matching --confirm-plan and --confirm-stopped")
    parser.add_argument("--confirm-plan", help="SHA-256 printed by the dry-run plan")
    parser.add_argument("--confirm-stopped", action="store_true", help="Assert Minecraft is fully closed")
    parser.add_argument("--backup-root", type=Path, default=BACKUP_ROOT)
    parser.add_argument("--rollback", type=Path, help="Install receipt to safely roll back")
    args = parser.parse_args()

    if args.rollback:
        result = rollback(args.rollback)
        print(json.dumps({"status": "rolled-back", "profile": result["profile"],
                          "planSha256": result["planSha256"], "restoredOperations": len(result["operations"]),
                          "backupDirectory": result["backupDirectory"]}, ensure_ascii=False))
        return

    work, release_dir = args.work.resolve(), args.release_dir.resolve()
    profile = args.profile.resolve()
    plan = collect_plan(work, release_dir, profile, args.version_id, args.candidate_sha256)
    fingerprint = plan_hash(plan)
    if args.apply:
        if not args.confirm_stopped:
            raise ValueError("Applying requires --confirm-stopped")
        if not args.confirm_plan or args.confirm_plan.lower() != fingerprint:
            raise ValueError(f"Plan changed or was not confirmed. Current plan SHA-256: {fingerprint}")
        receipt = apply_plan(plan, args.backup_root)
        print(json.dumps({"status": receipt["status"], "profile": receipt["profile"],
                          "planSha256": receipt["planSha256"], "installed": receipt.get("installedCount", 0),
                          "removed": receipt.get("removedCount", 0), "kept": receipt.get("keptCount", 0),
                          "privateFilesInspected": False, "protectedPersonalPaths": receipt["protectedPersonalPaths"],
                          "backupDirectory": receipt["backupDirectory"], "receipt": str(Path(receipt["backupDirectory"]) / "install-receipt.json")}, ensure_ascii=False))
        return

    if args.confirm_plan or args.confirm_stopped:
        raise ValueError("Confirmation flags are accepted only together with --apply")
    plan_suffix = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
    plan_out = args.plan_out.resolve() if args.plan_out else PLAN_DIR / f"{args.version_id}-{plan_suffix}.json"
    if not plan_out.resolve().is_relative_to((ROOT / "build-isolated").resolve()):
        raise ValueError("Dry-run plan must be written inside build-isolated")
    written_hash = write_plan(plan, plan_out)
    counts = {}
    for action in plan["actions"]:
        counts[action["action"]] = counts.get(action["action"], 0) + 1
    print(json.dumps({"status": "dry-run", "profile": str(profile), "planSha256": written_hash,
                      "plan": str(plan_out), "selectedJars": plan["selectedJarCount"],
                      "candidateSha256": plan["candidateSha256"], "actions": counts, "privateFilesInspected": False,
                      "protectedPersonalPaths": plan["protectedPersonalPaths"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
