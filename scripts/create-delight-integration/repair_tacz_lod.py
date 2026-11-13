#!/usr/bin/env python3
"""Repair the known bad optional LOD references in staged r71 TaCZ gunpacks.

Read the two original archives from the pinned base MRPack. The exported function
updates only the two exact archive filenames inside the r71 build overlay or
the dedicated scratch directory, after content-equivalence checks.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import io
import json
import zipfile
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parents[2]
RUN_ROOT = ROOT / "build-isolated/player-packaging/20261113-delight-joint"
OUTPUT = RUN_ROOT / "r71-three-fixes/lod"
SOURCE_TACZ_DIR = RUN_ROOT / "client-overlay/tacz"
BASE = RUN_ROOT / "quests-work/base.mrpack"
STAGED_TACZ_DIR = OUTPUT / "tacz"

PACKS = {
    "Applied Armorer-v1.1.4.1-for114+.zip": {
        "sha256": "e52ddb8b6412951f9b79c9ed9a7521a849cef4192f19af4843a3392cdbcfc420",
        "namespace": "applied_armorer",
        "expected_broken": {
            "moritz_gernade_gl3",
            "moritz_mg_emg_prototype",
            "niklas_pistol_semi_pride",
            "special_melee_task_manager",
        },
    },
    "Create Armorer-v1.1.4-for114+.zip": {
        "sha256": "ee126f32daeca2d5b1baa48579c05d6bb5fc0cd7e0db06e43df118c0bb7bf4f8",
        "namespace": "create_armorer",
        "expected_broken": {
            "mg_platemag_flywheel",
            "pistol_auto_stress",
            "rifle_assult_crane",
            "shotgun_db_stone",
            "sniper_semi_clockwork",
            "special_melee_atomic",
            "special_melee_wrench",
        },
    },
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def asset_path(resource_location: str, extension: str) -> str:
    if ":" not in resource_location:
        raise ValueError(f"Expected namespaced resource location: {resource_location}")
    namespace, resource = resource_location.split(":", 1)
    folder = "geo_models" if extension == ".json" else "textures"
    return f"assets/{namespace}/{folder}/{resource}{extension}"


def _read_zip(path: Path) -> tuple[list[zipfile.ZipInfo], dict[str, bytes]]:
    with zipfile.ZipFile(path, "r") as archive:
        infos = archive.infolist()
        if archive.testzip() is not None:
            raise ValueError(f"ZIP CRC validation failed: {path}")
        contents = {info.filename: archive.read(info.filename) for info in infos if not info.is_dir()}
    if len(contents) != sum(not info.is_dir() for info in infos):
        raise ValueError(f"Duplicate ZIP members are not accepted: {path}")
    return infos, contents


def _display_docs(namespace: str, contents: dict[str, bytes]) -> dict[str, dict]:
    prefix = f"assets/{namespace}/display/guns/"
    docs: dict[str, dict] = {}
    for name, content in contents.items():
        if name.startswith(prefix) and name.endswith("_display.json"):
            parsed = json.loads(content)
            if not isinstance(parsed, dict):
                raise ValueError(f"Display JSON must be an object: {name}")
            docs[name] = parsed
    return docs


def _resource_inventory(tacz_dir: Path) -> set[str]:
    """Collect assets from all installed TaCZ gunpacks, not just one namespace ZIP."""
    assets: set[str] = set()
    for path in tacz_dir.iterdir():
        if path.is_dir():
            for asset in path.rglob("*"):
                relative = asset.relative_to(path).as_posix()
                if asset.is_file() and relative.startswith("assets/"):
                    assets.add(relative)
        elif path.is_file() and path.suffix.lower() == ".zip":
            with zipfile.ZipFile(path, "r") as archive:
                assets.update(name for name in archive.namelist() if name.startswith("assets/") and not name.endswith("/"))
    return assets


def _broken_lod_members(namespace: str, contents: dict[str, bytes], resource_inventory: set[str]) -> dict[str, dict]:
    docs = _display_docs(namespace, contents)
    broken: dict[str, dict] = {}
    for display_path, display in docs.items():
        lod = display.get("lod")
        if lod is None:
            continue
        if not isinstance(lod, dict) or not isinstance(lod.get("model"), str) or not isinstance(lod.get("texture"), str):
            raise ValueError(f"Malformed LOD object: {display_path}")
        missing = []
        for resource, extension in ((lod["model"], ".json"), (lod["texture"], ".png")):
            member = asset_path(resource, extension)
            if member not in resource_inventory:
                missing.append(member)
        if missing:
            broken[PurePosixPath(display_path).stem.removesuffix("_display")] = {
                "display_path": display_path,
                "lod": lod,
                "missing_assets": missing,
            }
    return broken


def _require_primary_assets(resource_inventory: set[str], display_path: str, display: dict) -> None:
    for field, extension in (("model", ".json"), ("texture", ".png")):
        if not isinstance(display.get(field), str):
            raise ValueError(f"Missing primary {field} field in {display_path}")
        member = asset_path(display[field], extension)
        if member not in resource_inventory:
            raise ValueError(f"Primary {field} asset is missing: {display_path}: {member}")


def _canonical_without_lod(display: dict) -> dict:
    canonical = copy.deepcopy(display)
    canonical.pop("lod", None)
    return canonical


def _validate_candidate_matches_source(
    archive_name: str,
    namespace: str,
    source_contents: dict[str, bytes],
    candidate_contents: dict[str, bytes],
    expected_broken: set[str],
) -> None:
    if set(candidate_contents) != set(source_contents):
        missing = sorted(set(source_contents) - set(candidate_contents))
        extra = sorted(set(candidate_contents) - set(source_contents))
        raise ValueError(f"ZIP member set differs for {archive_name}; missing={missing[:5]}, extra={extra[:5]}")

    source_docs = _display_docs(namespace, source_contents)
    candidate_docs = _display_docs(namespace, candidate_contents)
    if set(source_docs) != set(candidate_docs):
        raise ValueError(f"Display member set differs for {archive_name}")

    for name in source_contents:
        if name not in source_docs:
            if candidate_contents[name] != source_contents[name]:
                raise ValueError(f"Unexpected non-display content change in {archive_name}: {name}")
            continue
        source_doc = source_docs[name]
        candidate_doc = candidate_docs[name]
        gun_id = PurePosixPath(name).stem.removesuffix("_display")
        if gun_id in expected_broken:
            if candidate_doc not in (source_doc, _canonical_without_lod(source_doc)):
                raise ValueError(f"Unexpected change to bad-LOD display JSON in {archive_name}: {name}")
        elif candidate_doc != source_doc:
            raise ValueError(f"Unexpected display change (including an effective LOD) in {archive_name}: {name}")


def repair_installed_tree(tacz_dir: Path) -> dict:
    """Repair two exact gunpack ZIPs in an isolated staged ``tacz`` directory.

    Reentrant: each ZIP may be either byte-content-equivalent to its pinned original
    package or equivalent to the already-repaired form. The function rejects missing
    main assets, unanticipated broken LODs, extra/missing ZIP members, and unrelated
    edits. It writes only the two expected ZIPs beneath ``tacz_dir``.
    """
    tacz_dir = tacz_dir.resolve()
    output_root = OUTPUT.resolve()
    if tacz_dir != SOURCE_TACZ_DIR.resolve() and not tacz_dir.is_relative_to(output_root):
        raise ValueError(f"Refusing to modify outside this task's isolated output tree: {tacz_dir}")
    if tacz_dir.name != "tacz":
        raise ValueError(f"Expected the staged TaCZ directory named 'tacz': {tacz_dir}")

    result = {"tacz_dir": str(tacz_dir), "archives": []}
    for archive_name, config in PACKS.items():
        namespace = config["namespace"]
        expected_broken = config["expected_broken"]
        staged_path = tacz_dir / archive_name
        if not staged_path.is_file():
            raise FileNotFoundError(staged_path)
        with zipfile.ZipFile(BASE) as base:
            original = base.read("overrides/tacz/" + archive_name)
        if sha256(original) != config["sha256"]:
            raise ValueError(f"Unexpected original gunpack in r71 base: {archive_name}")
        _, source_contents = _read_zip(io.BytesIO(original))
        inventory = _resource_inventory(SOURCE_TACZ_DIR) | _resource_inventory(tacz_dir)
        source_broken = _broken_lod_members(namespace, source_contents, inventory)
        detected = set(source_broken)
        if detected != expected_broken:
            raise ValueError(f"Unexpected broken LOD inventory for {archive_name}: {sorted(detected)}")
        source_docs = _display_docs(namespace, source_contents)
        for gun_id in sorted(expected_broken):
            display_path = source_broken[gun_id]["display_path"]
            _require_primary_assets(inventory, display_path, source_docs[display_path])

        staged_infos, staged_contents = _read_zip(staged_path)
        before_sha = sha256(staged_path.read_bytes())
        _validate_candidate_matches_source(
            archive_name,
            namespace,
            source_contents,
            staged_contents,
            expected_broken,
        )

        repaired_contents = dict(staged_contents)
        modified_members = []
        for gun_id in sorted(expected_broken):
            display_path = source_broken[gun_id]["display_path"]
            current = json.loads(repaired_contents[display_path])
            if "lod" in current:
                del current["lod"]
                repaired_contents[display_path] = (json.dumps(current, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
                modified_members.append(display_path)

        if _broken_lod_members(namespace, repaired_contents, inventory):
            raise ValueError(f"LOD references remain broken after repair: {archive_name}")
        if len(modified_members) not in (0, len(expected_broken)):
            raise ValueError(f"Partially repaired ZIP state is not accepted: {archive_name}")

        if modified_members:
            tmp_path = staged_path.with_name(staged_path.name + ".lod-repair.tmp")
            try:
                with zipfile.ZipFile(tmp_path, "w") as archive:
                    for info in staged_infos:
                        if info.is_dir():
                            archive.writestr(copy.copy(info), b"")
                        else:
                            archive.writestr(copy.copy(info), repaired_contents[info.filename])
                with zipfile.ZipFile(tmp_path, "r") as archive:
                    if archive.testzip() is not None:
                        raise ValueError(f"Repaired ZIP CRC validation failed: {tmp_path}")
                tmp_path.replace(staged_path)
            finally:
                if tmp_path.exists():
                    tmp_path.unlink()

        repaired_members = [source_broken[gun_id]["display_path"] for gun_id in sorted(expected_broken)]

        result["archives"].append(
            {
                "path_relative_to_tacz": archive_name,
                "path": str(staged_path),
                "source_sha256": config["sha256"],
                "input_sha256": before_sha,
                "result_sha256": sha256(staged_path.read_bytes()),
                "modified_members": repaired_members,
                "modified_count": len(repaired_members),
                "changed_members_this_call": modified_members,
            }
        )
    return result


def build_staged_results(output: Path) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    tacz_dir = output / "tacz"
    tacz_dir.mkdir(parents=True, exist_ok=True)
    for archive_name in PACKS:
        staged = tacz_dir / archive_name
        if not staged.exists():
            with zipfile.ZipFile(BASE) as base:
                staged.write_bytes(base.read("overrides/tacz/" + archive_name))

    repaired = repair_installed_tree(tacz_dir)
    all_changes = [archive for archive in repaired["archives"]]
    manifest = {
        "scope": "r71 staged client TaCZ gunpack copies only",
        "source_directory": str(SOURCE_TACZ_DIR),
        "result_directory": str(tacz_dir),
        "method": "delete only missing optional lod member from the exact affected display JSONs; preserve archive members and all other display semantics",
        "archives": all_changes,
        "pack_audit": {
            archive_name: {
                "namespace": config["namespace"],
                "broken_lod_count": len(config["expected_broken"]),
                "broken_lod_guns": sorted(config["expected_broken"]),
            }
            for archive_name, config in PACKS.items()
        },
        "client_only": True,
        "server_changes_required": False,
        "server_reason": "Only client display JSON in client gunpack archives changes; gun indexes, gun data, recipes, and registries are untouched.",
        "third_party_asset_handling": "The two full patched ZIPs remain inside the ignored build-isolated staging output. No models/textures are added or recreated, and no source archive is changed.",
    }
    manifest_path = output / "repair-manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "README.md").write_text(
        "# r71 TaCZ LOD 暂存包修复\n\n"
        "`tacz/` 下两份 ZIP 是原客户端枪包的暂存副本，文件名与 `<游戏目录>/tacz/` 中的原包相同；部署时以这两个结果文件替换客户端包中的同名 ZIP。修复只删去 11 处引用了不存在文件的可选 `lod` 字段；不增添枪包，不含新增模型或贴图，不改原始 `client-overlay/tacz`、活动目录或服务端数据。\n\n"
        "Applied Armorer 修改 4 个 display；Create Armorer 修改 7 个 display（含未被任务引用的 special_melee_atomic）。两包所有剩余字段和成员均与源包一致；有效 LOD 不动。\n\n"
        "重生成暂存结果：`python scripts/create-delight-integration/repair_tacz_lod.py`。清单与 SHA-256 见 `repair-manifest.json`。\n",
        encoding="utf-8",
    )
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT, help="Isolated output directory for staged ZIP copies")
    args = parser.parse_args()
    manifest = build_staged_results(args.output)
    print(json.dumps(manifest["archives"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
