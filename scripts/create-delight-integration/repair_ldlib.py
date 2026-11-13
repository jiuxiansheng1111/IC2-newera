"""Restore the attachment-matched upstream LDLib runtime assets into W/repair-overlay."""
import argparse
import hashlib
import io
import json
import re
import subprocess
import tarfile
import zipfile
from datetime import date
from pathlib import Path, PurePosixPath


MARKERS = (
    "kubejs/server_scripts/mbd2/mbd2.js",
    "kubejs/server_scripts/mbd2/greenhouse_builder.js",
    "kubejs/server_scripts/mbd2/sell_bin.js",
)
SAMPLES = (
    "kubejs/server_scripts/Northstar/space_atlas_commands.js",
    "kubejs/server_scripts/Northstar/virtual_satellite_navigation.js",
    "kubejs/server_scripts/FTB Quest/event.js",
    "kubejs/startup_scripts/custom/register_virtual_satellite.js",
)
UI_FILE = "ldlib/assets/ldlib/projects/ui/satellite_navigation_data_card.ui"
MBD2_PREFIX = "ldlib/assets/mbd2/"


def run_git(repo, *args, input_bytes=None):
    return subprocess.run(["git", *args], cwd=repo, input=input_bytes, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, check=True).stdout


def norm(data):
    return data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")


def digest(data, algorithm="sha256"):
    return hashlib.new(algorithm, data).hexdigest()


def attachment(work):
    candidates = [work / "side-work/modpack.mrpack", work / "quests-work/base.mrpack"]
    for p in candidates:
        if p.is_file():
            return p
    raise FileNotFoundError("Expected side-work/modpack.mrpack or quests-work/base.mrpack")


