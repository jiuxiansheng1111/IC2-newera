"""Compare the distributed and installed bytes, quest references, and source item data."""
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
from zipfile import ZipFile

from build_joint import HOTFIX_LOCK, NAME, OUT, PROFILE, W, digest, read, write
from merge_quests import read as read_snbt
from repair_extendedae_refmap import REFMAP_NAME, MIXIN_CLASS_NAME, UPSTREAM_SELECTOR_KEY_164, TARGET_SRG
from repair_tacz_lod import PACKS as LOD_PACKS


def exactly(value, expected, label):
    if value != expected:
        raise ValueError(f"{label}: {value!r} != {expected!r}")


def main():
    selections = read(W / "selection.json")
    hotfix_locks = read(HOTFIX_LOCK)["patches"]
    excluded_overlay_files = {rel for lock in hotfix_locks for rel in lock.get("excludeOverlayFiles", [])}
    installed = PROFILE.parent / NAME
    client = next(OUT.glob("*Windows.mrpack"))
    server = next(OUT.glob("*Server.zip"))
    byname = {r["name"]: r for r in selections}
    with ZipFile(client) as c, ZipFile(server) as s:
        if c.testzip() or s.testzip(): raise ValueError("Damaged compressed payload")
        cn, sn = set(c.namelist()), set(s.namelist())
        exactly(len(cn), len(c.namelist()), "client duplicate zip members")
        exactly(len(sn), len(s.namelist()), "server duplicate zip members")
        roots = {n.split("/")[0] for n in sn}
        exactly(len(roots), 1, "server root count")
        prefix = next(iter(roots)) + "/"
        index = json.loads(c.read("modrinth.index.json"))
        exactly(index["dependencies"], {"minecraft": "1.20.1", "forge": "47.4.28"}, "launcher versions")
        downloads = {i["path"]: i for i in index["files"]}
        files = {n.removeprefix("overrides/") for n in cn if n.startswith("overrides/mods/")}
        files.update(n for n in downloads if n.startswith("mods/"))
        exactly(files, {"mods/" + n for n in byname}, "client mods")
        exactly({p.name for p in (installed / "mods").glob("*.jar")}, set(byname), "installed mods")
        exactly({n.removeprefix(prefix + "mods/") for n in sn if n.startswith(prefix + "mods/")},
                {r["name"] for r in selections if r["server"]}, "server mods")
        for rel in excluded_overlay_files:
            if "overrides/" + rel in cn or prefix + rel in sn or (installed / rel).exists():
                raise ValueError("Locked incompatible override remains in release or installed instance: " + rel)
        gunpack_rel = "tacz/tacz_default_gun.zip"
        gunpack_source = W / "compat-work/tacz-overlay-1200/overrides" / gunpack_rel
        gunpack_bytes = gunpack_source.read_bytes()
        exactly(hashlib.sha256(c.read("overrides/" + gunpack_rel)).hexdigest(), digest(gunpack_source),
                "client TaCZ default asset bundle")
        exactly(hashlib.sha256(s.read(prefix + gunpack_rel)).hexdigest(), digest(gunpack_source),
                "server TaCZ default asset bundle")
        with ZipFile(io.BytesIO(gunpack_bytes)) as gunpack:
            if gunpack.testzip(): raise ValueError("TaCZ default asset bundle failed CRC verification")
            gun_ids = {PurePosixPath(n).stem for n in gunpack.namelist()
                       if n.startswith("data/tacz/index/guns/") and n.endswith(".json")}
            exactly(len(gun_ids), 70, "TaCZ default asset bundle index count")
            exactly({"ai_awp", "mk18"} <= gun_ids, True, "required TaCZ fieldwork entries")
            if any(n.startswith("data/tacz/recipes/") for n in gunpack.namelist()):
                raise ValueError("TaCZ default asset bundle unexpectedly contains recipe definitions")
        for row in selections:
            rel = "mods/" + row["name"]
            exactly(digest(installed / rel), row["sha256"], "installed " + rel)
            if rel in downloads:
                exactly(digest(installed / rel, "sha512"), downloads[rel]["hashes"]["sha512"], rel)
            else:
                exactly(hashlib.sha256(c.read("overrides/" + rel)).hexdigest(), row["sha256"], rel)
            if row["server"]:
                exactly(hashlib.sha256(s.read(prefix + rel)).hexdigest(), row["sha256"], "server " + rel)
        for visual in read(W / "visuals.json"):
            rel = visual["path"]
            for algorithm, expected in downloads[rel]["hashes"].items():
                exactly(digest(installed / rel, algorithm), expected, rel)
        for n in sn:
            rel = n.removeprefix(prefix)
            if rel.startswith(("world/", "saves/", "resourcepacks/", "shaderpacks/", "PCL/", "kubejs/client_scripts/")):
                raise ValueError("Unexpected dedicated-server file " + rel)
        for file in (W / "client-overlay").rglob("*"):
            if file.is_file():
                rel = file.relative_to(W / "client-overlay").as_posix()
                if rel.startswith(("config/ftbquests/", "config/ic2newera/quest_knowledge.json", "kubejs/data/",
                                   "kubejs/server_scripts/", "kubejs/startup_scripts/", "tacz/")) or rel.endswith(
                                       ("greenhouse_builder.sm", "sell_bin.sm", "satellite_navigation_data_card.ui")):
                    expected = file.read_bytes()
                    if c.read("overrides/" + rel) != expected or s.read(prefix + rel) != expected or (installed / rel).read_bytes() != expected:
                        raise ValueError("Static game data differ: " + rel)
        for rel in ("server.jar", "start-server.sh", "Start-Server.bat", "eula.txt", "server.properties",
                    "ic2newera-fixed-voice.jar", "LICENSE-Create-Delight-Remake.txt", "SERVER-BUILD-MANIFEST.json"):
            if prefix + rel not in sn: raise ValueError(rel)
    quests = installed / "config/ftbquests/quests"
    files = [(f, read_snbt(f)) for f in (quests / "chapters").glob("*.snbt")]
    all_quests = {q["id"]: q for _, ch in files for q in ch["quests"]}
    index = read(installed / "config/ic2newera/quest_knowledge.json")
    exactly({r["id"] for r in index["quests"]}, set(all_quests), "AI task IDs")
    done, visiting = set(), set()
    def walk(ident):
        if ident in done: return
        if ident in visiting: raise ValueError("Cyclic dependency: " + ident)
        visiting.add(ident)
        for dep in all_quests[ident].get("dependencies", []): walk(dep)
        visiting.remove(ident)
        done.add(ident)
    for ident in all_quests: walk(ident)
    new = [q for f, ch in files if f.stem.startswith(("joint_", "delight_")) for q in ch["quests"]]
    exactly(len(new), 120, "new task count")
    wanted = {entry["item"] for q in new for entry in q["tasks"] + q["rewards"] if isinstance(entry.get("item"), str)}
    pending = set(wanted)
    for f in [installed / (NAME + ".jar"), *sorted((installed / "mods").glob("*.jar"))]:
        with ZipFile(f) as jar:
            keys = set(jar.namelist())
            for item in pending.copy():
                ns, key = item.split(":")
                if f"assets/{ns}/models/item/{key}.json" in keys: pending.remove(item)
    # These two are registered by MBD2 from the matched LDLib project.
    dynamic = {"createdelight:" + n.stem for n in (installed / "ldlib/assets/mbd2/machine").glob("*.sm")}
    exactly(pending - dynamic, set(), "unsubstantiated new task/reward items")
    deps = read(W / "deps.json")["summary"]
    exactly(deps["issues"]["error"], 0, "dependency errors")
    hotfix_rows = []
    pair = {p["modId"]: p for p in hotfix_locks if p.get("useOfficialDownloadInMrpack")}
    for mod_id in ("jei", "extendedae_plus"):
        pinned = pair[mod_id]
        selected = byname[pinned["filename"]]
        rel = "mods/" + pinned["filename"]
        exactly(selected["version"], pinned["version"], mod_id + " version")
        exactly(downloads[rel]["downloads"], [pinned["source"]["downloadUrl"]], mod_id + " official download")
        exactly(downloads[rel]["hashes"], {h: pinned[h] for h in ("sha1", "sha512")}, mod_id + " PCL hashes")
        exactly("overrides/" + rel in cn, False, mod_id + " must not also be embedded")
        for old_name in {pinned["replaces"], pinned.get("previouslyInstalled", pinned["replaces"])}:
            exactly("mods/" + old_name in files, False, "superseded " + old_name)
    ae = byname[pair["extendedae_plus"]["filename"]]
    with ZipFile(installed / "mods" / ae["name"]) as lib:
        mapping = json.loads(lib.read(REFMAP_NAME))
        for table in [mapping["mappings"], mapping["data"]["named:intermediary"]]:
            exactly(table[MIXIN_CLASS_NAME][UPSTREAM_SELECTOR_KEY_164], TARGET_SRG, "AE2 CPU status mapping")
    mod = next(r for r in selections if r["primary"] == "ic2newera")
    cfg = "ic2newera.bulk-compat.mixins.json"
    with ZipFile(installed / "mods" / mod["name"]) as lib:
        attrs = lib.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
        configs = next(line.split(": ", 1)[1] for line in attrs.splitlines() if line.startswith("MixinConfigs: ")).split(",")
        exactly(cfg in configs, True, "active MEGA/OEI compatibility config")
        mc = json.loads(lib.read(cfg))
        for name in [mc["plugin"], *(mc["package"] + "." + v for v in mc["mixins"])]:
            exactly(name.replace(".", "/") + ".class" in lib.namelist(), True, "included " + name)
    lod_count = 0
    for name, pack in LOD_PACKS.items():
        with ZipFile(installed / "tacz" / name) as gz:
            for model in pack["expected_broken"]:
                doc = json.loads(gz.read(f"assets/{pack['namespace']}/display/guns/{model}_display.json"))
                exactly("lod" in doc, False, "broken distant resource: " + model)
                lod_count += 1
    for lock in hotfix_locks:
        selected = byname.get(lock["filename"])
        if not selected:
            raise ValueError("Locked compatibility patch is absent from selection.json: " + lock["filename"])
        exactly(digest(Path(selected["path"])), selected["sha256"], "selection SHA-256 " + lock["filename"])
        exactly(digest(Path(selected["path"]), "sha512"), lock["sha512"], "hotfix SHA-512 " + lock["filename"])
        exactly(Path(selected["path"]).stat().st_size, lock["fileSize"], "hotfix file size " + lock["filename"])
        exactly(selected["ids"], [lock["modId"]], "hotfix mod ID " + lock["filename"])
        exactly(bool(selected["server"]), bool(lock["server"]), "hotfix server classification " + lock["filename"])
        exactly(True, bool(lock["client"]), "hotfix client classification " + lock["filename"])
        hotfix_rows.append({"filename": lock["filename"], "modrinthVersionId": lock.get("versionId"),
                            "source": lock.get("source", {"service": "Modrinth"}),
                            "sha256": selected["sha256"], "sha512": lock.get("sha512"),
                            "sha1": lock.get("sha1"), "fileSize": lock["fileSize"],
                            "replaces": lock["replaces"], "client": lock["client"], "server": lock["server"],
                            "excludeOverlayFiles": lock.get("excludeOverlayFiles", [])})
    artifacts = {f.name: {"bytes": f.stat().st_size, "sha256": digest(f)} for f in (client, server)}
    receipt_path = W / "live-r71" / "dedicated-start.json"
    receipt = read(receipt_path) if receipt_path.is_file() else {}
    online = (receipt.get("serverZipSha256") == artifacts[server.name]["sha256"]
              and receipt.get("questKnowledgeSha256") == digest(installed / "config/ic2newera/quest_knowledge.json")
              and receipt.get("ftbChapters") == len(files) and receipt.get("ftbQuests") == len(done)
              and receipt.get("answeredConsoleList") is True and receipt.get("answeredPublicStatus") is True)
    report = {"release": "r71", "minecraft": "1.20.1", "forge": "47.4.28", "clientJars": len(selections),
              "serverJars": sum(r["server"] for r in selections), "allSharedJarHashesMatch": True,
              "chapters": len(files), "handbookQuests": len(done), "newQuests": len(new),
              "verifiedDistinctNewTaskAndRewardItems": len(wanted), "dynamicItems": sorted(pending),
              "aiQuestCount": index["count"], "aiSha256": digest(installed / "config/ic2newera/quest_knowledge.json"),
              "forgeDependencyErrors": 0, "forgeMetadataWarnings": deps["issues"]["warning"],
              "minecraftOrDedicatedServerStarted": online, "clientJoinVerified": False,
              "runtimeFixes": {"megaCellsOeiScopedLookupPackaged": True, "extendedAeOfficialVersion": ae["version"],
                               "extendedAeOfficialJarSha256": ae["sha256"], "fallbackModelCount": lod_count,
                               "jeiVersion": next(r["version"] for r in selections if r["primary"] == "jei")},
              "compatibilityHotfixes": hotfix_rows, "artifacts": artifacts}
    write(W / "verified-release.json", report)
    write(OUT / "r71-release-manifest.json", report)
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
