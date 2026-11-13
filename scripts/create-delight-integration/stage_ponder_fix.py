"""Build and stage the first-party CMR Ponder compatibility jar offline."""
import argparse
import hashlib
import importlib.util
import json
import os
import tempfile
import tomllib
from pathlib import Path
from zipfile import ZipFile

from advisory_lock import serialize_r71_release


ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "build-isolated/player-packaging/20261113-delight-joint"
INCOMING = WORK / "incoming-mods"
CORE = Path(__file__).with_name("cmr_ponder_fix.py")
CMR_ID = "cmr"
CMR_SHA256 = "ea46778fd93dee05e72250291dccab1a53bd5bb7818750c5e28800314345d134"


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def load_core():
    if not CORE.is_file():
        raise FileNotFoundError(f"First-party core module is not available yet: {CORE}")
    spec = importlib.util.spec_from_file_location("newera_cmr_ponder_fix", CORE)
    if spec is None or spec.loader is None:
        raise ImportError(f"Cannot load first-party Ponder fix module: {CORE}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if (module.MOD_ID, module.VERSION, module.NAME) != (
            "newera_ponder_fix", "1.0.0", "newera-ponder-fix-1.20.1-1.0.0.jar"):
        raise ValueError("cmr_ponder_fix.py identity differs from the agreed release identity")
    if not callable(module.build_jar) or not callable(module.audit_pair):
        raise TypeError("cmr_ponder_fix.py must expose build_jar(out) and audit_pair(fix_jar, cmr_jar)")
    return module


def selection_cmr(work: Path) -> Path:
    rows = json.loads((work / "selection.json").read_text(encoding="utf-8-sig"))
    matches = [row for row in rows if CMR_ID in {str(i).lower() for i in row.get("ids", [])}]
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one selected CMR mod ID, found {len(matches)}")
    row = matches[0]
    source = Path(row["path"])
    if row.get("sha256", "").lower() != CMR_SHA256 or not source.is_file() or digest(source) != CMR_SHA256:
        raise ValueError("Selected Create More Recipes source is not the exact audited CMR JAR")
    return source.resolve()


def verify_fix_identity(path: Path, core):
    if not path.is_file() or path.name != core.NAME:
        raise ValueError(f"First-party JAR path/name mismatch: {path}")
    with ZipFile(path) as jar:
        if "META-INF/mods.toml" not in jar.namelist():
            raise ValueError("First-party Ponder fix is missing Forge mods.toml")
        rows = tomllib.loads(jar.read("META-INF/mods.toml").decode("utf-8-sig")).get("mods", [])
    ids = [row.get("modId") for row in rows if isinstance(row, dict)]
    versions = [row.get("version") for row in rows if isinstance(row, dict) and row.get("modId") == core.MOD_ID]
    if ids != [core.MOD_ID] or versions != [core.VERSION]:
        raise ValueError(f"First-party JAR metadata mismatch: ids={ids}, versions={versions}")


def stage(work: Path = WORK, cmr_path: Path | None = None) -> tuple[Path, dict]:
    work = work.resolve()
    incoming = work / "incoming-mods"
    incoming.mkdir(parents=True, exist_ok=True)
    cmr = Path(cmr_path).resolve() if cmr_path is not None else selection_cmr(work)
    if not cmr.is_file() or digest(cmr) != CMR_SHA256:
        raise ValueError("Create More Recipes source is not the exact audited CMR JAR")
    core = load_core()
    target = incoming / core.NAME
    with tempfile.TemporaryDirectory(prefix=".newera-ponder-", dir=incoming) as scratch:
        temp = Path(scratch) / core.NAME
        built = Path(core.build_jar(temp)).resolve()
        if built != temp.resolve():
            raise ValueError(f"build_jar must write the requested staged output: {built}")
        verify_fix_identity(built, core)
        audit = core.audit_pair(built, cmr)
        if not isinstance(audit, dict):
            raise ValueError("audit_pair must return a JSON-compatible audit dictionary")
        output_sha = digest(built)
        if target.is_file() and digest(target) == output_sha:
            pass
        else:
            os.replace(temp, target)
        verify_fix_identity(target, core)
        final_audit = core.audit_pair(target, cmr)
        return target, {"modId": core.MOD_ID, "version": core.VERSION, "filename": core.NAME,
                        "sha256": digest(target), "cmrSha256": CMR_SHA256,
                        "audit": final_audit}


@serialize_r71_release
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, default=WORK)
    args = parser.parse_args()
    path, audit = stage(args.work)
    print(json.dumps({"status": "staged", "path": str(path), **audit}, ensure_ascii=False))


if __name__ == "__main__":
    main()