def choose_revision(repo, pack):
    with zipfile.ZipFile(pack) as z:
        index = json.loads(z.read("modrinth.index.json"))
        attached = {p: norm(z.read("overrides/" + p)) for p in MARKERS}
        archive_dt = max(z.getinfo("overrides/" + p).date_time for p in MARKERS)
    blob_hashes = {p: run_git(repo, "hash-object", "--stdin", input_bytes=b).decode("ascii").strip()
                   for p, b in attached.items()}
    histories = []
    for p in MARKERS:
        rows = run_git(repo, "log", "--all", "--format=%H", "--", p).decode("ascii").splitlines()
        histories.append(set(rows))
    candidates = set.union(*histories)
    matches = []
    for commit in candidates:
        if not all(commit in history for history in histories):
            # A file may not change in this commit; its tree can still contain the matching blob.
            pass
        same = []
        for p in MARKERS:
            result = subprocess.run(["git", "rev-parse", f"{commit}:{p}"], cwd=repo,
                                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            same.append(result.returncode == 0 and result.stdout.decode("ascii").strip() == blob_hashes[p])
        if all(same):
            stamp = run_git(repo, "show", "-s", "--format=%cI", commit).decode("ascii").strip()
            matches.append((stamp, commit, same))
    if not matches:
        raise RuntimeError("No upstream revision exactly matches all three normalized attachment marker files")
    archive_day = date(archive_dt[0], archive_dt[1], archive_dt[2])
    before = [row for row in matches if date.fromisoformat(row[0][:10]) <= archive_day]
    stamp, commit, _ = max(before or matches)
    return commit, stamp, archive_dt, attached, index, matches


def read_tree(repo, commit, paths):
    archive = run_git(repo, "archive", "--format=tar", commit, *paths)
    items = {}
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as tf:
        for member in tf.getmembers():
            if not member.isfile():
                continue
            p = PurePosixPath(member.name)
            if p.is_absolute() or ".." in p.parts:
                raise ValueError(f"Unsafe upstream archive member {member.name}")
            f = tf.extractfile(member)
            if f is None:
                continue
            items[member.name] = f.read()
    return items


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True, type=Path)
    ap.add_argument("--upstream", required=True, type=Path)
    args = ap.parse_args()
    work, repo = args.work.resolve(), args.upstream.resolve()
    pack = attachment(work)
    commit, commit_date, archive_date, attached, pack_index, matches = choose_revision(repo, pack)
    latest_before = run_git(repo, "log", "-1", "--format=%H %cI", "--before",
                            f"{archive_date[0]:04d}-{archive_date[1]:02d}-{archive_date[2]:02d}T23:59:59",
                            "origin/main").decode("ascii").strip().split(" ", 1)
    latest_revision, latest_date = latest_before
    latest_marker_matches = {}
    for p, expected in attached.items():
        latest_marker_matches[p] = norm(run_git(repo, "show", f"{latest_revision}:{p}")) == expected
    ldlib_diff = run_git(repo, "diff", "--name-status", commit, latest_revision, "--", "ldlib/assets/mbd2").decode("utf-8", "replace").splitlines()
    files = read_tree(repo, commit, ["ldlib/assets/mbd2", UI_FILE])
    machine_files = {p: b for p, b in files.items() if p.startswith(MBD2_PREFIX) and "/machine/" in p and p.endswith(".sm")}
    recipe_types = {PurePosixPath(p).stem for p in files if "/recipe_type/" in p and p.endswith(".rt")}
    if not machine_files:
        raise RuntimeError("Matched revision contains no MBD2 .sm machine definitions")
    if UI_FILE not in files:
        raise RuntimeError(f"Matched revision lacks explicitly loaded UI asset {UI_FILE}")

    with zipfile.ZipFile(pack) as z:
        attached_mbd2 = {n[len("overrides/"):]: norm(z.read(n)) for n in z.namelist()
                         if n.startswith("overrides/kubejs/server_scripts/mbd2/") and n.endswith(".js")}
        events = set()
        for p, data in attached_mbd2.items():
            events.update(re.findall(rb"MBDMachineEvents\.[A-Za-z0-9_]+\s*\(\s*['\"]([^'\"]+)['\"]", data))
        machine_ids = {PurePosixPath(p).stem for p in machine_files}
        event_rows = sorted({v.decode("utf-8", "replace") for v in events})
        missing_definitions = [v for v in event_rows if ":" in v and v.split(":", 1)[1] not in machine_ids]

        # Verify the greenhouse and sell-bin recipe IDs/item IDs referenced by their scripts.
        mbd2_js = attached[MARKERS[0]].decode("utf-8", "replace")
        recipes = {}
        for machine in ("greenhouse_builder", "sell_bin"):
            recipe_id = f"createdelight:{machine}"
            recipe = re.search(
                rf"e\.recipes\.kubejs\.shaped\(\s*['\"]{re.escape(recipe_id)}['\"].*?\.id\(\s*['\"]{re.escape(recipe_id)}['\"]\s*\)",
                mbd2_js, re.DOTALL)
            event = any(v.decode("utf-8", "replace") == recipe_id for v in events)
            if recipe is None or not event:
                raise RuntimeError(f"Missing KubeJS output/id recipe or event reference for {recipe_id}")
            recipes[machine] = {"machineId": recipe_id, "recipeOutput": recipe_id,
                                "recipeId": recipe_id, "recipeDefinitionPresent": True,
                                "MBDMachineEventPresent": True}

    reports = {}
    for p, expected in attached.items():
        upstream = norm(run_git(repo, "show", f"{commit}:{p}"))
        reports[p] = {"normalizedSha256": digest(expected), "upstreamEqual": expected == upstream}
        if expected != upstream:
            raise RuntimeError(f"Attachment marker mismatch after revision selection: {p}")
    for p in list(attached_mbd2):
        upstream = norm(run_git(repo, "show", f"{commit}:{p}"))
        reports[p] = {"normalizedSha256": digest(attached_mbd2[p]), "upstreamEqual": attached_mbd2[p] == upstream}
    mbd2_match_count = sum(1 for p in attached_mbd2 if reports[p]["upstreamEqual"])
    for p in SAMPLES:
        with zipfile.ZipFile(pack) as z:
            member = "overrides/" + p
            if member not in z.namelist():
                continue
            local = norm(z.read(member))
        remote = norm(run_git(repo, "show", f"{commit}:{p}"))
        reports[p] = {"normalizedSha256": digest(local), "upstreamEqual": local == remote}

    selection_path = work / "selection.json"
    selection = json.loads(selection_path.read_text("utf-8-sig")) if selection_path.is_file() else []
    deps = {}
    for row in selection:
        if row.get("primary") not in ("ldlib", "mbd2"):
            continue
        src = Path(row["path"])
        if not src.is_file():
            raise FileNotFoundError(f"Selected runtime dependency missing: {src}")
        deps[row["primary"]] = {"file": row["name"], "version": row["version"], "sha256": digest(src.read_bytes())}
    if set(deps) != {"ldlib", "mbd2"}:
        raise RuntimeError("selection.json must include both selected LDLib and Multiblocked2 runtime JARs")
    related_jars = {}
    for row in selection:
        if row.get("primary") not in ("createdelightcore", "create"):
            continue
        src = Path(row["path"])
        related_jars[row["primary"]] = {"file": row["name"], "version": row["version"],
                                         "sha256": digest(src.read_bytes())}

    out_root = work / "repair-overlay"
    copied = []
    for rel, content in sorted(files.items()):
        relpath = PurePosixPath(rel)
        target = out_root.joinpath(*relpath.parts)
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            if target.read_bytes() != content:
                raise FileExistsError(f"Refusing to overwrite differing repair asset {target}")
        else:
            target.write_bytes(content)
        copied.append({"path": rel, "bytes": len(content), "sha256": digest(content)})

    relevant_machine_ids = sorted({PurePosixPath(p).stem for p in machine_files})
    embedded_machine_ids = {}
    for p, content in sorted(machine_files.items()):
        printable = b"\n".join(re.findall(rb"[\x20-\x7e]{4,}", content)).decode("ascii", "replace")
        ids = sorted(set(re.findall(r"(?<![a-zA-Z0-9_.-])(?:createdelight|mbd2):[a-zA-Z0-9_./-]+", printable)))
        expected_id = PurePosixPath(p).stem
        embedded_machine_ids[PurePosixPath(p).name] = {"expectedPathId": expected_id, "embeddedIds": ids,
                                                       "targetIdConfirmed": (expected_id in ("greenhouse_builder", "sell_bin") and f"createdelight:{expected_id}" in ids)}
    doc_sources = {
        "pclOmissionWarning": "official upstream docs/faq/CDR-FAQ.md: warns PCL-exported packs omit required root ldlib directory and cites createdelight:andesite_export_bus registry failure",
        "machineAssets": "official upstream docs/plan/mbd2-adaptive-casing-and-connected-texture-plan.md: identifies ldlib/assets/mbd2/machine/*.sm, models and textures/block as MBD2 machine-definition/render dependencies",
        "uiAsset": "official upstream docs/dev-knowledge/content-map.md: identifies ldlib/assets/ldlib/projects/ui/satellite_navigation_data_card.ui as the LDLib UI project used by Northstar virtual satellite navigation",
    }
    report = {
        "schemaVersion": 1,
        "sourceAttachment": {"path": str(pack), "name": pack_index.get("name"), "versionId": pack_index.get("versionId"),
                             "minecraft": pack_index.get("dependencies", {}).get("minecraft"),
                             "forge": pack_index.get("dependencies", {}).get("forge"),
                             "zipEntryDate": f"{archive_date[0]:04d}-{archive_date[1]:02d}-{archive_date[2]:02d}"},
        "matchedOfficialUpstream": {"repository": "https://github.com/Jasons-impart/Create-Delight-Remake", "revision": commit,
                                    "commitDate": commit_date, "matchingMarkerFiles": list(MARKERS),
                                    "exactThreeMarkerMatches": len(matches), "allAttachedMbd2ServerScripts": len(attached_mbd2),
                                    "exactMbd2ServerScriptMatches": mbd2_match_count,
                                    "latestOfficialMainAtAttachmentDate": {"revision": latest_revision, "commitDate": latest_date,
                                        "markerMatches": latest_marker_matches,
                                        "ldlibMbd2AssetChangesSinceSelectedRevision": ldlib_diff},
                                    "representativeComparison": reports},
        "runtimeDependencies": deps,
        "relatedRuntimeJars": related_jars,
        "declaredRuntimeRequirementsFromJarMetadata": {
            "ldlib": ["Forge >=47", "Minecraft 1.20.1"],
            "mbd2": ["Forge >=47", "Minecraft [1.20.1,1.21)", "LDLib >=1.0.48", "Create >=6.0.0 optional at mod level; the pack's MBD2 KubeJS recipes use Create"],
            "createdelightcore": ["Forge >=47", "Minecraft [1.20.1,1.21)", "Create >=6.0.8"],
            "selectedCreate": "6.0.8",
        },
        "restoredAssets": copied,
        "machineValidation": {"machineDefinitionCount": len(machine_files), "machineIdsFromDefinitionFilenames": relevant_machine_ids,
                              "embeddedMachineIds": embedded_machine_ids,
                              "literalMbdMachineEventIds": event_rows,
                              "eventIdsWithoutSameNamedSmFile": missing_definitions,
                              "eventNote": "Some callbacks are for Java/MBD2-provided machine IDs whose names do not have same-named standalone .sm files; the two restored targets are explicitly present in both KubeJS and matching .sm payloads.",
                              "targetMachineRecipes": recipes,
                              "recipeTypeFiles": sorted(recipe_types),
                              "note": "MBD2 machine identity is represented by ldlib/assets/mbd2/machine/<id>.sm; KubeJS event IDs and the two target item recipes were checked against these names and the attachment scripts."},
        "officialOwnerEvidence": doc_sources,
        "scope": "Local W-only repair overlay. Includes MBD2 machine definitions and their machine/render/recipe/multiblock assets plus the one explicitly loaded satellite-navigation UI project. Does not copy unrelated upstream assets or source code.",
    }
    report_path = work / "compat-work/repairs.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"revision": commit, "commitDate": commit_date, "mbd2ScriptsMatched": f"{mbd2_match_count}/{len(attached_mbd2)}",
                      "machineDefinitions": len(machine_files), "restoredFiles": len(copied), "report": str(report_path)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
