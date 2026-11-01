"""Download fixed planetary-exploration dependencies only; audit, never install."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import io
import json
import tomllib
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "build-isolated/campaign-20261019/dependencies"
ARTIFACTS = [
    ("ad_astra", "ad-astra", 635042, 4643668, "ad_astra-forge-1.19.2-1.12.7.jar"),
    ("botarium", "botarium", 704113, 4306551, "botarium-forge-1.19.2-1.8.2.jar"),
    ("resourcefullib", "resourceful-lib", 570073, 4477753, "resourcefullib-forge-1.19.2-1.1.24.jar"),
    ("resourcefulconfig", "resourceful-config", 714059, 4444198, "resourcefulconfig-forge-1.19.2-1.0.20.jar"),
]


def fetch(entry):
    mod_id, slug, project_id, file_id, filename = entry
    source = f"https://www.curseforge.com/minecraft/mc-mods/{slug}/files/{file_id}"
    url = f"https://www.cursemaven.com/curse/maven/{slug}-{project_id}/{file_id}/{slug}-{project_id}-{file_id}.jar"
    target = DEST / filename
    if not target.exists():
        urls = [url, f"https://edge.forgecdn.net/files/{file_id // 1000}/{file_id % 1000}/{filename}"]
        failure = None
        for candidate in urls:
            try:
                request = urllib.request.Request(candidate, headers={"User-Agent": "IC2-New-Era dependency audit"})
                with urllib.request.urlopen(request, timeout=45) as response:
                    payload = response.read(80 * 1024 * 1024 + 1)
                if not zipfile.is_zipfile(io.BytesIO(payload)):
                    raise ValueError("invalid archive")
                url = candidate
                break
            except Exception as error:
                failure = error
        else:
            raise failure
        if len(payload) > 80 * 1024 * 1024:
            raise ValueError(f"oversized dependency: {mod_id}")
        if not payload.startswith(b"PK"):
            raise ValueError(f"not a jar: {mod_id}")
        target.write_bytes(payload)
    digest = hashlib.sha256(target.read_bytes()).hexdigest().upper()
    with zipfile.ZipFile(target) as archive:
        metadata = tomllib.loads(archive.read("META-INF/mods.toml").decode("utf-8"))
        names = archive.namelist()
        mods = metadata.get("mods", [])
        if not any(mod.get("modId") == mod_id for mod in mods):
            raise ValueError(f"unexpected Forge mod: {mod_id}: {mods}")
        hard = [dict(dependency, owner=owner) for owner, dependencies in metadata.get("dependencies", {}).items()
                for dependency in dependencies if dependency.get("mandatory") is True]
        jars = [name for name in names if name.endswith(".jar")]
        resources = sum(name.endswith(".json") for name in names)
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
    return {"modId": mod_id, "fileName": filename, "projectFile": target.relative_to(ROOT).as_posix(),
            "sha256": digest, "size": target.stat().st_size, "sourceUrl": source, "downloadUrl": url,
            "curseforgeProjectId": project_id, "curseforgeFileId": file_id,
            "loader": metadata.get("modLoader"), "loaderVersion": metadata.get("loaderVersion"),
            "mods": mods, "hardDependencies": hard, "embeddedJars": jars,
            "jsonResources": resources, "manifest": manifest}


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=4) as pool:
        artifacts = list(pool.map(fetch, ARTIFACTS))
    report = {"minecraft": "1.19.2", "forge": "43.5.0", "installed": False,
              "artifacts": artifacts}
    output = DEST.parent / "dependency-audit.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for artifact in artifacts:
        print(json.dumps({key: artifact[key] for key in ("modId", "size", "sha256", "hardDependencies", "embeddedJars")}, ensure_ascii=False))
    print(f"AUDIT {output}")


if __name__ == "__main__":
    main()
